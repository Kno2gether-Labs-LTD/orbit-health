package dev.avijit.orbithealth.ycbt

import dev.avijit.orbithealth.ring.HistoryBatch
import dev.avijit.orbithealth.ring.Metric
import dev.avijit.orbithealth.ring.Sample
import dev.avijit.orbithealth.ring.SampleSource
import dev.avijit.orbithealth.ring.SleepStage
import dev.avijit.orbithealth.ring.totalSleepSec
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class HistoryDecodersTest {
    /** Fixtures are synthetic (tools/synth_fixtures.py, tz UTC); hand-built records use a non-UTC zone. */
    private val zone = ZoneId.of("Pacific/Auckland")

    private fun meta(name: String): Pair<Instant, ZoneId> {
        val url = checkNotNull(Thread.currentThread().contextClassLoader?.getResource("fixtures/$name.json"))
        val o = JSONObject(File(url.toURI()).readText())
        return OffsetDateTime.parse(o.getString("captured")).toInstant() to ZoneId.of(o.getString("tz"))
    }

    private fun decodeFixture(type: HistoryType, name: String): Pair<HistoryBatch, Instant> {
        val (captured, zone) = meta(name)
        val asm = HistoryAssembler(type)
        val done = fixtureRx(name).map { asm.accept(checkNotNull(FrameDecoder.decode(it))) }
            .last() as Step.Complete
        return HistoryDecoders.decode(type, done.data, zone) to captured
    }

    private fun assertRecent(times: List<Instant>, captured: Instant) {
        assertEquals(times.sorted(), times)
        times.forEach {
            assertTrue("$it vs $captured", it <= captured.plusSeconds(3600) && it >= captured.minus(Duration.ofDays(30)))
        }
    }

    private fun le(v: Long, n: Int) = ByteArray(n) { ((v shr (8 * it)) and 0xFF).toByte() }
    private fun cat(vararg p: ByteArray) = p.fold(ByteArray(0)) { a, b -> a + b }
    private fun raw(y: Int, mo: Int, d: Int, h: Int, mi: Int, sec: Int = 0): Long =
        java.time.LocalDateTime.of(y, mo, d, h, mi, sec).toEpochSecond(ZoneOffset.UTC) - 946684800L

    /** A fixture time: fixtures are UTC, so ring wall-clock time equals the UTC instant. */
    private fun utc(s: String) = Instant.parse(s)
    private fun HistoryBatch.values(m: Metric) = samples.filter { it.metric == m }.map { it.value.toInt() }
    private fun HistoryBatch.times(m: Metric) = samples.filter { it.metric == m }.map { it.time }

    @Test
    fun decodesHeartFixture() {
        val (b, captured) = decodeFixture(HistoryType.HEART, "history_heart")
        assertEquals(5, b.samples.size)
        assertTrue(b.samples.all { it.metric == Metric.HEART_RATE && it.source == SampleSource.HISTORY })
        assertEquals(listOf(58, 61, 66, 72, 69), b.values(Metric.HEART_RATE))
        assertEquals(
            listOf("06:00", "06:30", "07:00", "07:30", "08:00").map { utc("2025-01-15T$it:00Z") },
            b.times(Metric.HEART_RATE),
        )
        assertRecent(b.samples.map { it.time }, captured)
    }

    @Test
    fun decodesBloodFixture() {
        val (b, captured) = decodeFixture(HistoryType.BLOOD, "history_blood")
        val bp = b.samples.filter { it.metric == Metric.BLOOD_PRESSURE_SYS || it.metric == Metric.BLOOD_PRESSURE_DIA }
        assertEquals(6, bp.size)
        assertTrue(b.samples.all { it.metric in setOf(Metric.BLOOD_PRESSURE_SYS, Metric.BLOOD_PRESSURE_DIA, Metric.HEART_RATE) })
        assertEquals(listOf(118, 122, 116), b.values(Metric.BLOOD_PRESSURE_SYS))
        assertEquals(listOf(77, 79, 75), b.values(Metric.BLOOD_PRESSURE_DIA))
        assertEquals(listOf(64, 70, 67), b.values(Metric.HEART_RATE))
        assertEquals(
            listOf("06:45", "07:45", "08:45").map { utc("2025-01-15T$it:00Z") },
            b.times(Metric.BLOOD_PRESSURE_SYS),
        )
        assertRecent(b.samples.map { it.time }.distinct().sorted(), captured)
    }

    @Test
    fun decodesAllFixture() {
        val (b, captured) = decodeFixture(HistoryType.ALL, "history_all")
        // ALL-record steps are the cumulative daily total, never interval steps.
        assertEquals(0, b.samples.count { it.metric == Metric.STEPS })
        assertEquals(listOf(6240, 6915, 7402), b.values(Metric.STEPS_DAY_TOTAL))
        assertEquals(listOf(74, 81, 69), b.values(Metric.HEART_RATE))
        assertEquals(listOf(118, 121, 119), b.values(Metric.BLOOD_PRESSURE_SYS))
        assertEquals(listOf(77, 78, 76), b.values(Metric.BLOOD_PRESSURE_DIA))
        assertEquals(listOf(97, 98, 96), b.values(Metric.SPO2))
        assertEquals(listOf(14, 15, 13), b.values(Metric.RESPIRATORY_RATE))
        assertEquals(listOf(48, 42, 51), b.values(Metric.HRV))
        assertEquals(
            listOf("18", "19", "20").map { utc("2025-01-14T$it:00:00Z") },
            b.times(Metric.STEPS_DAY_TOTAL),
        )
        assertRecent(b.samples.map { it.time }.distinct().sorted(), captured)
    }

    @Test
    fun decodesBodyFixture() {
        val (b, captured) = decodeFixture(HistoryType.BODY, "history_body")
        // Integer parts only: the fraction bytes (46.2, 28.4, ...) are ignored by the decoder.
        assertEquals(listOf(28, 12, 33), b.values(Metric.STRESS))
        assertEquals(listOf(46, 58, 41), b.values(Metric.HRV))
        assertEquals(
            listOf(utc("2025-01-14T21:00:00Z"), utc("2025-01-15T03:00:00Z"), utc("2025-01-15T08:00:00Z")),
            b.times(Metric.STRESS),
        )
        assertRecent(b.samples.map { it.time }.distinct().sorted(), captured)
    }

    @Test
    fun decodesSportFixture() {
        val (b, captured) = decodeFixture(HistoryType.SPORT, "history_sport")
        // 30-minute activity buckets, not workouts.
        assertTrue(b.workouts.isEmpty())
        val steps = b.samples.filter { it.metric == Metric.STEPS }
        assertEquals(6, steps.size)
        assertEquals(1205.0, steps.sumOf { it.value }, 0.0)
        assertEquals(utc("2025-01-15T06:00:00Z"), steps.first().time)
        // Consecutive buckets start ~30 min apart (the real ring's clock gives 1799–1800 s; the synthetic
        // fixture alternates 1799/1800 on purpose to keep that quirk covered).
        steps.zipWithNext().forEach { (a, n) -> assertTrue(Duration.between(a.time, n.time).seconds in 1799L..1801L) }
        assertTrue(b.samples.all { it.metric in setOf(Metric.STEPS, Metric.DISTANCE_M, Metric.CALORIES_KCAL) })
        assertRecent(steps.map { it.time }, captured)
    }

    @Test
    fun decodesSyntheticSleepRecord() {
        val start = raw(2025, 1, 14, 23, 0)
        val rec = cat(
            le(0xAA55, 2), le(36, 2), le(start, 4), le(start + 720, 4), le(2, 2), ByteArray(6),
            byteArrayOf(241.toByte()), le(start, 4), le(600, 3),
            byteArrayOf(244.toByte()), le(start + 600, 4), le(120, 3),
        )
        assertEquals(36, rec.size)
        val b = HistoryDecoders.decode(HistoryType.SLEEP, rec, zone)
        assertEquals(1, b.sleep.size)
        val s = b.sleep[0]
        assertEquals(listOf(SleepStage.DEEP, SleepStage.AWAKE), s.segments.map { it.stage })
        assertEquals(600, s.totalSleepSec)
        assertEquals(RingTime.toInstant(start, zone), s.start)
        assertEquals(RingTime.toInstant(start + 720, zone), s.end)
    }

    @Test
    fun decodesNewFormatSleepWithThreeSegmentsAndTwoRecords() {
        val start = raw(2025, 1, 14, 23, 0)
        fun record(t0: Long) = cat(
            le(0xAA55, 2), le(44, 2), le(t0, 4), le(t0 + 1800, 4), le(0xFFFF, 2), ByteArray(6),
            byteArrayOf(242.toByte()), le(t0, 4), le(900, 3),
            byteArrayOf(243.toByte()), le(t0 + 900, 4), le(600, 3),
            byteArrayOf(244.toByte()), le(t0 + 1500, 4), le(300, 3),
        )
        val b = HistoryDecoders.decode(HistoryType.SLEEP, record(start) + record(start + 7200), zone)
        assertEquals(2, b.sleep.size)
        val s = b.sleep[0]
        assertEquals(listOf(SleepStage.LIGHT, SleepStage.REM, SleepStage.AWAKE), s.segments.map { it.stage })
        assertEquals(1500, s.totalSleepSec)
        assertEquals(s.segments.map { it.start }.sorted(), s.segments.map { it.start })
        assertTrue(s.end > s.start)
    }

    private fun assertMalformed(type: HistoryType, data: ByteArray) {
        try {
            HistoryDecoders.decode(type, data, zone)
            fail("expected MalformedHistory for $type with ${data.size} bytes")
        } catch (e: MalformedHistory) {
            // expected
        }
    }

    private fun sleepRecord(t0: Long, len: Int = 36) = cat(
        le(0xAA55, 2), le(len.toLong(), 2), le(t0, 4), le(t0 + 720, 4), le(2, 2), ByteArray(6),
        byteArrayOf(241.toByte()), le(t0, 4), le(600, 3),
        byteArrayOf(244.toByte()), le(t0 + 600, 4), le(120, 3),
    )

    @Test
    fun sleepRecordOverrunningBufferThrows() {
        // Header claims 100 bytes but only 20 arrived.
        assertMalformed(HistoryType.SLEEP, cat(le(0xAA55, 2), le(100, 2), ByteArray(16)))
        // A valid record followed by a truncated one.
        val start = raw(2025, 1, 14, 23, 0)
        assertMalformed(HistoryType.SLEEP, sleepRecord(start) + sleepRecord(start + 7200).copyOf(30))
    }

    @Test
    fun sleepRecordShorterThanHeaderThrows() {
        val start = raw(2025, 1, 14, 23, 0)
        assertMalformed(HistoryType.SLEEP, sleepRecord(start, len = 19))
        assertMalformed(HistoryType.SLEEP, sleepRecord(start, len = 0))
    }

    @Test
    fun leftoverSleepBytesThrow() {
        val start = raw(2025, 1, 14, 23, 0)
        assertMalformed(HistoryType.SLEEP, sleepRecord(start) + ByteArray(5))
        assertMalformed(HistoryType.SLEEP, sleepRecord(start) + ByteArray(19))
    }

    @Test
    fun emptySleepPayloadDecodesToNothing() {
        assertTrue(HistoryDecoders.decode(HistoryType.SLEEP, ByteArray(0), zone).sleep.isEmpty())
    }

    @Test
    fun fixedStridePayloadsWithTruncatedOrExtraBytesThrow() {
        val strides = mapOf(
            HistoryType.SPORT to 14, HistoryType.HEART to 6, HistoryType.BLOOD to 8,
            HistoryType.ALL to 20, HistoryType.SPO2 to 6, HistoryType.BODY to 28,
        )
        val t = raw(2025, 1, 14, 8, 0)
        for ((type, stride) in strides) {
            val two = cat(le(t, 4), ByteArray(stride - 4), le(t + 60, 4), ByteArray(stride - 4))
            HistoryDecoders.decode(type, two, zone) // well-formed: no throw
            assertMalformed(type, two.copyOf(two.size - 1)) // truncated last record
            assertMalformed(type, two + ByteArray(1)) // trailing byte
            assertMalformed(type, two + ByteArray(stride - 1)) // partial extra record
        }
    }

    @Test
    fun sportRecordFieldsMapToStepsDistanceAndKcal() {
        // Two buckets of the synthetic history_sport fixture: (415 steps, 298 m, 17 kcal) and (12, 8, 0).
        val start = raw(2025, 1, 15, 7, 29, 58)
        val quiet = raw(2025, 1, 15, 6, 29, 59)
        val data = cat(
            le(start, 4), le(start + 1800, 4), le(415, 2), le(298, 2), le(17, 2),
            le(quiet, 4), le(quiet + 1800, 4), le(12, 2), le(8, 2), le(0, 2),
        )
        val b = HistoryDecoders.decode(HistoryType.SPORT, data, zone)
        val t = RingTime.toInstant(start, zone)
        val tq = RingTime.toInstant(quiet, zone)
        assertEquals(
            listOf(
                Sample(Metric.STEPS, t, 415.0, SampleSource.HISTORY),
                Sample(Metric.DISTANCE_M, t, 298.0, SampleSource.HISTORY),
                Sample(Metric.CALORIES_KCAL, t, 17.0, SampleSource.HISTORY),
                Sample(Metric.STEPS, tq, 12.0, SampleSource.HISTORY),
                Sample(Metric.DISTANCE_M, tq, 8.0, SampleSource.HISTORY),
            ),
            b.samples,
        )
    }

    @Test
    fun sportFixtureFieldValues() {
        val (b, _) = decodeFixture(HistoryType.SPORT, "history_sport")
        assertEquals(listOf(64, 12, 230, 415, 96, 388), b.values(Metric.STEPS))
        assertEquals(1205, b.values(Metric.STEPS).sum())
        assertEquals(listOf(45, 8, 162, 298, 68, 276), b.values(Metric.DISTANCE_M))
        // The synthetic fixture has one 0-kcal bucket on purpose (as the real ring sent): it is skipped.
        assertEquals(listOf(3, 9, 17, 4, 15), b.values(Metric.CALORIES_KCAL))
    }

    @Test
    fun decodesSleepNightFixture() {
        // Synthetic night (two block frames). It checks the decoder against our own encoding of the layout,
        // not against a real overnight capture, so it does not justify YcbtDriver.CLEAR_SLEEP_VERIFIED.
        val (b, captured) = decodeFixture(HistoryType.SLEEP, "history_sleep_night")
        assertTrue(b.samples.isEmpty())
        assertEquals(1, b.sleep.size)
        val s = b.sleep[0]
        assertEquals(utc("2025-01-14T23:30:00Z"), s.start)
        assertEquals(utc("2025-01-15T07:00:00Z"), s.end)
        val (d, l, r, a) = listOf(SleepStage.DEEP, SleepStage.LIGHT, SleepStage.REM, SleepStage.AWAKE)
        assertEquals(listOf(l, d, l, r, a, l, d, l, r, l, r, l, a, l), s.segments.map { it.stage })
        assertEquals(
            listOf(1200, 3000, 2400, 1500, 300, 2700, 2100, 2400, 1800, 3000, 2100, 2400, 600, 1500),
            s.segments.map { it.durationSec },
        )
        // Segments tile the night with no gaps.
        assertEquals(s.start, s.segments.first().start)
        s.segments.zipWithNext().forEach { (x, y) -> assertEquals(x.start.plusSeconds(x.durationSec.toLong()), y.start) }
        assertEquals(27000 - 900, s.totalSleepSec)
        assertRecent(s.segments.map { it.start }, captured)
    }

    @Test
    fun decodesSyntheticSpo2AndDropsInvalid() {
        val t = raw(2025, 1, 14, 8, 0)
        val data = cat(le(t, 4), byteArrayOf(0, 97), le(t + 60, 4), byteArrayOf(0, 40))
        val b = HistoryDecoders.decode(HistoryType.SPO2, data, zone)
        assertEquals(
            listOf(Sample(Metric.SPO2, RingTime.toInstant(t, zone), 97.0, SampleSource.HISTORY)),
            b.samples,
        )
    }

    @Test
    fun invalidHeartValuesAreDropped() {
        val t = raw(2025, 1, 14, 8, 0)
        val data = cat(le(t, 4), byteArrayOf(0, 0), le(t + 60, 4), byteArrayOf(0, 250.toByte()), le(t + 120, 4), byteArrayOf(0, 60))
        val b = HistoryDecoders.decode(HistoryType.HEART, data, zone)
        assertEquals(listOf(60.0), b.samples.map { it.value })
    }
}
