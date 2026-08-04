package com.lifeops.briefing

import androidx.room.Room
import com.lifeops.briefing.data.BlockedDayEntity
import com.lifeops.briefing.data.HistoryEventEntity
import com.lifeops.briefing.data.LifeOpsDatabase
import com.lifeops.briefing.data.RoutineEntity
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Exercises [runComputeTick] -- the fully testable core of
 * [LifeOpsComputeWorker] -- end to end against a real in-memory Room database
 * (same pattern `LifeOpsDatabaseTest.kt` already establishes) and a fake
 * [FlowSavvyFetch] returning fixture JSON built in-memory (no network, no
 * mock HTTP server -- same reasoning `FlowSavvyClientTest.kt`'s own kdoc
 * documents for why this project tests FlowSavvy behavior without one).
 *
 * Proves the three things this integration task's brief calls out
 * explicitly: engines receive real persisted history + fetched data, every
 * attention_state (ok/watch/risk/fucked) is reachable through the full tick,
 * and the widget-readable output shape ([ComputeTickResult]) is correct.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LifeOpsComputeWorkerTest {

    private lateinit var db: LifeOpsDatabase
    private val now = LocalDateTime.of(2026, 8, 2, 9, 0, 0)

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

    private fun scheduleItem(
        itemId: String,
        itemType: String,
        title: String,
        startTime: String?,
        allDay: Boolean = false,
        completed: Boolean = false,
    ) = JSONObject().apply {
        put("itemId", itemId)
        put("itemType", itemType)
        put("title", title)
        put("startTime", startTime)
        put("allDay", allDay)
        put("completed", completed)
    }

    private fun taskItem(
        id: String,
        title: String,
        dueDateTime: String?,
        durationMinutes: Int? = null,
        progressMinutes: Int? = null,
    ) = JSONObject().apply {
        put("id", id)
        put("title", title)
        if (dueDateTime != null) put("dueDateTime", dueDateTime) else put("dueDateTime", JSONObject.NULL)
        if (durationMinutes != null) put("durationMinutes", durationMinutes)
        if (progressMinutes != null) put("progressMinutes", progressMinutes)
    }

    private fun fakeFetch(scheduleItems: List<JSONObject>, tasks: List<JSONObject>): FlowSavvyFetch =
        FlowSavvyFetch {
            FlowSavvyFetchResult(
                scheduleItems = scheduleItems.map { parseScheduleItem(it) },
                incompleteTasks = tasks.map { parseTaskItem(it) },
            )
        }

    private fun insertGymEvent(daysAgo: Long) {
        val ts = now.minusDays(daysAgo)
        runBlocking {
            db.historyEventDao().insert(
                HistoryEventEntity(
                    domain = "gym",
                    timestampEpochMillis = ts.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                    eventType = HistoryEventEntity.EVENT_COMPLETED,
                ),
            )
        }
    }

    // ---- pure helper functions ----

    @Test
    fun nextTasksFromSchedule_excludesCompletedAllDayAndPastTasks() {
        val items = listOf(
            scheduleItem("t1", "task", "Reading", "2026-08-02T10:00:00"),
            scheduleItem("t2", "task", "Past task", "2026-08-01T10:00:00"),
            scheduleItem("t3", "task", "All day", "2026-08-02T00:00:00", allDay = true),
            scheduleItem("t4", "task", "Done", "2026-08-02T11:00:00", completed = true),
            scheduleItem("e1", "event", "Not a task", "2026-08-02T10:00:00"),
        ).map { parseScheduleItem(it) }

        val next = nextTasksFromSchedule(items, now)

        assertEquals(listOf("t1"), next.map { it.id })
    }

    @Test
    fun todayEventsFromSchedule_keepsOnlyTodaysTimedEvents() {
        val items = listOf(
            scheduleItem("e1", "event", "BBQ", "2026-08-02T18:00:00"),
            scheduleItem("e2", "event", "Tomorrow", "2026-08-03T10:00:00"),
            scheduleItem("e3", "event", "All day today", "2026-08-02T00:00:00", allDay = true),
        ).map { parseScheduleItem(it) }

        val events = todayEventsFromSchedule(items, now)

        assertEquals(listOf("BBQ"), events.map { it.title })
    }

    @Test
    fun assignmentsFromTasks_computesOverdueDueTodayAndAtRisk() {
        val tasks = listOf(
            // Overdue by 2 hours, real remaining work -> overdue reason.
            taskItem("a1", "Late reading", "2026-08-02T07:00:00", durationMinutes = 60, progressMinutes = 0),
            // Due in 10 hours (today), heavy + soon -> at-risk.
            taskItem("a2", "Big essay", "2026-08-02T19:00:00", durationMinutes = 180, progressMinutes = 0),
            // No due date -- excluded entirely.
            taskItem("a3", "Someday", null),
        ).map { parseTaskItem(it) }

        val assignments = assignmentsFromTasks(tasks, now)
        val overdue = overdueItemsFrom(assignments)
        val dueToday = dueTodayTitles(assignments)
        val atRisk = courseworkAtRiskTitles(assignments)

        assertEquals(listOf("Late reading"), overdue.map { it.title })
        assertTrue(dueToday.contains("Big essay"))
        assertTrue(atRisk.contains("Big essay"))
    }

    // ---- gym ring ----

    @Test
    fun computeGymRing_usesPersistedTargetAndRealHistory() = runBlocking {
        db.routineDao().insert(
            RoutineEntity(
                id = "gym", title = "Gym", timesPerWindow = 3, perDays = 7,
                anchor = RoutineEntity.ANCHOR_WINDOW, onDue = RoutineEntity.ON_DUE_NOTIFY,
            ),
        )
        insertGymEvent(daysAgo = 1)
        insertGymEvent(daysAgo = 3)

        val ring = computeGymRing(db, now)

        assertEquals(2, ring.gymLast7d)
        assertEquals(3, ring.gymTarget)
        assertTrue(!ring.todayDone)
    }

    @Test
    fun computeGymRing_fallsBackToDefaultTargetWhenNoRoutinePersisted() = runBlocking {
        insertGymEvent(daysAgo = 0)

        val ring = computeGymRing(db, now)

        assertEquals(4, ring.gymTarget)
        assertTrue(ring.todayDone)
    }

    // ---- gym plan (respecting blocked days) ----

    @Test
    fun gymDaysRespectingBlocks_marksOnlyThePersistedBlockedDate() = runBlocking {
        val today = now.toLocalDate()
        db.blockedDayDao().upsert(BlockedDayEntity(date = today.plusDays(2).toString()))

        val days = gymDaysRespectingBlocks(db.blockedDayDao(), today, days = 5)

        assertEquals(5, days.size)
        assertFalse(days[0].gymBlocked)
        assertFalse(days[1].gymBlocked)
        assertTrue(days[2].gymBlocked)
        assertFalse(days[3].gymBlocked)
        assertFalse(days[4].gymBlocked)
    }

    @Test
    fun computeGymPlanRespectingBlocks_noAlertWhenPlentyOfViableDaysAreOpen() = runBlocking {
        val plan = computeGymPlanRespectingBlocks(db, now)

        assertEquals(AlertLevel.NONE, plan.alert.level)
    }

    @Test
    fun computeGymPlanRespectingBlocks_blockingMostOfTheWindowRaisesAnAlert() = runBlocking {
        // Block 11 of the next 14 days (today..+10), leaving only 3
        // consecutive unblocked days (+11..+13) -- with the default
        // maxConsecutive=2 cap, at most 2 of those 3 can actually be booked,
        // which isn't enough to hit the floor (3) from a standing start ->
        // a real floor-risk alert, proving a BlockedDayEntity row actually
        // changes what GymSchedule.kt's plan() computes, not just a Room row
        // nothing reads.
        val today = now.toLocalDate()
        for (offset in 0..10) {
            db.blockedDayDao().upsert(BlockedDayEntity(date = today.plusDays(offset.toLong()).toString()))
        }

        val plan = computeGymPlanRespectingBlocks(db, now)

        assertTrue(plan.alert.level != AlertLevel.NONE)
    }

    @Test
    fun computeGymPlanRespectingBlocks_usesPersistedGymTargetWhenPresent() = runBlocking {
        db.routineDao().insert(
            RoutineEntity(
                id = "gym", title = "Gym", timesPerWindow = 2, perDays = 7,
                anchor = RoutineEntity.ANCHOR_WINDOW, onDue = RoutineEntity.ON_DUE_NOTIFY,
            ),
        )

        val plan = computeGymPlanRespectingBlocks(db, now)

        // target=2 (persisted) means only 2 sessions are needed this
        // window, easily reachable with the whole 14-day window open.
        assertEquals(AlertLevel.NONE, plan.alert.level)
        assertTrue(plan.summary.contains("target=2"))
    }

    // ---- refreshGymStateForAllWidgets ----

    @Test
    fun refreshGymStateForAllWidgets_doesNotThrowWithNoWidgetPlaced() = runBlocking {
        // No BriefingWidget instance placed anywhere (no GlanceId exists in
        // this unit-test environment) -- this must be a safe no-op, same
        // posture applyComputeTickResult's own "for every placed instance"
        // loop already has.
        refreshGymStateForAllWidgets(RuntimeEnvironment.getApplication(), db, now)
    }

    // ---- social / meal nudges ----

    @Test
    fun computeSocialNudges_firesWhenOverdue() = runBlocking {
        db.historyEventDao().insert(
            HistoryEventEntity(
                domain = "partner",
                timestampEpochMillis = now.minusDays(10).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                eventType = HistoryEventEntity.EVENT_COMPLETED,
            ),
        )

        val nudges = computeSocialNudges(db, now)

        assertTrue(nudges.isNotEmpty())
    }

    @Test
    fun computeSocialNudges_silentWhenNeverLogged() = runBlocking {
        // Real regression case SocialSchedule.kt's kdoc calls out: "unknown"
        // (never logged) must never itself trigger a nudge.
        val nudges = computeSocialNudges(db, now)

        assertTrue(nudges.isEmpty())
    }

    @Test
    fun computeMealNudge_dueWhenNeverLogged() = runBlocking {
        assertTrue(computeMealNudge(db, now) != null)
    }

    // ---- full end-to-end tick: attention_state for every severity ----

    @Test
    fun runComputeTick_okState_whenNothingIsWrong() = runBlocking {
        db.routineDao().insert(
            RoutineEntity(
                id = "gym", title = "Gym", timesPerWindow = 4, perDays = 7,
                anchor = RoutineEntity.ANCHOR_WINDOW, onDue = RoutineEntity.ON_DUE_NOTIFY,
            ),
        )
        for (d in 1L..4L) insertGymEvent(daysAgo = d)

        val fetch = fakeFetch(scheduleItems = emptyList(), tasks = emptyList())

        val result = runComputeTick(db, fetch, discretionaryDollars = 500, now = now)

        assertEquals(Severity.OK, result.attention.state)
        assertEquals(4, result.gymLast7d)
        assertTrue(result.attention.reasons.isEmpty())
    }

    @Test
    fun runComputeTick_watchState_whenGymBehindTarget() = runBlocking {
        // No gym history at all -- 0 of 4, target-2=2, 0 <= 2 -> watch.
        val fetch = fakeFetch(scheduleItems = emptyList(), tasks = emptyList())

        val result = runComputeTick(db, fetch, discretionaryDollars = 500, now = now)

        assertEquals(Severity.WATCH, result.attention.state)
        assertTrue(result.attention.reasons.any { it.domain == Domain.GYM })
    }

    @Test
    fun runComputeTick_riskState_whenDiscretionaryBalanceNegative() = runBlocking {
        for (d in 1L..4L) insertGymEvent(daysAgo = d)
        val fetch = fakeFetch(scheduleItems = emptyList(), tasks = emptyList())

        val result = runComputeTick(db, fetch, discretionaryDollars = -50, now = now)

        assertEquals(Severity.RISK, result.attention.state)
        assertTrue(result.attention.reasons.any { it.domain == Domain.MONEY })
    }

    @Test
    fun runComputeTick_fuckedState_whenTaskIsOverdueMoreThanADay() = runBlocking {
        for (d in 1L..4L) insertGymEvent(daysAgo = d)
        val tasks = listOf(
            taskItem("t1", "Way overdue", "2026-07-31T09:00:00", durationMinutes = 60, progressMinutes = 0),
        )
        val fetch = fakeFetch(scheduleItems = emptyList(), tasks = tasks)

        val result = runComputeTick(db, fetch, discretionaryDollars = 500, now = now)

        assertEquals(Severity.FUCKED, result.attention.state)
        assertTrue(result.attention.reasons.any { it.domain == Domain.COURSEWORK })
    }

    @Test
    fun runComputeTick_persistsFetchedTasksIntoTaskCache() = runBlocking {
        val tasks = listOf(taskItem("t1", "Reading", "2026-08-05T09:00:00", durationMinutes = 30, progressMinutes = 0))
        val fetch = fakeFetch(scheduleItems = emptyList(), tasks = tasks)

        runComputeTick(db, fetch, discretionaryDollars = null, now = now)

        val cached = db.taskCacheDao().getById("t1")
        assertEquals("Reading", cached?.title)
        assertEquals(false, cached?.completed)
    }

    @Test
    fun runComputeTick_buildsNextTasksAndTodayEventsFromSchedule() = runBlocking {
        val scheduleItems = listOf(
            scheduleItem("t1", "task", "Reading", "2026-08-02T10:00:00"),
            scheduleItem("e1", "event", "BBQ", "2026-08-02T18:00:00"),
        )
        val fetch = fakeFetch(scheduleItems = scheduleItems, tasks = emptyList())

        val result = runComputeTick(db, fetch, discretionaryDollars = null, now = now)

        assertEquals(listOf("t1"), result.nextTasks.tasks.map { it.id })
        assertEquals(listOf("BBQ"), result.nextTasks.events.map { it.title })
        assertNull(result.nextTasks.weather)
    }

    // ---- systemHealth threading (OnDeviceSystemHealth.kt) ----

    @Test
    fun runComputeTick_defaultsToNoSystemHealthWhenNotProvided() = runBlocking {
        // Existing/default behavior for every caller that doesn't pass
        // systemHealth explicitly -- must stay identical to before this was
        // wired (system = null, no system-domain reason possible).
        for (d in 1L..4L) insertGymEvent(daysAgo = d)
        val fetch = fakeFetch(scheduleItems = emptyList(), tasks = emptyList())

        val result = runComputeTick(db, fetch, discretionaryDollars = 500, now = now)

        assertEquals(Severity.OK, result.attention.state)
        assertFalse(result.attention.reasons.any { it.domain == Domain.SYSTEM })
    }

    @Test
    fun runComputeTick_freshSystemHealthProducesNoSystemReason() = runBlocking {
        for (d in 1L..4L) insertGymEvent(daysAgo = d)
        val fetch = fakeFetch(scheduleItems = emptyList(), tasks = emptyList())
        val freshHealth = SystemHealth(errors = emptyMap(), ageMins = 1.0)

        val result = runComputeTick(db, fetch, discretionaryDollars = 500, now = now, systemHealth = freshHealth)

        assertEquals(Severity.OK, result.attention.state)
    }

    @Test
    fun runComputeTick_staleSystemHealthRaisesAttentionToRisk() = runBlocking {
        for (d in 1L..4L) insertGymEvent(daysAgo = d)
        val fetch = fakeFetch(scheduleItems = emptyList(), tasks = emptyList())
        val staleHealth = SystemHealth(errors = emptyMap(), ageMins = 150.0)

        val result = runComputeTick(db, fetch, discretionaryDollars = 500, now = now, systemHealth = staleHealth)

        assertEquals(Severity.RISK, result.attention.state)
        assertTrue(result.attention.reasons.any { it.domain == Domain.SYSTEM })
    }

    @Test
    fun runComputeTick_systemHealthErrorsRaiseAttentionToFucked() = runBlocking {
        for (d in 1L..4L) insertGymEvent(daysAgo = d)
        val fetch = fakeFetch(scheduleItems = emptyList(), tasks = emptyList())
        val erroringHealth = SystemHealth(errors = mapOf("ynab" to "boom"), ageMins = 1.0)

        val result = runComputeTick(db, fetch, discretionaryDollars = 500, now = now, systemHealth = erroringHealth)

        assertEquals(Severity.FUCKED, result.attention.state)
        assertTrue(result.attention.reasons.any { it.domain == Domain.SYSTEM && it.severity == Severity.FUCKED })
    }

    // ---- EmptyFlowSavvyFetch (FlowSavvy-unconfigured compute tick) ----
    //
    // Pins the actual live-device bug this file's change fixes:
    // LifeOpsComputeWorker.doComputeTick used to `return Result.success()`
    // the moment FlowSavvy wasn't configured, BEFORE ever calling
    // runComputeTick/applyComputeTickResult -- so BRIEFING_JSON never got
    // regenerated and could sit forever on whatever was last persisted
    // (confirmed live-device: four "coursework" AttentionReason entries
    // with a null title, rendered as the bare domain word by
    // ui/AttentionScreen.kt's `reason.title ?: reason.domain` fallback, and
    // a blank Today-tab headline). These tests exercise runComputeTick
    // directly with EmptyFlowSavvyFetch -- the exact fetch
    // doComputeTick now hands it instead of skipping the tick -- and assert
    // the tick still produces a REAL, fully-titled attention_state/reasons/
    // headline from local-only facts (gym/social/meal/money/system health),
    // proving the fix's actual behavior rather than just its wiring.

    @Test
    fun emptyFlowSavvyFetch_returnsZeroScheduleItemsAndZeroTasks() {
        val result = EmptyFlowSavvyFetch.fetch(now)

        assertTrue(result.scheduleItems.isEmpty())
        assertTrue(result.incompleteTasks.isEmpty())
    }

    @Test
    fun runComputeTick_withEmptyFlowSavvyFetch_stillProducesARealHeadline() = runBlocking {
        // No gym history, no persisted routine, no FlowSavvy data at all --
        // the worst case for "is there anything real to say." compute()'s
        // own fallback headline ("You are clear...") must still come
        // through non-blank, never null/empty -- this is exactly what
        // TodayScreen.kt reads as `data.briefing.attentionHeadline ?:
        // data.briefing.text` for the Today tab's headline.
        val result = runComputeTick(db, EmptyFlowSavvyFetch, discretionaryDollars = null, now = now)

        assertTrue(result.attention.headline.isNotBlank())
    }

    @Test
    fun runComputeTick_withEmptyFlowSavvyFetch_everyReasonHasARealNonBlankTitle() = runBlocking {
        // Real local-only facts that DO produce reasons even with zero
        // FlowSavvy data: gym behind target (no gym history logged at all)
        // and a negative discretionary balance (money domain). Every
        // resulting AttentionReason must carry AttentionReason.title itself
        // non-blank (Attention.kt's compute() sets a real title on every
        // branch -- see this file's own kdoc -- so a blank/absent title
        // here would mean this test's fixture, not production code, is
        // wrong) -- the direct opposite of the live-device symptom (a
        // reason whose PERSISTED com.lifeops.briefing.data.AttentionReason.title
        // was null, forcing AttentionScreen.kt's bare-domain-word fallback).
        val result = runComputeTick(db, EmptyFlowSavvyFetch, discretionaryDollars = -50, now = now)

        assertTrue(result.attention.reasons.isNotEmpty())
        assertTrue(result.attention.reasons.all { it.title.isNotBlank() })
        assertTrue(result.attention.reasons.any { it.domain == Domain.MONEY })
        assertTrue(result.attention.reasons.any { it.domain == Domain.GYM })
        // No coursework reason is possible without real FlowSavvy task data
        // -- confirms the "narrower, not fabricated" half of the fix: this
        // tick doesn't invent coursework facts it has no source for.
        assertFalse(result.attention.reasons.any { it.domain == Domain.COURSEWORK })
    }

    @Test
    fun runComputeTick_withEmptyFlowSavvyFetch_leavesNextTasksAndEventsEmpty() = runBlocking {
        // No FlowSavvy schedule to derive next-tasks/today-events from --
        // must come back empty, not stale/fabricated, when FlowSavvy isn't
        // configured.
        val result = runComputeTick(db, EmptyFlowSavvyFetch, discretionaryDollars = null, now = now)

        assertTrue(result.nextTasks.tasks.isEmpty())
        assertTrue(result.nextTasks.events.isEmpty())
    }

    @Test
    fun isoSeconds_zeroPadsEveryFieldForLexicalComparison() {
        val dt = LocalDateTime.of(2026, 1, 5, 9, 5, 0)
        assertEquals("2026-01-05T09:05:00", isoSeconds(dt))
    }
}
