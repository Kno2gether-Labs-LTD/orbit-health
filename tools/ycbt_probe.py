"""Debug-only YCBT probe: sends ONE explicit command through the Mac BLE bridge and prints every reply.

Used to verify settings / measurement commands on a real ring before the app relies on them
(Plan 5, task P0). It never sends history-delete frames (0x054x) and refuses to.

Usage (bridge must be running: python3 -I tools/ble_bridge.py --code ABCDE-FGHJK; pass the same code here):
  python3 -I tools/ycbt_probe.py --code ABCDE-FGHJK read-config # 0x0207 GetDeviceUserConfig
  python3 -I tools/ycbt_probe.py support                     # 0x0201 support-function bitmap
  python3 -I tools/ycbt_probe.py raw 010c 0105 --wait 4      # any frame: type hex, payload hex
  python3 -I tools/ycbt_probe.py measure 1 --seconds 45      # 0x032F start type, wait, stop
"""
import argparse, hashlib, hmac, os, re, socket, struct, sys, time

HOST, PORT = "127.0.0.1", 7777


def crc16(data: bytes, seed: int = 0xFFFF) -> int:
    s = seed
    for b in data:
        s = ((s << 8) & 0xFF00) | ((s >> 8) & 0xFF)
        s ^= b
        s ^= (s & 0xFF) >> 4
        s ^= (s << 12) & 0xFFFF
        s ^= ((s & 0xFF) << 5) & 0xFFFF
    return s & 0xFFFF


def frame(ftype: int, payload: bytes) -> bytes:
    if (ftype >> 8) == 0x05 and (ftype & 0xFF) >= 0x40:
        sys.exit("refusing: 0x054x frames delete ring history")
    n = len(payload) + 6
    head = bytes([ftype >> 8, ftype & 0xFF, n & 0xFF, n >> 8]) + payload
    c = crc16(head)
    return head + bytes([c & 0xFF, c >> 8])


class Bridge:
    def __init__(self, code: str):
        self.s = socket.create_connection((HOST, PORT), timeout=5)
        # The bridge's mutual code handshake (see ble_bridge.py): prove the code, then check the bridge's proof.
        key = re.sub(r"[^0-9A-Za-z]", "", code).upper().encode()
        challenge = self._frame()
        if len(challenge) != 20 or not challenge.startswith(b"OBH2"):
            sys.exit("not the bridge (no challenge)")
        nonce_b, nonce_c = challenge[4:], os.urandom(16)
        answer = nonce_c + hmac.new(key, b"orbit-client" + nonce_b + nonce_c, hashlib.sha256).digest()
        self.s.sendall(struct.pack(">H", len(answer)) + answer)
        reply = self._frame()
        want = bytes([0xFE]) + hmac.new(key, b"orbit-bridge" + nonce_c + nonce_b, hashlib.sha256).digest()
        if not hmac.compare_digest(reply, want):
            sys.exit("bridge rejected the code or answered wrongly")
        self.s.settimeout(0.3)
        self.buf = b""

    def _frame(self) -> bytes:
        return self._exact(struct.unpack(">H", self._exact(2))[0])

    def _exact(self, n: int) -> bytes:
        out = b""
        while len(out) < n:
            chunk = self.s.recv(n - len(out))
            if not chunk:
                sys.exit("bridge closed during the handshake")
            out += chunk
        return out

    def send(self, ftype: int, payload: bytes = b""):
        f = frame(ftype, payload)
        print(f"-> {f.hex()}", flush=True)
        self.s.sendall(struct.pack(">H", len(f)) + f)

    def drain(self, seconds: float):
        end = time.time() + seconds
        while time.time() < end:
            try:
                chunk = self.s.recv(4096)
                if not chunk:
                    print("bridge closed", flush=True)
                    return
                self.buf += chunk
            except socket.timeout:
                pass
            while len(self.buf) >= 2:
                n = struct.unpack(">H", self.buf[:2])[0]
                if len(self.buf) < 2 + n:
                    break
                msg, self.buf = self.buf[2:2 + n], self.buf[2 + n:]
                tag, data = msg[0], msg[1:]
                t = (data[0] << 8 | data[1]) if len(data) >= 2 else -1
                pl = data[4:-2] if len(data) >= 6 else b""
                print(f"<- c{tag} type=0x{t:04x} payload={pl.hex()} raw={data.hex()}", flush=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--code", required=True, help="the code ble_bridge.py was started with")
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("read-config")
    sub.add_parser("support")
    r = sub.add_parser("raw"); r.add_argument("type"); r.add_argument("payload", nargs="?", default=""); r.add_argument("--wait", type=float, default=3)
    m = sub.add_parser("measure"); m.add_argument("kind", type=int); m.add_argument("--seconds", type=float, default=40)
    a = ap.parse_args()
    b = Bridge(a.code)
    if a.cmd == "read-config":
        b.send(0x0207, bytes([0x43, 0x46])); b.drain(4)
    elif a.cmd == "support":
        b.send(0x0201, bytes([0x47, 0x46])); b.drain(3)
    elif a.cmd == "raw":
        b.send(int(a.type, 16), bytes.fromhex(a.payload)); b.drain(a.wait)
    elif a.cmd == "measure":
        b.send(0x032F, bytes([1, a.kind])); b.drain(a.seconds)
        b.send(0x032F, bytes([0, a.kind])); b.drain(3)


if __name__ == "__main__":
    main()
