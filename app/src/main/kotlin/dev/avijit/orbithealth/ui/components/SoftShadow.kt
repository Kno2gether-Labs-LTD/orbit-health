package dev.avijit.orbithealth.ui.components

import android.graphics.BlurMaskFilter
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A CSS `box-shadow: 0 [offsetY] [blur] [spread] [color]` behind a rounded rect (pill by default): the rect grown by
 * [spread] (negative shrinks it), moved down by [offsetY] and blurred with sigma = blur / 2 (Android's
 * BlurMaskFilter radius ≈ σ / 0.57735). Hardware-accelerated blur masks are supported from API 28. Unclipped, so it
 * must sit before any clip in the modifier chain.
 */
fun Modifier.softShadow(color: Color, offsetY: Dp, blur: Dp, spread: Dp = 0.dp, cornerRadius: Dp? = null): Modifier = drawBehind {
    drawSoftShadow(color, offsetY.toPx(), blur.toPx(), spread.toPx(), cornerRadius?.toPx())
}

internal fun DrawScope.drawSoftShadow(color: Color, dy: Float, blur: Float, spread: Float, corner: Float? = null) {
    val l = -spread
    val t = -spread + dy
    val r = size.width + spread
    val b = size.height + spread + dy
    if (r <= l || b <= t) return
    val radius = corner?.let { (it + spread).coerceAtLeast(0f) } ?: ((b - t) / 2f)
    val sigma = blur / 2f
    val paint = Paint().asFrameworkPaint().apply {
        isAntiAlias = true
        this.color = color.toArgb()
        if (sigma > 0f) maskFilter = BlurMaskFilter(sigma / 0.57735f, BlurMaskFilter.Blur.NORMAL)
    }
    drawIntoCanvas { it.nativeCanvas.drawRoundRect(l, t, r, b, radius, radius, paint) }
}

/**
 * A planet-style glow around a circle of [radius] at ([cx], [cy]): CSS `box-shadow: 0 0 [blur] [spread] [color]` on a
 * round element, drawn as a blurred disc.
 */
internal fun DrawScope.drawCircleGlow(cx: Float, cy: Float, radius: Float, color: Color, blur: Float, spread: Float = 0f) {
    val sigma = blur / 2f
    val paint = Paint().asFrameworkPaint().apply {
        isAntiAlias = true
        this.color = color.toArgb()
        if (sigma > 0f) maskFilter = BlurMaskFilter(sigma / 0.57735f, BlurMaskFilter.Blur.NORMAL)
    }
    drawIntoCanvas { it.nativeCanvas.drawCircle(cx, cy, radius + spread, paint) }
}
