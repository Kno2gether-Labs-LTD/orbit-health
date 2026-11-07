# YCBT measurement and automatic-check commands (TK5)

Byte layouts read from the vendor SDK's public API surface (`YCBTClient`, `Constants.DATATYPE`, `DataUnpack`) for interoperability. No vendor code is copied. Each row is **SDK** until it is checked on the real ring through `tools/ycbt_probe.py`; then it becomes **verified (date)**.

Frame: `[type hi, type lo, len lo, len hi, payload…, crc lo, crc hi]`, CRC-16 with seed 0xFFFF (see `ring-ycbt/Crc16.kt`).

## On-demand measurement

| What | Frame | Payload | Status |
|---|---|---|---|
| Start/stop measuring | `0x032F` | `[on(1/0), type]`: type 0 HR, 1 BP, 2 SpO₂, 10 HRV, 12 stress. The ack is `0x032F [00]` | all five verified 2026-11-06 |
| Blood measurement with parameters | `0x032E` | 8 bytes (SDK `appStartBloodMeasurement`; meaning TBD) | SDK |
| Live heart rate | `0x0601` (ring→app, char 3) | `[bpm]` | verified |
| Live SpO₂ | `0x0602` | `[spo2 %]`, e.g. `63` = 99 % | verified 2026-11-06 (type 2) |
| Live blood (BP / HRV) | `0x0603` | `[sys, dia, hr, hrv, …]`, 14 bytes. BP (type 1): `76 4d 48` = 118/77, HR 72. HRV (type 10): `00 00 00 2e` = 46 ms (example values) | verified 2026-11-06 |
| Live body data (stress) | `0x0610` | 22 bytes. Byte 0–1 load index; byte 2–3 HRV; **byte 4–5 stress (pressure) integer.fraction**, e.g. `07 01` = 7.1; then body, sympathetic, SDNN, … | verified 2026-11-06 (type 12) |
| Measurement finished | `0x040E` (ring→app, char 1) | `[type, status]`; status 01 = done: `0101` BP, `0201` SpO₂, `0a01` HRV, `0c01` stress. 02 = not worn (observed 2026-11-06, ring removed): sent right after the start ack, with no value; the app shows NOT_WORN. Any other status is a ring error | verified 2026-11-06 |
| Timing (observed) | — | Live values (0x0603 for BP and HRV) come as a short burst near the end, not throughout. HRV's `0a01` arrived after 60 s, so the app's HRV typical length is **75 s** (the driver still ends early on "done", and waits a 30 s grace past 75 s for a first value) | observed 2026-11-06 over the bridge |

## Automatic checks (ring's own schedule)

| What | Frame | Payload | Read back? | Status |
|---|---|---|---|---|
| Heart-rate monitor | `0x010C` | `[mode, interval min]` | **no on TK5** (0x0207 answers `fc`) | **verified 2026-11-06**: `[01, 0a]` ack `00`. The ring also acks `[01, ff]`, so it does NOT validate; the app must offer only sane values. Left at 10 min. |
| Blood-pressure monitor | `0x011C` | `[mode, interval]` | no | **unsupported on TK5**: ack `fc` |
| Temperature monitor | `0x0120` | `[on, interval]` | switch only (`0x0207` byte 60) | SDK |
| SpO₂ monitor | `0x0126` | `[on, interval]` | no | **verified 2026-11-06**: `[01, 1e]` (30 min) ack `00` |
| Automatic measurement time | `0x0140` | 3 bytes | no | SDK |
| HRV monitor | `0x0145` | 5 bytes | no | SDK |

## Device user config (`0x0207`, request payload `[0x43, 0x46]`): **TK5 answers `fc` (unsupported)**

Selected fields (little-endian):
- bytes 0–2: step target
- bytes 9–10: sleep target
- bytes 11–14: height, weight, sex, age
- byte 37: heart hand
- bytes 38–39: HR alarm switch and value
- **bytes 40–41: HR monitor type and interval**
- byte 54+: sleep switch
- byte 60: temperature switch

The app shows "last set from Orbit" for monitors the ring can't report.

## Ack convention

Settings replies echo the type with a 1-byte status: `00` = accepted, `fc` = not supported by this ring.

## Probe record (2026-11-06, TK5 A1B2, firmware 1.18)

- Ring left at: HR monitor every 10 min, SpO₂ monitor every 30 min (user asked to try these).
- Not probed: HRV monitor `0x0145`, whose 5-byte meaning is unknown. Temperature: the TK5 has no temperature sensor in the 0x0201 bitmap.

## On-demand readings in history (observed 2026-11-06)

Seen on the TK5 (firmware 1.18) through the Measure sheet:
- **Heart rate is logged.** A measurement at about 21:10 (89 bpm) shows up in the HEART history (0x0506) as a record at 21:10:41 with 89 bpm, next to the automatic 21:00:56 record.
- **HRV is logged.** A measurement at about 21:13 (172 ms) shows up in the ALL history as a record at 21:13:07 with HRV 172. Every other field in that record is 0, apart from the day's step total.
- **SpO₂ was not logged.** A measurement at 21:12 (96 %) did not appear in any history.

How the app deduplicates, for any family: when history is stored (`SampleRepository.store`), a HISTORY sample is dropped if a MEASURED sample of the same metric with the same rounded value lies within ±120 s. Values are compared as whole numbers for HR, SpO₂, HRV and BP, and to one decimal otherwise (e.g. stress). The MEASURED row is kept, because it is what the user saw and may already be pushed. Other fields of the same record, such as the step total, are stored as usual. Copies stored before this fix (today's 21:10 and 21:13 readings) remain.
