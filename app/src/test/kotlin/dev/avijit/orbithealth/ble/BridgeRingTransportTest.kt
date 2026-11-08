package dev.avijit.orbithealth.ble

import org.junit.Assert.assertEquals
import dev.avijit.orbithealth.ycbt.YcbtFamily
import java.io.DataInputStream
import java.net.ServerSocket
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CoroutineStart
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeRingTransportTest {
    @Test
    fun bridgeOnlyEverTalksToThisComputer() {
        // The emulator reaches the computer's loopback through 10.0.2.2; a phone over USB through adb reverse.
        assertEquals("10.0.2.2", BridgeRingTransport.hostFor(emulator = true))
        assertEquals("127.0.0.1", BridgeRingTransport.hostFor(emulator = false))
        for (host in listOf("127.0.0.1", "::1")) BridgeRingTransport(CODE, host, 7777, emulator = false).close()
        BridgeRingTransport(CODE, "10.0.2.2", 7777, emulator = true).close()
        // 10.0.2.2 is somebody's LAN address on a real phone.
        assertThrows(IllegalArgumentException::class.java) { BridgeRingTransport(CODE, "10.0.2.2", 7777, emulator = false) }
        for (host in listOf("192.168.1.20", "10.0.0.2", "example.com", "localhost", "")) {
            assertThrows(IllegalArgumentException::class.java) { BridgeRingTransport(CODE, host, 7777, emulator = true) }
        }
        assertThrows(IllegalArgumentException::class.java) { BridgeRingTransport("", "127.0.0.1", 7777, emulator = false) }
    }

    @Test
    fun emulatorIsOnlyTheEmulatorsOwnHardware() {
        assertTrue(BridgeRingTransport.isEmulator(hardware = "ranchu", qemu = null))
        assertTrue(BridgeRingTransport.isEmulator(hardware = "goldfish", qemu = null))
        assertTrue(BridgeRingTransport.isEmulator(hardware = "qcom", qemu = "1"))
        assertFalse(BridgeRingTransport.isEmulator(hardware = "qcom", qemu = null))
        assertFalse(BridgeRingTransport.isEmulator(hardware = "qcom", qemu = "0"))
        // Fingerprint-style names on a real phone don't count.
        assertFalse(BridgeRingTransport.isEmulator(hardware = "generic_emulator_sdk_gphone", qemu = ""))
    }

    @Test
    fun proofsMatchTheBridgeToolsSums() {
        val nb = ByteArray(16) { it.toByte() }
        val nc = ByteArray(16) { (16 + it).toByte() }
        // Dashes and case don't matter.
        assertEquals(CLIENT_PROOF_HEX, hex(BridgeRingTransport.clientProof("abcde-fghjk", nb, nc)))
        assertEquals(BRIDGE_PROOF_HEX, hex(BridgeRingTransport.bridgeProof(CODE, nc, nb)))
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test
    fun anImpostorWithoutTheCodeIsRefused() = runBlocking {
        ServerSocket(0).use { server ->
            val transport = BridgeRingTransport(CODE, "127.0.0.1", server.localPort, emulator = false)
            val peer = async(kotlinx.coroutines.Dispatchers.IO) {
                server.accept().also { s ->
                    // A ring frame straight away instead of the challenge.
                    writeFrame(s, byteArrayOf(3, 0x06, 0x01))
                }
            }
            val failed = runCatching { transport.connect() }.exceptionOrNull()
            assertTrue(failed is java.io.IOException)
            assertFalse(transport.connected.value)
            peer.await().close()
        }
    }

    @Test
    fun aWrongCodeIsRefused() = runBlocking {
        ServerSocket(0).use { server ->
            var spent = 0
            val transport = BridgeRingTransport(CODE, "127.0.0.1", server.localPort, emulator = false, onProofSpent = { spent++ })
            val peer = async(kotlinx.coroutines.Dispatchers.IO) {
                server.accept().also { s -> answerHello(s, "ZZZZZ-ZZZZZ", appMustProve = false) }
            }
            assertTrue(runCatching { transport.connect() }.exceptionOrNull() is java.io.IOException)
            assertFalse(transport.connected.value)
            // The app's proof went to a peer that couldn't prove the code: that code must be retired.
            assertEquals(1, spent)
            peer.await().close()
        }
    }

    private fun readFrame(s: java.net.Socket): ByteArray {
        val din = DataInputStream(s.getInputStream())
        val len = din.readUnsignedShort()
        return ByteArray(len).also { din.readFully(it) }
    }

    private fun writeFrame(s: java.net.Socket, payload: ByteArray) {
        s.getOutputStream().apply {
            write(byteArrayOf((payload.size shr 8).toByte(), payload.size.toByte()))
            write(payload)
            flush()
        }
    }

    /** Plays the bridge's side of the handshake with [code]: challenge, check the app's proof, answer. */
    private fun answerHello(s: java.net.Socket, code: String, appMustProve: Boolean = true) {
        val nb = ByteArray(16) { (it * 7).toByte() }
        writeFrame(s, "OBH2".toByteArray(Charsets.US_ASCII) + nb)
        val answer = readFrame(s)
        assertEquals(48, answer.size)
        val nc = answer.copyOfRange(0, 16)
        if (appMustProve) assertArrayEquals(BridgeRingTransport.clientProof(code, nb, nc), answer.copyOfRange(16, 48))
        writeFrame(s, byteArrayOf(0xFE.toByte()) + BridgeRingTransport.bridgeProof(code, nc, nb))
    }

    @Test
    fun bridgeTagsMapToYcbtCharacteristics() {
        assertEquals(YcbtFamily.COMMAND, BridgeRingTransport.charForTag(1))
        assertEquals(YcbtFamily.DATA, BridgeRingTransport.charForTag(3))
        assertEquals(null, BridgeRingTransport.charForTag(2))
    }

    @Test
    fun bridgeFramesRoundTrip() = runBlocking {
        ServerSocket(0).use { server ->
            val transport = BridgeRingTransport(CODE, "127.0.0.1", server.localPort, emulator = false)
            val accepted = async(kotlinx.coroutines.Dispatchers.IO) { server.accept().also { answerHello(it, CODE) } }
            transport.connect()
            val peer = accepted.await()
            assertTrue(transport.connected.value)

            // Subscribe before writing (hot flow contract).
            val reply = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(5000) { transport.incoming.first() } }

            val cmd = byteArrayOf(0x02, 0x00, 0x08, 0x00, 0x47, 0x43, 0x11, 0x22)
            transport.write(cmd)
            val din = DataInputStream(peer.getInputStream())
            val len = din.readUnsignedShort()
            val got = ByteArray(len).also { din.readFully(it) }
            assertArrayEquals(cmd, got)

            val resp = byteArrayOf(0x06, 0x01, 0x06, 0x00, 0x0A, 0x0B)
            val payload = byteArrayOf(3) + resp
            peer.getOutputStream().apply {
                write(byteArrayOf((payload.size shr 8).toByte(), payload.size.toByte()))
                write(payload)
                flush()
            }
            assertArrayEquals(resp, reply.await())

            peer.close()
            withTimeout(5000) { transport.connected.filter { !it }.first() }
            assertFalse(transport.connected.value)
            transport.close()
        }
    }

    companion object {
        private const val CODE = "ABCDE-FGHJK"
        private const val CLIENT_PROOF_HEX = "30210dbbfbf547b9258455425626ded5a0c9f905def4a1c1247ea48b88d83b5a"
        private const val BRIDGE_PROOF_HEX = "472630edebd61522cc81dad33b0e8a4b42f1548e44035d9c23ef3190bea81470"
    }
}
