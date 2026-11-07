package dev.avijit.orbithealth.ui.today

import dev.avijit.orbithealth.ui.components.rememberMotionEnabled
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Canvas
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.avijit.orbithealth.OrbitApp
import dev.avijit.orbithealth.ui.components.EstimateBadge
import dev.avijit.orbithealth.ui.components.GhostButton
import dev.avijit.orbithealth.ui.components.GoalProgressRow
import dev.avijit.orbithealth.ui.components.LocalHazeState
import dev.avijit.orbithealth.ui.components.MiniMetricCard
import dev.avijit.orbithealth.ui.components.OrbitBottomBarDefaults
import dev.avijit.orbithealth.ui.components.OrbitSymbol
import dev.avijit.orbithealth.ui.components.PlanetDots
import dev.avijit.orbithealth.ui.components.TintedCard
import dev.avijit.orbithealth.ui.components.TintedCardDefaults
import dev.avijit.orbithealth.ui.components.WarmButton
import dev.avijit.orbithealth.ui.components.livingEnter
import dev.avijit.orbithealth.ui.theme.OrbitPalette
import dev.avijit.orbithealth.ui.theme.OrbitTypography
import dev.avijit.orbithealth.ui.theme.lineBox
import dev.avijit.orbithealth.ui.components.VendorAppCopy
import dev.chrisbanes.haze.hazeSource
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToInt

/** Coexistence banner copy (spec §5.13) for YCBT's SmartHealth; other vendors' names come from [VendorAppCopy]. */
val CoexistenceCopy = VendorAppCopy.bannerBody("SmartHealth")

private val Gutter = 24.dp

@Composable
fun TodayScreen(vm: TodayViewModel, onOpen: (HeroMetric) -> Unit = {}, onMeasure: () -> Unit = {}) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    TodayContent(
        state,
        onRefresh = vm::refresh,
        onDismissBanner = vm::dismissBanner,
        onUninstall = {
            val vendor = (context.applicationContext as? OrbitApp)?.graph?.coexistence?.installedVendorPackage()
                ?: return@TodayContent
            val intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:$vendor"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try { context.startActivity(intent) } catch (_: ActivityNotFoundException) { }
        },
        onOpen = onOpen,
        onMeasure = onMeasure,
    )
}

/**
 * Today, Living Skies (mockups screen-03/04/05): the date and sync line, a Fraunces greeting and the day in a sentence,
 * the swipeable hero (Readiness · Sleep · Activity skies), Heart / HRV / SpO₂ mini cards, today's goals as warm progress
 * rows and the stress ribbon. Pull down to sync now. The whole screen is the bottom bar's blur source.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayContent(
    state: TodayUiState,
    onRefresh: () -> Unit,
    onDismissBanner: () -> Unit,
    onUninstall: () -> Unit,
    modifier: Modifier = Modifier,
    onOpen: (HeroMetric) -> Unit = {},
    /** Long-press on the Heart mini card: the Measure sheet on its picker. */
    onMeasure: () -> Unit = {},
) {
    val pull = rememberPullToRefreshState()
    val scroll = rememberScrollState()
    val pager = rememberPagerState { TodayCopy.HeroPages.size }
    val haze = LocalHazeState.current
    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bottomClear = OrbitBottomBarDefaults.Clearance + max(0.dp, navInset - OrbitBottomBarDefaults.Bottom) + 24.dp

    Box(
        modifier
            .fillMaxSize()
            .then(if (haze != null) Modifier.hazeSource(haze) else Modifier)
            .background(Brush.verticalGradient(listOf(OrbitPalette.Base0, OrbitPalette.Base1))),
    ) {
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize(),
            state = pull,
            indicator = {
                // Bespoke, and only while pulling: once released the header's breathing amber dot and "Syncing with
                // your ring…" carry the sync, so nothing parks over the greeting.
                OrbitPullIndicator(
                    fraction = { pull.distanceFraction },
                    visible = !state.refreshing,
                    modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding(),
                )
            },
        ) {
            Column(Modifier.fillMaxSize().verticalScroll(scroll).statusBarsPadding().padding(bottom = bottomClear)) {
                Header(state, Modifier.graphicsLayer {
                    // The greeting fades out over the first 120dp of scroll.
                    alpha = 1f - (scroll.value / (120.dp.toPx())).coerceIn(0f, 1f)
                })
                AnimatedVisibility(state.showCoexistenceBanner, exit = fadeOut() + shrinkVertically()) {
                    CoexistenceBanner(state.vendorAppName, onUninstall, onDismissBanner, Modifier.padding(start = Gutter, end = Gutter, top = 20.dp))
                }
                HeroPager(
                    state, pager,
                    scrollPx = { scroll.value },
                    onOpen = onOpen,
                    modifier = Modifier.padding(top = 20.dp).livingEnter(120),
                )
                PlanetDots(
                    TodayCopy.HeroPages.size, pager.currentPage,
                    Modifier.align(Alignment.CenterHorizontally).padding(top = 14.dp, bottom = 16.dp).livingEnter(180),
                )
                Minis(state, onMeasure)
                Goals(state, Modifier.padding(start = Gutter, end = Gutter, top = 30.dp).livingEnter(380))
                Stress(state, Modifier.padding(start = Gutter, end = Gutter, top = 30.dp).livingEnter(440))
            }
        }
        // Top scrim (tokens §5): fades in over the first 40dp of scroll so content slides under the status bar.
        val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        Spacer(
            Modifier.fillMaxWidth().height(statusTop + 40.dp)
                .graphicsLayer { alpha = (scroll.value / 40.dp.toPx()).coerceIn(0f, 1f) }
                .drawBehind {
                    drawRect(Brush.verticalGradient(0f to OrbitPalette.Base0, 0.46f to OrbitPalette.Base0, 1f to OrbitPalette.Base0.copy(alpha = 0f)))
                },
        )
    }
}

@Composable
private fun Header(state: TodayUiState, modifier: Modifier) {
    Column(modifier.padding(start = Gutter, end = Gutter, top = 14.dp)) {
        Row(Modifier.fillMaxWidth().livingEnter(0), verticalAlignment = Alignment.CenterVertically) {
            Text(state.dateLabel, style = OrbitTypography.Eyebrow, maxLines = 1, modifier = Modifier.weight(1f).lineBox(OrbitTypography.Eyebrow))
            val dot = if (state.syncing || state.refreshing) OrbitPalette.Good else if (state.syncedAgo.isEmpty()) OrbitPalette.Ink2 else OrbitPalette.Optimal
            SyncDot(dot, breathing = state.syncing || state.refreshing)
            Spacer(Modifier.width(7.dp))
            Text(state.syncLine, style = OrbitTypography.Eyebrow, maxLines = 1, modifier = Modifier.lineBox(OrbitTypography.Eyebrow))
        }
        Text(
            buildAnnotatedString {
                val name = state.greetingName
                val g = state.greeting
                val at = if (name != null) g.lastIndexOf(name) else -1
                if (at < 0) append(g) else {
                    append(g.substring(0, at))
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(g.substring(at)) }
                }
            },
            style = OrbitTypography.Greeting,
            modifier = Modifier.padding(top = 10.dp).livingEnter(0).semantics { heading() },
        )
        Text(state.daySentence, style = OrbitTypography.Lead, modifier = Modifier.padding(top = 8.dp).livingEnter(60))
    }
}

/** The header's sync dot: a soft glow of its colour (CSS box-shadow 0 0 6px), breathing while a sync runs. */
@Composable
private fun SyncDot(color: Color, breathing: Boolean) {
    val motion = rememberMotionEnabled()
    val glow = if (breathing && motion) {
        val t = rememberInfiniteTransition(label = "sync")
        t.animateFloat(0.25f, 0.9f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "glow")
    } else null
    Box(
        Modifier.size(6.dp).drawBehind {
            val a = glow?.value ?: 0.55f
            val r = size.minDimension * (if (glow != null) 1.5f + glow.value else 1.5f)
            drawCircle(Brush.radialGradient(listOf(color.copy(alpha = a), Color.Transparent), radius = r), radius = r)
        }.background(color, CircleShape),
    )
}

/**
 * Pull-to-sync in the app's own language: the orbit ring's track fills as you pull, the planet riding its end, and
 * glows once a release will sync. Fades in with the pull; hidden while the sync runs (the header shows it).
 */
@Composable
private fun OrbitPullIndicator(fraction: () -> Float, visible: Boolean, modifier: Modifier = Modifier) {
    Canvas(
        modifier.size(30.dp).graphicsLayer {
            val f = fraction()
            alpha = if (visible) (f * 1.6f).coerceIn(0f, 1f) else 0f
            translationY = (f.coerceIn(0f, 1.3f) - 0.4f) * 36.dp.toPx()
            val s = 0.8f + 0.2f * f.coerceIn(0f, 1f)
            scaleX = s; scaleY = s
        },
    ) {
        val f = fraction().coerceIn(0f, 1f)
        val stroke = 2.dp.toPx()
        val r = size.minDimension / 2 - 6.dp.toPx()
        drawCircle(OrbitPalette.Ink.copy(alpha = 0.18f), r, style = Stroke(stroke))
        val sweep = 360f * f
        drawArc(
            OrbitPalette.Ink.copy(alpha = 0.85f), -90f, sweep, useCenter = false,
            topLeft = center - androidx.compose.ui.geometry.Offset(r, r), size = androidx.compose.ui.geometry.Size(2 * r, 2 * r),
            style = Stroke(stroke, cap = StrokeCap.Round),
        )
        val a = Math.toRadians((sweep - 90f).toDouble())
        val p = center + androidx.compose.ui.geometry.Offset((r * kotlin.math.cos(a)).toFloat(), (r * kotlin.math.sin(a)).toFloat())
        val ready = fraction() >= 1f
        drawCircle(OrbitPalette.PlanetHalo.copy(alpha = if (ready) 0.8f else 0.35f), (if (ready) 7.5f else 5.5f) * density, p)
        drawCircle(OrbitPalette.PlanetCore, 3.5.dp.toPx(), p)
    }
}

@Composable
private fun CoexistenceBanner(vendorAppName: String?, onUninstall: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    TintedCard(OrbitPalette.Attention, modifier.fillMaxWidth(), amount = TintedCardDefaults.ChipAmount) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            dev.avijit.orbithealth.ui.components.Symbol(OrbitSymbol.Shield, size = 18.dp, strokeWidth = 1.7f, tint = OrbitPalette.Attention)
            Text(VendorAppCopy.bannerTitle(vendorAppName), style = OrbitTypography.BodyStrong, modifier = Modifier.lineBox(OrbitTypography.BodyStrong))
        }
        Text(VendorAppCopy.bannerBody(vendorAppName), style = OrbitTypography.Meta, modifier = Modifier.padding(top = 8.dp, bottom = 14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            WarmButton("Uninstall", onUninstall, Modifier.weight(1f))
            GhostButton("Not now", onDismiss, Modifier.weight(1f))
        }
    }
}

@Composable
private fun Minis(state: TodayUiState, onMeasure: () -> Unit) {
    val hapticFeedback = LocalHapticFeedback.current
    Row(Modifier.padding(horizontal = Gutter), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        MiniMetricCard(
            OrbitPalette.HeartA, OrbitSymbol.Body, "Heart", state.hrValue, "bpm", state.heartCaption,
            Modifier.weight(1f).livingEnter(240)
                // A shortcut, not a button: no tap action, a long-press opens the Measure sheet (named for TalkBack).
                .clip(TintedCardDefaults.MiniShape)
                .pointerInput(onMeasure) {
                    // The platform's light long-press tick; MeasureStart belongs to starting a reading (a tile tap).
                    detectTapGestures(onLongPress = { hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress); onMeasure() })
                }
                .semantics { onLongClick(label = "Measure now") { onMeasure(); true } },
            // A week-old (or missing) reading is a neutral dash, never drawn like a live value.
            valueColor = if (state.hrFreshness == HrFreshness.HIDDEN || state.hrFreshness == HrFreshness.NONE) OrbitPalette.Ink2 else OrbitPalette.Ink,
        )
        val hrvColor = when (state.hrvVsNormal) {
            1 -> OrbitPalette.Optimal
            -1 -> OrbitPalette.Attention
            else -> OrbitPalette.Ink2
        }
        MiniMetricCard(
            OrbitPalette.ReadinessA, OrbitSymbol.Hrv, "HRV", state.hrv?.roundToInt()?.toString() ?: "--", "ms", state.hrvNote,
            Modifier.weight(1f).livingEnter(280), footColor = hrvColor,
        )
        MiniMetricCard(
            OrbitPalette.Spo2A, OrbitSymbol.Drop, "SpO₂", state.spo2?.roundToInt()?.toString() ?: "--", "%", state.spo2Note,
            Modifier.weight(1f).livingEnter(320),
            footColor = if (state.spo2 != null && state.spo2 < 94.0) OrbitPalette.Attention else OrbitPalette.Ink2,
        )
    }
}

@Composable
private fun SectionTitle(title: String, modifier: Modifier = Modifier, badge: Boolean = false, trailing: @Composable () -> Unit) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = OrbitTypography.H2, modifier = Modifier.semantics { heading() })
        if (badge) {
            Spacer(Modifier.width(10.dp))
            EstimateBadge()
        }
        Spacer(Modifier.weight(1f))
        trailing()
    }
}

@Composable
private fun Goals(state: TodayUiState, modifier: Modifier) {
    val fmt = NumberFormat.getIntegerInstance(Locale.US)
    Column(modifier) {
        SectionTitle("Today's goals") { Text("Tuned to your recovery", style = OrbitTypography.Meta, modifier = Modifier.padding(top = 4.dp)) }
        TintedCard(OrbitPalette.Ink, Modifier.padding(top = 12.dp).fillMaxWidth(), amount = TintedCardDefaults.NeutralAmount) {
            val steps = state.goals.getOrNull(0)
            val calm = state.goals.getOrNull(1)
            val bed = state.goals.getOrNull(2)
            if (steps != null) {
                GoalProgressRow(
                    OrbitSymbol.Steps, OrbitPalette.ActivityA, OrbitPalette.ActivityB,
                    "${fmt.format(steps.target)} steps", fmt.format(steps.progress), state.stepNote,
                    progress = if (steps.target > 0) steps.progress.toFloat() / steps.target else 0f,
                    fill = Brush.horizontalGradient(listOf(OrbitPalette.ActivityA, OrbitPalette.ActivityB)),
                    first = true,
                )
            }
            if (calm != null) {
                GoalProgressRow(
                    OrbitSymbol.Breath, OrbitPalette.StressA, OrbitPalette.Optimal,
                    calm.label, "${calm.progress.coerceAtMost(calm.target)} of ${calm.target}", "One minute of slow breathing",
                    progress = if (calm.target > 0) calm.progress.toFloat() / calm.target else 0f,
                    fill = Brush.horizontalGradient(listOf(OrbitPalette.StressA, OrbitPalette.StressFillEnd)),
                )
            }
            if (bed != null) {
                GoalProgressRow(
                    OrbitSymbol.Sleep, OrbitPalette.SleepB, OrbitPalette.StageRem,
                    bed.label, "Tonight", state.bedtimeNote, progress = null, fill = null, last = true,
                )
            }
        }
    }
}

@Composable
private fun Stress(state: TodayUiState, modifier: Modifier) {
    Column(modifier) {
        SectionTitle("Stress", badge = true) {
            state.stressNow?.let { v ->
                val color = when (TodayCopy.stressTone(v)) {
                    0 -> OrbitPalette.Optimal
                    1 -> OrbitPalette.Good
                    else -> OrbitPalette.Attention
                }
                Text(state.stressHeader, style = OrbitTypography.Word.copy(fontSize = OrbitTypography.Tab.fontSize), color = color, modifier = Modifier.padding(top = 4.dp))
            }
        }
        TintedCard(
            OrbitPalette.StressA, Modifier.padding(top = 12.dp).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 14.dp),
        ) {
            Text(state.stressCaption, style = OrbitTypography.InsightSm)
            StressRibbon(state.stressToday, state.nowMinute, Modifier.padding(top = 16.dp))
        }
    }
}
