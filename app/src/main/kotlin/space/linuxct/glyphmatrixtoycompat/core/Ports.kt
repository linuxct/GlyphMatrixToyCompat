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
    /**
     * Instantaneous charge power in watts, or null whenever it cannot be
     * trusted: not charging, the platform does not expose a current reading, or
     * the numbers are implausible. Always a positive magnitude — OEM sign
     * conventions for CURRENT_NOW disagree, so the sign is discarded.
     */
    fun chargeWatts(): Float?
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

/**
 * Static inclination of the device, derived from the gravity vector — unlike
 * [TiltPort], which reads TYPE_LINEAR_ACCELERATION (gravity removed) and so
 * reads ~0 at every resting angle.
 *
 * Sign convention, in the standard Android device frame (+X = right edge,
 * +Y = top edge, +Z = out of the screen), with g the gravity vector:
 *
 *  - [rollDegrees] = atan2(gx, gz): 0 when the device lies flat on its back;
 *    POSITIVE when its RIGHT edge is the LOW edge, negative when its LEFT edge
 *    is low. +-90 when stood on a long edge.
 *  - [pitchDegrees] = atan2(gy, gz): 0 when flat on its back; POSITIVE when
 *    the device's TOP edge is the LOW edge (leaning away from an upright
 *    reader), negative when its BOTTOM edge is low. -90 when stood upright.
 *
 * Both are in -180..180 and both read near +-180 with the device face down.
 * Mnemonic: the sign always points at the edge gravity runs toward, so a ball
 * rolling on the display moves toward +roll in X and toward +pitch in device Y
 * (which is UP the screen, i.e. toward matrix row 0).
 *
 * Null means no reading has landed yet, or the device has neither a gravity nor
 * an accelerometer sensor.
 */
interface InclinePort {
    fun pitchDegrees(): Float?
    fun rollDegrees(): Float?
}

interface LightPort {
    /**
     * Ambient illuminance in lux, or null when unavailable (no light sensor, or
     * no reading has landed yet — TYPE_LIGHT is an on-change sensor, so the
     * first poll after registering usually comes back empty).
     */
    fun lux(): Float?
}

enum class ConnectionState { WIFI, CELLULAR, AIRPLANE, NONE }

interface ConnectivityPort {
    fun state(): ConnectionState
}

interface LocationPort {
    /** Last known (latitude, longitude) in degrees, or null when unavailable. */
    fun latLon(): Pair<Double, Double>?
}

/** Side-effect port for the Timer screen: backstop alarm + completion chime/notification. */
interface TimerSignalPort {
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
    val incline: InclinePort,
    val light: LightPort,
    val connectivity: ConnectivityPort,
    val location: LocationPort,
    val timer: TimerSignalPort,
)
