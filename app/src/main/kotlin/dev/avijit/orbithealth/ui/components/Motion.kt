package dev.avijit.orbithealth.ui.components

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Glass Orbit motion language: subtle and fast. Cards fade in while rising 10dp over 260 ms, 40 ms apart;
 * big numerals count up over 600 ms with an ease-out so they settle gently; rings spring their arcs in.
 * Everything is skipped when the system "Remove animations" setting (animator duration scale 0) is on.
 */
object Motion {
    const val ENTER_MS = 260
    const val STAGGER_MS = 40
    const val COUNT_UP_MS = 600

    /** Cards past this index enter together with it, so a long list never leaves its tail waiting. */
    const val MAX_STAGGERED = 8

    /** Rise distance of entering cards, in dp. */
    const val ENTER_RISE_DP = 10

    /** Emphasised decelerate: quick start, long soft landing. */
    val EnterEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** Living Skies staggered entrance (tokens §6): alpha 0→1 and 16dp rise over 480 ms, 60 ms apart. */
    const val LIVING_ENTER_MS = 480
    const val LIVING_STAGGER_MS = 60
    const val LIVING_RISE_DP = 16
    val LivingEasing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

    fun staggerDelayMs(index: Int): Int = index.coerceIn(0, MAX_STAGGERED) * STAGGER_MS

    /**
     * Integer shown at [fraction] (0..1, clamped) of a count from [from] to [to], eased out (cubic). Monotonic in
     * [fraction] and exactly [to] at 1, so the number never steps back or lands off target.
     */
    fun countUpValue(from: Int, to: Int, fraction: Float): Int {
        val f = fraction.coerceIn(0f, 1f)
        if (f >= 1f) return to
        val eased = 1.0 - (1.0 - f).pow(3)
        return (from + (to - from) * eased).roundToInt()
    }

    /** The system animator duration scale; 0 means the user turned animations off. */
    fun animationsEnabled(animatorDurationScale: Float): Boolean = animatorDurationScale > 0f

    fun animationsEnabled(context: Context): Boolean = animationsEnabled(
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f),
    )
}

/** True when motion should play: animations are on in system settings and this is not a static preview. */
@Composable
fun rememberMotionEnabled(): Boolean {
    val context = LocalContext.current
    val static = LocalInspectionMode.current
    return remember(context, static) { !static && Motion.animationsEnabled(context) }
}

/**
 * Number for a big numeral that counts up to [target]: from 0 on first show, then from the old value whenever
 * [target] changes. Snaps straight to [target] when motion is off.
 */
@Composable
fun animateNumber(target: Int, durationMillis: Int = Motion.COUNT_UP_MS): State<Int> {
    val enabled = rememberMotionEnabled()
    val shown = remember { mutableIntStateOf(if (enabled) 0 else target) }
    LaunchedEffect(target, enabled) {
        val from = shown.intValue
        if (!enabled || from == target) {
            shown.intValue = target
            return@LaunchedEffect
        }
        Animatable(0f).animateTo(1f, tween(durationMillis, easing = LinearEasing)) {
            shown.intValue = Motion.countUpValue(from, target, value)
        }
        shown.intValue = target
    }
    return shown
}

/**
 * Entrance for a card: fades in while rising [Motion.ENTER_RISE_DP] dp, after a stagger of 40 ms per [index].
 * Plays once when the card first appears. Only the layer's alpha and translation change, so it never re-measures.
 */
fun Modifier.enterFadeSlide(index: Int = 0): Modifier =
    this then EnterFadeSlideElement(Motion.staggerDelayMs(index), Motion.ENTER_MS, Motion.ENTER_RISE_DP, living = false)

/**
 * Living Skies entrance (tokens §6): fades in while rising 16dp over 480 ms on the (0.22, 1, 0.36, 1) curve, after
 * [delayMs] (screens stagger 60 ms in reading order). Plays once per appearance; skipped when motion is off.
 */
fun Modifier.livingEnter(delayMs: Int): Modifier =
    this then EnterFadeSlideElement(delayMs, Motion.LIVING_ENTER_MS, Motion.LIVING_RISE_DP, living = true)

private data class EnterFadeSlideElement(
    val delayMs: Int, val durationMs: Int, val riseDp: Int, val living: Boolean,
) : ModifierNodeElement<EnterFadeSlideNode>() {
    override fun create() = EnterFadeSlideNode(delayMs, durationMs, riseDp, living)
    override fun update(node: EnterFadeSlideNode) {
        node.delayMs = delayMs
        node.durationMs = durationMs
        node.riseDp = riseDp
        node.living = living
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "enterFadeSlide"
        properties["delayMs"] = delayMs
    }
}

private class EnterFadeSlideNode(
    var delayMs: Int, var durationMs: Int, var riseDp: Int, var living: Boolean,
) : Modifier.Node(), LayoutModifierNode, CompositionLocalConsumerModifierNode {
    private var progress: Animatable<Float, AnimationVector1D> = Animatable(1f)
    private var started = false

    override fun onDetach() {
        started = false
    }

    /** Decided on the first measure after attach: the entrance plays once per appearance, never mid-life. */
    private fun startOnce() {
        if (started) return
        started = true
        val animate = !currentValueOf(LocalInspectionMode) && Motion.animationsEnabled(currentValueOf(LocalContext))
        progress = Animatable(if (animate) 0f else 1f)
        if (!animate) return
        coroutineScope.launch {
            delay(delayMs.toLong())
            progress.animateTo(1f, tween(durationMs, easing = if (living) Motion.LivingEasing else Motion.EnterEasing))
        }
    }

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        startOnce()
        val placeable = measurable.measure(constraints)
        val rise = riseDp.dp.toPx()
        return layout(placeable.width, placeable.height) {
            placeable.placeWithLayer(0, 0) {
                val t = progress.value
                alpha = t
                translationY = (1f - t) * rise
            }
        }
    }
}
