package dev.avijit.orbithealth.ui.ask

import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.avijit.orbithealth.ring.Metric
import dev.avijit.orbithealth.ui.charts.Sparkline
import dev.avijit.orbithealth.ui.components.BrandMark
import dev.avijit.orbithealth.ui.components.GhostButton
import dev.avijit.orbithealth.ui.components.LocalHazeState
import dev.avijit.orbithealth.ui.components.OrbitBottomBarDefaults
import dev.avijit.orbithealth.ui.components.OrbitSymbol
import dev.avijit.orbithealth.ui.components.Symbol
import dev.avijit.orbithealth.ui.components.TintedCard
import dev.avijit.orbithealth.ui.components.WarmButton
import dev.avijit.orbithealth.ui.components.cardTint
import dev.avijit.orbithealth.ui.components.livingEnter
import dev.avijit.orbithealth.ui.sky.Sky
import dev.avijit.orbithealth.ui.sky.SkyKind
import dev.avijit.orbithealth.ui.theme.OrbitPalette
import dev.avijit.orbithealth.ui.theme.OrbitTypography
import dev.avijit.orbithealth.ui.theme.lineBox
import dev.chrisbanes.haze.hazeSource

const val AskNotPairedTitle = "Connect your Orbit Server to ask questions"
const val AskNotPairedLead = "Your agent runs on a machine you control and answers from your own ring data, and only what you share with it."

private val Gutter = 24.dp

/** Ask headline: insight.lg at 26/33 (tokens §3). */
private val AskHeadline = OrbitTypography.InsightLg.copy(fontSize = 26.sp, lineHeight = 33.sp)

/** The agent's voice: Fraunces insight.sm (16/23). */
private val AgentVoice = OrbitTypography.InsightSm

@Composable
fun AskScreen(vm: AskViewModel, onSetup: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    AskContent(state, onSend = vm::send, onRetry = vm::retry, onSetup = onSetup, onNewConversation = vm::newConversation)
}

/**
 * The Ask tab (screen-12's sky and voice): a 470dp Night sky fading out, "Your health agent / Ask", then either the
 * invitation to connect an Orbit Server, or the conversation: the user's questions on the right, the agent's answers
 * in Fraunces on the left with a provenance chip and, when it read a metric, a mini chart from this phone's own data.
 * Suggested questions start an empty conversation; the composer sits above the bottom bar (and the keyboard).
 */
@Composable
fun AskContent(
    state: AskUiState,
    onSend: (String) -> Unit,
    onRetry: (AskMessage.Problem) -> Unit,
    onSetup: () -> Unit,
    modifier: Modifier = Modifier,
    initialDraft: String = "",
    onNewConversation: () -> Unit = {},
) {
    val haze = LocalHazeState.current
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .then(if (haze != null) Modifier.hazeSource(haze) else Modifier)
            .background(Brush.verticalGradient(listOf(OrbitPalette.Base0, OrbitPalette.Base1))),
    ) {
        // Mockup: mask-image linear-gradient(#000 55%, transparent) over the 470dp sky.
        Sky(
            SkyKind.NIGHT,
            Modifier.fillMaxWidth().height(470.dp)
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(Brush.verticalGradient(0.55f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
                },
            stars = 26, starSeed = 41,
        )
        val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        // Mockup: the input's bottom sits 104dp from the screen bottom, 18dp above the bar (more above a tall nav bar),
        // and above the keyboard while typing.
        val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val ime = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
        val bottom = max(104.dp + max(0.dp, navInset - OrbitBottomBarDefaults.Bottom), ime + 12.dp)
        when (state.connection) {
            AskConnection.PAIRED -> Conversation(state, statusTop, bottom, onSend, onRetry, onSetup, initialDraft, onNewConversation)
            AskConnection.NOT_PAIRED -> NotPaired(statusTop, bottom, maxHeight, onSetup)
            AskConnection.LOADING -> Header(Modifier.padding(top = statusTop), null)
        }
    }
}

@Composable
private fun Header(modifier: Modifier, serverName: String?) {
    Column(modifier.padding(start = Gutter, end = Gutter, top = 14.dp).livingEnter(0)) {
        Text("Your health agent", style = OrbitTypography.Eyebrow, modifier = Modifier.lineBox(OrbitTypography.Eyebrow))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                "Ask", style = OrbitTypography.Title,
                modifier = Modifier.padding(top = 2.dp).lineBox(OrbitTypography.Title).semantics { heading() },
            )
            if (serverName != null) {
                // Takes what the title leaves, end-aligned; a long host name ellipsizes instead of clipping or
                // crowding the title.
                Row(
                    Modifier.weight(1f).padding(start = 16.dp, bottom = 6.dp),
                    horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(OrbitPalette.Optimal))
                    Spacer(Modifier.width(7.dp))
                    Text(
                        "on $serverName", style = OrbitTypography.Meta.copy(color = OrbitPalette.InkSky2), maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun NotPaired(statusTop: Dp, bottom: Dp, maxHeight: Dp, onSetup: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight)
            .padding(top = statusTop, bottom = bottom),
    ) {
        Header(Modifier, null)
        OrbitIllustration(Modifier.livingEnter(60))
        Text(
            buildAnnotatedString {
                append("Connect your Orbit Server ")
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append("to ask questions") }
            },
            style = AskHeadline, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 36.dp).livingEnter(120)
                .semantics { contentDescription = AskNotPairedTitle },
        )
        Text(
            AskNotPairedLead, style = OrbitTypography.Lead, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(start = 40.dp, end = 40.dp, top = 10.dp).livingEnter(120),
        )
        Box(Modifier.fillMaxWidth().padding(top = 20.dp).livingEnter(180), contentAlignment = Alignment.Center) {
            WarmButton("Set up Agent & sync", onSetup)
        }
        Text(
            "You'll be able to ask", style = OrbitTypography.Eyebrow,
            modifier = Modifier.padding(start = Gutter, end = Gutter, top = 28.dp, bottom = 10.dp).lineBox(OrbitTypography.Eyebrow).livingEnter(240),
        )
        Column(Modifier.padding(horizontal = Gutter).livingEnter(240), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AskSuggestions.forEach { AskChip(it, onClick = null) }
        }
    }
}

@Composable
private fun Conversation(
    state: AskUiState,
    statusTop: Dp,
    bottom: Dp,
    onSend: (String) -> Unit,
    onRetry: (AskMessage.Problem) -> Unit,
    onSetup: () -> Unit,
    initialDraft: String,
    onNewConversation: () -> Unit,
) {
    var draft by rememberSaveable { mutableStateOf(initialDraft) }
    val list = rememberLazyListState()
    val count = state.messages.size + (if (state.sending) 1 else 0)
    LaunchedEffect(count) { if (count > 0) list.animateScrollToItem(count) }
    val send = { text: String -> onSend(text); draft = "" }
    Column(Modifier.fillMaxSize().padding(top = statusTop, bottom = bottom)) {
        Header(Modifier, state.serverName)
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            state = list,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = Gutter, end = Gutter, top = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (state.messages.isEmpty() && !state.sending) {
                item(key = "intro") { Intro(state.serverName, onAsk = { if (!state.sending) send(it) }) }
            }
            items(state.messages, key = { it.id }) { m ->
                when (m) {
                    is AskMessage.User -> UserBubble(m.text)
                    is AskMessage.Agent -> AgentBubble(m)
                    is AskMessage.Problem -> ProblemBubble(m, onRetry = { onRetry(m) }, onSetup = onSetup, onNewConversation = onNewConversation)
                }
            }
            if (state.sending) item(key = "thinking") { Thinking() }
            item(key = "end") { Spacer(Modifier.height(1.dp)) }
        }
        Composer(
            draft, onDraft = { draft = it }, enabled = !state.sending, onSend = { send(draft) }, onSendText = { send(it) },
            modifier = Modifier.padding(horizontal = Gutter),
        )
    }
}

@Composable
private fun Intro(serverName: String?, onAsk: (String) -> Unit) {
    Column {
        OrbitIllustration(Modifier.fillMaxWidth().livingEnter(60), height = 170.dp)
        Text(
            buildAnnotatedString {
                append("Ask your ring ")
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append("anything.") }
            },
            style = AskHeadline, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).livingEnter(120),
        )
        Text(
            "Answers come from your own data on ${serverName ?: "your server"}, and show what they're based on.",
            style = OrbitTypography.Lead, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp).livingEnter(120),
        )
        Text(
            "Try asking", style = OrbitTypography.Eyebrow,
            modifier = Modifier.padding(top = 24.dp, bottom = 10.dp).lineBox(OrbitTypography.Eyebrow).livingEnter(180),
        )
        Column(Modifier.livingEnter(180), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AskSuggestions.forEach { q -> AskChip(q, onClick = { onAsk(q) }) }
        }
    }
}

private val UserShape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomEnd = 6.dp, bottomStart = 20.dp)
private val AgentShape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomEnd = 22.dp, bottomStart = 6.dp)

/** The user's question: right-aligned, ink 10% with an ink 14% hairline, Manrope body. */
@Composable
private fun UserBubble(text: String) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Text(
            text, style = OrbitTypography.Body,
            modifier = Modifier.widthIn(max = 300.dp).clip(UserShape).background(OrbitPalette.Ink.copy(alpha = 0.10f))
                .border(1.dp, OrbitPalette.Ink.copy(alpha = 0.14f), UserShape).padding(horizontal = 16.dp, vertical = 11.dp)
                .semantics { contentDescription = "You asked: $text" },
        )
    }
}

/** The agent's answer: the ask-chip tint with the warm hairline, Fraunces, then the chart and the provenance chip. */
@Composable
private fun AgentBubble(m: AskMessage.Agent) {
    Column(Modifier.fillMaxWidth().padding(end = 24.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
        Column(
            Modifier.clip(AgentShape).background(OrbitPalette.TintAskChip).border(1.dp, OrbitPalette.HairlineWarm, AgentShape)
                .padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 16.dp),
        ) {
            Text(m.text, style = AgentVoice)
            m.chart?.let { MiniChart(it, Modifier.padding(top = 14.dp)) }
        }
        m.provenance?.let { ProvenanceChip(it, Modifier.padding(top = 8.dp, start = 4.dp)) }
    }
}

/** A trend from this phone's database for the range the agent read; never the model's numbers. */
@Composable
private fun MiniChart(c: AskChart, modifier: Modifier) {
    val hue = metricHue(c.metric)
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(cardTint(hue, 0.08f))
            .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
    ) {
        Sparkline(c.values, hue, fill = true, height = 44.dp, minSpan = minSpan(c.metric))
        Text(c.label, style = OrbitTypography.Caption, modifier = Modifier.padding(top = 6.dp))
    }
}

private fun metricHue(m: Metric): Color = when (m) {
    Metric.HEART_RATE, Metric.HRV, Metric.RESPIRATORY_RATE, Metric.BLOOD_PRESSURE_SYS, Metric.BLOOD_PRESSURE_DIA -> OrbitPalette.HeartA
    Metric.SPO2 -> OrbitPalette.Spo2A
    Metric.STRESS -> OrbitPalette.StressA
    Metric.STEPS, Metric.STEPS_DAY_TOTAL, Metric.DISTANCE_M, Metric.CALORIES_KCAL -> OrbitPalette.ActivityB
}

private fun minSpan(m: Metric): Float = when (m) {
    Metric.HEART_RATE -> 20f
    Metric.HRV -> 20f
    Metric.SPO2 -> 6f
    else -> 0f
}

/** "Based on: HRV, resting HR · 3–5 Nov": a quiet pill under the answer. */
@Composable
private fun ProvenanceChip(text: String, modifier: Modifier) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier.clip(shape).border(1.dp, OrbitPalette.Hairline, shape).padding(start = 9.dp, end = 12.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Symbol(OrbitSymbol.Doc, size = 13.dp, strokeWidth = 1.8f, tint = OrbitPalette.Ink2)
        Spacer(Modifier.size(6.dp))
        Text(text, style = OrbitTypography.Caption)
    }
}

/** A question that got no answer: the reason in the attention tint, and a way forward. */
@Composable
private fun ProblemBubble(m: AskMessage.Problem, onRetry: () -> Unit, onSetup: () -> Unit, onNewConversation: () -> Unit) {
    TintedCard(
        OrbitPalette.Attention, Modifier.fillMaxWidth().padding(end = 24.dp).semantics { liveRegion = LiveRegionMode.Polite },
        shape = AgentShape,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 14.dp),
    ) {
        Text(m.problem.message, style = OrbitTypography.Body)
        if (m.problem.needsSetup) {
            GhostButton("Open Agent & sync", onSetup, Modifier.padding(top = 10.dp))
        } else if (m.problem.needsNewConversation) {
            GhostButton("Start a new conversation", onNewConversation, Modifier.padding(top = 10.dp))
        } else {
            GhostButton("Try again", onRetry, Modifier.padding(top = 10.dp))
        }
    }
}

@Composable
private fun Thinking() {
    Text(
        "Looking at your data…", style = AgentVoice.copy(fontStyle = FontStyle.Italic, color = OrbitPalette.Ink2),
        modifier = Modifier.clip(AgentShape).background(OrbitPalette.TintAskChip.copy(alpha = 0.6f))
            .border(1.dp, OrbitPalette.HairlineWarm, AgentShape).padding(horizontal = 18.dp, vertical = 12.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/** The composer: 52dp pill (taller for longer questions), hairline border, ink 4% fill, and the send button. */
@Composable
private fun Composer(
    draft: String,
    onDraft: (String) -> Unit,
    enabled: Boolean,
    onSend: () -> Unit,
    onSendText: (String) -> Unit,
    modifier: Modifier,
) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val shape = RoundedCornerShape(26.dp)
    val canSend = enabled && draft.isNotBlank()
    Row(
        modifier.fillMaxWidth().heightIn(min = 52.dp).clip(shape).background(OrbitPalette.Base1.copy(alpha = 0.85f))
            .background(OrbitPalette.Ink.copy(alpha = 0.04f)).border(1.dp, OrbitPalette.Hairline, shape)
            .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).padding(end = 8.dp)) {
            BasicTextField(
                value = draft,
                onValueChange = { v ->
                    // Enter sends (the field wraps long questions but takes no line breaks).
                    if ('\n' in v) {
                        val text = v.replace('\n', ' ')
                        if (enabled && text.isNotBlank()) { focus.clearFocus(); keyboard?.hide(); onSendText(text) }
                    }
                    else onDraft(v.take(AskViewModel.MAX_QUESTION))
                },
                maxLines = 4,
                textStyle = OrbitTypography.Body, cursorBrush = SolidColor(OrbitPalette.SleepB),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (canSend) { focus.clearFocus(); onSend() } }),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Ask your agent" },
                decorationBox = { field ->
                    if (draft.isEmpty()) Text("Ask about your sleep, heart or activity", style = OrbitTypography.Meta, maxLines = 1)
                    field()
                },
            )
        }
        Box(
            Modifier.size(36.dp).alpha(if (canSend) 1f else 0.5f).clip(CircleShape)
                .background(if (canSend) OrbitPalette.Ink else OrbitPalette.Ink.copy(alpha = 0.08f))
                .border(1.dp, OrbitPalette.Ink.copy(alpha = 0.14f), CircleShape)
                .clickable(remember { MutableInteractionSource() }, indication = null, enabled = canSend, role = Role.Button) {
                    focus.clearFocus(); keyboard?.hide(); onSend()
                }
                .semantics { contentDescription = "Send" },
            contentAlignment = Alignment.Center,
        ) { Symbol(OrbitSymbol.Send, size = 18.dp, strokeWidth = 1.7f, tint = if (canSend) OrbitPalette.OnInk else OrbitPalette.Ink) }
    }
}

/**
 * The mockup's orbit (an svg 390 × 230 with viewBox y from 15): two orbits tilted −12° about (195, 118)
 * (150 × 46 at ink 14%, 108 × 32 at ink 20%), a warm planet at (330, 88) with a soft halo, a lilac moon at (96, 140),
 * and the 92dp mark centred at (195, 103).
 */
@Composable
private fun OrbitIllustration(modifier: Modifier = Modifier, height: Dp = 200.dp) {
    Box(modifier.fillMaxWidth().height(height).clearAndSetSemantics { }) {
        val scale = height / 200.dp
        Canvas(Modifier.fillMaxWidth().height(230.dp * scale)) {
            val u = 1.dp.toPx() * scale
            val dx = (size.width - 390f * u) / 2f
            fun p(x: Float, y: Float) = Offset(dx + x * u, (y - 15f) * u)
            val c = p(195f, 118f)
            rotate(-12f, pivot = c) {
                drawOval(OrbitPalette.Ink.copy(alpha = 0.14f), topLeft = c - Offset(150f * u, 46f * u), size = Size(300f * u, 92f * u), style = Stroke(1.dp.toPx()))
                drawOval(OrbitPalette.Ink.copy(alpha = 0.20f), topLeft = c - Offset(108f * u, 32f * u), size = Size(216f * u, 64f * u), style = Stroke(1.dp.toPx()))
            }
            drawCircle(PlanetWarm.copy(alpha = 0.18f), radius = 8f * u, center = p(330f, 88f))
            drawCircle(PlanetWarm, radius = 3f * u, center = p(330f, 88f))
            drawCircle(OrbitPalette.StageRem, radius = 2.2f * u, center = p(96f, 140f))
        }
        BrandMark(92.dp * scale, Modifier.align(Alignment.TopCenter).offset(y = (103.dp - 46.dp) * scale))
    }
}

private val PlanetWarm = Color(0xFFFFE6CC)

/** A question chip: 10/14 padding, 18-18-18-6 corners, ask-chip tint with the warm hairline, Manrope 500 14/19 @ 82%. */
@Composable
private fun AskChip(text: String, onClick: (() -> Unit)?) {
    val shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomEnd = 18.dp, bottomStart = 6.dp)
    Text(
        text,
        style = OrbitTypography.Body.copy(fontSize = 14.sp, lineHeight = 19.sp, color = OrbitPalette.InkSky2),
        modifier = Modifier
            .clip(shape)
            .background(OrbitPalette.TintAskChip)
            .border(1.dp, OrbitPalette.HairlineWarm, shape)
            .then(
                if (onClick != null) Modifier.clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onClick)
                else Modifier,
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
    )
}
