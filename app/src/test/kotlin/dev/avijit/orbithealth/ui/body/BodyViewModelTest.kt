package dev.avijit.orbithealth.ui.body

import dev.avijit.orbithealth.prefs.SettingsSnapshot
import dev.avijit.orbithealth.ring.Metric
import dev.avijit.orbithealth.ring.Sample
import dev.avijit.orbithealth.ring.SampleSource
import dev.avijit.orbithealth.ring.SleepSegment
import dev.avijit.orbithealth.ring.SleepSession
import dev.avijit.orbithealth.ring.SleepStage
import dev.avijit.orbithealth.ring.WorkoutRecord
import dev.avijit.orbithealth.sync.LiveUnavailable
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BodyViewModelTest {
    private val zone: ZoneId = ZoneOffset.UTC
    private val today: LocalDate = LocalDate.of(2026, 11, 5)
    private val now: Instant = today.atTime(14, 0).atZone(zone).toInstant()
    private fun at(day: LocalDate, h: Int, m: Int = 0): Instant = day.atTime(LocalTime.of(h, m)).atZone(zone).toInstant()
    private fun s(m: Metric, t: Instant, v: Double, src: SampleSource = SampleSource.HISTORY) = Sample(m, t, v, src)

    private val samples = MutableStateFlow<List<Sample>>(emptyList())
    private val sessions = MutableStateFlow<List<SleepSession>>(emptyList())
    private val settings = MutableStateFlow(SettingsSnapshot(stepGoalBase = 8000))
    private val workouts = MutableStateFlow<List<WorkoutRecord>>(emptyList())

    private fun TestScope.vm(): BodyViewModel {
        val d = UnconfinedTestDispatcher(testScheduler)
        val deps = BodyDeps(
            samples = { m, from, to -> samples.map { l -> l.filter { it.metric == m && it.time >= from && it.time <= to } } },
            sleepSessions = { from, to -> sessions.map { l -> l.filter { it.start >= from && it.start <= to } } },
            workouts = { from, to -> workouts.map { l -> l.filter { it.start >= from && it.start <= to } } },
            settings = settings,
            clock = Clock.fixed(now, zone),
            now = MutableStateFlow(now),
            zone = zone,
            compute = d,
        )
        val scope = CoroutineScope(backgroundScope.coroutineContext + d)
        return BodyViewModel(deps, scope).also { vm ->
            scope.launch { vm.heart.collect {} }
            scope.launch { vm.stress.collect {} }
            scope.launch { vm.activity.collect {} }
            scope.launch { vm.trends.collect {} }
        }
    }

    private fun night(wake: LocalDate): SleepSession {
        val start = at(wake.minusDays(1), 23, 0)
        val runs = listOf(SleepStage.LIGHT to 120, SleepStage.DEEP to 90, SleepStage.REM to 90, SleepStage.LIGHT to 150, SleepStage.AWAKE to 10)
        var t = start
        val segs = runs.map { (st, min) -> SleepSegment(st, t, min * 60).also { t = t.plusSeconds(min * 60L) } }
        return SleepSession(start, t, segs)
    }

    @Test fun restingHrUsesSleepWindow() = runTest {
        val n = night(today)
        // Sleep HR 58 every 10 min with a 40-minute trough of 52; daytime 75 with one low LIVE outlier.
        val sleepHr = (0 until 46).map { i ->
            val t = n.start.plusSeconds(i * 600L)
            s(Metric.HEART_RATE, t, if (i in 20..23) 52.0 else 58.0)
        }
        val dayHr = (0 until 30).map { s(Metric.HEART_RATE, at(today, 8).plusSeconds(it * 600L), 75.0) } +
            s(Metric.HEART_RATE, at(today, 12), 40.0, SampleSource.LIVE)
        samples.value = sleepHr + dayHr
        sessions.value = listOf(n)
        val vm = vm()
        assertEquals(52, vm.heart.value.resting)

        // Without sleep: the 10th percentile of the day's readings.
        sessions.value = emptyList()
        samples.value = (1..10).map { s(Metric.HEART_RATE, at(today, it), 60.0 + it) }
        assertEquals(61, vm().heart.value.resting)
    }

    @Test fun trendDirectionSign() = runTest {
        // Unit rule: the fitted change over the range, rounded; falling → "↓", rising → "↑", flat → "steady".
        assertEquals("Resting heart rate ↓ 3 bpm", BodyMath.trendCallout("Resting heart rate", listOf(61f, 60.5f, 60f, 59f, 58f), "bpm"))
        assertEquals("HRV ↑ 6 ms", BodyMath.trendCallout("HRV", listOf(42f, 44f, 45f, 46f, 48f), "ms"))
        assertEquals("HRV · steady", BodyMath.trendCallout("HRV", listOf(45f, 45.2f, 44.9f), "ms"))
        // A rounded change that is tiny next to the level is steady too.
        assertEquals("Steps · steady", BodyMath.trendCallout("Steps", listOf(6000f, 6010f, 5990f, 6012f), ""))

        // Through the view model: 30 nights with resting HR falling from 62 to 59.
        val days = 30
        val all = mutableListOf<Sample>()
        val nights = mutableListOf<SleepSession>()
        for (i in days - 1 downTo 0) {
            val d = today.minusDays(i.toLong())
            val n = night(d)
            nights += n
            val rhr = 59.0 + 3.0 * i / (days - 1)
            all += (0 until 46).map { k -> s(Metric.HEART_RATE, n.start.plusSeconds(k * 600L), rhr) }
            all += s(Metric.HRV, at(d, 3), 45.0)
            all += s(Metric.STEPS, at(d, 9), 6000.0 + 50 * (days - i))
            if (i == 0) all += s(Metric.STEPS, at(d, 10), -5000.0) // today so far would read as a fall: left out
        }
        samples.value = all
        sessions.value = nights
        val vm = vm()
        vm.selectTrendsRange(Range.MONTH)
        val t = vm.trends.value
        assertEquals(Range.MONTH, t.range)
        assertEquals(10, t.restingHr.values.size) // 3-day points
        assertTrue(t.restingHr.callout, t.restingHr.callout.startsWith("Resting heart rate ↓ 3"))
        assertTrue(t.steps.callout, t.steps.callout.startsWith("Steps ↑"))
        assertEquals("HRV · steady", t.hrv.callout)
        assertTrue(t.sleep.callout, t.sleep.callout.contains("avg 7h 30m"))

        // 7d shows only the last week; 1y is capped to the data there is.
        vm.selectTrendsRange(Range.WEEK)
        assertEquals(7, vm.trends.value.restingHr.values.size)
        vm.selectTrendsRange(Range.YEAR)
        assertTrue(vm.trends.value.restingHr.values.size in 4..6) // ~30 days in weekly points
    }

    @Test fun stressAndSpo2Day() = runTest {
        val n = night(today)
        sessions.value = listOf(n)
        samples.value = listOf(
            s(Metric.STRESS, at(today, 8), 20.0), s(Metric.STRESS, at(today, 9), 30.0),
            s(Metric.STRESS, at(today, 10, 40), 71.0), s(Metric.STRESS, at(today, 11), 25.0),
        ) + listOf(97.0, 93.0, 92.0, 97.0, 96.0, 93.5, 98.0).mapIndexed { i, v -> s(Metric.SPO2, at(today, 0, 0).plusSeconds(i * 1800L), v) }
        val st = vm().stress.value
        assertEquals(Range.DAY, st.range)
        assertEquals(4, st.stress.size)
        assertEquals(37, st.avg)
        assertEquals("peak 71 at 10:40", st.peakLabel)
        assertEquals(7, st.spo2.size)
        assertEquals(95, st.spo2Avg)
        assertEquals(92, st.spo2Low)
        // 93, 92 in a row is one dip; 93.5 is a second.
        assertEquals(2, st.dips)
        assertEquals("2 short dips below 94%: normal for most people, worth watching if frequent", st.dipCopy)
    }

    @Test fun activityTotals() = runTest {
        samples.value = listOf(
            s(Metric.STEPS, at(today, 9), 400.0), s(Metric.STEPS, at(today, 9, 30), 214.0), s(Metric.STEPS, at(today, 11), 300.0),
            s(Metric.DISTANCE_M, at(today, 9), 400.0), s(Metric.DISTANCE_M, at(today, 11), 194.0),
            s(Metric.CALORIES_KCAL, at(today, 9), 30.0), s(Metric.CALORIES_KCAL, at(today, 11), 16.0),
        )
        val a = vm().activity.value
        assertEquals(914, a.steps)
        assertEquals(8000, a.goal)
        assertEquals(614, a.perHour[9])
        assertEquals(300, a.perHour[11])
        assertEquals(0.594, a.distanceKm, 1e-9)
        assertEquals(46, a.kcal)
        assertTrue(a.workouts.isEmpty())
    }

    @Test fun dayBloodPressureIsTodaysOnly() = runTest {
        val yesterday = today.minusDays(1)
        samples.value = listOf(
            s(Metric.BLOOD_PRESSURE_SYS, at(yesterday, 19), 121.0), s(Metric.BLOOD_PRESSURE_DIA, at(yesterday, 19), 79.0),
        )
        val vm = vm()
        var st = vm.heart.value
        assertNull(st.bpSys)
        assertNull(st.bpDia)
        assertEquals("Wed 4 Nov", st.bpLast)

        samples.value = samples.value + listOf(
            s(Metric.BLOOD_PRESSURE_SYS, at(today, 9), 118.0), s(Metric.BLOOD_PRESSURE_DIA, at(today, 9), 76.0),
        )
        st = vm.heart.value
        assertEquals(118, st.bpSys)
        assertEquals(76, st.bpDia)
        assertNull(st.bpLast)
    }

    @Test fun trendFitUsesDayPositionsSoGapsDontDistortTheChange() {
        // Five steady days, a three-week gap, then one lower day: 3 bpm lower across 29 days. Fitting against list
        // position would squeeze the gap and say 2.
        val pts = listOf(0f to 60f, 1f to 60f, 2f to 60f, 3f to 60f, 4f to 60f, 29f to 57f)
        assertEquals("Resting heart rate ↓ 3 bpm", BodyMath.trendCalloutAt("Resting heart rate", pts, "bpm"))
        assertEquals("Resting heart rate ↓ 2 bpm", BodyMath.trendCallout("Resting heart rate", pts.map { it.second }, "bpm"))
    }

    @Test fun heartComparesRestingWithLastMonthAndHrvWithYourNormal() = runTest {
        // 31 nights: resting 62 for the month before, 59 last night; HRV 45 for two weeks, 50 today.
        val all = mutableListOf<Sample>()
        val nights = mutableListOf<SleepSession>()
        for (i in 30 downTo 0) {
            val d = today.minusDays(i.toLong())
            val n = night(d)
            nights += n
            val rhr = if (i == 0) 59.0 else 62.0
            all += (0 until 46).map { k -> s(Metric.HEART_RATE, n.start.plusSeconds(k * 600L), rhr) }
            all += s(Metric.HRV, at(d, 3), if (i == 0) 50.0 else 45.0)
        }
        // Daytime readings for the usual range; the latest (73) sits inside it.
        all += (0 until 30).map { k -> s(Metric.HEART_RATE, at(today, 8).plusSeconds(k * 600L), 65.0 + k % 15) }
        all += s(Metric.HEART_RATE, at(today, 13, 50), 73.0)
        samples.value = all
        sessions.value = nights
        val st = vm().heart.value
        assertEquals(59, st.resting)
        assertEquals(-3, st.restingVsMonth)
        assertEquals(5, st.hrvVsNormal)
        assertEquals("Within your usual daytime range", st.rangeLine)
        // Today's readings are placed on the clock for the hero chart, ending at "Now" (14:00).
        assertEquals("Now", st.chart.axis.last().second)
        assertTrue(st.chart.points.size >= 2)
    }

    @Test fun stressShowsTheLatestReadingAndWhereNowIs() = runTest {
        samples.value = listOf(
            s(Metric.STRESS, at(today, 8), 20.0), s(Metric.STRESS, at(today, 10, 40), 71.0), s(Metric.STRESS, at(today, 13, 50), 32.0),
        )
        val st = vm().stress.value
        assertEquals(32, st.now)
        assertEquals(14 * 60, st.nowMinute)
    }

    @Test fun trendsCarryTheSentenceTheAxisAndTheCardNotes() = runTest {
        val all = mutableListOf<Sample>()
        val nights = mutableListOf<SleepSession>()
        for (i in 29 downTo 0) {
            val d = today.minusDays(i.toLong())
            val n = night(d)
            nights += n
            val rhr = 59.0 + 3.0 * i / 29
            all += (0 until 46).map { k -> s(Metric.HEART_RATE, n.start.plusSeconds(k * 600L), rhr) }
            all += s(Metric.HRV, at(d, 3), 42.0 + 6.0 * (29 - i) / 29)
        }
        samples.value = all
        sessions.value = nights
        val t = vm().trends.value
        assertEquals(Range.MONTH, t.range)
        assertTrue(t.sentence, t.sentence.startsWith("Over the last month your heart has settled"))
        assertEquals(listOf("7 Oct", "21 Oct", "5 Nov"), t.axis)
        assertEquals("↓ 3 bpm", t.restingHr.delta)
        assertEquals(true, t.restingHr.improving)
        assertEquals("↑ 6 ms", t.hrv.delta)
        assertEquals(true, t.hrv.improving)
        assertEquals("Steady", t.sleep.delta)
        assertEquals("7h 30m", t.sleep.avg)
        // Every night is 7h 30m asleep: all over 7h, and the bars are daily.
        assertEquals("30 of 30 nights over 7h", t.sleepNote)
        assertEquals(30, t.sleep.bars.size)
        assertTrue(t.restingHr.avg, t.restingHr.avg in listOf("60", "61"))
    }

    @Test fun trendsWithTwoDaysShowNoDirectionAndNoRepeatedDates() = runTest {
        // A fresh install: yesterday and today. Two days are a difference, not a trend.
        val all = mutableListOf<Sample>()
        val nights = mutableListOf<SleepSession>()
        listOf(1L to 66.0, 0L to 55.0).forEach { (ago, rhr) ->
            val d = today.minusDays(ago)
            val n = night(d)
            nights += n
            // From midnight on, so nothing lands on the day before the first night.
            all += (0 until 40).map { k -> s(Metric.HEART_RATE, n.start.plusSeconds(3_600L + k * 600L), rhr) }
            all += s(Metric.HRV, at(d, 3), if (ago == 1L) 90.0 else 60.0)
        }
        samples.value = all
        sessions.value = nights
        val t = vm().trends.value
        assertEquals("", t.restingHr.delta)
        assertEquals(null, t.restingHr.improving)
        assertEquals("", t.hrv.delta)
        assertEquals("Resting heart rate", t.restingHr.callout)
        assertEquals(t.axis.distinct(), t.axis)
        assertEquals(2, t.axis.size)
    }

    @Test fun thisWeekListsOnlyTheLastSevenDaysOfWorkouts() = runTest {
        fun w(daysAgo: Long) = WorkoutRecord(at(today.minusDays(daysAgo), 8), at(today.minusDays(daysAgo), 8, 30), 0, 3000, 200, null, null)
        workouts.value = listOf(w(0), w(6), w(7), w(20))
        val a = vm().activity.value
        assertEquals(listOf(at(today, 8), at(today.minusDays(6), 8)), a.workouts.map { it.start })
    }

    @Test fun aStoredMeasuredReadingSaysMeasuredWithItsTime() = runTest {
        // The Measure sheet stores its reading as MEASURED; the Pulse hero names it so.
        samples.value = listOf(
            s(Metric.HEART_RATE, at(today, 9, 15), 62.0),
            s(Metric.HEART_RATE, at(today, 13, 58), 72.0, SampleSource.MEASURED),
        )
        val st = vm().heart.value
        assertEquals(72, st.latestBpm)
        assertEquals("Measured · 13:58", st.latestCaption)
        assertEquals(72f, st.chart.points.last().second, 1e-3f)
    }

    @Test fun theHeartHeroNeverShowsAnOldReadingAsCurrent() = runTest {
        samples.value = listOf(s(Metric.HEART_RATE, at(today, 9, 15), 62.0))
        val vm = vm()
        assertEquals("Last reading · 09:15", vm.heart.value.latestCaption)
        assertEquals(62, vm.heart.value.latestBpm)
        // Only a reading from 20 days ago (inside the 37-day window): a dash, not "62 bpm, last reading 09:15".
        samples.value = listOf(s(Metric.HEART_RATE, at(today.minusDays(20), 9, 15), 62.0))
        assertNotNull(vm.heart.value.latestHr)
        assertNull(vm.heart.value.latestBpm)
        assertEquals(dev.avijit.orbithealth.ui.today.HrFreshness.HIDDEN, vm.heart.value.latestFreshness)
        assertEquals(BodyMath.NO_RECENT_HR, vm.heart.value.latestCaption)
    }
}
