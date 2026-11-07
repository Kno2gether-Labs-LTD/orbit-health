package dev.avijit.orbithealth.ycbt

/**
 * One protocol frame. [type] is 16-bit: group = high byte, sub-command = low byte.
 *
 * Wire layout: `[type>>8, type&0xFF, len&0xFF, len>>8, payload..., crc&0xFF, crc>>8]`
 * where `len = payload.size + 6` (the whole frame, header and CRC included) and the
 * CRC covers everything before it.
 */
data class Frame(val type: Int, val payload: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is Frame && type == other.type && payload.contentEquals(other.payload)

    override fun hashCode(): Int = 31 * type + payload.contentHashCode()

    override fun toString(): String = "Frame(type=0x%04x, payload=%s)".format(type, payload.joinToString("") { "%02x".format(it.toInt() and 0xFF) })
}

private const val HEADER_SIZE = 4
private const val CRC_SIZE = 2
private const val OVERHEAD = HEADER_SIZE + CRC_SIZE

fun Frame.encode(): ByteArray {
    val len = payload.size + OVERHEAD
    val out = ByteArray(len)
    out[0] = (type shr 8).toByte()
    out[1] = type.toByte()
    out[2] = len.toByte()
    out[3] = (len shr 8).toByte()
    payload.copyInto(out, HEADER_SIZE)
    val crc = Crc16.compute(out, len - CRC_SIZE)
    out[len - 2] = crc.toByte()
    out[len - 1] = (crc shr 8).toByte()
    return out
}

object FrameDecoder {
    /** Returns the frame, or null if too short, length field mismatches, or CRC is wrong. */
    fun decode(bytes: ByteArray): Frame? {
        if (bytes.size < OVERHEAD) return null
        val declared = (bytes[2].toInt() and 0xFF) or ((bytes[3].toInt() and 0xFF) shl 8)
        if (declared != bytes.size) return null
        val crc = (bytes[bytes.size - 2].toInt() and 0xFF) or ((bytes[bytes.size - 1].toInt() and 0xFF) shl 8)
        if (Crc16.compute(bytes, bytes.size - CRC_SIZE) != crc) return null
        val type = ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
        val payload = bytes.copyOfRange(HEADER_SIZE, bytes.size - CRC_SIZE)
        return Frame(type, payload)
    }
}
