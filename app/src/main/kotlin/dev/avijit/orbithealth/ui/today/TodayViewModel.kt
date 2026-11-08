package dev.avijit.orbithealth.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.avijit.orbithealth.insights.Baseline
import dev.avijit.orbithealth.insights.Baselines
import dev.avijit.orbithealth.insights.DailySteps
import dev.avijit.orbithealth.insights.Goal
import dev.avijit.orbithealth.insights.RestingHr
import dev.avijit.orbithealth.insights.Score
import dev.avijit.orbithealth.insights.Scores
import dev.avijit.orbithealth.insights.SleepEstimate
import dev.avijit.orbithealth.insights.SleepEstimates
import dev.avijit.orbithealth.insights.SleepKind
import dev.avijit.orbithealth.insights.SmartGoals
import dev.avijit.orbithealth.prefs.SettingsSnapshot
import dev.avijit.orbithealth.ring.Metric
import dev.avijit.orbithealth.ring.Sample
import dev.avijit.orbithealth.ring.SleepSession
import dev.avijit.orbithealth.ring.SleepStage
import dev.avijit.orbithealth.ring.totalSleepSec
import dev.avijit.orbithealth.sync.SyncState
import dev.avijit.orbithealth.ui.charts.ChartMath
import dev.avijit.orbithealth.ui.sleep.EstimateAnswer
import dev.avijit.orbithealth.ui.sleep.EstimateCopy
import dev.avijit.orbithealth.ui.sleep.EstimateSuggestion
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Everything Today reads, so the screen logic runs against fakes in tests. */
class TodayDeps(
    val samples: (Metric, Instant, Instant) -> Flow<List<Sample>>,
    val sleepSessions: (Instant, Instant) -> Flow<List<SleepSession>>,
    val settings: Flow<SettingsSnapshot>,
    val syncState: Flow<SyncState>,
    /** Persisted time of the last successful sync (survives restarts, unlike [syncState]). */
    val lastSyncedAt: Flow<Instant?>,
    val vendorInstalled: Flow<Boolean>,
    val bannerDismissedAt: Flow<Instant?>,
    val dismissBanner: suspend (Instant) -> Unit,
    /** Pull-to-refresh: one sync now. */
    val refresh: suspend () -> Unit,
    val clock: Clock,
    /** Wall-clock ticks: drive "synced 2m", the stress strip's current hour and the day rollover. */
    val now: Flow<Instant> = minuteTicks(clock),
    val zone: ZoneId = ZoneId.systemDefault(),
    /** Where aggregation runs; never the main thread. */
    val compute: CoroutineDispatcher = Dispatchers.Default,
    /** The installed vendor app's name (e.g. "SmartHealth"), or null when unknown; names the coexistence banner. */
    val vendorAppName: Flow<String?> = flowOf(null),
    /** Estimated sleep (any status) ending in the range. */
    val estimates: (Instant, Instant) -> Flow<List<SleepEstimate>> = { _, _ -> flowOf(emptyList()) },
    /** Stores the user's answer to a suggestion. */
    val answerEstimate: suspend (EstimateAnswer) -> Unit = {},
    /** Looks for sleep the ring missed (idempotent); run when Today opens. */
    val detectEstimates: suspend () -> Unit = {},
)

fun minuteTicks(clock: Clock): Flow<Instant> = flow {
    while (true) {
        emit(clock.instant())
        delay(30_000)
    }
}

/** How current the latest heart reading is; only [CURRENT] is drawn as a live value. */
enum class HrFreshness(val live: Boolean) {
    /** No reading at all ("--"). */
    NONE(false),
    /** Within [TodayMath.HrStale]: the value alone. */
    CURRENT(true),
    /** Older, within [TodayMath.HrHidden]: the value with its time. */
    STALE(false),
    /** Over [TodayMath.HrHidden] old: a neutral "—". */
    HIDDEN(false),
}

/** The Today heart tile, computed once per state emission. */
data class HeartTile(val value: String, val caption: String?, val freshness: HrFreshness)

data class TodayUiState(
    val readiness: Score? = null,
    val sleep: Score? = null,
    val activity: Score = Score(0, emptyList()),
    val goals: List<Goal> = emptyList(),
    val stressToday: List<Sample> = emptyList(),
    val latestHr: Sample? = null,
    /** Heart tile value: the latest bpm, "—" when it is over [TodayMath.HrHidden] old, "--" with no reading. */
    val hrValue: String = "--",
    /** "Last: 08:40" / "Last: Tue 08:40" when the latest reading is over [TodayMath.HrStale] old; null when current. */
    val hrCaption: String? = null,
    val hrFreshness: HrFreshness = HrFreshness.NONE,
    val hrSpark: List<Float> = emptyList(),
    val hrv: Double? = null,
    val spo2: Double? = null,
    /** "just now", "2m", "3h", "2d"; empty when never synced. */
    val syncedAgo: String = "",
    val showCoexistenceBanner: Boolean = false,
    /** Names the coexistence banner's vendor app; null = "your ring's own app". */
    val vendorAppName: String? = null,
    // Extras the layout needs, all computed off the main thread.
    val loaded: Boolean = false,
    val dateLabel: String = "",
    val syncing: Boolean = false,
    val refreshing: Boolean = false,
    /** Past days (of the last 14) with both resting HR and HRV; readiness needs [Baselines.MIN_DAYS]. */
    val baselineDays: Int = 0,
    /** Today's HRV against the personal normal: 1 above, -1 below, 0 at it, null without a baseline. */
    val hrvVsNormal: Int? = null,
    /** Mean stress per local hour (0 = no reading), index 0..23. */
    val stressPerHour: List<Int> = List(24) { 0 },
    val stressAvg: Int? = null,
    val currentHour: Int = 0,
    // ---- Living Skies (R2): words and values the redesigned layout shows, all computed off the main thread.
    /** "Good morning, Sam." / "Good morning." */
    val greeting: String = "",
    /** The name inside [greeting] (set in italic), or null. */
    val greetingName: String? = null,
    /** One or two sentences under the greeting. */
    val daySentence: String = "",
    /** "Ring synced 2 min ago". */
    val syncLine: String = "",
    /** Minute of the local day, for the stress ribbon's "now". */
    val nowMinute: Int = 0,
    val restingHr: Int? = null,
    /** Last night's main sleep, for the Sleep hero's strip and times (a confirmed estimate has no segments). */
    val lastNight: SleepSession? = null,
    /** [lastNight] is a confirmed estimate: no stage strip, labelled as estimated. */
    val lastNightEstimated: Boolean = false,
    /** A night the ring missed, waiting for Yes / Adjust / No. */
    val nightSuggestion: EstimateSuggestion? = null,
    /** A nap waiting for Yes / No. */
    val napSuggestion: EstimateSuggestion? = null,
    /** Last night had the most deep sleep of the last seven (with at least three earlier nights to compare). */
    val deepestNight: Boolean = false,
    val stepBase: Int = 0,
    val distanceM: Int = 0,
    val kcal: Int = 0,
    val readinessInsight: String = "",
    val readinessMeta: String = "",
    val sleepInsight: String = "",
    val sleepMeta: String = "",
    val activityInsight: String = "",
    val activityMeta: String = "",
    val stepNote: String = "",
    val bedtimeNote: String = "",
    val hrvNote: String = "",
    val spo2Note: String = "",
    val heartCaption: String = "",
    val stressCaption: String = "",
    /** The latest stress reading (the ribbon's now dot and the header's colour). */
    val stressNow: Int? = null,
    /** "32 · Low", or "32 · Low at 09:00" when the latest reading is over [TodayCopy.StressStale] old; empty with none. */
    val stressHeader: String = "",
)

/** The day's numbers, computed in one pass on [TodayDeps.compute]. */
internal data class DayResult(
    val readiness: Score?,
    val sleep: Score?,
    val activity: Score,
    val goals: List<Goal>,
    val stressToday: List<Sample>,
    val stressPerHour: List<Int>,
    val stressAvg: Int?,
    val latestHr: Sample?,
    val hrSpark: List<Float>,
    val hrv: Double?,
    val spo2: Double?,
    val baselineDays: Int,
    val hrvVsNormal: Int?,
    val restingHr: Int?,
    val lastNight: SleepSession?,
    val deepestNight: Boolean,
    val distanceM: Int,
    val lastNightEstimated: Boolean = false,
    val nightSuggestion: SleepEstimate? = null,
    val napSuggestion: SleepEstimate? = null,
    val kcal: Int,
    val stepBase: Int,
    val copy: DayCopy,
)

/** The day's sentences (see [TodayCopy]). */
internal data class DayCopy(
    val daySentence: String,
    val readinessInsight: String,
    val readinessMeta: String,
    val sleepInsight: String,
    val sleepMeta: String,
    val activityInsight: String,
    val activityMeta: String,
    val stepNote: String,
    val bedtimeNote: String,
    val hrvNote: String,
    val spo2Note: String,
    val stressCaption: String,
)

/** Only the settings Today's numbers depend on, so sync bookkeeping writes don't trigger a recompute. */
internal data class GoalSettings(val stepBase: Int, val sleepGoalMin: Int, val bedtime: LocalTime)

internal class DayInput(
    val day: LocalDate,
    val zone: ZoneId,
    val hr: List<Sample>,
    val hrv: List<Sample>,
    val spo2: List<Sample>,
    val stress: List<Sample>,
    val steps: List<Sample>,
    val stepTotals: List<Sample>,
    val sessions: List<SleepSession>,
    val goals: GoalSettings,
    val distance: List<Sample> = emptyList(),
    val kcal: List<Sample> = emptyList(),
    val estimates: List<SleepEstimate> = emptyList(),
)

object TodayMath {
    const val BASELINE_DAYS = 14L
    val BannerSnooze: Duration = Duration.ofDays(7)
    /** A heart reading older than this is shown with its time instead of as current. */
    val HrStale: Duration = Duration.ofHours(2)
    /** A heart reading older than this is not shown at all. */
    val HrHidden: Duration = Duration.ofDays(7)

    /** Heart tile (value, caption) for the latest reading at [now]; see [TodayUiState.hrValue] / [TodayUiState.hrCaption]. */
    fun heartTile(latest: Sample?, now: Instant, zone: ZoneId): HeartTile {
        if (latest == null) return HeartTile("--", null, HrFreshness.NONE)
        val age = Duration.between(latest.time, now)
        if (age > HrHidden) return HeartTile("—", null, HrFreshness.HIDDEN)
        val bpm = latest.value.roundToInt().toString()
        if (age <= HrStale) return HeartTile(bpm, null, HrFreshness.CURRENT)
        val at = latest.time.atZone(zone)
        val sameDay = at.toLocalDate() == now.atZone(zone).toLocalDate()
        // Built per call (not cached) so a locale change is picked up.
        val caption = "Last: " + at.format(DateTimeFormatter.ofPattern(if (sameDay) "HH:mm" else "EEE HH:mm", Locale.getDefault()))
        return HeartTile(bpm, caption, HrFreshness.STALE)
    }

    fun formatAgo(d: Duration): String = when {
        d < Duration.ofMinutes(1) -> "just now"
        d < Duration.ofHours(1) -> "${d.toMinutes()}m"
        d < Duration.ofDays(1) -> "${d.toHours()}h"
        else -> "${d.toDays()}d"
    }

    fun bannerVisible(vendorInstalled: Boolean, dismissedAt: Instant?, now: Instant): Boolean =
        vendorInstalled && (dismissedAt == null || Duration.between(dismissedAt, now) >= BannerSnooze)

    /** "Friday 6 November". Built per call so a locale change is picked up. */
    fun dateLabel(day: LocalDate): String = day.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault()))

    /** Nights compared for "your deepest night this week": last night and the six before it. */
    const val WEEK_NIGHTS = 7
    /** Earlier nights needed before a night can be called the week's deepest. */
    const val MIN_NIGHTS_TO_COMPARE = 3

    /** Seconds of deep sleep in [session]. */
    fun deepSec(session: SleepSession): Int = ChartMath.stageTotals(session)[SleepStage.DEEP] ?: 0

    /**
     * Last night had strictly the most deep-stage sleep of the week (some deep sleep, and more than each earlier night),
     * with at least [MIN_NIGHTS_TO_COMPARE] earlier nights to compare.
     */
    fun deepestNight(lastNightDeepSec: Int?, earlierNightsDeepSec: List<Int>): Boolean =
        lastNightDeepSec != null && lastNightDeepSec > 0 && earlierNightsDeepSec.size >= MIN_NIGHTS_TO_COMPARE &&
            earlierNightsDeepSec.all { lastNightDeepSec > it }

    /**
     * A calm break: stress coming back down into the relaxed zone (< 26) after an elevated reading (>= 51).
     * Readings are in time order.
     */
    fun calmBreaks(stress: List<Sample>): Int {
        var elevated = false
        var n = 0
        for (s in stress) {
            val v = s.value
            if (v >= 51) elevated = true
            else if (v < 26 && elevated) { n++; elevated = false }
        }
        return n
    }

    internal fun compute(input: DayInput): DayResult {
        val zone = input.zone
        fun start(d: LocalDate) = d.atStartOfDay(zone).toInstant()
        val todayStart = start(input.day)
        val todayEnd = start(input.day.plusDays(1))

        // Per-day resting HR and HRV for the baselines (the 14 days before today).
        var bothDays = 0
        val rhrValues = mutableListOf<Double>()
        val hrvValues = mutableListOf<Double>()
        for (i in 1..BASELINE_DAYS) {
            val d = input.day.minusDays(i)
            val ds = start(d)
            val de = start(d.plusDays(1))
            val rhr = RestingHr.of(input.hr, nightOf(input, ds, de), ds, de)
            val hrv = dailyMean(input.hrv, ds, de)
            if (rhr != null) rhrValues += rhr
            if (hrv != null) hrvValues += hrv
            if (rhr != null && hrv != null) bothDays++
        }
        val hrBase: Baseline? = Baselines.of(rhrValues)
        val hrvBase: Baseline? = Baselines.of(hrvValues)

        val mainSleep = RestingHr.mainSleep(input.sessions, todayStart, todayEnd)
        // The ring's night always wins; a confirmed estimate stands in only when the ring has none.
        val estimated = if (mainSleep == null) SleepEstimates.confirmedNight(input.estimates, input.sessions, todayStart, todayEnd) else null
        val night = mainSleep ?: estimated?.asSession()
        val restingToday = RestingHr.of(input.hr, night, todayStart, todayEnd)
        val hrvToday = dailyMean(input.hrv, todayStart, todayEnd)
        val sleep = if (estimated != null) SleepEstimates.score(estimated, input.goals.sleepGoalMin)
        else Scores.sleep(mainSleep, input.goals.sleepGoalMin)
        val readiness = Scores.readiness(restingToday, hrBase, hrvToday, hrvBase, sleep)

        val stress = input.stress.filter { it.time >= todayStart && it.time < todayEnd }.sortedBy { it.time }
        fun today(l: List<Sample>) = l.filter { it.time >= todayStart && it.time < todayEnd }
        val steps = DailySteps.of(today(input.steps), DailySteps.totalsForDay(input.stepTotals, todayStart, todayEnd))
        val goals = SmartGoals.today(
            readiness, input.goals.stepBase, input.goals.sleepGoalMin, input.goals.bedtime, steps, calmBreaks(stress),
        )
        val activity = Scores.activity(steps, goals.first().target)

        val perHour = IntArray(24)
        stress.groupBy { it.time.atZone(zone).hour }.forEach { (h, l) -> perHour[h] = maxOf(1, l.map { it.value }.average().roundToInt()) }

        val hrSorted = input.hr.sortedBy { it.time }
        val hrvVsNormal = if (hrvBase != null && hrvToday != null) {
            val z = hrvBase.z(hrvToday)
            when { z > 0.5 -> 1; z < -0.5 -> -1; else -> 0 }
        } else null
        val spo2 = input.spo2.map { it.value }.takeIf { it.isNotEmpty() }?.average()

        val lastNightSec = estimated?.durationSec ?: mainSleep?.totalSleepSec?.takeIf { it > 0 }
        // "Deepest" is deep-stage time, not total sleep: a long light night is not a deep one. Every earlier night with
        // sleep counts (one without any deep stage compares as 0).
        val earlierDeep = (1 until WEEK_NIGHTS).mapNotNull { i ->
            val d = input.day.minusDays(i.toLong())
            RestingHr.mainSleep(input.sessions, start(d), start(d.plusDays(1)))?.takeIf { it.totalSleepSec > 0 }?.let(::deepSec)
        }
        val deepest = deepestNight(mainSleep?.takeIf { lastNightSec != null }?.let(::deepSec), earlierDeep)
        val rhr = restingToday?.roundToInt()
        val distanceM = DailySteps.sum(today(input.distance))
        val kcal = DailySteps.sum(today(input.kcal))
        val stepGoal = goals.first()
        val r = readiness?.value
        val totals = mainSleep?.let { ChartMath.stageTotals(it) }.orEmpty()
        val copy = DayCopy(
            daySentence = TodayCopy.daySentence(lastNightSec, deepest, r, bothDays, estimated = estimated != null),
            readinessInsight = TodayCopy.readinessInsight(r, bothDays),
            readinessMeta = TodayCopy.readinessMeta(r, bothDays, rhr, hrvToday?.roundToInt(), hrvVsNormal),
            sleepInsight = TodayCopy.sleepInsight(lastNightSec, deepest, input.goals.sleepGoalMin, sleep?.value),
            sleepMeta = when {
                estimated != null -> EstimateCopy.NoStages
                mainSleep == null -> "Wear your ring to bed and your night appears here."
                else -> "Deep ${TodayCopy.hm(totals[SleepStage.DEEP] ?: 0)} · REM ${TodayCopy.hm(totals[SleepStage.REM] ?: 0)}"
            },
            activityInsight = TodayCopy.activityInsight(steps, stepGoal.target),
            activityMeta = TodayCopy.activityMeta(distanceM, kcal),
            stepNote = TodayCopy.stepNote(input.goals.stepBase, stepGoal.target, r),
            bedtimeNote = TodayCopy.bedtimeNote(night?.start?.atZone(zone)?.toLocalTime(), input.goals.bedtime),
            hrvNote = TodayCopy.hrvNote(hrvToday, hrvVsNormal),
            spo2Note = TodayCopy.spo2Note(spo2),
            stressCaption = TodayCopy.stressCaption(stress, zone),
        )

        return DayResult(
            readiness = readiness,
            sleep = sleep,
            activity = activity,
            goals = goals,
            stressToday = stress,
            stressPerHour = perHour.toList(),
            stressAvg = stress.takeIf { it.isNotEmpty() }?.map { it.value }?.average()?.roundToInt(),
            latestHr = hrSorted.lastOrNull(),
            hrSpark = heartSpark(hrSorted, todayStart, zone),
            hrv = hrvToday,
            spo2 = spo2,
            baselineDays = bothDays,
            hrvVsNormal = hrvVsNormal,
            restingHr = rhr,
            lastNight = night,
            deepestNight = deepest,
            distanceM = distanceM,
            lastNightEstimated = estimated != null,
            nightSuggestion = SleepEstimates.pendingNight(input.estimates, input.sessions, start(input.day.minusDays(1)), zone),
            napSuggestion = SleepEstimates.pending(input.estimates, SleepKind.NAP, todayStart),
            kcal = kcal,
            stepBase = input.goals.stepBase,
            copy = copy,
        )
    }

    /** The day's night: the ring's main sleep, else a confirmed estimate (as a session without stages). */
    private fun nightOf(input: DayInput, from: Instant, to: Instant): SleepSession? =
        RestingHr.mainSleep(input.sessions, from, to)
            ?: SleepEstimates.confirmedNight(input.estimates, input.sessions, from, to)?.asSession()

    private fun dailyMean(samples: List<Sample>, from: Instant, to: Instant): Double? =
        samples.filter { it.time >= from && it.time < to }.map { it.value }.takeIf { it.isNotEmpty() }?.average()

    /** Today's heart rate in 2-hour means (the last 24 h when today has fewer than two buckets). */
    private fun heartSpark(hr: List<Sample>, todayStart: Instant, zone: ZoneId): List<Float> {
        fun buckets(from: Instant): List<Float> = hr.filter { it.time >= from }
            .groupBy { Duration.between(from, it.time).toHours() / 2 }
            .toSortedMap().values.map { l -> l.map { it.value }.average().toFloat() }
        val today = buckets(todayStart)
        if (today.size >= 2 || hr.isEmpty()) return today
        return buckets(hr.last().time.minus(Duration.ofHours(24)))
    }

    /** Zone word for the stress summary ("32 · low"). */
    fun stressWord(v: Int): String = when (ChartMath.stressZone(v)) {
        0 -> "relaxed"
        1 -> "low"
        2 -> "moderate"
        else -> "high"
    }
}

/**
 * Today: reacts to the database (Room flows), settings, sync state and the vendor-app detector. Aggregation (14 days of
 * resting HR / HRV baselines, scores, goals, the stress strip) runs on [TodayDeps.compute] and only when the data,
 * the goal settings or the day changes; the per-minute tick only refreshes "synced Nm" and the banner.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TodayViewModel(private val deps: TodayDeps, scope: CoroutineScope? = null) : ViewModel() {
    private val scope: CoroutineScope = scope ?: viewModelScope
    private val refreshing = MutableStateFlow(false)

    private val now = deps.now.distinctUntilChanged()
    private val day = now.map { it.atZone(deps.zone).toLocalDate() }.distinctUntilChanged()
    private val goalSettings = deps.settings.map { GoalSettings(it.stepGoalBase, it.sleepGoalMin, it.bedtimeTarget) }.distinctUntilChanged()

    private val dayResult: Flow<Pair<LocalDate, DayResult>> = day.flatMapLatest { d ->
        val dayStart = d.atStartOfDay(deps.zone).toInstant()
        val dayEnd = d.plusDays(1).atStartOfDay(deps.zone).toInstant().minusMillis(1)
        // Sleep that ends on the first baseline day starts the evening before it.
        val historyFrom = d.minusDays(TodayMath.BASELINE_DAYS + 1).atStartOfDay(deps.zone).toInstant()
        val nightFrom = dayStart.minus(Duration.ofHours(12))
        val sources = listOf(
            deps.samples(Metric.HEART_RATE, historyFrom, dayEnd),
            deps.samples(Metric.HRV, historyFrom, dayEnd),
            deps.samples(Metric.SPO2, nightFrom, dayEnd),
            deps.samples(Metric.STRESS, dayStart, dayEnd),
            deps.samples(Metric.STEPS, dayStart, dayEnd),
            deps.samples(Metric.STEPS_DAY_TOTAL, dayStart, dayEnd),
            deps.samples(Metric.DISTANCE_M, dayStart, dayEnd),
            deps.samples(Metric.CALORIES_KCAL, dayStart, dayEnd),
        )
        combine(
            combine(sources) { it.toList() }, deps.sleepSessions(historyFrom, dayEnd), goalSettings, deps.estimates(historyFrom, dayEnd),
        ) { s, sessions, goals, estimates ->
            DayInput(d, deps.zone, s[0], s[1], s[2], s[3], s[4], s[5], sessions, goals, distance = s[6], kcal = s[7], estimates = estimates)
        }
            // A sync writes many rows in quick succession: drop the inputs the compute could not keep up with.
            .conflate()
            .map { d to TodayMath.compute(it) }
    }.flowOn(deps.compute)

    private val name = deps.settings.map { it.name }.distinctUntilChanged()

    private val syncInfo = combine(deps.syncState, deps.lastSyncedAt) { st, persisted ->
        val fromState = (st as? SyncState.Synced)?.at
        val last = listOfNotNull(fromState, persisted).maxOrNull()
        // Only a transfer in progress counts: a connect attempt to an absent ring can take a while and says nothing new.
        last to (st == SyncState.Syncing)
    }

    private val banner = combine(deps.vendorInstalled, deps.bannerDismissedAt, deps.vendorAppName) { a, b, n -> Triple(a, b, n) }

    private val meta = combine(syncInfo, banner, name) { sync, b, n -> Triple(sync, b, n) }

    val state: StateFlow<TodayUiState> =
        combine(dayResult, now, meta, refreshing) { (d, r), t, (sync, b, name), refreshing ->
            val (last, syncing) = sync
            val (installed, dismissed, vendorName) = b
            val local = t.atZone(deps.zone)
            val since = last?.let { Duration.between(it, t).coerceAtLeast(Duration.ZERO) }
            val heart = TodayMath.heartTile(r.latestHr, t, deps.zone)
            val trimmed = name?.trim()?.takeIf { it.isNotEmpty() }
            TodayUiState(
                readiness = r.readiness,
                sleep = r.sleep,
                activity = r.activity,
                goals = r.goals,
                stressToday = r.stressToday,
                latestHr = r.latestHr,
                hrValue = heart.value,
                hrCaption = heart.caption,
                hrFreshness = heart.freshness,
                hrSpark = r.hrSpark,
                hrv = r.hrv,
                spo2 = r.spo2,
                syncedAgo = since?.let { TodayMath.formatAgo(it) }.orEmpty(),
                showCoexistenceBanner = TodayMath.bannerVisible(installed, dismissed, t),
                vendorAppName = vendorName,
                loaded = true,
                dateLabel = TodayMath.dateLabel(d),
                syncing = syncing,
                refreshing = refreshing,
                baselineDays = r.baselineDays,
                hrvVsNormal = r.hrvVsNormal,
                stressPerHour = r.stressPerHour,
                stressAvg = r.stressAvg,
                currentHour = local.hour,
                greeting = TodayCopy.greeting(local.toLocalTime(), trimmed),
                greetingName = trimmed,
                daySentence = r.copy.daySentence,
                syncLine = TodayCopy.syncLine(syncing, since),
                nowMinute = local.hour * 60 + local.minute,
                restingHr = r.restingHr,
                lastNight = r.lastNight,
                lastNightEstimated = r.lastNightEstimated,
                nightSuggestion = r.nightSuggestion?.let { EstimateCopy.suggestion(it, deps.zone) },
                napSuggestion = r.napSuggestion?.let { EstimateCopy.suggestion(it, deps.zone) },
                deepestNight = r.deepestNight,
                stepBase = r.stepBase,
                distanceM = r.distanceM,
                kcal = r.kcal,
                readinessInsight = r.copy.readinessInsight,
                readinessMeta = r.copy.readinessMeta,
                sleepInsight = r.copy.sleepInsight,
                sleepMeta = r.copy.sleepMeta,
                activityInsight = r.copy.activityInsight,
                activityMeta = r.copy.activityMeta,
                stepNote = r.copy.stepNote,
                bedtimeNote = r.copy.bedtimeNote,
                hrvNote = r.copy.hrvNote,
                spo2Note = r.copy.spo2Note,
                heartCaption = TodayCopy.heartCaption(heart.caption, r.restingHr, heart.freshness),
                stressCaption = r.copy.stressCaption,
                stressNow = r.stressToday.lastOrNull()?.value?.roundToInt(),
                stressHeader = TodayCopy.stressHeader(r.stressToday.lastOrNull(), t, deps.zone),
            )
        }.stateIn(this.scope, SharingStarted.WhileSubscribed(5_000), TodayUiState())

    init {
        this.scope.launch { runCatching { deps.detectEstimates() } }
    }

    fun dismissBanner() {
        scope.launch { deps.dismissBanner(deps.clock.instant()) }
    }

    /** The user's Yes / Adjust / No on a suggestion; the card leaves once the stored answer flows back. */
    fun answer(a: EstimateAnswer) {
        scope.launch { deps.answerEstimate(a) }
    }

    /** Pull-to-refresh: one sync; the indicator shows until it returns. Ignored while one is running. */
    fun refresh() {
        if (!refreshing.compareAndSet(false, true)) return
        scope.launch {
            try {
                deps.refresh()
            } finally {
                refreshing.value = false
            }
        }
    }
}
