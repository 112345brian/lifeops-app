package com.lifeops.briefing.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** CRUD for the singleton [ChoreCycleStateEntity] row. [upsert] is the
 * primary write path -- insert-or-replace by the fixed
 * [ChoreCycleStateEntity.SINGLETON_ID], matching `chore_state.json`'s own
 * "read whole, write whole" persistence shape. */
@Dao
interface ChoreCycleStateDao {
    @Query("SELECT * FROM chore_cycle_state WHERE id = ${ChoreCycleStateEntity.SINGLETON_ID}")
    suspend fun get(): ChoreCycleStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: ChoreCycleStateEntity)
}
