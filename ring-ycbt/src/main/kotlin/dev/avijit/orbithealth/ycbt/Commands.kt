package dev.avijit.orbithealth.ycbt

import dev.avijit.orbithealth.ring.MeasureKind
import dev.avijit.orbithealth.ring.Sex
import dev.avijit.orbithealth.ring.UserProfile
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

enum class HistoryType(val requestSub: Int, val deleteSub: Int?) {
    SPORT(0x02, 0x40),
    SLEEP(0x04, 0x41),
    HEART(0x06, 0x42),
    BLOOD(0x08, 0x43),
    ALL(0x09, 0x44),
    SPO2(0x1A, 0x45),
    BODY(0x33, 0x4E),
}

object Commands {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private fun flag(b: Boolean) = if (b) 1 else 0

    fun deviceInfo() = Frame(0x0200, bytes(0x47, 0x43))

    fun supportFunction() = Frame(0x0201, bytes(0x47, 0x46))

    fun setTime(now: ZonedDateTime) = Frame(
        0x0100,
        bytes(
            now.year and 0xFF, (now.year shr 8) and 0xFF, now.monthValue, now.dayOfMonth,
            now.hour, now.minute, now.second, now.dayOfWeek.value - 1,
        ),
    )

    fun setTimeZone(now: Instant, zone: ZoneId): Frame {
        val out = ByteArray(9)
        var s = now.epochSecond
        for (i in 0 until 8) {
            out[i] = (s and 0xFF).toByte()
            s = s shr 8
        }
        val hours = zone.rules.getOffset(now).totalSeconds / 3600 // truncates toward zero
        out[8] = hours.toByte()
        return Frame(0x0144, out)
    }

    fun setUserInfo(p: UserProfile) =
        Frame(0x0103, bytes(p.heightCm, p.weightKg, if (p.sex == Sex.FEMALE) 1 else 0, p.age))

    /** 0x010C [mode, interval min]: mode 1 = on, 0 = off (verified on the TK5). The ring does not validate [intervalMin]. */
    fun setHeartMonitor(enabled: Boolean, intervalMin: Int) = Frame(0x010C, bytes(flag(enabled), intervalMin))

    /** 0x0126 [on, interval min] (verified on the TK5). The ring does not validate [intervalMin]. */
    fun setSpo2Monitor(enabled: Boolean, intervalMin: Int) = Frame(0x0126, bytes(flag(enabled), intervalMin))

    fun realtime(on: Boolean, kind: Int) = Frame(0x0309, bytes(flag(on), kind, 2))

    /** 0x032F [on, type]: start or stop an on-demand measurement (all five types verified on the TK5). */
    fun measure(on: Boolean, kind: MeasureKind) = Frame(0x032F, bytes(flag(on), measureType(kind)))

    /** The wire type of [kind] in 0x032F and 0x040E. */
    fun measureType(kind: MeasureKind): Int = when (kind) {
        MeasureKind.HEART_RATE -> 0
        MeasureKind.BLOOD_PRESSURE -> 1
        MeasureKind.SPO2 -> 2
        MeasureKind.HRV -> 10
        MeasureKind.STRESS -> 12
    }

    fun historyRequest(t: HistoryType) = Frame(0x0500 or t.requestSub, ByteArray(0))

    fun historyAck(ok: Boolean) = Frame(0x0580, bytes(if (ok) 0 else 4))

    fun historyDelete(t: HistoryType) =
        Frame(0x0500 or requireNotNull(t.deleteSub) { "$t has no delete command" }, bytes(2))
}
