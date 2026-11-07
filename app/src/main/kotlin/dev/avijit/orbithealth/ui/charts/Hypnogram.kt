package dev.avijit.orbithealth.ui.charts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import kotlin.math.roundToInt
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import dev.avijit.orbithealth.ring.Sample
import dev.avijit.orbithealth.ring.SleepSession
import dev.avijit.orbithealth.ring.SleepStage
import dev.avijit.orbithealth.ui.theme.OrbitPalette
import dev.avijit.orbithealth.ui.theme.OrbitTypography
import dev.avijit.orbithealth.ui.theme.lineBox
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.semantics
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.ceil

private val Lanes = listOf(SleepStage.AWAKE to "Awake", SleepStage.REM to "REM", SleepStage.LIGHT to "Light", SleepStage.DEEP to "Deep")
private val HHmm: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val LaneStyle = OrbitTypography.Caption
private val TooltipStyle = OrbitTypography.Caption.copy(color = OrbitPalette.Ink, fontWeight = FontWeight.SemiBold)

/** Tokens §5 Hypnogram (detail): sizes in dp. */
private object HypnogramSpec {
    /** Lane labels column (40) plus the gap to the plot (10). */
    const val GUTTER = 50f
    const val PLOT_H = 106f
    const val BAR_H = 12f
    const val BAR_INSET = 0.75f
    /** Time row 10dp under the plot, caption line 14. */
    const val AXIS_GAP = 10f
    const val AXIS_H = 14f
    const val TOTAL_H = PLOT_H + AXIS_GAP + AXIS_H
}

/** Narrowest stage run drawn as its own bar, in dp (about 8 min of a 6 h night); shorter runs join a neighbour, display only. */
internal const val MinRunDp = 6f

/** Stage colour (tokens §1 Sleep stages), shared by the hypnogram bars and the split bar. */
fun stageColor(stage: SleepStage): Color = when (stage) {
    SleepStage.AWAKE -> OrbitPalette.StageAwake
    SleepStage.REM -> OrbitPalette.StageRem
    SleepStage.LIGHT -> OrbitPalette.StageLight
    SleepStage.DEEP -> OrbitPalette.StageDeep
}

/**
 * Sleep stage graph (tokens §5 Hypnogram): four rows (Awake, REM, Light, Deep) spaced evenly over 106dp; each stage run
 * is a 12dp bar in its stage colour with fully rounded ends (inset 0.75dp each side), joined by 1dp ink 22% connectors;
 * the night's heart rate is a dotted pink overlay (16 time buckets on at least a 16 bpm span, so drift reads gently).
 * Lane names sit in a 40dp column on the left; times run along the bottom in the device's zone. An empty session
 * shows the lanes with "No sleep recorded".
 *
 * [interactive]: tapping a point marks it with a hairline and a tooltip of the stage, time and nearest heart rate
 * ("Deep · 02:14 · 56 bpm"); tapping the same spot again, or the lane labels, clears it.
 */
@Composable
fun Hypnogram(session: SleepSession, heart: List<Sample>, modifier: Modifier = Modifier, interactive: Boolean = false) {
    val tm = rememberTextMeasurer()
    val zone = ZoneId.systemDefault()
    val data = remember(session, heart, zone) { HypnogramData.of(session, heart, zone) }
    var selected by remember(session) { mutableStateOf<Long?>(null) }
    val tap = if (interactive && !data.empty) {
        Modifier.pointerInput(session, data) {
            detectTapGestures { o ->
                val gutter = HypnogramSpec.GUTTER.dp.toPx()
                val plotW = size.width - gutter
                if (o.x < gutter || plotW <= 0f) { selected = null; return@detectTapGestures }
                val span = data.window.second - data.window.first
                val t = data.window.first + (((o.x - gutter) / plotW).coerceIn(0f, 1f) * span).toLong()
                val prev = selected
                // Same spot again (within ~3% of the night) toggles the tooltip off.
                selected = if (prev != null && kotlin.math.abs(prev - t) < span / 32) null else t
            }
        }
    } else Modifier
    ChartFrame(data.empty, modifier.then(Modifier.fillMaxWidth().height(HypnogramSpec.TOTAL_H.dp)), message = "No sleep recorded") {
        Spacer(
            Modifier.fillMaxSize().then(tap).drawWithCache {
                val laneText = Lanes.map { (_, name) -> tm.measure(name, LaneStyle) }
                val gutter = HypnogramSpec.GUTTER.dp.toPx()
                val plotH = HypnogramSpec.PLOT_H.dp.toPx()
                val barH = HypnogramSpec.BAR_H.dp.toPx()
                val inset = HypnogramSpec.BAR_INSET.dp.toPx()
                val gap = (plotH - barH) / 3f
                // Row tops; bars and connectors work from the row centre.
                val laneY = Lanes.mapIndexed { i, (st, _) -> st to i * gap }.toMap()
                val plotW = size.width - gutter
                val hairW = 1.dp.toPx()
                val connector = OrbitPalette.Ink.copy(alpha = 0.22f)

                // Stage bars and connectors, all mapped through the one window (data.window) the overlay and labels use.
                class Bar(val x: Float, val w: Float, val y: Float, val color: Color)
                val bars = ArrayList<Bar>()
                val risers = ArrayList<Pair<Offset, Size>>()
                var prevY: Float? = null
                var lastX = gutter
                // Runs narrower than MinRunDp fold into their neighbour for drawing only (totals and the tooltip use the
                // session as recorded), so brief flickers don't draw as slivers with a pair of risers each.
                val minSec = if (plotW > 0f) ((data.window.second - data.window.first) * MinRunDp.dp.toPx() / plotW).toInt() else 0
                ChartMath.displaySegments(session.segments, minSec).forEach { seg ->
                    val y = laneY.getValue(seg.stage)
                    val x0 = maxOf(lastX, gutter + ChartMath.timeX(seg.start.epochSecond, data.window, plotW))
                    val x1 = maxOf(x0, gutter + ChartMath.timeX(seg.start.epochSecond + seg.durationSec, data.window, plotW))
                    prevY?.let { py ->
                        if (py != y) {
                            val y0 = minOf(py, y) + barH / 2
                            val y1 = maxOf(py, y) + barH / 2
                            risers += Offset(x0 - hairW / 2, y0) to Size(hairW, y1 - y0)
                        }
                    }
                    bars += Bar(x0 + inset, maxOf(x1 - x0 - 2 * inset, 2.dp.toPx()), y, stageColor(seg.stage))
                    lastX = x1
                    prevY = y
                }
                val barR = CornerRadius(barH / 2)

                // Heart-rate overlay, scaled into the band between the REM and Deep rows.
                val yTop = laneY.getValue(SleepStage.REM) + barH / 2 + gap * 0.35f
                val yBot = laneY.getValue(SleepStage.DEEP) + barH / 2 - gap * 0.2f
                val hrPath = data.heart.takeIf { it.size >= 2 }?.let { hp ->
                    smoothPath(hp.map { (f, v) -> Offset(gutter + f * plotW, ChartMath.scale(v, data.hrLo, data.hrHi, yBot, yTop)) })
                }
                val hrStroke = Stroke(
                    1.4.dp.toPx(), cap = StrokeCap.Round,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(1.dp.toPx(), 4.dp.toPx())),
                )

                // Time axis: window start, a couple of round hours, window end.
                val axisY = plotH + HypnogramSpec.AXIS_GAP.dp.toPx()
                val labels = ArrayList<Pair<Offset, TextLayoutResult>>()
                if (!data.empty) {
                    val first = tm.measure(data.startLabel, LaneStyle)
                    val last = tm.measure(data.endLabel, LaneStyle)
                    labels += Offset(gutter, axisY) to first
                    labels += Offset(size.width - last.size.width, axisY) to last
                    val minX = gutter + first.size.width + 8.dp.toPx()
                    val maxX = size.width - last.size.width - 8.dp.toPx()
                    data.ticks.forEach { (f, text) ->
                        val t = tm.measure(text, LaneStyle)
                        val cx = gutter + f * plotW - t.size.width / 2f
                        if (cx > minX && cx + t.size.width < maxX) labels += Offset(cx, axisY) to t
                    }
                }

                onDrawBehind {
                    Lanes.forEachIndexed { i, (st, _) ->
                        val t = laneText[i]
                        drawText(t, topLeft = Offset(0f, laneY.getValue(st) + barH / 2 - t.size.height / 2f))
                    }
                    risers.forEach { (o, sz) -> drawRect(connector, o, sz) }
                    bars.forEach { b -> drawRoundRect(b.color, Offset(b.x, b.y), Size(b.w, barH), CornerRadius(minOf(barR.x, b.w / 2f))) }
                    hrPath?.let { drawPath(it, Color(0xFFFF8AAA).copy(alpha = 0.75f), style = hrStroke) }
                    labels.forEach { (o, t) -> drawText(t, topLeft = o) }
                    selected?.let { t ->
                        val stage = ChartMath.stageAt(session, t) ?: return@let
                        val x = gutter + ChartMath.timeX(t, data.window, plotW)
                        val y = laneY.getValue(stage) + barH / 2
                        drawLine(OrbitPalette.Ink.copy(alpha = 0.35f), Offset(x, 0f), Offset(x, plotH), hairW)
                        drawCircle(stageColor(stage).copy(alpha = 0.3f), 7.dp.toPx(), Offset(x, y))
                        drawCircle(OrbitPalette.PlanetCore, 3.5.dp.toPx(), Offset(x, y))
                        val label = buildString {
                            append(Lanes.first { it.first == stage }.second).append(" · ")
                            append(HHmm.format(Instant.ofEpochSecond(t).atZone(zone)))
                            data.heartAt(t)?.let { append(" · ").append(it).append(" bpm") }
                        }
                        val text = tm.measure(label, TooltipStyle)
                        val padH = 8.dp.toPx()
                        val padV = 4.dp.toPx()
                        val w = text.size.width + 2 * padH
                        val h = text.size.height + 2 * padV
                        val left = (x - w / 2f).coerceIn(gutter, size.width - w)
                        // Above the lane it marks when there is room, otherwise below it.
                        val tipTop = if (y - h - 9.dp.toPx() >= 0f) y - h - 9.dp.toPx() else y + 9.dp.toPx()
                        val r = CornerRadius(h / 2f)
                        drawRoundRect(OrbitPalette.Base1.copy(alpha = 0.94f), Offset(left, tipTop), Size(w, h), r)
                        drawRoundRect(OrbitPalette.HairlineWarm, Offset(left, tipTop), Size(w, h), r, style = Stroke(hairW))
                        drawText(text, topLeft = Offset(left + padH, tipTop + padV))
                    }
                }
            },
        )
    }
}

/** Data prep for [Hypnogram], done once per (session, heart, zone) rather than per frame. */
private class HypnogramData(
    val empty: Boolean,
    val window: Pair<Long, Long>,
    /** Heart rate as (0..1 position in [window], bpm), bucketed to at most 24 points. */
    val heart: List<Pair<Float, Float>>,
    val hrLo: Float,
    val hrHi: Float,
    val startLabel: String,
    val endLabel: String,
    val ticks: List<Pair<Float, String>>,
    /** The night's readings (epoch s, bpm), time order, for the tap tooltip. */
    private val readings: List<Pair<Long, Int>>,
) {
    /** Heart rate of the reading nearest [epochS], if one is within 15 minutes. */
    fun heartAt(epochS: Long): Int? = readings.minByOrNull { kotlin.math.abs(it.first - epochS) }
        ?.takeIf { kotlin.math.abs(it.first - epochS) <= 15 * 60 }?.second

    companion object {
        fun of(session: SleepSession, heart: List<Sample>, zone: ZoneId): HypnogramData {
            val empty = session.segments.none { it.durationSec > 0 }
            val window = ChartMath.hypnogramWindow(session)
            val span = (window.second - window.first).toFloat()
            val hr = heart.filter { it.time.epochSecond in window.first..window.second }.sortedBy { it.time }
            val hrPts = if (empty || hr.size < 2) emptyList() else {
                val h0 = hr.first().time.epochSecond
                val h1 = hr.last().time.epochSecond
                ChartMath.bucketPoints(hr, 16).map { (f, v) -> ((h0 - window.first) + f * (h1 - h0)) / span to v }
            }
            // At least a 16 bpm span, so a few beats of overnight drift read as the gentle wave they are.
            val (lo, hi) = if (hrPts.isEmpty()) 0f to 0f else ChartMath.sparkRange(hrPts.map { it.second }, 16f)
            fun label(epochS: Long) = HHmm.format(Instant.ofEpochSecond(epochS).atZone(zone))
            val step = ceil(span / 3600f / 3f).toInt().coerceAtLeast(1)
            return HypnogramData(
                empty = empty,
                window = window,
                heart = hrPts,
                hrLo = lo,
                hrHi = hi,
                startLabel = label(window.first),
                endLabel = label(window.second),
                ticks = ChartMath.hourTicks(window.first, window.second, zone, step)
                    .map { (f, h) -> f to "${h.toString().padStart(2, '0')}:00" },
                readings = hr.map { it.time.epochSecond to it.value.roundToInt() },
            )
        }
    }
}

/**
 * Stage split (tokens §5): an 8dp bar of per-stage pieces sized by time, 3dp apart with radius 4, then a four-column
 * legend: a stage dot and name (caption) over its time ("1h 48m", 14sp semibold).
 */
@Composable
fun StageSplitBar(session: SleepSession, modifier: Modifier = Modifier) {
    val totals = remember(session) { ChartMath.stageTotals(session) }
    val order = listOf(SleepStage.AWAKE, SleepStage.REM, SleepStage.LIGHT, SleepStage.DEEP)
    val sum = totals.values.sum()
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(8.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            if (sum > 0) order.filter { totals.getValue(it) > 0 }.forEach { st ->
                Box(Modifier.weight(totals.getValue(st).toFloat()).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(stageColor(st)))
            } else Box(Modifier.fillMaxWidth().fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(OrbitPalette.Ink.copy(alpha = 0.10f)))
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            order.forEach { st ->
                val name = Lanes.first { it.first == st }.second
                Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(stageColor(st)))
                        Text(name, style = OrbitTypography.Caption.copy(fontFeatureSettings = null), maxLines = 1,
                            modifier = Modifier.padding(start = 5.dp).lineBox(OrbitTypography.Caption))
                    }
                    Text(
                        formatStageDuration(totals.getValue(st)), style = SplitValue, maxLines = 1,
                        modifier = Modifier.padding(top = 3.dp).lineBox(SplitValue),
                    )
                }
            }
        }
    }
}

/** Legend times: Manrope 600 14/18, tabular. */
private val SplitValue = OrbitTypography.BodyStrong.copy(fontSize = 14.sp, lineHeight = 18.sp, fontFeatureSettings = "tnum")

/** "26m" under an hour, "1h 48m" above (the mockup's legend). */
fun formatStageDuration(sec: Int): String {
    val m = sec / 60
    return if (m < 60) "${m}m" else "${m / 60}h ${(m % 60).toString().padStart(2, '0')}m"
}
