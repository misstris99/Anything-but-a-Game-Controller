package com.trisha.mazecontroller;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.util.Log;

/**
 * SensorReader — reads the accelerometer and gyroscope and converts them
 * into a tilt (x, y) value the game understands.
 *
 * How it works:
 *   The accelerometer measures the force of gravity plus any motion the phone makes.
 *   When the phone is lying flat, gravity reads (0, 0, -9.8).
 *   When you tilt it forward, X and Y change.
 *   We use X and Y directly as the tilt signal: tiltX drives the ball left/right,
 *   tiltY drives it forward/backward.
 *
 *   The gyroscope isn't used for tilt yet — it's registered here and shown on screen
 *   so you can see its values. We'll use it for flick detection in a later substage.
 *
 * Why a separate class?
 *   ControllerActivity handles UI. SensorReader handles hardware.
 *   Keeping them separate means you can test or replace one without touching the other.
 */
public class SensorReader implements SensorEventListener {

    private static final String TAG = "SensorReader";

    // How much the raw accelerometer value is scaled before being sent as tilt.
    // The accelerometer on a tilted phone gives roughly ±5 m/s² on X and Y.
    // TILT_SENSITIVITY in game.js is 0.002, so a raw value of 5 produces
    // 5 * SCALE = tiltX, which is then multiplied by 0.002 each frame.
    // fake_phone.py uses max TILT_STRENGTH = 2.0, so we target the same range.
    // 5 m/s² * 0.4 = 2.0 — that matches fake_phone.py's max.
    private static final float ACCEL_SCALE = 0.4f;

    // Low-pass filter factor: smooths out sensor jitter.
    // 0 = completely frozen, 1 = no smoothing (raw values).
    // 0.15 gives a noticeable but not laggy response.
    private static final float SMOOTHING = 0.15f;

    private final SensorManager sensorManager;
    private Sensor accelerometer;
    private Sensor gyroscope;

    // Smoothed accelerometer values (updated each sensor tick)
    private float smoothX = 0f;
    private float smoothY = 0f;
    private float smoothZ = 0f;

    // Last raw gyroscope values (for display only in 2A)
    private float gyroX = 0f;
    private float gyroY = 0f;
    private float gyroZ = 0f;

    // The caller provides this callback to receive tilt and raw sensor data.
    // Using an interface here would add indirection for no benefit — a simple
    // nested class works fine for one caller.
    public interface Listener {
        /**
         * Called on every accelerometer update (~50 Hz on most phones).
         *
         * @param tiltX  scaled tilt to send as x in the tilt message (right = positive)
         * @param tiltY  scaled tilt to send as y in the tilt message (down = positive)
         * @param rawAx  raw accelerometer x (m/s²) — for debug display
         * @param rawAy  raw accelerometer y (m/s²) — for debug display
         * @param rawAz  raw accelerometer z (m/s²) — for debug display
         * @param rawGx  raw gyroscope x (rad/s)   — for debug display
         * @param rawGy  raw gyroscope y (rad/s)   — for debug display
         * @param rawGz  raw gyroscope z (rad/s)   — for debug display
         */
        void onSensorUpdate(float tiltX, float tiltY,
                            float rawAx, float rawAy, float rawAz,
                            float rawGx, float rawGy, float rawGz);
    }

    private final Listener listener;

    public SensorReader(Context context, Listener listener) {
        this.listener = listener;
        sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);

        // getDefaultSensor returns null if the sensor doesn't exist on this device.
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        gyroscope     = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);

        if (accelerometer == null) {
            // Without an accelerometer we can't control the ball at all.
            // Log it loudly — the activity will check and show an error.
            Log.e(TAG, "No accelerometer found on this device.");
        }
        if (gyroscope == null) {
            // Gyroscope is optional for now. Log a warning but continue.
            Log.w(TAG, "No gyroscope found — gyro readings will be zero.");
        }
    }

    /** Returns true if the device has an accelerometer. */
    public boolean hasAccelerometer() {
        return accelerometer != null;
    }

    /**
     * Start listening to sensors. Call from Activity.onResume().
     * SENSOR_DELAY_GAME gives us ~50 Hz updates, which is plenty for smooth control.
     */
    public void register() {
        if (accelerometer != null) {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME);
        }
        if (gyroscope != null) {
            sensorManager.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_GAME);
        }
    }

    /**
     * Stop listening to sensors. Call from Activity.onPause().
     * If we don't unregister here, the sensors keep draining the battery
     * even after the user switches to another app.
     */
    public void unregister() {
        sensorManager.unregisterListener(this);
    }

    // ── SensorEventListener callbacks ─────────────────────────────

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            handleAccelerometer(event.values);
        } else if (event.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
            handleGyroscope(event.values);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // We don't need to react to accuracy changes for this use case.
    }

    // ── Private helpers ────────────────────────────────────────────

    private void handleAccelerometer(float[] values) {
        // values[0] = X axis (left/right when phone is portrait)
        // values[1] = Y axis (up/down when phone is portrait)
        // values[2] = Z axis (into/out of screen)

        // Low-pass filter: blend the new reading with the running average.
        // This smooths out jitter without adding much lag.
        smoothX = smoothX + SMOOTHING * (values[0] - smoothX);
        smoothY = smoothY + SMOOTHING * (values[1] - smoothY);
        smoothZ = smoothZ + SMOOTHING * (values[2] - smoothZ);

        // Map accelerometer axes to game axes.
        //
        // Phone in portrait, tilting right → values[0] increases (positive X).
        // game.js: positive tiltX moves the ball right. ✓ Same sign, no flip needed.
        //
        // Phone in portrait, tilting toward you (top away from you) → values[1] decreases.
        // game.js: positive tiltY moves the ball DOWN (y increases downward on canvas).
        // So we negate Y: tilting top-away increases tiltY → ball goes down. ✓
        float tiltX =  smoothX * ACCEL_SCALE;
        float tiltY = -smoothY * ACCEL_SCALE;

        listener.onSensorUpdate(
            tiltX, tiltY,
            smoothX, smoothY, smoothZ,
            gyroX,  gyroY,  gyroZ
        );
    }

    private void handleGyroscope(float[] values) {
        // Just store the gyro values; handleAccelerometer fires more often and
        // is the one that calls the listener, so the gyro values get included there.
        gyroX = values[0];
        gyroY = values[1];
        gyroZ = values[2];
    }
}
