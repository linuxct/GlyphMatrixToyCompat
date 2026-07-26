package space.linuxct.glyphmatrixtoycompat.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import space.linuxct.glyphmatrixtoycompat.core.TiltPort

/**
 * Linear-acceleration tilt source for the ambient tilt-ball background.
 * Self-managing: starts listening on first poll and unregisters after 5 s
 * without polls, so it costs nothing unless the tilt background is selected.
 */
class TiltSensor(app: Context) : TiltPort, SensorEventListener {

    private val sensorManager = app.getSystemService(SensorManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var x = 0f
    @Volatile private var y = 0f
    @Volatile private var lastPollAt = 0L
    private var started = false

    private val idleCheck = object : Runnable {
        override fun run() {
            synchronized(this@TiltSensor) {
                if (System.currentTimeMillis() - lastPollAt > IDLE_STOP_MS) {
                    if (started) {
                        sensorManager?.unregisterListener(this@TiltSensor)
                        started = false
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
                val sensor = sensorManager?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
                    ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
                    ?: return
                sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME, mainHandler)
                started = true
                mainHandler.removeCallbacks(idleCheck)
                mainHandler.postDelayed(idleCheck, IDLE_STOP_MS)
            }
        }
    }

    override fun tiltX(): Float {
        poll()
        return x
    }

    override fun tiltY(): Float {
        poll()
        return y
    }

    override fun onSensorChanged(event: SensorEvent) {
        x = event.values[0]
        y = event.values[1]
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        const val IDLE_STOP_MS = 5000L
    }
}
