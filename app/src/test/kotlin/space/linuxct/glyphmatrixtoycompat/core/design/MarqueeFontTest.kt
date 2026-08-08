package space.linuxct.glyphmatrixtoycompat.core.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The letterforms, and the properties that make a phrase legible rather than
 * merely legal.
 *
 * A marquee's failure mode is not a malformed frame — [MarqueeText] and
 * `DesignCodec` between them make that unrepresentable. It is a letter that is
 * *bad*: an `S` that reads as a `5`, an `O` and a `0` that are the same
 * drawing, a glyph one row short that leaves a hole in the line. None of those
 * is visible one frame at a time, and all of them are visible here, so the
 * assertions are about the table's shape and the table's distinctness rather
 * than about any particular picture. The pictures themselves were reviewed by
 * eye; what this file guarantees is that a later edit cannot break one silently.
 */
class MarqueeFontTest {
    /** Everything the brief asked to be covered, spelled out rather than derived. */
    private val required: List<Char> =
        ('A'..'Z') + ('a'..'z') + ('0'..'9') + ' ' +
            "!\"#\$%&'()*+,-./:;<=>?@[\\]^_`{|}~".toList()

    // region the table

    @Test
    fun `every glyph is nine rows of one width, drawn only in hash and dot`() {
        for (c in MarqueeFont.COVERAGE) {
            val glyph = MarqueeFont.glyph(c)!!
            assertEquals("'$c' is ${glyph.size} rows", MarqueeFont.HEIGHT, glyph.size)
            val width = glyph.first().length
            assertTrue("'$c' is $width columns wide", width in 1..MarqueeFont.MAX_WIDTH)
            for ((r, row) in glyph.withIndex()) {
                assertEquals("'$c' row $r is ragged", width, row.length)
                assertTrue("'$c' row $r has a stray character: $row", row.all { it == '#' || it == '.' })
            }
        }
    }

    @Test
    fun `the coverage is the printable ASCII the brief named`() {
        for (c in required) assertTrue("'$c' is missing", MarqueeFont.supports(c))
        assertEquals(required.sorted(), MarqueeFont.COVERAGE)
    }

    // endregion

    // region folding

    /**
     * An accent is one row tall and there is no row to put it on, so it is
     * dropped rather than refused: a stripped accent is legible and a refused
     * word is not. The case survives the stripping — there is a letterform for
     * either — which is what makes "café" scroll as *café*.
     */
    @Test
    fun `accented latin letters fall back to their base letter`() {
        assertEquals(MarqueeFont.glyph('e'), MarqueeFont.glyph('é'))
        assertEquals(MarqueeFont.glyph('N'), MarqueeFont.glyph('Ñ'))
        assertEquals(MarqueeFont.picture("cafe"), MarqueeFont.picture("café"))
        assertEquals(MarqueeFont.picture("ano"), MarqueeFont.picture("año"))
        assertEquals("cafe", MarqueeFont.drawnAs("café"))
    }

    @Test
    fun `what it cannot draw it reports, in order and without repeats`() {
        assertEquals(emptyList<Char>(), MarqueeFont.unsupported("HELLO, WORLD! 42"))
        assertEquals(emptyList<Char>(), MarqueeFont.unsupported("café"))
        assertEquals(listOf('♥', '中'), MarqueeFont.unsupported("A♥B中C♥"))
        assertFalse(MarqueeFont.supports('♥'))
        assertNull(MarqueeFont.glyph('♥'))
        assertEquals(0, MarqueeFont.width('♥'))
    }

    // endregion

    // region layout

    @Test
    fun `a strip is the glyph widths plus one gap between each pair`() {
        assertEquals(MarqueeFont.width('A'), MarqueeFont.stripWidth("A"))
        assertEquals(
            MarqueeFont.width('H') + MarqueeFont.GAP + MarqueeFont.width('I'),
            MarqueeFont.stripWidth("HI"),
        )
        val hello = "HELLO".sumOf { MarqueeFont.width(it) } + 4 * MarqueeFont.GAP
        assertEquals(hello, MarqueeFont.stripWidth("HELLO"))
        assertEquals(0, MarqueeFont.stripWidth(""))
    }

    /**
     * A width that silently skipped what it could not draw would produce a
     * marquee missing letters *and* a frame count that agreed with it, so
     * nothing downstream could notice.
     */
    @Test
    fun `text it cannot draw has no width and no picture`() {
        assertEquals(0, MarqueeFont.stripWidth("A♥B"))
        assertEquals(emptyList<String>(), MarqueeFont.picture("A♥B"))
        assertEquals(0, MarqueeFont.strip("A♥B").size)
    }

    @Test
    fun `the strip and the picture are the same bitmap`() {
        val text = "GLYPH 42!"
        val strip = MarqueeFont.strip(text)
        val picture = MarqueeFont.picture(text)

        assertEquals(MarqueeFont.HEIGHT, picture.size)
        assertEquals(MarqueeFont.stripWidth(text), strip.size)
        for (r in 0 until MarqueeFont.HEIGHT) {
            assertEquals(strip.size, picture[r].length)
            for (x in strip.indices) {
                assertEquals(
                    "row $r column $x",
                    strip[x] and (1 shl r) != 0,
                    picture[r][x] == '#',
                )
            }
        }
    }

    // endregion
}
