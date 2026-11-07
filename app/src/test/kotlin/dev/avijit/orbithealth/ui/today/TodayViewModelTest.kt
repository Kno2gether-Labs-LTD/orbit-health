package dev.avijit.orbithealth.ui.today

import dev.avijit.orbithealth.prefs.SettingsSnapshot
import dev.avijit.orbithealth.ring.Metric
import dev.avijit.orbithealth.ring.Sample
import dev.avijit.orbithealth.ring.SampleSource
import dev.avijit.orbithealth.ring.SleepSegment
import dev.avijit.orbithealth.ring.SleepSession
import dev.avijit.orbithealth.ring.SleepStage
import dev.avijit.orbithealth.sync.SyncState
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import dev.avijit.orbithealth.ui.sky.SkyKind
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.Locale

@OptIn(ExperimentalCoroutinesApi::class)
class TodayViewModelTest {
    private val zone: ZoneId = ZoneOffset.UTC
    private val today: LocalDate = LocalDate.of(2026, 11, 5)
    private val now: Instant = today.atTime(10, 30).atZone(zone).toInstant()
    private fun at(day: LocalDate, h: Int, m: Int = 0): Instant = day.atTime(LocalTime.of(h, m)).atZone(zone).toInstant()

    /** In-memory stand-in for the repository: flows filter a mutable list, like Room's live queries. */
    private inner class FakeRepo {
        val samples = MutableStateFlow<List<Sample>>(emptyList())
        val sessions = MutableStateFlow<List<SleepSession>>(emptyList())
        fun samples(m: Metric, from: Instant, to: Instant): Flow<List<Sample>> =
            samples.map { l -> l.filter { it.metric == m && it.time >= from && it.time <= to }.sortedBy { it.time } }
        fun sessions(from: Instant, to: Instant): Flow<List<SleepSession>> =
            sessions.map { l -> l.filter { it.start >= from && it.start <= to } }
    }

    private val repo = FakeRepo()
    private val settings = MutableStateFlow(SettingsSnapshot(stepGoalBase = 8000, sleepGoalMin = 450, bedtimeTarget = LocalTime.of(23, 15)))
    private val sync = MutableStateFlow<SyncState>(SyncState.Idle)
    private val lastSynced = MutableStateFlow<Instant?>(null)
    private val vendor = MutableStateFlow(false)
    private val dismissedAt = MutableStateFlow<Instant?>(null)
    private val clockNow = MutableStateFlow(now)
    private var refreshes = 0
    private val savedLocale: Locale = Locale.getDefault()

    // Copy follows the device locale (dates, digit grouping): pin it so the English sentences below are deterministic.
    @Before fun pinLocale() = Locale.setDefault(Locale.US)
    @After fun restoreLocale() = Locale.setDefault(savedLocale)

    private fun TestScope.vm(): TodayViewModel {
        val d = UnconfinedTestDispatcher(testScheduler)
        val deps = TodayDeps(
            samples = repo::samples,
            sleepSessions = repo::sessions,
            settings = settings,
            syncState = sync,
            lastSyncedAt = lastSynced,
            vendorInstalled = vendor,
            bannerDismissedAt = dismissedAt,
            dismissBanner = { dismissedAt.value = it },
            refresh = { refreshes++ },
            now = clockNow,
            clock = object : Clock() {
                override fun getZone(): ZoneId = zone
                override fun withZone(z: ZoneId?): Clock = this
                override fun instant(): Instant = clockNow.value
            },
            zone = zone,
            compute = d,
        )
        val scope = CoroutineScope(backgroundScope.coroutineContext + d)
        return TodayViewModel(deps, scope).also { vm -> scope.launch { vm.state.collect {} } }
    }

    private fun s(m: Metric, t: Instant, v: Double, src: SampleSource = SampleSource.HISTORY) = Sample(m, t, v, src)

    private fun night(day: LocalDate): SleepSession {
        val start = at(day.minusDays(1), 23, 0)
        var t = start
        val runs = listOf(SleepStage.LIGHT to 120, SleepStage.DEEP to 90, SleepStage.REM to 90, SleepStage.LIGHT to 150, SleepStage.AWAKE to 10)
        val segs = runs.map { (st, min) -> SleepSegment(st, t, min * 60).also { t = t.plusSeconds(min * 60L) } }
        return SleepSession(start, t, segs)
    }

    /** One night + day of data for [day]: sleep HR at [rhr], day HR 75, HRV [hrv] overnight. */
    private fun dayData(day: LocalDate, rhr: Double, hrv: Double): List<Sample> {
        val n = night(day)
        val sleepHr = (0 until 46).map { s(Metric.HEART_RATE, n.start.plusSeconds(it * 600L), rhr) }
        val dayHr = (0 until 6).map { s(Metric.HEART_RATE, at(day, 8, it * 10), 75.0) }
        val hrvs = (1..4).map { s(Metric.HRV, at(day, it), hrv) }
        return sleepHr + dayHr + hrvs
    }

    @Test fun todayStateCombinesLatestData() = runTest {
        val past = (1..5).flatMap { i -> dayData(today.minusDays(i.toLong()), rhr = 58.0 + (i % 2), hrv = 44.0 + (i % 3)) }
        repo.samples.value = past + dayData(today, rhr = 56.0, hrv = 50.0) + listOf(
            s(Metric.HEART_RATE, at(today, 10, 20), 73.0, SampleSource.LIVE),
            s(Metric.SPO2, at(today, 3), 96.0), s(Metric.SPO2, at(today, 4), 98.0),
            s(Metric.STEPS, at(today, 8), 500.0), s(Metric.STEPS, at(today, 9), 414.0),
            s(Metric.STEPS, at(today.minusDays(1), 18), 9999.0), // yesterday: not counted
            s(Metric.STRESS, at(today, 7, 0), 20.0), s(Metric.STRESS, at(today, 7, 30), 30.0), s(Metric.STRESS, at(today, 9, 0), 70.0),
        )
        repo.sessions.value = (0..5).map { night(today.minusDays(it.toLong())) }
        lastSynced.value = now.minusSeconds(120)
        val vm = vm()
        val st = vm.state.value

        assertTrue(st.loaded)
        assertEquals("Thursday 5 November", st.dateLabel)
        assertEquals("2m", st.syncedAgo)
        assertEquals("Ring synced 2 min ago", st.syncLine)
        assertEquals(56, st.restingHr)
        assertEquals("Resting HR 56 · HRV 50 ms, above your normal", st.readinessMeta)
        assertEquals("Recovered and steady. A good day to move a little more.", st.readinessInsight)
        assertEquals("Raised 1,200 because you recovered well", st.stepNote)
        // Last night started at 23:00 against a 23:15 target.
        assertEquals("Last night you were 15 min earlier", st.bedtimeNote)
        assertEquals("↑ Above avg", st.hrvNote)
        assertEquals("Steady", st.spo2Note)
        assertEquals("8,286 to go. A brisk half hour covers about 3,500.", st.activityInsight)
        assertEquals("A busy spell around 09:00, and it's still with you.", st.stressCaption)
        assertEquals(70, st.stressNow)
        // The latest reading (09:00) is 1h 30m old at 10:30: the header says when it was.
        assertEquals("70 · Moderate at 09:00", st.stressHeader)
        assertEquals(73.0, st.latestHr!!.value, 0.0)
        assertEquals(97.0, st.spo2!!, 1e-9)
        assertEquals(50.0, st.hrv!!, 1e-9)
        assertEquals(1, st.hrvVsNormal)
        // Sleep: 7h of 7h30 goal, deep+REM 3h/7h, awake 10 of 430 min.
        assertNotNull(st.sleep)
        // Resting HR 56 below a 58.4 normal, HRV 50 above a ~45 normal: readiness well above 70 -> step goal raised.
        val readiness = st.readiness
        assertNotNull(readiness)
        readiness!!
        assertTrue("readiness ${readiness.value}", readiness.value >= 75)
        assertEquals(5, st.baselineDays)
        val steps = st.goals.first()
        assertEquals(9200, steps.target)
        assertEquals(914, steps.progress)
        assertEquals("raised: well recovered", steps.why)
        assertEquals("In bed by 23:15", st.goals[2].label)
        assertEquals(Math.round(100.0 * 914 / 9200).toInt(), st.activity.value)
        assertEquals(3, st.stressToday.size)
        assertEquals(25, st.stressPerHour[7])
        assertEquals(70, st.stressPerHour[9])
        assertEquals(0, st.stressPerHour[8])
        assertEquals(40, st.stressAvg)
        assertEquals(10, st.currentHour)
        assertTrue(st.hrSpark.size >= 2)
        assertFalse(st.showCoexistenceBanner)

        // Live update: a new HR reading flows straight through.
        repo.samples.value = repo.samples.value + s(Metric.HEART_RATE, at(today, 10, 29), 80.0, SampleSource.LIVE)
        assertEquals(80.0, vm.state.value.latestHr!!.value, 0.0)
    }

    @Test fun heartTileFlagsStaleAndHidesVeryOldReadings() = runTest {
        // Current (<= 2 h): the value alone.
        repo.samples.value = listOf(s(Metric.HEART_RATE, now.minus(Duration.ofMinutes(90)), 64.0))
        val vm = vm()
        assertEquals("64", vm.state.value.hrValue)
        assertNull(vm.state.value.hrCaption)
        assertEquals(HrFreshness.CURRENT, vm.state.value.hrFreshness)

        // Older than 2 h, same day: shown with its time.
        repo.samples.value = listOf(s(Metric.HEART_RATE, at(today, 7, 5), 61.0))
        assertEquals("61", vm.state.value.hrValue)
        assertEquals("Last: 07:05", vm.state.value.hrCaption)
        assertEquals(HrFreshness.STALE, vm.state.value.hrFreshness)

        // An earlier day (within 7 days): the day is named too.
        repo.samples.value = listOf(s(Metric.HEART_RATE, at(today.minusDays(3), 21, 40), 58.0))
        assertEquals("58", vm.state.value.hrValue)
        val caption = vm.state.value.hrCaption!!
        assertTrue(caption, caption.startsWith("Last: ") && caption.endsWith(" 21:40") && caption.length > "Last: 21:40".length)

        // Older than 7 days: not shown.
        repo.samples.value = listOf(s(Metric.HEART_RATE, now.minus(Duration.ofDays(8)), 70.0))
        assertEquals("—", vm.state.value.hrValue)
        assertNull(vm.state.value.hrCaption)
        // Shown as a neutral dash, never in the live colour, and the caption says why (not "Resting 61").
        assertEquals(HrFreshness.HIDDEN, vm.state.value.hrFreshness)
        assertFalse(vm.state.value.hrFreshness.live)
        assertEquals("No reading in 7 days", vm.state.value.heartCaption)

        // No reading at all.
        repo.samples.value = emptyList()
        assertEquals("--", vm.state.value.hrValue)
        assertEquals(HrFreshness.NONE, vm.state.value.hrFreshness)
    }

    @Test fun heartTileGoesStaleAsTimePasses() = runTest {
        repo.samples.value = listOf(s(Metric.HEART_RATE, at(today, 10, 0), 66.0))
        val vm = vm()
        assertNull(vm.state.value.hrCaption)
        clockNow.value = at(today, 12, 30)
        assertEquals("Last: 10:00", vm.state.value.hrCaption)
    }

    @Test fun stepsUseTheDailyTotalWhenBucketsWereCleared() = runTest {
        repo.samples.value = listOf(
            s(Metric.STEPS, at(today, 9, 30), 120.0), s(Metric.STEPS, at(today, 10, 0), 80.0),
            s(Metric.STEPS_DAY_TOTAL, at(today, 9), 700.0), s(Metric.STEPS_DAY_TOTAL, at(today, 10), 1071.0),
            s(Metric.STEPS_DAY_TOTAL, at(today.minusDays(1), 22), 9000.0), // yesterday's total: not today's
        )
        assertEquals(1071, vm().state.value.goals.first().progress)
    }

    @Test fun stepTotalJustAfterMidnightBelongsToYesterday() = runTest {
        repo.samples.value = listOf(
            s(Metric.STEPS_DAY_TOTAL, at(today, 0, 5), 9000.0), // yesterday's final count, stamped after midnight
            s(Metric.STEPS, at(today, 9, 30), 120.0),
        )
        assertEquals(120, vm().state.value.goals.first().progress)
    }

    @Test fun greetingByTimeOfDay() = runTest {
        assertEquals("Good morning, Sam.", TodayCopy.greeting(LocalTime.of(5, 0), "Sam"))
        assertEquals("Good morning, Sam.", TodayCopy.greeting(LocalTime.of(11, 59), " Sam "))
        assertEquals("Good afternoon, Sam.", TodayCopy.greeting(LocalTime.of(12, 0), "Sam"))
        assertEquals("Good afternoon.", TodayCopy.greeting(LocalTime.of(17, 59), null))
        assertEquals("Good evening.", TodayCopy.greeting(LocalTime.of(18, 0), "  "))
        assertEquals("Good evening, Sam.", TodayCopy.greeting(LocalTime.of(23, 30), "Sam"))

        val vm = vm()
        assertEquals("Good morning.", vm.state.value.greeting)
        assertNull(vm.state.value.greetingName)
        settings.value = settings.value.copy(name = "Sam")
        assertEquals("Good morning, Sam.", vm.state.value.greeting)
        assertEquals("Sam", vm.state.value.greetingName)
        clockNow.value = at(today, 19, 0)
        assertEquals("Good evening, Sam.", vm.state.value.greeting)
    }

    /** [night] with an extra [min]-minute segment of [stage] after it. */
    private fun nightPlus(day: LocalDate, stage: SleepStage, min: Int): SleepSession = night(day).let { n ->
        SleepSession(n.start, n.end.plusSeconds(min * 60L), n.segments + SleepSegment(stage, n.end, min * 60))
    }

    @Test fun daySentenceMentionsDeepestNight() = runTest {
        val past = (1..5).flatMap { i -> dayData(today.minusDays(i.toLong()), rhr = 58.0 + (i % 2), hrv = 44.0 + (i % 3)) }
        repo.samples.value = past + dayData(today, rhr = 56.0, hrv = 50.0)
        // Six earlier nights with 1h 30m of deep sleep; last night 12 more minutes of deep: the most of the seven.
        val deepest = nightPlus(today, SleepStage.DEEP, 12)
        repo.sessions.value = (1..6).map { night(today.minusDays(it.toLong())) } + deepest
        val vm = vm()
        assertTrue(vm.state.value.deepestNight)
        assertEquals(
            "You slept 7h 42m, your deepest night this week. Your body is ready for a little more today.",
            vm.state.value.daySentence,
        )
        assertEquals("Your deepest night this week.", vm.state.value.sleepInsight)

        // An ordinary night (the same as the rest): no superlative.
        repo.sessions.value = (0..6).map { night(today.minusDays(it.toLong())) }
        assertFalse(vm.state.value.deepestNight)
        assertEquals("You slept 7h 30m. Your body is ready for a little more today.", vm.state.value.daySentence)

        // The longest night of the week, but only from extra light sleep: longer is not deeper.
        repo.sessions.value = (1..6).map { night(today.minusDays(it.toLong())) } + nightPlus(today, SleepStage.LIGHT, 40)
        assertFalse(vm.state.value.deepestNight)
        assertEquals("You slept 8h 10m. Your body is ready for a little more today.", vm.state.value.daySentence)

        // A shorter night that still had more deep sleep than any other is the deepest.
        val shortDeep = night(today).let { n ->
            val segs = n.segments.map { if (it.stage == SleepStage.LIGHT) it.copy(durationSec = it.durationSec / 2) else it } +
                SleepSegment(SleepStage.DEEP, n.end, 10 * 60)
            SleepSession(n.start, n.end, segs)
        }
        repo.sessions.value = (1..6).map { night(today.minusDays(it.toLong())) } + shortDeep
        assertTrue(vm.state.value.deepestNight)

        // Too little history to call it the week's deepest (three earlier nights are needed).
        repo.sessions.value = listOf(night(today.minusDays(1)), night(today.minusDays(2)), deepest)
        assertFalse(vm.state.value.deepestNight)
        repo.sessions.value = (1..3).map { night(today.minusDays(it.toLong())) } + deepest
        assertTrue(vm.state.value.deepestNight)
    }

    @Test fun deepestNightComparesDeepStageTime() {
        assertTrue(TodayMath.deepestNight(5400, listOf(3000, 4000, 5000)))
        assertFalse("a tie is not the deepest", TodayMath.deepestNight(5000, listOf(3000, 4000, 5000)))
        assertFalse("too few nights", TodayMath.deepestNight(5400, listOf(3000, 4000)))
        assertFalse("no deep sleep", TodayMath.deepestNight(0, listOf(0, 0, 0)))
        assertFalse(TodayMath.deepestNight(null, listOf(1, 2, 3)))
        assertEquals(90 * 60, TodayMath.deepSec(night(today)))
    }

    @Test fun stressHeaderNamesTheTimeOfAnOldReading() {
        assertEquals("", TodayCopy.stressHeader(null, now, zone))
        assertEquals("32 · Low", TodayCopy.stressHeader(s(Metric.STRESS, now.minus(Duration.ofMinutes(20)), 32.0), now, zone))
        assertEquals("32 · Low", TodayCopy.stressHeader(s(Metric.STRESS, now.minus(Duration.ofHours(1)), 32.0), now, zone))
        assertEquals("32 · Low at 09:29", TodayCopy.stressHeader(s(Metric.STRESS, at(today, 9, 29), 32.0), now, zone))
    }

    @Test fun spo2NoteUsesAFixedThreshold() {
        assertEquals("No reading yet", TodayCopy.spo2Note(null))
        assertEquals("Steady", TodayCopy.spo2Note(94.0))
        assertEquals("Below 94%", TodayCopy.spo2Note(93.4))
    }

    @Test fun numbersFollowTheDeviceLocale() {
        Locale.setDefault(Locale.GERMANY)
        assertEquals("8.286 to go. A brisk half hour covers about 3.500.", TodayCopy.activityInsight(914, 9200))
        assertEquals("3,2 km · 1.210 kcal", TodayCopy.activityMeta(3240, 1210))
    }

    @Test fun uiStateEqualityComparesTheHourlyStressByValue() {
        val a = TodayUiState(stressPerHour = List(24) { it }, greeting = "Good morning.")
        assertEquals(a, a.copy(stressPerHour = List(24) { it }))
        assertEquals(a.hashCode(), a.copy(stressPerHour = List(24) { it }).hashCode())
        assertFalse(a == a.copy(stressPerHour = List(24) { 0 }))
        assertFalse(a == a.copy(greeting = "Good evening."))
    }

    @Test fun daySentenceWithoutData() = runTest {
        assertEquals("Wear your ring tonight and Orbit will start learning your rhythm.", vm().state.value.daySentence)
        assertEquals(
            "You slept 6h 30m. Orbit is still learning your baseline.",
            TodayCopy.daySentence(sleepSec = 6 * 3600 + 1800, deepest = false, readiness = null, baselineDays = 1),
        )
        assertEquals(
            "You slept 5h 00m. Go easy today and let your body catch up.",
            TodayCopy.daySentence(sleepSec = 5 * 3600, deepest = false, readiness = 48, baselineDays = 9),
        )
    }

    @Test fun heroPagesOrder() {
        assertEquals(listOf(HeroMetric.READINESS, HeroMetric.SLEEP, HeroMetric.ACTIVITY), TodayCopy.HeroPages)
        assertEquals(
            listOf(SkyKind.FIRST_LIGHT, SkyKind.NIGHT, SkyKind.EMBER),
            TodayCopy.HeroPages.map { it.sky },
        )
        assertEquals("hero-sky-READINESS", HeroMetric.READINESS.skyKey)
        assertEquals("hero-arc-SLEEP", HeroMetric.SLEEP.arcKey)
    }

    @Test fun goalAndStatusCopy() {
        assertEquals("Good", TodayCopy.status(78).word)
        assertEquals("Optimal", TodayCopy.status(85).word)
        assertEquals("Pay attention", TodayCopy.status(69).word)
        assertEquals("Lowered 1,200 so you can recover", TodayCopy.stepNote(8000, 6800, readiness = 40))
        assertEquals("Your usual goal while Orbit learns you", TodayCopy.stepNote(8000, 8000, readiness = null))
        assertEquals("Last night you were 40 min later", TodayCopy.bedtimeNote(LocalTime.of(23, 55), LocalTime.of(23, 15)))
        assertEquals("Last night you were 1h 20m later", TodayCopy.bedtimeNote(LocalTime.of(0, 35), LocalTime.of(23, 15)))
        assertEquals("Last night you were right on time", TodayCopy.bedtimeNote(LocalTime.of(23, 20), LocalTime.of(23, 15)))
        assertEquals("A steady bedtime helps you recover", TodayCopy.bedtimeNote(null, LocalTime.of(23, 15)))
        assertEquals("Goal reached. Everything from here is a bonus.", TodayCopy.activityInsight(9300, 9200))
        assertEquals("1,200 to go. A short walk will do it.", TodayCopy.activityInsight(8000, 9200))
        assertEquals("0.59 km · 46 kcal", TodayCopy.activityMeta(594, 46))
        assertEquals("3.2 km · 210 kcal", TodayCopy.activityMeta(3240, 210))
        assertEquals("Ring synced just now", TodayCopy.syncLine(false, Duration.ofSeconds(20)))
        assertEquals("Ring synced 3 h ago", TodayCopy.syncLine(false, Duration.ofHours(3)))
        assertEquals("Ring synced 1 day ago", TodayCopy.syncLine(false, Duration.ofDays(1)))
        assertEquals("Ring not synced yet", TodayCopy.syncLine(false, null))
        assertEquals("Syncing with your ring…", TodayCopy.syncLine(true, Duration.ofDays(1)))
    }

    @Test fun stressCaptionNamesTheBusySpell() {
        fun st(h: Int, m: Int, v: Double) = s(Metric.STRESS, at(today, h, m), v)
        assertEquals("No stress readings yet today.", TodayCopy.stressCaption(emptyList(), zone))
        assertEquals(
            "A busy spell around 10:40, then you settled quickly.",
            TodayCopy.stressCaption(listOf(st(9, 0, 20.0), st(10, 40, 71.0), st(11, 10, 40.0), st(11, 50, 32.0)), zone),
        )
        assertEquals(
            "A busy spell around 10:40, then you settled.",
            TodayCopy.stressCaption(listOf(st(10, 40, 71.0), st(11, 10, 60.0), st(12, 30, 55.0), st(13, 0, 30.0)), zone),
        )
        assertEquals("A calm day so far. Nothing stood out.", TodayCopy.stressCaption(listOf(st(9, 0, 20.0), st(10, 0, 44.0)), zone))
    }

    @Test fun readinessNullUntilThreeDaysOfBaseline() = runTest {
        repo.samples.value = (1..2).flatMap { dayData(today.minusDays(it.toLong()), 58.0, 45.0) } + dayData(today, 57.0, 47.0)
        repo.sessions.value = (0..2).map { night(today.minusDays(it.toLong())) }
        val st = vm().state.value
        assertNull(st.readiness)
        assertEquals(2, st.baselineDays)
        assertNotNull(st.sleep)
        assertEquals(8000, st.goals.first().target)
        assertNull(st.goals.first().why)
        assertEquals("Learning your baseline: night 2 of 3.", st.readinessInsight)
        assertEquals("Readiness appears after three nights with your ring.", st.readinessMeta)
    }

    @Test fun bannerShownOnlyWhenVendorInstalledAndNotDismissed() = runTest {
        val vm = vm()
        assertFalse(vm.state.value.showCoexistenceBanner)
        vendor.value = true
        assertTrue(vm.state.value.showCoexistenceBanner)
        vm.dismissBanner()
        assertEquals(now, dismissedAt.value)
        assertFalse(vm.state.value.showCoexistenceBanner)
        clockNow.value = now.plus(Duration.ofDays(6))
        assertFalse(vm.state.value.showCoexistenceBanner)
        clockNow.value = now.plus(Duration.ofDays(7))
        assertTrue(vm.state.value.showCoexistenceBanner)
        vendor.value = false
        assertFalse(vm.state.value.showCoexistenceBanner)
    }

    @Test fun syncedAgoFormatting() = runTest {
        assertEquals("just now", TodayMath.formatAgo(Duration.ZERO))
        assertEquals("just now", TodayMath.formatAgo(Duration.ofSeconds(59)))
        assertEquals("2m", TodayMath.formatAgo(Duration.ofMinutes(2)))
        assertEquals("59m", TodayMath.formatAgo(Duration.ofMinutes(59).plusSeconds(30)))
        assertEquals("3h", TodayMath.formatAgo(Duration.ofHours(3)))
        assertEquals("2d", TodayMath.formatAgo(Duration.ofDays(2)))

        val vm = vm()
        assertEquals("", vm.state.value.syncedAgo) // never synced
        sync.value = SyncState.Synced(now)
        assertEquals("just now", vm.state.value.syncedAgo)
        clockNow.value = now.plus(Duration.ofHours(3))
        assertEquals("3h", vm.state.value.syncedAgo)
        sync.value = SyncState.Syncing
        assertTrue(vm.state.value.syncing)
    }

    @Test fun pullToRefreshRunsOneSync() = runTest {
        val vm = vm()
        vm.refresh()
        assertEquals(1, refreshes)
        assertFalse(vm.state.value.refreshing)
    }
}
