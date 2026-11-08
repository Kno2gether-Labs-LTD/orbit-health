package dev.avijit.orbithealth.ble

import dev.avijit.orbithealth.prefs.SettingsSnapshot
import dev.avijit.orbithealth.sync.SyncMode
import dev.avijit.orbithealth.sync.SyncService
import dev.avijit.orbithealth.sync.SyncState
import dev.avijit.orbithealth.sync.TransportKind
import dev.avijit.orbithealth.ui.me.MeMath
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Mac bridge is only used with a code made in this process, and it is never used silently. */
class BridgeSessionTest {
    private val on = SettingsSnapshot(useMacBridge = true)

    @Test fun aStoredSwitchWithoutThisSessionsCodeStaysOnBluetooth() {
        assertEquals(TransportKind.BLE, AppTransportProvider { null }.kindFor(on))
        assertEquals(TransportKind.BLE, AppTransportProvider { "ABCDE-FGHJK" }.kindFor(SettingsSnapshot()))
        assertEquals(TransportKind.DEBUG_BRIDGE, AppTransportProvider { "ABCDE-FGHJK" }.kindFor(on))
    }

    @Test fun codesAreShortReadableAndFresh() {
        val codes = (1..50).map { BridgeCode.generate() }
        codes.forEach { assertTrue(it, Regex("[A-HJ-NP-Z2-9]{5}-[A-HJ-NP-Z2-9]{5}").matches(it)) }
        assertNotEquals(1, codes.toSet().size)
    }

    @Test fun meAndTheNotificationSayWhenTheBridgeIsInUse() {
        val now = Instant.parse("2026-11-07T10:00:00Z")
        val s = SettingsSnapshot(ringFirmware = "1.18")
        val me = MeMath.state(s, SyncState.Idle, linked = true, mode = SyncMode.COEXISTENCE, now = now, bridge = true)
        assertEquals("Using Mac bridge · connected", me.ringStatus)
        assertEquals("Firmware 1.18 · connected", MeMath.state(s, SyncState.Idle, true, SyncMode.COEXISTENCE, now).ringStatus)
        assertEquals("Orbit Health · using Mac bridge · syncing", SyncService.statusText(SyncState.Syncing, now, bridge = true))
        assertEquals("Orbit Health · syncing", SyncService.statusText(SyncState.Syncing, now))
    }
}
