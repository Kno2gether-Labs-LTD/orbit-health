package dev.avijit.orbithealth.ui.body

import dev.avijit.orbithealth.insights.DailySteps
import dev.avijit.orbithealth.insights.RestingHr
import dev.avijit.orbithealth.ring.Metric
import dev.avijit.orbithealth.ring.Sample
import dev.avijit.orbithealth.ring.SampleSource
import dev.avijit.orbithealth.ring.SleepSession
import dev.avijit.orbithealth.ring.WorkoutRecord
import dev.avijit.orbithealth.ring.totalSleepSec
import dev.avijit.orbithealth.ui.charts.ChartMath
import dev.avijit.orbithealth.ui.today.DayInput
import dev.avijit.orbithealth.ui.today.GoalSettings
import dev.avijit.orbithealth.ui.today.HrFreshness
import dev.avijit.orbithealth.ui.today.TodayMath
import java.text.NumberFormat
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Body's pure logic: aggregation per section, trend fits and copy. Runs on the compute dispatcher. */
object BodyMath {

    /** SpO₂ below this is a dip. */
    const val DIP_BELOW = 94.0

    private val HHmm = DateTimeFormatter.ofPattern("HH:mm")

    /**
     * Fitted change across [points] of (day index, value): least-squares slope x the days spanned, so days missing
     * in between don't compress time. Null with fewer than two points or a single day.
     */
    fun trendDelta(points: List<Pair<Float, Float>>): Float? {
        if (points.size < 2) return null
        val mx = points.map { it.first.toDouble() }.average()
        val my = points.map { it.second.toDouble() }.average()
        var num = 0.0
        var den = 0.0
        points.forEach { (x, y) -> num += (x - mx) * (y - my); den += (x - mx) * (x - mx) }
        if (den == 0.0) return null
        return (num / den * (points.maxOf { it.first } - points.minOf { it.first })).toFloat()
    }

    /** A fitted change smaller than this share of the mean reads as steady (12 steps on 6,000 is not a trend). */
    const val STEADY_SHARE = 0.02

    /**
     * "Resting heart rate ↓ 3 bpm": the fitted change over the range, rounded with [format]; "HRV · steady" when it
     * rounds to 0 or is under [STEADY_SHARE] of the mean; just [name] with fewer than two points.
     */
    fun trendCallout(name: String, values: List<Float>, unit: String, format: (Int) -> String = { it.toString() }): String =
        trendCalloutAt(name, values.mapIndexed { i, v -> i.toFloat() to v }, unit, format)

    /** [trendCallout] over (day index, value) points, for series with missing days. */
    fun trendCalloutAt(name: String, points: List<Pair<Float, Float>>, unit: String, format: (Int) -> String = { it.toString() }): String {
        val delta = trendDelta(points) ?: return name
        val d = delta.roundToInt()
        if (d == 0 || abs(delta) < STEADY_SHARE * abs(points.map { it.second }.average())) return "$name · steady"
        return "$name ${if (d > 0) "↑" else "↓"} ${format(abs(d))}${if (unit.isEmpty()) "" else " $unit"}"
    }

    /** The direction alone: "↓ 3 bpm", "Steady", or "" with fewer than two points (same rule as [trendCalloutAt]). */
    fun trendDeltaLabel(points: List<Pair<Float, Float>>, unit: String, format: (Int) -> String = { it.toString() }): String =
        trendCalloutAt("", points, unit, format).trim().let { if (it == "· steady") "Steady" else it }

    /** Shown on Trends until there are a few days to talk about. */
    const val NO_TRENDS = "Your trends appear after a few days with your ring."

    /** A resting-HR fit within this many bpm reads as steady. */
    private const val HEART_STEADY_BPM = 1f
    /** A sleep-duration fit within this many minutes reads as steady. */
    private const val SLEEP_STEADY_MIN = 15f
    /** Nights are steadier (or less regular) when their spread changes by this share and at least [SPREAD_MIN] min. */
    private const val SPREAD_SHARE = 0.2f
    private const val SPREAD_MIN = 5f

    /**
     * One Fraunces sentence for Trends over [range], from the daily resting HR and minutes asleep as (day index, value):
     * the heart clause from the fitted resting-HR change (needs 3 days), the nights clause from how much the nights
     * swing in the second half against the first (spread down 20%+ = steadier), else from the fitted change in sleep
     * (needs 6 nights). "Over the last month your heart has settled and your nights are steadier." The period is
     * named only when the days (index from the range's first day with data) span at least half the range; otherwise
     * "Over the last N days".
     */
    fun trendsSentence(range: Range, restingHr: List<Pair<Float, Float>>, sleepMin: List<Pair<Float, Float>>): String {
        // Name the range only when the data covers at least half of it; a short history says how many days it is.
        val days = (restingHr + sleepMin).map { it.first }
        val span = if (days.isEmpty()) 0 else (days.max() - days.min()).roundToInt() + 1
        val rangeDays = if (range == Range.DAY) Range.WEEK.days else range.days
        val period = when {
            span * 2 < rangeDays -> "Over the last $span days"
            range == Range.MONTH -> "Over the last month"
            range == Range.YEAR -> "Over the last year"
            else -> "Over the last week"
        }
        val heartDelta = if (restingHr.size >= 3) trendDelta(restingHr) else null
        val heart = heartDelta?.let {
            when {
                it <= -HEART_STEADY_BPM -> "your heart has settled"
                it >= HEART_STEADY_BPM -> "your resting heart rate has crept up"
                else -> null // steady
            }
        }
        val heartSteady = heartDelta != null && heart == null
        var nightsSteady = false
        val nights = if (sleepMin.size >= 6) {
            val sorted = sleepMin.sortedBy { it.first }.map { it.second }
            val a = spread(sorted.take(sorted.size / 2))
            val b = spread(sorted.drop(sorted.size / 2))
            val change = trendDelta(sleepMin) ?: 0f
            when {
                b <= a * (1 - SPREAD_SHARE) && a - b >= SPREAD_MIN -> "your nights are steadier"
                b >= a * (1 + SPREAD_SHARE) && b - a >= SPREAD_MIN -> "your nights have been less regular"
                change >= SLEEP_STEADY_MIN -> "you're sleeping longer"
                change <= -SLEEP_STEADY_MIN -> "you're sleeping less"
                else -> { nightsSteady = true; null }
            }
        } else null
        val heartClause = heart ?: if (heartSteady) "your heart has held steady" else null
        return when {
            heartSteady && nightsSteady -> "$period your heart and your sleep have held steady."
            heartClause != null && (nights != null || nightsSteady) -> "$period $heartClause and ${nights ?: "your sleep has held steady"}."
            heartClause != null -> "$period $heartClause."
            nights != null -> "$period $nights."
            nightsSteady -> "$period your sleep has held steady."
            else -> NO_TRENDS
        }
    }

    /** Population standard deviation. */
    private fun spread(v: List<Float>): Float {
        if (v.isEmpty()) return 0f
        val m = v.average()
        return sqrt(v.sumOf { (it - m) * (it - m) } / v.size).toFloat()
    }

    /** Readings needed before a "usual daytime range" means anything. */
    const val USUAL_MIN_READINGS = 20

    /**
     * Where [latest] sits against the 10th–90th percentile of [daytime] readings (the past week, 07:00–22:00):
     * "Within / Above / Below your usual daytime range"; null with fewer than [USUAL_MIN_READINGS].
     */
    fun usualRangeLine(latest: Double, daytime: List<Double>): String? {
        if (daytime.size < USUAL_MIN_READINGS) return null
        val s = daytime.sorted()
        fun pct(p: Double) = s[((s.size - 1) * p).roundToInt()]
        return when {
            latest > pct(0.9) -> "Above your usual daytime range"
            latest < pct(0.1) -> "Below your usual daytime range"
            else -> "Within your usual daytime range"
        }
    }

    /** The minis' foot with nothing to compare against: the period the value covers. */
    fun periodFoot(week: Boolean): String = if (week) "Last 7 days" else "Today"

    /** Resting mini's foot: "↓ 3 vs last month" (lower is better), "Same as last month", or the period ("Today"). */
    fun restingFoot(vsMonth: Int?, week: Boolean = false): String = when {
        vsMonth == null -> periodFoot(week)
        vsMonth < 0 -> "↓ ${-vsMonth} vs last month"
        vsMonth > 0 -> "↑ $vsMonth vs last month"
        else -> "Same as last month"
    }

    /** HRV within this many ms of your normal is at your normal. */
    const val HRV_NORMAL_MS = 3

    /** HRV mini's foot against the past two weeks. */
    fun hrvFoot(vsNormal: Int?, week: Boolean = false): String = when {
        vsNormal == null -> periodFoot(week)
        vsNormal >= HRV_NORMAL_MS -> "↑ Above your normal"
        vsNormal <= -HRV_NORMAL_MS -> "↓ Below your normal"
        else -> "At your normal"
    }

    /** "Busiest 11:00 · 398": the hour with the most steps; "" before any. */
    fun busiestLabel(perHour: IntArray): String {
        val h = perHour.indices.maxByOrNull { perHour[it] } ?: return ""
        if (perHour[h] <= 0) return ""
        return "Busiest ${h.toString().padStart(2, '0')}:00 · ${formatThousands(perHour[h])}"
    }

    /** An hour with at least this many steps is an active hour (the common "move each hour" target). */
    const val ACTIVE_HOUR_STEPS = 250

    fun activeHours(perHour: IntArray): Int = perHour.count { it >= ACTIVE_HOUR_STEPS }

    /**
     * The Pulse hero's stored reading at [now], with the same freshness rules as Today's heart tile
     * ([TodayMath.heartTile]): a reading from today reads "Last reading · 08:40", an earlier one names its day
     * ("Last reading · Tue 3 Nov 21:40"), and one over [TodayMath.HrHidden] old is not shown at all (a dash, and the
     * Pulse sky stops beating).
     */
    fun heartLatest(latest: Sample?, now: Instant, zone: ZoneId): HeartLatest {
        val tile = TodayMath.heartTile(latest, now, zone)
        if (latest == null) return HeartLatest(HrFreshness.NONE, null, "")
        if (tile.freshness == HrFreshness.HIDDEN) return HeartLatest(HrFreshness.HIDDEN, null, NO_RECENT_HR)
        val at = latest.time.atZone(zone)
        val sameDay = at.toLocalDate() == now.atZone(zone).toLocalDate()
        // Built per call so a locale change is picked up.
        val time = at.format(DateTimeFormatter.ofPattern(if (sameDay) "HH:mm" else "EEE d MMM HH:mm", Locale.getDefault()))
        // A reading the user took in the Measure sheet says so.
        val label = if (latest.source == SampleSource.MEASURED) "Measured" else "Last reading"
        return HeartLatest(tile.freshness, latest.value.roundToInt(), "$label · $time")
    }

    const val NO_RECENT_HR = "No reading in 7 days"

    /**
     * The Heart hero's chart for [s] at [now]: DAY = today's readings on the clock
     * from the hour of the first to now (means per slot, at most about 36 points), labelled every 1–4 hours and "Now";
     * WEEK = daily means at their day, labelled every other day through "Today".
     */
    fun heartChart(s: HeartUiState, now: Instant, zone: ZoneId): HeartChart {
        if (s.range == Range.WEEK) {
            if (s.hrWeek.size < 2) return HeartChart()
            val d = now.atZone(zone).toLocalDate()
            val axis = listOf(0, 2, 4).map { i -> i / 6f to weekday(d.minusDays(6L - i)) } + (1f to "Today")
            return HeartChart(s.hrWeek.map { (i, v) -> i / 6f to v }, axis)
        }
        val hr = s.hrToday.sortedBy { it.time }
        if (hr.size < 2) return HeartChart()
        val start = hr.first().time.atZone(zone).withMinute(0).withSecond(0).withNano(0).toInstant()
        val end = listOfNotNull(now, hr.lastOrNull()?.time).max()
        val spanS = (end.epochSecond - start.epochSecond).toFloat().coerceAtLeast(1f)
        val slotS = maxOf(600L, ceil(spanS / 36f / 60f).toLong() * 60L)
        val points = hr.groupBy { (it.time.epochSecond - start.epochSecond) / slotS }.toSortedMap().map { (_, l) ->
            (l.map { it.time.epochSecond }.average() - start.epochSecond).toFloat() / spanS to l.map { it.value }.average().toFloat()
        }
        val stepH = ceil(spanS / 3600f / 3f).toInt().coerceIn(1, 4)
        val startH = start.atZone(zone)
        val axis = generateSequence(0) { it + stepH }
            .map { k -> k to startH.plusHours(k.toLong()) }
            .takeWhile { (k, _) -> k * 3600f <= spanS - stepH * 1800f }
            .map { (k, t) -> k * 3600f / spanS to t.format(HHmm) }
            .toList() + (1f to "Now")
        return HeartChart(points, axis)
    }

    /** Runs of consecutive readings below [DIP_BELOW] (a dip lasting several readings counts once). */
    fun spo2Dips(samples: List<Sample>): Int {
        var n = 0
        var inDip = false
        samples.sortedBy { it.time }.forEach { s ->
            val low = s.value < DIP_BELOW
            if (low && !inDip) n++
            inDip = low
        }
        return n
    }

    fun dipCopy(dips: Int, hasData: Boolean, week: Boolean): String = when {
        !hasData -> ""
        dips == 0 -> "No dips below 94% ${if (week) "this week" else "last night"}"
        else -> "$dips short dip${if (dips == 1) "" else "s"} below 94%${if (week) " this week" else ""}: " +
            "normal for most people, worth watching if frequent"
    }

    fun minutesLabel(min: Int): String = if (min < 60) "${min}m" else "${min / 60}h ${(min % 60).toString().padStart(2, '0')}m"

    fun formatThousands(n: Int): String = NumberFormat.getIntegerInstance(Locale.getDefault()).format(n)

    private fun mean(l: List<Sample>): Double? = l.takeIf { it.isNotEmpty() }?.map { it.value }?.average()
    private fun Double?.r(): Int? = this?.roundToInt()

    private fun startOf(d: LocalDate, zone: ZoneId): Instant = d.atStartOfDay(zone).toInstant()

    /** The night before [d]: its main sleep if there is one, else 18:00 the day before until 10:00. */
    private fun overnight(sessions: List<SleepSession>, d: LocalDate, zone: ZoneId): Pair<Instant, Instant> {
        val main = RestingHr.mainSleep(sessions, startOf(d, zone), startOf(d.plusDays(1), zone))
        return if (main != null) main.start to main.end
        else startOf(d, zone).minus(Duration.ofHours(6)) to startOf(d, zone).plus(Duration.ofHours(10))
    }

    private fun List<Sample>.between(from: Instant, to: Instant) = filter { it.time >= from && it.time < to }

    private fun restingFor(hr: List<Sample>, sessions: List<SleepSession>, d: LocalDate, zone: ZoneId): Double? {
        val ds = startOf(d, zone)
        val de = startOf(d.plusDays(1), zone)
        return RestingHr.of(hr, RestingHr.mainSleep(sessions, ds, de), ds, de)
    }

    internal class HeartInput(
        val hr: List<Sample>, val hrv: List<Sample>, val spo2: List<Sample>, val stress: List<Sample>,
        val sys: List<Sample>, val dia: List<Sample>, val sessions: List<SleepSession>,
    )

    /** Days of resting HR the "vs last month" comparison averages, and how many it needs. */
    private const val MONTH_DAYS = 30L
    private const val MONTH_MIN_DAYS = 7
    /** Days of HRV that make "your normal", and how many it needs. */
    private const val NORMAL_DAYS = 14L
    private const val NORMAL_MIN_DAYS = 3

    /**
     * Heart tiles for [range] ending on [d]: DAY = that day (SpO₂ overnight), WEEK = the 7 days' means. Resting HR is
     * compared with the 30 days before the range and HRV with the 14 days before it; the latest daytime reading is
     * placed against the past week's usual daytime range. Needs heart rate and HRV from 37 days back.
     */
    internal fun heart(i: HeartInput, d: LocalDate, range: Range, zone: ZoneId): HeartUiState {
        val dayStart = startOf(d, zone)
        val dayEnd = startOf(d.plusDays(1), zone)
        val hrSorted = i.hr.sortedBy { it.time }
        val latestHr = hrSorted.lastOrNull()
        val first = if (range == Range.DAY) d else d.minusDays(6)
        val restingMonth = (1..MONTH_DAYS).mapNotNull { restingFor(i.hr, i.sessions, first.minusDays(it), zone) }
            .takeIf { it.size >= MONTH_MIN_DAYS }?.average()
        val hrvNormal = (1..NORMAL_DAYS).mapNotNull { k ->
            val day = first.minusDays(k)
            mean(i.hrv.between(startOf(day, zone), startOf(day.plusDays(1), zone)))
        }.takeIf { it.size >= NORMAL_MIN_DAYS }?.average()
        val weekFrom = startOf(d.minusDays(6), zone)
        val rangeLine = latestHr?.takeIf { it.time >= dayStart && isDaytime(it.time, zone) }?.let { last ->
            usualRangeLine(last.value, hrSorted.filter { it.time >= weekFrom && isDaytime(it.time, zone) }.map { it.value })
        }
        fun vs(v: Double?, base: Double?) = if (v == null || base == null) null else (v - base).roundToInt()
        if (range == Range.DAY) {
            val (nFrom, nTo) = overnight(i.sessions, d, zone)
            val today = hrSorted.between(dayStart, dayEnd)
            // Only today's reading counts as today's value; an older one is named by its date instead.
            val sys = i.sys.between(dayStart, dayEnd).maxByOrNull { it.time }
            val dia = i.dia.filter { sys != null && abs(it.time.epochSecond - sys.time.epochSecond) <= 60 }.maxByOrNull { it.time }
            val bpLast = if (sys != null) null
            else i.sys.filter { it.time < dayStart }.maxByOrNull { it.time }?.time?.atZone(zone)?.toLocalDate()
                ?.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()))
            val resting = restingFor(i.hr, i.sessions, d, zone)
            val hrv = mean(i.hrv.between(dayStart, dayEnd))
            return HeartUiState(
                range = range, loaded = true, latestHr = latestHr,
                hrSpark = ChartMath.bucketAverages(today, 12),
                resting = resting.r(),
                hrv = hrv.r(),
                spo2 = mean(i.spo2.filter { it.time >= nFrom && it.time <= nTo }).r(),
                stress = mean(i.stress.between(dayStart, dayEnd)).r(),
                bpSys = sys?.value?.roundToInt(), bpDia = dia?.value?.roundToInt(), bpLast = bpLast,
                // The hero curve is the waking day: from the end of last night's sleep (sleep has its own screen).
                hrToday = RestingHr.mainSleep(i.sessions, dayStart, dayEnd)?.end
                    ?.let { wake -> today.filter { it.time >= wake }.takeIf { it.size >= 2 } } ?: today,
                restingVsMonth = vs(resting, restingMonth), hrvVsNormal = vs(hrv, hrvNormal),
                rangeLine = rangeLine,
            )
        }
        val days = (6 downTo 0).map { d.minusDays(it.toLong()) }
        val weekStart = startOf(days.first(), zone)
        val dailyHr = days.mapIndexedNotNull { k, day ->
            mean(hrSorted.between(startOf(day, zone), startOf(day.plusDays(1), zone)))?.let { k.toFloat() to it.toFloat() }
        }
        val resting = days.mapNotNull { restingFor(i.hr, i.sessions, it, zone) }.takeIf { it.isNotEmpty() }?.average()
        val hrv = mean(i.hrv.between(weekStart, dayEnd))
        return HeartUiState(
            range = range, loaded = true, latestHr = latestHr, hrSpark = dailyHr.map { it.second },
            resting = resting.r(),
            hrv = hrv.r(),
            spo2 = mean(i.spo2.between(weekStart.minus(Duration.ofHours(6)), dayEnd)).r(),
            stress = mean(i.stress.between(weekStart, dayEnd)).r(),
            bpSys = mean(i.sys.between(weekStart, dayEnd)).r(), bpDia = mean(i.dia.between(weekStart, dayEnd)).r(),
            hrWeek = dailyHr, restingVsMonth = vs(resting, restingMonth), hrvVsNormal = vs(hrv, hrvNormal), rangeLine = rangeLine,
        )
    }

    /** 07:00–22:00 local: the hours a "daytime range" is made of. */
    private fun isDaytime(t: Instant, zone: ZoneId): Boolean = t.atZone(zone).hour in 7..21

    internal class StressInput(val stress: List<Sample>, val spo2: List<Sample>, val sessions: List<SleepSession>)

    internal fun stress(i: StressInput, d: LocalDate, range: Range, zone: ZoneId): StressUiState {
        val dayStart = startOf(d, zone)
        val dayEnd = startOf(d.plusDays(1), zone)
        if (range == Range.DAY) {
            val st = i.stress.between(dayStart, dayEnd).sortedBy { it.time }
            val peak = st.maxByOrNull { it.value }
            val (nFrom, nTo) = overnight(i.sessions, d, zone)
            val sp = i.spo2.filter { it.time >= nFrom && it.time <= nTo }.sortedBy { it.time }
            val dips = spo2Dips(sp)
            return StressUiState(
                range = range, loaded = true, stress = st, zoneSamples = st,
                avg = mean(st).r(),
                peakLabel = peak?.let { "peak ${it.value.roundToInt()} at ${it.time.atZone(zone).format(HHmm)}" }.orEmpty(),
                spo2 = sp, spo2Avg = mean(sp).r(), spo2Low = sp.minOfOrNull { it.value }?.roundToInt(),
                dips = dips, dipCopy = dipCopy(dips, sp.isNotEmpty(), week = false),
                now = st.lastOrNull()?.value?.roundToInt(),
            )
        }
        val days = (6 downTo 0).map { d.minusDays(it.toLong()) }
        val weekStart = startOf(days.first(), zone)
        val raw = i.stress.between(weekStart, dayEnd).sortedBy { it.time }
        val daily = days.mapNotNull { day ->
            mean(raw.between(startOf(day, zone), startOf(day.plusDays(1), zone)))?.let { v ->
                Sample(Metric.STRESS, startOf(day, zone).plus(Duration.ofHours(12)), v, SampleSource.HISTORY)
            }
        }
        val peak = daily.maxByOrNull { it.value }
        // Daily points sit at noon; label each by its weekday at its position along the curve.
        val axis = if (daily.size >= 2) {
            val t0 = daily.first().time.epochSecond
            val span = (daily.last().time.epochSecond - t0).toFloat()
            daily.map { (it.time.epochSecond - t0) / span to weekday(it.time, zone) }
        } else null
        val nights = days.map { overnight(i.sessions, it, zone) }
        val rawSpo2 = i.spo2.filter { s -> nights.any { (a, b) -> s.time >= a && s.time <= b } }.sortedBy { it.time }
        val nightly = days.zip(nights).mapNotNull { (day, w) ->
            mean(rawSpo2.filter { it.time >= w.first && it.time <= w.second })?.let {
                Sample(Metric.SPO2, startOf(day, zone), it, SampleSource.HISTORY)
            }
        }
        val dips = spo2Dips(rawSpo2)
        return StressUiState(
            range = range, loaded = true, stress = daily, zoneSamples = raw, axis = axis,
            avg = mean(raw).r(),
            peakLabel = peak?.let { "peak ${it.value.roundToInt()} on ${weekday(it.time, zone)}" }.orEmpty(),
            spo2 = nightly, spo2Avg = mean(rawSpo2).r(), spo2Low = rawSpo2.minOfOrNull { it.value }?.roundToInt(),
            dips = dips, dipCopy = dipCopy(dips, rawSpo2.isNotEmpty(), week = true),
            now = mean(raw).r(),
        )
    }

    private fun weekday(t: Instant, zone: ZoneId): String = t.atZone(zone).dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
    private fun weekday(d: LocalDate): String = d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())

    internal class ActivityInput(
        val hr: List<Sample>, val hrv: List<Sample>, val steps: List<Sample>, val totals: List<Sample>,
        val distance: List<Sample>, val kcal: List<Sample>, val sessions: List<SleepSession>,
        val workouts: List<WorkoutRecord>, val goals: GoalSettings,
    )

    /** Today's steps against the smart goal (the same rule as Today), steps per hour, distance and calories. */
    internal fun activity(i: ActivityInput, d: LocalDate, zone: ZoneId): ActivityUiState {
        val dayStart = startOf(d, zone)
        val dayEnd = startOf(d.plusDays(1), zone)
        fun today(l: List<Sample>) = l.between(dayStart, dayEnd)
        val day = TodayMath.compute(
            DayInput(d, zone, i.hr, i.hrv, emptyList(), emptyList(), today(i.steps), i.totals, i.sessions, i.goals),
        )
        val stepsGoal = day.goals.first()
        val perHour = IntArray(24)
        today(i.steps).forEach { perHour[it.time.atZone(zone).hour] += it.value.roundToInt() }
        return ActivityUiState(
            loaded = true,
            steps = stepsGoal.progress,
            goal = stepsGoal.target,
            perHour = perHour,
            distanceKm = DailySteps.sum(today(i.distance)) / 1000.0,
            kcal = DailySteps.sum(today(i.kcal)),
            // "This week": the last 7 days including today.
            workouts = i.workouts.filter { it.start >= startOf(d.minusDays(6), zone) && it.start < dayEnd }.sortedByDescending { it.start },
        )
    }

    internal class TrendsInput(
        val hr: List<Sample>, val hrv: List<Sample>, val steps: List<Sample>, val totals: List<Sample>, val sessions: List<SleepSession>,
    )

    /** Days per plotted point: 30 days read as ~10 calm points (as in the mockup), a year as weekly points. */
    fun bucketDays(range: Range): Int = when (range) {
        Range.MONTH -> 3
        Range.YEAR -> 7
        else -> 1
    }

    /**
     * Values over the range ending on [d], starting at the first day with any data (so a short history is simply a
     * shorter line); days without a value are skipped. Plotted as means of [bucketDays] days; the direction callouts
     * are fitted on the daily values. Today's steps are left out until the day is over.
     */
    internal fun trends(i: TrendsInput, d: LocalDate, range: Range, zone: ZoneId): TrendsUiState {
        fun dayOf(s: Sample) = s.time.atZone(zone).toLocalDate()
        val hrByDay = i.hr.groupBy(::dayOf)
        val hrvByDay = i.hrv.groupBy(::dayOf)
        val stepsByDay = i.steps.groupBy(::dayOf)
        val totalsByDay = i.totals.groupBy { DailySteps.dayOfTotal(it, zone) }
        val days = (range.days - 1 downTo 0).map { d.minusDays(it) }
        class Day(val rhr: Float?, val sleepMin: Float?, val hrv: Float?, val steps: Float?)
        val perDay = days.map { day ->
            val ds = startOf(day, zone)
            val de = startOf(day.plusDays(1), zone)
            val main = RestingHr.mainSleep(i.sessions, ds, de)
            val hr = hrByDay[day.minusDays(1)].orEmpty() + hrByDay[day].orEmpty()
            val st = stepsByDay[day].orEmpty()
            val tot = totalsByDay[day].orEmpty()
            Day(
                rhr = RestingHr.of(hr, main, ds, de)?.toFloat(),
                sleepMin = main?.totalSleepSec?.takeIf { it > 0 }?.div(60f),
                hrv = mean(hrvByDay[day].orEmpty())?.toFloat(),
                steps = if (st.isEmpty() && tot.isEmpty()) null else DailySteps.of(st, tot).toFloat(),
            )
        }
        // Cap to the data there is: start at the first day with anything.
        val first = perDay.indexOfFirst { it.rhr != null || it.sleepMin != null || it.hrv != null || it.steps != null }
        val kept = if (first < 0) emptyList() else perDay.drop(first)
        val per = bucketDays(range)
        /** (day index within the range, value) for days that have one: gaps keep their place in time. */
        fun daily(pick: (Day) -> Float?) = kept.mapIndexedNotNull { i, day -> pick(day)?.let { i.toFloat() to it } }
        fun plotted(pick: (Day) -> Float?): List<Float> =
            if (per == 1) kept.mapNotNull(pick)
            else kept.chunked(per).mapNotNull { w -> w.mapNotNull(pick).takeIf { it.isNotEmpty() }?.average()?.toFloat() }
        val today = kept.lastOrNull() // the range ends on [d]
        val stepsPick: (Day) -> Float? = { day -> if (day === today) null else day.steps }
        val sleepDaily = daily { it.sleepMin }
        val sleepAvg = sleepDaily.takeIf { it.isNotEmpty() }?.map { it.second }?.average()?.roundToInt()
        val sleepDir = trendCalloutAt("Sleep duration", sleepDaily, "", ::minutesLabel)
        val sleepCallout = when {
            sleepAvg == null -> "Sleep duration"
            sleepDir.endsWith("· steady") || sleepDaily.size < MIN_TREND_DAYS -> "Sleep duration · avg ${minutesLabel(sleepAvg)}"
            else -> "$sleepDir · avg ${minutesLabel(sleepAvg)}"
        }
        fun series(name: String, pick: (Day) -> Float?, unit: String, upIsGood: Boolean, format: (Int) -> String, avgOf: (Int) -> String) =
            daily(pick).let { pts ->
                // Two days are a difference, not a trend: no direction until there are MIN_TREND_DAYS of values.
                val enough = pts.size >= MIN_TREND_DAYS
                val delta = if (enough) trendDeltaLabel(pts, unit, format) else ""
                TrendSeries(
                    values = plotted(pick),
                    callout = if (enough) trendCalloutAt(name, pts, unit, format) else name,
                    delta = delta,
                    improving = when {
                        delta.startsWith("↑") -> upIsGood
                        delta.startsWith("↓") -> !upIsGood
                        else -> null
                    },
                    avg = pts.takeIf { it.isNotEmpty() }?.map { it.second }?.average()?.roundToInt()?.let(avgOf).orEmpty(),
                )
            }
        val plain: (Int) -> String = { it.toString() }
        val nightsWithData = sleepDaily.size
        val labelFmt = DateTimeFormatter.ofPattern(if (range == Range.YEAR) "MMM yyyy" else "d MMM", Locale.getDefault())
        val axis = trendAxis(kept.size, d).map { it.format(labelFmt) }
        return TrendsUiState(
            range = range, loaded = true,
            restingHr = series("Resting heart rate", { it.rhr }, "bpm", upIsGood = false, format = plain, avgOf = plain),
            sleep = series("Sleep duration", { it.sleepMin }, "", upIsGood = true, format = ::minutesLabel, avgOf = ::minutesLabel)
                .copy(callout = sleepCallout, bars = if (per <= 3) kept.mapNotNull { it.sleepMin } else plotted { it.sleepMin }),
            hrv = series("HRV", { it.hrv }, "ms", upIsGood = true, format = plain, avgOf = plain),
            steps = series("Steps", stepsPick, "", upIsGood = true, format = ::formatThousands, avgOf = ::formatThousands),
            sentence = trendsSentence(range, daily { it.rhr }, sleepDaily),
            axis = axis,
            sleepNote = if (nightsWithData == 0) "" else
                "${sleepDaily.count { it.second >= GOOD_NIGHT_MIN }} of $nightsWithData nights over ${GOOD_NIGHT_MIN / 60}h",
        )
    }

    /** A night of at least this many minutes asleep counts as a full one on Trends (the bar turns lavender). */
    const val GOOD_NIGHT_MIN = 420

    /** Days of values a Trends card needs before it shows a direction ("↓ 3 bpm"). */
    const val MIN_TREND_DAYS = 3

    /**
     * The Trends axis dates for [days] days of history ending on [end], never repeating a date: one day is just [end],
     * two are the first and [end], three or more add the middle day.
     */
    fun trendAxis(days: Int, end: LocalDate): List<LocalDate> = when {
        days <= 0 -> emptyList()
        days == 1 -> listOf(end)
        days == 2 -> listOf(end.minusDays(1), end)
        else -> {
            val from = end.minusDays((days - 1).toLong())
            listOf(from, from.plusDays(((days - 1) / 2).toLong()), end)
        }
    }

    /** Where [trendAxis]'s labels sit along the chart (0..1): one at the end, two at both ends, three at 0, ½, 1. */
    fun trendAxisPositions(count: Int): List<Float> = when (count) {
        0 -> emptyList()
        1 -> listOf(1f)
        2 -> listOf(0f, 1f)
        else -> listOf(0f, 0.5f, 1f)
    }

    /** Bar slots for a range on Trends, so a short history is a few slim bars ending at today, not one wide slab. */
    fun trendBarSlots(range: Range): Int = when (range) {
        Range.YEAR -> 53
        Range.MONTH -> 30
        else -> 7
    }
}
