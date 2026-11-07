package dev.avijit.orbithealth.ycbt

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** Ring timestamps are seconds since 2000-01-01 in local wall-clock time. */
object RingTime {
    private const val EPOCH_2000 = 946684800L

    fun toInstant(raw: Long, zone: ZoneId): Instant =
        LocalDateTime.ofEpochSecond(EPOCH_2000 + raw, 0, ZoneOffset.UTC).atZone(zone).toInstant()
}
