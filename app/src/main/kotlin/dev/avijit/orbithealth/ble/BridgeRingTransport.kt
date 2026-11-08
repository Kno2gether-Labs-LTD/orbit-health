package dev.avijit.orbithealth.ble

import dev.avijit.orbithealth.ring.GattChar
import dev.avijit.orbithealth.ring.RingPacket
import dev.avijit.orbithealth.ring.RingTransport
import dev.avijit.orbithealth.ycbt.YcbtFamily
import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
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
 * Transport that relays ring traffic through the Mac BLE bridge (tools/ble_bridge.py), chosen only by the shake-revealed
 * "Use Mac bridge" developer switch. Wire format both ways: [len u16 BE][payload]. Client->bridge payload = raw command
 * bytes; bridge->client payload = [char tag u8 (1 = be940001, 3 = be940003)][bytes]. The tag becomes the packet's YCBT
 * characteristic. The bridge only relays the TK5 (YCBT): writes go to be940001, and reads are not supported.
 *
 * It only ever connects to the computer the phone is attached to: [host] must be loopback (127.0.0.1 or ::1), or
 * 10.0.2.2 on a real emulator (its alias for the host's loopback). A real phone reaches the bridge through
 * `adb reverse tcp:7777 tcp:7777`. Nothing on the local network can be reached.
 *
 * Loopback on a phone is shared by every app (and `adb reverse` exposes the bridge to all of them), so both sides
 * prove they know [code], the code the app shows when the developer switch is turned on (and that the user passes to
 * `ble_bridge.py --code`). The client proves first, so the bridge never signs anything for an unproven client:
 *  1. bridge -> app, first frame: `"OBH2"` + a fresh 16-byte nonce_b;
 *  2. app -> bridge: nonce_c (16 bytes) + HMAC-SHA256(code, `"orbit-client"` + nonce_b + nonce_c);
 *  3. the bridge checks it (constant time), else drops the client without touching the ring (and quits after 3 bad
 *     attempts); then bridge -> app: tag 0xFE + HMAC-SHA256(code, `"orbit-bridge"` + nonce_c + nonce_b).
 * The app accepts no ring frame and sends no ring command until step 3 checks out; anything else, a wrong proof or
 * silence for [HANDSHAKE_TIMEOUT_MS] closes the link. The code is compared without its dash, case-insensitively.
 */
class BridgeRingTransport(
    private val code: String,
    private val host: String = hostFor(isEmulator()),
    private val port: Int = PORT,
    emulator: Boolean = isEmulator(),
) : RingTransport {
    init {
        require(host in LOOPBACK || (emulator && host == EMULATOR_HOST_LOOPBACK)) {
            "The Mac bridge is only reachable on this computer (loopback), not at '$host'"
        }
        require(code.isNotBlank()) { "The Mac bridge needs the code shown in Developer options" }
    }

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
        val din = try {
            withContext(Dispatchers.IO) {
                s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                DataInputStream(s.getInputStream().buffered()).also { handshake(s, it) }
            }
        } catch (t: Throwable) {
            close()
            throw if (t is IOException || t is kotlinx.coroutines.CancellationException) t
            else IOException("Bridge connect failed: ${t.message}", t)
        }
        _connected.value = true
        scope.launch { readLoop(din) }
    }

    /** The mutual code handshake (see the class comment); throws [IOException] unless the bridge proves the code. */
    private fun handshake(s: Socket, din: DataInputStream) {
        s.soTimeout = HANDSHAKE_TIMEOUT_MS
        fun frame(): ByteArray = try {
            val len = din.readUnsignedShort()
            ByteArray(len).also { din.readFully(it) }
        } catch (e: java.net.SocketTimeoutException) {
            throw IOException("The Mac bridge didn't answer. Start it with the code shown in Developer options.", e)
        }
        val challenge = frame()
        if (challenge.size != MAGIC.size + NONCE_BYTES || !challenge.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            throw IOException(IMPOSTOR)
        }
        val nonceB = challenge.copyOfRange(MAGIC.size, challenge.size)
        val nonceC = ByteArray(NONCE_BYTES).also { SecureRandom().nextBytes(it) }
        val answer = nonceC + clientProof(code, nonceB, nonceC)
        s.getOutputStream().apply {
            write(byteArrayOf((answer.size shr 8).toByte(), answer.size.toByte()) + answer)
            flush()
        }
        val reply = frame()
        s.soTimeout = 0
        val expected = bridgeProof(code, nonceC, nonceB)
        val ok = reply.size == 1 + expected.size && (reply[0].toInt() and 0xFF) == PROOF_TAG &&
            MessageDigest.isEqual(expected, reply.copyOfRange(1, reply.size))
        if (!ok) throw IOException(IMPOSTOR)
    }

    private fun readLoop(din: DataInputStream) {
        try {
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
        const val HANDSHAKE_TIMEOUT_MS = 5_000
        const val PORT = 7777
        private const val EMULATOR_HOST_LOOPBACK = "10.0.2.2"
        private val LOOPBACK = setOf("127.0.0.1", "::1")
        private val MAGIC = "OBH2".toByteArray(Charsets.US_ASCII)
        private const val NONCE_BYTES = 16
        const val PROOF_TAG = 0xFE
        private const val IMPOSTOR = "Something on this phone answered as the Mac bridge without the right code; not using it."

        /** HMAC-SHA256(code, "orbit-client" + nonce_b + nonce_c): the app's proof (checked by tools/ble_bridge.py). */
        fun clientProof(code: String, nonceB: ByteArray, nonceC: ByteArray): ByteArray = hmac(code, "orbit-client", nonceB + nonceC)

        /** HMAC-SHA256(code, "orbit-bridge" + nonce_c + nonce_b): the bridge's answer, once the app has proved itself. */
        fun bridgeProof(code: String, nonceC: ByteArray, nonceB: ByteArray): ByteArray = hmac(code, "orbit-bridge", nonceC + nonceB)

        /** The code as a key: without dashes or spaces, upper case. */
        fun codeKey(code: String): ByteArray = code.filter { it.isLetterOrDigit() }.uppercase().toByteArray(Charsets.US_ASCII)

        private fun hmac(code: String, label: String, data: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(codeKey(code), "HmacSHA256"))
            doFinal(label.toByteArray(Charsets.US_ASCII) + data)
        }

        /** Where the bridge listens as seen from this device: the emulator's host alias, else loopback (adb reverse). */
        fun hostFor(emulator: Boolean): String = if (emulator) EMULATOR_HOST_LOOPBACK else "127.0.0.1"

        /**
         * Strictly the Android emulator: its virtual hardware (`ranchu`, or the older `goldfish`) or `ro.kernel.qemu=1`.
         * Never guessed from fingerprints or model names, which any device can carry.
         */
        fun isEmulator(
            hardware: String = android.os.Build.HARDWARE.orEmpty(),
            qemu: String? = systemProperty("ro.kernel.qemu"),
        ): Boolean = hardware == "ranchu" || hardware == "goldfish" || qemu == "1"

        private fun systemProperty(name: String): String? = try {
            Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, name) as? String
        } catch (_: Throwable) {
            null
        }

        /** Bridge char tag -> the YCBT characteristic it relays; null for an unknown tag (dropped). */
        internal fun charForTag(tag: Int): GattChar? = when (tag) {
            1 -> YcbtFamily.COMMAND
            3 -> YcbtFamily.DATA
            else -> null
        }
    }
}
