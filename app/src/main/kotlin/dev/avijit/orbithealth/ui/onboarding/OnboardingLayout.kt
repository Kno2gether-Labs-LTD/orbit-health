package dev.avijit.orbithealth.ui.onboarding

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.avijit.orbithealth.ui.components.BrandMark
import dev.avijit.orbithealth.ui.components.GhostButtonPair
import dev.avijit.orbithealth.ui.components.livingEnter
import dev.avijit.orbithealth.ui.components.rememberMotionEnabled
import dev.avijit.orbithealth.ui.sky.Sky
import dev.avijit.orbithealth.ui.sky.SkyDefaults
import dev.avijit.orbithealth.ui.sky.SkyKind
import dev.avijit.orbithealth.ui.theme.OrbitPalette
import dev.avijit.orbithealth.ui.theme.OrbitTypography

/* Welcome and Pair, laid out from the Living Skies mockup (screen-01, 390 × 844 with a 36dp status bar). */
internal val OnboardingGutter = 24.dp

/** The Welcome card on the sky (mockup: rgba(16,21,50,.62) fill, ink 12% border, radius 24, padding 16/18). */
internal val OnboardingCardFill = Color(0xFF101532).copy(alpha = 0.62f)
internal val OnboardingCardBorder = OrbitPalette.Ink.copy(alpha = 0.12f)
internal val OnboardingCardShape = RoundedCornerShape(24.dp)
internal val OnboardingCardPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp)

/** Where the sun sits (tokens §7 First light `param`): on the horizon before a ring is known, rising as it is found. */
internal object Sunrise {
    const val WAITING = 0.5f
    const val SCANNING = 0.56f
    const val FOUND = 0.6f
    const val CONNECTED = 0.74f
}

/** "Orbit *Health*" with the italic word, as on Welcome and Lock. */
internal val BrandTitle = buildAnnotatedString {
    append("Orbit ")
    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append("Health") }
}

/**
 * Welcome and Pair share this frame, so moving between them only changes the card: the full-screen First light sky
 * (horizon at 62%, 22 stars in the top 35%), the 112dp mark, "Orbit *Health*" and the promise stay put. The card
 * sits 45dp under the horizon, on the water below the sun, as in the mockup; [bottom] (button and footer) follows
 * it. [sun] is the sky's sunrise (0.5 = on the horizon). Scrolls when a large font needs more room.
 */
@Composable
internal fun OnboardingFrame(
    sun: Float,
    card: @Composable ColumnScope.() -> Unit,
    bottom: @Composable ColumnScope.() -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize().background(OrbitPalette.Base0)) {
        val screen = maxHeight
        Sky(
            SkyKind.FIRST_LIGHT, Modifier.fillMaxSize(), param = sun, horizon = SkyDefaults.WelcomeHorizon,
            stars = 22, starSeed = 3,
        )
        val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val cardTop = screen * SkyDefaults.WelcomeHorizon + 45.dp
        SplitColumn(
            minHeight = screen,
            bottomTop = cardTop,
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
            top = {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    // Mockup: the mark 92dp under the 36dp status bar.
                    Spacer(Modifier.height(statusTop + 92.dp))
                    BrandMark(112.dp, Modifier.livingEnter(0))
                    Text(
                        BrandTitle, style = OrbitTypography.TitleBrand, textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 30.dp).livingEnter(60).semantics { heading() },
                    )
                    Text(
                        "Your ring. Your data. On your phone, and nowhere else unless you say so.",
                        style = OrbitTypography.Lead.copy(fontSize = PromiseSize, lineHeight = PromiseLine, color = OrbitPalette.InkSky2),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(start = 40.dp, end = 40.dp, top = 12.dp).livingEnter(120),
                    )
                }
            },
            bottom = {
                Column(Modifier.fillMaxWidth().padding(horizontal = OnboardingGutter).padding(top = 16.dp, bottom = navBottom + 24.dp)) {
                    card()
                    Spacer(Modifier.height(16.dp))
                    bottom()
                }
            },
        )
    }
}

/** Welcome's lead at 16/24 (tokens §3 lead, "Welcome 16 / 24 @ 82%"). */
private val PromiseSize = OrbitTypography.Lead.fontSize * (16f / 15f)
private val PromiseLine = OrbitTypography.Lead.lineHeight * (24f / 22f)

/**
 * [top] from the top; [bottom] placed so its content starts at [bottomTop] (its own 16dp top padding sits above
 * that line), or straight after [top] when that would overlap. At least [minHeight] tall.
 */
@Composable
private fun SplitColumn(
    minHeight: Dp,
    bottomTop: Dp,
    modifier: Modifier,
    top: @Composable () -> Unit,
    bottom: @Composable () -> Unit,
) {
    Layout(contents = listOf(top, bottom), modifier = modifier) { (topM, bottomM), constraints ->
        val loose = constraints.copy(minHeight = 0, maxHeight = androidx.compose.ui.unit.Constraints.Infinity)
        val t = topM.first().measure(loose)
        val b = bottomM.first().measure(loose)
        val y = maxOf(t.height, (bottomTop - 16.dp).roundToPx())
        val height = maxOf(minHeight.roundToPx(), y + b.height)
        layout(constraints.maxWidth, height) {
            t.place(0, 0)
            b.place(0, y)
        }
    }
}

/** The Welcome card surface: the mockup's translucent midnight glass on the sky. */
@Composable
internal fun OnboardingCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .background(OnboardingCardFill, OnboardingCardShape)
            .border(1.dp, OnboardingCardBorder, OnboardingCardShape)
            .padding(OnboardingCardPadding),
        content = content,
    )
}

/** The quiet promise under the button (tokens meta, ink2). */
@Composable
internal fun Footer(text: String = "No account. No cloud. No sign-in.", color: Color = OrbitPalette.Ink2) {
    Text(
        text,
        style = OrbitTypography.Meta.copy(color = color),
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
    )
}

/** A calm explanation with two ways forward, in the Welcome card. */
@Composable
internal fun ExplanationCard(
    title: String,
    body: String,
    first: Pair<String, () -> Unit>,
    second: Pair<String, () -> Unit>,
) {
    OnboardingCard {
        Text(title, style = OrbitTypography.BodyStrong)
        Text(body, style = OrbitTypography.Meta, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))
        GhostButtonPair(first.first, first.second, second.first, second.second)
    }
}

/**
 * The mockup's ring found glyph: a 22Ø ring in #B67BEA (5dp stroke) inside a 40dp cell. While [pinging], a 1dp
 * mint ring pings out from it (scale 0.6 → 2.4, alpha 0.9 → 0 over 2.4 s, ease-out, on loop; tokens §6).
 */
@Composable
internal fun RingPing(pinging: Boolean, modifier: Modifier = Modifier) {
    val animate = pinging && rememberMotionEnabled()
    Box(modifier.size(40.dp), contentAlignment = Alignment.Center) {
        if (animate) {
            val t = rememberInfiniteTransition(label = "ping")
            val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart), label = "p")
            Canvas(
                Modifier.size(40.dp).graphicsLayer {
                    // CSS ease-out: cubic-bezier(0, 0, 0.58, 1).
                    val e = PingEasing.transform(p)
                    val s = 0.6f + 1.8f * e
                    scaleX = s; scaleY = s; alpha = 0.9f * (1f - e)
                },
            ) { drawCircle(PingColor, radius = size.minDimension / 2f - 0.5.dp.toPx(), style = Stroke(1.dp.toPx())) }
        }
        Canvas(Modifier.size(22.dp)) {
            val w = 5.dp.toPx()
            drawCircle(RingGlyph, radius = size.minDimension / 2f - w / 2f, style = Stroke(w))
        }
    }
}

private val PingEasing = androidx.compose.animation.core.CubicBezierEasing(0f, 0f, 0.58f, 1f)
private val PingColor = Color(0xFF5ED6C2).copy(alpha = 0.6f)
private val RingGlyph = Color(0xFFB67BEA)

/** Ring name on Welcome (tokens ringName at 17/22). */
internal val RingNameWelcome = OrbitTypography.RingName.copy(
    fontSize = OrbitTypography.RingName.fontSize * (17f / 19f),
    lineHeight = OrbitTypography.RingName.lineHeight * (22f / 24f),
)

/** The right-hand figure on the ring card (Manrope 600 15/20). */
internal val CardFigure = OrbitTypography.BodyStrong
