package dev.avijit.orbithealth.ui.body

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.avijit.orbithealth.ui.charts.Spo2Dots
import dev.avijit.orbithealth.ui.charts.StressCurve
import dev.avijit.orbithealth.ui.charts.StressZoneBar
import dev.avijit.orbithealth.ui.components.EstimateBadge
import dev.avijit.orbithealth.ui.components.livingEnter
import dev.avijit.orbithealth.ui.sky.Sky
import dev.avijit.orbithealth.ui.sky.SkyKind
import dev.avijit.orbithealth.ui.theme.OrbitPalette
import dev.avijit.orbithealth.ui.theme.OrbitTypography
import dev.avijit.orbithealth.ui.theme.lineBox
import dev.avijit.orbithealth.ui.today.StressRibbon
import dev.avijit.orbithealth.ui.today.TodayCopy

/** The Calm card's word when there is nothing to show yet. */
const val NoStressCopy = "No stress readings yet"

/** The Tide card's line when no night has SpO₂ readings. */
const val NoSpo2Copy = "Wear your ring overnight to see your blood oxygen."

/** Mockup screen-08 sizes. */
private val CalmHeight = 330.dp
/** The Tide card hugs its content; this keeps a short one from looking stunted. */
private val TideMinHeight = 200.dp

@Composable
internal fun StressSpo2Section(vm: BodyViewModel) {
    val st by vm.stress.collectAsStateWithLifecycle()
    StressContent(st, onTab = vm::selectTab, onRange = vm::selectStressRange)
}

/**
 * Stress & SpO₂ (mockup screen-08): the Calm sky (it warms with stress, and is the tab's only moving sky) with the
 * latest estimate, the 12-hour ribbon to now (a week of daily means on Week), and gap-weighted time in zones; then the
 * Tide sky, held still, with overnight SpO₂ dots against the healthy band and the dips copy.
 */
@Composable
internal fun StressContent(st: StressUiState, onTab: (BodyTab) -> Unit, onRange: (Range) -> Unit) {
    val week = st.range == Range.WEEK
    BodyPage(BodyTab.STRESS, onTab, trailing = { DayWeekPills(st.range, onRange) }) {
        CalmCard(st, week, Modifier.padding(horizontal = Gutter).livingEnter(120))
        TideCard(st, week, Modifier.padding(start = Gutter, end = Gutter, top = 12.dp).livingEnter(180))
    }
}

@Composable
private fun CalmCard(st: StressUiState, week: Boolean, modifier: Modifier) {
    val value = st.now
    Box(modifier.fillMaxWidth().height(CalmHeight)) {
        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(28.dp))) {
            Sky(SkyKind.CALM, Modifier.fillMaxSize(), param = (value ?: 0) / 100f)
        }
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 18.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("STRESS", style = OrbitTypography.Overline, maxLines = 1, modifier = Modifier.lineBox(OrbitTypography.Overline))
                Spacer(Modifier.width(8.dp))
                EstimateBadge()
                Spacer(Modifier.weight(1f))
                if (st.peakLabel.isNotEmpty()) {
                    Text(
                        st.peakLabel.replaceFirstChar { it.uppercase() }, style = OrbitTypography.Caption, color = OrbitPalette.InkSky2,
                        maxLines = 1, modifier = Modifier.lineBox(OrbitTypography.Caption),
                    )
                }
            }
            Row(
                Modifier.padding(top = 12.dp).semantics(mergeDescendants = true) {
                    contentDescription = value?.let { "Stress ${if (week) "average " else ""}$it, ${TodayCopy.stressWord(it)}, estimate" } ?: NoStressCopy
                },
            ) {
                Text(
                    value?.toString() ?: "—", style = OrbitTypography.NumLg, maxLines = 1,
                    color = if (value == null) OrbitPalette.Ink.copy(alpha = 0.55f) else OrbitPalette.Ink,
                    modifier = Modifier.alignByBaseline().lineBox(OrbitTypography.NumLg),
                )
                Text(
                    value?.let { (if (week) "avg · " else "") + TodayCopy.stressWord(it) } ?: NoStressCopy,
                    style = OrbitTypography.Word, maxLines = 1,
                    color = value?.let { stressToneColor(it) } ?: OrbitPalette.InkSky2,
                    modifier = Modifier.padding(start = 10.dp).alignByBaseline(),
                )
            }
            if (week) {
                StressCurve(st.stress, Modifier.padding(top = 14.dp), axis = st.axis)
            } else {
                StressRibbon(
                    st.stress, st.nowMinute, Modifier.padding(top = 14.dp),
                    chartHeight = 86.dp, axisColor = OrbitPalette.InkSky2, axisTop = 6.dp,
                )
            }
            StressZoneBar(st.zoneSamples, Modifier.padding(top = 16.dp))
        }
    }
}

/** Calm and normal read optimal, medium good, high attention (the status words, never red or green). */
private fun stressToneColor(v: Int) = when (TodayCopy.stressTone(v)) {
    0 -> OrbitPalette.Optimal
    1 -> OrbitPalette.Good
    else -> OrbitPalette.Attention
}

@Composable
private fun TideCard(st: StressUiState, week: Boolean, modifier: Modifier) {
    // Sized to its content (the mockup's two-line copy lands at its 250dp), so a one-line caption doesn't
    // leave a band of empty sky under it.
    Box(modifier.fillMaxWidth().heightIn(min = TideMinHeight)) {
        Box(Modifier.matchParentSize().clip(RoundedCornerShape(24.dp))) {
            // Held still: the Calm sky above is the tab's one moving sky.
            Sky(SkyKind.TIDE, Modifier.fillMaxSize(), animate = false)
        }
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 20.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    (if (week) "Blood oxygen · 7 nights" else "Blood oxygen · overnight").uppercase(), style = OrbitTypography.Overline,
                    maxLines = 1, modifier = Modifier.weight(1f).lineBox(OrbitTypography.Overline),
                )
                st.spo2Low?.let {
                    Text("Low $it%", style = OrbitTypography.Caption, color = OrbitPalette.InkSky2, maxLines = 1, modifier = Modifier.lineBox(OrbitTypography.Caption))
                }
            }
            Text(
                buildAnnotatedString {
                    append(st.spo2Avg?.toString() ?: "—")
                    withStyle(OrbitTypography.Unit.copy(fontSize = 15.sp, color = OrbitPalette.InkSky2).toSpanStyle()) { append(" % avg") }
                },
                style = OrbitTypography.NumMd, maxLines = 1,
                color = if (st.spo2Avg == null) OrbitPalette.Ink.copy(alpha = 0.55f) else OrbitPalette.Ink,
                modifier = Modifier.padding(top = 10.dp).lineBox(OrbitTypography.NumMd),
            )
            Spo2Dots(st.spo2, Modifier.padding(top = 12.dp))
            Text(
                st.dipCopy.ifEmpty { NoSpo2Copy },
                style = OrbitTypography.Caption.copy(fontSize = 12.sp, lineHeight = 17.sp), color = OrbitPalette.InkSky2,
                maxLines = 2, modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}
