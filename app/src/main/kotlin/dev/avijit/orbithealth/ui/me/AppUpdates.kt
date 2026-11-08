package dev.avijit.orbithealth.ui.me

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.avijit.orbithealth.ui.components.GhostButton
import dev.avijit.orbithealth.ui.components.ListCard
import dev.avijit.orbithealth.ui.components.ListRow
import dev.avijit.orbithealth.ui.components.OrbitSheet
import dev.avijit.orbithealth.ui.components.OrbitToggle
import dev.avijit.orbithealth.ui.components.TintedCard
import dev.avijit.orbithealth.ui.components.TintedCardDefaults
import dev.avijit.orbithealth.ui.components.WarmButton
import dev.avijit.orbithealth.ui.theme.OrbitPalette
import dev.avijit.orbithealth.ui.theme.OrbitTypography
import dev.avijit.orbithealth.update.UpdatePhase
import dev.avijit.orbithealth.update.UpdateUiState
import java.time.Instant

/** The words of App updates (row and sheet). Pure, so tests and previews share them. */
object UpdateCopy {
    const val ROW = "App updates"
    const val UP_TO_DATE = "Up to date"
    const val CHECKING = "Checking…"
    const val VERIFYING = "Checking the download…"
    const val INSTALLING = "Waiting for Android to install…"
    const val NEEDS_PERMISSION = "Allow Orbit Health to install updates"
    const val RELEASE_ONLY = "Updates install on the release build only"
    const val RELEASE_ONLY_NOTE = "This is a debug build, signed with a developer key, so Android won't put a release " +
        "on top of it. You can still see what's new."
    const val AUTO = "Check for updates automatically"
    const val AUTO_CAPTION = "Once a day Orbit Health asks GitHub whether a new version is out. It sends only the app's " +
        "version, nothing about you or your health."
    const val CHECK = "Check for updates"
    const val UPDATE = "Update"
    const val OPEN_SETTINGS = "Allow in Settings"
    const val PERMISSION_NOTE = "Android asks once before an app can install its own updates. Allow it for Orbit " +
        "Health, come back, and tap Update."
    const val SAFETY_NOTE = "The download is checked against its published checksum and Orbit Health's signature " +
        "before Android asks you to install it."

    /** The row's caption: where updates stand, in a few words. */
    fun status(s: UpdateUiState): String = when (val p = s.phase) {
        UpdatePhase.Idle -> if (!s.installsHere) RELEASE_ONLY else s.lastCheckedAt?.let { UP_TO_DATE } ?: "Version ${s.currentVersion}"
        UpdatePhase.Checking -> CHECKING
        UpdatePhase.UpToDate -> if (s.installsHere) UP_TO_DATE else "$UP_TO_DATE · $RELEASE_ONLY"
        is UpdatePhase.Available -> "Version ${p.version} available" + if (s.installsHere) "" else " · $RELEASE_ONLY"
        is UpdatePhase.Downloading -> p.fraction?.let { "Downloading ${(it * 100).toInt()}%" } ?: "Downloading…"
        is UpdatePhase.Verifying -> VERIFYING
        is UpdatePhase.Installing -> INSTALLING
        is UpdatePhase.NeedsPermission -> NEEDS_PERMISSION
        is UpdatePhase.Failed -> p.message
    }

    /** The sheet's title. */
    fun title(s: UpdateUiState): String = when (val p = s.phase) {
        UpdatePhase.UpToDate -> "You're up to date"
        is UpdatePhase.Available -> "Orbit Health ${p.version}"
        else -> s.release?.let { "Orbit Health ${it.version}" } ?: "Orbit Health ${s.currentVersion}"
    }

    /** "Checked 2 hr ago", or null before the first check. */
    fun lastChecked(at: Instant?, now: Instant): String? = at?.let { "Checked ${MeMath.ago(it, now)}" }
}

/** A block of release notes, from the Markdown GitHub keeps. */
sealed interface NoteBlock {
    data class Heading(val text: String) : NoteBlock
    data class Bullet(val text: String) : NoteBlock
    data class Paragraph(val text: String) : NoteBlock
}

/** Just enough Markdown for release notes: headings, bullets and paragraphs, with emphasis, code and links as text. */
object ReleaseNotes {
    private val LINK = Regex("""\[([^\]]+)]\([^)]*\)""")
    private val EMPHASIS = Regex("""(\*\*|__|\*|_|`)""")

    fun blocks(markdown: String): List<NoteBlock> {
        val out = mutableListOf<NoteBlock>()
        val para = StringBuilder()
        fun flush() {
            if (para.isNotBlank()) out += NoteBlock.Paragraph(para.toString().trim())
            para.clear()
        }
        for (raw in markdown.lines()) {
            val line = raw.trim()
            when {
                line.isEmpty() -> flush()
                line.startsWith("#") -> { flush(); clean(line.trimStart('#')).takeIf { it.isNotEmpty() }?.let { out += NoteBlock.Heading(it) } }
                line.startsWith("- ") || line.startsWith("* ") || line.startsWith("+ ") -> { flush(); out += NoteBlock.Bullet(clean(line.drop(2))) }
                else -> { if (para.isNotEmpty()) para.append(' '); para.append(clean(line)) }
            }
        }
        flush()
        return out
    }

    private fun clean(s: String) = EMPHASIS.replace(LINK.replace(s) { it.groupValues[1] }, "").trim()
}

/** What App updates can do from Me. */
class UpdateActions(
    val check: () -> Unit = {},
    val update: () -> Unit = {},
    val setAutoCheck: (Boolean) -> Unit = {},
    val openInstallPermission: () -> Unit = {},
)

/** Me's "App updates" sheet: the version, what's new, one clear action, and the automatic-check switch. */
@Composable
internal fun AppUpdatesSheet(state: UpdateUiState, actions: UpdateActions, now: Instant, onDismiss: () -> Unit) {
    val phase = state.phase
    OrbitSheet(title = UpdateCopy.title(state), kicker = UpdateCopy.ROW, onDismiss = onDismiss) {
        Text(
            buildString {
                append("You have ${state.currentVersion}.")
                UpdateCopy.lastChecked(state.lastCheckedAt, now)?.let { append(" $it.") }
            },
            style = OrbitTypography.Meta.copy(color = OrbitPalette.Ink2),
        )
        Spacer(Modifier.height(12.dp))
        val line = when {
            !state.installsHere -> UpdateCopy.RELEASE_ONLY_NOTE
            phase is UpdatePhase.NeedsPermission -> UpdateCopy.PERMISSION_NOTE
            phase is UpdatePhase.Failed -> phase.message
            phase is UpdatePhase.Available || phase is UpdatePhase.Downloading || phase is UpdatePhase.Verifying -> UpdateCopy.SAFETY_NOTE
            else -> null
        }
        line?.let {
            Text(
                it, style = OrbitTypography.Lead.copy(color = if (phase is UpdatePhase.Failed) OrbitPalette.Attention else OrbitTypography.Lead.color),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            Spacer(Modifier.height(16.dp))
        }
        state.release?.notes?.let(ReleaseNotes::blocks)?.takeIf { it.isNotEmpty() }?.let { blocks ->
            ReleaseNotesCard(blocks)
            Spacer(Modifier.height(20.dp))
        }
        UpdateButtons(state, actions)
        Spacer(Modifier.height(20.dp))
        ListCard {
            ListRow(
                UpdateCopy.AUTO, first = true, caption = UpdateCopy.AUTO_CAPTION,
                onClick = { actions.setAutoCheck(!state.autoCheck) },
                trailing = { OrbitToggle(state.autoCheck, onCheckedChange = actions.setAutoCheck) },
            )
        }
    }
}

@Composable
private fun UpdateButtons(state: UpdateUiState, actions: UpdateActions) {
    val phase = state.phase
    val wide = Modifier.fillMaxWidth()
    when {
        state.installsHere && phase is UpdatePhase.NeedsPermission -> {
            WarmButton(UpdateCopy.OPEN_SETTINGS, actions.openInstallPermission, wide)
            Spacer(Modifier.height(10.dp))
            GhostButton(UpdateCopy.UPDATE, actions.update, wide)
        }
        state.installsHere && (phase is UpdatePhase.Available || (phase is UpdatePhase.Failed && phase.release != null)) ->
            WarmButton(UpdateCopy.UPDATE, actions.update, wide)
        state.installsHere && (phase is UpdatePhase.Downloading || phase is UpdatePhase.Verifying || phase is UpdatePhase.Installing) ->
            WarmButton(UpdateCopy.UPDATE, {}, wide, busy = true, busyText = UpdateCopy.status(state))
        phase is UpdatePhase.Checking -> GhostButton(UpdateCopy.CHECKING, {}, wide, enabled = false)
        else -> GhostButton(UpdateCopy.CHECK, actions.check, wide)
    }
}

/** What's new, in a quiet neutral card: headings as eyebrows, bullets with a small warm dot. */
@Composable
private fun ReleaseNotesCard(blocks: List<NoteBlock>) {
    TintedCard(
        OrbitPalette.Ink, Modifier.fillMaxWidth(), amount = TintedCardDefaults.NeutralAmount,
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            blocks.forEachIndexed { i, b ->
                when (b) {
                    is NoteBlock.Heading -> Text(
                        b.text, style = OrbitTypography.Eyebrow, modifier = Modifier.padding(top = if (i == 0) 0.dp else 6.dp),
                    )
                    is NoteBlock.Bullet -> Row {
                        Text("•", style = OrbitTypography.Body.copy(color = OrbitPalette.Good))
                        Spacer(Modifier.width(10.dp))
                        Text(b.text, style = OrbitTypography.Body)
                    }
                    is NoteBlock.Paragraph -> Text(b.text, style = OrbitTypography.Body.copy(color = OrbitPalette.Ink2))
                }
            }
        }
    }
}
