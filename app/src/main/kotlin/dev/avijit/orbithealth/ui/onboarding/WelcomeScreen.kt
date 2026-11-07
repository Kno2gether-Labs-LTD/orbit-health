package dev.avijit.orbithealth.ui.onboarding

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.avijit.orbithealth.ui.components.WarmButton
import dev.avijit.orbithealth.ui.components.livingEnter

/** Bluetooth runtime permissions onboarding needs; notifications are asked alongside but optional. */
internal object OnboardingPermissions {
    val bluetooth = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    val all: Array<String> =
        if (Build.VERSION.SDK_INT >= 33) bluetooth + Manifest.permission.POST_NOTIFICATIONS else bluetooth

    fun bluetoothGranted(context: Context) =
        bluetooth.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    fun bluetoothOn(context: Context): Boolean =
        context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true

    fun appSettingsIntent(context: Context) =
        Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

private enum class Blocker { None, PermissionDenied, BluetoothOff }

/**
 * Living Skies screen-01 without the ring card (no ring is known yet; its space is kept so the button does not jump
 * when Pair shows the card), the sun resting on the horizon. "Connect my ring" asks for Bluetooth (and notification) access, turns Bluetooth
 * on if needed, then opens Pair. A refusal shows a calm card with a way forward instead of a dead end.
 */
@Composable
fun WelcomeScreen(bridgeMode: Boolean, onReady: () -> Unit) {
    val context = LocalContext.current
    var blocker by rememberSaveable { mutableStateOf(Blocker.None) }

    val enableBluetooth = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (OnboardingPermissions.bluetoothOn(context)) {
            blocker = Blocker.None
            onReady()
        } else {
            blocker = Blocker.BluetoothOff
        }
    }

    fun continueWithBluetooth() {
        when {
            bridgeMode || OnboardingPermissions.bluetoothOn(context) -> { blocker = Blocker.None; onReady() }
            else -> try {
                enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            } catch (e: ActivityNotFoundException) {
                blocker = Blocker.BluetoothOff
            } catch (e: SecurityException) {
                blocker = Blocker.PermissionDenied
            }
        }
    }

    val requestPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (OnboardingPermissions.bluetoothGranted(context)) continueWithBluetooth() else blocker = Blocker.PermissionDenied
    }

    fun start() {
        if (OnboardingPermissions.bluetoothGranted(context)) continueWithBluetooth()
        else requestPermissions.launch(OnboardingPermissions.all)
    }

    // Back from system settings with access granted (or Bluetooth on): drop the explanation.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (blocker == Blocker.PermissionDenied && OnboardingPermissions.bluetoothGranted(context)) blocker = Blocker.None
        if (blocker == Blocker.BluetoothOff && OnboardingPermissions.bluetoothOn(context)) blocker = Blocker.None
    }

    OnboardingFrame(
        sun = Sunrise.WAITING,
        card = {
            AnimatedContent(blocker, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "blocker") { b ->
                when (b) {
                    Blocker.None -> RingCardPlaceholder()
                    Blocker.PermissionDenied -> ExplanationCard(
                        title = "Bluetooth access needed",
                        body = "Orbit Health uses Bluetooth only to talk to your ring. It never uses it to find your location, " +
                            "and nothing leaves this phone.",
                        first = "Try again" to { start() },
                        second = "Open settings" to {
                            runCatching { context.startActivity(OnboardingPermissions.appSettingsIntent(context)) }
                        },
                    )
                    Blocker.BluetoothOff -> ExplanationCard(
                        title = "Bluetooth is off",
                        body = "Turn Bluetooth on so Orbit Health can find your ring nearby.",
                        first = "Turn on Bluetooth" to { continueWithBluetooth() },
                        second = "Open settings" to {
                            runCatching {
                                context.startActivity(Intent(AndroidSettings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                        },
                    )
                }
            }
        },
        bottom = {
            WarmButton("Connect my ring", onClick = ::start, modifier = Modifier.fillMaxWidth().livingEnter(180))
            Footer()
        },
    )
}

/**
 * Keeps the ring card's space (the 40dp glyph row in its 16dp padding) so Welcome and Pair line up and the button
 * sits where the mockup puts it; invisible and silent to screen readers.
 */
@Composable
private fun RingCardPlaceholder() {
    Spacer(Modifier.fillMaxWidth().height(RingCardHeight).clearAndSetSemantics { })
}

/** The found-ring card: 16 + 41 (meta over name) + 16, plus its 1dp border. */
internal val RingCardHeight = 75.dp
