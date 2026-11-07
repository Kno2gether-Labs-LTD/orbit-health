package dev.avijit.orbithealth.ycbt

import dev.avijit.orbithealth.ring.GattChar
import dev.avijit.orbithealth.ring.RingAdvertisement
import dev.avijit.orbithealth.ring.RingIdentity
import dev.avijit.orbithealth.ring.RingRegistry
import dev.avijit.orbithealth.ring.fake.FakeFamily
import dev.avijit.orbithealth.ring.fake.FakeRingTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class YcbtFamilyTest {
    private fun adv(name: String?, vararg services: String) = RingAdvertisement(name, "C3:1A:00:00:A1:B2", services.toList(), rssi = -62)

    @Test
    fun registryPicksYcbtForTk5Advert() {
        // A competing family that also claims TK5 names, but less confidently.
        val other = FakeFamily(id = "other", namePrefix = "TK", confidence = 40)
        val id = RingRegistry(listOf(other, YcbtFamily)).identify(adv("TK5 A1B2")) as RingIdentity.Supported
        assertSame(YcbtFamily, id.family)
        assertEquals("ycbt", id.family.id)
        assertEquals("TK5", id.match.modelName)
        assertEquals(YcbtFamily.NAME_CONFIDENCE, id.match.confidence)
    }

    @Test
    fun serviceOnlyMatchIsLowConfidence() {
        val fee7 = YcbtFamily.match(adv(null, "0000fee7-0000-1000-8000-00805f9b34fb"))!!
        val band = YcbtFamily.match(adv("Band", "0000FEE7-0000-1000-8000-00805F9B34FB"))!!
        assertEquals(YcbtFamily.SERVICE_CONFIDENCE, fee7.confidence)
        assertEquals("YCBT ring", fee7.modelName)
        assertEquals("Band", band.modelName)
        assertTrue(YcbtFamily.SERVICE_CONFIDENCE < dev.avijit.orbithealth.ring.RingRegistry.AUTO_PICK_CONFIDENCE)
        // The generic Heart Rate service alone (any HR strap or watch) is not a YCBT ring.
        assertNull(YcbtFamily.match(adv("Polar H10", "0000180D-0000-1000-8000-00805F9B34FB")))
        assertNull(YcbtFamily.match(adv("Pixel Buds", "0000110b-0000-1000-8000-00805f9b34fb")))
    }

    @Test
    fun gattProfileMatchesTheTk5() {
        val p = YcbtFamily.gattProfile
        val svc = "be940000-7333-be46-b7ae-689e71722bd5"
        assertEquals(GattChar(svc, "be940001-7333-be46-b7ae-689e71722bd5"), p.defaultWrite)
        assertEquals(listOf(p.defaultWrite), p.writes)
        assertEquals(
            listOf(GattChar(svc, "be940001-7333-be46-b7ae-689e71722bd5"), GattChar(svc, "be940003-7333-be46-b7ae-689e71722bd5")),
            p.notifies,
        )
        assertEquals(setOf("com.zhuoting.healthyucheng"), YcbtFamily.vendorAppPackages)
        assertTrue(p.writeWithResponse)
        assertTrue(YcbtFamily.createDriver(FakeRingTransport()) is YcbtDriver)
    }
}
