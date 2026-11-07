package dev.avijit.orbithealth.ui.components

/**
 * Coexistence copy naming the ring vendor's own app ([dev.avijit.orbithealth.ring.RingFamily.vendorAppName], e.g.
 * "SmartHealth" for YCBT rings). When the name is unknown it says "your ring's own app".
 */
object VendorAppCopy {
    const val FALLBACK = "Your ring's own app"

    /** The Today banner's title: "SmartHealth detected". */
    fun bannerTitle(name: String?): String = "${name ?: FALLBACK} detected"

    /** The Today banner's body (spec §5.13). */
    fun bannerBody(name: String?): String =
        "${name ?: FALLBACK} is also installed. Both apps share your ring, so syncing may be slower and " +
            "${name ?: "that app"} may upload your data to its servers. For the best experience, uninstall it."

    /** Me's "Keep connected" caption in COEXISTENCE. */
    fun keepConnectedCaption(name: String?): String =
        "Uninstall ${name ?: FALLBACK.replaceFirstChar { it.lowercase() }} to keep your ring connected"
}
