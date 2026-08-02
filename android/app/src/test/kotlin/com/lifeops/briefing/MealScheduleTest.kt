package com.lifeops.briefing

import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reproduces the pure due-check scenarios from `tests/test_meal_routine.py`
 * (whose own docstring notes "no dedicated test file existed for this
 * domain before the v1 routine consolidation") against `MealSchedule.kt`'s
 * [isMealDue]/[planMeal] -- specifically the three cases that are pure
 * due-check math, not the FlowSavvy-create/ntfy-skip-poll scenarios in that
 * same Python file (`test_meal_prep_is_blocked_by_groceries_task_id`,
 * `test_already_planned_is_a_noop`,
 * `test_skip_deletes_open_lifeops_meal_tasks_and_counts_as_handled`), which
 * are genuinely I/O-entangled and explicitly out of scope for this
 * extracted core (see `MealSchedule.kt`'s top-level kdoc).
 *
 * Also covers [mealPlanDates]' date arithmetic directly, ported from
 * `run_meal`'s `d0`/`d3`/`d4` computation -- not separately tested in the
 * Python suite (the FlowSavvy-create tests there only assert task titles
 * and the `blockedByIds` link, not the actual date values), so these
 * assertions are new coverage for logic that already existed unverified.
 *
 * No Robolectric needed: `MealSchedule.kt` has zero Android-framework
 * dependency (pure java.time + `Routine.kt`), same as `RoutineTest.kt`/
 * `GymScheduleTest.kt`.
 */
class MealScheduleTest {

    // tests/test_meal_routine.py's NOW = datetime.datetime(2026, 7, 8, 9, 0, 0)
    private val NOW: LocalDateTime = LocalDateTime.of(2026, 7, 8, 9, 0, 0)

    // test_not_due_when_recently_handled: last = 2026-07-05T09:00:00 (3 days ago)
    @Test
    fun notDueWhenRecentlyHandled() {
        assertFalse(isMealDue("2026-07-05T09:00:00", NOW))
        assertEquals(MealSchedule.NotDue, planMeal("2026-07-05T09:00:00", NOW))
    }

    // test_due_at_six_days_creates_groceries_and_meal_prep: last = 2026-07-02T09:00:00 (6 days ago)
    @Test
    fun dueAtSixDays() {
        assertTrue(isMealDue("2026-07-02T09:00:00", NOW))
        assertTrue(planMeal("2026-07-02T09:00:00", NOW) is MealSchedule.Due)
    }

    // test_due_when_never_logged: last = null
    @Test
    fun dueWhenNeverLogged() {
        assertTrue(isMealDue(null, NOW))
        assertTrue(planMeal(null, NOW) is MealSchedule.Due)
    }

    // Boundary just under the 6-day threshold: 5 days ago is not yet due,
    // matching Routine.kt's dueSinceLast semantics (days_since_last >= per_days).
    @Test
    fun notDueAtFiveDays() {
        assertFalse(isMealDue("2026-07-03T09:00:00", NOW))
    }

    // Ports run_meal's d0/d3/d4 arithmetic: d0 = today, d3 = today+3, d4 = today+4,
    // with Groceries due at d3T19:00 (can-start d0T00:00) and Meal prep due at
    // d4T19:00 (can-start d3T00:00).
    @Test
    fun mealPlanDatesMatchesRunMealsArithmetic() {
        val dates = mealPlanDates(LocalDate.of(2026, 7, 8))

        assertEquals(LocalDateTime.of(2026, 7, 8, 0, 0), dates.groceriesCanStartAt)
        assertEquals(LocalDateTime.of(2026, 7, 11, 19, 0), dates.groceriesDueAt)
        assertEquals(LocalDateTime.of(2026, 7, 11, 0, 0), dates.mealPrepCanStartAt)
        assertEquals(LocalDateTime.of(2026, 7, 12, 19, 0), dates.mealPrepDueAt)
    }

    @Test
    fun planMealDue_carriesTodaysDates() {
        val plan = planMeal(null, NOW)

        require(plan is MealSchedule.Due)
        assertEquals(mealPlanDates(NOW.toLocalDate()), plan.dates)
    }
}
