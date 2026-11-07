package dev.avijit.orbithealth.ycbt

/** CRC-16 used by the Yucheng (YCBT) frame protocol. */
object Crc16 {
    fun compute(bytes: ByteArray, length: Int = bytes.size, seed: Int = 0xFFFF): Int {
        var s = seed
        for (i in 0 until length) {
            s = ((s shl 8) and 0xFF00) or ((s shr 8) and 0xFF)
            s = s xor (bytes[i].toInt() and 0xFF)
            s = s xor ((s and 0xFF) shr 4)
            s = s xor ((s shl 12) and 0xFFFF)
            s = s xor (((s and 0xFF) shl 5) and 0xFFFF)
        }
        return s and 0xFFFF
    }
}
