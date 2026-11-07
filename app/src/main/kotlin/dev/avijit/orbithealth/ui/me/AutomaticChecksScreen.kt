package dev.avijit.orbithealth.ui.me

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.avijit.orbithealth.ring.MonitorKind
import dev.avijit.orbithealth.ui.components.HapticEvent
import dev.avijit.orbithealth.ui.components.OrbitIconButton
import dev.avijit.orbithealth.ui.components.OrbitIconButtonDefaults
import dev.avijit.orbithealth.ui.components.OrbitSymbol
import dev.avijit.orbithealth.ui.components.OrbitToggle
import dev.avijit.orbithealth.ui.components.Symbol
import dev.avijit.orbithealth.ui.components.TintedCard
import dev.avijit.orbithealth.ui.components.livingEnter
import dev.avijit.orbithealth.ui.components.rememberHaptics
import dev.avijit.orbithealth.ui.components.rememberMotionEnabled
import dev.avijit.orbithealth.ui.components.subscriptTwoText
import dev.avijit.orbithealth.ui.sky.Sky
import dev.avijit.orbithealth.ui.sky.SkyKind
import dev.avijit.orbithealth.ui.theme.OrbitPalette
import dev.avijit.orbithealth.ui.theme.OrbitTypography
import dev.avijit.orbithealth.ui.theme.lineBox

private val Gutter = 24.dp

/** What the rows can do, so [AutomaticChecksContent] can be shown and tested without a ViewModel. */
class AutomaticChecksActions(
    val setEnabled: (MonitorKind, Boolean) -> Unit = { _, _ -> },
    val setInterval: (MonitorKind, Int) -> Unit = { _, _ -> },
    val setOvernightOnly: (MonitorKind, Boolean) -> Unit = { _, _ -> },
    val back: () -> Unit = {},
)

@Composable
fun AutomaticChecksScreen(vm: AutomaticChecksViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    AutomaticChecksContent(
        state,
        AutomaticChecksActions(vm::setEnabled, vm::setInterval, vm::setOvernightOnly, onBack),
    )
}

/** The metric's hue pair (tokens §1) and glyph for each check. */
private data class CheckLook(val a: Color, val b: Color, val symbol: OrbitSymbol)

private fun lookOf(kind: MonitorKind) = when (kind) {
    MonitorKind.HEART_RATE -> CheckLook(OrbitPalette.HeartA, OrbitPalette.HeartB, OrbitSymbol.Body)
    MonitorKind.SPO2 -> CheckLook(OrbitPalette.Spo2A, OrbitPalette.Spo2B, OrbitSymbol.Drop)
    MonitorKind.BLOOD_PRESSURE -> CheckLook(OrbitPalette.HeartB, OrbitPalette.HeartA, OrbitSymbol.Gauge)
    MonitorKind.HRV -> CheckLook(OrbitPalette.ReadinessA, OrbitPalette.Optimal, OrbitSymbol.Hrv)
    MonitorKind.TEMPERATURE -> CheckLook(OrbitPalette.ActivityA, OrbitPalette.ActivityB, OrbitSymbol.Flame)
    MonitorKind.STRESS -> CheckLook(OrbitPalette.StressA, OrbitPalette.StressB, OrbitSymbol.Lotus)
}

/**
 * Automatic checks in Living Skies: a detail header ("My ring" over "Automatic *checks*"), a lead, where the values come
 * from, the quiet sending/result line, then one card per check in its metric's tint: glyph, name and a plain sentence,
 * the switch, the ring's allowed intervals as a gliding segmented track, and the battery note.
 */
@Composable
fun AutomaticChecksContent(state: AutomaticChecksUiState, actions: AutomaticChecksActions, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(Brush.verticalGradient(listOf(OrbitPalette.Base0, OrbitPalette.Base1)))) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().navigationBarsPadding()
                .padding(bottom = 32.dp),
        ) {
            OrbitIconButton(
                OrbitSymbol.Back, onClick = actions.back, size = 40.dp, contentDescription = "Back to Me",
                modifier = Modifier.padding(start = 16.dp - OrbitIconButtonDefaults.spill(40.dp), top = 8.dp),
            )
            Column(Modifier.padding(start = Gutter, end = Gutter, top = 10.dp).livingEnter(0)) {
                Text(AutomaticChecksCopy.TITLE_EYEBROW, style = OrbitTypography.Eyebrow, modifier = Modifier.lineBox(OrbitTypography.Eyebrow))
                Text(
                    buildAnnotatedString {
                        append("Automatic ")
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append("checks") }
                    },
                    style = OrbitTypography.Title,
                    modifier = Modifier.padding(top = 2.dp).lineBox(OrbitTypography.Title).semantics { heading() },
                )
                Text(AutomaticChecksCopy.LEAD, style = OrbitTypography.Lead, modifier = Modifier.padding(top = 8.dp))
            }
            if (!state.loaded) return@Column

            val empty = state.empty
            if (empty != null) {
                EmptyRing(empty, Modifier.padding(start = Gutter, end = Gutter, top = 24.dp).livingEnter(60))
                return@Column
            }

            SourceLine(state.source, state.caption, Modifier.padding(start = Gutter, end = Gutter, top = 18.dp).livingEnter(60))
            StatusLine(state.status, Modifier.padding(horizontal = Gutter))

            Column(
                Modifier.padding(start = Gutter, end = Gutter, top = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                state.rows.forEachIndexed { i, row -> CheckCard(row, actions, Modifier.livingEnter(120 + 60 * i)) }
            }
            state.measureOnly?.let { note ->
                Text(
                    note, style = OrbitTypography.Meta,
                    modifier = Modifier.padding(start = Gutter + 4.dp, end = Gutter, top = 16.dp).livingEnter(120 + 60 * state.rows.size),
                )
            }
            // Said once for the whole screen: true of every check, and no fake numbers.
            Text(
                AutomaticChecksCopy.BATTERY_NOTE, style = OrbitTypography.Meta,
                modifier = Modifier.padding(start = Gutter + 4.dp, end = Gutter, top = 16.dp).livingEnter(120 + 60 * state.rows.size),
            )
        }
    }
}

/** Where the values come from: a small planet dot (lit once the ring has them) and the caption. */
@Composable
private fun SourceLine(source: ScheduleSource, caption: String, modifier: Modifier) {
    Row(modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
        val lit = source != ScheduleSource.NOT_SENT
        Box(
            Modifier.size(8.dp).clip(CircleShape)
                .then(
                    if (lit) Modifier.background(Brush.linearGradient(listOf(OrbitPalette.WarmPlanetA, OrbitPalette.Optimal)))
                    else Modifier.border(1.dp, OrbitPalette.Ink2, CircleShape),
                ),
        )
        Spacer(Modifier.width(10.dp))
        Text(caption, style = OrbitTypography.Meta)
    }
}

/** "Sending to your ring…" with a breathing dot, then what the ring did; nothing while idle. */
@Composable
private fun StatusLine(status: ApplyStatus, modifier: Modifier) {
    AnimatedVisibility(status !is ApplyStatus.Idle, enter = fadeIn(tween(220)) + expandVertically(), exit = fadeOut() + shrinkVertically()) {
        AnimatedContent(
            targetState = status,
            transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
            label = "status",
            modifier = modifier.padding(top = 12.dp).semantics { liveRegion = LiveRegionMode.Polite },
        ) { st ->
            val shape = RoundedCornerShape(18.dp)
            Row(
                Modifier.fillMaxWidth().clip(shape).background(OrbitPalette.Ink.copy(alpha = 0.05f))
                    .border(1.dp, OrbitPalette.HairlineWarm, shape).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when (st) {
                    is ApplyStatus.Done -> if (st.ok) {
                        Symbol(OrbitSymbol.Check, size = 16.dp, strokeWidth = 2f, tint = OrbitPalette.Optimal)
                    } else {
                        Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
                            Box(Modifier.size(7.dp).clip(CircleShape).background(OrbitPalette.Attention))
                        }
                    }
                    else -> BreathingDot()
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    when (st) {
                        is ApplyStatus.Done -> st.message
                        else -> AutomaticChecksCopy.SENDING
                    },
                    style = OrbitTypography.Body.copy(color = if (st is ApplyStatus.Done) OrbitPalette.Ink else OrbitPalette.Ink2),
                )
            }
        }
    }
}

@Composable
private fun BreathingDot() {
    val motion = rememberMotionEnabled()
    val t = rememberInfiniteTransition(label = "breath")
    val k by t.animateFloat(0.55f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "k")
    Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(14.dp).graphicsLayer { val s = if (motion) k else 0.8f; scaleX = s; scaleY = s; alpha = (1.2f - s).coerceIn(0f, 1f) }
                .clip(CircleShape).background(OrbitPalette.PlanetB.copy(alpha = 0.45f)),
        )
        Box(Modifier.size(7.dp).clip(CircleShape).background(Brush.linearGradient(listOf(OrbitPalette.PlanetA, OrbitPalette.PlanetB))))
    }
}

/** One check: its tinted card with the switch, the allowed intervals, the window option and the battery note. */
@Composable
private fun CheckCard(row: CheckRowUi, actions: AutomaticChecksActions, modifier: Modifier) {
    val look = lookOf(row.kind)
    TintedCard(look.a, modifier.fillMaxWidth(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).background(look.a.copy(alpha = 0.16f), CircleShape), contentAlignment = Alignment.Center) {
                Symbol(look.symbol, size = 18.dp, strokeWidth = 1.7f, tint = look.b.takeIf { row.kind != MonitorKind.HEART_RATE } ?: look.a)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                Text(subscriptTwoText(row.title), style = OrbitTypography.BodyStrong, modifier = Modifier.lineBox(OrbitTypography.BodyStrong))
                AnimatedContent(row.line, transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(140)) }, label = "line") { line ->
                    Text(line, style = OrbitTypography.Meta, modifier = Modifier.padding(top = 2.dp))
                }
            }
            if (row.showSwitch) {
                Spacer(Modifier.width(12.dp))
                OrbitToggle(
                    row.enabled, onCheckedChange = { actions.setEnabled(row.kind, it) },
                    modifier = Modifier.semantics { contentDescription = "Automatic ${AutomaticChecksCopy.noun(row.kind)} checks" },
                )
            }
        }
        AnimatedVisibility(row.enabled, modifier = Modifier.padding(bottom = 2.dp), enter = fadeIn(tween(220)) + expandVertically(), exit = fadeOut(tween(160)) + shrinkVertically()) {
            Column {
                Text("Check every", style = OrbitTypography.Label, modifier = Modifier.padding(top = 18.dp, bottom = 8.dp).lineBox(OrbitTypography.Label))
                IntervalTrack(row, look) { actions.setInterval(row.kind, it) }
                if (row.showOvernight) {
                    Row(Modifier.padding(top = 14.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Overnight only", style = OrbitTypography.Body)
                            Text("Quiet through the day, checks while you sleep.", style = OrbitTypography.Meta, modifier = Modifier.padding(top = 2.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        OrbitToggle(
                            row.overnightOnly, onCheckedChange = { actions.setOvernightOnly(row.kind, it) },
                            modifier = Modifier.semantics { contentDescription = "Overnight only" },
                        )
                    }
                }
            }
        }
        AnimatedVisibility(row.ringLine != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Symbol(OrbitSymbol.Sync, size = 14.dp, strokeWidth = 1.8f, tint = OrbitPalette.Ink2)
                Spacer(Modifier.width(8.dp))
                Text(row.ringLine.orEmpty(), style = OrbitTypography.Meta)
            }
        }
    }
}

/**
 * The ring's allowed intervals and nothing else, as one capsule track (tokens segmented: ink 6%, hairline): equal
 * segments, the numeral with a small unit, and a lit pill in the metric's gradient that glides to the choice.
 */
@Composable
private fun IntervalTrack(row: CheckRowUi, look: CheckLook, onSelect: (Int) -> Unit) {
    val haptics = rememberHaptics()
    val outer = RoundedCornerShape(22.dp)
    val inner = RoundedCornerShape(19.dp)
    // -1 (a value outside the list) shows no selection rather than a wrong one.
    val idx = row.intervals.indexOf(row.intervalMin)
    BoxWithConstraints(
        Modifier.fillMaxWidth().height(44.dp).clip(outer).background(OrbitPalette.Ink.copy(alpha = 0.06f))
            .border(1.dp, OrbitPalette.Hairline, outer).padding(3.dp),
    ) {
        val n = row.intervals.size.coerceAtLeast(1)
        val seg = maxWidth / n
        val x by animateDpAsState(seg * idx.coerceAtLeast(0), spring(dampingRatio = 0.8f, stiffness = 480f), label = "pill")
        if (idx >= 0) Box(
            Modifier.offset(x = x).width(seg).fillMaxHeight().clip(inner)
                .background(Brush.horizontalGradient(listOf(look.a.copy(alpha = 0.50f), look.b.copy(alpha = 0.38f))))
                .border(1.dp, OrbitPalette.Ink.copy(alpha = 0.16f), inner),
        )
        Row(Modifier.fillMaxSize().selectableGroup()) {
            row.intervals.forEachIndexed { i, min ->
                val on = i == idx
                val ink by animateColorAsState(if (on) OrbitPalette.Ink else OrbitPalette.Ink2, tween(200), label = "ink")
                val (num, unit) = AutomaticChecksCopy.chip(min)
                Box(
                    Modifier.weight(1f).fillMaxHeight()
                        .selectable(on, remember { MutableInteractionSource() }, indication = null, role = Role.RadioButton) {
                            if (!on) {
                                haptics.perform(HapticEvent.TabChange)
                                onSelect(min)
                            }
                        }
                        .semantics(mergeDescendants = true) { contentDescription = AutomaticChecksCopy.every(min).replaceFirstChar { it.uppercase() } },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        buildAnnotatedString {
                            append(num)
                            withStyle(SpanStyle(fontSize = OrbitTypography.Caption.fontSize, color = ink.copy(alpha = ink.alpha * 0.8f))) { append(" $unit") }
                        },
                        style = OrbitTypography.Segment.copy(color = ink, lineHeight = 16.sp, fontFeatureSettings = "tnum"),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** No ring yet, or capabilities not known: a small night sky and one calm sentence. */
@Composable
private fun EmptyRing(text: String, modifier: Modifier) {
    Box(modifier.fillMaxWidth().height(168.dp).clip(RoundedCornerShape(24.dp)).semantics(mergeDescendants = true) {}) {
        Sky(SkyKind.NIGHT, Modifier.matchParentSize(), stars = 18, starSeed = 47)
        Text(
            text, style = OrbitTypography.Insight,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
        )
    }
}
