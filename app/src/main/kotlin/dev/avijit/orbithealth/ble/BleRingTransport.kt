package dev.avijit.orbithealth.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import dev.avijit.orbithealth.ring.GattChar
import dev.avijit.orbithealth.ring.GattProfile
import dev.avijit.orbithealth.ring.RingPacket
import dev.avijit.orbithealth.ring.RingTransport
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * GATT transport for any ring family: service, characteristics, write type and MTU come from the family's
 * [GattProfile]. The GATT queue, MTU request and disconnect handling are shared. Single use: [connect] once, then
 * [close]. Caller must hold BLUETOOTH_CONNECT (onboarding handles permissions).
 */
@SuppressLint("MissingPermission")
class BleRingTransport(
    private val context: Context,
    private val address: String,
    private val profile: GattProfile,
) : RingTransport {

    // Hot flow: replies emitted while write() is suspended reach earlier subscribers.
    private val _packets = MutableSharedFlow<RingPacket>(extraBufferCapacity = 256)
    override val packets: Flow<RingPacket> = _packets.asSharedFlow()

    private val _connected = MutableStateFlow(false)
    override val connected: StateFlow<Boolean> = _connected.asStateFlow()

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var closed = false

    private val ops: GattOps = object : GattOps {
        override fun writeCharacteristic(target: GattTarget, value: ByteArray, withResponse: Boolean): Boolean {
            val g = gatt ?: return false
            val ch = g.getService(target.service)?.getCharacteristic(target.characteristic) ?: return false
            val type = if (withResponse) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            return if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(ch, value, type) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run {
                    ch.writeType = type
                    ch.value = value
                    g.writeCharacteristic(ch)
                }
            }
        }

        override fun readCharacteristic(target: GattTarget): Boolean {
            val g = gatt ?: return false
            val ch = g.getService(target.service)?.getCharacteristic(target.characteristic) ?: return false
            return g.readCharacteristic(ch)
        }

        override fun writeDescriptor(target: GattTarget): Boolean {
            val g = gatt ?: return false
            val ch = g.getService(target.service)?.getCharacteristic(target.characteristic) ?: return false
            val cccd = ch.getDescriptor(CCCD) ?: return false
            g.setCharacteristicNotification(ch, true)
            val enable = cccdValueFor(ch.properties)
            return if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(cccd, enable) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run {
                    cccd.value = enable
                    g.writeDescriptor(cccd)
                }
            }
        }
    }
    private val queue: GattQueue = GattQueue(ops)
    private val link: GattProfileLink = GattProfileLink(profile, queue)

    private val linkUp = CompletableDeferred<Unit>()
    private val servicesFound = CompletableDeferred<Unit>()
    private val mtuDone = CompletableDeferred<Unit>()

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                linkUp.complete(Unit)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                handleDisconnect(IOException("GATT disconnected (status=$status, state=$newState)"))
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) servicesFound.complete(Unit)
            else servicesFound.completeExceptionally(IOException("Service discovery failed, status=$status"))
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) { mtuDone.complete(Unit) }

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) =
            queue.onWriteComplete(status)

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) =
            queue.onDescriptorComplete(status)

        // API 33+: the platform calls only this overload (no super call, so no double delivery).
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            _packets.tryEmit(RingPacket(charOf(ch), value.copyOf()))
        }

        // API 31-32: the platform calls only this deprecated overload.
        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) {
                @Suppress("DEPRECATION")
                ch.value?.let { _packets.tryEmit(RingPacket(charOf(ch), it.copyOf())) }
            }
        }

        // API 33+ read result.
        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray, status: Int) =
            queue.onReadComplete(status, value)

        // API 31-32 read result.
        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < 33) {
                @Suppress("DEPRECATION")
                queue.onReadComplete(status, ch.value)
            }
        }
    }

    suspend fun connect() {
        check(gatt == null && !closed) { "BleRingTransport is single-use" }
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: throw IOException("Bluetooth unavailable")
        val device: BluetoothDevice = adapter.getRemoteDevice(address)
        val g = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            ?: throw IOException("connectGatt returned null")
        gatt = g
        try {
            withTimeout(CONNECT_TIMEOUT_MS) { linkUp.await() }
            if (!g.discoverServices()) throw IOException("discoverServices not accepted")
            withTimeout(CONNECT_TIMEOUT_MS) { servicesFound.await() }
            if (g.requestMtu(profile.mtu)) withTimeoutOrNull(MTU_TIMEOUT_MS) { mtuDone.await() }
            link.subscribe()
            _connected.value = true
        } catch (t: Throwable) {
            close()
            throw mapConnectFailure(t)
        }
    }

    override suspend fun write(bytes: ByteArray) {
        if (gatt == null || closed) throw IOException("Not connected")
        link.send(bytes)
    }

    override suspend fun write(char: GattChar, bytes: ByteArray) {
        if (gatt == null || closed) throw IOException("Not connected")
        link.send(char, bytes)
    }

    override suspend fun read(char: GattChar): ByteArray {
        if (gatt == null || closed) throw IOException("Not connected")
        return link.read(char)
    }

    fun close() = handleDisconnect(IOException("Transport closed"))

    private fun handleDisconnect(error: IOException) {
        if (closed) return
        closed = true
        _connected.value = false
        queue.failAll(error)
        linkUp.completeExceptionally(error)
        servicesFound.completeExceptionally(error)
        gatt?.let { it.disconnect(); it.close() }
        gatt = null
    }

    companion object {
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val CONNECT_TIMEOUT_MS = 20_000L
        private const val MTU_TIMEOUT_MS = 3_000L
    }
}

/** The tag for a packet from [ch] (lower-case UUID strings, as in profiles). */
private fun charOf(ch: BluetoothGattCharacteristic) = GattChar(ch.service.uuid.toString(), ch.uuid.toString())

/**
 * The CCCD value that subscribes to a characteristic with [properties]: indications when it offers them (or offers
 * neither, as before), notifications when it offers only those.
 */
internal fun cccdValueFor(properties: Int): ByteArray {
    val indicate = properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
    val notify = properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
    return if (notify && !indicate) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
    else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
}

/**
 * Maps a failure during connect() to what the caller should see: timeouts become IOException,
 * other cancellations pass through unchanged (structured concurrency), everything else is wrapped.
 */
internal fun mapConnectFailure(t: Throwable): Throwable = when (t) {
    is TimeoutCancellationException -> IOException("BLE connect timed out: ${t.message}", t)
    is CancellationException -> t
    is IOException -> t
    else -> IOException("BLE connect failed: ${t.message}", t)
}
