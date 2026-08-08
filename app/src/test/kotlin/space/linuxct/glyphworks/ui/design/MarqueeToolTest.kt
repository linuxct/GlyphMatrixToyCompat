package space.linuxct.glyphworks.ui.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import space.linuxct.glyphworks.core.design.DEFAULT_LEVELS
import space.linuxct.glyphworks.core.design.Design
import space.linuxct.glyphworks.core.design.DesignCodec
import space.linuxct.glyphworks.core.design.DesignFrame
import space.linuxct.glyphworks.core.design.DesignFrames
import space.linuxct.glyphworks.core.design.DesignKind
import space.linuxct.glyphworks.core.design.DesignVariant
import space.linuxct.glyphworks.core.design.MarqueePlan
import space.linuxct.glyphworks.core.design.PokemonCodename

/**
 * The editor's scrolling-text tool, from the phrase to the document the editor
 * is left holding.
 *
 * Three tests on purpose. The letterforms are covered where they live
 * (`MarqueeFontTest`) and so is the scroll (`MarqueeTextTest`); what is only
 * true *here* is that the button's phrase reaches the canvas as an ordinary
 * design, that pressing undo afterwards puts the artwork back, and that the one
 * refusal a user can actually hit hands back numbers that work when acted on.
 * Everything else this path does is `EditorState.replaceDesign`, which has its
 * own file.
 */
class MarqueeToolTest {

    private val home = PokemonCodename.BELLSPROUT

    /** A design with art on the open panel and a second, empty panel carried. */
    private fun drawn(): Design = Design(
        id = "0123456789abcdef0123456789abcdef",
        name = "Test",
        author = "someone",
        createdAt = "2026-01-01T00:00:00Z",
        modifiedAt = "2026-01-01T00:00:00Z",
        kind = DesignKind.STATIC,
        levels = DEFAULT_LEVELS,
        variants = mapOf(
            home.codename to DesignVariant(
                frames = listOf(
                    DesignFrame(
                        durationMs = 120,
                        cells = DesignFrames.encode(
                            IntArray(home.cellCount) { DEFAULT_LEVELS[2] },
                            DEFAULT_LEVELS,
                            home.size,
                        )!!,
                    ),
                ),
            ),
            PokemonCodename.ARBOK.codename to DesignVariant(),
        ),
    )

    @Test
    fun aGeneratedMarqueeReplacesTheOpenPanelAndIsAnOrdinaryDesign() {
        val state = EditorState(drawn(), home)
        val before = state.design
        // The canvas is painted, so the dialog would confirm before doing this.
        assertEquals(1, drawnFrameCount(state))

        val plan = marqueePlanFor(state, "Hey") as MarqueePlan.Ready
        assertTrue(applyMarquee(state, plan.frames))

        // An animation that repeats, which is what a marquee is...
        assertEquals(DesignKind.DYNAMIC, state.design.kind)
        assertTrue(state.design.loop)
        // ...on the canvas, frame for frame with what the generator produced...
        assertEquals(plan.frames.size, state.frames.size)
        assertEquals(plan.frames, state.design.variantFor(home)!!.frames)
        assertNotEquals(before.variantFor(home), state.design.variantFor(home))
        // ...leaving the other panel and the file's identity alone...
        assertEquals(before.variantFor(PokemonCodename.ARBOK), state.design.variantFor(PokemonCodename.ARBOK))
        assertEquals(before.id, state.design.id)
        assertEquals(before.author, state.design.author)
        // ...and editable and saveable through the ordinary path, which means
        // the document the editor would write has to pass the ordinary codec.
        assertTrue(DesignCodec.validate(state.composed()) is DesignCodec.Result.Ok)
    }

    /**
     * The promise the dialog now makes in place of its confirmation step: the
     * artwork a marquee replaced comes back on the editor's ORDINARY undo — the
     * same `EditorState.undo` the tool row's arrow calls, not a revert of its own
     * — and redo puts the marquee back after it.
     *
     * Driven from the static design above deliberately. The tool-row button is
     * composed only for a dynamic design, so this is `applyMarquee`'s own
     * promotion rather than a route through the UI, and it is what makes `kind`
     * worth asserting: the step being restored is a whole document, so it has to
     * carry back the things per-frame undo cannot express.
     */
    @Test
    fun theEditorsOwnUndoTakesAMarqueeBackAndRedoPutsItOn() {
        val state = EditorState(drawn(), home)
        val before = state.composed()
        assertFalse(state.canUndo)

        val plan = marqueePlanFor(state, "Hey") as MarqueePlan.Ready
        assertTrue(applyMarquee(state, plan.frames))
        assertTrue("the marquee left nothing to undo", state.canUndo)

        assertTrue(state.undo())
        // Down to the document: kind, loop, the timeline and every pixel of the
        // panel that was painted before the phrase was typed.
        assertEquals(before.copy(modifiedAt = ""), state.composed().copy(modifiedAt = ""))
        assertEquals(DesignKind.STATIC, state.design.kind)
        assertEquals(1, state.frames.size)

        assertTrue("a marquee that cannot be redone is not on the stack", state.canRedo)
        assertTrue(state.redo())
        assertEquals(DesignKind.DYNAMIC, state.design.kind)
        assertEquals(plan.frames, state.design.variantFor(home)!!.frames)
    }

    @Test
    fun aPhraseTooLongToFitComesBackWithTwoWaysOutThatBothWork() {
        val state = EditorState(drawn(), home)
        val long = "the quick brown fox jumps over the lazy dog and keeps on going"

        val plan = marqueePlanFor(state, long) as MarqueePlan.TooLong
        assertTrue(plan.framesNeeded > plan.maxFrames)
        assertEquals(DesignCodec.MAX_FRAMES, plan.maxFrames)

        // Say less: the prefix it measured is a prefix of what was typed, and it
        // fits when it is sent back.
        assertTrue(plan.prefix.isNotEmpty() && long.startsWith(plan.prefix))
        assertTrue(marqueePlanFor(state, plan.prefix) is MarqueePlan.Ready)

        // Or move faster: the step it worked out carries the whole phrase.
        val faster = requireNotNull(plan.stepThatFits)
        assertTrue(marqueePlanFor(state, long, faster) is MarqueePlan.Ready)
    }
}
