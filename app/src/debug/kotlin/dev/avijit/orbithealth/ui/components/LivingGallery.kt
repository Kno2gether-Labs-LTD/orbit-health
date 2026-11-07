package dev.avijit.orbithealth.ui.components

import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.avijit.orbithealth.ui.nav.Routes
import dev.avijit.orbithealth.ui.sky.Sky
import dev.avijit.orbithealth.ui.sky.SkyKind
import dev.avijit.orbithealth.ui.theme.OrbitPalette
import dev.avijit.orbithealth.ui.theme.OrbitTheme
import dev.avijit.orbithealth.ui.theme.OrbitTypography
import dev.avijit.orbithealth.ui.theme.lineBox
import kotlin.math.roundToInt

/**
 * Living Skies gallery pages (debug GalleryActivity pages 6–8): the R1 design-system layer with mockup sample data.
 * Page 6 is laid out like the mockup's Today screen (screen-03) so the hero, arc, type, minis and goals can be
 * compared side by side; 7 shows every sky animated; 8 is the component kit.
 */
val LivingGalleryPages = listOf("Living Skies · Today", "Living Skies · Skies", "Living Skies · Kit")

/** First gallery page index of the Living Skies pages (0–5 were the Glass Orbit pages, removed in R5; kept stable). */
const val LivingGalleryFirstPage = 6

@Composable
fun LivingGallery(page: Int, modifier: Modifier = Modifier) {
    var route by remember { mutableStateOf(Routes.TODAY) }
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding()) {
            when (page) {
                0 -> TodayKitPage()
                1 -> SkiesPage()
                else -> KitPage()
            }
            Spacer(Modifier.height(OrbitBottomBarDefaults.Clearance + 40.dp))
        }
        OrbitBottomBar(route, { route = it }, Modifier.align(Alignment.BottomCenter))
    }
}

private val Gutter = 24.dp

// ----------------------------------------------------------------------------------------------- Today (screen-03)

@Composable
private fun ColumnScope.TodayKitPage() {
    Column(Modifier.padding(start = Gutter, end = Gutter, top = 14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Friday 6 November", style = OrbitTypography.Eyebrow, modifier = Modifier.weight(1f))
            Box(Modifier.size(6.dp).background(OrbitPalette.Optimal, CircleShape))
            Spacer(Modifier.width(7.dp))
            Text("Ring synced 2 min ago", style = OrbitTypography.Eyebrow)
        }
        Text(
            buildAnnotatedString {
                append("Good morning, ")
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append("Sam.") }
            },
            style = OrbitTypography.Greeting, modifier = Modifier.padding(top = 10.dp),
        )
        Text(
            "You slept 7h 12m, your deepest night this week. Your body is ready for a little more today.",
            style = OrbitTypography.Lead, modifier = Modifier.padding(top = 8.dp),
        )
    }
    ReadinessHero(Modifier.padding(start = Gutter, end = Gutter, top = 20.dp))
    PlanetDots(3, 0, Modifier.align(Alignment.CenterHorizontally).padding(top = 14.dp, bottom = 16.dp))
    Row(Modifier.padding(horizontal = Gutter), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        MiniCard(OrbitPalette.HeartA, OrbitSymbol.Body, "Heart", "73", "bpm", "Resting 61", Modifier.weight(1f))
        MiniCard(OrbitPalette.ReadinessA, OrbitSymbol.Hrv, "HRV", "48", "ms", "↑ Above avg", Modifier.weight(1f), OrbitPalette.Optimal)
        MiniCard(OrbitPalette.Spo2A, OrbitSymbol.Drop, "SpO₂", "97", "%", "Steady", Modifier.weight(1f))
    }
    Column(Modifier.padding(start = Gutter, end = Gutter, top = 30.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("Today's goals", style = OrbitTypography.H2, modifier = Modifier.weight(1f))
            Text("Tuned to your recovery", style = OrbitTypography.Meta)
        }
        TintedCard(OrbitPalette.Ink, Modifier.padding(top = 12.dp).fillMaxWidth(), amount = TintedCardDefaults.NeutralAmount) {
            KitGoal(OrbitSymbol.Steps, OrbitPalette.ActivityA, OrbitPalette.ActivityB, "9,200 steps", "914", "Raised 1,200 because you recovered well",
                0.099f, Brush.horizontalGradient(listOf(OrbitPalette.ActivityA, OrbitPalette.ActivityB)), first = true)
            KitGoal(OrbitSymbol.Breath, OrbitPalette.StressA, OrbitPalette.Optimal, "2 calm breaks", "1 of 2", "One minute of slow breathing",
                0.5f, Brush.horizontalGradient(listOf(OrbitPalette.StressA, Color(0xFF8FE3D3))))
            KitGoal(OrbitSymbol.Sleep, OrbitPalette.SleepB, OrbitPalette.StageRem, "In bed by 23:15", "Tonight", "Last night you were 40 min later", null, null)
        }
    }
}

/** Mockup heroReady(): 342 × 392 card, First light at 78, arc top 46, numeral top 106, word 202, insight 284. */
@Composable
private fun ReadinessHero(modifier: Modifier = Modifier) {
    val score = 0.78f
    val shown by rememberArcReveal(score)
    Box(modifier.fillMaxWidth().height(392.dp).clip(RoundedCornerShape(28.dp))) {
        Sky(SkyKind.FIRST_LIGHT, Modifier.fillMaxSize(), param = score)
        HeroTop("Readiness")
        OrbitArc(score, Modifier.align(Alignment.TopCenter).offset(y = 46.dp), animateIn = false, style = OrbitArcDefaults.Hero)
        ArcValue(shown, score, Modifier.align(Alignment.TopCenter))
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 284.dp)) {
            Text("Recovered and steady. A good day to move a little more.", style = OrbitTypography.Insight)
            Text("Resting HR 61 · HRV 48 ms, above your normal", style = OrbitTypography.Meta, color = OrbitPalette.InkSky2, modifier = Modifier.padding(top = 10.dp))
        }
    }
}

@Composable
private fun BoxScope.HeroTop(label: String) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 10.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label.uppercase(), style = OrbitTypography.Overline, modifier = Modifier.weight(1f))
        OrbitIconButton(OrbitSymbol.Expand, onClick = {})
    }
}

/** Numeral synced to the arc reveal, then the status word fading in once the value passes 60% of the score. */
@Composable
private fun ArcValue(shown: Float, score: Float, modifier: Modifier, word: String = "Good") {
    val wordAlpha by animateFloatAsState(if (shown >= 0.6f * score) 1f else 0f, tween(240), label = "word")
    Column(modifier.padding(top = 106.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text((shown * 100).roundToInt().toString(), style = OrbitTypography.NumXl, textAlign = TextAlign.Center, modifier = Modifier.lineBox(OrbitTypography.NumXl))
        Text(word, style = OrbitTypography.Word, modifier = Modifier.padding(top = 4.dp).alpha(wordAlpha))
    }
}

@Composable
private fun MiniCard(
    hue: Color, symbol: OrbitSymbol, label: String, value: String, unit: String, foot: String,
    modifier: Modifier, footColor: Color = OrbitPalette.Ink2,
) {
    TintedCard(hue, modifier, shape = TintedCardDefaults.MiniShape, contentPadding = TintedCardDefaults.MiniPadding) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Symbol(symbol, size = 14.dp, strokeWidth = 1.8f, tint = hue)
            Text(label, style = OrbitTypography.Label)
        }
        Text(
            buildAnnotatedString {
                append(value)
                withStyle(OrbitTypography.Unit.toSpanStyle()) { append(" " + unit) }
            },
            style = OrbitTypography.Value, modifier = Modifier.padding(top = 10.dp).lineBox(OrbitTypography.Value),
        )
        Text(foot, style = OrbitTypography.Caption, color = footColor, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun KitGoal(
    symbol: OrbitSymbol, discHue: Color, glyph: Color, title: String, right: String, meta: String,
    progress: Float?, fill: Brush?, first: Boolean = false,
) {
    Column {
        if (!first) Spacer(Modifier.fillMaxWidth().height(1.dp).background(OrbitPalette.Hairline))
        Row(Modifier.padding(top = if (first) 4.dp else 14.dp, bottom = 14.dp)) {
            Box(Modifier.size(36.dp).background(discHue.copy(alpha = 0.16f), CircleShape), contentAlignment = Alignment.Center) {
                Symbol(symbol, size = 18.dp, strokeWidth = 1.7f, tint = glyph)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row {
                    Text(title, style = OrbitTypography.BodyStrong, modifier = Modifier.weight(1f))
                    Text(right, style = if (progress != null) OrbitTypography.BodyStrong else OrbitTypography.Meta)
                }
                Text(meta, style = OrbitTypography.Meta, modifier = Modifier.padding(top = 2.dp))
                if (progress != null && fill != null) {
                    Canvas(Modifier.padding(top = 10.dp).fillMaxWidth().height(4.dp)) {
                        val r = CornerRadius(2.dp.toPx())
                        drawRoundRect(OrbitPalette.Ink.copy(alpha = 0.10f), cornerRadius = r)
                        drawRoundRect(fill, size = Size(size.width * progress, size.height), cornerRadius = r)
                    }
                }
            }
        }
    }
}

// ----------------------------------------------------------------------------------------------- Skies

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SkiesPage() {
    Column(Modifier.padding(horizontal = Gutter, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Design system", style = OrbitTypography.Eyebrow)
        Text("Living skies", style = OrbitTypography.Title)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SkyTile(SkyKind.FIRST_LIGHT, "First light", "Readiness 78", 0.78f, Modifier.weight(1f))
            SkyTile(SkyKind.NIGHT, "Night", "Sleep", 0f, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SkyTile(SkyKind.EMBER, "Ember", "Activity", 0f, Modifier.weight(1f))
            SkyTile(SkyKind.PULSE, "Pulse", "Heart · 73 bpm", 73f, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SkyTile(SkyKind.CALM, "Calm", "Stress 32", 0.32f, Modifier.weight(1f))
            SkyTile(SkyKind.TIDE, "Tide", "SpO₂", 0f, Modifier.weight(1f))
        }
        Text("Orbit arc", style = OrbitTypography.H2, modifier = Modifier.padding(top = 18.dp))
        ArcRow()
        Text("At mockup size", style = OrbitTypography.H2, modifier = Modifier.padding(top = 18.dp))
        MockupSky(SkyKind.NIGHT, "Sleep hero · Night", 392.dp, 28.dp, stars = 30, seed = 5)
        MockupSky(SkyKind.PULSE, "Heart · Pulse at 73 bpm", 352.dp, 28.dp, param = 73f)
        MockupSky(SkyKind.CALM, "Stress · Calm at 32", 330.dp, 28.dp, param = 0.32f)
        MockupSky(SkyKind.TIDE, "SpO₂ · Tide", 250.dp, 24.dp)
        MockupSky(SkyKind.EMBER, "Activity · Ember", 300.dp, 28.dp)
    }
}

@Composable
private fun MockupSky(kind: SkyKind, label: String, height: Dp, radius: Dp, param: Float = 0f, stars: Int? = null, seed: Int = 11) {
    Text(label, style = OrbitTypography.Eyebrow)
    Box(Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(radius))) {
        Sky(kind, Modifier.fillMaxSize(), param = param, stars = stars, starSeed = seed)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ArcRow() {
    Column {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            listOf(null, 0f, 0.099f, 0.5f, 0.84f, 1f).forEach { f ->
                Box(Modifier.size(96.dp).clip(RoundedCornerShape(20.dp)), contentAlignment = Alignment.Center) {
                    Sky(SkyKind.NIGHT, Modifier.fillMaxSize(), stars = 4, starSeed = 3)
                    OrbitArc(f, size = 66.dp, style = OrbitArcDefaults.Detail.copy(planet = 7.dp, halo = 10.5.dp))
                    Text(f?.let { "${(it * 100).roundToInt()}" } ?: "--", style = OrbitTypography.Value)
                }
            }
        }
    }
}

@Composable
private fun SkyTile(kind: SkyKind, name: String, metric: String, param: Float, modifier: Modifier) {
    Box(modifier.height(200.dp).clip(RoundedCornerShape(24.dp))) {
        Sky(kind, Modifier.fillMaxSize(), param = param, stars = if (kind == SkyKind.NIGHT) 9 else null)
        Column(Modifier.align(Alignment.BottomStart).padding(14.dp)) {
            Text(name, style = OrbitTypography.InsightSm)
            Text(metric, style = OrbitTypography.Caption, color = OrbitPalette.InkSky2)
        }
    }
}

// ----------------------------------------------------------------------------------------------- Kit

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KitPage() {
    Column(Modifier.padding(horizontal = Gutter, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Design system", style = OrbitTypography.Eyebrow)
        Text(buildAnnotatedString { append("Orbit "); withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append("kit") } }, style = OrbitTypography.Title)
        // Sleep detail header: arc 176 over Night.
        Box(Modifier.fillMaxWidth().height(250.dp).clip(RoundedCornerShape(bottomStart = 32.dp, bottomEnd = 32.dp, topStart = 28.dp, topEnd = 28.dp))) {
            Sky(SkyKind.NIGHT, Modifier.fillMaxSize(), starSeed = 9)
            val shown by rememberArcReveal(0.84f)
            OrbitArc(0.84f, Modifier.align(Alignment.TopCenter).offset(y = 30.dp), size = OrbitArcDefaults.DetailSize, animateIn = false)
            Column(Modifier.align(Alignment.TopCenter).padding(top = 74.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text((shown * 100).roundToInt().toString(), style = OrbitTypography.NumLg, modifier = Modifier.lineBox(OrbitTypography.NumLg))
                Text("7h 12m asleep", style = OrbitTypography.Meta, color = OrbitPalette.InkSky2)
            }
            Text("Your deepest night this week.", style = OrbitTypography.InsightLg, modifier = Modifier.align(Alignment.BottomStart).padding(20.dp))
        }
        Text("Cards", style = OrbitTypography.H2, modifier = Modifier.padding(top = 12.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp), maxItemsInEachRow = 3) {
            listOf(
                "Sleep" to OrbitPalette.SleepA, "Readiness" to OrbitPalette.ReadinessA, "Activity" to OrbitPalette.ActivityA,
                "Heart" to OrbitPalette.HeartA, "Stress" to OrbitPalette.StressA, "SpO₂" to OrbitPalette.Spo2A,
            ).forEach { (n, hue) ->
                TintedCard(hue, Modifier.weight(1f), shape = TintedCardDefaults.MiniShape, contentPadding = TintedCardDefaults.MiniPadding) {
                    Text(n, style = OrbitTypography.Label)
                    Text("48", style = OrbitTypography.Value, modifier = Modifier.padding(top = 10.dp))
                }
            }
        }
        TintedCard(OrbitPalette.Ink, Modifier.fillMaxWidth(), amount = TintedCardDefaults.NeutralAmount) {
            Text("Stress", style = OrbitTypography.BodyStrong)
            Text("Calm most of the morning, a short peak at 10:40.", style = OrbitTypography.InsightSm, modifier = Modifier.padding(top = 6.dp))
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Good" to OrbitPalette.Good, "Optimal" to OrbitPalette.Optimal, "Pay attention" to OrbitPalette.Attention).forEach { (w, c) ->
                    Text(w, style = OrbitTypography.Word, color = c)
                }
            }
        }
        Text("Buttons & dots", style = OrbitTypography.H2, modifier = Modifier.padding(top = 12.dp))
        WarmButton("Unlock", onClick = {}, modifier = Modifier.fillMaxWidth()) { Symbol(OrbitSymbol.Finger, size = 20.dp, tint = OrbitPalette.OnInk) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            GhostButton("Measure now", onClick = {}, modifier = Modifier.weight(1f)) { Symbol(OrbitSymbol.Hrv, size = 18.dp, strokeWidth = 1.7f) }
            WarmButton("Disabled", onClick = {}, enabled = false, modifier = Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            var sel by remember { mutableIntStateOf(1) }
            PlanetDots(3, sel, Modifier.clickable { sel = (sel + 1) % 3 })
            PlanetDots(5, 0)
            OrbitIconButton(OrbitSymbol.Back, onClick = {}, size = 40.dp)
            OrbitArc(0.54f, size = OrbitArcDefaults.BatterySize)
        }
        Text("Glyphs", style = OrbitTypography.H2, modifier = Modifier.padding(top = 12.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            OrbitSymbol.entries.forEach { Symbol(it, size = 24.dp, tint = OrbitPalette.Ink) }
        }
        Text("Type", style = OrbitTypography.H2, modifier = Modifier.padding(top = 12.dp))
        Text("914", style = OrbitTypography.NumXl)
        Text(buildAnnotatedString { append("97"); withStyle(OrbitTypography.Unit.toSpanStyle()) { append(" %") } }, style = OrbitTypography.NumMd)
        Text("READINESS · LIVE", style = OrbitTypography.Overline)
        Text("Sentences inside skies speak in Fraunces.", style = OrbitTypography.Insight)
        Text("Labels, numbers and UI are Manrope.", style = OrbitTypography.Lead)
    }
}

@Preview(name = "Living Skies · Today", widthDp = 390, heightDp = 844, showBackground = true, backgroundColor = 0xFF0A0E24)
@Composable
private fun LivingTodayPreview() = OrbitTheme { LivingBackdrop { LivingGallery(0) } }

/**
 * One sky alone at a mockup card size, 24dp in and 96dp down, for pixel-aligned compare crops (debug gallery
 * `--es sky NIGHT --ei h 392 --ef param 73`).
 */
@Composable
fun LivingSkySolo(kind: SkyKind, height: Dp, param: Float, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize()) {
        Box(Modifier.padding(start = Gutter, end = Gutter, top = 96.dp).fillMaxWidth().height(height).clip(RoundedCornerShape(28.dp))) {
            Sky(kind, Modifier.fillMaxSize(), param = param, stars = if (kind == SkyKind.NIGHT) 30 else null, starSeed = 5)
        }
    }
}
