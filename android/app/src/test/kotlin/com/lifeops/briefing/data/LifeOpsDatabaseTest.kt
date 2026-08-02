package com.lifeops.briefing.data

import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Exercises the Room persistence layer (LifeOpsDatabase.kt) against a real
 * in-memory SQLite database -- same "real backing store, not a mock"
 * approach PendingRemovalsTest.kt already uses for Preferences DataStore.
 * Room needs Robolectric's SQLite shadow to run at all under a plain JVM
 * unit test (no instrumented/device test in this project), same reason
 * SimpleHttpTest/PendingRemovalsTest are already `@RunWith(RobolectricTestRunner::class)`. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LifeOpsDatabaseTest {

    private lateinit var db: LifeOpsDatabase

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

    // ---- RoutineEntity / RoutineDao ----

    private fun gymRoutine() = RoutineEntity(
        id = "gym",
        title = "Gym",
        timesPerWindow = 4,
        perDays = 7,
        anchor = RoutineEntity.ANCHOR_WINDOW,
        onDue = RoutineEntity.ON_DUE_NOTIFY,
        constraintsJson = """{"floor":3,"max_consecutive":2}""",
    )

    @Test
    fun routine_insertThenGetById_returnsInsertedRow() = runBlocking {
        db.routineDao().insert(gymRoutine())

        val loaded = db.routineDao().getById("gym")

        assertEquals(gymRoutine(), loaded)
    }

    @Test
    fun routine_getAll_returnsEveryInsertedRoutine() = runBlocking {
        db.routineDao().insert(gymRoutine())
        db.routineDao().insert(
            RoutineEntity(
                id = "partner",
                title = "Partner time",
                timesPerWindow = 1,
                perDays = 7,
                anchor = RoutineEntity.ANCHOR_SINCE_LAST,
                onDue = RoutineEntity.ON_DUE_NOTIFY,
            )
        )

        val all = db.routineDao().getAll()

        assertEquals(listOf("gym", "partner"), all.map { it.id })
    }

    @Test
    fun routine_update_persistsChangedFields() = runBlocking {
        db.routineDao().insert(gymRoutine())

        db.routineDao().update(gymRoutine().copy(timesPerWindow = 5, constraintsJson = """{"floor":4}"""))

        val loaded = db.routineDao().getById("gym")
        assertEquals(5, loaded?.timesPerWindow)
        assertEquals("""{"floor":4}""", loaded?.constraintsJson)
    }

    @Test
    fun routine_delete_removesTheRow() = runBlocking {
        db.routineDao().insert(gymRoutine())

        db.routineDao().delete(gymRoutine())

        assertNull(db.routineDao().getById("gym"))
    }

    @Test
    fun routine_upsert_insertsWhenAbsentAndReplacesWhenPresent() = runBlocking {
        db.routineDao().upsert(gymRoutine())
        assertEquals(4, db.routineDao().getById("gym")?.timesPerWindow)

        db.routineDao().upsert(gymRoutine().copy(timesPerWindow = 6))

        assertEquals(6, db.routineDao().getById("gym")?.timesPerWindow)
        assertEquals(1, db.routineDao().getAll().size)
    }

    @Test
    fun routine_toRoutine_mapsIntoThePureLogicShape() {
        val routine = gymRoutine().toRoutine()

        assertEquals("gym", routine.id)
        assertEquals(4, routine.times)
        assertEquals(7, routine.perDays)
        assertEquals(com.lifeops.briefing.Anchor.WINDOW, routine.anchor)
    }

    // ---- HistoryEventEntity / HistoryEventDao ----

    @Test
    fun historyEvent_insertThenGetForDomain_returnsItOldestFirst() = runBlocking {
        val dao = db.historyEventDao()
        dao.insert(HistoryEventEntity(domain = "gym", timestampEpochMillis = 2_000L, eventType = HistoryEventEntity.EVENT_COMPLETED))
        dao.insert(HistoryEventEntity(domain = "gym", timestampEpochMillis = 1_000L, eventType = HistoryEventEntity.EVENT_COMPLETED))
        dao.insert(HistoryEventEntity(domain = "partner", timestampEpochMillis = 1_500L, eventType = HistoryEventEntity.EVENT_COMPLETED))

        val gymEvents = dao.getForDomain("gym")

        assertEquals(listOf(1_000L, 2_000L), gymEvents.map { it.timestampEpochMillis })
    }

    @Test
    fun historyEvent_update_persistsChangedEventType() = runBlocking {
        val dao = db.historyEventDao()
        val id = dao.insert(HistoryEventEntity(domain = "gym", timestampEpochMillis = 1_000L, eventType = HistoryEventEntity.EVENT_COMPLETED))
        val stored = dao.getById(id)!!

        dao.update(stored.copy(eventType = HistoryEventEntity.EVENT_SKIPPED, metadataJson = """{"reason":"sick"}"""))

        val updated = dao.getById(id)
        assertEquals(HistoryEventEntity.EVENT_SKIPPED, updated?.eventType)
        assertEquals("""{"reason":"sick"}""", updated?.metadataJson)
    }

    @Test
    fun historyEvent_delete_removesTheRow() = runBlocking {
        val dao = db.historyEventDao()
        val id = dao.insert(HistoryEventEntity(domain = "gym", timestampEpochMillis = 1_000L, eventType = HistoryEventEntity.EVENT_COMPLETED))

        dao.delete(dao.getById(id)!!)

        assertNull(dao.getById(id))
    }

    @Test
    fun historyEvent_getSince_excludesEventsBeforeTheCutoff() = runBlocking {
        val dao = db.historyEventDao()
        dao.insert(HistoryEventEntity(domain = "gym", timestampEpochMillis = 1_000L, eventType = HistoryEventEntity.EVENT_COMPLETED))
        dao.insert(HistoryEventEntity(domain = "gym", timestampEpochMillis = 2_000L, eventType = HistoryEventEntity.EVENT_COMPLETED))
        dao.insert(HistoryEventEntity(domain = "gym", timestampEpochMillis = 3_000L, eventType = HistoryEventEntity.EVENT_COMPLETED))

        val since = dao.getSince(2_000L)

        assertEquals(listOf(2_000L, 3_000L), since.map { it.timestampEpochMillis })
    }

    @Test
    fun historyEvent_getForDomainSince_scopesByBothDomainAndCutoff() = runBlocking {
        val dao = db.historyEventDao()
        dao.insert(HistoryEventEntity(domain = "gym", timestampEpochMillis = 1_000L, eventType = HistoryEventEntity.EVENT_COMPLETED))
        dao.insert(HistoryEventEntity(domain = "gym", timestampEpochMillis = 3_000L, eventType = HistoryEventEntity.EVENT_COMPLETED))
        dao.insert(HistoryEventEntity(domain = "partner", timestampEpochMillis = 3_000L, eventType = HistoryEventEntity.EVENT_COMPLETED))

        val since = dao.getForDomainSince("gym", 2_000L)

        assertEquals(listOf(3_000L), since.map { it.timestampEpochMillis })
    }

    @Test
    fun historyEvent_getLastTimestampForDomain_isNullWhenNoEventsLoggedYet() = runBlocking {
        assertNull(db.historyEventDao().getLastTimestampForDomain("gym"))
    }

    @Test
    fun historyEvent_getLastTimestampForDomain_returnsTheMostRecentOne() = runBlocking {
        val dao = db.historyEventDao()
        dao.insert(HistoryEventEntity(domain = "gym", timestampEpochMillis = 1_000L, eventType = HistoryEventEntity.EVENT_COMPLETED))
        dao.insert(HistoryEventEntity(domain = "gym", timestampEpochMillis = 5_000L, eventType = HistoryEventEntity.EVENT_COMPLETED))

        assertEquals(5_000L, dao.getLastTimestampForDomain("gym"))
    }

    @Test
    fun historyEvent_getTimestampsIsoForDomain_bridgesIntoRoutineStatusInputShape() = runBlocking {
        // Confirms the epoch-millis -> ISO-local-date-time bridge documented
        // on HistoryEventEntity/HistoryEventDao actually feeds
        // com.lifeops.briefing.status() a shape it accepts and computes a
        // sane, non-throwing result from -- not just that the string parses.
        val dao = db.historyEventDao()
        val now = java.time.LocalDateTime.of(2026, 7, 30, 9, 0, 0)
        val threeDaysAgoMillis = now.minusDays(3)
            .atZone(java.time.ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        dao.insert(HistoryEventEntity(domain = "partner", timestampEpochMillis = threeDaysAgoMillis, eventType = HistoryEventEntity.EVENT_COMPLETED))

        val timestamps = dao.getTimestampsIsoForDomain("partner")
        val routine = com.lifeops.briefing.Routine(
            id = "partner", times = 1, perDays = 7, anchor = com.lifeops.briefing.Anchor.SINCE_LAST,
        )
        val status = com.lifeops.briefing.status(routine, timestamps, now) as com.lifeops.briefing.RoutineStatus.SinceLast

        assertEquals(3, status.daysSinceLast)
        assertTrue(!status.due) // 3 days < 7-day perDays threshold
    }

    // ---- TaskCacheEntity / TaskCacheDao ----

    private fun task(id: String, completed: Boolean = false, dueAtEpochMillis: Long? = null) = TaskCacheEntity(
        id = id,
        title = "Task $id",
        dueAtEpochMillis = dueAtEpochMillis,
        completed = completed,
        sourceJson = """{"id":"$id"}""",
    )

    @Test
    fun taskCache_insertThenGetById_returnsInsertedRow() = runBlocking {
        db.taskCacheDao().insert(task("t1", dueAtEpochMillis = 1_000L))

        assertEquals(task("t1", dueAtEpochMillis = 1_000L), db.taskCacheDao().getById("t1"))
    }

    @Test
    fun taskCache_update_persistsCompletedFlag() = runBlocking {
        db.taskCacheDao().insert(task("t1"))

        db.taskCacheDao().update(task("t1", completed = true))

        assertEquals(true, db.taskCacheDao().getById("t1")?.completed)
    }

    @Test
    fun taskCache_delete_removesTheRow() = runBlocking {
        db.taskCacheDao().insert(task("t1"))

        db.taskCacheDao().delete(task("t1"))

        assertNull(db.taskCacheDao().getById("t1"))
    }

    @Test
    fun taskCache_upsertAll_replacesExistingRowsByIdInOneBatch() = runBlocking {
        db.taskCacheDao().insert(task("t1", dueAtEpochMillis = 1_000L))

        db.taskCacheDao().upsertAll(
            listOf(
                task("t1", completed = true, dueAtEpochMillis = 1_000L),
                task("t2", dueAtEpochMillis = 2_000L),
            )
        )

        assertEquals(true, db.taskCacheDao().getById("t1")?.completed)
        assertEquals(listOf("t1", "t2"), db.taskCacheDao().getAll().map { it.id })
    }

    @Test
    fun taskCache_getIncomplete_excludesCompletedTasks() = runBlocking {
        val dao = db.taskCacheDao()
        dao.insert(task("done", completed = true, dueAtEpochMillis = 1_000L))
        dao.insert(task("pending", completed = false, dueAtEpochMillis = 2_000L))

        val incomplete = dao.getIncomplete()

        assertEquals(listOf("pending"), incomplete.map { it.id })
    }

    @Test
    fun taskCache_getIncomplete_sortsSoonestDueFirstWithNullsLast() = runBlocking {
        val dao = db.taskCacheDao()
        dao.insert(task("noDueDate", dueAtEpochMillis = null))
        dao.insert(task("soon", dueAtEpochMillis = 1_000L))
        dao.insert(task("later", dueAtEpochMillis = 5_000L))

        val incomplete = dao.getIncomplete()

        assertEquals(listOf("soon", "later", "noDueDate"), incomplete.map { it.id })
    }
}
