package com.lifeops.briefing

import androidx.room.Room
import com.lifeops.briefing.data.LifeOpsDatabase
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
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
 * Exercises [runChoreCycle] -- `ChoreCycle.kt`'s wiring of `ChoreSchedule.kt`'s
 * already-ported/already-tested `plan()` to a real FlowSavvy read/write --
 * end to end against a real in-memory Room database (same pattern
 * `LifeOpsComputeWorkerTest.kt` already establishes) and fake
 * [ChoreCompletedFetch]/[ChoreTaskCreator] seams (no network, no mock HTTP
 * server -- same reasoning `FlowSavvyClientTest.kt`'s own kdoc documents).
 *
 * Covers this task's four required scenarios: a completed `[cycle:7d]`-tagged
 * task creates a next occurrence 7 days out; a completed task with no cycle
 * tag creates nothing; a second tick against the same completion does NOT
 * create a duplicate (the actual dedup guarantee); a malformed cycle tag is
 * skipped gracefully, matching `chore_engine.py`'s own "not a managed chore"
 * treatment for anything the `\[cycle:(\d+)d\]` regex doesn't match.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChoreCycleTest {

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

    private class RecordingCreator : ChoreTaskCreator {
        val created = mutableListOf<Map<String, Any?>>()
        override fun create(body: Map<String, Any?>) {
            created.add(body)
        }
    }

    private fun fixedFetch(tasks: List<RawCompletedChoreTask>): ChoreCompletedFetch =
        ChoreCompletedFetch { _, _ -> tasks }

    private fun laundryTask(
        id: String = "c1",
        notes: String = "[cycle:7d]",
        completedDate: String = "2026-07-26",
    ) = RawCompletedChoreTask(
        id = id,
        title = "Laundry",
        notes = notes,
        completedDate = completedDate,
        durationMinutes = 90,
        minLengthMinutes = 30,
        listId = "6784",
        priority = "low",
        schedulingHoursId = "427988",
        dueTime = "20:00",
    )

    // ---- required scenario 1: creates next occurrence N days out ----

    @Test
    fun completedCycleTaggedTask_createsNextOccurrenceNDaysOut() = runBlocking {
        val creator = RecordingCreator()
        val fetch = fixedFetch(listOf(laundryTask()))

        val createdCount = runChoreCycle(db, fetch, creator, now)

        assertEquals(1, createdCount)
        assertEquals(1, creator.created.size)
        val body = creator.created[0]
        assertEquals("Laundry", body["title"])
        assertEquals("6784", body["listId"])
        assertEquals(false, body["isAutoIgnored"])
        // completedDate 2026-07-26 + 7d cycle = 2026-08-02
        assertTrue((body["dueDateTime"] as String).startsWith("2026-08-02"))
        assertTrue((body["dueDateTime"] as String).contains("20:00"))
    }

    // ---- required scenario 2: no cycle tag creates nothing ----

    @Test
    fun completedTaskWithNoCycleTag_createsNothing() = runBlocking {
        val creator = RecordingCreator()
        val fetch = fixedFetch(listOf(laundryTask(notes = "just a note, no tag")))

        val createdCount = runChoreCycle(db, fetch, creator, now)

        assertEquals(0, createdCount)
        assertTrue(creator.created.isEmpty())
    }

    // ---- required scenario 3: the actual dedup guarantee ----

    @Test
    fun secondTickAgainstSameCompletion_doesNotCreateDuplicate() = runBlocking {
        val creator = RecordingCreator()
        val fetch = fixedFetch(listOf(laundryTask()))

        val firstCount = runChoreCycle(db, fetch, creator, now)
        // A second tick 15 minutes later still "sees" the exact same
        // completion (FlowSavvy hasn't dropped it from its completed-list
        // window yet) -- the persisted `processed` id must prevent a second
        // create.
        val secondCount = runChoreCycle(db, fetch, creator, now.plusMinutes(15))

        assertEquals(1, firstCount)
        assertEquals(0, secondCount)
        assertEquals(1, creator.created.size)
    }

    // ---- required scenario 4: malformed cycle tag handled gracefully ----

    @Test
    fun malformedCycleTag_isSkippedGracefully_notCreatedNotMarkedProcessed() = runBlocking {
        val creator = RecordingCreator()
        // "[cycle:d]" has no digits before the "d" -- doesn't match
        // ChoreSchedule.kt's `\[cycle:(\d+)d\]` regex, so plan() treats this
        // exactly like "not a managed chore" (same as no tag at all): no
        // create, and (matching chore_engine.py) NOT marked processed either
        // -- the skip happens before plan()'s own `processed.add` call, so a
        // corrected tag on this same task id could still be honored later.
        val fetch = fixedFetch(listOf(laundryTask(notes = "[cycle:d]")))

        val createdCount = runChoreCycle(db, fetch, creator, now)

        assertEquals(0, createdCount)
        assertTrue(creator.created.isEmpty())
        val state = db.choreCycleStateDao().get()
        assertTrue(state == null || "c1" !in state.processedIdsJoined)
    }

    // ---- modifiedAfter cursor plumbing ----

    @Test
    fun modifiedAfterCursor_defaultsToEpochOnFirstRunThenAdvances() = runBlocking {
        val creator = RecordingCreator()
        var received: String? = null
        val fetch = ChoreCompletedFetch { _, modifiedAfterIso -> received = modifiedAfterIso; emptyList() }

        runChoreCycle(db, fetch, creator, now)
        assertEquals("1970-01-01T00:00:00Z", received)

        val later = now.plusMinutes(15)
        runChoreCycle(db, fetch, creator, later)
        val expectedCursor = utcIsoSeconds(now.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
        assertEquals(expectedCursor, received)
    }

    // ---- real JSON parsing (RealChoreCompletedFetch's pre-filter + field mapping) ----

    private fun completedTaskJson(
        id: String,
        notes: String?,
        dueDateTime: String? = "2026-08-09T20:00:00",
        lastModified: String? = "2026-08-02T09:00:00",
    ) = JSONObject().apply {
        put("id", id)
        put("title", "Vacuum")
        if (notes != null) put("notes", notes) else put("notes", JSONObject.NULL)
        if (dueDateTime != null) put("dueDateTime", dueDateTime) else put("dueDateTime", JSONObject.NULL)
        if (lastModified != null) put("lastModified", lastModified) else put("lastModified", JSONObject.NULL)
        put("durationMinutes", 20)
        put("listId", "999")
    }

    @Test
    fun parseCompletedChoreTask_extractsFieldsMatchingRunChorePyShape() {
        val parsed = parseCompletedChoreTask(completedTaskJson("v1", "[cycle:14d]"), now)

        assertEquals("v1", parsed.id)
        assertEquals("Vacuum", parsed.title)
        assertEquals("[cycle:14d]", parsed.notes)
        assertEquals("2026-08-02", parsed.completedDate) // from lastModified[:10]
        assertEquals("20:00", parsed.dueTime) // from dueDateTime[11:16]
        assertEquals(20, parsed.durationMinutes)
        assertEquals("999", parsed.listId)
    }

    @Test
    fun parseCompletedChoreTask_fallsBackToNowAndDefaultDueTimeWhenMissing() {
        val parsed = parseCompletedChoreTask(
            completedTaskJson("v2", "[cycle:14d]", dueDateTime = null, lastModified = null),
            now,
        )

        assertEquals(now.toLocalDate().toString(), parsed.completedDate)
        assertEquals("20:00", parsed.dueTime)
    }

    @Test
    fun utcIsoSeconds_matchesUnderscoreUtcIsoFormatExactly() {
        val epochMillis = LocalDateTime.of(2026, 8, 2, 9, 0, 0).atZone(ZoneId.of("UTC")).toInstant().toEpochMilli()
        assertEquals("2026-08-02T09:00:00Z", utcIsoSeconds(epochMillis))
    }
}
