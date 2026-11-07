package dev.avijit.orbithealth.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.avijit.orbithealth.ble.FoundRing
import dev.avijit.orbithealth.prefs.PairedRing
import dev.avijit.orbithealth.prefs.Settings
import dev.avijit.orbithealth.ring.DeviceInfo
import dev.avijit.orbithealth.ring.RingCapabilities
import dev.avijit.orbithealth.ring.RingRegistry
import dev.avijit.orbithealth.ring.Sex
import dev.avijit.orbithealth.ring.UserProfile
import dev.avijit.orbithealth.sync.RingConnection
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/** What onboarding persists. Backed by [Settings] in the app; faked in tests. */
interface OnboardingStore {
    /** What to call the user (Today's greeting); null when left blank. */
    suspend fun saveName(name: String?) {}
    suspend fun saveProfile(profile: UserProfile)
    /** The paired ring: family, address and model, saved together. */
    suspend fun saveRing(ring: PairedRing)
    /** The paired ring's scan name and handshake info, shown on Me. */
    suspend fun saveRingInfo(name: String?, info: DeviceInfo) {}
    /** The paired ring's capabilities from the final handshake (cached for offline screens). */
    suspend fun saveCapabilities(caps: RingCapabilities) {}
    suspend fun batteryPromptAsked(): Boolean
    suspend fun markBatteryPromptAsked()
}

/** Everything onboarding talks to, so the flow can run against fakes. */
class OnboardingDeps(
    /** Nearby rings, supported or not; re-emits an address when its RSSI changes (see RingScanner). */
    val scan: () -> Flow<FoundRing>,
    /** Debug builds with the Mac bridge selected: offer one bridge entry instead of scanning. */
    val bridgeMode: suspend () -> Boolean,
    /** Opens a link to [ring] (its family picks driver and GATT profile) over the transport the build uses. */
    val connect: suspend (ring: PairedRing) -> RingConnection,
    val store: OnboardingStore,
    val startSync: () -> Unit,
    val batteryExempt: () -> Boolean,
    /** Where non-fatal problems are logged (Logcat in the app; a list in tests). */
    val logError: (String, Throwable) -> Unit = { _, _ -> },
    /** Family of the debug bridge entry: the Mac bridge relays the TK5, a YCBT ring. */
    val bridgeFamilyId: String = PairedRing.LEGACY_FAMILY_ID,
)

/**
 * One row in the pair list. [rssi] is null for the debug bridge entry. [familyId] is null for a ring no registered
 * family supports: it is listed as "Not supported yet" and can't be selected or connected.
 */
data class RingItem(
    val name: String,
    val address: String,
    val rssi: Int?,
    val familyId: String? = null,
    val modelName: String? = null,
    val confidence: Int = 0,
) {
    val supported: Boolean get() = familyId != null

    /** Supported and matched confidently enough to be picked without a tap. */
    val autoPickable: Boolean get() = supported && confidence >= RingRegistry.AUTO_PICK_CONFIDENCE

    /** The ring to pair, or null when unsupported. */
    fun toPaired(): PairedRing? = familyId?.let { PairedRing(it, address, modelName) }
}

/** Profile fields as typed (text, so a half-typed or out-of-range value can be shown with its message). */
data class ProfileInput(val height: String, val weight: String, val age: String, val sex: Sex) {
    companion object {
        val Default = Settings.DEFAULT_PROFILE.let { ProfileInput(it.heightCm.toString(), it.weightKg.toString(), it.age.toString(), it.sex) }
    }
}

/** Inline messages per field; null = fine. */
data class ProfileErrors(val height: String? = null, val weight: String? = null, val age: String? = null) {
    val isValid: Boolean get() = height == null && weight == null && age == null
}

object ProfileRules {
    val HeightCm = 100..230
    val WeightKg = 30..250
    val Age = 13..100

    fun heightError(text: String) = fieldError(text, HeightCm, "Enter your height", "Height must be 100–230 cm")
    fun weightError(text: String) = fieldError(text, WeightKg, "Enter your weight", "Weight must be 30–250 kg")
    fun ageError(text: String) = fieldError(text, Age, "Enter your age", "Age must be 13–100")

    fun validate(input: ProfileInput) = ProfileErrors(heightError(input.height), weightError(input.weight), ageError(input.age))

    /** The profile to send to the ring, or null while any field is invalid. */
    fun profileOf(input: ProfileInput): UserProfile? {
        if (!validate(input).isValid) return null
        return UserProfile(input.height.trim().toInt(), input.weight.trim().toInt(), input.sex, input.age.trim().toInt())
    }

    private fun fieldError(text: String, range: IntRange, empty: String, outOfRange: String): String? {
        val t = text.trim()
        if (t.isEmpty()) return empty
        val v = t.toIntOrNull() ?: return outOfRange
        return if (v in range) null else outOfRange
    }
}

/** 0–4 signal bars for an RSSI in dBm. */
fun signalBars(rssi: Int): Int = when {
    rssi >= -60 -> 4
    rssi >= -70 -> 3
    rssi >= -80 -> 2
    rssi >= -90 -> 1
    else -> 0
}

data class OnboardingState(
    /** Found rings, supported first, then strongest signal first, one row per address. */
    val rings: List<RingItem> = emptyList(),
    val scanning: Boolean = false,
    val scanError: String? = null,
    val selected: String? = null,
    val connecting: String? = null,
    /** Address whose handshake succeeded; [device] holds its battery and firmware. */
    val connected: String? = null,
    val device: DeviceInfo? = null,
    val pairError: String? = null,
    /** One-shot: the Pair screen should move on to Profile, then call [OnboardingViewModel.onProceededToProfile]. */
    val proceedToProfile: Boolean = false,
    val profile: ProfileInput = ProfileInput.Default,
    /** "What should we call you?" as typed; optional. */
    val name: String = "",
    val profileErrors: ProfileErrors = ProfileErrors(),
    val finishing: Boolean = false,
    val finishError: String? = null,
    /** One-shot: show the system battery-optimisation prompt, then call [OnboardingViewModel.onBatteryPromptHandled]. */
    val askBatteryExemption: Boolean = false,
    /** Paired and saved: go to Today and clear the back stack. */
    val completed: Boolean = false,
)

/**
 * Pair → Profile → final handshake. Shared by the Pair and Profile screens (activity scoped).
 *
 * The ring address is saved only after the profile is saved and a handshake carrying it succeeds, so an interrupted
 * onboarding never leaves a half-paired app; the link is then closed and [OnboardingDeps.startSync] takes over.
 */
class OnboardingViewModel(private val deps: OnboardingDeps, scope: CoroutineScope? = null) : ViewModel() {
    private val scope: CoroutineScope = scope ?: viewModelScope
    private val _state = MutableStateFlow(OnboardingState())
    val state: StateFlow<OnboardingState> = _state.asStateFlow()

    private var scanJob: Job? = null
    private var connectJob: Job? = null
    private val linkLock = Mutex()
    private var link: RingConnection? = null
    /** The ring last opened (kept after close, so finish() still knows it if the scan list dropped it). */
    @Volatile private var linkRing: PairedRing? = null

    // --- Pair ---

    fun startScan() {
        if (scanJob?.isActive == true) return
        scanJob = scope.launch {
            if (deps.bridgeMode()) {
                val bridge = RingItem(DEBUG_BRIDGE_NAME, DEBUG_BRIDGE_ADDRESS, null, deps.bridgeFamilyId, confidence = 100)
                _state.update { it.copy(rings = listOf(bridge), scanning = false, scanError = null) }
                return@launch
            }
            // A fresh scan starts from an empty list (rings from an earlier scan may be gone), keeping only the ring we
            // are linked to: a connected ring usually stops advertising, so the scan would never list it again.
            _state.update { s -> s.copy(scanning = true, scanError = null, rings = s.rings.filter { it.address == s.connected }) }
            val seen = LinkedHashMap<String, RingItem>()
            _state.value.rings.forEach { seen[it.address] = it }
            try {
                deps.scan().collect { r ->
                    seen[r.address] = RingItem(r.name.ifBlank { "Ring" }, r.address, r.rssi, r.familyId, r.modelName, r.confidence)
                    val sorted = seen.values.sortedWith(
                        compareByDescending<RingItem> { it.autoPickable }.thenByDescending { it.supported }
                            .thenByDescending { it.rssi ?: Int.MAX_VALUE },
                    )
                    _state.update { it.copy(rings = sorted) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(scanError = SCAN_FAILED) }
            } finally {
                _state.update { it.copy(scanning = false) }
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
    }

    /**
     * Connects to [address] and handshakes with the default profile to show its battery and firmware. Unsupported
     * rings are ignored: they are never connected to.
     */
    fun select(address: String) {
        if (_state.value.rings.none { it.address == address && it.supported }) return
        connectTo(address, proceed = false)
    }

    /**
     * The main button: continue with the connected ring, or connect the selected ring (else the strongest confidently
     * matched one) first. Weak matches (e.g. a heart-rate chest strap matched only by a shared service) are never
     * auto-picked; they connect only when tapped.
     */
    fun connectAndContinue() {
        val s = _state.value
        val target = s.selected ?: s.rings.firstOrNull { it.autoPickable }?.address ?: return
        if (s.connected == target && s.device != null) {
            _state.update { it.copy(proceedToProfile = true) }
        } else {
            connectTo(target, proceed = true)
        }
    }

    /**
     * Leaving Pair backwards: stop scanning and let go of the ring (an open link holds it exclusively, which blocks
     * the vendor app). Nothing has been saved at this point.
     */
    fun releaseRing() {
        stopScan()
        connectJob?.cancel()
        connectJob = null
        scope.launch { closeLink() } // under the lock, after the cancelled connect has let go of it
        _state.update {
            it.copy(selected = null, connecting = null, connected = null, device = null, pairError = null, proceedToProfile = false)
        }
    }

    fun onProceededToProfile() = _state.update { it.copy(proceedToProfile = false) }

    private fun connectTo(address: String, proceed: Boolean) {
        val s = _state.value
        if (s.finishing) return
        val ring = s.rings.firstOrNull { it.address == address }?.toPaired() ?: return // unsupported: never connect
        if (s.connecting == address) {
            if (proceed) pendingProceed = true
            return
        }
        connectJob?.cancel()
        pendingProceed = proceed
        _state.update {
            it.copy(
                selected = address, connecting = address, pairError = null,
                connected = it.connected.takeIf { c -> c == address }, device = it.device.takeIf { _ -> it.connected == address },
            )
        }
        connectJob = scope.launch {
            try {
                val info = timed { openLink(ring).driver.handshake(Settings.DEFAULT_PROFILE).first }
                _state.update { it.copy(connecting = null, connected = address, device = info, proceedToProfile = pendingProceed) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                closeLink()
                _state.update { it.copy(connecting = null, connected = null, device = null, pairError = CONNECT_FAILED) }
            }
        }
    }

    @Volatile private var pendingProceed = false

    // --- Profile ---

    fun setHeight(text: String) = edit(text) { p, t -> p.copy(height = t) }
    fun setWeight(text: String) = edit(text) { p, t -> p.copy(weight = t) }
    fun setAge(text: String) = edit(text) { p, t -> p.copy(age = t) }
    fun setName(raw: String) = _state.update { it.copy(name = raw.take(MAX_NAME)) }
    fun setSex(sex: Sex) = _state.update { it.copy(profile = it.profile.copy(sex = sex)) }

    fun stepHeight(delta: Int) = step(delta, ProfileRules.HeightCm, { it.height }, ::setHeight)
    fun stepWeight(delta: Int) = step(delta, ProfileRules.WeightKg, { it.weight }, ::setWeight)
    fun stepAge(delta: Int) = step(delta, ProfileRules.Age, { it.age }, ::setAge)

    private fun edit(raw: String, apply: (ProfileInput, String) -> ProfileInput) {
        val text = raw.filter(Char::isDigit).take(3)
        _state.update { s ->
            val profile = apply(s.profile, text)
            // Messages appear on Continue; while one is showing, it follows the edits (and clears once fixed).
            val all = ProfileRules.validate(profile)
            val shown = s.profileErrors
            val errors = ProfileErrors(
                height = all.height.takeIf { shown.height != null },
                weight = all.weight.takeIf { shown.weight != null },
                age = all.age.takeIf { shown.age != null },
            )
            s.copy(profile = profile, profileErrors = errors)
        }
    }

    private fun step(delta: Int, range: IntRange, get: (ProfileInput) -> String, set: (String) -> Unit) {
        val current = get(_state.value.profile).trim().toIntOrNull()
        val next = when {
            current == null -> if (delta > 0) range.first else range.last
            else -> (current + delta).coerceIn(range)
        }
        set(next.toString())
    }

    /** Validates, saves the profile, handshakes with it, then saves the address and starts sync. */
    fun finish() {
        val s = _state.value
        if (s.finishing || s.completed) return
        val errors = ProfileRules.validate(s.profile)
        _state.update { it.copy(profileErrors = errors, finishError = null) }
        val profile = ProfileRules.profileOf(s.profile) ?: return
        val address = s.connected ?: s.selected
        val ring = address?.let { a -> s.rings.firstOrNull { it.address == a }?.toPaired() ?: linkRing?.takeIf { it.address == a } }
        if (ring == null) {
            _state.update { it.copy(finishError = NO_RING) }
            return
        }
        connectJob?.cancel()
        _state.update { it.copy(finishing = true) }
        scope.launch {
            try {
                deps.store.saveName(s.name.trim().ifEmpty { null })
                deps.store.saveProfile(profile)
                val (info, caps) = timed { openLink(ring).driver.handshake(profile) }
                _state.update { it.copy(device = info) }
                closeLink()
                deps.store.saveRing(ring)
                deps.store.saveRingInfo(s.rings.firstOrNull { it.address == address }?.name, info)
                deps.store.saveCapabilities(caps)
                // The ring is paired now. If the service cannot start (e.g. a background-start restriction), carry on:
                // MainActivity.onStart starts it on the next app open.
                try {
                    deps.startSync()
                } catch (e: Exception) {
                    deps.logError("could not start sync service after pairing", e)
                }
                var ask = false
                if (!deps.store.batteryPromptAsked()) {
                    deps.store.markBatteryPromptAsked()
                    ask = !deps.batteryExempt()
                }
                _state.update { it.copy(finishing = false, askBatteryExemption = ask, completed = !ask) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                closeLink()
                _state.update { it.copy(finishing = false, finishError = FINISH_FAILED) }
            }
        }
    }

    fun onBatteryPromptHandled() = _state.update { it.copy(askBatteryExemption = false, completed = true) }

    // --- Link ---

    /** A ring that stops answering is a failure like any other, not a cancellation: map our own timeout to IOException. */
    private suspend fun <T> timed(block: suspend () -> T): T = try {
        withTimeout(LINK_TIMEOUT) { block() }
    } catch (e: TimeoutCancellationException) {
        throw IOException("ring did not answer within $LINK_TIMEOUT")
    }

    /** Reuses the open link to [ring] while it is up; otherwise (re)connects. */
    private suspend fun openLink(ring: PairedRing): RingConnection = linkLock.withLock {
        link?.let { if (linkRing == ring && it.connected.value) return it }
        link?.close()
        link = null
        val opened = deps.connect(ring)
        link = opened
        linkRing = ring
        opened
    }

    private suspend fun closeLink() = linkLock.withLock { closeLinkLocked() }

    private fun closeLinkLocked() {
        runCatching { link?.close() }
        link = null
    }

    override fun onCleared() {
        stopScan()
        closeLinkLocked()
    }

    companion object {
        const val DEBUG_BRIDGE_NAME = "Mac bridge (debug)"

        /**
         * Saved as the ring address when pairing through the Mac bridge (the bridge transport ignores the address).
         * A valid but unused MAC, so switching the bridge off later just fails to connect instead of crashing.
         */
        const val DEBUG_BRIDGE_ADDRESS = "00:00:00:00:00:00"

        private val LINK_TIMEOUT = 40.seconds
        const val SCAN_FAILED = "Scanning stopped. Check that Bluetooth is on, then try again."
        const val CONNECT_FAILED = "Couldn't connect. Keep the ring close and tap it again."
        const val FINISH_FAILED = "Couldn't reach your ring. Keep it close and try again."
        const val NO_RING = "Go back and pick your ring first."
        /** Long enough for any first name; the greeting has one line. */
        const val MAX_NAME = 40
    }
}
