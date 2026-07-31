package com.lifeops.briefing

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reproduces `tests/test_gym_engine.py`'s (and the former `GymEngineTest.kt`'s)
 * 19 test cases, unchanged in assertion, against the NEW data-driven gym
 * (`GymSchedule.kt`'s `plan()`, built on a [Routine] + two [TimeSlot]s
 * evaluated by `Routine.kt`'s generic [scheduleRoutine]/[slotFor], instead
 * of gym-specific hand-rolled slot/cap logic). Every type name and the
 * `plan()` entry point are unchanged from the old `GymEngine.kt`, so this
 * file is a faithful behavior-preservation check, not a rewrite: same
 * inputs, same expected outputs, different implementation underneath.
 *
 * No Robolectric needed: `GymSchedule.kt` has zero Android-framework
 * dependency (pure java.time + `LifeScript`), so plain JUnit is enough,
 * same as `RoutineTest.kt`/`RoutineSchedulingTest.kt`.
 */
class GymScheduleTest {

    private val MON: LocalDate = LocalDate.of(2026, 7, 6)
    private val defaultRules = GymRules(allowMorning = true, eveningStart = "19:00", eveningEnd = "20:00", maxConsecutive = 2)

    private fun day(
        date: LocalDate,
        eveningBlocked: Boolean = false,
        dayAfterShow: Boolean = false,
        priorNightBlocked: Boolean = false,
        deadlineHeavy: Boolean = false,
        sleepOk: Boolean = true,
    ) = GymDay(
        date = date,
        eveningBlocked = eveningBlocked,
        dayAfterShow = dayAfterShow,
        priorNightBlocked = priorNightBlocked,
        deadlineHeavy = deadlineHeavy,
        sleepOk = sleepOk,
    )

    private fun dates(n: Int, start: LocalDate = MON): List<LocalDate> =
        (0 until n).map { start.plusDays(it.toLong()) }

    private fun input(
        completed: Int = 0,
        scheduled: List<ScheduledGym> = emptyList(),
        days: List<GymDay> = emptyList(),
        sickUntil: LocalDate? = null,
        rules: GymRules = defaultRules,
        completedDates: List<LocalDate> = emptyList(),
    ) = GymInput(
        today = MON,
        sickUntil = sickUntil,
        completedCount = completed,
        completedDates = completedDates,
        scheduled = scheduled,
        days = days,
        rules = rules,
    )

    private fun creates(out: GymPlan): List<GymAction.Create> = out.actions.filterIsInstance<GymAction.Create>()
    private fun deletes(out: GymPlan): List<GymAction.Delete> = out.actions.filterIsInstance<GymAction.Delete>()

    // Corresponds to test_creates_to_hit_target
    @Test
    fun createsToHitTarget() {
        val days = dates(7).map { day(it) }
        val out = plan(input(completed = 0, days = days))
        assertEquals(4, creates(out).size) // target=4, have=0
    }

    // Corresponds to test_no_creates_when_target_met
    @Test
    fun noCreatesWhenTargetMet() {
        val days = dates(7).map { day(it) }
        val sched = dates(7).take(4).map { ScheduledGym(date = it, manual = false, started = false) }
        val out = plan(input(completed = 0, scheduled = sched, days = days))
        assertTrue(creates(out).isEmpty())
    }

    // Corresponds to test_completed_count_reduces_creates
    @Test
    fun completedCountReducesCreates() {
        val days = dates(7).map { day(it) }
        val out = plan(input(completed = 3, days = days))
        assertEquals(1, creates(out).size) // need 1 more to hit target=4
    }

    // Corresponds to test_sick_week_deletes_scheduled
    @Test
    fun sickWeekDeletesScheduled() {
        val days = dates(7).map { day(it) }
        val sched = listOf(ScheduledGym(date = dates(7)[1], manual = false, started = false))
        val out = plan(input(scheduled = sched, days = days, sickUntil = dates(7).last()))
        assertTrue(deletes(out).isNotEmpty())
        assertTrue(creates(out).isEmpty())
    }

    // Corresponds to test_sick_week_skips_started_blocks
    @Test
    fun sickWeekSkipsStartedBlocks() {
        val days = dates(7).map { day(it) }
        val sched = listOf(ScheduledGym(date = MON, manual = false, started = true))
        val out = plan(input(scheduled = sched, days = days, sickUntil = dates(7).last()))
        assertFalse(deletes(out).any { it.date == MON })
    }

    // Corresponds to test_evening_blocked_falls_back_to_morning
    @Test
    fun eveningBlockedFallsBackToMorning() {
        val d7 = dates(7)
        val days = listOf(day(d7[0], eveningBlocked = true)) + d7.drop(1).map { day(it) }
        val out = plan(input(completed = 3, days = days))
        val c = creates(out)
        assertEquals(1, c.size)
        assertEquals("morning", c[0].kind)
    }

    // Corresponds to test_deadline_heavy_forces_morning
    @Test
    fun deadlineHeavyForcesMorning() {
        // all days have deadline_heavy -- only morning slots viable
        val days = dates(7).map { day(it, deadlineHeavy = true) }
        val out = plan(input(completed = 3, days = days))
        assertTrue(creates(out).all { it.kind == "morning" })
    }

    // Corresponds to test_morning_gym_creates_prior_night_wind_down
    @Test
    fun morningGymCreatesPriorNightWindDown() {
        val out = plan(input(completed = 3, days = listOf(day(LocalDate.of(2026, 7, 7), deadlineHeavy = true))))
        assertEquals(listOf(WindDownBlock(LocalDate.of(2026, 7, 6), "21:00", "23:00")), out.windDown)
    }

    // Corresponds to test_wednesday_morning_gym_does_not_create_tuesday_wind_down
    @Test
    fun wednesdayMorningGymDoesNotCreateTuesdayWindDown() {
        val out = plan(input(completed = 3, days = listOf(day(LocalDate.of(2026, 7, 8), deadlineHeavy = true))))
        assertTrue(out.windDown.isEmpty())
    }

    // Corresponds to test_day_after_show_skipped
    @Test
    fun dayAfterShowSkipped() {
        // day 1 is after a show -> skip it, day 2 is clean
        val d7 = dates(7)
        val days = listOf(day(d7[0]), day(d7[1], dayAfterShow = true)) + d7.drop(2).map { day(it) }
        val out = plan(input(completed = 3, days = days))
        assertFalse(creates(out).any { it.date == d7[1] })
    }

    // Corresponds to test_consecutive_cap_respected
    @Test
    fun consecutiveCapRespected() {
        // fill 4 consecutive days -- should cap at 2 in a row
        val days = dates(7).map { day(it) }
        val out = plan(input(completed = 0, days = days))
        val chosen = creates(out).map { it.date }.sorted()
        for (i in 0..chosen.size - 3) {
            val a = chosen[i]
            val b = chosen[i + 1]
            val c = chosen[i + 2]
            val threeInARow = a.plusDays(1) == b && b.plusDays(1) == c
            assertFalse("3 consecutive days chosen", threeInARow)
        }
    }

    // Corresponds to test_no_morning_when_suppressed
    @Test
    fun noMorningWhenSuppressed() {
        val rules = defaultRules.copy(allowMorning = false)
        val days = dates(7).map { day(it, eveningBlocked = true) } // all evenings blocked, no morning
        val out = plan(input(completed = 3, days = days, rules = rules))
        assertTrue(creates(out).isEmpty()) // nowhere to go
    }

    // Corresponds to test_floor_alert_when_cant_hit_floor
    @Test
    fun floorAlertWhenCantHitFloor() {
        // only 1 viable day left, need 3 to hit floor
        val d7 = dates(7)
        val days = listOf(day(d7[0])) + d7.drop(1).map { day(it, eveningBlocked = true) }
        val rules = defaultRules.copy(allowMorning = false)
        val out = plan(input(completed = 0, days = days, rules = rules))
        assertTrue(out.alert.level == AlertLevel.HIGH || out.alert.level == AlertLevel.URGENT)
    }

    // Corresponds to test_uses_configured_evening_time
    @Test
    fun usesConfiguredEveningTime() {
        val rules = defaultRules.copy(eveningStart = "18:00", eveningEnd = "19:00")
        val days = dates(7).map { day(it) }
        val out = plan(input(completed = 3, days = days, rules = rules))
        assertEquals("18:00", creates(out)[0].start)
    }

    // Corresponds to test_consecutive_cap_counts_completed_history
    @Test
    fun consecutiveCapCountsCompletedHistory() {
        // trained Sat+Sun (completed, not scheduled) -- Monday would be a REAL
        // 3rd consecutive day and must be skipped even though nothing is
        // "scheduled"
        val d7 = dates(7)
        val sat = MON.minusDays(2)
        val sun = MON.minusDays(1)
        val out = plan(input(completed = 2, days = d7.map { day(it) }, completedDates = listOf(sat, sun)))
        assertFalse("booked a real 3rd straight day", creates(out).any { it.date == MON })
    }

    // Corresponds to test_completed_day_not_rescheduled
    @Test
    fun completedDayNotRescheduled() {
        val out = plan(input(completed = 1, days = dates(7).map { day(it) }, completedDates = listOf(MON)))
        assertFalse(creates(out).any { it.date == MON })
    }

    // Corresponds to test_viable_left_excludes_cap_rejected_days
    @Test
    fun viableLeftExcludesCapRejectedDays() {
        // 3 open days but they're consecutive with completed Sat+Sun around
        // them: viable count must reflect the cap, not raw candidate count
        val d7 = dates(7)
        val days = listOf(day(d7[0]), day(d7[1])) + d7.drop(2).map { day(it, eveningBlocked = true) }
        val rules = defaultRules.copy(allowMorning = false, maxConsecutive = 1)
        val out = plan(input(completed = 0, days = days, rules = rules))
        // with max_consecutive=1 only one of the two adjacent days is usable;
        // floor=3, so this must be a "set to miss" high alert with viable_left=1
        assertEquals(AlertLevel.HIGH, out.alert.level)
        assertTrue(out.alert.text.contains("only 1 viable"))
    }

    // Corresponds to test_viable_left_checks_candidates_against_each_other_not_just_busy
    @Test
    fun viableLeftChecksCandidatesAgainstEachOtherNotJustBusy() {
        // regression: when `needed=0` the booking loop never runs, so `busy`
        // stays frozen at its starting value -- the OLD viable_left checked
        // every remaining candidate against that frozen `busy` independently,
        // so three mutually-adjacent-to-EACH-OTHER (but not to `busy`) days
        // all looked individually viable even though max_consecutive=1 means
        // at most 2 of them could ever actually be booked together. This
        // property now lives in `Routine.kt`'s [scheduleRoutine] and is
        // re-proven generically by `RoutineSchedulingTest.kt`; this test
        // proves the gym-specific wiring still exercises it correctly.
        val days = listOf("2026-07-06", "2026-07-09", "2026-07-10", "2026-07-11")
            .map { day(LocalDate.parse(it)) }
        val rules = defaultRules.copy(maxConsecutive = 1, target = 0) // needed=0 -> the chosen-loop never runs
        val out = plan(input(completed = 0, days = days, rules = rules))
        assertTrue(out.summary, out.summary.contains("viable_left=3"))
    }

    // Corresponds to test_needs_wind_down_default_matches_exported_constant
    @Test
    fun needsWindDownDefaultMatchesExportedConstant() {
        // 2026-07-08 is a Wednesday; the day before (Tuesday) is exempt by
        // default, so no wind-down block is needed for a Wednesday gym day.
        assertFalse(needsWindDown(LocalDate.of(2026, 7, 8)))
        // 2026-07-09 is a Thursday; the day before (Wednesday) is not exempt.
        assertTrue(needsWindDown(LocalDate.of(2026, 7, 9)))
        assertEquals(setOf("Tue"), DEFAULT_WIND_DOWN_EXEMPT_WEEKDAYS)
    }
}
