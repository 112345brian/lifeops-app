package com.lifeops.briefing.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** CRUD for [BlockedDayEntity]. [upsert] is the primary write path (the
 * block-day quick action, `ui/PanelActionsClient.blockDay`) -- insert-or-
 * replace by [BlockedDayEntity.date], matching `_block_day`'s own idempotent
 * "append if not already present" behavior for `_sched_blocks()`. */
@Dao
interface BlockedDayDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(day: BlockedDayEntity)

    @Delete
    suspend fun delete(day: BlockedDayEntity)

    @Query("DELETE FROM blocked_days WHERE date = :date")
    suspend fun deleteByDate(date: String)

    @Query("SELECT * FROM blocked_days WHERE date = :date")
    suspend fun getByDate(date: String): BlockedDayEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM blocked_days WHERE date = :date)")
    suspend fun isBlocked(date: String): Boolean

    @Query("SELECT * FROM blocked_days ORDER BY date")
    suspend fun getAll(): List<BlockedDayEntity>

    /** Every blocked date within [startDate]..[endDate] inclusive (both
     * ISO-8601 calendar-date strings), as raw strings -- the shape
     * `GymSchedule.kt`'s [gymDaysRespectingBlocks][com.lifeops.briefing.gymDaysRespectingBlocks]
     * needs to build a candidate [com.lifeops.briefing.GymDay] window
     * without a query per day. */
    @Query("SELECT date FROM blocked_days WHERE date >= :startDate AND date <= :endDate ORDER BY date")
    suspend fun getDatesBetween(startDate: String, endDate: String): List<String>
}
