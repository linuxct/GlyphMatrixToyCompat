package space.linuxct.glyphmatrixtoycompat.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import space.linuxct.glyphmatrixtoycompat.core.design.DEFAULT_LEVELS
import space.linuxct.glyphmatrixtoycompat.core.design.DesignFrames
import space.linuxct.glyphmatrixtoycompat.core.design.PokemonCodename
import space.linuxct.glyphmatrixtoycompat.matrix.PanelMask

/**
 * The renderer is the model's only eyes, so what is tested here is not that it
 * produces *some* text but that the text agrees with the hardware cell for cell.
 * A preview that disagreed with [PanelMask] would be worse than no preview: it
 * would tell a model its art was fine while the panel clipped it.
 */
class GlyphAsciiPreviewTest {

    // region the mask

    @Test
    fun `a fully lit frame renders exactly the live cells at each geometry`() {
        for (codename in PokemonCodename.entries) {
            val size = codename.size
            val lit = IntArray(size * size) { DesignFrames.MAX_BRIGHTNESS }

            val drawn = GlyphAsciiPreview.render(lit, size)
            val cells = drawn.filter { it != '\n' }

            assertEquals("every cell has a character", size * size, cells.length)
            assertEquals(
                "${codename.codename} draws its LEDs",
                PanelMask.count(size),
                cells.count { it != GlyphAsciiPreview.OFF_PANEL },
            )
        }
    }

    /** The numbers the panel was photographed and counted for. See [PanelMask]. */
    @Test
    fun `the live cell counts are 137 and 489`() {
        assertEquals(137, PanelMask.count(PokemonCodename.BELLSPROUT.size))
        assertEquals(489, PanelMask.count(PokemonCodename.ARBOK.size))
    }

    @Test
    fun `every masked cell is blank however bright it was drawn`() {
        for (codename in PokemonCodename.entries) {
            val size = codename.size
            val lit = IntArray(size * size) { DesignFrames.MAX_BRIGHTNESS }
            val rows = GlyphAsciiPreview.render(lit, size).split("\n")

            assertEquals(size, rows.size)
            for (y in 0 until size) {
                assertEquals(size, rows[y].length)
                for (x in 0 until size) {
                    val onPanel = PanelMask.contains(x, y, size)
                    val char = rows[y][x]
                    if (onPanel) {
                        assertTrue(
                            "($x,$y) at $size is an LED and must be drawn, got '$char'",
                            char != GlyphAsciiPreview.OFF_PANEL,
                        )
                    } else {
                        assertEquals(
                            "($x,$y) at $size is off panel and must be blank",
                            GlyphAsciiPreview.OFF_PANEL,
                            char,
                        )
                    }
                }
            }
        }
    }

    /**
     * The row counts from the photograph of the lit 13x13 panel. Asserting the
     * *shape* and not merely the total is what would catch a mask that kept 137
     * cells in the wrong places — the exact defect described in [PanelMask]'s
     * KDoc.
     */
    @Test
    fun `the 13x13 rows are 5 9 11 11 13 13 13 13 13 11 11 9 5`() {
        val size = PokemonCodename.BELLSPROUT.size
        val lit = IntArray(size * size) { DesignFrames.MAX_BRIGHTNESS }

        val perRow = GlyphAsciiPreview.render(lit, size)
            .split("\n")
            .map { row -> row.count { it != GlyphAsciiPreview.OFF_PANEL } }

        assertEquals(listOf(5, 9, 11, 11, 13, 13, 13, 13, 13, 11, 11, 9, 5), perRow)
    }

    @Test
    fun `the panel map draws a hash for every LED and nothing else`() {
        for (codename in PokemonCodename.entries) {
            val map = GlyphAsciiPreview.panelMap(codename.size).filter { it != '\n' }

            assertEquals(codename.cellCount, map.length)
            assertEquals(PanelMask.count(codename.size), map.count { it == '#' })
            assertEquals(
                codename.cellCount - PanelMask.count(codename.size),
                map.count { it == GlyphAsciiPreview.OFF_PANEL },
            )
        }
    }

    // endregion

    // region the ramp

    @Test
    fun `off is distinct from any lit level however dim`() {
        assertEquals(GlyphAsciiPreview.RAMP[0], GlyphAsciiPreview.charFor(0))
        assertEquals(GlyphAsciiPreview.RAMP[0], GlyphAsciiPreview.charFor(-1))
        // The discontinuity at 1 is the point: a cell set to the dimmest palette
        // entry must not render as "off".
        assertTrue(GlyphAsciiPreview.charFor(1) != GlyphAsciiPreview.RAMP[0])
        assertEquals(GlyphAsciiPreview.RAMP.last(), GlyphAsciiPreview.charFor(DesignFrames.MAX_BRIGHTNESS))
        assertEquals(GlyphAsciiPreview.RAMP.last(), GlyphAsciiPreview.charFor(99_999))
    }

    @Test
    fun `the ramp never goes backwards`() {
        var previous = GlyphAsciiPreview.charFor(0)
        for (v in 0..DesignFrames.MAX_BRIGHTNESS) {
            val c = GlyphAsciiPreview.charFor(v)
            assertTrue(
                "brightness $v rendered '$c' after '$previous'",
                GlyphAsciiPreview.RAMP.indexOf(c) >= GlyphAsciiPreview.RAMP.indexOf(previous),
            )
            previous = c
        }
    }

    // endregion

    // region cells

    @Test
    fun `rendering cells matches rendering the decoded frame`() {
        val codename = PokemonCodename.BELLSPROUT
        val cells = "2".repeat(codename.cellCount)

        val fromCells = GlyphAsciiPreview.renderCells(cells, DEFAULT_LEVELS, codename)
        val decoded = DesignFrames.decode(cells, DEFAULT_LEVELS, codename.size)

        assertNotNull(decoded)
        assertEquals(GlyphAsciiPreview.render(decoded!!, codename.size), fromCells)
    }

    @Test
    fun `cells the codec would refuse are not drawn at all`() {
        val codename = PokemonCodename.BELLSPROUT
        // Wrong length, a character that is not base36, and a palette index the
        // design does not define. Drawing any of these would show the model a
        // picture of a frame the app will not store.
        assertNull(GlyphAsciiPreview.renderCells("0".repeat(codename.cellCount - 1), DEFAULT_LEVELS, codename))
        assertNull(GlyphAsciiPreview.renderCells("!".repeat(codename.cellCount), DEFAULT_LEVELS, codename))
        assertNull(GlyphAsciiPreview.renderCells("5".repeat(codename.cellCount), DEFAULT_LEVELS, codename))
    }

    @Test
    fun `a short frame renders rather than throwing`() {
        // Never reached through the tools, which validate first — but this runs
        // inside tool results, where an exception would replace the model's only
        // feedback with nothing.
        val drawn = GlyphAsciiPreview.render(IntArray(4), 13)

        assertEquals(13, drawn.split("\n").size)
    }

    @Test
    fun `a nonsense geometry renders as empty`() {
        assertEquals("", GlyphAsciiPreview.render(IntArray(0), 0))
        assertEquals("", GlyphAsciiPreview.panelMap(-3))
    }

    // endregion

    // region spans

    @Test
    fun `live spans describe the same rows the drawing does`() {
        for (codename in PokemonCodename.entries) {
            val size = codename.size
            val spans = GlyphAsciiPreview.liveSpans(size)

            assertEquals(size, spans.size)
            assertEquals(
                PanelMask.count(size),
                spans.sumOf { it?.let { r -> r.last - r.first + 1 } ?: 0 },
            )
            // The disc is convex, so a row's live cells are one unbroken run —
            // which is the assumption the first/last pair encodes.
            for (y in 0 until size) {
                val span = spans[y] ?: continue
                for (x in span) {
                    assertTrue("($x,$y) inside the span must be live", PanelMask.contains(x, y, size))
                }
            }
        }
    }

    @Test
    fun `the span table names every row`() {
        val table = GlyphAsciiPreview.liveSpanTable(PokemonCodename.BELLSPROUT.size)

        assertEquals(13, table.split("\n").size)
        assertTrue(table.contains("row 6: columns 0-12 (13 cells)"))
        assertTrue(table.contains("row 0: columns 4-8 (5 cells)"))
    }

    // endregion
}
