package dev.avijit.orbithealth.ui.me

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.avijit.orbithealth.ui.components.DangerButton
import dev.avijit.orbithealth.ui.components.GhostButton
import dev.avijit.orbithealth.ui.components.OrbitSheet
import dev.avijit.orbithealth.ui.components.WarmButton
import dev.avijit.orbithealth.ui.theme.OrbitTypography

/** The words of the Developer group's two confirmations (tests read them from here). */
object DeveloperCopy {
    const val RESET_TITLE = "Delete all Orbit Health data on this phone?"
    const val RESET_BODY = "This removes every reading, every night of sleep, your settings and the pairing with your ring " +
        "from this phone. It can't be undone. Your ring doesn't keep history once it has synced to the phone, so it " +
        "can't bring it back either."
    const val RESET_CONFIRM = "Delete everything"
    const val SEED_TITLE = "Add made-up sample readings?"
    const val SEED_BODY = "Five weeks of invented heart rate, sleep and steps, for trying the app without a ring. " +
        "They mix with your real data until you reset."
    const val SEED_CONFIRM = "Add sample data"
    const val CANCEL = "Cancel"

    /** Under "Use Mac bridge" while it is on. */
    fun bridgeCode(code: String) = "Code $code. Start the bridge with ble_bridge.py --code $code"
}

/** Which Developer action is waiting for a yes. */
internal enum class DeveloperConfirm { SEED, RESET }

/**
 * The confirmation before a Developer action. "Cancel" is the warm filled button, so the easy tap is the safe one;
 * a destructive choice sits below it as a [DangerButton]. Dismissing the sheet any other way also cancels.
 */
@Composable
internal fun DeveloperConfirmSheet(which: DeveloperConfirm, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val (title, body) = when (which) {
        DeveloperConfirm.RESET -> DeveloperCopy.RESET_TITLE to DeveloperCopy.RESET_BODY
        DeveloperConfirm.SEED -> DeveloperCopy.SEED_TITLE to DeveloperCopy.SEED_BODY
    }
    OrbitSheet(title = title, kicker = "Developer", onDismiss = onDismiss) {
        Text(body, style = OrbitTypography.Lead)
        Spacer(Modifier.height(22.dp))
        WarmButton(DeveloperCopy.CANCEL, onDismiss, Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        when (which) {
            DeveloperConfirm.RESET -> DangerButton(DeveloperCopy.RESET_CONFIRM, onConfirm, Modifier.fillMaxWidth())
            DeveloperConfirm.SEED -> GhostButton(DeveloperCopy.SEED_CONFIRM, onConfirm, Modifier.fillMaxWidth())
        }
    }
}
