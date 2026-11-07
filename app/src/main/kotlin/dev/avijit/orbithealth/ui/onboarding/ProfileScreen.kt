package dev.avijit.orbithealth.ui.onboarding

import androidx.compose.runtime.getValue
import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.avijit.orbithealth.ring.Sex
import dev.avijit.orbithealth.sync.BatteryOptimization
import dev.avijit.orbithealth.ui.components.ListCard
import dev.avijit.orbithealth.ui.components.ListGroupLabel
import dev.avijit.orbithealth.ui.components.SegmentedPill
import dev.avijit.orbithealth.ui.components.StepperRow
import dev.avijit.orbithealth.ui.components.TintedCard
import dev.avijit.orbithealth.ui.components.TintedCardDefaults
import dev.avijit.orbithealth.ui.components.WarmButton
import dev.avijit.orbithealth.ui.components.livingEnter
import dev.avijit.orbithealth.ui.sky.Sky
import dev.avijit.orbithealth.ui.sky.SkyKind
import dev.avijit.orbithealth.ui.theme.OrbitPalette
import dev.avijit.orbithealth.ui.theme.OrbitTypography
import dev.avijit.orbithealth.ui.theme.lineBox
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.withStyle

private val SexOptions = listOf(Sex.FEMALE to "Female", Sex.MALE to "Male", Sex.UNSPECIFIED to "Prefer not to say")

/**
 * Living Skies "About you": a First light header (the sun risen over Welcome and Pair), then the optional name, height,
 * weight and age steppers in a list card, and sex as the segmented control. Height, weight and age (sex optional)
 * feed the ring's calorie and distance maths. "Finish setup" saves the profile,
 * handshakes with it, saves the ring, starts sync, asks once for the battery exemption, then [onDone].
 */
@Composable
fun ProfileScreen(vm: OnboardingViewModel, onDone: () -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val focus = LocalFocusManager.current

    val batteryPrompt = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        vm.onBatteryPromptHandled()
    }
    LaunchedEffect(s.askBatteryExemption) {
        if (s.askBatteryExemption) {
            try {
                batteryPrompt.launch(BatteryOptimization.requestIntent(context))
            } catch (e: ActivityNotFoundException) {
                vm.onBatteryPromptHandled()
            }
        }
    }
    LaunchedEffect(s.completed) { if (s.completed) onDone() }

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(OrbitPalette.Base0, OrbitPalette.Base1)))) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .navigationBarsPadding(),
        ) {
            ProfileHeader()
            Column(Modifier.padding(horizontal = OnboardingGutter)) {
                ListGroupLabel("What should we call you? · optional", Modifier.livingEnter(180), gutter = 0.dp)
                TintedCard(
                    OrbitPalette.Ink, Modifier.fillMaxWidth().livingEnter(180), amount = TintedCardDefaults.NeutralAmount,
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
                ) {
                    NameField(s.name, vm::setName)
                    Text("Only used to greet you on Today.", style = OrbitTypography.Meta, modifier = Modifier.padding(top = 6.dp))
                }
                ListGroupLabel("Your body", Modifier.livingEnter(240), gutter = 0.dp)
                ListCard(Modifier.livingEnter(240)) {
                    StepperRow(
                        label = "Height", unit = "cm", value = s.profile.height, error = s.profileErrors.height, first = true,
                        onValue = vm::setHeight, onStep = vm::stepHeight,
                    )
                    StepperRow(
                        label = "Weight", unit = "kg", value = s.profile.weight, error = s.profileErrors.weight, first = false,
                        onValue = vm::setWeight, onStep = vm::stepWeight,
                    )
                    StepperRow(
                        label = "Age", unit = "years", value = s.profile.age, error = s.profileErrors.age, first = false,
                        onValue = vm::setAge, onStep = vm::stepAge, last = true, onDone = { focus.clearFocus() },
                    )
                }
                ListGroupLabel("Sex · optional", Modifier.livingEnter(300), gutter = 0.dp)
                SegmentedPill(
                    options = SexOptions.map { it.second },
                    selected = SexOptions.indexOfFirst { it.first == s.profile.sex }.coerceAtLeast(0),
                    onSelect = { vm.setSex(SexOptions[it].first) },
                    modifier = Modifier.livingEnter(300),
                )
                AnimatedVisibility(s.finishError != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                    TintedCard(OrbitPalette.Attention, Modifier.padding(top = 16.dp).fillMaxWidth(), amount = 0.10f) {
                        Text(s.finishError.orEmpty(), style = OrbitTypography.Body)
                    }
                }
                Spacer(Modifier.height(28.dp))
                WarmButton(
                    text = if (s.finishError != null) "Try again" else "Finish setup",
                    onClick = { focus.clearFocus(); vm.finish() },
                    modifier = Modifier.fillMaxWidth().livingEnter(360),
                    busy = s.finishing,
                    busyText = "Connecting to your ring…",
                )
                Footer("Stored only on this phone.")
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/**
 * The onboarding's morning: a 300dp First light header with Sleep's 32dp bottom corners, the sun now well up (it
 * rose over Welcome and Pair), and "Almost there / About *you*" with the lead on the upper sky.
 */
@Composable
private fun ProfileHeader() {
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Box(Modifier.fillMaxWidth().height(statusTop + HeaderHeight).clip(HeaderShape)) {
        Sky(SkyKind.FIRST_LIGHT, Modifier.matchParentSize(), param = Sunrise.CONNECTED + 0.1f, horizon = 0.78f, stars = 0)
        Column(Modifier.padding(start = OnboardingGutter, end = OnboardingGutter, top = statusTop + 14.dp)) {
            Text("Almost there", style = OrbitTypography.Eyebrow.copy(color = OrbitPalette.InkSky2), modifier = Modifier.lineBox(OrbitTypography.Eyebrow).livingEnter(0))
            Text(
                buildAnnotatedString {
                    append("About ")
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append("you") }
                },
                style = OrbitTypography.Title,
                modifier = Modifier.padding(top = 2.dp).lineBox(OrbitTypography.Title).livingEnter(60).semantics { heading() },
            )
            Text(
                "Your ring uses these to work out calories and distance. They stay on this phone.",
                style = OrbitTypography.Lead.copy(color = OrbitPalette.InkSky2),
                modifier = Modifier.padding(top = 10.dp, end = 24.dp).livingEnter(120),
            )
        }
    }
}

private val HeaderHeight = 264.dp
private val HeaderShape = RoundedCornerShape(bottomStart = 32.dp, bottomEnd = 32.dp)

/** The optional first name, in the value role with a quiet placeholder. */
@Composable
private fun NameField(name: String, onName: (String) -> Unit) {
    BasicTextField(
        value = name,
        onValueChange = onName,
        singleLine = true,
        textStyle = OrbitTypography.Value,
        cursorBrush = SolidColor(OrbitPalette.SleepB),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Your name" },
        decorationBox = { field ->
            Box {
                if (name.isEmpty()) Text("Your first name", style = OrbitTypography.Value.copy(color = OrbitPalette.Ink.copy(alpha = 0.35f)))
                field()
            }
        },
    )
}
