package space.linuxct.glyphworks.core.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import space.linuxct.glyphworks.core.design.Design
import space.linuxct.glyphworks.core.design.DesignCodec
import space.linuxct.glyphworks.core.design.DesignFrame
import space.linuxct.glyphworks.core.design.DesignKind
import space.linuxct.glyphworks.core.design.DesignVariant
import space.linuxct.glyphworks.core.design.PokemonCodename

/**
 * `set_frames` exists because `apply_design` replaces the whole document.
 *
 * That is the right primitive and it is also a trap on a long animation: 240
 * frames of `arbok` is around 150 kB of base36, so "make frame 7 brighter" was a
 * request to retype every frame — slow, expensive, and worse than both, a fresh
 * chance to corrupt one of the 239 that were already correct, in art nobody was
 * looking at.
 *
 * So the property that matters most here is the boring one:
 * [a replaced range leaves its neighbours byte-identical]. Everything else is
 * about the range arithmetic, which is where an off-by-one would silently eat
 * somebody's frame.
 */
class SetFramesTest {
    private val bellsprout = PokemonCodename.BELLSPROUT
    private val arbok = PokemonCodename.ARBOK

    // region the range arithmetic

    /**
     * The whole point of the tool, stated as an equality: the frames outside the
     * window are not merely *equivalent* afterwards, they are the same strings.
     */
    @Test
    fun `a replaced range leaves its neighbours byte-identical`() {
        val before = animation(10)
        val ctx = ctx(before)

        val design = applied(
            call(mode = GlyphAiTools.MODE_REPLACE, at = 4, frames = listOf(marked(7), marked(8)), ctx = ctx),
        )

        val after = design.variantFor(bellsprout)!!.frames
        assertEquals(10, after.size)
        for (i in 0 until 10) {
            when (i) {
                4 -> assertEquals(marked(7), after[i].cells)
                5 -> assertEquals(marked(8), after[i].cells)
                else -> assertEquals(
                    "frame $i was not touched and must be identical",
                    before[i].cells,
                    after[i].cells,
                )
            }
        }
        // Including their durations, which are just as easy to lose.
        assertEquals(before[0].durationMs, after[0].durationMs)
        assertEquals(before[9].durationMs, after[9].durationMs)
    }

    @Test
    fun `an insert shifts everything after it along and removes nothing`() {
        val before = animation(6)
        val ctx = ctx(before)

        val after = applied(
            call(mode = GlyphAiTools.MODE_INSERT, at = 2, frames = listOf(marked(9)), ctx = ctx),
        ).variantFor(bellsprout)!!.frames

        assertEquals(7, after.size)
        assertEquals(before[0].cells, after[0].cells)
        assertEquals(before[1].cells, after[1].cells)
        assertEquals(marked(9), after[2].cells)
        for (i in 2 until 6) {
            assertEquals("old frame $i is now frame ${i + 1}", before[i].cells, after[i + 1].cells)
        }
    }

    @Test
    fun `an insert at the frame count appends`() {
        val before = animation(3)

        val after = applied(
            call(mode = GlyphAiTools.MODE_INSERT, at = 3, frames = listOf(marked(9)), ctx = ctx(before)),
        ).variantFor(bellsprout)!!.frames

        assertEquals(4, after.size)
        assertEquals(marked(9), after[3].cells)
        assertEquals(before[2].cells, after[2].cells)
    }

    @Test
    fun `a delete closes the gap and shifts the rest back`() {
        val before = animation(8)

        val after = applied(
            call(mode = GlyphAiTools.MODE_DELETE, at = 3, count = 2, ctx = ctx(before)),
        ).variantFor(bellsprout)!!.frames

        assertEquals(6, after.size)
        assertEquals(before[2].cells, after[2].cells)
        assertEquals("old frame 5 is now frame 3", before[5].cells, after[3].cells)
        assertEquals(before[7].cells, after[5].cells)
    }

    @Test
    fun `it reports what it did in numbers`() {
        val body = ok(
            call(mode = GlyphAiTools.MODE_REPLACE, at = 1, frames = listOf(marked(7)), ctx = ctx(animation(5))),
        )

        assertTrue(body["applied"]!!.jsonPrimitive.boolean)
        assertEquals("replace", body[GlyphAiTools.ARG_MODE]!!.jsonPrimitive.content)
        assertEquals(1, body[GlyphAiTools.ARG_AT]!!.jsonPrimitive.content.toInt())
        assertEquals(1, body["removed"]!!.jsonPrimitive.content.toInt())
        assertEquals(1, body["inserted"]!!.jsonPrimitive.content.toInt())
        assertEquals(5, body["frame_count_before"]!!.jsonPrimitive.content.toInt())
        assertEquals(5, body["frame_count_after"]!!.jsonPrimitive.content.toInt())
        assertEquals("bellsprout", body["variant"]!!.jsonPrimitive.content)
    }

    // endregion

    // region what it refuses

    @Test
    fun `more frames than a design may hold is refused, with the arithmetic`() {
        val ctx = ctx(animation(DesignCodec.MAX_FRAMES - 2))

        val result = call(
            mode = GlyphAiTools.MODE_INSERT,
            at = 0,
            frames = List(5) { marked(7) },
            ctx = ctx,
        )

        val message = errorOf(result)
        assertTrue(message, message.contains("${DesignCodec.MAX_FRAMES + 3} frames"))
        assertTrue(message, message.contains("${DesignCodec.MAX_FRAMES - 2} now"))
        assertTrue(expected(result), expected(result).contains("At most ${DesignCodec.MAX_FRAMES}"))
        assertTrue("the way out is named", expected(result).contains("Add at most 2"))
        // Exactly at the limit is fine, so the check is a limit and not a fence.
        assertEquals(
            DesignCodec.MAX_FRAMES,
            applied(
                call(mode = GlyphAiTools.MODE_INSERT, at = 0, frames = List(2) { marked(7) }, ctx = ctx),
            ).variantFor(bellsprout)!!.frames.size,
        )
    }

    @Test
    fun `a panel the design does not carry is refused, naming what is allowed`() {
        val result = call(
            mode = GlyphAiTools.MODE_REPLACE,
            at = 0,
            frames = listOf(marked(7)),
            variant = "arbok",
            ctx = ctx(animation(3)),
        )

        assertTrue(errorOf(result), errorOf(result).contains("arbok"))
        assertEquals(
            listOf("bellsprout"),
            body(result)["allowed_variants"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertTrue(expected(result), expected(result).contains("\"bellsprout\""))
    }

    @Test
    fun `a frame of the wrong length is refused in apply_design's own words`() {
        val short = call(
            mode = GlyphAiTools.MODE_REPLACE,
            at = 0,
            frames = listOf("000"),
            ctx = ctx(animation(2)),
        )

        val message = errorOf(short)
        assertTrue(message, message.contains("variants.bellsprout frame 0 has 3 cells"))
        assertTrue(expected(short), expected(short).contains("Exactly ${bellsprout.cellCount}"))
    }

    @Test
    fun `a mode that does not exist is refused, naming the three that do`() {
        for (arguments in listOf(
            """{"at": 0}""",
            """{"mode": "shuffle", "at": 0}""",
            """{"mode": 7, "at": 0}""",
        )) {
            val result = GlyphAiTools.run(GlyphAiTools.SET_FRAMES, arguments, ctx(animation(2)))

            assertTrue(result.json, result.isError)
            assertEquals(
                listOf("replace", "insert", "delete"),
                body(result)["modes"]!!.jsonArray.map { it.jsonPrimitive.content },
            )
        }
    }

    @Test
    fun `no shape of nonsense throws, and each failure says what was expected`() {
        val ctx = ctx(animation(3))
        val nonsense = listOf(
            "",
            "[]",
            "null",
            "{",
            """{"mode": "replace"}""",
            """{"mode": "replace", "at": 0}""",
            """{"mode": "replace", "at": 0, "frames": []}""",
            """{"mode": "replace", "at": 0, "frames": {}}""",
            """{"mode": "replace", "at": 0, "frames": [7]}""",
            """{"mode": "replace", "at": 0, "frames": [{"durationMs": 120}]}""",
            """{"mode": "replace", "at": "first", "frames": ["0"]}""",
            """{"mode": "insert", "at": 0, "frames": [{"cells": "0", "durationMs": "fast"}]}""",
            """{"mode": "delete", "at": 0, "count": 0}""",
            """{"mode": "delete", "at": 0, "count": -3}""",
            """{"mode": "delete", "at": 0, "variant": "pikachu"}""",
        )

        for (arguments in nonsense) {
            val result = GlyphAiTools.run(GlyphAiTools.SET_FRAMES, arguments, ctx)
            val parsed = Json.parseToJsonElement(result.json).jsonObject

            if (result.isError) {
                assertTrue("$arguments has no error text", parsed.containsKey("error"))
                assertNull("an error must never carry a design", result.design)
            }
        }
    }

    @Test
    fun `replacing in a panel with no frames points at insert instead`() {
        val empty = TestDesigns.design(mapOf(bellsprout.codename to DesignVariant(emptyList())))

        val result = call(
            mode = GlyphAiTools.MODE_REPLACE,
            at = 0,
            frames = listOf(marked(7)),
            ctx = GlyphToolContext(empty, openVariant = bellsprout),
        )

        assertTrue(errorOf(result), errorOf(result).contains("nothing to replace"))
        assertTrue(expected(result), expected(result).contains(GlyphAiTools.MODE_INSERT))
    }

    // endregion

    // region the design it hands back

    /**
     * A static design may hold exactly one frame, and there is no way to set
     * `kind` from here — so a static design that gains a second frame is
     * promoted rather than being refused with advice this tool cannot act on.
     * It is reported, because it changes how the design plays.
     */
    @Test
    fun `a static design that gains a frame becomes dynamic, and says so`() {
        val still = TestDesigns.design(
            mapOf(bellsprout.codename to DesignVariant(listOf(DesignFrame(120, marked(1))))),
            kind = DesignKind.STATIC,
        )
        val ctx = GlyphToolContext(still, openVariant = bellsprout)

        val result = call(mode = GlyphAiTools.MODE_INSERT, at = 1, frames = listOf(marked(7)), ctx = ctx)

        val body = ok(result)
        assertEquals("dynamic", body["kind"]!!.jsonPrimitive.content)
        assertTrue(body["kind_changed"]!!.jsonPrimitive.boolean)
        assertEquals(DesignKind.DYNAMIC, result.design!!.kind)
        assertTrue(warningsOf(body).any { it.contains("dynamic") })
        // ...and it is never demoted on the way back down.
        val back = call(
            mode = GlyphAiTools.MODE_DELETE,
            at = 1,
            ctx = GlyphToolContext(result.design!!, openVariant = bellsprout),
        )
        assertEquals(DesignKind.DYNAMIC, back.design!!.kind)
        assertNull(ok(back)["kind_changed"])
    }

    @Test
    fun `the design it hands back is one the codec would store`() {
        val design = applied(
            call(mode = GlyphAiTools.MODE_REPLACE, at = 0, frames = listOf(marked(7)), ctx = ctx(animation(3))),
        )

        assertTrue(DesignCodec.decode(DesignCodec.encode(design)) is DesignCodec.Result.Ok)
        // Fields the app owns are the canvas's, never invented here.
        assertEquals(TestDesigns.bellsproutOnly().id, design.id)
        assertEquals(TestDesigns.bellsproutOnly().createdAt, design.createdAt)
        assertEquals("Slow Ember", design.name)
    }

    @Test
    fun `the other panel of a two-panel design is not touched`() {
        val both = TestDesigns.bothVariants()
        val ctx = GlyphToolContext(both, openVariant = bellsprout)

        val design = applied(
            call(mode = GlyphAiTools.MODE_REPLACE, at = 0, frames = listOf(marked(7)), ctx = ctx),
        )

        assertEquals(
            both.variantFor(arbok)!!.frames.map { it.cells },
            design.variantFor(arbok)!!.frames.map { it.cells },
        )
        assertEquals(marked(7), design.variantFor(bellsprout)!!.frames[0].cells)
    }

    @Test
    fun `a frame may be sent as a bare cells string, and takes the default duration`() {
        val design = applied(
            call(mode = GlyphAiTools.MODE_REPLACE, at = 0, frames = listOf(marked(7)), ctx = ctx(animation(2))),
        )

        assertEquals(120, design.variantFor(bellsprout)!!.frames[0].durationMs)
    }

    // endregion

    // region helpers

    /**
     * A legal frame with one lit cell, in a place unique to [tag] — and never in
     * the place [animation] lights, so a new frame is always distinguishable
     * from the ones it replaced.
     */
    private fun marked(tag: Int): String {
        val cells = CharArray(bellsprout.cellCount) { '0' }
        // Inside rows 4-8 and columns 4-8, which are LEDs at 13x13, so the frame
        // is legal art as well as legal data.
        cells[(4 + tag % 5) * bellsprout.size + (4 + (tag / 5) % 5)] = '2'
        return String(cells)
    }

    /**
     * [count] frames that are all different from each other, so a frame that
     * moved when it should not have is visible as a mismatch rather than as a
     * coincidence.
     */
    private fun animation(count: Int): List<DesignFrame> = (0 until count).map { i ->
        val cells = CharArray(bellsprout.cellCount) { '0' }
        // The centre is lit in every frame of an animation and in none of
        // [marked]'s frames, which is what tells the two apart.
        cells[6 * bellsprout.size + 6] = '1'
        cells[(4 + i % 5) * bellsprout.size + (4 + (i / 5) % 5)] = '2'
        DesignFrame(100 + i, String(cells))
    }

    private fun ctx(frames: List<DesignFrame>): GlyphToolContext = GlyphToolContext(
        TestDesigns.design(mapOf(bellsprout.codename to DesignVariant(frames))),
        openVariant = bellsprout,
    )

    private fun call(
        mode: String,
        at: Int? = null,
        count: Int? = null,
        frames: List<String>? = null,
        variant: String? = null,
        ctx: GlyphToolContext,
    ): GlyphToolResult = GlyphAiTools.run(
        GlyphAiTools.SET_FRAMES,
        buildJsonObject {
            put(GlyphAiTools.ARG_MODE, mode)
            at?.let { put(GlyphAiTools.ARG_AT, it) }
            count?.let { put(GlyphAiTools.ARG_COUNT, it) }
            variant?.let { put(GlyphAiTools.ARG_VARIANT, it) }
            frames?.let { list -> put(GlyphAiTools.ARG_FRAME_LIST, buildJsonArray { list.forEach { add(it) } }) }
        }.toString(),
        ctx,
    )

    private fun warningsOf(body: JsonObject): List<String> =
        body["warnings"]!!.jsonArray.map { it.jsonPrimitive.content }

    private fun body(result: GlyphToolResult): JsonObject =
        Json.parseToJsonElement(result.json).jsonObject

    private fun ok(result: GlyphToolResult): JsonObject {
        assertFalse("expected success, got ${result.json}", result.isError)
        return body(result)
    }

    private fun applied(result: GlyphToolResult): Design {
        assertFalse("expected success, got ${result.json}", result.isError)
        return requireNotNull(result.design) { "set_frames must hand the caller a design to apply" }
    }

    private fun errorOf(result: GlyphToolResult): String {
        assertTrue("expected an error, got ${result.json}", result.isError)
        assertNull("an error must never carry a design to apply", result.design)
        return body(result)["error"]!!.jsonPrimitive.content
    }

    private fun expected(result: GlyphToolResult): String =
        body(result)["expected"]!!.jsonPrimitive.content

    // endregion
}
