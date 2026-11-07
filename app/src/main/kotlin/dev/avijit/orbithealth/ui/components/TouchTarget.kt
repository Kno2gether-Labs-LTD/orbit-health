package dev.avijit.orbithealth.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Material's minimum touch target. */
val MinTouchTarget = 48.dp

/**
 * Keeps a control's layout at [visible] height (so nothing around it moves) while what follows in the chain (the
 * clickable and its content) is measured at least [touch] tall and overflows: centred on the visible box, or hanging
 * below it when [hangBelow] (a tab row whose gap underneath is part of its target). Compose delivers touches to
 * children outside their parent's bounds as long as no ancestor clips, so the whole [touch] height is tappable.
 */
fun Modifier.touchTargetHeight(visible: Dp, touch: Dp = MinTouchTarget, hangBelow: Boolean = false): Modifier =
    layout { measurable, constraints ->
        val v = visible.roundToPx()
        val t = maxOf(touch.roundToPx(), v)
        val p = measurable.measure(constraints.copy(minHeight = t, maxHeight = t))
        layout(p.width, v) { p.place(0, if (hangBelow) 0 else -(t - v) / 2) }
    }
