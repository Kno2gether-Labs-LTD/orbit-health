package dev.avijit.orbithealth.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.avijit.orbithealth.ui.theme.OrbitPalette

private fun circle(cx: Float, cy: Float, r: Float) = "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0"

/**
 * The Living Skies glyph set, ported 1:1 from the mockup's `G` object (24 × 24 viewBox, round caps and joins).
 * Circles and the lock's rounded rect are written as arcs; [filled] paths (the planet on "me") are filled, not stroked.
 */
enum class OrbitSymbol(val paths: List<String>, val filled: List<String> = emptyList()) {
    Today(listOf("M3 18h18", "M7 18a5 5 0 0 1 10 0", "M12 9V6.5M6.7 11.2 5 9.5M17.3 11.2 19 9.5")),
    Sleep(listOf("M19.5 14.6A7.8 7.8 0 0 1 9.4 4.5a7.8 7.8 0 1 0 10.1 10.1z")),
    Body(listOf("M12 19.5s-7.5-4.6-7.5-10.2A4.2 4.2 0 0 1 12 6.9a4.2 4.2 0 0 1 7.5 2.4c0 5.6-7.5 10.2-7.5 10.2z")),
    Ask(listOf("M12 3.5c.6 4.9 3 7.4 8 8.5-5 1.1-7.4 3.6-8 8.5-.6-4.9-3-7.4-8-8.5 5-1.1 7.4-3.6 8-8.5z")),
    Me(listOf(circle(11f, 13f, 6.8f)), filled = listOf(circle(17.6f, 6.4f, 2.1f))),
    Back(listOf("M14.5 5.5 8 12l6.5 6.5")),
    Chev(listOf("M9.5 5.5 16 12l-6.5 6.5")),
    Expand(listOf("M8 16 16 8M9.5 8H16v6.5")),
    Hrv(listOf("M3 12h3.2l2.1-5 3.4 10 2.8-7.5 1.8 2.5H21")),
    Drop(listOf("M12 3.8s6 6.4 6 10.6a6 6 0 0 1-12 0c0-4.2 6-10.6 6-10.6z")),
    Steps(
        listOf(
            "M8 3.5c1.7 0 2.8 1.9 2.8 4.4 0 1.9-.6 3.3-.9 4.6H6.1c-.3-1.3-.9-2.7-.9-4.6 0-2.5 1.1-4.4 2.8-4.4z",
            "M6.4 15.4h3.3v1.2a1.65 1.65 0 0 1-3.3 0z",
            "M16 6.5c1.7 0 2.8 1.9 2.8 4.4 0 1.9-.6 3.3-.9 4.6h-3.8c-.3-1.3-.9-2.7-.9-4.6 0-2.5 1.1-4.4 2.8-4.4z",
            "M14.4 18.4h3.3v.2a1.65 1.65 0 0 1-3.3 0z",
        ),
    ),
    Route(listOf(circle(6f, 18f, 2f), circle(18f, 6f, 2f), "M8 18h6.5a3.5 3.5 0 0 0 0-7h-5a3.5 3.5 0 0 1 0-7H16")),
    Flame(listOf("M12 20.5c-3.6 0-6-2.4-6-5.6 0-3.4 2.6-5 3.4-8.4 1.9 1.3 2.6 3.2 2.6 4.6.9-.6 1.5-1.6 1.7-2.8 2.1 1.7 4.3 4 4.3 6.6 0 3.2-2.4 5.6-6 5.6z")),
    Breath(listOf("M3 9.5c3 0 3-3 6-3s3 3 6 3 3-3 6-3", "M3 16.5c3 0 3-3 6-3s3 3 6 3 3-3 6-3")),
    Run(listOf(circle(14.5f, 4.8f, 1.7f), "M5.5 20.5 9 15.5l3 2.2 1-5.4 3 2.7h3", "M8 11l3.6-2.8 2.4 1.1")),
    Lotus(listOf("M12 19c-4.2 0-7.4-2.2-8.5-5.5 2.7-.5 5.4.3 8.5 3.2 3.1-2.9 5.8-3.7 8.5-3.2-1.1 3.3-4.3 5.5-8.5 5.5z", "M12 16.6c-1.9-2.1-2.7-4.8 0-9.6 2.7 4.8 1.9 7.5 0 9.6z")),
    Lock(listOf("M8 10.5h8a3 3 0 0 1 3 3v3.5a3 3 0 0 1-3 3H8a3 3 0 0 1-3-3v-3.5a3 3 0 0 1 3-3z", "M8.5 10.5V8a3.5 3.5 0 0 1 7 0v2.5")),
    Shield(listOf("M12 3.5 19 6.3v5.2c0 4.4-3 7.6-7 9-4-1.4-7-4.6-7-9V6.3z", "m9 12 2.2 2.2L15.5 10")),
    Doc(listOf("M7 3.5h7l4 4v13H7z", "M14 3.5v4h4")),
    Agent(listOf(circle(12f, 12f, 3f), "M12 3v3M12 18v3M3 12h3M18 12h3M5.6 5.6l2.1 2.1M16.3 16.3l2.1 2.1M5.6 18.4l2.1-2.1M16.3 7.7l2.1-2.1")),
    Finger(
        listOf(
            "M6.2 15.5c.5-1.3.8-2.8.8-4.5a5 5 0 0 1 10 0c0 1.1-.1 2.2-.3 3.2", "M12 11v1.5c0 2.8-.7 5.2-2 7.3",
            "M9.3 11a2.7 2.7 0 0 1 5.4 0v1.3c0 2.7-.5 5-1.4 7", "M4.4 8.6A8 8 0 0 1 19.6 8.6", "M15.8 17.6c-.3.9-.6 1.6-1 2.3",
        ),
    ),
    Sync(listOf("M19.5 10.5A7.7 7.7 0 0 0 5.6 7", "M4.5 13.5A7.7 7.7 0 0 0 18.4 17", "M5.3 3.8V7.2h3.4M18.7 20.2v-3.4h-3.4")),
    Down(listOf("M12 5v13M6.5 12.5 12 18l5.5-5.5")),
    Up(listOf("M12 19V6M6.5 11.5 12 6l5.5 5.5")),
    Plus(listOf("M12 5v14M5 12h14")),
    Send(listOf("M5 12h13M13 6.5 18.5 12 13 17.5")),
    /** Not in the mockup set: the selected option in a sheet list, drawn in the same stroke language. */
    Check(listOf("M5.5 12.5 10 17l8.5-9.5")),
    /** Not in the mockup set: blood pressure, a dial with its needle, in the same stroke language. */
    Gauge(listOf("M4.5 16.5a7.5 7.5 0 1 1 15 0", "M12 16.5l3.4-4.4", "M6.6 11.2l1.2.9M12 9v1.4M17.4 11.2l-1.2.9"), filled = listOf(circle(12f, 16.5f, 1.3f))),
    ;

    internal val strokePath: Path by lazy { Path().apply { paths.forEach { addPath(PathParser().parsePathString(it).toPath()) } } }
    internal val fillPath: Path? by lazy {
        if (filled.isEmpty()) null else Path().apply { filled.forEach { addPath(PathParser().parsePathString(it).toPath(), Offset.Zero) } }
    }
}

/**
 * Draws [symbol] at [size] with [strokeWidth] in viewBox units (the mockup uses 1.6 at 24, 1.7 at 18, 1.8 at 14–16;
 * like SVG, the stroke scales with the icon).
 */
@Composable
fun Symbol(
    symbol: OrbitSymbol,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    strokeWidth: Float = 1.6f,
    tint: Color = OrbitPalette.Ink,
) {
    Canvas(modifier.size(size)) {
        val k = this.size.minDimension / 24f
        scale(k, pivot = Offset.Zero) {
            drawPath(symbol.strokePath, tint, style = Stroke(strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))
            symbol.fillPath?.let { drawPath(it, tint, style = Fill) }
        }
    }
}
