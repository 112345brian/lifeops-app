package com.lifeops.briefing

import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Kotlin port of the PURE core of `lifeops/runner.py`'s `run_meal` --
 * per docs/lifeops_capability_todo.md's "On-Device Migration" section:
 * "`runner.py`'s meal-prep due-check (`routine_store.load_routine("meal")` +
 * simple date math) is a small pure core buried inside a larger function
 * that's otherwise genuinely I/O-entangled (FlowSavvy create/delete, ntfy
 * polling for skip signals) -- extract the core, redesign the rest."
 *
 * Extracted here, following `GymSchedule.kt`'s convention of re-deriving a
 * domain's due-check as a [Routine] rather than bespoke code:
 * - [mealRoutine] -- the meal cadence as data (`times=1, per_days=6,
 *   anchor=since_last`), matching `routine_store.py`'s own
 *   `_DEFAULTS["meal"]` exactly ("Matches today's hardcoded values exactly,
 *   so loading with nothing persisted yet reproduces current behavior
 *   bit-for-bit").
 * - [isMealDue] -- ports `run_meal`'s
 *   `routine_status(meal_routine, [last] if last else [], now)["due"]` via
 *   `Routine.kt`'s existing [status] function, rather than reimplementing
 *   the since_last due-check.
 * - [mealPlanDates] -- ports `run_meal`'s `d0`/`d3`/`d4` date arithmetic and
 *   the four `canBeStartedAt`/`dueDateTime` timestamps it builds for the
 *   "Groceries" and "Meal prep" tasks.
 * - [planMeal] -- combines the two into one entry point mirroring
 *   `run_meal`'s due-gate: returns [MealSchedule.NotDue] when not due
 *   (matching `if not due: ... return`), or [MealSchedule.Due] with the
 *   computed dates when it is.
 *
 * Deliberately OUT OF SCOPE, left for the WorkManager-wiring task per the
 * capability doc's own framing ("extract the core, redesign the rest"):
 * - The "have leftovers, skip this week" mechanism (`ntfy.poll` for a
 *   `meal-skip` message, and the matching FlowSavvy task deletion + history
 *   append) -- genuinely I/O-entangled, no pure core to extract.
 * - The "already planned" guard (`fs.list_items(itemType="task",
 *   query="Meal prep", completed=False)`) -- a live FlowSavvy read, not pure
 *   due-check math.
 * - Actually calling `FlowSavvyClient.createTask` with the computed dates,
 *   and the `blockedByIds` dependency link between the two created tasks.
 * - The "meal-prep week" alert/notification.
 *
 * NOT wired into any widget/worker/persistence path yet.
 */

/** The meal routine as data, matching `routine_store.py`'s
 * `_DEFAULTS["meal"] = {"times": 1, "per_days": 6, "anchor": "since_last"}`
 * exactly. `routine_store.py`'s persisted-override layer is NOT ported here
 * (Room/persistence is explicitly another task's scope per this task's
 * brief) -- this always returns the hardcoded default, same as a fresh
 * install with nothing persisted yet on the Python side. */
fun mealRoutine(): Routine = Routine(id = "meal", times = 1, perDays = 6, anchor = Anchor.SINCE_LAST)

/**
 * Ports `run_meal`'s due-check:
 * `routine_status(meal_routine, [last] if last else [], now)["due"]`.
 *
 * [lastCompletedTimestamp] mirrors `history.last("meal")`'s return shape
 * exactly -- an ISO-8601 local-datetime string, or `null` when meal has
 * never been logged (Python's falsy-`None` case, ported as Kotlin `null`
 * rather than an empty string, since `history.last` returns `None`, never
 * `""`, for "no prior completion"). Delegates the actual since-last math to
 * `Routine.kt`'s [status] (the timestamp-list-based function `run_meal`
 * itself calls, not the precomputed-days-since-last [statusSinceLast]) so
 * this can't drift from the shared primitive.
 */
fun isMealDue(lastCompletedTimestamp: String?, now: LocalDateTime, routine: Routine = mealRoutine()): Boolean {
    val eventTimestamps = if (lastCompletedTimestamp != null) listOf(lastCompletedTimestamp) else emptyList()
    return status(routine, eventTimestamps, now).due
}

/** The four scheduling timestamps `run_meal` computes for its two created
 * tasks. Matches the Python source's `d0`/`d3`/`d4` local variables and how
 * each feeds `canBeStartedAt`/`dueDateTime` for "Groceries" (`canBeStartedAt
 * = d0T00:00:00`, `dueDateTime = d3T19:00:00`) and "Meal prep"
 * (`canBeStartedAt = d3T00:00:00`, `dueDateTime = d4T19:00:00`, and
 * `blockedByIds` on Groceries' task id -- the dependency link itself is an
 * I/O-entangled FlowSavvy write, out of scope here per this file's kdoc). */
data class MealPlanDates(
    val groceriesCanStartAt: LocalDateTime,
    val groceriesDueAt: LocalDateTime,
    val mealPrepCanStartAt: LocalDateTime,
    val mealPrepDueAt: LocalDateTime,
)

/**
 * Ports `run_meal`'s date arithmetic exactly:
 * ```python
 * d0 = now.date().isoformat()
 * d3 = (now.date() + datetime.timedelta(days=3)).isoformat()
 * d4 = (now.date() + datetime.timedelta(days=4)).isoformat()
 * ```
 * plus the four `f"{dN}T..."` timestamps built from them.
 */
fun mealPlanDates(today: LocalDate): MealPlanDates {
    val d0 = today
    val d3 = today.plusDays(3)
    val d4 = today.plusDays(4)
    return MealPlanDates(
        groceriesCanStartAt = d0.atStartOfDay(),
        groceriesDueAt = d3.atTime(19, 0),
        mealPrepCanStartAt = d3.atStartOfDay(),
        mealPrepDueAt = d4.atTime(19, 0),
    )
}

/** Result of [planMeal]: mirrors `run_meal`'s `if not due: ... return`
 * early exit ([NotDue]) versus falling through to build the two tasks'
 * dates ([Due]). Does not distinguish the Python source's further "skipped"
 * vs "already planned" vs "created" outcomes -- those all depend on live
 * FlowSavvy state, out of scope for this pure due-check core (see this
 * file's top-level kdoc). */
sealed class MealSchedule {
    object NotDue : MealSchedule()
    data class Due(val dates: MealPlanDates) : MealSchedule()
}

/** Combines [isMealDue] and [mealPlanDates] into one entry point mirroring
 * `run_meal`'s due-gated shape. */
fun planMeal(lastCompletedTimestamp: String?, now: LocalDateTime, routine: Routine = mealRoutine()): MealSchedule {
    if (!isMealDue(lastCompletedTimestamp, now, routine)) return MealSchedule.NotDue
    return MealSchedule.Due(mealPlanDates(now.toLocalDate()))
}
