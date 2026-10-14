/*
 * game.js — Ball-maze game controlled by phone tilt over WebSocket.
 * All physics and rendering live here. The server only relays messages.
 */

/* ── Tuning constants (tweak these) ──────────────────────────── */
var TILT_SENSITIVITY = 0.002;   // tilt-to-acceleration factor (grid-units / frame²)
var FRICTION         = 0.94;    // velocity damping per frame (lower = more drag)
var BALL_RADIUS      = 0.28;    // ball size as a fraction of one cell
var BOOST_MULTIPLIER = 2.5;     // speed factor during a flick boost
var BOOST_FRAMES     = 15;      // how long a flick boost lasts (frames)
var DEBUG_KEY_FORCE  = 3.0;     // simulated tilt strength from arrow keys

/* ── Canvas ──────────────────────────────────────────────────── */
var canvas = document.getElementById('maze-canvas');
var ctx    = canvas.getContext('2d');
var cellSize = 0;

function resize() {
    var hud  = document.getElementById('hud');
    var maxW = window.innerWidth - 32;
    var maxH = window.innerHeight - hud.offsetHeight - 48;
    cellSize = Math.min(Math.floor(maxW / MAZE_COLS), Math.floor(maxH / MAZE_ROWS));
    canvas.width  = cellSize * MAZE_COLS;
    canvas.height = cellSize * MAZE_ROWS;
}
window.addEventListener('resize', resize);

/* ── Theme colors (read once from CSS variables) ─────────────── */
var CLR = {};

function loadColors() {
    var s = getComputedStyle(document.documentElement);
    CLR.bg     = s.getPropertyValue('--bg').trim();
    CLR.fg     = s.getPropertyValue('--fg').trim();
    CLR.wall   = s.getPropertyValue('--wall').trim();
    CLR.accent = s.getPropertyValue('--accent').trim();
    CLR.muted  = s.getPropertyValue('--muted').trim();
}

/* ── Players ─────────────────────────────────────────────────── */
var players = {};
// Each player: { x, y, velX, velY, tiltX, tiltY, color, boost, won }

function addPlayer(id, color) {
    players[id] = {
        x: MAZE_START.col + 0.5,
        y: MAZE_START.row + 0.5,
        velX: 0, velY: 0,
        tiltX: 0, tiltY: 0,
        color: color,
        boost: 0,
        won: false
    };
}

function respawn(id) {
    var p = players[id];
    if (!p) return;
    p.x = MAZE_START.col + 0.5;
    p.y = MAZE_START.row + 0.5;
    p.velX = 0;
    p.velY = 0;
    p.won = false;
    document.getElementById('win-banner').style.display = 'none';
}

/* ── Collision detection (all in grid coordinates) ───────────── */

function isWall(gx, gy) {
    var col = Math.floor(gx);
    var row = Math.floor(gy);
    if (col < 0 || col >= MAZE_COLS || row < 0 || row >= MAZE_ROWS) return true;
    return MAZE[row][col] === 1;
}

function hitsWall(cx, cy) {
    // Check the four cardinal edge-points of the ball
    var r = BALL_RADIUS - 0.01; // tiny inset avoids float-precision false positives
    return isWall(cx - r, cy) || isWall(cx + r, cy) ||
           isWall(cx, cy - r) || isWall(cx, cy + r);
}

/* ── Physics (per player, per frame) ─────────────────────────── */

function updatePlayer(p) {
    if (p.won) return;

    var sens = (p.boost > 0) ? TILT_SENSITIVITY * BOOST_MULTIPLIER : TILT_SENSITIVITY;

    p.velX += p.tiltX * sens;
    p.velY += p.tiltY * sens;
    p.velX *= FRICTION;
    p.velY *= FRICTION;

    if (p.boost > 0) p.boost--;

    // Resolve X movement first, then Y (prevents corner-sticking)
    var nx = p.x + p.velX;
    if (hitsWall(nx, p.y)) {
        p.velX = 0;
    } else {
        p.x = nx;
    }

    var ny = p.y + p.velY;
    if (hitsWall(p.x, ny)) {
        p.velY = 0;
    } else {
        p.y = ny;
    }

    // Win check: ball center is inside the exit cell
    if (Math.floor(p.x) === MAZE_EXIT.col && Math.floor(p.y) === MAZE_EXIT.row) {
        p.won = true;
        document.getElementById('win-banner').style.display = 'block';
    }
}

/* ── Rendering ───────────────────────────────────────────────── */

function draw() {
    // Background (also the path color)
    ctx.fillStyle = CLR.bg;
    ctx.fillRect(0, 0, canvas.width, canvas.height);

    // Walls
    ctx.fillStyle = CLR.wall;
    for (var r = 0; r < MAZE_ROWS; r++) {
        for (var c = 0; c < MAZE_COLS; c++) {
            if (MAZE[r][c] === 1) {
                ctx.fillRect(c * cellSize, r * cellSize, cellSize, cellSize);
            }
        }
    }

    // Exit marker (accent-colored square inside the cell)
    var pad = Math.floor(cellSize * 0.15);
    ctx.fillStyle = CLR.accent;
    ctx.fillRect(
        MAZE_EXIT.col * cellSize + pad,
        MAZE_EXIT.row * cellSize + pad,
        cellSize - pad * 2,
        cellSize - pad * 2
    );

    // Start marker (subtle outline)
    ctx.strokeStyle = CLR.muted;
    ctx.lineWidth = 1;
    ctx.strokeRect(
        MAZE_START.col * cellSize + pad,
        MAZE_START.row * cellSize + pad,
        cellSize - pad * 2,
        cellSize - pad * 2
    );

    // Player balls
    for (var id in players) {
        var p  = players[id];
        var px = p.x * cellSize;
        var py = p.y * cellSize;
        var pr = BALL_RADIUS * cellSize;

        ctx.beginPath();
        ctx.arc(px, py, pr, 0, Math.PI * 2);
        ctx.fillStyle = p.color;
        ctx.fill();

        if (p.won) {
            ctx.strokeStyle = CLR.accent;
            ctx.lineWidth = 3;
            ctx.stroke();
        }
    }
}

/* ── WebSocket ───────────────────────────────────────────────── */
var ws = null;

function connect() {
    var url = 'ws://' + window.location.host + '/ws';
    ws = new WebSocket(url);

    ws.onopen = function () {
        setStatus('connected');
        ws.send(JSON.stringify({ type: 'create' }));
    };

    ws.onmessage = function (e) {
        handleMessage(JSON.parse(e.data));
    };

    ws.onclose = function () { setStatus('disconnected'); };
    ws.onerror = function () { setStatus('error'); };
}

function handleMessage(msg) {
    switch (msg.type) {
        case 'created':
            document.getElementById('room-code').textContent = msg.room;
            var port = window.location.port || '8000';
            document.getElementById('ws-url').textContent =
                'ws://' + msg.lanIp + ':' + port + '/ws   room ' + msg.room;
            break;

        case 'player_joined':
            addPlayer(msg.playerId, msg.color);
            updatePlayerCount();
            break;

        case 'player_left':
            delete players[msg.playerId];
            updatePlayerCount();
            break;

        case 'tilt':
            if (players[msg.playerId]) {
                players[msg.playerId].tiltX = msg.x;
                players[msg.playerId].tiltY = msg.y;
            }
            break;

        case 'flick':
            if (players[msg.playerId]) {
                players[msg.playerId].boost = BOOST_FRAMES;
            }
            break;

        case 'shake':
            respawn(msg.playerId);
            break;

        case 'button':
            if (msg.name === 'reset') respawn(msg.playerId);
            break;

        case 'pong':
            // Latency measurement — wired up in stage 4
            break;
    }
}

/* ── HUD helpers ─────────────────────────────────────────────── */

function setStatus(state) {
    var el = document.getElementById('connection-status');
    el.textContent = state.charAt(0).toUpperCase() + state.slice(1);
    el.className = state;
}

function updatePlayerCount() {
    var n = Object.keys(players).length;
    document.getElementById('player-count').textContent =
        n + ' player' + (n !== 1 ? 's' : '');
}

/* ── Debug: arrow keys ───────────────────────────────────────── 
   Creates a "keyboard" player on first press so you can test
   without a phone or fake_phone.py.                            */
var keysDown = {};

document.addEventListener('keydown', function (e) {
    if (e.key.startsWith('Arrow')) {
        keysDown[e.key] = true;
        e.preventDefault();
    }
});

document.addEventListener('keyup', function (e) {
    keysDown[e.key] = false;
});

function handleDebugKeys() {
    var anyArrow = keysDown.ArrowUp || keysDown.ArrowDown ||
                   keysDown.ArrowLeft || keysDown.ArrowRight;

    // Lazy-create a keyboard player when arrows are first pressed
    if (!players['keyboard'] && anyArrow) {
        addPlayer('keyboard', CLR.muted || '#888888');
        updatePlayerCount();
    }

    var p = players['keyboard'];
    if (!p) return;

    p.tiltX = 0;
    p.tiltY = 0;
    if (keysDown.ArrowLeft)  p.tiltX -= DEBUG_KEY_FORCE;
    if (keysDown.ArrowRight) p.tiltX += DEBUG_KEY_FORCE;
    if (keysDown.ArrowUp)    p.tiltY -= DEBUG_KEY_FORCE;
    if (keysDown.ArrowDown)  p.tiltY += DEBUG_KEY_FORCE;
}

/* ── Game loop ───────────────────────────────────────────────── */

function loop() {
    handleDebugKeys();
    for (var id in players) {
        updatePlayer(players[id]);
    }
    draw();
    requestAnimationFrame(loop);
}

/* ── Boot ─────────────────────────────────────────────────────── */
resize();
loadColors();
connect();
loop();
