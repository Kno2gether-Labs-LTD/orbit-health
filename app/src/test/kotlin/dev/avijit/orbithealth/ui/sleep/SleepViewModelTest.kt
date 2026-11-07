package dev.avijit.orbithealth.ui.sleep

import dev.avijit.orbithealth.prefs.SettingsSnapshot
import dev.avijit.orbithealth.ring.Metric
import dev.avijit.orbithealth.ring.Sample
import dev.avijit.orbithealth.ring.SampleSource
import dev.avijit.orbithealth.ring.SleepSegment
import dev.avijit.orbithealth.ring.SleepSession
import dev.avijit.orbithealth.ring.SleepStage
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SleepViewModelTest {
    private val zone: ZoneId = ZoneOffset.UTC
    private val today: LocalDate = LocalDate.of(2026, 11, 5)
    private val now: Instant = today.atTime(9, 0).atZone(zone).toInstant()
    private fun at(day: LocalDate, h: Int, m: Int = 0): Instant = day.atTime(LocalTime.of(h, m)).atZone(zone).toInstant()

    private val samples = MutableStateFlow<List<Sample>>(emptyList())
    private val sessions = MutableStateFlow<List<SleepSession>>(emptyList())
    private val settings = MutableStateFlow(SettingsSnapshot(sleepGoalMin = 450))

    private fun TestScope.vm(): SleepViewModel {
        val d = UnconfinedTestDispatcher(testScheduler)
        val deps = SleepDeps(
            samples = { m, from, to -> samples.map { l -> l.filter { it.metric == m && it.time >= from && it.time <= to } } },
            sleepSessions = { from, to -> sessions.map { l -> l.filter { it.start >= from && it.start <= to } } },
            settings = settings,
            clock = Clock.fixed(now, zone),
            now = MutableStateFlow(now),
            zone = zone,
            compute = d,
        )
        val scope = CoroutineScope(backgroundScope.coroutineContext + d)
        return SleepViewModel(deps, scope).also { vm -> scope.launch { vm.state.collect {} } }
    }

    /** A night that starts the evening before [wake] at [bed] and runs the given (stage, minutes) runs. */
    private fun night(wake: LocalDate, bed: LocalTime, runs: List<Pair<SleepStage, Int>>): SleepSession {
        val start = wake.minusDays(1).atTime(bed).atZone(zone).toInstant().let { if (bed.hour < 12) it.plusSeconds(86_400) else it }
        var t = start
        val segs = runs.map { (st, min) -> SleepSegment(st, t, min * 60).also { t = t.plusSeconds(min * 60L) } }
        return SleepSession(start, t, segs)
    }

    private val earlyDeep = listOf(
        SleepStage.AWAKE to 10, SleepStage.LIGHT to 30, SleepStage.DEEP to 60, SleepStage.REM to 40,
        SleepStage.LIGHT to 120, SleepStage.DEEP to 15, SleepStage.REM to 60, SleepStage.LIGHT to 50, SleepStage.AWAKE to 16,
    )

    @Test fun stageTotalsMatchSegments() = runTest {
        sessions.value = listOf(night(today, LocalTime.of(23, 41), earlyDeep))
        val st = vm().state.value
        assertTrue(st.loaded)
        val n = st.nights.single()
        assertEquals(26 * 60, n.stageTotals[SleepStage.AWAKE])
        assertEquals(200 * 60, n.stageTotals[SleepStage.LIGHT])
        assertEquals(75 * 60, n.stageTotals[SleepStage.DEEP])
        assertEquals(100 * 60, n.stageTotals[SleepStage.REM])
        assertEquals(n.session.segments.sumOf { it.durationSec }, n.stageTotals.values.sum())
        // Asleep = everything but awake: 375 min.
        assertEquals("6h 15m", n.duration)
        assertEquals("Last night", n.title)
        assertEquals("23:41 → 06:22", n.timeRange)
        assertNotNull(n.score)
    }

    @Test fun lowestHrAndSpo2ComeFromTheNightOnly() = runTest {
        val s = night(today, LocalTime.of(23, 0), earlyDeep)
        sessions.value = listOf(s)
        fun hr(t: Instant, v: Double, src: SampleSource = SampleSource.HISTORY) = Sample(Metric.HEART_RATE, t, v, src)
        samples.value = listOf(
            hr(at(today.minusDays(1), 20), 48.0), // before bed: ignored
            hr(at(today, 1), 57.0), hr(at(today, 2), 54.4), hr(at(today, 3), 58.0),
            hr(at(today, 8), 50.0), // after waking: ignored
            Sample(Metric.SPO2, at(today, 1), 95.0, SampleSource.HISTORY),
            Sample(Metric.SPO2, at(today, 2), 98.0, SampleSource.HISTORY),
            Sample(Metric.SPO2, at(today, 12), 90.0, SampleSource.HISTORY),
        )
        val n = vm().state.value.nights.single()
        assertEquals(54, n.lowestHr)
        assertEquals(97, n.avgSpo2) // 96.5 rounds half up
        assertEquals(3, n.heartOverlay.size)
    }

    @Test fun nightMinisCarryTimeOfLowestHrHrvAndLowestSpo2() = runTest {
        val past = (1..4).map { i -> night(today.minusDays(i.toLong()), LocalTime.of(23, 0), earlyDeep) }
        val last = night(today, LocalTime.of(23, 0), earlyDeep)
        sessions.value = past + last
        fun s(m: Metric, t: Instant, v: Double) = Sample(m, t, v, SampleSource.HISTORY)
        samples.value = buildList {
            // Earlier nights: HRV 46 and 48 alternating, so the usual is 47.
            past.forEachIndexed { i, n -> add(s(Metric.HRV, n.start.plusSeconds(3600), if (i % 2 == 0) 46.0 else 48.0)) }
            add(s(Metric.HEART_RATE, at(today, 1), 57.0)); add(s(Metric.HEART_RATE, at(today, 3, 10), 54.0))
            add(s(Metric.HEART_RATE, at(today, 5), 58.0))
            add(s(Metric.HRV, at(today, 1), 50.0)); add(s(Metric.HRV, at(today, 3), 52.0))
            add(s(Metric.SPO2, at(today, 1), 97.0)); add(s(Metric.SPO2, at(today, 2), 92.0)); add(s(Metric.SPO2, at(today, 4), 98.0))
        }
        val n = vm().state.value.nights.last()
        assertEquals("03:10", n.lowestHrAt)
        assertEquals(51, n.avgHrv)
        assertEquals(4, n.hrvVsNormal) // 51 vs the usual 47
        assertEquals(92, n.lowSpo2)
        // Without three earlier nights of HRV there is no "normal" to compare with.
        assertNull(vm().state.value.nights.first().hrvVsNormal)
    }

    @Test fun stageSplitReadsHoursAndMinutes() {
        assertEquals("26m", SleepMath.stageHm(26 * 60))
        assertEquals("1h 48m", SleepMath.stageHm(108 * 60))
        assertEquals("3h 07m", SleepMath.stageHm(187 * 60 + 30))
        assertEquals("0m", SleepMath.stageHm(0))
    }

    @Test fun insightComparesBedtimeWithTheMedianAndDeepTiming() = runTest {
        // Usual bedtime 23:00 (one night at 00:30 does not move the median); last night 23:40.
        val past = (1..6).map { i ->
            night(today.minusDays(i.toLong()), if (i == 3) LocalTime.of(0, 30) else LocalTime.of(23, 0), earlyDeep)
        }
        sessions.value = past + night(today, LocalTime.of(23, 40), earlyDeep)
        val nights = vm().state.value.nights
        assertEquals(7, nights.size)
        assertEquals("Last night", nights.last().title)
        assertEquals("Deep sleep came early, as it should. Bedtime was 40 min later than usual.", nights.last().insight)

        // Most deep sleep late, bedtime 25 min early.
        val lateDeep = listOf(SleepStage.LIGHT to 200, SleepStage.DEEP to 70, SleepStage.REM to 60, SleepStage.LIGHT to 40)
        sessions.value = past + night(today, LocalTime.of(22, 35), lateDeep)
        assertEquals(
            "Most deep sleep came late in the night. Bedtime was 25 min earlier than usual.",
            vm().state.value.nights.last().insight,
        )
    }

    @Test fun noSleepIsAnEmptyState() = runTest {
        val st = vm().state.value
        assertTrue(st.loaded)
        assertTrue(st.nights.isEmpty())
        // A night without segments is not shown either (nothing to draw).
        sessions.value = listOf(SleepSession(at(today, 1), at(today, 6), emptyList()))
        assertTrue(vm().state.value.nights.isEmpty())
        assertNull(SleepMath.bedtimeInsight(night(today, LocalTime.of(23, 0), earlyDeep), emptyList(), zone))
    }
}
