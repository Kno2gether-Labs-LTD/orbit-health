package dev.avijit.orbithealth.ui.body

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.avijit.orbithealth.ui.components.LocalHazeState
import dev.avijit.orbithealth.ui.components.OrbitBottomBarDefaults
import dev.avijit.orbithealth.ui.components.SegmentedPill
import dev.avijit.orbithealth.ui.components.TextTabs
import dev.avijit.orbithealth.ui.components.livingEnter
import dev.avijit.orbithealth.ui.theme.Manrope
import dev.avijit.orbithealth.ui.theme.OrbitPalette
import dev.avijit.orbithealth.ui.theme.OrbitTypography
import dev.avijit.orbithealth.ui.theme.lineBox
import dev.chrisbanes.haze.hazeSource

/** Tokens §2: every card edge and header line sits on x = 24. */
internal val Gutter = 24.dp

/**
 * Body, Living Skies (mockup screens 07–10): one screen with text tabs Heart · Stress & SpO₂ · Activity · Trends.
 * Each section draws the header ("Body" over its Fraunces title, its range control or goal on the right), the tabs,
 * then its sky and cards; only the visible section subscribes to its data, and each shows at most one moving sky.
 */
@Composable
fun BodyScreen(vm: BodyViewModel, onMeasure: () -> Unit = {}) {
    val tab by vm.tab.collectAsStateWithLifecycle()
    Crossfade(tab, animationSpec = tween(220), label = "body-tab") { t ->
        when (t) {
            BodyTab.HEART -> HeartSection(vm, onMeasure)
            BodyTab.STRESS -> StressSpo2Section(vm)
            BodyTab.ACTIVITY -> ActivitySection(vm)
            BodyTab.TRENDS -> TrendsSection(vm)
        }
    }
}

/** "Stress & SpO₂" as the mockup sets it: an italic ampersand and a small Manrope 2. */
internal fun bodyTitle(tab: BodyTab): AnnotatedString = when (tab) {
    BodyTab.STRESS -> buildAnnotatedString {
        append("Stress ")
        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append("&") }
        append(" SpO")
        withStyle(SpanStyle(fontFamily = Manrope, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, baselineShift = BaselineShift(-0.13f), letterSpacing = 0.sp)) {
            append("2")
        }
    }
    else -> AnnotatedString(tab.label)
}

@Composable
private fun bottomClearance(): Dp {
    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    return OrbitBottomBarDefaults.Clearance + max(0.dp, navInset - OrbitBottomBarDefaults.Bottom) + 24.dp
}

/**
 * A Body section's page: base gradient (the bottom bar's blur source), a scrolling column with the header (eyebrow
 * "Body", the title and [trailing] on one row), the text tabs, then [content]; and the top scrim that fades in over
 * the first 40dp of scroll.
 */
@Composable
internal fun BodyPage(
    tab: BodyTab,
    onTab: (BodyTab) -> Unit,
    trailing: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val scroll = rememberScrollState()
    val haze = LocalHazeState.current
    Box(
        Modifier
            .fillMaxSize()
            .then(if (haze != null) Modifier.hazeSource(haze) else Modifier)
            .background(Brush.verticalGradient(listOf(OrbitPalette.Base0, OrbitPalette.Base1))),
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll).statusBarsPadding().padding(bottom = bottomClearance())) {
            Column(Modifier.padding(start = Gutter, end = Gutter, top = 14.dp).livingEnter(0)) {
                Text("Body", style = OrbitTypography.Eyebrow, modifier = Modifier.lineBox(OrbitTypography.Eyebrow))
                Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        bodyTitle(tab), style = OrbitTypography.Title, maxLines = 1,
                        modifier = Modifier.weight(1f).lineBox(OrbitTypography.Title).semantics { heading() },
                    )
                    trailing()
                }
            }
            TextTabs(
                BodyTab.entries.map { it.label }, tab.ordinal, { onTab(BodyTab.entries[it]) },
                Modifier.padding(top = 14.dp).livingEnter(60),
            )
            content()
        }
        val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        Spacer(
            Modifier.fillMaxWidth().height(statusTop + 40.dp)
                .graphicsLayer { alpha = (scroll.value / 40.dp.toPx()).coerceIn(0f, 1f) }
                .drawBehind {
                    drawRect(Brush.verticalGradient(0f to OrbitPalette.Base0, 0.46f to OrbitPalette.Base0, 1f to OrbitPalette.Base0.copy(alpha = 0f)))
                },
        )
    }
}

/** Day / Week for a section header (the Living segmented control). */
@Composable
internal fun DayWeekPills(range: Range, onSelect: (Range) -> Unit) {
    val options = listOf(Range.DAY, Range.WEEK)
    SegmentedPill(listOf("Day", "Week"), options.indexOf(range).coerceAtLeast(0), { onSelect(options[it]) })
}
