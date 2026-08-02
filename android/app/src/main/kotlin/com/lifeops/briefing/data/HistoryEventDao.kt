package com.lifeops.briefing.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

/** CRUD + cadence-query surface for [HistoryEventEntity], mirroring
 * `lifeops/history.py`'s actual query shapes (`events(action)`, `last(action)`,
 * `days_with(action, start, end)`) rather than a generic ORM-shaped
 * surface, since those are exactly the queries the ported
 * gym/chore/social adherence logic needs fed to it. */
@Dao
interface HistoryEventDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(event: HistoryEventEntity): Long

    @Update
    suspend fun update(event: HistoryEventEntity)

    @Delete
    suspend fun delete(event: HistoryEventEntity)

    @Query("DELETE FROM history_events WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM history_events WHERE id = :id")
    suspend fun getById(id: Long): HistoryEventEntity?

    @Query("SELECT * FROM history_events ORDER BY timestampEpochMillis, id")
    suspend fun getAll(): List<HistoryEventEntity>

    /** Ports `history.py`'s `events(action)`: every event for one domain,
     * oldest first. */
    @Query("SELECT * FROM history_events WHERE domain = :domain ORDER BY timestampEpochMillis, id")
    suspend fun getForDomain(domain: String): List<HistoryEventEntity>

    /** Ports `history.py`'s `events(action=None)` scoped to a time range --
     * "everything logged since (or at) a given instant," across every
     * domain. The per-domain equivalent is [getForDomainSince]. */
    @Query("SELECT * FROM history_events WHERE timestampEpochMillis >= :sinceEpochMillis ORDER BY timestampEpochMillis, id")
    suspend fun getSince(sinceEpochMillis: Long): List<HistoryEventEntity>

    @Query(
        "SELECT * FROM history_events WHERE domain = :domain AND timestampEpochMillis >= :sinceEpochMillis " +
            "ORDER BY timestampEpochMillis, id"
    )
    suspend fun getForDomainSince(domain: String, sinceEpochMillis: Long): List<HistoryEventEntity>

    /** Ports `history.py`'s `last(action)`: the most recent event's
     * timestamp for one domain, or `null` if there is none yet (matches
     * `last()` returning `None` on an empty result). */
    @Query("SELECT MAX(timestampEpochMillis) FROM history_events WHERE domain = :domain")
    suspend fun getLastTimestampForDomain(domain: String): Long?

    /**
     * The bridge [HistoryEventEntity]'s kdoc promises: every event
     * timestamp for [domain], already converted to the naive ISO-8601
     * local-date-time [String] shape
     * [com.lifeops.briefing.status]/[com.lifeops.briefing.statusSinceLast]
     * (see `Routine.kt`) take as `eventTimestamps`, oldest first. A default
     * method (not a `@Query`) so the one-line epoch-millis-to-ISO-string
     * conversion lives here once instead of being repeated at every call
     * site that feeds a [RoutineEntity]'s history into those functions.
     */
    suspend fun getTimestampsIsoForDomain(domain: String): List<String> =
        getForDomain(domain).map { it.toIsoLocalDateTime() }
}
