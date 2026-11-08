package dev.avijit.orbithealth.ui.me

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import dev.avijit.orbithealth.ring.Sex
import dev.avijit.orbithealth.ui.components.GhostButton
import dev.avijit.orbithealth.ui.components.ListCard
import dev.avijit.orbithealth.ui.components.ListRow
import dev.avijit.orbithealth.ui.components.OrbitSheet
import dev.avijit.orbithealth.ui.components.OrbitSymbol
import dev.avijit.orbithealth.ui.components.SegmentedPill
import dev.avijit.orbithealth.ui.components.StepperRow
import dev.avijit.orbithealth.ui.components.Symbol
import dev.avijit.orbithealth.ui.components.TintedCard
import dev.avijit.orbithealth.ui.components.TintedCardDefaults
import dev.avijit.orbithealth.ui.components.WarmButton
import dev.avijit.orbithealth.ui.onboarding.ProfileInput
import dev.avijit.orbithealth.ui.onboarding.ProfileRules
import dev.avijit.orbithealth.ui.theme.OrbitPalette
import dev.avijit.orbithealth.ui.theme.OrbitTypography
import java.text.NumberFormat

/** Title of the sheet behind Me's door to Plan 2. */
const val ComingSoonTitle = "Coming in the next update"
const val DataPrivacySoon = "Health Connect, open exports and a live list of every connection your data makes, all in one place. " +
    "Orbit Health only goes online to talk to your own Orbit Server, once you pair one, and to ask GitHub whether " +
    "a new version is out (App updates; you can turn that off). " +
    dev.avijit.orbithealth.ui.agent.AgentSyncCopy.ZONE_NOTE

private val StepGoals = 2_000..30_000
private val SleepGoals = 300..660
private val SexOptions = listOf(Sex.FEMALE to "Female", Sex.MALE to "Male", Sex.UNSPECIFIED to "Prefer not to say")

/** A sheet's explanatory line (tokens lead, ink2). */
@Composable
private fun SheetNote(text: String) {
    Text(text, style = OrbitTypography.Lead)
}

/** Data & privacy: still coming, but already says how much went to the user's server this month (network log). */
@Composable
internal fun DataPrivacySheet(sentThisMonth: String, onDismiss: () -> Unit) {
    OrbitSheet(title = ComingSoonTitle, kicker = "Data & privacy", onDismiss = onDismiss) {
        SheetNote(DataPrivacySoon)
        Spacer(Modifier.height(16.dp))
        ListCard {
            ListRow(
                sentThisMonth, first = true,
                leading = { Symbol(OrbitSymbol.Up, size = 18.dp, strokeWidth = 1.7f, tint = OrbitPalette.Optimal) },
            )
        }
        Spacer(Modifier.height(24.dp))
        GhostButton("Got it", onDismiss, Modifier.fillMaxWidth())
    }
}

/** Editors for Me's name, goals and profile; each saves on "Save". */
@Composable
internal fun EditSheet(sheet: MeSheet, state: MeUiState, actions: MeActions, onDismiss: () -> Unit) {
    when (sheet) {
        MeSheet.NAME -> NameSheet(state.name.orEmpty(), onSave = { actions.setName(it); onDismiss() }, onDismiss = onDismiss)
        MeSheet.STEP_GOAL -> {
            val nf = remember { NumberFormat.getIntegerInstance() }
            var v by remember { mutableIntStateOf(state.stepGoal) }
            StepperSheet(
                title = "Base step goal",
                note = "Your everyday target. Today tunes it to your recovery each morning, so the goal you see there can be " +
                    "a little higher or lower than this.",
                label = "Steps", value = nf.format(v), onStep = { v = (v + it * 500).coerceIn(StepGoals) },
                onSave = { actions.setStepGoal(v); onDismiss() }, onDismiss = onDismiss,
            )
        }
        MeSheet.SLEEP_GOAL -> {
            var v by remember { mutableIntStateOf(state.sleepGoalMin) }
            StepperSheet(
                title = "Sleep goal", note = "How long you aim to sleep each night.",
                label = "Sleep", value = MeMath.hoursMinutes(v), onStep = { v = (v + it * 15).coerceIn(SleepGoals) },
                onSave = { actions.setSleepGoalMin(v); onDismiss() }, onDismiss = onDismiss,
            )
        }
        MeSheet.BEDTIME -> {
            var v by remember { mutableStateOf(state.bedtime) }
            StepperSheet(
                title = "Bedtime", note = "When you aim to be in bed. Today reminds you of it in the evening.",
                label = "In bed by", value = v.toString(), onStep = { v = v.plusMinutes(15L * it) },
                onSave = { actions.setBedtime(v); onDismiss() }, onDismiss = onDismiss,
            )
        }
        MeSheet.PROFILE -> ProfileSheet(state, actions, onDismiss)
        else -> Unit
    }
}

/** The greeting name: typed in the value role, saved trimmed (blank removes it). */
@Composable
private fun NameSheet(initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    val focus = LocalFocusManager.current
    var text by remember { mutableStateOf(initial) }
    OrbitSheet(title = "Your name", kicker = "Profile", onDismiss = onDismiss) {
        SheetNote("Only used to greet you on Today. Leave it empty for a plain greeting.")
        Spacer(Modifier.height(16.dp))
        TintedCard(
            OrbitPalette.Ink, Modifier.fillMaxWidth(), amount = TintedCardDefaults.NeutralAmount,
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
        ) {
            BasicTextField(
                value = text,
                onValueChange = { text = it.take(MeMath.MAX_NAME) },
                singleLine = true,
                textStyle = OrbitTypography.Value,
                cursorBrush = SolidColor(OrbitPalette.SleepB),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focus.clearFocus(); onSave(text) }),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Your name" },
                decorationBox = { field ->
                    Box {
                        if (text.isEmpty()) Text("Your first name", style = OrbitTypography.Value.copy(color = OrbitPalette.Ink.copy(alpha = 0.35f)))
                        field()
                    }
                },
            )
        }
        Spacer(Modifier.height(20.dp))
        WarmButton("Save", { focus.clearFocus(); onSave(text) }, Modifier.fillMaxWidth())
    }
}

@Composable
private fun StepperSheet(
    title: String,
    note: String,
    label: String,
    value: String,
    onStep: (Int) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    OrbitSheet(title = title, kicker = "Goals", onDismiss = onDismiss) {
        SheetNote(note)
        Spacer(Modifier.height(16.dp))
        ListCard {
            StepperRow(label = label, unit = "", value = value, error = null, first = true, onValue = {}, onStep = onStep, readOnly = true)
        }
        Spacer(Modifier.height(20.dp))
        WarmButton("Save", onSave, Modifier.fillMaxWidth())
    }
}

/** Height, weight, age and sex, with the same steppers and rules as onboarding. Saved for the next handshake. */
@Composable
private fun ProfileSheet(state: MeUiState, actions: MeActions, onDismiss: () -> Unit) {
    val focus = LocalFocusManager.current
    var input by remember {
        mutableStateOf(state.profile.let { ProfileInput(it.heightCm.toString(), it.weightKg.toString(), it.age.toString(), it.sex) })
    }
    var showErrors by remember { mutableStateOf(false) }
    val errors = ProfileRules.validate(input)
    fun step(text: String, delta: Int, range: IntRange): String {
        val cur = text.trim().toIntOrNull()
        return (if (cur == null) (if (delta > 0) range.first else range.last) else (cur + delta).coerceIn(range)).toString()
    }
    OrbitSheet(title = "About you", kicker = "Profile", onDismiss = onDismiss) {
        SheetNote("Your ring uses these to work out calories and distance. They stay on this phone.")
        Spacer(Modifier.height(16.dp))
        ListCard {
            StepperRow(
                "Height", "cm", input.height, errors.height.takeIf { showErrors }, first = true,
                onValue = { input = input.copy(height = it.filter(Char::isDigit).take(3)) },
                onStep = { input = input.copy(height = step(input.height, it, ProfileRules.HeightCm)) },
            )
            StepperRow(
                "Weight", "kg", input.weight, errors.weight.takeIf { showErrors }, first = false,
                onValue = { input = input.copy(weight = it.filter(Char::isDigit).take(3)) },
                onStep = { input = input.copy(weight = step(input.weight, it, ProfileRules.WeightKg)) },
            )
            StepperRow(
                "Age", "years", input.age, errors.age.takeIf { showErrors }, first = false,
                onValue = { input = input.copy(age = it.filter(Char::isDigit).take(3)) },
                onStep = { input = input.copy(age = step(input.age, it, ProfileRules.Age)) },
                last = true, onDone = { focus.clearFocus() },
            )
        }
        Text("Sex · optional", style = OrbitTypography.Eyebrow, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
        SegmentedPill(
            options = SexOptions.map { it.second },
            selected = SexOptions.indexOfFirst { it.first == input.sex }.coerceAtLeast(0),
            onSelect = { input = input.copy(sex = SexOptions[it].first) },
        )
        Spacer(Modifier.height(24.dp))
        Column(Modifier.padding(bottom = 4.dp)) {
            WarmButton("Save", {
                focus.clearFocus()
                val p = ProfileRules.profileOf(input)
                if (p == null) showErrors = true else { actions.setProfile(p); onDismiss() }
            }, Modifier.fillMaxWidth())
        }
    }
}
