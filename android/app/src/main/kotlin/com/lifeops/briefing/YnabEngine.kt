package com.lifeops.briefing

import kotlin.math.abs

/**
 * Kotlin port of `lifeops/engines/ynab_engine.py` -- the second step of the
 * on-device migration (see docs/lifeops_capability_todo.md's "On-Device
 * Migration" section: "fully pure and fully self-contained (only needs
 * `defaultdict(Counter)`/`most_common` idiom translation)"). Runs
 * independently of the `Routine.kt` port -- this engine doesn't call it.
 *
 * Pure: given categories, payee history, unapproved transactions, and the
 * current month's category snapshot, decide what to categorize / approve /
 * hold / cover. The novel-payee judgment (the only subjective bit) is handed
 * back as `novel` for the LLM to resolve -- unchanged from Python, this port
 * does NOT call any LLM itself.
 *
 * NOT wired into any widget/worker/persistence path yet -- standalone,
 * unused-so-far port, same status as `Routine.kt`.
 *
 * Python-idiom translation notes:
 * - `collections.defaultdict(Counter)` (payee -> {category_id -> count})
 *   becomes `Map<String, Map<String, Int>>`, built with a plain
 *   `mutableMapOf<String, MutableMap<String, Int>>()` and `getOrPut`. Kotlin's
 *   `mutableMapOf()` is backed by `LinkedHashMap`, which preserves insertion
 *   order the same way Python's `dict`/`Counter` has since 3.7 -- load-bearing
 *   for the next point.
 * - `Counter.most_common(1)[0]` (most-frequent category for a payee, ties
 *   broken by first-inserted) becomes `counts.entries.maxByOrNull { it.value }`.
 *   Kotlin's `maxByOrNull` returns the *first* maximal element by iteration
 *   order on a tie, which for a `LinkedHashMap` is insertion order -- the same
 *   tie-break `Counter.most_common` uses. No test in the Python suite actually
 *   exercises a tie (see `test_split_small_sample_not_auto`, which is a
 *   1-vs-1 split that the confidence check rejects before a tie would ever
 *   matter), so this is a documented equivalence, not one locked down by a
 *   ported test.
 * - Python's `amt / 1000` (milliunits -> dollars, float division) becomes
 *   `amt / 1000.0` -- `amount` stays an `Int` (milliunits) throughout, matching
 *   how `YnabRefresh.kt` already represents YNAB amounts on this codebase.
 * - `_cover` is ported as `internal fun cover` (not `private`) purely so
 *   `YnabEngineTest.kt` can call it directly, mirroring how the Python test
 *   suite imports and calls the underscore-prefixed `ynab_engine._cover`
 *   directly rather than only exercising it through `plan()`.
 */

/** Never auto-approve a transaction at/above this amount (milliunits, i.e. $150). */
const val YNAB_REVIEW_THRESHOLD_MILLIUNITS = 150_000

/** Matches the fields `ynab_engine.py` actually reads off a YNAB category dict. */
data class YnabCategory(val id: String, val name: String)

/**
 * One approved historical transaction, as read from payee history. Matches
 * the fields `_payee_map` actually reads off a YNAB transaction dict.
 */
data class YnabHistoryTxn(
    val approved: Boolean,
    val categoryId: String?,
    val payeeName: String?,
)

/**
 * One unapproved transaction awaiting a decision. Matches the fields `plan()`
 * actually reads off a YNAB transaction dict. `amount` is milliunits, same
 * sign convention as YNAB's API (negative = outflow).
 */
data class YnabUnapprovedTxn(
    val id: String,
    val amount: Int = 0,
    val payeeName: String? = null,
    val categoryId: String? = null,
    val transferAccountId: String? = null,
)

/** One category's balance/budgeted snapshot for the current month. */
data class YnabMonthCategory(
    val id: String,
    val name: String,
    val balance: Int = 0,
    val budgeted: Int = 0,
)

/** The current month's category snapshot, as passed to `plan()`/`_cover`. */
data class YnabMonth(val categories: List<YnabMonthCategory> = emptyList())

/** categorize: assign `categoryId` to transaction `id`. */
data class YnabCategorizeAction(val id: String, val categoryId: String)

/** holds: transaction `id` needs human review before approval, with `why`. */
data class YnabHold(val id: String, val why: String)

/** novel: a payee with no confident historical category -- hand to the LLM. */
data class YnabNovelTxn(val id: String, val payee: String?, val amount: Double)

/** cover: set category `categoryId`'s budgeted amount to `budgeted` (milliunits). */
data class YnabCoverAction(val categoryId: String, val budgeted: Int)

/** Full return shape of `plan()`, mirroring the Python function's result dict. */
data class YnabPlan(
    val categorize: List<YnabCategorizeAction>,
    val approve: List<String>,
    val novel: List<YnabNovelTxn>,
    val holds: List<YnabHold>,
    val cover: List<YnabCoverAction>,
)

/**
 * Build payee -> {category_id -> count} from approved history entries that
 * have both a category and a payee. Ports `_payee_map`. See the file-level
 * kdoc for the `defaultdict(Counter)` translation note.
 */
private fun payeeMap(history: List<YnabHistoryTxn>): Map<String, Map<String, Int>> {
    val m = mutableMapOf<String, MutableMap<String, Int>>()
    for (t in history) {
        val cid = t.categoryId
        val payee = t.payeeName
        if (t.approved && cid != null && payee != null) {
            val counts = m.getOrPut(payee.lowercase()) { mutableMapOf() }
            counts[cid] = (counts[cid] ?: 0) + 1
        }
    }
    return m
}

/**
 * Decide categorize/approve/novel/hold/cover actions for a batch of
 * unapproved transactions plus the current month's overspend. Ports `plan`.
 */
fun plan(
    categories: List<YnabCategory>,
    history: List<YnabHistoryTxn>,
    unapproved: List<YnabUnapprovedTxn>,
    month: YnabMonth,
    coverOrder: List<String> = emptyList(),
    noAssign: List<String> = emptyList(),
): YnabPlan {
    val pmap = payeeMap(history)
    val noAssignSet = noAssign.toSet()
    val noAssignIds = categories.filter { it.name in noAssignSet }.map { it.id }.toSet()

    val categorize = mutableListOf<YnabCategorizeAction>()
    val approve = mutableListOf<String>()
    val novel = mutableListOf<YnabNovelTxn>()
    val holds = mutableListOf<YnabHold>()

    for (t in unapproved) {
        val amt = t.amount
        if (amt > 0 || t.transferAccountId != null) {
            continue // income / transfer / refund -- leave it
        }
        var cid = t.categoryId
        if (cid != null && cid in noAssignIds) {
            // Pre-categorized straight into a pure fund (import rule / fat-finger).
            // A card swipe is never "Savings" -- hold it for review, never auto-approve.
            holds.add(YnabHold(t.id, "assigned to protected fund"))
            continue
        }
        if (cid == null) {
            val counts = pmap[(t.payeeName ?: "").lowercase()]
            if (counts != null && counts.isNotEmpty()) {
                val (best, n) = counts.entries.maxByOrNull { it.value }!!.toPair()
                val total = counts.values.sum()
                // confident: 3+ sightings at >=70%, or a UNANIMOUS small sample of 2+.
                // (a single historical txn is not a rule -- that goes to the LLM)
                val ok = (n >= 3 && n.toDouble() / total >= 0.7) || (n == total && n >= 2)
                if (ok && best !in noAssignIds) { // never auto-assign a pure fund
                    cid = best
                }
            }
            if (cid == null) {
                novel.add(YnabNovelTxn(t.id, t.payeeName, amt / 1000.0))
                continue
            }
            categorize.add(YnabCategorizeAction(t.id, cid))
        }
        if (abs(amt) >= YNAB_REVIEW_THRESHOLD_MILLIUNITS) {
            holds.add(YnabHold(t.id, "large/unusual"))
        } else {
            approve.add(t.id)
        }
    }
    return YnabPlan(categorize, approve, novel, holds, cover(month, coverOrder))
}

/**
 * Cover overspent categories by draining discretionary WANTS in priority
 * order. Never touches anything not in `coverOrder` (so savings/funds are
 * safe). Sources are never driven below zero; uncovered overspend is left
 * red. Ports `_cover`. `internal` rather than `private` so the test suite can
 * call it directly, matching how the Python test suite calls
 * `ynab_engine._cover` directly -- see the file-level kdoc.
 */
internal fun cover(month: YnabMonth, coverOrder: List<String>): List<YnabCoverAction> {
    if (coverOrder.isEmpty()) return emptyList()
    val cats = month.categories
    val byName = cats.associateBy { it.name.lowercase() }
    val sources = coverOrder.mapNotNull { byName[it.lowercase()] }
    val avail = mutableMapOf<String, Int>()
    for (s in sources) avail[s.id] = s.balance
    val newBudget = mutableMapOf<String, Int>() // category_id -> updated budgeted
    fun cur(c: YnabMonthCategory): Int = newBudget[c.id] ?: c.budgeted

    for (c in cats) {
        val bal = c.balance
        if (bal >= 0) continue
        var deficit = -bal
        for (s in sources) {
            if (deficit <= 0) break
            if (s.id == c.id) continue
            val give = minOf(deficit, avail[s.id] ?: 0)
            if (give <= 0) continue
            newBudget[c.id] = cur(c) + give
            newBudget[s.id] = cur(s) - give
            avail[s.id] = (avail[s.id] ?: 0) - give
            deficit -= give
        }
    }
    return newBudget.map { (cid, b) -> YnabCoverAction(cid, b) }
}
