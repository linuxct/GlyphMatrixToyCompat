package space.linuxct.glyphmatrixtoycompat.core

import space.linuxct.glyphmatrixtoycompat.core.design.Design

/**
 * Data-source ports consumed by screens. Screens depend ONLY on these
 * interfaces (never on android.*), which keeps every renderer runnable and
 * golden-testable on the JVM. Android implementations live in util/, sensors/,
 * audio/ and designs/.
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
 * +Y = top edge, +Z = out of the screen), with g the gravity vector — see
 * [InclineMath], which owns the derivation and the reasoning:
 *
 *  - [rollDegrees]: 0 when the device lies flat, EITHER face up or face down;
 *    POSITIVE when the edge that appears on the RIGHT to whoever is looking at
 *    the matrix is the LOW edge. +-90 when stood on a long edge.
 *  - [pitchDegrees]: 0 when flat, either way up; POSITIVE when the device's TOP
 *    edge is the LOW edge, negative when its BOTTOM edge is low. +-90 when
 *    stood on a short edge.
 *
 * Both are in -90..90: these are angles away from horizontal, so a device more
 * than 90 deg from flat simply saturates. Face down — the only orientation the
 * Glyph Matrix can be read in — is NOT a separate case; it reads 0 flat, and
 * roll is mirrored so the sign still tracks what the viewer sees.
 *
 * Mnemonic: the sign always points at the edge gravity runs toward, so a ball
 * rolling on the matrix moves toward +roll in X and toward +pitch in device Y
 * (which is UP the matrix, i.e. toward row 0).
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

/**
 * The user design the Custom screen plays.
 *
 * A design is frame data in a file, and [Prefs] can only hold scalars — so
 * unlike every other setting a screen reads, this one cannot travel through
 * prefs. It travels as a port for the same reason the sensors do: screens must
 * not import android.*, and reading a design means touching the filesystem.
 *
 * [selected] performs file I/O and is therefore called from `onActivate` (the
 * scheduler thread), never from a ticker and never from `glyph-io`. Null means
 * "nothing to draw" — no design chosen, or the chosen one is gone — which the
 * screen answers with its placeholder rather than a dark matrix.
 */
interface DesignPort {
    fun selected(): Design?
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
    val design: DesignPort,
)
