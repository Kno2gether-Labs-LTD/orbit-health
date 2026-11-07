package dev.avijit.orbithealth.ble

import android.content.Context
import dev.avijit.orbithealth.prefs.SettingsSnapshot
import dev.avijit.orbithealth.ring.RingRegistry
import dev.avijit.orbithealth.sync.RingConnection
import dev.avijit.orbithealth.sync.TransportKind

/** Release: BLE only. `useMacBridge` is ignored. */
object BuildTransportProvider : TransportProvider {
    override fun kindFor(settings: SettingsSnapshot) = TransportKind.BLE

    override suspend fun connect(context: Context, kind: TransportKind, settings: SettingsSnapshot, registry: RingRegistry): RingConnection {
        check(kind == TransportKind.BLE) { "Only BLE is available in release builds" }
        return connectBle(context, settings, registry)
    }
}
