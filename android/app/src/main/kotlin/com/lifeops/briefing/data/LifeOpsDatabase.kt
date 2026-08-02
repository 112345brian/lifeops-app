package com.lifeops.briefing.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * The app's single Room database -- the on-device replacement for the
 * home-server Python backend's SQLite-backed persistence (`lifeops/db.py`'s
 * `history_events` table, `routine_store.py`'s `routines.json`, and
 * FlowSavvy's remote task list) that the data-gather layer, WorkManager
 * compute tick, chore/social re-derivation, and native UI work all build on
 * (see this file's originating task). Deliberately just three tables today
 * ([RoutineEntity], [HistoryEventEntity], [TaskCacheEntity]) -- no
 * relational joins between them are declared here, since [HistoryEventEntity.domain]
 * is intentionally a loose string key rather than a strict foreign key
 * (see that entity's kdoc).
 *
 * Version starts at 1: this is the very first Room schema in this app (no
 * prior on-device relational store to migrate from -- confirmed via grep,
 * zero Room/SQLite usage existed before this change), so there is nothing
 * to migrate away from yet.
 */
@Database(
    entities = [RoutineEntity::class, HistoryEventEntity::class, TaskCacheEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class LifeOpsDatabase : RoomDatabase() {
    abstract fun routineDao(): RoutineDao
    abstract fun historyEventDao(): HistoryEventDao
    abstract fun taskCacheDao(): TaskCacheDao

    companion object {
        private const val DATABASE_NAME = "lifeops.db"

        @Volatile
        private var instance: LifeOpsDatabase? = null

        /** Standard double-checked-locking singleton -- same pattern this
         * app already uses elsewhere for shared, expensive-to-create
         * resources (see e.g. `WidgetConfigStore.kt`'s
         * EncryptedSharedPreferences accessor), applied to Room's own
         * documented `Room.databaseBuilder(...)` construction. */
        fun getInstance(context: Context): LifeOpsDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    LifeOpsDatabase::class.java,
                    DATABASE_NAME,
                ).build().also { instance = it }
            }
        }
    }
}
