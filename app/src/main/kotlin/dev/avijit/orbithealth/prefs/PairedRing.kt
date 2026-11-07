package dev.avijit.orbithealth.prefs

/**
 * The paired ring: which [dev.avijit.orbithealth.ring.RingFamily] (by id) re-opens it, its BLE address, and the model
 * name the family matched at pairing (null for installs paired before multi-ring support).
 */
data class PairedRing(val familyId: String, val address: String, val modelName: String? = null) {
    companion object {
        /**
         * Family of rings paired before the family id was stored: the app only supported YCBT then.
         * Equal to `YcbtFamily.ID` (kept as a literal so prefs don't depend on a ring module).
         */
        const val LEGACY_FAMILY_ID = "ycbt"

        /** The paired ring from stored fields; an address without a family id migrates to [LEGACY_FAMILY_ID]. */
        fun fromStored(address: String?, familyId: String?, modelName: String?): PairedRing? =
            address?.let { PairedRing(familyId ?: LEGACY_FAMILY_ID, it, modelName) }
    }
}
