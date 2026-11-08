package dev.avijit.orbithealth.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1 → v2 adds `network_log` and keeps every health row. The v1 database is built from the committed schema
 * (schemas/.../1.json, Room's own CREATE statements and identity hash); Room then migrates it and validates the result
 * against the v2 schema it was compiled with (a mismatch throws on open). A Robolectric stand-in for MigrationTestHelper.
 */
@RunWith(RobolectricTestRunner::class)
class NetworkLogMigrationTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val name = "migration-test.db"

    @Before @After fun clean() { ctx.deleteDatabase(name) }

    private fun schema(version: Int) = JSONObject(File("schemas/dev.avijit.orbithealth.data.OrbitDatabase/$version.json").readText())
        .getJSONObject("database")

    @Test fun migrate1To2KeepsHealthDataAndAddsTheNetworkLog() = runBlocking {
        val v1 = schema(1)
        SQLiteDatabase.openOrCreateDatabase(ctx.getDatabasePath(name).apply { parentFile?.mkdirs() }, null).use { db ->
            val entities = v1.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val e = entities.getJSONObject(i)
                val table = e.getString("tableName")
                db.execSQL(e.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = e.optJSONArray("indices")
                if (indices != null) for (j in 0 until indices.length()) {
                    db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
            }
            val setup = v1.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            db.execSQL("INSERT INTO samples(metric, epochMs, value, source) VALUES ('HEART_RATE', 1000, 61.0, 'HISTORY')")
            db.execSQL("INSERT INTO sleep_sessions(startMs, endMs) VALUES (5000, 9000)")
            db.version = 1
        }

        val db = Room.databaseBuilder(ctx, OrbitDatabase::class.java, name)
            .addMigrations(*OrbitDatabase.MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            assertEquals(61.0, db.sampleDao().latest("HEART_RATE")!!.value, 0.0)
            assertEquals(1, db.sleepDao().sessionCount())
            db.networkLogDao().insert(NetworkLogEntity(atMs = 1, host = "h", purpose = "state", bytesUp = 10, bytesDown = 20, ok = true))
            assertEquals(30L, db.networkLogDao().bytesSince(0).first())
            // "Sent to your server" leaves out the app-update requests to GitHub.
            db.networkLogDao().insert(NetworkLogEntity(atMs = 2, host = "api.github.com", purpose = "update_check", bytesUp = 300, bytesDown = 9000, ok = true))
            assertEquals(10L, db.networkLogDao().bytesUpSince(0).first())
            // Room runs every later step too (2 → 3: sleep_estimates).
            assertEquals(3, db.openHelper.readableDatabase.version)
        } finally {
            db.close()
        }
    }
}
