package space.linuxct.glyphmatrixtoycompat.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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
    fun `a lit cell never goes dark`() {
        // 1 * 0.05 rounds to 0, which would drop the cell off the panel; the
        // MIN_LIT floor is what keeps a design's structure legible when dim.
        val out = BrightnessScale.scale(intArrayOf(1, 300, MAX_BRIGHTNESS), 0.05f)
        assertTrue("every lit cell must stay lit: ${out.toList()}", out.all { it > 0 })
    }

    @Test
    fun `dark cells are never lit by the floor`() {
        assertArrayEquals(IntArray(9), BrightnessScale.scale(IntArray(9), 0.05f))
        val out = BrightnessScale.scale(intArrayOf(0, MAX_BRIGHTNESS, 0), 0.1f)
        assertEquals(0, out[0])
        assertEquals(0, out[2])
    }

    @Test
    fun `brightness is clamped to the valid range`() {
        val frame = intArrayOf(MAX_BRIGHTNESS, 2048)
        assertArrayEquals(frame, BrightnessScale.scale(frame, 5f))
        // Negative clamps to 0, where only the floor is left standing.
        assertTrue(BrightnessScale.scale(frame, -1f).all { it > 0 })
    }
}
