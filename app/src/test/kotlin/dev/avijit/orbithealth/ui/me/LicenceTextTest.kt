package dev.avijit.orbithealth.ui.me

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LicenceTextTest {
    @Test fun reflowJoinsParagraphLinesKeepsHeadingsAndDropsRules() {
        val raw = listOf(
            "Copyright (c) 2016 The Inter", "Project Authors", "", "-----------", "SIL OPEN FONT LICENSE", "-----------", "",
            "  PREAMBLE", "The goals", "of the OFL", "",
        ).joinToString("\n")
        assertEquals(
            "Copyright (c) 2016 The Inter Project Authors\n\nSIL OPEN FONT LICENSE\n\nPREAMBLE\nThe goals of the OFL",
            reflowLicence(raw),
        )
    }

    @Test fun allCapsDisclaimerParagraphReflowsUnderItsHeading() {
        val raw = listOf(
            "DISCLAIMER",
            "THE FONT SOFTWARE IS PROVIDED \"AS IS\", WITHOUT WARRANTY OF ANY KIND,",
            "EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO ANY WARRANTIES OF",
            "MERCHANTABILITY.",
        ).joinToString("\n")
        assertEquals(
            "DISCLAIMER\nTHE FONT SOFTWARE IS PROVIDED \"AS IS\", WITHOUT WARRANTY OF ANY KIND, " +
                "EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO ANY WARRANTIES OF MERCHANTABILITY.",
            reflowLicence(raw),
        )
    }

    @Test fun everyBundledFontHasALicenceEntry() {
        // res/font/<family>[_style].ttf -> the Licences list names that family (Fraunces, Manrope).
        val families = File("src/main/res/font").listFiles()!!.map { it.name.substringBefore('_').substringBefore('.') }.toSet()
        assertTrue("fonts found", families.isNotEmpty())
        val named = Licences.map { it.name.lowercase() }
        families.forEach { assertTrue("licence entry for $it", it in named) }
    }

    @Test fun licencesListTheShippedWorksOnly() {
        // Inter left with the Glass Orbit screens (R5): only what the app bundles is listed.
        assertEquals(listOf("Fraunces", "Manrope", "Haze", "SQLCipher", "OkHttp", "ZXing", "Jetpack & Kotlin"), Licences.map { it.name })
        assertTrue("no Inter files left", File("src/main/res/font").listFiles()!!.none { it.name.startsWith("inter") })
    }

    @Test fun noStaleLicenceFilesShipInTheRepo() {
        // Inter is gone (R5): its OFL text must not linger at the repo root.
        assertTrue("stale licenses/Inter-OFL.txt", !File("../licenses/Inter-OFL.txt").exists())
    }

    @Test fun jetpackEntryNamesBiometricAndFragment() {
        // App lock pulls androidx.biometric and androidx.fragment (both Apache 2.0): the entry must name them.
        val jetpack = Licences.first { it.name == "Jetpack & Kotlin" }.copyright
        assertTrue(jetpack, "Biometric" in jetpack)
        assertTrue(jetpack, "Fragment" in jetpack)
    }
}
