package dev.avijit.orbithealth.ui.today

import dev.avijit.orbithealth.ui.components.HapticEvent
import dev.avijit.orbithealth.ui.components.HapticEvents
import dev.avijit.orbithealth.ui.components.rememberHaptics
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.remember
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.semantics.clearAndSetSemantics
import dev.avijit.orbithealth.ui.components.RollingNumber
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import dev.avijit.orbithealth.ui.charts.ChartMath
import dev.avijit.orbithealth.ui.components.rememberFittedStyle
import dev.avijit.orbithealth.ui.charts.MinRunDp
import androidx.compose.ui.unit.sp
import dev.avijit.orbithealth.ring.SleepSession
import dev.avijit.orbithealth.ring.SleepStage
import dev.avijit.orbithealth.ring.totalSleepSec
import dev.avijit.orbithealth.ui.components.OrbitArc
import dev.avijit.orbithealth.ui.components.OrbitArcDefaults
import dev.avijit.orbithealth.ui.components.OrbitIconButton
import dev.avijit.orbithealth.ui.components.OrbitIconButtonDefaults
import dev.avijit.orbithealth.ui.components.OrbitSymbol
import dev.avijit.orbithealth.ui.components.rememberArcReveal
import dev.avijit.orbithealth.ui.nav.heroTransitionActive
import dev.avijit.orbithealth.ui.nav.sharedHero
import dev.avijit.orbithealth.ui.sky.Sky
import dev.avijit.orbithealth.ui.sky.SkyKind
import dev.avijit.orbithealth.ui.sky.skySpec
import dev.avijit.orbithealth.ui.theme.OrbitPalette
import dev.avijit.orbithealth.ui.theme.OrbitTypography
import dev.avijit.orbithealth.ui.theme.lineBox
import java.text.NumberFormat
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Tokens §4–5: the hero sky card. */
internal object HeroDefaults {
    val Height = 392.dp
    val Shape = RoundedCornerShape(28.dp)
    val ArcTop = 46.dp
    val NumeralTop = 106.dp
    /** Widest the hero numeral may run and still clear the arc's stroke. */
    val NumeralMaxWidth = 184.dp
    /** First light: the insight starts just under the horizon (66.8%). */
    val InsightTop = 284.dp
    /** Night: the hypnogram strip. */
    val StripTop = 262.dp
    val StripHeight = 30.dp
    val PageGutter = 24.dp
    val PageSpacing = 12.dp
    /** Pager parallax: sky layers move −pageOffset × 40dp against the card. */
    val ParallaxDp = 40f
    /** Scroll parallax: the sky drifts down at 0.4 × scroll inside the card. */
    const val ScrollParallax = 0.4f

    /**
     * Which hero skies run (at most 2 animated per screen): the current page, plus the neighbour a drag is heading to,
     * so it is already live (and caught up with the shared timeline) when it lands. Nothing in the pager runs during
     * the hero -> detail transition (the destination's sky does).
     */
    fun skyAnimates(page: Int, currentPage: Int, offsetFraction: Float, scrolling: Boolean, transition: Boolean = false): Boolean {
        if (transition) return false
        if (page == currentPage) return true
        if (!scrolling || offsetFraction == 0f) return false
        return page == currentPage + if (offsetFraction > 0f) 1 else -1
    }
}

/**
 * The swipeable hero (tokens §5 Pager): Readiness under First light, Sleep under Night, Activity under Ember, 24dp in,
 * 12dp apart so neighbours peek in, snapping on a critically damped spring. Only the current page's sky animates;
 * the peeking neighbours hold a still frame. [scrollPx] is the screen's scroll, for the sky's vertical parallax.
 */
@Composable
internal fun HeroPager(
    state: TodayUiState,
    pager: PagerState,
    scrollPx: () -> Int,
    onOpen: (HeroMetric) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberHaptics()
    LaunchedEffect(pager) {
        var previous: Int? = null
        snapshotFlow { pager.settledPage }.collect { page ->
            if (HapticEvents.pagerSettled(previous, page)) haptics.perform(HapticEvent.PagerSettle)
            previous = page
        }
    }
    HorizontalPager(
        state = pager,
        modifier = modifier.fillMaxWidth().height(HeroDefaults.Height),
        contentPadding = PaddingValues(horizontal = HeroDefaults.PageGutter),
        pageSpacing = HeroDefaults.PageSpacing,
        beyondViewportPageCount = 1,
        flingBehavior = PagerDefaults.flingBehavior(pager, snapAnimationSpec = spring(dampingRatio = 1f, stiffness = 380f)),
        key = { TodayCopy.HeroPages[it].name },
    ) { page ->
        val metric = TodayCopy.HeroPages[page]
        val offset = { page - pager.currentPage - pager.currentPageOffsetFraction }
        val current = page == pager.currentPage
        val transition = heroTransitionActive()
        val animate by remember(page) {
            derivedStateOf {
                HeroDefaults.skyAnimates(page, pager.currentPage, pager.currentPageOffsetFraction, pager.isScrollInProgress)
            }
        }
        HeroCard(
            metric = metric,
            animate = animate && !transition,
            pageOffset = offset,
            skyParam = skyParam(metric, state),
            scrollPx = scrollPx,
            onOpen = { onOpen(metric) },
        ) {
            when (metric) {
                HeroMetric.READINESS -> ReadinessContent(state, offset, current)
                HeroMetric.SLEEP -> SleepContent(state, offset, current)
                HeroMetric.ACTIVITY -> ActivityContent(state, offset, current)
            }
        }
    }
}

@Composable
private fun HeroCard(
    metric: HeroMetric,
    animate: Boolean,
    pageOffset: () -> Float,
    skyParam: Float,
    scrollPx: () -> Int,
    onOpen: () -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    // Behind the parallaxing sky: its own top colour, so a shifted edge never shows a seam.
    val edge = remember(metric) { skySpec(metric.sky).stops.first().color }
    val px = with(LocalDensity.current) { 1.dp.toPx() }
    Box(
        Modifier
            .fillMaxSize()
            .sharedHero(metric.skyKey, bounds = true)
            .clip(HeroDefaults.Shape)
            .background(edge)
            .clickable(role = Role.Button, onClickLabel = "Open ${metric.label}", onClick = onOpen),
    ) {
        Sky(
            metric.sky,
            Modifier.fillMaxSize().graphicsLayer {
                translationX = -pageOffset() * HeroDefaults.ParallaxDp * px
                translationY = scrollPx() * HeroDefaults.ScrollParallax
            },
            param = skyParam,
            stars = if (metric.sky == SkyKind.NIGHT) 30 else null,
            starSeed = 5,
            // HeroDefaults.skyAnimates: the current page (and a drag's target); none during the hero -> detail transition.
            animate = animate,
        )
        Row(
            // Tokens §4: overline at (20, 18), expand button right 16 top 18; the 48dp touch target spills 6dp past it.
            Modifier.fillMaxWidth().padding(
                start = 20.dp, end = 16.dp - OrbitIconButtonDefaults.spill(36.dp), top = 18.dp - OrbitIconButtonDefaults.spill(36.dp),
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(metric.label.uppercase(), style = OrbitTypography.Overline, modifier = Modifier.weight(1f).lineBox(OrbitTypography.Overline))
            // The card already offers "Open <metric>" to TalkBack; the corner button is the same action, so it stays
            // out of the accessibility tree rather than announcing it twice.
            OrbitIconButton(OrbitSymbol.Expand, onClick = onOpen, modifier = Modifier.clearAndSetSemantics {})
        }
        content()
    }
}

/** Readiness lifts the sun with the score (0.5 sits on the horizon; still learning: just under it). */
private fun skyParam(metric: HeroMetric, state: TodayUiState): Float = when (metric) {
    HeroMetric.READINESS -> state.readiness?.value?.div(100f) ?: 0.42f
    else -> 0f
}

/**
 * Arc, numeral and status word, laid out per tokens §4: arc bounds top 46, numeral box top 106 (92 tall), word 4dp
 * under it. The numeral follows the arc's reveal spring; the word fades in once the reveal passes 60% of the score;
 * off-page the numeral eases to 96%.
 */
@Composable
private fun BoxScope.ArcBlock(
    metric: HeroMetric,
    fraction: Float?,
    numeral: (shown: Float) -> String,
    word: String,
    wordColor: Color,
    pageOffset: () -> Float,
    current: Boolean,
    numeralStyle: TextStyle = OrbitTypography.NumXl,
    description: String,
) {
    // Reveal once per page per session, starting when the page becomes current (not while it waits off-screen):
    // coming back from the Sleep detail (or another tab) the arc lands where it was, no second count-up.
    var revealed by rememberSaveable(metric) { mutableStateOf(false) }
    val reveal = rememberArcReveal(fraction, animateIn = !revealed, start = current)
    val shown by reveal
    LaunchedEffect(fraction, revealed) {
        val goal = fraction ?: return@LaunchedEffect
        if (!revealed) {
            snapshotFlow { reveal.value }.first { abs(it - goal) < 0.005f }
            revealed = true
        }
    }
    val wordAlpha by animateFloatAsState(
        if (fraction == null || shown >= 0.6f * fraction) 1f else 0f, tween(240), label = "word",
    )
    OrbitArc(
        // The planet rides the arc end through the reveal (tokens §6).
        fraction?.let { shown },
        Modifier.align(Alignment.TopCenter).offset(y = HeroDefaults.ArcTop).sharedHero(metric.arcKey, bounds = false),
        animateIn = false,
        style = OrbitArcDefaults.Hero,
    )
    Column(
        Modifier.align(Alignment.TopCenter).padding(top = HeroDefaults.NumeralTop)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .graphicsLayer {
                val s = 1f - 0.04f * abs(pageOffset()).coerceAtMost(1f)
                scaleX = s; scaleY = s
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.height(92.dp), contentAlignment = Alignment.Center) {
            // Counting with the reveal, then the numeral rolls its digits on a data change (tokens §6 number roll).
            RollingNumber(
                if (revealed || fraction == null) numeral(fraction ?: 0f) else numeral(shown), numeralStyle,
                color = if (fraction == null) OrbitPalette.Ink.copy(alpha = 0.55f) else OrbitPalette.Ink,
                roll = revealed,
            )
        }
        Text(
            word, style = OrbitTypography.Word, color = wordColor,
            modifier = Modifier.padding(top = 4.dp).alpha(wordAlpha).skyScrim().lineBox(OrbitTypography.Word),
        )
    }
}

/**
 * Tokens §5 scrim rule: a soft 0.25-alpha Base0 pool behind a text block that sits over a bright part of a sky. The
 * status word sits just above First light's sunlit horizon, where it measured 4.0:1 without it (R6 contrast pass).
 */
internal fun Modifier.skyScrim(): Modifier = drawBehind {
    val padX = 14.dp.toPx()
    val padY = 8.dp.toPx()
    val w = size.width + 2 * padX
    val h = size.height + 2 * padY
    val c = center
    val scrim = OrbitPalette.Base0.copy(alpha = 0.25f)
    // A circle squashed to the text's aspect: full strength over the glyphs, fading out past them.
    scale(1f, h / w, pivot = c) {
        drawCircle(Brush.radialGradient(0f to scrim, 0.62f to scrim, 1f to Color.Transparent, center = c, radius = w / 2), w / 2, c)
    }
}

private fun Status.color(): Color = when (this) {
    Status.OPTIMAL -> OrbitPalette.Optimal
    Status.GOOD -> OrbitPalette.Good
    Status.ATTENTION -> OrbitPalette.Attention
}

@Composable
private fun BoxScope.ReadinessContent(state: TodayUiState, pageOffset: () -> Float, current: Boolean) {
    val r = state.readiness?.value
    val learning = state.baselineDays < dev.avijit.orbithealth.insights.Baselines.MIN_DAYS
    ArcBlock(
        HeroMetric.READINESS,
        fraction = r?.div(100f),
        numeral = { if (r == null) "—" else (it * 100).roundToInt().toString() },
        word = r?.let { TodayCopy.status(it).word } ?: if (learning) "Learning" else "Waiting",
        wordColor = r?.let { TodayCopy.status(it).color() } ?: OrbitPalette.InkSky2,
        pageOffset = pageOffset,
        current = current,
        description = r?.let { "Readiness $it, ${TodayCopy.status(it).word}" } ?: "Readiness not available yet",
    )
    HeroText(state.readinessInsight, state.readinessMeta, top = HeroDefaults.InsightTop, metaGap = 10)
}

@Composable
private fun BoxScope.SleepContent(state: TodayUiState, pageOffset: () -> Float, current: Boolean) {
    val s = state.sleep?.value
    ArcBlock(
        HeroMetric.SLEEP,
        fraction = s?.div(100f),
        numeral = { if (s == null) "—" else (it * 100).roundToInt().toString() },
        word = s?.let { TodayCopy.status(it).word } ?: "No data",
        wordColor = s?.let { TodayCopy.status(it).color() } ?: OrbitPalette.InkSky2,
        pageOffset = pageOffset,
        current = current,
        description = s?.let { "Sleep score $it, ${TodayCopy.status(it).word}" } ?: "No sleep recorded last night",
    )
    val night = state.lastNight
    if (night == null) {
        HeroText(state.sleepInsight, state.sleepMeta, top = HeroDefaults.InsightTop, metaGap = 10)
        return
    }
    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = HeroDefaults.StripTop)) {
        HeroHypnogram(night, Modifier.fillMaxWidth().height(HeroDefaults.StripHeight))
        val hhmm = remember { DateTimeFormatter.ofPattern("HH:mm") }
        val zone = remember { ZoneId.systemDefault() }
        Row(Modifier.padding(top = 8.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(night.start.atZone(zone).format(hhmm), style = OrbitTypography.Caption, color = OrbitPalette.InkSky2, modifier = Modifier.lineBox(OrbitTypography.Caption))
            Text(
                "${TodayCopy.hm(night.totalSleepSec)} asleep", style = OrbitTypography.Caption, color = OrbitPalette.Ink,
                textAlign = TextAlign.Center, modifier = Modifier.weight(1f).lineBox(OrbitTypography.Caption),
            )
            Text(night.end.atZone(zone).format(hhmm), style = OrbitTypography.Caption, color = OrbitPalette.InkSky2, modifier = Modifier.lineBox(OrbitTypography.Caption))
        }
        // Under the strip there is room for one line of insight and the meta, or two lines of insight alone: a
        // wrapped insight hides the meta (the stage times it repeats are on the Sleep detail) instead of clipping it.
        var insightLines by remember(state.sleepInsight) { mutableStateOf(1) }
        Text(
            state.sleepInsight, style = OrbitTypography.Insight, maxLines = 2, overflow = TextOverflow.Ellipsis,
            onTextLayout = { insightLines = it.lineCount }, modifier = Modifier.padding(top = 14.dp),
        )
        if (insightLines <= 1 && state.sleepMeta.isNotEmpty()) {
            Text(
                state.sleepMeta, style = OrbitTypography.Meta, color = OrbitPalette.InkSky2, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun BoxScope.ActivityContent(state: TodayUiState, pageOffset: () -> Float, current: Boolean) {
    val goal = state.goals.firstOrNull()
    val steps = goal?.progress ?: 0
    val target = goal?.target ?: 0
    val fraction = state.activity.value / 100f
    val fmt = remember { NumberFormat.getIntegerInstance(Locale.US) }
    // Sized once for the final count so 10,000+ stays inside the 220dp arc (tokens §4 hero: the numeral box sits 50dp
    // below the circle's top, where the arc is ~196dp across).
    val numeralStyle = rememberFittedStyle(fmt.format(steps), OrbitTypography.NumXl, HeroDefaults.NumeralMaxWidth, minSp = 48f)
    ArcBlock(
        HeroMetric.ACTIVITY,
        fraction = fraction,
        // The numeral climbs with the arc (and lands exactly on the count).
        numeral = { shown -> fmt.format(if (fraction <= 0f || shown >= fraction) steps else (steps * shown / fraction).roundToInt()) },
        word = "of ${fmt.format(target)} steps",
        wordColor = OrbitPalette.ActivityB,
        pageOffset = pageOffset,
        current = current,
        numeralStyle = numeralStyle,
        description = "$steps of $target steps",
    )
    HeroText(state.activityInsight, state.activityMeta, top = HeroDefaults.InsightTop, metaGap = 10)
}

@Composable
private fun HeroText(insight: String, meta: String, top: androidx.compose.ui.unit.Dp, metaGap: Int) {
    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = top)) {
        // Two lines of insight and one of meta fill the card to its bottom padding; longer copy ellipsizes, never clips.
        Text(insight, style = OrbitTypography.Insight, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            meta, style = OrbitTypography.Meta, color = OrbitPalette.InkSky2, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = metaGap.dp),
        )
    }
}

/**
 * The hero's compact hypnogram (tokens §5 Night): stages on four rows (Awake, REM, Light, Deep), 6dp bars with round
 * ends inset 0.75dp, 1dp ink 22% connectors between rows, widths by duration.
 */
@Composable
internal fun HeroHypnogram(session: SleepSession, modifier: Modifier = Modifier) {
    Canvas(modifier.semantics { contentDescription = "Sleep stages from ${session.start} to ${session.end}" }) {
        val raw = session.segments.sumOf { it.durationSec.coerceAtLeast(0) }.toFloat()
        if (raw <= 0f) return@Canvas
        // Display only: runs under MinRunDp join a neighbour so the strip never draws slivers (totals are unaffected).
        val segs = ChartMath.displaySegments(session.segments, (raw * MinRunDp.dp.toPx() / size.width).toInt())
        val total = segs.sumOf { it.durationSec }.toFloat()
        if (total <= 0f) return@Canvas
        val barH = 6.dp.toPx()
        val gap = (size.height - barH) / 3f
        val inset = 0.75.dp.toPx()
        val connector = OrbitPalette.Ink.copy(alpha = 0.22f)
        var x = 0f
        var prevY: Float? = null
        segs.forEach { seg ->
            val row = when (seg.stage) {
                SleepStage.AWAKE -> 0
                SleepStage.REM -> 1
                SleepStage.LIGHT -> 2
                SleepStage.DEEP -> 3
            }
            val y = row * gap
            val w = seg.durationSec / total * size.width
            prevY?.let { py ->
                if (py != y) {
                    val y0 = minOf(py, y) + barH / 2
                    val y1 = maxOf(py, y) + barH / 2
                    drawRect(connector, Offset(x - 0.5.dp.toPx(), y0), Size(1.dp.toPx(), y1 - y0))
                }
            }
            drawRoundRect(
                stageColor(seg.stage), Offset(x + inset, y), Size(maxOf(w - 2 * inset, 2.dp.toPx()), barH),
                cornerRadius = CornerRadius(minOf(barH / 2, maxOf(w - 2 * inset, 2.dp.toPx()) / 2)),
            )
            x += w
            prevY = y
        }
    }
}

private fun stageColor(stage: SleepStage): Color = when (stage) {
    SleepStage.AWAKE -> OrbitPalette.StageAwake
    SleepStage.REM -> OrbitPalette.StageRem
    SleepStage.LIGHT -> OrbitPalette.StageLight
    SleepStage.DEEP -> OrbitPalette.StageDeep
}
