package space.linuxct.glyphmatrixtoycompat.matrix

import org.junit.Assert.assertEquals
import org.junit.Test
import space.linuxct.glyphmatrixtoycompat.GoldenAscii

class MatrixCanvasTest {

    @Test
    fun `out of bounds drawing is ignored`() {
        val c = MatrixCanvas(13)
        c.set(-1, 0, 4095)
        c.set(0, -1, 4095)
        c.set(13, 0, 4095)
        c.set(0, 13, 4095)
        c.line(-5, -5, 20, 20, 4095) // clipped but in-bounds diagonal drawn
        assertEquals(4095, c.get(0, 0))
        assertEquals(4095, c.get(12, 12))
    }

    @Test
    fun `values clamp to matrix range`() {
        val c = MatrixCanvas(13)
        c.set(1, 1, 99999)
        c.set(2, 1, -7)
        assertEquals(MAX_BRIGHTNESS, c.get(1, 1))
        assertEquals(0, c.get(2, 1))
    }

    @Test
    fun `light never darkens`() {
        val c = MatrixCanvas(13)
        c.light(3, 3, 3000)
        c.light(3, 3, 100)
        assertEquals(3000, c.get(3, 3))
    }

    @Test
    fun `shapes sampler 13`() {
        val c = MatrixCanvas(13)
        c.circle(6, 6, 6, 1200)
        c.ray(6, 6, 0f, 5f, 4095)
        c.ray(6, 6, 90f, 4f, 2400)
        GoldenAscii.check("canvas_13_shapes", c.copyOut(), 13)
    }

    @Test
    fun `progress ring quarters 13`() {
        for ((name, pct) in listOf("25" to 0.25f, "50" to 0.5f, "75" to 0.75f)) {
            val c = MatrixCanvas(13)
            c.arcRing(6f, 6f, 5f, 6.2f, 0f, 360f * pct, 4095)
            GoldenAscii.check("canvas_13_ring_$name", c.copyOut(), 13)
        }
    }

    @Test
    fun `soft disc extremes 13`() {
        val small = MatrixCanvas(13)
        small.discSoft(6f, 6f, 1.5f, 4095)
        GoldenAscii.check("canvas_13_disc_small", small.copyOut(), 13)
        val big = MatrixCanvas(13)
        big.discSoft(6f, 6f, 5.8f, 4095)
        GoldenAscii.check("canvas_13_disc_big", big.copyOut(), 13)
    }

    @Test
    fun `shapes sampler 25`() {
        val c = MatrixCanvas(25)
        c.circle(12, 12, 12, 1200)
        c.ring(12f, 12f, 5f, 6f, 2400)
        c.ray(12, 12, 45f, 11f, 4095)
        GoldenAscii.check("canvas_25_shapes", c.copyOut(), 25)
    }
}
