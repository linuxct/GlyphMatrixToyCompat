package space.linuxct.glyphmatrixtoycompat.core.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import space.linuxct.glyphmatrixtoycompat.matrix.PanelMask

/**
 * The scroll itself: where the letters sit, what the disc takes off them, and
 * that the whole thing survives the real [DesignCodec].
 *
 * The measurements these tests are built on came from `PanelMask`, not from
 * taste. At 13x13 the live row-span per column is 5, 9, 11, 11, 13, 13, 13, 13,
 * 13, 11, 11, 9, 5 — so a nine-row band on rows 2-10 is whole in eleven of the
 * thirteen columns and cut only in the two outermost ones. That is the trade the
 * whole feature rests on: letters that fill the panel, clipped where nobody
 * reads them.
 */
class MarqueeTextTest {
    private val bellsprout = PokemonCodename.BELLSPROUT
    private val arbok = PokemonCodename.ARBOK

    // region geometry

    /**
     * The scale, the band and the rows it lands on, checked against the mask
     * rather than against each other.
     */
    @Test
    fun `the band is centred and the mask agrees with where it lands`() {
        assertEquals(1, MarqueeText.scaleFor(13))
        assertEquals(2, MarqueeText.scaleFor(25))

        assertEquals(2, MarqueeText.topRow(13, 1))
        assertEquals(3, MarqueeText.topRow(25, 2))

        // Rows 2-10 at 13x13 are entirely live in every column but 0 and 12.
        for (x in 1..11) {
            for (y in 2..10) {
                assertTrue("($x, $y) should be on the panel", PanelMask.contains(x, y, 13))
            }
        }
        assertEquals(false, PanelMask.contains(0, 3, 13))
        assertEquals(false, PanelMask.contains(12, 9, 13))
    }

    // endregion

    // region the traverse

    /**
     * Panel width + message width - 1, which is what the assistant's animation
     * guidance states in those words.
     */
    @Test
    fun `the traverse is the panel plus the message`() {
        assertEquals(9, MarqueeFont.stripWidth("HI"))
        assertEquals(13 + 9 - 1, MarqueeText.frameCount("HI", 13, 1, 1))
        assertEquals(13 + 9 - 1, MarqueeText.frameCount("HI", 25, 2, 2))
    }

    /**
     * The bound is not the count, and this is the case that shows why: the last
     * column of "HI" is the `I`'s bottom serif, whose only lit rows are the
     * band's top and bottom — both outside the five live rows of the panel's
     * outermost column. That frame arrives blank, and a design that ends on a
     * dark panel is the defect the prompt calls out by name.
     */
    @Test
    fun `a frame that would arrive blank is dropped rather than shipped`() {
        val frames = frames("HI", bellsprout)

        assertEquals(21, MarqueeText.frameCount("HI", 13, 1, 1))
        assertEquals(20, frames.size)
        assertTrue(frames.first().cells.any { it != '0' })
        assertTrue(frames.last().cells.any { it != '0' })
    }

    /**
     * The exact first and last frames of a known phrase.
     *
     * "HI" at 13x13 opens with the `H`'s left upright at the panel's right-hand
     * edge — nine rows of it, of which the disc keeps the middle five — and
     * closes with the `I`'s stem at the left edge plus the two serif cells of its
     * last column, which column 1 is tall enough to hold.
     */
    @Test
    fun `the first and last frames of HI are exactly these cells`() {
        val frames = frames("HI", bellsprout)

        assertEquals(setOf(12 to 4, 12 to 5, 12 to 6, 12 to 7, 12 to 8), litCells(frames.first().cells, 13))
        assertEquals(
            setOf(0 to 4, 0 to 5, 0 to 6, 0 to 7, 0 to 8, 1 to 2, 1 to 10),
            litCells(frames.last().cells, 13),
        )
    }

    // endregion

    // region what may never be in a frame

    @Test
    fun `no lit cell is ever outside the panel mask`() {
        for (text in listOf("HI", "HELLO WORLD", "@#\$%&", "GLYPH 42!")) {
            for (codename in listOf(bellsprout, arbok)) {
                for ((i, frame) in frames(text, codename).withIndex()) {
                    for ((x, y) in litCells(frame.cells, codename.size)) {
                        assertTrue(
                            "$text frame $i lights ($x, $y), which ${codename.codename} has no LED for",
                            PanelMask.contains(x, y, codename.size),
                        )
                    }
                }
            }
        }
    }

    /**
     * The anti-shear assertion, and mechanical rather than eyeballed.
     *
     * Frame n+1 must be frame n moved left by exactly `step`, with the disc
     * applied afresh. Stated as a two-way implication so that neither a cell
     * that failed to move nor a cell that appeared from nowhere can pass: a
     * single row a column out of step fails it.
     */
    @Test
    fun `every frame is the frame before it moved left by one step`() {
        for (codename in listOf(bellsprout, arbok)) {
            val size = codename.size
            val step = MarqueeText.defaultStep(size)
            val frames = frames("HELLO WORLD", codename)
            for (i in 0 until frames.size - 1) {
                val before = litCells(frames[i].cells, size)
                val after = litCells(frames[i + 1].cells, size)
                for ((x, y) in before) {
                    if (!PanelMask.contains(x - step, y, size)) continue
                    assertTrue("frame $i cell ($x, $y) did not move", after.contains(x - step to y))
                }
                for ((x, y) in after) {
                    if (!PanelMask.contains(x + step, y, size)) continue
                    assertTrue("frame ${i + 1} cell ($x, $y) came from nowhere", before.contains(x + step to y))
                }
            }
        }
    }

    // endregion

    // region the budget

    @Test
    fun `maxPrefixLength names a prefix that fits and a longer one that does not`() {
        val phrase = "THE QUICK BROWN FOX JUMPS OVER THE LAZY DOG"
        for (codename in listOf(bellsprout, arbok)) {
            val size = codename.size
            val scale = MarqueeText.scaleFor(size)
            val step = MarqueeText.defaultStep(size)
            val fits = MarqueeText.maxPrefixLength(phrase, size, scale, step)

            assertTrue("$fits characters of $phrase", fits in 1 until phrase.length)
            assertTrue(MarqueeText.frameCount(phrase.take(fits), size, scale, step) <= DesignCodec.MAX_FRAMES)
            assertTrue(MarqueeText.frameCount(phrase.take(fits + 1), size, scale, step) > DesignCodec.MAX_FRAMES)
            assertTrue(frames(phrase.take(fits), codename).isNotEmpty())
        }
    }

    @Test
    fun `a phrase past the limit produces no frames at all`() {
        val phrase = "THE QUICK BROWN FOX JUMPS OVER THE LAZY DOG"

        assertEquals(emptyList<DesignFrame>(), MarqueeText.frames(phrase, 13))
        assertEquals(emptyList<DesignFrame>(), MarqueeText.frames("", 13))
        assertEquals(emptyList<DesignFrame>(), MarqueeText.frames("A♥B", 13))
        // Index 0 is the off level, so every frame would be blank.
        assertEquals(emptyList<DesignFrame>(), MarqueeText.frames("HI", 13, paletteIndex = 0))
        // Nine rows at scale 2 do not fit on a thirteen-row panel.
        assertEquals(emptyList<DesignFrame>(), MarqueeText.frames("HI", 13, scale = 2))
    }

    // endregion

    // region the codec

    /**
     * The property that makes the whole thing worth having: what comes out is a
     * design this app will store, checked by the real validator rather than by
     * inspection.
     */
    @Test
    fun `a generated marquee validates through the real codec`() {
        for (codename in listOf(bellsprout, arbok)) {
            val design = Design(
                id = "abc123",
                name = "Marquee",
                createdAt = "2026-08-02T12:00:00Z",
                modifiedAt = "2026-08-02T12:00:00Z",
                kind = DesignKind.DYNAMIC,
                loop = true,
                levels = DEFAULT_LEVELS,
                variants = mapOf(codename.codename to DesignVariant(frames("HELLO WORLD", codename))),
            )

            val result = DesignCodec.validate(design)
            assertTrue("${codename.codename}: $result", result is DesignCodec.Result.Ok)

            // ...and it survives a round trip through the file format, which is
            // the same bytes the store writes.
            val decoded = DesignCodec.decode(DesignCodec.encode(design))
            assertTrue(decoded is DesignCodec.Result.Ok)
            assertEquals(
                design.variantFor(codename)!!.frames,
                (decoded as DesignCodec.Result.Ok).design.variantFor(codename)!!.frames,
            )
        }
    }

    @Test
    fun `every frame is the right length and every duration is legal`() {
        for (codename in listOf(bellsprout, arbok)) {
            val frames = frames("HELLO WORLD", codename)
            assertNotEquals(0, frames.size)
            for (frame in frames) {
                assertEquals(codename.cellCount, frame.cells.length)
                assertEquals(MarqueeText.DEFAULT_DURATION_MS, frame.durationMs)
                assertTrue(frame.durationMs >= DesignCodec.MIN_DURATION_MS)
                assertTrue(frame.durationMs <= DesignCodec.MAX_DURATION_MS)
            }
        }
    }

    // endregion

    // region helpers

    private fun frames(text: String, codename: PokemonCodename): List<DesignFrame> =
        MarqueeText.frames(text, codename.size, paletteIndex = DEFAULT_LEVELS.size - 1)

    private fun litCells(cells: String, size: Int): Set<Pair<Int, Int>> {
        val out = HashSet<Pair<Int, Int>>()
        for (i in cells.indices) if (cells[i] != '0') out.add((i % size) to (i / size))
        return out
    }

    // endregion
}
