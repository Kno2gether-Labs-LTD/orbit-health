package dev.avijit.orbithealth.ui.components

import android.annotation.SuppressLint
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalView

/** The moments the app answers with a tap of the motor (tokens §6). */
enum class HapticEvent { UnlockSuccess, MeasureStart, MeasureDone, TabChange, PagerSettle }

/** Plain rules behind the haptics, so they can be tested on the JVM. */
object HapticEvents {
    /** The platform feedback constant for [event]: Confirm, ContextClick, or SegmentTick (TextHandleMove before 34). */
    @SuppressLint("InlinedApi") // SEGMENT_TICK is only returned when sdkInt >= 34.
    fun constantFor(event: HapticEvent, sdkInt: Int = Build.VERSION.SDK_INT): Int = when (event) {
        HapticEvent.UnlockSuccess, HapticEvent.MeasureDone -> HapticFeedbackConstants.CONFIRM
        HapticEvent.MeasureStart -> HapticFeedbackConstants.CONTEXT_CLICK
        HapticEvent.TabChange, HapticEvent.PagerSettle ->
            if (sdkInt >= 34) HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.TEXT_HANDLE_MOVE
    }

    /** "Measure now" finished with a reading (not still running, not refused by the ring, not empty). */
    fun measureFinished(wasMeasuring: Boolean, measuring: Boolean, gotReading: Boolean, failed: Boolean): Boolean =
        wasMeasuring && !measuring && gotReading && !failed

    /** The pager came to rest on a different page than before; the first settle (the initial page) is silent. */
    fun pagerSettled(previous: Int?, current: Int): Boolean = previous != null && previous != current
}

/** Plays a [HapticEvent]. Tests provide a recorder through [LocalHaptics]. */
fun interface Haptics {
    fun perform(event: HapticEvent)
}

/**
 * Haptics through [View.performHapticFeedback] without FLAG_IGNORE_GLOBAL_SETTING, so the system's touch-feedback
 * setting is respected and no VIBRATE permission is needed. This is what Compose's LocalHapticFeedback does underneath;
 * on Compose 1.7 its HapticFeedbackType only offers LongPress and TextHandleMove, so the token constants go direct.
 */
class ViewHaptics(private val view: View) : Haptics {
    override fun perform(event: HapticEvent) {
        view.performHapticFeedback(HapticEvents.constantFor(event))
    }
}

/** Overrides the haptics for a subtree (tests); null means the view's own haptics. */
val LocalHaptics = staticCompositionLocalOf<Haptics?> { null }

@Composable
fun rememberHaptics(): Haptics {
    val override = LocalHaptics.current
    val view = LocalView.current
    return override ?: remember(view) { ViewHaptics(view) }
}
