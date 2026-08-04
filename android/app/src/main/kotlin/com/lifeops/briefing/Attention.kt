package com.lifeops.briefing

/**
 * Kotlin port of `lifeops/attention.py` -- the deterministic cross-domain
 * attention model. "The LLM may explain this state, but it never chooses
 * it" (Python module docstring) -- this port stays equally pure: no I/O, no
 * LLM call, same ordered reasons/headline for every client.
 *
 * Per docs/lifeops_capability_todo.md, this was deliberately ported LAST
 * among the engine ports ("it's the capstone: it only becomes useful once
 * everything above is actually wired into a real on-device data-fetch +
 * compute tick"). [compute] itself is a faithful line-for-line translation
 * of `attention.py`'s pure decision function -- unchanged by the note below.
 *
 * [SystemHealth] is now produced on-device, but NOT via a literal port of
 * `system.errors`/`age_mins`: those assumed a server-side automation
 * PROCESS being monitored (last successful scheduled run, error count from
 * that process), a concept with no on-device equivalent -- there is no
 * separate "automation process" on the phone the way there was on the
 * server; the periodic tick just runs (or doesn't) and either fetches data
 * successfully or doesn't. See `OnDeviceSystemHealth.kt`'s top-level kdoc
 * for the genuinely-new on-device concept this required ("when did each
 * data source last successfully sync on this device, and how stale is
 * that") and every judgment call involved in designing it.
 *
 * Wired into [LifeOpsComputeWorker]'s periodic tick; see that file's own
 * kdoc.
 */

/** Mirrors `_RANK`'s four severity levels AND `visuals`'s three-way
 * symbol/label mapping's implicit domain (an overall "no reasons" state is
 * also representable as OK, never assigned to an individual [AttentionReason]
 * in practice). Declared in ascending severity order so [Severity.ordinal]
 * IS `_RANK`'s value -- no separate rank table needed, unlike the Python
 * source's standalone `_RANK` dict. */
enum class Severity { OK, WATCH, RISK, FUCKED }

/** Mirrors `_DOMAIN_PRIORITY`'s four keys, declared in the exact same
 * priority order (`coursework`=0, `system`=1, `money`=2, `gym`=3) so
 * [Domain.ordinal] IS `_DOMAIN_PRIORITY`'s value -- no separate priority
 * table needed. Python falls back to `99` for an unrecognized domain
 * string; not ported, since every reason `compute` itself produces uses one
 * of these four literal domain strings -- there is no code path that could
 * produce a fifth. */
enum class Domain { COURSEWORK, SYSTEM, MONEY, GYM }

/**
 * One overdue-task fact, matching the fields `compute` actually reads off
 * each `facts["overdue"]` dict entry: `title`, `due_in_h`, `due`.
 */
data class OverdueItem(
    val title: String? = null,
    val dueInHours: Double = 0.0,
    val due: String? = null,
)

/**
 * The structured daily-briefing input, matching every top-level key
 * `compute` reads off `facts`: `overdue`, `coursework_at_risk`, `due_today`,
 * `discretionary_dollars`, `gym_last_7d`, `gym_target`.
 */
data class AttentionFacts(
    val overdue: List<OverdueItem> = emptyList(),
    val courseworkAtRisk: List<String> = emptyList(),
    val dueToday: List<String> = emptyList(),
    val discretionaryDollars: Double? = null,
    val gymLast7d: Int? = null,
    val gymTarget: Int? = null,
)

/**
 * Optional runner-health input, matching `system`'s two read keys: `errors`,
 * `age_mins`. Passing `null` to [compute] (as opposed to a
 * `SystemHealth(errors = emptyMap(), ageMins = null)` instance) mirrors
 * Python's `system=None` -- `has_system` is `false` and none of the
 * system-domain branches fire, exactly as `system is not None` gates them
 * in the Python source.
 *
 * On-device, this is built by `OnDeviceSystemHealth.kt`'s
 * `computeOnDeviceSystemHealth` -- see that file's top-level kdoc for what
 * `errors`/`ageMins` mean on a phone (per-data-source sync freshness, not
 * server automation-process health) and why the shape is genuinely
 * different from `attention.py`'s server-side source of the same fields.
 */
data class SystemHealth(
    val errors: Map<String, String> = emptyMap(),
    val ageMins: Double? = null,
)

/** Mirrors `_reason(...)`'s output dict shape: `domain`, `severity`,
 * `title`, `recommended_action`, and an optional `due` (only overdue-item
 * reasons ever set it, matching `_reason`'s `due=None` default and
 * conditional `out["due"] = due`). */
data class AttentionReason(
    val domain: Domain,
    val severity: Severity,
    val title: String,
    val recommendedAction: String,
    val due: String? = null,
)

/** `compute`'s full return shape: `{state, symbol, label, headline,
 * reasons}`. */
data class AttentionResult(
    val state: Severity,
    val symbol: String,
    val label: String,
    val headline: String,
    val reasons: List<AttentionReason>,
)

private const val CAP = 6

private val SEVERITY_VISUALS: Map<Severity, Pair<String, String>> = mapOf(
    Severity.OK to ("●" to "OK"),
    Severity.WATCH to ("▲" to "WATCH"),
    Severity.RISK to ("◆" to "RISK"),
    Severity.FUCKED to ("■" to "FUCKED"),
)

/** Reasons ordered worst-severity-first, domain-priority-second -- ports
 * `reasons.sort(key=lambda r: (-_RANK[r["severity"]], _DOMAIN_PRIORITY[...]))`. */
private fun worstFirst(reasons: List<AttentionReason>): List<AttentionReason> =
    reasons.sortedWith(compareByDescending<AttentionReason> { it.severity.ordinal }.thenBy { it.domain.ordinal })

/**
 * Ports `compute(facts, system=None)` field-for-field and branch-for-branch.
 * See this file's top-level kdoc for what's genuinely new work (on-device
 * `SystemHealth` sourcing) versus what this function itself is (a faithful,
 * already-complete pure port).
 */
fun compute(facts: AttentionFacts, system: SystemHealth? = null): AttentionResult {
    val hasSystem = system != null
    val sys = system ?: SystemHealth()
    val ageMins = sys.ageMins
    val reasons = mutableListOf<AttentionReason>()

    if (hasSystem && sys.errors.isNotEmpty()) {
        reasons += AttentionReason(
            Domain.SYSTEM, Severity.FUCKED, "Automation has errors",
            "Open System and fix the failed domains.",
        )
    } else if (hasSystem && (ageMins == null || ageMins >= 120)) {
        reasons += AttentionReason(
            Domain.SYSTEM, Severity.RISK, "LifeOps data is stale",
            "Run catchup and check the scheduler.",
        )
    } else if (hasSystem && ageMins != null && ageMins >= 30) {
        reasons += AttentionReason(
            Domain.SYSTEM, Severity.WATCH, "LifeOps has not synced recently",
            "Refresh if the next scheduled run does not recover.",
        )
    }

    for (item in facts.overdue) {
        // Math.rint, not kotlin.math.roundToInt: Python's round() is
        // round-half-to-even ("banker's rounding"), and Math.rint matches
        // that exactly (roundToInt/Math.round round half away from zero) --
        // load-bearing only for an exact X.5-hour overdue item, but ported
        // faithfully rather than left as a latent tie-breaking mismatch.
        val hours = maxOf(0, Math.rint(-item.dueInHours).toInt())
        val severity = if (hours >= 24) Severity.FUCKED else Severity.RISK
        val title = item.title?.takeIf { it.isNotEmpty() } ?: "Overdue task"
        reasons += AttentionReason(
            Domain.COURSEWORK, severity, "Overdue: $title",
            "Do it now or deliberately reschedule it.", due = item.due,
        )
    }

    // `{r["title"].removeprefix("Overdue: ") for r in reasons}` -- computed
    // from whatever's in `reasons` so far (system + overdue reasons), same
    // as the Python source.
    val seen = reasons.map { it.title.removePrefix("Overdue: ") }.toSet()
    for (title in facts.courseworkAtRisk) {
        if (title !in seen) {
            reasons += AttentionReason(
                Domain.COURSEWORK, Severity.RISK, "Deadline risk: $title",
                "Protect enough focused time before the deadline.",
            )
        }
    }

    if (facts.dueToday.isNotEmpty()) {
        val title = if (facts.dueToday.size == 1) facts.dueToday[0] else "${facts.dueToday.size} tasks due today"
        reasons += AttentionReason(
            Domain.COURSEWORK, Severity.WATCH, title,
            "Finish today's due work before lower-priority tasks.",
        )
    }

    // discretionary_dollars is net of known upcoming event costs (see
    // gather.spend_input's docstring) -- "after what's already spoken for,"
    // not the raw account balance, hence the copy below says so rather than
    // reading like the bank balance itself is gone.
    val money = facts.discretionaryDollars
    if (money != null && money < 0) {
        reasons += AttentionReason(
            Domain.MONEY, Severity.RISK, "Discretionary balance is negative after upcoming plans",
            "Pause optional spending and review the cash-flow plan.",
        )
    } else if (money != null && money < 100) {
        reasons += AttentionReason(
            Domain.MONEY, Severity.WATCH, "Discretionary buffer is low after upcoming plans",
            "Check upcoming spending before committing.",
        )
    }

    val gym = facts.gymLast7d
    val target = facts.gymTarget
    if (gym != null && target != null && target != 0 && gym <= maxOf(0, target - 2)) {
        reasons += AttentionReason(
            Domain.GYM, Severity.WATCH, "Gym cadence is behind target",
            "Take the next viable gym slot.",
        )
    }

    val sorted = worstFirst(reasons)

    // A flat top-6 truncation let one domain's flood (e.g. 6+ overdue
    // coursework items, all "fucked") silently crowd out every other
    // domain's reasons -- including one as urgent as a newly-negative
    // discretionary balance. Guarantee each domain that produced at least
    // one reason keeps its single worst one, then fill the remaining cap
    // slots with the next-worst reasons overall -- still worst-first, just
    // never fully silent for an entire domain. Indices into `sorted` stand
    // in for Python's `id(r)` identity check (a data-class `AttentionReason`
    // can structurally equal another one; index identity can't).
    val guaranteedIndices = mutableSetOf<Int>()
    val seenDomains = mutableSetOf<Domain>()
    val capped = mutableListOf<AttentionReason>()
    for ((i, r) in sorted.withIndex()) {
        if (r.domain !in seenDomains) {
            capped += r
            seenDomains += r.domain
            guaranteedIndices += i
        }
    }
    for ((i, r) in sorted.withIndex()) {
        if (capped.size >= CAP) break
        if (i !in guaranteedIndices) capped += r
    }

    val finalReasons = worstFirst(capped).take(CAP)
    val state = finalReasons.firstOrNull()?.severity ?: Severity.OK
    val (symbol, label) = SEVERITY_VISUALS.getValue(state)
    val headline = finalReasons.firstOrNull()?.recommendedAction
        ?: "You are clear. Follow the next scheduled move."

    return AttentionResult(state, symbol, label, headline, finalReasons)
}
