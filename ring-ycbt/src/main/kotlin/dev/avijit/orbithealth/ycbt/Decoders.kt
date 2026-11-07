package dev.avijit.orbithealth.ycbt

import dev.avijit.orbithealth.ring.DeviceInfo
import dev.avijit.orbithealth.ring.MeasureKind
import dev.avijit.orbithealth.ring.MeasureSpec
import dev.avijit.orbithealth.ring.Metric
import dev.avijit.orbithealth.ring.MonitorKind
import dev.avijit.orbithealth.ring.MonitorSpec
import dev.avijit.orbithealth.ring.RingCapabilities

private fun ByteArray.u8(i: Int) = this[i].toInt() and 0xFF
private fun ByteArray.le16(i: Int) = u8(i) or (u8(i + 1) shl 8)
private fun ByteArray.le24(i: Int) = u8(i) or (u8(i + 1) shl 8) or (u8(i + 2) shl 16)

/** [p] is the payload of a 0x0200 frame. */
fun decodeDeviceInfo(p: ByteArray): DeviceInfo =
    DeviceInfo(deviceId = p.le16(0), firmware = "${p.u8(3)}.${p.u8(2)}", batteryPct = p.u8(5))

/** (payload byte index, bit) positions in the 0x0201 support-function bitmap. */
object CapabilityBits {
    val STEP_COUNT = 0 to 7
    val SLEEP = 0 to 6
    val REAL_DATA = 0 to 5
    val HEART_RATE = 0 to 3
    val BLOOD = 0 to 0
    val BLOOD_OXYGEN = 1 to 3
    val RESPIRATORY_RATE = 1 to 2
    val HRV = 1 to 1
    val MORE_SPORT = 1 to 0
    val RUNNING = 11 to 7
    val PRESSURE = 22 to 6
}

/** [p] is the payload of a 0x0201 frame. Bytes beyond the payload read as 0 (unsupported). */
fun decodeCapabilities(p: ByteArray): RingCapabilities {
    fun has(bit: Pair<Int, Int>) = bit.first < p.size && (p.u8(bit.first) shr bit.second) and 1 == 1
    val m = mutableSetOf<Metric>()
    if (has(CapabilityBits.HEART_RATE)) m += Metric.HEART_RATE
    if (has(CapabilityBits.HRV)) m += Metric.HRV
    if (has(CapabilityBits.BLOOD_OXYGEN)) m += Metric.SPO2
    if (has(CapabilityBits.PRESSURE)) m += Metric.STRESS
    if (has(CapabilityBits.BLOOD)) { m += Metric.BLOOD_PRESSURE_SYS; m += Metric.BLOOD_PRESSURE_DIA }
    if (has(CapabilityBits.RESPIRATORY_RATE)) m += Metric.RESPIRATORY_RATE
    if (has(CapabilityBits.STEP_COUNT)) { m += Metric.STEPS; m += Metric.DISTANCE_M; m += Metric.CALORIES_KCAL }
    // Every kind is verified on the TK5 (docs/protocol/ycbt-settings.md); a ring declares it through its bitmap bit.
    val measure = buildMap {
        if (has(CapabilityBits.HEART_RATE)) put(MeasureKind.HEART_RATE, YcbtDefaults.HEART_RATE_MEASURE)
        if (has(CapabilityBits.BLOOD_OXYGEN)) put(MeasureKind.SPO2, YcbtDefaults.SPO2_MEASURE)
        if (has(CapabilityBits.HRV)) put(MeasureKind.HRV, YcbtDefaults.HRV_MEASURE)
        if (has(CapabilityBits.PRESSURE)) put(MeasureKind.STRESS, YcbtDefaults.STRESS_MEASURE)
        if (has(CapabilityBits.BLOOD)) put(MeasureKind.BLOOD_PRESSURE, YcbtDefaults.BLOOD_PRESSURE_MEASURE)
    }
    // Blood pressure (0x011C) answers 0xFC on the TK5, and HRV/temperature monitors are unverified: not offered.
    val monitoring = buildMap {
        if (has(CapabilityBits.HEART_RATE)) put(MonitorKind.HEART_RATE, YcbtDefaults.HEART_RATE_MONITOR)
        if (has(CapabilityBits.BLOOD_OXYGEN)) put(MonitorKind.SPO2, YcbtDefaults.SPO2_MONITOR)
    }
    return RingCapabilities(
        history = m,
        sleep = has(CapabilityBits.SLEEP),
        workouts = has(CapabilityBits.MORE_SPORT) || has(CapabilityBits.RUNNING),
        realtime = has(CapabilityBits.REAL_DATA),
        measure = measure,
        monitoring = monitoring,
        canClearHistory = true,
    )
}

/** Family defaults that the 0x0201 bitmap does not carry. */
object YcbtDefaults {
    /** Heart-rate monitor intervals the app offers. The ring acks any byte (even 255), so the driver enforces these. */
    val INTERVALS_MIN = listOf(10, 15, 20, 30, 60)
    val SPO2_INTERVALS_MIN = listOf(30, 60)
    val HEART_RATE_MEASURE = MeasureSpec(typicalSeconds = 30, live = true, estimate = false)
    val SPO2_MEASURE = MeasureSpec(typicalSeconds = 45, live = true, estimate = false)
    /** 75 s: on the TK5 the HRV "done" (0x040E 0a01) came after 60 s, so a 60 s stop cut it short. */
    val HRV_MEASURE = MeasureSpec(typicalSeconds = 75, live = true, estimate = false)
    /** Stress arrives as a single value (0x0610), so no Live values. */
    val STRESS_MEASURE = MeasureSpec(typicalSeconds = 60, live = false, estimate = true)
    val BLOOD_PRESSURE_MEASURE = MeasureSpec(typicalSeconds = 50, live = true, estimate = true)
    val HEART_RATE_MONITOR = MonitorSpec(INTERVALS_MIN, canTurnOff = true, supportsWindow = false)
    /** No time window on the TK5 ("overnight" in the app = on every 30 min). */
    val SPO2_MONITOR = MonitorSpec(SPO2_INTERVALS_MIN, canTurnOff = true, supportsWindow = false)

    val MEASURE: Map<MeasureKind, MeasureSpec> = mapOf(
        MeasureKind.HEART_RATE to HEART_RATE_MEASURE,
        MeasureKind.SPO2 to SPO2_MEASURE,
        MeasureKind.HRV to HRV_MEASURE,
        MeasureKind.STRESS to STRESS_MEASURE,
        MeasureKind.BLOOD_PRESSURE to BLOOD_PRESSURE_MEASURE,
    )
    val MONITORING: Map<MonitorKind, MonitorSpec> = mapOf(
        MonitorKind.HEART_RATE to HEART_RATE_MONITOR,
        MonitorKind.SPO2 to SPO2_MONITOR,
    )
}

/** 0x0601 payload: bpm in byte 0. */
fun decodeRealtimeHeart(p: ByteArray): Int? = if (p.isEmpty()) null else p.u8(0)

/** 0x040E status "measurement finished"; the TK5 sent only this value in every verified run. */
const val MEASURE_DONE = 1

/** 0x040E status the TK5 sends right after start when it is off a finger (observed 2026-11-06, ring removed). */
const val MEASURE_NOT_WORN = 2

/** 0x040E payload: (kind or null for an unknown type, raw status byte); null if too short. */
fun decodeMeasureStatus(p: ByteArray): Pair<MeasureKind?, Int>? {
    if (p.size < 2) return null
    val kind = MeasureKind.entries.firstOrNull { Commands.measureType(it) == p.u8(0) }
    return kind to p.u8(1)
}

/** 0x0602 payload: SpO₂ % in byte 0 (`63` = 99). */
fun decodeRealtimeSpo2(p: ByteArray): Int? = if (p.isEmpty()) null else p.u8(0)

/** One 0x0603 live blood frame: [sys, dia, hr, hrv, …]; zero = not measured. */
data class BloodReading(val sys: Int, val dia: Int, val hr: Int, val hrv: Int)

/** 0x0603 payload (14 bytes on the TK5): sys, dia, hr, hrv in bytes 0–3. */
fun decodeRealtimeBlood(p: ByteArray): BloodReading? =
    if (p.size < 4) null else BloodReading(p.u8(0), p.u8(1), p.u8(2), p.u8(3))

/**
 * 0x0610 payload (22 bytes): stress in bytes 4–5 as integer + fraction. Verified: `07 01` = 7.1 (one decimal digit,
 * fraction 0–9). A fraction byte of 10 or more was never seen; it is read as hundredths (`07 0a` = 7.10, `07 2d` = 7.45)
 * (monotonic within that range, unlike the old string join where `07 0a` = "7.10" = `07 01`). Unverified: a multiple of ten
 * (`07 0a`) still equals a tenths reading, which no interpretation of an unseen byte can rule out.
 */
fun decodeRealtimeStress(p: ByteArray): Double? {
    if (p.size < 6) return null
    val int = p.u8(4)
    val frac = p.u8(5)
    return if (frac < 10) int + frac / 10.0 else int + frac / 100.0
}

/** Live frame types per measure kind. */
object LiveFrames {
    const val HEART = 0x0601
    const val SPO2 = 0x0602
    const val BLOOD = 0x0603
    const val BODY = 0x0610
}

/**
 * The valid values in live frame [f] for a [kind] measurement, or null if the frame isn't one of that kind's or
 * carries nothing valid yet. BLOOD_PRESSURE gives SYS + DIA, plus HEART_RATE when the ring sent one.
 */
fun decodeMeasureValue(kind: MeasureKind, f: Frame): Map<Metric, Double>? {
    val p = f.payload
    return when (kind) {
        MeasureKind.HEART_RATE -> if (f.type != LiveFrames.HEART) null
            else decodeRealtimeHeart(p)?.takeIf(Validity::hr)?.let { mapOf(Metric.HEART_RATE to it.toDouble()) }
        MeasureKind.SPO2 -> if (f.type != LiveFrames.SPO2) null
            else decodeRealtimeSpo2(p)?.takeIf(Validity::spo2)?.let { mapOf(Metric.SPO2 to it.toDouble()) }
        MeasureKind.HRV -> if (f.type != LiveFrames.BLOOD) null
            else decodeRealtimeBlood(p)?.hrv?.takeIf(Validity::hrv)?.let { mapOf(Metric.HRV to it.toDouble()) }
        MeasureKind.BLOOD_PRESSURE -> {
            if (f.type != LiveFrames.BLOOD) return null
            val b = decodeRealtimeBlood(p)?.takeIf { Validity.bp(it.sys, it.dia) } ?: return null
            buildMap {
                put(Metric.BLOOD_PRESSURE_SYS, b.sys.toDouble())
                put(Metric.BLOOD_PRESSURE_DIA, b.dia.toDouble())
                if (Validity.hr(b.hr)) put(Metric.HEART_RATE, b.hr.toDouble())
            }
        }
        MeasureKind.STRESS -> if (f.type != LiveFrames.BODY) null
            else decodeRealtimeStress(p)?.takeIf { Validity.stress(it) }?.let { mapOf(Metric.STRESS to it) }
    }
}

/** Pedometer characteristic: [flags, steps LE24, distanceM LE24, kcal LE24] -> (steps, distanceM, kcal). */
fun decodePedometer(p: ByteArray): Triple<Int, Int, Int> = Triple(p.le24(1), p.le24(4), p.le24(7))

object Validity {
    fun hr(v: Int): Boolean = v in 30..220
    fun spo2(v: Int) = v in 70..100
    fun bp(s: Int, d: Int) = s in 70..230 && d in 40..150 && s > d
    fun hrv(v: Int) = v in 1..250
    fun stress(v: Int) = v in 1..100
    fun stress(v: Double) = v > 0.0 && v <= 100.0
}
