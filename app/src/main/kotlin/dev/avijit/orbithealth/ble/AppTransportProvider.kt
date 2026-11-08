package dev.avijit.orbithealth.ble

import android.content.Context
import dev.avijit.orbithealth.prefs.SettingsSnapshot
import dev.avijit.orbithealth.ring.RingRegistry
import dev.avijit.orbithealth.sync.RingConnection
import dev.avijit.orbithealth.sync.TransportKind
import dev.avijit.orbithealth.ycbt.YcbtFamily
import java.io.IOException
import java.security.SecureRandom

/**
 * BLE, or the Mac BLE bridge while the shake-revealed `useMacBridge` switch is on AND this process holds a bridge code
 * ([bridgeCode], made when the switch was turned on and kept in memory only). After a restart there is no code, so a
 * stored switch alone never routes the ring through the bridge. The bridge relays the TK5 (tools/ble_bridge.py), so it
 * always speaks YCBT, whatever ring is stored, and its link never allows clears. See [BridgeRingTransport] for the
 * loopback-only rule and the code handshake.
 */
class AppTransportProvider(private val retireCode: (String) -> Unit = {}, private val bridgeCode: () -> String?) : TransportProvider {
    override fun kindFor(settings: SettingsSnapshot) =
        if (settings.useMacBridge && bridgeCode() != null) TransportKind.DEBUG_BRIDGE else TransportKind.BLE

    override suspend fun connect(context: Context, kind: TransportKind, settings: SettingsSnapshot, registry: RingRegistry): RingConnection =
        when (kind) {
            TransportKind.BLE -> connectBle(context, settings, registry)
            TransportKind.DEBUG_BRIDGE -> {
                val code = bridgeCode() ?: throw IOException("Turn Use Mac bridge on again to get a code")
                val transport = BridgeRingTransport(code, onProofSpent = { retireCode(code) })
                transport.connect()
                RingConnection(YcbtFamily.createDriver(transport), transport.connected, allowsClear = false, onClose = transport::close)
            }
        }
}

/**
 * The code that pairs the app with `ble_bridge.py --code` for one session: 10 characters (50 bits) of a base32 alphabet
 * without 0/O and 1/I, shown in two groups ("ABCDE-FGHJK") for typing. Made fresh each time the switch is turned on.
 */
object BridgeCode {
    private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    const val LENGTH = 10

    fun generate(random: java.util.Random = SecureRandom()): String =
        (1..LENGTH).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("").chunked(LENGTH / 2).joinToString("-")
}
