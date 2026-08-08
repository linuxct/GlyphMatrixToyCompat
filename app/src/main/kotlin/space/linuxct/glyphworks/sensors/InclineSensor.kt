package space.linuxct.glyphworks.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import space.linuxct.glyphworks.core.InclineMath
import space.linuxct.glyphworks.core.InclinePort

/**
 * Inclination source for the Level toy, from TYPE_GRAVITY (falling back to
 * TYPE_ACCELEROMETER, which also carries gravity, on devices without it).
 *
 * TYPE_LINEAR_ACCELERATION — what [space.linuxct.glyphworks.sensors.TiltSensor]
 * uses — is deliberately NOT an option here: it has gravity subtracted out, so
 * a phone resting at any angle reads ~0 and inclination is unrecoverable.
 *
 * Self-managing like the other sensor adapters: registers on the first poll and
 * unregisters after 5 s without one, dropping its readings so a stale angle can
 * never be served to a later session.
 *
 * See [InclinePort] for the exact sign convention.
 */
class InclineSensor(app: Context) : InclinePort, SensorEventListener {

    private val sensorManager = app.getSystemService(SensorManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Smoothed gravity vector; only touched on the main looper. */
    private val gravity = FloatArray(3)
    private var haveGravity = false

    @Volatile private var pitch: Float? = null
    @Volatile private var roll: Float? = null
    @Volatile private var lastPollAt = 0L
    private var started = false

    private val idleCheck = object : Runnable {
        override fun run() {
            synchronized(this@InclineSensor) {
                if (System.currentTimeMillis() - lastPollAt > IDLE_STOP_MS) {
                    if (started) {
                        sensorManager?.unregisterListener(this@InclineSensor)
                        started = false
                        haveGravity = false
                        pitch = null
                        roll = null
                    }
                } else {
                    mainHandler.postDelayed(this, IDLE_STOP_MS)
                }
            }
        }
    }

    private fun poll() {
        lastPollAt = System.currentTimeMillis()
        synchronized(this) {
            if (!started) {
                val sensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
                    ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
                    ?: return
                sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME, mainHandler)
                started = true
                mainHandler.removeCallbacks(idleCheck)
                mainHandler.postDelayed(idleCheck, IDLE_STOP_MS)
            }
        }
    }

    override fun pitchDegrees(): Float? {
        poll()
        return pitch
    }

    override fun rollDegrees(): Float? {
        poll()
        return roll
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.values.size < 3) return
        if (haveGravity) {
            // Low-pass: TYPE_GRAVITY is already smooth, but the accelerometer
            // fallback needs it to reject hand tremor and taps.
            for (i in gravity.indices) gravity[i] += ALPHA * (event.values[i] - gravity[i])
        } else {
            // Seed from the first sample so we never ramp up from a fake (0,0,0).
            event.values.copyInto(gravity, endIndex = 3)
            haveGravity = true
        }
        val gx = gravity[0]
        val gy = gravity[1]
        val gz = gravity[2]
        // The conversion itself lives in core so it can be unit-tested off-device;
        // this class only owns registration, smoothing and lifetime. See
        // [InclineMath] for why it is not the textbook atan2(g, gz).
        roll = InclineMath.rollDegrees(gx, gz)
        pitch = InclineMath.pitchDegrees(gy, gz)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        const val ALPHA = 0.2f
        const val IDLE_STOP_MS = 5000L
    }
}
