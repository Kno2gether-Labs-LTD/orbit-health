package dev.avijit.orbithealth.ui.today

import dev.avijit.orbithealth.insights.EstimateStatus
import dev.avijit.orbithealth.insights.SleepEstimate
import dev.avijit.orbithealth.insights.SleepKind
import dev.avijit.orbithealth.prefs.SettingsSnapshot
import dev.avijit.orbithealth.sync.SyncState
import dev.avijit.orbithealth.ui.sleep.EstimateAnswer
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Today's view model with estimates: detection on open, the suggestion card, and the answer flowing back. */
@OptIn(ExperimentalCoroutinesApi::class)
class TodayEstimatesTest {
    private val zone: ZoneId = ZoneOffset.UTC
    private val now = Instant.parse("2026-11-07T12:00:00Z")
    private val start = Instant.parse("2026-11-07T03:10:00Z")
    private val end = Instant.parse("2026-11-07T10:40:00Z")
    private val estimates = MutableStateFlow(
        listOf(SleepEstimate(1, SleepKind.NIGHT, EstimateStatus.SUGGESTED, start, end, start, end, 54, 93, 0.9)),
    )
    private var detections = 0

    private fun TestScope.vm(): TodayViewModel {
        val d = UnconfinedTestDispatcher(testScheduler)
        val deps = TodayDeps(
            samples = { _, _, _ -> flowOf(emptyList()) },
            sleepSessions = { _, _ -> flowOf(emptyList()) },
            settings = MutableStateFlow(SettingsSnapshot(sleepGoalMin = 450)),
            syncState = MutableStateFlow(SyncState.Idle),
            lastSyncedAt = MutableStateFlow(null),
            vendorInstalled = MutableStateFlow(false),
            bannerDismissedAt = MutableStateFlow(null),
            dismissBanner = {},
            refresh = {},
            now = MutableStateFlow(now),
            clock = Clock.fixed(now, zone),
            zone = zone,
            compute = d,
            estimates = { from, to -> estimates.map { l -> l.filter { it.end in from..to } } },
            answerEstimate = { a ->
                estimates.value = estimates.value.map {
                    if (it.id != a.id) it
                    else when (a) {
                        is EstimateAnswer.Confirm -> it.copy(status = EstimateStatus.CONFIRMED, start = a.start ?: it.start, end = a.end ?: it.end, decidedAt = now)
                        is EstimateAnswer.Dismiss -> it.copy(status = EstimateStatus.DISMISSED, decidedAt = now)
                    }
                }
            },
            detectEstimates = { detections++ },
        )
        val scope = CoroutineScope(backgroundScope.coroutineContext + d)
        return TodayViewModel(deps, scope).also { vm -> scope.launch { vm.state.collect {} } }
    }

    @Test fun opensWithDetectionAndAsks() = runTest {
        val vm = vm()
        assertEquals(1, detections)
        assertEquals("Looks like you slept 3:10–10:40. Right?", vm.state.value.nightSuggestion?.headline)
        assertNull(vm.state.value.lastNight)
    }

    @Test fun yesTurnsTheSuggestionIntoLastNight() = runTest {
        val vm = vm()
        vm.answer(EstimateAnswer.Confirm(1))
        val st = vm.state.value
        assertNull(st.nightSuggestion)
        assertTrue(st.lastNightEstimated)
        assertEquals(start, st.lastNight?.start)
        assertEquals(80, st.sleep?.value)
    }

    @Test fun noClearsItForGood() = runTest {
        val vm = vm()
        vm.answer(EstimateAnswer.Dismiss(1))
        assertNull(vm.state.value.nightSuggestion)
        assertNull(vm.state.value.lastNight)
    }
}
