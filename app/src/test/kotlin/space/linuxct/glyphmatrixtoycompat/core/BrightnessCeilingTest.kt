package space.linuxct.glyphmatrixtoycompat.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class BrightnessCeilingTest {

    @Test
    fun `all dark frame unchanged`() {
        val frame = IntArray(9)
        assertArrayEquals(IntArray(9), BrightnessCeiling.apply(frame, 1f))
    }

    @Test
    fun `max normalizes up`() {
        // The brightest cell is scaled TO the ceiling, so a dim source at the
        // full brightness setting becomes fully bright.
        val frame = intArrayOf(0, 1000, 2000)
        val out = BrightnessCeiling.apply(frame, 1f)
        assertEquals(4095, out[2])
        assertEquals(2047, out[1]) // 1000 * 4095 / 2000
        assertEquals(0, out[0])
    }

    @Test
    fun `max normalizes down at half brightness`() {
        val frame = intArrayOf(4095, 2048)
        val out = BrightnessCeiling.apply(frame, 0.5f)
        assertEquals(2048, out[0])
        assertEquals(1024, out[1])
    }

    @Test
    fun `zero brightness blanks`() {
        val out = BrightnessCeiling.apply(intArrayOf(4095, 100), 0f)
        assertArrayEquals(intArrayOf(0, 0), out)
    }
}
