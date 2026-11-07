package dev.avijit.orbithealth.ui.body

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.avijit.orbithealth.ring.WorkoutRecord
import dev.avijit.orbithealth.ui.charts.HourBars
import dev.avijit.orbithealth.ui.components.rememberFittedStyle
import dev.avijit.orbithealth.ui.components.MiniMetricCard
import dev.avijit.orbithealth.ui.components.OrbitArc
import dev.avijit.orbithealth.ui.components.OrbitArcDefaults
import dev.avijit.orbithealth.ui.components.OrbitSymbol
import dev.avijit.orbithealth.ui.components.Symbol
import dev.avijit.orbithealth.ui.components.TintedCard
import dev.avijit.orbithealth.ui.components.TintedCardDefaults
import dev.avijit.orbithealth.ui.components.livingEnter
import dev.avijit.orbithealth.ui.components.rememberArcReveal
import dev.avijit.orbithealth.ui.sky.Sky
import dev.avijit.orbithealth.ui.sky.SkyKind
import dev.avijit.orbithealth.ui.theme.OrbitPalette
import dev.avijit.orbithealth.ui.theme.OrbitTypography
import dev.avijit.orbithealth.ui.theme.lineBox
import dev.avijit.orbithealth.ui.today.TodayCopy
import java.time.Duration
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

/** Workouts card when there are none (Plan 1 has no "Start workout"). */
const val NoWorkoutsCopy = "Workouts you start will appear here"

/** Mockup screen-09: the Ember sky is 300 tall; arc top 46, numeral top 96, sentence top 238. */
private val EmberHeight = 300.dp
private val StepsBrush = Brush.verticalGradient(listOf(OrbitPalette.ActivityB, OrbitPalette.ActivityA))

@Composable
internal fun ActivitySection(vm: BodyViewModel) {
    val st by vm.activity.collectAsStateWithLifecycle()
    ActivityContent(st, onTab = vm::selectTab)
}

/**
 * Activity (mockup screen-09): the Ember sky with the orbit arc as progress towards today's smart step goal, the
 * steps riding the arc's reveal and a sentence on what's left; steps by hour; distance, calories and active hours;
 * and this week's workouts (a calm empty state until there are some).
 */
@Composable
internal fun ActivityContent(st: ActivityUiState, onTab: (BodyTab) -> Unit) {
    BodyPage(
        BodyTab.ACTIVITY, onTab,
        trailing = {
            if (st.goal > 0) Text("Goal ${BodyMath.formatThousands(st.goal)}", style = OrbitTypography.Meta, maxLines = 1, modifier = Modifier.lineBox(OrbitTypography.Meta))
        },
    ) {
        EmberHero(st, Modifier.padding(horizontal = Gutter).livingEnter(120))
        TintedCard(
            OrbitPalette.ActivityA, Modifier.padding(start = Gutter, end = Gutter, top = 12.dp).fillMaxWidth().livingEnter(180),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 14.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("By hour", style = OrbitTypography.BodyStrong, maxLines = 1, modifier = Modifier.weight(1f).lineBox(OrbitTypography.BodyStrong))
                Text(BodyMath.busiestLabel(st.perHour), style = OrbitTypography.Caption, maxLines = 1, modifier = Modifier.lineBox(OrbitTypography.Caption))
            }
            HourBars(
                st.perHour, OrbitPalette.ActivityA, Modifier.padding(top = 14.dp), nowHour = st.currentHour, height = 84.dp,
                emptyMessage = "No steps yet today", brush = StepsBrush,
            )
        }
        Row(
            Modifier.padding(start = Gutter, end = Gutter, top = 10.dp).fillMaxWidth().height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val mini = Modifier.weight(1f).fillMaxHeight()
            MiniMetricCard(
                OrbitPalette.ActivityA, OrbitSymbol.Route, "Distance", "%.2f".format(Locale.getDefault(), st.distanceKm), "km", "Today",
                mini.livingEnter(240), glyphTint = OrbitPalette.ActivityB,
            )
            MiniMetricCard(
                OrbitPalette.ActivityA, OrbitSymbol.Flame, "Calories", BodyMath.formatThousands(st.kcal), "kcal", "Active",
                mini.livingEnter(280), glyphTint = OrbitPalette.ActivityB,
            )
            MiniMetricCard(
                OrbitPalette.ActivityA, OrbitSymbol.Run, "Active", BodyMath.activeHours(st.perHour).toString(), "h",
                "≥ ${BodyMath.ACTIVE_HOUR_STEPS} steps/h", mini.livingEnter(320), glyphTint = OrbitPalette.ActivityB,
            )
        }
        Workouts(st.workouts, Modifier.padding(start = Gutter, end = Gutter, top = 24.dp).livingEnter(360))
    }
}

@Composable
private fun EmberHero(st: ActivityUiState, modifier: Modifier) {
    val fraction = if (st.goal > 0) (st.steps.toFloat() / st.goal).coerceIn(0f, 1f) else null
    val shown by rememberArcReveal(fraction)
    // The numeral rides the arc's reveal, landing on the exact count (past the goal too).
    val steps = if (fraction != null && fraction > 0f) minOf(st.steps, (shown / fraction * st.steps).roundToInt()) else st.steps
    val wordAlpha by animateFloatAsState(if (fraction == null || shown >= 0.6f * fraction) 1f else 0f, tween(240), label = "word")
    Box(
        modifier.fillMaxWidth().height(EmberHeight).semantics(mergeDescendants = true) {
            contentDescription = "${BodyMath.formatThousands(st.steps)} steps today" +
                if (st.goal > 0) " of ${BodyMath.formatThousands(st.goal)}" else ""
        },
    ) {
        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(28.dp))) {
            Sky(SkyKind.EMBER, Modifier.fillMaxSize())
        }
        Text(
            "STEPS TODAY", style = OrbitTypography.Overline, maxLines = 1,
            modifier = Modifier.padding(start = 20.dp, top = 18.dp).lineBox(OrbitTypography.Overline),
        )
        OrbitArc(
            fraction, Modifier.align(Alignment.TopCenter).offset(y = 46.dp), size = OrbitArcDefaults.DetailSize,
            animateIn = false, style = OrbitArcDefaults.Detail,
        )
        Column(Modifier.align(Alignment.TopCenter).padding(top = 96.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            // Fitted to the final count so four- and five-digit days clear the 176dp arc's stroke.
            val numeral = rememberFittedStyle(BodyMath.formatThousands(st.steps), OrbitTypography.NumLg, ActivityNumeralMaxWidth, minSp = 40f)
            Text(BodyMath.formatThousands(steps), style = numeral, maxLines = 1, modifier = Modifier.lineBox(OrbitTypography.NumLg))
            if (st.goal > 0) {
                Text(
                    "of ${BodyMath.formatThousands(st.goal)}", style = OrbitTypography.Word, color = OrbitPalette.ActivityB,
                    modifier = Modifier.padding(top = 6.dp).alpha(wordAlpha).lineBox(OrbitTypography.Word),
                )
            }
        }
        Text(
            TodayCopy.activityInsight(st.steps, st.goal), style = OrbitTypography.InsightSm, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(start = 28.dp, end = 28.dp, top = 238.dp),
        )
    }
}

/** Widest the Activity numeral may run inside the detail arc (about 168dp across at the numeral, less a margin). */
internal val ActivityNumeralMaxWidth = 148.dp

/** "This week": the week's workouts as rows, or a calm note until there are any. */
@Composable
private fun Workouts(workouts: List<WorkoutRecord>, modifier: Modifier) {
    Column(modifier.fillMaxWidth()) {
        Text("This week", style = OrbitTypography.H2, modifier = Modifier.lineBox(OrbitTypography.H2).semantics { heading() })
        TintedCard(
            OrbitPalette.Ink, Modifier.padding(top = 12.dp).fillMaxWidth(), amount = TintedCardDefaults.NeutralAmount,
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = if (workouts.isEmpty()) 16.dp else 6.dp),
        ) {
            if (workouts.isEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(36.dp).background(OrbitPalette.ActivityA.copy(alpha = 0.16f), CircleShape), contentAlignment = Alignment.Center) {
                        Symbol(OrbitSymbol.Run, size = 18.dp, strokeWidth = 1.7f, tint = OrbitPalette.ActivityB)
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(NoWorkoutsCopy, style = OrbitTypography.Body, color = OrbitPalette.Ink2)
                }
            } else {
                workouts.take(5).forEachIndexed { i, w -> WorkoutRow(w, first = i == 0) }
            }
        }
    }
}

/** A list row (tokens §5): disc + "Workout · Monday", "5.2 km · 31 min" in ink2; hairline between rows. */
@Composable
private fun WorkoutRow(w: WorkoutRecord, first: Boolean) {
    val zone = ZoneId.systemDefault()
    val day = w.start.atZone(zone).dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
    val min = Duration.between(w.start, w.end).toMinutes()
    val right = listOfNotNull(
        if (w.distanceM > 0) "%.1f km".format(Locale.getDefault(), w.distanceM / 1000.0) else null, "$min min",
    ).joinToString(" · ")
    Column(Modifier.semantics(mergeDescendants = true) {}) {
        if (!first) Spacer(Modifier.fillMaxWidth().height(1.dp).background(OrbitPalette.Hairline))
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).background(OrbitPalette.ActivityA.copy(alpha = 0.16f), CircleShape), contentAlignment = Alignment.Center) {
                Symbol(OrbitSymbol.Run, size = 18.dp, strokeWidth = 1.7f, tint = OrbitPalette.ActivityB)
            }
            Spacer(Modifier.width(12.dp))
            Text("Workout · $day", style = OrbitTypography.Body, maxLines = 1, modifier = Modifier.weight(1f))
            Text(right, style = OrbitTypography.Body.copy(fontFeatureSettings = "tnum"), color = OrbitPalette.Ink2, maxLines = 1)
        }
    }
}
