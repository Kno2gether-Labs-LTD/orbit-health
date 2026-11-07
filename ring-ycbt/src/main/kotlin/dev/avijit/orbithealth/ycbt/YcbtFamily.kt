package dev.avijit.orbithealth.ycbt

import dev.avijit.orbithealth.ring.GattChar
import dev.avijit.orbithealth.ring.GattProfile
import dev.avijit.orbithealth.ring.RingAdvertisement
import dev.avijit.orbithealth.ring.RingDriver
import dev.avijit.orbithealth.ring.RingFamily
import dev.avijit.orbithealth.ring.RingMatch
import dev.avijit.orbithealth.ring.RingTransport

/** Yucheng-protocol (YCBT) rings, e.g. the TK5. */
object YcbtFamily : RingFamily {
    /** Stored with the paired ring; also the id installs from before multi-ring support migrate to. */
    const val ID = "ycbt"

    override val id = ID
    override val displayName = "Yucheng (YCBT) rings"

    /** Commands go to be940001 (write with response); replies arrive as indications on be940001 and be940003. */
    const val SERVICE = "be940000-7333-be46-b7ae-689e71722bd5"
    val COMMAND = GattChar(SERVICE, "be940001-7333-be46-b7ae-689e71722bd5")
    val DATA = GattChar(SERVICE, "be940003-7333-be46-b7ae-689e71722bd5")

    override val gattProfile = GattProfile(
        writes = listOf(COMMAND),
        notifies = listOf(COMMAND, DATA),
        writeWithResponse = true,
        mtu = 247,
    )

    /** The vendor's SmartHealth app (needs the matching `<queries>` entry in the app manifest). */
    const val VENDOR_APP = "com.zhuoting.healthyucheng"
    override val vendorAppPackages = setOf(VENDOR_APP)
    override val vendorAppName = "SmartHealth"

    const val NAME_PREFIX = "TK5"
    private val FEE7 = RingAdvertisement.sigUuid("fee7")

    const val NAME_CONFIDENCE = 90
    /**
     * FEE7 is shared with many other devices, so a service-only match is weak (never auto-picked). The generic Heart
     * Rate service (180D) is not claimed at all: every HR strap and watch advertises it.
     */
    const val SERVICE_CONFIDENCE = 20

    override fun match(advertisement: RingAdvertisement): RingMatch? {
        val name = advertisement.name.orEmpty()
        return when {
            name.startsWith(NAME_PREFIX) -> RingMatch(NAME_PREFIX, NAME_CONFIDENCE)
            advertisement.hasService(FEE7) ->
                RingMatch(name.ifBlank { "YCBT ring" }, SERVICE_CONFIDENCE)
            else -> null
        }
    }

    override fun createDriver(transport: RingTransport): RingDriver = YcbtDriver(transport)
}
