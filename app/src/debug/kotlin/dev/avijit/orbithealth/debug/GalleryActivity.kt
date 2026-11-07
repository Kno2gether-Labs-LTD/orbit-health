package dev.avijit.orbithealth.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import dev.avijit.orbithealth.ui.components.GrainOverlay
import dev.avijit.orbithealth.ui.components.LivingBackdrop
import dev.avijit.orbithealth.ui.components.LivingGallery
import dev.avijit.orbithealth.ui.components.LivingGalleryFirstPage
import dev.avijit.orbithealth.ui.components.LivingSkySolo
import dev.avijit.orbithealth.ui.sky.SkyKind
import androidx.compose.ui.unit.dp
import dev.avijit.orbithealth.security.LockContent
import dev.avijit.orbithealth.security.LockStatus
import dev.avijit.orbithealth.ui.sky.LocalSkyForceFallback
import dev.avijit.orbithealth.ui.theme.OrbitTheme

/**
 * Debug-only component gallery for screenshots:
 * `adb shell am start -n dev.avijit.orbithealth/.debug.GalleryActivity [--ei page N]` (N = 6..8 Living Skies: Today
 * hero, every sky, kit), `--es onboarding <screen>` for the onboarding screens over sample data (see
 * [OnboardingPreview]), or `--es lock prompting|lockedout|failed|noscreenlock` for the lock screen (this activity has
 * no FLAG_SECURE, so it can be screenshotted). `--ez fallback true` forces the API 31–32 static sky fallback;
 * `--es sky NIGHT --ei h 392 [--ef param 73]` shows one sky alone at a mockup size for compare crops.
 */
class GalleryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Light bar icons, as the app (always dark).
        enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        val page = intent.getIntExtra("page", -1).takeIf { it >= 0 }
        val onboarding = intent.getStringExtra("onboarding")
        val lock = intent.getStringExtra("lock")
        val fallback = intent.getBooleanExtra("fallback", false)
        val sky = intent.getStringExtra("sky")
        val skyHeight = intent.getIntExtra("h", 392)
        val skyParam = intent.getFloatExtra("param", 0f)
        setContent {
            OrbitTheme {
                CompositionLocalProvider(LocalSkyForceFallback provides fallback) {
                    Box(Modifier.fillMaxSize()) {
                        if (sky != null) {
                            LivingBackdrop { LivingSkySolo(SkyKind.valueOf(sky), skyHeight.dp, skyParam) }
                        } else if (lock != null) {
                            val status = LockStatus.entries.firstOrNull { it.name.equals(lock, ignoreCase = true) } ?: LockStatus.Prompting
                            LockContent(status, onUnlock = {}, onOpenSecuritySettings = {}, onContinueWithoutLock = {})
                        } else if (onboarding != null) {
                            LivingBackdrop { OnboardingPreview(onboarding) }
                        } else {
                            LivingBackdrop { LivingGallery(((page ?: LivingGalleryFirstPage) - LivingGalleryFirstPage).coerceAtLeast(0)) }
                        }
                        GrainOverlay(Modifier.fillMaxSize())
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        recreate()
    }
}
