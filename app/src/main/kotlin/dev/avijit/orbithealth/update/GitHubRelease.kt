package dev.avijit.orbithealth.update

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A plain release version, `MAJOR.MINOR.PATCH` (PATCH may be omitted). Pre-release suffixes are not versions here. */
data class SemVer(val major: Int, val minor: Int, val patch: Int) : Comparable<SemVer> {
    override fun compareTo(other: SemVer): Int = compareValuesBy(this, other, SemVer::major, SemVer::minor, SemVer::patch)
    override fun toString() = "$major.$minor.$patch"

    companion object {
        private val PATTERN = Regex("""[vV]?(\d{1,9})\.(\d{1,9})(?:\.(\d{1,9}))?""")

        /** "v0.7.1", "0.7.1" or "0.7"; anything else (pre-releases, words, extra parts) is null. */
        fun parse(text: String): SemVer? {
            val m = PATTERN.matchEntire(text) ?: return null
            return SemVer(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].ifEmpty { "0" }.toInt())
        }
    }
}

/** The parts of a published GitHub release an update needs. */
data class ReleaseInfo(
    val tag: String,
    val version: SemVer,
    val name: String,
    /** The release notes as written on GitHub (Markdown). */
    val notes: String,
    val htmlUrl: String,
    val apkUrl: String,
    /** The APK's size as GitHub reports it; null when absent. */
    val apkSize: Long?,
    val sha256Url: String,
)

/** Reads GitHub's `releases/latest` answer. Pure: unit tested with canned JSON. */
object ReleaseParser {
    const val APK_ASSET = "OrbitHealth.apk"
    const val SHA_ASSET = "OrbitHealth.apk.sha256"

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; isLenient = false }

    @Serializable
    private class Asset(
        val name: String = "",
        val size: Long? = null,
        @SerialName("browser_download_url") val url: String = "",
    )

    @Serializable
    private class Release(
        @SerialName("tag_name") val tag: String? = null,
        val name: String? = null,
        val body: String? = null,
        @SerialName("html_url") val htmlUrl: String? = null,
        val draft: Boolean = false,
        val prerelease: Boolean = false,
        val assets: List<Asset> = emptyList(),
    )

    /**
     * The release in [text], or null when it is not something this app can install: malformed JSON, a draft or
     * pre-release, a tag that is not a plain version, or missing either asset (or one not served over HTTPS).
     */
    fun parse(text: String): ReleaseInfo? {
        val r = try {
            json.decodeFromString(Release.serializer(), text)
        } catch (e: Exception) {
            return null
        }
        if (r.draft || r.prerelease) return null
        val tag = r.tag ?: return null
        val version = SemVer.parse(tag) ?: return null
        val apk = r.assets.firstOrNull { it.name == APK_ASSET }?.takeIf { it.url.isHttps() } ?: return null
        val sha = r.assets.firstOrNull { it.name == SHA_ASSET }?.takeIf { it.url.isHttps() } ?: return null
        return ReleaseInfo(
            tag = tag,
            version = version,
            name = r.name?.takeIf { it.isNotBlank() } ?: "Orbit Health $version",
            notes = r.body.orEmpty().replace("\r\n", "\n").trim(),
            htmlUrl = r.htmlUrl?.takeIf { it.isHttps() } ?: "",
            apkUrl = apk.url,
            apkSize = apk.size?.takeIf { it > 0 },
            sha256Url = sha.url,
        )
    }

    private fun String.isHttps() = startsWith("https://")
}

/** Whether a fetched release is newer than the running build. */
sealed interface UpdateDecision {
    data object UpToDate : UpdateDecision
    data class Available(val release: ReleaseInfo) : UpdateDecision

    companion object {
        /** [release] is an update only when both versions read as plain versions and it is strictly higher. */
        fun of(release: ReleaseInfo?, currentVersion: String): UpdateDecision {
            val current = SemVer.parse(currentVersion) ?: return UpToDate
            return if (release != null && release.version > current) Available(release) else UpToDate
        }
    }
}

/** SHA-256 helpers for the downloaded APK. */
object Sha256 {
    private val HEX64 = Regex("[0-9a-fA-F]{64}")

    /** The digest in a `.sha256` file: the first word, 64 hex digits (`sha256sum` style, filename optional). */
    fun parseExpected(text: String): String? =
        text.trim().split(Regex("\\s+")).firstOrNull()?.takeIf { HEX64.matches(it) }?.lowercase()

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    fun ofFile(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return hex(md.digest())
    }
}
