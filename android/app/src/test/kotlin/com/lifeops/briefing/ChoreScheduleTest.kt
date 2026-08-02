package com.lifeops.briefing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reproduces `tests/test_chore_engine.py`'s (and the former
 * `ChoreEngineTest.kt`'s) 12 test cases, unchanged in assertion, against the
 * NEW `ChoreSchedule.kt` (see that file's kdoc for the finding that chore
 * has no per-day/slot decision to make, so it stays a direct call into
 * `Routine.kt`'s SINCE_LAST primitive rather than growing a [TimeSlot]).
 * Every type name and the `plan()` entry point are unchanged, so this file
 * is a faithful behavior-preservation check, not a rewrite.
 *
 * No Robolectric needed: `ChoreSchedule.kt` has zero Android-framework
 * dependency (pure java.time + regex), same as `RoutineTest.kt`.
 */
class ChoreScheduleTest {

    // Mirrors the Python module-level BASE dict fixture.
    private val base = CompletedChore(
        id = "c1",
        title = "Laundry",
        notes = "[cycle:7d]",
        completedDate = "2026-07-06",
        durationMinutes = 90,
        minLengthMinutes = 30,
        listId = "6784",
        priority = "low",
        schedulingHoursId = "427988",
        dueTime = "20:00",
    )

    // Corresponds to test_creates_next_occurrence
    @Test
    fun createsNextOccurrence() {
        val out = plan(ChorePlanInput(completed = listOf(base)))
        assertEquals(1, out.creates.size)
        val c = out.creates[0]
        assertEquals("Laundry", c.title)
        assertTrue(c.dueDateTime.startsWith("2026-07-13")) // +7 days
    }

    // Corresponds to test_due_time_preserved
    @Test
    fun dueTimePreserved() {
        val out = plan(ChorePlanInput(completed = listOf(base)))
        assertTrue(out.creates[0].dueDateTime.contains("20:00"))
    }

    // Corresponds to test_lead_time_capped_at_3
    @Test
    fun leadTimeCappedAt3() {
        // 7-day cycle -> lead = min(3, 7-1) = 3
        val out = plan(ChorePlanInput(completed = listOf(base)))
        assertTrue(out.creates[0].canBeStartedAt.startsWith("2026-07-10")) // 13 - 3
    }

    // Corresponds to test_lead_time_short_cycle
    @Test
    fun leadTimeShortCycle() {
        // 2-day cycle -> lead = min(3, 2-1) = 1
        val c = base.copy(id = "c2", notes = "[cycle:2d]")
        val out = plan(ChorePlanInput(completed = listOf(c)))
        // due = 2026-07-08, canBeStartedAt = 2026-07-07
        assertTrue(out.creates[0].canBeStartedAt.startsWith("2026-07-07"))
    }

    // Corresponds to test_skips_already_processed
    @Test
    fun skipsAlreadyProcessed() {
        val out = plan(ChorePlanInput(completed = listOf(base), processed = listOf("c1")))
        assertTrue(out.creates.isEmpty())
    }

    // Corresponds to test_skips_no_cycle_tag
    @Test
    fun skipsNoCycleTag() {
        val c = base.copy(notes = "no tag here")
        val out = plan(ChorePlanInput(completed = listOf(c)))
        assertTrue(out.creates.isEmpty())
    }

    // Corresponds to test_malformed_date_skips_item_not_batch
    @Test
    fun malformedDateSkipsItemNotBatch() {
        val bad = base.copy(id = "bad", completedDate = "not-a-date")
        val none = base.copy(id = "none", completedDate = null)
        val out = plan(ChorePlanInput(completed = listOf(bad, none, base)))
        assertEquals(1, out.creates.size) // good one still cycles
        assertEquals("Laundry", out.creates[0].title)
        assertTrue("bad" !in out.processed) // bad items can retry when fixed
    }

    // Corresponds to test_missing_id_or_title_skipped
    @Test
    fun missingIdOrTitleSkipped() {
        val noId = base.copy(id = null)
        val noTitle = base.copy(id = "c9", title = null)
        val out = plan(ChorePlanInput(completed = listOf(noId, noTitle)))
        assertTrue(out.creates.isEmpty())
    }

    // Corresponds to test_missing_title_marked_processed_not_retried_forever
    @Test
    fun missingTitleMarkedProcessedNotRetriedForever() {
        // regression: a record missing "title" (valid cycle tag + completed_date,
        // so not caught by the malformed-date guard) was skipped WITHOUT being
        // added to processed -- meaning it would be refetched and re-skipped on
        // every single future run indefinitely instead of being handled once.
        val noTitle = base.copy(id = "c9", title = null)
        val out = plan(ChorePlanInput(completed = listOf(noTitle)))
        assertTrue(out.creates.isEmpty())
        assertTrue(
            "malformed record must be marked processed, not retried forever",
            "c9" in out.processed,
        )
    }

    // Corresponds to test_marks_as_processed
    @Test
    fun marksAsProcessed() {
        val out = plan(ChorePlanInput(completed = listOf(base)))
        assertTrue("c1" in out.processed)
    }

    // Corresponds to test_multiple_chores
    @Test
    fun multipleChores() {
        val c2 = base.copy(id = "c2", title = "Clean bathroom", notes = "[cycle:21d]")
        val out = plan(ChorePlanInput(completed = listOf(base, c2)))
        assertEquals(2, out.creates.size)
    }

    // Corresponds to test_default_due_time_when_missing
    @Test
    fun defaultDueTimeWhenMissing() {
        val c = base.copy(dueTime = null)
        val out = plan(ChorePlanInput(completed = listOf(c)))
        assertTrue(out.creates[0].dueDateTime.contains("20:00"))
    }
}
