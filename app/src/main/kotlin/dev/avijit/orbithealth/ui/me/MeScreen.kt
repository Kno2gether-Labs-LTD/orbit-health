package dev.avijit.orbithealth.ui.me

import kotlinx.coroutines.flow.flowOf
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.avijit.orbithealth.security.LocalLockAuthenticator
import dev.avijit.orbithealth.ui.components.BrandMark
import dev.avijit.orbithealth.ui.components.GhostButtonPair
import dev.avijit.orbithealth.ui.components.ListCard
import dev.avijit.orbithealth.ui.components.ListGroupLabel
import dev.avijit.orbithealth.ui.components.ListRow
import dev.avijit.orbithealth.ui.components.LocalHazeState
import dev.avijit.orbithealth.ui.components.OrbitArc
import dev.avijit.orbithealth.ui.components.OrbitArcDefaults
import dev.avijit.orbithealth.ui.components.OrbitSymbol
import dev.avijit.orbithealth.ui.components.OrbitToggle
import dev.avijit.orbithealth.ui.components.Symbol
import dev.avijit.orbithealth.ui.components.livingEnter
import dev.avijit.orbithealth.ui.components.orbitBottomClearance
import dev.avijit.orbithealth.ui.sky.Sky
import dev.avijit.orbithealth.ui.sky.SkyKind
import dev.avijit.orbithealth.ui.theme.OrbitPalette
import dev.avijit.orbithealth.ui.theme.OrbitTypography
import dev.avijit.orbithealth.ui.theme.lineBox
import dev.chrisbanes.haze.hazeSource
import java.text.NumberFormat
import kotlinx.coroutines.launch
import dev.avijit.orbithealth.update.UpdateUiState

private val Gutter = 24.dp

/** Which sheet is open on Me. */
enum class MeSheet { NAME, STEP_GOAL, SLEEP_GOAL, BEDTIME, PROFILE, DATA_PRIVACY, APP_UPDATES }

/** Everything the Me layout can do, so [MeContent] can be previewed and screenshotted without a ViewModel. */
class MeActions(
    val setStepGoal: (Int) -> Unit = {},
    val setSleepGoalMin: (Int) -> Unit = {},
    val setBedtime: (java.time.LocalTime) -> Unit = {},
    val setProfile: (dev.avijit.orbithealth.ring.UserProfile) -> Unit = {},
    val setName: (String) -> Unit = {},
    val setAppLockEnabled: (Boolean) -> Unit = {},
    val openLicences: () -> Unit = {},
    val openAgentSync: () -> Unit = {},
    val openAutomaticChecks: () -> Unit = {},
)

@Composable
fun MeScreen(
    vm: MeViewModel,
    developer: DeveloperTools?,
    onLicences: () -> Unit,
    onAgentSync: () -> Unit,
    onReset: () -> Unit,
    onAutomaticChecks: () -> Unit = {},
    updates: dev.avijit.orbithealth.update.UpdateManager? = null,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val updateState = updates?.state?.collectAsStateWithLifecycle()?.value
    val scope = rememberCoroutineScope()
    // The activity's biometric/PIN check; absent (previews) means turning the lock off is refused.
    val authenticator = LocalLockAuthenticator.current
    MeContent(
        state,
        MeActions(
            setStepGoal = vm::setStepGoal,
            setSleepGoalMin = vm::setSleepGoalMin,
            setBedtime = vm::setBedtime,
            setProfile = vm::setProfile,
            setName = vm::setName,
            setAppLockEnabled = { on -> scope.launch { vm.setAppLockEnabled(on) { authenticator?.confirm() ?: false } } },
            openLicences = onLicences,
            openAgentSync = onAgentSync,
            openAutomaticChecks = onAutomaticChecks,
        ),
        developer = developer,
        onReset = onReset,
        updates = updateState,
        updateActions = remember(updates) {
            updates?.let { u ->
                UpdateActions(
                    check = { u.checkNow() }, update = { u.update() },
                    setAutoCheck = u::setAutoCheck, openInstallPermission = u::openInstallPermission,
                )
            } ?: UpdateActions()
        },
    )
}

/**
 * Living Skies Me (screen-11): "Me" over "Your *ring*", the ring under a small Night sky (mark, name, firmware and
 * connection, battery arc), then quiet grouped settings: Sync, Goals & profile (with the greeting Name), Privacy
 * (app lock) and the doors to later plans and the licences. Debug builds add a Developer group.
 */
@Composable
fun MeContent(
    state: MeUiState,
    actions: MeActions,
    modifier: Modifier = Modifier,
    developer: DeveloperTools? = null,
    onReset: () -> Unit = {},
    initialSheet: MeSheet? = null,
    developerRevealed: Boolean = false,
    updates: UpdateUiState? = null,
    updateActions: UpdateActions = UpdateActions(),
    now: () -> java.time.Instant = { java.time.Instant.now() },
) {
    var sheet by rememberSaveable { mutableStateOf(initialSheet) }
    // Developer options (any build) stay hidden until the phone is shaken on Me, and stay visible while the
    // Mac bridge is on so it can always be turned off again.
    var revealed by rememberSaveable { mutableStateOf(developerRevealed) }
    val bridgeOn by remember(developer) { developer?.useMacBridge ?: flowOf(false) }.collectAsState(initial = false)
    val haptics = LocalHapticFeedback.current
    if (developer != null) ShakeListener(enabled = !revealed) {
        revealed = true
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }
    val nf = remember { NumberFormat.getIntegerInstance() }
    val scroll = rememberScrollState()
    val haze = LocalHazeState.current
    Box(
        modifier
            .fillMaxSize()
            .then(if (haze != null) Modifier.hazeSource(haze) else Modifier)
            .background(Brush.verticalGradient(listOf(OrbitPalette.Base0, OrbitPalette.Base1))),
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll).statusBarsPadding().padding(bottom = orbitBottomClearance())) {
            Column(Modifier.padding(start = Gutter, end = Gutter, top = 14.dp).livingEnter(0)) {
                Text("Me", style = OrbitTypography.Eyebrow, modifier = Modifier.lineBox(OrbitTypography.Eyebrow))
                Text(
                    buildAnnotatedString {
                        append("Your ")
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append("ring") }
                    },
                    style = OrbitTypography.Title,
                    modifier = Modifier.padding(top = 2.dp).lineBox(OrbitTypography.Title).semantics { heading() },
                )
            }
            if (!state.loaded) return@Column

            RingCard(state, Modifier.padding(start = Gutter, end = Gutter, top = 16.dp).livingEnter(60))

            ListGroupLabel("Sync", Modifier.livingEnter(120))
            ListCard(Modifier.padding(horizontal = Gutter).livingEnter(120)) {
                ListRow("Last sync", first = true, value = state.lastSync)
                ListRow(
                    "Keep connected in background", first = false, caption = state.keepConnected.caption,
                    // Plan 1: the sync mode decides this (on while Orbit Health is the ring's only app); not a user switch.
                    trailing = { OrbitToggle(state.keepConnected.on, onCheckedChange = null, enabled = state.keepConnected.enabled) },
                )
                // Everything the ring measures by itself lives on its own screen (one place for it).
                ListRow(
                    "Automatic checks", first = false, chevron = true,
                    caption = state.autoChecks ?: "Connect your ring to set them",
                    onClick = actions.openAutomaticChecks,
                )
            }

            ListGroupLabel("Goals & profile", Modifier.livingEnter(180))
            ListCard(Modifier.padding(horizontal = Gutter).livingEnter(180)) {
                // "Base": Today adapts it with readiness, so the goal shown there can differ (MeSheets says so).
                ListRow("Base step goal", first = true, value = nf.format(state.stepGoal), chevron = true) { sheet = MeSheet.STEP_GOAL }
                ListRow("Sleep goal", first = false, value = MeMath.hoursMinutes(state.sleepGoalMin), chevron = true) { sheet = MeSheet.SLEEP_GOAL }
                ListRow("Bedtime", first = false, value = state.bedtime.toString(), chevron = true) { sheet = MeSheet.BEDTIME }
                ListRow(
                    "Profile", first = false,
                    value = if (state.hasProfile) "${state.profile.heightCm} cm · ${state.profile.weightKg} kg" else "Add",
                    chevron = true,
                ) { sheet = MeSheet.PROFILE }
                ListRow("Name", first = false, value = state.name ?: "Add", chevron = true) { sheet = MeSheet.NAME }
            }

            ListGroupLabel("Privacy", Modifier.livingEnter(240))
            ListCard(Modifier.padding(horizontal = Gutter).livingEnter(240)) {
                ListRow(
                    "App lock", first = true,
                    caption = MeMath.appLockCaption(state.lockAfterSec, Build.VERSION.SDK_INT),
                    leading = { Symbol(OrbitSymbol.Lock, size = 20.dp, tint = OrbitPalette.SleepB) },
                    onClick = { actions.setAppLockEnabled(!state.appLockEnabled) },
                    trailing = { OrbitToggle(state.appLockEnabled, onCheckedChange = actions.setAppLockEnabled) },
                )
            }

            ListCard(Modifier.padding(start = Gutter, end = Gutter, top = 12.dp).livingEnter(300)) {
                ListRow(
                    "Data & privacy", first = true, chevron = true,
                    leading = { Symbol(OrbitSymbol.Shield, size = 20.dp, tint = OrbitPalette.Optimal) },
                ) { sheet = MeSheet.DATA_PRIVACY }
                ListRow(
                    "Agent & sync", first = false, chevron = true, value = state.agentSync,
                    leading = { Symbol(OrbitSymbol.Agent, size = 20.dp, tint = OrbitPalette.StageRem) },
                    onClick = actions.openAgentSync,
                )
                ListRow(
                    "Open-source licences", first = false, chevron = true,
                    leading = { Symbol(OrbitSymbol.Doc, size = 20.dp, tint = OrbitPalette.Good) },
                    onClick = actions.openLicences,
                )
                updates?.let { u ->
                    ListRow(
                        UpdateCopy.ROW, first = false, chevron = true, value = u.currentVersion,
                        caption = UpdateCopy.status(u),
                        leading = { Symbol(OrbitSymbol.Down, size = 20.dp, tint = OrbitPalette.SleepB) },
                    ) { sheet = MeSheet.APP_UPDATES }
                }
            }

            if (developer != null && (revealed || bridgeOn)) DeveloperGroup(developer, onReset, Modifier.livingEnter(360))
        }
        TopScrim { scroll.value }
    }

    when (sheet) {
        null -> Unit
        MeSheet.DATA_PRIVACY -> DataPrivacySheet(state.sentThisMonth) { sheet = null }
        MeSheet.APP_UPDATES -> updates?.let { AppUpdatesSheet(it, updateActions, now()) { sheet = null } } ?: run { sheet = null }
        else -> EditSheet(sheet!!, state, actions) { sheet = null }
    }
}

/** Tokens §5 top scrim: fades in over the first 40dp of scroll under the status bar. */
@Composable
internal fun TopScrim(scrollPx: () -> Int) {
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Spacer(
        Modifier.fillMaxWidth().height(statusTop + 40.dp)
            .graphicsLayer { alpha = (scrollPx() / 40.dp.toPx()).coerceIn(0f, 1f) }
            .drawBehind {
                drawRect(Brush.verticalGradient(0f to OrbitPalette.Base0, 0.46f to OrbitPalette.Base0, 1f to OrbitPalette.Base0.copy(alpha = 0f)))
            },
    )
}

/**
 * The mockup's ring card: a 132dp Night sky (radius 24, 16 stars) holding the 68dp mark, the ring's name over
 * "Firmware 1.18 · connected" in inkSky2, and the battery arc with its percentage.
 */
@Composable
private fun RingCard(state: MeUiState, modifier: Modifier) {
    Box(modifier.fillMaxWidth().height(132.dp).clip(RoundedCornerShape(24.dp)).semantics(mergeDescendants = true) {}) {
        Sky(SkyKind.NIGHT, Modifier.matchParentSize(), stars = 16, starSeed = 31)
        Row(
            Modifier.matchParentSize().padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            BrandMark(68.dp, glow = false)
            Column(Modifier.weight(1f)) {
                Text(state.ringName, style = OrbitTypography.RingName, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.lineBox(OrbitTypography.RingName))
                Text(
                    // Each " · " phrase stays whole ("last synced 1 hr ago"), so a wrap falls at the dot, never orphaning "ago".
                    MeMath.unbreakablePhrases(state.ringStatus), style = OrbitTypography.Meta.copy(color = OrbitPalette.InkSky2), maxLines = 2,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            state.batteryPct?.let { pct ->
                Box(Modifier.size(OrbitArcDefaults.BatterySize).semantics { contentDescription = "Battery $pct percent" }, contentAlignment = Alignment.Center) {
                    OrbitArc(pct / 100f, size = OrbitArcDefaults.BatterySize)
                    Text("$pct%", style = OrbitTypography.RingName.copy(fontSize = OrbitTypography.Meta.fontSize, lineHeight = OrbitTypography.Meta.fontSize))
                }
            }
        }
    }
}

/** After a shake on Me (any build): the Mac bridge switch and sample data, each data change confirmed first. */
@Composable
private fun DeveloperGroup(tools: DeveloperTools, onReset: () -> Unit, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    val bridge by remember(tools) { tools.useMacBridge }.collectAsState(initial = false)
    val code by remember(tools) { tools.bridgeCode }.collectAsState(initial = null)
    var note by remember { mutableStateOf<String?>(null) }
    // Seed and Reset both change real data: each waits for a yes on its own sheet.
    var confirm by rememberSaveable { mutableStateOf<DeveloperConfirm?>(null) }
    Column(modifier) {
        ListGroupLabel("Developer")
        ListCard(Modifier.padding(horizontal = Gutter)) {
            ListRow(
                "Use Mac bridge", first = true, onClick = { scope.launch { tools.setUseMacBridge(!bridge) } },
                // The code proves to the app that it is talking to the real bridge, not another app on this phone.
                caption = code?.takeIf { bridge }?.let { DeveloperCopy.bridgeCode(it) },
                trailing = { OrbitToggle(bridge, onCheckedChange = { on -> scope.launch { tools.setUseMacBridge(on) } }) },
            )
            GhostButtonPair(
                first = "Seed sample data",
                onFirst = { confirm = DeveloperConfirm.SEED },
                second = "Reset",
                onSecond = { confirm = DeveloperConfirm.RESET },
                modifier = Modifier.padding(top = 6.dp, bottom = 14.dp),
            )
            note?.let { Text(it, style = OrbitTypography.Meta, modifier = Modifier.padding(bottom = 14.dp)) }
            Text(
                "For testing with a computer. Hidden again when the app closes.",
                style = OrbitTypography.Meta.copy(color = OrbitPalette.InkSky2), modifier = Modifier.padding(bottom = 14.dp),
            )
        }
        Spacer(Modifier.width(1.dp))
    }
    confirm?.let { which ->
        DeveloperConfirmSheet(which, onDismiss = { confirm = null }, onConfirm = {
            confirm = null
            when (which) {
                DeveloperConfirm.SEED -> scope.launch {
                    note = "Seeding…"
                    note = runCatching { tools.seedSampleData() }.getOrElse { "Seeding failed: ${it.message}" }
                }
                DeveloperConfirm.RESET -> scope.launch {
                    runCatching { tools.reset() }.onSuccess { onReset() }.onFailure { note = "Reset failed: ${it.message}" }
                }
            }
        })
    }
}
