package com.lifeops.briefing.ui

import com.lifeops.briefing.data.RoutineEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/** [RoutineEntity.cadenceLabel] is pure -- no Compose/Android dependency --
 * so plain JUnit is enough, same reasoning `RoutineTest.kt` gives for
 * `Routine.kt`'s own pure functions. */
class RoutineCadenceLabelTest {

    private fun routine(times: Int, perDays: Int, anchor: String) = RoutineEntity(
        id = "test",
        title = "Test",
        timesPerWindow = times,
        perDays = perDays,
        anchor = anchor,
        onDue = RoutineEntity.ON_DUE_NOTIFY,
    )

    @Test
    fun window_onceAWeek() {
        assertEquals(
            "Once a week",
            routine(1, 7, RoutineEntity.ANCHOR_WINDOW).cadenceLabel(),
        )
    }

    @Test
    fun window_onceADay() {
        assertEquals(
            "Once a day",
            routine(1, 1, RoutineEntity.ANCHOR_WINDOW).cadenceLabel(),
        )
    }

    @Test
    fun window_multipleTimesAWeek() {
        assertEquals(
            "4x a week",
            routine(4, 7, RoutineEntity.ANCHOR_WINDOW).cadenceLabel(),
        )
    }

    @Test
    fun window_multipleTimesADay() {
        assertEquals(
            "3x a day",
            routine(3, 1, RoutineEntity.ANCHOR_WINDOW).cadenceLabel(),
        )
    }

    @Test
    fun window_arbitraryPeriod() {
        assertEquals(
            "2x every 10 days",
            routine(2, 10, RoutineEntity.ANCHOR_WINDOW).cadenceLabel(),
        )
    }

    @Test
    fun sinceLast_everyDay() {
        assertEquals(
            "Every day",
            routine(1, 1, RoutineEntity.ANCHOR_SINCE_LAST).cadenceLabel(),
        )
    }

    @Test
    fun sinceLast_everyWeek() {
        assertEquals(
            "Every week",
            routine(1, 7, RoutineEntity.ANCHOR_SINCE_LAST).cadenceLabel(),
        )
    }

    @Test
    fun sinceLast_arbitraryPeriod_ignoresTimesPerWindow() {
        // status() never reads timesPerWindow for anchor=since_last (see
        // Routine.kt's status()), so the label must not surface it either --
        // otherwise it would describe a number that doesn't drive the
        // actual due-check.
        assertEquals(
            "Every 14 days",
            routine(5, 14, RoutineEntity.ANCHOR_SINCE_LAST).cadenceLabel(),
        )
    }
}
