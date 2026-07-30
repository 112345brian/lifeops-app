package com.lifeops.briefing

import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Kotlin port of `tests/test_routine.py` -- see `Routine.kt`'s kdoc for why
 * this exists (first piece of the on-device migration). One test per
 * meaningful case from the Python suite, same NOW instant, each annotated
 * with which Python test it corresponds to. No Robolectric needed: unlike
 * most of this package, Routine.kt has zero Android-framework dependency
 * (pure java.time), so plain JUnit is enough. */
class RoutineTest {

    private val now = LocalDateTime.of(2026, 7, 30, 9, 0, 0)

    // Corresponds to test_since_last_not_due_when_recent
    @Test
    fun sinceLast_notDueWhenRecent() {
        val r = Routine(id = "partner", times = 1, perDays = 7, anchor = Anchor.SINCE_LAST)
        val out = status(r, listOf("2026-07-25T12:00:00"), now) as RoutineStatus.SinceLast

        assertFalse(out.due)
        assertEquals(4, out.daysSinceLast)
    }

    // Corresponds to test_since_last_due_at_exact_boundary
    @Test
    fun sinceLast_dueAtExactBoundary() {
        // Matches the Python test's inclusive >= semantics at exactly
        // per_days days since the last completion.
        val r = Routine(id = "partner", times = 1, perDays = 7, anchor = Anchor.SINCE_LAST)
        val out = status(r, listOf("2026-07-23T09:00:00"), now) as RoutineStatus.SinceLast

        assertEquals(7, out.daysSinceLast)
        assertTrue(out.due)
    }

    // Corresponds to test_since_last_due_when_no_history
    @Test
    fun sinceLast_dueWhenNoHistory() {
        val r = Routine(id = "friends", times = 1, perDays = 7, anchor = Anchor.SINCE_LAST)
        val out = status(r, emptyList(), now) as RoutineStatus.SinceLast

        assertTrue(out.due)
        assertNull(out.daysSinceLast)
    }

    // Corresponds to test_since_last_uses_most_recent_of_multiple_events
    @Test
    fun sinceLast_usesMostRecentOfMultipleEvents() {
        val r = Routine(id = "meal", times = 1, perDays = 6, anchor = Anchor.SINCE_LAST)
        val out = status(
            r,
            listOf("2026-07-01T09:00:00", "2026-07-26T09:00:00", "2026-07-10T09:00:00"),
            now,
        ) as RoutineStatus.SinceLast

        assertEquals(4, out.daysSinceLast)
        assertFalse(out.due)
    }

    // Corresponds to test_window_not_due_when_target_met
    @Test
    fun window_notDueWhenTargetMet() {
        val r = Routine(id = "gym", times = 4, perDays = 7, anchor = Anchor.WINDOW)
        val events = listOf(24, 25, 27, 29).map { "2026-07-%02dT09:00:00".format(it) }
        val out = status(r, events, now) as RoutineStatus.Window

        assertEquals(4, out.timesThisWindow)
        assertFalse(out.due)
    }

    // Corresponds to test_window_due_when_below_target
    @Test
    fun window_dueWhenBelowTarget() {
        val r = Routine(id = "gym", times = 4, perDays = 7, anchor = Anchor.WINDOW)
        val events = listOf(24, 27).map { "2026-07-%02dT09:00:00".format(it) }
        val out = status(r, events, now) as RoutineStatus.Window

        assertEquals(2, out.timesThisWindow)
        assertTrue(out.due)
    }

    // Corresponds to test_window_excludes_events_outside_window
    @Test
    fun window_excludesEventsOutsideWindow() {
        val r = Routine(id = "gym", times = 4, perDays = 7, anchor = Anchor.WINDOW)
        // 2026-07-22 is 8 days before now -- outside a trailing 7-day window.
        val out = status(
            r,
            listOf("2026-07-22T09:00:00", "2026-07-29T09:00:00"),
            now,
        ) as RoutineStatus.Window

        assertEquals(1, out.timesThisWindow)
    }

    // Corresponds to test_window_dedups_same_calendar_day
    @Test
    fun window_dedupsSameCalendarDay() {
        val r = Routine(id = "gym", times = 4, perDays = 7, anchor = Anchor.WINDOW)
        val out = status(
            r,
            listOf("2026-07-29T06:00:00", "2026-07-29T18:00:00"),
            now,
        ) as RoutineStatus.Window

        assertEquals(1, out.timesThisWindow)
    }

    // Corresponds to test_window_empty_history_is_due
    @Test
    fun window_emptyHistoryIsDue() {
        val r = Routine(id = "gym", times = 4, perDays = 7, anchor = Anchor.WINDOW)
        val out = status(r, emptyList(), now) as RoutineStatus.Window

        assertEquals(0, out.timesThisWindow)
        assertTrue(out.due)
    }

    // Corresponds to test_next_due_date_since_last
    @Test
    fun nextDueDate_sinceLast() {
        val r = Routine(id = "vacuum", times = 1, perDays = 7, anchor = Anchor.SINCE_LAST)
        assertEquals(LocalDate.of(2026, 7, 27), nextDueDate(r, LocalDate.of(2026, 7, 20)))
    }

    // Corresponds to test_next_due_date_rejects_window_anchor
    @Test(expected = IllegalArgumentException::class)
    fun nextDueDate_rejectsWindowAnchor() {
        val r = Routine(id = "gym", times = 4, perDays = 7, anchor = Anchor.WINDOW)
        nextDueDate(r, LocalDate.of(2026, 7, 20))
    }
}
