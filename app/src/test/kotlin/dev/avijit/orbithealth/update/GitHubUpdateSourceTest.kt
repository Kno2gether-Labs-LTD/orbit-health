package dev.avijit.orbithealth.update

import dev.avijit.orbithealth.data.NetworkLogEntity
import dev.avijit.orbithealth.server.NetworkRecorder
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The GitHub client against a local HTTPS server standing in for api.github.com and its download hosts. */
class GitHubUpdateSourceTest {
    @get:Rule val tmp = TemporaryFolder()

    private val cert = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
    private val serverCerts = HandshakeCertificates.Builder().heldCertificate(cert).build()
    private val clientCerts = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
    private val web = MockWebServer()
    private val logged = mutableListOf<NetworkLogEntity>()
    private val apk = ByteArray(300_000) { (it % 251).toByte() }
    private val apkSha = MessageDigest.getInstance("SHA-256").digest(apk).joinToString("") { "%02x".format(it) }

    @Before fun setUp() {
        web.useHttps(serverCerts.sslSocketFactory(), false)
        web.start()
    }

    @After fun tearDown() = web.shutdown()

    private val source by lazy { makeSource() }

    private fun makeSource() = GitHubUpdateSource(
        http = GitHubUpdateSource.client().newBuilder().sslSocketFactory(clientCerts.sslSocketFactory(), clientCerts.trustManager).build(),
        netLog = NetworkRecorder { logged += it },
        userAgent = "OrbitHealth/0.6.0",
        downloadDir = tmp.newFolder("updates"),
        latestUrl = web.url("/repos/x/releases/latest").toString(),
        allowedHosts = { it == "localhost" },
    )

    private fun release(apkPath: String = "/dl/OrbitHealth.apk", size: Long? = apk.size.toLong()) = ReleaseInfo(
        "v0.7.1", SemVer(0, 7, 1), "Orbit Health 0.7.1", "", "", web.url(apkPath).toString(), size,
        web.url("/dl/OrbitHealth.apk.sha256").toString(),
    )

    private val releaseJson get() = """
        {"tag_name": "v0.7.1", "name": "Orbit Health 0.7.1", "body": "Notes", "html_url": "https://github.com/x",
         "draft": false, "prerelease": false, "assets": [
           {"name": "OrbitHealth.apk", "size": ${apk.size}, "browser_download_url": "${web.url("/dl/OrbitHealth.apk")}"},
           {"name": "OrbitHealth.apk.sha256", "size": 81, "browser_download_url": "${web.url("/dl/OrbitHealth.apk.sha256")}"}]}
    """.trimIndent()

    @Test fun checksSendNothingButTheAppVersion() = runBlocking {
        web.enqueue(MockResponse().setBody(releaseJson))
        val r = source.latest()!!
        assertEquals(SemVer(0, 7, 1), r.version)
        val req = web.takeRequest()
        assertEquals("/repos/x/releases/latest", req.path)
        assertEquals("OrbitHealth/0.6.0", req.getHeader("User-Agent"))
        assertEquals("application/vnd.github+json", req.getHeader("Accept"))
        assertNull(req.getHeader("Authorization"))
        assertNull(req.getHeader("Cookie"))
        val entry = logged.single()
        assertEquals("update_check", entry.purpose)
        assertEquals("localhost", entry.host)
        assertTrue(entry.ok)
        assertTrue(entry.bytesUp > 0 && entry.bytesDown > 0)
    }

    @Test fun noPublishedReleaseIsNoUpdate() = runBlocking {
        web.enqueue(MockResponse().setResponseCode(404).setBody("""{"message": "Not Found"}"""))
        assertNull(source.latest())
        web.enqueue(MockResponse().setResponseCode(500))
        try {
            source.latest(); fail()
        } catch (_: IOException) {
        }
        assertFalse(logged.last().ok)
    }

    @Test fun downloadIsVerifiedAgainstItsChecksum() = runBlocking {
        web.enqueue(MockResponse().setBody("$apkSha  OrbitHealth.apk\n"))
        web.enqueue(MockResponse().setBody(Buffer().write(apk)))
        val progress = mutableListOf<Float?>()
        val file = source.download(release()) { progress += it }
        assertArrayEquals(apk, file.readBytes())
        assertEquals(1f, progress.last())
        assertEquals(listOf("update_checksum", "update_download"), logged.map { it.purpose })
        assertTrue(logged.all { it.ok })
        assertEquals("OrbitHealth/0.6.0", web.takeRequest().getHeader("User-Agent"))
    }

    @Test fun aTamperedDownloadIsDeleted() = runBlocking {
        web.enqueue(MockResponse().setBody("${"0".repeat(64)}  OrbitHealth.apk"))
        web.enqueue(MockResponse().setBody(Buffer().write(apk)))
        val dir = tmp.root.resolve("updates")
        try {
            source.download(release()) {}
            fail()
        } catch (e: UpdateFailure) {
            assertEquals(GitHubUpdateSource.CHECKSUM_MISMATCH, e.message)
        }
        assertTrue(dir.listFiles().isNullOrEmpty())
    }

    @Test fun anUnreadableChecksumStopsBeforeDownloading() = runBlocking {
        web.enqueue(MockResponse().setBody("<html>not a checksum</html>"))
        try {
            source.download(release()) {}
            fail()
        } catch (e: UpdateFailure) {
            assertEquals(GitHubUpdateSource.BAD_CHECKSUM_FILE, e.message)
        }
        assertEquals(1, web.requestCount)
    }

    @Test fun redirectsMayNotLeaveGitHubOrDropToHttp() = runBlocking {
        web.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://example.com/latest"))
        try {
            source.latest(); fail()
        } catch (_: IOException) {
        }
        web.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://localhost:${web.port}/latest"))
        try {
            source.latest(); fail()
        } catch (_: IOException) {
        }
        assertEquals(2, web.requestCount)
    }

    @Test fun assetsOutsideGitHubAreNeverFetched() = runBlocking {
        val elsewhere = release().copy(sha256Url = "https://example.com/OrbitHealth.apk.sha256")
        try {
            source.download(elsewhere) {}
            fail()
        } catch (e: UpdateFailure) {
            assertEquals(GitHubUpdateSource.BAD_URL, e.message)
        }
        assertEquals(0, web.requestCount)
    }

    @Test fun githubHostsAreRecognised() {
        for (h in listOf("api.github.com", "github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com")) {
            assertTrue(h, GitHubUpdateSource.isGitHubHost(h))
        }
        for (h in listOf("github.com.evil.example", "evilgithub.com", "githubusercontent.com.example", "gist.github.io")) {
            assertFalse(h, GitHubUpdateSource.isGitHubHost(h))
        }
    }
}
