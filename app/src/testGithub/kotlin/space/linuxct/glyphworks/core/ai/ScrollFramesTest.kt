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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import space.linuxct.glyphworks.core.design.DesignCodec
import space.linuxct.glyphworks.core.design.PokemonCodename
import space.linuxct.glyphworks.matrix.PanelMask

/**
 * `scroll_frames` exists because of one decoded export, and these tests are that
 * export turned into assertions.
 *
 * A user asked for "HI" scrolling right to left. What came back was nine frames
 * in which frame 0 was blank, the brightness fell from 4095 to 2048 partway
 * through, and the H sheared apart — its uprights at columns 1 and 3 on rows 4-5
 * and at columns 2 and 4 on rows 6-8. Prompt guidance can argue with the first
 * three, because they are decisions. It cannot fix the shear, which is not a
 * decision but sixteen thousand characters of bookkeeping with no error signal.
 *
 * So the arithmetic moved into the app, and what these tests check is that it is
 * now *impossible* rather than discouraged:
 *
 * - [the HI that failed comes back as the full traverse] — the frame count.
 * - [no frame of a default scroll is blank] — frame 0 especially.
 * - [every frame is a pure horizontal translation of the frame before it] — the
 *   anti-shear assertion, and mechanical rather than eyeballed: it compares the
 *   lit cells of each pair of neighbours as sets, so a single row a column out
 *   fails it.
 * - [the palette indices in every frame are the ones the source was drawn with]
 *   — the flicker.
 *
 * Plus the property that makes the whole thing worth having: what the tool hands
 * back is accepted by the real `apply_design` without a character being retyped.
 */
class ScrollFramesTest {
    private val bellsprout = PokemonCodename.BELLSPROUT
    private val arbok = PokemonCodename.ARBOK

    /**
     * "HI" as the prompt lays it out, drawn at palette index 2 — full brightness
     * under the default `[0, 2048, 4095]`, and deliberately not '1', so a frame
     * that came back at half brightness would be visible as a different
     * character rather than hidden behind the prompt's own example.
     */
    private val hi = listOf(
        "2020222",
        "2020020",
        "2220020",
        "2020020",
        "2020222",
    )

    // region the "HI" that failed

    /**
     * The anti-shear assertion, stated mechanically.
     *
     * Every lit cell of frame n must appear in frame n+1 exactly `step` columns
     * to the left, with the same character and on the SAME ROW — and every lit
     * cell of frame n+1 that did not just enter from the right must have come
     * from frame n the same way. A single row shifted a column further than its
     * neighbour breaks both directions at once, which is precisely the defect the
     * decoded export had and precisely what no amount of prose could prevent.
     */
    @Test
    fun `every frame is a pure horizontal translation of the frame before it`() {
        for (step in 1..3) {
            val frames = framesOf(ok(scroll(hi, step = step)))

            for (n in 0 until frames.size - 1) {
                val before = litCells(frames[n], bellsprout.size)
                val after = litCells(frames[n + 1], bellsprout.size)

                for ((cell, ch) in before) {
                    val moved = cell.copy(x = cell.x - step)
                    if (moved.x < 0) continue // left the panel; nothing to check
                    assertEquals(
                        "step $step, frame $n -> ${n + 1}: the cell at $cell did not land at $moved",
                        ch,
                        after[moved],
                    )
                }
                for ((cell, ch) in after) {
                    val came = cell.copy(x = cell.x + step)
                    if (came.x > bellsprout.size - 1) continue // entered from the right
                    assertEquals(
                        "step $step, frame ${n + 1} <- $n: the cell at $cell was not at $came",
                        ch,
                        before[came],
                    )
                }
            }
        }
    }

    // endregion

    // region the arithmetic

    @Test
    fun `the frame count is panel width plus source width minus one`() {
        for (codename in PokemonCodename.entries) {
            for (width in listOf(1, 7, 13, 30)) {
                val source = List(5) { "2".repeat(width) }
                val body = ok(
                    scroll(
                        source,
                        variant = codename.codename,
                        ctx = GlyphToolContext(TestDesigns.bothVariants()),
                    ),
                )

                assertEquals(
                    "${codename.codename} with a $width-column message",
                    codename.size + width - 1,
                    body["frame_count"]!!.jsonPrimitive.content.toInt(),
                )
            }
        }
    }

    @Test
    fun `the art is centred in the band of rows that never clips`() {
        val body = ok(scroll(hi))

        // Rows 4-8 at 13x13, and a five-row glyph fills them exactly.
        assertEquals(4, body["top_row"]!!.jsonPrimitive.content.toInt())
        assertEquals(GlyphAiPrompt.fullWidthRows(bellsprout.size)!!.first, body["top_row"]!!.jsonPrimitive.content.toInt())
        // Every lit cell of every frame has an LED behind it.
        for (cells in framesOf(body)) {
            for ((cell, _) in litCells(cells, bellsprout.size)) {
                assertTrue("$cell has no LED", PanelMask.contains(cell.x, cell.y, bellsprout.size))
            }
        }
    }

    @Test
    fun `arbok gets its own geometry, and never appears in a bellsprout-only result`() {
        val body = ok(
            scroll(hi, variant = "arbok", ctx = GlyphToolContext(TestDesigns.bothVariants())),
        )

        assertEquals(31, body["frame_count"]!!.jsonPrimitive.content.toInt())
        assertEquals(arbok.cellCount, framesOf(body).first().length)
        assertEquals(10, body["top_row"]!!.jsonPrimitive.content.toInt())
        assertFalse(ok(scroll(hi)).toString().contains("arbok"))
    }

    // endregion

    // region what it hands back

    /**
     * The point of the whole tool: the document it returns is applied by the real
     * `apply_design`, with nothing retyped in between.
     */
    @Test
    fun `apply_this is a document apply_design accepts as it stands`() {
        val ctx = GlyphToolContext(TestDesigns.bellsproutOnly(), openVariant = bellsprout)
        val document = ok(scroll(hi, ctx = ctx))[GlyphAiTools.KEY_APPLY_THIS]!!.jsonPrimitive.content

        val applied = GlyphAiTools.run(
            GlyphAiTools.APPLY_DESIGN,
            buildJsonObject { put(GlyphAiTools.ARG_DESIGN, document) }.toString(),
            ctx,
        )

        assertFalse(applied.json, applied.isError)
        val design = applied.design!!
        assertEquals(19, design.variantFor(bellsprout)!!.frames.size)
        // It carries "dynamic" itself, or nineteen frames would be refused as a
        // static design that could only ever show its first.
        assertEquals(
            framesOf(ok(scroll(hi, ctx = ctx))),
            design.variantFor(bellsprout)!!.frames.map { it.cells },
        )
        // ...and the design the app would store reads back the same.
        assertTrue(DesignCodec.decode(DesignCodec.encode(design)) is DesignCodec.Result.Ok)
    }

    /**
     * It computes; it does not decide. The frames land on the canvas only when
     * the model has read the pictures and asked for them, which is the same
     * bargain every other tool in this file strikes.
     */
    @Test
    fun `it applies nothing and offers nothing to apply`() {
        val result = scroll(hi)

        assertFalse(result.isError)
        assertNull("scroll_frames must never put frames on the canvas", result.design)
        assertNull("nor offer a draft the orchestrator could land", result.validated)
        assertFalse(ok(result)["applied"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `every frame is drawn, and the cells are not repeated beside the drawing`() {
        val body = ok(scroll(hi))
        val frames = body["frames"]!!.jsonArray

        assertEquals(19, frames.size)
        for (frame in frames) {
            assertNotNull(frame.jsonObject["preview"])
            // The cells live in apply_this, once. Repeating them here would
            // double the payload and, worse, invite the model to read them back
            // out and write them again, which is where brightness drifts.
            assertNull(frame.jsonObject["cells"])
        }
    }

    // endregion

    // region the warnings

    @Test
    fun `art outside the band that is live across every column is warned about`() {
        // Row 3 at 13x13 is not live across all 13 columns, so a glyph placed
        // there loses cells at the start and end of the travel and nowhere in
        // between - which no single frame's picture can show.
        val body = ok(scroll(hi, topRow = 3))
        val warnings = warningsOf(body)

        assertEquals(1, warnings.size)
        assertTrue(warnings[0], warnings[0].contains("clipped"))
        assertTrue(warnings[0], warnings[0].contains("3"))
        assertTrue(warnings[0], warnings[0].contains("Rows 4 to 8"))
        // Still generated: clipping by the rim is a legitimate design choice.
        assertEquals(19, framesOf(body).size)
    }

    // endregion

    // region every failure is a result

    /**
     * The property this whole file shares with `GlyphAiToolsTest`: model output is
     * malformed as a matter of routine, and every shape of malformed must come
     * back as something the model can read and correct from.
     */
    @Test
    fun `no shape of nonsense throws, and each failure says what was expected`() {
        val ctx = GlyphToolContext(TestDesigns.bellsproutOnly(), openVariant = bellsprout)
        val nonsense = listOf(
            "",
            "[]",
            "null",
            "{",
            """{"source_rows": null}""",
            """{"source_rows": []}""",
            """{"source_rows": 7}""",
            """{"source_rows": [7]}""",
            """{"source_rows": [""]}""",
            """{"source_rows": ["2222", "222"]}""",
            """{"source_rows": ["!!!"]}""",
            """{"source_rows": ["0000"]}""",
            """{"source_rows": ["2", "2", "2", "2", "2", "2", "2", "2", "2", "2", "2", "2", "2", "2"]}""",
            """{"source_rows": ["222"], "top_row": -1}""",
            """{"source_rows": ["222"], "top_row": 13}""",
            """{"source_rows": ["222"], "top_row": "middle"}""",
            """{"source_rows": ["222"], "step": 0}""",
            """{"source_rows": ["222"], "step": -1}""",
            """{"source_rows": ["222"], "step": 99}""",
            """{"source_rows": ["222"], "frames": 0}""",
            """{"source_rows": ["222"], "frames": 241}""",
            """{"source_rows": ["222"], "duration_ms": 5}""",
            """{"source_rows": ["222"], "duration_ms": 60001}""",
            """{"source_rows": ["222"], "start_column": 9999}""",
            """{"source_rows": ["222"], "variant": "pikachu"}""",
            """{"source_rows": ["222"], "variant": "arbok"}""",
            """{"source_rows": ["222"], "variant": 7}""",
        )

        for (arguments in nonsense) {
            val result = GlyphAiTools.run(GlyphAiTools.SCROLL_FRAMES, arguments, ctx)
            val parsed = Json.parseToJsonElement(result.json).jsonObject

            if (result.isError) {
                assertTrue("$arguments has no error text", parsed.containsKey("error"))
                assertNull("an error must never carry a design", result.design)
            }
        }
    }

    @Test
    fun `rows of unequal length are refused with both lengths`() {
        val message = errorOf(scroll(listOf("2222", "222", "2222")))

        assertTrue(message, message.contains("row 1 is 3 characters"))
        assertTrue(message, message.contains("row 0 is 4"))
    }

    @Test
    fun `a source taller than the panel is refused with the panel's height`() {
        val message = errorOf(scroll(List(14) { "2" }))

        assertTrue(message, message.contains("14 rows tall"))
        assertTrue(message, message.contains("only 13 rows"))
    }

    @Test
    fun `a top_row that would push the art off the panel is refused with the range`() {
        val result = scroll(hi, topRow = 10)

        assertTrue(errorOf(result), errorOf(result).contains("outside a 13-row panel"))
        assertTrue(expected(result).contains("0 to 8"))
        // And the advice that is actually useful: where a scrolling glyph lives.
        assertTrue(expected(result).contains("Rows 4 to 8"))
    }

    @Test
    fun `a scroll that would need more frames than a design may hold is refused`() {
        // 240 frames is the ceiling; 13 + 240 - 1 needs 252.
        val result = scroll(List(5) { "2".repeat(240) })

        assertTrue(errorOf(result), errorOf(result).contains("252 frames"))
        assertTrue(expected(result).contains("${DesignCodec.MAX_FRAMES}"))
        assertTrue("the way out is named", expected(result).contains("step"))
    }

    @Test
    fun `a panel the design does not carry is refused, naming what is allowed`() {
        val result = scroll(hi, variant = "arbok")

        assertTrue(errorOf(result), errorOf(result).contains("arbok"))
        assertEquals(
            listOf("bellsprout"),
            body(result)["allowed_variants"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `two carried panels and none open is a question, not a guess`() {
        val result = GlyphAiTools.run(
            GlyphAiTools.SCROLL_FRAMES,
            arguments(hi),
            GlyphToolContext(TestDesigns.bothVariants()),
        )

        assertTrue(errorOf(result), errorOf(result).contains("cannot be left null"))
    }

    @Test
    fun `the open panel is what a null variant means`() {
        val body = ok(
            GlyphAiTools.run(
                GlyphAiTools.SCROLL_FRAMES,
                arguments(hi),
                GlyphToolContext(TestDesigns.bothVariants(), openVariant = arbok),
            ),
        )

        assertEquals("arbok", body["variant"]!!.jsonPrimitive.content)
    }

    /** Models send a newline-separated block as often as they send an array. */
    @Test
    fun `the source may arrive as one newline-separated string`() {
        val result = GlyphAiTools.run(
            GlyphAiTools.SCROLL_FRAMES,
            buildJsonObject { put(GlyphAiTools.ARG_SOURCE_ROWS, hi.joinToString("\n")) }.toString(),
            GlyphToolContext(TestDesigns.bellsproutOnly(), openVariant = bellsprout),
        )

        assertEquals(framesOf(ok(scroll(hi))), framesOf(ok(result)))
    }

    // endregion

    // region helpers

    private data class Cell(val x: Int, val y: Int)

    /** Every lit cell of a frame, by position, with the character it carries. */
    private fun litCells(cells: String, size: Int): Map<Cell, Char> =
        cells.indices.filter { cells[it] != '0' }.associate { Cell(it % size, it / size) to cells[it] }

    private fun scroll(
        source: List<String>,
        variant: String? = null,
        topRow: Int? = null,
        startColumn: Int? = null,
        step: Int? = null,
        frames: Int? = null,
        durationMs: Int? = null,
        ctx: GlyphToolContext = GlyphToolContext(TestDesigns.bellsproutOnly(), openVariant = bellsprout),
    ): GlyphToolResult = GlyphAiTools.run(
        GlyphAiTools.SCROLL_FRAMES,
        arguments(source, variant, topRow, startColumn, step, frames, durationMs),
        ctx,
    )

    private fun arguments(
        source: List<String>,
        variant: String? = null,
        topRow: Int? = null,
        startColumn: Int? = null,
        step: Int? = null,
        frames: Int? = null,
        durationMs: Int? = null,
    ): String = buildJsonObject {
        put(GlyphAiTools.ARG_SOURCE_ROWS, buildJsonArray { source.forEach { add(it) } })
        variant?.let { put(GlyphAiTools.ARG_VARIANT, it) }
        topRow?.let { put(GlyphAiTools.ARG_TOP_ROW, it) }
        startColumn?.let { put(GlyphAiTools.ARG_START_COLUMN, it) }
        step?.let { put(GlyphAiTools.ARG_STEP, it) }
        frames?.let { put(GlyphAiTools.ARG_FRAMES, it) }
        durationMs?.let { put(GlyphAiTools.ARG_DURATION_MS, it) }
    }.toString()

    /** The generated cells, read back out of the document the tool hands on. */
    private fun framesOf(body: JsonObject): List<String> =
        Json.parseToJsonElement(body[GlyphAiTools.KEY_APPLY_THIS]!!.jsonPrimitive.content)
            .jsonObject["variants"]!!.jsonObject
            .values.first().jsonObject["frames"]!!.jsonArray
            .map { it.jsonObject["cells"]!!.jsonPrimitive.content }

    private fun warningsOf(body: JsonObject): List<String> =
        body["warnings"]!!.jsonArray.map { it.jsonPrimitive.content }

    private fun body(result: GlyphToolResult): JsonObject =
        Json.parseToJsonElement(result.json).jsonObject

    private fun ok(result: GlyphToolResult): JsonObject {
        assertFalse("expected success, got ${result.json}", result.isError)
        return body(result)
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
