package dev.avijit.orbithealth.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.avijit.orbithealth.ring.*
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SampleRepositoryTest {
    private lateinit var db: OrbitDatabase
    private lateinit var repo: SampleRepository
    private val t0 = Instant.parse("2026-01-01T00:00:00Z")

    @Before fun setUp() {
        db = OrbitDatabase.inMemory(ApplicationProvider.getApplicationContext<Context>())
        repo = SampleRepository(db)
    }
    @After fun tearDown() { db.close() }

    private fun hr(min: Long, v: Double, src: SampleSource = SampleSource.HISTORY) =
        Sample(Metric.HEART_RATE, t0.plusSeconds(min * 60), v, src)

    private fun batch(samples: List<Sample> = listOf(hr(0, 60.0), hr(1, 61.0)),
                      sleep: List<SleepSession> = emptyList(),
                      workouts: List<WorkoutRecord> = emptyList()) = HistoryBatch(samples, sleep, workouts)

    private fun all() = runBlocking { repo.samples(Metric.HEART_RATE, t0.minusSeconds(1), t0.plusSeconds(86400)).first() }

    @Test fun insertingSameSamplesTwiceKeepsOneCopy() = runBlocking {
        repo.store(batch()); repo.store(batch())
        assertEquals(2, all().size)
    }

    @Test fun firstWrittenValueWins() = runBlocking {
        repo.storeLive(hr(0, 70.0, SampleSource.LIVE))
        repo.store(batch())
        assertEquals(70.0, all().first().value, 0.0)
    }

    @Test fun sleepSessionRoundTrips() = runBlocking {
        val s = SleepSession(t0, t0.plusSeconds(3600), listOf(
            SleepSegment(SleepStage.LIGHT, t0, 1800), SleepSegment(SleepStage.DEEP, t0.plusSeconds(1800), 1800)))
        repo.store(batch(sleep = listOf(s))); repo.store(batch(sleep = listOf(s)))
        val got = repo.sleepSessions(t0.minusSeconds(10), t0.plusSeconds(7200)).first()
        assertEquals(listOf(s), got)
    }

    @Test fun workoutRoundTrips() = runBlocking {
        val w = WorkoutRecord(t0, t0.plusSeconds(600), 1000, 800, 50, null, 120)
        repo.store(batch(workouts = listOf(w)))
        assertEquals(listOf(w), repo.workouts(t0.minusSeconds(1), t0.plusSeconds(1000)).first())
    }

    @Test fun samplesQueryIsTimeBounded() = runBlocking {
        repo.store(batch(listOf(hr(0, 1.0), hr(10, 2.0), hr(20, 3.0))))
        val got = repo.samples(Metric.HEART_RATE, t0.plusSeconds(300), t0.plusSeconds(900)).first()
        assertEquals(listOf(2.0), got.map { it.value })
        assertTrue(repo.samples(Metric.SPO2, t0, t0.plusSeconds(9999)).first().isEmpty())
    }

    @Test fun latestReturnsNewest() = runBlocking {
        assertNull(repo.latest(Metric.HEART_RATE))
        repo.store(batch(listOf(hr(0, 1.0), hr(10, 2.0))))
        assertEquals(2.0, repo.latest(Metric.HEART_RATE)!!.value, 0.0)
    }

    @Test fun storeIsAtomic() = runBlocking {
        val exploding = object : AbstractList<SleepSession>() {
            override val size get() = 1
            override fun get(index: Int): SleepSession = throw IllegalStateException("boom")
        }
        val r = runCatching { repo.store(batch(sleep = exploding)) }
        assertTrue(r.isFailure)
        assertTrue(all().isEmpty())
    }

    @Test fun countsReflectStoredRows() = runBlocking {
        val s = SleepSession(t0, t0.plusSeconds(3600), listOf(SleepSegment(SleepStage.LIGHT, t0, 3600)))
        val w = WorkoutRecord(t0, t0.plusSeconds(600), 1000, 800, 50, null, 120)
        repo.store(batch(listOf(hr(0, 1.0), hr(1, 2.0), Sample(Metric.SPO2, t0, 97.0, SampleSource.HISTORY)),
            sleep = listOf(s), workouts = listOf(w)))
        assertEquals(StoreCounts(mapOf("HEART_RATE" to 2, "SPO2" to 1), 1, 1, 1), repo.counts())
    }

    private fun bucket(m: Metric, v: Double) = Sample(m, t0, v, SampleSource.HISTORY)
    private fun valueOf(m: Metric) = runBlocking { repo.samples(m, t0.minusSeconds(1), t0.plusSeconds(1)).first().single().value }

    @Test fun bucketRewrittenLargerIsUpdated() = runBlocking {
        val maxMetrics = listOf(Metric.STEPS, Metric.DISTANCE_M, Metric.CALORIES_KCAL, Metric.STEPS_DAY_TOTAL)
        repo.store(batch(maxMetrics.map { bucket(it, 100.0) }))
        repo.store(batch(maxMetrics.map { bucket(it, 250.0) }))
        maxMetrics.forEach { assertEquals("$it", 250.0, valueOf(it), 0.0) }
    }

    @Test fun bucketRewrittenSmallerIsIgnored() = runBlocking {
        repo.store(batch(listOf(bucket(Metric.STEPS, 400.0), bucket(Metric.STEPS_DAY_TOTAL, 1200.0))))
        repo.store(batch(listOf(bucket(Metric.STEPS, 120.0), bucket(Metric.STEPS_DAY_TOTAL, 900.0))))
        assertEquals(400.0, valueOf(Metric.STEPS), 0.0)
        assertEquals(1200.0, valueOf(Metric.STEPS_DAY_TOTAL), 0.0)
    }

    @Test fun nonIntervalMetricsKeepFirstWriteEvenWhenLarger() = runBlocking {
        repo.store(batch(listOf(hr(0, 60.0))))
        repo.store(batch(listOf(hr(0, 90.0))))
        assertEquals(60.0, all().single().value, 0.0)
    }

    @Test fun sessionEndExtends() = runBlocking {
        val provisional = SleepSession(t0, t0.plusSeconds(3600), listOf(SleepSegment(SleepStage.LIGHT, t0, 3600)))
        val full = SleepSession(t0, t0.plusSeconds(7200), listOf(
            SleepSegment(SleepStage.LIGHT, t0, 1800), // same key as the provisional segment: first write kept
            SleepSegment(SleepStage.DEEP, t0.plusSeconds(3600), 3600),
        ))
        repo.store(batch(sleep = listOf(provisional)))
        repo.store(batch(sleep = listOf(full)))
        val got = repo.sleepSessions(t0.minusSeconds(10), t0.plusSeconds(9000)).first().single()
        assertEquals(t0.plusSeconds(7200), got.end)
        assertEquals(listOf(SleepSegment(SleepStage.LIGHT, t0, 3600), SleepSegment(SleepStage.DEEP, t0.plusSeconds(3600), 3600)), got.segments)
        // A shorter rewrite never shortens the session.
        repo.store(batch(sleep = listOf(provisional)))
        assertEquals(t0.plusSeconds(7200), repo.sleepSessions(t0.minusSeconds(10), t0.plusSeconds(9000)).first().single().end)
    }

    // --- A ring's history copy of an on-demand (Measure sheet) reading -------------------------------------------

    private val tm = Instant.parse("2026-11-06T15:40:21Z") // when the MEASURED result arrived
    private fun at(metric: Metric, offsetSec: Long, v: Double, src: SampleSource = SampleSource.HISTORY) =
        Sample(metric, tm.plusSeconds(offsetSec), v, src)
    private fun rows(metric: Metric) =
        runBlocking { repo.samples(metric, tm.minusSeconds(86_400), tm.plusSeconds(86_400)).first() }

    @Test fun historyCopyOfAMeasuredReadingIsNotStoredTwice() = runBlocking {
        repo.store(batch(listOf(at(Metric.HEART_RATE, 0, 89.0, SampleSource.MEASURED))))
        repo.store(batch(listOf(at(Metric.HEART_RATE, -600, 78.0), at(Metric.HEART_RATE, 20, 89.0))))
        assertEquals(
            listOf(at(Metric.HEART_RATE, -600, 78.0), at(Metric.HEART_RATE, 0, 89.0, SampleSource.MEASURED)),
            rows(Metric.HEART_RATE),
        )
    }

    @Test fun historySampleWithADifferentValueIsKept() = runBlocking {
        repo.store(batch(listOf(at(Metric.HEART_RATE, 0, 89.0, SampleSource.MEASURED))))
        repo.store(batch(listOf(at(Metric.HEART_RATE, 1, 90.0), at(Metric.HEART_RATE, 20, 88.0))))
        assertEquals(listOf(89.0, 90.0, 88.0), rows(Metric.HEART_RATE).map { it.value })
    }

    @Test fun historySampleWithTheSameValueFiveMinutesAwayIsKept() = runBlocking {
        repo.store(batch(listOf(at(Metric.HEART_RATE, 0, 89.0, SampleSource.MEASURED))))
        repo.store(batch(listOf(at(Metric.HEART_RATE, 300, 89.0), at(Metric.HEART_RATE, -300, 89.0))))
        assertEquals(3, rows(Metric.HEART_RATE).size)
    }

    @Test fun hrvCopyInAnAllRecordIsDroppedButTheStepTotalIsKept() = runBlocking {
        repo.store(batch(listOf(at(Metric.HRV, 0, 172.0, SampleSource.MEASURED))))
        repo.store(batch(listOf(at(Metric.HRV, 15, 172.4), at(Metric.STEPS_DAY_TOTAL, 15, 4_321.0))))
        assertEquals(listOf(at(Metric.HRV, 0, 172.0, SampleSource.MEASURED)), rows(Metric.HRV))
        assertEquals(listOf(4_321.0), rows(Metric.STEPS_DAY_TOTAL).map { it.value })
    }

    @Test fun stressIsComparedToOneDecimal() = runBlocking {
        repo.store(batch(listOf(at(Metric.STRESS, 0, 31.2, SampleSource.MEASURED))))
        repo.store(batch(listOf(at(Metric.STRESS, 10, 31.24), at(Metric.STRESS, 20, 31.4))))
        assertEquals(listOf(31.2, 31.4), rows(Metric.STRESS).map { it.value })
    }

    @Test fun spo2WithoutAHistoryCopyIsUnchanged() = runBlocking {
        repo.store(batch(listOf(at(Metric.SPO2, 0, 96.0, SampleSource.MEASURED))))
        repo.store(batch(listOf(at(Metric.SPO2, -1_800, 97.0), at(Metric.HEART_RATE, 30, 96.0))))
        assertEquals(listOf(97.0, 96.0), rows(Metric.SPO2).map { it.value })
        assertEquals(listOf(96.0), rows(Metric.HEART_RATE).map { it.value }) // same value, other metric: kept
    }
}
