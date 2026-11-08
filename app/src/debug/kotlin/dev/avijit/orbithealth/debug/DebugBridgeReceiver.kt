package dev.avijit.orbithealth.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.avijit.orbithealth.OrbitApp
import dev.avijit.orbithealth.ble.BridgeRingTransport
import dev.avijit.orbithealth.devtools.SampleDataSeeder
import dev.avijit.orbithealth.ring.Sex
import dev.avijit.orbithealth.ring.UserProfile
import dev.avijit.orbithealth.sync.SyncService
import dev.avijit.orbithealth.ycbt.YcbtFamily
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Debug-only, explicit broadcasts (`am broadcast -n dev.avijit.orbithealth/.debug.DebugBridgeReceiver -a …`):
 * - DEBUG_HANDSHAKE: handshake via the Mac bridge.
 * - DEBUG_SYNC: select the Mac bridge transport and run one [dev.avijit.orbithealth.sync.SyncEngine.syncOnce],
 *   then restore the previous `useMacBridge`. The bridge forces COEXISTENCE and its link disallows
 *   clears, so this never deletes ring history.
 * - DEBUG_USE_BRIDGE (`--ez on true|false`): persistently select the bridge (e.g. for the service).
 * - The bridge actions take `--es code XXXXXX`, the code `tools/ble_bridge.py --code` was started with; without it the
 *   current code is kept, or a new one is made and logged.
 * - DEBUG_COUNTS: log row counts from the encrypted DB.
 * - DEBUG_START_SERVICE: start [SyncService] (the app must be in the foreground for an FGS start).
 * - DEBUG_SEED: write 35 days of sample data, a fake ring address and a recent sync time ([SampleDataSeeder]) so the app
 *   opens on Today with realistic numbers. Emulator/screenshot use only. `--ez lock false` also turns the app lock
 *   off, so screenshots are not blanked by the (secure) biometric prompt. `--ez data false` only marks a ring as
 *   paired (no samples), so a bridge DEBUG_SYNC shows the real ring's data alone.
 * - DEBUG_RESET: wipe every table of the database and all settings (back to onboarding after a restart).
 * - DEBUG_PAIR (`--es uri 'orbit://pair?...'`, optional `--es scopes heart,sleep`): pair with an Orbit Server as if its
 *   QR code had been scanned (end-to-end tests). Quote the link for `adb shell`: it contains `&`. Logs the server name
 *   and scopes only, never the link or the token.
 */
class DebugBridgeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_HANDSHAKE -> handshake(context, intent)
            ACTION_SYNC -> { code(graph(context), intent); sync(context) }
            ACTION_COUNTS -> counts(context)
            ACTION_START_SERVICE -> SyncService.start(context)
            ACTION_USE_BRIDGE -> {
                if (intent.getBooleanExtra("on", false)) code(graph(context), intent) else graph(context).bridgeCode.value = null
                useBridge(context, intent.getBooleanExtra("on", false))
            }
            ACTION_SEED -> seed(context, intent.takeIf { it.hasExtra("lock") }?.getBooleanExtra("lock", true), intent.getBooleanExtra("data", true))
            ACTION_RESET -> reset(context)
            ACTION_PAIR -> pair(context, intent.getStringExtra("uri"), intent.getStringExtra("scopes"))
        }
    }

    private fun graph(context: Context) = (context.applicationContext as OrbitApp).graph

    /** The bridge code: `--es code XXXXXX` (must match `ble_bridge.py --code`), else the current one, else a new one (logged). */
    private fun code(graph: dev.avijit.orbithealth.AppGraph, intent: Intent): String {
        intent.getStringExtra("code")?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }?.let { graph.bridgeCode.value = it }
        return graph.bridgeCode.value ?: dev.avijit.orbithealth.ble.BridgeCode.generate().also {
            graph.bridgeCode.value = it
            Log.i(TAG, "bridge code $it: run ble_bridge.py --code $it")
        }
    }

    private fun handshake(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            val transport = BridgeRingTransport(code(graph(context), intent))
            try {
                withTimeout(30_000) {
                    transport.connect()
                    val (info, caps) = YcbtFamily.createDriver(transport).handshake(UserProfile(175, 75, Sex.UNSPECIFIED, 35))
                    Log.i(TAG, "firmware=${info.firmware} battery=${info.batteryPct} caps=$caps")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "handshake failed: $t", t)
            } finally {
                transport.close()
                pending.finish()
            }
        }
    }

    private fun sync(context: Context) {
        val graph = (context.applicationContext as OrbitApp).graph
        // A full history transfer can outlast a broadcast's time budget: run it in the app scope.
        graph.appScope.launch(Dispatchers.IO) {
            val previous = graph.settings.current().useMacBridge
            try {
                graph.settings.setUseMacBridge(true)
                graph.syncEngine.disconnect() // drop any held BLE link so the next connect uses the bridge
                Log.i(TAG, "DEBUG_SYNC start")
                val ran = graph.syncEngine.syncOnce()
                Log.i(TAG, "DEBUG_SYNC ran=$ran state=${graph.syncEngine.state.value} transport=${graph.transportKind} mode=${graph.mode()}")
                logCounts(graph.repository.counts().toString())
            } catch (t: Throwable) {
                Log.e(TAG, "DEBUG_SYNC failed: $t", t)
            } finally {
                withContext(NonCancellable) { graph.settings.setUseMacBridge(previous) }
                Log.i(TAG, "DEBUG_SYNC restored useMacBridge=$previous")
            }
        }
    }

    private fun useBridge(context: Context, on: Boolean) {
        val graph = (context.applicationContext as OrbitApp).graph
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                graph.settings.setUseMacBridge(on)
                graph.syncEngine.disconnect()
                Log.i(TAG, "useMacBridge=$on")
            } finally {
                pending.finish()
            }
        }
    }

    private fun counts(context: Context) {
        val graph = (context.applicationContext as OrbitApp).graph
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                logCounts(graph.repository.counts().toString())
            } catch (t: Throwable) {
                Log.e(TAG, "DEBUG_COUNTS failed: $t", t)
            } finally {
                pending.finish()
            }
        }
    }

    private fun seed(context: Context, lock: Boolean?, data: Boolean) {
        val graph = (context.applicationContext as OrbitApp).graph
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // `--ez data false`: only mark a ring as paired (past onboarding) with no sample data, so a bridge sync
                // fills Today with the real ring's readings alone.
                val n = if (data) SampleDataSeeder.seed(graph) else 0.also { graph.settings.setPairedRing(SampleDataSeeder.FAKE_RING) }
                if (lock != null) graph.settings.setAppLockEnabled(lock)
                Log.i(TAG, "DEBUG_SEED wrote $n samples + 1 sleep session; ring=${SampleDataSeeder.FAKE_RING_ADDRESS}")
                logCounts(graph.repository.counts().toString())
            } catch (t: Throwable) {
                Log.e(TAG, "DEBUG_SEED failed: $t", t)
            } finally {
                pending.finish()
            }
        }
    }

    private fun reset(context: Context) {
        val graph = (context.applicationContext as OrbitApp).graph
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                graph.syncEngine.disconnect()
                graph.database.clearAllTables()
                graph.settings.clearAll()
                Log.i(TAG, "DEBUG_RESET database and settings wiped")
            } catch (t: Throwable) {
                Log.e(TAG, "DEBUG_RESET failed: $t", t)
            } finally {
                pending.finish()
            }
        }
    }

    private fun pair(context: Context, uri: String?, scopes: String?) {
        val graph = (context.applicationContext as OrbitApp).graph
        if (uri.isNullOrBlank()) {
            Log.e(TAG, "DEBUG_PAIR needs --es uri 'orbit://pair?...'")
            return
        }
        val wanted = scopes?.split(',')?.mapNotNull { dev.avijit.orbithealth.sync.Scope.fromWire(it.trim()) }?.toSet()
        // Pairing then pushes in the background: run it in the app scope, beyond the broadcast's time budget.
        graph.appScope.launch(Dispatchers.IO) {
            try {
                val info = graph.server.pair(uri, wanted)
                Log.i(TAG, "DEBUG_PAIR paired with '${info.name}' scopes=${info.scopes.map { it.wire }.sorted()}")
            } catch (t: Throwable) {
                Log.e(TAG, "DEBUG_PAIR failed: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }

    private fun logCounts(c: String) = Log.i(TAG, "counts $c")

    companion object {
        const val ACTION_HANDSHAKE = "dev.avijit.orbithealth.DEBUG_HANDSHAKE"
        const val ACTION_SYNC = "dev.avijit.orbithealth.DEBUG_SYNC"
        const val ACTION_COUNTS = "dev.avijit.orbithealth.DEBUG_COUNTS"
        const val ACTION_START_SERVICE = "dev.avijit.orbithealth.DEBUG_START_SERVICE"
        const val ACTION_USE_BRIDGE = "dev.avijit.orbithealth.DEBUG_USE_BRIDGE"
        const val ACTION_SEED = "dev.avijit.orbithealth.DEBUG_SEED"
        const val ACTION_RESET = "dev.avijit.orbithealth.DEBUG_RESET"
        const val ACTION_PAIR = "dev.avijit.orbithealth.DEBUG_PAIR"
        private const val TAG = "OrbitBridge"
    }
}
