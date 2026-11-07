package dev.avijit.orbithealth.ui.body

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.avijit.orbithealth.prefs.SettingsSnapshot
import dev.avijit.orbithealth.ring.Metric
import dev.avijit.orbithealth.ring.Sample
import dev.avijit.orbithealth.ring.SleepSession
import dev.avijit.orbithealth.ring.WorkoutRecord
import dev.avijit.orbithealth.ui.today.GoalSettings
import dev.avijit.orbithealth.ui.today.HrFreshness
import dev.avijit.orbithealth.ui.today.TodayMath
import dev.avijit.orbithealth.ui.today.minuteTicks
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Everything Body reads, so the screen logic runs against fakes in tests. */
class BodyDeps(
    val samples: (Metric, Instant, Instant) -> Flow<List<Sample>>,
    val sleepSessions: (Instant, Instant) -> Flow<List<SleepSession>>,
    val workouts: (Instant, Instant) -> Flow<List<WorkoutRecord>>,
    val settings: Flow<SettingsSnapshot>,
    val clock: Clock,
    val now: Flow<Instant> = minuteTicks(clock),
    val zone: ZoneId = ZoneId.systemDefault(),
    /** Where aggregation runs; never the main thread. */
    val compute: CoroutineDispatcher = Dispatchers.Default,
)

/** Body's sub-tabs, in the order shown. */
enum class BodyTab(val label: String) { HEART("Heart"), STRESS("Stress & SpO₂"), ACTIVITY("Activity"), TRENDS("Trends") }

/** Time range of a section: Heart and Stress use DAY / WEEK; Trends WEEK (7d), MONTH (30d), YEAR (1y). */
enum class Range(val days: Long) { DAY(1), WEEK(7), MONTH(30), YEAR(365) }

data class HeartUiState(
    val range: Range = Range.DAY,
    val loaded: Boolean = false,
    /** The latest stored heart rate (a Measure sheet reading is stored as MEASURED and lands here too). */
    val latestHr: Sample? = null,
    /** Today's heart rate (2-hour means), or the week's daily means. */
    val hrSpark: List<Float> = emptyList(),
    val resting: Int? = null,
    val hrv: Int? = null,
    val spo2: Int? = null,
    val stress: Int? = null,
    val bpSys: Int? = null,
    val bpDia: Int? = null,
    /** Day view with no BP today: the date of the latest reading in the past week ("Wed 4 Nov"), else null. */
    val bpLast: String? = null,
    /** DAY: today's heart-rate readings, placed on the clock for the hero chart. */
    val hrToday: List<Sample> = emptyList(),
    /** WEEK: (day index 0..6, that day's mean), days without readings left out. */
    val hrWeek: List<Pair<Float, Float>> = emptyList(),
    /** The hero chart: points and axis labels at 0..1, ending at "Now" (or "Today"). */
    val chart: HeartChart = HeartChart(),
    /** Resting HR against the mean of the 30 days before the range, rounded (negative = lower). */
    val restingVsMonth: Int? = null,
    /** HRV against the mean of the 14 days before the range, rounded. */
    val hrvVsNormal: Int? = null,
    /** "Within your usual daytime range" for the latest daytime reading, when there is enough history. */
    val rangeLine: String? = null,
    /** How current [latestHr] is at the last tick (see [BodyMath.heartLatest]). */
    val latestFreshness: HrFreshness = HrFreshness.NONE,
    /**
     * "Last reading · 08:40" / "Measured · 11:40" (a Measure sheet reading) / "… · Tue 3 Nov 21:40" / "No reading in
     * 7 days"; empty with no reading.
     */
    val latestCaption: String = "",
    /** [latestHr] rounded, or null when there is none or it is over 7 days old. */
    val latestBpm: Int? = null,
)

/** The Pulse hero's stored reading: how fresh it is, the bpm to show (null: a dash) and its caption. */
data class HeartLatest(val freshness: HrFreshness, val bpm: Int?, val caption: String)

/** The Heart hero's chart: (x 0..1, bpm) and axis labels at their x. */
data class HeartChart(val points: List<Pair<Float, Float>> = emptyList(), val axis: List<Pair<Float, String>> = emptyList())

data class StressUiState(
    val range: Range = Range.DAY,
    val loaded: Boolean = false,
    /** Points for the curve: the day's readings, or one daily mean per day of the week. */
    val stress: List<Sample> = emptyList(),
    /** Raw readings for time in zones. */
    val zoneSamples: List<Sample> = emptyList(),
    /** Axis labels for the week view (fraction, "Mon"); null = hours. */
    val axis: List<Pair<Float, String>>? = null,
    val avg: Int? = null,
    /** "peak 71 at 10:40" / "peak 48 on Tue"; empty without data. */
    val peakLabel: String = "",
    /** Overnight readings, or one mean per night for the week. */
    val spo2: List<Sample> = emptyList(),
    val spo2Avg: Int? = null,
    val spo2Low: Int? = null,
    val dips: Int = 0,
    val dipCopy: String = "",
    /** The big numeral: the latest reading today, or the week's mean. */
    val now: Int? = null,
    /** Minute of the local day now, for the 12-hour ribbon window. */
    val nowMinute: Int = 0,
)

data class ActivityUiState(
    val loaded: Boolean = false,
    val steps: Int = 0,
    /** Today's smart step goal (raised or lowered with readiness). */
    val goal: Int = 0,
    val perHour: IntArray = IntArray(24),
    val distanceKm: Double = 0.0,
    val kcal: Int = 0,
    val workouts: List<WorkoutRecord> = emptyList(),
    val currentHour: Int = 0,
) {
    override fun equals(other: Any?): Boolean = other is ActivityUiState && loaded == other.loaded && steps == other.steps &&
        goal == other.goal && perHour.contentEquals(other.perHour) && distanceKm == other.distanceKm && kcal == other.kcal &&
        workouts == other.workouts && currentHour == other.currentHour
    override fun hashCode(): Int = listOf(loaded, steps, goal, perHour.contentHashCode(), distanceKm, kcal, workouts, currentHour).hashCode()
}

/**
 * One trend card: points in time order (days, or weeks for 1y) and its title with the direction ("Resting heart rate
 * ↓ 3 bpm"); the direction alone ([delta] "↓ 3 bpm" / "Steady", [improving] true when it moved the healthy way), the
 * range's average ([avg] "61", "7h 02m") and per-day [bars] (sleep; weekly for 1y).
 */
data class TrendSeries(
    val values: List<Float> = emptyList(),
    val callout: String = "",
    val delta: String = "",
    val improving: Boolean? = null,
    val avg: String = "",
    val bars: List<Float> = emptyList(),
)

data class TrendsUiState(
    val range: Range = Range.MONTH,
    val loaded: Boolean = false,
    val restingHr: TrendSeries = TrendSeries(),
    /** Minutes asleep per night. */
    val sleep: TrendSeries = TrendSeries(),
    val hrv: TrendSeries = TrendSeries(),
    val steps: TrendSeries = TrendSeries(),
    /** One sentence for the range ("Over the last month your heart has settled and your nights are steadier."). */
    val sentence: String = "",
    /** Date labels for the charts' start, middle and end ("7 Oct", "21 Oct", "6 Nov"). */
    val axis: List<String> = emptyList(),
    /** "18 of 30 nights over 7h". */
    val sleepNote: String = "",
)

/**
 * Body: Heart · Stress & SpO₂ · Activity · Trends. Each section is its own flow, started only while its tab is on
 * screen; aggregation runs on [BodyDeps.compute], sources are conflated so a burst of sync writes computes once, and
 * only data, settings or date changes recompute. "Measure now" opens the Measure sheet (ui/measure), which stores its
 * reading; it then arrives here like any other sample.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BodyViewModel(private val deps: BodyDeps, scope: CoroutineScope? = null) : ViewModel() {
    private val scope: CoroutineScope = scope ?: viewModelScope
    private val zone = deps.zone

    private val _tab = MutableStateFlow(BodyTab.HEART)
    val tab: StateFlow<BodyTab> = _tab.asStateFlow()
    private val heartRange = MutableStateFlow(Range.DAY)
    private val stressRange = MutableStateFlow(Range.DAY)
    private val trendsRange = MutableStateFlow(Range.MONTH)

    private val now = deps.now.distinctUntilChanged()
    private val day = now.map { it.atZone(zone).toLocalDate() }.distinctUntilChanged()
    private val hour = now.map { it.atZone(zone).hour }.distinctUntilChanged()

    private fun start(d: LocalDate): Instant = d.atStartOfDay(zone).toInstant()
    private fun end(d: LocalDate): Instant = start(d.plusDays(1)).minusMillis(1)
    private fun src(m: Metric, from: Instant, to: Instant) = deps.samples(m, from, to).distinctUntilChanged()
    private fun sessions(from: Instant, to: Instant) = deps.sleepSessions(from, to).distinctUntilChanged()

    private fun <T> Flow<T>.share(initial: T): StateFlow<T> =
        flowOn(deps.compute).stateIn(scope, SharingStarted.WhileSubscribed(5_000), initial)

    /** Both ranges are computed per data change, so flipping Day / Week is instant. */
    private val heartData = day.flatMapLatest { d ->
        // A week back, plus the evening before it (that night's sleep sets its resting HR); heart rate and HRV reach
        // back a further 30 days for "vs last month" and "your normal".
        val from = start(d.minusDays(7)).minus(Duration.ofHours(12))
        val longFrom = start(d.minusDays(37)).minus(Duration.ofHours(12))
        val to = end(d)
        combine(
            combine(
                src(Metric.HEART_RATE, longFrom, to), src(Metric.HRV, longFrom, to), src(Metric.SPO2, from, to), src(Metric.STRESS, from, to),
            ) { hr, hrv, sp, st -> listOf(hr, hrv, sp, st) },
            src(Metric.BLOOD_PRESSURE_SYS, from, to), src(Metric.BLOOD_PRESSURE_DIA, from, to), sessions(from, to),
        ) { a, sys, dia, ss -> BodyMath.HeartInput(a[0], a[1], a[2], a[3], sys, dia, ss) }
            .conflate()
            .map { i -> Range.entries.filter { it <= Range.WEEK }.associateWith { r -> BodyMath.heart(i, d, r, zone) } }
    }

    val heart: StateFlow<HeartUiState> = combine(heartData, heartRange, now) { byRange, r, t ->
        byRange.getValue(r).let {
            val latest = BodyMath.heartLatest(it.latestHr, t, zone)
            it.copy(
                chart = BodyMath.heartChart(it, t, zone),
                latestFreshness = latest.freshness, latestCaption = latest.caption, latestBpm = latest.bpm,
            )
        }
    }.share(HeartUiState())

    private val stressData = day.flatMapLatest { d ->
        val from = start(d.minusDays(7)).minus(Duration.ofHours(12))
        val to = end(d)
        combine(src(Metric.STRESS, from, to), src(Metric.SPO2, from, to), sessions(from, to)) { st, sp, ss ->
            BodyMath.StressInput(st, sp, ss)
        }.conflate().map { i -> listOf(Range.DAY, Range.WEEK).associateWith { r -> BodyMath.stress(i, d, r, zone) } }
    }

    val stress: StateFlow<StressUiState> =
        combine(stressData, stressRange, now) { byRange, r, t ->
            byRange.getValue(r).copy(nowMinute = t.atZone(zone).let { it.hour * 60 + it.minute })
        }.share(StressUiState())

    private val goalSettings = deps.settings.map { GoalSettings(it.stepGoalBase, it.sleepGoalMin, it.bedtimeTarget) }.distinctUntilChanged()

    private val activityData = day.flatMapLatest { d ->
        // The smart goal needs the same 14-day baselines as Today.
        val history = start(d.minusDays(TodayMath.BASELINE_DAYS + 1))
        val from = start(d)
        val to = end(d)
        combine(
            combine(
                src(Metric.HEART_RATE, history, to), src(Metric.HRV, history, to), src(Metric.STEPS, from, to),
                src(Metric.STEPS_DAY_TOTAL, from, to), src(Metric.DISTANCE_M, from, to), src(Metric.CALORIES_KCAL, from, to),
            ) { it.toList() },
            sessions(history, to),
            deps.workouts(start(d.minusDays(30)), to).distinctUntilChanged(),
            goalSettings,
        ) { s, ss, w, g -> BodyMath.ActivityInput(s[0], s[1], s[2], s[3], s[4], s[5], ss, w, g) }
            .conflate()
            .map { BodyMath.activity(it, d, zone) }
    }

    val activity: StateFlow<ActivityUiState> =
        combine(activityData, hour) { a, h -> a.copy(currentHour = h) }.share(ActivityUiState())

    val trends: StateFlow<TrendsUiState> = combine(day, trendsRange) { d, r -> d to r }.distinctUntilChanged()
        .flatMapLatest { (d, r) ->
            val from = start(d.minusDays(r.days)).minus(Duration.ofHours(12))
            val to = end(d)
            combine(
                src(Metric.HEART_RATE, from, to), src(Metric.HRV, from, to), src(Metric.STEPS, from, to),
                src(Metric.STEPS_DAY_TOTAL, from, to), sessions(from, to),
            ) { hr, hrv, st, tot, ss -> BodyMath.TrendsInput(hr, hrv, st, tot, ss) }
                .conflate()
                .map { BodyMath.trends(it, d, r, zone) }
        }.share(TrendsUiState())

    fun selectTab(t: BodyTab) { _tab.value = t }
    fun selectHeartRange(r: Range) { heartRange.value = r }
    fun selectStressRange(r: Range) { stressRange.value = r }
    fun selectTrendsRange(r: Range) { trendsRange.value = r }
}
