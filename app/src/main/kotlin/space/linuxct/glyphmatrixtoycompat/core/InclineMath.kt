package space.linuxct.glyphmatrixtoycompat.core

import kotlin.math.abs
import kotlin.math.atan2

/**
 * Gravity vector -> (pitch, roll) for the Level toy. Pure Kotlin, no `android.*`,
 * so the conversion the toy actually depends on is unit-testable — the previous
 * version lived inside the Android-only sensor adapter, had zero JVM coverage,
 * and shipped a bug that made the toy unusable (see below).
 *
 * Input is the gravity vector in the standard Android device frame:
 * +X = right edge, +Y = top edge, +Z = out of the *screen*.
 *
 * ### Why not the textbook `atan2(g, gz)`
 *
 * The Glyph Matrix is on the BACK of the phone, so the only way to look at this
 * toy is with the phone face DOWN — and face down `gz` is negative. The old
 * `atan2(gy, gz)` / `atan2(gx, gz)` therefore read (180, 180) lying dead flat on
 * a desk, a combined magnitude of 254 deg, which saturated the ball's deflection
 * and pinned it in a corner forever. No tolerance can fix that; the derivation
 * was wrong for the only orientation the toy is ever viewed in.
 *
 * Using `abs(gz)` folds the two flat orientations onto the same answer: both
 * face up and face down read (0, 0), and the angle is the tilt away from
 * horizontal in either case. The range narrows from -180..180 to -90..90, which
 * is all the toy ever wanted — a level has nothing to say about a phone that is
 * more than 90 deg from flat.
 *
 * ### The face-down mirror
 *
 * Looking at the *back* of the phone, one horizontal axis is mirrored relative
 * to the screen's coordinate frame: you flip the phone about its long axis, so
 * "up" stays up but left and right swap. [rollDegrees] is therefore negated when
 * `gz < 0`, so the ball still rolls toward the edge that looks low *to someone
 * looking at the matrix*.
 *
 * NEEDS ONE ON-DEVICE CONFIRMATION: which axis mirrors depends on how the matrix
 * panel is physically oriented on the back, which cannot be derived from gravity
 * alone. X is the likely one (a phone is normally turned over about its long
 * axis). To check: hold the phone face down and lift one known edge — the ball
 * must roll toward the edge that is LOW. If left/right is backwards, flip the
 * sign in [rollDegrees]; if up/down is backwards instead, move the negation to
 * [pitchDegrees].
 */
object InclineMath {

    /**
     * Positive when the device's TOP edge is the low edge. Range -90..90.
     * Not mirrored face down: turning the phone over about its long axis leaves
     * the vertical axis pointing the same way.
     */
    fun pitchDegrees(gy: Float, gz: Float): Float = degrees(gy, gz)

    /**
     * Positive when the edge that appears on the RIGHT to whoever is looking at
     * the matrix is the low edge. Range -90..90.
     */
    fun rollDegrees(gx: Float, gz: Float): Float {
        val a = degrees(gx, gz)
        // Face down (gz < 0) the horizontal axis is seen mirrored. Exactly
        // gz == 0 (device on edge) keeps the un-mirrored sign; the tilt is
        // saturated at that point anyway, so the discontinuity is invisible.
        return if (gz < 0f) -a else a
    }

    /**
     * Angle of [g] away from horizontal, using the MAGNITUDE of the vertical
     * axis so face-up and face-down flat both read 0.
     *
     * `atan2` is total: `gz == 0` saturates cleanly at +-90 (or 0 when the whole
     * vector is 0) with no NaN and no division.
     */
    private fun degrees(g: Float, gz: Float): Float =
        Math.toDegrees(atan2(g.toDouble(), abs(gz).toDouble())).toFloat()
}
