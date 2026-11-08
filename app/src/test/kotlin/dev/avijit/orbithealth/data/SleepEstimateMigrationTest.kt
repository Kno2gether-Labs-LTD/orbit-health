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
 * v2 → v3 adds `sleep_estimates` and keeps every existing row, built from the committed v2 schema as in
 * [NetworkLogMigrationTest]; Room validates the migrated database against the v3 schema on open. Also v1 → v3 in one go.
 */
@RunWith(RobolectricTestRunner::class)
class SleepEstimateMigrationTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val name = "estimate-migration-test.db"

    @Before @After fun clean() { ctx.deleteDatabase(name) }

    private fun schema(version: Int) = JSONObject(File("schemas/dev.avijit.orbithealth.data.OrbitDatabase/$version.json").readText())
        .getJSONObject("database")

    private fun create(version: Int, rows: SQLiteDatabase.() -> Unit) {
        val v = schema(version)
        SQLiteDatabase.openOrCreateDatabase(ctx.getDatabasePath(name).apply { parentFile?.mkdirs() }, null).use { db ->
            val entities = v.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val e = entities.getJSONObject(i)
                val table = e.getString("tableName")
                db.execSQL(e.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = e.optJSONArray("indices")
                if (indices != null) for (j in 0 until indices.length()) {
                    db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
            }
            val setup = v.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            db.rows()
            db.version = version
        }
    }

    private fun open() = Room.databaseBuilder(ctx, OrbitDatabase::class.java, name)
        .addMigrations(*OrbitDatabase.MIGRATIONS)
        .allowMainThreadQueries()
        .build()

    private fun estimate() = SleepEstimateEntity(
        kind = "NIGHT", status = "SUGGESTED", startMs = 1_000, endMs = 9_000, detectedStartMs = 1_000, detectedEndMs = 9_000,
        lowestHr = 55, lowestSpo2 = null, confidence = 0.9, createdAtMs = 10_000, decidedAtMs = null,
    )

    @Test fun migrate2To3KeepsDataAndAddsEstimates() = runBlocking {
        create(2) {
            execSQL("INSERT INTO samples(metric, epochMs, value, source) VALUES ('HEART_RATE', 1000, 61.0, 'HISTORY')")
            execSQL("INSERT INTO sleep_sessions(startMs, endMs) VALUES (5000, 9000)")
            execSQL("INSERT INTO network_log(atMs, host, purpose, bytesUp, bytesDown, ok) VALUES (1, 'h', 'state', 10, 20, 1)")
        }
        val db = open()
        try {
            assertEquals(61.0, db.sampleDao().latest("HEART_RATE")!!.value, 0.0)
            assertEquals(1, db.sleepDao().sessionCount())
            assertEquals(30L, db.networkLogDao().bytesSince(0).first())
            val id = db.sleepEstimateDao().insert(estimate())
            assertEquals(1, db.sleepEstimateDao().dismiss(id, 11_000))
            assertEquals("DISMISSED", db.sleepEstimateDao().byId(id)!!.status)
            assertEquals(3, db.openHelper.readableDatabase.version)
        } finally {
            db.close()
        }
    }

    @Test fun migrate1To3() = runBlocking {
        create(1) { execSQL("INSERT INTO samples(metric, epochMs, value, source) VALUES ('HEART_RATE', 1000, 61.0, 'HISTORY')") }
        val db = open()
        try {
            assertEquals(61.0, db.sampleDao().latest("HEART_RATE")!!.value, 0.0)
            assertEquals(0, db.sleepEstimateDao().count())
            assertEquals(3, db.openHelper.readableDatabase.version)
        } finally {
            db.close()
        }
    }
}
