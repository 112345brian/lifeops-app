package com.lifeops.briefing

import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * Re-derives "the gym routine" as DATA + a thin gym-specific wrapper, per
 * the on-device migration design (see docs/lifeops_capability_todo.md's
 * "On-Device Migration" section and the design notes attached to this
 * change): gym's slot-picking/consecutive-day-cap/greedy-viability logic
 * was NOT bespoke gym code -- it decomposed into (1) generic scheduling
 * math, now `Routine.kt`'s [scheduleRoutine]/[slotFor]/[runLength], and
 * (2) plain data: a [Routine] carrying two [TimeSlot]s with LifeScript
 * condition strings, evaluated by the already-built [LifeScript] engine.
 *
 * This file REPLACES the former `GymEngine.kt` (deleted). Its `plan()`
 * function keeps the exact same public input/output shapes
 * (`GymInput`/`GymDay`/`ScheduledGym`/`GymRules`/`GymPlan`/`GymAction`/
 * `WindDownBlock`/`GymAlert`/`AlertLevel`/`needsWindDown`/
 * `DEFAULT_WIND_DOWN_EXEMPT_WEEKDAYS`) so every caller and the reproduced
 * 19-scenario test suite (`GymScheduleTest.kt`) needed no changes beyond
 * the rename -- proving this refactor is behavior-preserving, not just
 * "it compiles."
 *
 * ## What's generic vs. what stays gym-specific here
 *
 * The slot-selection/consecutive-cap/greedy-viability MECHANICS now live
 * entirely in `Routine.kt` and are called into, not re-implemented. What
 * remains in this file is genuinely gym-specific and does NOT belong in
 * the generic scheduler:
 * - Building the gym [Routine] + its two [TimeSlot] condition strings
 *   ([gymRoutine], [dayContext]) -- gym's actual boolean-flag vocabulary.
 * - The floor/target/alert-level decision logic (`needed`/`floorNeeded`,
 *   the "high"/"urgent" thresholds and their exact message text). This is
 *   NOT pure scheduling mechanics -- it's a decision/alerting policy layered
 *   on top of [ScheduleResult.viableLeft]. A generic "below floor and
 *   running out of viable days" primitive could plausibly be extracted for
 *   any routine with a floor concept, but the exact alert wording/thresholds
 *   here are gym-specific product copy, not something every routine needs,
 *   so it was deliberately left as a thin wrapper rather than forced into
 *   `Routine.kt`. This matches the user's own framing: the alert wording is
 *   a legitimate small gym-specific remainder, not a design failure.
 * - The sick-week short-circuit (delete scheduled/non-started/non-manual
 *   blocks and bail) -- a gym-specific business rule with no scheduling
 *   content at all.
 * - [needsWindDown]/wind-down-block attachment -- a gym-specific
 *   consequence of a "morning" slot being chosen, not scheduling mechanics.
 *
 * ## Condition-string translation (re-verified against `gym_engine.py`'s
 * actual `slot_for`, not just the design summary)
 *
 * Python's `slot_for(day, r)`:
 * ```python
 * if day.get("gym_blocked"): return None
 * def morning():
 *     if allow_m and day.get("sleep_ok", True) and not day.get("prior_night_blocked"):
 *         return ("05:10", "06:10", "morning")
 *     return None
 * if day.get("day_after_show"): return None
 * if day.get("deadline_heavy"): return morning()          # morning-only, evening never tried
 * if not day.get("evening_blocked"): return (es, ee, "evening")
 * return morning()                                        # evening_blocked -> fall back to morning
 * ```
 * Reading this branch-by-branch: `gym_blocked` and `day_after_show` kill
 * BOTH slots outright; `deadline_heavy` kills the evening slot specifically
 * (forces morning-only, without even checking `evening_blocked`); otherwise
 * evening is tried first and, if blocked, morning is the fallback. The two
 * [TimeSlot] condition strings below encode exactly this by folding every
 * "kills both" flag into both conditions, and every "prefer evening, then
 * morning" behavior into slot declaration order (see [slotFor]'s kdoc on
 * `Routine.kt`: first-declared-slot-whose-condition-is-true, matching
 * `slot_for`'s if/elif short-circuit exactly):
 * - EVENING_CONDITION = `!deadlineHeavy && !eveningBlocked && !gymBlocked && !dayAfterShow`
 *   -- true (and picked, since declared first) exactly when Python's
 *   `if not day_after_show and not gym_blocked` guard clauses pass AND
 *   `deadline_heavy` is false AND `evening_blocked` is false, i.e. exactly
 *   Python's "return evening" branch.
 * - MORNING_CONDITION = `allowMorning && sleepOk && !priorNightBlocked && !gymBlocked && !dayAfterShow`
 *   -- true exactly when Python's `morning()` closure would return non-null
 *   AND the `gym_blocked`/`day_after_show` guards pass. Since morning is
 *   declared second, it is only reached (per [slotFor]'s first-match
 *   semantics) when evening's condition is false -- exactly matching
 *   Python's "evening condition false -> fall to morning()" behavior,
 *   whether that's because `deadline_heavy` was true (morning-only) or
 *   because `evening_blocked` was true (fallback).
 *
 * `gymBlocked`/`dayAfterShow` appearing in BOTH conditions reproduces
 * Python's early `return None` for those two flags: when either is true,
 * neither slot's condition can be true, so [slotFor] returns `null` for
 * that day, exactly as `slot_for` does.
 *
 * `allowMorning` is a per-routine rule (not a per-day flag in the Python
 * input), so it's broadcast into every day's context map from
 * [GymRules.allowMorning] by [dayContext] rather than varying by day.
 */

/** Default weekdays (abbreviated, English, matching Python's `%a`) exempt
 * from a prior-night wind-down block before a morning gym session --
 * exported so any future caller mirroring `runner.py`'s wind-down pruning
 * pass reads the SAME constant [needsWindDown] uses by default, instead of
 * an independently hardcoded value that could silently drift out of sync.
 * Matches `gym_engine.py`'s `DEFAULT_WIND_DOWN_EXEMPT_WEEKDAYS = ["Tue"]`. */
val DEFAULT_WIND_DOWN_EXEMPT_WEEKDAYS: Set<String> = setOf("Tue")

/** One calendar day's gym-relevant calendar/context flags. Matches the
 * fields `slot_for`/`plan()` actually read off the Python `day` dict --
 * `_day()` in `tests/test_gym_engine.py` also sets a `weekday` key, but
 * `plan()`/`slot_for()` never read it (it exists only as test-fixture
 * flavor), so it is deliberately NOT part of this shape. */
data class GymDay(
    val date: LocalDate,
    val gymBlocked: Boolean = false,
    val eveningBlocked: Boolean = false,
    val dayAfterShow: Boolean = false,
    val priorNightBlocked: Boolean = false,
    val deadlineHeavy: Boolean = false,
    val sleepOk: Boolean = true,
)

/** An already-scheduled (or in-progress) gym calendar event. Matches only
 * the fields `plan()` actually reads (`date`, `manual`, `started`) --
 * Python's dicts likely carry `id`/`start`/`end` too (for the calendar
 * event itself), but `plan()` never reads them, so per the same
 * don't-guess-a-schema principle as [GymDay] they're omitted here. */
data class ScheduledGym(
    val date: LocalDate,
    val manual: Boolean = false,
    val started: Boolean = false,
)

/** Rule overrides. Matches `plan()`'s `r = {"target": 4, "floor": 3,
 * "max_consecutive": 2}; r.update(inp.get("rules", {}))` plus every other
 * key `slot_for`/`needs_wind_down` read off `r` (`allow_morning`,
 * `evening_start`, `evening_end`, `wind_down_exempt_weekdays`). Kotlin
 * translation note: Python merges a partial override dict onto hardcoded
 * defaults at call time; a data class with the same defaults achieves the
 * identical effect via `GymRules(target = ...)` / `.copy(...)` at call
 * sites, without needing a separate merge step. */
data class GymRules(
    val target: Int = 4,
    val floor: Int = 3,
    val maxConsecutive: Int = 2,
    val allowMorning: Boolean = true,
    val eveningStart: String = "19:00",
    val eveningEnd: String = "20:00",
    val windDownExemptWeekdays: Set<String> = DEFAULT_WIND_DOWN_EXEMPT_WEEKDAYS,
)

/** `plan()`'s full input shape. Matches the top-level keys `plan()` reads
 * off `inp`: `today`, `sick_until`, `completed_count`, `completed_dates`,
 * `scheduled`, `days`, `rules`. `inp["now"]` is deliberately NOT part of
 * this shape -- `plan()` never reads it (only the dropped `log()` does). */
data class GymInput(
    val today: LocalDate,
    val sickUntil: LocalDate? = null,
    val completedCount: Int = 0,
    val completedDates: List<LocalDate> = emptyList(),
    val scheduled: List<ScheduledGym> = emptyList(),
    val days: List<GymDay> = emptyList(),
    val rules: GymRules = GymRules(),
)

/** One entry of `plan()`'s `out["actions"]` list. Python represents both
 * shapes as one dict distinguished by an `"op"` string key ("create" carries
 * start/end/buffers/kind, "delete" carries only a reason) -- a sealed class
 * makes each op's actual field set explicit and unrepresentable-otherwise,
 * same reasoning `Routine.kt`'s `RoutineStatus` sealed class documents for
 * its own two dict shapes. */
sealed class GymAction {
    data class Create(
        val date: LocalDate,
        val start: String,
        val end: String,
        val kind: String,
        val bufferBefore: Int = 10,
        val bufferAfter: Int = 10,
    ) : GymAction()

    data class Delete(val date: LocalDate, val reason: String) : GymAction()
}

/** One entry of `plan()`'s `out["wind_down"]` list. Matches
 * `{"date": ..., "start": "21:00", "end": "23:00"}` -- start/end are always
 * these two literals in the Python source (not configurable), so they're
 * kept as plain defaulted fields rather than threaded through [GymRules]. */
data class WindDownBlock(val date: LocalDate, val start: String = "21:00", val end: String = "23:00")

/** Mirrors `out["alert"]["level"]`'s three string values (`"none"`,
 * `"high"`, `"urgent"`) as a closed enum instead of a raw string, same
 * unrepresentable-invalid-state reasoning as `Routine.kt`'s `Anchor`. */
enum class AlertLevel { NONE, HIGH, URGENT }

/** Mirrors `out["alert"]` (`{"level": ..., "text": ...}`). */
data class GymAlert(val level: AlertLevel = AlertLevel.NONE, val text: String = "")

/** `plan()`'s full return shape: `{"actions": [...], "wind_down": [...],
 * "alert": {...}, "summary": ...}`. */
data class GymPlan(
    val actions: List<GymAction> = emptyList(),
    val windDown: List<WindDownBlock> = emptyList(),
    val alert: GymAlert = GymAlert(),
    val summary: String = "",
)

/**
 * Ports `needs_wind_down(gym_date, r)`: whether the night before a morning
 * gym session on [gymDate] needs a wind-down block, i.e. whether the prior
 * day's abbreviated weekday name is NOT in [exemptWeekdays].
 *
 * Kotlin translation note: Python's `prior.strftime("%a")` (locale-default
 * abbreviated weekday, e.g. "Tue") is ported as
 * `DayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)`, which
 * produces the identical three-letter abbreviations for every day of the
 * week -- pinning `Locale.ENGLISH` explicitly (rather than the platform
 * default) is deliberate so this never silently depends on device locale,
 * unlike Python's locale-dependent `strftime`.
 */
fun needsWindDown(gymDate: LocalDate, exemptWeekdays: Set<String> = DEFAULT_WIND_DOWN_EXEMPT_WEEKDAYS): Boolean {
    val prior = gymDate.minusDays(1)
    val priorAbbrev = prior.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
    return priorAbbrev !in exemptWeekdays
}

// See this file's top-level kdoc "Condition-string translation" section for
// the full re-verification of these two strings against `slot_for`.
private const val EVENING_CONDITION = "!deadlineHeavy && !eveningBlocked && !gymBlocked && !dayAfterShow"
private const val MORNING_CONDITION = "allowMorning && sleepOk && !priorNightBlocked && !gymBlocked && !dayAfterShow"

/** Builds "the gym routine" as data: anchor=WINDOW (gym's actual "N times
 * per rolling 7-day window" semantics), times=[GymRules.target], and the
 * two [TimeSlot]s in evening-then-morning declared order (see this file's
 * kdoc for why that order matters). */
private fun gymRoutine(rules: GymRules): Routine = Routine(
    id = "gym",
    times = rules.target,
    perDays = 7,
    anchor = Anchor.WINDOW,
    slots = listOf(
        TimeSlot(start = rules.eveningStart, end = rules.eveningEnd, kind = "evening", condition = EVENING_CONDITION),
        TimeSlot(start = "05:10", end = "06:10", kind = "morning", condition = MORNING_CONDITION),
    ),
)

/** Builds one [GymDay]'s LifeScript variable-context map -- every name the
 * two condition strings above reference. [GymRules.allowMorning] is a
 * per-routine rule, not a per-day flag in the original Python input, so
 * it's broadcast into every day's context here rather than varying by day. */
private fun dayContext(day: GymDay, rules: GymRules): Map<String, Any> = mapOf(
    "gymBlocked" to day.gymBlocked,
    "eveningBlocked" to day.eveningBlocked,
    "dayAfterShow" to day.dayAfterShow,
    "priorNightBlocked" to day.priorNightBlocked,
    "deadlineHeavy" to day.deadlineHeavy,
    "sleepOk" to day.sleepOk,
    "allowMorning" to rules.allowMorning,
)

/**
 * Ports `plan(inp)` in full: sick-week short-circuit, then delegates the
 * slot-selection/consecutive-day-cap greedy pick and the `viable_left`
 * simulation entirely to `Routine.kt`'s [scheduleRoutine] (see this file's
 * top-level kdoc for the generic/gym-specific split), then applies gym's
 * own floor/target/alert-level decision logic and wind-down attachment on
 * top of the result.
 */
fun plan(input: GymInput): GymPlan {
    val rules = input.rules

    val sickUntil = input.sickUntil
    if (sickUntil != null && input.today <= sickUntil) {
        val deleteActions = input.scheduled
            .filter { !it.manual && !it.started }
            .map { GymAction.Delete(it.date, "sick / skip week") }
        return GymPlan(actions = deleteActions, summary = "sick week — paused")
    }

    val completed = input.completedCount
    val scheduled = input.scheduled
    val have = completed + scheduled.size
    val target = rules.target
    val floor = rules.floor
    val needed = maxOf(0, target - have)
    val floorNeeded = maxOf(0, floor - have)
    val schedDates = scheduled.map { it.date }.toSet()
    // days he ACTUALLY trained recently -- the consecutive cap must count
    // these, not just scheduled blocks, or we book a real 3rd straight day
    // after the fact (completed sessions aren't in `scheduled` anymore).
    val doneDates = input.completedDates.toSet()
    val busyDates = schedDates + doneDates

    val routine = gymRoutine(rules)
    val candidateDays = input.days.map { CandidateDay(it.date, dayContext(it, rules)) }

    val result = scheduleRoutine(
        routine = routine,
        candidateDays = candidateDays,
        needed = needed,
        maxConsecutive = rules.maxConsecutive,
        excludeDates = busyDates,
        busyDates = busyDates,
    )

    val actions = mutableListOf<GymAction>()
    val windDown = mutableListOf<WindDownBlock>()
    for (c in result.chosen) {
        actions.add(GymAction.Create(date = c.date, start = c.slot.start, end = c.slot.end, kind = c.slot.kind))
        if (c.slot.kind == "morning" && needsWindDown(c.date, rules.windDownExemptWeekdays)) {
            windDown.add(WindDownBlock(date = c.date.minusDays(1)))
        }
    }

    val viableLeft = result.viableLeft

    val alert = when {
        floorNeeded > viableLeft -> GymAlert(
            AlertLevel.HIGH,
            "Heads up: set to miss ${floor}x this week — only $viableLeft viable day(s) left.",
        )
        floorNeeded > 0 && floorNeeded == viableLeft -> GymAlert(
            AlertLevel.URGENT,
            "GO TODAY — last viable day to hit ${floor}x this week.",
        )
        else -> GymAlert(AlertLevel.NONE)
    }

    val summary = "have=$have (done=$completed+sched=${scheduled.size}) " +
        "target=$target needed=$needed " +
        "chose=${result.chosen.map { it.date }} viable_left=$viableLeft"

    return GymPlan(actions = actions, windDown = windDown, alert = alert, summary = summary)
}
