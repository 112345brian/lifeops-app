package com.lifeops.briefing

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Kotlin port of `lifeops/routine.py` -- the shared "is this recurring thing
 * due" primitive named as the first step of the on-device migration (see
 * docs/lifeops_capability_todo.md's "On-Device Migration" section: "fully
 * pure, no I/O, the shared due-check math everything else depends on. Port
 * and test this first."). Deliberately only that shared math -- no on_due
 * action, no cross-routine gating, no scripting escape hatch -- mirroring
 * the Python module's own scope note (see its docstring history).
 *
 * NOT wired into any widget/worker/persistence path yet -- this is a
 * standalone, unused-so-far port. The gym/chore/social engine ports this
 * doc lists as next steps will depend on it, but nothing calls it today.
 */

/**
 * Which cadence model a [Routine] uses.
 * - [WINDOW]: "N times per rolling per_days-day window" (e.g. gym: 4x/7d).
 * - [SINCE_LAST]: "due again per_days days after the last completion"
 *   (e.g. a chore, or a social nudge).
 *
 * Kotlin translation note: the Python dataclass stores this as a raw
 * `str` ("window" | "since_last") and `status()` raises a runtime
 * `ValueError` for anything else. An enum makes that invalid state
 * unrepresentable at compile time instead of only at call time --
 * strictly more type-safe, with identical runtime behavior for every
 * valid input, so [status] has no `ValueError`-equivalent branch to port.
 * [nextDueDate]'s anchor-mismatch check is still ported as a runtime
 * check, since that one is a genuine cross-call business rule ("this
 * function only makes sense for since_last routines"), not a case of an
 * otherwise-unrepresentable value sneaking through.
 */
enum class Anchor { WINDOW, SINCE_LAST }

/** Matches `lifeops/routine.py`'s `Routine` dataclass field-for-field. */
data class Routine(
    val id: String,
    val times: Int,
    val perDays: Int,
    val anchor: Anchor,
)

/**
 * Return shape of [status]/[statusSinceLast], mirroring the Python
 * functions' two possible result dict shapes. Both variants carry `due` so
 * callers that only care about due/not-due don't need a `when` first.
 */
sealed class RoutineStatus {
    abstract val due: Boolean

    /** anchor=WINDOW result: mirrors `{"due": bool, "times_this_window": int}`. */
    data class Window(override val due: Boolean, val timesThisWindow: Int) : RoutineStatus()

    /** anchor=SINCE_LAST result: mirrors `{"due": bool, "days_since_last": int?}`. */
    data class SinceLast(override val due: Boolean, val daysSinceLast: Int?) : RoutineStatus()
}

private fun parse(ts: String): LocalDateTime = LocalDateTime.parse(ts)

private fun dueSinceLast(daysSinceLast: Int?, perDays: Int): Boolean =
    daysSinceLast == null || daysSinceLast >= perDays

private fun dueWindow(timesThisWindow: Int, times: Int): Boolean =
    timesThisWindow < times

/**
 * anchor=SINCE_LAST, for callers that already have a precomputed
 * days-since-last-completion int rather than raw timestamps (Python's own
 * docstring cites `gather.social_input`'s `ago(history.last(...))` as the
 * motivating caller) -- avoids a lossy round-trip through a synthetic
 * timestamp just to reuse [status]'s timestamp-based path. Ports
 * `status_since_last` verbatim.
 */
fun statusSinceLast(routine: Routine, daysSinceLast: Int?): RoutineStatus.SinceLast =
    RoutineStatus.SinceLast(dueSinceLast(daysSinceLast, routine.perDays), daysSinceLast)

/**
 * `eventTimestamps`: ISO-8601 local-datetime strings, already filtered to
 * this routine's own completions (e.g. a single chore's own completion
 * dates). Ports `status()`.
 *
 * Behavioral note ported deliberately: "most recent" and "within window"
 * are computed by comparing *parsed* [LocalDateTime] values, never by
 * comparing the raw strings -- routine.py's own docstring flags this as a
 * real historical bug class it fixed (string comparison happens to agree
 * with chronological order for consistently-formatted ISO-8601 timestamps,
 * but that's an implicit assumption a shared primitive shouldn't quietly
 * depend on). Using `java.time.LocalDateTime.compareTo`/`Duration` here
 * instead of any string comparison is a deliberate choice to not
 * reintroduce that exact bug class in the port.
 */
fun status(routine: Routine, eventTimestamps: List<String>, now: LocalDateTime): RoutineStatus {
    return when (routine.anchor) {
        Anchor.SINCE_LAST -> {
            // max() over parsed datetimes, not the raw strings -- see the
            // kdoc above.
            val last = eventTimestamps.map(::parse).maxOrNull()
            // Duration.between(...).toDays() truncates toward zero, which
            // is equivalent to Python's timedelta.days (floor division) for
            // any non-negative duration -- the only case this primitive is
            // ever fed (a completion can't be in the future relative to
            // `now`).
            val daysSince = last?.let { Duration.between(it, now).toDays().toInt() }
            statusSinceLast(routine, daysSince)
        }
        Anchor.WINDOW -> {
            val windowStart = now.minusDays(routine.perDays.toLong())
            // Dedup by calendar date -- source data is often already
            // date-grained, but callers passing raw timestamps shouldn't
            // double-count two events on the same day.
            val dates = eventTimestamps
                .map(::parse)
                .filter { !it.isBefore(windowStart) }
                .map { it.toLocalDate() }
                .toSet()
            RoutineStatus.Window(dueWindow(dates.size, routine.times), dates.size)
        }
    }
}

/**
 * anchor=SINCE_LAST only: the date this routine is next due, N days after a
 * specific completion date. Ports `next_due_date`.
 */
fun nextDueDate(routine: Routine, lastCompletedDate: LocalDate): LocalDate {
    require(routine.anchor == Anchor.SINCE_LAST) {
        "nextDueDate only applies to anchor=SINCE_LAST"
    }
    return lastCompletedDate.plusDays(routine.perDays.toLong())
}
