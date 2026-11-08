package dev.avijit.orbithealth.ble

import android.content.Context
import dev.avijit.orbithealth.prefs.SettingsSnapshot
import dev.avijit.orbithealth.ring.GattProfile
import dev.avijit.orbithealth.ring.RingRegistry
import dev.avijit.orbithealth.ring.RingTransport
import dev.avijit.orbithealth.sync.RingConnection
import dev.avijit.orbithealth.sync.TransportKind
import java.io.IOException

/**
 * Picks and opens the ring transport: [AppTransportProvider] in the app (BLE, or the loopback-only Mac bridge when the
 * developer switch is on); fakes in tests.
 */
interface TransportProvider {
    fun kindFor(settings: SettingsSnapshot): TransportKind
    /** Opens the paired ring in [settings]; its family (driver and GATT profile) is resolved through [registry]. */
    suspend fun connect(context: Context, kind: TransportKind, settings: SettingsSnapshot, registry: RingRegistry): RingConnection
}

/**
 * Opens a GATT link to the paired ring with its family's GATT profile and driver. Ring history may be cleared over
 * it in EXCLUSIVE mode, but only if the family's handshake declares [dev.avijit.orbithealth.ring.RingCapabilities.canClearHistory].
 */
suspend fun connectBle(context: Context, settings: SettingsSnapshot, registry: RingRegistry): RingConnection =
    openPairedRing(settings, registry) { address, profile ->
        val transport = BleRingTransport(context.applicationContext, address, profile)
        transport.connect()
        OpenedTransport(transport, transport::close)
    }

/** A connected transport and how to close it. */
class OpenedTransport(val transport: RingTransport, val close: () -> Unit)

/**
 * Resolves the paired ring's family through [registry] and opens it: [open] gets the address and the family's
 * GATT profile, and the family builds the driver. Throws IOException when nothing is paired or this build lacks the
 * family.
 */
suspend fun openPairedRing(
    settings: SettingsSnapshot,
    registry: RingRegistry,
    open: suspend (address: String, profile: GattProfile) -> OpenedTransport,
): RingConnection {
    val ring = settings.pairedRing ?: throw IOException("No ring paired")
    val family = registry.family(ring.familyId)
        ?: throw IOException("This build doesn't support the paired ring (family '${ring.familyId}')")
    val opened = open(ring.address, family.gattProfile)
    return RingConnection(
        family.createDriver(opened.transport), opened.transport.connected, allowsClear = true, ringId = ring.address,
        onClose = opened.close,
    )
}
