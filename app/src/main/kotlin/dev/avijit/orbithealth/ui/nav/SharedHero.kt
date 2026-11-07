package dev.avijit.orbithealth.ui.nav

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import dev.avijit.orbithealth.ui.components.rememberMotionEnabled

/** The app's [SharedTransitionScope] (set by [OrbitNavHost]); null in previews, the gallery and tests. */
@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedTransitionScope = staticCompositionLocalOf<SharedTransitionScope?> { null }

/** The current destination's enter/exit scope inside the NavHost; null outside it (and on the Sleep tab). */
val LocalNavAnimatedScope = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

/** Tokens §6: hero → detail bounds spring (damping 0.9, stiffness 300). */
@OptIn(ExperimentalSharedTransitionApi::class)
val HeroBoundsTransform = BoundsTransform { _, _ -> spring(dampingRatio = 0.9f, stiffness = 300f) }

/** Tokens §6: what is not shared (the two sides' text and content) cross-fades over 220 ms. */
const val HeroCrossfadeMs = 220

/**
 * A shared-element modifier for a hero part keyed [key] ("hero-sky-SLEEP", "hero-arc-SLEEP"). Both are shared bounds
 * whose two sides cross-fade over [HeroCrossfadeMs]: the sky remeasures into the detail header ([bounds] = true); the
 * arc ([bounds] = false) scales between its hero and detail sizes. A plain [Modifier] when there is no shared scope
 * (previews, gallery, tests, the Sleep tab) or when motion is off ("Remove animations": no shared transition).
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedHero(key: String, bounds: Boolean): Modifier {
    val shared = LocalSharedTransitionScope.current ?: return this
    val anim = LocalNavAnimatedScope.current ?: return this
    if (!rememberMotionEnabled()) return this
    return with(shared) {
        val state = rememberSharedContentState(key)
        this@sharedHero.sharedBounds(
            state, anim,
            enter = fadeIn(tween(HeroCrossfadeMs)),
            exit = fadeOut(tween(HeroCrossfadeMs)),
            boundsTransform = HeroBoundsTransform,
            resizeMode = if (bounds) SharedTransitionScope.ResizeMode.RemeasureToBounds else SharedTransitionScope.ResizeMode.ScaleToBounds(),
        )
    }
}

/** True while a hero ↔ detail shared transition is running (Today's sky then holds its frame). */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun heroTransitionActive(): Boolean = LocalSharedTransitionScope.current?.isTransitionActive == true
