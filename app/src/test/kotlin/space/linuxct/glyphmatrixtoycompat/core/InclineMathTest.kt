package space.linuxct.glyphmatrixtoycompat.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import space.linuxct.glyphmatrixtoycompat.screens.LevelScreen
import kotlin.math.cos
import kotlin.math.sin

/**
 * Raw-gravity coverage for the Level toy's angle derivation.
 *
 * This file exists because the conversion used to live in the Android-only
 * sensor adapter with no JVM tests at all, and the fake port hands the screen
 * *already-computed* degrees — so every existing test fed it a clean 0 and
 * passed while the real device was reading 180 on both axes. The tests below
 * start from gravity vectors, which is the only place that bug was visible.
 */
class InclineMathTest {

    private val G = 9.81f

    /** Gravity for a device tilted [deg] about its X axis, face up or down. */
    private fun pitchVector(deg: Double, faceUp: Boolean): Triple<Float, Float, Float> {
        val r = Math.toRadians(deg)
        val z = (if (faceUp) 1 else -1) * G * cos(r).toFloat()
        return Triple(0f, G * sin(r).toFloat(), z)
    }

    /** Gravity for a device tilted [deg] about its Y axis, face up or down. */
    private fun rollVector(deg: Double, faceUp: Boolean): Triple<Float, Float, Float> {
        val r = Math.toRadians(deg)
        val z = (if (faceUp) 1 else -1) * G * cos(r).toFloat()
        return Triple(G * sin(r).toFloat(), 0f, z)
    }

    private fun pitch(v: Triple<Float, Float, Float>) = InclineMath.pitchDegrees(v.second, v.third)

    private fun roll(v: Triple<Float, Float, Float>) = InclineMath.rollDegrees(v.first, v.third)

    @Test
    fun `face up flat reads dead level`() {
        assertEquals(0f, InclineMath.pitchDegrees(0f, G), 1e-4f)
        assertEquals(0f, InclineMath.rollDegrees(0f, G), 1e-4f)
    }

    @Test
    fun `face down flat reads dead level`() {
        // THE regression test. The Glyph Matrix is on the BACK of the phone, so
        // the toy is only ever looked at face down, where gz is negative. The
        // old atan2(gy, gz) / atan2(gx, gz) returned 180 on both axes here — a
        // combined magnitude of 254 deg that saturated the ball's deflection and
        // pinned it in a corner forever, no matter how flat the desk was.
        assertEquals(0f, InclineMath.pitchDegrees(0f, -G), 1e-4f)
        assertEquals(0f, InclineMath.rollDegrees(0f, -G), 1e-4f)
    }

    @Test
    fun `face down flat is inside the level tolerance`() {
        // The same thing stated the way the user experiences it: lying flat on
        // its face, the toy must call itself level.
        val (gx, gy, gz) = Triple(0f, 0f, -G)
        assertTrue(LevelScreen.isLevel(InclineMath.pitchDegrees(gy, gz), InclineMath.rollDegrees(gx, gz)))
    }

    @Test
    fun `face down tilt keeps the pitch sign and magnitude`() {
        // Top edge low, phone face down: pitch is positive and equals the tilt.
        assertEquals(5f, pitch(pitchVector(5.0, faceUp = false)), 1e-3f)
        assertEquals(20f, pitch(pitchVector(20.0, faceUp = false)), 1e-3f)
        // Bottom edge low is the mirror image.
        assertEquals(-20f, pitch(pitchVector(-20.0, faceUp = false)), 1e-3f)
        // Pitch is NOT mirrored: turning the phone over about its long axis
        // leaves the vertical axis pointing the same way, so both faces agree.
        assertEquals(pitch(pitchVector(20.0, faceUp = true)), pitch(pitchVector(20.0, faceUp = false)), 1e-3f)
    }

    @Test
    fun `roll magnitude survives the flip and the sign mirrors`() {
        val up = roll(rollVector(20.0, faceUp = true))
        val down = roll(rollVector(20.0, faceUp = false))
        assertEquals(20f, up, 1e-3f)
        // Looking at the BACK of the phone, left and right swap, so the same
        // physical tilt reads with the opposite sign — which is what keeps the
        // ball rolling toward the edge that looks low to the viewer.
        assertEquals(-up, down, 1e-3f)
    }

    @Test
    fun `near vertical saturates without NaN`() {
        for (v in listOf(pitchVector(89.9, faceUp = false), pitchVector(89.9, faceUp = true))) {
            val p = pitch(v)
            assertTrue("not finite: $p", p.isFinite())
            assertTrue("did not saturate: $p", p > 85f && p <= 90f)
        }
        // Exactly on edge: gz is 0, which is where a divide-based derivation
        // would blow up. atan2 is total, so this is a clean +-90.
        assertEquals(90f, InclineMath.pitchDegrees(G, 0f), 1e-3f)
        assertEquals(-90f, InclineMath.pitchDegrees(-G, 0f), 1e-3f)
        assertEquals(90f, InclineMath.rollDegrees(G, 0f), 1e-3f)
        assertEquals(-90f, InclineMath.rollDegrees(-G, 0f), 1e-3f)
    }

    @Test
    fun `a zero vector is finite, not NaN`() {
        // Free fall, or the very first accelerometer sample on a broken sensor.
        assertEquals(0f, InclineMath.pitchDegrees(0f, 0f), 1e-4f)
        assertEquals(0f, InclineMath.rollDegrees(0f, 0f), 1e-4f)
    }

    @Test
    fun `every orientation stays in the documented minus 90 to 90 range`() {
        for (deg in -180..180 step 5) {
            for (faceUp in listOf(true, false)) {
                val p = pitch(pitchVector(deg.toDouble(), faceUp))
                val r = roll(rollVector(deg.toDouble(), faceUp))
                assertTrue("pitch $p at $deg", p.isFinite() && p >= -90f && p <= 90f)
                assertTrue("roll $r at $deg", r.isFinite() && r >= -90f && r <= 90f)
            }
        }
    }
}
