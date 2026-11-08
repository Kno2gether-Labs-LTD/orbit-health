package dev.avijit.orbithealth.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.avijit.orbithealth.MainActivity
import dev.avijit.orbithealth.OrbitApp
import dev.avijit.orbithealth.R
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service (type connectedDevice) that keeps ring history flowing into the encrypted DB:
 * syncs on start, then every 15 min (or with backoff after failures, see [SyncEngine.nextDelay]).
 * A start while already running (e.g. app open) triggers an immediate sync.
 */
class SyncService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null
    private val graph get() = (application as OrbitApp).graph
    private val engine get() = graph.syncEngine

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForeground(NOTIFICATION_ID, notification(engine.state.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } catch (e: Exception) {
            // Missing BLUETOOTH_CONNECT grant or background-start restriction: nothing we can do here.
            Log.w(TAG, "cannot start foreground: $e")
            stopSelf()
            return START_NOT_STICKY
        }
        if (loop?.isActive == true) {
            scope.launch { engine.syncOnce() } // no-op if a sync is already running
        } else {
            loop = scope.launch {
                while (isActive) {
                    engine.syncOnce()
                    delay(engine.nextDelay())
                }
            }
            scope.launch {
                // Refresh on every state change and once a minute so "N min ago" stays true.
                engine.state.collectLatest { s ->
                    while (isActive) {
                        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(s))
                        delay(60_000)
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(s: SyncState): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Ring sync", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_logo_mono)
            .setContentTitle(statusText(s, Instant.now(), bridge = graph.transportKind == TransportKind.DEBUG_BRIDGE))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
    }

    companion object {
        private const val TAG = "OrbitSync"
        private const val CHANNEL_ID = "sync"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, SyncService::class.java))
        }

        /** The notification line; [bridge] (developer Mac bridge in use) puts "using Mac bridge" right after the name. */
        fun statusText(s: SyncState, now: Instant, bridge: Boolean = false): String =
            "Orbit Health · " + (if (bridge) "using Mac bridge · " else "") + when (s) {
            is SyncState.Synced -> "synced ${Duration.between(s.at, now).toMinutes().coerceAtLeast(0)} min ago"
            SyncState.Idle -> "waiting to sync"
            SyncState.Connecting -> "connecting to ring"
            SyncState.Syncing -> "syncing"
            SyncState.RingBusy -> "ring busy (vendor app connected)"
            is SyncState.Failed -> "sync failed, retrying"
        }
    }
}
