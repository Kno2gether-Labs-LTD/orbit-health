package dev.avijit.orbithealth.ui.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.avijit.orbithealth.sync.Scope
import dev.avijit.orbithealth.ui.components.EstimateBadge
import dev.avijit.orbithealth.ui.components.GhostButton
import dev.avijit.orbithealth.ui.components.GhostButtonPair
import dev.avijit.orbithealth.ui.components.ListCard
import dev.avijit.orbithealth.ui.components.ListGroupLabel
import dev.avijit.orbithealth.ui.components.ListRow
import dev.avijit.orbithealth.ui.components.OrbitIconButton
import dev.avijit.orbithealth.ui.components.OrbitIconButtonDefaults
import dev.avijit.orbithealth.ui.components.OrbitSheet
import dev.avijit.orbithealth.ui.components.OrbitSymbol
import dev.avijit.orbithealth.ui.components.OrbitToggle
import dev.avijit.orbithealth.ui.components.Symbol
import dev.avijit.orbithealth.ui.components.TintedCard
import dev.avijit.orbithealth.ui.components.TintedCardDefaults
import dev.avijit.orbithealth.ui.components.WarmButton
import dev.avijit.orbithealth.ui.components.livingEnter
import dev.avijit.orbithealth.ui.me.TopScrim
import dev.avijit.orbithealth.ui.sky.Sky
import dev.avijit.orbithealth.ui.sky.SkyKind
import dev.avijit.orbithealth.ui.theme.OrbitPalette
import dev.avijit.orbithealth.ui.theme.OrbitTypography
import dev.avijit.orbithealth.ui.theme.lineBox
import java.net.URI

private val Gutter = 24.dp

/** Everything the layout can do, so [AgentSyncContent] can be previewed and screenshotted without a ViewModel. */
class AgentSyncActions(
    val back: () -> Unit = {},
    val scan: () -> Unit = {},
    val pasteLink: (String) -> Unit = {},
    val toggleScope: (Scope, Boolean) -> Unit = { _, _ -> },
    val syncNow: () -> Unit = {},
    val remove: () -> Unit = {},
    val refreshLog: () -> Unit = {},
    val dismissNotice: () -> Unit = {},
)

@Composable
fun AgentSyncScreen(vm: AgentSyncViewModel, onBack: () -> Unit, allowPastedLink: Boolean) {
    val state by vm.state.collectAsStateWithLifecycle()
    val scan = rememberQrScanner(onResult = vm::onScanned, onCameraDenied = { vm.showNotice(AgentSyncCopy.CAMERA_DENIED) })
    AgentSyncContent(
        state,
        AgentSyncActions(
            back = onBack, scan = scan, pasteLink = vm::pair, toggleScope = vm::toggleScope, syncNow = vm::syncNow,
            remove = vm::remove, refreshLog = vm::refreshAccessLog, dismissNotice = vm::dismissNotice,
        ),
        allowPastedLink = allowPastedLink,
    )
}

/**
 * Agent & sync in Living Skies (derived from the v2 mockup): "Me" over "Agent & *sync*", a Night sky card with the
 * server (or the promise), where the agent lives (this phone: coming soon; My Orbit Server), then pairing by QR or the
 * paired server's status and Remove, the data groups shared with it, and the access log the server reports.
 */
@Composable
fun AgentSyncContent(state: AgentSyncUiState, actions: AgentSyncActions, modifier: Modifier = Modifier, allowPastedLink: Boolean = false) {
    var confirmRemove by rememberSaveable { mutableStateOf(false) }
    val scroll = rememberScrollState()
    Box(modifier.fillMaxSize().background(Brush.verticalGradient(listOf(OrbitPalette.Base0, OrbitPalette.Base1)))) {
        Column(
            Modifier.fillMaxSize().verticalScroll(scroll).statusBarsPadding().navigationBarsPadding()
                .padding(bottom = 32.dp),
        ) {
            OrbitIconButton(
                OrbitSymbol.Back, onClick = actions.back, size = 40.dp, contentDescription = "Back to Me",
                modifier = Modifier.padding(start = 16.dp - OrbitIconButtonDefaults.spill(40.dp), top = 8.dp),
            )
            Column(Modifier.padding(start = Gutter, end = Gutter, top = 10.dp).livingEnter(0)) {
                Text("Me", style = OrbitTypography.Eyebrow, modifier = Modifier.lineBox(OrbitTypography.Eyebrow))
                Text(
                    buildAnnotatedString {
                        append("Agent & ")
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append("sync") }
                    },
                    style = OrbitTypography.Title,
                    modifier = Modifier.padding(top = 2.dp).lineBox(OrbitTypography.Title).semantics { heading() },
                )
            }
            if (!state.loaded) return@Column

            ServerSkyCard(state, Modifier.padding(start = Gutter, end = Gutter, top = 16.dp).livingEnter(60))

            val banner = state.notice ?: state.repair
            if (banner != null) {
                Notice(
                    banner,
                    action = if (state.notice == null && state.repair != null) "Scan a new code" to actions.scan else null,
                    onDismiss = if (state.notice != null) actions.dismissNotice else null,
                    modifier = Modifier.padding(start = Gutter, end = Gutter, top = 12.dp),
                )
            }

            ListGroupLabel("Where does your agent live?", Modifier.livingEnter(120))
            Column(Modifier.padding(horizontal = Gutter).livingEnter(120), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PlaceOption(
                    title = "On this phone", caption = "On-device agent coming soon. Your data would never leave the phone.",
                    selected = false, enabled = false, symbol = OrbitSymbol.Lock,
                )
                PlaceOption(
                    title = "My Orbit Server", caption = "Runs on your Mac, home server or VPS. Your data syncs there; your agent, " +
                        "models and tools run beside it.",
                    selected = true, enabled = true, symbol = OrbitSymbol.Agent, badge = "Recommended",
                )
            }

            val server = state.server
            if (server == null) {
                ListGroupLabel("Pair your server", Modifier.livingEnter(180))
                PairCard(state, actions, allowPastedLink, Modifier.padding(horizontal = Gutter).livingEnter(180))
            } else {
                ListGroupLabel("Server", Modifier.livingEnter(180))
                ListCard(Modifier.padding(horizontal = Gutter).livingEnter(180)) {
                    ListRow("Name", first = true, value = server.name)
                    ListRow("Address", first = false, value = hostOf(server.url))
                    ListRow(
                        "Connection", first = false,
                        value = AgentSyncCopy.secured(state.repair, server.trustMode),
                    )
                    ListRow("Last sync", first = false, value = state.lastSync, caption = state.lastSyncCaption)
                    GhostButtonPair(
                        first = if (state.busy == AgentBusy.SYNCING) "Syncing…" else "Sync now", onFirst = actions.syncNow,
                        second = if (state.busy == AgentBusy.REMOVING) "Removing…" else "Remove server", onSecond = { confirmRemove = true },
                        modifier = Modifier.padding(top = 6.dp, bottom = 14.dp),
                    )
                }
            }

            ListGroupLabel(if (server == null) "What it may receive" else "Shared with your server", Modifier.livingEnter(240))
            ListCard(Modifier.padding(horizontal = Gutter).livingEnter(240)) {
                ScopeRows.forEachIndexed { i, row ->
                    val on = row.scope in state.scopes
                    ListRow(
                        row.label, first = i == 0, caption = row.caption,
                        leading = { Symbol(row.symbol, size = 20.dp, tint = row.tint) },
                        onClick = { actions.toggleScope(row.scope, !on) },
                        trailing = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                if (row.estimate) EstimateBadge()
                                OrbitToggle(on, onCheckedChange = { actions.toggleScope(row.scope, it) })
                            }
                        },
                    )
                }
            }
            Text(
                (if (server == null) "Your server and its agents only ever see what you share. You can change this any time."
                else "Switching a group off stops sending it at once, and agents on your server can no longer read it.") +
                    " " + AgentSyncCopy.ZONE_NOTE,
                style = OrbitTypography.Meta, modifier = Modifier.padding(start = Gutter, end = Gutter, top = 10.dp),
            )

            if (server != null) {
                Row(
                    Modifier.padding(start = Gutter, end = Gutter, top = 22.dp, bottom = 8.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Text("Access log", style = OrbitTypography.Eyebrow, modifier = Modifier.weight(1f).lineBox(OrbitTypography.Eyebrow))
                    Text("Reported by your server", style = OrbitTypography.Caption)
                }
                AccessLogCard(state.accessLog, actions.refreshLog, Modifier.padding(horizontal = Gutter))
            }
        }
        // Tokens §5: the same top scrim as Me, so scrolled rows never run under the status bar.
        TopScrim { scroll.value }
    }

    if (confirmRemove && state.server != null) {
        OrbitSheet(title = "Remove ${state.server.name}?", kicker = "Agent & sync", onDismiss = { confirmRemove = false }) {
            Text(
                "This phone stops syncing and asks your server to forget it. Data already on your server stays there " +
                    "until you delete it in its dashboard.",
                style = OrbitTypography.Lead,
            )
            Spacer(Modifier.height(20.dp))
            WarmButton("Remove server", { confirmRemove = false; actions.remove() }, Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            GhostButton("Keep it", { confirmRemove = false }, Modifier.fillMaxWidth())
        }
    }
}

/** The host (and port) of a server URL, for the status row. */
internal fun hostOf(url: String): String = try {
    URI(url).let { u -> if (u.port > 0 && u.port != 443) "${u.host}:${u.port}" else u.host } ?: url
} catch (e: Exception) {
    url
}

/** The status card: a 148dp Night sky with the paired server, or the promise and an invitation to pair. */
@Composable
private fun ServerSkyCard(state: AgentSyncUiState, modifier: Modifier) {
    Box(modifier.fillMaxWidth().heightIn(min = 148.dp).clip(RoundedCornerShape(24.dp)).semantics(mergeDescendants = true) {}) {
        Sky(SkyKind.NIGHT, Modifier.matchParentSize(), stars = 18, starSeed = 53)
        val server = state.server
        Column(Modifier.padding(horizontal = 22.dp, vertical = 22.dp)) {
            if (server == null) {
                Text(
                    buildAnnotatedString {
                        append("Your agent, on a machine ")
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append("you") }
                        append(" control.")
                    },
                    style = OrbitTypography.Insight,
                )
                Text(
                    "Pair your Orbit Server and your ring's data syncs there, and nowhere else.",
                    style = OrbitTypography.Meta.copy(color = OrbitPalette.InkSky2), modifier = Modifier.padding(top = 8.dp),
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(if (state.repair != null) OrbitPalette.Attention else OrbitPalette.Optimal)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (state.repair != null) "Needs pairing again" else "Paired",
                        style = OrbitTypography.Label.copy(color = OrbitPalette.InkSky2),
                    )
                }
                Text(
                    server.name, style = OrbitTypography.RingName, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 10.dp).lineBox(OrbitTypography.RingName),
                )
                Text(hostOf(server.url), style = OrbitTypography.Meta.copy(color = OrbitPalette.InkSky2), modifier = Modifier.padding(top = 3.dp))
                Text(
                    buildAnnotatedString {
                        append("Last sync ")
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(state.lastSync.lowercase()) }
                    },
                    style = OrbitTypography.InsightSm, modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun StatusDot(color: Color) {
    Box(Modifier.size(8.dp).clip(CircleShape).background(color))
}

/** A "where does your agent live" choice: tinted card, glyph, title (+ badge), caption, and a radio dot. */
@Composable
private fun PlaceOption(title: String, caption: String, selected: Boolean, enabled: Boolean, symbol: OrbitSymbol, badge: String? = null) {
    val shape = TintedCardDefaults.Shape
    TintedCard(
        if (selected) OrbitPalette.SleepB else OrbitPalette.Ink,
        Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.55f)
            .then(if (selected) Modifier.border(1.dp, OrbitPalette.SleepB.copy(alpha = 0.45f), shape) else Modifier)
            .semantics(mergeDescendants = true) {
                this.selected = selected
                if (!enabled) contentDescription = "$title. $caption"
            },
        amount = if (selected) TintedCardDefaults.Amount else TintedCardDefaults.NeutralAmount,
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Symbol(symbol, size = 22.dp, tint = if (selected) OrbitPalette.SleepB else OrbitPalette.Ink2)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = OrbitTypography.BodyStrong)
                    if (badge != null) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            badge.uppercase(), style = OrbitTypography.Badge.copy(color = OrbitPalette.SleepB),
                            modifier = Modifier.clip(RoundedCornerShape(50)).background(OrbitPalette.SleepB.copy(alpha = 0.14f))
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
                Text(caption, style = OrbitTypography.Meta, modifier = Modifier.padding(top = 4.dp))
            }
            Spacer(Modifier.width(12.dp))
            RadioDot(selected)
        }
    }
}

@Composable
private fun RadioDot(on: Boolean) {
    Box(
        Modifier.size(20.dp).clip(CircleShape).border(1.5.dp, if (on) OrbitPalette.SleepB else OrbitPalette.Ink.copy(alpha = 0.3f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (on) Box(Modifier.size(10.dp).clip(CircleShape).background(OrbitPalette.SleepB))
    }
}

/** How to pair: the three steps, the scan button, and (debug builds) a field for a pasted link. */
@Composable
private fun PairCard(state: AgentSyncUiState, actions: AgentSyncActions, allowPastedLink: Boolean, modifier: Modifier) {
    TintedCard(OrbitPalette.Ink, modifier.fillMaxWidth(), amount = TintedCardDefaults.NeutralAmount) {
        listOf(
            "Run Orbit Server on your Mac, home server or VPS.",
            "In its dashboard open Pair a phone, or run orbit-server pair.",
            "Scan the code it shows. Pairing codes work once, for 10 minutes.",
        ).forEachIndexed { i, step ->
            Row(Modifier.padding(top = if (i == 0) 0.dp else 10.dp), verticalAlignment = Alignment.Top) {
                Text("${i + 1}", style = OrbitTypography.Label.copy(color = OrbitPalette.SleepB), modifier = Modifier.width(20.dp))
                Text(step, style = OrbitTypography.Body.copy(color = OrbitPalette.InkSky2))
            }
        }
        Spacer(Modifier.height(18.dp))
        WarmButton(
            "Scan pairing code", actions.scan, Modifier.fillMaxWidth(), enabled = state.canPair,
            busy = state.busy == AgentBusy.PAIRING, busyText = "Pairing…",
        )
        Text(
            if (state.canPair) "The camera is used only to read the code." else "Nothing will be shared. Switch on at least one kind of data below.",
            style = OrbitTypography.Caption, modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        )
        if (allowPastedLink) {
            var link by remember { mutableStateOf("") }
            Text("Debug · paste a pairing link", style = OrbitTypography.Label, modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(OrbitPalette.Ink.copy(alpha = 0.04f))
                    .border(1.dp, OrbitPalette.Hairline, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                BasicTextField(
                    value = link, onValueChange = { link = it.take(2_000) }, singleLine = true,
                    textStyle = OrbitTypography.Meta.copy(color = OrbitPalette.Ink), cursorBrush = SolidColor(OrbitPalette.SleepB),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Pairing link" },
                    decorationBox = { field ->
                        if (link.isEmpty()) Text("orbit://pair?…", style = OrbitTypography.Meta)
                        field()
                    },
                )
            }
            GhostButton("Pair with link", { actions.pasteLink(link) }, Modifier.fillMaxWidth().padding(top = 10.dp), enabled = link.isNotBlank() && state.canPair)
        }
    }
}

/** An inline message in the attention tint, with an optional action and a close glyph. */
@Composable
private fun Notice(text: String, action: Pair<String, () -> Unit>?, onDismiss: (() -> Unit)?, modifier: Modifier) {
    TintedCard(
        OrbitPalette.Attention, modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        contentPadding = PaddingValues(start = 18.dp, end = 10.dp, top = 14.dp, bottom = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Text(text, style = OrbitTypography.Body, modifier = Modifier.weight(1f).padding(top = 2.dp))
            if (onDismiss != null) {
                Box(
                    Modifier.size(32.dp).clip(CircleShape)
                        .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onDismiss)
                        .semantics { contentDescription = "Dismiss" },
                    contentAlignment = Alignment.Center,
                ) { Symbol(OrbitSymbol.Plus, Modifier.rotate(45f), size = 16.dp, strokeWidth = 1.8f, tint = OrbitPalette.Ink2) }
            }
        }
        if (action != null) {
            GhostButton(action.first, action.second, Modifier.padding(top = 10.dp, end = 8.dp))
        }
    }
}

@Composable
private fun AccessLogCard(log: AccessLogUi, onRetry: () -> Unit, modifier: Modifier) {
    ListCard(modifier) {
        when (log) {
            AccessLogUi.Hidden, AccessLogUi.Loading -> ListRow("Loading…", first = true)
            is AccessLogUi.Failed -> {
                ListRow("Couldn't load the log", first = true, caption = log.message)
                GhostButton("Try again", onRetry, Modifier.padding(bottom = 14.dp))
            }
            is AccessLogUi.Loaded -> if (log.rows.isEmpty()) {
                ListRow("Nothing has read your data yet", first = true, caption = "Agents, scripts and this phone appear here when they use it.")
            } else {
                log.rows.forEachIndexed { i, r -> ListRow(r.who, first = i == 0, caption = r.what, value = r.time) }
            }
        }
    }
}

/** The scope switches, in the mockup's metric hues. */
private data class ScopeRow(val scope: Scope, val label: String, val caption: String?, val symbol: OrbitSymbol, val tint: Color, val estimate: Boolean = false)

private val ScopeRows = listOf(
    ScopeRow(Scope.HEART, "Heart", "Heart rate, HRV, breathing, blood pressure (estimate)", OrbitSymbol.Body, OrbitPalette.HeartA),
    ScopeRow(Scope.SLEEP, "Sleep", "Nights and sleep stages", OrbitSymbol.Sleep, OrbitPalette.SleepB),
    ScopeRow(Scope.ACTIVITY, "Activity", "Steps, distance, calories", OrbitSymbol.Steps, OrbitPalette.ActivityB),
    ScopeRow(Scope.STRESS, "Stress", null, OrbitSymbol.Lotus, OrbitPalette.StressA, estimate = true),
    ScopeRow(Scope.SPO2, "SpO₂", "Blood oxygen", OrbitSymbol.Drop, OrbitPalette.Spo2A),
    ScopeRow(Scope.PROFILE, "Ring & profile", "Ring battery and firmware", OrbitSymbol.Me, OrbitPalette.Good),
)
