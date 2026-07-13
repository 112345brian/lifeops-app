package com.lifeops.briefing

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.lifeops.briefing.data.NextTask
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercises PendingRemovals against a real (temp-file-backed) Preferences
 * DataStore -- the same MutablePreferences/Preferences types
 * updateAppWidgetState hands to production code -- rather than a mock, since
 * MutablePreferences has no public constructor. org.json.JSONObject (used
 * internally by PendingRemovals) also needs Robolectric's shadow, same as
 * NextTasksStateTest/BriefingStateTest. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PendingRemovalsTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val task = NextTask(id = "t1", title = "Finish reading", start = "2026-07-13T09:00:00")

    private var fileCounter = 0
    private fun newStore() = PreferenceDataStoreFactory.create(
        scope = CoroutineScope(Dispatchers.Unconfined),
    ) { File(tempFolder.root, "pending_removals_test_${fileCounter++}.preferences_pb") }

    private suspend fun add(store: androidx.datastore.core.DataStore<Preferences>, task: NextTask, now: Long) {
        store.edit { prefs -> PendingRemovals.add(prefs, task, now) }
    }

    private suspend fun clearConfirmed(store: androidx.datastore.core.DataStore<Preferences>, ids: Set<String>) {
        store.edit { prefs -> PendingRemovals.clearConfirmed(prefs, ids) }
    }

    private suspend fun takeExpired(store: androidx.datastore.core.DataStore<Preferences>, now: Long): List<NextTask> {
        var result: List<NextTask> = emptyList()
        store.edit { prefs -> result = PendingRemovals.takeExpired(prefs, now) }
        return result
    }

    private suspend fun readActive(store: androidx.datastore.core.DataStore<Preferences>, now: Long) =
        PendingRemovals.readActive(store.data.first(), now)

    @Test
    fun add_thenReadActive_returnsThePendingTask() = runBlocking {
        val store = newStore()
        val tappedAt = 1_000_000L

        add(store, task, tappedAt)
        val active = readActive(store, tappedAt)

        assertEquals(setOf("t1"), active.keys)
        assertEquals(task, active.getValue("t1").task)
        assertEquals(tappedAt, active.getValue("t1").tappedAtMillis)
    }

    @Test
    fun confirmedGone_clearsPendingRecord() = runBlocking {
        // optimistic-hide -> confirmed-gone clears pending: a fresh snapshot
        // that no longer contains the task id is an unambiguous completion
        // signal, so the pending record is dropped immediately (this is
        // what persistNextTasksForInstance does on such a snapshot).
        val store = newStore()
        val tappedAt = 1_000_000L
        add(store, task, tappedAt)

        val now = tappedAt + 30_000L // well within grace
        clearConfirmed(store, setOf("t1"))

        assertTrue(readActive(store, now).isEmpty())
        assertTrue(takeExpired(store, now).isEmpty())
    }

    @Test
    fun stillPresentWithinGrace_staysMasked() = runBlocking {
        // A fresh snapshot landing before the known ~2-min ingest cycle
        // catches up still contains the task -- expected, not a failure
        // signal, so it must stay in the active/masked set.
        val store = newStore()
        val tappedAt = 1_000_000L
        add(store, task, tappedAt)

        val now = tappedAt + 60_000L // 1 min later, within the 3-min grace window
        val active = readActive(store, now)

        assertEquals(setOf("t1"), active.keys)
        assertTrue(!active.getValue("t1").isPastGrace(now))
    }

    @Test
    fun stillPresentPastGrace_readyToRevertImmediately() = runBlocking {
        // optimistic-hide -> fresh-snapshot-still-has-it reverts immediately:
        // once past the grace window (but still well short of the 10-min
        // hard timeout), presence in a fresh snapshot is a genuine-failure
        // signal. isPastGrace flags this so persistNextTasksForInstance
        // clears the pending record right away instead of waiting out the
        // full hard timeout.
        val store = newStore()
        val tappedAt = 1_000_000L
        add(store, task, tappedAt)

        val now = tappedAt + (4 * 60 * 1000L) // past the 3-min grace, short of the 10-min hard cap
        val active = readActive(store, now)

        assertEquals(setOf("t1"), active.keys)
        assertTrue(active.getValue("t1").isPastGrace(now))
        assertTrue(!active.getValue("t1").isExpired(now))

        // The caller reacts to isPastGrace by clearing the record right away.
        clearConfirmed(store, setOf("t1"))
        assertTrue(readActive(store, now).isEmpty())
    }

    @Test
    fun pastHardTimeout_takeExpiredReturnsAndClearsIt() = runBlocking {
        // optimistic-hide -> timeout reverts: with no confirmation either
        // way, once the ~10-min hard timeout passes, takeExpired hands back
        // the original task (title/start intact) so the caller can restore
        // it to the visible list, and the pending record is gone afterward.
        val store = newStore()
        val tappedAt = 1_000_000L
        add(store, task, tappedAt)

        val now = tappedAt + (10 * 60 * 1000L) + 1L
        assertTrue(readActive(store, now).isEmpty()) // expired, so no longer "active"

        val expired = takeExpired(store, now)

        assertEquals(listOf(task), expired)
        assertTrue(takeExpired(store, now).isEmpty()) // already drained
    }

    @Test
    fun takeExpired_leavesStillActiveEntriesUntouched() = runBlocking {
        val store = newStore()
        val old = NextTask(id = "old", title = "Old task", start = null)
        val fresh = NextTask(id = "fresh", title = "Fresh task", start = null)
        add(store, old, 0L)
        add(store, fresh, 5 * 60 * 1000L)

        val now = (10 * 60 * 1000L) + 1L // "old" (tapped at 0) is expired; "fresh" isn't yet
        val expired = takeExpired(store, now)

        assertEquals(listOf(old), expired)
        assertEquals(setOf("fresh"), readActive(store, now).keys)
    }

    @Test
    fun malformedStoredJson_readsAsEmptyRatherThanThrowing() = runBlocking {
        val store = newStore()
        store.edit { prefs -> prefs[WidgetKeys.PENDING_REMOVED_JSON] = "not json" }

        assertTrue(readActive(store, 0L).isEmpty())
        assertTrue(takeExpired(store, 0L).isEmpty())
    }
}
