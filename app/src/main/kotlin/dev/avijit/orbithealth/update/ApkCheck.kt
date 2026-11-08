package dev.avijit.orbithealth.update

/**
 * What matters about an APK for an update: its package, version code and signing certificates (SHA-256 of each
 * certificate, lowercase hex). [pastSigners] is the signing-certificate lineage the APK proves (key rotation).
 */
data class ApkFacts(
    val packageName: String,
    val versionCode: Long,
    val signers: Set<String>,
    val pastSigners: Set<String> = emptySet(),
)

/** A step of the update that failed, with words for the user. */
class UpdateFailure(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Checks a downloaded APK against the installed app before it goes anywhere near the installer. Pure. */
object ApkCheck {
    const val UNREADABLE = "The download isn't a readable app, so it wasn't installed."
    const val OTHER_APP = "The download is a different app, not Orbit Health, so it wasn't installed."
    const val NOT_NEWER = "The download isn't newer than the version you have, so it wasn't installed."
    const val UNSIGNED = "The download isn't signed, so it wasn't installed."
    const val OTHER_SIGNER = "The download isn't signed by Orbit Health's developers, so it wasn't installed."

    /** Null when [candidate] may replace [installed]; otherwise why not. */
    fun problem(candidate: ApkFacts?, installed: ApkFacts): String? = when {
        candidate == null -> UNREADABLE
        candidate.packageName != installed.packageName -> OTHER_APP
        candidate.versionCode <= installed.versionCode -> NOT_NEWER
        candidate.signers.isEmpty() -> UNSIGNED
        !sameSigner(candidate, installed) -> OTHER_SIGNER
        else -> null
    }

    /** The same signer set, or a rotated key whose proven lineage includes the installed signer. */
    private fun sameSigner(candidate: ApkFacts, installed: ApkFacts): Boolean =
        installed.signers.isNotEmpty() &&
            (candidate.signers == installed.signers || (installed.signers.size == 1 && installed.signers.single() in candidate.pastSigners))
}

/** When the automatic check may run. Pure. */
object UpdatePolicy {
    const val INTERVAL_MS = 24 * 3_600_000L

    /** At most once per [INTERVAL_MS], only where updates can install, and only when the user leaves it on. */
    fun autoCheckDue(nowMs: Long, lastCheckMs: Long?, enabled: Boolean, installsHere: Boolean): Boolean =
        installsHere && enabled && (lastCheckMs == null || nowMs - lastCheckMs >= INTERVAL_MS || nowMs < lastCheckMs)
}
