package dev.avijit.orbithealth.ui.agent

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.avijit.orbithealth.ring.Metric
import dev.avijit.orbithealth.server.ServerInfo
import dev.avijit.orbithealth.sync.Scope
import dev.avijit.orbithealth.ui.ask.AskChart
import dev.avijit.orbithealth.ui.ask.AskConnection
import dev.avijit.orbithealth.ui.ask.AskContent
import dev.avijit.orbithealth.ui.ask.AskMessage
import dev.avijit.orbithealth.ui.ask.AskNotPairedTitle
import dev.avijit.orbithealth.ui.ask.AskProblem
import dev.avijit.orbithealth.ui.ask.AskUiState
import dev.avijit.orbithealth.ui.sky.LocalSkyForceFallback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Agent & sync and Ask layouts render their states and route taps to the right actions. */
@Config(qualifiers = "w390dp-h844dp-xxhdpi")
@RunWith(RobolectricTestRunner::class)
class AgentAskContentTest {
    @get:Rule val rule = createComposeRule()

    private val home = ServerInfo("https://orbit.home.lan:8443", "Home Mac", "ab".repeat(32), "d1", Scope.entries.toSet())

    @Test fun agentSyncNotPairedOffersScanAndNoCloudOption() {
        var scans = 0
        rule.setContent {
            CompositionLocalProvider(LocalSkyForceFallback provides true) {
                AgentSyncContent(AgentSyncUiState(loaded = true), AgentSyncActions(scan = { scans++ }))
            }
        }
        rule.onNodeWithText("On this phone").performScrollTo()
        rule.onNodeWithText("On-device agent coming soon", substring = true).performScrollTo()
        rule.onNodeWithText("My Orbit Server").performScrollTo()
        rule.onAllNodesWithText("Orbit Cloud", substring = true).assertCountEquals(0)
        rule.onNodeWithText("Scan pairing code").performScrollTo().performClick()
        assertEquals(1, scans)
    }

    @Test fun agentSyncPairedShowsStatusScopesAndTheServerReportedLog() {
        val toggles = mutableListOf<Pair<Scope, Boolean>>()
        rule.setContent {
            CompositionLocalProvider(LocalSkyForceFallback provides true) {
                AgentSyncContent(
                    AgentSyncUiState(
                        loaded = true, server = home, scopes = Scope.entries.toSet() - Scope.STRESS, lastSync = "2 min ago",
                        lastSyncCaption = "1,204 readings sent",
                        accessLog = AccessLogUi.Loaded(listOf(AccessRow("claude-code", "get metric · HEART_RATE avg · 24 rows", "3 min ago"))),
                    ),
                    AgentSyncActions(toggleScope = { s, on -> toggles += s to on }),
                )
            }
        }
        rule.onAllNodesWithText("orbit.home.lan:8443").assertCountEquals(2) // sky card and the Address row
        rule.onNodeWithText("1,204 readings sent").performScrollTo()
        rule.onNodeWithText("Stress").performScrollTo().performClick()
        assertEquals(listOf(Scope.STRESS to true), toggles)
        rule.onNodeWithText("Reported by your server").performScrollTo()
        rule.onNodeWithText("claude-code").performScrollTo()
    }

    @Test fun askShowsTheAnswerProvenanceAndChart() {
        rule.setContent {
            CompositionLocalProvider(LocalSkyForceFallback provides true) {
                AskContent(
                    AskUiState(
                        connection = AskConnection.PAIRED, serverName = "Home Mac",
                        messages = listOf(
                            AskMessage.User(0, "Am I recovering well?"),
                            AskMessage.Agent(
                                1, "Your HRV has been steady.", "Based on: HRV, heart rate · 3–5 Nov",
                                AskChart(Metric.HRV, "HRV on this phone · 3–5 Nov", listOf(40f, 44f, 47f)),
                            ),
                            AskMessage.Problem(2, AskProblem.NO_MODEL, "And today?"),
                        ),
                    ),
                    onSend = {}, onRetry = {}, onSetup = {},
                )
            }
        }
        rule.onNodeWithText("Based on: HRV, heart rate · 3–5 Nov").assertExists()
        rule.onNodeWithText("HRV on this phone · 3–5 Nov").assertExists()
        rule.onNodeWithText("Choose a model in your Orbit Server dashboard").assertExists()
    }

    @Test fun askNotPairedPointsToAgentAndSync() {
        var setup = 0
        rule.setContent {
            CompositionLocalProvider(LocalSkyForceFallback provides true) {
                AskContent(AskUiState(connection = AskConnection.NOT_PAIRED), onSend = {}, onRetry = {}, onSetup = { setup++ })
            }
        }
        rule.onNode(androidx.compose.ui.test.hasContentDescription(AskNotPairedTitle)).assertExists()
        rule.onNodeWithText("Set up Agent & sync").performScrollTo().performClick()
        assertEquals(1, setup)
    }

    @Test fun askSuggestionsSendTheQuestion() {
        val sent = mutableListOf<String>()
        rule.setContent {
            CompositionLocalProvider(LocalSkyForceFallback provides true) {
                AskContent(AskUiState(connection = AskConnection.PAIRED, serverName = "Home Mac"), onSend = { sent += it }, onRetry = {}, onSetup = {})
            }
        }
        rule.onNodeWithText("How did I sleep last night?").performClick()
        assertTrue(sent == listOf("How did I sleep last night?"))
    }
}
