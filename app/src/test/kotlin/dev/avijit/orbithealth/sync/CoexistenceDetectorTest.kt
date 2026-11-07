package dev.avijit.orbithealth.sync

import androidx.test.core.app.ApplicationProvider
import dev.avijit.orbithealth.ring.RingRegistry
import dev.avijit.orbithealth.ring.fake.FakeFamily
import dev.avijit.orbithealth.ycbt.YcbtFamily
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CoexistenceDetectorTest {
    private val fake = FakeFamily(vendorAppPackages = setOf("com.fake.vendor"))
    private val registry = RingRegistry(listOf(YcbtFamily, fake))

    private fun detector(pairedFamily: String?, installed: Set<String>) = CoexistenceDetector(
        ApplicationProvider.getApplicationContext(),
        MutableStateFlow(registry.vendorAppPackagesFor(pairedFamily)),
        isInstalled = { it in installed },
    )

    @Test
    fun coexistenceUsesThePairedFamilysPackages() {
        // SmartHealth installed: COEXISTENCE for a YCBT ring, but not for the fake family's ring.
        val smartHealth = setOf(YcbtFamily.VENDOR_APP)
        assertTrue(detector("ycbt", smartHealth).vendorAppInstalled())
        assertEquals(YcbtFamily.VENDOR_APP, detector("ycbt", smartHealth).installedVendorPackage())
        assertFalse(detector("fake", smartHealth).vendorAppInstalled())
        // And the fake family's own vendor app counts only for its rings.
        assertTrue(detector("fake", setOf("com.fake.vendor")).vendorAppInstalled())
        assertFalse(detector("ycbt", setOf("com.fake.vendor")).vendorAppInstalled())
        assertNull(detector("ycbt", emptySet()).installedVendorPackage())
    }

    @Test
    fun beforePairingAnyFamilysVendorAppCounts() {
        assertTrue(detector(null, setOf("com.fake.vendor")).vendorAppInstalled())
        assertTrue(detector(null, setOf(YcbtFamily.VENDOR_APP)).vendorAppInstalled())
        assertFalse(detector(null, emptySet()).vendorAppInstalled())
    }
}
