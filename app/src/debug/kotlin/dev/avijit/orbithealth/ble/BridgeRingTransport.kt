package dev.avijit.orbithealth.ble

import dev.avijit.orbithealth.ring.GattChar
import dev.avijit.orbithealth.ring.RingPacket
import dev.avijit.orbithealth.ring.RingTransport
import dev.avijit.orbithealth.ycbt.YcbtFamily
import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Debug-only transport that relays ring traffic through the Mac BLE bridge (tools/ble_bridge.py).
 * Wire format both ways: [len u16 BE][payload]. Client->bridge payload = raw command bytes;
 * bridge->client payload = [char tag u8 (1 = be940001, 3 = be940003)][bytes]. The tag becomes the packet's YCBT
 * characteristic. The bridge only relays the TK5 (YCBT): writes go to be940001, and reads are not supported.
 */
class BridgeRingTransport(
    private val host: String = "10.0.2.2",
    private val port: Int = 7777,
) : RingTransport {
    // Hot flow: replies emitted while write() is suspended reach earlier subscribers.
    private val _packets = MutableSharedFlow<RingPacket>(extraBufferCapacity = 256)
    override val packets: Flow<RingPacket> = _packets.asSharedFlow()

    private val _connected = MutableStateFlow(false)
    override val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeLock = Mutex()
    @Volatile private var socket: Socket? = null
    @Volatile private var closed = false

    suspend fun connect() {
        check(socket == null && !closed) { "BridgeRingTransport is single-use" }
        val s = Socket()
        socket = s
        try {
            withContext(Dispatchers.IO) { s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS) }
        } catch (t: Throwable) {
            close()
            throw if (t is IOException || t is kotlinx.coroutines.CancellationException) t
            else IOException("Bridge connect failed: ${t.message}", t)
        }
        _connected.value = true
        scope.launch { readLoop(s) }
    }

    private fun readLoop(s: Socket) {
        try {
            val din = DataInputStream(s.getInputStream().buffered())
            while (true) {
                val len = din.readUnsignedShort()
                val payload = ByteArray(len).also { din.readFully(it) }
                if (len < 1) continue
                val char = charForTag(payload[0].toInt() and 0xFF) ?: continue
                _packets.tryEmit(RingPacket(char, payload.copyOfRange(1, len)))
            }
        } catch (_: IOException) {
            // EOF or socket closed: fall through.
        } finally {
            close()
        }
    }

    override suspend fun write(bytes: ByteArray) {
        val s = socket
        if (s == null || closed) throw IOException("Not connected")
        require(bytes.size <= 0xFFFF) { "frame too large" }
        writeLock.withLock {
            withContext(Dispatchers.IO) {
                try {
                    val out = s.getOutputStream()
                    out.write(byteArrayOf((bytes.size shr 8).toByte(), bytes.size.toByte()) + bytes)
                    out.flush()
                } catch (e: IOException) {
                    close()
                    throw e
                }
            }
        }
    }

    override suspend fun write(char: GattChar, bytes: ByteArray) {
        if (char != YcbtFamily.COMMAND) throw UnsupportedOperationException("The Mac bridge writes only to be940001")
        write(bytes)
    }

    override suspend fun read(char: GattChar): ByteArray =
        throw UnsupportedOperationException("The Mac bridge can't read characteristics")

    fun close() {
        if (closed) return
        closed = true
        _connected.value = false
        try { socket?.close() } catch (_: IOException) {}
        scope.cancel()
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 5_000

        /** Bridge char tag -> the YCBT characteristic it relays; null for an unknown tag (dropped). */
        internal fun charForTag(tag: Int): GattChar? = when (tag) {
            1 -> YcbtFamily.COMMAND
            3 -> YcbtFamily.DATA
            else -> null
        }
    }
}
