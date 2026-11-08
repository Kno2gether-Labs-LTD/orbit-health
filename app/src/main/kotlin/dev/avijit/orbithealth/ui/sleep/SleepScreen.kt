package dev.avijit.orbithealth.ui.sleep

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.avijit.orbithealth.ui.charts.Hypnogram
import dev.avijit.orbithealth.ui.charts.StageSplitBar
import dev.avijit.orbithealth.ui.components.EstimateBadge
import dev.avijit.orbithealth.ui.components.LocalHazeState
import dev.avijit.orbithealth.ui.components.MiniMetricCard
import dev.avijit.orbithealth.ui.components.OrbitArc
import dev.avijit.orbithealth.ui.components.OrbitArcDefaults
import dev.avijit.orbithealth.ui.components.OrbitBottomBarDefaults
import dev.avijit.orbithealth.ui.components.OrbitIconButton
import dev.avijit.orbithealth.ui.components.OrbitIconButtonDefaults
import dev.avijit.orbithealth.ui.components.OrbitSymbol
import dev.avijit.orbithealth.ui.components.Symbol
import dev.avijit.orbithealth.ui.components.TintedCard
import dev.avijit.orbithealth.ui.components.TintedCardDefaults
import dev.avijit.orbithealth.ui.components.livingEnter
import dev.avijit.orbithealth.ui.components.rememberArcReveal
import dev.avijit.orbithealth.ui.nav.LocalNavAnimatedScope
import dev.avijit.orbithealth.ui.nav.sharedHero
import dev.avijit.orbithealth.ui.sky.Sky
import dev.avijit.orbithealth.ui.sky.SkyKind
import dev.avijit.orbithealth.ui.sky.skySpec
import dev.avijit.orbithealth.ui.today.HeroMetric
import dev.avijit.orbithealth.ui.today.Status
import dev.avijit.orbithealth.ui.today.TodayCopy
import dev.avijit.orbithealth.ui.theme.OrbitPalette
import dev.avijit.orbithealth.ui.theme.OrbitTypography
import dev.avijit.orbithealth.ui.theme.lineBox
import dev.chrisbanes.haze.hazeSource
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** Calm empty state when no night has been recorded. */
const val NoSleepCopy = "Wear your ring to bed to see your sleep"

/** The legend beside "Stages": what the dotted line is and that the graph answers taps. */
const val HeartOverlayCaption = "heart rate overlay · tap any point"

/**
 * Tokens §4 detail layout and screen-06, measured from the top of the screen less the mockup's 36dp status bar (the
 * device's own status bar inset is added back), so everything keeps its distance from the status bar.
 */
private object SleepHeader {
    val Height = 316.dp
    val Shape = RoundedCornerShape(bottomStart = 32.dp, bottomEnd = 32.dp)
    val RowTop = 12.dp
    val ArcTop = 70.dp
    val NumeralTop = 114.dp
    val MetaTop = 264.dp
    const val STARS = 34
    const val STAR_SEED = 9
}

private val Gutter = 24.dp
private val BackSpill = OrbitIconButtonDefaults.spill(40.dp)
private val Edge = skySpec(SkyKind.NIGHT).stops.first().color

@Composable
fun SleepScreen(vm: SleepViewModel, onBack: () -> Unit = {}, focusNight: Long? = null, heroScore: Int? = null) {
    val state by vm.state.collectAsStateWithLifecycle()
    SleepContent(state, onBack = onBack, focusNight = focusNight, heroScore = heroScore, onAnswerEstimate = vm::answer)
}

/**
 * Sleep detail, Living Skies (mockup screen-06): a Night-sky header (back, the night's name, the 176dp orbit arc with
 * the score and status word, time asleep and the times), the insight as a Fraunces lead, the Stages card (hypnogram
 * in stage colours with the dotted heart-rate overlay, tap for stage and time; the stage split and its legend) and
 * Lowest HR / Avg HRV / Avg SpO₂ minis. Swipe for earlier nights.
 *
 * Pushed from Today's Sleep hero, the header sky and arc are the hero's shared elements ("hero-sky-SLEEP",
 * "hero-arc-SLEEP") on the [focusNight] page (the latest when null); [heroScore] fills the header while the night
 * loads so the transition always has a landing place. On the tab there is no shared scope and the arc reveals.
 */
@Composable
fun SleepContent(
    state: SleepScreenState,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    focusNight: Long? = null,
    heroScore: Int? = null,
    /** Yes / Adjust / No on a night the ring missed. */
    onAnswerEstimate: (EstimateAnswer) -> Unit = {},
) {
    val haze = LocalHazeState.current
    Box(
        modifier
            .fillMaxSize()
            .then(if (haze != null) Modifier.hazeSource(haze) else Modifier)
            .background(Brush.verticalGradient(listOf(OrbitPalette.Base0, OrbitPalette.Base1))),
    ) {
        when {
            !state.loaded -> NightHeader(
                title = "Last night", fraction = heroScore?.div(100f), numeral = { heroScore?.toString() ?: "" },
                word = heroScore?.let { TodayCopy.status(it).word } ?: "", wordColor = heroScore?.let { TodayCopy.status(it).color() } ?: Color.Transparent,
                meta = "", shared = true, animateSky = true, onBack = onBack,
            )
            state.nights.isEmpty() -> EmptySleep(onBack, state.suggestion, onAnswerEstimate)
            else -> NightPager(state.nights, focusNight, onBack, state.suggestion, onAnswerEstimate)
        }
    }
}

@Composable
private fun NightPager(
    nights: List<SleepUiState>,
    focusNight: Long?,
    onBack: () -> Unit,
    suggestion: EstimateSuggestion?,
    onAnswerEstimate: (EstimateAnswer) -> Unit,
) {
    val focus = remember(nights, focusNight) {
        nights.indexOfFirst { it.session.start.toEpochMilli() == focusNight }.takeIf { it >= 0 } ?: nights.lastIndex
    }
    val pager = rememberPagerState(initialPage = focus) { nights.size }
    val scope = rememberCoroutineScope()
    // A newly synced night: if the newest was on screen, move to the new newest.
    val lastCount = remember { mutableIntStateOf(nights.size) }
    LaunchedEffect(nights.size) {
        if (nights.size > lastCount.intValue && pager.currentPage == lastCount.intValue - 1) pager.scrollToPage(nights.lastIndex)
        lastCount.intValue = nights.size
    }
    HorizontalPager(pager, Modifier.fillMaxSize(), key = { nights[it].session.start.toEpochMilli() }, beyondViewportPageCount = 1) { page ->
        NightPage(
            nights[page],
            // Only while the hero's night is the one on screen: swiped to another night, Back is a plain pop.
            shared = page == focus && pager.currentPage == focus && pager.settledPage == focus,
            current = page == pager.currentPage,
            onBack = onBack,
            onPrev = if (page > 0) ({ scope.launch { pager.animateScrollToPage(page - 1) } }) else null,
            onNext = if (page < nights.lastIndex) ({ scope.launch { pager.animateScrollToPage(page + 1) } }) else null,
            // A night the ring missed is asked about on the newest page, where you land.
            suggestion = suggestion.takeIf { page == nights.lastIndex },
            onAnswerEstimate = onAnswerEstimate,
        )
    }
}

@Composable
private fun bottomClearance(): Dp {
    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    return OrbitBottomBarDefaults.Clearance + max(0.dp, navInset - OrbitBottomBarDefaults.Bottom) + 24.dp
}

@Composable
private fun NightPage(
    night: SleepUiState,
    shared: Boolean,
    current: Boolean,
    onBack: () -> Unit,
    onPrev: (() -> Unit)?,
    onNext: (() -> Unit)?,
    suggestion: EstimateSuggestion? = null,
    onAnswerEstimate: (EstimateAnswer) -> Unit = {},
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = bottomClearance())) {
        val score = night.score?.value
        NightHeader(
            title = night.title,
            fraction = score?.div(100f),
            numeral = { shown -> if (score == null) "—" else (shown * 100).roundToInt().toString() },
            word = score?.let { TodayCopy.status(it).word } ?: "No score",
            wordColor = score?.let { TodayCopy.status(it).color() } ?: OrbitPalette.InkSky2,
            meta = if (night.estimated) "About ${night.duration} · ${night.timeRange}" else "${night.duration} asleep · ${night.timeRange}",
            shared = shared,
            animateSky = current,
            onBack = onBack, onPrev = onPrev, onNext = onNext,
            description = score?.let { "Sleep score $it, ${TodayCopy.status(it).word}" } ?: "No sleep score",
        )
        suggestion?.let { s ->
            androidx.compose.runtime.key(s.id) {
                NightSuggestionCard(s, onAnswerEstimate, Modifier.livingEnter(0).padding(start = Gutter, end = Gutter, top = 22.dp))
            }
        }
        night.insight?.let {
            Text(
                leadText(it), style = OrbitTypography.InsightLg,
                modifier = Modifier.livingEnter(0).padding(start = Gutter, end = Gutter, top = 22.dp),
            )
        }
        if (night.estimated) EstimatedNightCard(Modifier.livingEnter(60).padding(start = Gutter, end = Gutter, top = 20.dp))
        else StagesCard(night, Modifier.livingEnter(60).padding(start = Gutter, end = Gutter, top = 20.dp))
        NightMinis(night, Modifier.padding(start = Gutter, end = Gutter, top = 12.dp))
    }
}

/**
 * The Night-sky header (tokens §2 bottom corners 32, §4 detail arc): back button, the night's [title] (previous / next
 * night as accessibility actions; swiping pages too), the arc with the numeral riding its reveal, the status word and
 * [meta]. [shared]: the sky and arc are Today's Sleep hero's shared elements.
 */
@Composable
private fun NightHeader(
    title: String,
    fraction: Float?,
    numeral: (shown: Float) -> String,
    word: String,
    wordColor: Color,
    meta: String,
    shared: Boolean,
    animateSky: Boolean,
    onBack: () -> Unit,
    onPrev: (() -> Unit)? = null,
    onNext: (() -> Unit)? = null,
    description: String? = null,
) {
    val inset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Box(
        Modifier
            .fillMaxWidth()
            .height(SleepHeader.Height + inset)
            .then(if (shared) Modifier.sharedHero(HeroMetric.SLEEP.skyKey, bounds = true) else Modifier),
    ) {
        // Only the sky is clipped to the header shape; the controls sit unclipped above it (a clip with uneven corners
        // is also a hit-test outline, which some renderers cannot answer).
        Box(Modifier.fillMaxSize().clip(SleepHeader.Shape).background(Edge)) {
            Sky(SkyKind.NIGHT, Modifier.fillMaxSize(), stars = SleepHeader.STARS, starSeed = SleepHeader.STAR_SEED, animate = animateSky)
        }
        Row(
            // Back button at (16, 12) by its visible circle; its 48dp touch target spills 4dp past it.
            Modifier.fillMaxWidth().padding(
                start = 16.dp - BackSpill, end = 16.dp - BackSpill, top = inset + SleepHeader.RowTop - BackSpill,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OrbitIconButton(OrbitSymbol.Back, onClick = onBack, size = 40.dp, contentDescription = "Back")
            Text(
                title, style = OrbitTypography.BodyStrong, textAlign = TextAlign.Center, maxLines = 1,
                modifier = Modifier.weight(1f).lineBox(OrbitTypography.BodyStrong).semantics {
                    heading()
                    customActions = listOfNotNull(
                        onPrev?.let { CustomAccessibilityAction("Previous night") { it(); true } },
                        onNext?.let { CustomAccessibilityAction("Next night") { it(); true } },
                    )
                },
            )
            Spacer(Modifier.width(OrbitIconButtonDefaults.TouchTarget))
        }
        HeaderArc(fraction, numeral, word, wordColor, inset, shared, description)
        if (meta.isNotEmpty()) {
            Text(
                meta, style = OrbitTypography.Meta, color = OrbitPalette.InkSky2, textAlign = TextAlign.Center, maxLines = 1,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = inset + SleepHeader.MetaTop).lineBox(OrbitTypography.Meta),
            )
        }
    }
}

@Composable
private fun BoxScope.HeaderArc(
    fraction: Float?,
    numeral: (shown: Float) -> String,
    word: String,
    wordColor: Color,
    inset: Dp,
    shared: Boolean,
    description: String?,
) {
    // Arriving from the hero the arc is already drawn: no second reveal. On the tab it reveals as on Today.
    val reveal = LocalNavAnimatedScope.current == null
    val shown by rememberArcReveal(fraction, animateIn = reveal)
    val wordAlpha by animateFloatAsState(
        if (fraction == null || shown >= 0.6f * fraction) 1f else 0f, tween(240), label = "word",
    )
    OrbitArc(
        fraction,
        Modifier.align(Alignment.TopCenter).offset(y = inset + SleepHeader.ArcTop)
            .then(if (shared) Modifier.sharedHero(HeroMetric.SLEEP.arcKey, bounds = false) else Modifier),
        size = OrbitArcDefaults.DetailSize,
        animateIn = false,
        style = OrbitArcDefaults.Detail,
    )
    Column(
        Modifier.align(Alignment.TopCenter).padding(top = inset + SleepHeader.NumeralTop)
            .then(if (description != null) Modifier.semantics(mergeDescendants = true) { contentDescription = description } else Modifier),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.height(72.dp), contentAlignment = Alignment.Center) {
            Text(
                numeral(shown), style = OrbitTypography.NumLg, textAlign = TextAlign.Center, maxLines = 1,
                color = if (fraction == null) OrbitPalette.Ink.copy(alpha = 0.55f) else OrbitPalette.Ink,
                modifier = Modifier.lineBox(OrbitTypography.NumLg),
            )
        }
        Text(
            word, style = OrbitTypography.Word, color = wordColor,
            modifier = Modifier.padding(top = 4.dp).alpha(wordAlpha).lineBox(OrbitTypography.Word),
        )
    }
}

/** Stages card: title and the overlay legend, the hypnogram (tap for stage, time and heart rate), the stage split. */
@Composable
private fun StagesCard(night: SleepUiState, modifier: Modifier) {
    TintedCard(
        OrbitPalette.SleepA, modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(18.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Stages", style = OrbitTypography.BodyStrong, modifier = Modifier.weight(1f).lineBox(OrbitTypography.BodyStrong))
            Canvas(Modifier.size(18.dp, 4.dp)) {
                drawLine(
                    Color(0xFFFF8AAA).copy(alpha = 0.85f), Offset(1.dp.toPx(), size.height / 2), Offset(17.dp.toPx(), size.height / 2),
                    strokeWidth = 1.6.dp.toPx(), cap = StrokeCap.Round,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(1.dp.toPx(), 4.dp.toPx())),
                )
            }
            Text(
                HeartOverlayCaption, style = OrbitTypography.Caption, maxLines = 1,
                modifier = Modifier.padding(start = 6.dp).lineBox(OrbitTypography.Caption),
            )
        }
        Hypnogram(night.session, night.heartOverlay, Modifier.padding(top = 16.dp), interactive = true)
        StageSplitBar(night.session, Modifier.padding(top = 16.dp))
    }
}

/** In place of the Stages card for a confirmed estimate: plainly, why there are no stages. */
@Composable
private fun EstimatedNightCard(modifier: Modifier) {
    TintedCard(OrbitPalette.SleepA, modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(18.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                EstimateCopy.EstimatedNightTitle, style = OrbitTypography.BodyStrong,
                modifier = Modifier.weight(1f).lineBox(OrbitTypography.BodyStrong),
            )
            EstimateBadge()
        }
        Text(EstimateCopy.EstimatedNightBody, style = OrbitTypography.Meta, modifier = Modifier.padding(top = 8.dp))
        Text(
            EstimateCopy.NoStages, style = OrbitTypography.Caption, color = OrbitPalette.InkSky2,
            modifier = Modifier.padding(top = 14.dp).lineBox(OrbitTypography.Caption),
        )
    }
}

@Composable
private fun NightMinis(night: SleepUiState, modifier: Modifier) {
    Row(modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        MiniMetricCard(
            OrbitPalette.HeartA, OrbitSymbol.Body, "Lowest HR", night.lowestHr?.toString() ?: "--", "bpm",
            night.lowestHrAt?.let { "at $it" } ?: "Overnight",
            Modifier.weight(1f).fillMaxHeight().livingEnter(120),
            labelMaxLines = 2,
        )
        val d = night.hrvVsNormal
        MiniMetricCard(
            OrbitPalette.ReadinessA, OrbitSymbol.Hrv, "Avg HRV", night.avgHrv?.toString() ?: "--", "ms",
            when {
                d == null -> "Overnight"
                d > 0 -> "↑ $d vs normal"
                d < 0 -> "↓ ${-d} vs normal"
                else -> "At your normal"
            },
            Modifier.weight(1f).fillMaxHeight().livingEnter(160),
            footColor = when {
                d == null || d == 0 -> OrbitPalette.Ink2
                d > 0 -> OrbitPalette.Optimal
                else -> OrbitPalette.Attention
            },
        )
        MiniMetricCard(
            OrbitPalette.Spo2A, OrbitSymbol.Drop, "Avg SpO₂", night.avgSpo2?.toString() ?: "--", "%",
            night.lowSpo2?.let { "Low $it" } ?: "Overnight",
            Modifier.weight(1f).fillMaxHeight().livingEnter(200),
            footColor = if ((night.lowSpo2 ?: 100) < 90) OrbitPalette.Attention else OrbitPalette.Ink2,
        )
    }
}

@Composable
private fun EmptySleep(onBack: () -> Unit, suggestion: EstimateSuggestion? = null, onAnswerEstimate: (EstimateAnswer) -> Unit = {}) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = bottomClearance())) {
        NightHeader(
            title = "Sleep", fraction = null, numeral = { "—" }, word = "No nights yet", wordColor = OrbitPalette.InkSky2,
            meta = "", shared = true, animateSky = true, onBack = onBack, description = "No sleep recorded yet",
        )
        suggestion?.let { s ->
            androidx.compose.runtime.key(s.id) {
                NightSuggestionCard(s, onAnswerEstimate, Modifier.livingEnter(0).padding(start = Gutter, end = Gutter, top = 22.dp))
            }
        }
        TintedCard(OrbitPalette.SleepA, Modifier.livingEnter(0).padding(start = Gutter, end = Gutter, top = 22.dp).fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(44.dp).background(OrbitPalette.SleepB.copy(alpha = 0.16f), androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) {
                    Symbol(OrbitSymbol.Sleep, size = 22.dp, strokeWidth = 1.7f, tint = OrbitPalette.StageRem)
                }
                Text(NoSleepCopy, style = OrbitTypography.Insight, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 14.dp))
                Text(
                    "Stages, heart rate and SpO₂ appear here after your first night.", style = OrbitTypography.Meta,
                    textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

private val Amount = Regex("""\d+h \d{2}m|\d+ min""")

/** The insight with its amount ("40 min") in Fraunces italic, as the mockup's lead. */
internal fun leadText(s: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    Amount.findAll(s).forEach { m ->
        append(s.substring(i, m.range.first))
        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(m.value) }
        i = m.range.last + 1
    }
    append(s.substring(i))
}

private fun Status.color(): Color = when (this) {
    Status.OPTIMAL -> OrbitPalette.Optimal
    Status.GOOD -> OrbitPalette.Good
    Status.ATTENTION -> OrbitPalette.Attention
}
