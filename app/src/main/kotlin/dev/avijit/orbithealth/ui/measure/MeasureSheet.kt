package dev.avijit.orbithealth.ui.measure

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.avijit.orbithealth.ring.MeasureKind
import dev.avijit.orbithealth.ring.Metric
import dev.avijit.orbithealth.ui.components.EstimateBadge
import dev.avijit.orbithealth.ui.components.GhostButton
import dev.avijit.orbithealth.ui.components.HapticEvent
import dev.avijit.orbithealth.ui.components.OrbitArc
import dev.avijit.orbithealth.ui.components.OrbitArcDefaults
import dev.avijit.orbithealth.ui.components.OrbitSheet
import dev.avijit.orbithealth.ui.components.OrbitSymbol
import dev.avijit.orbithealth.ui.components.RollingNumber
import dev.avijit.orbithealth.ui.components.Symbol
import dev.avijit.orbithealth.ui.components.TintedCardDefaults
import dev.avijit.orbithealth.ui.components.WarmButton
import dev.avijit.orbithealth.ui.components.cardTint
import dev.avijit.orbithealth.ui.components.livingEnter
import dev.avijit.orbithealth.ui.components.rememberHaptics
import dev.avijit.orbithealth.ui.components.rememberMotionEnabled
import dev.avijit.orbithealth.ui.sky.Sky
import dev.avijit.orbithealth.ui.sky.SkyKind
import dev.avijit.orbithealth.ui.theme.OrbitPalette
import dev.avijit.orbithealth.ui.theme.OrbitTypography
import dev.avijit.orbithealth.ui.theme.lineBox
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** A metric's look: its tint hue, glyph and living sky (with the sky's resting parameter). */
internal data class MeasureLook(val hue: Color, val symbol: OrbitSymbol, val sky: SkyKind, val skyParam: Float)

internal fun lookOf(kind: MeasureKind): MeasureLook = when (kind) {
    MeasureKind.HEART_RATE -> MeasureLook(OrbitPalette.HeartA, OrbitSymbol.Body, SkyKind.PULSE, 60f)
    MeasureKind.SPO2 -> MeasureLook(OrbitPalette.Spo2A, OrbitSymbol.Drop, SkyKind.TIDE, 0f)
    // HRV shares Readiness's hue; on First light the sun rises into the arc's open bottom.
    MeasureKind.HRV -> MeasureLook(OrbitPalette.ReadinessA, OrbitSymbol.Hrv, SkyKind.FIRST_LIGHT, 0.62f)
    MeasureKind.STRESS -> MeasureLook(OrbitPalette.StressA, OrbitSymbol.Lotus, SkyKind.CALM, 0.3f)
    MeasureKind.BLOOD_PRESSURE -> MeasureLook(OrbitPalette.HeartB, OrbitSymbol.Gauge, SkyKind.PULSE, 60f)
}

/** "SpO₂" with the 2 set small and low, as the mockup does; other names as they are. */
internal fun metricName(name: String): AnnotatedString = buildAnnotatedString {
    name.forEach { c ->
        if (c == '₂') withStyle(SpanStyle(fontSize = 0.62.em, baselineShift = BaselineShift(-0.18f))) { append('2') } else append(c)
    }
}

private val CardShape = RoundedCornerShape(28.dp)
private val ReadingHeight = 340.dp
private val TileShape = TintedCardDefaults.MiniShape
private val PillShape = RoundedCornerShape(percent = 50)
private val HHmm: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/**
 * The Measure sheet (spec §5) in the Haze-glass [OrbitSheet], shown while [MeasureViewModel.state] is visible.
 * Dismissing it (swipe, scrim, back) or leaving the screen cancels the reading, which stops the ring.
 */
@Composable
fun MeasureSheet(vm: MeasureViewModel, zone: ZoneId = ZoneId.systemDefault()) {
    val st by vm.state.collectAsStateWithLifecycle()
    if (!st.visible) return
    // The screen under the sheet went away (another tab, back, process death of the UI): stop the ring.
    DisposableEffect(vm) { onDispose { vm.dismiss() } }
    val phase = st.phase
    val title = when (phase) {
        MeasurePhase.Picker -> AnnotatedString("Take a reading")
        is MeasurePhase.Measuring -> metricName(MeasureCopy.name(phase.kind))
        is MeasurePhase.Done -> metricName(MeasureCopy.name(phase.kind))
        is MeasurePhase.Failed -> metricName(MeasureCopy.name(phase.kind))
    }
    OrbitSheet(title, onDismiss = vm::dismiss, kicker = "Measure now") {
        MeasureContent(
            st, zone = zone,
            onStart = vm::start, onAgain = vm::again, onDone = vm::dismiss, onStop = vm::showPicker,
        )
    }
}

/** The sheet's body for [st] (separate from the sheet window so it can be tested and previewed). */
@Composable
fun MeasureContent(
    st: MeasureUiState,
    onStart: (MeasureKind) -> Unit,
    onAgain: () -> Unit,
    onDone: () -> Unit,
    onStop: () -> Unit,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    MeasureHaptics(st.phase)
    val phase = st.phase
    Column(Modifier.fillMaxWidth()) {
        if (phase == MeasurePhase.Picker) {
            Picker(st.tiles, onStart)
        } else {
            Reading(st, phase, onStart, onAgain, onDone, onStop, zone)
        }
        // Breathing room under the last tile or pill, above the sheet's edge and the navigation bar (zero when the
        // sheet already pads for it).
        Spacer(Modifier.height(24.dp))
        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
    }
}

@Composable
private fun Reading(
    st: MeasureUiState,
    phase: MeasurePhase,
    onStart: (MeasureKind) -> Unit,
    onAgain: () -> Unit,
    onDone: () -> Unit,
    onStop: () -> Unit,
    zone: ZoneId,
) {
    Column(Modifier.fillMaxWidth()) {
        val kind = phaseKind(phase) ?: return@Column
        val tile = st.tiles.firstOrNull { it.kind == kind }
        ReadingCard(phase, estimate = tile?.estimate ?: false, live = tile?.live ?: true, zone = zone)
        Spacer(Modifier.height(16.dp))
        when (phase) {
            is MeasurePhase.Measuring -> GhostButton("Stop", onStop, Modifier.fillMaxWidth())
            is MeasurePhase.Done -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                WarmButton("Done", onDone, Modifier.weight(1f))
                GhostButton("Measure again", onAgain, Modifier.weight(1f), contentPadding = 12.dp)
            }
            is MeasurePhase.Failed -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                WarmButton("Try again", onAgain, Modifier.weight(1f))
                GhostButton("Close", onDone, Modifier.weight(1f), contentPadding = 12.dp)
            }
            MeasurePhase.Picker -> Unit
        }
        val others = st.tiles.filter { it.kind != kind }
        if (others.isNotEmpty()) {
            Text(
                "Or measure", style = OrbitTypography.Eyebrow,
                modifier = Modifier.padding(top = 24.dp).lineBox(OrbitTypography.Eyebrow),
            )
            PillRow(Modifier.padding(top = 10.dp)) { others.forEach { OtherPill(it, onStart) } }
        }
    }
}

/**
 * One row of pills that scrolls sideways edge to edge: it runs out under the sheet's 24dp gutters (so a pill is never
 * cut at the content edge) and fades out over those gutters, starting and resting on the gutter line.
 */
@Composable
private fun PillRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val gutter = 24.dp
    Row(
        modifier
            .layout { m, c ->
                val extra = gutter.roundToPx()
                val w = c.maxWidth + 2 * extra
                val p = m.measure(c.copy(minWidth = w, maxWidth = w))
                layout(c.maxWidth, p.height) { p.place(-extra, 0) }
            }
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                val f = gutter.toPx() / size.width
                drawRect(
                    Brush.horizontalGradient(0f to Color.Transparent, f to Color.Black, 1f - f to Color.Black, 1f to Color.Transparent),
                    blendMode = BlendMode.DstIn,
                )
            }
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = gutter),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

private fun phaseKind(p: MeasurePhase): MeasureKind? = when (p) {
    MeasurePhase.Picker -> null
    is MeasurePhase.Measuring -> p.kind
    is MeasurePhase.Done -> p.kind
    is MeasurePhase.Failed -> p.kind
}

/** Tokens §6: a context click when a reading starts, a confirm when its result lands. */
@Composable
private fun MeasureHaptics(phase: MeasurePhase) {
    val haptics = rememberHaptics()
    val last = remember { arrayOf<MeasurePhase?>(null) }
    LaunchedEffect(phase) {
        val before = last[0]
        last[0] = phase
        val startedNow = phase is MeasurePhase.Measuring &&
            (before !is MeasurePhase.Measuring || before.kind != phase.kind)
        when {
            startedNow -> haptics.perform(HapticEvent.MeasureStart)
            phase is MeasurePhase.Done && before is MeasurePhase.Measuring -> haptics.perform(HapticEvent.MeasureDone)
        }
    }
}

// ---- Picker ----

@Composable
private fun Picker(tiles: List<MeasureTile>, onStart: (MeasureKind) -> Unit) {
    if (tiles.isEmpty()) {
        Text(
            "Your ring doesn't take readings on demand.", style = OrbitTypography.Lead,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        return
    }
    Text(
        "Your ring can take these now. Sit comfortably and keep your hand still.", style = OrbitTypography.Meta,
        modifier = Modifier.padding(bottom = 16.dp),
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        tiles.chunked(2).forEachIndexed { row, pair ->
            if (pair.size == 1) {
                WideTile(pair[0], onStart, Modifier.livingEnter(60 * row * 2))
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    pair.forEachIndexed { i, t -> Tile(t, onStart, Modifier.weight(1f).livingEnter(60 * (row * 2 + i))) }
                }
            }
        }
    }
}

private fun tileDescription(t: MeasureTile): String =
    "${MeasureCopy.spokenName(t.kind)}, about ${t.typicalSeconds} seconds" + if (t.estimate) ", estimate" else ""

@Composable
private fun Modifier.tileSurface(t: MeasureTile, onStart: (MeasureKind) -> Unit): Modifier {
    val look = lookOf(t.kind)
    return this
        .clip(TileShape)
        .background(cardTint(look.hue))
        .drawBehind {
            // A soft glow of the hue from the glyph's corner: depth from light, not elevation.
            drawCircle(
                Brush.radialGradient(listOf(look.hue.copy(alpha = 0.22f), Color.Transparent), center = androidx.compose.ui.geometry.Offset(32.dp.toPx(), 32.dp.toPx()), radius = 120.dp.toPx()),
                radius = 120.dp.toPx(), center = androidx.compose.ui.geometry.Offset(32.dp.toPx(), 32.dp.toPx()),
            )
        }
        .border(1.dp, OrbitPalette.HairlineWarm, TileShape)
        .clickable(role = Role.Button, onClickLabel = "Measure") { onStart(t.kind) }
        .clearAndSetSemantics { contentDescription = tileDescription(t) }
}

@Composable
private fun GlyphDisc(kind: MeasureKind) {
    val look = lookOf(kind)
    Box(Modifier.size(36.dp).background(look.hue.copy(alpha = 0.16f), CircleShape), contentAlignment = Alignment.Center) {
        Symbol(look.symbol, size = 18.dp, strokeWidth = 1.7f, tint = OrbitPalette.Ink)
    }
}

@Composable
private fun Tile(t: MeasureTile, onStart: (MeasureKind) -> Unit, modifier: Modifier) {
    Column(modifier.tileSurface(t, onStart).padding(start = 16.dp, end = 14.dp, top = 16.dp, bottom = 16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            GlyphDisc(t.kind)
            Spacer(Modifier.weight(1f))
            if (t.estimate) EstimateBadge()
        }
        Text(
            metricName(MeasureCopy.name(t.kind)), style = OrbitTypography.BodyStrong, maxLines = 1,
            modifier = Modifier.padding(top = 18.dp).lineBox(OrbitTypography.BodyStrong),
        )
        Text(
            MeasureCopy.duration(t.typicalSeconds), style = OrbitTypography.Meta, maxLines = 1,
            modifier = Modifier.padding(top = 2.dp).lineBox(OrbitTypography.Meta),
        )
    }
}

@Composable
private fun WideTile(t: MeasureTile, onStart: (MeasureKind) -> Unit, modifier: Modifier) {
    Row(
        modifier.fillMaxWidth().tileSurface(t, onStart).padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlyphDisc(t.kind)
        Column(Modifier.padding(start = 14.dp).weight(1f)) {
            Text(metricName(MeasureCopy.name(t.kind)), style = OrbitTypography.BodyStrong, maxLines = 1, modifier = Modifier.lineBox(OrbitTypography.BodyStrong))
            Text(MeasureCopy.duration(t.typicalSeconds), style = OrbitTypography.Meta, maxLines = 1, modifier = Modifier.padding(top = 2.dp).lineBox(OrbitTypography.Meta))
        }
        if (t.estimate) EstimateBadge()
    }
}

@Composable
private fun OtherPill(t: MeasureTile, onStart: (MeasureKind) -> Unit) {
    val look = lookOf(t.kind)
    Row(
        Modifier.height(40.dp).clip(PillShape).background(cardTint(look.hue))
            .border(1.dp, OrbitPalette.HairlineWarm, PillShape)
            .clickable(role = Role.Button, onClickLabel = "Measure") { onStart(t.kind) }
            .clearAndSetSemantics { contentDescription = tileDescription(t) }
            .padding(start = 12.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Symbol(look.symbol, size = 16.dp, strokeWidth = 1.8f, tint = OrbitPalette.Ink)
        Spacer(Modifier.width(8.dp))
        Text(metricName(MeasureCopy.name(t.kind)), style = OrbitTypography.Tab, maxLines = 1)
    }
}

// ---- Reading card ----

/**
 * The reading on the metric's living sky: an overline, the countdown arc (OrbitArc) around the live numeral, and one
 * line beneath. Measuring: "Keep still…" and the time left; Done: the value and its context; Failed: the honest reason.
 */
@Composable
private fun ReadingCard(phase: MeasurePhase, estimate: Boolean, live: Boolean, zone: ZoneId) {
    val kind = phaseKind(phase) ?: return
    val look = lookOf(kind)
    val motion = rememberMotionEnabled()
    val values = when (phase) {
        is MeasurePhase.Measuring -> phase.values
        is MeasurePhase.Done -> phase.values
        else -> null
    }
    val hr = values?.get(Metric.HEART_RATE)?.toFloat()
    val skyParam = when (look.sky) {
        SkyKind.PULSE -> hr ?: look.skyParam
        SkyKind.CALM -> (values?.get(Metric.STRESS)?.toFloat()?.div(100f)) ?: look.skyParam
        else -> look.skyParam
    }
    val failed = phase is MeasurePhase.Failed
    Box(Modifier.fillMaxWidth().height(ReadingHeight).clip(CardShape)) {
        Sky(
            look.sky, Modifier.fillMaxSize(), intensity = if (failed) 0.55f else 1f, param = skyParam,
            animate = motion && !failed,
        )
        // Text-safe scrim (tokens §7): the beating rose and the tide sit where the lines are.
        Spacer(
            Modifier.fillMaxSize().drawBehind {
                drawRect(Brush.verticalGradient(0.5f to Color.Transparent, 1f to OrbitPalette.Base0.copy(alpha = 0.5f)))
            },
        )
        Overline(phase, estimate, zone, Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp))
        Box(Modifier.align(Alignment.TopCenter).padding(top = 58.dp), contentAlignment = Alignment.Center) {
            CountdownArc(phase, motion)
            Center(phase, kind, live)
        }
        BottomLines(phase, kind, estimate, Modifier.align(Alignment.BottomCenter).padding(start = 24.dp, end = 24.dp, bottom = 20.dp))
    }
}

@Composable
private fun Overline(phase: MeasurePhase, estimate: Boolean, zone: ZoneId, modifier: Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        val receiving = phase is MeasurePhase.Measuring && phase.values != null
        if (receiving) {
            Box(
                Modifier.size(6.dp).drawBehind {
                    drawCircle(Brush.radialGradient(listOf(OrbitPalette.HeartA.copy(alpha = 0.6f), Color.Transparent), radius = size.minDimension * 1.8f), radius = size.minDimension * 1.8f)
                }.background(OrbitPalette.PlanetCore, CircleShape),
            )
            Spacer(Modifier.width(8.dp))
        }
        val text = when (phase) {
            is MeasurePhase.Measuring -> when {
                !phase.started -> "Connecting"
                receiving -> "Live"
                else -> "Measuring"
            }
            is MeasurePhase.Done -> "Measured · ${phase.at.atZone(zone).format(HHmm)}"
            is MeasurePhase.Failed -> MeasureCopy.failureTitle(phase.reason)
            MeasurePhase.Picker -> ""
        }
        Text(text.uppercase(), style = OrbitTypography.Overline, maxLines = 1, modifier = Modifier.weight(1f).lineBox(OrbitTypography.Overline))
        if (estimate) EstimateBadge()
    }
}

/** The arc fills over the reading's typical length (a step a second under reduced motion), and closes on a result. */
@Composable
private fun CountdownArc(phase: MeasurePhase, motion: Boolean) {
    val key = phaseKind(phase)
    val target = when (phase) {
        is MeasurePhase.Measuring ->
            if (!phase.started) 0f
            // Aim one second ahead so the planet glides between ticks instead of jumping.
            else ((phase.elapsedSeconds + if (motion) 1 else 0).toFloat() / phase.typicalSeconds).coerceIn(0f, 1f)
        is MeasurePhase.Done -> 1f
        else -> 0f
    }
    val anim = remember(key, phase is MeasurePhase.Measuring && !phase.started) { Animatable(0f) }
    LaunchedEffect(target, motion) {
        if (!motion) anim.snapTo(target)
        else if (phase is MeasurePhase.Done) anim.animateTo(target, OrbitArcDefaults.RevealSpring)
        else anim.animateTo(target, tween(1_000, easing = LinearEasing))
    }
    OrbitArc(
        if (phase is MeasurePhase.Failed) null else anim.value,
        size = OrbitArcDefaults.DetailSize, animateIn = false,
    )
}

@Composable
private fun Center(phase: MeasurePhase, kind: MeasureKind, live: Boolean) {
    when (phase) {
        is MeasurePhase.Failed -> Symbol(lookOf(kind).symbol, size = 30.dp, strokeWidth = 1.5f, tint = OrbitPalette.InkSky2)
        // "Reading…" until the ring sends a value (the TK5 sends BP and HRV in a burst near the end; stress never
        // streams), then the numeral rolls in.
        is MeasurePhase.Measuring -> AnimatedContent(
            live && phase.values != null,
            transitionSpec = { (fadeIn(tween(320)) + slideInVertically(tween(360)) { it / 6 }) togetherWith fadeOut(tween(180)) },
            label = "measure-numeral",
        ) { showValue ->
            if (showValue) {
                Numeral(kind, MeasureCopy.value(kind, phase.values))
            } else {
                Text(
                    MeasureCopy.READING, style = OrbitTypography.InsightLg, color = OrbitPalette.Ink,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
        is MeasurePhase.Done -> Numeral(kind, MeasureCopy.value(kind, phase.values))
        MeasurePhase.Picker -> Unit
    }
}

@Composable
private fun Numeral(kind: MeasureKind, value: String?) {
    val style = if (kind == MeasureKind.BLOOD_PRESSURE) OrbitTypography.NumMd else OrbitTypography.NumLg
    val unit = MeasureCopy.unit(kind)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.semantics(mergeDescendants = true) {
            contentDescription = value?.let { "$it ${spokenUnit(kind)}" } ?: "Waiting for a reading"
            liveRegion = LiveRegionMode.Polite
        },
    ) {
        RollingNumber(value ?: "—", style, color = if (value == null) OrbitPalette.Ink.copy(alpha = 0.55f) else OrbitPalette.Ink)
        if (unit.isNotEmpty()) {
            Text(
                unit, style = OrbitTypography.Unit.copy(fontSize = 15.sp, lineHeight = 20.sp), color = OrbitPalette.InkSky2,
                modifier = Modifier.padding(top = 2.dp).lineBox(OrbitTypography.Unit.copy(fontSize = 15.sp, lineHeight = 20.sp)),
            )
        }
    }
}

private fun spokenUnit(kind: MeasureKind): String = when (kind) {
    MeasureKind.HEART_RATE -> "beats per minute"
    MeasureKind.SPO2 -> "percent"
    MeasureKind.HRV -> "milliseconds"
    MeasureKind.STRESS -> ""
    MeasureKind.BLOOD_PRESSURE -> "millimetres of mercury"
}

@Composable
private fun BottomLines(phase: MeasurePhase, kind: MeasureKind, estimate: Boolean, modifier: Modifier) {
    val (lead, meta) = when (phase) {
        is MeasurePhase.Measuring -> MeasureCopy.KEEP_STILL to
            (if (phase.started) MeasureCopy.remaining(phase.typicalSeconds, phase.elapsedSeconds) else MeasureCopy.CONNECTING)
        // One short line each, never a wrapped run-on: "Heart rate 88 bpm" over "Estimated by the ring's light sensor".
        is MeasurePhase.Done -> phase.context to listOfNotNull(
            MeasureCopy.secondary(kind, phase.values),
            if (estimate) MeasureCopy.ESTIMATE_NOTE else null,
        ).joinToString("\n").ifEmpty { null }
        is MeasurePhase.Failed -> MeasureCopy.failure(phase.reason, kind) to null
        MeasurePhase.Picker -> "" to null
    }
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        AnimatedContent(
            lead, transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) }, label = "measure-lead",
        ) { text ->
            Text(
                text, style = OrbitTypography.InsightSm, textAlign = TextAlign.Center, maxLines = 3,
                modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        if (meta != null) {
            Text(
                meta, style = OrbitTypography.Caption.copy(fontSize = 12.sp, lineHeight = 16.sp), color = OrbitPalette.InkSky2,
                textAlign = TextAlign.Center, maxLines = 2, modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
