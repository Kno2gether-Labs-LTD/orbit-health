package dev.avijit.orbithealth.ui.sleep

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.avijit.orbithealth.insights.SleepEstimate
import dev.avijit.orbithealth.insights.SleepKind
import dev.avijit.orbithealth.ui.components.EstimateBadge
import dev.avijit.orbithealth.ui.components.GhostButton
import dev.avijit.orbithealth.ui.components.GhostButtonPair
import dev.avijit.orbithealth.ui.components.ListCard
import dev.avijit.orbithealth.ui.components.OrbitSheet
import dev.avijit.orbithealth.ui.components.OrbitSymbol
import dev.avijit.orbithealth.ui.components.StepperRow
import dev.avijit.orbithealth.ui.components.Symbol
import dev.avijit.orbithealth.ui.components.TintedCard
import dev.avijit.orbithealth.ui.components.WarmButton
import dev.avijit.orbithealth.ui.theme.OrbitPalette
import dev.avijit.orbithealth.ui.theme.OrbitTypography
import dev.avijit.orbithealth.ui.theme.lineBox
import dev.avijit.orbithealth.ui.today.TodayCopy
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** A suggestion ready to show: the stored estimate's [id], its times and the words for it. */
data class EstimateSuggestion(
    val id: Long,
    val kind: SleepKind,
    val start: Instant,
    val end: Instant,
    /** Night: "Looks like you slept 3:10–10:40. Right?" Nap: "Nap · 14:10–14:50 · 40 min". */
    val headline: String,
    /** Night: "7h 30m · lowest 54 bpm · SpO₂ low 93%". Nap: "From your heart rate and stillness". */
    val detail: String,
)

/** What the user answered. */
sealed interface EstimateAnswer {
    val id: Long

    /** Yes (times null) or Adjust (the user's times). */
    data class Confirm(override val id: Long, val start: Instant? = null, val end: Instant? = null) : EstimateAnswer

    data class Dismiss(override val id: Long) : EstimateAnswer
}

/** Estimated sleep's words. Pure, so every sentence is pinned by a test. */
object EstimateCopy {
    /** Over the night card: why the app is asking. */
    const val NightKicker = "Your ring missed this one"

    /** Wherever an estimated night stands in for the ring's: no stages, said plainly. */
    const val NoStages = "Estimated · no stages"

    const val SheetTitle = "When did you sleep?"
    const val SheetKicker = "Estimated sleep"
    const val SheetNote = "Move the times to when you fell asleep and woke up. Orbit learns your rhythm from this."
    const val EstimatedNightTitle = "No stages for this night"
    const val EstimatedNightBody =
        "Your ring didn't record it, so Orbit worked out the times from your heart rate and movement. Stages need the ring's own record."

    /** Minutes per step of the adjust sheet's steppers. */
    const val ADJUST_STEP_MIN = 10L

    /** Adjusting keeps a night at least this long. */
    val MinNight: Duration = Duration.ofMinutes(30)

    /** Adjusting keeps each time within this of what was suggested. */
    val MaxShift: Duration = Duration.ofHours(4)

    private val Hmm = DateTimeFormatter.ofPattern("H:mm")
    private val HHmm = DateTimeFormatter.ofPattern("HH:mm")

    /** "3:10–10:40": conversational times, no leading zero. */
    fun span(start: Instant, end: Instant, zone: ZoneId): String = "${start.atZone(zone).format(Hmm)}–${end.atZone(zone).format(Hmm)}"

    /** "23:40" for the adjust sheet's steppers (the app's usual clock format). */
    fun clock(t: Instant, zone: ZoneId): String = t.atZone(zone).format(HHmm)

    fun nightHeadline(start: Instant, end: Instant, zone: ZoneId): String = "Looks like you slept ${span(start, end, zone)}. Right?"

    /** "7h 30m · lowest 54 bpm · SpO₂ low 93%", leaving out what was not measured. */
    fun nightDetail(durationSec: Int, lowestHr: Int?, lowestSpo2: Int?): String =
        listOfNotNull(TodayCopy.hm(durationSec), lowestHr?.let { "lowest $it bpm" }, lowestSpo2?.let { "SpO₂ low $it%" }).joinToString(" · ")

    /** "Nap · 14:10–14:50 · 40 min" ("1h 05m" from an hour). */
    fun napHeadline(start: Instant, end: Instant, zone: ZoneId): String {
        val min = Duration.between(start, end).toMinutes().toInt()
        val length = if (min < 60) "$min min" else TodayCopy.hm(min * 60)
        return "Nap · ${span(start, end, zone)} · $length"
    }

    const val NapDetail = "From your heart rate and stillness. Was it a nap?"

    fun suggestion(e: SleepEstimate, zone: ZoneId): EstimateSuggestion = when (e.kind) {
        SleepKind.NIGHT -> EstimateSuggestion(
            e.id, e.kind, e.start, e.end, nightHeadline(e.start, e.end, zone), nightDetail(e.durationSec, e.lowestHr, e.lowestSpo2),
        )
        SleepKind.NAP -> EstimateSuggestion(e.id, e.kind, e.start, e.end, napHeadline(e.start, e.end, zone), NapDetail)
    }

    /** [t] moved by [steps] × [ADJUST_STEP_MIN], kept within [MaxShift] of [suggested] and inside [lo]..[hi]. */
    fun step(t: Instant, steps: Int, suggested: Instant, lo: Instant, hi: Instant): Instant {
        val moved = t.plus(Duration.ofMinutes(steps * ADJUST_STEP_MIN))
        val min = maxOf(suggested.minus(MaxShift), lo)
        val max = minOf(suggested.plus(MaxShift), hi)
        return if (max < min) t else moved.coerceIn(min, max)
    }

    private fun Instant.coerceIn(a: Instant, b: Instant): Instant = if (this < a) a else if (this > b) b else this
}

/**
 * The night suggestion on Today (and Sleep): the estimate badge and why, the question in Fraunces, what was measured, and
 * Yes · Adjust · No. Adjust opens [AdjustSleepSheet].
 */
@Composable
fun NightSuggestionCard(
    s: EstimateSuggestion,
    onAnswer: (EstimateAnswer) -> Unit,
    modifier: Modifier = Modifier,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    var adjusting by rememberSaveable(s.id) { mutableStateOf(false) }
    TintedCard(OrbitPalette.SleepA, modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(28.dp).background(OrbitPalette.SleepB.copy(alpha = 0.16f), CircleShape), contentAlignment = Alignment.Center) {
                Symbol(OrbitSymbol.Sleep, size = 15.dp, strokeWidth = 1.7f, tint = OrbitPalette.StageRem)
            }
            Spacer(Modifier.width(10.dp))
            Text(
                EstimateCopy.NightKicker, style = OrbitTypography.Eyebrow, maxLines = 1,
                modifier = Modifier.weight(1f).lineBox(OrbitTypography.Eyebrow),
            )
            EstimateBadge()
        }
        Text(
            s.headline, style = OrbitTypography.Insight,
            modifier = Modifier.padding(top = 14.dp).semantics { heading() },
        )
        Text(s.detail, style = OrbitTypography.Meta, modifier = Modifier.padding(top = 6.dp))
        Row(Modifier.padding(top = 16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WarmButton("Yes", { onAnswer(EstimateAnswer.Confirm(s.id)) }, Modifier.weight(1f).height(48.dp))
            GhostButton("Adjust", { adjusting = true }, Modifier.weight(1f), contentPadding = 12.dp)
            GhostButton("No", { onAnswer(EstimateAnswer.Dismiss(s.id)) }, Modifier.weight(1f), contentPadding = 12.dp)
        }
    }
    if (adjusting) {
        AdjustSleepSheet(
            s, zone = zone,
            onSave = { start, end -> adjusting = false; onAnswer(EstimateAnswer.Confirm(s.id, start, end)) },
            onDismiss = { adjusting = false },
        )
    }
}

/** A nap, kept small: its times and length, and two quiet answers. */
@Composable
fun NapSuggestionCard(s: EstimateSuggestion, onAnswer: (EstimateAnswer) -> Unit, modifier: Modifier = Modifier) {
    TintedCard(
        OrbitPalette.SleepA, modifier.fillMaxWidth(), amount = 0.10f,
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Symbol(OrbitSymbol.Sleep, size = 16.dp, strokeWidth = 1.7f, tint = OrbitPalette.StageRem)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(s.headline, style = OrbitTypography.BodyStrong, maxLines = 1, modifier = Modifier.lineBox(OrbitTypography.BodyStrong))
                Text(s.detail, style = OrbitTypography.Meta, modifier = Modifier.padding(top = 4.dp))
            }
        }
        GhostButtonPair(
            "Yes, a nap", { onAnswer(EstimateAnswer.Confirm(s.id)) },
            "No", { onAnswer(EstimateAnswer.Dismiss(s.id)) },
            Modifier.padding(top = 12.dp),
        )
    }
}

/**
 * Adjust an estimated night: fell asleep and woke up in 10-minute steps (the goals sheets' steppers), the time asleep
 * as it changes, then Save. Each time stays within four hours of the suggestion and the night at least 30 minutes long.
 */
@Composable
fun AdjustSleepSheet(
    s: EstimateSuggestion,
    onSave: (Instant, Instant) -> Unit,
    onDismiss: () -> Unit,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    var start by remember(s.id) { mutableStateOf(s.start) }
    var end by remember(s.id) { mutableStateOf(s.end) }
    OrbitSheet(title = EstimateCopy.SheetTitle, kicker = EstimateCopy.SheetKicker, onDismiss = onDismiss) {
        Text(EstimateCopy.SheetNote, style = OrbitTypography.Lead)
        Spacer(Modifier.height(16.dp))
        ListCard {
            StepperRow(
                label = "Fell asleep", unit = "", value = EstimateCopy.clock(start, zone), error = null, first = true,
                onValue = {}, readOnly = true,
                onStep = { start = EstimateCopy.step(start, it, s.start, Instant.MIN, end.minus(EstimateCopy.MinNight)) },
            )
            StepperRow(
                label = "Woke up", unit = "", value = EstimateCopy.clock(end, zone), error = null, first = false, last = true,
                onValue = {}, readOnly = true,
                onStep = { end = EstimateCopy.step(end, it, s.end, start.plus(EstimateCopy.MinNight), Instant.MAX) },
            )
        }
        Text(
            "${TodayCopy.hm(Duration.between(start, end).seconds.toInt())} asleep", style = OrbitTypography.Meta,
            modifier = Modifier.padding(top = 12.dp, start = 4.dp),
        )
        Spacer(Modifier.height(20.dp))
        WarmButton("Save", { onSave(start, end) }, Modifier.fillMaxWidth())
    }
}
