package dev.avijit.orbithealth.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseParserTest {
    private fun release(
        tag: String = "v0.7.1",
        draft: Boolean = false,
        prerelease: Boolean = false,
        assets: String = APK_ASSETS,
        body: String = "## What's new\\n- Calmer sleep chart\\n- Fixes",
    ) = """
        {
          "url": "https://api.github.com/repos/Kno2gether-Labs-LTD/orbit-health/releases/1",
          "html_url": "https://github.com/Kno2gether-Labs-LTD/orbit-health/releases/tag/$tag",
          "tag_name": "$tag",
          "name": "Orbit Health ${tag.removePrefix("v")}",
          "draft": $draft,
          "prerelease": $prerelease,
          "body": "$body",
          "author": {"login": "someone"},
          "assets": $assets
        }
    """.trimIndent()

    @Test fun parsesTheLatestRelease() {
        val r = ReleaseParser.parse(release())!!
        assertEquals("v0.7.1", r.tag)
        assertEquals(SemVer(0, 7, 1), r.version)
        assertEquals("Orbit Health 0.7.1", r.name)
        assertEquals("## What's new\n- Calmer sleep chart\n- Fixes", r.notes)
        assertEquals("https://github.com/Kno2gether-Labs-LTD/orbit-health/releases/tag/v0.7.1", r.htmlUrl)
        assertEquals("https://github.com/Kno2gether-Labs-LTD/orbit-health/releases/download/v0.7.1/OrbitHealth.apk", r.apkUrl)
        assertEquals(12_345_678L, r.apkSize)
        assertEquals("https://github.com/Kno2gether-Labs-LTD/orbit-health/releases/download/v0.7.1/OrbitHealth.apk.sha256", r.sha256Url)
    }

    @Test fun draftsPrereleasesAndOddTagsAreNotUpdates() {
        assertNull(ReleaseParser.parse(release(draft = true)))
        assertNull(ReleaseParser.parse(release(prerelease = true)))
        assertNull(ReleaseParser.parse(release(tag = "nightly")))
        assertNull(ReleaseParser.parse(release(tag = "v0.7.1-rc.1")))
    }

    @Test fun aReleaseWithoutBothAssetsIsNotInstallable() {
        val onlyApk = """[{"name": "OrbitHealth.apk", "size": 10, "browser_download_url": "https://github.com/x/OrbitHealth.apk"}]"""
        assertNull(ReleaseParser.parse(release(assets = onlyApk)))
        assertNull(ReleaseParser.parse(release(assets = "[]")))
    }

    @Test fun assetUrlsMustBeHttps() {
        val http = APK_ASSETS.replace("https://github.com", "http://github.com")
        assertNull(ReleaseParser.parse(release(assets = http)))
    }

    @Test fun malformedJsonIsNoUpdate() {
        assertNull(ReleaseParser.parse(""))
        assertNull(ReleaseParser.parse("not json"))
        assertNull(ReleaseParser.parse("""{"message": "Not Found"}"""))
        assertNull(ReleaseParser.parse("[]"))
    }

    @Test fun newerOnlyWhenTheVersionIsHigher() {
        val r = ReleaseParser.parse(release())!!
        assertTrue(UpdateDecision.of(r, "0.6.0") is UpdateDecision.Available)
        assertTrue(UpdateDecision.of(r, "0.7.1") is UpdateDecision.UpToDate)
        assertTrue(UpdateDecision.of(r, "0.8.0") is UpdateDecision.UpToDate)
        assertTrue(UpdateDecision.of(null, "0.6.0") is UpdateDecision.UpToDate)
        // A build whose own version can't be read never offers an update.
        assertTrue(UpdateDecision.of(r, "dev") is UpdateDecision.UpToDate)
    }

    @Test fun semanticVersionsCompareByNumberNotText() {
        assertEquals(SemVer(1, 2, 3), SemVer.parse("v1.2.3"))
        assertEquals(SemVer(1, 2, 0), SemVer.parse("1.2"))
        assertEquals(SemVer(0, 10, 0), SemVer.parse("V0.10.0"))
        assertTrue(SemVer.parse("0.10.0")!! > SemVer.parse("0.9.9")!!)
        assertTrue(SemVer.parse("1.0.0")!! > SemVer.parse("0.99.99")!!)
        assertTrue(SemVer.parse("0.6.1")!! > SemVer.parse("0.6.0")!!)
        assertEquals("0.7.1", SemVer(0, 7, 1).toString())
        for (bad in listOf("", "v", "1", "1.2.3.4", "1.x.0", "-1.0.0", "1.2.3-beta", "1..2", " 1.2.3", "99999999999.0.0")) {
            assertNull(bad, SemVer.parse(bad))
        }
    }

    @Test fun checksumFileGivesTheHexDigest() {
        val hex = "a".repeat(64)
        assertEquals(hex, Sha256.parseExpected(hex))
        assertEquals(hex, Sha256.parseExpected("${hex.uppercase()}  OrbitHealth.apk\n"))
        assertEquals(hex, Sha256.parseExpected("$hex *OrbitHealth.apk"))
        assertNull(Sha256.parseExpected(""))
        assertNull(Sha256.parseExpected("abc OrbitHealth.apk"))
        assertNull(Sha256.parseExpected("g".repeat(64)))
    }

    companion object {
        private const val DL = "https://github.com/Kno2gether-Labs-LTD/orbit-health/releases/download/v0.7.1"
        val APK_ASSETS = """
            [
              {"name": "OrbitHealth.apk", "size": 12345678, "content_type": "application/vnd.android.package-archive",
               "browser_download_url": "$DL/OrbitHealth.apk"},
              {"name": "OrbitHealth.apk.sha256", "size": 81, "browser_download_url": "$DL/OrbitHealth.apk.sha256"}
            ]
        """.trimIndent()
    }
}
