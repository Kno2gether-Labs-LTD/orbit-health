package dev.avijit.orbithealth.ble

import dev.avijit.orbithealth.ring.GattChar
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** A characteristic on a GATT server: its service and its own UUID. */
data class GattTarget(val service: UUID, val characteristic: UUID) {
    companion object {
        fun of(c: GattChar) = GattTarget(UUID.fromString(c.service), UUID.fromString(c.characteristic))
    }
}

/** The tiny slice of BluetoothGatt that [GattQueue] needs. Each call returns true if the stack accepted the op. */
interface GattOps {
    /** Writes [value] to [target] with response ([withResponse]) or without. */
    fun writeCharacteristic(target: GattTarget, value: ByteArray, withResponse: Boolean): Boolean
    /** Subscribes to [target]: writes its CCCD (indications if offered, else notifications). */
    fun writeDescriptor(target: GattTarget): Boolean
    /** Starts a read of [target]; the value arrives through [GattQueue.onReadComplete]. */
    fun readCharacteristic(target: GattTarget): Boolean
}

/**
 * Android allows one outstanding GATT operation. This queue starts the next op only after the
 * previous op's callback ([onWriteComplete]/[onDescriptorComplete]/[onReadComplete]), or after [timeoutMs].
 */
class GattQueue(private val ops: GattOps, private val timeoutMs: Long = 5_000) {
    private class Completion(val status: Int, val value: ByteArray?)

    private val mutex = Mutex()
    @Volatile private var current: CompletableDeferred<Completion>? = null
    @Volatile private var failure: IOException? = null

    suspend fun write(target: GattTarget, value: ByteArray, withResponse: Boolean = true) {
        run("write") { ops.writeCharacteristic(target, value, withResponse) }
    }

    suspend fun enableIndication(target: GattTarget) {
        run("descriptor write") { ops.writeDescriptor(target) }
    }

    /** Reads [target]; throws [IOException] if the read fails or its callback carries no value. */
    suspend fun read(target: GattTarget): ByteArray =
        run("read") { ops.readCharacteristic(target) }.value ?: throw IOException("GATT read returned no value")

    fun onWriteComplete(status: Int) { current?.complete(Completion(status, null)) }
    fun onDescriptorComplete(status: Int) { current?.complete(Completion(status, null)) }
    fun onReadComplete(status: Int, value: ByteArray?) { current?.complete(Completion(status, value?.copyOf())) }

    /** Fails the in-flight op and every queued/future op (used on disconnect). */
    fun failAll(error: IOException) {
        failure = error
        current?.completeExceptionally(error)
    }

    private suspend fun run(what: String, start: () -> Boolean): Completion = mutex.withLock {
        failure?.let { throw it }
        val d = CompletableDeferred<Completion>()
        current = d
        try {
            if (!start()) throw IOException("GATT $what was not accepted")
            val done = withTimeoutOrNull(timeoutMs) { d.await() }
                ?: throw IOException("GATT $what timed out after ${timeoutMs}ms")
            if (done.status != 0) throw IOException("GATT $what failed, status=${done.status}")
            done
        } finally {
            current = null
        }
    }
}
