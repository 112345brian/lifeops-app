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

/**
 * One candidate time-of-day slot a [Routine] can be scheduled into on a
 * given day, evaluated against that day's own variable context by
 * [LifeScript].
 *
 * NAMING HAZARD, read before renaming or reusing this: this is a completely
 * different concept from [Anchor.WINDOW] ("N times per rolling per_days-day
 * period" -- a cadence model with no notion of clock time). [TimeSlot] is a
 * specific clock-time range within a single day (e.g. "19:00-20:00,
 * evening") that becomes available when [condition] evaluates true against
 * that day's context map. Deliberately NOT named `Window` anywhere in this
 * file so it can never be confused with `Anchor.WINDOW` by a future reader
 * skimming call sites.
 *
 * @param kind a free-form label for this slot (e.g. "morning"/"evening"),
 *   carried through to the scheduled action/output -- not interpreted by
 *   the scheduling machinery itself.
 * @param condition a raw LifeScript expression string (see [LifeScript]),
 *   evaluated per candidate day against that day's variable-context map.
 *   Must evaluate to a `Boolean`; anything else is an evaluate-time error
 *   (see [slotFor]). A day is "available" for this slot when [condition]
 *   evaluates `true`.
 */
data class TimeSlot(
    val start: String,
    val end: String,
    val kind: String,
    val condition: String,
)

/** Matches `lifeops/routine.py`'s `Routine` dataclass field-for-field, plus
 * [slots] -- the generic time-of-day scheduling-window extension (see
 * [TimeSlot]'s kdoc). Defaults to an empty list so every existing caller
 * (`ChoreEngine.kt`, `SocialEngine.kt`, and their tests, none of which use
 * scheduling) is source-compatible without any change. */
data class Routine(
    val id: String,
    val times: Int,
    val perDays: Int,
    val anchor: Anchor,
    val slots: List<TimeSlot> = emptyList(),
)

/**
 * One candidate day for scheduling purposes: a calendar [date] paired with
 * its own LifeScript variable-context map (e.g. `{"gymBlocked": false,
 * "sleepOk": true, ...}`), used to evaluate a [Routine]'s [TimeSlot]
 * conditions for that specific day. Plain data -- deliberately not coupled
 * to any one domain (gym, chore, social, ...).
 */
data class CandidateDay(val date: LocalDate, val context: Map<String, Any>)

/** One (date, slot) pair a [Routine] was actually matched to -- either as a
 * candidate under consideration or as a final chosen booking. */
data class ScheduleCandidate(val date: LocalDate, val slot: TimeSlot)

/** Result of [scheduleRoutine]: the greedily chosen (date, slot) pairs (up
 * to `needed`, respecting the consecutive-day cap) plus [viableLeft] -- how
 * many total slots (chosen + a greedy simulation of the remaining viable
 * candidates) could actually be booked under the cap. See [scheduleRoutine]
 * for the full algorithm notes. */
data class ScheduleResult(
    val chosen: List<ScheduleCandidate>,
    val viableLeft: Int,
)

/**
 * Ports `gym_engine.py`'s `slot_for(day, r)`, generalized to any [Routine]
 * carrying [TimeSlot]s. Returns the FIRST slot (in [Routine.slots]'
 * declared order) whose [TimeSlot.condition] evaluates `true` against
 * [context] -- not "any" true slot. This preserves `slot_for`'s exact
 * precedence/short-circuit behavior (e.g. gym's evening slot is tried
 * before its morning slot, and morning is only reached if evening's
 * condition evaluates false) as long as the condition strings themselves
 * encode the same branching gym's Python `if`/`elif` chain did -- see
 * `GymSchedule.kt`'s condition-string kdoc for gym's specific translation.
 *
 * Returns `null` when no slot's condition is true that day (mirrors
 * `slot_for` returning `None`).
 *
 * Every slot's condition is PARSED up front (see [compileSlots]) before any
 * of them is evaluated, so a syntactically broken condition on a
 * rarely-reached later slot fails immediately and deterministically instead
 * of lying dormant until the one flag combination that finally reaches it.
 *
 * @throws LifeScriptParseException if any slot's condition string is
 *   syntactically malformed.
 * @throws LifeScriptEvaluateException if an evaluated condition references
 *   an undefined context variable or evaluates to a non-Boolean value.
 */
fun slotFor(routine: Routine, context: Map<String, Any>): TimeSlot? =
    firstMatchingSlot(compileSlots(routine), context)

/**
 * Parses each of [routine]'s [TimeSlot.condition] strings exactly once,
 * pairing every slot with its reusable parsed expression.
 *
 * `LifeScript`'s own kdoc calls this out explicitly ("a routine's window
 * condition is evaluated once per candidate day, so re-parsing the same
 * expression string every time would be wasteful") -- [scheduleRoutine]
 * compiles once and then evaluates the same parsed trees against every
 * candidate day's context, rather than re-lexing/re-parsing per day.
 */
private fun compileSlots(routine: Routine): List<Pair<TimeSlot, LifeScriptExpr>> =
    routine.slots.map { it to LifeScript.parse(it.condition) }

/** The first slot (in declared order) whose already-parsed condition
 * evaluates `true` against [context]. See [slotFor] for the semantics. */
private fun firstMatchingSlot(
    compiled: List<Pair<TimeSlot, LifeScriptExpr>>,
    context: Map<String, Any>,
): TimeSlot? {
    for ((slot, condition) in compiled) {
        val result = condition.evaluate(context)
        val ok = result as? Boolean
            ?: throw LifeScriptEvaluateException(
                "TimeSlot '${slot.kind}' condition '${slot.condition}' must evaluate to a boolean, got $result"
            )
        if (ok) return slot
    }
    return null
}

/**
 * Ports `gym_engine.py`'s `run_length(date_str, busy)`: how many consecutive
 * calendar days (including [date] itself) around [date] are present in
 * [busy]. Pure date/set math -- no gym-specific content, despite the name
 * this algorithm is documented under in the original Python module.
 */
fun runLength(date: LocalDate, busy: Set<LocalDate>): Int {
    var n = 1
    var x = date.minusDays(1)
    while (busy.contains(x)) {
        n++
        x = x.minusDays(1)
    }
    x = date.plusDays(1)
    while (busy.contains(x)) {
        n++
        x = x.plusDays(1)
    }
    return n
}

/**
 * Generic greedy slot-selection/consecutive-day-cap scheduler, extracted
 * from `gym_engine.py`'s `plan()` (the candidate-building loop, the
 * `chosen`/`busy` greedy pick loop, and the `viable_left` simulation) with
 * every gym-specific concept removed -- domain-agnostic date/set math plus
 * one [LifeScript] evaluation per candidate day.
 *
 * Algorithm, matching `plan()`'s structure exactly:
 * 1. Build the candidate list: every [CandidateDay] not in [excludeDates]
 *    (e.g. days already scheduled or already completed for this routine),
 *    sorted by date, paired with the slot [slotFor] resolves for that day's
 *    context (days with no viable slot are dropped).
 * 2. Greedily walk the sorted candidates, picking up to [needed] of them
 *    into `chosen`, skipping any candidate whose date is ALREADY busy
 *    (see below) and any candidate whose date would push its surrounding
 *    consecutive run (via [runLength], seeded with [busyDates] plus every
 *    date already `chosen`) past [maxConsecutive].
 * 3. Compute [ScheduleResult.viableLeft]: `chosen.size` plus a SECOND greedy
 *    simulation over the same sorted candidate list, seeded with `chosen`'s
 *    dates added to [busyDates] -- checking each remaining candidate against
 *    the simulation's growing busy set, not just the original [busyDates].
 *    This is deliberately the same shape as step 2's loop, ported exactly
 *    as structured in `gym_engine.py`'s own comment: two candidates can be
 *    mutually adjacent to EACH OTHER (not just to the frozen busy set) --
 *    e.g. three back-to-back open days with `maxConsecutive=1` can fit at
 *    most 2 of them, not all 3, even though each looks individually viable
 *    against [busyDates] alone. This is the exact property
 *    `RoutineSchedulingTest.kt`'s viable-left-checks-candidates-against-
 *    each-other test locks down generically (mirroring
 *    `GymEngineTest.kt`'s original gym-specific regression test for the
 *    same bug class).
 *
 * @param excludeDates dates to drop from candidacy entirely before slot
 *   evaluation (e.g. dates already scheduled/completed) -- ported from
 *   `plan()`'s own pre-filter (`if day["date"] in sched_dates or
 *   day["date"] in done_dates: continue`), which skips a day BEFORE calling
 *   `slot_for` on it at all.
 * @param busyDates the starting busy set the cap is evaluated against --
 *   normally the same dates as [excludeDates] (a day already booked/done
 *   still counts toward the consecutive-day cap even though it can't be
 *   re-booked), but kept as a separate parameter since a caller could
 *   legitimately want a busy set that isn't identical to its exclude set.
 *   A date that is busy is never double-booked even if the caller left it
 *   out of [excludeDates]: "busy" means the day is already occupied, so
 *   both the greedy pick loop and the viable-left simulation skip it. That
 *   guard is a no-op for a caller (like `GymSchedule.kt`) passing identical
 *   sets -- it only matters when the two sets genuinely differ, and it also
 *   makes a duplicated date in [candidateDays] resolve to a single booking
 *   rather than two bookings of the same day.
 */
fun scheduleRoutine(
    routine: Routine,
    candidateDays: List<CandidateDay>,
    needed: Int,
    maxConsecutive: Int,
    excludeDates: Set<LocalDate> = emptySet(),
    busyDates: Set<LocalDate> = emptySet(),
): ScheduleResult {
    // Parsed once, evaluated against every candidate day's context below --
    // see compileSlots' kdoc.
    val compiled = compileSlots(routine)

    val candidates = candidateDays
        .asSequence()
        .filter { it.date !in excludeDates }
        .sortedBy { it.date }
        .mapNotNull { cd -> firstMatchingSlot(compiled, cd.context)?.let { ScheduleCandidate(cd.date, it) } }
        .toList()

    val chosen = mutableListOf<ScheduleCandidate>()
    val busy = busyDates.toMutableSet()
    for (c in candidates) {
        if (chosen.size >= needed) break
        // A day that is already busy cannot be booked again -- symmetric
        // with the viable-left simulation's own `in simBusy` guard below.
        // No-op when excludeDates == busyDates and candidate dates are
        // unique (the only shape `gym_engine.py`'s plan() ever produced);
        // load-bearing when they differ, or when candidateDays repeats a
        // date, either of which would otherwise double-book that day.
        if (c.date in busy) continue
        if (runLength(c.date, busy + c.date) > maxConsecutive) continue
        chosen.add(c)
        busy.add(c.date)
    }

    val simBusy = busy.toMutableSet()
    var viableLeft = chosen.size
    for (c in candidates) {
        if (c.date in simBusy) continue
        if (runLength(c.date, simBusy + c.date) <= maxConsecutive) {
            simBusy.add(c.date)
            viableLeft++
        }
    }

    return ScheduleResult(chosen, viableLeft)
}

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
