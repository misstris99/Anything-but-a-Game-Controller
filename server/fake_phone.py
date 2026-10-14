"""
Fake phone client for testing the server and game without a real phone.
Connects to a room and sends tilt data in a slow circle pattern.

Usage:
    python fake_phone.py ROOM_CODE
    python fake_phone.py ROOM_CODE ws://192.168.1.5:8000/ws

Run it multiple times (different terminals) to simulate several players.
"""

import asyncio
import json
import math
import sys

import websockets

# ── Configuration ────────────────────────────────────────────────
SERVER_URL    = "ws://localhost:8000/ws"  # default server address
SEND_RATE     = 30     # messages per second
CIRCLE_SPEED  = 0.05   # how fast the tilt angle rotates (radians/tick)
TILT_STRENGTH = 2.0    # max tilt value sent


async def run(room: str, url: str) -> None:
    async with websockets.connect(url) as ws:
        await ws.send(json.dumps({"type": "join", "room": room}))

        resp = json.loads(await ws.recv())
        if resp.get("type") == "error":
            print(f"Error: {resp['message']}")
            return

        pid   = resp["playerId"]
        color = resp["color"]
        print(f"Joined room {room} as {pid} (color {color})")
        print("Sending circular tilt data… Ctrl+C to stop.\n")

        angle = 0.0
        try:
            while True:
                x = math.sin(angle) * TILT_STRENGTH
                y = math.cos(angle) * TILT_STRENGTH
                msg = {"type": "tilt", "x": round(x, 2), "y": round(y, 2)}
                await ws.send(json.dumps(msg))
                angle += CIRCLE_SPEED
                await asyncio.sleep(1.0 / SEND_RATE)
        except KeyboardInterrupt:
            print("Stopped.")


def main() -> None:
    if len(sys.argv) < 2:
        print("Usage: python fake_phone.py ROOM_CODE [SERVER_URL]")
        sys.exit(1)

    room = sys.argv[1]
    url  = sys.argv[2] if len(sys.argv) > 2 else SERVER_URL

    asyncio.run(run(room, url))


if __name__ == "__main__":
    main()
