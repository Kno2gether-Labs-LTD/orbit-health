package dev.avijit.orbithealth.ui.me

import androidx.annotation.RawRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.avijit.orbithealth.R
import dev.avijit.orbithealth.ui.components.CardRow
import dev.avijit.orbithealth.ui.components.OrbitIconButton
import dev.avijit.orbithealth.ui.components.OrbitIconButtonDefaults
import dev.avijit.orbithealth.ui.components.OrbitSymbol
import dev.avijit.orbithealth.ui.components.Symbol
import dev.avijit.orbithealth.ui.components.TintedCard
import dev.avijit.orbithealth.ui.components.TintedCardDefaults
import dev.avijit.orbithealth.ui.components.livingEnter
import dev.avijit.orbithealth.ui.theme.OrbitPalette
import dev.avijit.orbithealth.ui.theme.OrbitTypography
import dev.avijit.orbithealth.ui.theme.lineBox

/** A bundled third-party work and its licence text (a raw resource, so the app never fetches it). */
data class Licence(val name: String, val use: String, val licence: String, val copyright: String, @RawRes val text: Int)

val Licences = listOf(
    Licence(
        name = "Fraunces", use = "Typeface", licence = "SIL Open Font License 1.1",
        copyright = "Copyright 2018 The Fraunces Project Authors", text = R.raw.fraunces_ofl,
    ),
    Licence(
        name = "Manrope", use = "Typeface", licence = "SIL Open Font License 1.1",
        copyright = "Copyright 2018 The Manrope Project Authors", text = R.raw.manrope_ofl,
    ),
    Licence(
        name = "Haze", use = "Glass blur", licence = "Apache License 2.0",
        copyright = "Copyright 2023, Christopher Banes and the Haze project contributors", text = R.raw.apache_2_0,
    ),
    Licence(
        name = "SQLCipher", use = "Encrypted database", licence = "BSD-style (Zetetic)",
        copyright = "Copyright (c) 2008-2024, ZETETIC LLC. Based on SQLite (public domain).", text = R.raw.sqlcipher_bsd,
    ),
    Licence(
        name = "OkHttp", use = "Talking to your Orbit Server", licence = "Apache License 2.0",
        copyright = "Copyright 2019 Square, Inc.", text = R.raw.apache_2_0,
    ),
    Licence(
        name = "ZXing", use = "Scanning the pairing code", licence = "Apache License 2.0",
        copyright = "ZXing core: Copyright (C) 2008 ZXing authors. ZXing Android Embedded: Copyright (C) 2012-2022 " +
            "ZXing authors, Journey Mobile.",
        text = R.raw.apache_2_0,
    ),
    Licence(
        name = "Jetpack & Kotlin", use = "Platform libraries", licence = "Apache License 2.0",
        copyright = "AndroidX (Core, Activity, Fragment, Biometric, Room, DataStore, Navigation, Lifecycle, SQLite), Jetpack Compose " +
            "(UI, Foundation, Material 3): Copyright The Android Open Source Project. Kotlin standard library, " +
            "kotlinx.coroutines and kotlinx.serialization: Copyright JetBrains s.r.o. and Kotlin Programming Language contributors.",
        text = R.raw.apache_2_0,
    ),
)

/**
 * Open-source licences in Living Skies: a back button, "Open source" over "Licences", a thank-you lead, then one
 * neutral card per work (what it is for, its name and licence, the copyright) that opens to its full licence text.
 */
@Composable
fun LicencesScreen(onBack: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(OrbitPalette.Base0, OrbitPalette.Base1)))) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().navigationBarsPadding()
                .padding(bottom = 24.dp),
        ) {
            // Detail header (tokens §5): the 40Ø back button, its 48dp target centred 36dp in from the edge.
            OrbitIconButton(
                OrbitSymbol.Back, onClick = onBack, size = 40.dp, contentDescription = "Back to Me",
                modifier = Modifier.padding(start = 16.dp - OrbitIconButtonDefaults.spill(40.dp), top = 8.dp),
            )
            Column(Modifier.padding(start = Gutter, end = Gutter, top = 10.dp).livingEnter(0)) {
                Text("Open source", style = OrbitTypography.Eyebrow, modifier = Modifier.lineBox(OrbitTypography.Eyebrow))
                Text(
                    "Licences", style = OrbitTypography.Title,
                    modifier = Modifier.padding(top = 2.dp).lineBox(OrbitTypography.Title).semantics { heading() },
                )
                Text(
                    "Orbit Health is built with these open-source works. Thank you to their authors.",
                    style = OrbitTypography.Lead, modifier = Modifier.padding(top = 8.dp),
                )
            }
            Column(
                Modifier.padding(start = Gutter, end = Gutter, top = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Licences.forEachIndexed { i, l -> LicenceCard(l, Modifier.livingEnter(60 + 60 * i)) }
            }
        }
    }
}

private val Gutter = 24.dp

@Composable
private fun LicenceCard(l: Licence, modifier: Modifier) {
    val context = LocalContext.current
    var open by rememberSaveable(l.name) { mutableStateOf(false) }
    val text = remember(l.text) { reflowLicence(context.resources.openRawResource(l.text).bufferedReader().use { it.readText() }) }
    val turn by animateFloatAsState(if (open) 90f else 0f, tween(220), label = "chev")
    TintedCard(
        OrbitPalette.Ink, modifier.fillMaxWidth(), amount = TintedCardDefaults.NeutralAmount,
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 4.dp),
    ) {
        Text(l.use, style = OrbitTypography.Label, modifier = Modifier.lineBox(OrbitTypography.Label))
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(l.name, style = OrbitTypography.BodyStrong, modifier = Modifier.weight(1f).padding(end = 12.dp))
            Text(l.licence, style = OrbitTypography.Meta, maxLines = 1)
        }
        Text(l.copyright, style = OrbitTypography.Caption, modifier = Modifier.padding(top = 6.dp))
        CardRow(first = false, modifier = Modifier.padding(top = 12.dp), onClick = { open = !open }) {
            Text(if (open) "Hide licence" else "Read licence", style = OrbitTypography.Body)
            Symbol(OrbitSymbol.Chev, Modifier.rotate(turn), size = 16.dp, strokeWidth = 1.8f, tint = OrbitPalette.Ink2)
        }
        AnimatedVisibility(open, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Text(
                text,
                style = OrbitTypography.Meta.copy(fontSize = OrbitTypography.Label.fontSize, lineHeight = OrbitTypography.Meta.lineHeight),
                modifier = Modifier.padding(top = 2.dp, bottom = 16.dp),
            )
        }
    }
}

/**
 * Licence files are hard-wrapped at ~70 columns, which wraps raggedly on a phone. Joins the lines of each paragraph
 * (paragraphs are separated by blank lines), keeps short all-caps headings ("PREAMBLE") on their own line, and drops rule lines
 * made only of dashes.
 */
internal fun reflowLicence(raw: String): String =
    raw.replace("\r\n", "\n")
        .lines()
        .filterNot { it.isNotBlank() && it.trim().all { c -> c == '-' } }
        .joinToString("\n")
        .split(Regex("\n\\s*\n"))
        .map { para ->
            val lines = para.lines().map { it.trim() }.filter { it.isNotEmpty() }
            buildString {
                lines.forEachIndexed { i, line ->
                    if (i > 0) append(if (lines[i - 1].isHeading()) "\n" else " ")
                    append(line)
                }
            }
        }
        .filter { it.isNotEmpty() }
        .joinToString("\n\n")

/** A short all-caps line ("PREAMBLE", "DISCLAIMER"); long all-caps lines are body text (the OFL disclaimer). */
private fun String.isHeading(): Boolean = length <= 40 && any { it.isLetter() } && none { it.isLowerCase() }
