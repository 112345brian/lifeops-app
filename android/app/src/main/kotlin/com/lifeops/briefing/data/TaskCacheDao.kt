package com.lifeops.briefing.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

/** CRUD for [TaskCacheEntity]. [upsertAll] is the primary write path: a
 * periodic FlowSavvy refresh (`LifeOpsComputeWorker`'s eventual
 * Room-backed successor) fetches a whole task list at once and needs to
 * write it back as one batch, insert-or-replace by [TaskCacheEntity.id]. */
@Dao
interface TaskCacheDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(task: TaskCacheEntity)

    @Update
    suspend fun update(task: TaskCacheEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: TaskCacheEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(tasks: List<TaskCacheEntity>)

    @Delete
    suspend fun delete(task: TaskCacheEntity)

    @Query("DELETE FROM task_cache WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM task_cache WHERE id = :id")
    suspend fun getById(id: String): TaskCacheEntity?

    @Query("SELECT * FROM task_cache ORDER BY dueAtEpochMillis IS NULL, dueAtEpochMillis, id")
    suspend fun getAll(): List<TaskCacheEntity>

    /** The "show/act on tasks even if the network fetch failed" query this
     * task's brief calls out explicitly: every not-yet-completed cached
     * task, soonest-due first (nulls -- no known due date -- last). */
    @Query("SELECT * FROM task_cache WHERE completed = 0 ORDER BY dueAtEpochMillis IS NULL, dueAtEpochMillis, id")
    suspend fun getIncomplete(): List<TaskCacheEntity>
}
