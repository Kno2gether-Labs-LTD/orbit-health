"""Debug-only Mac BLE bridge: relays a TK5 ring to the Android emulator app over TCP.

Wire format both ways: [len u16 BE][payload].
  client -> bridge: raw command bytes, written to be940001 with response.
  bridge -> client: [char tag u8 (1 = be940001, 3 = be940003)][indication bytes].
The bridge never sends anything to the ring on its own except subscribing; it only forwards client bytes.
Loopback-only by design: binds 127.0.0.1 (the emulator's 10.0.2.2 maps to host loopback) and serves a single
client at a time, so no other LAN host can write frames to the ring.
Run: python3 -I tools/ble_bridge.py   (force-stop SmartHealth first)
"""
import asyncio, struct, sys
from bleak import BleakClient, BleakScanner

C1 = "be940001-7333-be46-b7ae-689e71722bd5"
C3 = "be940003-7333-be46-b7ae-689e71722bd5"
TAGS = {C1: 1, C3: 3}
PORT = 7777


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


async def main():
    state = {"ring": None}
    clients = set()

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
        clients.add(writer)
        try:
            ring = await ensure_ring()
            while True:
                n = struct.unpack(">H", await reader.readexactly(2))[0]
                data = await reader.readexactly(n)
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
            await server.serve_forever()
    finally:
        try:
            if state["ring"] is not None:
                await state["ring"].disconnect()
        except Exception:
            pass


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        pass
