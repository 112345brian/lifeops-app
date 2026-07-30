package com.lifeops.briefing

/**
 * Kotlin port of `lifeops/engines/load_engine.py` -- the coursework/deadline
 * "won't fit" watchdog (advisory, warn-only). Named in
 * docs/lifeops_capability_todo.md's "On-Device Migration" section as one of
 * the pure-logic engine ports; unlike the gym/chore/social engine ports
 * listed alongside it, this one does NOT depend on `Routine.kt` -- there is
 * no recurring-cadence concept here at all, just a greedy cumulative-hours-
 * vs-available-capacity walk over sorted-by-due-date items.
 *
 * Two call sites share one shape in the Python source (both `plan()`'s
 * per-item alerts and `deadline_risk()`'s single "earliest binding deadline"
 * alert read from the same kind of task/assignment dict, just using
 * different subsets of its fields), so this port also uses a single
 * [Assignment] data class for both, matching the Python dicts' actual field
 * usage rather than inventing separate input types per function:
 *  - `deadline_risk`/`_deadline_crunch`/`deadline_crunch_item` read only
 *    `title`, `due_in_days`, `remaining_min`.
 *  - `at_risk_assignments`/`plan` read `title`, `due_in_h`, `due_in_days`,
 *    `remaining_min`, `progress`.
 *
 * `progress` is intentionally kept as a raw nullable [Double] rather than
 * a more specific "progressMinutes" type: the Python source only ever tests
 * it against zero (`_n(a.get("progress"), 0) == 0` -- "has anything been
 * started on this yet") and never does arithmetic with it, so porting it as
 * anything more structured than "an optional number, compared to zero"
 * would be guessing at a schema the source doesn't establish.
 *
 * `_n(v, default)` (missing/None-tolerant numeric read, so a malformed
 * assignment can't crash the advisory-only watcher) becomes [nz] here --
 * same "null means use the default, anything else passes through
 * unchanged" semantics, just spelled for Kotlin's nullable types instead of
 * Python's `None`.
 *
 * The `10**9` "effectively no deadline" sentinel Python uses for missing
 * `due_in_days`/`due_in_h` is ported as the literal [NO_DEADLINE] constant
 * (`1e9`) rather than e.g. `Double.MAX_VALUE`: it has to survive being
 * multiplied by a daily-capacity factor and compared against a real
 * cumulative-hours total without overflowing or behaving differently than
 * the Python original, and `1e9` does exactly what `10**9` did there.
 * `plan()`'s week-lookback filter uses its own distinct default of `99`
 * (days) for a missing `due_in_days` -- not `NO_DEADLINE` -- because `99`
 * is just "clearly outside the 7-day lookback window", a different
 * business rule than "no deadline exists"; ported as a literal to match.
 *
 * Alert severity is ported as a plain [String] (always `"high"` in this
 * engine, exactly like the Python `(message, severity)` tuples) rather than
 * an enum -- the source never uses any other severity value here, so an
 * enum would be speculative structure with no second case to justify it.
 *
 * Numeric formatting in alert messages (`%.0f`-equivalent, `int(round(...))`)
 * is ported using Kotlin/Java rounding (`String.format("%.0f", ...)`,
 * `Math.round`), which is HALF_UP rather than Python's HALF_EVEN
 * (banker's) rounding for `.0f` formatting. This is a cosmetic-only
 * difference (at most 1 unit, only exactly on a `.5` boundary) in
 * human-facing alert prose text; nothing in the Python test suite asserts
 * exact digit-for-digit formatted output, only alert count/severity/title
 * substrings, so this port doesn't attempt to reimplement banker's
 * rounding just to chase bit-for-bit string parity.
 */

/** Realistic study hours/week a person has, for `plan()`'s week-overbooked check. */
const val WEEK_CAPACITY_H: Double = 25.0

/** Realistic discretionary work-hours/day across everything, the default daily capacity. */
const val DAILY_CAPACITY_H: Double = 3.5

/** Python's `10**9` "no binding deadline" sentinel -- see file kdoc. */
private const val NO_DEADLINE: Double = 1e9

/** Missing/None-tolerant numeric read, mirroring Python's `_n(v, default)`. */
private fun nz(v: Double?, default: Double): Double = v ?: default

/** `(title or '?')[:limit]`, mirroring the Python alert-message title truncation. */
private fun displayTitle(title: String?, limit: Int): String =
    (if (title.isNullOrEmpty()) "?" else title).take(limit)

/**
 * One task/assignment, as read by both the deadline-crunch family and the
 * heavy-plus-soon/overbooked family of checks -- see file kdoc for exactly
 * which fields each family reads. All fields optional/nullable to mirror
 * a Python dict where any key may be missing or `None`; [nz] supplies the
 * same per-call defaults the Python source used at each read site.
 */
data class Assignment(
    val title: String? = null,
    val dueInHours: Double? = null,
    val dueInDays: Double? = null,
    val remainingMin: Double? = null,
    val progress: Double? = null,
)

/** One `(message, severity)` alert, mirroring the Python tuple shape verbatim. */
data class Alert(val message: String, val severity: String)

/** `{"alerts": [...]}`, mirroring `plan()`/`deadline_risk()`'s single-key result dict. */
data class AlertsResult(val alerts: List<Alert>)

/**
 * Result of the shared crunch walk: the earliest binding item plus the
 * cumulative hours due by it and the hours realistically available before
 * it, or all-null if nothing's at risk. Mirrors `_deadline_crunch`'s
 * `(item, cum_h, available)` tuple return. Kept internal, like Python's
 * leading-underscore convention, since it's an implementation detail shared
 * by [deadlineCrunchItem] and [deadlineRisk], not part of this file's
 * public surface.
 */
internal data class DeadlineCrunch(
    val item: Assignment?,
    val cumHours: Double?,
    val availableHours: Double?,
)

/**
 * Shared walk behind both [deadlineCrunchItem] and [deadlineRisk], so the
 * two can never disagree about what's at risk or how the cumulative hours
 * were computed. Ports `_deadline_crunch` verbatim: sort deadline-bearing
 * items (positive remaining work) by due date, walk them accumulating
 * hours, and return the first item whose deadline the cumulative work
 * blows past the hours realistically available before it.
 */
internal fun deadlineCrunch(items: List<Assignment>, dailyCapacityH: Double = DAILY_CAPACITY_H): DeadlineCrunch {
    val dated = items
        .filter { nz(it.remainingMin, 0.0) > 0.0 }
        .sortedBy { nz(it.dueInDays, NO_DEADLINE) }

    var cumH = 0.0
    for (i in dated) {
        val d = maxOf(0.25, nz(i.dueInDays, NO_DEADLINE))
        cumH += nz(i.remainingMin, 0.0) / 60.0
        val available = d * dailyCapacityH
        if (cumH > available) {
            return DeadlineCrunch(i, cumH, available)
        }
    }
    return DeadlineCrunch(null, null, null)
}

/**
 * The raw item (not a formatted string) behind [deadlineRisk]'s "won't fit"
 * alert, or `null` if nothing's at risk. Exposed separately so a caller
 * that wants the real due date/title doesn't have to parse it back out of
 * `deadlineRisk`'s prose alert string. Ports `deadline_crunch_item`.
 */
fun deadlineCrunchItem(items: List<Assignment>, dailyCapacityH: Double = DAILY_CAPACITY_H): Assignment? =
    deadlineCrunch(items, dailyCapacityH).item

/**
 * Motion-style "this won't fit" check over ALL deadline-bearing tasks, not
 * just coursework. Walk deadlines in order; if the total remaining work due
 * on or before a deadline exceeds the work-hours realistically available
 * before it (days-until times daily capacity), that deadline is at risk.
 * Reports the EARLIEST binding deadline -- the real constraint to act on
 * now -- rather than every downstream one it also blows. Deterministic,
 * advisory. Ports `deadline_risk` verbatim.
 */
fun deadlineRisk(items: List<Assignment>, dailyCapacityH: Double = DAILY_CAPACITY_H): AlertsResult {
    val alerts = mutableListOf<Alert>()
    val (i, cumH, available) = deadlineCrunch(items, dailyCapacityH)
    if (i != null && cumH != null && available != null) {
        val d = maxOf(0.25, nz(i.dueInDays, NO_DEADLINE))
        val over = cumH - available
        val title = displayTitle(i.title, 34)
        alerts.add(
            Alert(
                "Deadline crunch: ~${String.format("%.0f", cumH)}h of work due by " +
                    "\"$title\" (~${Math.round(d)}d out) but only " +
                    "~${String.format("%.0f", available)}h realistically free -- " +
                    "~${String.format("%.0f", over)}h over. Start early or cut scope.",
                "high",
            ),
        )
    }
    return AlertsResult(alerts)
}

/**
 * The raw assignment dicts (not formatted strings) behind [plan]'s "Heavy +
 * soon" per-item alerts -- same criterion `plan` uses internally, so a
 * caller that wants the real title/dueInHours doesn't have to parse it back
 * out of `plan`'s prose alert string. Ports `at_risk_assignments` verbatim.
 */
fun atRiskAssignments(assignments: List<Assignment>): List<Assignment> =
    assignments.filter {
        nz(it.dueInHours, NO_DEADLINE) <= 48.0 &&
            nz(it.progress, 0.0) == 0.0 &&
            nz(it.remainingMin, 0.0) >= 120.0
    }

/** Ports `plan` verbatim: per-item "heavy + soon" alerts, plus one week-level "overbooked" alert. */
fun plan(assignments: List<Assignment>): AlertsResult {
    val alerts = mutableListOf<Alert>()
    for (a in atRiskAssignments(assignments)) {
        val dueH = nz(a.dueInHours, NO_DEADLINE)
        val rem = nz(a.remainingMin, 0.0)
        val title = displayTitle(a.title, 42)
        alerts.add(
            Alert(
                "Heavy + soon: $title (~${(rem / 60.0).toInt()}h) due in ${dueH.toInt()}h.",
                "high",
            ),
        )
    }

    // "99" here is a distinct business default from NO_DEADLINE -- see file kdoc.
    val weekH = assignments
        .filter { nz(it.dueInDays, 99.0) <= 7.0 }
        .sumOf { nz(it.remainingMin, 0.0) } / 60.0
    if (weekH > WEEK_CAPACITY_H) {
        alerts.add(
            Alert(
                "Overbooked: ~${String.format("%.0f", weekH)}h of coursework due in 7 days " +
                    "(> ~${WEEK_CAPACITY_H.toInt()}h you realistically have). Start early or cut something.",
                "high",
            ),
        )
    }
    return AlertsResult(alerts)
}
