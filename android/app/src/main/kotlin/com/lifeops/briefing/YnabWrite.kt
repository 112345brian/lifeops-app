package com.lifeops.briefing

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime
import kotlin.math.abs

/**
 * The on-device read-decide-write pipeline for `YnabEngine.kt` -- the piece
 * `LifeOpsComputeWorker.kt`'s own kdoc flags as "Deliberately NOT wired":
 * *"YnabEngine's categorize/approve/hold/cover WRITE actions. Those need a
 * live 'unapproved transactions' YNAB read plus YNAB PATCH calls
 * `YnabRefresh.kt` doesn't have today (it only reads category balances)."*
 *
 * Ports `lifeops/runner.py`'s `run_ynab` end to end: fetch categories +
 * 120-day payee history + unapproved transactions + the current month, run
 * them through `YnabEngine.plan()`, resolve `novel` payees via
 * [AnthropicClient.categorizeUnknownPayee] exactly like `run_ynab` does, then
 * write the result back to YNAB with two PATCH calls (bulk transaction
 * update, and per-category `budgeted` for `cover`).
 *
 * ## Gating: ungated, matching the Python source's own unattended behavior
 *
 * Read `lifeops/runner.py` directly (`DOMAINS`/`TIERS`, `_run()`/`main()`):
 * `run_ynab` is registered in the `"daily"` tier and fires from an unattended
 * scheduled run with no human review/approval step anywhere in the path --
 * the categorize/approve/hold/cover writes AND the novel-payee LLM call both
 * already happen today with zero human in the loop. Per the locked-in
 * product decision (`BiometricGate.kt`'s own kdoc: gating applies to
 * user-INITIATED writes/LLM spends, never background/unattended automation),
 * this pipeline is therefore implemented UNGATED, run from
 * [LifeOpsComputeWorker]'s periodic tick exactly like [runWeeklyDigestIfDue]
 * already is (see that file's own kdoc making the identical judgment call for
 * the other on-device LLM call). [BiometricGate.authenticate] is NOT called
 * anywhere in this file -- consistent with every other background-automation
 * write in this codebase, and NOT a place to invent a UI approval flow that
 * doesn't exist server-side either.
 *
 * ## Retry-safety: no asymmetric retry guard needed for transactions --
 * but `cover` needed a real fix, not just a claim
 *
 * Unlike `FlowSavvyClient.kt`'s `create_task`/`create_event` (a POST that
 * risks creating a DUPLICATE resource if blindly retried after an ambiguous
 * response), YNAB's transaction PATCH
 * (`PATCH /budgets/{b}/transactions`) is idempotent for what this pipeline
 * sends: it sets `category_id`/`approved` to an explicit value, it never
 * appends or creates anything. Applying the exact same
 * `{id, category_id, approved}` payload twice in a row (e.g. after a
 * mid-write connection drop) converges to the identical end state both
 * times -- there is no "ran twice -> duplicate transaction" hazard the way
 * there is for FlowSavvy's non-idempotent creates.
 *
 * Given that, this port intentionally does NOT add `FlowSavvyClient.kt`-style
 * retry/backoff machinery around the write calls -- `lifeops/ynab.py`'s own
 * `_patch`/`_post` have none either (plain `requests.patch(...).raise_for_status()`,
 * no retry wrapper), so adding one here would be inventing behavior beyond
 * the Python source, not porting it. Safety instead comes from WHEN the gate
 * marks itself done: [runYnabWriteTickIfDue] only persists
 * [KEY_LAST_YNAB_WRITE_DATE] on a fully successful write, mirroring
 * `WeeklyDigest.kt`'s "dedup key written only on success" rule exactly -- a
 * transient failure (network blip, one bad HTTP status) simply gets retried
 * on the very next 15-minute tick (same day), rather than being silently
 * skipped until tomorrow. Because a retried run re-fetches `unapproved`
 * transactions fresh from YNAB every time, any transaction the first attempt
 * DID successfully write is naturally excluded from the next attempt's input
 * (it's no longer unapproved / already has a category), so a retry cannot
 * double-apply anything even at the whole-tick level, for TRANSACTIONS.
 *
 * `cover`'s per-category budget PATCH is NOT covered by that same reasoning,
 * despite an earlier version of this file claiming it was -- a per-write
 * "absolute assignment" guarantee does not make a MULTI-category BATCH safe
 * to partially apply, since `cover()`'s decision is recomputed fresh from
 * live category state each tick, not diffed against what the previous
 * attempt actually wrote. See [applyCoverMoves]'s kdoc for the exact
 * money-duplication scenario a review caught and the debit-before-credit
 * ordering fix that closes it.
 */

// ---------------------------------------------------------------------
// JSON parsing -- pure, directly unit-testable without any network I/O.
// Mirrors the field names lifeops/ynab.py's callers actually read.
// ---------------------------------------------------------------------

/** Ports the `category_groups[].categories[]` walk `runner.py`'s `run_ynab`
 * does (`[c for g in groups for c in g["categories"]] `), filtering out
 * hidden/deleted categories exactly like that comprehension's own guard. */
internal fun parseYnabCategories(data: JSONObject): List<YnabCategory> {
    val groups = data.optJSONArray("category_groups") ?: JSONArray()
    val out = mutableListOf<YnabCategory>()
    for (i in 0 until groups.length()) {
        val group = groups.getJSONObject(i)
        val cats = group.optJSONArray("categories") ?: continue
        for (j in 0 until cats.length()) {
            val c = cats.getJSONObject(j)
            if (c.optBoolean("hidden", false) || c.optBoolean("deleted", false)) continue
            out.add(YnabCategory(id = c.getString("id"), name = c.optString("name")))
        }
    }
    return out
}

private fun JSONObject.optStringOrNullYnab(key: String): String? =
    if (isNull(key) || !has(key)) null else optString(key).takeIf { it.isNotEmpty() }

/** Ports `_payee_map`'s per-transaction field reads (`approved`, `category_id`,
 * `payee_name`) off a `/transactions` response's `transactions[]`. */
internal fun parseYnabHistoryTxns(data: JSONObject): List<YnabHistoryTxn> {
    val arr = data.optJSONArray("transactions") ?: JSONArray()
    return (0 until arr.length()).map { i ->
        val t = arr.getJSONObject(i)
        YnabHistoryTxn(
            approved = t.optBoolean("approved", false),
            categoryId = t.optStringOrNullYnab("category_id"),
            payeeName = t.optStringOrNullYnab("payee_name"),
        )
    }
}

/** Ports `plan()`'s per-unapproved-transaction field reads off the same
 * `/transactions?type=unapproved` response shape. */
internal fun parseYnabUnapprovedTxns(data: JSONObject): List<YnabUnapprovedTxn> {
    val arr = data.optJSONArray("transactions") ?: JSONArray()
    return (0 until arr.length()).map { i ->
        val t = arr.getJSONObject(i)
        YnabUnapprovedTxn(
            id = t.getString("id"),
            amount = t.optInt("amount", 0),
            payeeName = t.optStringOrNullYnab("payee_name"),
            categoryId = t.optStringOrNullYnab("category_id"),
            transferAccountId = t.optStringOrNullYnab("transfer_account_id"),
        )
    }
}

/** Ports `_cover`'s per-category `balance`/`budgeted` reads off
 * `/months/current`'s `month.categories[]`, same hidden/deleted filter as
 * [parseYnabCategories]. */
internal fun parseYnabMonth(data: JSONObject): YnabMonth {
    val month = data.optJSONObject("month") ?: return YnabMonth(emptyList())
    val cats = month.optJSONArray("categories") ?: JSONArray()
    val out = mutableListOf<YnabMonthCategory>()
    for (i in 0 until cats.length()) {
        val c = cats.getJSONObject(i)
        if (c.optBoolean("hidden", false) || c.optBoolean("deleted", false)) continue
        out.add(
            YnabMonthCategory(
                id = c.getString("id"),
                name = c.optString("name"),
                balance = c.optInt("balance", 0),
                budgeted = c.optInt("budgeted", 0),
            ),
        )
    }
    return YnabMonth(out)
}

// ---------------------------------------------------------------------
// Fetch -- a small seam (mirrors FlowSavvyFetch in LifeOpsComputeWorker.kt)
// so the decide logic below is testable with fixture JSON, no real network.
// ---------------------------------------------------------------------

internal data class YnabFetchResult(
    val categories: List<YnabCategory>,
    val history: List<YnabHistoryTxn>,
    val unapproved: List<YnabUnapprovedTxn>,
    val month: YnabMonth,
)

internal fun interface YnabFetch {
    fun fetch(now: LocalDateTime): YnabFetchResult
}

/** One PATCH-ready transaction update -- `{id, category_id?, approved?}`,
 * exactly the shape `ynab.py`'s `update_transactions([{"id":..,"category_id":..,"approved":..}])`
 * sends. */
internal data class YnabTxnUpdate(val id: String, val categoryId: String? = null, val approved: Boolean? = null)

internal fun interface YnabWriteClient {
    /** Ports `yn.update_transactions(updates)` -> `PATCH /budgets/{b}/transactions`. No-ops on an empty list. */
    fun updateTransactions(updates: List<YnabTxnUpdate>)
}

internal fun interface YnabBudgetWriteClient {
    /** Ports `yn.set_budgeted(category_id, budgeted)` -> `PATCH /budgets/{b}/months/current/categories/{category_id}`. */
    fun setBudgeted(categoryId: String, budgeted: Int)
}

private const val YNAB_API_BASE = "https://api.ynab.com/v1"

/** Real network-backed [YnabFetch]. Same `HttpURLConnection` + `data`-envelope
 * conventions `YnabRefresh.kt` already established for this codebase, not a
 * new HTTP style. */
internal class RealYnabFetch(private val token: String, private val budget: String) : YnabFetch {
    private val encodedBudget = URLEncoder.encode(budget, StandardCharsets.UTF_8.name())
    private val headers = mapOf("Authorization" to "Bearer $token")

    private fun getData(path: String): JSONObject {
        val body = httpRequest(
            url = "$YNAB_API_BASE/budgets/$encodedBudget$path",
            method = "GET",
            headers = headers,
            connectTimeoutMs = 10_000,
            readTimeoutMs = 15_000,
        )
        return JSONObject(body).getJSONObject("data")
    }

    override fun fetch(now: LocalDateTime): YnabFetchResult {
        // 120-day rolling history window, matching runner.py's run_ynab
        // exactly (`now.date() - timedelta(days=120)`) -- the YNAB client
        // never uses server_knowledge delta-sync on either side (see
        // docs/lifeops_capability_todo.md's own note on this), so this is a
        // faithful port of the existing behavior, not a new gap.
        val since = now.toLocalDate().minusDays(120).toString()
        val categories = parseYnabCategories(getData("/categories"))
        val history = parseYnabHistoryTxns(getData("/transactions?since_date=$since"))
        val unapproved = parseYnabUnapprovedTxns(getData("/transactions?type=unapproved"))
        val month = parseYnabMonth(getData("/months/current"))
        return YnabFetchResult(categories, history, unapproved, month)
    }
}

/** Real network-backed [YnabWriteClient]/[YnabBudgetWriteClient]. See this
 * file's top-level kdoc "Retry-safety" section for why no retry wrapper
 * is added here, matching `ynab.py`'s own plain `_patch`. */
internal class RealYnabWrite(private val token: String, private val budget: String) :
    YnabWriteClient, YnabBudgetWriteClient {
    private val encodedBudget = URLEncoder.encode(budget, StandardCharsets.UTF_8.name())
    private val headers = mapOf(
        "Authorization" to "Bearer $token",
        "Content-Type" to "application/json",
    )

    override fun updateTransactions(updates: List<YnabTxnUpdate>) {
        if (updates.isEmpty()) return
        val txnArray = JSONArray()
        for (u in updates) {
            val obj = JSONObject().apply { put("id", u.id) }
            if (u.categoryId != null) obj.put("category_id", u.categoryId)
            if (u.approved != null) obj.put("approved", u.approved)
            txnArray.put(obj)
        }
        val payload = JSONObject().apply { put("transactions", txnArray) }.toString()
        httpRequest(
            url = "$YNAB_API_BASE/budgets/$encodedBudget/transactions",
            method = "PATCH",
            body = payload,
            headers = headers,
            connectTimeoutMs = 15_000,
            readTimeoutMs = 20_000,
        )
    }

    override fun setBudgeted(categoryId: String, budgeted: Int) {
        val payload = JSONObject().apply {
            put("category", JSONObject().apply { put("budgeted", budgeted) })
        }.toString()
        httpRequest(
            url = "$YNAB_API_BASE/budgets/$encodedBudget/months/current/categories/$categoryId",
            method = "PATCH",
            body = payload,
            headers = headers,
            connectTimeoutMs = 15_000,
            readTimeoutMs = 20_000,
        )
    }
}

// ---------------------------------------------------------------------
// Decide -- pure merge logic (`run_ynab`'s body after `plan()`), with the
// LLM call injected as a plain function so it's unit-testable with a fake,
// no real network / no Robolectric-only Anthropic wiring required here.
// ---------------------------------------------------------------------

/** Categories `no_assign`'d into a pure fund -- never auto-assigned, held for
 * review instead. Mirrors `lifeops/config.py`'s own
 * `YNAB_NO_ASSIGN` default exactly (`"Savings,Emergency Fund,Stocks"`,
 * comma-split). No Settings UI field exists for this yet, same "interim
 * default, no UI" posture `WidgetConfigStore`'s FlowSavvy fields already
 * document -- building that UI is out of scope for this pipeline. */
internal val YNAB_NO_ASSIGN_DEFAULT = listOf("Savings", "Emergency Fund", "Stocks")

/** Result of resolving every `novel` payee via the LLM: additional
 * categorize/approve entries to fold into `YnabEngine.plan()`'s own output,
 * exactly like `run_ynab` appends to `out["categorize"]`/`out["approve"]`
 * before building the final PATCH payload. */
internal data class NovelResolution(
    val categorize: List<YnabCategorizeAction>,
    val approve: List<String>,
)

/**
 * Ports `run_ynab`'s novel-payee loop exactly:
 * ```
 * for nv in out["novel"]:
 *     cat = llm.categorize_unknown(nv["payee"], nv["amount"], names)
 *     if cat in nid:
 *         out["categorize"].append({"id": nv["id"], "category_id": nid[cat]})
 *         if abs(nv["amount"]) * 1000 < ynab_engine.REVIEW:
 *             out["approve"].append(nv["id"])
 * ```
 * [categorize] is injected (a real caller passes
 * [AnthropicClient.categorizeUnknownPayee]) so this merge logic is testable
 * with a fake, no real Anthropic call. Returns empty if [novel] is empty --
 * callers should also skip calling this entirely when no API key is
 * configured, mirroring `run_ynab`'s `if config.ANTHROPIC_API_KEY and
 * out["novel"]` guard (checked in [runYnabWriteTickIfDue], not here, so this
 * function itself stays a pure merge over an already-decided [categorize]
 * function).
 */
internal fun resolveNovelPayees(
    novel: List<YnabNovelTxn>,
    allCategories: List<YnabCategory>,
    noAssign: List<String>,
    categorize: (payee: String, amount: Double, categoryNames: List<String>) -> String?,
): NovelResolution {
    if (novel.isEmpty()) return NovelResolution(emptyList(), emptyList())
    val skip = noAssign.toSet()
    val allowedNames = allCategories.filter { it.name !in skip }.map { it.name }
    val nameToId = allCategories.associate { it.name to it.id }
    val extraCategorize = mutableListOf<YnabCategorizeAction>()
    val extraApprove = mutableListOf<String>()
    for (nv in novel) {
        val cat = categorize(nv.payee ?: "", nv.amount, allowedNames) ?: continue
        val cid = nameToId[cat] ?: continue
        extraCategorize.add(YnabCategorizeAction(nv.id, cid))
        if (abs(nv.amount) * 1000 < YNAB_REVIEW_THRESHOLD_MILLIUNITS) {
            extraApprove.add(nv.id)
        }
    }
    return NovelResolution(extraCategorize, extraApprove)
}

/** Full decision for one tick: every [YnabTxnUpdate] to PATCH, plus the
 * `cover` budgeted-moves, plus counts for logging -- mirrors `run_ynab`'s own
 * summary line (`"YNAB: categorized N, approved N, N novel, N held, covered
 * N cat(s)"`). */
internal data class YnabWriteDecision(
    val updates: List<YnabTxnUpdate>,
    val cover: List<YnabCoverAction>,
    val categorizedCount: Int,
    val approvedCount: Int,
    val novelCount: Int,
    val heldCount: Int,
)

/**
 * Ports the rest of `run_ynab`'s body after `plan()`/the novel-payee loop:
 * builds the final bulk-PATCH payload from `categorize`/`approve`, exactly
 * matching Python's `catmap`/`appr` set-union logic
 * (`for tid in set(catmap) | appr`).
 */
internal fun buildYnabWriteDecision(plan: YnabPlan, novelResolution: NovelResolution): YnabWriteDecision {
    val categorize = plan.categorize + novelResolution.categorize
    val approve = (plan.approve + novelResolution.approve).toSet()
    val catMap = categorize.associate { it.id to it.categoryId }
    val ids = catMap.keys + approve
    val updates = ids.map { tid ->
        YnabTxnUpdate(
            id = tid,
            categoryId = catMap[tid],
            approved = if (tid in approve) true else null,
        )
    }
    return YnabWriteDecision(
        updates = updates,
        cover = plan.cover,
        categorizedCount = categorize.size,
        approvedCount = approve.size,
        novelCount = plan.novel.size,
        heldCount = plan.holds.size,
    )
}

// ---------------------------------------------------------------------
// Self-gated tick, called from LifeOpsComputeWorker.doWork.
// ---------------------------------------------------------------------

internal const val YNAB_WRITE_GATE_PREFS_NAME = "lifeops_ynab_write_gate"

/** ISO date string of the last successfully-completed write tick, or absent
 * if none has ever run. Written ONLY on success -- see this file's top-level
 * kdoc "Retry-safety" section for why: a failed attempt must be retried on
 * the very next 15-min tick rather than silently skipped for the rest of the
 * day. Mirrors [KEY_LAST_DIGEST_SENT_DATE]'s exact same reasoning. */
internal const val KEY_LAST_YNAB_WRITE_DATE = "last_ynab_write_date"

/** Once-per-day gate (any day, unlike the digest's Sunday-only gate) --
 * matches `runner.py`'s own cadence: `run_ynab` lives in the `"daily"` tier,
 * not the 10-min `"tick"` tier (see that file's `TIERS` dict and its own
 * comment on why: category writes/novel-payee LLM calls don't need
 * sub-daily latency, and a tighter cadence would multiply YNAB API + LLM
 * cost for no real benefit). */
internal fun isYnabWriteDue(now: LocalDateTime, lastRunDate: String?): Boolean =
    lastRunDate != now.toLocalDate().toString()

/**
 * Applies [cover]'s per-category absolute `budgeted` targets to YNAB, in an
 * order chosen to make a partial failure fail SAFE rather than fail
 * money-duplicating.
 *
 * [cover] each entry is a single absolute `budgeted` value per category
 * (`YnabEngine.kt`'s `cover()` collapses all draws from/to one category into
 * one final target, never multiple partial writes to the same category), so
 * the only ordering question is: across DIFFERENT categories in the same
 * batch, which do we write first?
 *
 * This file's own kdoc used to claim no special care was needed here beyond
 * "each individual PATCH is an absolute set, so it's idempotent" -- true per
 * write, but NOT sufficient for the batch as a whole, and this is a real bug
 * a review caught (not a hypothetical): [cover]'s decision is a STATE-BASED
 * diff recomputed fresh from `/months/current` on every tick, not a
 * diff-from-last-attempt. If a destination category's credit (raising its
 * `budgeted`, curing its overspend) is written BEFORE its funding source's
 * debit (lowering the source's `budgeted`), and the tick then fails/crashes
 * before the debit PATCH fires, the next tick's fresh snapshot sees the
 * destination as no longer overspent -- `cover()` finds no deficit there at
 * all and never retries debiting the source. Net effect: the destination
 * permanently keeps money it was credited, and the source is never actually
 * charged for it -- real money materialized from nothing, non-self-healing.
 *
 * Writing every DEBIT (a category whose new `budgeted` is LOWER than its
 * `oldBudgeted` from the same snapshot the plan was computed against) before
 * any CREDIT (higher) closes this: a partial failure can now only ever leave
 * a destination still accurately overspent (a debit succeeded, or hasn't run
 * yet, but the matching credit hasn't landed) -- never holding uncredited
 * money with no debited source. A retry's fresh snapshot then correctly
 * re-derives the real remaining deficit/availability and converges, instead
 * of silently duplicating or losing money. Entries with no change from
 * [oldMonth] (shouldn't occur in practice, since `cover()` only emits
 * entries it actually changed) are treated as debits (order doesn't matter
 * for a true no-op).
 */
internal fun applyCoverMoves(budgetWrite: YnabBudgetWriteClient, cover: List<YnabCoverAction>, oldMonth: YnabMonth) {
    val oldBudgetedById = oldMonth.categories.associate { it.id to it.budgeted }
    val (debits, credits) = cover.partition { mv -> mv.budgeted <= (oldBudgetedById[mv.categoryId] ?: mv.budgeted) }
    for (mv in debits) budgetWrite.setBudgeted(mv.categoryId, mv.budgeted)
    for (mv in credits) budgetWrite.setBudgeted(mv.categoryId, mv.budgeted)
}

/**
 * The actual on-device YNAB read-decide-write tick. No-ops silently (mirrors
 * `run_ynab`'s own always-available-if-configured posture, and
 * [runWeeklyDigestIfDue]'s no-op shape) if: it already ran today, no YNAB
 * token is configured, or the fetch/write hits a network error -- any
 * failure just logs and returns, leaving the gate unmarked so the next
 * 15-min tick retries (see top-level kdoc).
 *
 * The Anthropic API key is OPTIONAL here, exactly like `run_ynab`'s own
 * `if config.ANTHROPIC_API_KEY and out["novel"]` guard: without one, novel
 * payees are simply left unresolved (no categorize/approve for them) rather
 * than blocking the rest of the categorize/approve/hold/cover writes that
 * don't need the LLM at all.
 */
internal fun runYnabWriteTickIfDue(
    context: Context,
    now: LocalDateTime,
    fetch: YnabFetch,
    write: YnabWriteClient,
    budgetWrite: YnabBudgetWriteClient,
    categorizeUnknownPayee: ((payee: String, amount: Double, categoryNames: List<String>) -> String?)?,
    noAssign: List<String> = YNAB_NO_ASSIGN_DEFAULT,
    coverOrder: List<String> = emptyList(),
) {
    val gatePrefs = context.getSharedPreferences(YNAB_WRITE_GATE_PREFS_NAME, Context.MODE_PRIVATE)
    val lastRun = gatePrefs.getString(KEY_LAST_YNAB_WRITE_DATE, null)
    if (!isYnabWriteDue(now, lastRun)) return

    val fetched = try {
        fetch.fetch(now)
    } catch (e: Exception) {
        Log.e("YnabWrite", "YNAB fetch failed during write tick", e)
        return
    }

    val plan = plan(fetched.categories, fetched.history, fetched.unapproved, fetched.month, coverOrder, noAssign)

    val novelResolution = if (categorizeUnknownPayee != null && plan.novel.isNotEmpty()) {
        resolveNovelPayees(plan.novel, fetched.categories, noAssign, categorizeUnknownPayee)
    } else {
        NovelResolution(emptyList(), emptyList())
    }

    val decision = buildYnabWriteDecision(plan, novelResolution)

    try {
        write.updateTransactions(decision.updates)
        applyCoverMoves(budgetWrite, decision.cover, fetched.month)
    } catch (e: Exception) {
        Log.e("YnabWrite", "YNAB write failed during write tick", e)
        return
    }

    gatePrefs.edit().putString(KEY_LAST_YNAB_WRITE_DATE, now.toLocalDate().toString()).apply()
    Log.i(
        "YnabWrite",
        "YNAB: categorized ${decision.categorizedCount}, approved ${decision.approvedCount}, " +
            "${decision.novelCount} novel, ${decision.heldCount} held, covered ${decision.cover.size} cat(s)",
    )
}

/** Real-dependency entry point called from [LifeOpsComputeWorker.doWork].
 * No-ops if no YNAB token is configured (same "not configured yet" posture
 * every other self-gated piece in this file's sibling functions uses). */
internal fun runYnabWriteTickIfConfigured(context: Context, now: LocalDateTime) {
    val token = WidgetConfigStore.getYnabToken(context)
    if (token == null) {
        Log.i("YnabWrite", "skipping YNAB write tick: no YNAB token configured")
        return
    }
    val budget = WidgetConfigStore.getYnabBudget(context)
    val apiKey = WidgetConfigStore.getAnthropicApiKey(context)
    val client = apiKey?.let { AnthropicClient(it) }

    runYnabWriteTickIfDue(
        context = context,
        now = now,
        fetch = RealYnabFetch(token, budget),
        write = RealYnabWrite(token, budget),
        budgetWrite = RealYnabWrite(token, budget),
        categorizeUnknownPayee = client?.let { c -> { payee: String, amount: Double, names: List<String> -> c.categorizeUnknownPayee(payee, amount, names) } },
        coverOrder = WidgetConfigStore.getYnabDiscretionaryCategories(context),
    )
}
