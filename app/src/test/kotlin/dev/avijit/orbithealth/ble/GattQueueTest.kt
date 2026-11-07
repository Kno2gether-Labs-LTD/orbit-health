package dev.avijit.orbithealth.ble

import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GattQueueTest {
    private class FakeOps : GattOps {
        val started = mutableListOf<String>()
        override fun writeCharacteristic(target: GattTarget, value: ByteArray, withResponse: Boolean): Boolean {
            started += "w:${value.joinToString("") { "%02x".format(it) }}"; return true
        }
        override fun writeDescriptor(target: GattTarget): Boolean {
            started += "d:${target.characteristic}"; return true
        }
        override fun readCharacteristic(target: GattTarget): Boolean {
            started += "r:${target.characteristic}"; return true
        }
    }

    private val svc = UUID.fromString("be940000-7333-be46-b7ae-689e71722bd5")
    private val c1 = GattTarget(svc, UUID.fromString("be940001-7333-be46-b7ae-689e71722bd5"))
    private val c3 = GattTarget(svc, UUID.fromString("be940003-7333-be46-b7ae-689e71722bd5"))

    @Test
    fun writesAreSerialisedUntilCallback() = runTest {
        val ops = FakeOps()
        val q = GattQueue(ops)
        val a = async { q.write(c1, byteArrayOf(1)) }
        val b = async { q.write(c1, byteArrayOf(2)) }
        val d = async { q.enableIndication(c3) }
        runCurrent()
        assertEquals(listOf("w:01"), ops.started)
        q.onWriteComplete(0); runCurrent()
        assertTrue(a.isCompleted)
        assertEquals(listOf("w:01", "w:02"), ops.started)
        assertFalse(b.isCompleted)
        q.onWriteComplete(0); runCurrent()
        assertEquals(3, ops.started.size)
        q.onDescriptorComplete(0); runCurrent()
        assertTrue(b.isCompleted && d.isCompleted)
    }

    @Test
    fun operationTimesOut() = runTest {
        val q = GattQueue(FakeOps())
        val a = async { runCatching { q.write(c1, byteArrayOf(1)) } }
        runCurrent()
        advanceTimeBy(4_999); runCurrent()
        assertFalse(a.isCompleted)
        advanceTimeBy(2); runCurrent()
        assertTrue(a.await().exceptionOrNull() is IOException)
    }

    @Test
    fun failAllFailsPendingAndQueued() = runTest {
        val q = GattQueue(FakeOps())
        val a = async { runCatching { q.write(c1, byteArrayOf(1)) } }
        val b = async { runCatching { q.write(c1, byteArrayOf(2)) } }
        runCurrent()
        q.failAll(IOException("disconnected")); runCurrent()
        assertTrue(a.await().exceptionOrNull() is IOException)
        assertTrue(b.await().exceptionOrNull() is IOException)
    }

    @Test
    fun nonZeroStatusFails() = runTest {
        val q = GattQueue(FakeOps())
        val a = async { runCatching { q.write(c1, byteArrayOf(1)) } }
        runCurrent()
        q.onWriteComplete(133); runCurrent()
        assertTrue(a.await().exceptionOrNull() is IOException)
        // queue still usable afterwards
        val b = async { q.write(c1, byteArrayOf(2)) }
        runCurrent(); q.onWriteComplete(0); b.await()
    }

    @Test
    fun startRefusedFails() = runTest {
        val ops = object : GattOps {
            override fun writeCharacteristic(target: GattTarget, value: ByteArray, withResponse: Boolean) = false
            override fun writeDescriptor(target: GattTarget) = false
            override fun readCharacteristic(target: GattTarget) = false
        }
        try { GattQueue(ops).write(c1, byteArrayOf(1)); fail() } catch (_: IOException) {}
    }

    @Test
    fun readReturnsTheCallbackValue() = runTest {
        val ops = FakeOps()
        val q = GattQueue(ops)
        val r = async { q.read(c3) }
        runCurrent()
        assertEquals(listOf("r:${c3.characteristic}"), ops.started)
        q.onReadComplete(0, byteArrayOf(54)); runCurrent()
        assertEquals(listOf<Byte>(54), r.await().toList())
    }

    @Test
    fun readWithNullValueFails() = runTest {
        val q = GattQueue(FakeOps())
        val r = async { runCatching { q.read(c3) } }
        runCurrent()
        q.onReadComplete(0, null); runCurrent()
        assertTrue(r.await().exceptionOrNull() is IOException)
        // The queue is free again for the next op.
        val w = async { q.write(c1, byteArrayOf(1)) }
        runCurrent(); q.onWriteComplete(0); w.await()
    }
}
