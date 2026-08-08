package space.linuxct.glyphmatrixtoycompat.ui.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import space.linuxct.glyphmatrixtoycompat.core.design.DesignCodec
import kotlin.math.abs

/**
 * The timeline's arithmetic, tested away from Compose.
 *
 * Everything here is a pure function on purpose: the reorder is the one piece of
 * this editor whose bugs are silent — a frame landing one slot off, or the
 * selection drifting onto a frame the user is not looking at, both *look* like
 * working software — and none of it is provable by reading it. So the drag
 * itself is simulated: [Sim] is `DesignTimeline.applyReorder` transcribed, which
 * lets a sixty-frame gesture (with the auto-scroll feeding it) run in a
 * millisecond and assert on the resulting order.
 */
class DesignTimelineTest {
    /**
     * The reorder loop, exactly as `applyReorder` runs it: accumulate a logical
     * offset, ask [reorderShift] how many neighbours that has earned, move that
     * many and rebase the offset by one item width each time.
     *
     * The list holds ids rather than frames, which is the point of the whole
     * exercise — see [duplicatedFramesKeepTheirOwnIdentity].
     */
    private class Sim(count: Int, val width: Int = 60) {
        val ids = MutableList(count) { it }
        var index = 0
        var offset = 0f

        fun startAt(i: Int) {
            index = i
            offset = 0f
        }

        /** A pointer sample of [px] physical pixels, in a locale of [sign]. */
        fun drag(px: Float, sign: Int = 1) {
            offset += px * sign
            settle()
        }

        /** One auto-scroll step: the strip moved under a stationary finger. */
        fun autoScroll(consumed: Float) {
            offset += consumed
            settle()
        }

        private fun settle() {
            val shift = reorderShift(offset, width, index, ids.lastIndex)
            if (shift == 0) return
            val step = if (shift > 0) 1 else -1
            repeat(abs(shift)) {
                moveItem(ids, index, index + step)
                index += step
                offset -= step * width
            }
        }
    }

    // ---- the threshold itself ----

    @Test
    fun crossingTheThresholdMovesOnePlace() {
        assertEquals(1, reorderShift(37f, 60, 3, 9))
        assertEquals(-1, reorderShift(-37f, 60, 3, 9))
    }

    @Test
    fun theEndsOfTheListAreHard() {
        // Already last: nothing to move past, however far the finger goes.
        assertEquals(0, reorderShift(10_000f, 60, 9, 9))
        assertEquals(0, reorderShift(-10_000f, 60, 0, 9))
        // A single-frame timeline has no reorder at all.
        assertEquals(0, reorderShift(10_000f, 60, 0, 0))
        // Degenerate width (nothing measured yet) must not divide anything.
        assertEquals(0, reorderShift(10_000f, 0, 0, 9))
    }

    // ---- acceptance criterion 1: the far end of a 60-frame timeline ----

    /**
     * **Timeline acceptance criterion 1.** A frame is dragged from position 1 to
     * the far end of a 60-frame timeline in one uninterrupted gesture: the
     * finger moves a little and then holds at the edge, and the auto-scroll
     * feeds the offset from there.
     *
     * The frame must arrive at the very end, every other frame must have shifted
     * down by exactly one, and the drag's own index must have tracked it the
     * whole way — a mismatch there is the case where releasing drops the frame
     * in the wrong slot.
     */
    @Test
    fun aFrameCanBeDraggedToTheEndOfASixtyFrameTimeline() {
        val sim = Sim(count = 60)
        sim.startAt(1)
        val dragged = sim.ids[1]

        // The finger moves one thumbnail's worth to reach the edge...
        sim.drag(60f)
        // ...and then holds there while the strip scrolls beneath it. 1.2 px/ms
        // at 120 Hz is ~10 px a frame, so this is about three and a half seconds
        // of holding — and the last of it is spent against the end of the list,
        // which must not overshoot.
        repeat(400) { sim.autoScroll(10f) }

        assertEquals("did not reach the end", 59, sim.index)
        assertEquals("wrong frame at the end", dragged, sim.ids[59])
        assertEquals(59, sim.ids.indexOf(dragged))
        // Everything else kept its order, one slot earlier.
        assertEquals((0 until 60).filter { it != dragged }, sim.ids.dropLast(1))
    }

    // ---- acceptance criterion 2: duplicates are not interchangeable ----

    // ---- acceptance criterion 3: RTL ----

    /**
     * **Timeline acceptance criterion 3.** In an RTL locale the strip is drawn
     * right-to-left, so a *physically leftward* drag has to move a frame towards
     * a HIGHER index — the opposite of what the same pixels mean in LTR.
     *
     * [dragSign] is applied to pointer deltas going in and to `translationX`
     * coming out, so this asserts the pair: the same physical gesture produces
     * mirrored index movement, and mirrored index movement produces the same
     * on-screen displacement.
     */
    @Test
    fun aHorizontalDragIsMirroredInAnRtlLocale() {
        assertEquals(1, dragSign(rtl = false))
        assertEquals(-1, dragSign(rtl = true))

        // The identical physical gesture: 120 px to the left.
        val ltr = Sim(count = 6).apply { startAt(3); drag(-120f, dragSign(rtl = false)) }
        val rtl = Sim(count = 6).apply { startAt(3); drag(-120f, dragSign(rtl = true)) }

        assertEquals("LTR: leftward is towards the start", 1, ltr.index)
        assertEquals("RTL: leftward is towards the end", 5, rtl.index)
        assertEquals(listOf(0, 3, 1, 2, 4, 5), ltr.ids)
        assertEquals(listOf(0, 1, 2, 4, 5, 3), rtl.ids)

        // And the same offset renders mirrored, which is what keeps the frame
        // under the finger rather than sliding away from it.
        val logical = 40f
        assertEquals(40f, logical * dragSign(rtl = false), 0f)
        assertEquals(-40f, logical * dragSign(rtl = true), 0f)
    }

    // ---- list surgery ----

    /** The selection follows the FRAME, never the slot. */
    @Test
    fun selectionFollowsTheFrameThroughAMove() {
        // The selected frame is the one being moved.
        assertEquals(7, selectionAfterMove(selected = 2, from = 2, to = 7))
        // Something before it moved past it: everything shuffles down one.
        assertEquals(3, selectionAfterMove(selected = 4, from = 1, to = 6))
        // Something after it moved in front of it: shuffles up one.
        assertEquals(5, selectionAfterMove(selected = 4, from = 8, to = 2))
        // A move entirely on the far side of it changes nothing.
        assertEquals(4, selectionAfterMove(selected = 4, from = 6, to = 8))
        assertEquals(4, selectionAfterMove(selected = 4, from = 1, to = 0))
    }

    // ---- durations ----

    /**
     * The control cannot produce a value the codec would reject. That is not a
     * separate check in the UI, it is the shape of the ladder — so the ladder is
     * what is asserted.
     */
    @Test
    fun everyLadderRungIsAValueTheCodecAccepts() {
        assertEquals(DesignCodec.MIN_DURATION_MS, DURATION_STEPS.first())
        assertEquals(DesignCodec.MAX_DURATION_MS, DURATION_STEPS.last())
        for (step in DURATION_STEPS) {
            assertTrue("$step below the floor", step >= DesignCodec.MIN_DURATION_MS)
            assertTrue("$step above the ceiling", step <= DesignCodec.MAX_DURATION_MS)
        }
        // Strictly ascending, or stepping could stall or go backwards.
        for (i in 1 until DURATION_STEPS.size) {
            assertTrue("not ascending at $i", DURATION_STEPS[i] > DURATION_STEPS[i - 1])
        }
    }

    @Test
    fun durationsAreClampedToTheCodecsRange() {
        assertEquals(DesignCodec.MIN_DURATION_MS, clampDuration(0))
        assertEquals(DesignCodec.MIN_DURATION_MS, clampDuration(-5_000))
        assertEquals(DesignCodec.MAX_DURATION_MS, clampDuration(Int.MAX_VALUE))
        assertEquals(120, clampDuration(120))
    }

    @Test
    fun steppingWalksTheLadderAndSaturates() {
        assertEquals(150, stepDuration(120, up = true))
        assertEquals(100, stepDuration(120, up = false))
        // Off-ladder values (an imported file's own timing) still move, by
        // comparison rather than by index.
        assertEquals(120, stepDuration(111, up = true))
        assertEquals(100, stepDuration(111, up = false))
        // The ends hold rather than wrapping round.
        assertEquals(DesignCodec.MAX_DURATION_MS, stepDuration(DesignCodec.MAX_DURATION_MS, up = true))
        assertEquals(DesignCodec.MIN_DURATION_MS, stepDuration(DesignCodec.MIN_DURATION_MS, up = false))
        // An illegal value coming in is clamped before it is stepped.
        assertEquals(DesignCodec.MIN_DURATION_MS, stepDuration(-1, up = false))
        assertEquals(30, stepDuration(-1, up = true))
    }
}
