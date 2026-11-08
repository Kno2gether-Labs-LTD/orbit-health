# Adding a ring

Orbit Health talks to rings through **ring families**. A family is one protocol, for example YCBT (the TK5). It lives in its own Gradle module and is registered once. Scanning, pairing, the BLE transport, sync, storage, the server push, the agent tools and the screens all go through the family's interfaces. So adding a ring means writing one module and adding one registry line. You don't need to change any screen.

This guide walks through adding a family from scratch. The YCBT family (`:ring-ycbt`) is the reference implementation, and the test-only `FakeFamily` (`ring-core/src/testFixtures`) is the smallest complete example.

Contents:
1. [Before you start](#1-before-you-start)
2. [Create the module](#2-create-the-module)
3. [Implement `RingFamily`](#3-implement-ringfamily)
4. [Implement `RingDriver`](#4-implement-ringdriver)
5. [Register the family](#5-register-the-family)
6. [Tests to write](#6-tests-to-write)
7. [Verify on a real ring](#7-verify-on-a-real-ring)
8. [Capabilities drive the UI](#8-capabilities-drive-the-ui)
9. [Premium rings without an open protocol](#9-premium-rings-without-an-open-protocol)
10. [Worked example: a Nordic-UART ring](#10-worked-example-a-nordic-uart-ring)
11. [Checklist](#11-checklist)

## 1. Before you start

You need the ring's protocol: GATT services and characteristics, command frames, reply frames, and how history is paged out. Good sources are a published spec, an open-source project you may read for byte layouts, or your own captures. **Never copy vendor code.** Read it only for byte layouts and write original code. `docs/protocol/ycbt-settings.md` shows the expected rigour: each command is marked **SDK** until it has been checked on a real ring, and then **verified (date)**.

The contract lives in `:ring-core` (package `dev.avijit.orbithealth.ring`, pure JVM, no Android):

| File | What it holds |
|---|---|
| `RingFamily.kt` | `RingFamily`, `RingMatch` |
| `RingDriver.kt` | `RingDriver`, `SyncFailed`, `MonitoringNotApplied`, `MeasureFailedException` |
| `RingTransport.kt` | `RingTransport`, `RingPacket` |
| `GattProfile.kt` | `GattProfile`, `GattChar` |
| `RingAdvertisement.kt` | `RingAdvertisement` (plus `sigUuid`) |
| `RingRegistry.kt` | `RingRegistry`, `RingIdentity`, `AUTO_PICK_CONFIDENCE` |
| `Capabilities.kt` | `RingCapabilities`, `MeasureSpec`, `MonitorSpec` |
| `Measure.kt` | `MeasureKind`, `MeasureEvent`, `MeasureFailure` |
| `Monitoring.kt` | `MonitorKind`, `MonitorSetting`, `MonitoringConfig` |
| `Records.kt` | `Metric`, `Sample`, `SampleSource`, `HistoryBatch`, `SleepSession`, `DeviceInfo`, `UserProfile` |

## 2. Create the module

Use a module named `:ring-<family>` with the package `dev.avijit.orbithealth.<family>`. Copy `ring-ycbt/build.gradle.kts`:

```kotlin
// ring-acme/build.gradle.kts
plugins {
    kotlin("jvm")
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":ring-core"))
    testImplementation(testFixtures(project(":ring-core"))) // FakeRingTransport, FakeFamily
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")            // only if you load JSON frame fixtures
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
```

Keep the module a plain `kotlin("jvm")` module, not an Android one. Drivers then stay testable on the JVM without Robolectric, and the app supplies the Android BLE transport.

Then wire it up:

```kotlin
// settings.gradle.kts
include(":ring-core", ":ring-ycbt", ":ring-acme", ":sync-protocol", ":server", ":app")

// app/build.gradle.kts, next to the ring-ycbt line
implementation(project(":ring-acme"))
```

Layout:

```
ring-acme/
  build.gradle.kts
  src/main/kotlin/dev/avijit/orbithealth/acme/
    AcmeFamily.kt      // RingFamily
    AcmeDriver.kt      // RingDriver
    Frames.kt          // encode/decode, CRC, …
    Decoders.kt        // reply → Sample / SleepSession
  src/test/kotlin/dev/avijit/orbithealth/acme/
  src/test/resources/fixtures/*.json
```

## 3. Implement `RingFamily`

```kotlin
interface RingFamily {
    val id: String
    val displayName: String
    val gattProfile: GattProfile
    val vendorAppPackages: Set<String>
    val vendorAppName: String? get() = null   // e.g. "SmartHealth"
    fun match(advertisement: RingAdvertisement): RingMatch?
    fun createDriver(transport: RingTransport): RingDriver
}
```

Use a Kotlin `object`, as `YcbtFamily` does.

### `id`

The `id` is stored with the paired ring (DataStore key `ring_family_id`). **Never change it once shipped.** If you do, every existing pairing points at a family this build doesn't have, and `openPairedRing` throws "This build doesn't support the paired ring". Use lower case with no spaces, for example `"acme"`.

### `displayName`

This is the human name of the family, for example `"Acme rings"`.

### `match()` and confidence

`match` gets a `RingAdvertisement`: `name`, `address`, `serviceUuids` (full 128-bit strings), `manufacturerData` (keyed by SIG company id) and `rssi`. Return `RingMatch(modelName, confidence)` if the ring is yours, or `null` if it isn't. Confidence must be between 0 and 100; `RingMatch` enforces that.

`RingRegistry.identify` picks the family with the highest confidence; registry order breaks ties. Pairing picks a ring by itself only when the confidence is at least **`RingRegistry.AUTO_PICK_CONFIDENCE` (50)**. Weaker matches are still listed, and the user can tap them.

| Evidence | Suggested confidence |
|---|---|
| Your own service UUID, or your company id plus a model byte in manufacturer data | 90–100 |
| A distinctive name prefix (YCBT: `"TK5"`, 90) | 80–90 |
| A shared vendor service (FEE7, Nordic UART) alone | 10–30, below 50 so it is never auto-picked |
| A generic SIG service every wearable has (Heart Rate 180D, Battery 180F) alone | don't match (return null) |

```kotlin
override fun match(advertisement: RingAdvertisement): RingMatch? = when {
    advertisement.manufacturerData[ACME_COMPANY_ID]?.firstOrNull() == MODEL_R1 -> RingMatch("Acme R1", 95)
    advertisement.name.orEmpty().startsWith("ACME") -> RingMatch(advertisement.name!!, 85)
    advertisement.hasService(NUS_SERVICE) -> RingMatch(advertisement.name ?: "Acme ring", 20)
    else -> null
}
```

`hasService` compares case-insensitively. `RingAdvertisement.sigUuid("fee7")` expands a 16-bit SIG UUID to its full form.

A ring that no family claims, but whose name looks like a ring (`RingRegistry.looksLikeRing`), is listed as "Not supported yet". The app never connects to it.

### `gattProfile`

```kotlin
data class GattChar(val service: String, val characteristic: String)   // lower-case 128-bit UUIDs
data class GattProfile(
    val writes: List<GattChar>,       // first = default write; empty = write-less ring
    val notifies: List<GattChar>,     // subscribed in order, across any services
    val writeWithResponse: Boolean,
    val mtu: Int = GattProfile.DEFAULT_MTU,   // 247, a hint
)
```

- UUIDs must be **lower case**, and `GattChar` checks this. Use `GattChar.sig("180f", "2a19")` for 16-bit SIG characteristics.
- **Several services:** list characteristics from any services. A ring with a command service and a separate "big data" service just lists both. The transport subscribes to every `notifies` entry in order, using indications when the characteristic offers them and notifications otherwise. Each packet arrives tagged with its `GattChar` (`RingTransport.packets`).
- **Notify-only and write-less rings:** a ring that only exposes standard services, such as Heart Rate 180D/2A37 and Battery 180F/2A19, has `writes = emptyList()`. `RingTransport.write(bytes)` then throws, while subscribe and `read(char)` still work.
- **Write type:** it applies to the whole profile. If your protocol mixes write types per characteristic, `writeWithResponse` would first have to move onto `GattChar`; that change belongs in ring-core.

YCBT, for comparison:

```kotlin
val COMMAND = GattChar(SERVICE, "be940001-7333-be46-b7ae-689e71722bd5")
val DATA = GattChar(SERVICE, "be940003-7333-be46-b7ae-689e71722bd5")
override val gattProfile = GattProfile(writes = listOf(COMMAND), notifies = listOf(COMMAND, DATA), writeWithResponse = true, mtu = 247)
```

### `vendorAppPackages` and the manifest `<queries>` line

These are the package names of the vendor's own phone app. While one of them is installed, Orbit Health runs in **COEXISTENCE**: it never clears ring history and lets go of the ring after each sync, so the vendor app can still connect. Use an empty set if the ring has no vendor app.

Each package also needs a line in `app/src/main/AndroidManifest.xml`. Without it, Android 11+ hides the package and it always looks uninstalled:

```xml
<queries>
    <package android:name="com.zhuoting.healthyucheng" />
    <package android:name="com.acme.ringapp" />   <!-- new -->
</queries>
```

Also set `vendorAppName` to the app's name as people know it (YCBT: `"SmartHealth"`). The Today banner ("SmartHealth detected") and Me's "Keep connected" caption use it. It defaults to null, and the copy then says "your ring's own app". `RingRegistry.vendorAppName(packageName)` finds the name for the installed package.

`RingRegistry.vendorAppPackagesFor(familyId)` returns the paired family's packages. With nothing paired, it returns every family's packages, which is the cautious choice. `CoexistenceDetector` checks only those packages.

### `createDriver()`

```kotlin
override fun createDriver(transport: RingTransport): RingDriver = AcmeDriver(transport)
```

The app calls it once per link. The transport is already connected and subscribed.

## 4. Implement `RingDriver`

```kotlin
interface RingDriver {
    suspend fun handshake(profile: UserProfile): Pair<DeviceInfo, RingCapabilities>
    suspend fun syncHistory(): HistoryBatch
    suspend fun clearSyncedHistory(batch: HistoryBatch)
    fun measure(kind: MeasureKind): Flow<MeasureEvent>
    suspend fun readMonitoring(): MonitoringConfig?
    suspend fun applyMonitoring(config: MonitoringConfig): MonitoringConfig
    // defaults, usually not overridden:
    fun measureContinuously(kind: MeasureKind): Flow<MeasureEvent>
    fun liveHeartRate(): Flow<Int>
}
```

### Talking to the transport

- `transport.packets` is **hot**. Subscribe before you write, for example with `async(start = CoroutineStart.UNDISPATCHED) { … }`, or with a session channel as `YcbtDriver.openSession` does. Otherwise a reply that arrives during the write is lost.
- `transport.incoming` gives the same packets without the tag, for single-channel protocols.
- `transport.write(bytes)` writes to the default characteristic, and `write(char, bytes)` to a specific one. `read(char)` reads once.
- `transport.connected` is a `StateFlow<Boolean>`. Use it to tell DISCONNECTED apart from RING_ERROR.
- Serialise access yourself. YCBT holds one `Mutex` per driver, so a monitoring apply queued during a measurement runs only after the measurement's stop frame.

### `handshake(profile)` returns `RingCapabilities`

Set the ring's clock, time zone and user profile if the protocol has them. Then return `DeviceInfo(deviceId, firmware, batteryPct)` and what the ring can do:

```kotlin
data class RingCapabilities(
    val history: Set<Metric>,
    val sleep: Boolean,
    val workouts: Boolean,
    val realtime: Boolean,
    val measure: Map<MeasureKind, MeasureSpec> = emptyMap(),
    val monitoring: Map<MonitorKind, MonitorSpec> = emptyMap(),
    val canClearHistory: Boolean = false,
)
data class MeasureSpec(val typicalSeconds: Int, val live: Boolean, val estimate: Boolean)
data class MonitorSpec(val intervalsMin: List<Int>, val canTurnOff: Boolean, val supportsWindow: Boolean)
```

- **Combine what the ring reports with family defaults.** YCBT reads the 0x0201 support bitmap and declares a measure or monitor kind only when its bit is set (`decodeCapabilities`, `YcbtDefaults`).
- **`measure`:** declare only kinds you have verified on a real ring.
  - `typicalSeconds` drives the countdown.
  - Set `live` if the ring streams interim values.
  - Set `estimate = true` for blood pressure and stress, or anything uncalibrated. The UI then labels it ESTIMATE.
- **`monitoring`:** `intervalsMin` is ascending, and the picker offers only these values. An **empty** list means "not really supported": the kind is hidden and never sent. `canTurnOff` controls whether a switch is shown. `supportsWindow` offers "Overnight only".
- **`canClearHistory` defaults to `false`, so a new family is non-destructive.** Opt in only when all of these hold:
  - your clear command is verified on a real ring;
  - it deletes only what was synced (per history type);
  - `clearSyncedHistory` implements the two-phase contract below.

  Until then the app never asks your driver to clear, even in EXCLUSIVE mode. Leaving history on the ring is always safe.

Capabilities are cached after every full handshake (`CapabilitiesCodec`, key `ring_capabilities_json`), so the screens work offline. The cache is dropped on re-pair.

### `syncHistory()`

This reads all history **without deleting anything** and returns a `HistoryBatch(samples, sleep, workouts)`:
- samples are tagged `SampleSource.HISTORY`;
- times are `Instant`s, so convert the ring's clock with the device's zone rules for each timestamp (see `RingTime.kt`);
- `Metric` semantics are fixed. STEPS, DISTANCE_M and CALORIES_KCAL are per-interval amounts, and STEPS_DAY_TOTAL is the running daily count. Map your ring onto these; don't invent new meanings.

Throw `SyncFailed(reason)` when the link drops or a transfer is malformed. Decode failures should be isolated per history type: one bad type must not lose the others.

### `clearSyncedHistory(batch)`: the two-phase safety contract

This is the only destructive call. It is two-phase:
1. `syncHistory()` returns a batch, and the app **persists it** (the Room transaction commits).
2. Only then does the app call `clearSyncedHistory(thatBatch)`, and only if:
   - the mode is EXCLUSIVE;
   - the link allows clearing (debug bridge links never do);
   - the latest full handshake declared `canClearHistory`;
   - the store returned normally.

Your driver must also defend itself:
- Clear **only** if `batch` is the exact instance your most recent successful `syncHistory()` returned. Use reference identity (`===`), not equality.
- Clear only the history **types that were fully received** in that sync.
- Do nothing if there was no successful sync since the last clear, or if the latest sync failed.
- Never clear inside `syncHistory()`, `measure()` or anything else.

`YcbtDriver` keeps a private `Synced(batch, types)` record for this.

### `measure(kind)`: `Flow<MeasureEvent>`

```kotlin
sealed interface MeasureEvent {
    data object Started; data class Live(val values: Map<Metric, Double>)
    data class Result(val values: Map<Metric, Double>); data class Failed(val reason: MeasureFailure)
}
```

The contract:
- **Order:** `Started`, then any number of `Live`, then exactly **one** `Result` or `Failed`, and the flow completes. `Failed` may come without `Started` if the ring refused to start.
- **Never throw for ring trouble.** Turn every failure into `Failed(reason)`.
- **Cancelling the collection stops the measurement on the ring.** The user closes the sheet and the coroutine is cancelled. Send your stop command in a `finally` under `withContext(NonCancellable)`, on result, failure and cancel alike.
- A kind you didn't declare emits only `Failed(UNSUPPORTED)` and sends nothing.
- **Values** use the same `Metric` keys in `Live` and `Result`:

  | Kind | Keys |
  |---|---|
  | HEART_RATE | `HEART_RATE` |
  | SPO2 | `SPO2` |
  | HRV | `HRV` |
  | STRESS | `STRESS` |
  | BLOOD_PRESSURE | `BLOOD_PRESSURE_SYS` and `BLOOD_PRESSURE_DIA` (plus `HEART_RATE` if the ring sends it) |

- **Don't store anything.** `SyncEngine.measure` saves a `Result` as `MEASURED` samples, and the server push picks them up.
- **Some rings also log on-demand readings into their history.** Return them from `syncHistory` as usual: the app drops a HISTORY sample that matches a MEASURED one (same metric and rounded value) within ±120 s, for any family.

**Failure mapping.** The UI turns each reason into specific, human copy.

| Reason | When |
|---|---|
| `NOT_WORN` | The ring says it is off a finger. YCBT: 0x040E status 02 right after start. |
| `TIMEOUT` | No value in time. YCBT waits `typicalSeconds` plus a 30 s grace. |
| `BUSY` | The ring is busy with another measurement. (SyncEngine also uses it when the vendor app holds the ring.) |
| `UNSUPPORTED` | The kind wasn't declared, or the ring answered "unsupported" to the start command. |
| `DISCONNECTED` | The link dropped (`transport.connected` is false). |
| `RING_ERROR` | Any other ring failure, a start command with no reply, or "done" with no value. |

Copy the skeleton from `YcbtDriver.runMeasure`: a `channelFlow`, the driver mutex held from start to stop, `try { … } catch (SyncFailed) { … } finally { withContext(NonCancellable) { stop } }`, and then `send(outcome)`.

### `measureContinuously` and `liveHeartRate`: the defaults

You usually implement **only** `measure()`:
- `measureContinuously(kind)` runs `measure` back to back. It emits Started once, turns each Result into a Live value, restarts after a Result or a TIMEOUT, and ends on any other failure.
- `liveHeartRate()` is the HEART_RATE values of `measureContinuously(HEART_RATE)`, and throws `MeasureFailedException` on failure.

Override `measureContinuously` only if the ring streams natively (YCBT keeps sending bpm until told to stop), so the ring is started and stopped once. Don't let `measure` return a Result instantly, or the default loop spins.

### `readMonitoring()` and `applyMonitoring(config)`

```kotlin
data class MonitorSetting(val enabled: Boolean, val intervalMin: Int, val overnightOnly: Boolean = false)
data class MonitoringConfig(val settings: Map<MonitorKind, MonitorSetting>)
```

- `readMonitoring()` returns the ring's current schedule, or **null** if the family can't read it back (the TK5 can't). The screen then shows "Last set from Orbit Health".
- `applyMonitoring(config)`:
  - **Kinds absent from `config` are left untouched.**
  - **Validate intervals yourself before sending anything.** Rings may not validate: the TK5 happily acks a 255-minute interval. Throw `IllegalArgumentException` for anything outside your declared `intervalsMin`. The app snaps values to allowed ones first, so this is a second line of defence. If it ever fires, Automatic checks says "Orbit couldn't build that setting for your ring" (`MonitoringApplyResult.Invalid`), not that the ring is out of reach.
  - **Return only what the ring accepted.** A kind the ring says it can't do is left out of the result and not retried. A kind refused for another, possibly transient, reason: send the remaining kinds, then throw `MonitoringNotApplied(accepted, refused)`. The app retries at the next sync.
  - Throw on a link failure.
  - Ignore `overnightOnly` if no declared spec has `supportsWindow`.
  - Never send kinds you didn't declare. (SyncEngine already filters out kinds missing from caps or declared with no intervals.)

## 5. Register the family

There is one line, in `app/src/main/kotlin/dev/avijit/orbithealth/AppGraph.kt`:

```kotlin
val ringRegistry = RingRegistry(listOf(YcbtFamily, AcmeFamily))
```

If two families claim an advertisement with the same confidence, the one listed first wins. Don't rely on that: make the confidences differ. Distinctive evidence (your own service, company id or name prefix) should score high, and shared evidence low or not at all. YCBT, for example, claims FEE7 only at 20 and doesn't claim the generic Heart Rate service (180D) at all, because every HR strap and watch advertises it. Duplicate ids are rejected at start-up.

That is all the app needs. Scanning (`RingScanFilter`), pairing (`PairedRing(familyId, address, modelName)`), re-opening (`openPairedRing` → `registry.family(id)`), the BLE transport (built from your `gattProfile`), coexistence, the clear gate and sync all go through the registry.

## 6. Tests to write

Model them on these:
- `ring-core/src/testFixtures/.../fake/FakeFamily.kt`: a complete, tiny family with a scripted driver.
- `ring-ycbt/src/test/.../YcbtFamilyTest.kt` for matching.
- `YcbtMeasureTest.kt` and `YcbtDriverTest.kt` for driver behaviour over a fake transport.

| Test | What it proves |
|---|---|
| `registryPicksAcmeForItsAdvert` | `RingRegistry(listOf(FakeFamily(...), AcmeFamily)).identify(adv)` returns your family with the expected model and confidence. A strong match is ≥ 50 and wins over a weaker competitor. |
| `genericServiceOnlyIsWeak` | A shared-service-only advert is below `AUTO_PICK_CONFIDENCE`, and unrelated devices return null. |
| `profileUuidsAreLowerCaseAndComplete` | The `gattProfile` has the expected writes and notifies. |
| Frame encode tests | Each command encodes to the exact bytes you verified on the ring (hex strings). |
| Decoder tests | Each reply decodes from captured bytes, including edge cases: invalid values filtered, partial pages, unknown types ignored. |
| `handshakeDeclaresVerifiedCapabilities` | Feeding the captured handshake replies gives the expected `RingCapabilities`, with `canClearHistory` false until verified. |
| `syncNeverClears` | The `written` frames of a sync contain no delete command. |
| `clearOnlyAfterTheSameBatch` | Clear with another batch, after a failed sync, or twice: nothing is sent. Only fully received types are cleared. |
| `measureHappyPath` | Started, Live…, Result, with the exact tx frames. |
| `cancelSendsStop` | Cancel the collecting job mid-measure and assert the stop frame was written. |
| `notWornTimeoutDisconnected` | Each failure reason maps as in the table above, and the stop frame still goes out. |
| `undeclaredKindIsUnsupportedWithoutSending` | |
| `applyValidatesBeforeSending` | An out-of-caps interval throws IAE with nothing written. |
| `applyReturnsOnlyAccepted` | An "unsupported" reply drops the kind; another refusal throws `MonitoringNotApplied`. |

Fakes:
- `FakeRingTransport(connected = true, defaultWrite = …)` records `written: List<Pair<GattChar, ByteArray>>`. `emit(bytes, char)` delivers a ring reply, `readable[char] = bytes` answers `read`, and `disconnect()` drops the link. Reply from a coroutine that watches `written`, or emit after the write. The flow is hot, so it mirrors real timing.
- For app-level tests with your family next to YCBT, follow `app/src/test/.../ring/MultiFamilyTest.kt` (scan, auto-pick, pair, cache capabilities, re-open). In app tests, `FakeFamily.caps(...)` builds capabilities with sensible defaults.

**Frame fixtures.** Record real exchanges as JSON in `src/test/resources/fixtures/<name>.json`, in the shape `tools/capture_fixtures.py` writes:

```json
{ "captured": "2025-01-15T09:00:00+00:00", "tz": "UTC",
  "frames": [ { "dir": "tx", "char": "be940001", "hex": "032f080000007e28" },
              { "dir": "rx", "char": "be940001", "hex": "032f070000ee99" } ] }
```

Load them with a helper like `ring-ycbt/src/test/.../Fixtures.kt` (`fixtureFrames`, `fixtureRx`, `hex`). **Scrub fixtures** before committing: no MAC addresses, serials or personal data. The repo is public.

Run your tests with `./gradlew :ring-acme:test`, then the whole suite (`:ring-core:test :ring-ycbt:test :ring-acme:test :app:testDebugUnitTest`).

## 7. Verify on a real ring

Verify every command on a real ring before the app relies on it. The repo has two debug-only tools for YCBT; copy their approach for your family:
- **`tools/ble_bridge.py`** (Python + bleak on a Mac) connects to the ring and relays it over TCP on `127.0.0.1:7777`. The frame format is `[len u16 BE][payload]`, and replies carry a characteristic tag byte. It binds loopback only, serves one client at a time and rescans when a client connects. It never sends anything on its own. The app's `BridgeRingTransport` connects to it when the shake-revealed "Use Mac bridge" developer switch on Me is on (any build). It only accepts loopback hosts: the emulator reaches the bridge at `10.0.2.2:7777`, and a phone on USB at `127.0.0.1:7777` after `adb reverse tcp:7777 tcp:7777`, so you can run the full app against the real ring. Turning the switch on shows a fresh ten-character code (`ABCDE-FGHJK`); start the bridge with `python3 -I tools/ble_bridge.py --code <code>`. A mutual HMAC handshake follows: the app proves the code first (so the bridge never signs anything for an unproven client, and drops it without touching the ring; it exits after three failures), then the bridge proves it before the app accepts any ring frame, so another app on the phone can neither drive the ring through `adb reverse` nor pose as the bridge. Release builds turn the switch off again on every cold start, and Me and the sync notification say "using Mac bridge" while it is in use.
- **`tools/ycbt_probe.py`** sends **one** explicit frame through the bridge and prints every reply, for example `raw 010c 0105 --wait 4` or `measure 1 --seconds 45`. It refuses history-delete frames (0x054x).

For a new family:
1. Generalise the bridge's ring filter (it matches names starting `TK5`) and its characteristic tags to your profile.
2. Map those tags in `BridgeRingTransport.charForTag` and `BuildTransportProvider`. Today both are YCBT-only, as is `DebugBridgeReceiver`'s handshake.
3. Write `tools/<family>_probe.py` with a hard refusal for any destructive command.
4. Probe each command, record the exact bytes and replies in `docs/protocol/<family>.md`, and mark each row **verified (date)**.
5. Run the app over the bridge:
   - pair or seed the ring;
   - `adb shell am broadcast -a dev.avijit.orbithealth.DEBUG_SYNC -n dev.avijit.orbithealth/.debug.DebugBridgeReceiver`;
   - open the Measure sheet and Automatic checks;
   - read the bridge log for the exact frames.

   Debug and bridge links are always COEXISTENCE and never clear history.

Safety rules while probing:
- Never send delete or factory-reset frames.
- Restore any setting you change.
- Don't run the vendor app at the same time; force-stop it first.

## 8. Capabilities drive the UI

These screens read your capabilities, so they need no changes:
- **Measure sheet** (`ui/measure/MeasureViewModel`). It shows one tile per `measure` key in a fixed order, with "about N s" from `typicalSeconds` and ESTIMATE from `estimate`. A Live value replaces "Reading…" as soon as it arrives. The failure copy comes from `MeasureFailure`.
- **Automatic checks** (`ui/me/AutomaticChecksViewModel`). It shows one card per `monitoring` key with a non-empty `intervalsMin`, offering only those intervals. The switch appears only if `canTurnOff`, and "Overnight only" only if `supportsWindow`. When `readMonitoring()` returns null, it shows "Last set from Orbit Health". Me's summary row uses the same specs.
- **Onboarding**, through `match` and confidence (auto-pick at ≥ 50, weaker matches tappable).
- **Coexistence and clearing**, through `vendorAppPackages`, `vendorAppName` and `canClearHistory`.
- **Today, Sleep, Body, the server push and the agent tools** read stored samples by `Metric`, whichever family produced them.

Not yet built: spec §2's "Your ring doesn't measure this" copy for metrics outside `history`. Today a metric your ring never records simply has no data in Body and Today.

**When your ring measures something new.** If the ring can do something `MeasureKind`, `MonitorKind` or `Metric` doesn't cover yet (for example temperature or respiratory rate on demand), add the enum value in `:ring-core`, then build. The `when` blocks over these enums are exhaustive, so the compiler lists every place to update:
- **YCBT:** `measureType` in `Commands.kt` (the 0x032F type byte) and `decodeMeasureValue` in `Decoders.kt`. A kind left out of `YcbtDefaults.MEASURE` is answered `UNSUPPORTED` without sending anything.
- **The Measure sheet:**
  - `MeasureCopy` in `MeasureViewModel.kt`: the name, primary metric, unit, value format and its place in `ORDER`;
  - `lookOf` in `MeasureSheet.kt`: tint, glyph and sky;
  - `spokenUnit` in `MeasureSheet.kt`.
- **Automatic checks:** `AutomaticChecksCopy` (title, noun, `ORDER`, defaults) and `lookOf` in `AutomaticChecksScreen.kt`.
- **Test fixtures:** `FakeFamily.defaultMeasure` in the test fixtures.
- **A new `Metric`:**
  - the Ask/agent mappings (`AskViewModel`, and `server/.../agent/Tools.kt` on the server);
  - any chart or insight that should show it.

  The server accepts any `Metric` name from the shared enum. `CapabilitiesCodec` skips names an older build doesn't know, so cached capabilities never crash a downgrade.

**What `DeviceInfo` must mean.**
- `deviceId` is whatever numeric identity the ring reports. It is shown, and sent to the Orbit Server as device state.
- It does **not** have to be unique per ring: on YCBT it is a model code. The app keys per-ring state, such as "these settings were already sent to this ring", on the paired ring's BLE address (`PairedRing.address`, `RingConnection.ringId`), never on `deviceId`.
- `firmware` is a display string.
- `batteryPct` is 0–100.

## 9. Premium rings without an open protocol

Some premium rings publish no BLE protocol, and their vendor app owns the link. Don't reverse-engineer an encrypted, closed link. These rings usually write their data into **Health Connect**, and that is the planned way in.

**Status: designed, not built.** Spec §4 names the seam; no code exists yet.

The plan is a second kind of source next to ring families, a `DataSource` with a read-only `HealthConnectSource`:

```kotlin
// Planned (not in the codebase yet)
interface DataSource {
    val id: String                       // e.g. "healthconnect"
    suspend fun importSince(since: Instant): HistoryBatch
}
class HealthConnectSource(client: HealthConnectClient, val originPackages: Set<String>) : DataSource
```

How it would plug in:
- **Storage.** It returns the same `HistoryBatch` (samples plus sleep sessions), stored through the same `BatchStore` / `SampleRepository.store`. Samples would carry a new source, for example `SampleSource.IMPORTED`, plus the origin package (a new column via a Room migration), so charts can show "from Health Connect (Oura)" and duplicates from two sources can be told apart.
- **Sync.** It is a periodic import beside `SyncEngine`'s ring sync. It has no link, no measure, no monitoring and **never any clear**: it is read-only by design, so the two-phase clear rules don't apply.
- **Server push and agent tools.** `ServerSync` pushes stored samples whatever their origin. The server checks `source` against the shared `SampleSource` enum, so adding IMPORTED in `:ring-core` updates both sides; older servers skip unknown sources. The agent tools read the server's store, so imported data becomes visible to them with no change.
- **UI.** No Measure tiles or Automatic checks, because a data source declares no `measure` or `monitoring`. The history screens work as they do now.
- **Permissions.** It needs the Health Connect read permissions only for the metrics it imports, asked for in context, plus the user's choice of origin apps.

Rule of thumb: if a ring has an open or documented BLE protocol, write a `RingFamily` module (sections 2–8). If it doesn't, it waits for the Health Connect `DataSource`.

## 10. Worked example: a Nordic-UART ring

Here is a sketch for a hypothetical "Acme" ring that speaks a simple framed protocol over the Nordic UART Service (NUS: write to RX 6e400002, notifications on TX 6e400003). These are skeletons to show the shape, not a working driver.

```kotlin
package dev.avijit.orbithealth.acme

object AcmeFamily : RingFamily {
    const val ID = "acme"
    override val id = ID
    override val displayName = "Acme rings"

    const val NUS = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
    val RX = GattChar(NUS, "6e400002-b5a3-f393-e0a9-e50e24dcca9e")   // app → ring
    val TX = GattChar(NUS, "6e400003-b5a3-f393-e0a9-e50e24dcca9e")   // ring → app
    val BATTERY = GattChar.sig("180f", "2a19")                        // read once at handshake

    override val gattProfile = GattProfile(writes = listOf(RX), notifies = listOf(TX), writeWithResponse = false)
    override val vendorAppPackages = setOf("com.acme.ringapp")        // + <queries> line in the manifest
    override val vendorAppName = "Acme Health"

    override fun match(advertisement: RingAdvertisement): RingMatch? {
        val name = advertisement.name.orEmpty()
        return when {
            name.startsWith("ACME-R") -> RingMatch(name.substringBefore(' '), 90)
            advertisement.hasService(NUS) -> RingMatch(name.ifBlank { "Acme ring" }, 15) // NUS is generic: never auto-pick
            else -> null
        }
    }

    override fun createDriver(transport: RingTransport): RingDriver = AcmeDriver(transport)
}
```

```kotlin
class AcmeDriver(private val transport: RingTransport) : RingDriver {
    private val mutex = Mutex()
    private var lastSynced: HistoryBatch? = null   // for the clear contract

    override suspend fun handshake(profile: UserProfile): Pair<DeviceInfo, RingCapabilities> = mutex.withLock {
        val info = request(Frames.getInfo()).let(Decoders::deviceInfo)
        request(Frames.setTime(Instant.now(), ZoneId.systemDefault()))
        val battery = runCatching { transport.read(AcmeFamily.BATTERY)[0].toInt() }.getOrNull()
        info.copy(batteryPct = battery ?: info.batteryPct) to RingCapabilities(
            history = setOf(Metric.HEART_RATE, Metric.SPO2, Metric.STEPS),
            sleep = true, workouts = false, realtime = true,
            measure = mapOf(
                MeasureKind.HEART_RATE to MeasureSpec(typicalSeconds = 30, live = true, estimate = false),
                MeasureKind.SPO2 to MeasureSpec(typicalSeconds = 40, live = false, estimate = false),
            ),
            monitoring = mapOf(MonitorKind.HEART_RATE to MonitorSpec(listOf(5, 10, 30, 60), canTurnOff = true, supportsWindow = false)),
            // canClearHistory stays false until the delete command is verified on a real ring.
        )
    }

    override suspend fun syncHistory(): HistoryBatch = mutex.withLock {
        val batch = HistoryBatch(readHeartPages() + readSpo2Pages() + readStepPages(), readSleep(), emptyList())
        lastSynced = batch
        batch
    }

    override suspend fun clearSyncedHistory(batch: HistoryBatch) {
        // Not opted in (canClearHistory = false): the app never calls this. When it is opted in:
        // if (batch !== lastSynced) return; send delete only for fully received types; lastSynced = null.
    }

    override fun measure(kind: MeasureKind): Flow<MeasureEvent> = channelFlow {
        if (kind != MeasureKind.HEART_RATE && kind != MeasureKind.SPO2) {
            send(MeasureEvent.Failed(MeasureFailure.UNSUPPORTED)); return@channelFlow
        }
        val outcome = mutex.withLock {
            try {
                coroutineScope {
                    val replies = transport.packets.buffer(Channel.UNLIMITED).produceIn(this) // subscribed before the write
                    transport.write(Frames.startMeasure(kind))
                    send(MeasureEvent.Started)
                    withTimeoutOrNull(45.seconds) {
                        for (p in replies) when (val r = Decoders.measureReply(kind, p.bytes)) {
                            is Reply.Value -> send(MeasureEvent.Live(r.values))
                            is Reply.Done -> return@withTimeoutOrNull MeasureEvent.Result(r.values)
                            Reply.OffFinger -> return@withTimeoutOrNull MeasureEvent.Failed(MeasureFailure.NOT_WORN)
                            null -> Unit
                        }
                        null
                    } ?: MeasureEvent.Failed(MeasureFailure.TIMEOUT)
                }
            } catch (e: IOException) {
                MeasureEvent.Failed(if (transport.connected.value) MeasureFailure.RING_ERROR else MeasureFailure.DISCONNECTED)
            } finally {
                withContext(NonCancellable) { runCatching { transport.write(Frames.stopMeasure(kind)) } }  // cancel stops the ring
            }
        }
        send(outcome)
    }

    override suspend fun readMonitoring(): MonitoringConfig? = null   // this ring can't report its schedule

    override suspend fun applyMonitoring(config: MonitoringConfig): MonitoringConfig = mutex.withLock {
        val hr = config[MonitorKind.HEART_RATE] ?: return@withLock MonitoringConfig.EMPTY
        require(hr.intervalMin in listOf(5, 10, 30, 60)) { "interval ${hr.intervalMin} not allowed" }  // ring doesn't validate
        val ack = request(Frames.setHrInterval(hr.enabled, hr.intervalMin))
        when (Decoders.ack(ack)) {
            Ack.OK -> MonitoringConfig(mapOf(MonitorKind.HEART_RATE to hr))
            Ack.UNSUPPORTED -> MonitoringConfig.EMPTY
            Ack.BUSY -> throw MonitoringNotApplied(MonitoringConfig.EMPTY, setOf(MonitorKind.HEART_RATE))
        }
    }

    // measureContinuously / liveHeartRate: inherited defaults.

    private suspend fun request(frame: ByteArray): ByteArray = TODO("subscribe, write, await the matching reply with a timeout; throw SyncFailed on none")
    // readHeartPages(), readSpo2Pages(), readStepPages(), readSleep(): page through history; never delete.
}
```

Then:
- add `include(":ring-acme")` and `implementation(project(":ring-acme"))`;
- add `AcmeFamily` to `AppGraph.ringRegistry`;
- add `<package android:name="com.acme.ringapp" />` to the manifest's `<queries>`;
- write the tests in section 6;
- verify with a probe.

The Measure sheet then shows Heart rate and SpO₂ tiles, and Automatic checks shows one Heart rate card with 5 / 10 / 30 / 60 min.

## 11. Checklist

- [ ] The protocol doc in `docs/protocol/<family>.md` has every command marked verified on a real ring.
- [ ] The `:ring-<family>` module exists, with the `settings.gradle.kts` include and the `app/build.gradle.kts` dependency.
- [ ] `RingFamily` has a stable `id`, `match` (≥ 50 only for distinctive evidence), lower-case `gattProfile`, `vendorAppPackages` plus manifest `<queries>`, `vendorAppName`, and `createDriver`.
- [ ] `RingDriver`:
  - [ ] handshake declares only verified capabilities, and `canClearHistory` stays false unless the clear is verified;
  - [ ] `syncHistory` never deletes;
  - [ ] `clearSyncedHistory` follows the two-phase contract;
  - [ ] `measure` follows the event order, its stop survives cancel, and every failure is mapped;
  - [ ] `applyMonitoring` validates and returns only what was accepted.
- [ ] The family is in `AppGraph.ringRegistry`.
- [ ] Tests: matching, frames, decoders, capabilities, never-clears, measure (happy, cancel, failures) and monitoring.
- [ ] Fixtures are scrubbed of MACs, serials and personal data.
- [ ] A real-ring run over a bridge or probe is described in your pull request.
