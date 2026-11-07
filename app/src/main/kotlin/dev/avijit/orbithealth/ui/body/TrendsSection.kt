package dev.avijit.orbithealth.ui.body

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.avijit.orbithealth.ui.charts.ChartAxis
import dev.avijit.orbithealth.ui.charts.TrendLine
import dev.avijit.orbithealth.ui.components.SegmentedPill
import dev.avijit.orbithealth.ui.components.TintedCard
import dev.avijit.orbithealth.ui.components.livingEnter
import dev.avijit.orbithealth.ui.theme.OrbitPalette
import dev.avijit.orbithealth.ui.theme.OrbitTypography
import dev.avijit.orbithealth.ui.theme.lineBox

private val TrendRanges = listOf(Range.WEEK to "7d", Range.MONTH to "30d", Range.YEAR to "1y")

/** Trend card chart height (mockup 62) and the RHR line's lighter rose. */
private val ChartHeight = 62.dp
private val RhrLine = Color(0xFFFF8FB0)
/** Sleep bars run from 6h (a 6dp stub) to 8h (full height). */
private val SleepBarRange = 360f to 480f

@Composable
internal fun TrendsSection(vm: BodyViewModel) {
    val st by vm.trends.collectAsStateWithLifecycle()
    TrendsContent(st, onTab = vm::selectTab, onRange = vm::selectTrendsRange)
}

/**
 * Trends (mockup screen-10): one Fraunces sentence summing up the range, then soft rounded charts: resting heart rate
 * and HRV as curves with their fitted change, sleep duration as nightly bars (lavender from 7h), and steps. No sky.
 */
@Composable
internal fun TrendsContent(st: TrendsUiState, onTab: (BodyTab) -> Unit, onRange: (Range) -> Unit) {
    BodyPage(
        BodyTab.TRENDS, onTab,
        trailing = {
            SegmentedPill(
                TrendRanges.map { it.second }, TrendRanges.indexOfFirst { it.first == st.range }.coerceAtLeast(0),
                { onRange(TrendRanges[it].first) },
            )
        },
    ) {
        val sentence = st.sentence.ifEmpty { if (st.loaded) BodyMath.NO_TRENDS else "" }
        Text(sentence, style = OrbitTypography.Insight, modifier = Modifier.padding(start = Gutter, end = Gutter, top = 6.dp).livingEnter(120))
        val axis = BodyMath.trendAxisPositions(st.axis.size).zip(st.axis)
        TrendCard(
            OrbitPalette.HeartA, "Resting heart rate", st.restingHr, "bpm avg", axis, 180,
        ) { TrendLine(st.restingHr.values, OrbitPalette.HeartA, height = ChartHeight, minSpan = 8f, stroke = RhrLine) }
        TrendCard(
            OrbitPalette.SleepA, "Sleep duration", st.sleep, "avg", axis, 240, note = st.sleepNote,
        ) {
            TrendLine(
                st.sleep.bars, OrbitPalette.SleepB, bars = true, height = ChartHeight, range = SleepBarRange,
                barSlots = BodyMath.trendBarSlots(st.range),
                barColor = { if (it >= BodyMath.GOOD_NIGHT_MIN) OrbitPalette.SleepB else ShortNight },
            )
        }
        TrendCard(
            OrbitPalette.ReadinessA, "Heart rate variability", st.hrv, "ms avg", axis, 300,
        ) { TrendLine(st.hrv.values, OrbitPalette.ReadinessA, height = ChartHeight, minSpan = 14f, stroke = OrbitPalette.Optimal) }
        TrendCard(
            OrbitPalette.ActivityA, "Steps", st.steps, "avg", axis, 360,
        ) { TrendLine(st.steps.values, OrbitPalette.ActivityA, height = ChartHeight, minSpan = 4000f, stroke = OrbitPalette.ActivityB) }
    }
}

/** Nights under 7h (tokens §5 Bars). */
private val ShortNight = Color(0xFF5B4FD8)

/**
 * A trend card (tokens: radius 24, padding 16/20/14): title and, at the right, the change ("↓ 3 bpm" in optimal when
 * it moved the healthy way, attention when not) or a [note]; the average; the chart; then the dates.
 */
@Composable
private fun TrendCard(
    hue: Color,
    title: String,
    s: TrendSeries,
    unit: String,
    axis: List<Pair<Float, String>>,
    enterMs: Int,
    note: String? = null,
    chart: @Composable () -> Unit,
) {
    TintedCard(
        hue, Modifier.padding(start = Gutter, end = Gutter, top = 12.dp).fillMaxWidth().livingEnter(enterMs),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 14.dp),
    ) {
        Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = OrbitTypography.BodyStrong, maxLines = 1, modifier = Modifier.weight(1f).lineBox(OrbitTypography.BodyStrong))
            val right = note ?: s.delta
            val color = when {
                note != null -> OrbitPalette.Ink2
                s.improving == true -> OrbitPalette.Optimal
                s.improving == false -> OrbitPalette.Attention
                else -> OrbitPalette.Ink2
            }
            if (right.isNotEmpty()) {
                Text(right, style = CardDelta, color = color, maxLines = 1, modifier = Modifier.padding(start = 8.dp).lineBox(CardDelta))
            }
        }
        Text(
            buildAnnotatedString {
                append(s.avg.ifEmpty { "—" })
                withStyle(OrbitTypography.Unit.toSpanStyle()) { append(" $unit") }
            },
            style = OrbitTypography.Value, maxLines = 1, modifier = Modifier.padding(top = 4.dp).lineBox(OrbitTypography.Value),
        )
        androidx.compose.foundation.layout.Box(Modifier.padding(top = 12.dp)) { chart() }
        if (axis.isNotEmpty()) ChartAxis(axis, Modifier.fillMaxWidth().padding(top = 6.dp))
    }
}

/** The card's right-hand change or note: caption at 12sp. */
private val CardDelta = OrbitTypography.Caption.copy(fontSize = 12.sp, lineHeight = 16.sp)
