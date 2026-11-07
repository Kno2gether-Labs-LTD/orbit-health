package dev.avijit.orbithealth.sync

import java.time.Instant

/** EXCLUSIVE: Orbit Health owns the ring and clears history it has stored. COEXISTENCE: read-only, never clears. */
enum class SyncMode { EXCLUSIVE, COEXISTENCE }

/** Which transport the app is talking to the ring through. */
enum class TransportKind { BLE, DEBUG_BRIDGE }

/**
 * The debug Mac bridge always forces COEXISTENCE (never clear the real ring into a throwaway emulator
 * DB); otherwise the vendor app being installed forces COEXISTENCE.
 */
fun syncModeFor(kind: TransportKind, vendorAppInstalled: Boolean): SyncMode = when {
    kind == TransportKind.DEBUG_BRIDGE -> SyncMode.COEXISTENCE
    vendorAppInstalled -> SyncMode.COEXISTENCE
    else -> SyncMode.EXCLUSIVE
}

sealed interface SyncState {
    data object Idle : SyncState
    data object Connecting : SyncState
    data object Syncing : SyncState
    data class Synced(val at: Instant) : SyncState
    data class Failed(val reason: String) : SyncState
    /** The ring could not be reached while the vendor app is installed (it probably holds the link). */
    data object RingBusy : SyncState
}
