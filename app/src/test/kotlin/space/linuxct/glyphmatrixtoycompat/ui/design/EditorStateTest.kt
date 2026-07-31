package space.linuxct.glyphmatrixtoycompat.ui.design

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import space.linuxct.glyphmatrixtoycompat.core.design.DEFAULT_LEVELS
import space.linuxct.glyphmatrixtoycompat.core.design.Design
import space.linuxct.glyphmatrixtoycompat.core.design.DesignCodec
import space.linuxct.glyphmatrixtoycompat.core.design.DesignFrame
import space.linuxct.glyphmatrixtoycompat.core.design.DesignFrames
import space.linuxct.glyphmatrixtoycompat.core.design.DesignKind
import space.linuxct.glyphmatrixtoycompat.core.design.DesignVariant
import space.linuxct.glyphmatrixtoycompat.core.design.PokemonCodename

/**
 * The editor's model, driven directly.
 *
 * `EditorState` holds Compose snapshot state but is otherwise plain Kotlin and
 * touches no `android.*`, so it runs in a JVM unit test exactly as it runs on the
 * device — which matters, because the things worth asserting here are the ones a
 * screenshot cannot show: that a duplicate is a *different frame* that merely
 * looks the same, that undo belongs to the frame it was performed on, and that
 * a design can never be reduced to no frames at all.
 */
class EditorStateTest {

    private val home = PokemonCodename.BELLSPROUT

    private fun design(kind: DesignKind = DesignKind.DYNAMIC, frames: Int = 1): Design = Design(
        id = "0123456789abcdef0123456789abcdef",
        name = "Test",
        kind = kind,
        levels = DEFAULT_LEVELS,
        variants = mapOf(
            home.codename to DesignVariant(
                frames = List(frames) { DesignFrame(durationMs = 100 + it, cells = DesignFrames.blank(home)) },
            ),
            PokemonCodename.ARBOK.codename to DesignVariant(),
        ),
    )

    private fun state(kind: DesignKind = DesignKind.DYNAMIC, frames: Int = 1) =
        EditorState(design(kind, frames), home)

    /** One stroke of one cell, the way the pointer handler does it. */
    private fun EditorState.stroke(x: Int, y: Int) {
        beginStroke()
        paint(x, y)
        endStroke()
    }

    private fun EditorState.cells(index: Int): IntArray = frames[index].frame.copyOfCells()

    // ---- loading ----

    @Test
    fun everyStoredFrameIsLoadedWithItsOwnTiming() {
        val state = state(frames = 4)
        assertEquals(4, state.frames.size)
        assertEquals(listOf(100, 101, 102, 103), state.frames.map { it.durationMs })
        assertEquals(0, state.selectedIndex)
    }

    /**
     * A variant that has never been drawn on carries no frames at all — the
     * format's blank-canvas rule — so the editor has to invent one rather than
     * render nothing. `selected` must never be able to throw.
     */
    @Test
    fun anEmptyVariantOpensOnOneBlankFrame() {
        val state = state()
        assertTrue(state.switchTo(PokemonCodename.ARBOK))
        assertEquals(1, state.frames.size)
        assertEquals(PokemonCodename.ARBOK.size, state.selected.frame.size)
        assertTrue(state.cells(0).all { it == 0 })
    }

    @Test
    fun frameIdsAreUniqueAcrossEverythingTheEditorEverMakes() {
        val state = state(frames = 3)
        state.addFrame()
        state.duplicateFrame()
        val before = state.frames.map { it.id }
        state.switchTo(PokemonCodename.ARBOK)
        state.addFrame()
        val all = before + state.frames.map { it.id }
        assertEquals("ids were reused", all.size, all.toSet().size)
    }

    // ---- add / duplicate / delete ----

    @Test
    fun addingAFrameInsertsAfterTheSelectedOneAndSelectsIt() {
        val state = state(frames = 3)
        state.select(1)
        assertTrue(state.addFrame())
        assertEquals(4, state.frames.size)
        assertEquals(2, state.selectedIndex)
        assertTrue("a new frame starts blank", state.cells(2).all { it == 0 })
        // It inherits the timing of the frame it was added after, so an
        // animation being built at one rate does not step to another.
        assertEquals(101, state.frames[2].durationMs)
    }

    @Test
    fun duplicatingCopiesThePixelsIntoANewFrame() {
        val state = state(frames = 2)
        state.select(0)
        state.stroke(6, 6)
        state.setSelectedDuration(250)

        assertTrue(state.duplicateFrame())
        assertEquals(3, state.frames.size)
        assertEquals(1, state.selectedIndex)
        assertArrayEquals("the copy is not a copy", state.cells(0), state.cells(1))
        assertEquals(250, state.frames[1].durationMs)

        // ...and it is a genuinely separate frame: a different id, and painting
        // on one must not touch the other. This is the property the timeline's
        // `key` depends on and the reason content can never be that key.
        assertTrue(state.frames[0].id != state.frames[1].id)
        state.stroke(2, 2)
        assertEquals(0, state.cells(0)[2 * home.size + 2])
        assertEquals(DEFAULT_LEVELS.last(), state.cells(1)[2 * home.size + 2])
    }

    @Test
    fun duplicatesDoNotInheritTheOriginalsUndoHistory() {
        val state = state(frames = 1)
        state.stroke(4, 4)
        assertTrue(state.canUndo)
        state.duplicateFrame()
        assertFalse("the copy arrived with somebody else's history", state.canUndo)
    }

    /**
     * The decision documented on `EditorState.deleteFrame`: the last frame is
     * not deletable, and the design is NOT silently turned static.
     */
    @Test
    fun theLastFrameCannotBeDeleted() {
        val state = state(frames = 2)
        assertTrue(state.deleteFrame())
        assertEquals(1, state.frames.size)
        assertFalse(state.deleteFrame())
        assertEquals(1, state.frames.size)
        assertEquals(DesignKind.DYNAMIC, state.design.kind)
    }

    @Test
    fun deletingMovesTheSelectionToWhateverTookItsPlace() {
        val state = state(frames = 4)
        state.select(1)
        state.deleteFrame()
        // Frame 2 slid into slot 1.
        assertEquals(1, state.selectedIndex)
        assertEquals(102, state.selected.durationMs)
        // Deleting the last frame in the list falls back one.
        state.select(2)
        state.deleteFrame()
        assertEquals(1, state.selectedIndex)
    }

    @Test
    fun theFormatsFrameCeilingIsRespected() {
        val state = state(frames = DesignCodec.MAX_FRAMES)
        assertTrue(state.atFrameLimit)
        assertFalse(state.addFrame())
        assertFalse(state.duplicateFrame())
        assertEquals(DesignCodec.MAX_FRAMES, state.frames.size)
    }

    // ---- reordering ----

    @Test
    fun movingAFrameCarriesTheSelectionWithIt() {
        val state = state(frames = 5)
        state.select(3)
        val moved = state.selected.id
        assertTrue(state.moveFrame(3, 0))
        assertEquals(0, state.selectedIndex)
        assertEquals(moved, state.frames[0].id)
        assertEquals(moved, state.selected.id)
        assertEquals(listOf(103, 100, 101, 102, 104), state.frames.map { it.durationMs })
    }

    // ---- per-frame undo ----

    /**
     * The trap this design exists to avoid: with one shared history, drawing on
     * frame 0, moving to frame 1 and pressing undo would restore frame 0's
     * pixels — into whichever frame is selected. Undo has to mean "undo what I
     * did *here*".
     */
    @Test
    fun undoHistoryBelongsToTheFrameItWasMadeOn() {
        val state = state(frames = 2)
        state.select(0)
        state.stroke(6, 6)
        assertTrue(state.canUndo)

        state.select(1)
        assertFalse("frame 1 inherited frame 0's history", state.canUndo)
        assertFalse("undo would have edited a frame nobody is looking at", state.undo())
        assertTrue("frame 0 was altered from frame 1", state.cells(1).all { it == 0 })
        assertEquals(DEFAULT_LEVELS.last(), state.cells(0)[6 * home.size + 6])

        // ...and it is still there when the user comes back to it.
        state.select(0)
        assertTrue(state.canUndo)
        assertTrue(state.undo())
        assertTrue(state.cells(0).all { it == 0 })
        assertTrue(state.canRedo)
        assertTrue(state.redo())
        assertEquals(DEFAULT_LEVELS.last(), state.cells(0)[6 * home.size + 6])
    }

    @Test
    fun clearAndFillActOnTheSelectedFrameOnly() {
        val state = state(frames = 3)
        state.select(1)
        assertTrue(state.fillAll(state.brushValue()))
        assertTrue(state.cells(1).all { it == DEFAULT_LEVELS.last() })
        assertTrue(state.cells(0).all { it == 0 })
        assertTrue(state.cells(2).all { it == 0 })
        assertTrue(state.undo())
        assertTrue(state.cells(1).all { it == 0 })
    }

    // ---- durations, loop, key mode ----

    @Test
    fun durationsCannotLeaveTheRangeTheCodecAccepts() {
        val state = state(frames = 2)
        state.setSelectedDuration(-1)
        assertEquals(DesignCodec.MIN_DURATION_MS, state.selected.durationMs)
        state.setSelectedDuration(Int.MAX_VALUE)
        assertEquals(DesignCodec.MAX_DURATION_MS, state.selected.durationMs)
        // A no-op change reports false, so it does not schedule a pointless save.
        assertFalse(state.setSelectedDuration(DesignCodec.MAX_DURATION_MS))
        // And it is per frame, not per design.
        assertEquals(101, state.frames[1].durationMs)
    }

    @Test
    fun theTotalIsTheSumOfTheFrames() {
        val state = state(frames = 3)
        assertEquals(100 + 101 + 102, state.totalDurationMs)
        state.select(2)
        state.setSelectedDuration(1_000)
        assertEquals(100 + 101 + 1_000, state.totalDurationMs)
    }

    @Test
    fun loopAndKeyModeLandOnTheDesign() {
        val state = state()
        val before = state.design.loop
        assertTrue(state.setLoop(!before))
        assertEquals(!before, state.design.loop)
        assertFalse(state.setLoop(!before))
    }

    // ---- onion skin ----

    @Test
    fun onionSkinNeedsAPreviousFrameAndAnAnimation() {
        val single = state(frames = 1)
        assertFalse(single.canOnionSkin)
        single.onionSkin = true
        assertNull(single.onionCellsForDraw())

        val still = state(kind = DesignKind.STATIC, frames = 3)
        assertFalse("a still image has no previous frame", still.canOnionSkin)

        val animation = state(frames = 3)
        assertTrue(animation.canOnionSkin)
        animation.onionSkin = true
        // Frame 0 of a non-looping design has nothing before it...
        assertNull(animation.onionCellsForDraw())
        animation.select(1)
        animation.stroke(5, 5)
        animation.select(2)
        assertEquals(DEFAULT_LEVELS.last(), animation.onionCellsForDraw()!![5 * home.size + 5])
        // ...but in a LOOPING design the frame before frame 0 is the last one,
        // which is exactly what you need to see to close a loop cleanly.
        animation.select(0)
        assertNull(animation.onionCellsForDraw())
        animation.setLoop(true)
        assertArrayEquals(animation.cells(2), animation.onionCellsForDraw())
        // Off is off.
        animation.onionSkin = false
        assertNull(animation.onionCellsForDraw())
    }

    // ---- variants stay independent ----

    @Test
    fun eachVariantKeepsItsOwnTimeline() {
        val state = state(frames = 2)
        state.stroke(6, 6)
        state.addFrame()
        assertEquals(3, state.frames.size)

        assertTrue(state.switchTo(PokemonCodename.ARBOK))
        assertEquals("arbok inherited bellsprout's timeline", 1, state.frames.size)
        state.addFrame()
        state.addFrame()

        assertTrue(state.switchTo(home))
        assertEquals(3, state.frames.size)
        assertEquals(DEFAULT_LEVELS.last(), state.cells(0)[6 * home.size + 6])
        // The other geometry's frames survived being left.
        assertEquals(3, state.design.variantFor(PokemonCodename.ARBOK)?.frames?.size)
        assertEquals(
            PokemonCodename.ARBOK.cellCount,
            state.design.variantFor(PokemonCodename.ARBOK)?.frames?.first()?.cells?.length,
        )
    }

    // ---- one variant, or two ----

    /**
     * A design created for one phone. `variantsPresent` is what the editor's
     * layout asks before spending ~56 dp on a switcher, so it has to be a
     * function of the artwork and of nothing else.
     */
    private fun singleVariant(codename: PokemonCodename) = EditorState(
        Design(
            id = "0123456789abcdef0123456789abcdef",
            name = "One size",
            levels = DEFAULT_LEVELS,
            variants = mapOf(
                codename.codename to DesignVariant(
                    frames = listOf(DesignFrame(cells = DesignFrames.blank(codename))),
                ),
            ),
        ),
        codename,
    )

    @Test
    fun aDesignWithOneVariantHasNothingToSwitchBetween() {
        val state = singleVariant(home)
        assertEquals(listOf(home), state.variantsPresent)
        assertEquals(PokemonCodename.ARBOK, state.missingVariant)
    }

    @Test
    fun aDesignWithBothVariantsGetsTheSwitcher() {
        val state = state()
        assertEquals(PokemonCodename.entries.toList(), state.variantsPresent)
        assertNull("nothing is missing", state.missingVariant)
    }

    /**
     * The escape hatch. Adding the other size makes the switcher appear and
     * leaves every pixel of the drawing that was already there alone — it adds a
     * blank canvas, which is what a second size has always been.
     */
    @Test
    fun addingTheMissingVariantIsWhatBringsTheSwitcherBack() {
        val state = singleVariant(home)
        state.stroke(6, 6)

        assertTrue(state.addVariant(PokemonCodename.ARBOK))
        assertEquals(PokemonCodename.entries.toList(), state.variantsPresent)
        assertNull(state.missingVariant)
        // A canvas, not artwork: nothing has been drawn for it yet.
        assertEquals(0, state.design.variantFor(PokemonCodename.ARBOK)?.frames?.size)
        // The drawing that was already open is untouched.
        assertEquals(DEFAULT_LEVELS.last(), state.cells(0)[6 * home.size + 6])

        // Idempotent, and now switchable — the newly added size opens on the
        // blank frame `loadFrames` invents for a variant nobody has drawn.
        assertFalse(state.addVariant(PokemonCodename.ARBOK))
        assertTrue(state.switchTo(PokemonCodename.ARBOK))
        assertEquals(1, state.frames.size)
        assertTrue(state.cells(0).all { it == 0 })
    }

    /**
     * The one that would silently undo somebody's choice. `composed()` writes the
     * OPEN variant back into the design, so an editor that opened a Phone
     * (3)-only design on this phone's own 13x13 would create a bellsprout variant
     * on the first save. It opens on the variant the design actually has instead.
     */
    @Test
    fun theEditorOpensOnAVariantTheDesignActuallyHas() {
        val phone3Only = singleVariant(PokemonCodename.ARBOK).design
        assertEquals(PokemonCodename.ARBOK, openingCodename(phone3Only, home = home))

        val phone4aOnly = singleVariant(home).design
        assertEquals(home, openingCodename(phone4aOnly, home = home))

        // With both present the phone in the user's hand still wins.
        val both = state().design
        assertEquals(home, openingCodename(both, home = home))
        assertEquals(PokemonCodename.ARBOK, openingCodename(both, home = PokemonCodename.ARBOK))
    }

    /**
     * The same guarantee from the other end. `composed()` writes `codename`'s
     * frames back, so "which variant is open" is precisely "which variant a save
     * can create" — and for a one-size design opened this way it is the one that
     * already exists, on this phone or the other.
     */
    @Test
    fun aOneSizeDesignOpensOnTheSizeItHas() {
        val design = singleVariant(PokemonCodename.ARBOK).design
        val state = EditorState(design, openingCodename(design, home = home))
        assertEquals(PokemonCodename.ARBOK, state.codename)
        assertEquals(PokemonCodename.ARBOK.size, state.selected.frame.size)
        assertEquals(listOf(PokemonCodename.ARBOK), state.variantsPresent)
    }
}
