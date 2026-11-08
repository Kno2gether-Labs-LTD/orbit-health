package dev.avijit.orbithealth.update

import java.io.File
import java.io.IOException
import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Where the update check's own memory lives (the settings DataStore in the app). */
interface UpdatePrefs {
    val autoCheck: Flow<Boolean>
    suspend fun setAutoCheck(on: Boolean)
    suspend fun lastCheckMs(): Long?
    suspend fun lastSeenVersion(): String?
    /** Records a check at [atMs]; [seenVersion] is the latest release's version, or null when there was none. */
    suspend fun recordCheck(atMs: Long, seenVersion: String?)
}

/** The latest published release; null when there is none this app can install. Throws [IOException] when offline. */
fun interface ReleaseSource {
    suspend fun latest(): ReleaseInfo?
}

/** Downloads [release]'s APK and checks it against its published SHA-256; throws [UpdateFailure] or [IOException]. */
fun interface ApkDownloader {
    suspend fun download(release: ReleaseInfo, onProgress: (Float?) -> Unit): File
}

/** The Android side: package facts, the "install unknown apps" permission and the installer session. */
interface UpdatePlatform {
    fun installedFacts(): ApkFacts
    fun inspect(apk: File): ApkFacts?
    fun canInstall(): Boolean
    fun openInstallPermission()
    /** Hands [apk] to the system installer; the outcome arrives through [UpdateManager.onInstallResult]. */
    suspend fun install(apk: File)
}

/** Where an update stands. */
sealed interface UpdatePhase {
    data object Idle : UpdatePhase
    data object Checking : UpdatePhase
    data object UpToDate : UpdatePhase
    /** A newer version; [release] is null when it is only remembered from an earlier check (fetched again on Update). */
    data class Available(val version: SemVer, val release: ReleaseInfo?) : UpdatePhase
    data class Downloading(val release: ReleaseInfo, val fraction: Float?) : UpdatePhase
    data class Verifying(val release: ReleaseInfo) : UpdatePhase
    data class Installing(val release: ReleaseInfo) : UpdatePhase
    data class NeedsPermission(val release: ReleaseInfo) : UpdatePhase
    data class Failed(val message: String, val release: ReleaseInfo? = null) : UpdatePhase
}

data class UpdateUiState(
    val currentVersion: String = "",
    /** False on debug builds: they are signed with the debug key, so a release can't install over them. */
    val installsHere: Boolean = true,
    val autoCheck: Boolean = true,
    val phase: UpdatePhase = UpdatePhase.Idle,
    val lastCheckedAt: Instant? = null,
) {
    /** The release in view, if any (for its notes). */
    val release: ReleaseInfo?
        get() = when (val p = phase) {
            is UpdatePhase.Available -> p.release
            is UpdatePhase.Downloading -> p.release
            is UpdatePhase.Verifying -> p.release
            is UpdatePhase.Installing -> p.release
            is UpdatePhase.NeedsPermission -> p.release
            is UpdatePhase.Failed -> p.release
            else -> null
        }
}

/** How the system installer finished. */
enum class InstallResult { SUCCESS, CANCELLED, FAILED }

/**
 * In-app updates from GitHub Releases: checks (automatically at most once a day where updates can install, or when
 * asked), downloads the APK, verifies its SHA-256 and that it is a newer Orbit Health signed by the same key, then hands
 * it to the system installer. Nothing is installed without the system's own confirmation.
 */
class UpdateManager(
    private val currentVersion: String,
    private val installsHere: Boolean,
    private val prefs: UpdatePrefs,
    private val source: ReleaseSource,
    private val downloader: ApkDownloader,
    private val platform: UpdatePlatform,
    private val clock: Clock,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(UpdateUiState(currentVersion = currentVersion, installsHere = installsHere))
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()
    private val lock = Mutex()
    private var job: Job? = null

    init {
        scope.launch {
            val last = prefs.lastCheckMs()?.let(Instant::ofEpochMilli)
            val seen = prefs.lastSeenVersion()?.let(SemVer::parse)
            val current = SemVer.parse(currentVersion)
            _state.update { s ->
                val phase = if (s.phase == UpdatePhase.Idle && seen != null && current != null && seen > current) {
                    UpdatePhase.Available(seen, null)
                } else s.phase
                s.copy(lastCheckedAt = s.lastCheckedAt ?: last, phase = phase)
            }
        }
        scope.launch { prefs.autoCheck.collect { on -> _state.update { it.copy(autoCheck = on) } } }
    }

    /** On app start: a quiet check when it is due. A failure here leaves no error on screen. */
    suspend fun autoCheckIfDue() {
        val due = UpdatePolicy.autoCheckDue(clock.millis(), prefs.lastCheckMs(), prefs.autoCheck.first(), installsHere)
        if (due) check(quiet = true)
    }

    /** "Check for updates". */
    fun checkNow() = launchExclusive { check(quiet = false) }

    fun setAutoCheck(on: Boolean) {
        scope.launch { prefs.setAutoCheck(on) }
    }

    fun openInstallPermission() = platform.openInstallPermission()

    /** "Update": fetch (if needed), download, verify, install. */
    fun update() = launchExclusive {
        if (!installsHere) return@launchExclusive
        val release = _state.value.release ?: run {
            check(quiet = false)
            _state.value.release
        } ?: return@launchExclusive
        if (!platform.canInstall()) {
            setPhase(UpdatePhase.NeedsPermission(release))
            return@launchExclusive
        }
        try {
            setPhase(UpdatePhase.Downloading(release, 0f))
            val apk = downloader.download(release) { f -> setPhase(UpdatePhase.Downloading(release, f)) }
            setPhase(UpdatePhase.Verifying(release))
            ApkCheck.problem(platform.inspect(apk), platform.installedFacts())?.let { problem ->
                apk.delete()
                throw UpdateFailure(problem)
            }
            setPhase(UpdatePhase.Installing(release))
            platform.install(apk)
        } catch (e: CancellationException) {
            throw e
        } catch (e: UpdateFailure) {
            setPhase(UpdatePhase.Failed(e.message ?: GENERIC, release))
        } catch (e: IOException) {
            setPhase(UpdatePhase.Failed(DOWNLOAD_FAILED, release))
        } catch (e: Exception) {
            setPhase(UpdatePhase.Failed(GENERIC, release))
        }
    }

    /** From the installer's status broadcast. */
    fun onInstallResult(result: InstallResult, detail: String? = null) {
        val release = _state.value.release
        setPhase(
            when (result) {
                InstallResult.SUCCESS -> UpdatePhase.UpToDate
                InstallResult.CANCELLED -> release?.let { UpdatePhase.Available(it.version, it) } ?: UpdatePhase.Idle
                InstallResult.FAILED -> UpdatePhase.Failed(
                    "Android couldn't install the update" + (detail?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "."), release,
                )
            },
        )
    }

    private suspend fun check(quiet: Boolean) {
        val before = _state.value.phase
        if (!quiet) setPhase(UpdatePhase.Checking)
        val now = clock.millis()
        try {
            val latest = source.latest()
            prefs.recordCheck(now, latest?.version?.toString())
            _state.update { it.copy(lastCheckedAt = Instant.ofEpochMilli(now)) }
            setPhase(
                when (val d = UpdateDecision.of(latest, currentVersion)) {
                    is UpdateDecision.Available -> UpdatePhase.Available(d.release.version, d.release)
                    UpdateDecision.UpToDate -> UpdatePhase.UpToDate
                },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (quiet) {
                // Counted as this day's check, so a phone without a connection doesn't retry on every start.
                runCatching { prefs.recordCheck(now, prefs.lastSeenVersion()) }
                setPhase(before)
            } else {
                setPhase(UpdatePhase.Failed(CHECK_FAILED))
            }
        }
    }

    private fun setPhase(p: UpdatePhase) = _state.update { it.copy(phase = p) }

    /** One check or update at a time; a second tap while one runs does nothing. */
    private fun launchExclusive(block: suspend () -> Unit): Job? {
        if (job?.isActive == true) return null
        return scope.launch { lock.withLock { block() } }.also { job = it }
    }

    companion object {
        const val CHECK_FAILED = "Couldn't reach GitHub. Check your connection and try again."
        const val DOWNLOAD_FAILED = "The download stopped before it finished. Try again on a steadier connection."
        const val GENERIC = "Something went wrong with the update. Try again later."
    }
}
