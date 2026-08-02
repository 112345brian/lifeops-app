package com.lifeops.briefing.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * The app's single Room database -- the on-device replacement for the
 * home-server Python backend's SQLite-backed persistence (`lifeops/db.py`'s
 * `history_events` table, `routine_store.py`'s `routines.json`, and
 * FlowSavvy's remote task list) that the data-gather layer, WorkManager
 * compute tick, chore/social re-derivation, and native UI work all build on
 * (see this file's originating task). Four tables today ([RoutineEntity],
 * [HistoryEventEntity], [TaskCacheEntity], [BlockedDayEntity]) -- no
 * relational joins between them are declared here, since [HistoryEventEntity.domain]
 * is intentionally a loose string key rather than a strict foreign key
 * (see that entity's kdoc), and [BlockedDayEntity] has no foreign key into
 * anything either (see its own kdoc).
 *
 * Version history:
 * - v1: the very first Room schema in this app (no prior on-device
 *   relational store to migrate from -- confirmed via grep, zero Room/SQLite
 *   usage existed before that change), so there was nothing to migrate away
 *   from yet: [RoutineEntity], [HistoryEventEntity], [TaskCacheEntity].
 * - v2: adds [BlockedDayEntity] (`blocked_days` table) for the on-device
 *   "block day" quick action (`ui/PanelActionsClient.blockDay`). Unlike v1,
 *   this IS a real schema change on top of a version that may already be
 *   installed, so [MIGRATION_1_2] is a real (not destructive) migration --
 *   just `CREATE TABLE IF NOT EXISTS`, since a new additive table can't lose
 *   data from any existing table.
 */
@Database(
    entities = [RoutineEntity::class, HistoryEventEntity::class, TaskCacheEntity::class, BlockedDayEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class LifeOpsDatabase : RoomDatabase() {
    abstract fun routineDao(): RoutineDao
    abstract fun historyEventDao(): HistoryEventDao
    abstract fun taskCacheDao(): TaskCacheDao
    abstract fun blockedDayDao(): BlockedDayDao

    companion object {
        private const val DATABASE_NAME = "lifeops.db"

        /** v1 -> v2: adds the `blocked_days` table (see this class's kdoc).
         * Matches [BlockedDayEntity]'s Room-generated column set exactly
         * (`date` TEXT primary key, `reason`/`source` nullable TEXT). */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `blocked_days` (" +
                        "`date` TEXT NOT NULL, `reason` TEXT, `source` TEXT, " +
                        "PRIMARY KEY(`date`))"
                )
            }
        }

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
                ).addMigrations(MIGRATION_1_2).build().also { instance = it }
            }
        }
    }
}
