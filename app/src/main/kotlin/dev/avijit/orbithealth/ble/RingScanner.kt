package dev.avijit.orbithealth.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.util.SparseArray
import dev.avijit.orbithealth.ring.RingAdvertisement
import dev.avijit.orbithealth.ring.RingIdentity
import dev.avijit.orbithealth.ring.RingRegistry
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * A ring seen by the scan. [familyId], [modelName] and [confidence] come from the registry's best match; the family
 * is null for a ring no registered family supports, which is listed as "not supported yet" and never connected to.
 */
data class FoundRing(
    val name: String,
    val address: String,
    val rssi: Int,
    val familyId: String? = null,
    val modelName: String? = null,
    /** The match's confidence 0..100 (0 when unsupported); below [RingRegistry.AUTO_PICK_CONFIDENCE] it is never auto-picked. */
    val confidence: Int = 0,
) {
    val supported: Boolean get() = familyId != null
}

/**
 * Turns advertisements into [FoundRing]s: asks [registry] which family (if any) claims each one, keeps unsupported
 * devices only when they look like a ring, and re-emits an address only when its RSSI moved by [RingScanner.RSSI_DELTA_DBM].
 * Pure logic (no Android), one instance per scan.
 */
class RingScanFilter(private val registry: RingRegistry) {
    private val seen = HashMap<String, Int>() // address -> last emitted rssi

    fun accept(adv: RingAdvertisement): FoundRing? {
        val name = adv.name.orEmpty()
        val found = when (val id = registry.identify(adv)) {
            is RingIdentity.Supported ->
                FoundRing(name, adv.address, adv.rssi, id.family.id, id.match.modelName, id.match.confidence)
            RingIdentity.Unsupported ->
                if (registry.looksLikeRing(adv)) FoundRing(name, adv.address, adv.rssi) else return null
        }
        if (!RingScanner.shouldEmit(seen[adv.address], adv.rssi)) return null
        seen[adv.address] = adv.rssi
        return found
    }
}

/** Scans for rings of every family in [registry]. Caller must hold BLUETOOTH_SCAN (and Bluetooth must be on). */
class RingScanner(private val context: Context, private val registry: RingRegistry) {

    /**
     * Emits a [FoundRing] when an address is first seen, and again when its RSSI changes by at least
     * [RSSI_DELTA_DBM] dBm. Consumers key by address (later emissions carry the latest RSSI).
     */
    @SuppressLint("MissingPermission")
    fun scan(): Flow<FoundRing> = callbackFlow {
        val scanner = context.getSystemService(BluetoothManager::class.java)
            ?.adapter?.bluetoothLeScanner
        if (scanner == null) {
            close(IllegalStateException("Bluetooth LE scanner unavailable (Bluetooth off?)"))
            return@callbackFlow
        }
        val filter = RingScanFilter(registry)
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = handle(result)
            override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::handle)
            override fun onScanFailed(errorCode: Int) {
                close(IllegalStateException("BLE scan failed, error=$errorCode"))
            }

            private fun handle(result: ScanResult) {
                filter.accept(advertisementOf(result))?.let { trySend(it) }
            }
        }
        // Name prefixes cannot be filtered in hardware, so scan unfiltered and match here.
        scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
        awaitClose { scanner.stopScan(callback) }
    }

    @SuppressLint("MissingPermission")
    private fun advertisementOf(result: ScanResult): RingAdvertisement {
        val record = result.scanRecord
        return RingAdvertisement(
            name = record?.deviceName ?: result.device.name,
            address = result.device.address,
            serviceUuids = record?.serviceUuids.orEmpty().map { it.uuid.toString() },
            manufacturerData = record?.manufacturerSpecificData.toMap(),
            rssi = result.rssi,
        )
    }

    private fun SparseArray<ByteArray>?.toMap(): Map<Int, ByteArray> =
        if (this == null) emptyMap() else (0 until size()).associate { keyAt(it) to valueAt(it) }

    companion object {
        const val RSSI_DELTA_DBM = 3

        internal fun shouldEmit(lastEmittedRssi: Int?, rssi: Int): Boolean =
            lastEmittedRssi == null || kotlin.math.abs(rssi - lastEmittedRssi) >= RSSI_DELTA_DBM
    }
}
