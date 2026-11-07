package dev.avijit.orbithealth.ycbt

import dev.avijit.orbithealth.ring.GattChar
import dev.avijit.orbithealth.ring.RingPacket
import dev.avijit.orbithealth.ring.RingTransport
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.IOException

/**
 * Scripted ring: every written frame is recorded in [tx] and answered with the rx frames
 * [script] returns for it. [dropAfter] (a tx frame type) disconnects right after that frame's
 * replies have been delivered; [drop] disconnects immediately. Writes after a drop throw.
 */
class FakeTransport(private val script: (Frame) -> List<ByteArray>) : RingTransport {
    private val _packets = MutableSharedFlow<RingPacket>(extraBufferCapacity = 4096)
    private val _connected = MutableStateFlow(true)
    override val packets: SharedFlow<RingPacket> = _packets
    override val connected: StateFlow<Boolean> = _connected

    val tx = mutableListOf<Frame>()
    var dropAfter: Int? = null

    override suspend fun write(bytes: ByteArray) {
        if (!_connected.value) throw IOException("disconnected")
        val frame = checkNotNull(FrameDecoder.decode(bytes)) { "driver wrote an invalid frame: ${bytes.toHex()}" }
        tx += frame
        for (reply in script(frame)) check(_packets.tryEmit(RingPacket(NOTIFY, reply)))
        if (dropAfter == frame.type) drop()
    }

    override suspend fun write(char: GattChar, bytes: ByteArray) {
        check(char == YcbtFamily.gattProfile.defaultWrite) { "YCBT writes only to be940001, not $char" }
        write(bytes)
    }

    override suspend fun read(char: GattChar): ByteArray = throw UnsupportedOperationException("YCBT reads nothing")

    /** A frame from the ring that no write prompted (e.g. a late "done"). */
    fun inject(bytes: ByteArray) = check(_packets.tryEmit(RingPacket(NOTIFY, bytes)))

    fun drop() {
        _connected.value = false
    }

    companion object {
        private val NOTIFY = YcbtFamily.gattProfile.notifies.last()

        /** tx frame type -> the rx frames that followed that tx in the given fixtures. */
        fun fixtureReplies(vararg names: String): Map<Int, List<ByteArray>> {
            val out = mutableMapOf<Int, MutableList<ByteArray>>()
            for (name in names) {
                var current: MutableList<ByteArray>? = null
                for (f in fixtureFrames(name)) {
                    if (f.dir == "tx") {
                        val type = checkNotNull(FrameDecoder.decode(f.bytes)).type
                        current = out.getOrPut(type) { mutableListOf() }.also { it.clear() }
                    } else {
                        current?.add(f.bytes)
                    }
                }
            }
            return out
        }

        /** Generic one-byte success reply of the same type. */
        fun echo(f: Frame): ByteArray = Frame(f.type, byteArrayOf(0)).encode()
    }
}
