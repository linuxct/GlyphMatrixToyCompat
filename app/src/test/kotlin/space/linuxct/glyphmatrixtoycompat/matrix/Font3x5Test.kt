package space.linuxct.glyphmatrixtoycompat.matrix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import space.linuxct.glyphmatrixtoycompat.GoldenAscii

class Font3x5Test {
    @Test
    fun `all needed glyphs exist`() {
        "0123456789ADEHKMNPSTW%-+?:. ".forEach {
            assertTrue("missing glyph '$it'", Font3x5.has(it))
        }
    }

    @Test
    fun `stacked clock digits fit 13`() {
        // Two digits (3+1+3 = 7 wide) must fit with the layout used by clock
        // screens: x = 3..9.
        assertEquals(7, Font3x5.stringWidth("12"))
        val c = MatrixCanvas(13)
        Font3x5.drawString(c, "12", 3, 1, 4095)
        Font3x5.drawString(c, "45", 3, 7, 4095)
        GoldenAscii.check("font_13_clock_1245", c.copyOut(), 13)
    }

    @Test
    fun `digit sampler golden`() {
        val top = MatrixCanvas(25)
        Font3x5.drawString(top, "01234", 2, 3, 4095)
        Font3x5.drawString(top, "56789", 2, 10, 4095)
        Font3x5.drawString(top, "HTKMN", 2, 17, 4095)
        GoldenAscii.check("font_25_sampler", top.copyOut(), 25)
    }
}
