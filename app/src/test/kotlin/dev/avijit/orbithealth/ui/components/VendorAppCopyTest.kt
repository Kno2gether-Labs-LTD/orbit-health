package dev.avijit.orbithealth.ui.components

import dev.avijit.orbithealth.ring.RingRegistry
import dev.avijit.orbithealth.ui.today.CoexistenceCopy
import dev.avijit.orbithealth.ycbt.YcbtFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class VendorAppCopyTest {
    @Test fun ycbtStillSaysSmartHealth() {
        val name = RingRegistry(listOf(YcbtFamily)).vendorAppName(YcbtFamily.VENDOR_APP)
        assertEquals("SmartHealth", name)
        assertEquals("SmartHealth detected", VendorAppCopy.bannerTitle(name))
        assertEquals(
            "SmartHealth is also installed. Both apps share your ring, so syncing may be slower and SmartHealth may " +
                "upload your data to its servers. For the best experience, uninstall it.",
            VendorAppCopy.bannerBody(name),
        )
        assertEquals(CoexistenceCopy, VendorAppCopy.bannerBody(name)) // spec §5.13 verbatim
        assertEquals("Uninstall SmartHealth to keep your ring connected", VendorAppCopy.keepConnectedCaption(name))
    }

    @Test fun anotherVendorIsNamedAndAnUnknownOneIsGeneric() {
        assertEquals("Acme Health detected", VendorAppCopy.bannerTitle("Acme Health"))
        assertEquals("Your ring's own app detected", VendorAppCopy.bannerTitle(null))
        val body = VendorAppCopy.bannerBody(null)
        assertEquals(
            "Your ring's own app is also installed. Both apps share your ring, so syncing may be slower and that app " +
                "may upload your data to its servers. For the best experience, uninstall it.",
            body,
        )
        assertFalse(body.contains("SmartHealth"))
        assertEquals("Uninstall your ring's own app to keep your ring connected", VendorAppCopy.keepConnectedCaption(null))
    }
}
