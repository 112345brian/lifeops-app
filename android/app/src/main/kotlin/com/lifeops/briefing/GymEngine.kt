package com.lifeops.briefing

import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * Kotlin port of `lifeops/engines/gym_engine.py`'s `plan()` -- the pure
 * slot-selection/consecutive-day-cap/greedy-viability decision function for
 * scheduling gym sessions. Part of the on-device migration sequence (see
 * docs/lifeops_capability_todo.md's "On-Device Migration" section, step 3:
 * "all pure `plan()` functions; the I/O (logging, file reads) each does
 * today is trivially dropped/replaced.").
 *
 * `log()`/`main()` from the Python module do file I/O (writing to
 * `gym_log.jsonl`, reading/writing JSON files via argv) and are
 * deliberately NOT ported -- `plan()` alone is the pure decision function.
 *
 * IMPORTANT: unlike `chore_engine.py`/`social_engine.py` (per the todo doc),
 * `gym_engine.py` does NOT call into `lifeops/routine.py`/`Routine.kt`'s
 * shared due-check primitive at all -- confirmed by reading the Python
 * source in full: its only import is `from .. import state_store` (used
 * solely by the dropped `log()` function). The todo doc itself notes why:
 * "its 'due' concept is inseparable from slot-picking/consecutive-day-cap,
 * not a simple cadence check" -- `plan()` computes `needed`/`floor_needed`
 * inline from `target`/`floor` rule overrides rather than reusing
 * `Routine`'s WINDOW-anchor math. This port therefore has zero dependency
 * on `Routine.kt` and does not import it -- forcing one would not match
 * the Python source.
 *
 * NOT wired into any widget/worker/persistence path yet -- standalone,
 * unused-so-far port, same as `Routine.kt`.
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

/** One viable (date, slot) candidate -- Python builds a plain `(date, slot)`
 * tuple list (`cand`); a small local data class here reads more clearly
 * than `Pair<LocalDate, Triple<String, String, String>>` at every use site. */
private data class Candidate(val date: LocalDate, val start: String, val end: String, val kind: String)

/**
 * Ports `slot_for(day, r)`. Returns `null` when no slot is viable that day
 * (Python returns `None`).
 *
 * Kotlin translation note: Python's nested nullary `morning()` closure
 * (capturing `day`/`r`) is ported as a local function capturing the same
 * two parameters -- behaviorally identical, just Kotlin's equivalent
 * closure syntax.
 */
private fun slotFor(day: GymDay, rules: GymRules): Candidate? {
    if (day.gymBlocked) return null

    fun morning(): Candidate? =
        if (rules.allowMorning && day.sleepOk && !day.priorNightBlocked) {
            Candidate(day.date, "05:10", "06:10", "morning")
        } else {
            null
        }

    if (day.dayAfterShow) return null
    // Hard-deadline day: coursework outranks the gym EVENING -- morning only.
    if (day.deadlineHeavy) return morning()
    if (!day.eveningBlocked) return Candidate(day.date, rules.eveningStart, rules.eveningEnd, "evening")
    return morning()
}

/**
 * Ports `run_length(date_str, busy)`: how many consecutive calendar days
 * (including `date` itself) around `date` are present in `busy`.
 */
private fun runLength(date: LocalDate, busy: Set<LocalDate>): Int {
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

/**
 * Ports `plan(inp)` in full: sick-week short-circuit, the greedy
 * slot-selection loop under the consecutive-day cap, wind-down attachment,
 * and the `viable_left` greedy simulation that drives the floor alert.
 *
 * Kotlin translation notes:
 * - Python builds `busy`/`sim_busy` as plain `set`s mutated in a loop;
 *   ported as `mutableSetOf` for the same reason (the loop's own cap check
 *   for date N needs to see dates 1..N-1 already added within the SAME
 *   loop, so an immutable-and-rebuilt-each-iteration set would be wrong,
 *   not just slower).
 * - `cand.sort(key=lambda c: c[0])` sorts by date only; `sortedBy { it.date }`
 *   is a stable sort in Kotlin (same guarantee `list.sort()` gives in
 *   Python), so candidates with equal dates (impossible here since `days`
 *   entries are date-keyed, but noted for completeness) would preserve
 *   input order identically either way.
 * - The `viable_left` simulation is ported exactly as structured in the
 *   Python source's own extensive comment: iterate `cand` in the same
 *   sorted order, greedily add to a COPY of `busy` (`simBusy`), so that
 *   candidates adjacent to each OTHER (not just to the frozen `busy` set)
 *   correctly cap each other -- this is the exact regression the Python
 *   test `test_viable_left_checks_candidates_against_each_other_not_just_busy`
 *   locks down, and the Kotlin port preserves the identical loop shape
 *   specifically so that guarantee carries over.
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

    val cand = input.days
        .asSequence()
        .filter { it.date !in schedDates && it.date !in doneDates }
        .mapNotNull { slotFor(it, rules) }
        .sortedBy { it.date }
        .toList()

    val chosen = mutableListOf<Candidate>()
    val busy = (schedDates + doneDates).toMutableSet()
    for (c in cand) {
        if (chosen.size >= needed) break
        if (runLength(c.date, busy + c.date) > rules.maxConsecutive) continue
        chosen.add(c)
        busy.add(c.date)
    }

    val actions = mutableListOf<GymAction>()
    val windDown = mutableListOf<WindDownBlock>()
    for (c in chosen) {
        actions.add(GymAction.Create(date = c.date, start = c.start, end = c.end, kind = c.kind))
        if (c.kind == "morning" && needsWindDown(c.date, rules.windDownExemptWeekdays)) {
            windDown.add(WindDownBlock(date = c.date.minusDays(1)))
        }
    }

    // viable = chosen days + a greedy simulation of how many of the
    // REMAINING candidates could actually be booked together under the
    // consecutive-day cap -- see the kdoc above and the Python source's own
    // comment for why this must simulate against each other, not just the
    // frozen `busy` set.
    val simBusy = busy.toMutableSet()
    var viableLeft = chosen.size
    for (c in cand) {
        if (c.date in simBusy) continue
        if (runLength(c.date, simBusy + c.date) <= rules.maxConsecutive) {
            simBusy.add(c.date)
            viableLeft++
        }
    }

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
        "chose=${chosen.map { it.date }} viable_left=$viableLeft"

    return GymPlan(actions = actions, windDown = windDown, alert = alert, summary = summary)
}
