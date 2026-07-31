package space.linuxct.glyphmatrixtoycompat.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import space.linuxct.glyphmatrixtoycompat.matrix.MAX_BRIGHTNESS

class BrightnessScaleTest {

    /**
     * THE regression this rework exists for. The old max-normalising
     * implementation rescaled every frame until its brightest cell hit the
     * brightness setting, so a frame of nothing but mid-grey came out as full
     * white and grey was literally unreachable.
     */
    @Test
    fun `grey stays grey at full brightness`() {
        val frame = IntArray(9) { 2048 }
        val out = BrightnessScale.scale(frame, 1f)
        assertTrue("no cell may be brightened: ${out.toList()}", out.all { it == 2048 })
    }

    @Test
    fun `grey holds a constant ratio to white at every level`() {
        val frame = intArrayOf(MAX_BRIGHTNESS, 2048)
        for (b in listOf(0.1f, 0.5f, 1.0f)) {
            val out = BrightnessScale.scale(frame, b)
            // Half as bright as white at every setting, to within rounding.
            val ratio = out[1].toFloat() / out[0]
            assertTrue("grey/white was $ratio at brightness $b", ratio in 0.49f..0.51f)
            val white = (MAX_BRIGHTNESS * b).toDouble()
            assertEquals("white tracks the setting at $b", white, out[0].toDouble(), 1.0)
        }
    }

    @Test
    fun `a lit cell never goes dark`() {
        // 1 * 0.05 rounds to 0, which would drop the cell off the panel; the
        // MIN_LIT floor is what keeps a design's structure legible when dim.
        val out = BrightnessScale.scale(intArrayOf(1, 300, MAX_BRIGHTNESS), 0.05f)
        assertTrue("every lit cell must stay lit: ${out.toList()}", out.all { it > 0 })
    }

    /**
     * The floor's whole job, at both ends of what a user can actually reach: the
     * slider bottoms out at 0.05 and auto-brightness at [AutoBrightness.FLOOR].
     * Every value that survives a round trip through `nearestLevel` is checked,
     * plus the single count that rounds to zero first.
     */
    @Test
    fun `no lit cell reaches zero at the lowest reachable brightness`() {
        val frame = IntArray(MAX_BRIGHTNESS) { it + 1 }
        for (b in listOf(0.05f, AutoBrightness.FLOOR)) {
            val out = BrightnessScale.scale(frame, b)
            val dark = out.indices.filter { out[it] == 0 }
            assertTrue(
                "cells ${dark.take(5)} went dark at brightness $b",
                dark.isEmpty(),
            )
        }
    }

    /**
     * THE regression this second rework exists for.
     *
     * `MIN_LIT` used to be 63 and used to apply at *every* brightness, so a frame
     * containing any cell below 63 took the scaling path even at 1.0 and had
     * those cells lifted to 63. Every anti-aliased edge in the app emits values
     * in that range — `MatrixCanvas.discSoft`, `ring`, `arcRing`, and
     * `TimerScreen`'s `MAX_BRIGHTNESS * cover` sand surface — so a smooth
     * fade-out was being squared off into a 1.5 % step in art that was not being
     * scaled at all. Nothing is scaled at full brightness, so nothing may change.
     */
    @Test
    fun `full brightness leaves an anti-aliased edge byte-identical`() {
        // A soft edge as the canvas primitives actually produce one: full inside,
        // a coverage ramp through the sub-63 range, dark outside.
        val edge = intArrayOf(MAX_BRIGHTNESS, 2048, 205, 62, 31, 8, 1, 0)
        val out = BrightnessScale.scale(edge, 1f)
        assertSame("a frame that is not being scaled must not be copied", edge, out)
        assertArrayEquals(intArrayOf(MAX_BRIGHTNESS, 2048, 205, 62, 31, 8, 1, 0), out)
    }

    @Test
    fun `dark cells are never lit by the floor`() {
        assertArrayEquals(IntArray(9), BrightnessScale.scale(IntArray(9), 0.05f))
        val out = BrightnessScale.scale(intArrayOf(0, MAX_BRIGHTNESS, 0), 0.1f)
        assertEquals(0, out[0])
        assertEquals(0, out[2])
    }

    /**
     * The hot path: at full brightness, art that follows the house rule (peak
     * 4095, everything else a ratio of it) comes out byte-identical AND
     * uncopied — this runs on every pushed frame.
     */
    @Test
    fun `full brightness on 4095-peaked art is the input itself`() {
        val frame = intArrayOf(MAX_BRIGHTNESS, 2048, 700, 0)
        assertSame(frame, BrightnessScale.scale(frame, 1f))
    }

    @Test
    fun `brightness is clamped to the valid range`() {
        val frame = intArrayOf(MAX_BRIGHTNESS, 2048)
        assertArrayEquals(frame, BrightnessScale.scale(frame, 5f))
        // Negative clamps to 0, where only the floor is left standing.
        assertTrue(BrightnessScale.scale(frame, -1f).all { it > 0 })
    }
}
