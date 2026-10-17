# Anything but a Game Controller

A phone becomes a motion controller for a ball-balancing maze game that runs in a laptop browser. Tilt the phone to roll the ball. The phone and the laptop talk through a small Python server over the local Wi-Fi.

## Demo

Debug APK: `releases/`

## Features

| Feature | Status |
|---|---|
| Tilt the phone to roll the ball | done |
| Pair by QR code, with manual IP + room code fallback | done |
| Sensor data streamed over WebSocket (about 30 messages per second) | done |
| Maze game in the browser, controlled by the phone | done |
| Calibration button (sets the neutral position) | done |
| Reset Ball button on the phone | done |
| Connection status ON game page


## Tech stack

| Part | What | Why |
|---|---|---|
| Phone app | Native Android, Java, minSdk 26 | Direct access to the sensors, and Java is what I know best |
| WebSocket client | OkHttp | Small, well-known, handles the socket in the background |
| QR scanning | ZXing (journeyapps zxing-android-embedded) | Ready-made scanner screen, no camera code to write |
| Server | Python, FastAPI + uvicorn, one file (`server/main.py`) | Built-in WebSocket support, and it also serves the game page |
| Game | Plain HTML, vanilla JavaScript, Canvas | No frameworks and no build step |

## How it works

```
Phone (Android)  ---ws--->  Server (FastAPI)  ---ws--->  Game page (browser)
 sensors, QR scan           rooms, relay only              physics, walls, win check
```

The server only keeps track of rooms and passes messages along. All game logic (physics, wall collisions, win check) is in `game/game.js`.

A room looks like this on the server:

```
room = { game: <socket>, players: { playerId: { socket, color } } }
```

This is already shaped for several phones, even though multiplayer is not built yet.

## Pairing flow

1. The game page opens a WebSocket to the server and sends `create`.
2. The server makes a 4-letter room code, finds the laptop's Wi-Fi address, and replies with `created` (room code and LAN IP).
3. The game page shows a QR code containing `ws://<lan-ip>:8000/ws?room=XXXX`, and the same text for manual entry.
4. The phone scans the QR code (or the user types the IP and room code), opens its own WebSocket and sends `join`.
5. The server replies `joined` with a `playerId` and a colour, and tells the game page that a player joined.
6. From then on the phone sends tilt readings, and the server forwards each one to the game with the `playerId` added.

The QR code uses the laptop's LAN IP, not `localhost`, because `localhost` on the phone would mean the phone itself.

## Message protocol

All messages are JSON over WebSocket.

| Direction | `type` | Extra fields | Purpose |
|---|---|---|---|
| game to server | `create` | none | Open a new room |
| server to game | `created` | `room`, `lanIp` | Room code and LAN IP for the QR |
| phone to server | `join` | `room`, optional `playerId` | Join a room; `playerId` lets a reconnecting phone keep its slot |
| server to phone | `joined` | `playerId`, `color` | Confirms the join |
| phone to server to game | `tilt` | `x`, `y` | Tilt reading, about 30 per second |
| phone to server to game | `button` | `name` | On-screen button, currently `"reset"` |
| server to game and phones | `player_joined`, `player_left` | `playerId` | Room events |
| server to phones | `game_left` | none | The game page closed |

Every message from a phone reaches the game tagged with its `playerId`. The server assigns the id, so a phone cannot pick or fake one.

## Tilt and calibration

- The phone reads the accelerometer, then smooths it with a simple low-pass filter (`SMOOTHING` in `SensorReader.java`).
- Calibration stores the current smoothed reading as an offset. Every later reading has that offset subtracted before it is scaled and sent, so the position you held when you pressed Calibrate becomes "level".
- The phone sends tilt on a fixed timer (about 30 per second) using the latest value, not once per sensor event, so the network load stays steady.

## Running it

### Requirements
- Python 3.10 or newer
- Android Studio and an Android phone (8.0 or newer) with USB debugging on
- Phone and laptop on the same Wi-Fi. A phone hotspot works well if the Wi-Fi blocks devices from reaching each other.

### Server and game
```
python -m venv venv
venv\Scripts\activate          (Mac/Linux: source venv/bin/activate)
pip install -r server/requirements.txt
python server/main.py
```
Open the address the server prints (for example `http://192.168.x.x:8000`) in the laptop browser. On Windows, allow Python through the firewall on private networks the first time.

### Android app
1. Open the `android/` folder in Android Studio and let Gradle sync.
2. Plug in the phone and press Run.
3. To build an APK: Build, Build Bundle(s) / APK(s), Build APK(s). Copy the debug APK into `releases/`.
4. In the app, scan the QR code on the game page, or type the IP and room code.

### Testing without a phone
```
python server/fake_phone.py
```
It joins a room and sends tilt in a slow circle. Run it in several terminals to simulate several players. The arrow keys also move the ball, as a debug input.

## Where to change things

| What | Where |
|---|---|
| Server port, send rate | constants at the top of `server/main.py` and `SocketClient.java` |
| Tilt sensitivity, smoothing | `ACCEL_SCALE` and `SMOOTHING` in `SensorReader.java` |
| Ball physics (friction, sensitivity) | constants at the top of `game/game.js` |
| Maze layout | `game/maze.js` (a hand-written grid, edit it directly) |
| Colours and fonts, web | `game/theme.css` |
| Colours, phone | `android/app/src/main/res/values/colors.xml` |
| Phone UI text | `android/app/src/main/res/values/strings.xml` |

## Known limits

- Works on a local network only. There is no internet relay, and the connection uses plain `ws://`, so cleartext traffic is allowed in the Android manifest on purpose.
- Some campus and hostel Wi-Fi networks stop devices from reaching each other. Use a phone hotspot in that case.
- Calibration is not saved, so it resets to zero when the app is closed.
- The game page does not handle window resizing. Reload the page after resizing.
- The maze is a fixed hand-written layout, not generated.
- The server keeps rooms in memory, so restarting it ends all rooms.

## Project layout

```
server/    main.py, requirements.txt, fake_phone.py
game/      index.html, game.js, maze.js, theme.css
android/   Android Studio project (package com.trisha.mazecontroller)
releases/  debug APK
```