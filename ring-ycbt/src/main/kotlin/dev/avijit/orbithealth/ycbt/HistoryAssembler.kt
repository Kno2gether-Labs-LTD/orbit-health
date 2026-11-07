package dev.avijit.orbithealth.ycbt

import java.io.ByteArrayOutputStream

sealed interface Step {
    /** Nothing to do yet (or the frame is not part of this transfer). */
    object Continue : Step

    /** The ring has no data of this type. */
    object Empty : Step

    /** CRC and length verified; [ack] must be sent to the ring. */
    data class Complete(val data: ByteArray, val ack: Frame) : Step

    /** Verification failed; [nack] must be sent and no records decoded. */
    data class Corrupt(val nack: Frame) : Step
}

/**
 * Reassembles one history transfer (group 0x05): header (same sub as the request), block frames,
 * then the 0x80 end frame carrying `[packetCount LE16, totalBytes LE16, crc LE16]`.
 * Feed every received frame to [accept]; the assembler resets itself after each terminal step.
 */
class HistoryAssembler(val type: HistoryType) {
    private var started = false
    private var buffer = ByteArrayOutputStream()

    private fun reset() {
        started = false
        buffer = ByteArrayOutputStream()
    }

    fun accept(f: Frame): Step {
        if (f.type shr 8 != GROUP) return Step.Continue
        val sub = f.type and 0xFF
        return when {
            sub == type.requestSub -> {
                reset()
                if (f.payload.size <= NO_DATA_MAX_PAYLOAD) {
                    Step.Empty
                } else {
                    started = true
                    Step.Continue
                }
            }
            !started -> Step.Continue
            sub == END_SUB -> finish(f.payload)
            else -> {
                buffer.write(f.payload)
                Step.Continue
            }
        }
    }

    private fun finish(p: ByteArray): Step {
        val data = buffer.toByteArray()
        reset()
        val ok = p.size >= 6 && run {
            val total = (p[2].toInt() and 0xFF) or ((p[3].toInt() and 0xFF) shl 8)
            val crc = (p[4].toInt() and 0xFF) or ((p[5].toInt() and 0xFF) shl 8)
            total == (data.size and 0xFFFF) && crc == Crc16.compute(data, data.size)
        }
        return if (ok) Step.Complete(data, Commands.historyAck(true)) else Step.Corrupt(Commands.historyAck(false))
    }

    private companion object {
        const val GROUP = 0x05
        const val END_SUB = 0x80
        const val NO_DATA_MAX_PAYLOAD = 9
    }
}
