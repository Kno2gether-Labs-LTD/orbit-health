package dev.avijit.orbithealth.debug

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import dev.avijit.orbithealth.ble.FoundRing
import dev.avijit.orbithealth.ring.DeviceInfo
import dev.avijit.orbithealth.ring.HistoryBatch
import dev.avijit.orbithealth.ring.MeasureEvent
import dev.avijit.orbithealth.ring.MeasureFailure
import dev.avijit.orbithealth.ring.MeasureKind
import dev.avijit.orbithealth.ring.MonitoringConfig
import dev.avijit.orbithealth.ring.RingCapabilities
import dev.avijit.orbithealth.prefs.PairedRing
import dev.avijit.orbithealth.ycbt.YcbtFamily
import dev.avijit.orbithealth.ring.RingDriver
import dev.avijit.orbithealth.ring.UserProfile
import dev.avijit.orbithealth.sync.RingConnection
import dev.avijit.orbithealth.ui.onboarding.OnboardingDeps
import dev.avijit.orbithealth.ui.onboarding.OnboardingStore
import dev.avijit.orbithealth.ui.onboarding.OnboardingViewModel
import dev.avijit.orbithealth.ui.onboarding.PairScreen
import dev.avijit.orbithealth.ui.onboarding.ProfileScreen
import dev.avijit.orbithealth.ui.onboarding.WelcomeScreen
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf

/**
 * Debug-only onboarding screens over sample data (no ring, nothing saved), for fidelity screenshots:
 * `adb shell am start -n dev.avijit.orbithealth/.debug.GalleryActivity --es onboarding welcome|scanning|pair|profile|profile-error`.
 */
@Composable
fun OnboardingPreview(screen: String) {
    val scope = rememberCoroutineScope()
    val vm = remember(screen) { OnboardingViewModel(sampleDeps(scanForever = screen == "scanning"), scope) }
    LaunchedEffect(screen) {
        when (screen) {
            "pair" -> { vm.startScan(); vm.select("C3:1A:00:00:A1:B2") }
            "profile-error" -> { vm.setAge("9"); vm.setHeight("250"); vm.finish() }
        }
        if (screen == "scanning") vm.startScan()
    }
    when (screen) {
        "welcome" -> WelcomeScreen(bridgeMode = false, onReady = {})
        "scanning", "pair" -> PairScreen(vm, bridgeMode = true, onProfile = {}, onBack = {})
        else -> ProfileScreen(vm, onDone = {})
    }
}

private fun sampleDeps(scanForever: Boolean) = OnboardingDeps(
    scan = {
        flow {
            if (!scanForever) emit(FoundRing("TK5 A1B2", "C3:1A:00:00:A1:B2", -62, YcbtFamily.ID, "TK5", YcbtFamily.NAME_CONFIDENCE))
            awaitCancellation()
        }
    },
    bridgeMode = { false },
    connect = { RingConnection(SampleDriver, MutableStateFlow(true), allowsClear = false) {} },
    store = object : OnboardingStore {
        override suspend fun saveProfile(profile: UserProfile) = Unit
        override suspend fun saveRing(ring: PairedRing) = Unit
        override suspend fun batteryPromptAsked() = true
        override suspend fun markBatteryPromptAsked() = Unit
    },
    startSync = {},
    batteryExempt = { true },
)

private object SampleDriver : RingDriver {
    override suspend fun handshake(profile: UserProfile) =
        DeviceInfo(1, "1.2.6", 54) to RingCapabilities(emptySet(), sleep = true, workouts = true, realtime = true)
    override suspend fun syncHistory(): HistoryBatch = HistoryBatch(emptyList(), emptyList(), emptyList())
    override suspend fun clearSyncedHistory(batch: HistoryBatch) = Unit
    override fun liveHeartRate(): Flow<Int> = emptyFlow()
    override fun measure(kind: MeasureKind): Flow<MeasureEvent> = flowOf(MeasureEvent.Failed(MeasureFailure.UNSUPPORTED))
    override suspend fun readMonitoring(): MonitoringConfig? = null
    override suspend fun applyMonitoring(config: MonitoringConfig) = MonitoringConfig.EMPTY
}
