package dev.avijit.orbithealth

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dev.avijit.orbithealth.ble.AppTransportProvider
import dev.avijit.orbithealth.ble.BridgeCode
import dev.avijit.orbithealth.ble.TransportProvider
import dev.avijit.orbithealth.data.OrbitDatabase
import dev.avijit.orbithealth.data.SampleRepository
import dev.avijit.orbithealth.data.SleepEstimateRepository
import dev.avijit.orbithealth.prefs.Settings
import dev.avijit.orbithealth.prefs.PairedRing
import dev.avijit.orbithealth.ring.DeviceInfo
import dev.avijit.orbithealth.ring.RingRegistry
import dev.avijit.orbithealth.ycbt.YcbtFamily
import dev.avijit.orbithealth.server.KeystoreSecretBox
import dev.avijit.orbithealth.server.PairedServer
import dev.avijit.orbithealth.server.PinnedTls
import dev.avijit.orbithealth.server.PublicCaTls
import dev.avijit.orbithealth.server.serverHttpFor
import dev.avijit.orbithealth.server.RepositorySyncSource
import dev.avijit.orbithealth.server.RoomNetworkRecorder
import dev.avijit.orbithealth.server.ServerClient
import dev.avijit.orbithealth.server.ServerRepository
import dev.avijit.orbithealth.server.ServerStore
import dev.avijit.orbithealth.server.ServerSync
import dev.avijit.orbithealth.server.SettingsSyncResultStore
import dev.avijit.orbithealth.sync.DeviceInfoDto
import dev.avijit.orbithealth.security.AppLock
import dev.avijit.orbithealth.sync.BatchStore
import dev.avijit.orbithealth.sync.CoexistenceDetector
import dev.avijit.orbithealth.sync.monitoringPrefs
import dev.avijit.orbithealth.sync.RingConnection
import dev.avijit.orbithealth.sync.SyncEngine
import dev.avijit.orbithealth.sync.SyncMode
import dev.avijit.orbithealth.sync.SyncState
import dev.avijit.orbithealth.sync.TransportKind
import dev.avijit.orbithealth.sync.syncModeFor
import java.io.File
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import okhttp3.OkHttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Manual DI: one instance per process, created in [OrbitApp]. */
class AppGraph(context: Context, transportOverride: TransportProvider? = null) {
    private val app = context.applicationContext
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * The Mac bridge code for this process: made when "Use Mac bridge" is turned on, shown in Developer options, never
     * stored. Without it the bridge is never used (see [AppTransportProvider]).
     */
    val bridgeCode = MutableStateFlow<String?>(null)
    private val transports: TransportProvider = transportOverride ?: AppTransportProvider(
        bridgeCode = { bridgeCode.value },
        // A failed handshake spent this code (its proof could be guessed from offline): show a new one.
        retireCode = { spent -> if (bridgeCode.compareAndSet(spent, BridgeCode.generate())) Log.i("OrbitBridge", "Mac bridge handshake failed: new code shown in Developer options") },
    )

    /**
     * Every ring family this build supports. To add a ring: add its module (implementing RingFamily), append its
     * family here, and add a manifest `<queries><package …/></queries>` line for each of its
     * [dev.avijit.orbithealth.ring.RingFamily.vendorAppPackages]. Scanning, pairing, transport, coexistence, the
     * clear gate (RingCapabilities.canClearHistory) and sync then go through the registry and the family.
     */
    val ringRegistry = RingRegistry(listOf(YcbtFamily))

    /** The single encrypted database for the process. Opened lazily (off the main thread by its first user). */
    val database: OrbitDatabase by lazy { OrbitDatabase.open(app) }
    val repository: SampleRepository by lazy { SampleRepository(database) }
    /** Sleep the ring missed, estimated on the phone (never sent to the server). */
    val sleepEstimates: SleepEstimateRepository by lazy { SleepEstimateRepository(database, repository, Clock.systemUTC()) }
    val settings = Settings(app)
    /** Vendor apps that force COEXISTENCE: the paired family's (every family's until settings are read / nothing is paired). */
    private val vendorPackages = MutableStateFlow(ringRegistry.vendorAppPackagesFor(null))
    val coexistence = CoexistenceDetector(app, vendorPackages)
    /**
     * The vendor app's name for coexistence copy: that of the installed vendor app's family, else the paired ring's
     * family (e.g. "SmartHealth" for a YCBT ring); null when neither names one ("your ring's own app").
     */
    val vendorAppName: Flow<String?> by lazy {
        combine(coexistence.changes, settings.values.map { it.pairedRing?.familyId }.distinctUntilChanged()) { _, family ->
            ringRegistry.vendorAppName(coexistence.installedVendorPackage()) ?: family?.let(ringRegistry::family)?.vendorAppName
        }.distinctUntilChanged()
    }
    /** UI-only lock (the DB key stays usable while locked so background sync keeps working; see [AppLock]). */
    val appLock = AppLock(settings, appScope)

    /** Transport currently selected; null until settings have been read, which counts as COEXISTENCE. */
    @Volatile var transportKind: TransportKind? = null
        private set

    /** Debug bridge → always COEXISTENCE; unknown transport → COEXISTENCE; else depends on the vendor app. */
    fun mode(): SyncMode =
        transportKind?.let { syncModeFor(it, coexistence.vendorAppInstalled()) } ?: SyncMode.COEXISTENCE

    /** [mode] as a flow: re-evaluated when the transport selection or the vendor app's presence changes. */
    val modeChanges: Flow<SyncMode> =
        combine(settings.values.map { transports.kindFor(it) }.distinctUntilChanged(), coexistence.changes) { kind, vendor ->
            syncModeFor(kind, vendor)
        }.distinctUntilChanged()

    val syncEngine: SyncEngine by lazy {
        SyncEngine(
            connector = ::connect,
            store = BatchStore { repository.store(it) },
            mode = ::mode,
            clock = Clock.systemUTC(),
            profile = { settings.current().profile ?: Settings.DEFAULT_PROFILE },
            vendorAppInstalled = coexistence::vendorAppInstalled,
            scope = appScope,
            monitoring = { settings.current().monitoringPrefs() },
            onMonitoringPrefsMigrated = { settings.setRingMonitoringWanted(it.schedule) },
            onMonitoringAccepted = { settings.recordRingMonitoringAccepted(it) },
            onDeviceInfo = { lastDeviceInfo = it; settings.setDeviceInfo(it) },
            onCapabilities = { settings.setRingCapabilities(it) },
            onStored = { detectSleepEstimates(); serverSync.afterRingSync() },
        )
    }

    /**
     * Looks for sleep the ring missed, in the background: after every stored ring sync (the moment new heart rate and
     * steps arrive). Idempotent; never touches an answered estimate.
     */
    fun detectSleepEstimates() {
        appScope.launch {
            try {
                sleepEstimates.refresh()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("SleepEstimates", "detection failed: ${e.javaClass.simpleName}") // never values
            }
        }
    }

    /** The ring's identity, firmware and battery from the latest handshake in this process (sent to the server). */
    @Volatile private var lastDeviceInfo: DeviceInfo? = null

    // ---- Orbit Server (the app's only internet use; nothing happens until the user pairs a server) ----

    private val serverStore by lazy { ServerStore(File(app.noBackupFilesDir, "server.bin"), KeystoreSecretBox()) }
    private val serverHttpBase by lazy { PinnedTls.baseClient() }
    private val pinnedClients = ConcurrentHashMap<String, OkHttpClient>()
    private fun pinnedHttp(fingerprint: String): OkHttpClient =
        pinnedClients.getOrPut(fingerprint) { PinnedTls.client(fingerprint, serverHttpBase) }
    private val caClients = ConcurrentHashMap<String, OkHttpClient>()
    /** Public-CA client for a server URL (system trust store, exact paired host). */
    private fun caHttp(url: String): OkHttpClient =
        caClients.getOrPut(url) { PublicCaTls.client(url, serverHttpBase) }
    /** The client for [server]'s stored trust mode. */
    private fun serverHttp(server: PairedServer): OkHttpClient = serverHttpFor(server, ::pinnedHttp, ::caHttp)
    private val networkRecorder by lazy { RoomNetworkRecorder(database.networkLogDao(), Clock.systemUTC()) }

    val serverSync: ServerSync by lazy {
        ServerSync(
            store = serverStore,
            client = { ServerClient(it, serverHttp(it), networkRecorder) },
            source = RepositorySyncSource(repository),
            deviceInfo = { lastDeviceInfo?.let { DeviceInfoDto(it.deviceId, it.firmware, it.batteryPct) } },
            scope = appScope,
            clock = Clock.systemUTC(),
            log = { Log.i("OrbitServer", it) }, // counts and error kinds only; never values or tokens
            results = SettingsSyncResultStore(settings),
        )
    }

    // ---- App updates from GitHub Releases (the only other network use; the user can turn the automatic check off) ----

    /** Check, download, verify and install. Debug builds check but never install (they are signed with another key). */
    val updates: dev.avijit.orbithealth.update.UpdateManager by lazy {
        val github = dev.avijit.orbithealth.update.GitHubUpdateSource(
            http = dev.avijit.orbithealth.update.GitHubUpdateSource.client(),
            netLog = networkRecorder,
            userAgent = "OrbitHealth/${BuildConfig.VERSION_NAME}",
            downloadDir = File(app.cacheDir, "updates"),
        )
        dev.avijit.orbithealth.update.UpdateManager(
            currentVersion = BuildConfig.VERSION_NAME,
            installsHere = !BuildConfig.DEBUG,
            prefs = dev.avijit.orbithealth.update.SettingsUpdatePrefs(settings),
            source = github,
            downloader = github,
            platform = dev.avijit.orbithealth.update.AndroidUpdatePlatform(app),
            clock = Clock.systemUTC(),
            scope = appScope,
        )
    }

    /** The Ask conversation for this process (memory only). */
    val askSession = dev.avijit.orbithealth.ui.ask.AskSession()

    /** Agent & sync and Ask talk to the paired server through this. */
    val server: ServerRepository by lazy {
        ServerRepository(
            store = serverStore,
            sync = serverSync,
            pinnedHttp = ::pinnedHttp,
            caHttp = ::caHttp,
            netLog = networkRecorder,
            networkLogDao = database.networkLogDao(),
            deviceName = { Build.MODEL.orEmpty() },
            background = appScope,
        )
    }

    private suspend fun connect(): RingConnection {
        val s = settings.current()
        val kind = transports.kindFor(s)
        transportKind = kind
        return transports.connect(app, kind, s, ringRegistry)
    }

    /** True while the developer switch routes the ring through the Mac bridge (loopback only). */
    suspend fun usesDebugBridge(): Boolean = transports.kindFor(settings.current()) == TransportKind.DEBUG_BRIDGE

    /** [usesDebugBridge] as a flow, for the "Using Mac bridge" indicators on Me and in the sync notification. */
    val bridgeActive: Flow<Boolean> =
        combine(settings.values.map { it.useMacBridge }, bridgeCode) { on, code -> on && code != null }.distinctUntilChanged()

    /** Turns the Mac bridge on (with a fresh code) or off, and drops the current link so the next sync uses the change. */
    suspend fun setUseMacBridge(on: Boolean) {
        bridgeCode.value = if (on) BridgeCode.generate() else null
        settings.setUseMacBridge(on)
        syncEngine.disconnect()
    }

    /** Opens a link to [ring] before it is saved (onboarding), over the transport this build uses. */
    suspend fun connectTo(ring: PairedRing): RingConnection {
        val s = settings.current().withPairedRing(ring)
        return transports.connect(app, transports.kindFor(s), s, ringRegistry)
    }

    /** Starts background bookkeeping; called once from [OrbitApp.onCreate]. */
    fun start() {
        appScope.launch {
            if (BuildConfig.DEBUG) {
                // Debug: a bridge left on keeps working after a restart, with a new code (adb logcat -s OrbitBridge).
                if (settings.current().useMacBridge) {
                    bridgeCode.value = BridgeCode.generate()
                    Log.i("OrbitBridge", "Mac bridge on: run ble_bridge.py --code ${bridgeCode.value}")
                }
            } else {
                // Release: the bridge never survives a cold start; it must be switched on (and a code read off) again.
                settings.setUseMacBridge(false)
            }
        }
        // Time spent in the background decides whether the app locks again (main thread: ProcessLifecycleOwner).
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = appLock.onForegrounded()
            override fun onStop(owner: LifecycleOwner) = appLock.onBackgrounded()
        })
        appScope.launch {
            settings.values.collect {
                transportKind = transports.kindFor(it)
                vendorPackages.value = ringRegistry.vendorAppPackagesFor(it.pairedRing?.familyId)
            }
        }
        // Vendor app installed while we hold an EXCLUSIVE link: let go so it can connect.
        appScope.launch { coexistence.changes.collect { installed -> if (installed) syncEngine.disconnect() } }
        // Remember the last successful sync across restarts ("synced 2m" on Today).
        appScope.launch { syncEngine.state.filterIsInstance<SyncState.Synced>().collect { settings.setLastSyncedAt(it.at) } }
        // The Ask conversation belongs to one pairing: drop it when the server changes or is removed, even while no Ask
        // screen is alive.
        // (From the sealed store, which needs no database.)
        askSession.follow(serverStore.paired.map { it?.deviceId }, appScope)
        // At most once a day, release builds only, unless the user turned it off (UpdateManager/UpdatePolicy).
        appScope.launch(Dispatchers.IO) { runCatching { updates.autoCheckIfDue() } }
    }
}
