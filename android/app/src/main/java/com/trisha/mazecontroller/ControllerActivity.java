package com.trisha.mazecontroller;

import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.Locale;

/**
 * ControllerActivity — the one and only Activity for 2A.
 *
 * In this substage it:
 *   1. Creates a SensorReader.
 *   2. Receives sensor updates via the Listener callback.
 *   3. Displays the raw accelerometer, gyroscope, and computed tilt values
 *      on screen so you can verify the mapping before adding the WebSocket.
 *
 * It does NOT open a WebSocket yet — that's 2B.
 *
 * UI work only lives here. No physics, no networking.
 */
public class ControllerActivity extends AppCompatActivity
        implements SensorReader.Listener {

    // How often to update the UI, in milliseconds.
    // The sensor fires ~50 times per second (every 20 ms), but updating the
    // TextViews that often would make the numbers unreadable. Every 100 ms
    // is fast enough to see changes while staying readable.
    private static final int UI_UPDATE_INTERVAL_MS = 100;

    private SensorReader sensorReader;

    private TextView tvAccel;
    private TextView tvGyro;
    private TextView tvTiltOut;

    // The latest values from the sensor — we hold them here and write to
    // the TextViews on a timer rather than on every sensor tick.
    private volatile float latestTiltX;
    private volatile float latestTiltY;
    private volatile float latestAx, latestAy, latestAz;
    private volatile float latestGx, latestGy, latestGz;

    // Tracks when we last updated the UI
    private long lastUiUpdateMs = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_controller);

        tvAccel   = findViewById(R.id.tv_accel);
        tvGyro    = findViewById(R.id.tv_gyro);
        tvTiltOut = findViewById(R.id.tv_tilt_out);

        sensorReader = new SensorReader(this, this);

        if (!sensorReader.hasAccelerometer()) {
            // Show an error and explain to the user — never silently fail.
            Toast.makeText(this,
                "This device has no accelerometer. Tilt control won't work.",
                Toast.LENGTH_LONG).show();
        }
    }

    // ── Activity lifecycle ─────────────────────────────────────────

    @Override
    protected void onResume() {
        super.onResume();
        // Start reading sensors when the screen is visible.
        sensorReader.register();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Stop reading sensors to save battery when the screen is not visible.
        sensorReader.unregister();
    }

    // ── SensorReader.Listener ──────────────────────────────────────

    /**
     * Called by SensorReader on every accelerometer tick (~50 Hz).
     * We store the values and update the UI only at UI_UPDATE_INTERVAL_MS.
     *
     * Why not update UI on every tick?
     * TextView.setText() causes a layout pass, which is expensive.
     * 50 layout passes per second for a debug display is wasteful.
     */
    @Override
    public void onSensorUpdate(float tiltX, float tiltY,
                               float rawAx, float rawAy, float rawAz,
                               float rawGx, float rawGy, float rawGz) {
        // Store the latest values (volatile ensures the UI thread sees them)
        latestTiltX = tiltX;
        latestTiltY = tiltY;
        latestAx = rawAx; latestAy = rawAy; latestAz = rawAz;
        latestGx = rawGx; latestGy = rawGy; latestGz = rawGz;

        long now = System.currentTimeMillis();
        if (now - lastUiUpdateMs >= UI_UPDATE_INTERVAL_MS) {
            lastUiUpdateMs = now;
            updateDebugDisplay();
        }
    }

    // ── Private helpers ────────────────────────────────────────────

    /**
     * Updates the three TextViews with the latest sensor snapshot.
     * onSensorChanged is called on the main thread by Android's SensorManager,
     * so we can safely update Views here without runOnUiThread().
     */
    private void updateDebugDisplay() {
        tvAccel.setText(formatXYZ(latestAx, latestAy, latestAz));
        tvGyro.setText(formatXYZ(latestGx, latestGy, latestGz));
        tvTiltOut.setText(String.format(Locale.US, "x: %+.2f  y: %+.2f",
                latestTiltX, latestTiltY));
    }

    /** Formats three floats as "x: +1.23  y: -0.45  z: +9.81" */
    private static String formatXYZ(float x, float y, float z) {
        return String.format(Locale.US, "x: %+.2f  y: %+.2f  z: %+.2f", x, y, z);
    }
}
