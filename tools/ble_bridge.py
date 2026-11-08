"""Developer-only Mac BLE bridge: relays a TK5 ring to the Orbit Health app over TCP.

Wire format both ways: [len u16 BE][payload].
  client -> bridge: raw command bytes, written to be940001 with response.
  bridge -> client: [char tag u8 (1 = be940001, 3 = be940003)][indication bytes].
Mutual code handshake first, client proves first (adb reverse lets every app on the phone reach this port, and a fake
bridge on the phone must not be able to feed the app readings):
  bridge -> client: b"OBH2" + 16-byte nonce_b
  client -> bridge: nonce_c (16 bytes) + HMAC-SHA256(code, b"orbit-client" + nonce_b + nonce_c)
  bridge checks it in constant time; on failure the client is dropped, nothing reaches the ring, and after 3 failures
  the bridge exits. Otherwise:
  bridge -> client: 0xFE + HMAC-SHA256(code, b"orbit-bridge" + nonce_c + nonce_b)
The code is the one Orbit Health shows under "Use Mac bridge" (Me, shake for Developer options), e.g. ABCDE-FGHJK;
dashes and case don't matter. The bridge never signs anything for a client that hasn't proved the code.
The bridge never sends anything to the ring on its own except subscribing; it only forwards client bytes.
Loopback-only by design: binds 127.0.0.1 and serves a single client at a time, so no other LAN host can write
frames to the ring. The emulator reaches it at 10.0.2.2; a phone on USB after `adb reverse tcp:7777 tcp:7777`.
Run: python3 -I tools/ble_bridge.py --code ABCDE-FGHJK   (force-stop SmartHealth first)
"""
import argparse, asyncio, hashlib, hmac, os, re, struct, sys
from bleak import BleakClient, BleakScanner

C1 = "be940001-7333-be46-b7ae-689e71722bd5"
C3 = "be940003-7333-be46-b7ae-689e71722bd5"
TAGS = {C1: 1, C3: 3}
PORT = 7777
MAGIC = b"OBH2"
PROOF_TAG = 0xFE
MAX_FAILURES = 3
HANDSHAKE_TIMEOUT_S = 5


def code_key(code: str) -> bytes:
    """The code without dashes or spaces, upper case (as BridgeRingTransport.codeKey)."""
    return re.sub(r"[^0-9A-Za-z]", "", code).upper().encode()


def mac(code: str, label: bytes, data: bytes) -> bytes:
    return hmac.new(code_key(code), label + data, hashlib.sha256).digest()


async def find_and_connect():
    last = None
    for attempt in range(3):
        print(f"scan attempt {attempt + 1}", flush=True)
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
    raise RuntimeError("ring not found")


async def main(code: str):
    state = {"ring": None, "failures": 0}
    clients = set()
    stop = asyncio.Event()

    async def read_frame(reader):
        n = struct.unpack(">H", await reader.readexactly(2))[0]
        return await reader.readexactly(n)

    def send_frame(writer, payload: bytes):
        writer.write(struct.pack(">H", len(payload)) + payload)

    async def client_proves_code(reader, writer) -> bool:
        """Challenge, then the client's proof; only a client that knows the code gets the bridge's proof back."""
        nonce_b = os.urandom(16)
        send_frame(writer, MAGIC + nonce_b)
        await writer.drain()
        answer = await asyncio.wait_for(read_frame(reader), HANDSHAKE_TIMEOUT_S)
        if len(answer) != 48:
            return False
        nonce_c, proof = answer[:16], answer[16:]
        if not hmac.compare_digest(proof, mac(code, b"orbit-client", nonce_b + nonce_c)):
            return False
        send_frame(writer, bytes([PROOF_TAG]) + mac(code, b"orbit-bridge", nonce_c + nonce_b))
        await writer.drain()
        return True

    def make_cb(uuid):
        tag = TAGS[uuid]

        def cb(_, data):
            payload = bytes([tag]) + bytes(data)
            print(f"ring->app c{tag} {bytes(data).hex()}", flush=True)
            msg = struct.pack(">H", len(payload)) + payload
            for w in list(clients):
                w.write(msg)
        return cb

    async def ensure_ring():
        """(Re)connect and resubscribe when the ring dropped the link (it closes idle connections)."""
        r = state["ring"]
        if r is not None and r.is_connected:
            return r
        if r is not None:
            print("ring link lost; reconnecting", flush=True)
            try:
                await r.disconnect()
            except Exception:
                pass
        r = await find_and_connect()
        for u in (C1, C3):
            await r.start_notify(u, make_cb(u))
        state["ring"] = r
        print("ring connected and subscribed", flush=True)
        return r

    await ensure_ring()

    async def handle(reader, writer):
        if clients:
            print("rejecting second client", flush=True)
            writer.close()
            return
        print("client connected", writer.get_extra_info("peername"), flush=True)
        try:
            ok = await client_proves_code(reader, writer)
        except (asyncio.IncompleteReadError, ConnectionError, asyncio.TimeoutError):
            ok = False
        if not ok:
            state["failures"] += 1
            print(f"client didn't prove the code; dropped ({state['failures']}/{MAX_FAILURES})", flush=True)
            writer.close()
            if state["failures"] >= MAX_FAILURES:
                print("too many failed attempts; stopping. Restart with the code the app shows.", flush=True)
                stop.set()
            return
        clients.add(writer)
        try:
            ring = await ensure_ring()
            while True:
                data = await read_frame(reader)
                print(f"app->ring {data.hex()}", flush=True)
                await ring.write_gatt_char(C1, data, response=True)
        except (asyncio.IncompleteReadError, ConnectionError):
            pass
        except Exception as e:  # BLE error: drop this client; the next one triggers a reconnect
            print("ble error", type(e).__name__, e, flush=True)
        finally:
            clients.discard(writer)
            writer.close()
            print("client disconnected", flush=True)

    server = await asyncio.start_server(handle, "127.0.0.1", PORT)
    print(f"bridge listening on 127.0.0.1:{PORT}", flush=True)
    try:
        async with server:
            await stop.wait()
    finally:
        try:
            if state["ring"] is not None:
                await state["ring"].disconnect()
        except Exception:
            pass


if __name__ == "__main__":
    ap = argparse.ArgumentParser(description="Relay a TK5 ring to Orbit Health over loopback TCP.")
    ap.add_argument("--code", required=True, help="the code Orbit Health shows under 'Use Mac bridge'")
    args = ap.parse_args()
    try:
        asyncio.run(main(args.code))
    except KeyboardInterrupt:
        pass
