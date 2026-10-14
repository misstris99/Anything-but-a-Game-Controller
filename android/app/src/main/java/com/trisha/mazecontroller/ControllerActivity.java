package com.trisha.mazecontroller;

import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.util.Locale;

/**
 * ControllerActivity — user interface for the maze controller.
 *
 * Responsibilities:
 *   1. Displays live sensor readings (from SensorReader).
 *   2. Handles connection UI (Server IP, Room Code, Connect / Disconnect).
 *   3. Relays calculated tilt values to SocketClient.
 *   4. Displays connection status, assigned player color, and network latency.
 *
 * All networking is delegated to SocketClient; all sensor logic to SensorReader.
 */
public class ControllerActivity extends AppCompatActivity
        implements SensorReader.Listener, SocketClient.Listener {

    private static final String TAG = "ControllerActivity";

    // How often to refresh debug TextViews on screen (every 100 ms = 10 Hz)
    private static final int UI_UPDATE_INTERVAL_MS = 100;

    // Hardware and network delegates
    private SensorReader sensorReader;
    private SocketClient socketClient;

    // Connection UI
    private EditText etServerIp;
    private EditText etRoomCode;
    private Button btnConnect;
    private TextView tvStatus;
    private TextView tvLatency;

    // Sensor debug readouts
    private TextView tvAccel;
    private TextView tvGyro;
    private TextView tvTiltOut;

    // Cached sensor values for throttled UI updates
    private volatile float latestTiltX;
    private volatile float latestTiltY;
    private volatile float latestAx, latestAy, latestAz;
    private volatile float latestGx, latestGy, latestGz;

    private long lastUiUpdateMs = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_controller);

        // Bind connection views
        etServerIp = findViewById(R.id.et_server_ip);
        etRoomCode = findViewById(R.id.et_room_code);
        btnConnect = findViewById(R.id.btn_connect);
        tvStatus   = findViewById(R.id.tv_status);
        tvLatency  = findViewById(R.id.tv_latency);

        // Bind sensor debug views
        tvAccel   = findViewById(R.id.tv_accel);
        tvGyro    = findViewById(R.id.tv_gyro);
        tvTiltOut = findViewById(R.id.tv_tilt_out);

        // Initialize delegates
        sensorReader = new SensorReader(this, this);
        socketClient = new SocketClient(this);

        if (!sensorReader.hasAccelerometer()) {
            Toast.makeText(this,
                    "This device has no accelerometer. Tilt control won't work.",
                    Toast.LENGTH_LONG).show();
        }

        btnConnect.setOnClickListener(v -> toggleConnection());
    }

    // ── Activity lifecycle ─────────────────────────────────────────

    @Override
    protected void onResume() {
        super.onResume();
        // Start reading hardware sensors when activity is visible
        sensorReader.register();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Stop reading hardware sensors when invisible to save battery
        sensorReader.unregister();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (socketClient != null) {
            socketClient.disconnect();
        }
    }

    // ── Connection Actions ─────────────────────────────────────────

    private void toggleConnection() {
        if (socketClient.getState() == SocketClient.State.CONNECTED ||
                socketClient.getState() == SocketClient.State.CONNECTING) {
            socketClient.disconnect();
        } else {
            String host = etServerIp.getText().toString().trim();
            String room = etRoomCode.getText().toString().trim();

            if (host.isEmpty()) {
                Toast.makeText(this, "Enter the server IP address", Toast.LENGTH_SHORT).show();
                etServerIp.requestFocus();
                return;
            }
            if (room.isEmpty()) {
                Toast.makeText(this, "Enter the 4-character room code", Toast.LENGTH_SHORT).show();
                etRoomCode.requestFocus();
                return;
            }

            socketClient.connect(host, room);
        }
    }

    // ── SensorReader.Listener ──────────────────────────────────────

    @Override
    public void onSensorUpdate(float tiltX, float tiltY,
                               float rawAx, float rawAy, float rawAz,
                               float rawGx, float rawGy, float rawGz) {
        // Cache values for UI display
        latestTiltX = tiltX;
        latestTiltY = tiltY;
        latestAx = rawAx; latestAy = rawAy; latestAz = rawAz;
        latestGx = rawGx; latestGy = rawGy; latestGz = rawGz;

        // Feed latest tilt into socket client for 30 Hz transmission loop
        socketClient.setLatestTilt(tiltX, tiltY);

        // Throttle UI layout pass to 10 Hz
        long now = System.currentTimeMillis();
        if (now - lastUiUpdateMs >= UI_UPDATE_INTERVAL_MS) {
            lastUiUpdateMs = now;
            updateDebugDisplay();
        }
    }

    // ── SocketClient.Listener ──────────────────────────────────────

    @Override
    public void onStateChanged(SocketClient.State newState) {
        runOnUiThread(() -> {
            switch (newState) {
                case CONNECTING:
                    btnConnect.setText(R.string.btn_disconnect);
                    btnConnect.setEnabled(true);
                    etServerIp.setEnabled(false);
                    etRoomCode.setEnabled(false);
                    tvStatus.setText(R.string.status_connecting);
                    tvStatus.setTextColor(ContextCompat.getColor(this, R.color.accent));
                    break;

                case CONNECTED:
                    btnConnect.setText(R.string.btn_disconnect);
                    btnConnect.setEnabled(true);
                    etServerIp.setEnabled(false);
                    etRoomCode.setEnabled(false);
                    break;

                case DISCONNECTED:
                    btnConnect.setText(R.string.btn_connect);
                    btnConnect.setEnabled(true);
                    etServerIp.setEnabled(true);
                    etRoomCode.setEnabled(true);
                    tvLatency.setText(R.string.latency_placeholder);
                    tvLatency.setTextColor(ContextCompat.getColor(this, R.color.disabled));
                    break;
            }
        });
    }

    @Override
    public void onJoined(String playerId, String colorHex) {
        runOnUiThread(() -> {
            tvStatus.setText(String.format(Locale.US, "Connected: %s (%s)", playerId, colorHex));
            try {
                int parsedColor = Color.parseColor(colorHex);
                tvStatus.setTextColor(parsedColor);
            } catch (Exception e) {
                tvStatus.setTextColor(ContextCompat.getColor(this, R.color.accent));
            }
            Toast.makeText(this, "Joined as " + playerId, Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    public void onDisconnected(String reason) {
        runOnUiThread(() -> {
            tvStatus.setText(getString(R.string.status_disconnected) + ": " + reason);
            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.disabled));
        });
    }

    @Override
    public void onError(String message) {
        runOnUiThread(() -> {
            tvStatus.setText("Error: " + message);
            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.error));
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        });
    }

    @Override
    public void onGameLeft() {
        runOnUiThread(() -> {
            tvStatus.setText(R.string.status_game_ended);
            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.error));
            Toast.makeText(this, "Host game disconnected.", Toast.LENGTH_LONG).show();
        });
    }

    @Override
    public void onHaptic(String kind) {
        // Log received haptic event — actual vibration will be implemented in Stage 3
        Log.d(TAG, "Received haptic event: " + kind);
    }

    @Override
    public void onLatencyUpdate(long latencyMs) {
        runOnUiThread(() -> {
            tvLatency.setText(String.format(Locale.US, "Latency: %d ms", latencyMs));
            tvLatency.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        });
    }

    // ── Private helpers ────────────────────────────────────────────

    private void updateDebugDisplay() {
        tvAccel.setText(formatXYZ(latestAx, latestAy, latestAz));
        tvGyro.setText(formatXYZ(latestGx, latestGy, latestGz));
        tvTiltOut.setText(String.format(Locale.US, "x: %+.2f  y: %+.2f",
                latestTiltX, latestTiltY));
    }

    private static String formatXYZ(float x, float y, float z) {
        return String.format(Locale.US, "x: %+.2f  y: %+.2f  z: %+.2f", x, y, z);
    }
}
