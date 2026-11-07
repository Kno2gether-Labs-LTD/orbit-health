package dev.avijit.orbithealth.ui.today

import dev.avijit.orbithealth.insights.Baselines
import dev.avijit.orbithealth.ring.Sample
import dev.avijit.orbithealth.ui.charts.ChartMath
import dev.avijit.orbithealth.ui.sky.SkyKind
import java.text.NumberFormat
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * One page of Today's hero pager and the sky it lives under. [skyKey] / [arcKey] are the shared-element keys the
 * detail screens (R3) match to continue the sky and arc across the hero → detail transition.
 */
enum class HeroMetric(val sky: SkyKind, val label: String) {
    READINESS(SkyKind.FIRST_LIGHT, "Readiness"),
    SLEEP(SkyKind.NIGHT, "Sleep"),
    ACTIVITY(SkyKind.EMBER, "Activity");

    val skyKey: String get() = "hero-sky-$name"
    val arcKey: String get() = "hero-arc-$name"
}

/** The warm status words (tokens §1): no pure red or green. */
enum class Status(val word: String) { OPTIMAL("Optimal"), GOOD("Good"), ATTENTION("Pay attention") }

/** Today's words. Pure functions, so every sentence the screen shows is pinned by a unit test. */
object TodayCopy {
    val HeroPages: List<HeroMetric> = listOf(HeroMetric.READINESS, HeroMetric.SLEEP, HeroMetric.ACTIVITY)

    /** Morning before 12, afternoon before 18, evening after. No name: "Good morning." (no comma). */
    fun greeting(time: LocalTime, name: String?): String {
        val part = when {
            time.hour < 12 -> "morning"
            time.hour < 18 -> "afternoon"
            else -> "evening"
        }
        val n = name?.trim().orEmpty()
        return if (n.isEmpty()) "Good $part." else "Good $part, $n."
    }

    fun status(score: Int): Status = when {
        score >= 85 -> Status.OPTIMAL
        score >= 70 -> Status.GOOD
        else -> Status.ATTENTION
    }

    /**
     * "7h 12m", "5h 03m": rounded to the nearest minute, minutes zero-padded. The one sleep-duration format, shared by
     * Today, the Sleep detail and Trends, so a night never reads 5h 43m on one screen and 5h 42m on the next.
     */
    fun hm(sec: Int): String {
        val m = (sec / 60.0).roundToInt()
        return "${m / 60}h ${(m % 60).toString().padStart(2, '0')}m"
    }

    /**
     * The one- or two-sentence summary under the greeting: last night's sleep (with "your deepest night this week"
     * when it had the most deep-stage sleep of seven, see [TodayMath.deepestNight]), then what the body is ready for.
     */
    fun daySentence(sleepSec: Int?, deepest: Boolean, readiness: Int?, baselineDays: Int): String {
        if (sleepSec == null && readiness == null && baselineDays == 0) {
            return "Wear your ring tonight and Orbit will start learning your rhythm."
        }
        val slept = sleepSec?.let { "You slept ${hm(it)}" + (if (deepest) ", your deepest night this week" else "") + "." }
        val ready = when {
            readiness == null && baselineDays < Baselines.MIN_DAYS -> "Orbit is still learning your baseline."
            readiness == null -> "Pull down to sync last night."
            readiness >= 75 -> "Your body is ready for a little more today."
            readiness >= 60 -> "A steady day: keep to your usual rhythm."
            else -> "Go easy today and let your body catch up."
        }
        return listOfNotNull(slept, ready).joinToString(" ")
    }

    fun readinessInsight(readiness: Int?, baselineDays: Int): String = when {
        readiness == null && baselineDays < Baselines.MIN_DAYS ->
            "Learning your baseline: night ${baselineDays.coerceIn(1, Baselines.MIN_DAYS)} of ${Baselines.MIN_DAYS}."
        readiness == null -> "Waiting for last night's data."
        readiness >= 75 -> "Recovered and steady. A good day to move a little more."
        readiness >= 60 -> "Steady, if not fully charged. Keep to your usual pace."
        else -> "Your body is still recovering. Make it an easier day."
    }

    /** "Resting HR 61 · HRV 48 ms, above your normal". */
    fun readinessMeta(readiness: Int?, baselineDays: Int, restingHr: Int?, hrv: Int?, hrvVsNormal: Int?): String {
        if (readiness == null) {
            return if (baselineDays < Baselines.MIN_DAYS) "Readiness appears after three nights with your ring."
            else "Wear your ring to bed; readiness follows in the morning."
        }
        val parts = listOfNotNull(restingHr?.let { "Resting HR $it" }, hrv?.let { "HRV $it ms" }).joinToString(" · ")
        val vs = when (hrvVsNormal) {
            1 -> ", above your normal"
            -1 -> ", below your normal"
            0 -> ", at your normal"
            else -> ""
        }
        return parts + if (hrv != null) vs else ""
    }

    /**
     * The sleep hero's line. A short night that still scored Good or better (efficient, deep) says so rather than
     * contradicting the status word under the score.
     */
    fun sleepInsight(asleepSec: Int?, deepest: Boolean, goalMin: Int, score: Int? = null): String = when {
        asleepSec == null -> "No sleep recorded last night."
        deepest -> "Your deepest night this week."
        asleepSec >= goalMin * 60 -> "A full night. You met your sleep goal."
        asleepSec >= (goalMin - 60) * 60 -> "A solid night, a little under your goal."
        score != null && score >= 70 -> "Short, but sound. An early night will top you up."
        else -> "A short night. An early bedtime will help."
    }

    /** "8,286 to go. A brisk half hour covers about 3,500." */
    fun activityInsight(steps: Int, target: Int): String {
        if (target <= 0 || steps >= target) return "Goal reached. Everything from here is a bonus."
        val toGo = target - steps
        return if (toGo > BRISK_HALF_HOUR) "${n(toGo)} to go. A brisk half hour covers about ${n(BRISK_HALF_HOUR)}."
        else "${n(toGo)} to go. A short walk will do it."
    }

    /** "0.59 km · 46 kcal". */
    fun activityMeta(distanceM: Int, kcal: Int): String {
        val km = distanceM / 1000.0
        val d = if (km < 1) String.format(Locale.getDefault(), "%.2f", km) else String.format(Locale.getDefault(), "%.1f", km)
        return "$d km · ${n(kcal)} kcal"
    }

    /** The step goal's sub-line: why it moved from the base. */
    fun stepNote(base: Int, target: Int, readiness: Int?): String = when {
        target > base -> "Raised ${n(target - base)} because you recovered well"
        target < base -> "Lowered ${n(base - target)} so you can recover"
        readiness == null -> "Your usual goal while Orbit learns you"
        else -> "Your usual goal"
    }

    /** Last night's bedtime against the target, across midnight. */
    fun bedtimeNote(lastBedtime: LocalTime?, target: LocalTime): String {
        if (lastBedtime == null) return "A steady bedtime helps you recover"
        var diff = Duration.between(target, lastBedtime).toMinutes().toInt()
        if (diff > 720) diff -= 1440
        if (diff <= -720) diff += 1440
        val a = abs(diff)
        val amount = if (a >= 60) "${a / 60}h ${a % 60}m" else "$a min"
        return when {
            diff > ON_TIME_MIN -> "Last night you were $amount later"
            diff < -ON_TIME_MIN -> "Last night you were $amount earlier"
            else -> "Last night you were right on time"
        }
    }

    fun hrvNote(hrv: Double?, vsNormal: Int?): String = when {
        hrv == null -> "No reading yet"
        vsNormal == 1 -> "↑ Above avg"
        vsNormal == -1 -> "↓ Below avg"
        vsNormal == 0 -> "At your normal"
        else -> "Learning"
    }

    fun spo2Note(spo2: Double?): String = when {
        spo2 == null -> "No reading yet"
        spo2 >= 94.0 -> "Steady"
        else -> "Below 94%"
    }

    /** The heart mini card's caption: a stale reading's time first, else today's resting HR. */
    fun heartCaption(staleCaption: String?, restingHr: Int?, freshness: HrFreshness = HrFreshness.CURRENT): String =
        if (freshness == HrFreshness.HIDDEN) "No reading in 7 days"
        else staleCaption ?: restingHr?.let { "Resting $it" } ?: "No resting HR yet"

    /** "Ring synced 2 min ago" from the time since the last sync (null: never). */
    fun syncLine(syncing: Boolean, sinceLast: Duration?): String = when {
        syncing -> "Syncing with your ring…"
        sinceLast == null -> "Ring not synced yet"
        else -> "Ring synced " + when {
            sinceLast < Duration.ofMinutes(1) -> "just now"
            sinceLast < Duration.ofHours(1) -> "${sinceLast.toMinutes()} min ago"
            sinceLast < Duration.ofDays(1) -> "${sinceLast.toHours()} h ago"
            sinceLast.toDays() == 1L -> "1 day ago"
            else -> "${sinceLast.toDays()} days ago"
        }
    }

    /**
     * One sentence over the stress ribbon. A busy spell is a peak in the medium zone or above; "settled quickly"
     * when stress dropped back below it within the hour.
     */
    fun stressCaption(stress: List<Sample>, zone: ZoneId): String {
        if (stress.isEmpty()) return "No stress readings yet today."
        val sorted = stress.sortedBy { it.time }
        val peak = sorted.maxBy { it.value }
        if (peak.value < BUSY) return "A calm day so far. Nothing stood out."
        val at = peak.time.atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm"))
        val settledAt = sorted.firstOrNull { it.time > peak.time && it.value < BUSY }
            ?: return "A busy spell around $at, and it's still with you."
        val quick = Duration.between(peak.time, settledAt.time) <= Duration.ofHours(1)
        return "A busy spell around $at, then you settled${if (quick) " quickly" else ""}."
    }

    /** A stress reading older than this is shown with its time in the header. */
    val StressStale: Duration = Duration.ofHours(1)

    /** The stress header: "32 · Low", with "at 09:00" when the latest reading is over [StressStale] old. */
    fun stressHeader(latest: Sample?, now: Instant, zone: ZoneId): String {
        if (latest == null) return ""
        val v = latest.value.roundToInt()
        val head = "$v · ${stressWord(v)}"
        if (Duration.between(latest.time, now) <= StressStale) return head
        return "$head at ${latest.time.atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm"))}"
    }

    /** "Low" for the stress header ("32 · Low"). */
    fun stressWord(v: Int): String = TodayMath.stressWord(v).replaceFirstChar { it.uppercase() }

    /** Status colour role of a stress value: 0 calm (optimal), 1 medium (good), 2 high (attention). */
    fun stressTone(v: Int): Int = when (ChartMath.stressZone(v)) {
        0, 1 -> 0
        2 -> 1
        else -> 2
    }

    /** First hour of the 12-hour stress window: now sits about halfway, on a 3-hour grid (06–18 at 11:52). */
    fun stressWindowStart(minuteOfDay: Int): Int {
        val h = minuteOfDay / 60.0 - 6
        return (kotlin.math.ceil(h / 3.0).toInt() * 3).coerceIn(0, 12)
    }

    private const val BRISK_HALF_HOUR = 3500
    private const val ON_TIME_MIN = 10
    private const val BUSY = 51.0

    /** Grouped with the device locale ("8,286", "8.286", "8 286"); built per call so a locale change is picked up. */
    private fun n(v: Int): String = NumberFormat.getIntegerInstance(Locale.getDefault()).format(v)
}
