package space.linuxct.glyphworks.core.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import space.linuxct.glyphworks.core.design.Design
import space.linuxct.glyphworks.core.design.DesignCodec
import space.linuxct.glyphworks.core.design.DesignFrames
import space.linuxct.glyphworks.core.design.DesignKind
import space.linuxct.glyphworks.core.design.DesignVariant
import space.linuxct.glyphworks.core.design.MarqueeFont
import space.linuxct.glyphworks.core.design.MarqueeText
import space.linuxct.glyphworks.core.design.PokemonCodename
import space.linuxct.glyphworks.matrix.PanelMask

/**
 * The prompt is the only place the model learns that the panel is round, and
 * every hand-authoring failure in this project's history came from not knowing
 * it. These assertions exist so that fact cannot quietly fall out of the string.
 */
class GlyphAiPromptTest {
    @Test
    fun `a bellsprout only design is never told about arbok`() {
        val prompt = GlyphAiPrompt.build(TestDesigns.bellsproutOnly())

        assertFalse("arbok must not be offered", prompt.contains("arbok"))
        assertFalse(prompt.contains("489"))
        assertTrue(prompt.contains("bellsprout"))
        assertTrue(prompt.contains("137"))
        // The closed-set sentence is the instruction the tool then enforces.
        assertTrue(prompt.contains("THAT LIST IS CLOSED"))
    }

    @Test
    fun `the limits quoted are the ones the codec enforces`() {
        val prompt = GlyphAiPrompt.build(TestDesigns.bothVariants())

        assertTrue(prompt.contains("${DesignCodec.MAX_FRAMES}"))
        assertTrue(prompt.contains("${DesignCodec.MIN_DURATION_MS}"))
        assertTrue(prompt.contains("${DesignCodec.MAX_DURATION_MS}"))
        assertTrue(prompt.contains("${DesignFrames.MAX_BRIGHTNESS}"))
        assertTrue(prompt.contains("${DesignFrames.MAX_PALETTE}"))
        assertTrue(prompt.contains("${DesignCodec.MAX_NAME_LENGTH}"))
    }

    @Test
    fun `the prompt names the tools it tells the model to use`() {
        val prompt = GlyphAiPrompt.build(TestDesigns.bothVariants())

        for (tool in GlyphAiTools.build()) {
            assertTrue("${tool.name} is explained", prompt.contains(tool.name))
        }
    }

    @Test
    fun `variantsPresent is the set the design carries`() {
        assertEquals(
            listOf(PokemonCodename.BELLSPROUT),
            GlyphAiPrompt.variantsPresent(TestDesigns.bellsproutOnly()),
        )
        assertEquals(
            listOf(PokemonCodename.BELLSPROUT, PokemonCodename.ARBOK),
            GlyphAiPrompt.variantsPresent(TestDesigns.bothVariants()),
        )
        assertEquals(emptyList<PokemonCodename>(), GlyphAiPrompt.variantsPresent(TestDesigns.noVariants()))
    }

    // region the worked example

    /**
     * The reason the example is a constant at all.
     *
     * An example in a system prompt carries the prompt's authority: a model will
     * reproduce its shape faithfully, so an example that has quietly become
     * illegal teaches the model a document this app refuses — and it would do so
     * silently, in production, with the only symptom being an assistant that
     * cannot apply anything. This runs it through the same [DesignCodec] the
     * store and the tools run everything through.
     *
     * The app-managed fields are supplied here rather than in the example,
     * because that is exactly what the app does: `GlyphAiTools.prepare` merges
     * the model's document onto the design on screen and never takes an id or a
     * timestamp from it. See the next test for that path end to end.
     */
    @Test
    fun `every panel's worked example is a design the codec accepts`() {
        for (codename in PokemonCodename.entries) {
            assertTrue("every known panel gets an example", GlyphAiPrompt.workedExample(codename) != null)

            val parsed = exampleDesign(codename)
            // Supplied here rather than in the example because that is exactly
            // what the app does: `GlyphAiTools.prepare` merges the model's
            // document onto the design on screen and never takes an id or a
            // timestamp from it. The next test covers that path end to end.
            val withAppFields = parsed.copy(
                id = "abc123",
                createdAt = "2026-07-30T12:00:00Z",
                modifiedAt = "2026-07-30T12:00:00Z",
            )

            val result = DesignCodec.validate(withAppFields)

            assertTrue(
                "${codename.codename}'s example must validate: " +
                    "${(result as? DesignCodec.Result.Invalid)?.reason}",
                result is DesignCodec.Result.Ok,
            )
            val design = (result as DesignCodec.Result.Ok).design
            assertEquals(DesignKind.DYNAMIC, design.kind)
            assertEquals(listOf(0, DesignFrames.MAX_BRIGHTNESS), design.levels)
            assertEquals(GlyphAiPrompt.EXAMPLE_LEVELS, design.levels)
            val frames = design.variantFor(codename)!!.frames
            assertEquals("two frames, as the prompt says", 2, frames.size)
            frames.forEach { assertEquals(codename.cellCount, it.cells.length) }
            // Round-trips: what the codec writes back is something it will read.
            assertTrue(DesignCodec.decode(DesignCodec.encode(design)) is DesignCodec.Result.Ok)
        }
    }

    @Test
    fun `the worked example survives the path a model's document actually takes`() {
        val ctx = GlyphToolContext(design = TestDesigns.bellsproutOnly())

        val result = GlyphAiTools.run(
            GlyphAiTools.APPLY_DESIGN,
            Json.encodeToString(
                JsonObject.serializer(),
                buildJsonObject { put(GlyphAiTools.ARG_DESIGN, GlyphAiPrompt.WORKED_EXAMPLE) },
            ),
            ctx,
        )

        assertFalse("apply_design refused the prompt's own example: ${result.json}", result.isError)
        val applied = result.design!!
        assertEquals("Blink", applied.name)
        val frames = applied.variantFor(PokemonCodename.BELLSPROUT)!!.frames
        assertEquals(2, frames.size)
        assertEquals(GlyphAiPrompt.EXAMPLE_CELLS_EYES_OPEN, frames[0].cells)
        assertEquals(GlyphAiPrompt.EXAMPLE_CELLS_EYES_SHUT, frames[1].cells)
        // The id is the editor's, never the model's.
        assertEquals(TestDesigns.bellsproutOnly().id, applied.id)
    }

    /**
     * The example has to *demonstrate* the rule the prompt shouts about, not
     * merely be legal under it. A worked example whose art ran into the corners
     * would teach the exact mistake the loudest section exists to prevent.
     *
     * Checked on every geometry, since larger panels get the same art translated
     * to their centre and a translation that overshot would be silent.
     */
    @Test
    fun `nothing in any worked example is drawn off the disc`() {
        for (codename in PokemonCodename.entries) {
            val design = exampleDesign(codename)
            val size = codename.size
            for (frame in design.variantFor(codename)!!.frames) {
                for (i in frame.cells.indices) {
                    if (frame.cells[i] == '0') continue
                    val x = i % size
                    val y = i / size
                    assertTrue(
                        "${codename.codename}'s example lights ($x, $y), which has no LED behind it",
                        PanelMask.contains(x, y, size),
                    )
                }
            }
        }
    }

    /** As forgiving as `DesignCodec`'s own reader, so the example is judged by the codec's rules. */
    private val lenient = Json { ignoreUnknownKeys = true }

    private fun exampleDesign(codename: PokemonCodename): Design =
        lenient.decodeFromString(Design.serializer(), GlyphAiPrompt.workedExample(codename)!!)

    // endregion

    // region not guessing

    // endregion

    // region asking before guessing

    // endregion

    // region delivering something

    // endregion

    // region the rim, and the greys

    // endregion

    // region the marquee, and the alphabet that stopped being the model's job

    /**
     * The numbers the prompt quotes are the ones the tool will produce, and the
     * frames behind them are ones this app would actually store — checked
     * through the real [DesignCodec] rather than by eye, for the reason every
     * other example in this file is.
     */
    @Test
    fun `the frame counts quoted are the ones the generator produces, and they validate`() {
        val prompt = GlyphAiPrompt.build(TestDesigns.bothVariants())

        for (codename in PokemonCodename.entries) {
            val frames = GlyphAiPrompt.marqueeExampleFrames(codename)
            assertTrue("${codename.codename} has frames", frames.isNotEmpty())
            assertTrue(
                "${codename.codename}'s count is quoted",
                prompt.contains(
                    "on ${codename.codename} the letters are " +
                        "${MarqueeFont.HEIGHT * MarqueeText.scaleFor(codename.size)} of ${codename.size} rows " +
                        "tall, and \"${GlyphAiPrompt.MARQUEE_EXAMPLE_TEXT}\" is ${frames.size} frames",
                ),
            )

            val design = TestDesigns.design(
                mapOf(codename.codename to DesignVariant(frames)),
                levels = GlyphAiPrompt.EXAMPLE_LEVELS,
            )
            assertTrue(
                "${codename.codename}: the prompt's own example must be a design this app accepts",
                DesignCodec.validate(design) is DesignCodec.Result.Ok,
            )
        }
    }

    // endregion

    // region animation, and the decoded "HI" that produced all of it

    /**
     * The band of rows a scrolling glyph can live in, which the animation section
     * points at and the panel section names. Computed from the mask rather than
     * typed, so it cannot disagree with the panel map printed beside it.
     */
    @Test
    fun `the full-width row band is computed from the mask, for every panel`() {
        assertEquals(4..8, GlyphAiPrompt.fullWidthRows(PokemonCodename.BELLSPROUT.size))
        assertEquals(9..15, GlyphAiPrompt.fullWidthRows(PokemonCodename.ARBOK.size))

        for (codename in PokemonCodename.entries) {
            val band = GlyphAiPrompt.fullWidthRows(codename.size)!!
            // Every cell of every row in the band has an LED behind it...
            for (y in band) {
                for (x in 0 until codename.size) {
                    assertTrue("($x, $y) on ${codename.codename}", PanelMask.contains(x, y, codename.size))
                }
            }
            // ...and the rows either side of it do not, or the band is understated.
            for (y in listOf(band.first - 1, band.last + 1)) {
                assertFalse(PanelMask.contains(0, y, codename.size))
            }
            // And the prompt says so, for a design that carries the panel.
            assertTrue(
                GlyphAiPrompt.build(TestDesigns.bothVariants())
                    .contains("rows ${band.first} to ${band.last} are the only"),
            )
        }
    }

    // endregion
}
