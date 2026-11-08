package dev.avijit.orbithealth.ui.nav

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import android.content.Context
import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.runtime.CompositionLocalProvider
import dev.avijit.orbithealth.ui.components.BarTabs
import dev.avijit.orbithealth.ui.components.OrbitBottomBar
import dev.avijit.orbithealth.ui.today.HeroMetric
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import dev.avijit.orbithealth.AppGraph
import dev.avijit.orbithealth.OrbitApp
import dev.avijit.orbithealth.ble.RingScanner
import dev.avijit.orbithealth.prefs.PairedRing
import dev.avijit.orbithealth.prefs.Settings
import dev.avijit.orbithealth.ring.RingCapabilities
import dev.avijit.orbithealth.ring.DeviceInfo
import dev.avijit.orbithealth.ring.UserProfile
import dev.avijit.orbithealth.sync.BatteryOptimization
import dev.avijit.orbithealth.sync.SyncService
import dev.avijit.orbithealth.ui.onboarding.OnboardingDeps
import dev.avijit.orbithealth.ui.onboarding.OnboardingStore
import dev.avijit.orbithealth.ui.onboarding.OnboardingViewModel
import dev.avijit.orbithealth.ui.onboarding.PairScreen
import dev.avijit.orbithealth.ui.onboarding.ProfileScreen
import dev.avijit.orbithealth.ui.onboarding.WelcomeScreen
import dev.avijit.orbithealth.ui.body.BodyDeps
import dev.avijit.orbithealth.ring.MeasureKind
import dev.avijit.orbithealth.ui.measure.MeasureDeps
import dev.avijit.orbithealth.ui.measure.MeasureSheet
import dev.avijit.orbithealth.ui.measure.MeasureViewModel
import dev.avijit.orbithealth.ui.body.BodyScreen
import dev.avijit.orbithealth.ui.body.BodyViewModel
import dev.avijit.orbithealth.ui.sleep.EstimateAnswer
import dev.avijit.orbithealth.ui.sleep.SleepDeps
import dev.avijit.orbithealth.ui.sleep.SleepScreen
import dev.avijit.orbithealth.ui.sleep.SleepViewModel
import dev.avijit.orbithealth.ui.agent.AgentSyncDeps
import dev.avijit.orbithealth.ui.agent.AgentSyncScreen
import dev.avijit.orbithealth.ui.agent.AgentSyncViewModel
import dev.avijit.orbithealth.ui.agent.BuildAgentDebug
import dev.avijit.orbithealth.devtools.AppDeveloperTools
import dev.avijit.orbithealth.ui.ask.AskDeps
import dev.avijit.orbithealth.ui.ask.AskScreen
import dev.avijit.orbithealth.ui.ask.AskViewModel
import dev.avijit.orbithealth.ui.me.LicencesScreen
import dev.avijit.orbithealth.ui.me.AutomaticChecksDeps
import dev.avijit.orbithealth.ui.me.AutomaticChecksScreen
import dev.avijit.orbithealth.ui.me.AutomaticChecksViewModel
import dev.avijit.orbithealth.ui.me.MeDeps
import dev.avijit.orbithealth.ui.me.MeScreen
import dev.avijit.orbithealth.ui.me.MeStore
import dev.avijit.orbithealth.ui.me.MeViewModel
import dev.avijit.orbithealth.ui.today.TodayDeps
import dev.avijit.orbithealth.ui.today.TodayScreen
import dev.avijit.orbithealth.ui.today.TodayViewModel
import java.time.Clock
import java.time.LocalTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

object Routes {
    const val ONBOARDING = "onboarding"
    const val WELCOME = "welcome"
    const val PAIR = "pair"
    const val PROFILE = "profile"
    const val TODAY = "main/today"
    const val SLEEP = "main/sleep"
    const val BODY = "main/body"
    const val ASK = "main/ask"
    const val ME = "main/me"
    const val LICENCES = "me/licences"
    const val AGENT_SYNC = "me/agent-sync"
    const val AUTO_CHECKS = "me/automatic-checks"

    /** Sleep detail pushed from Today's Sleep hero (Back reverses the shared transition); `night` = session start ms. */
    const val SLEEP_DETAIL = "sleep/detail?night={night}&score={score}"
    val sleepDetailArguments = listOf(
        navArgument("night") { type = NavType.LongType; defaultValue = -1L },
        navArgument("score") { type = NavType.IntType; defaultValue = -1 },
    )

    /** The detail route for the night starting at [nightStartMs] (null: the latest), with the hero's [score] to show at once. */
    fun sleepDetail(nightStartMs: Long?, score: Int? = null): String =
        "sleep/detail?night=${nightStartMs ?: -1L}&score=${score ?: -1}"

    fun nightOf(entry: NavBackStackEntry): Long? = entry.arguments?.getLong("night")?.takeIf { it >= 0 }
    fun scoreOf(entry: NavBackStackEntry): Int? = entry.arguments?.getInt("score")?.takeIf { it >= 0 }

    /** Welcome (inside the onboarding graph) until a ring is saved, then Today. */
    fun start(ringAddress: String?) = if (ringAddress == null) ONBOARDING else TODAY
}

/**
 * App navigation. Onboarding (welcome → pair → profile) is a nested graph whose [OnboardingViewModel] is scoped to it,
 * so the open ring link and typed profile survive moving between Pair and Profile and are dropped once it is popped.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun OrbitNavHost(startDestination: String, navController: NavHostController = rememberNavController()) {
    val context = LocalContext.current
    val graph = remember(context) { (context.applicationContext as OrbitApp).graph }
    var bridgeMode by remember { mutableStateOf(false) }
    LaunchedEffect(graph) { bridgeMode = graph.usesDebugBridge() }

    val backStack by navController.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    // The pushed Sleep detail keeps the bar, with Sleep lit (mockup screen-06).
    val barRoute = if (route == Routes.SLEEP_DETAIL) Routes.SLEEP else route
    val onMainTab = BarTabs.any { it.route == barRoute }

    val selectTab: (String) -> Unit = { target ->
        navController.navigate(target) {
            popUpTo(Routes.TODAY) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    Box(Modifier.fillMaxSize()) {
      // Shared elements (tokens §6): Today's hero sky and arc carry their keys into the detail screens (R3 wires Sleep).
      SharedTransitionLayout(Modifier.fillMaxSize()) {
       CompositionLocalProvider(LocalSharedTransitionScope provides this) {
        NavHost(
            navController,
            startDestination = startDestination,
            enterTransition = { fadeIn(tween(280)) },
            exitTransition = { fadeOut(tween(200)) },
            popEnterTransition = { fadeIn(tween(280)) },
            popExitTransition = { fadeOut(tween(200)) },
        ) {
            navigation(startDestination = Routes.WELCOME, route = Routes.ONBOARDING) {
                composable(Routes.WELCOME) {
                    WelcomeScreen(bridgeMode = bridgeMode, onReady = { navController.navigate(Routes.PAIR) { launchSingleTop = true } })
                }
                composable(Routes.PAIR) { entry ->
                    val parent = remember(entry) { navController.getBackStackEntry(Routes.ONBOARDING) }
                    val vm = onboardingViewModel(parent, context, graph)
                    PairScreen(
                        vm, bridgeMode,
                        onProfile = { navController.navigate(Routes.PROFILE) { launchSingleTop = true } },
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(Routes.PROFILE) { entry ->
                    val parent = remember(entry) { navController.getBackStackEntry(Routes.ONBOARDING) }
                    val vm = onboardingViewModel(parent, context, graph)
                    ProfileScreen(vm, onDone = {
                        navController.navigate(Routes.TODAY) {
                            popUpTo(navController.graph.id) { inclusive = true }
                            launchSingleTop = true
                        }
                    })
                }
            }
            composable(Routes.TODAY) {
                val vm: TodayViewModel = viewModel(factory = viewModelFactory { initializer { TodayViewModel(appTodayDeps(graph)) } })
                val measure: MeasureViewModel = viewModel(factory = viewModelFactory { initializer { MeasureViewModel(appMeasureDeps(graph)) } })
                MeasureSheet(measure)
                CompositionLocalProvider(LocalNavAnimatedScope provides this) {
                    TodayScreen(vm, onMeasure = { measure.open() }, onOpen = { metric ->
                        if (metric == HeroMetric.SLEEP) {
                            val st = vm.state.value
                            navController.navigate(Routes.sleepDetail(st.lastNight?.start?.toEpochMilli(), st.sleep?.value)) {
                                launchSingleTop = true
                            }
                        } else selectTab(Routes.BODY)
                    })
                }
            }
            // The tab: no shared transition (no LocalNavAnimatedScope), just the NavHost fade.
            composable(Routes.SLEEP) {
                val vm: SleepViewModel = viewModel(factory = viewModelFactory { initializer { SleepViewModel(appSleepDeps(graph)) } })
                SleepScreen(vm, onBack = { navController.popBackStack() })
            }
            // Pushed from Today's Sleep hero: the hero sky and arc grow into the header (tokens §6).
            composable(Routes.SLEEP_DETAIL, arguments = Routes.sleepDetailArguments) { entry ->
                val vm: SleepViewModel = viewModel(factory = viewModelFactory { initializer { SleepViewModel(appSleepDeps(graph)) } })
                CompositionLocalProvider(LocalNavAnimatedScope provides this) {
                    SleepScreen(vm, onBack = { navController.popBackStack() }, focusNight = Routes.nightOf(entry), heroScore = Routes.scoreOf(entry))
                }
            }
            composable(Routes.BODY) {
                val vm: BodyViewModel = viewModel(factory = viewModelFactory { initializer { BodyViewModel(appBodyDeps(graph)) } })
                val measure: MeasureViewModel = viewModel(factory = viewModelFactory { initializer { MeasureViewModel(appMeasureDeps(graph)) } })
                MeasureSheet(measure)
                // "Measure now" keeps its one tap: the sheet opens on Heart rate and starts it; the tiles stay to switch.
                BodyScreen(vm, onMeasure = { measure.open(MeasureKind.HEART_RATE, autoStart = true) })
            }
            // The agent on the user's Orbit Server (Plan 3 will add an on-device one).
            composable(Routes.ASK) {
                val vm: AskViewModel = viewModel(factory = viewModelFactory { initializer { AskViewModel(appAskDeps(graph)) } })
                AskScreen(vm, onSetup = { navController.navigate(Routes.AGENT_SYNC) { launchSingleTop = true } })
            }
            composable(Routes.ME) {
                val vm: MeViewModel = viewModel(factory = viewModelFactory { initializer { MeViewModel(appMeDeps(graph)) } })
                // Every build: hidden until a shake on Me (the group says so), never reachable from outside the app.
                val developer = remember(graph) { AppDeveloperTools(graph) }
                MeScreen(
                    vm, developer,
                    onLicences = { navController.navigate(Routes.LICENCES) { launchSingleTop = true } },
                    onAgentSync = { navController.navigate(Routes.AGENT_SYNC) { launchSingleTop = true } },
                    onAutomaticChecks = { navController.navigate(Routes.AUTO_CHECKS) { launchSingleTop = true } },
                    updates = graph.updates,
                    onReset = {
                        // Everything is wiped: start again at Welcome.
                        navController.navigate(Routes.ONBOARDING) {
                            popUpTo(navController.graph.id) { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                )
            }
            composable(Routes.LICENCES) { LicencesScreen(onBack = { navController.popBackStack() }) }
            composable(Routes.AUTO_CHECKS) {
                val vm: AutomaticChecksViewModel =
                    viewModel(factory = viewModelFactory { initializer { AutomaticChecksViewModel(appAutomaticChecksDeps(graph)) } })
                AutomaticChecksScreen(vm, onBack = { navController.popBackStack() })
            }
            composable(Routes.AGENT_SYNC) {
                val vm: AgentSyncViewModel = viewModel(factory = viewModelFactory { initializer { AgentSyncViewModel(appAgentSyncDeps(graph)) } })
                AgentSyncScreen(vm, onBack = { navController.popBackStack() }, allowPastedLink = BuildAgentDebug.ALLOW_PASTED_LINK)
            }
        }
       }
      }
        // One floating bar shared by every main tab; it stays put while the tabs cross-fade beneath it.
        AnimatedVisibility(
            onMainTab, modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(tween(280)), exit = fadeOut(tween(200)),
        ) {
            OrbitBottomBar(barRoute, onSelect = selectTab)
        }
    }
}

/** Today wired to the encrypted database, settings, the sync engine and the vendor-app detector. */
fun appTodayDeps(graph: AppGraph) = TodayDeps(
    // The repository (and so the database) is first touched inside the flow, on the compute dispatcher.
    samples = { metric, from, to -> graph.repository.samples(metric, from, to) },
    sleepSessions = { from, to -> graph.repository.sleepSessions(from, to) },
    settings = graph.settings.values,
    syncState = graph.syncEngine.state,
    lastSyncedAt = graph.settings.values.map { it.lastSyncedAt }.distinctUntilChanged(),
    vendorInstalled = graph.coexistence.changes,
    bannerDismissedAt = graph.settings.values.map { it.coexistenceBannerDismissedAt }.distinctUntilChanged(),
    dismissBanner = { graph.settings.setCoexistenceBannerDismissedAt(it) },
    refresh = { withContext(Dispatchers.Default) { graph.syncEngine.syncOnce() } },
    clock = Clock.systemDefaultZone(),
    vendorAppName = graph.vendorAppName,
    estimates = { from, to -> graph.sleepEstimates.estimates(from, to) },
    answerEstimate = { answerEstimate(graph, it) },
    detectEstimates = { withContext(Dispatchers.IO) { graph.sleepEstimates.refresh() } },
)

/** Stores a Yes / Adjust / No on an estimated night or nap. */
private suspend fun answerEstimate(graph: AppGraph, a: EstimateAnswer) {
    withContext(Dispatchers.IO) {
        when (a) {
            is EstimateAnswer.Confirm -> graph.sleepEstimates.confirm(a.id, a.start, a.end)
            is EstimateAnswer.Dismiss -> graph.sleepEstimates.dismiss(a.id)
        }
    }
}

/** Sleep wired to the encrypted database and settings. */
fun appSleepDeps(graph: AppGraph) = SleepDeps(
    samples = { metric, from, to -> graph.repository.samples(metric, from, to) },
    sleepSessions = { from, to -> graph.repository.sleepSessions(from, to) },
    settings = graph.settings.values,
    clock = Clock.systemDefaultZone(),
    estimates = { from, to -> graph.sleepEstimates.estimates(from, to) },
    answerEstimate = { answerEstimate(graph, it) },
    detectEstimates = { withContext(Dispatchers.IO) { graph.sleepEstimates.refresh() } },
)

/** Body wired to the database and settings. */
fun appBodyDeps(graph: AppGraph) = BodyDeps(
    samples = { metric, from, to -> graph.repository.samples(metric, from, to) },
    sleepSessions = { from, to -> graph.repository.sleepSessions(from, to) },
    workouts = { from, to -> graph.repository.workouts(from, to) },
    settings = graph.settings.values,
    clock = Clock.systemDefaultZone(),
)

/** The Measure sheet: the cached ring capabilities, the sync engine's link (readings are stored) and the store. */
fun appMeasureDeps(graph: AppGraph) = MeasureDeps(
    capabilities = graph.settings.ringCapabilities,
    measure = { kind -> graph.syncEngine.measure(kind).flowOn(Dispatchers.Default) },
    samples = { metric, from, to -> graph.repository.samples(metric, from, to).flowOn(Dispatchers.Default) },
    clock = Clock.systemDefaultZone(),
)

@Composable
private fun onboardingViewModel(owner: androidx.lifecycle.ViewModelStoreOwner, context: Context, graph: AppGraph): OnboardingViewModel =
    viewModel(owner, factory = viewModelFactory { initializer { OnboardingViewModel(appOnboardingDeps(context.applicationContext, graph)) } })

/** Onboarding wired to the real scanner, transport, settings and sync service. */
fun appOnboardingDeps(app: Context, graph: AppGraph) = OnboardingDeps(
    scan = { RingScanner(app, graph.ringRegistry).scan() },
    bridgeMode = graph::usesDebugBridge,
    connect = graph::connectTo,
    store = SettingsOnboardingStore(graph.settings),
    startSync = { SyncService.start(app) },
    batteryExempt = { BatteryOptimization.isExempt(app) },
    logError = { msg, e -> Log.w("OrbitOnboarding", msg, e) },
)

private class SettingsOnboardingStore(private val settings: Settings) : OnboardingStore {
    override suspend fun saveName(name: String?) { settings.setName(name) }
    override suspend fun saveProfile(profile: UserProfile) { settings.setProfile(profile) }
    override suspend fun saveRing(ring: PairedRing) { settings.setPairedRing(ring) }
    override suspend fun saveCapabilities(caps: RingCapabilities) { settings.setRingCapabilities(caps) }
    override suspend fun saveRingInfo(name: String?, info: DeviceInfo) {
        settings.setRingName(name)
        settings.setDeviceInfo(info)
    }
    override suspend fun batteryPromptAsked() = settings.current().batteryPromptAsked
    override suspend fun markBatteryPromptAsked() { settings.setBatteryPromptAsked() }
}

/** Agent & sync over the server repository (the app's only network door). */
fun appAgentSyncDeps(graph: AppGraph) = AgentSyncDeps(
    paired = graph.server.pairedServer,
    lastSync = graph.server.lastSyncResult,
    load = { withContext(Dispatchers.IO) { graph.server.current() } },
    pair = { link, scopes -> withContext(Dispatchers.IO) { graph.server.pair(link, scopes) } },
    unpair = { withContext(Dispatchers.IO) { graph.server.unpair() } },
    setScopes = { withContext(Dispatchers.IO) { graph.server.setScopes(it) } },
    accessLog = { withContext(Dispatchers.IO) { graph.server.accessLog() } },
    syncNow = { withContext(Dispatchers.IO) { graph.server.syncNow() } },
    clock = Clock.systemDefaultZone(),
)

/** Ask: questions go to the paired server's agent; mini charts re-read this phone's encrypted database. */
fun appAskDeps(graph: AppGraph) = AskDeps(
    paired = graph.server.pairedServer,
    load = { withContext(Dispatchers.IO) { graph.server.current() } },
    ask = { q, history -> withContext(Dispatchers.IO) { graph.server.ask(q, history) } },
    samples = { metric, from, to -> withContext(Dispatchers.IO) { graph.repository.samples(metric, from, to).first() } },
    clock = Clock.systemDefaultZone(),
    zone = java.time.ZoneId.systemDefault(),
    session = graph.askSession,
)

/** Automatic checks: cached capabilities and schedules from settings; reading and applying through the sync engine. */
fun appAutomaticChecksDeps(graph: AppGraph) = AutomaticChecksDeps(
    settings = graph.settings.values,
    saveWanted = { graph.settings.setRingMonitoringWanted(it) },
    readFromRing = { withContext(Dispatchers.Default) { graph.syncEngine.readMonitoring() } },
    apply = { withContext(Dispatchers.Default) { graph.syncEngine.applyMonitoringNow() } },
    clock = Clock.systemDefaultZone(),
)

/** Me wired to settings, the sync engine (link state, ring settings) and the sync mode. */
fun appMeDeps(graph: AppGraph) = MeDeps(
    settings = graph.settings.values,
    syncState = graph.syncEngine.state,
    linked = graph.syncEngine.linked,
    mode = graph.modeChanges,
    store = SettingsMeStore(graph.settings),
    clock = Clock.systemDefaultZone(),
    serverName = graph.server.pairedServer.map { it?.name },
    sentSince = { from -> graph.server.bytesSentSince(from).flowOn(Dispatchers.IO) },
    vendorAppName = graph.vendorAppName,
    bridgeActive = graph.bridgeActive,
)

private class SettingsMeStore(private val settings: Settings) : MeStore {
    override suspend fun setAppLockEnabled(v: Boolean) { settings.setAppLockEnabled(v) }
    override suspend fun setStepGoalBase(v: Int) { settings.setStepGoalBase(v) }
    override suspend fun setSleepGoalMin(v: Int) { settings.setSleepGoalMin(v) }
    override suspend fun setBedtimeTarget(v: LocalTime) { settings.setBedtimeTarget(v) }
    override suspend fun setProfile(p: UserProfile) { settings.setProfile(p) }
    override suspend fun setName(name: String?) { settings.setName(name) }
}
