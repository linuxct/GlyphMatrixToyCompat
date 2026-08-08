package space.linuxct.glyphmatrixtoycompat.ui.design

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
    fun aDesignWithBothVariantsGetsTheSwitcher() {
        val state = state()
        assertEquals(PokemonCodename.entries.toList(), state.variantsPresent)
        assertNull("nothing is missing", state.missingVariant)
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

    // ---- whole-document replacement, and the way back ----
    //
    // The assistant's only route onto the canvas. Everything here is about the
    // two things a per-frame edit cannot express: a document that changes `kind`
    // or `levels` under the editor, and a step back that has to restore all of it
    // at once.

    /**
     * A frame of [codename]'s geometry filled with palette entry [index].
     *
     * `encode` takes *brightness*, not indices — the editor paints straight from
     * the palette — so the level is looked up rather than passed through.
     */
    private fun solidFrame(codename: PokemonCodename, index: Int, levels: List<Int>) = DesignFrame(
        durationMs = 90,
        cells = DesignFrames.encode(
            IntArray(codename.cellCount) { levels[index] },
            levels,
            codename.size,
        )!!,
    )

    @Test
    fun replacingTheDocumentPutsTheNewArtOnTheOpenCanvas() {
        val state = state()
        val before = state.design

        val applied = state.replaceDesign(
            before.copy(
                variants = before.variants + (
                    home.codename to DesignVariant(
                        frames = listOf(solidFrame(home, 2, DEFAULT_LEVELS)),
                    )
                    ),
            ),
        )

        assertNotNull("the apply was refused", applied)
        assertEquals(1, state.frames.size)
        assertTrue(state.cells(0).all { it == DEFAULT_LEVELS[2] })
        assertEquals(0, state.selectedIndex)
    }

    /**
     * The edit scope the whole feature was specified around: the model may write
     * **any variant the design carries**, not merely the one on screen. A change
     * to the variant that is not open has nothing to do on the canvas — it has to
     * be there when the user switches.
     */
    @Test
    fun aVariantThatIsNotOpenIsWrittenStraightIntoTheDesign() {
        val state = state()
        val arbok = PokemonCodename.ARBOK
        val before = state.design

        state.replaceDesign(
            before.copy(
                variants = before.variants + (
                    arbok.codename to DesignVariant(
                        frames = listOf(solidFrame(arbok, 2, DEFAULT_LEVELS)),
                    )
                    ),
            ),
        )

        // Nothing changed on screen: the open variant is still bellsprout.
        assertEquals(home, state.codename)
        assertEquals(1, state.design.variantFor(arbok)?.frames?.size)

        assertTrue(state.switchTo(arbok))
        assertTrue(state.cells(0).all { it == DEFAULT_LEVELS[2] })
    }

    /**
     * A timeline that appears mid-session. `kind` decides whether the editor
     * shows one at all, so a static design becoming a three-frame animation has
     * to arrive with three live frames, not with one and a stale `kind`.
     */
    @Test
    fun aStaticDesignCanBecomeAnAnimationAndBack() {
        val state = state(kind = DesignKind.STATIC, frames = 1)
        val before = state.composed()

        state.replaceDesign(
            before.copy(
                kind = DesignKind.DYNAMIC,
                loop = true,
                variants = before.variants + (
                    home.codename to DesignVariant(
                        frames = List(3) { solidFrame(home, it % 3, DEFAULT_LEVELS) },
                    )
                    ),
            ),
        )

        assertEquals(DesignKind.DYNAMIC, state.design.kind)
        assertTrue(state.design.loop)
        assertEquals(3, state.frames.size)
        assertTrue(state.canOnionSkin)

        // And back the other way, which is the case that would leave a timeline
        // on screen with a static design behind it.
        state.replaceDesign(before)
        assertEquals(DesignKind.STATIC, state.design.kind)
        assertEquals(1, state.frames.size)
        assertFalse(state.canOnionSkin)
    }

    /**
     * `cells` are palette *indices*, so a document that arrives with a shorter
     * palette moves every swatch under the user's brush. Left unclamped, the
     * brush would point past the end of the palette and paint an index no swatch
     * shows.
     */
    @Test
    fun aShorterPaletteRe_clampsTheBrush() {
        val state = state()
        state.brushIndex = 2
        val before = state.composed()

        val twoLevels = listOf(0, 4095)
        state.replaceDesign(
            before.copy(
                levels = twoLevels,
                variants = before.variants + (
                    home.codename to DesignVariant(
                        frames = listOf(solidFrame(home, 1, twoLevels)),
                    )
                    ),
            ),
        )

        assertEquals(twoLevels, state.design.levels)
        assertEquals(listOf(0, 1), state.brushIndices)
        assertEquals(1, state.brushIndex)
        assertEquals(4095, state.brushValue())
    }

    /**
     * Identity is the app's, never the document's. `id` names the file on disk,
     * and `author` and `createdAt` describe a person and a moment that a model
     * rewriting the artwork has no business restating.
     */
    @Test
    fun theDocumentCannotRenameOrRe_attributeTheDesign() {
        val state = state()
        val before = state.composed()

        state.replaceDesign(
            before.copy(
                id = "ffffffffffffffffffffffffffffffff",
                author = "Somebody else",
                createdAt = "1999-01-01T00:00:00Z",
                name = "A new name",
            ),
        )

        assertEquals(before.id, state.design.id)
        assertEquals(before.author, state.design.author)
        assertEquals(before.createdAt, state.design.createdAt)
        // The name is content, not identity: the model may change it.
        assertEquals("A new name", state.design.name)
    }

    /**
     * The one-tap way back, over the two changes that per-frame undo cannot
     * express. Everything except `modifiedAt` — which is honestly restamped,
     * because reverting is itself a change to the file — comes back identical.
     */
    @Test
    fun revertingRestoresTheWholeDocumentIncludingKindAndLevels() {
        val state = state(kind = DesignKind.STATIC, frames = 1)
        state.stroke(6, 6)
        val before = state.composed()

        val twoLevels = listOf(0, 4095)
        val previous = state.replaceDesign(
            before.copy(
                kind = DesignKind.DYNAMIC,
                levels = twoLevels,
                name = "Assistant's version",
                variants = mapOf(
                    home.codename to DesignVariant(
                        frames = List(4) { solidFrame(home, 1, twoLevels) },
                    ),
                    PokemonCodename.ARBOK.codename to DesignVariant(
                        frames = listOf(solidFrame(PokemonCodename.ARBOK, 1, twoLevels)),
                    ),
                ),
            ),
        )
        assertNotNull(previous)
        assertEquals(4, state.frames.size)

        // The snapshot the editor handed back is what "Undo AI change" applies.
        state.replaceDesign(previous!!)

        val after = state.composed()
        assertEquals(before.copy(modifiedAt = ""), after.copy(modifiedAt = ""))
        assertEquals(1, state.frames.size)
        assertEquals(DesignKind.STATIC, state.design.kind)
        assertEquals(DEFAULT_LEVELS, state.design.levels)
        // Down to the pixel that was drawn before the assistant touched anything.
        assertEquals(DEFAULT_LEVELS.last(), state.cells(0)[6 * home.size + 6])
    }

    /**
     * A document with no artwork at all is refused rather than applied. Nothing
     * validated can be in that state; the point is that the caller gets an answer
     * it can hand back to the model instead of the user getting a blank canvas
     * nobody explains.
     */
    @Test
    fun aDocumentWithNoArtworkIsRefusedAndChangesNothing() {
        val state = state()
        state.stroke(2, 2)
        val before = state.composed()

        assertNull(state.replaceDesign(Design(id = before.id)))

        assertEquals(before.copy(modifiedAt = ""), state.composed().copy(modifiedAt = ""))
        assertEquals(DEFAULT_LEVELS.last(), state.cells(0)[2 * home.size + 2])
    }
}
