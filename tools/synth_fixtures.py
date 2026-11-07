#!/usr/bin/env python3
"""Generate SYNTHETIC YCBT ring fixtures for ring-ycbt tests (Python 3 stdlib only).

Every health value and timestamp below is invented. The frame sequences mirror the shape of real TK5
captures (tx request, rx header, 0x05xx block frame(s) on be940003, 0x0580 end frame, tx ack), but no real
record bytes are kept. Only the model-describing frames (device id / firmware, support-function bitmap)
keep the TK5's structure; the battery level is invented.

    python3 tools/synth_fixtures.py            # writes ring-ycbt/src/test/resources/fixtures/*.json

Output is deterministic: running it twice produces identical files.
"""
import datetime
import json
import os

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "ring-ycbt", "src", "test", "resources", "fixtures")
CAPTURED = datetime.datetime(2025, 1, 15, 9, 0, 0, tzinfo=datetime.timezone.utc)
TZ = "UTC"
C1, C3 = "be940001", "be940003"


def crc16(data: bytes, seed: int = 0xFFFF) -> int:
    s = seed
    for b in data:
        s = ((s << 8) & 0xFF00) | ((s >> 8) & 0xFF)
        s ^= b
        s ^= (s & 0xFF) >> 4
        s ^= (s << 12) & 0xFFFF
        s ^= ((s & 0xFF) << 5) & 0xFFFF
    return s & 0xFFFF


def frame(ftype: int, payload: bytes = b"") -> bytes:
    """[type hi, type lo, len lo, len hi, payload, crc lo, crc hi]; len counts the whole frame."""
    n = len(payload) + 6
    b = bytes([(ftype >> 8) & 0xFF, ftype & 0xFF, n & 0xFF, (n >> 8) & 0xFF]) + payload
    c = crc16(b)
    return b + bytes([c & 0xFF, c >> 8])


def le(v: int, n: int) -> bytes:
    return v.to_bytes(n, "little")


def ring(y, mo, d, h, mi, s=0) -> int:
    """Ring time: seconds since 2000-01-01 in local wall-clock time (fixtures use tz UTC)."""
    return int((datetime.datetime(y, mo, d, h, mi, s) - datetime.datetime(2000, 1, 1)).total_seconds())


def tx(ftype, payload=b""):
    return {"dir": "tx", "char": C1, "hex": frame(ftype, payload).hex()}


def rx(ftype, payload=b"", char=C1):
    return {"dir": "rx", "char": char, "hex": frame(ftype, payload).hex()}


def history(req: int, block: int, records: bytes, count: int, max_block: int = 0) -> list:
    """tx request, rx header [records LE16, packets LE32, bytes LE32], block frame(s), end frame, tx ack."""
    size = max_block or len(records)
    chunks = [records[i:i + size] for i in range(0, len(records), size)]
    header = le(count, 2) + le(len(chunks), 4) + le(len(records), 4)
    end = le(len(chunks), 2) + le(len(records), 2) + le(crc16(records), 2)
    return ([tx(req), rx(req, header)] + [rx(block, c, C3) for c in chunks]
            + [rx(0x0580, end), tx(0x0580, b"\x00")])


def heart() -> list:
    # 6-byte records: t, 0, bpm.
    recs = [(ring(2025, 1, 15, 6, 0), 58), (ring(2025, 1, 15, 6, 30), 61), (ring(2025, 1, 15, 7, 0), 66),
            (ring(2025, 1, 15, 7, 30), 72), (ring(2025, 1, 15, 8, 0), 69)]
    data = b"".join(le(t, 4) + bytes([0, bpm]) for t, bpm in recs)
    return history(0x0506, 0x0515, data, len(recs))


def blood() -> list:
    # 8-byte records: t, mode, sys, dia, hr.
    recs = [(ring(2025, 1, 15, 6, 45), 118, 77, 64), (ring(2025, 1, 15, 7, 45), 122, 79, 70),
            (ring(2025, 1, 15, 8, 45), 116, 75, 67)]
    data = b"".join(le(t, 4) + bytes([1, s, d, h]) for t, s, d, h in recs)
    return history(0x0508, 0x0517, data, len(recs))


def all_() -> list:
    # 20-byte records: t, day-total steps LE16, hr, sys, dia, spo2, resp, hrv, 8 filler bytes (invented).
    recs = [(ring(2025, 1, 14, 18, 0), 6240, 74, 118, 77, 97, 14, 48),
            (ring(2025, 1, 14, 19, 0), 6915, 81, 121, 78, 98, 15, 42),
            (ring(2025, 1, 14, 20, 0), 7402, 69, 119, 76, 96, 13, 51)]
    data = b"".join(le(t, 4) + le(st, 2) + bytes([hr, s, d, o2, rr, hrv]) + bytes.fromhex("05000f0000002408")
                    for t, st, hr, s, d, o2, rr, hrv in recs)
    return history(0x0509, 0x0518, data, len(recs))


def body() -> list:
    # 28-byte records: t, load LE16, hrv int/frac, stress int/frac, 18 filler bytes (invented).
    recs = [(ring(2025, 1, 14, 21, 0), 5, 46, 2, 28, 4, 0x11), (ring(2025, 1, 15, 3, 0), 2, 58, 7, 12, 1, 0x22),
            (ring(2025, 1, 15, 8, 0), 6, 41, 5, 33, 0, 0x33)]
    data = b"".join(le(t, 4) + le(load, 2) + bytes([hrv, hf, st, sf]) + bytes([2, 5, 3, 4, 0x60]) + bytes(12) + bytes([tail])
                    for t, load, hrv, hf, st, sf, tail in recs)
    return history(0x0533, 0x0534, data, len(recs))


def sport() -> list:
    # 14-byte 30-minute buckets: start, end, steps, distance m, kcal.
    # Quirks reproduced from the real ring on purpose: consecutive starts alternate 1799/1800 s apart, and one
    # bucket has 0 kcal (the decoder skips it).
    base = ring(2025, 1, 15, 6, 0)
    offsets = [0, 1799, 3599, 5398, 7198, 8997]
    vals = [(64, 45, 3), (12, 8, 0), (230, 162, 9), (415, 298, 17), (96, 68, 4), (388, 276, 15)]
    data = b"".join(le(base + o, 4) + le(base + o + 1800, 4) + le(st, 2) + le(dist, 2) + le(kcal, 2)
                    for o, (st, dist, kcal) in zip(offsets, vals))
    return history(0x0502, 0x0511, data, len(vals))


def sleep_empty() -> list:
    # Quirk reproduced on purpose: the ring had no sleep stored, so it answers a 2-byte "no data" header.
    return [tx(0x0504), rx(0x0504, bytes(2))]


SLEEP_STAGES = {"deep": 241, "light": 242, "rem": 243, "awake": 244}


def sleep_night() -> list:
    """One invented night 2025-01-14 23:30 -> 2025-01-15 07:00, split over two block frames (multi-packet)."""
    start = ring(2025, 1, 14, 23, 30)
    plan = [("light", 1200), ("deep", 3000), ("light", 2400), ("rem", 1500), ("awake", 300), ("light", 2700),
            ("deep", 2100), ("light", 2400), ("rem", 1800), ("light", 3000), ("rem", 2100), ("light", 2400),
            ("awake", 600), ("light", 1500)]
    segs, t = b"", start
    for stage, dur in plan:
        segs += bytes([SLEEP_STAGES[stage]]) + le(t, 4) + le(dur, 3)
        t += dur
    length = 20 + len(segs)
    rec = le(0xAA55, 2) + le(length, 2) + le(start, 4) + le(t, 4) + le(len(plan), 2) + bytes(6) + segs
    return history(0x0504, 0x0513, rec, 1, max_block=84)


def spo2() -> list:
    # Quirk reproduced on purpose: the TK5 did not answer the SpO2 history request at all (tx only).
    return [tx(0x051A)]


def device_info() -> list:
    # Model description (device id 0x00a3, firmware 1.18) kept; battery (byte 5) invented: 62 %.
    p = bytes.fromhex("a300120100" + "3e" + "000100030000000001000000010000000000")
    return [tx(0x0200, b"\x47\x43"), rx(0x0200, p)]


def support_function() -> list:
    # Capability bitmap of the ring model (not personal data), kept so capability tests hold.
    p = bytes.fromhex("f90b000000000cd8101401b2b600400f0014500000006005") + bytes(36)
    return [tx(0x0201, b"\x47\x46"), rx(0x0201, p)]


def realtime_hr() -> list:
    ack = lambda t: rx(t, b"\x00")
    f = []
    for kind in (0, 1):
        f += [tx(0x0309, bytes([1, kind, 2])), ack(0x0309)]
    # Live pedometer frame (0x0600: steps, distance m, kcal as LE16) arrives between the realtime acks.
    f += [tx(0x0309, bytes([1, 2, 2])), rx(0x0600, le(1205, 2) + le(850, 2) + le(48, 2), C3), ack(0x0309)]
    f += [tx(0x0309, bytes([1, 3, 2])), ack(0x0309)]
    f += [tx(0x032F, bytes([1, 0])), ack(0x032F)]
    f += [rx(0x0601, bytes([bpm]), C3) for bpm in (72, 72, 73, 74, 74, 75, 75, 74, 73, 73)]
    f += [rx(0x040E, bytes([0, 1]))]  # heart-rate measurement done
    return f


def measure_result() -> list:
    ack = lambda t: rx(t, b"\x00")
    f = [tx(0x032F, bytes([0, 0])), ack(0x032F)]
    for kind in (0, 1, 2, 3):
        f += [tx(0x0309, bytes([0, kind, 2])), ack(0x0309)]
    return f + [rx(0x040E, bytes([0, 1]))]


FIXTURES = [
    ("device_info", device_info), ("support_function", support_function), ("realtime_hr", realtime_hr),
    ("measure_result", measure_result), ("history_sport", sport), ("history_sleep", sleep_empty),
    ("history_heart", heart), ("history_blood", blood), ("history_all", all_), ("history_spo2", spo2),
    ("history_body", body), ("history_sleep_night", sleep_night),
]


def main():
    os.makedirs(OUT, exist_ok=True)
    for i, (name, build) in enumerate(FIXTURES):
        captured = (CAPTURED + datetime.timedelta(seconds=5 * i)).isoformat(timespec="seconds")
        with open(os.path.join(OUT, name + ".json"), "w") as fh:
            json.dump({"captured": captured, "tz": TZ, "frames": build()}, fh, indent=1)
            fh.write("\n")
        print("wrote", name)


if __name__ == "__main__":
    main()
