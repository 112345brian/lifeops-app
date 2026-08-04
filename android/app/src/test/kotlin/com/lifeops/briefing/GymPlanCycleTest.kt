package com.lifeops.briefing

import androidx.room.Room
import com.lifeops.briefing.data.BlockedDayEntity
import com.lifeops.briefing.data.HistoryEventEntity
import com.lifeops.briefing.data.LifeOpsDatabase
import com.lifeops.briefing.data.RoutineEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Exercises [runGymPlanCycle] -- `GymPlanCycle.kt`'s wiring of
 * `GymSchedule.kt`'s already-ported/already-tested `plan()` to a real
 * FlowSavvy read/write -- end to end against a real in-memory Room database
 * (same pattern [ChoreCycleTest]/[LifeOpsComputeWorkerTest] already
 * establish) and fake [GymPlanFetch]/[GymTaskCreator]/[GymTaskDeleter] seams
 * (no network, no mock HTTP server).
 *
 * Covers this task's three required scenarios: a normal week books the
 * expected gym slots, a week with blocked days correctly skips them
 * (matching [LifeOpsComputeWorkerTest]'s existing
 * `computeGymPlanRespectingBlocks` block-day coverage, not regressing it),
 * and running the tick twice does not create a duplicate for an
 * already-booked slot.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GymPlanCycleTest {

    private lateinit var db: LifeOpsDatabase
    // A Monday, so the 7-day evening-first candidate window has plenty of
    // open, non-consecutive-capped days to book against.
    private val now = LocalDateTime.of(2026, 7, 6, 9, 0, 0)
    private val today: LocalDate = now.toLocalDate()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            LifeOpsDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private class RecordingCreator : GymTaskCreator {
        val created = mutableListOf<Map<String, Any?>>()
        override fun create(body: Map<String, Any?>) {
            created.add(body)
        }
    }

    private class RecordingDeleter : GymTaskDeleter {
        val deleted = mutableListOf<String>()
        override fun delete(id: String) {
            deleted.add(id)
        }
    }

    private fun emptyFetch(
        scheduledGymTasks: List<RawGymScheduledTask> = emptyList(),
        calendarEvents: List<RawCalendarEvent> = emptyList(),
        existingWindDownDates: Set<String> = emptySet(),
    ): GymPlanFetch = GymPlanFetch {
        GymPlanFetchResult(scheduledGymTasks, calendarEvents, existingWindDownDates)
    }

    private suspend fun setGymTarget(target: Int) {
        db.routineDao().insert(
            RoutineEntity(
                id = "gym", title = "Gym", timesPerWindow = target, perDays = 7,
                anchor = RoutineEntity.ANCHOR_WINDOW, onDue = RoutineEntity.ON_DUE_NOTIFY,
            ),
        )
    }

    // ---- required scenario 1: a normal week books the expected gym slots ----

    @Test
    fun normalWeek_booksGymSlotsUpToTarget() = runBlocking {
        setGymTarget(2)
        val creator = RecordingCreator()

        val plan = runGymPlanCycle(db, emptyFetch(), incompleteTasks = emptyList(), creator, RecordingDeleter(), now)

        // target=2, nothing done/scheduled yet -> 2 create actions, evening
        // slot first (GymSchedule.kt's declared slot order), consecutive-cap
        // respected (maxConsecutive defaults to 2, so Mon+Tue is legal).
        assertEquals(2, plan.actions.count { it is GymAction.Create })
        assertEquals(2, creator.created.size)
        val first = creator.created[0]
        assertEquals("Gym", first["title"])
        assertEquals(false, first["isAutoScheduled"])
        assertTrue((first["startDateTime"] as String).startsWith(today.toString()))
        assertTrue((first["startDateTime"] as String).contains("19:00:00"))
        assertEquals("Auto-scheduled by LifeOps.", first["notes"])
    }

    @Test
    fun normalWeek_noActionsNeededWhenAlreadyAtTarget() = runBlocking {
        setGymTarget(1)
        db.historyEventDao().insert(
            HistoryEventEntity(
                domain = "gym",
                timestampEpochMillis = now.minusDays(1).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                eventType = HistoryEventEntity.EVENT_COMPLETED,
            ),
        )
        val creator = RecordingCreator()

        val plan = runGymPlanCycle(db, emptyFetch(), incompleteTasks = emptyList(), creator, RecordingDeleter(), now)

        assertTrue(plan.actions.isEmpty())
        assertTrue(creator.created.isEmpty())
    }

    // ---- required scenario 2: blocked days are correctly skipped ----

    @Test
    fun blockedDay_isNeverChosenAsAGymSlot() = runBlocking {
        setGymTarget(1)
        // Block every day except Wednesday (today+2) so plan() has exactly
        // one legal candidate day left.
        for (offset in listOf(0, 1, 3, 4, 5, 6)) {
            db.blockedDayDao().upsert(BlockedDayEntity(date = today.plusDays(offset.toLong()).toString()))
        }
        val creator = RecordingCreator()

        val plan = runGymPlanCycle(db, emptyFetch(), incompleteTasks = emptyList(), creator, RecordingDeleter(), now)

        val createDates = plan.actions.filterIsInstance<GymAction.Create>().map { it.date }
        assertEquals(listOf(today.plusDays(2)), createDates)
        assertEquals(1, creator.created.size)
        assertTrue((creator.created[0]["startDateTime"] as String).startsWith(today.plusDays(2).toString()))
    }

    @Test
    fun allDaysBlocked_createsNothing() = runBlocking {
        setGymTarget(2)
        for (offset in 0..13) {
            db.blockedDayDao().upsert(BlockedDayEntity(date = today.plusDays(offset.toLong()).toString()))
        }
        val creator = RecordingCreator()

        val plan = runGymPlanCycle(db, emptyFetch(), incompleteTasks = emptyList(), creator, RecordingDeleter(), now)

        assertTrue(plan.actions.isEmpty())
        assertTrue(creator.created.isEmpty())
        assertEquals(AlertLevel.HIGH, plan.alert.level)
    }

    // ---- required scenario 3: a second tick does not duplicate an already-booked slot ----

    @Test
    fun secondTickAgainstAlreadyScheduledSlot_doesNotCreateDuplicate() = runBlocking {
        setGymTarget(1)
        val creator = RecordingCreator()

        // First tick: nothing scheduled yet -> books one evening slot today.
        val firstPlan = runGymPlanCycle(db, emptyFetch(), incompleteTasks = emptyList(), creator, RecordingDeleter(), now)
        assertEquals(1, firstPlan.actions.count { it is GymAction.Create })
        assertEquals(1, creator.created.size)

        // Second tick, 15 minutes later: FlowSavvy now reports that same
        // "Gym" task as already scheduled today (the real, live dedup source
        // -- see GymPlanCycle.kt's kdoc) -- must NOT create a second one.
        val fetchWithScheduled = emptyFetch(
            scheduledGymTasks = listOf(
                RawGymScheduledTask(id = "g1", startDateTime = "${today}T19:00:00", endDateTime = "${today}T20:00:00"),
            ),
        )
        val secondPlan = runGymPlanCycle(db, fetchWithScheduled, incompleteTasks = emptyList(), creator, RecordingDeleter(), now.plusMinutes(15))

        assertTrue(secondPlan.actions.none { it is GymAction.Create && it.date == today })
        assertEquals(1, creator.created.size) // still just the one from the first tick
    }

    // ---- wind-down dedup: existing "Wind down" task blocks a re-create ----

    @Test
    fun windDown_skippedWhenAnEntryAlreadyExistsForThatDate() = runBlocking {
        setGymTarget(1)
        // Force a morning slot (which triggers a wind-down block the night
        // before) by blocking the evening window on every candidate day.
        val events = (0..6).map { offset ->
            RawCalendarEvent(
                startDateTime = "${today.plusDays(offset.toLong())}T18:30:00",
                endDateTime = "${today.plusDays(offset.toLong())}T20:30:00",
            )
        }
        val creator = RecordingCreator()

        val plan = runGymPlanCycle(
            db,
            emptyFetch(calendarEvents = events),
            incompleteTasks = emptyList(),
            creator,
            RecordingDeleter(),
            now,
        )

        // Sanity: this scenario really did produce a wind-down block.
        assertTrue(plan.windDown.isNotEmpty())
        val windDownCountFirstRun = creator.created.count { it["title"] == "Wind down — early gym" }
        assertTrue(windDownCountFirstRun >= 1)

        // Now simulate a re-run where FlowSavvy already reports that
        // wind-down date as existing -- must be skipped, not duplicated.
        val existingDate = plan.windDown.first().date.toString()
        val creator2 = RecordingCreator()
        runGymPlanCycle(
            db,
            emptyFetch(calendarEvents = events, existingWindDownDates = setOf(existingDate)),
            incompleteTasks = emptyList(),
            creator2,
            RecordingDeleter(),
            now,
        )
        assertTrue(creator2.created.none { it["title"] == "Wind down — early gym" && (it["startDateTime"] as String).startsWith(existingDate) })
    }

    // ---- deadline-heavy day context (real fetched-task load) ----

    @Test
    fun deadlineLoadMinutesByDate_sumsRemainingMinutesPerDueDate() {
        val tasks = listOf(
            RawTaskItem(id = "a", title = "Essay", dueDateTime = "2026-07-08T19:00:00", durationMinutes = 120, progressMinutes = 0),
            RawTaskItem(id = "b", title = "Reading", dueDateTime = "2026-07-08T09:00:00", durationMinutes = 90, progressMinutes = 30),
            RawTaskItem(id = "c", title = "No due date", dueDateTime = null, durationMinutes = 60, progressMinutes = 0),
        )

        val load = deadlineLoadMinutesByDate(tasks)

        assertEquals(180, load[LocalDate.of(2026, 7, 8)]) // 120 + (90-30)
        assertTrue(LocalDate.of(2026, 7, 9) !in load)
    }

    @Test
    fun buildGymCandidateDays_marksDeadlineHeavyAndEveningBlockedFromRealEvents() {
        val day0 = today
        val events = listOf(
            // An event 18:30-20:30 today overlaps the 18:00-21:00 evening
            // window -> evening_blocked=true for today, and marks tomorrow
            // as "day after a show" / "prior night blocked".
            RawCalendarEvent(startDateTime = "${day0}T18:30:00", endDateTime = "${day0}T20:30:00"),
        )
        val load = mapOf(day0.plusDays(3) to 200) // >=180min due 3 days out -> that day is deadline_heavy

        val days = buildGymCandidateDays(day0, 7, blockedDates = emptySet(), calendarEvents = events, deadlineLoadMinutesByDate = load)

        assertTrue(days[0].eveningBlocked)
        assertTrue(days[1].dayAfterShow)
        assertTrue(days[1].priorNightBlocked)
        assertTrue(days[3].deadlineHeavy)
        // day 2's "next day" (day 3) is the heavy due date too -- _heavy()
        // checks the due day OR the day before it, per gather.py's own
        // `_heavy` -- so day 2 is heavy, but day 1 (whose "next day" is day
        // 2, with no load) is not.
        assertTrue(days[2].deadlineHeavy)
        assertFalse(days[1].deadlineHeavy)
    }
}
