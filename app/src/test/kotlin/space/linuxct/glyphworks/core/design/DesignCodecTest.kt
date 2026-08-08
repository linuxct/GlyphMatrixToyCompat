package space.linuxct.glyphworks.core.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.time.Instant

/**
 * The codec is the app's trust boundary for files other people wrote, so most of
 * this class is hostile input. Every rejection asserts the *exact* reason, not
 * merely that something failed: "it was rejected" would still pass if the codec
 * started rejecting everything, and a user needs to be told which of these
 * things went wrong.
 */
class DesignCodecTest {
    // region round trip

    @Test
    fun `a design round-trips through encode and decode`() {
        val original = sampleDesign()

        val encoded = DesignCodec.encode(original)
        val decoded = ok(encoded)

        assertEquals(original, decoded)
    }

    @Test
    fun `the encoded envelope is self-describing and readable`() {
        val encoded = DesignCodec.encode(sampleDesign())

        // encodeDefaults must be on, or a design whose fields equal their
        // defaults would be written without the fields that identify the format.
        assertTrue(encoded.contains("\"format\": \"glyph.design\""))
        assertTrue(encoded.contains("\"formatVersion\": 1"))
        assertTrue(encoded.contains("\"kind\": \"dynamic\""))
        assertTrue(encoded.contains("\"keyMode\": \"playPause\""))
        assertTrue(encoded.contains("\"bellsprout\""))
        // prettyPrint: a design in a pull request must show which frame changed.
        assertTrue(encoded.contains("\n"))
    }

    @Test
    fun `decoding from a stream matches decoding from a string`() {
        val encoded = DesignCodec.encode(sampleDesign())
        val fromStream = DesignCodec.decode(ByteArrayInputStream(encoded.toByteArray()))

        assertTrue(fromStream is DesignCodec.Result.Ok)
        assertEquals(sampleDesign(), (fromStream as DesignCodec.Result.Ok).design)
    }

    // endregion

    // region size

    @Test
    fun `a five megabyte payload is rejected before parsing`() {
        val bomb = "{\"format\":\"glyph.design\"," + "\"pad\":\"" + "x".repeat(5 * 1024 * 1024) + "\"}"

        assertEquals(DesignCodec.REASON_TOO_LARGE, reason(bomb))
    }

    @Test
    fun `ten thousand frames are still rejected when they do fit`() {
        // Same document, small enough to reach validation: the per-variant frame
        // cap catches it there.
        val many = List(10_000) { DesignFrame(durationMs = 40, cells = blankBellsprout()) }
        val design = sampleDesign().copy(
            variants = mapOf("bellsprout" to DesignVariant(many)),
        )

        assertEquals(
            DesignCodec.REASON_TOO_MANY_FRAMES,
            invalid(DesignCodec.validate(design)),
        )
    }

    @Test
    fun `one frame over the cap is rejected`() {
        val frames = (1..DesignCodec.MAX_FRAMES + 1).joinToString(",") {
            frame(cells = blankBellsprout())
        }

        assertEquals(
            DesignCodec.REASON_TOO_MANY_FRAMES,
            reason(json(variants = """{"bellsprout":{"frames":[$frames]}}""")),
        )
    }

    // endregion

    // region envelope

    @Test
    fun `truncated JSON is rejected`() {
        val truncated = DesignCodec.encode(sampleDesign()).let { it.substring(0, it.length / 2) }

        assertEquals(DesignCodec.REASON_NOT_JSON, reason(truncated))
        assertEquals(DesignCodec.REASON_NOT_JSON, reason(""))
        assertEquals(DesignCodec.REASON_NOT_JSON, reason("not json at all"))
        assertEquals(DesignCodec.REASON_NOT_JSON, reason("{"))
    }

    @Test
    fun `a field of the wrong type is rejected as unparseable`() {
        assertEquals(DesignCodec.REASON_NOT_JSON, reason(json(formatVersion = "\"one\"")))
        assertEquals(DesignCodec.REASON_NOT_JSON, reason(json(levels = "\"0,1,2\"")))
    }

    @Test
    fun `a file that is not a design is rejected`() {
        assertEquals(DesignCodec.REASON_NOT_A_DESIGN, reason(json(format = "\"glyph.toy\"")))
        assertEquals(DesignCodec.REASON_NOT_A_DESIGN, reason(json(format = "\"\"")))
        assertEquals(DesignCodec.REASON_NOT_A_DESIGN, reason(json(format = "42")))
        // No `format` key at all. Every field of Design has a default, so this
        // must be caught on the JSON tree rather than after deserialisation, or
        // the missing magic string would be filled in with our own.
        assertEquals(DesignCodec.REASON_NOT_A_DESIGN, reason("""{"hello":"world"}"""))
        assertEquals(DesignCodec.REASON_NOT_A_DESIGN, reason("{}"))
        // Valid JSON, but not even an object.
        assertEquals(DesignCodec.REASON_NOT_A_DESIGN, reason("[]"))
        assertEquals(DesignCodec.REASON_NOT_A_DESIGN, reason("\"glyph.design\""))
    }

    @Test
    fun `a newer format version is refused with its own message`() {
        assertEquals(DesignCodec.REASON_NEWER_VERSION, reason(json(formatVersion = "2")))
        assertEquals(DesignCodec.REASON_NEWER_VERSION, reason(json(formatVersion = "99")))
    }

    @Test
    fun `unknown fields are ignored`() {
        val design = ok(json(extra = ""","tags":["ember","slow"],"futureFlag":true"""))

        assertEquals("Slow Ember", design.name)
    }

    @Test
    fun `an unrecognised enum value degrades to the default instead of failing`() {
        val design = ok(json(kind = "\"kaleidoscope\"", keyMode = "\"triplePress\""))

        assertEquals(DesignKind.STATIC, design.kind)
        assertEquals(KeyMode.PLAY_PAUSE, design.keyMode)
    }

    // endregion

    // region id, the value that becomes a filename

    @Test
    fun `an id containing NUL or whitespace or Unicode is rejected`() {
        assertEquals(DesignCodec.REASON_BAD_ID, reason(json(id = "\"a\\u0000b\"")))
        assertEquals(DesignCodec.REASON_BAD_ID, reason(json(id = "\"a b\"")))
        assertEquals(DesignCodec.REASON_BAD_ID, reason(json(id = "\"embër\"")))
        assertEquals(DesignCodec.REASON_BAD_ID, reason(json(id = "\"\"")))
        assertEquals(DesignCodec.REASON_BAD_ID, reason(json(id = "\"" + "a".repeat(65) + "\"")))
    }

    @Test
    fun `a generated id is always a safe id`() {
        repeat(50) {
            val id = newDesignId()
            assertTrue(id, DesignCodec.isSafeId(id))
        }
        assertFalse(DesignCodec.isSafeId("../x"))
        assertTrue(DesignCodec.isSafeId("a-b_C9"))
    }

    // endregion

    // region text and timestamps

    @Test
    fun `over-long free text is rejected`() {
        assertEquals(
            DesignCodec.REASON_NAME_TOO_LONG,
            reason(json(name = "\"" + "n".repeat(65) + "\"")),
        )
        assertEquals(
            DesignCodec.REASON_AUTHOR_TOO_LONG,
            reason(json(author = "\"" + "a".repeat(65) + "\"")),
        )
        assertEquals(
            DesignCodec.REASON_CREATED_WITH_TOO_LONG,
            reason(json(createdWith = "\"" + "c".repeat(65) + "\"")),
        )
    }

    @Test
    fun `a timestamp that is not ISO-8601 UTC is rejected`() {
        assertEquals(DesignCodec.REASON_BAD_TIMESTAMP, reason(json(createdAt = "\"yesterday\"")))
        assertEquals(DesignCodec.REASON_BAD_TIMESTAMP, reason(json(createdAt = "\"1753876800000\"")))
        assertEquals(DesignCodec.REASON_BAD_TIMESTAMP, reason(json(modifiedAt = "\"\"")))
    }

    @Test
    fun `a timestamp carrying an offset is normalised to UTC, not rejected`() {
        val decoded = ok(
            json(
                createdAt = "\"2026-07-30T12:00:00+02:00\"",
                modifiedAt = "\"2026-07-30T09:30:00-05:00\"",
            ),
        )

        // Same spelling as everything else in storage...
        assertEquals("2026-07-30T10:00:00Z", decoded.createdAt)
        assertEquals("2026-07-30T14:30:00Z", decoded.modifiedAt)
        // ...and the same *instant* the file named, which is what makes the
        // rewrite lossless in meaning rather than merely tidy.
        assertEquals(Instant.parse("2026-07-30T12:00:00+02:00"), Instant.parse(decoded.createdAt))
        assertEquals(Instant.parse("2026-07-30T09:30:00-05:00"), Instant.parse(decoded.modifiedAt))
    }

    @Test
    fun `sub-second precision is truncated, so it cannot sort before the whole second`() {
        val decoded = ok(json(modifiedAt = "\"2026-07-30T12:00:00.500Z\""))

        assertEquals("2026-07-30T12:00:00Z", decoded.modifiedAt)
        // The bug this prevents: '.' is below 'Z' in ASCII, so the untruncated
        // string would sort BEFORE the whole second it is later than.
        assertTrue("2026-07-30T12:00:00.500Z" < "2026-07-30T12:00:00Z")
    }

    // endregion

    // region palette

    @Test
    fun `an empty palette is rejected`() {
        assertEquals(DesignCodec.REASON_EMPTY_PALETTE, reason(json(levels = "[]")))
    }

    @Test
    fun `a palette longer than base36 can address is rejected`() {
        val tooMany = (0..36).joinToString(",") { "$it" }

        assertEquals(DesignCodec.REASON_PALETTE_TOO_LONG, reason(json(levels = "[$tooMany]")))
    }

    @Test
    fun `palette entries outside the panel range are coerced, not rejected`() {
        // The one lenient rule: an out-of-range brightness has exactly one
        // sensible reading and no structural consequence.
        val design = ok(json(levels = "[-500, 2048, 99999]"))

        assertEquals(listOf(0, 2048, 4095), design.levels)
    }

    // endregion

    // region frames

    @Test
    fun `cells of the wrong length are rejected, never padded or cropped`() {
        val short = json(variants = """{"bellsprout":{"frames":[${frame(cells = "0".repeat(168))}]}}""")
        val long = json(variants = """{"bellsprout":{"frames":[${frame(cells = "0".repeat(170))}]}}""")
        // An arbok-sized frame filed under bellsprout is the interesting case:
        // both sizes are legal, only the pairing is wrong.
        val wrongPanel =
            json(variants = """{"bellsprout":{"frames":[${frame(cells = "0".repeat(625))}]}}""")

        assertEquals(DesignCodec.REASON_BAD_FRAME_SIZE, reason(short))
        assertEquals(DesignCodec.REASON_BAD_FRAME_SIZE, reason(long))
        assertEquals(DesignCodec.REASON_BAD_FRAME_SIZE, reason(wrongPanel))
    }

    @Test
    fun `a cell referencing a palette entry that does not exist is rejected`() {
        val cells = "5" + "0".repeat(168)

        assertEquals(
            DesignCodec.REASON_BAD_FRAME_CELL,
            reason(json(variants = """{"bellsprout":{"frames":[${frame(cells = cells)}]}}""")),
        )
    }

    @Test
    fun `a cell that is not a base36 digit is rejected`() {
        val cells = "!" + "0".repeat(168)

        assertEquals(
            DesignCodec.REASON_BAD_FRAME_CELL,
            reason(json(variants = """{"bellsprout":{"frames":[${frame(cells = cells)}]}}""")),
        )
    }

    @Test
    fun `a negative or zero frame duration is rejected`() {
        assertEquals(
            DesignCodec.REASON_BAD_DURATION,
            reason(json(variants = oneBellsproutFrame(durationMs = -1))),
        )
        assertEquals(
            DesignCodec.REASON_BAD_DURATION,
            reason(json(variants = oneBellsproutFrame(durationMs = 0))),
        )
        assertEquals(
            DesignCodec.REASON_BAD_DURATION,
            reason(json(variants = oneBellsproutFrame(durationMs = 19))),
        )
    }

    @Test
    fun `the duration bounds themselves are accepted`() {
        val fastest = ok(json(variants = oneBellsproutFrame(durationMs = 20)))
        val slowest = ok(json(variants = oneBellsproutFrame(durationMs = 60_000)))

        assertEquals(20, fastest.variantFor(PokemonCodename.BELLSPROUT)!!.frames[0].durationMs)
        assertEquals(60_000, slowest.variantFor(PokemonCodename.BELLSPROUT)!!.frames[0].durationMs)
    }

    // endregion

    // region variants

    @Test
    fun `a file with no variants at all is rejected`() {
        assertEquals(DesignCodec.REASON_NO_VARIANTS, reason(json(variants = "{}")))
        // `variants` omitted entirely, rather than present and empty.
        assertEquals(
            DesignCodec.REASON_NO_VARIANTS,
            reason(
                """
                {"format":"glyph.design","formatVersion":1,"id":"abc123","name":"n",
                 "createdAt":"2026-07-30T12:00:00Z","modifiedAt":"2026-07-30T12:00:00Z"}
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `an unknown pokemonCodename is ignored, not fatal`() {
        val design = ok(
            json(
                variants = """
                {"bellsprout":{"frames":[${frame(cells = blankBellsprout())}]},
                 "mewtwo":{"frames":[{"durationMs":120,"cells":"${"0".repeat(81)}"}]}}
                """.trimIndent(),
            ),
        )

        assertEquals(setOf("bellsprout"), design.variants.keys)
        assertNull(design.variants["mewtwo"])
        assertEquals(1, design.variantFor(PokemonCodename.BELLSPROUT)!!.frames.size)
    }

    @Test
    fun `an empty variant is legal - the second size starts blank`() {
        val design = ok(
            json(
                variants = """
                {"bellsprout":{"frames":[${frame(cells = blankBellsprout())}]},
                 "arbok":{"frames":[]}}
                """.trimIndent(),
            ),
        )

        assertEquals(setOf("bellsprout", "arbok"), design.variants.keys)
        assertEquals(0, design.variantFor(PokemonCodename.ARBOK)!!.frames.size)
    }

    @Test
    fun `an arbok variant is validated against 625 cells`() {
        val good = json(
            variants = """{"arbok":{"frames":[{"durationMs":120,"cells":"${"1".repeat(625)}"}]}}""",
        )
        val bad = json(
            variants = """{"arbok":{"frames":[{"durationMs":120,"cells":"${"1".repeat(169)}"}]}}""",
        )

        assertEquals(625, ok(good).variantFor(PokemonCodename.ARBOK)!!.frames[0].cells.length)
        assertEquals(DesignCodec.REASON_BAD_FRAME_SIZE, reason(bad))
    }

    @Test
    fun `a design resolves the variant for this device's panel size`() {
        val design = ok(json())

        assertEquals(design.variantFor(PokemonCodename.BELLSPROUT), design.variantForSize(13))
        assertNull(design.variantForSize(169))
    }

    // endregion

    // region helpers

    private fun ok(text: String): Design {
        val result = DesignCodec.decode(text)
        assertTrue("expected acceptance, got $result", result is DesignCodec.Result.Ok)
        return (result as DesignCodec.Result.Ok).design
    }

    private fun reason(text: String): String = invalid(DesignCodec.decode(text))

    private fun invalid(result: DesignCodec.Result): String {
        assertTrue("expected rejection, got $result", result is DesignCodec.Result.Invalid)
        return (result as DesignCodec.Result.Invalid).reason
    }

    private fun blankBellsprout() = "0".repeat(PokemonCodename.BELLSPROUT.cellCount)

    private fun frame(durationMs: Int = 120, cells: String) =
        """{"durationMs":$durationMs,"cells":"$cells"}"""

    private fun oneBellsproutFrame(durationMs: Int) =
        """{"bellsprout":{"frames":[${frame(durationMs, blankBellsprout())}]}}"""

    /**
     * Builds a design document from raw JSON fragments (so a test can supply a
     * wrong *type*, not just a wrong value) with every field otherwise valid.
     */
    private fun json(
        format: String = "\"glyph.design\"",
        formatVersion: String = "1",
        id: String = "\"abc123\"",
        name: String = "\"Slow Ember\"",
        author: String = "\"linuxct\"",
        createdAt: String = "\"2026-07-30T12:00:00Z\"",
        modifiedAt: String = "\"2026-07-30T12:34:56Z\"",
        createdWith: String = "\"GlyphWorks 2.0.0\"",
        kind: String = "\"dynamic\"",
        keyMode: String = "\"playPause\"",
        loop: String = "true",
        levels: String = "[0, 2048, 4095]",
        variants: String = oneBellsproutFrame(120),
        extra: String = "",
    ): String = """
        {
          "format": $format,
          "formatVersion": $formatVersion,
          "id": $id,
          "name": $name,
          "author": $author,
          "createdAt": $createdAt,
          "modifiedAt": $modifiedAt,
          "createdWith": $createdWith,
          "kind": $kind,
          "keyMode": $keyMode,
          "loop": $loop,
          "levels": $levels,
          "variants": $variants$extra
        }
    """.trimIndent()

    private fun sampleDesign(): Design {
        val size = PokemonCodename.BELLSPROUT.size
        val lit = IntArray(size * size).also { buf ->
            for (x in 0 until size) buf[6 * size + x] = 2048
            buf[6 * size + 6] = 4095
        }
        return Design(
            id = "abc123",
            name = "Slow Ember",
            author = "linuxct",
            createdAt = "2026-07-30T12:00:00Z",
            modifiedAt = "2026-07-30T12:34:56Z",
            createdWith = "GlyphWorks 2.0.0",
            kind = DesignKind.DYNAMIC,
            keyMode = KeyMode.PLAY_PAUSE,
            loop = true,
            levels = DEFAULT_LEVELS,
            variants = mapOf(
                "bellsprout" to DesignVariant(
                    listOf(
                        DesignFrame(120, DesignFrames.blank(PokemonCodename.BELLSPROUT)),
                        DesignFrame(240, DesignFrames.encode(lit, DEFAULT_LEVELS, size)!!),
                    ),
                ),
                "arbok" to DesignVariant(emptyList()),
            ),
        )
    }

    // endregion
}
