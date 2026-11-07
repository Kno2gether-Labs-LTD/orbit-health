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
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeRingTransportTest {
    @Test
    fun bridgeTagsMapToYcbtCharacteristics() {
        assertEquals(YcbtFamily.COMMAND, BridgeRingTransport.charForTag(1))
        assertEquals(YcbtFamily.DATA, BridgeRingTransport.charForTag(3))
        assertEquals(null, BridgeRingTransport.charForTag(2))
    }

    @Test
    fun bridgeFramesRoundTrip() = runBlocking {
        ServerSocket(0).use { server ->
            val transport = BridgeRingTransport("127.0.0.1", server.localPort)
            val accepted = async(kotlinx.coroutines.Dispatchers.IO) { server.accept() }
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
}
