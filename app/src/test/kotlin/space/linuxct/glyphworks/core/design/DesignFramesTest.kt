package space.linuxct.glyphworks.core.design

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DesignFramesTest {
    private val palette = DEFAULT_LEVELS

    @Test
    fun `decodes a bellsprout frame against the palette`() {
        val size = PokemonCodename.BELLSPROUT.size
        val cells = StringBuilder("0".repeat(size * size)).apply {
            setCharAt(0, '1')                 // (0,0) -> 50% grey
            setCharAt(2 * size + 3, '2')      // (3,2) -> white
            setCharAt(size * size - 1, '2')   // (12,12) -> white
        }.toString()

        val frame = DesignFrames.decode(cells, palette, size)

        assertNotNull(frame)
        assertEquals(size * size, frame!!.size)
        assertEquals(2048, frame[0])
        // Row-major: the cell at (x=3, y=2) is at index y * size + x.
        assertEquals(4095, frame[2 * size + 3])
        assertEquals(4095, frame[size * size - 1])
        assertEquals(0, frame[1])
    }

    @Test
    fun `round-trips IntArray to cells and back at both codenames`() {
        for (codename in PokemonCodename.entries) {
            val size = codename.size
            val original = IntArray(size * size) { palette[it % palette.size] }

            val cells = DesignFrames.encode(original, palette, size)
            assertNotNull("encode failed for ${codename.codename}", cells)
            assertEquals(codename.cellCount, cells!!.length)

            val decoded = DesignFrames.decode(cells, palette, size)
            assertNotNull("decode failed for ${codename.codename}", decoded)
            assertArrayEquals("round-trip differs for ${codename.codename}", original, decoded)
        }
    }

    @Test
    fun `a blank frame is all palette index zero`() {
        val cells = DesignFrames.blank(PokemonCodename.BELLSPROUT)
        assertEquals(169, cells.length)
        assertArrayEquals(
            IntArray(169),
            DesignFrames.decode(cells, palette, PokemonCodename.BELLSPROUT.size),
        )
    }

    @Test
    fun `a frame of the wrong length does not decode`() {
        assertNull(DesignFrames.decode("0".repeat(168), palette, 13))
        assertNull(DesignFrames.decode("0".repeat(170), palette, 13))
        // An arbok frame handed to a bellsprout panel is a length mismatch, not
        // something to crop.
        assertNull(DesignFrames.decode("0".repeat(625), palette, 13))
    }

    @Test
    fun `a character outside the palette does not decode`() {
        // '3' is a legal base36 digit but this palette only defines indices 0..2.
        val cells = "3" + "0".repeat(168)
        assertNull(DesignFrames.decode(cells, palette, 13))
    }

    @Test
    fun `upper case is accepted on decode and never written on encode`() {
        // A 20-entry palette, so base36 'j' (= index 19) is the last legal digit.
        val long = List(20) { it * 200 }
        val size = 13
        val upper = "J" + "0".repeat(168)
        val lower = "j" + "0".repeat(168)

        val fromLower = DesignFrames.decode(lower, long, size)
        assertNotNull(fromLower)
        assertEquals(long[19], fromLower!![0])
        assertArrayEquals(fromLower, DesignFrames.decode(upper, long, size))

        val frame = IntArray(size * size).also { it[0] = long[19] }
        val encoded = DesignFrames.encode(frame, long, size)!!
        assertEquals(encoded, encoded.lowercase())
    }

    @Test
    fun `encoding snaps a value to the nearest palette entry`() {
        val size = 13
        val frame = IntArray(size * size)
        frame[0] = 0
        frame[1] = 900      // nearer 0 than 2048
        frame[2] = 1500     // nearer 2048
        frame[3] = 4095

        val cells = DesignFrames.encode(frame, palette, size)!!

        assertEquals('0', cells[0])
        assertEquals('0', cells[1])
        assertEquals('1', cells[2])
        assertEquals('2', cells[3])
    }

    @Test
    fun `encoding rejects a frame that does not match the geometry`() {
        assertNull(DesignFrames.encode(IntArray(168), palette, 13))
        assertNull(DesignFrames.encode(IntArray(169), emptyList(), 13))
        assertNull(DesignFrames.encode(IntArray(169), List(37) { it }, 13))
    }

    @Test
    fun `codenames resolve both ways and unknown ones are null`() {
        assertEquals(PokemonCodename.BELLSPROUT, PokemonCodename.ofCodename("bellsprout"))
        assertEquals(PokemonCodename.ARBOK, PokemonCodename.ofCodename("arbok"))
        assertEquals(PokemonCodename.BELLSPROUT, PokemonCodename.ofSize(13))
        assertEquals(PokemonCodename.ARBOK, PokemonCodename.ofSize(25))
        assertNull(PokemonCodename.ofCodename("mewtwo"))
        assertNull(PokemonCodename.ofCodename("Bellsprout"))
        assertNull(PokemonCodename.ofSize(169))
    }
}
