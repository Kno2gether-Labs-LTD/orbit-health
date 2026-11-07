package dev.avijit.orbithealth.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [
        SampleEntity::class, SleepSessionEntity::class, SleepSegmentEntity::class, WorkoutEntity::class,
        NetworkLogEntity::class,
    ],
    version = 2,
    exportSchema = true, // schema JSON lives in app/schemas; keep every version for migrations
)
abstract class OrbitDatabase : RoomDatabase() {
    abstract fun sampleDao(): SampleDao
    abstract fun sleepDao(): SleepDao
    abstract fun workoutDao(): WorkoutDao
    abstract fun networkLogDao(): NetworkLogDao

    companion object {
        /** Production: SQLCipher-encrypted with the Keystore-wrapped [DbKey]. */
        fun open(context: Context): OrbitDatabase {
            System.loadLibrary("sqlcipher")
            val app = context.applicationContext
            return Room.databaseBuilder(app, OrbitDatabase::class.java, "orbit.db")
                .openHelperFactory(SupportOpenHelperFactory(DbKey.get(app)))
                .addMigrations(*MIGRATIONS)
                .build()
        }

        /** v2 (Orbit Server): the `network_log` table. Health data tables are untouched. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `network_log` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`atMs` INTEGER NOT NULL, `host` TEXT NOT NULL, `purpose` TEXT NOT NULL, " +
                        "`bytesUp` INTEGER NOT NULL, `bytesDown` INTEGER NOT NULL, `ok` INTEGER NOT NULL)",
                )
            }
        }

        /** Every schema step; never destructive (this database is the only copy of the user's history). */
        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2)

        /** Tests: unencrypted in-memory database. */
        fun inMemory(context: Context): OrbitDatabase =
            Room.inMemoryDatabaseBuilder(context.applicationContext, OrbitDatabase::class.java).build()
    }
}
