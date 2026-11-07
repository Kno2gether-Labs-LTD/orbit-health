"""Capture real TK5 ring frames as JSON. Read-only commands only (no delete/setting/OTA).

WARNING: a capture contains YOUR PERSONAL HEALTH DATA (heart rate, sleep, steps, SpO2, blood pressure, with real
timestamps and time zone). Never commit captures or copy their bytes into the repo. Write them outside the repo,
use them only to learn the layout, and encode what you learn in tools/synth_fixtures.py, which generates the
committed (synthetic) fixtures in ring-ycbt/src/test/resources/fixtures/.
"""
import argparse, asyncio, datetime, json, os, sys
from bleak import BleakScanner, BleakClient

SVC = "be940000-7333-be46-b7ae-689e71722bd5"
C1 = "be940001-7333-be46-b7ae-689e71722bd5"
C3 = "be940003-7333-be46-b7ae-689e71722bd5"
CHARS = {C1: "be940001", C3: "be940003"}


def crc16(b):
    s = 0xFFFF
    for x in b:
        s = ((s << 8) & 0xFF00) | ((s >> 8) & 0xFF)
        s ^= x
        s ^= (s & 0xFF) >> 4
        s ^= (s << 12) & 0xFFFF
        s ^= ((s & 0xFF) << 5) & 0xFFFF
    return s & 0xFFFF


def frame(t, p=b""):
    n = len(p) + 6
    b = bytes([(t >> 8) & 255, t & 255, n & 255, (n >> 8) & 255]) + p
    c = crc16(b)
    return b + bytes([c & 255, c >> 8])


ALLOWED = {0x0200, 0x0201, 0x0309, 0x032F, 0x0502, 0x0504, 0x0506, 0x0508, 0x0509, 0x051A, 0x0533, 0x0580}
SILENCE = 3.0
TZ_NAME = "UTC"  # IANA zone the ring clock was set to; set with --tz


class Capture:
    def __init__(self, client):
        self.c = client
        self.frames = []  # current fixture
        self.bufs = {}
        self.last = 0.0
        self.notes = []

    def on_data(self, uuid):
        short = CHARS[uuid]

        def cb(_, d):
            buf = self.bufs.get(short, b"") + bytes(d)
            while len(buf) >= 4:
                n = buf[2] | (buf[3] << 8)
                if n < 6:
                    buf = b""
                    break
                if len(buf) < n:
                    self.notes.append(f"partial frame on {short}: have {len(buf)} of {n}")
                    break
                self.frames.append({"dir": "rx", "char": short, "hex": buf[:n].hex()})
                buf = buf[n:]
                self.last = asyncio.get_event_loop().time()
            self.bufs[short] = buf
        return cb

    async def send(self, t, p=b""):
        assert t in ALLOWED, f"command 0x{t:04x} not allowed"
        f = frame(t, p)
        self.frames.append({"dir": "tx", "char": "be940001", "hex": f.hex()})
        self.last = asyncio.get_event_loop().time()
        await self.c.write_gatt_char(C1, f, response=True)

    async def settle(self, history=False):
        """Wait for SILENCE seconds of quiet; on history end frame verify CRC and ack."""
        seen = len(self.frames)
        acked = False
        while True:
            await asyncio.sleep(0.2)
            if history and not acked:
                for fr in self.frames[seen:]:
                    if fr["dir"] == "rx" and fr["hex"].startswith("0580"):
                        await self.ack_history()
                        acked = True
                        break
            if asyncio.get_event_loop().time() - self.last > (6.0 if len(self.frames) == seen else SILENCE):
                return

    def history_info(self):
        req = next((int(f["hex"][:4], 16) for f in self.frames if f["dir"] == "tx"), 0)
        rx = [bytes.fromhex(f["hex"]) for f in self.frames if f["dir"] == "rx"]
        info = {"rx": len(rx), "subs": [], "blocks": 0, "block_bytes": 0, "end": None, "crc": None}
        blocks = b""
        for f in rx:
            t = (f[0] << 8) | f[1]
            if t == 0x0580:
                pl = f[4:-2]
                info["subs"].append("end")
                if len(pl) >= 6:
                    cnt = pl[0] | pl[1] << 8
                    tot = pl[2] | pl[3] << 8
                    crc = pl[4] | pl[5] << 8
                    info["end"] = {"packets": cnt, "bytes": tot, "crc": crc}
                    info["crc"] = (crc16(blocks) == crc)
                    info["computed_crc"] = crc16(blocks)
            elif (t >> 8) == 0x05:
                if t == req:
                    info["subs"].append("header")
                    info["header_payload_len"] = len(f) - 6
                else:
                    info["subs"].append("block")
                    info["blocks"] += 1
                    blocks += f[4:-2]
                    info["block_bytes"] += len(f) - 6
            else:
                info["subs"].append(f"other_{t:04x}")
        return info

    async def ack_history(self):
        info = self.history_info()
        if info["end"] and info["crc"]:
            await self.send(0x0580, b"\x00")
        else:
            self.notes.append(f"history CRC mismatch / not acked: {info}")

    def fixture(self, frames=None):
        return {"captured": datetime.datetime.now().astimezone().isoformat(timespec="seconds"),
                "tz": TZ_NAME, "frames": frames if frames is not None else self.frames}


async def find_and_connect():
    last = None
    for attempt in range(3):
        print(f"scan attempt {attempt+1}", flush=True)
        d = await BleakScanner.find_device_by_filter(lambda d, a: (d.name or "").startswith("TK5"), timeout=30)
        if not d:
            continue
        print("found", d.name, d.address, flush=True)
        try:
            c = BleakClient(d, timeout=45)
            await c.connect()
            return c
        except Exception as e:
            last = e
            print("connect failed", e, flush=True)
    print("BLOCKED: could not find/connect to TK5 ring", last, flush=True)
    sys.exit(2)


async def main(out):
    os.makedirs(out, exist_ok=True)
    c = await find_and_connect()
    cap = Capture(c)
    summary = {}
    try:
        for u in (C1, C3):
            await c.start_notify(u, cap.on_data(u))

        def save(name, frames=None, extra=None):
            with open(os.path.join(out, name + ".json"), "w") as f:
                json.dump(cap.fixture(frames), f, indent=1)
            fr = cap.frames if frames is None else frames
            summary[name] = {"frames": len(fr), "rx": sum(1 for x in fr if x["dir"] == "rx")}
            if extra:
                summary[name].update(extra)
            print(name, summary[name], flush=True)

        for name, t, p in [("device_info", 0x0200, b"\x47\x43"), ("support_function", 0x0201, b"\x47\x46")]:
            cap.frames = []
            await cap.send(t, p)
            await cap.settle()
            save(name)

        # realtime HR session
        cap.frames = []
        for kind in (0, 1, 2, 3):
            await cap.send(0x0309, bytes([1, kind, 2]))
            await asyncio.sleep(0.4)
        await cap.send(0x032F, bytes([1, 0]))
        await asyncio.sleep(30)
        live = list(cap.frames)
        save("realtime_hr", live)
        cap.frames = []
        await cap.send(0x032F, bytes([0, 0]))
        await asyncio.sleep(1)
        for kind in (0, 1, 2, 3):
            await cap.send(0x0309, bytes([0, kind, 2]))
            await asyncio.sleep(0.4)
        await cap.settle()
        mr = list(cap.frames)
        # include any 0x040e measure-result frames from the live session too
        extra = [f for f in live if f["dir"] == "rx" and f["hex"].startswith("040e")]
        save("measure_result", mr + [f for f in extra if f not in mr])

        for name, t in [("history_sport", 0x0502), ("history_sleep", 0x0504), ("history_heart", 0x0506),
                        ("history_blood", 0x0508), ("history_all", 0x0509), ("history_spo2", 0x051A),
                        ("history_body", 0x0533)]:
            cap.frames = []
            cap.notes = []
            await cap.send(t)
            await cap.settle(history=True)
            info = cap.history_info()
            info["notes"] = list(cap.notes)
            save(name, extra=info)
    finally:
        try:
            await c.disconnect()
        except Exception:
            pass
    with open(os.path.join(out, "_summary.txt"), "w") as f:
        json.dump(summary, f, indent=1)


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True, help="directory OUTSIDE the repo (captures hold personal health data)")
    ap.add_argument("--tz", default="UTC", help="IANA zone of the ring clock, e.g. the phone's zone")
    a = ap.parse_args()
    TZ_NAME = a.tz
    repo = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    if os.path.abspath(a.out).startswith(repo + os.sep):
        print("WARNING: --out is inside the repo. Captures contain personal health data: do not commit them.",
              file=sys.stderr, flush=True)
    asyncio.run(main(a.out))
