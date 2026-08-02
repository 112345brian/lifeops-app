package com.lifeops.briefing

import androidx.room.Room
import com.lifeops.briefing.data.HistoryEventEntity
import com.lifeops.briefing.data.LifeOpsDatabase
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
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Exercises WeeklyDigest.kt's pure gating/facts-assembly functions against a
 * real in-memory Room database (same pattern LifeOpsComputeWorkerTest.kt
 * already establishes) -- no network call, no AnthropicClient involved.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WeeklyDigestTest {

    private lateinit var db: LifeOpsDatabase
    private val zone = ZoneId.systemDefault()

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

    private fun epochMillisOf(dateTime: LocalDateTime): Long =
        dateTime.atZone(zone).toInstant().toEpochMilli()

    private suspend fun logEvent(domain: String, at: LocalDateTime) {
        db.historyEventDao().insert(
            HistoryEventEntity(
                domain = domain,
                timestampEpochMillis = epochMillisOf(at),
                eventType = HistoryEventEntity.EVENT_COMPLETED,
            ),
        )
    }

    // ---- isDigestDue ----

    @Test
    fun isDigestDue_falseOnNonSunday() {
        val monday = LocalDateTime.of(2026, 8, 3, 9, 0) // a Monday
        assertFalse(isDigestDue(monday, lastSentDate = null))
    }

    @Test
    fun isDigestDue_trueOnSunday_whenNeverSentBefore() {
        val sunday = LocalDateTime.of(2026, 8, 2, 9, 0) // a Sunday
        assertTrue(isDigestDue(sunday, lastSentDate = null))
    }

    @Test
    fun isDigestDue_falseOnSunday_whenAlreadySentToday() {
        val sunday = LocalDateTime.of(2026, 8, 2, 9, 0)
        assertFalse(isDigestDue(sunday, lastSentDate = "2026-08-02"))
    }

    @Test
    fun isDigestDue_trueOnSunday_whenLastSentWasADifferentDate() {
        val sunday = LocalDateTime.of(2026, 8, 2, 9, 0)
        assertTrue(isDigestDue(sunday, lastSentDate = "2026-07-26"))
    }

    // ---- buildDigestFacts ----

    @Test
    fun buildDigestFacts_countsDistinctDaysWithinCurrentWeekOnly() = runBlocking {
        val sunday = LocalDateTime.of(2026, 8, 2, 9, 0) // week is Mon 2026-07-27 .. Sun 2026-08-02
        // Two gym sessions on the same day -> counts as 1 day, not 2.
        logEvent("gym", LocalDateTime.of(2026, 7, 28, 7, 0))
        logEvent("gym", LocalDateTime.of(2026, 7, 28, 19, 0))
        logEvent("gym", LocalDateTime.of(2026, 7, 30, 7, 0))
        // Outside this week -- must not be counted.
        logEvent("gym", LocalDateTime.of(2026, 7, 20, 7, 0))
        logEvent("partner", LocalDateTime.of(2026, 7, 29, 20, 0))
        logEvent("friends", LocalDateTime.of(2026, 8, 1, 20, 0))
        logEvent("laundry", LocalDateTime.of(2026, 7, 27, 10, 0))
        logEvent("clean_room", LocalDateTime.of(2026, 7, 27, 10, 0))
        logEvent("clean_bathroom", LocalDateTime.of(2026, 7, 31, 10, 0))

        val facts = buildDigestFacts(db, sunday)

        assertEquals(2, facts.getInt("gym_done"))
        assertEquals(4, facts.getInt("gym_target"))
        assertEquals(3, facts.getInt("chores_done"))
        assertEquals(1, facts.getInt("saw_partner"))
        assertEquals(1, facts.getInt("saw_friends"))
    }

    @Test
    fun buildDigestFacts_zeroForEveryDomainWithNoHistory() = runBlocking {
        val sunday = LocalDateTime.of(2026, 8, 2, 9, 0)

        val facts = buildDigestFacts(db, sunday)

        assertEquals(0, facts.getInt("gym_done"))
        assertEquals(0, facts.getInt("chores_done"))
        assertEquals(0, facts.getInt("saw_partner"))
        assertEquals(0, facts.getInt("saw_friends"))
    }

    // ---- invariant: this LLM call path never touches Attention/attention_state ----

    @Test
    fun buildDigestFacts_hasNoAttentionOrAttentionStateKeys() = runBlocking {
        // WeeklyDigest.kt is a completely separate call path from
        // LifeOpsComputeWorker's Attention.compute() -- runComputeTick never
        // calls into AnthropicClient at all (see AnthropicClient.kt's
        // top-level kdoc: the retired daily-briefing narrative call was
        // deliberately NOT reintroduced). This test pins that structural
        // guarantee down concretely: the JSON this LLM call is built from
        // carries none of Attention's fields, so there is no way for the
        // LLM's own output to feed back into (or be mistaken for) the
        // deterministic attention_state/attention_reasons.
        val facts = buildDigestFacts(db, LocalDateTime.of(2026, 8, 2, 9, 0))

        assertFalse(facts.has("attention"))
        assertFalse(facts.has("attention_state"))
        assertFalse(facts.has("reasons"))
    }
}
