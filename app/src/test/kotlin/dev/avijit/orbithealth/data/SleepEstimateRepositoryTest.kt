package dev.avijit.orbithealth.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.avijit.orbithealth.insights.EstimateStatus
import dev.avijit.orbithealth.insights.SleepKind
import dev.avijit.orbithealth.ring.HistoryBatch
import dev.avijit.orbithealth.ring.Metric
import dev.avijit.orbithealth.ring.Sample
import dev.avijit.orbithealth.ring.SampleSource
import dev.avijit.orbithealth.ring.SleepSegment
import dev.avijit.orbithealth.ring.SleepSession
import dev.avijit.orbithealth.ring.SleepStage
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Detection against the real (in-memory) database: idempotent, and it never undoes what the user decided. */
@RunWith(RobolectricTestRunner::class)
class SleepEstimateRepositoryTest {
    private lateinit var db: OrbitDatabase
    private lateinit var samples: SampleRepository
    private lateinit var estimates: SleepEstimateRepository
    private var now = Instant.parse("2026-11-07T13:00:00Z")
    private val clock = object : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = now
    }

    @Before fun setUp() {
        db = OrbitDatabase.inMemory(ApplicationProvider.getApplicationContext<Context>())
        samples = SampleRepository(db)
        estimates = SleepEstimateRepository(db, samples, clock, zone = { ZoneOffset.UTC })
    }
    @After fun tearDown() { db.close() }

    private fun at(s: String) = Instant.parse("2026-11-${s}Z")
    private fun every(from: Instant, to: Instant, minutes: Long, f: (Instant) -> Sample) =
        generateSequence(from) { it.plus(Duration.ofMinutes(minutes)) }.takeWhile { it < to }.map(f).toList()

    /** Four days awake at 72 bpm by day, asleep 56 bpm on the 7th 03:10–10:40 with no ring session. */
    private fun storeWeek(sleepFrom: Instant = at("07T03:10:00"), sleepTo: Instant = at("07T10:40:00")) = runBlocking {
        val hr = every(at("03T09:00:00"), at("07T13:00:00"), 10) { t ->
            val h = t.atZone(ZoneOffset.UTC).hour
            val v = when {
                t >= sleepFrom && t < sleepTo -> 56.0
                h in 9 until 23 || t >= sleepTo -> 72.0
                else -> 74.0 // awake late, not asleep: an evening on the sofa does not count
            }
            Sample(Metric.HEART_RATE, t, v, SampleSource.HISTORY)
        }
        val steps = every(at("03T09:00:00"), at("07T13:00:00"), 30) { t ->
            Sample(Metric.STEPS, t, if (t >= sleepFrom.minusSeconds(1799) && t < sleepTo) 0.0 else 300.0, SampleSource.HISTORY)
        }
        samples.store(HistoryBatch(hr + steps, emptyList(), emptyList()))
    }

    private fun all() = runBlocking { estimates.estimates(at("01T00:00:00"), at("09T00:00:00")).first() }

    @Test fun detectsOnceAndIsIdempotent() = runBlocking {
        storeWeek()
        val first = estimates.refresh()
        assertEquals(1, first.insert.size)
        assertTrue(estimates.refresh().isEmpty)
        val e = all().single()
        assertEquals(EstimateStatus.SUGGESTED, e.status)
        assertEquals(SleepKind.NIGHT, e.kind)
    }

    @Test fun dismissedNeverComesBack() = runBlocking {
        storeWeek()
        estimates.refresh()
        val id = all().single().id
        assertTrue(estimates.dismiss(id))
        now = now.plus(Duration.ofHours(2))
        estimates.refresh()
        assertEquals(listOf(EstimateStatus.DISMISSED), all().map { it.status })
        assertFalse("already answered", estimates.dismiss(id))
    }

    @Test fun confirmedWithAdjustmentIsKeptAndTeachesTheTuning() = runBlocking {
        storeWeek()
        estimates.refresh()
        val e = all().single()
        assertTrue(estimates.confirm(e.id, e.start.plus(Duration.ofMinutes(30)), e.end))
        estimates.refresh()
        val kept = all().single()
        assertEquals(EstimateStatus.CONFIRMED, kept.status)
        assertEquals(e.start.plus(Duration.ofMinutes(30)), kept.start)
        assertEquals(e.start, kept.detectedStart)
        assertTrue(estimates.tuning().startBiasMin > 0)
    }

    @Test fun aRingNightArrivingLaterRemovesTheSuggestion() = runBlocking {
        storeWeek()
        estimates.refresh()
        assertEquals(1, all().size)
        samples.store(
            HistoryBatch(
                emptyList(),
                listOf(SleepSession(at("07T03:00:00"), at("07T10:30:00"), listOf(SleepSegment(SleepStage.LIGHT, at("07T03:00:00"), 27_000)))),
                emptyList(),
            ),
        )
        estimates.refresh()
        assertTrue(all().isEmpty())
    }
}
