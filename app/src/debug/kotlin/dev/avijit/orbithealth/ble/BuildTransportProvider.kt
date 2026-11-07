package dev.avijit.orbithealth.ble

import android.content.Context
import dev.avijit.orbithealth.prefs.SettingsSnapshot
import dev.avijit.orbithealth.ring.RingRegistry
import dev.avijit.orbithealth.sync.RingConnection
import dev.avijit.orbithealth.sync.TransportKind
import dev.avijit.orbithealth.ycbt.YcbtFamily

/**
 * Debug: BLE, or the Mac BLE bridge when `useMacBridge` is set. The bridge relays the TK5 (tools/ble_bridge.py), so it
 * always speaks YCBT, whatever ring is stored. The bridge link never allows clears.
 */
object BuildTransportProvider : TransportProvider {
    override fun kindFor(settings: SettingsSnapshot) =
        if (settings.useMacBridge) TransportKind.DEBUG_BRIDGE else TransportKind.BLE

    override suspend fun connect(context: Context, kind: TransportKind, settings: SettingsSnapshot, registry: RingRegistry): RingConnection =
        when (kind) {
            TransportKind.BLE -> connectBle(context, settings, registry)
            TransportKind.DEBUG_BRIDGE -> {
                val transport = BridgeRingTransport("10.0.2.2", 7777)
                transport.connect()
                RingConnection(YcbtFamily.createDriver(transport), transport.connected, allowsClear = false, onClose = transport::close)
            }
        }
}
