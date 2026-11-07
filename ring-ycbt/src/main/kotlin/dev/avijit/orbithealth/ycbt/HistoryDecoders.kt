package dev.avijit.orbithealth.ycbt

import dev.avijit.orbithealth.ring.HistoryBatch
import dev.avijit.orbithealth.ring.Metric
import dev.avijit.orbithealth.ring.Sample
import dev.avijit.orbithealth.ring.SampleSource
import dev.avijit.orbithealth.ring.SleepSegment
import dev.avijit.orbithealth.ring.SleepSession
import dev.avijit.orbithealth.ring.SleepStage
import java.time.Instant
import java.time.ZoneId

private fun ByteArray.u8(i: Int) = this[i].toInt() and 0xFF
private fun ByteArray.u16(i: Int) = u8(i) or (u8(i + 1) shl 8)
private fun ByteArray.u24(i: Int) = u16(i) or (u8(i + 2) shl 16)
private fun ByteArray.u32(i: Int) = u16(i).toLong() or (u16(i + 2).toLong() shl 16)

/** Thrown when a history payload does not have the expected record structure. */
class MalformedHistory(message: String) : IllegalArgumentException(message)

/**
 * Decodes the concatenated, CRC-verified payload of a history transfer. Out-of-range values are dropped,
 * but a payload whose structure does not match (fixed-stride size mismatch, bad sleep record length,
 * leftover bytes) throws [MalformedHistory]: the driver then leaves that type out of the batch and
 * never clears it, so nothing is deleted from the ring on the strength of a partial decode.
 */
object HistoryDecoders {
    fun decode(type: HistoryType, data: ByteArray, zone: ZoneId): HistoryBatch {
        val samples = mutableListOf<Sample>()
        val sleep = mutableListOf<SleepSession>()
        fun time(off: Int, at: Int) = RingTime.toInstant(data.u32(off + at), zone)
        fun add(m: Metric, t: Instant, v: Int) {
            samples += Sample(m, t, v.toDouble(), SampleSource.HISTORY)
        }

        when (type) {
            HistoryType.SLEEP -> decodeSleep(data, zone, sleep)
            // 30-minute activity buckets (start, end, steps, distance m, kcal), confirmed by capture: not workouts.
            // Steps are kept even when 0 so a day's buckets are complete; zero distance/kcal rows are skipped.
            HistoryType.SPORT -> forEachRecord(data, 14) { o ->
                val start = time(o, 0)
                add(Metric.STEPS, start, data.u16(o + 8))
                data.u16(o + 10).takeIf { it > 0 }?.let { add(Metric.DISTANCE_M, start, it) }
                data.u16(o + 12).takeIf { it > 0 }?.let { add(Metric.CALORIES_KCAL, start, it) }
            }
            HistoryType.HEART -> forEachRecord(data, 6) { o ->
                val bpm = data.u8(o + 5)
                if (Validity.hr(bpm)) add(Metric.HEART_RATE, time(o, 0), bpm)
            }
            HistoryType.BLOOD -> forEachRecord(data, 8) { o ->
                val t = time(o, 0)
                val sys = data.u8(o + 5)
                val dia = data.u8(o + 6)
                val hr = data.u8(o + 7)
                if (Validity.bp(sys, dia)) {
                    add(Metric.BLOOD_PRESSURE_SYS, t, sys)
                    add(Metric.BLOOD_PRESSURE_DIA, t, dia)
                }
                if (Validity.hr(hr)) add(Metric.HEART_RATE, t, hr)
            }
            HistoryType.ALL -> forEachRecord(data, 20) { o ->
                val t = time(o, 0)
                val steps = data.u16(o + 4)
                val hr = data.u8(o + 6)
                val sys = data.u8(o + 7)
                val dia = data.u8(o + 8)
                val spo2 = data.u8(o + 9)
                val resp = data.u8(o + 10)
                val hrv = data.u8(o + 11)
                // ALL-record steps are the cumulative total for the day so far (e.g. 6240, 6915, 7402 on successive hourly records).
                if (steps > 0) add(Metric.STEPS_DAY_TOTAL, t, steps)
                if (Validity.hr(hr)) add(Metric.HEART_RATE, t, hr)
                if (Validity.bp(sys, dia)) {
                    add(Metric.BLOOD_PRESSURE_SYS, t, sys)
                    add(Metric.BLOOD_PRESSURE_DIA, t, dia)
                }
                if (Validity.spo2(spo2)) add(Metric.SPO2, t, spo2)
                if (resp in 4..60) add(Metric.RESPIRATORY_RATE, t, resp)
                if (Validity.hrv(hrv)) add(Metric.HRV, t, hrv)
            }
            HistoryType.SPO2 -> forEachRecord(data, 6) { o ->
                val v = data.u8(o + 5)
                if (Validity.spo2(v)) add(Metric.SPO2, time(o, 0), v)
            }
            // Body record (28 B, confirmed by capture): t, loadIndex [4..5], hrv int/frac [6..7],
            // stress int/frac [8..9]; remaining fields ignored.
            HistoryType.BODY -> forEachRecord(data, 28) { o ->
                val t = time(o, 0)
                val hrv = data.u8(o + 6)
                val stress = data.u8(o + 8)
                if (Validity.stress(stress)) add(Metric.STRESS, t, stress)
                if (Validity.hrv(hrv)) add(Metric.HRV, t, hrv)
            }
        }
        return HistoryBatch(samples, sleep, workouts = emptyList())
    }

    private inline fun forEachRecord(data: ByteArray, stride: Int, block: (Int) -> Unit) {
        if (data.size % stride != 0) {
            throw MalformedHistory("${data.size} bytes is not a multiple of the $stride-byte record")
        }
        var o = 0
        while (o + stride <= data.size) {
            block(o)
            o += stride
        }
    }

    private const val SLEEP_HEADER = 20
    private const val SLEEP_SEGMENT = 8

    private fun decodeSleep(data: ByteArray, zone: ZoneId, out: MutableList<SleepSession>) {
        var o = 0
        while (o < data.size) {
            if (o + SLEEP_HEADER > data.size) {
                throw MalformedHistory("${data.size - o} sleep bytes left over at offset $o")
            }
            val len = data.u16(o + 2)
            if (len < SLEEP_HEADER) throw MalformedHistory("sleep record at $o has length $len < $SLEEP_HEADER")
            if (o + len > data.size) throw MalformedHistory("sleep record at $o (length $len) overruns ${data.size} bytes")
            val segments = (0 until (len - SLEEP_HEADER) / SLEEP_SEGMENT).mapNotNull { i ->
                val s = o + SLEEP_HEADER + i * SLEEP_SEGMENT
                val stage = when (data.u8(s)) {
                    241 -> SleepStage.DEEP
                    242 -> SleepStage.LIGHT
                    243 -> SleepStage.REM
                    244 -> SleepStage.AWAKE
                    else -> return@mapNotNull null
                }
                SleepSegment(stage, RingTime.toInstant(data.u32(s + 1), zone), data.u24(s + 5))
            }
            val start = RingTime.toInstant(data.u32(o + 4), zone)
            val end = RingTime.toInstant(data.u32(o + 8), zone)
            if (end > start) out += SleepSession(start, end, segments)
            o += len
        }
    }
}
