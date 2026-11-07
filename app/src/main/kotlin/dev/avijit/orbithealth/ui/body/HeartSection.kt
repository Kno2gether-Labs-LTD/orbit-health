package dev.avijit.orbithealth.ui.body

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.avijit.orbithealth.ui.charts.ChartAxis
import dev.avijit.orbithealth.ui.charts.TrendLine
import dev.avijit.orbithealth.ui.components.EstimateBadge
import dev.avijit.orbithealth.ui.components.GhostButton
import dev.avijit.orbithealth.ui.components.MiniMetricCard
import dev.avijit.orbithealth.ui.components.TintedCard
import dev.avijit.orbithealth.ui.components.TintedCardDefaults
import dev.avijit.orbithealth.ui.components.RollingNumber
import dev.avijit.orbithealth.ui.components.livingEnter
import dev.avijit.orbithealth.ui.sky.Sky
import dev.avijit.orbithealth.ui.sky.SkyKind
import dev.avijit.orbithealth.ui.theme.OrbitPalette
import dev.avijit.orbithealth.ui.theme.OrbitTypography
import dev.avijit.orbithealth.ui.theme.lineBox
import dev.avijit.orbithealth.ui.today.HrFreshness

/** Mockup screen-07: the Pulse sky card is 352 tall with its button 18 from the bottom. */
private val PulseHeight = 352.dp
private val HeroShape = RoundedCornerShape(28.dp)
/** The chart's light rose line, rgba(255,214,226,.9). */
private val HeartLine = Color(0xFFFFD6E2).copy(alpha = 0.9f)

/** [onMeasure] opens the Measure sheet on Heart rate and starts it (one tap still measures heart rate). */
@Composable
internal fun HeartSection(vm: BodyViewModel, onMeasure: () -> Unit) {
    val st by vm.heart.collectAsStateWithLifecycle()
    HeartContent(st, onTab = vm::selectTab, onMeasure = onMeasure, onRange = vm::selectHeartRange)
}

/**
 * Heart (mockup screen-07): the Pulse sky beating at the latest heart rate with the live or latest reading, where it
 * sits against your usual range, today's curve to "Now" and Measure now; Resting and HRV minis against last month and
 * your normal; and the estimated blood pressure (its last date when there is none today).
 */
@Composable
internal fun HeartContent(st: HeartUiState, onTab: (BodyTab) -> Unit, onMeasure: () -> Unit, onRange: (Range) -> Unit) {
    BodyPage(BodyTab.HEART, onTab, trailing = { DayWeekPills(st.range, onRange) }) {
        PulseHero(st, onMeasure, Modifier.padding(horizontal = Gutter).livingEnter(120))
        val week = st.range == Range.WEEK
        Row(
            Modifier.padding(start = Gutter, end = Gutter, top = 12.dp).fillMaxWidth().height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val d = st.restingVsMonth
            MiniMetricCard(
                OrbitPalette.HeartA, null, if (week) "Resting · avg" else "Resting", st.resting?.toString() ?: "—", "bpm",
                BodyMath.restingFoot(d, week), Modifier.weight(1f).fillMaxHeight().livingEnter(180),
                footColor = deltaColor(d?.let { -it }), contentPadding = PaddingValues(16.dp), valueTop = 8.dp,
            )
            val h = st.hrvVsNormal
            MiniMetricCard(
                OrbitPalette.ReadinessA, null, if (week) "HRV · avg" else "HRV", st.hrv?.toString() ?: "—", "ms",
                BodyMath.hrvFoot(h, week), Modifier.weight(1f).fillMaxHeight().livingEnter(220),
                footColor = deltaColor(h?.takeIf { kotlin.math.abs(it) >= BodyMath.HRV_NORMAL_MS }),
                contentPadding = PaddingValues(16.dp), valueTop = 8.dp,
            )
        }
        BloodPressureCard(st, Modifier.padding(start = Gutter, end = Gutter, top = 10.dp).livingEnter(260))
    }
}

/** Optimal for a change the healthy way (positive), Attention the other way, ink2 for none. */
private fun deltaColor(good: Int?): Color = when {
    good == null || good == 0 -> OrbitPalette.Ink2
    good > 0 -> OrbitPalette.Optimal
    else -> OrbitPalette.Attention
}

@Composable
private fun PulseHero(st: HeartUiState, onMeasure: () -> Unit, modifier: Modifier) {
    // A stored reading over 7 days old is not shown as the heart rate (a dash), and the sky stops beating. A Measure
    // sheet reading is stored, so it shows here as "Measured · HH:mm" until a newer reading arrives.
    val bpm = st.latestBpm
    Box(modifier.fillMaxWidth().height(PulseHeight)) {
        Box(Modifier.fillMaxSize().clip(HeroShape)) {
            // The rose blob beats at the heart rate shown; with nothing to show it holds still. The tab's only moving sky.
            Sky(SkyKind.PULSE, Modifier.fillMaxSize(), param = (bpm ?: 0).toFloat(), animate = bpm != null)
        }
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 18.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Heart rate".uppercase(), style = OrbitTypography.Overline, maxLines = 1,
                    modifier = Modifier.weight(1f).lineBox(OrbitTypography.Overline),
                )
                if (st.latestCaption.isNotEmpty()) {
                    Text(st.latestCaption, style = OrbitTypography.Caption, color = OrbitPalette.InkSky2, maxLines = 1, modifier = Modifier.lineBox(OrbitTypography.Caption))
                }
            }
            Row(
                Modifier.padding(top = 8.dp).semantics(mergeDescendants = true) {
                    contentDescription = if (bpm != null) "$bpm beats per minute" else "No heart rate yet"
                },
                verticalAlignment = Alignment.Bottom,
            ) {
                PulseNumeral(bpm, Modifier.alignByBaseline())
                Text(
                    "bpm", style = OrbitTypography.Unit.copy(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight(500)),
                    color = OrbitPalette.InkSky2, modifier = Modifier.padding(start = 6.dp).alignByBaseline(),
                )
            }
            val line = st.rangeLine ?: when {
                st.latestHr == null || st.latestFreshness == HrFreshness.HIDDEN -> "Wear your ring to see your heart rate"
                st.range == Range.WEEK -> "Daily averages this week"
                else -> "Your heart rate through today"
            }
            Text(line, style = OrbitTypography.Meta, color = OrbitPalette.InkSky2, maxLines = 2, modifier = Modifier.padding(top = 6.dp))
            // Mapped once per chart, not on every recomposition of the hero.
            val values = remember(st.chart) { st.chart.points.map { it.second } }
            val xs = remember(st.chart) { st.chart.points.map { it.first } }
            val range = remember(values) {
                minOf(50f, (values.minOrNull() ?: 50f) - 3f) to maxOf(95f, (values.maxOrNull() ?: 95f) + 3f)
            }
            TrendLine(
                values, OrbitPalette.HeartA, modifier = Modifier.padding(top = 14.dp),
                height = 70.dp, stroke = HeartLine, fillAlpha = 0.30f,
                xs = xs, range = range, emptyMessage = "",
            )
            ChartAxis(
                st.chart.axis, Modifier.fillMaxWidth().padding(top = 6.dp), color = OrbitPalette.InkSky2,
            )
        }
        GhostButton(
            "Measure now", onClick = onMeasure,
            modifier = Modifier.align(Alignment.BottomCenter).padding(start = 20.dp, end = 20.dp, bottom = 18.dp).fillMaxWidth(),
        )
    }
}

/**
 * The big heart-rate numeral in its own small composable, so the digit roll (tokens §6 number roll: each changed
 * digit slides 18dp, 30 ms apart from the right) animates here without recomposing the whole Pulse hero per frame.
 */
@Composable
private fun PulseNumeral(bpm: Int?, modifier: Modifier = Modifier) {
    RollingNumber(
        bpm?.toString() ?: "—", OrbitTypography.NumXl, modifier,
        color = if (bpm == null) OrbitPalette.Ink.copy(alpha = 0.55f) else OrbitPalette.Ink,
    )
}

/** Neutral card: "Blood pressure" with the ESTIMATE badge, the reading, and where it comes from (or its last date). */
@Composable
private fun BloodPressureCard(st: HeartUiState, modifier: Modifier) {
    TintedCard(
        OrbitPalette.Ink, modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, amount = TintedCardDefaults.NeutralAmount,
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (st.range == Range.WEEK) "Blood pressure · avg" else "Blood pressure", style = OrbitTypography.Label, maxLines = 1,
                modifier = Modifier.weight(1f).lineBox(OrbitTypography.Label),
            )
            EstimateBadge()
        }
        val bp = if (st.bpSys != null && st.bpDia != null) "${st.bpSys}/${st.bpDia}" else "—"
        Text(
            androidx.compose.ui.text.buildAnnotatedString {
                append(bp)
                withStyle(OrbitTypography.Unit.toSpanStyle()) { append(" mmHg") }
            },
            style = OrbitTypography.Value, maxLines = 1, modifier = Modifier.padding(top = 8.dp).lineBox(OrbitTypography.Value),
        )
        val source = "From the ring's light sensor. A guide, not a diagnosis."
        Text(
            st.bpLast?.let { "Last: $it. $source" } ?: source, style = OrbitTypography.Caption,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}
