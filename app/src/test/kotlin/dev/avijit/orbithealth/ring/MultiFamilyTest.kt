package dev.avijit.orbithealth.ring

import dev.avijit.orbithealth.ble.OpenedTransport
import dev.avijit.orbithealth.ble.RingScanFilter
import dev.avijit.orbithealth.ble.openPairedRing
import dev.avijit.orbithealth.prefs.PairedRing
import dev.avijit.orbithealth.prefs.SettingsSnapshot
import dev.avijit.orbithealth.ring.fake.FakeFamily
import dev.avijit.orbithealth.ring.fake.FakeRingDriver
import dev.avijit.orbithealth.ring.fake.FakeRingTransport
import dev.avijit.orbithealth.sync.RingConnection
import dev.avijit.orbithealth.ui.onboarding.OnboardingDeps
import dev.avijit.orbithealth.ui.onboarding.OnboardingStore
import dev.avijit.orbithealth.ui.onboarding.OnboardingViewModel
import dev.avijit.orbithealth.ycbt.YcbtDriver
import dev.avijit.orbithealth.ycbt.YcbtFamily
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Two registered families end to end: scan → pick → pair → re-open, with no app code knowing either family. */
@OptIn(ExperimentalCoroutinesApi::class)
class MultiFamilyTest {
    private val fake = FakeFamily(capabilities = FakeFamily.caps(canClearHistory = false))
    private val registry = RingRegistry(listOf(YcbtFamily, fake))

    @Test
    fun secondFamilyScansPairsAndReopens() = runTest(UnconfinedTestDispatcher()) {
        val filter = RingScanFilter(registry)
        val found = listOf(
            RingAdvertisement("TK5 A1B2", "AA:01", rssi = -80),
            RingAdvertisement("FAKE 01", "AA:02", rssi = -50),
            RingAdvertisement("Band 7", "AA:03", listOf(RingAdvertisement.sigUuid("fee7")), rssi = -40), // weak YCBT match
        ).mapNotNull(filter::accept)
        assertEquals(listOf("ycbt", "fake", "ycbt"), found.map { it.familyId })

        val opened = mutableListOf<PairedRing>()
        var saved: PairedRing? = null
        var savedCaps: RingCapabilities? = null
        val vm = OnboardingViewModel(
            OnboardingDeps(
                scan = { found.asFlow() },
                bridgeMode = { false },
                // What AppGraph.connectTo does: the registry resolves the family, which builds the driver.
                connect = { ring ->
                    opened += ring
                    openPairedRing(SettingsSnapshot().withPairedRing(ring), registry) { _, _ ->
                        val t = FakeRingTransport()
                        OpenedTransport(t) {}
                    }
                },
                store = object : OnboardingStore {
                    override suspend fun saveProfile(profile: UserProfile) = Unit
                    override suspend fun saveRing(ring: PairedRing) { saved = ring }
                    override suspend fun saveCapabilities(caps: RingCapabilities) { savedCaps = caps }
                    override suspend fun batteryPromptAsked() = true
                    override suspend fun markBatteryPromptAsked() = Unit
                },
                startSync = {},
                batteryExempt = { true },
            ),
            scope = backgroundScope,
        )
        vm.startScan()
        advanceUntilIdle()
        // The strong FAKE match is auto-picked; the FEE7-only band (confidence 20) is listed but never auto-picked.
        assertEquals("AA:02", vm.state.value.rings.first().address)
        vm.connectAndContinue()
        advanceUntilIdle()
        assertEquals(listOf(PairedRing("fake", "AA:02", "Fake 1")), opened)
        vm.finish()
        advanceUntilIdle()
        assertEquals(PairedRing("fake", "AA:02", "Fake 1"), saved)
        assertEquals(fake.capabilities, savedCaps)
        assertTrue(fake.drivers.isNotEmpty())
    }

    @Test
    fun weakMatchIsNeverAutoPickedButCanBeTapped() = runTest(UnconfinedTestDispatcher()) {
        val strap = RingScanFilter(registry).accept(RingAdvertisement("Band 7", "AA:03", listOf(RingAdvertisement.sigUuid("fee7")), rssi = -40))!!
        val opened = mutableListOf<String>()
        val vm = OnboardingViewModel(
            OnboardingDeps(
                scan = { listOf(strap).asFlow() },
                bridgeMode = { false },
                connect = { ring -> opened += ring.address; RingConnection(fake.driver(), kotlinx.coroutines.flow.MutableStateFlow(true), false) {} },
                store = object : OnboardingStore {
                    override suspend fun saveProfile(profile: UserProfile) = Unit
                    override suspend fun saveRing(ring: PairedRing) = Unit
                    override suspend fun batteryPromptAsked() = true
                    override suspend fun markBatteryPromptAsked() = Unit
                },
                startSync = {},
                batteryExempt = { true },
            ),
            scope = backgroundScope,
        )
        vm.startScan()
        advanceUntilIdle()
        vm.connectAndContinue()
        advanceUntilIdle()
        assertTrue("never auto-picked", opened.isEmpty())
        vm.select("AA:03")
        advanceUntilIdle()
        assertEquals(listOf("AA:03"), opened)
    }

    @Test
    fun connectResolvesThePairedFamily() = runTest {
        var profileSeen: GattProfile? = null
        val open: suspend (String, GattProfile) -> OpenedTransport = { _, p -> profileSeen = p; OpenedTransport(FakeRingTransport()) {} }

        val fakeConn = openPairedRing(SettingsSnapshot().withPairedRing(PairedRing("fake", "AA:02", "Fake 1")), registry, open)
        assertTrue(fakeConn.driver is FakeRingDriver)
        assertSame(fake.gattProfile, profileSeen)
        assertEquals("AA:02", fakeConn.ringId)

        // An old install (address only) re-opens as YCBT.
        val ycbtConn = openPairedRing(SettingsSnapshot(ringAddress = "AA:01"), registry, open)
        assertTrue(ycbtConn.driver is YcbtDriver)
        assertSame(YcbtFamily.gattProfile, profileSeen)
    }

    @Test
    fun unknownFamilyOrNoRingRaises() = runTest {
        val open: suspend (String, GattProfile) -> OpenedTransport = { _, _ -> fail("must not open"); error("") }
        try {
            openPairedRing(SettingsSnapshot().withPairedRing(PairedRing("colmi", "AA:09")), registry, open)
            fail()
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("colmi"))
        }
        try {
            openPairedRing(SettingsSnapshot(), registry, open)
            fail()
        } catch (e: IOException) {
            assertEquals("No ring paired", e.message)
        }
        assertNull(registry.family("colmi"))
    }
}
