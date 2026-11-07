package dev.avijit.orbithealth.ui.onboarding

import dev.avijit.orbithealth.ble.FoundRing
import dev.avijit.orbithealth.ble.RingScanFilter
import dev.avijit.orbithealth.ring.RingAdvertisement
import dev.avijit.orbithealth.ring.RingRegistry
import dev.avijit.orbithealth.ycbt.YcbtFamily
import dev.avijit.orbithealth.prefs.PairedRing
import dev.avijit.orbithealth.prefs.Settings
import dev.avijit.orbithealth.ring.DeviceInfo
import dev.avijit.orbithealth.ring.HistoryBatch
import dev.avijit.orbithealth.ring.MeasureEvent
import dev.avijit.orbithealth.ring.MeasureKind
import dev.avijit.orbithealth.ring.MonitoringConfig
import dev.avijit.orbithealth.ring.RingCapabilities
import dev.avijit.orbithealth.ring.RingDriver
import dev.avijit.orbithealth.ring.Sex
import dev.avijit.orbithealth.ring.UserProfile
import dev.avijit.orbithealth.sync.RingConnection
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {
    private val events = mutableListOf<String>()

    private inner class FakeDriver : RingDriver {
        val profiles = mutableListOf<UserProfile>()
        var failHandshakeWith: Exception? = null
        var hang = false
        override suspend fun handshake(profile: UserProfile): Pair<DeviceInfo, RingCapabilities> {
            events += "handshake"
            profiles += profile
            failHandshakeWith?.let { throw it }
            if (hang) awaitCancellation()
            return DeviceInfo(7, "2.3.1", 54) to RingCapabilities(emptySet(), sleep = true, workouts = true, realtime = true)
        }
        override suspend fun syncHistory(): HistoryBatch = error("not used")
        override suspend fun clearSyncedHistory(batch: HistoryBatch) = error("not used")
        override fun liveHeartRate(): Flow<Int> = emptyFlow()
        override fun measure(kind: MeasureKind): Flow<MeasureEvent> = error("not used")
        override suspend fun readMonitoring(): MonitoringConfig? = error("not used")
        override suspend fun applyMonitoring(config: MonitoringConfig): MonitoringConfig = error("not used")
    }

    private inner class FakeStore : OnboardingStore {
        var profile: UserProfile? = null
        var ringAddress: String? = null
        var ring: PairedRing? = null
        var asked = false
        var name: String? = "unset"
        var nameBeforeAddress = false
        override suspend fun saveName(name: String?) { nameBeforeAddress = ringAddress == null; this.name = name }
        override suspend fun saveProfile(profile: UserProfile) { events += "saveProfile"; this.profile = profile }
        override suspend fun saveRing(ring: PairedRing) { events += "saveAddress"; ringAddress = ring.address; this.ring = ring }
        override suspend fun batteryPromptAsked() = asked
        override suspend fun markBatteryPromptAsked() { asked = true }
    }

    private val driver = FakeDriver()
    private val store = FakeStore()
    private val found = MutableSharedFlow<FoundRing>(replay = 16)
    private var scanSource: Flow<FoundRing> = found
    private var syncError: RuntimeException? = null
    private val logged = mutableListOf<String>()
    private val connectedTo = mutableListOf<String>()
    private var syncStarts = 0

    private fun TestScope.viewModel(bridge: Boolean = false, exempt: Boolean = false) = OnboardingViewModel(
        OnboardingDeps(
            scan = { scanSource },
            bridgeMode = { bridge },
            connect = { ring ->
                events += "connect"
                connectedTo += ring.address
                RingConnection(driver, MutableStateFlow(true), allowsClear = false) { events += "close" }
            },
            store = store,
            startSync = {
                events += "startSync"
                syncStarts++
                syncError?.let { throw it }
            },
            batteryExempt = { exempt },
            logError = { msg, _ -> logged += msg },
        ),
        scope = backgroundScope,
    )


    @Test
    fun profileValidationRejectsOutOfRange() {
        fun errors(h: String, w: String, a: String) = ProfileRules.validate(ProfileInput(h, w, a, Sex.UNSPECIFIED))

        assertTrue(errors("170", "70", "35").isValid)
        // Inclusive bounds.
        assertTrue(errors("100", "30", "13").isValid)
        assertTrue(errors("230", "250", "100").isValid)

        val low = errors("99", "29", "12")
        assertEquals("Height must be 100–230 cm", low.height)
        assertEquals("Weight must be 30–250 kg", low.weight)
        assertEquals("Age must be 13–100", low.age)

        val high = errors("231", "251", "101")
        assertFalse(high.isValid)
        assertEquals("Height must be 100–230 cm", high.height)
        assertEquals("Weight must be 30–250 kg", high.weight)
        assertEquals("Age must be 13–100", high.age)

        val blank = errors("", "7x", " ")
        assertEquals("Enter your height", blank.height)
        assertEquals("Weight must be 30–250 kg", blank.weight)
        assertEquals("Enter your age", blank.age)

        assertEquals(UserProfile(182, 80, Sex.FEMALE, 41), ProfileRules.profileOf(ProfileInput("182", "80", "41", Sex.FEMALE)))
        assertNull(ProfileRules.profileOf(ProfileInput("182", "80", "12", Sex.FEMALE)))
    }

    @Test
    fun invalidProfileShowsInlineErrorsAndDoesNotPair() = runTest(UnconfinedTestDispatcher()) {
        val vm = viewModel()
        found.emit(FoundRing("TK5 A1B2", "AA:BB", -62, "ycbt", "TK5", 90))
        vm.startScan()
        vm.select("AA:BB")
        vm.setAge("9")
        vm.finish()
        advanceUntilIdle()

        assertEquals("Age must be 13–100", vm.state.value.profileErrors.age)
        assertNull(store.profile)
        assertNull(store.ringAddress)
        assertEquals(0, syncStarts)
    }

    @Test
    fun finishSavesTheTrimmedNameOrNoneWhenBlank() = runTest(UnconfinedTestDispatcher()) {
        val vm = viewModel(exempt = true)
        found.emit(FoundRing("TK5 A1B2", "AA:BB", -62, "ycbt", "TK5", 90))
        vm.startScan()
        vm.select("AA:BB")
        vm.setName("  Sam ")
        assertEquals("  Sam ", vm.state.value.name)
        vm.finish()
        advanceUntilIdle()
        assertEquals("Sam", store.name)
        assertTrue(store.nameBeforeAddress)

        store.ringAddress = null
        val blank = viewModel(exempt = true)
        blank.startScan()
        blank.select("AA:BB")
        blank.setName("   ")
        blank.finish()
        advanceUntilIdle()
        assertNull(store.name)
    }

    @Test
    fun scanListKeysByAddressAndSortsBySignal() = runTest(UnconfinedTestDispatcher()) {
        val vm = viewModel()
        vm.startScan()
        found.emit(FoundRing("TK5 0001", "AA:01", -80, "ycbt", "TK5", 90))
        found.emit(FoundRing("TK5 0002", "AA:02", -70, "ycbt", "TK5", 90))
        found.emit(FoundRing("TK5 0001", "AA:01", -55, "ycbt", "TK5", 90)) // RSSI update for a known ring
        assertEquals(listOf("AA:01", "AA:02"), vm.state.value.rings.map { it.address })
        assertEquals(-55, vm.state.value.rings.first().rssi)
    }

    @Test
    fun selectingRingHandshakesWithDefaultProfileAndShowsBattery() = runTest(UnconfinedTestDispatcher()) {
        val vm = viewModel()
        vm.startScan()
        found.emit(FoundRing("TK5 A1B2", "AA:BB", -62, "ycbt", "TK5", 90))
        vm.select("AA:BB")

        assertEquals(listOf("AA:BB"), connectedTo)
        assertEquals(listOf(Settings.DEFAULT_PROFILE), driver.profiles)
        val s = vm.state.value
        assertEquals("AA:BB", s.connected)
        assertEquals(54, s.device?.batteryPct)
        assertEquals("2.3.1", s.device?.firmware)
        assertNull(store.ringAddress) // nothing saved before the profile step
    }

    @Test
    fun connectButtonPicksStrongestRingAndProceeds() = runTest(UnconfinedTestDispatcher()) {
        val vm = viewModel()
        vm.startScan()
        found.emit(FoundRing("TK5 weak", "AA:01", -90, "ycbt", "TK5", 90))
        found.emit(FoundRing("TK5 near", "AA:02", -50, "ycbt", "TK5", 90))
        vm.connectAndContinue()
        assertEquals(listOf("AA:02"), connectedTo)
        assertTrue(vm.state.value.proceedToProfile)
        vm.onProceededToProfile()
        assertFalse(vm.state.value.proceedToProfile)
    }

    @Test
    fun pairingSuccessPersistsAddressAndStartsSync() = runTest(UnconfinedTestDispatcher()) {
        val vm = viewModel()
        vm.startScan()
        found.emit(FoundRing("TK5 A1B2", "AA:BB", -62, "ycbt", "TK5", 90))
        vm.select("AA:BB")
        vm.setHeight("182")
        vm.setWeight("80")
        vm.setAge("41")
        vm.setSex(Sex.FEMALE)
        vm.finish()
        advanceUntilIdle()

        val real = UserProfile(182, 80, Sex.FEMALE, 41)
        assertEquals(real, store.profile)
        assertEquals("AA:BB", store.ringAddress)
        assertEquals(PairedRing("ycbt", "AA:BB", "TK5"), store.ring) // family + address + model saved together
        assertEquals(real, driver.profiles.last())
        assertEquals(1, syncStarts)
        // Profile first, then the final handshake with it, and only then the address; the link is closed before sync.
        assertEquals(
            listOf("connect", "handshake", "saveProfile", "handshake", "close", "saveAddress", "startSync"),
            events,
        )
        // First run: ask for the battery exemption once, then finish.
        assertTrue(vm.state.value.askBatteryExemption)
        assertTrue(store.asked)
        assertFalse(vm.state.value.completed)
        vm.onBatteryPromptHandled()
        assertTrue(vm.state.value.completed)
    }

    @Test
    fun failedFinalHandshakeSavesNoAddressAndAllowsRetry() = runTest(UnconfinedTestDispatcher()) {
        val vm = viewModel()
        vm.startScan()
        found.emit(FoundRing("TK5 A1B2", "AA:BB", -62, "ycbt", "TK5", 90))
        vm.select("AA:BB")
        driver.failHandshakeWith = IOException("link lost")
        vm.finish()
        advanceUntilIdle()

        assertNull(store.ringAddress)
        assertEquals(0, syncStarts)
        assertTrue(vm.state.value.finishError != null)
        assertFalse(vm.state.value.finishing)

        driver.failHandshakeWith = null
        vm.finish()
        advanceUntilIdle()
        assertEquals("AA:BB", store.ringAddress)
        assertEquals(1, syncStarts)
    }

    @Test
    fun batteryPromptSkippedWhenAlreadyAskedOrExempt() = runTest(UnconfinedTestDispatcher()) {
        store.asked = true
        val vm = viewModel()
        vm.startScan()
        found.emit(FoundRing("TK5 A1B2", "AA:BB", -62, "ycbt", "TK5", 90))
        vm.select("AA:BB")
        vm.finish()
        advanceUntilIdle()
        assertFalse(vm.state.value.askBatteryExemption)
        assertTrue(vm.state.value.completed)

        store.asked = false
        val exemptVm = viewModel(exempt = true)
        exemptVm.startScan()
        exemptVm.select("AA:BB")
        exemptVm.finish()
        advanceUntilIdle()
        assertFalse(exemptVm.state.value.askBatteryExemption)
        assertTrue(exemptVm.state.value.completed)
        assertTrue(store.asked) // recorded so we never ask later either
    }

    @Test
    fun bridgeModeShowsSingleDebugEntryInsteadOfScanning() = runTest(UnconfinedTestDispatcher()) {
        found.emit(FoundRing("TK5 A1B2", "AA:BB", -62, "ycbt", "TK5", 90))
        val vm = viewModel(bridge = true)
        vm.startScan()
        val rings = vm.state.value.rings
        assertEquals(1, rings.size)
        assertEquals("Mac bridge (debug)", rings.single().name)
        assertNull(rings.single().rssi)
        vm.select(rings.single().address)
        assertEquals(OnboardingViewModel.DEBUG_BRIDGE_ADDRESS, connectedTo.single())
        assertEquals("ycbt", rings.single().familyId) // the bridge relays the TK5
    }

    @Test
    fun connectTimeoutShowsErrorAndClosesLink() = runTest(UnconfinedTestDispatcher()) {
        val vm = viewModel()
        vm.startScan()
        found.emit(FoundRing("TK5 A1B2", "AA:BB", -62, "ycbt", "TK5", 90))
        driver.hang = true
        vm.select("AA:BB")
        assertEquals("AA:BB", vm.state.value.connecting)
        advanceTimeBy(41_000)

        val s = vm.state.value
        assertNull(s.connecting)
        assertNull(s.connected)
        assertEquals(OnboardingViewModel.CONNECT_FAILED, s.pairError)
        assertEquals("close", events.last())

        driver.hang = false
        vm.select("AA:BB") // recoverable: a new tap reconnects
        assertEquals("AA:BB", vm.state.value.connected)
    }

    @Test
    fun finishTimeoutAllowsRetry() = runTest(UnconfinedTestDispatcher()) {
        val vm = viewModel()
        vm.startScan()
        found.emit(FoundRing("TK5 A1B2", "AA:BB", -62, "ycbt", "TK5", 90))
        vm.select("AA:BB")
        driver.hang = true
        vm.finish()
        assertTrue(vm.state.value.finishing)
        advanceTimeBy(41_000)

        assertFalse(vm.state.value.finishing)
        assertEquals(OnboardingViewModel.FINISH_FAILED, vm.state.value.finishError)
        assertNull(store.ringAddress)
        assertEquals("close", events.last())

        driver.hang = false
        vm.finish()
        advanceUntilIdle()
        assertEquals("AA:BB", store.ringAddress)
        assertEquals(1, syncStarts)
    }

    @Test
    fun releaseRingOnBackClosesLinkAndForgetsSelection() = runTest(UnconfinedTestDispatcher()) {
        val vm = viewModel()
        vm.startScan()
        found.emit(FoundRing("TK5 A1B2", "AA:BB", -62, "ycbt", "TK5", 90))
        vm.select("AA:BB")
        vm.releaseRing()

        assertEquals("close", events.last())
        val s = vm.state.value
        assertNull(s.selected)
        assertNull(s.connected)
        assertNull(s.device)
        assertFalse(s.scanning)
        assertNull(store.ringAddress)
    }

    @Test
    fun restartingScanDropsStaleRings() = runTest(UnconfinedTestDispatcher()) {
        val vm = viewModel()
        vm.startScan()
        found.emit(FoundRing("TK5 gone", "AA:01", -60, "ycbt", "TK5", 90))
        vm.stopScan()
        val fresh = MutableSharedFlow<FoundRing>()
        scanSource = fresh
        vm.startScan()
        assertTrue(vm.state.value.rings.isEmpty()) // nothing stale while the new scan has found nothing
        fresh.emit(FoundRing("TK5 here", "AA:02", -70, "ycbt", "TK5", 90))
        assertEquals(listOf("AA:02"), vm.state.value.rings.map { it.address })
    }

    @Test
    fun syncStartFailureAfterSavingStillCompletes() = runTest(UnconfinedTestDispatcher()) {
        store.asked = true
        syncError = IllegalStateException("FGS start not allowed")
        val vm = viewModel()
        vm.startScan()
        found.emit(FoundRing("TK5 A1B2", "AA:BB", -62, "ycbt", "TK5", 90))
        vm.select("AA:BB")
        vm.finish()
        advanceUntilIdle()

        assertEquals("AA:BB", store.ringAddress)
        assertNull(vm.state.value.finishError)
        assertFalse(vm.state.value.finishing)
        assertTrue(vm.state.value.completed) // MainActivity.onStart retries the service
        assertEquals(1, logged.size)
    }

    @Test
    fun scannerListsUnsupportedButNeverConnects() = runTest(UnconfinedTestDispatcher()) {
        // Real scan filter + registry (YCBT only) feeding the view model.
        val filter = RingScanFilter(RingRegistry(listOf(YcbtFamily)))
        val adverts = listOf(
            RingAdvertisement("R02_1A2B", "AA:01", rssi = -40), // a Colmi ring: no family yet
            RingAdvertisement("Living Room TV", "AA:03", rssi = -30), // not a ring: hidden
            RingAdvertisement("TK5 A1B2", "AA:02", rssi = -70),
        )
        val emitted = adverts.mapNotNull(filter::accept)
        assertEquals(listOf("AA:01", "AA:02"), emitted.map { it.address })
        assertFalse(emitted[0].supported)
        assertEquals("ycbt", emitted[1].familyId)
        assertEquals("TK5", emitted[1].modelName)

        scanSource = emitted.asFlow()
        val vm = viewModel()
        vm.startScan()
        advanceUntilIdle()
        val rings = vm.state.value.rings
        assertEquals("supported first, then by signal", listOf("AA:02", "AA:01"), rings.map { it.address })
        assertFalse(rings.single { it.address == "AA:01" }.supported)

        vm.select("AA:01") // tapping the unsupported row does nothing
        advanceUntilIdle()
        assertTrue(connectedTo.isEmpty())
        assertNull(vm.state.value.selected)

        vm.connectAndContinue() // picks the strongest *supported* ring
        advanceUntilIdle()
        assertEquals(listOf("AA:02"), connectedTo)
    }

    @Test
    fun onlyUnsupportedRingsNearbyConnectsNothing() = runTest(UnconfinedTestDispatcher()) {
        val vm = viewModel()
        vm.startScan()
        found.emit(FoundRing("Galaxy Ring", "AA:09", -50))
        vm.connectAndContinue()
        vm.select("AA:09")
        vm.finish()
        advanceUntilIdle()
        assertTrue(connectedTo.isEmpty())
        assertNull(store.ringAddress)
    }

    @Test
    fun signalBarsFromRssi() {
        assertEquals(4, signalBars(-50))
        assertEquals(3, signalBars(-65))
        assertEquals(2, signalBars(-75))
        assertEquals(1, signalBars(-85))
        assertEquals(0, signalBars(-100))
    }
}
