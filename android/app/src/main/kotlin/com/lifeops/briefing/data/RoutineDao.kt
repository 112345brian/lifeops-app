package com.lifeops.briefing.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

/** CRUD for [RoutineEntity]. `id` is a stable, small, well-known set today
 * ("gym"/"partner"/"friends"/"meal", per `routine_store.py`'s
 * `_DEFAULTS`), so [upsert] (insert-or-replace by primary key) is the
 * natural "save a routine" primitive -- matching `save_routine`'s own
 * "merge a partial override, write the whole merged record back" contract
 * more directly than a separate insert-only + update-only pair would for
 * every call site. Plain [insert]/[update] are still exposed too, for a
 * caller that specifically needs insert-must-not-clobber or
 * update-must-match-an-existing-row semantics. */
@Dao
interface RoutineDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(routine: RoutineEntity)

    @Update
    suspend fun update(routine: RoutineEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(routine: RoutineEntity)

    @Delete
    suspend fun delete(routine: RoutineEntity)

    @Query("DELETE FROM routines WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM routines WHERE id = :id")
    suspend fun getById(id: String): RoutineEntity?

    @Query("SELECT * FROM routines ORDER BY id")
    suspend fun getAll(): List<RoutineEntity>
}
