package dev.avijit.orbithealth.ui.me

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import dev.avijit.orbithealth.ui.sky.LocalSkyForceFallback
import dev.avijit.orbithealth.update.ReleaseInfo
import dev.avijit.orbithealth.update.SemVer
import dev.avijit.orbithealth.update.UpdatePhase
import dev.avijit.orbithealth.update.UpdateUiState
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(qualifiers = "w390dp-h844dp-xxhdpi")
@RunWith(RobolectricTestRunner::class)
class AppUpdatesTest {
    @get:Rule val rule = createComposeRule()

    private val now = Instant.parse("2026-11-07T10:00:00Z")
    private val release = ReleaseInfo(
        "v0.7.1", SemVer(0, 7, 1), "Orbit Health 0.7.1", "## What's new\n- A **calmer** sleep chart\n- Fixes for [pairing](https://x)",
        "", "https://github.com/x/OrbitHealth.apk", 10, "https://github.com/x/OrbitHealth.apk.sha256",
    )
    private val available = UpdateUiState("0.6.0", phase = UpdatePhase.Available(release.version, release), lastCheckedAt = now.minusSeconds(7200))
    private val loaded = MeUiState(loaded = true, ringName = "TK5 A1B2", hasProfile = true)

    private fun show(u: UpdateUiState, actions: UpdateActions = UpdateActions()) = rule.setContent {
        CompositionLocalProvider(LocalSkyForceFallback provides true) {
            MeContent(loaded, MeActions(), updates = u, updateActions = actions, now = { now })
        }
    }

    private fun openSheet() {
        rule.onNodeWithText(UpdateCopy.ROW).performScrollTo().performClick()
        rule.waitForIdle()
    }

    @Test fun rowShowsTheVersionAndWhatIsWaiting() {
        show(available)
        rule.onNodeWithText(UpdateCopy.ROW).performScrollTo()
        rule.onNodeWithText("0.6.0").assertExists()
        rule.onNodeWithText("Version 0.7.1 available").assertExists()
    }

    @Test fun sheetShowsNotesAndUpdates() {
        var updates = 0
        show(available, UpdateActions(update = { updates++ }))
        openSheet()
        rule.onNodeWithText("Orbit Health 0.7.1").assertExists()
        rule.onNodeWithText("A calmer sleep chart").assertExists()
        rule.onNodeWithText("Fixes for pairing").assertExists()
        rule.onNodeWithText("You have 0.6.0. Checked 2 hr ago.").assertExists()
        rule.onNodeWithText(UpdateCopy.AUTO_CAPTION).assertExists()
        rule.onNodeWithText(UpdateCopy.UPDATE).performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, updates)
    }

    @Test fun debugBuildsExplainAndOfferNoUpdateButton() {
        var checks = 0
        show(available.copy(installsHere = false), UpdateActions(check = { checks++ }))
        rule.onNodeWithText("Version 0.7.1 available · ${UpdateCopy.RELEASE_ONLY}").performScrollTo()
        openSheet()
        rule.onNodeWithText(UpdateCopy.RELEASE_ONLY_NOTE).assertExists()
        rule.onAllNodesWithText(UpdateCopy.UPDATE).assertCountEquals(0)
        rule.onNodeWithText(UpdateCopy.CHECK).performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, checks)
    }

    @Test fun withoutPermissionTheSheetLeadsToSettings() {
        var opened = 0
        show(available.copy(phase = UpdatePhase.NeedsPermission(release)), UpdateActions(openInstallPermission = { opened++ }))
        openSheet()
        rule.onNodeWithText(UpdateCopy.PERMISSION_NOTE).assertExists()
        rule.onNodeWithText(UpdateCopy.OPEN_SETTINGS).performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, opened)
    }

    @Test fun theAutomaticCheckCanBeTurnedOff() {
        var set: Boolean? = null
        show(available, UpdateActions(setAutoCheck = { set = it }))
        openSheet()
        rule.onNodeWithText(UpdateCopy.AUTO).performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(false, set)
    }

    @Test fun statusWords() {
        val s = UpdateUiState("0.6.0")
        assertEquals("Version 0.6.0", UpdateCopy.status(s))
        assertEquals(UpdateCopy.UP_TO_DATE, UpdateCopy.status(s.copy(lastCheckedAt = now)))
        assertEquals(UpdateCopy.RELEASE_ONLY, UpdateCopy.status(s.copy(installsHere = false)))
        assertEquals("Downloading 42%", UpdateCopy.status(s.copy(phase = UpdatePhase.Downloading(release, 0.42f))))
        assertEquals("Couldn't reach GitHub.", UpdateCopy.status(s.copy(phase = UpdatePhase.Failed("Couldn't reach GitHub."))))
        assertEquals("You're up to date", UpdateCopy.title(s.copy(phase = UpdatePhase.UpToDate)))
    }

    @Test fun releaseNotesReadAsPlainBlocks() {
        assertEquals(
            listOf(
                NoteBlock.Heading("What's new"), NoteBlock.Bullet("A calmer sleep chart"), NoteBlock.Bullet("Fixes for pairing"),
                NoteBlock.Paragraph("Thanks to everyone who tested this build."),
            ),
            ReleaseNotes.blocks(release.notes + "\n\nThanks to everyone\nwho tested `this` build."),
        )
    }
}
