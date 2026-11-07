package dev.avijit.orbithealth.debug

import dev.avijit.orbithealth.AppGraph
import dev.avijit.orbithealth.prefs.PairedRing
import dev.avijit.orbithealth.ycbt.YcbtFamily
import dev.avijit.orbithealth.ring.DeviceInfo
import dev.avijit.orbithealth.ring.HistoryBatch
import dev.avijit.orbithealth.ring.Metric
import dev.avijit.orbithealth.ring.Sample
import dev.avijit.orbithealth.ring.SampleSource
import dev.avijit.orbithealth.ring.Sex
import dev.avijit.orbithealth.ring.SleepSegment
import dev.avijit.orbithealth.ring.SleepSession
import dev.avijit.orbithealth.ring.SleepStage
import dev.avijit.orbithealth.ring.UserProfile
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * Debug only: fills the (throwaway, emulator) database with 35 days of believable data so screens can be checked
 * against the mockups without a ring. Written as ring history so every rule (e.g. resting HR from sleep) applies.
 *
 * - Heart rate every 10 min (sleeping ~55–60, daytime ~68–85), HRV hourly overnight, SpO₂ every 30 min overnight,
 *   stress every 10 min from 07:00 with a morning peak and an afternoon bump, 30-min step buckets (with distance
 *   and kcal) plus the ring's hourly running day total, and three blood-pressure readings a day.
 * - Resting HR drifts down ~3 bpm and HRV up ~6 ms over the 35 days, so Trends has a direction to show.
 * - A sleep session every night (bedtime around 23:00, varied stage runs); last night 23:41 → 06:53 with the
 *   mockup's stage pattern and two short SpO₂ dips.
 * - Settings: a fake, locally administered ring address (no real ring), so the app opens on Today, a last sync
 *   2 minutes ago and the name "Sam" for the greeting (unless one is set). Today's steps total 914, as in the mockup.
 *
 * Nothing is dated after "now". Re-seeding is harmless (rows are de-duplicated by metric and time).
 * Undo with DEBUG_RESET, which wipes the database and all settings.
 */
object DebugSeeder {
    /** Locally administered (bit 1 of the first octet), so it can never be a real ring's address. */
    const val FAKE_RING_ADDRESS = "02:00:5E:ED:00:01"
    private const val DAYS = 35

    /** The seeded pairing: a YCBT TK5 at [FAKE_RING_ADDRESS]. */
    val FAKE_RING = PairedRing(YcbtFamily.ID, FAKE_RING_ADDRESS, "TK5")

    suspend fun seed(graph: AppGraph, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): Int {
        val batch = build(now, zone)
        graph.repository.store(batch)
        graph.settings.setPairedRing(FAKE_RING)
        graph.settings.setLastSyncedAt(now.minus(Duration.ofMinutes(2)))
        graph.settings.setRingName("TK5 A1B2")
        if (graph.settings.current().profile == null) graph.settings.setProfile(UserProfile(175, 72, Sex.UNSPECIFIED, 35))
        if (graph.settings.current().name == null) graph.settings.setName("Sam")
        graph.settings.setDeviceInfo(DeviceInfo(0, "1.18", 54))
        return batch.samples.size
    }

    fun build(now: Instant, zone: ZoneId): HistoryBatch {
        val rnd = Random(20261105)
        val today = now.atZone(zone).toLocalDate()
        val samples = mutableListOf<Sample>()
        fun add(m: Metric, t: Instant, v: Double) {
            if (t <= now) samples += Sample(m, t, v, SampleSource.HISTORY)
        }
        for (i in DAYS downTo 0) {
            val day = today.minusDays(i.toLong())
            val isToday = i == 0
            // Resting HR drifts from ~61.5 down to ~58.5, HRV from ~42 up to ~48, with day-to-day noise.
            // Today is a well-recovered day.
            val rhr = if (isToday) 59.0 else 58.5 + 3.0 * i / DAYS + rnd.nextDouble(-1.5, 1.5)
            val hrv = if (isToday) 47.5 else 42.0 + 6.0 * (DAYS - i) / DAYS + rnd.nextDouble(-3.0, 3.0)
            val start = day.atStartOfDay(zone).toInstant()
            for (k in 0 until 144) {
                val t = start.plusSeconds(k * 600L)
                val h = k / 6.0
                add(Metric.HEART_RATE, t, heartRate(h, rhr, rnd).roundToInt().toDouble())
                if (h >= 7.0 && h < 22.5) add(Metric.STRESS, t, stress(h, rnd, isToday).roundToInt().toDouble())
            }
            for (hh in 0..6) {
                add(Metric.HRV, start.plusSeconds(hh * 3600L + 1800), (hrv + rnd.nextDouble(-3.0, 3.0)).roundToInt().toDouble())
            }
            for (k in 0 until 14) {
                val spo2 = when {
                    isToday && k == 5 -> 93.0 // last night's two short dips
                    isToday && k == 10 -> 92.0
                    rnd.nextDouble() < 0.08 -> 94.0
                    else -> 96.0 + rnd.nextInt(0, 4)
                }
                add(Metric.SPO2, start.plusSeconds(k * 1800L), spo2)
            }
            for (hh in listOf(9, 14, 19)) {
                val t = start.plusSeconds(hh * 3600L + 600)
                add(Metric.BLOOD_PRESSURE_SYS, t, (118 + rnd.nextInt(-4, 5)).toDouble())
                add(Metric.BLOOD_PRESSURE_DIA, t, (76 + rnd.nextInt(-3, 4)).toDouble())
            }
            steps(day, zone, now, isToday, rnd).forEach { (m, tv) -> add(m, tv.first, tv.second) }
        }
        val nights = (DAYS downTo 1).map { earlierNight(today.minusDays(it.toLong()), zone, rnd) } + lastNight(today, zone)
        return HistoryBatch(samples, nights.filter { it.end <= now }, emptyList())
    }

    /** Sleep-low until ~06:50, a daytime level with a lunchtime walk and an evening wind-down. */
    private fun heartRate(h: Double, rhr: Double, rnd: Random): Double {
        val night = h < 6.9 || h >= 23.4
        val base = if (night) {
            // Lowest around 03:00.
            val x = if (h >= 23.4) h - 24 else h
            rhr + 3.0 * (1 - cos((x - 3.0) / 10.0 * PI))
        } else {
            72.0 + 6.0 * sin((h - 7) / 16.0 * PI) + 9.0 * exp(-((h - 12.5) * (h - 12.5)) / 0.3)
        }
        return base + rnd.nextDouble(-2.0, 2.0)
    }

    /** Calm mornings, a peak around 10:30, a calm break after, a smaller bump at 15:00. */
    private fun stress(h: Double, rnd: Random, today: Boolean): Double {
        val peak = if (today) 66.0 else 52.0 + rnd.nextDouble(-8.0, 10.0)
        val v = 19.0 + (peak - 19.0) * exp(-((h - 10.5) * (h - 10.5)) / 0.9) + 26.0 * exp(-((h - 15.0) * (h - 15.0)) / 1.2)
        return (v + rnd.nextDouble(-2.5, 2.5)).coerceIn(5.0, 95.0)
    }

    /**
     * Steps as the ring reports them: 30-minute STEPS buckets 07:00–21:00 (5–8k a day; today 914 so far) plus the
     * ring's running STEPS_DAY_TOTAL on the hour. Only buckets that have ended by [now] are written.
     */
    private fun steps(day: LocalDate, zone: ZoneId, now: Instant, today: Boolean, rnd: Random): List<Pair<Metric, Pair<Instant, Double>>> {
        val start = day.atStartOfDay(zone).toInstant()
        val buckets = (14 until 42).map { k -> start.plusSeconds(k * 1800L) }.filter { it.plusSeconds(1800) <= now }
        val counts = if (!today) buckets.map { 120 + rnd.nextInt(0, 330) } else {
            val n = buckets.size
            // A morning walk and a lunchtime stroll, quiet otherwise; totals 914.
            if (n == 0) emptyList() else {
                val w = List(n) { i -> 0.2 + exp(-((i - 3.0) * (i - 3.0)) / 2.0) * 3 + exp(-((i - 10.0) * (i - 10.0)) / 1.5) * 2 }
                val raw = w.map { (914 * it / w.sum()).toInt() }
                raw.mapIndexed { i, c -> if (i == n - 1) c + 914 - raw.sum() else c }
            }
        }
        val out = mutableListOf<Pair<Metric, Pair<Instant, Double>>>()
        var running = 0
        buckets.zip(counts).forEach { (t, c) ->
            out += Metric.STEPS to (t to c.toDouble())
            if (c > 0) {
                out += Metric.DISTANCE_M to (t to (c * 0.65).roundToInt().toDouble())
                out += Metric.CALORIES_KCAL to (t to (c * 0.05).roundToInt().toDouble())
            }
            running += c
            val end = t.plusSeconds(1800)
            if (end.atZone(zone).minute == 0) out += Metric.STEPS_DAY_TOTAL to (end to running.toDouble())
        }
        return out
    }

    /** A night ending on [wake]: bedtime around 23:00 (22:40–23:20), the mockup's runs each stretched 75–125%. */
    private fun earlierNight(wake: LocalDate, zone: ZoneId, rnd: Random): SleepSession {
        val start = wake.minusDays(1).atTime(22, 40).atZone(zone).toInstant().plusSeconds(rnd.nextLong(0, 41) * 60)
        var t = start
        val segs = MockupRuns.map { (st, min) ->
            val m = (min * rnd.nextDouble(0.75, 1.25)).roundToInt()
            SleepSegment(st, t, m * 60).also { t = t.plusSeconds(m * 60L) }
        }
        return SleepSession(start, t, segs)
    }

    /** 23:41 → 06:53, the mockup's stage runs (minutes). */
    private fun lastNight(today: LocalDate, zone: ZoneId): SleepSession {
        val start = today.minusDays(1).atTime(23, 41).atZone(zone).toInstant()
        var t = start
        val segs = MockupRuns.map { (st, min) -> SleepSegment(st, t, min * 60).also { t = t.plusSeconds(min * 60L) } }
        return SleepSession(start, t, segs)
    }

    private val MockupRuns = listOf(
        SleepStage.AWAKE to 16, SleepStage.LIGHT to 27, SleepStage.DEEP to 45, SleepStage.LIGHT to 24,
        SleepStage.REM to 36, SleepStage.LIGHT to 24, SleepStage.DEEP to 30, SleepStage.LIGHT to 35,
        SleepStage.REM to 26, SleepStage.LIGHT to 37, SleepStage.DEEP to 16, SleepStage.LIGHT to 39,
        SleepStage.REM to 26, SleepStage.LIGHT to 31, SleepStage.AWAKE to 20,
    )
}
