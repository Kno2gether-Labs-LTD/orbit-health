package dev.avijit.orbithealth

import android.Manifest
import android.content.pm.PackageManager
import android.content.pm.ApplicationInfo
import android.os.Bundle
import dev.avijit.orbithealth.ui.sky.LocalSkyForceFallback
import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dev.avijit.orbithealth.security.BiometricUnlocker
import dev.avijit.orbithealth.security.LocalLockAuthenticator
import dev.avijit.orbithealth.security.LocalLockPrivacy
import dev.avijit.orbithealth.security.LockGate
import dev.avijit.orbithealth.security.LockPrivacy
import dev.avijit.orbithealth.security.LockScreen
import dev.avijit.orbithealth.sync.SyncService
import dev.avijit.orbithealth.ui.components.GrainOverlay
import dev.avijit.orbithealth.ui.components.LivingBackdrop
import dev.avijit.orbithealth.ui.nav.OrbitNavHost
import dev.avijit.orbithealth.ui.nav.Routes
import dev.avijit.orbithealth.ui.theme.OrbitTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** A FragmentActivity because BiometricPrompt (the app lock) needs one. */
class MainActivity : FragmentActivity() {
    /** App-open sync trigger: (re)start the sync service once a ring is paired and BT access granted. No prompts here. */
    override fun onStart() {
        super.onStart()
        val graph = (application as OrbitApp).graph
        lifecycleScope.launch {
            val paired = graph.settings.current().ringAddress != null
            val granted = ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
            if (paired && granted && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                SyncService.start(this@MainActivity)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The app is always dark (Living Skies): light status and navigation bar icons whatever the system theme.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        val graph = (application as OrbitApp).graph
        val lock = graph.appLock
        val unlocker = BiometricUnlocker(this, lock)
        lifecycleScope.launch {
            graph.settings.values.map { it.appLockEnabled }.distinctUntilChanged().collect(::applyRecentsPrivacy)
        }
        // Debuggable builds only: `am start ... --ez fallback true` forces the API 31–32 static sky fallback app-wide,
        // to check it on a newer device. Release builds are not debuggable, so the extra is ignored there.
        val forceFallback = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0 &&
            intent.getBooleanExtra("fallback", false)
        setContent {
            OrbitTheme {
             CompositionLocalProvider(LocalSkyForceFallback provides forceFallback) {
              // Film grain (3%) over everything the app draws; it never takes touches or semantics.
              Box(Modifier.fillMaxSize()) {
                // One base gradient behind every route (the Haze source for the bottom bar), so screens cross-fade
                // over a still background.
                LivingBackdrop {
                    // Read once: onboarding until a ring is saved, then Today. Nothing is drawn but the sky until known
                    // (and until the lock has read its settings, so the lock screen never flashes when it is off).
                    val start by produceState<String?>(null) {
                        lock.awaitReady()
                        value = Routes.start(graph.settings.current().ringAddress)
                    }
                    val locked by lock.locked.collectAsStateWithLifecycle()
                    val lockEnabled by remember { graph.settings.values.map { it.appLockEnabled } }
                        .collectAsStateWithLifecycle(initialValue = true)
                    val privacy = LockPrivacy(locked = locked, secureWindows = lockEnabled && Build.VERSION.SDK_INT < 33)
                    start?.let {
                        CompositionLocalProvider(LocalLockPrivacy provides privacy, LocalLockAuthenticator provides unlocker) {
                            LockGate(
                                locked = locked,
                                lockScreen = { LockScreen(unlocker, onBack = { moveTaskToBack(true) }) },
                            ) { OrbitNavHost(startDestination = it) }
                        }
                    }
                }
                GrainOverlay(Modifier.fillMaxSize())
              }
             }
            }
        }
    }

    /**
     * Keeps Orbit Health out of the recents thumbnail while the lock is on. API 33+ blanks just the thumbnail;
     * API 31–32 has only FLAG_SECURE, which also blocks ordinary screenshots (the Me caption says so).
     */
    private fun applyRecentsPrivacy(lockEnabled: Boolean) {
        if (Build.VERSION.SDK_INT >= 33) {
            setRecentsScreenshotEnabled(!lockEnabled)
        } else if (lockEnabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}
