package space.linuxct.glyphmatrixtoycompat.screens

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import space.linuxct.glyphmatrixtoycompat.GoldenAscii
import space.linuxct.glyphmatrixtoycompat.TestHarness
import space.linuxct.glyphmatrixtoycompat.core.Events
import space.linuxct.glyphmatrixtoycompat.core.design.DEFAULT_LEVELS
import space.linuxct.glyphmatrixtoycompat.core.design.Design
import space.linuxct.glyphmatrixtoycompat.core.design.DesignFrame
import space.linuxct.glyphmatrixtoycompat.core.design.DesignFrames
import space.linuxct.glyphmatrixtoycompat.core.design.DesignKind
import space.linuxct.glyphmatrixtoycompat.core.design.DesignVariant
import space.linuxct.glyphmatrixtoycompat.core.design.KeyMode
import space.linuxct.glyphmatrixtoycompat.core.design.PokemonCodename

/**
 * The Custom screen, driven entirely through the fake scheduler.
 *
 * Timing is asserted against each frame's own `durationMs`, never against a tick
 * count: the whole reason this screen chains one-shots instead of setting a
 * ticker is that a design's frames may each be held for a different length of
 * time, and a test that advanced by a fixed interval would pass just as happily
 * against a ticker that ignored the authored durations.
 */
class CustomScreenTest {

    // ---------- static ----------

    @Test
    fun `a static design pushes its one frame and nothing else`() {
        val h = TestHarness(13)
        h.design.design = design(DesignKind.STATIC, frames = listOf(frame(13, lit = 40)))
        val screen = CustomScreen()

        screen.onActivate(h.context)

        assertEquals(1, h.frames.size)
        assertArrayEquals(decoded(13, lit = 40), h.frames[0])
        // No chain was armed: a still image must cost nothing while it is up.
        h.scheduler.advanceTime(60_000)
        assertEquals(1, h.frames.size)
    }

    @Test
    fun `a design declared static shows only its first frame`() {
        val h = TestHarness(13)
        // Extra frames are legal in the file — the editor keeps them when the
        // author switches a design back to static — and must not animate.
        h.design.design = design(
            DesignKind.STATIC,
            frames = listOf(frame(13, lit = 40), frame(13, lit = 41)),
        )
        val screen = CustomScreen()

        screen.onActivate(h.context)
        h.scheduler.advanceTime(10_000)

        assertEquals(1, h.frames.size)
        assertArrayEquals(decoded(13, lit = 40), h.frames[0])
    }

    // ---------- dynamic timing ----------

    @Test
    fun `each frame is held for exactly its own authored duration`() {
        val h = TestHarness(13)
        h.design.design = design(
            DesignKind.DYNAMIC,
            keyMode = KeyMode.PLAY_PAUSE,
            frames = listOf(
                frame(13, lit = 0, durationMs = 100),
                frame(13, lit = 1, durationMs = 250),
                frame(13, lit = 2, durationMs = 40),
            ),
        )
        val screen = CustomScreen()

        screen.onActivate(h.context)
        assertArrayEquals(decoded(13, lit = 0), h.frames.last())

        h.scheduler.advanceTime(99)
        assertEquals("frame 0 must survive its full 100 ms", 1, h.frames.size)
        h.scheduler.advanceTime(1)
        assertArrayEquals(decoded(13, lit = 1), h.frames.last())

        h.scheduler.advanceTime(249)
        assertEquals("frame 1 is authored at 250 ms, not at frame 0's 100", 2, h.frames.size)
        h.scheduler.advanceTime(1)
        assertArrayEquals(decoded(13, lit = 2), h.frames.last())

        // Last frame of a non-looping playPause design: held, not cleared.
        h.scheduler.advanceTime(10_000)
        assertEquals(3, h.frames.size)
        assertArrayEquals(decoded(13, lit = 2), h.frames.last())
    }

    // ---------- key modes ----------

    @Test
    fun `playOnce rests on frame 0, plays through on a press, and returns`() {
        val h = TestHarness(13)
        h.design.design = design(
            DesignKind.DYNAMIC,
            keyMode = KeyMode.PLAY_ONCE,
            frames = List(3) { frame(13, lit = it, durationMs = 100) },
        )
        val screen = CustomScreen()

        screen.onActivate(h.context)
        assertArrayEquals(decoded(13, lit = 0), h.frames.last())
        // Resting means resting: no chain until asked.
        h.scheduler.advanceTime(5_000)
        assertEquals(1, h.frames.size)

        screen.onEvent(Events.CHANGE)
        h.scheduler.advanceTime(100)
        assertArrayEquals(decoded(13, lit = 1), h.frames.last())
        h.scheduler.advanceTime(100)
        assertArrayEquals(decoded(13, lit = 2), h.frames.last())

        // Past the last frame it returns to 0 and stops there — and the return is
        // pushed, so the matrix shows the rest position rather than the end of
        // the animation.
        h.scheduler.advanceTime(100)
        assertArrayEquals(decoded(13, lit = 0), h.frames.last())
        val settled = h.frames.size
        h.scheduler.advanceTime(5_000)
        assertEquals(settled, h.frames.size)
    }

    @Test
    fun `playPause toggles on every press`() {
        val h = TestHarness(13)
        h.design.design = design(
            DesignKind.DYNAMIC,
            keyMode = KeyMode.PLAY_PAUSE,
            loop = true,
            frames = List(4) { frame(13, lit = it, durationMs = 100) },
        )
        val screen = CustomScreen()

        // A dynamic playPause design animates as soon as it is on screen.
        screen.onActivate(h.context)
        h.scheduler.advanceTime(100)
        assertArrayEquals(decoded(13, lit = 1), h.frames.last())

        screen.onEvent(Events.CHANGE) // pause
        val paused = h.frames.size
        h.scheduler.advanceTime(5_000)
        assertEquals("a paused design must not advance", paused, h.frames.size)
        assertArrayEquals(decoded(13, lit = 1), h.frames.last())

        screen.onEvent(Events.CHANGE) // resume, from where it stopped
        h.scheduler.advanceTime(100)
        assertArrayEquals(decoded(13, lit = 2), h.frames.last())
    }

    @Test
    fun `loop on restarts at frame 0 after the last frame`() {
        val h = TestHarness(13)
        h.design.design = design(
            DesignKind.DYNAMIC,
            keyMode = KeyMode.PLAY_PAUSE,
            loop = true,
            frames = List(3) { frame(13, lit = it, durationMs = 100) },
        )
        val screen = CustomScreen()

        screen.onActivate(h.context)
        // One advance per frame, never one big jump: FakeScheduler re-anchors a
        // one-shot armed from inside a callback to the clock as it stands at that
        // moment, so a single 300 ms jump would fire one link of the chain, not
        // three. The real Handler behaves the same way — it just runs on a clock
        // that does not move in 300 ms steps.
        h.scheduler.advanceTime(100) // -> frame 1
        h.scheduler.advanceTime(100) // -> frame 2
        h.scheduler.advanceTime(100) // past the end: loop, back to frame 0
        assertArrayEquals(decoded(13, lit = 0), h.frames.last())
        h.scheduler.advanceTime(100)
        assertArrayEquals(decoded(13, lit = 1), h.frames.last())
    }

    @Test
    fun `loop off holds the last frame and a press replays from the start`() {
        val h = TestHarness(13)
        h.design.design = design(
            DesignKind.DYNAMIC,
            keyMode = KeyMode.PLAY_PAUSE,
            loop = false,
            frames = List(3) { frame(13, lit = it, durationMs = 100) },
        )
        val screen = CustomScreen()

        screen.onActivate(h.context)
        h.scheduler.advanceTime(100) // -> frame 1
        h.scheduler.advanceTime(100) // -> frame 2, the last one
        assertArrayEquals(decoded(13, lit = 2), h.frames.last())
        // Held, indefinitely, and the chain is not re-armed.
        h.scheduler.advanceTime(10_000)
        assertArrayEquals(decoded(13, lit = 2), h.frames.last())
        val held = h.frames.size

        // Stopped at the end, so a toggle has nothing to resume — it starts over.
        screen.onEvent(Events.CHANGE)
        assertArrayEquals(decoded(13, lit = 0), h.frames.last())
        assertTrue(h.frames.size > held)
        h.scheduler.advanceTime(100)
        assertArrayEquals(decoded(13, lit = 1), h.frames.last())
    }

    /**
     * A shake is a press. Coin, Dice, Rps and Bottle all take the two gestures
     * as one input, and this screen used to drop the shake on the floor — so on
     * a phone lying face down, the toy that is *most* likely to be a hand-drawn
     * animation was the one that could not be started without pressing the key.
     */
    @Test
    fun `a shake pauses and resumes exactly as a press does`() {
        val h = TestHarness(13)
        h.design.design = design(
            DesignKind.DYNAMIC,
            keyMode = KeyMode.PLAY_PAUSE,
            loop = true,
            frames = List(4) { frame(13, lit = it, durationMs = 100) },
        )
        val screen = CustomScreen()

        screen.onActivate(h.context)
        h.scheduler.advanceTime(100)
        assertArrayEquals(decoded(13, lit = 1), h.frames.last())

        screen.onEvent(Events.SHAKE)
        val paused = h.frames.size
        h.scheduler.advanceTime(5_000)
        assertEquals("a shake must pause, like a press", paused, h.frames.size)

        screen.onEvent(Events.SHAKE)
        h.scheduler.advanceTime(100)
        assertArrayEquals(decoded(13, lit = 2), h.frames.last())
    }

    // ---------- the placeholder ----------

    @Test
    fun `no design selected renders the placeholder`() {
        val h = TestHarness(13)
        h.design.design = null
        val screen = CustomScreen()

        screen.onActivate(h.context)

        assertEquals(1, h.frames.size)
        assertArrayEquals(CustomScreen.renderPlaceholder(13), h.frames[0])
    }

    @Test
    fun `a design with no variant for this device renders the placeholder`() {
        val h = TestHarness(13)
        // Authored on a Phone (3) and never opened on a (4a) Pro: real art, none
        // of it for this panel.
        h.design.design = design(
            DesignKind.STATIC,
            codename = PokemonCodename.ARBOK,
            frames = listOf(frame(25, lit = 0)),
        )
        val screen = CustomScreen()

        screen.onActivate(h.context)

        assertArrayEquals(CustomScreen.renderPlaceholder(13), h.frames.last())
    }

    @Test
    fun `an empty variant renders the placeholder rather than a dark matrix`() {
        val h = TestHarness(13)
        // The blank second canvas every dual-size design starts with.
        h.design.design = design(DesignKind.STATIC, frames = emptyList())
        val screen = CustomScreen()

        screen.onActivate(h.context)

        assertArrayEquals(CustomScreen.renderPlaceholder(13), h.frames.last())
    }

    @Test
    fun `the placeholder is a dim border around a full-brightness question mark`() {
        GoldenAscii.check("custom_13_placeholder", CustomScreen.renderPlaceholder(13), 13)
        GoldenAscii.check("custom_25_placeholder", CustomScreen.renderPlaceholder(25), 25)
        for (size in intArrayOf(13, 25)) {
            // The audit rule: the brightest element is 4095, the border a ratio.
            assertEquals(4095, CustomScreen.renderPlaceholder(size).max())
        }
    }

    // ---------- deactivation ----------

    @Test
    fun `onDeactivate cancels the chain so no frame arrives afterwards`() {
        val h = TestHarness(13)
        h.design.design = design(
            DesignKind.DYNAMIC,
            keyMode = KeyMode.PLAY_PAUSE,
            loop = true,
            frames = List(3) { frame(13, lit = it, durationMs = 100) },
        )
        val screen = CustomScreen()

        screen.onActivate(h.context)
        h.scheduler.advanceTime(50) // mid-frame: a one-shot is armed and due soon
        val atDeactivation = h.frames.size

        screen.onDeactivate()
        h.scheduler.advanceTime(60_000)

        // The ScreenContext is a single instance ScreenManager hands to whichever
        // screen is active, so a surviving one-shot would not merely be wasted
        // work — it would paint this design over the next toy.
        assertEquals(
            "a pending frame after deactivate would land on the NEXT screen",
            atDeactivation,
            h.frames.size,
        )
    }

    @Test
    fun `presses after deactivation do nothing`() {
        val h = TestHarness(13)
        h.design.design = design(
            DesignKind.DYNAMIC,
            keyMode = KeyMode.PLAY_ONCE,
            frames = List(3) { frame(13, lit = it, durationMs = 100) },
        )
        val screen = CustomScreen()

        screen.onActivate(h.context)
        screen.onDeactivate()
        val atDeactivation = h.frames.size

        screen.onEvent(Events.CHANGE)
        h.scheduler.advanceTime(1_000)

        assertEquals(atDeactivation, h.frames.size)
    }

    @Test
    fun `reactivation re-reads the selection`() {
        val h = TestHarness(13)
        val screen = CustomScreen()

        h.design.design = null
        screen.onActivate(h.context)
        assertArrayEquals(CustomScreen.renderPlaceholder(13), h.frames.last())
        screen.onDeactivate()

        // The user picked a design in Settings while another toy was up.
        h.design.design = design(DesignKind.STATIC, frames = listOf(frame(13, lit = 77)))
        screen.onActivate(h.context)
        assertArrayEquals(decoded(13, lit = 77), h.frames.last())
    }

    private companion object {

        /** A frame with exactly one cell at palette index 2 (4095), the rest dark. */
        fun frame(size: Int, lit: Int, durationMs: Int = 120): DesignFrame {
            val cells = StringBuilder("0".repeat(size * size))
            cells.setCharAt(lit, '2')
            return DesignFrame(durationMs, cells.toString())
        }

        /** What [frame] decodes to, so assertions name the art rather than an index. */
        fun decoded(size: Int, lit: Int): IntArray =
            DesignFrames.decode(frame(size, lit).cells, DEFAULT_LEVELS, size)!!

        /**
         * A design carrying [frames] for one device. Built directly rather than
         * through [space.linuxct.glyphmatrixtoycompat.core.design.DesignCodec]:
         * the screen is being tested, not the validator, and the port hands it
         * whatever the store held.
         */
        fun design(
            kind: DesignKind,
            keyMode: KeyMode = KeyMode.PLAY_PAUSE,
            loop: Boolean = false,
            codename: PokemonCodename = PokemonCodename.BELLSPROUT,
            frames: List<DesignFrame>,
        ) = Design(
            id = "testdesign",
            name = "Test",
            kind = kind,
            keyMode = keyMode,
            loop = loop,
            levels = DEFAULT_LEVELS,
            variants = mapOf(codename.codename to DesignVariant(frames)),
        )
    }
}
