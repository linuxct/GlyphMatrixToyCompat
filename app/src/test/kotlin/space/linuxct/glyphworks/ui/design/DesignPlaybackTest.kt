package space.linuxct.glyphworks.ui.design

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import space.linuxct.glyphworks.core.design.KeyMode

/**
 * Playback, driven without a panel, a clock or a composition.
 *
 * The three things asserted here are the three that are wrong *silently*. An
 * animation that ignores its frame durations, one that loops when its author asked
 * it to stop, and one that stops when they asked it to loop all render perfectly
 * happily — the failure is only visible to somebody sitting and watching the back
 * of a phone with a stopwatch. Same argument for the preview's sizing: a widget
 * that is 8 dp too big in a corner looks fine everywhere except on the window
 * where it covers the artwork.
 */
class DesignPlaybackTest {
    // ---------- the frame-advance schedule ----------

    @Test
    fun `playback walks the frames in order`() {
        assertEquals(1, nextPlaybackFrame(0, count = 3, loop = false))
        assertEquals(2, nextPlaybackFrame(1, count = 3, loop = false))
    }

    @Test
    fun `a looping design starts again`() {
        assertEquals(0, nextPlaybackFrame(2, count = 3, loop = true))
    }

    @Test
    fun `a non-looping design holds its last frame`() {
        // Null is "come to rest", and the caller pushes nothing further — so the
        // panel keeps the last frame rather than snapping back to the first.
        // Ending on the image its author ended it on is the whole point of
        // switching repeat off, and `CustomScreen.advance` does the same.
        assertNull(nextPlaybackFrame(2, count = 3, loop = false))
    }

    // ---------- whether it repeats at all ----------

    @Test
    fun `repeat is the design's own field when the key plays and pauses`() {
        assertTrue(designRepeats(loop = true, keyMode = KeyMode.PLAY_PAUSE))
        assertFalse(designRepeats(loop = false, keyMode = KeyMode.PLAY_PAUSE))
    }

    // ---------- how long each frame is held ----------

    @Test
    fun `a frame is held for exactly as long as the design says`() {
        assertEquals(120L, playbackHoldMs(120))
        // No ceiling: DesignCodec allows a 60-second frame, and a playback that
        // clamped it would be lying about the one thing it exists to check.
        assertEquals(60_000L, playbackHoldMs(60_000))
    }

    // ---------- the two together: a whole run ----------

    /** The panel loop's schedule, as `LiveMatrixPreview` walks it. */
    private fun run(durationsMs: List<Int>, loop: Boolean, limit: Int = 12): List<Pair<Int, Long>> {
        val pushes = mutableListOf<Pair<Int, Long>>()
        var index = 0
        while (pushes.size < limit) {
            pushes += index to playbackHoldMs(durationsMs[index])
            index = nextPlaybackFrame(index, durationsMs.size, loop) ?: break
        }
        return pushes
    }

    @Test
    fun `a design that does not repeat plays through once`() {
        val pushes = run(listOf(100, 250, 20), loop = false)
        assertEquals(listOf(0, 1, 2), pushes.map { it.first })
        // Each frame's own duration, with only the 20 ms one floored.
        assertEquals(listOf(100L, 250L, PREVIEW_INTERVAL_MS), pushes.map { it.second })
    }

    @Test
    fun `a repeating design cycles for as long as it is left running`() {
        val pushes = run(listOf(100, 250, 20), loop = true, limit = 7)
        assertEquals(listOf(0, 1, 2, 0, 1, 2, 0), pushes.map { it.first })
    }

    // ---------- the floating preview's loop ----------

    @Test
    fun `the preview holds each frame for its own duration`() {
        val durations = listOf(100, 200, 300)
        val playback = PreviewPlayback()
        // The first tick only arms the deadline: the clock it is handed is the
        // frame clock's, which has no defined zero.
        playback.tick(0, durations.size, loop = true) { durations[it] }
        assertEquals(0, playback.frameIndex)
        playback.tick(99, durations.size, loop = true) { durations[it] }
        assertEquals(0, playback.frameIndex)
        playback.tick(100, durations.size, loop = true) { durations[it] }
        assertEquals(1, playback.frameIndex)
        playback.tick(299, durations.size, loop = true) { durations[it] }
        assertEquals(1, playback.frameIndex)
        playback.tick(300, durations.size, loop = true) { durations[it] }
        assertEquals(2, playback.frameIndex)
        // And round again, because the widget always cycles.
        playback.tick(600, durations.size, loop = true) { durations[it] }
        assertEquals(0, playback.frameIndex)
    }

    @Test
    fun `the preview rests on the last frame of a design that does not repeat`() {
        val durations = listOf(100, 200)
        val playback = PreviewPlayback()
        playback.tick(0, durations.size, loop = false) { durations[it] }
        playback.tick(100, durations.size, loop = false) { durations[it] }
        assertEquals(1, playback.frameIndex)
        // 200 ms of frame, then the rest beat, and only then does it wrap: the
        // corner preview never stops, but it does not pretend a design that ends
        // runs straight back into its own beginning.
        playback.tick(100 + 200 + PREVIEW_REST_MS - 1, durations.size, loop = false) { durations[it] }
        assertEquals(1, playback.frameIndex)
        playback.tick(100 + 200 + PREVIEW_REST_MS, durations.size, loop = false) { durations[it] }
        assertEquals(0, playback.frameIndex)
    }

    // ---------- how big the floating preview is ----------

    /** The 411 x 919 dp window of the phone this app is built for. */
    private val phone = DpSize(411.dp, 919.dp)

    @Test
    fun `the resting preview is a fixed corner disc on a phone`() {
        assertEquals(72.dp, floatingPreviewDiameter(phone, expanded = false))
    }

    @Test
    fun `the expanded preview is near-fullscreen but not fullscreen`() {
        val large = floatingPreviewDiameter(phone, expanded = true)
        assertTrue("$large should be most of the width", large > 340.dp)
        assertTrue("$large should not fill the width", large < 411.dp)
    }

    // ---------- the preview's bitmap cache ----------

    @Test
    fun `a frame keeps its own cache`() {
        val caches = FramePreviewCaches()
        assertSame(caches.of(7L), caches.of(7L))
        assertNotSame(caches.of(7L), caches.of(8L))
    }

    // endregion

    private companion object {
        const val SAMPLES = 400
        const val TOLERANCE = 1e-4f
    }
}
