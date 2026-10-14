"""
Relay server for the maze controller game.
Routes WebSocket messages between phones and the browser game.
No game logic here — just rooms, routing, and ping/pong.
"""

import json
import pathlib
import random
import socket
import string

from fastapi import FastAPI, WebSocket, WebSocketDisconnect
from fastapi.staticfiles import StaticFiles

# ── Configuration ────────────────────────────────────────────────
PORT = 8000  # server listen port

# Colors assigned to players in join order
PLAYER_COLORS = ["#D4943A", "#4A8C7F", "#C25B56", "#5B8EBD", "#8B6DAF", "#6DAF8B"]

app = FastAPI()

# Room storage: code -> { game: WebSocket|None, players: { id: { socket, color } } }
rooms: dict[str, dict] = {}


# ── Helpers ──────────────────────────────────────────────────────

def get_lan_ip() -> str:
    """Find this machine's LAN IP (no data is actually sent)."""
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return "127.0.0.1"


def make_room_code() -> str:
    """Generate a unique 4-character room code."""
    while True:
        code = "".join(random.choices(string.ascii_uppercase + string.digits, k=4))
        if code not in rooms:
            return code


def pick_player_id(room: dict) -> str:
    """Lowest unused player ID (p1, p2, …)."""
    i = 1
    while f"p{i}" in room["players"]:
        i += 1
    return f"p{i}"


def pick_color(room: dict) -> str:
    """First unused color from the palette."""
    used = {p["color"] for p in room["players"].values()}
    for c in PLAYER_COLORS:
        if c not in used:
            return c
    return PLAYER_COLORS[0]


async def send(ws: WebSocket, data: dict) -> None:
    """Send JSON to a socket. Silently skips closed connections."""
    try:
        await ws.send_text(json.dumps(data))
    except Exception:
        pass


async def notify_phones(room: dict, data: dict, exclude: str | None = None) -> None:
    """Broadcast a message to every connected phone in a room."""
    for pid, info in room["players"].items():
        if pid != exclude and info["socket"] is not None:
            await send(info["socket"], data)


# ── WebSocket endpoint ──────────────────────────────────────────

@app.websocket("/ws")
async def ws_endpoint(ws: WebSocket):
    await ws.accept()

    role: str | None = None        # "game" or "phone"
    room_code: str | None = None
    player_id: str | None = None

    try:
        # First message decides role
        first = json.loads(await ws.receive_text())

        if first["type"] == "create":
            # ── Game client: create a new room ───────────────────
            role = "game"
            room_code = make_room_code()
            rooms[room_code] = {"game": ws, "players": {}}
            lan_ip = get_lan_ip()
            await send(ws, {"type": "created", "room": room_code, "lanIp": lan_ip})
            print(f"[room {room_code}] created  (LAN {lan_ip})")

        elif first["type"] == "join":
            # ── Phone client: join an existing room ──────────────
            role = "phone"
            room_code = first.get("room", "")

            if room_code not in rooms:
                await send(ws, {"type": "error", "message": "Room not found"})
                await ws.close()
                return

            room = rooms[room_code]
            requested_id = first.get("playerId")

            if requested_id and requested_id in room["players"]:
                # Reconnecting: reclaim the old slot and color
                player_id = requested_id
                color = room["players"][player_id]["color"]
                room["players"][player_id]["socket"] = ws
            else:
                # Brand-new player
                player_id = pick_player_id(room)
                color = pick_color(room)
                room["players"][player_id] = {"socket": ws, "color": color}

            await send(ws, {"type": "joined", "playerId": player_id, "color": color})

            joined_msg = {"type": "player_joined", "playerId": player_id, "color": color}
            if room["game"]:
                await send(room["game"], joined_msg)
            await notify_phones(room, joined_msg, exclude=player_id)

            print(f"[room {room_code}] {player_id} joined  ({color})")

        else:
            await ws.close()
            return

        # ── Main message loop ────────────────────────────────────
        while True:
            raw = await ws.receive_text()
            msg = json.loads(raw)

            if msg["type"] == "ping":
                # Reply immediately so each side measures its own RTT
                await send(ws, {"type": "pong", "ts": msg["ts"]})

            elif role == "game" and msg["type"] == "haptic":
                # Game tells a specific phone to vibrate
                room = rooms.get(room_code)
                target = msg.get("playerId")
                if room and target in room["players"]:
                    sock = room["players"][target]["socket"]
                    if sock:
                        await send(sock, {"type": "haptic", "kind": msg["kind"]})

            elif role == "phone" and msg["type"] in ("tilt", "flick", "shake", "button"):
                # Relay phone input to the game, tagged with this player's id
                msg["playerId"] = player_id
                room = rooms.get(room_code)
                if room and room["game"]:
                    await send(room["game"], msg)

    except WebSocketDisconnect:
        pass
    except Exception as e:
        print(f"[ws error] {e}")
    finally:
        if room_code not in rooms:
            return

        room = rooms[room_code]

        if role == "game":
            # Game left: tell all phones, delete the room
            await notify_phones(room, {"type": "game_left"})
            del rooms[room_code]
            print(f"[room {room_code}] game left — room deleted")

        elif role == "phone" and player_id:
            # Phone left: keep the slot (reconnection), clear the socket
            if player_id in room["players"]:
                room["players"][player_id]["socket"] = None
            left_msg = {"type": "player_left", "playerId": player_id}
            if room["game"]:
                await send(room["game"], left_msg)
            await notify_phones(room, left_msg, exclude=player_id)
            print(f"[room {room_code}] {player_id} left")


# ── Static file serving ─────────────────────────────────────────
# Serves game/ as the site root, so http://ip:8000/ loads index.html
GAME_DIR = str(pathlib.Path(__file__).resolve().parent.parent / "game")
app.mount("/", StaticFiles(directory=GAME_DIR, html=True), name="game")


if __name__ == "__main__":
    import uvicorn
    print(f"\n  Open in browser:  http://{get_lan_ip()}:{PORT}\n")
    uvicorn.run(app, host="0.0.0.0", port=PORT)
