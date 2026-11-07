package dev.avijit.orbithealth.ui.sky

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale

/**
 * The API 31–32 sky (no RuntimeShader): the token fill, blobs and First light's sun group, drawn once with Compose
 * brushes at the static frame (t = 0). Only the fbm noise is skipped; the global grain overlay still gives the surface
 * texture, so the fallback reads as a deliberate, quieter sky rather than a broken one.
 */
internal fun DrawScope.drawSkyFallback(spec: SkySpec, frame: SkyFrame) {
    val px = density
    val h = size.height

    // 1. Fill.
    val stops = spec.stops.map { it.position.coerceIn(0f, 1f) to it.color }.toTypedArray()
    drawRect(Brush.verticalGradient(*stops, startY = 0f, endY = h))

    // 2. Blobs (under and over the noise alike, in order).
    frame.blobs.filter { !it.afterNoise }.forEach { drawBlob(it, px) }
    frame.blobs.filter { it.afterNoise }.forEach { drawBlob(it, px) }

    // 3. Sun group.
    spec.sun?.let { sun ->
        val hz = frame.horizonY * px
        val c = Offset(frame.sunX * px, frame.sunY * px)
        clipRect(bottom = hz) {
            val haloR = (sun.radius + sun.haloSpread + sun.haloBlur) * px
            val inner = ((sun.radius + sun.haloSpread - sun.haloBlur) * px / haloR).coerceIn(0f, 1f)
            drawCircle(
                Brush.radialGradient(0f to sun.halo, inner to sun.halo, 1f to sun.halo.copy(alpha = 0f), center = c, radius = haloR),
                radius = haloR, center = c,
            )
            // CSS radial-gradient(circle, ...) runs to the farthest corner (r√2); the round element clips it at r.
            val r = sun.radius * px
            drawCircle(
                Brush.radialGradient(
                    0f to sun.core, sun.coreStop to sun.core, sun.midStop to sun.mid, 1f to sun.mid.copy(alpha = 0f),
                    center = c, radius = r * 1.41421356f,
                ),
                radius = r, center = c,
            )
        }
        val line = sun.horizonLine
        drawRect(
            Brush.horizontalGradient(0f to line.copy(alpha = 0f), 0.5f to line, 1f to line.copy(alpha = 0f)),
            topLeft = Offset(0f, hz), size = Size(size.width, px),
        )
        val top = hz + px
        val rx = sun.reflectWidth / 2f * px
        val ry = sun.reflectHeight * px
        val rc = Offset(size.width / 2f, top)
        val reflect = sun.reflect.copy(alpha = sun.reflect.alpha * frame.reflectAlpha)
        clipRect(top = top) {
            scale(1f, ry / rx, pivot = rc) {
                drawCircle(Brush.radialGradient(listOf(reflect, reflect.copy(alpha = 0f)), center = rc, radius = rx), radius = rx, center = rc)
            }
        }
    }

    // 4. Scrim.
    spec.scrim?.let { s ->
        drawRect(Brush.verticalGradient(0f to s.color.copy(alpha = 0f), s.from to s.color.copy(alpha = 0f), 1f to s.color.copy(alpha = s.maxAlpha), startY = 0f, endY = h))
    }
}

/** An elliptical radial blob: a circular gradient at radius rx, squashed vertically about its centre. */
private fun DrawScope.drawBlob(b: ResolvedBlob, px: Float) {
    if (b.rx <= 0f || b.ry <= 0f) return
    val c = Offset(b.cx * px, b.cy * px)
    val rx = b.rx * px
    val end = (b.midColor ?: b.color).copy(alpha = 0f)
    val brush = if (b.midStop != null && b.midColor != null) {
        Brush.radialGradient(0f to b.color, b.midStop to b.midColor, 1f to end, center = c, radius = rx)
    } else {
        Brush.radialGradient(0f to b.color, 1f to end, center = c, radius = rx)
    }
    scale(1f, b.ry / b.rx, pivot = c) { drawCircle(brush, radius = rx, center = c) }
}
