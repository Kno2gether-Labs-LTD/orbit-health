package dev.avijit.orbithealth.ui.onboarding

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.avijit.orbithealth.ui.components.WarmButton
import dev.avijit.orbithealth.ui.components.livingEnter
import dev.avijit.orbithealth.ui.theme.OrbitPalette
import dev.avijit.orbithealth.ui.theme.OrbitTypography
import kotlinx.coroutines.delay

/**
 * Living Skies screen-01 with the ring card: the found ring(s), strongest first; tapping one connects and shows its
 * battery. "Connect my ring" continues with that ring (or connects the strongest) and opens Profile. The sun rises
 * as the ring is found and connected.
 */
@Composable
fun PairScreen(vm: OnboardingViewModel, bridgeMode: Boolean, onProfile: () -> Unit, onBack: () -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var bluetoothOff by remember { mutableStateOf(!bridgeMode && !OnboardingPermissions.bluetoothOn(context)) }

    val enableBluetooth = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        bluetoothOff = !OnboardingPermissions.bluetoothOn(context)
        if (!bluetoothOff) vm.startScan()
    }

    // Back to Welcome: let go of the ring so the vendor app (or a later attempt) can connect.
    BackHandler {
        vm.releaseRing()
        onBack()
    }

    // Scan only while this screen is shown (and Bluetooth is usable).
    DisposableEffect(bluetoothOff) {
        if (!bluetoothOff) vm.startScan()
        onDispose { vm.stopScan() }
    }
    LaunchedEffect(s.proceedToProfile) {
        if (s.proceedToProfile) {
            vm.onProceededToProfile()
            onProfile()
        }
    }
    // After a while with nothing found, say what usually helps.
    var slow by remember { mutableStateOf(false) }
    LaunchedEffect(s.rings.isEmpty(), s.scanning) {
        slow = false
        if (s.rings.isEmpty() && s.scanning) {
            delay(15_000)
            slow = true
        }
    }

    // The sun rises while the ring is looked for, found and connected (tokens §7 First light).
    val sunTarget = when {
        s.connected != null -> Sunrise.CONNECTED
        s.rings.isNotEmpty() -> Sunrise.FOUND
        s.scanning -> Sunrise.SCANNING
        else -> Sunrise.WAITING
    }
    val sun by animateFloatAsState(sunTarget, tween(2400, easing = FastOutSlowInEasing), label = "sunrise")

    OnboardingFrame(
        sun = sun,
        card = {
            when {
                bluetoothOff -> ExplanationCard(
                    title = "Bluetooth is off",
                    body = "Turn Bluetooth on so Orbit Health can find your ring nearby.",
                    first = "Turn on Bluetooth" to {
                        runCatching { enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
                    },
                    second = "Try again" to {
                        bluetoothOff = !OnboardingPermissions.bluetoothOn(context)
                    },
                )
                s.scanError != null && s.rings.isEmpty() -> ExplanationCard(
                    title = "Couldn't look for rings",
                    body = s.scanError!!,
                    first = "Try again" to {
                        bluetoothOff = !OnboardingPermissions.bluetoothOn(context)
                        if (!bluetoothOff) vm.startScan()
                    },
                    second = "Open settings" to {
                        runCatching { context.startActivity(OnboardingPermissions.appSettingsIntent(context)) }
                    },
                )
                else -> RingCard(s, slow, onSelect = vm::select)
            }
        },
        bottom = {
            WarmButton(
                "Connect my ring",
                onClick = vm::connectAndContinue,
                modifier = Modifier.fillMaxWidth().livingEnter(180),
                enabled = (s.rings.any { it.autoPickable } || s.rings.any { it.supported && it.address == s.selected }) && !bluetoothOff,
                busy = s.connecting != null,
                busyText = "Connecting…",
            )
            Footer()
        },
    )
}

/**
 * The mockup's ring card on the sky. Looking: the pinging glyph with "Looking for your ring…". One ring: "Ring found
 * nearby" over its name, and on the right the battery once a tap has connected to it (signal strength until then).
 * Several rings: one row each, strongest first; the tapped one is lit. A pairing problem shows under its ring.
 * Rings no registered family supports are listed as "Not supported yet" and can't be tapped.
 */
@Composable
private fun RingCard(s: OnboardingState, slow: Boolean, onSelect: (String) -> Unit) {
    OnboardingCard(Modifier.animateContentSize(tween(220))) {
        if (s.rings.isEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RingPing(pinging = true)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Looking for your ring…", style = OrbitTypography.Meta)
                    Text(
                        if (slow) "Nothing yet. Make sure the ring is charged and close by." else "Keep it close to your phone",
                        style = if (slow) OrbitTypography.Body else RingNameWelcome,
                        modifier = Modifier.padding(top = 1.dp),
                    )
                }
            }
            return@OnboardingCard
        }
        val single = s.rings.count { it.supported } == 1
        s.rings.forEachIndexed { i, ring ->
            if (i > 0) Spacer(Modifier.fillMaxWidth().padding(vertical = 12.dp).height(1.dp).background(OrbitPalette.Hairline))
            val selected = ring.address == s.selected
            val device = s.device.takeIf { selected && s.connected == ring.address }
            RingRow(
                ring = ring,
                // One ring reads as the mockup: "Ring found nearby", with its battery once connected.
                caption = when {
                    !ring.supported -> NOT_SUPPORTED
                    s.connecting == ring.address -> "Connecting…"
                    single -> "Ring found nearby"
                    device != null -> "Connected · firmware ${device.firmware}"
                    selected -> "Tap Connect to use this ring"
                    else -> "Found nearby"
                },
                lit = selected && !single,
                pinging = device == null && ring.supported,
                batteryPct = device?.batteryPct,
                onClick = if (ring.supported) ({ onSelect(ring.address) }) else null,
            )
            if (selected && s.pairError != null) {
                Text(s.pairError, style = OrbitTypography.Meta.copy(color = OrbitPalette.Attention), modifier = Modifier.padding(top = 10.dp))
            }
        }
    }
}

@Composable
private fun RingRow(
    ring: RingItem,
    caption: String,
    lit: Boolean,
    pinging: Boolean,
    batteryPct: Int?,
    /** Null for an unsupported ring: the row is shown but not tappable. */
    onClick: (() -> Unit)?,
) {
    Row(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RingPing(pinging)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(caption, style = OrbitTypography.Meta, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                ring.name, style = RingNameWelcome.copy(color = when { lit -> OrbitPalette.SleepB; ring.supported -> OrbitPalette.Ink; else -> OrbitPalette.Ink2 }),
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 1.dp),
            )
        }
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = 12.dp)) {
            if (batteryPct != null) {
                Text("Battery", style = OrbitTypography.Meta)
                Text("$batteryPct%", style = CardFigure.copy(color = OrbitPalette.Optimal))
            } else if (ring.rssi != null) {
                Text("Signal", style = OrbitTypography.Meta)
                Text(signalWord(signalBars(ring.rssi)), style = CardFigure.copy(color = OrbitPalette.Optimal))
            } else {
                Text("Bridge", style = OrbitTypography.Meta)
                Text("via Mac", style = CardFigure.copy(color = OrbitPalette.Optimal))
            }
        }
    }
}

/** Caption of a nearby ring that no family in this build supports. */
internal const val NOT_SUPPORTED = "Not supported yet"

/** Signal bars (0–4) in words, so the card needs no tiny bar chart. */
internal fun signalWord(bars: Int): String = when {
    bars >= 4 -> "Strong"
    bars == 3 -> "Good"
    bars == 2 -> "Fair"
    else -> "Weak"
}
