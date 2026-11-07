package dev.avijit.orbithealth.sync

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import dev.avijit.orbithealth.OrbitApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Restarts ring sync after a reboot or an app update, so history keeps flowing without the user
 * opening the app. Starts [SyncService] only when a ring is paired and BLUETOOTH_CONNECT is granted
 * (the connectedDevice foreground service type needs it); otherwise does nothing and never prompts.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in ACTIONS) return
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val paired = (app as OrbitApp).graph.settings.current().ringAddress != null
                val granted = ContextCompat.checkSelfPermission(app, Manifest.permission.BLUETOOTH_CONNECT) ==
                    PackageManager.PERMISSION_GRANTED
                if (shouldStartSync(intent.action, paired, granted)) SyncService.start(app)
            } catch (e: Exception) {
                Log.w("OrbitBoot", "could not restart sync: $e")
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        val ACTIONS = setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)

        /** Pure decision: restart sync only for our actions, with a paired ring and Bluetooth access. */
        fun shouldStartSync(action: String?, paired: Boolean, bluetoothGranted: Boolean): Boolean =
            action in ACTIONS && paired && bluetoothGranted
    }
}
