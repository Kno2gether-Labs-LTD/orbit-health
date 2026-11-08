package dev.avijit.orbithealth.ui.sleep

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.avijit.orbithealth.insights.Score
import dev.avijit.orbithealth.insights.Scores
import dev.avijit.orbithealth.insights.SleepEstimate
import dev.avijit.orbithealth.insights.SleepEstimates
import dev.avijit.orbithealth.prefs.SettingsSnapshot
import dev.avijit.orbithealth.ring.Metric
import dev.avijit.orbithealth.ring.Sample
import dev.avijit.orbithealth.ring.SleepSession
import dev.avijit.orbithealth.ring.SleepStage
import dev.avijit.orbithealth.ring.totalSleepSec
import dev.avijit.orbithealth.ui.charts.ChartMath
import dev.avijit.orbithealth.ui.charts.formatStageDuration
import dev.avijit.orbithealth.ui.today.minuteTicks
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Everything Sleep reads, so the screen logic runs against fakes in tests. */
class SleepDeps(
    val samples: (Metric, Instant, Instant) -> Flow<List<Sample>>,
    val sleepSessions: (Instant, Instant) -> Flow<List<SleepSession>>,
    val settings: Flow<SettingsSnapshot>,
    val clock: Clock,
    /** Wall-clock ticks; only the date matters here (which night is "last night"). */
    val now: Flow<Instant> = minuteTicks(clock),
    val zone: ZoneId = ZoneId.systemDefault(),
    /** Where aggregation runs; never the main thread. */
    val compute: CoroutineDispatcher = Dispatchers.Default,
    /** Estimated sleep (any status) ending in the range. */
    val estimates: (Instant, Instant) -> Flow<List<SleepEstimate>> = { _, _ -> flowOf(emptyList()) },
    /** Stores the user's answer to a suggestion. */
    val answerEstimate: suspend (EstimateAnswer) -> Unit = {},
    /** Looks for sleep the ring missed (idempotent); run when Sleep opens. */
    val detectEstimates: suspend () -> Unit = {},
)

/** One night, ready to draw. */
data class SleepUiState(
    val session: SleepSession,
    val score: Score?,
    /** Seconds per stage; every stage present. */
    val stageTotals: Map<SleepStage, Int>,
    /** Lowest heart-rate reading during the night. */
    val lowestHr: Int?,
    val avgSpo2: Int?,
    /** The night's heart-rate readings, for the hypnogram overlay. */
    val heartOverlay: List<Sample>,
    /** "Deep sleep came early, as it should. Bedtime was 40 min later than usual." */
    val insight: String?,
    /** "Last night" or "Tue 3 Nov". */
    val title: String,
    /** "23:41 → 06:53". */
    val timeRange: String,
    /** Time asleep: "7h 12m". */
    val duration: String,
    /** When the lowest heart rate was read: "03:10". */
    val lowestHrAt: String? = null,
    /** Mean overnight HRV, ms. */
    val avgHrv: Int? = null,
    /** [avgHrv] minus the usual (mean of up to [SleepMath.BEDTIME_NIGHTS] earlier nights); null without enough nights. */
    val hrvVsNormal: Int? = null,
    /** Lowest SpO₂ reading of the night. */
    val lowSpo2: Int? = null,
    /** A confirmed estimate standing in for a night the ring missed: times only, no stages. */
    val estimated: Boolean = false,
)

/** The nights to page through, oldest first (the newest is shown first). Empty when no sleep was recorded. */
data class SleepScreenState(
    val loaded: Boolean = false,
    val nights: List<SleepUiState> = emptyList(),
    /** A recent night the ring missed, waiting for Yes / Adjust / No. */
    val suggestion: EstimateSuggestion? = null,
)

object SleepMath {
    /** Nights to page back through. */
    const val NIGHTS = 30L

    /** Nights before this one that set the usual bedtime. */
    const val BEDTIME_NIGHTS = 14

    /** Fewer earlier nights than this and there is no "usual" to compare with. */
    const val MIN_BEDTIME_NIGHTS = 3

    /** A bedtime within this many minutes of the median counts as usual. */
    const val BEDTIME_TOLERANCE_MIN = 15

    private val HHmm = DateTimeFormatter.ofPattern("HH:mm")

    /** Time asleep in the app's one duration format (see [dev.avijit.orbithealth.ui.today.TodayCopy.hm]). */
    fun hm(sec: Int): String = dev.avijit.orbithealth.ui.today.TodayCopy.hm(sec)

    /** Stage split legend: "26m" under an hour, "1h 48m" above. */
    fun stageHm(sec: Int): String = formatStageDuration(sec)

    /** Bedtime as minutes after noon, so 23:30 and 00:30 are an hour apart, not 23 hours. */
    private fun bedMinutes(s: SleepSession, zone: ZoneId): Int {
        val t = s.start.atZone(zone).toLocalTime()
        return ((t.hour * 60 + t.minute) - 12 * 60 + 24 * 60) % (24 * 60)
    }

    /**
     * "Bedtime was 40 min later than usual." against the median bedtime of the [BEDTIME_NIGHTS] nights before
     * [night] (from [all]); "Bedtime was close to usual." within [BEDTIME_TOLERANCE_MIN]; null without
     * [MIN_BEDTIME_NIGHTS] earlier nights.
     */
    fun bedtimeInsight(night: SleepSession, all: List<SleepSession>, zone: ZoneId): String? {
        val before = all.filter { it.start < night.start }.sortedByDescending { it.start }.take(BEDTIME_NIGHTS)
        if (before.size < MIN_BEDTIME_NIGHTS) return null
        val sorted = before.map { bedMinutes(it, zone) }.sorted()
        val median = if (sorted.size % 2 == 1) sorted[sorted.size / 2].toDouble()
        else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
        val diff = (bedMinutes(night, zone) - median).roundToInt()
        if (abs(diff) < BEDTIME_TOLERANCE_MIN) return "Bedtime was close to usual."
        val amount = abs(diff).let { if (it < 60) "$it min" else "${it / 60}h ${(it % 60).toString().padStart(2, '0')}m" }
        return "Bedtime was $amount ${if (diff > 0) "later" else "earlier"} than usual."
    }

    /** Deep sleep mostly in the first half of the night is the healthy pattern; null without deep sleep. */
    fun deepTiming(night: SleepSession): String? {
        val deep = night.segments.filter { it.stage == SleepStage.DEEP && it.durationSec > 0 }
        if (deep.isEmpty()) return null
        val (from, to) = ChartMath.hypnogramWindow(night)
        val mid = (from + to) / 2
        var early = 0L
        var total = 0L
        deep.forEach { s ->
            val a = s.start.epochSecond
            val b = a + s.durationSec
            early += (minOf(b, mid) - a).coerceAtLeast(0)
            total += b - a
        }
        return if (early * 2 >= total) "Deep sleep came early, as it should." else "Most deep sleep came late in the night."
    }

    fun night(
        s: SleepSession,
        all: List<SleepSession>,
        hr: List<Sample>,
        spo2: List<Sample>,
        sleepGoalMin: Int,
        today: LocalDate,
        zone: ZoneId,
        hrv: List<Sample> = emptyList(),
        usualHrv: Double? = null,
    ): SleepUiState {
        fun inNight(x: Sample) = x.time >= s.start && x.time <= s.end
        val nightHr = hr.filter(::inNight)
        val nightSpo2 = spo2.filter(::inNight)
        val lowest = nightHr.minByOrNull { it.value }
        val avgHrv = avgHrv(s, hrv)
        val wake = s.end.atZone(zone).toLocalDate()
        val insight = listOfNotNull(deepTiming(s), bedtimeInsight(s, all, zone)).joinToString(" ").ifEmpty { null }
        return SleepUiState(
            session = s,
            score = Scores.sleep(s, sleepGoalMin),
            stageTotals = ChartMath.stageTotals(s),
            lowestHr = lowest?.value?.roundToInt(),
            avgSpo2 = nightSpo2.takeIf { it.isNotEmpty() }?.map { it.value }?.average()?.roundToInt(),
            heartOverlay = nightHr,
            insight = insight,
            title = if (wake == today) "Last night" else wake.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())),
            timeRange = "${s.start.atZone(zone).format(HHmm)} → ${s.end.atZone(zone).format(HHmm)}",
            duration = hm(s.totalSleepSec),
            lowestHrAt = lowest?.time?.atZone(zone)?.format(HHmm),
            avgHrv = avgHrv?.roundToInt(),
            hrvVsNormal = if (avgHrv != null && usualHrv != null) (avgHrv - usualHrv).roundToInt() else null,
            lowSpo2 = nightSpo2.minOfOrNull { it.value }?.roundToInt(),
        )
    }

    /** Mean HRV read during [s], or null without readings. */
    private fun avgHrv(s: SleepSession, hrv: List<Sample>): Double? =
        hrv.filter { it.time >= s.start && it.time <= s.end }.takeIf { it.isNotEmpty() }?.map { it.value }?.average()

    /**
     * Every recorded night with stages, plus confirmed estimates for nights the ring missed (see
     * [SleepEstimates.confirmedNights]: the ring's night always wins), oldest first. Estimated nights take part in the
     * usual bedtime and HRV like any other.
     */
    internal fun nights(
        sessions: List<SleepSession>,
        hr: List<Sample>,
        spo2: List<Sample>,
        sleepGoalMin: Int,
        today: LocalDate,
        zone: ZoneId,
        hrv: List<Sample> = emptyList(),
        estimates: List<SleepEstimate> = emptyList(),
    ): List<SleepUiState> {
        val withStages = sessions.filter { s -> s.segments.any { it.durationSec > 0 } }
        val estimated = SleepEstimates.confirmedNights(estimates, withStages, zone).associateBy { it.asSession() }
        val all = (withStages + estimated.keys).sortedBy { it.start }
        val hrSorted = hr.sortedBy { it.time }
        val spo2Sorted = spo2.sortedBy { it.time }
        val hrvSorted = hrv.sortedBy { it.time }
        val nightHrv = all.map { avgHrv(it, hrvSorted) }
        return all.mapIndexed { i, s ->
            // The usual HRV: the nights before this one that have readings, the latest BEDTIME_NIGHTS of them.
            val earlier = nightHrv.subList(0, i).filterNotNull().takeLast(BEDTIME_NIGHTS)
            val usual = if (earlier.size >= MIN_BEDTIME_NIGHTS) earlier.average() else null
            val n = night(s, all, hrSorted, spo2Sorted, sleepGoalMin, today, zone, hrvSorted, usual)
            val e = estimated[s] ?: return@mapIndexed n
            n.copy(score = SleepEstimates.score(e, sleepGoalMin), duration = hm(e.durationSec), estimated = true)
        }
    }
}

/**
 * Sleep: the last [SleepMath.NIGHTS] nights (plus the two weeks before them for the usual bedtime), recomputed
 * off the main thread when the data, the sleep goal or the date changes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SleepViewModel(private val deps: SleepDeps, scope: CoroutineScope? = null) : ViewModel() {
    private val scope: CoroutineScope = scope ?: viewModelScope

    private val day = deps.now.map { it.atZone(deps.zone).toLocalDate() }.distinctUntilChanged()
    private val goal = deps.settings.map { it.sleepGoalMin }.distinctUntilChanged()

    val state: StateFlow<SleepScreenState> = day.flatMapLatest { d ->
        val to = d.plusDays(1).atStartOfDay(deps.zone).toInstant()
        val from = d.minusDays(SleepMath.NIGHTS + SleepMath.BEDTIME_NIGHTS + 1).atStartOfDay(deps.zone).toInstant()
        val shownFrom = d.minusDays(SleepMath.NIGHTS).atStartOfDay(deps.zone).toInstant().minus(Duration.ofHours(12))
        combine(
            combine(
                deps.sleepSessions(from, to).distinctUntilChanged(),
                deps.samples(Metric.HEART_RATE, shownFrom, to).distinctUntilChanged(),
                deps.samples(Metric.SPO2, shownFrom, to).distinctUntilChanged(),
                deps.samples(Metric.HRV, shownFrom, to).distinctUntilChanged(),
                goal,
            ) { sessions, hr, spo2, hrv, g -> Input(sessions, hr, spo2, hrv, g) },
            deps.estimates(from, to).distinctUntilChanged(),
        ) { i, e -> i to e }
            .conflate()
            .map { (i, estimates) ->
                val all = SleepMath.nights(i.sessions, i.hr, i.spo2, i.goal, d, deps.zone, i.hrv, estimates)
                val since = d.minusDays(1).atStartOfDay(deps.zone).toInstant()
                SleepScreenState(
                    loaded = true,
                    nights = all.filter { it.session.end >= shownFrom },
                    suggestion = SleepEstimates.pendingNight(estimates, i.sessions, since, deps.zone)
                        ?.let { EstimateCopy.suggestion(it, deps.zone) },
                )
            }
    }.flowOn(deps.compute)
        .stateIn(this.scope, SharingStarted.WhileSubscribed(5_000), SleepScreenState())

    init {
        this.scope.launch { runCatching { deps.detectEstimates() } }
    }

    /** The user's Yes / Adjust / No on the night the ring missed. */
    fun answer(a: EstimateAnswer) {
        scope.launch { deps.answerEstimate(a) }
    }

    private class Input(
        val sessions: List<SleepSession>,
        val hr: List<Sample>,
        val spo2: List<Sample>,
        val hrv: List<Sample>,
        val goal: Int,
    )
}
