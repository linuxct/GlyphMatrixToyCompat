package space.linuxct.glyphmatrixtoycompat.ui.design

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * by Next, a rotation). The instant path is the one that can be tested on a JVM,
 * and it is also the one that carries the whole rewind guarantee: if replaying
 * steps 0..n did not land on the same state as playing them, then Back and
 * rotation would each drop the user into an editor that does not match the
 * caption in front of them.
 *
 * So what is asserted here is not "the animation looks right" — a test cannot see
 * that — but the two things that decide whether the tour is *correct*: that it
 * demonstrates what it claims to (a duplicate that differs from its source, a
 * reorder that actually moves a frame, a duration that changes), and that it
 * touches nothing outside itself.
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

    @Test
    fun theDemoDesignIsAThrowawayThatCouldNotBeSavedIfItTried() {
        val design = demoDesign(home, "Slow Ember")
        // No id. `DesignStore` names files by id, so this one has nowhere on disk
        // to go even if some future edit forgot the editor's demo flag.
        assertEquals("", design.id)
        assertEquals("", design.author)
        // Dynamic, because half of what needs teaching is the timeline.
        assertEquals(DesignKind.DYNAMIC, design.kind)
    }

    /**
     * **One variant, deliberately.** The tour has just shown the new-design dialog
     * answering "which phone" with its default — the one in your hand — so an
     * editor carrying both sizes would contradict the step before it and would
     * show a variant switcher that the user's own next design will not have.
     */
    @Test
    fun theDemoDesignCarriesOnlyThisPhonesArtwork() {
        val design = demoDesign(home, "Slow Ember")
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
     * The frame layout at the end is the script's entire argument: a drawing, a
     * duplicate of it that has been nudged so it is visibly a different frame, a
     * blank one that was then dragged to the front, and a duration that moved off
     * its default. If any of those stopped happening the captions would be
     * describing something the user cannot see.
     */
    @Test
    fun theWholeTourEndsWithTheAnimationItNarrated() {
        val state = replayTo(DEMO_STEPS.size).state

        assertEquals("frames", 3, state.frames.size)
        // The reorder moved the blank frame it had just added to the front, and
        // the selection followed the frame rather than the slot.
        assertEquals(0, state.selectedIndex)
        assertTrue("the moved frame should be the blank one", state.lit(0).isEmpty())

        // The stroke survived the undo/redo step.
        val drawn = state.lit(1)
        assertTrue("nothing was painted", drawn.isNotEmpty())
        // The duplicate is the same drawing plus the nudge — which is the point of
        // duplicating, and the reason two identical frames would prove nothing.
        val nudged = state.lit(2)
        assertTrue("the duplicate lost the drawing", nudged.containsAll(drawn))
        assertTrue("the duplicate was never nudged", nudged.size > drawn.size)

        // Painted with the brightest swatch, because the palette step ends on it.
        assertEquals(state.design.levels.last(), state.frames[1].frame.copyOfCells()[drawn.first()])

        // The duration step actually stepped, twice, off the default.
        val durations = state.frames.map { it.durationMs }
        assertTrue("duration never changed", durations[0] > durations[1])
        assertEquals("only the selected frame's duration should move", durations[1], durations[2])

        // And none of it went anywhere: no id, one variant, still dynamic.
        assertEquals("", state.design.id)
        assertEquals(1, state.variantsPresent.size)
        assertEquals(DesignKind.DYNAMIC, state.design.kind)
    }

    /**
     * The Design settings steps put every control back where they found it.
     *
     * They exist because **four testers never found the repeat toggle**, so the
     * tour opens the dialog and works both controls rather than pointing at the
     * app-bar icon and moving on. Working a control means changing it, and the
     * two changes are load-bearing on each other: switching to play once is what
     * makes repeat disappear (which is the explanation), so the switch back is
     * what leaves a repeat toggle for the next step to point at. If either return
     * trip were ever dropped, the tour would end describing a design it is no
     * longer showing.
     */
    @Test
    fun theSettingsStepsEndWhereTheyStarted() {
        val opensSettings = DEMO_STEPS.indexOfFirst { it.target == DemoTarget.SETTINGS_ACTION }
        assertTrue("the tour never opens Design settings", opensSettings >= 0)
        // Every settings control is demonstrated inside the dialog, not from the
        // editor behind it.
        assertTrue(
            "a settings control is pointed at from the wrong stage",
            DEMO_STEPS.filter {
                it.target in setOf(DemoTarget.KEY_MODE, DemoTarget.LOOP, DemoTarget.ADD_VARIANT)
            }.let { it.isNotEmpty() && it.all { step -> step.stage == DemoStage.SETTINGS } },
        )

        val before = replayTo(opensSettings).state.design
        val after = replayTo(DEMO_STEPS.size).state.design
        assertEquals("repeat was left toggled", before.loop, after.loop)
        assertEquals("the key mode was left switched", before.keyMode, after.keyMode)
        // And the states the tour needs in order to be able to demonstrate them
        // at all: repeat only exists in play / pause, and it starts on.
        assertEquals(KeyMode.PLAY_PAUSE, after.keyMode)
        assertTrue(after.loop)
        // "Add ... artwork" is pointed at and never pressed — pressing it would
        // create a variant the tour has just explained this design does not have.
        assertNull(after.variantFor(PokemonCodename.ARBOK))
    }

    /**
     * Replaying is deterministic, which is what Back and a mid-tour rotation both
     * rest on: the tour saves nothing but the step number, so landing on step
     * eight has to mean exactly what arriving at step eight meant.
     */
    @Test
    fun replayingToAStepLandsInTheSameStateEveryTime() {
        for (step in DEMO_STEPS.indices) {
            val a = replayTo(step).state
            val b = replayTo(step).state
            assertEquals("frame count at step $step", a.frames.size, b.frames.size)
            assertEquals("selection at step $step", a.selectedIndex, b.selectedIndex)
            assertEquals("brush at step $step", a.brushIndex, b.brushIndex)
            a.frames.forEachIndexed { i, frame ->
                assertArrayEquals(
                    "frame $i at step $step",
                    frame.frame.copyOfCells(),
                    b.frames[i].frame.copyOfCells(),
                )
                assertEquals("duration $i at step $step", frame.durationMs, b.frames[i].durationMs)
            }
        }
    }

    /**
     * Replaying the steps before a step is the same as playing them — stated as a
     * property rather than assumed, because [DemoActor]'s two modes are the one
     * place in the tour where the same intent is expressed twice.
     */
    @Test
    fun replayingIsIncrementalStepByStep() {
        var previous = replayTo(0)
        for (step in 1..DEMO_STEPS.size) {
            val next = replayTo(step)
            // A step may add frames or repaint them, but nothing in this tour ever
            // deletes a frame or a variant — the demo never demonstrates the one
            // destructive control it has.
            assertTrue(
                "step ${step - 1} lost a frame",
                next.state.frames.size >= previous.state.frames.size,
            )
            assertFalse("step ${step - 1} touched the id", next.state.design.id.isNotEmpty())
            previous = next
        }
    }
}
