package dev.avijit.orbithealth.ycbt

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryAssemblerTest {
    private fun replay(type: HistoryType, name: String, mutate: (Frame) -> Frame = { it }): List<Step> {
        val asm = HistoryAssembler(type)
        return fixtureRx(name).map { asm.accept(mutate(checkNotNull(FrameDecoder.decode(it)))) }
    }

    @Test
    fun assemblesHeartFixtureTransfer() {
        val steps = replay(HistoryType.HEART, "history_heart")
        val last = steps.last()
        assertTrue("last was $last", last is Step.Complete)
        last as Step.Complete
        assertEquals(Frame(0x0580, byteArrayOf(0)), last.ack)
        assertEquals(30, last.data.size)
        assertTrue(steps.dropLast(1).all { it == Step.Continue })
    }

    @Test
    fun assemblesEveryFixtureTransfer() {
        val expected = mapOf(
            HistoryType.SPORT to ("history_sport" to 84),
            HistoryType.HEART to ("history_heart" to 30),
            HistoryType.BLOOD to ("history_blood" to 24),
            HistoryType.ALL to ("history_all" to 60),
            HistoryType.BODY to ("history_body" to 84),
            HistoryType.SLEEP to ("history_sleep_night" to 132),
        )
        for ((type, v) in expected) {
            val last = replay(type, v.first).last()
            assertTrue("$type: $last", last is Step.Complete)
            assertEquals(v.second, (last as Step.Complete).data.size)
        }
    }

    @Test
    fun multiPacketTransferConcatenatesBlocksInOrder() {
        val frames = fixtureRx("history_sleep_night").map { checkNotNull(FrameDecoder.decode(it)) }
        assertEquals(listOf(0x0504, 0x0513, 0x0513, 0x0580), frames.map { it.type })
        assertEquals(listOf(84, 48), frames.filter { it.type == 0x0513 }.map { it.payload.size })
        val asm = HistoryAssembler(HistoryType.SLEEP)
        val steps = frames.map { asm.accept(it) }
        assertEquals(listOf<Step>(Step.Continue, Step.Continue, Step.Continue), steps.dropLast(1))
        val done = steps.last() as Step.Complete
        assertArrayEquals(frames[1].payload + frames[2].payload, done.data)
        // Blocks out of order fail the CRC.
        val swapped = listOf(frames[0], frames[2], frames[1], frames[3]).map { asm.accept(it) }.last()
        assertTrue(swapped is Step.Corrupt)
    }

    @Test
    fun sleepFixtureHeaderIsEmpty() {
        // The synthetic history_sleep fixture reproduces the real ring's "no sleep stored" 2-byte header on purpose.
        val steps = replay(HistoryType.SLEEP, "history_sleep")
        assertEquals(listOf<Step>(Step.Empty), steps)
    }

    @Test
    fun emptyHeaderIsEmpty() {
        val asm = HistoryAssembler(HistoryType.SLEEP)
        assertEquals(Step.Empty, asm.accept(Frame(0x0504, ByteArray(6))))
    }

    @Test
    fun crcMismatchProducesNackAndNoRecords() {
        var flipped = false
        val steps = replay(HistoryType.HEART, "history_heart") { f ->
            if (!flipped && f.type == 0x0515) {
                flipped = true
                Frame(f.type, f.payload.copyOf().also { it[3] = (it[3].toInt() xor 1).toByte() })
            } else f
        }
        val last = steps.last()
        assertTrue(last is Step.Corrupt)
        assertEquals(Frame(0x0580, byteArrayOf(4)), (last as Step.Corrupt).nack)
        assertTrue(steps.none { it is Step.Complete })
    }

    @Test
    fun lengthMismatchIsCorrupt() {
        val asm = HistoryAssembler(HistoryType.HEART)
        val frames = fixtureRx("history_heart").map { checkNotNull(FrameDecoder.decode(it)) }
        asm.accept(frames[0])
        // drop the block frame entirely: totals and CRC no longer match
        assertTrue(asm.accept(frames[2]) is Step.Corrupt)
    }

    @Test
    fun ignoresOtherGroupsAndResetsAfterTransfer() {
        val asm = HistoryAssembler(HistoryType.HEART)
        val frames = fixtureRx("history_heart").map { checkNotNull(FrameDecoder.decode(it)) }
        assertEquals(Step.Continue, asm.accept(Frame(0x0601, byteArrayOf(70))))
        val r1 = frames.map { asm.accept(it) }.last()
        assertTrue(r1 is Step.Complete)
        val r2 = frames.map { asm.accept(it) }.last()
        assertTrue(r2 is Step.Complete)
        assertArrayEquals((r1 as Step.Complete).data, (r2 as Step.Complete).data)
    }
}
