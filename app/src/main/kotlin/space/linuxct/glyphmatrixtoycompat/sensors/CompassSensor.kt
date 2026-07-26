package space.linuxct.glyphmatrixtoycompat.sensors

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import space.linuxct.glyphmatrixtoycompat.core.AzimuthPort

/**
 * Accelerometer + magnetometer fusion with a low-pass filter, plus magnetic
 * declination from the last known location when a location permission is
 * granted (skipped otherwise). Self-managing lifecycle: registers on first
 * poll, unregisters after 5 s without polls.
 */
class CompassSensor(private val app: Context) : AzimuthPort, SensorEventListener {

    private val sensorManager = app.getSystemService(SensorManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val gravity = FloatArray(3)
    private val geomagnetic = FloatArray(3)
    private var haveGravity = false
    private var haveGeo = false

    @Volatile private var azimuth: Float? = null
    @Volatile private var declination = 0f
    @Volatile private var lastPollAt = 0L
    private var started = false

    private val idleCheck = object : Runnable {
        override fun run() {
            synchronized(this@CompassSensor) {
                if (System.currentTimeMillis() - lastPollAt > IDLE_STOP_MS) {
                    if (started) {
                        sensorManager?.unregisterListener(this@CompassSensor)
                        started = false
                        azimuth = null
                        haveGravity = false
                        haveGeo = false
                    }
                } else {
                    mainHandler.postDelayed(this, IDLE_STOP_MS)
                }
            }
        }
    }

    override fun azimuthDegrees(): Float? {
        lastPollAt = System.currentTimeMillis()
        synchronized(this) {
            if (!started) {
                val accel = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
                val mag = sensorManager?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
                if (accel == null || mag == null) return null
                sensorManager.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME, mainHandler)
                sensorManager.registerListener(this, mag, SensorManager.SENSOR_DELAY_GAME, mainHandler)
                started = true
                loadDeclination()
                mainHandler.removeCallbacks(idleCheck)
                mainHandler.postDelayed(idleCheck, IDLE_STOP_MS)
            }
        }
        return azimuth?.let { (it + declination + 360f) % 360f }
    }

    private fun loadDeclination() {
        val fine = app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = app.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return
        try {
            val lm = app.getSystemService(LocationManager::class.java) ?: return
            val location = lm.allProviders.firstNotNullOfOrNull { p ->
                try {
                    lm.getLastKnownLocation(p)
                } catch (_: SecurityException) {
                    null
                }
            } ?: return
            declination = GeomagneticField(
                location.latitude.toFloat(),
                location.longitude.toFloat(),
                location.altitude.toFloat(),
                System.currentTimeMillis(),
            ).declination
        } catch (_: Exception) {
            // Declination stays 0 — magnetic north is close enough.
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                lowPass(event.values, gravity)
                haveGravity = true
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                lowPass(event.values, geomagnetic)
                haveGeo = true
            }
        }
        if (haveGravity && haveGeo) {
            val r = FloatArray(9)
            val i = FloatArray(9)
            if (SensorManager.getRotationMatrix(r, i, gravity, geomagnetic)) {
                val orientation = FloatArray(3)
                SensorManager.getOrientation(r, orientation)
                val deg = Math.toDegrees(orientation[0].toDouble()).toFloat()
                azimuth = (deg + 360f) % 360f
            }
        }
    }

    private fun lowPass(input: FloatArray, output: FloatArray) {
        for (i in input.indices) {
            output[i] += ALPHA * (input[i] - output[i])
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        const val ALPHA = 0.15f
        const val IDLE_STOP_MS = 5000L
    }
}
