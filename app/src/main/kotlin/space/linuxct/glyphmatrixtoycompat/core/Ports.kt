package space.linuxct.glyphmatrixtoycompat.core

/**
 * Data-source ports consumed by screens. Screens depend ONLY on these
 * interfaces (never on android.*), which keeps every renderer runnable and
 * golden-testable on the JVM. Android implementations live in util/, sensors/
 * and audio/.
 */

interface ClockPort {
    fun nowMillis(): Long
    fun hourOfDay(): Int // 0..23
    fun minute(): Int
    fun second(): Int
    /** Local-time offset from UTC in minutes (incl. DST), e.g. +120 for CEST. */
    fun utcOffsetMinutes(): Int
    /** 1-based day of the year. */
    fun dayOfYear(): Int
}

interface RandomPort {
    fun nextInt(bound: Int): Int
    fun nextFloat(): Float
}

interface BatteryPort {
    fun levelPercent(): Int
    /** True only for BATTERY_STATUS_CHARGING (not merely plugged in). */
    fun isCharging(): Boolean
}

interface SpeedPort {
    /** Cumulative received bytes since boot (TrafficStats semantics). */
    fun totalRxBytes(): Long
}

interface SpectrumPort {
    /**
     * [n] frequency bands normalized 0..1, or null when capture is
     * unavailable (permission missing or engine failure). Silence returns
     * near-zero bands, not null.
     */
    fun bands(n: Int): FloatArray?
}

interface AzimuthPort {
    /** Magnetic-north azimuth in degrees 0..360 (0 = north), or null until sensors deliver. */
    fun azimuthDegrees(): Float?
}

interface ShakePort {
    /** Milliseconds since the last detected shake, Long.MAX_VALUE if none yet. */
    fun millisSinceLastShake(): Long
}

interface TiltPort {
    /** Lateral linear acceleration (m/s^2), +x = right edge down, +y = bottom edge down. */
    fun tiltX(): Float
    fun tiltY(): Float
}

enum class ConnectionState { WIFI, CELLULAR, AIRPLANE, NONE }

interface ConnectivityPort {
    fun state(): ConnectionState
}

interface LocationPort {
    /** Last known (latitude, longitude) in degrees, or null when unavailable. */
    fun latLon(): Pair<Double, Double>?
}

/** Side-effect port for the Tea Time screen: backstop alarm + completion chime/notification. */
interface TeaSignalPort {
    fun scheduleAlarm(atEpochMillis: Long)
    fun cancelAlarm()
    fun chime()
}

class Ports(
    val clock: ClockPort,
    val random: RandomPort,
    val battery: BatteryPort,
    val speed: SpeedPort,
    val spectrum: SpectrumPort,
    val azimuth: AzimuthPort,
    val shake: ShakePort,
    val tilt: TiltPort,
    val connectivity: ConnectivityPort,
    val location: LocationPort,
    val tea: TeaSignalPort,
)
