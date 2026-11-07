package dev.avijit.orbithealth.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onStart

/**
 * Detects the paired ring's vendor app ([dev.avijit.orbithealth.ring.RingFamily.vendorAppPackages]); while one is
 * installed we run in COEXISTENCE mode (never clear the ring).
 *
 * [vendorPackages] is the paired family's set (every family's before pairing; see
 * [dev.avijit.orbithealth.ring.RingRegistry.vendorAppPackagesFor]). Each package must also be listed under
 * `<queries>` in AndroidManifest.xml, or Android 11+ reports it as not installed.
 */
class CoexistenceDetector(
    context: Context,
    private val vendorPackages: StateFlow<Set<String>>,
    private val isInstalled: (String) -> Boolean = packageCheck(context.applicationContext),
) {
    private val app = context.applicationContext

    fun vendorAppInstalled(): Boolean = installedVendorPackage() != null

    /** The first installed vendor app of the paired family (e.g. for an uninstall prompt), or null. */
    fun installedVendorPackage(): String? = vendorPackages.value.firstOrNull(isInstalled)

    /** Current state first, then every change (PACKAGE_ADDED / PACKAGE_REMOVED, or a different paired family). */
    val changes: Flow<Boolean> = combine(vendorPackages, packageEvents().onStart { emit(Unit) }) { _, _ ->
        vendorAppInstalled()
    }.distinctUntilChanged()

    private fun packageEvents(): Flow<Unit> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.data?.schemeSpecificPart in vendorPackages.value) trySend(Unit)
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        awaitClose { app.unregisterReceiver(receiver) }
    }

    companion object {
        private fun packageCheck(app: Context): (String) -> Boolean = { pkg ->
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    app.packageManager.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    app.packageManager.getPackageInfo(pkg, 0)
                }
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }
        }
    }
}
