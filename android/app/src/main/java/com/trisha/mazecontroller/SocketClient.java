package com.trisha.mazecontroller;

import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * SocketClient — manages WebSocket networking to the game relay server.
 *
 * Responsibilities:
 *   1. Connects to ws://<host>:<port>/ws using OkHttp.
 *   2. Sends the initial join handshake with room code and reconnect playerId.
 *   3. Transmits tilt updates at a pinned 30 Hz rate using a background executor.
 *   4. Periodically pings the server to calculate round-trip latency.
 *   5. Dispatches server messages (joined, error, game_left, haptic, pong) to Listener.
 */
public class SocketClient {

    private static final String TAG = "SocketClient";

    // ── Configurable constants ─────────────────────────────────────
    public static final int DEFAULT_PORT = 8000;         // Default FastAPI server port
    public static final int SEND_RATE_HZ = 30;           // 30 messages/sec (matches fake_phone.py)
    private static final long PING_INTERVAL_MS = 2000;   // Send ping every 2 seconds to check latency

    // ── Connection state ───────────────────────────────────────────
    public enum State {
        DISCONNECTED,
        CONNECTING,
        CONNECTED
    }

    public interface Listener {
        void onStateChanged(State newState);
        void onJoined(String playerId, String colorHex);
        void onDisconnected(String reason);
        void onError(String message);
        void onGameLeft();
        void onHaptic(String kind);
        void onLatencyUpdate(long latencyMs);
    }

    private final Listener listener;
    private final OkHttpClient httpClient;

    private WebSocket webSocket;
    private ScheduledExecutorService executor;

    private State currentState = State.DISCONNECTED;
    private String currentRoom = "";

    // In-memory player ID preserved for reconnection while app runs
    private String savedPlayerId = null;

    // Latest tilt readings written by the sensor listener, read by the 30 Hz loop
    private volatile float latestTiltX = 0f;
    private volatile float latestTiltY = 0f;

    public SocketClient(Listener listener) {
        this.listener = listener;
        this.httpClient = new OkHttpClient.Builder()
                .readTimeout(0, TimeUnit.MILLISECONDS) // Keep-alive socket
                .build();
    }

    /** Returns current connection state. */
    public State getState() {
        return currentState;
    }

    /** Returns the saved player ID (e.g. "p1"), or null if not yet joined. */
    public String getSavedPlayerId() {
        return savedPlayerId;
    }

    /** Updates the latest tilt values to be sent on the next 30 Hz tick. */
    public void setLatestTilt(float x, float y) {
        this.latestTiltX = x;
        this.latestTiltY = y;
    }

    /**
     * Connect to the server at the given host and room.
     * Host can be an IP ("192.168.1.5") or "IP:PORT".
     */
    public synchronized void connect(String hostInput, String roomCode) {
        if (currentState == State.CONNECTING || currentState == State.CONNECTED) {
            disconnect();
        }

        String trimmedHost = hostInput.trim();
        String trimmedRoom = roomCode.trim().toUpperCase(Locale.US);

        if (trimmedHost.isEmpty() || trimmedRoom.isEmpty()) {
            notifyError("Host and Room Code cannot be empty.");
            return;
        }

        this.currentRoom = trimmedRoom;
        setState(State.CONNECTING);

        // Normalize URL: strip ws:// or http:// if user entered it
        if (trimmedHost.startsWith("ws://")) {
            trimmedHost = trimmedHost.substring(5);
        } else if (trimmedHost.startsWith("http://")) {
            trimmedHost = trimmedHost.substring(7);
        }

        // Add default port 8000 if no port was specified
        if (!trimmedHost.contains(":")) {
            trimmedHost = trimmedHost + ":" + DEFAULT_PORT;
        }

        String wsUrl = "ws://" + trimmedHost + "/ws";
        Log.d(TAG, "Connecting to: " + wsUrl + " (room: " + currentRoom + ")");

        Request request;
        try {
            request = new Request.Builder().url(wsUrl).build();
        } catch (IllegalArgumentException e) {
            notifyError("Invalid server URL: " + wsUrl);
            setState(State.DISCONNECTED);
            return;
        }

        webSocket = httpClient.newWebSocket(request, new SocketListener());
    }

    /** Disconnects the socket and shuts down the transmission loop. */
    public synchronized void disconnect() {
        stopSendLoop();

        if (webSocket != null) {
            try {
                webSocket.close(1000, "User disconnected");
            } catch (Exception ignored) {
            }
            webSocket = null;
        }

        if (currentState != State.DISCONNECTED) {
            setState(State.DISCONNECTED);
            if (listener != null) {
                listener.onDisconnected("Disconnected");
            }
        }
    }

    // ── Internal loops ─────────────────────────────────────────────

    private synchronized void startSendLoop() {
        stopSendLoop();

        executor = Executors.newSingleThreadScheduledExecutor();

        // 30 Hz tilt update loop (period = ~33.3 ms)
        long periodMs = 1000 / SEND_RATE_HZ;
        executor.scheduleAtFixedRate(this::sendTiltMessage, 0, periodMs, TimeUnit.MILLISECONDS);

        // 2-second ping loop for latency tracking
        executor.scheduleAtFixedRate(this::sendPingMessage, 1000, PING_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private synchronized void stopSendLoop() {
        if (executor != null && !executor.isShutdown()) {
            executor.shutdownNow();
            executor = null;
        }
    }

    private void sendTiltMessage() {
        if (webSocket == null || currentState != State.CONNECTED) return;

        try {
            // Round to 2 decimal places to match fake_phone.py format
            double rx = Math.round(latestTiltX * 100.0) / 100.0;
            double ry = Math.round(latestTiltY * 100.0) / 100.0;

            JSONObject msg = new JSONObject();
            msg.put("type", "tilt");
            msg.put("x", rx);
            msg.put("y", ry);

            webSocket.send(msg.toString());
        } catch (JSONException e) {
            Log.e(TAG, "Failed to serialize tilt message", e);
        }
    }

    private void sendPingMessage() {
        if (webSocket == null || currentState != State.CONNECTED) return;

        try {
            JSONObject msg = new JSONObject();
            msg.put("type", "ping");
            msg.put("ts", System.currentTimeMillis());
            webSocket.send(msg.toString());
        } catch (JSONException e) {
            Log.e(TAG, "Failed to serialize ping message", e);
        }
    }

    /**
     * Sends a button message to reset the player's ball position in the maze.
     * Only sends if currently connected.
     */
    public void sendResetButton() {
        if (webSocket == null || currentState != State.CONNECTED) {
            Log.w(TAG, "Cannot send reset button: socket is not connected");
            return;
        }

        try {
            JSONObject msg = new JSONObject();
            msg.put("type", "button");
            msg.put("name", "reset");

            boolean sent = webSocket.send(msg.toString());
            if (!sent) {
                Log.e(TAG, "Failed to send reset button message: queue full or socket closed");
                notifyError("Failed to send reset command");
            }
        } catch (JSONException e) {
            Log.e(TAG, "Failed to serialize reset button message", e);
            notifyError("JSON error: " + e.getMessage());
        }
    }

    // ── WebSocket listener implementation ──────────────────────────

    private class SocketListener extends WebSocketListener {

        @Override
        public void onOpen(WebSocket ws, Response response) {
            Log.d(TAG, "WebSocket opened. Sending join message for room " + currentRoom);

            try {
                JSONObject joinMsg = new JSONObject();
                joinMsg.put("type", "join");
                joinMsg.put("room", currentRoom);

                // If reconnecting, provide the saved playerId to reclaim the slot
                if (savedPlayerId != null && !savedPlayerId.isEmpty()) {
                    joinMsg.put("playerId", savedPlayerId);
                    Log.d(TAG, "Reconnecting with playerId: " + savedPlayerId);
                }

                ws.send(joinMsg.toString());
            } catch (JSONException e) {
                Log.e(TAG, "Failed to create join message", e);
                notifyError("Internal JSON error");
            }
        }

        @Override
        public void onMessage(WebSocket ws, String text) {
            try {
                JSONObject msg = new JSONObject(text);
                String type = msg.optString("type", "");

                switch (type) {
                    case "joined": {
                        savedPlayerId = msg.getString("playerId");
                        String color = msg.getString("color");
                        Log.i(TAG, "Joined room as " + savedPlayerId + " with color " + color);

                        setState(State.CONNECTED);
                        startSendLoop();

                        if (listener != null) {
                            listener.onJoined(savedPlayerId, color);
                        }
                        break;
                    }

                    case "error": {
                        String errMsg = msg.optString("message", "Error from server");
                        Log.e(TAG, "Server error: " + errMsg);
                        notifyError(errMsg);
                        disconnect();
                        break;
                    }

                    case "game_left": {
                        Log.w(TAG, "Host game disconnected");
                        if (listener != null) {
                            listener.onGameLeft();
                        }
                        disconnect();
                        break;
                    }

                    case "haptic": {
                        // Handled safely — vibration is deferred to Stage 3
                        String kind = msg.optString("kind", "bump");
                        Log.d(TAG, "Haptic event received: " + kind + " (vibration deferred to Stage 3)");
                        if (listener != null) {
                            listener.onHaptic(kind);
                        }
                        break;
                    }

                    case "pong": {
                        long sentTs = msg.optLong("ts", 0);
                        if (sentTs > 0) {
                            long latency = System.currentTimeMillis() - sentTs;
                            if (listener != null) {
                                listener.onLatencyUpdate(latency);
                            }
                        }
                        break;
                    }

                    default:
                        Log.d(TAG, "Unhandled message type: " + type);
                        break;
                }
            } catch (JSONException e) {
                Log.e(TAG, "Failed to parse incoming message: " + text, e);
            }
        }

        @Override
        public void onClosed(WebSocket ws, int code, String reason) {
            Log.d(TAG, "WebSocket closed: " + reason);
            stopSendLoop();
            setState(State.DISCONNECTED);
            if (listener != null) {
                listener.onDisconnected(reason != null && !reason.isEmpty() ? reason : "Disconnected");
            }
        }

        @Override
        public void onFailure(WebSocket ws, Throwable t, Response response) {
            Log.e(TAG, "WebSocket failure", t);
            stopSendLoop();
            setState(State.DISCONNECTED);
            notifyError("Connection failed: " + (t.getMessage() != null ? t.getMessage() : "Unknown error"));
        }
    }

    private void setState(State state) {
        this.currentState = state;
        if (listener != null) {
            listener.onStateChanged(state);
        }
    }

    private void notifyError(String error) {
        if (listener != null) {
            listener.onError(error);
        }
    }
}
