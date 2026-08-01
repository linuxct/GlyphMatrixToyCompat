package space.linuxct.glyphmatrixtoycompat.core.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import space.linuxct.glyphmatrixtoycompat.core.design.DEFAULT_LEVELS
import space.linuxct.glyphmatrixtoycompat.core.design.Design
import space.linuxct.glyphmatrixtoycompat.core.design.DesignCodec
import space.linuxct.glyphmatrixtoycompat.core.design.DesignFrames
import space.linuxct.glyphmatrixtoycompat.core.design.DesignKind
import space.linuxct.glyphmatrixtoycompat.core.design.PokemonCodename
import space.linuxct.glyphmatrixtoycompat.matrix.PanelMask

/**
 * The prompt is the only place the model learns that the panel is round, and
 * every hand-authoring failure in this project's history came from not knowing
 * it. These assertions exist so that fact cannot quietly fall out of the string.
 */
class GlyphAiPromptTest {

    @Test
    fun `the prompt states the mask rule and both LED counts`() {
        val prompt = GlyphAiPrompt.build(TestDesigns.bothVariants())

        assertTrue("the mask rule itself", prompt.contains(GlyphAiPrompt.MASK_RULE))
        assertTrue("bellsprout's LED count", prompt.contains("137"))
        assertTrue("arbok's LED count", prompt.contains("489"))
        assertTrue("the square is still stored whole", prompt.contains("169"))
        assertTrue(prompt.contains("625"))
    }

    @Test
    fun `the prompt draws each panel it names`() {
        val prompt = GlyphAiPrompt.build(TestDesigns.bothVariants())

        for (codename in PokemonCodename.entries) {
            // The picture, not merely the word: a map for a geometry the design
            // carries is what lets the model place a glyph without counting.
            assertTrue(
                "${codename.codename} is drawn",
                // Indented as the prompt sets it, which also asserts that the
                // picture's own columns survive the surrounding trimIndent.
                prompt.contains(GlyphAsciiPreview.panelMap(codename.size).prependIndent("  ")),
            )
        }
        assertTrue(prompt.contains(GlyphAsciiPreview.LEGEND))
    }

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
    fun `an arbok only design is never told about bellsprout`() {
        val prompt = GlyphAiPrompt.build(TestDesigns.arbokOnly())

        assertFalse(prompt.contains("bellsprout"))
        assertTrue(prompt.contains("arbok"))
        assertTrue(prompt.contains("489"))
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

    @Test
    fun `a design with no known artwork is told it cannot be edited`() {
        val prompt = GlyphAiPrompt.build(TestDesigns.noVariants())

        assertTrue(prompt.contains("You cannot edit"))
        assertFalse(prompt.contains("THAT LIST IS CLOSED"))
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

    @Test
    fun `the example art is exactly one bellsprout panel's worth`() {
        for (cells in exampleFrames) {
            assertEquals(PokemonCodename.BELLSPROUT.cellCount, cells.length)
        }
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

    @Test
    fun `the prompt shows the example and draws both of its frames`() {
        val prompt = GlyphAiPrompt.build(TestDesigns.bellsproutOnly())

        assertTrue(
            "the document itself, indented as the prompt sets it",
            prompt.contains(GlyphAiPrompt.WORKED_EXAMPLE.prependIndent("  ")),
        )
        for (cells in exampleFrames) {
            val preview = GlyphAsciiPreview.renderCells(
                cells,
                GlyphAiPrompt.EXAMPLE_LEVELS,
                PokemonCodename.BELLSPROUT,
            )
            assertTrue("a frame that will not render has no business being the example", preview != null)
            assertTrue(
                "frame drawn beside its string, with its rows labelled",
                prompt.contains(GlyphAiPrompt.labelledRows(preview!!).prependIndent("  ")),
            )
        }
    }

    /**
     * The mapping shown rather than described.
     *
     * The prompt already said twice which way y runs, and a model still drew a
     * "10" upside down and then "rotated it 180 degrees" wrongly. The worked
     * example is the part of a prompt a model pattern-matches hardest, so it now
     * carries the row index beside every row — and the bars, because an off-panel
     * cell renders as a space and a row's ends are otherwise invisible.
     */
    @Test
    fun `the worked example labels its rows with their indices`() {
        val prompt = GlyphAiPrompt.build(TestDesigns.bellsproutOnly())

        // The top row and the bottom row, named, for the geometry in question.
        assertTrue(prompt.contains("row  0 |"))
        assertTrue(prompt.contains("row 12 |"))
        // And the sentence that says what the labels mean, plus the warning that
        // they are scaffolding rather than part of the format.
        assertTrue(prompt.contains("Row 0 is the FIRST 13 characters"))
        assertTrue(prompt.contains("they are not"))
        assertTrue(prompt.contains("part of cells"))
    }

    @Test
    fun `labelled rows keep the picture and add the index`() {
        val labelled = GlyphAiPrompt.labelledRows("ab\ncd\nef")

        assertEquals("row  0 |ab|\nrow  1 |cd|\nrow  2 |ef|", labelled)
    }

    /**
     * The gating property, restated for the example: the prompt's own sample
     * document must never name a panel the design does not carry. A hardcoded
     * bellsprout example would put that word in front of an arbok-only
     * conversation, which is precisely what `a bellsprout only design is never
     * told about arbok` exists to prevent, in the other direction.
     */
    @Test
    fun `the example is drawn for a panel the design actually carries`() {
        assertTrue(
            GlyphAiPrompt.build(TestDesigns.arbokOnly())
                .contains(GlyphAiPrompt.workedExample(PokemonCodename.ARBOK)!!.prependIndent("  ")),
        )
        assertTrue(
            GlyphAiPrompt.build(TestDesigns.bellsproutOnly())
                .contains(GlyphAiPrompt.workedExample(PokemonCodename.BELLSPROUT)!!.prependIndent("  ")),
        )
    }

    private val exampleFrames = listOf(
        GlyphAiPrompt.EXAMPLE_CELLS_EYES_OPEN,
        GlyphAiPrompt.EXAMPLE_CELLS_EYES_SHUT,
    )

    /** As forgiving as `DesignCodec`'s own reader, so the example is judged by the codec's rules. */
    private val lenient = Json { ignoreUnknownKeys = true }

    private fun exampleDesign(codename: PokemonCodename): Design =
        lenient.decodeFromString(Design.serializer(), GlyphAiPrompt.workedExample(codename)!!)

    // endregion

    // region not guessing

    /**
     * The instruction that stops the assistant answering from an imagined canvas.
     * `apply_design` replaces the whole document, so a model working from memory
     * does not merely misdescribe the art — it overwrites it.
     */
    @Test
    fun `the prompt forbids describing or writing art it has not read`() {
        val prompt = GlyphAiPrompt.build(TestDesigns.bothVariants())

        assertTrue(prompt.contains(GlyphAiPrompt.NO_FABRICATION))
        assertTrue(prompt.contains("NEVER describe"))
        assertTrue(prompt.contains("NEVER write a design you have not read this turn"))
        assertTrue(prompt.contains("NEVER invent a cells string"))
        // It has to sit with the workflow, where the model is deciding what to
        // call — not in the style guidance at the end, which is read as taste.
        assertTrue(
            "the refusal to guess must precede the numbered steps",
            prompt.indexOf(GlyphAiPrompt.NO_FABRICATION) < prompt.indexOf("1. Call ${GlyphAiTools.GET_CURRENT_DESIGN}"),
        )
    }

    // endregion

    // region asking before guessing

    /**
     * A turn here costs the user a minute or two, so a wrong guess on a genuinely
     * ambiguous request is expensive in a way it is not in an ordinary chat. The
     * permission to ask has to sit with the workflow, where the model is deciding
     * what to do, and it has to stay *ahead* of the numbered steps — a step 1 that
     * says "call get_current_design" read before the permission is read as "start
     * working" and the question never gets asked.
     */
    @Test
    fun `the prompt allows one clarifying question, before the numbered steps`() {
        val prompt = GlyphAiPrompt.build(TestDesigns.bothVariants())

        assertTrue(prompt.contains(GlyphAiPrompt.ONE_QUESTION))
        assertTrue(
            "the permission to ask must precede the numbered steps",
            prompt.indexOf(GlyphAiPrompt.ONE_QUESTION) <
                prompt.indexOf("1. Call ${GlyphAiTools.GET_CURRENT_DESIGN}"),
        )
        // ...and it must not have displaced the refusal to guess, which is the
        // more important of the two and states the harder rule.
        assertTrue(
            prompt.indexOf(GlyphAiPrompt.NO_FABRICATION) < prompt.indexOf(GlyphAiPrompt.ONE_QUESTION),
        )
    }

    /**
     * The counterweight, and the reason this section is not simply "ask when
     * unsure". An assistant that interrogates somebody who typed "a smiley" has
     * turned a one-sentence task into a conversation, which is the opposite of
     * what this feature is for.
     */
    @Test
    fun `asking is bounded to one question and biased towards drawing`() {
        val prompt = GlyphAiPrompt.build(TestDesigns.bothVariants())

        assertTrue(prompt.contains("Bias hard towards drawing"))
        assertTrue(prompt.contains("Never ask more than one question"))
        assertTrue(prompt.contains("never ask twice about the same request"))
    }

    // endregion

    // region delivering something

    /**
     * The instruction that was missing while an image of a plain "10" took eight
     * attempts and then six more: a photograph is not a thing this panel can
     * reproduce, so the job is what survives at this resolution and the
     * simplifying belongs in the FIRST draft rather than in the seventh.
     */
    @Test
    fun `a reference image is something to distil, not to reproduce`() {
        val prompt = GlyphAiPrompt.build(TestDesigns.bellsproutOnly())

        assertTrue(prompt.contains(GlyphAiPrompt.REFERENCE_NOT_TARGET))
        // Short fragments, as elsewhere in this file: the prompt is hard-wrapped
        // prose and an assertion spanning a line break would fail on a reflow.
        assertTrue(prompt.contains("recognisable ESSENCE at"))
        assertTrue(prompt.contains("simplify aggressively"))
    }

    /**
     * A rejected draft needs a named next step, not encouragement. Without one a
     * model retries variations of the same too-detailed idea until the budget is
     * gone, which is precisely the turn that delivered nothing.
     */
    @Test
    fun `the prompt gives an ordered ladder to simplify down, and says to descend it`() {
        val prompt = GlyphAiPrompt.build(TestDesigns.bellsproutOnly())

        assertTrue(prompt.contains(GlyphAiPrompt.SIMPLIFY_LADDER))
        for (rung in listOf("Fewer distinct shapes", "Thicker strokes", "Fewer frames", "Fewer palette levels")) {
            assertTrue(rung, prompt.contains(rung))
        }
        assertTrue(prompt.contains("Go down another step, not sideways"))
    }

    /**
     * `validate_design` answers one question — would this app store it — and a
     * model that reads a pass as "not good enough yet" spends the user's minutes
     * re-deciding something it had already decided.
     */
    @Test
    fun `a passing check is stated to be legality, not a verdict on the drawing`() {
        val prompt = GlyphAiPrompt.build(TestDesigns.bellsproutOnly())

        assertTrue(prompt.contains(GlyphAiPrompt.VALID_IS_NOT_GOOD))
        assertTrue(prompt.contains("costs the user real time"))
    }

    /**
     * The user's own point, and the one this brief called the most valuable: they
     * can correct a simplified drawing in three words, and can do nothing at all
     * with a turn that ended empty-handed.
     */
    @Test
    fun `the prompt says to land something rather than chase a perfect drawing`() {
        val prompt = GlyphAiPrompt.build(TestDesigns.bellsproutOnly())

        assertTrue(prompt.contains(GlyphAiPrompt.LAND_SOMETHING))
        assertTrue(prompt.contains("After two drafts that did not work"))
        assertTrue(prompt.contains("APPLY IT"))
        assertTrue(prompt.contains("Never spend your whole budget"))
    }

    /**
     * Where it sits. This is not taste the user can overrule — it is about what
     * the assistant does with their time — so it follows the workflow it modifies
     * and precedes the style notes, which are read as preferences.
     */
    @Test
    fun `the simplify guidance follows the workflow and precedes the style notes`() {
        val prompt = GlyphAiPrompt.build(TestDesigns.bellsproutOnly())

        assertTrue(
            prompt.indexOf("1. Call ${GlyphAiTools.GET_CURRENT_DESIGN}") <
                prompt.indexOf(GlyphAiPrompt.REFERENCE_NOT_TARGET),
        )
        assertTrue(
            prompt.indexOf(GlyphAiPrompt.LAND_SOMETHING) <
                prompt.indexOf("MAKING ART THAT READS ON THIS PANEL"),
        )
    }

    // endregion

    // region the rim, and the greys

    /**
     * Fact and taste, kept apart.
     *
     * The old margin advice sat under the loudest section in the prompt and read
     * as one instruction with it — *stay away from the edge* — which on device
     * produced an assistant that argued when the user asked for a bolder shape and
     * said outright that overflow was acceptable. The geometry must stay exactly
     * as loud; the preference must not.
     */
    @Test
    fun `the mask is stated as fact while the margin is only a default`() {
        val prompt = GlyphAiPrompt.build(TestDesigns.bellsproutOnly())

        // The fact, undiminished: it is still the loudest thing in the prompt.
        assertTrue(prompt.contains("THE PANEL IS A DISC. THIS IS THE ONE THING THAT GOES WRONG."))
        assertTrue(prompt.contains("There is no LED behind them"))
        assertTrue(prompt.contains("cells outside the inscribed circle have no LED"))

        // The taste, demoted.
        assertTrue(prompt.contains("A one-cell margin is a DEFAULT, not a rule"))
        assertTrue(prompt.contains("is a real design choice"))
        assertTrue(
            "the failure is a lost meaning, not a touched rim",
            prompt.contains("not a shape that touches the rim"),
        )
        assertFalse(
            "the sentence that over-corrected must be gone, not merely softened",
            prompt.contains("Leave a margin."),
        )
        assertFalse(prompt.contains("makes the art look intentional rather than cropped"))
    }

    /** A stated preference is not something to weigh against a style note. */
    @Test
    fun `an explicit ask for bolder overrides the margin default`() {
        val prompt = GlyphAiPrompt.build(TestDesigns.bellsproutOnly())

        assertTrue(prompt.contains(GlyphAiPrompt.BOLD_BEATS_MARGIN))
        // Short fragments deliberately: the prompt is hard-wrapped prose, and an
        // assertion long enough to span a line break would fail on a reflow that
        // changed nothing.
        assertTrue(prompt.contains("Do not argue for a margin"))
        assertTrue(prompt.contains("do not shrink a drawing to protect one"))
    }

    /**
     * The model shipped pure on/off every time on device, and both causes were
     * this file's: guidance that read as "avoid grey", and nothing anywhere saying
     * a mid level was already in the palette. Both are asserted here, including
     * the concrete `[0, 2048, 4095]` → `'1'` mapping, because "you may use
     * intermediate levels" without a character to type is advice a model cannot
     * act on.
     */
    @Test
    fun `the prompt offers the greys it already has`() {
        val prompt = GlyphAiPrompt.build(TestDesigns.bellsproutOnly())

        assertTrue(prompt.contains(GlyphAiPrompt.GREY_AVAILABLE))
        // The palette it will actually find on a default design, and the character
        // that indexes its middle entry.
        assertTrue(prompt.contains("$DEFAULT_LEVELS, where '1' is a half-brightness cell"))
        assertEquals(
            "the example only means what it says while the default palette has a middle",
            3,
            DEFAULT_LEVELS.size,
        )
        // What they are FOR. Aliased curves are the case that prompted this.
        assertTrue(prompt.contains("staircase pixels"))
        assertTrue(prompt.contains("motion trails"))
        // And that the palette itself is editable when three levels are not enough.
        assertTrue(prompt.contains("You may also EXTEND levels"))
        assertTrue(prompt.contains("rewrite the cells strings to match"))

        // The true half of the old advice survives; the discouraging half does not.
        assertTrue(prompt.contains("a design drawn entirely in mid-grey just"))
        assertFalse(
            "\"not for the main shape\" is what taught it to avoid grey",
            prompt.contains("not for the main shape"),
        )
    }

    // endregion

    // region animation, and the decoded "HI" that produced all of it

    /**
     * The evidence these assertions exist for.
     *
     * A user asked for "HI" scrolling right to left and exported the result. It
     * decoded to nine frames in which frame 0 was blank, the brightness dropped
     * from 4095 to 2048 partway through, and the H sheared apart — its uprights
     * at columns 1 and 3 on rows 4-5 and at columns 2 and 4 on rows 6-8. Each
     * test below pins the guidance for one of those, and this one pins the fix
     * for the shear, which is the defect the other three hang off: the model was
     * shifting each row on its own, which is five independent chances per frame
     * to be a column out.
     */
    @Test
    fun `the prompt gives a mechanical method for scrolling text`() {
        val prompt = GlyphAiPrompt.build(TestDesigns.bellsproutOnly())

        assertTrue(prompt.contains(GlyphAiPrompt.ONE_WIDE_BITMAP))
        assertTrue(prompt.contains(GlyphAiPrompt.SAME_SHIFT_EVERY_ROW))
        // The method, in the three steps that make the shift one number.
        assertTrue(prompt.contains("panel-width WINDOW"))
        assertTrue(prompt.contains("ONE number changes per frame"))
        assertTrue(prompt.contains("Do NOT make a scrolled frame by nudging the rows"))
        // The bitmap itself, laid out as the prompt sets it, so its columns line
        // up: a ragged worked example would teach the very defect this fixes.
        assertTrue(
            prompt.contains(GlyphAiPrompt.MARQUEE_BITMAP.joinToString("\n").prependIndent("      ")),
        )
    }

    /** The worked bitmap must be a rectangle, or it is not a bitmap. */
    @Test
    fun `the worked marquee bitmap is rectangular and matches its stated size`() {
        assertEquals(GlyphAiPrompt.MARQUEE_HEIGHT, GlyphAiPrompt.MARQUEE_BITMAP.size)
        for (row in GlyphAiPrompt.MARQUEE_BITMAP) {
            assertEquals("every row is the same width", GlyphAiPrompt.MARQUEE_WIDTH, row.length)
            assertTrue("only palette indices 0 and 1", row.all { it == '0' || it == '1' })
        }
        // Tall enough to be a legible glyph, and short enough to sit inside the
        // band of rows that is live across every column at 13x13 — which is the
        // property that lets it scroll without losing a cell.
        val band = GlyphAiPrompt.fullWidthRows(PokemonCodename.BELLSPROUT.size)!!
        assertTrue(GlyphAiPrompt.MARQUEE_HEIGHT <= band.count())
    }

    /**
     * Nine frames cannot carry two letters across a thirteen-wide panel: the
     * message has to arrive, cross and leave. The model had no way to know that
     * and produced a count that could not have worked whatever was in the frames.
     */
    @Test
    fun `the prompt states the marquee frame budget for each panel it names`() {
        assertTrue(
            GlyphAiPrompt.build(TestDesigns.bellsproutOnly()).contains(GlyphAiPrompt.MARQUEE_BUDGET),
        )
        assertTrue(
            GlyphAiPrompt.build(TestDesigns.bellsproutOnly())
                .contains("frames = panel width + message width - 1"),
        )
        // The arithmetic done for the reader, per panel, and gated exactly as
        // every other panel-specific line in the prompt is.
        for (codename in PokemonCodename.entries) {
            val expected = "${codename.codename} is ${codename.size} columns wide, so the " +
                "${GlyphAiPrompt.MARQUEE_WIDTH}-column \"HI\" above is " +
                "${codename.size + GlyphAiPrompt.MARQUEE_WIDTH - 1} frames."
            assertTrue(expected, GlyphAiPrompt.build(TestDesigns.bothVariants()).contains(expected))
        }
        assertFalse(
            "a bellsprout-only conversation is never told arbok's frame count",
            GlyphAiPrompt.build(TestDesigns.bellsproutOnly()).contains("arbok is 25 columns wide"),
        )
    }

    /**
     * The ladder says "fewer frames" three sections earlier, and read against a
     * marquee that is the wrong advice — a scroll cut short does not read as a
     * shorter scroll. The two sections have to be reconciled in the text or they
     * contradict each other.
     */
    @Test
    fun `the frame budget is fenced off from the simplify ladder`() {
        val prompt = GlyphAiPrompt.build(TestDesigns.bellsproutOnly())

        assertTrue(prompt.contains("about distinct POSES"))
        // Short fragments, as elsewhere in this file: the prompt is hard-wrapped
        // prose and an assertion spanning a line break would fail on a reflow.
        assertTrue(prompt.contains("it never"))
        assertTrue(prompt.contains("means truncating a scroll"))
        // And the two cheaper ways out, so "make it shorter" has somewhere to go.
        assertTrue(prompt.contains("scroll a SHORTER message"))
        assertTrue(prompt.contains("move two columns per frame"))
    }

    /** Frame 0 of the failed "HI" was empty: a wasted beat at the top of the loop. */
    @Test
    fun `the prompt forbids a blank frame that is not deliberate`() {
        val prompt = GlyphAiPrompt.build(TestDesigns.bellsproutOnly())

        assertTrue(prompt.contains(GlyphAiPrompt.NO_BLANK_FRAMES))
        assertTrue(prompt.contains("a blank frame 0 means"))
        // And why it happens on a marquee, which is the case in hand.
        assertTrue(prompt.contains("the window started one step too early"))
    }

    /** Frame 1 was drawn at 4095 and frames 2-3 at 2048, which on the panel is a flicker. */
    @Test
    fun `the prompt keeps an element's brightness constant across frames`() {
        val prompt = GlyphAiPrompt.build(TestDesigns.bellsproutOnly())

        assertTrue(prompt.contains(GlyphAiPrompt.STEADY_BRIGHTNESS))
        assertTrue(prompt.contains("it reads as a flicker"))
        // The carve-out, so this does not become "never change brightness" and
        // undo the greys guidance in the style notes.
        assertTrue(prompt.contains("Changing brightness on purpose"))
    }

    /**
     * Said once in the format section was not enough: a model drew a "10" upside
     * down, was told, "rotated it 180 degrees" and got it wrong again. So it is
     * said beside the format, shown on the worked example's rows, and said a
     * third time here with the string operations that actually perform a flip.
     */
    @Test
    fun `the prompt says which way y runs, more than once, and how to flip a frame`() {
        val prompt = GlyphAiPrompt.build(TestDesigns.bellsproutOnly())

        assertTrue(prompt.contains(GlyphAiPrompt.ROW_ZERO_IS_TOP))
        assertTrue(
            "once beside the format and again where frames are animated",
            prompt.indexOf(GlyphAiPrompt.ROW_ZERO_IS_TOP) <
                prompt.lastIndexOf(GlyphAiPrompt.ROW_ZERO_IS_TOP),
        )
        // ...and the first of the two is in the format section, where rows are
        // written, rather than only in the animation notes at the end.
        assertTrue(
            prompt.indexOf(GlyphAiPrompt.ROW_ZERO_IS_TOP) <
                prompt.indexOf(GlyphAiPrompt.WORKED_EXAMPLE.prependIndent("  ")),
        )
        assertTrue(prompt.contains("reversing the ORDER OF THE ROWS"))
        assertTrue(prompt.contains("reversing the characters WITHIN each row"))
        assertTrue(prompt.contains("reversing the WHOLE cells string end to end"))
    }

    /**
     * The instruction that catches the other four. Every one of these defects is
     * plainly visible in the ASCII previews the tools already return, and none of
     * them is visible in the base36 — the model simply was not comparing frames
     * against each other.
     */
    @Test
    fun `the prompt tells the model to read the frame previews against each other`() {
        val prompt = GlyphAiPrompt.build(TestDesigns.bellsproutOnly())

        assertTrue(prompt.contains(GlyphAiPrompt.COMPARE_THE_FRAMES))
        assertTrue(prompt.contains("Read them AGAINST EACH OTHER"))
        // The three checks, in the order the defects were found.
        assertTrue(prompt.contains("Is any frame blank that should not be"))
        assertTrue(prompt.contains("must NEVER change row"))
        assertTrue(prompt.contains("Is every element at the same brightness in every frame"))
    }

    /**
     * Where it sits. This is arithmetic, not taste — a frame budget and an axis
     * direction are not things the user overrules — so it follows the workflow
     * and the ladder it qualifies, and precedes the style notes.
     */
    @Test
    fun `the animation guidance follows the simplify ladder and precedes the style notes`() {
        val prompt = GlyphAiPrompt.build(TestDesigns.bellsproutOnly())

        assertTrue(
            prompt.indexOf(GlyphAiPrompt.LAND_SOMETHING) <
                prompt.indexOf(GlyphAiPrompt.ONE_WIDE_BITMAP),
        )
        assertTrue(
            prompt.indexOf(GlyphAiPrompt.COMPARE_THE_FRAMES) <
                prompt.indexOf("MAKING ART THAT READS ON THIS PANEL"),
        )
    }

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
