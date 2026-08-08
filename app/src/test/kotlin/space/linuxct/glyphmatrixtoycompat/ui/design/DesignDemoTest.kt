package space.linuxct.glyphmatrixtoycompat.ui.design

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import space.linuxct.glyphmatrixtoycompat.core.design.DesignKind
import space.linuxct.glyphmatrixtoycompat.core.design.KeyMode
import space.linuxct.glyphmatrixtoycompat.core.design.PokemonCodename

/**
 * The guided demo's script, driven with the clock taken out.
 *
 * Every step is written as one body that runs two ways — animated on screen, and
 * **instantly** when the tour has to rebuild its sandbox (Back, a step cut short
 * by Next, a rotation). The instant path is the one that can be tested on a JVM.
 *
 * Four things are worth a test here, and they are the four below: the sandbox is
 * a throwaway, the script is internally coherent (nothing points at a control
 * that is not on the stage it is spotlit from, nothing in the registry is dead),
 * and the tour actually *does* what its captions claim. Nothing here pins the
 * order of individual steps: the tour has been merged down twice now, and a test
 * that spelled out which step comes fourth would have to be rewritten every time
 * — which makes it a transcript of the script rather than a check on it.
 */
class DesignDemoTest {
    private val home = PokemonCodename.BELLSPROUT

    /** The tour as the host replays it: a fresh sandbox, every step, no waits. */
    private fun replayTo(step: Int): DemoSandbox = runBlocking {
        val sandbox = DemoSandbox(home)
        // No reported bounds at all, which is also what a replay really has when
        // it runs before the stage has been laid out: every ghost movement is a
        // no-op and only the state changes land.
        val actor = DemoActor(DemoGhost(), DemoTargets(), instant = true)
        for (i in 0 until step) DEMO_STEPS[i].act(actor, sandbox)
        sandbox
    }

    private fun EditorState.lit(index: Int): List<Int> =
        frames[index].frame.copyOfCells().withIndex().filter { it.value > 0 }.map { it.index }

    // ---- the sandbox ----

    /**
     * The design the tour draws on could not be saved if it tried, and carries
     * **one variant, deliberately** — the dialog step has just answered "which
     * phone" with its default, so an editor showing both sizes would contradict
     * the step before it and would put a variant switcher on screen that the
     * user's own next design will not have.
     */
    @Test
    fun theDemoDesignIsAThrowawayThatCouldNotBeSavedIfItTried() {
        val design = demoDesign(home, "Slow Ember")
        // No id. `DesignStore` names files by id, so this one has nowhere on disk
        // to go even if some future edit forgot the editor's demo flag.
        assertEquals("", design.id)
        assertEquals("", design.author)
        // Dynamic, because half of what needs teaching is the timeline.
        assertEquals(DesignKind.DYNAMIC, design.kind)
        assertNotNull(design.variantFor(home))
        assertNull(design.variantFor(PokemonCodename.ARBOK))
        assertEquals(1, DemoSandbox(home).state.variantsPresent.size)
    }

    // ---- the script ----

    @Test
    fun everyStepSaysSomethingAndPointsAtSomething() {
        assertTrue(DEMO_STEPS.size > 1)
        DEMO_STEPS.forEachIndexed { i, step ->
            assertTrue("step $i has no caption", step.caption != 0)
            // Only the closing step points at nothing: it is the tour saying it is
            // over, and a spotlight on an arbitrary control would be a lie about
            // where to look.
            if (i == DEMO_STEPS.lastIndex) {
                assertNull("the last step should point at nothing", step.target)
            } else {
                assertNotNull("step $i points at nothing", step.target)
            }
            // An index without a target would silently spotlight nothing.
            if (step.targetIndex != null) assertNotNull("step $i", step.target)
        }
        assertEquals(
            "two steps share a caption",
            DEMO_STEPS.size,
            DEMO_STEPS.map { it.caption }.toSet().size,
        )
        // The tour has to start where a design starts and end in the editor.
        assertEquals(DemoStage.CREATE, DEMO_STEPS.first().stage)
        assertEquals(DemoStage.EDITOR, DEMO_STEPS.last().stage)
    }

    /**
     * The whole tour, replayed — and every claim its captions make, checked.
     *
     * This is the one test that survives a merge unchanged, and the reason is that
     * it asserts the tour's *result* rather than its running order. Four steps
     * became one when the timeline steps were merged and the frame layout below is
     * identical, because the merged step still performs all four gestures: a
     * drawing, a duplicate of it nudged so it is visibly a different frame, a
     * blank frame dragged to the front, and a duration moved off its default. If
     * any of that stopped happening — which is exactly what "shortening" a tour
     * risks — the captions would be describing something the user cannot see.
     */
    @Test
    fun theWholeTourEndsWithTheAnimationItNarrated() {
        val state = replayTo(DEMO_STEPS.size).state

        assertEquals("frames", 3, state.frames.size)
        // The reorder moved the blank frame it had just added to the front, and
        // the selection followed the frame rather than the slot.
        assertEquals(0, state.selectedIndex)
        assertTrue("the moved frame should be the blank one", state.lit(0).isEmpty())

        // The stroke survived the undo and redo the drawing step performs on it.
        val drawn = state.lit(1)
        assertTrue("nothing was painted", drawn.isNotEmpty())
        // The duplicate is the same drawing plus the nudge — which is the point of
        // duplicating, and the reason two identical frames would prove nothing.
        val nudged = state.lit(2)
        assertTrue("the duplicate lost the drawing", nudged.containsAll(drawn))
        assertTrue("the duplicate was never nudged", nudged.size > drawn.size)

        // Painted with the brightest swatch, because the palette taps end on it.
        assertEquals(state.design.levels.last(), state.frames[1].frame.copyOfCells()[drawn.first()])

        // The duration step actually stepped off the default, on the selected
        // frame only.
        val durations = state.frames.map { it.durationMs }
        assertTrue("duration never changed", durations[0] > durations[1])
        assertEquals("only the selected frame's duration should move", durations[1], durations[2])

        // The Design settings steps put both controls back where they found them.
        // They work the controls rather than pointing at them because four testers
        // never found repeat — and the two changes are load-bearing on each other:
        // switching to play once is what makes repeat disappear, so the switch
        // back is what leaves a repeat toggle to demonstrate. Ending anywhere else
        // would leave the tour describing a design it is no longer showing.
        assertEquals(KeyMode.PLAY_PAUSE, state.design.keyMode)
        assertTrue(state.design.loop)

        // And none of it went anywhere: no id, one variant, still dynamic. "Add
        // ... artwork" is pointed at and never pressed.
        assertEquals("", state.design.id)
        assertEquals(1, state.variantsPresent.size)
        assertNull(state.design.variantFor(PokemonCodename.ARBOK))
        assertEquals(DesignKind.DYNAMIC, state.design.kind)
    }
}
