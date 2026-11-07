package dev.avijit.orbithealth.ui.me

import dev.avijit.orbithealth.prefs.SettingsSnapshot
import dev.avijit.orbithealth.ring.RingCapabilities
import dev.avijit.orbithealth.ring.MonitorKind
import dev.avijit.orbithealth.ring.MonitorSpec
import dev.avijit.orbithealth.ring.DeviceInfo
import dev.avijit.orbithealth.ring.HistoryBatch
import dev.avijit.orbithealth.ring.MeasureEvent
import dev.avijit.orbithealth.ring.MeasureKind
import dev.avijit.orbithealth.ring.RingDriver
import dev.avijit.orbithealth.ring.Sex
import dev.avijit.orbithealth.ring.UserProfile
import dev.avijit.orbithealth.sync.BatchStore
import dev.avijit.orbithealth.sync.MonitoringPrefs
import dev.avijit.orbithealth.sync.toPrefs
import dev.avijit.orbithealth.ring.MonitoringConfig
import dev.avijit.orbithealth.sync.RingConnection
import dev.avijit.orbithealth.sync.SyncEngine
import dev.avijit.orbithealth.sync.SyncMode
import dev.avijit.orbithealth.sync.SyncState
import java.time.Clock
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneOffset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MeViewModelTest {
    private val now = Instant.parse("2026-11-05T09:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val settings = MutableStateFlow(SettingsSnapshot(ringAddress = "AA:BB", hrIntervalMin = 10, spo2Overnight = true))

    private inner class FakeDriver : RingDriver {
        val configured = mutableListOf<MonitoringPrefs>()
        override suspend fun handshake(profile: UserProfile) =
            DeviceInfo(7, "1.18", 54) to RingCapabilities(emptySet(), sleep = true, workouts = false, realtime = true)
        override suspend fun syncHistory() = HistoryBatch(emptyList(), emptyList(), emptyList())
        override suspend fun clearSyncedHistory(batch: HistoryBatch) = Unit
        override fun liveHeartRate(): Flow<Int> = emptyFlow()
        override fun measure(kind: MeasureKind): Flow<MeasureEvent> = emptyFlow()
        override suspend fun readMonitoring(): MonitoringConfig? = null
        override suspend fun applyMonitoring(config: MonitoringConfig): MonitoringConfig {
            configured += config.toPrefs()
            return config
        }
    }

    private val driver = FakeDriver()

    private fun engine(mode: SyncMode) = SyncEngine(
        connector = { RingConnection(driver, MutableStateFlow(true), allowsClear = false) {} },
        store = BatchStore { },
        mode = { mode },
        clock = clock,
        profile = { UserProfile(175, 75, Sex.UNSPECIFIED, 35) },
        vendorAppInstalled = { mode == SyncMode.COEXISTENCE },
        monitoring = { settings.value.let { MonitoringPrefs(it.hrIntervalMin, it.spo2Overnight) } },
    )

    private val store = object : MeStore {
        override suspend fun setStepGoalBase(v: Int) = settings.update { it.copy(stepGoalBase = v) }
        override suspend fun setSleepGoalMin(v: Int) = settings.update { it.copy(sleepGoalMin = v) }
        override suspend fun setBedtimeTarget(v: LocalTime) = settings.update { it.copy(bedtimeTarget = v) }
        override suspend fun setProfile(p: UserProfile) = settings.update { it.copy(profile = p) }
        override suspend fun setAppLockEnabled(v: Boolean) = settings.update { it.copy(appLockEnabled = v) }
        override suspend fun setName(name: String?) = settings.update { it.copy(name = name) }
    }

    private fun TestScope.vm(engine: SyncEngine, mode: SyncMode, vendorAppName: String? = "SmartHealth") = MeViewModel(
        MeDeps(
            vendorAppName = flowOf(vendorAppName),
            settings = settings,
            syncState = engine.state,
            linked = engine.linked,
            mode = MutableStateFlow(mode),
            store = store,
            clock = clock,
            now = flowOf(now),
        ),
        scope = this.backgroundScope,
    )

    @Test fun automaticChecksRowSummarisesTheRingsChecks() = runTest {
        // The controls moved to their own screen; Me only says what is set, for the checks the ring declares.
        val vm = vm(engine(SyncMode.COEXISTENCE), SyncMode.COEXISTENCE)
        assertNull("capabilities unknown", vm.state.first { it.loaded }.autoChecks)
        settings.update {
            it.copy(
                ringCapabilities = RingCapabilities(
                    emptySet(), sleep = true, workouts = false, realtime = true,
                    monitoring = mapOf(
                        MonitorKind.SPO2 to MonitorSpec(listOf(30, 60), canTurnOff = true, supportsWindow = false),
                        MonitorKind.HEART_RATE to MonitorSpec(listOf(10, 15, 20, 30, 60), canTurnOff = true, supportsWindow = false),
                    ),
                ),
                hrIntervalMin = 15, spo2Overnight = false,
            )
        }
        assertEquals("Heart rate every 15 min · SpO₂ off", vm.state.first { it.autoChecks != null }.autoChecks)
    }

    @Test fun keepConnectedDisabledInCoexistence() = runTest {
        val vm = vm(engine(SyncMode.COEXISTENCE), SyncMode.COEXISTENCE)
        val keep = vm.state.first { it.loaded }.keepConnected
        assertFalse(keep.on)
        assertFalse(keep.enabled)
        assertEquals("Uninstall SmartHealth to keep your ring connected", keep.caption)
    }

    @Test fun coexistenceCaptionNamesTheVendorAppOrSaysItGenerically() = runTest {
        val other = vm(engine(SyncMode.COEXISTENCE), SyncMode.COEXISTENCE, vendorAppName = "Acme Health")
        assertEquals("Uninstall Acme Health to keep your ring connected", other.state.first { it.loaded }.keepConnected.caption)
        val unknown = vm(engine(SyncMode.COEXISTENCE), SyncMode.COEXISTENCE, vendorAppName = null)
        assertEquals("Uninstall your ring's own app to keep your ring connected", unknown.state.first { it.loaded }.keepConnected.caption)
    }

    @Test fun keepConnectedOnInExclusive() = runTest {
        val vm = vm(engine(SyncMode.EXCLUSIVE), SyncMode.EXCLUSIVE)
        val keep = vm.state.first { it.loaded }.keepConnected
        assertTrue(keep.on)
        assertNull(keep.caption)
    }

    @Test fun ringCardShowsHandshakeInfoAndConnection() = runTest {
        settings.update {
            it.copy(ringName = "TK5 A1B2", ringFirmware = "1.18", ringBatteryPct = 54, lastSyncedAt = now.minusSeconds(120))
        }
        val engine = engine(SyncMode.COEXISTENCE)
        val vm = vm(engine, SyncMode.COEXISTENCE)
        val s = vm.state.first { it.loaded }
        assertEquals("TK5 A1B2", s.ringName)
        assertEquals("Firmware 1.18 · last synced 2 min ago", s.ringStatus)
        assertEquals(54, s.batteryPct)
        assertEquals("2 min ago", s.lastSync)
    }

    @Test fun ringCardDefaultsAndConnectedState() = runTest {
        val engine = engine(SyncMode.EXCLUSIVE)
        engine.syncOnce()
        val vm = vm(engine, SyncMode.EXCLUSIVE)
        val s = vm.state.first { it.loaded && it.ringStatus.endsWith("connected", ignoreCase = true) }
        assertEquals("Your ring", s.ringName)
        assertEquals("Connected", s.ringStatus)
    }

    @Test fun connectionWords() {
        assertEquals("syncing", MeMath.connection(SyncState.Syncing, linked = false, lastSyncedAt = null, now = now))
        assertEquals("ring busy", MeMath.connection(SyncState.RingBusy, linked = false, lastSyncedAt = null, now = now))
        assertEquals("connected", MeMath.connection(SyncState.Synced(now), linked = true, lastSyncedAt = now, now = now))
        assertEquals("last synced just now", MeMath.connection(SyncState.Idle, false, now.minusSeconds(20), now))
        assertEquals("last synced 3 hr ago", MeMath.connection(SyncState.Idle, false, now.minusSeconds(3 * 3600 + 60), now))
        assertEquals("not synced yet", MeMath.connection(SyncState.Idle, false, null, now))
    }

    @Test fun sleepGoalFormatting() {
        assertEquals("7h 30m", MeMath.hoursMinutes(450))
        assertEquals("8h", MeMath.hoursMinutes(480))
    }

    @Test fun appLockToggleIsSavedAndShown() = runTest {
        val vm = vm(engine(SyncMode.EXCLUSIVE), SyncMode.EXCLUSIVE)
        assertTrue(vm.state.first { it.loaded }.appLockEnabled) // on by default
        vm.setAppLockEnabled(false) { true }
        runCurrent()
        assertFalse(settings.value.appLockEnabled)
        assertFalse(vm.state.first { it.loaded }.appLockEnabled)
    }

    @Test fun appLockCaptionMentionsRecentsAndScreenshotsOnOldApis() {
        assertEquals("Fingerprint or phone PIN after 1 min away. Hides Orbit Health in recent apps.", MeMath.appLockCaption(60, 34))
        assertTrue(MeMath.appLockCaption(60, 32).endsWith("Hides Orbit Health in recent apps and blocks screenshots."))
    }

    @Test fun turningLockOffNeedsAuthentication_onDoesNot() = runTest {
        val vm = vm(engine(SyncMode.EXCLUSIVE), SyncMode.EXCLUSIVE)
        vm.state.first { it.loaded }
        var asked = 0

        assertFalse(vm.setAppLockEnabled(false) { asked++; false }) // failed / cancelled check
        assertTrue(settings.value.appLockEnabled)

        assertTrue(vm.setAppLockEnabled(false) { asked++; true })
        assertFalse(settings.value.appLockEnabled)
        assertEquals(2, asked)

        assertTrue(vm.setAppLockEnabled(true) { asked++; false }) // turning it on never asks
        assertTrue(settings.value.appLockEnabled)
        assertEquals(2, asked)
    }

    @Test fun nameShowsOnMeAndIsSavedTrimmed() = runTest {
        val vm = vm(engine(SyncMode.EXCLUSIVE), SyncMode.EXCLUSIVE)
        assertNull("no name yet", vm.state.first { it.loaded }.name)

        vm.setName("  Sam  ")
        runCurrent()
        assertEquals("Sam", settings.value.name)
        assertEquals("Sam", vm.state.first { it.loaded }.name)
    }

    @Test fun blankNameClearsTheGreetingNameAndLongNamesAreCapped() = runTest {
        settings.update { it.copy(name = "Sam") }
        val vm = vm(engine(SyncMode.EXCLUSIVE), SyncMode.EXCLUSIVE)
        vm.state.first { it.loaded }

        vm.setName("   ")
        runCurrent()
        assertNull(settings.value.name)

        vm.setName("x".repeat(60))
        runCurrent()
        assertEquals(MeMath.MAX_NAME, settings.value.name!!.length)
    }

    @Test fun agentSyncRowAndDataPrivacyShowTheServerAndBytesSentThisMonth() = runTest {
        val server = MutableStateFlow<String?>(null)
        val sent = MutableStateFlow(0L)
        val engine = engine(SyncMode.COEXISTENCE)
        val vm = MeViewModel(
            MeDeps(
                settings = settings, syncState = engine.state, linked = engine.linked, mode = MutableStateFlow(SyncMode.COEXISTENCE),
                store = store, clock = clock, now = flowOf(now), serverName = server, sentSince = { sent },
            ),
            scope = backgroundScope,
        )
        runCurrent()
        assertEquals("Not set up", vm.state.value.agentSync)
        assertEquals("Sent to your server this month: 0 KB", vm.state.value.sentThisMonth)
        server.value = "Home Mac"
        sent.value = 12_800
        runCurrent()
        assertEquals("Home Mac", vm.state.value.agentSync)
        assertEquals("Sent to your server this month: 13 KB", vm.state.value.sentThisMonth)
        sent.value = 1_044_000
        runCurrent()
        assertEquals("Sent to your server this month: 1,020 KB", vm.state.value.sentThisMonth)
        sent.value = 5_400_000
        runCurrent()
        assertEquals("Sent to your server this month: 5.1 MB", vm.state.value.sentThisMonth)
    }

    @Test fun bytesThisMonthRestartOnTheFirstWithoutARestart() = runTest {
        val ticks = MutableStateFlow(Instant.parse("2026-10-31T23:59:00Z"))
        val asked = mutableListOf<Long>()
        val engine = engine(SyncMode.COEXISTENCE)
        val vm = MeViewModel(
            MeDeps(
                settings = settings, syncState = engine.state, linked = engine.linked, mode = MutableStateFlow(SyncMode.COEXISTENCE),
                store = store, clock = clock, now = ticks,
                sentSince = { from -> asked += from; flowOf(if (from >= Instant.parse("2026-11-01T00:00:00Z").toEpochMilli()) 0L else 900_000L) },
            ),
            scope = backgroundScope,
        )
        runCurrent()
        assertEquals("Sent to your server this month: 879 KB", vm.state.value.sentThisMonth)
        ticks.value = Instant.parse("2026-10-31T23:59:30Z")
        runCurrent()
        assertEquals("same month: no new query", 1, asked.size)
        ticks.value = Instant.parse("2026-11-01T00:00:30Z")
        runCurrent()
        assertEquals(listOf(Instant.parse("2026-10-01T00:00:00Z").toEpochMilli(), Instant.parse("2026-11-01T00:00:00Z").toEpochMilli()), asked)
        assertEquals("Sent to your server this month: 0 KB", vm.state.value.sentThisMonth)
    }
}
