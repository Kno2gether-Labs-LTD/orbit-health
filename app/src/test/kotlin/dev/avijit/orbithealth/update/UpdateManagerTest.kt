package dev.avijit.orbithealth.update

import java.io.File
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateManagerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val now = Instant.parse("2026-11-07T10:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val release = ReleaseInfo(
        "v0.7.1", SemVer(0, 7, 1), "Orbit Health 0.7.1", "- Calmer sleep chart",
        "https://github.com/x/releases/tag/v0.7.1", "https://github.com/x/OrbitHealth.apk", 10, "https://github.com/x/OrbitHealth.apk.sha256",
    )
    private val installed = ApkFacts("dev.example.app", 5, setOf("aa"))

    private class FakePrefs(var last: Long? = null, var seen: String? = null, auto: Boolean = true) : UpdatePrefs {
        override val autoCheck = MutableStateFlow(auto)
        var checks = 0
        override suspend fun setAutoCheck(on: Boolean) { autoCheck.value = on }
        override suspend fun lastCheckMs() = last
        override suspend fun lastSeenVersion() = seen
        override suspend fun recordCheck(atMs: Long, seenVersion: String?) { checks++; last = atMs; seen = seenVersion }
    }

    private inner class FakePlatform(var allowed: Boolean = true, var candidate: ApkFacts? = ApkFacts("dev.example.app", 6, setOf("aa"))) : UpdatePlatform {
        val installedFiles = mutableListOf<File>()
        var permissionOpened = 0
        override fun installedFacts() = installed
        override fun inspect(apk: File) = candidate
        override fun canInstall() = allowed
        override fun openInstallPermission() { permissionOpened++ }
        override suspend fun install(apk: File) { installedFiles += apk }
    }

    private var fetches = 0
    private var latest: () -> ReleaseInfo? = { release }
    private var download: () -> File = { tmp.newFile("OrbitHealth.apk") }

    private fun TestScope.manager(prefs: FakePrefs = FakePrefs(), platform: UpdatePlatform = FakePlatform(), installsHere: Boolean = true, version: String = "0.6.0") =
        UpdateManager(
            version, installsHere, prefs, { fetches++; latest() }, { _, progress -> progress(0.5f); download() }, platform, clock,
            backgroundScope,
        )

    @Test fun autoCheckRunsOncePerDayOnlyWhereUpdatesInstall() {
        val day = UpdatePolicy.INTERVAL_MS
        val t = 10 * day
        assertTrue(UpdatePolicy.autoCheckDue(t, null, enabled = true, installsHere = true))
        assertFalse(UpdatePolicy.autoCheckDue(t, t - day + 1, enabled = true, installsHere = true))
        assertTrue(UpdatePolicy.autoCheckDue(t, t - day, enabled = true, installsHere = true))
        assertFalse(UpdatePolicy.autoCheckDue(t, null, enabled = false, installsHere = true))
        assertFalse(UpdatePolicy.autoCheckDue(t, null, enabled = true, installsHere = false))
        // A clock set back doesn't block checks forever.
        assertTrue(UpdatePolicy.autoCheckDue(t, t + day, enabled = true, installsHere = true))
    }

    @Test fun apkMustBeNewerOrbitHealthFromTheSameKey() {
        assertNull(ApkCheck.problem(ApkFacts("dev.example.app", 6, setOf("aa")), installed))
        assertEquals(ApkCheck.UNREADABLE, ApkCheck.problem(null, installed))
        assertEquals(ApkCheck.OTHER_APP, ApkCheck.problem(ApkFacts("com.other", 6, setOf("aa")), installed))
        assertEquals(ApkCheck.NOT_NEWER, ApkCheck.problem(ApkFacts("dev.example.app", 5, setOf("aa")), installed))
        assertEquals(ApkCheck.UNSIGNED, ApkCheck.problem(ApkFacts("dev.example.app", 6, emptySet()), installed))
        assertEquals(ApkCheck.OTHER_SIGNER, ApkCheck.problem(ApkFacts("dev.example.app", 6, setOf("bb")), installed))
        // A rotated key that proves the old one is fine.
        assertNull(ApkCheck.problem(ApkFacts("dev.example.app", 6, setOf("bb"), pastSigners = setOf("aa", "bb")), installed))
    }

    @Test fun autoCheckIsDailyAndQuiet() = runTest(UnconfinedTestDispatcher()) {
        val prefs = FakePrefs(last = now.toEpochMilli() - 3_600_000)
        val m = manager(prefs)
        m.autoCheckIfDue()
        assertEquals(0, fetches)
        prefs.last = now.toEpochMilli() - UpdatePolicy.INTERVAL_MS
        m.autoCheckIfDue()
        assertEquals(1, fetches)
        assertEquals("0.7.1", prefs.seen)
        assertEquals(UpdatePhase.Available(release.version, release), m.state.value.phase)

        // Offline: no error on screen, and it still counts as today's check.
        latest = { throw IOException("offline") }
        prefs.last = 0
        val m2 = manager(prefs)
        advanceUntilIdle()
        m2.autoCheckIfDue()
        assertFalse(m2.state.value.phase is UpdatePhase.Failed)
        assertEquals(now.toEpochMilli(), prefs.last)
    }

    @Test fun debugBuildsSkipTheAutomaticCheckAndNeverInstall() = runTest(UnconfinedTestDispatcher()) {
        val platform = FakePlatform()
        val m = manager(platform = platform, installsHere = false)
        m.autoCheckIfDue()
        assertEquals(0, fetches)
        m.checkNow()
        assertTrue(m.state.value.phase is UpdatePhase.Available)
        m.update()
        assertTrue(platform.installedFiles.isEmpty())
    }

    @Test fun turnedOffMeansNoAutomaticCheck() = runTest(UnconfinedTestDispatcher()) {
        val m = manager(FakePrefs(auto = false))
        m.autoCheckIfDue()
        assertEquals(0, fetches)
        m.setAutoCheck(true)
        assertTrue(m.state.value.autoCheck)
    }

    @Test fun manualCheckReportsUpToDateOrAFailure() = runTest(UnconfinedTestDispatcher()) {
        val m = manager(version = "0.7.1")
        m.checkNow()
        assertEquals(UpdatePhase.UpToDate, m.state.value.phase)
        assertEquals(now, m.state.value.lastCheckedAt)
        latest = { throw IOException("offline") }
        m.checkNow()
        assertEquals(UpdatePhase.Failed(UpdateManager.CHECK_FAILED), m.state.value.phase)
    }

    @Test fun aVersionSeenEarlierShowsBeforeAnyNetwork() = runTest(UnconfinedTestDispatcher()) {
        val m = manager(FakePrefs(last = 1, seen = "0.7.1"))
        assertEquals(UpdatePhase.Available(SemVer(0, 7, 1), null), m.state.value.phase)
        assertEquals(0, fetches)
    }

    @Test fun updateDownloadsVerifiesAndInstalls() = runTest(UnconfinedTestDispatcher()) {
        val platform = FakePlatform()
        val m = manager(platform = platform)
        m.checkNow()
        m.update()
        assertEquals(1, platform.installedFiles.size)
        assertEquals(UpdatePhase.Installing(release), m.state.value.phase)
        m.onInstallResult(InstallResult.CANCELLED)
        assertEquals(UpdatePhase.Available(release.version, release), m.state.value.phase)
        m.onInstallResult(InstallResult.FAILED, "INSTALL_FAILED_UPDATE_INCOMPATIBLE")
        assertTrue((m.state.value.phase as UpdatePhase.Failed).message.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE"))
    }

    @Test fun aRememberedVersionIsFetchedAgainBeforeUpdating() = runTest(UnconfinedTestDispatcher()) {
        val platform = FakePlatform()
        val m = manager(FakePrefs(last = 1, seen = "0.7.1"), platform)
        m.update()
        assertEquals(1, fetches)
        assertEquals(1, platform.installedFiles.size)
    }

    @Test fun withoutInstallPermissionTheUserIsSentToSettings() = runTest(UnconfinedTestDispatcher()) {
        val platform = FakePlatform(allowed = false)
        val m = manager(platform = platform)
        m.checkNow()
        m.update()
        assertEquals(UpdatePhase.NeedsPermission(release), m.state.value.phase)
        assertTrue(platform.installedFiles.isEmpty())
        m.openInstallPermission()
        assertEquals(1, platform.permissionOpened)
        platform.allowed = true
        m.update()
        assertEquals(1, platform.installedFiles.size)
    }

    @Test fun aBadDownloadIsRejectedWithAClearMessage() = runTest(UnconfinedTestDispatcher()) {
        val platform = FakePlatform(candidate = ApkFacts("dev.example.app", 6, setOf("bb")))
        val m = manager(platform = platform)
        m.checkNow()
        m.update()
        assertEquals(UpdatePhase.Failed(ApkCheck.OTHER_SIGNER, release), m.state.value.phase)
        assertTrue(platform.installedFiles.isEmpty())

        download = { throw UpdateFailure(GitHubUpdateSource.CHECKSUM_MISMATCH) }
        m.update()
        assertEquals(UpdatePhase.Failed(GitHubUpdateSource.CHECKSUM_MISMATCH, release), m.state.value.phase)

        download = { throw IOException("reset") }
        m.update()
        assertEquals(UpdatePhase.Failed(UpdateManager.DOWNLOAD_FAILED, release), m.state.value.phase)
    }
}
