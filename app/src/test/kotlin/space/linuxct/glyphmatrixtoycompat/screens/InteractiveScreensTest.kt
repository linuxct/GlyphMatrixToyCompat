package space.linuxct.glyphmatrixtoycompat.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import space.linuxct.glyphmatrixtoycompat.GoldenAscii
import space.linuxct.glyphmatrixtoycompat.TestHarness
import space.linuxct.glyphmatrixtoycompat.core.Events
import space.linuxct.glyphmatrixtoycompat.core.PrefKeys
import space.linuxct.glyphmatrixtoycompat.matrix.MAX_BRIGHTNESS

class DiceScreenTest {

    @Test
    fun `all six faces render`() {
        for (face in 1..6) {
            GoldenAscii.check("dice_13_face$face", DiceScreen.renderFace(13, face, 6), 13)
        }
        GoldenAscii.check("dice_25_face5", DiceScreen.renderFace(25, 5, 6), 25)
        GoldenAscii.check("dice_13_d20_17", DiceScreen.renderFace(13, 17, 20), 13)
    }

    @Test
    fun `glyph touch rolls to a valid face`() {
        val h = TestHarness(13)
        val screen = DiceScreen()
        screen.onActivate(h.context)
        assertTrue(h.lastFrame().contentEquals(DiceScreen.renderFace(13, 6, 6)))

        screen.onEvent(Events.CHANGE)
        assertEquals(33L, h.scheduler.tickerInterval)
        val framesBefore = h.frames.size
        h.scheduler.tick(26) // 26 * 33 ms > 800 ms roll
        assertTrue(h.frames.size > framesBefore)
        assertNull(h.scheduler.tickerInterval) // roll finished, ticker cleared
        // Final frame must be one of the six face renders.
        val last = h.lastFrame()
        assertTrue((1..6).any { last.contentEquals(DiceScreen.renderFace(13, it, 6)) })
    }

    @Test
    fun `shake also rolls and mid-roll press restarts`() {
        val h = TestHarness(13)
        val screen = DiceScreen()
        screen.onActivate(h.context)
        screen.onEvent(Events.SHAKE)
        h.scheduler.tick(10)
        screen.onEvent(Events.CHANGE) // a press mid-roll restarts the roll
        h.scheduler.tick(26)
        assertNull(h.scheduler.tickerInterval)
    }
}

class CoinScreenTest {

    @Test
    fun `result renders`() {
        GoldenAscii.check("coin_13_heads", CoinScreen.renderResult(13, true), 13)
        GoldenAscii.check("coin_13_tails", CoinScreen.renderResult(13, false), 13)
        GoldenAscii.check("coin_25_heads", CoinScreen.renderResult(25, true), 25)
    }

    @Test
    fun `art design renders portrait and numeral at both sizes`() {
        val d = CoinScreen.DESIGN_ART
        GoldenAscii.check("coin_13_art_heads", CoinScreen.renderResult(13, true, d), 13)
        GoldenAscii.check("coin_13_art_tails", CoinScreen.renderResult(13, false, d), 13)
        GoldenAscii.check("coin_25_art_heads", CoinScreen.renderResult(25, true, d), 25)
        GoldenAscii.check("coin_25_art_tails", CoinScreen.renderResult(25, false, d), 25)
    }

    @Test
    fun `the default design is the letters and the designs differ`() {
        for (size in intArrayOf(13, 25)) for (heads in booleanArrayOf(true, false)) {
            val letters = CoinScreen.renderResult(size, heads, CoinScreen.DESIGN_LETTERS)
            assertTrue(letters.contentEquals(CoinScreen.renderResult(size, heads)))
            assertTrue(!letters.contentEquals(CoinScreen.renderResult(size, heads, CoinScreen.DESIGN_ART)))
        }
        // Unknown design ids fall back to the letters rather than a blank coin.
        assertTrue(
            CoinScreen.renderResult(13, true, 7)
                .contentEquals(CoinScreen.renderResult(13, true, CoinScreen.DESIGN_LETTERS)),
        )
    }

    @Test
    fun `the art keeps clear of the coin ring`() {
        // The art is authored to sit inside the ring: no cell of the ring
        // (2200) is overwritten to full brightness by a sprite, and heads/tails
        // both leave the ring itself intact.
        for (size in intArrayOf(13, 25)) {
            val ringOnly = CoinScreen.renderResult(size, true, CoinScreen.DESIGN_LETTERS)
            for (heads in booleanArrayOf(true, false)) {
                val art = CoinScreen.renderResult(size, heads, CoinScreen.DESIGN_ART)
                ringOnly.forEachIndexed { i, v ->
                    if (v == 2200) assertEquals("ring cell $i on $size", 2200, art[i])
                }
            }
        }
    }

    @Test
    fun `the art leaves a dark gap around the whole ring`() {
        // Stronger than "does not collide": every one of the ring's eight
        // neighbours that is not itself ring must be unlit, so the sprite never
        // even touches the border of the coin.
        for (size in intArrayOf(13, 25)) {
            val ringOnly = CoinScreen.renderResult(size, true, CoinScreen.DESIGN_LETTERS)
            val isRing = { x: Int, y: Int ->
                x in 0 until size && y in 0 until size && ringOnly[y * size + x] == 2200
            }
            for (heads in booleanArrayOf(true, false)) {
                val art = CoinScreen.renderResult(size, heads, CoinScreen.DESIGN_ART)
                for (y in 0 until size) for (x in 0 until size) {
                    if (!isRing(x, y)) continue
                    for (dy in -1..1) for (dx in -1..1) {
                        val nx = x + dx
                        val ny = y + dy
                        if (nx !in 0 until size || ny !in 0 until size) continue
                        if (isRing(nx, ny)) continue
                        assertEquals(
                            "sprite lights ($nx,$ny), touching ring cell ($x,$y) " +
                                "on $size ${if (heads) "heads" else "tails"}",
                            0,
                            art[ny * size + nx],
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `flip lands on a result`() {
        val h = TestHarness(13)
        val screen = CoinScreen()
        screen.onActivate(h.context)
        screen.onEvent(Events.CHANGE)
        h.scheduler.tick(32) // 32 * 33 ms > 1000 ms flip
        assertNull(h.scheduler.tickerInterval)
        val last = h.lastFrame()
        assertTrue(
            last.contentEquals(CoinScreen.renderResult(13, true)) ||
                last.contentEquals(CoinScreen.renderResult(13, false)),
        )
    }

    @Test
    fun `the design pref picks the landed frame`() {
        val h = TestHarness(13)
        h.prefs.putInt(PrefKeys.COIN_DESIGN, CoinScreen.DESIGN_ART)
        val screen = CoinScreen()
        screen.onActivate(h.context)
        val first = h.lastFrame()
        assertTrue(
            first.contentEquals(CoinScreen.renderResult(13, true, CoinScreen.DESIGN_ART)) ||
                first.contentEquals(CoinScreen.renderResult(13, false, CoinScreen.DESIGN_ART)),
        )
    }
}

class CounterScreenTest {

    @Test
    fun `value renders at fixed columns`() {
        GoldenAscii.check("counter_13_0", CounterScreen.renderFrame(13, 0), 13)
        GoldenAscii.check("counter_13_42", CounterScreen.renderFrame(13, 42), 13)
        GoldenAscii.check("counter_13_999", CounterScreen.renderFrame(13, 999), 13)
        GoldenAscii.check("counter_25_42", CounterScreen.renderFrame(25, 42), 25)
    }

    @Test
    fun `increments persist and wrap at 999`() {
        val h = TestHarness(13)
        val screen = CounterScreen()
        screen.onActivate(h.context)
        screen.onEvent(Events.CHANGE)
        assertEquals(1, h.prefs.getInt(PrefKeys.COUNTER, -1))
        h.prefs.putInt(PrefKeys.COUNTER, 999)
        screen.onEvent(Events.CHANGE)
        assertEquals(0, h.prefs.getInt(PrefKeys.COUNTER, -1))
    }

    @Test
    fun `shake resets with blink confirmation`() {
        val h = TestHarness(13)
        val screen = CounterScreen()
        h.prefs.putInt(PrefKeys.COUNTER, 42)
        screen.onActivate(h.context)
        screen.onEvent(Events.SHAKE)
        assertEquals(0, h.prefs.getInt(PrefKeys.COUNTER, -1))
        assertTrue(h.lastFrame().contentEquals(CounterScreen.renderFrame(13, 0)))
        h.scheduler.advanceTime(150) // blink: blank
        assertTrue(h.lastFrame().contentEquals(IntArray(13 * 13)))
        h.scheduler.advanceTime(150) // blink: back
        assertTrue(h.lastFrame().contentEquals(CounterScreen.renderFrame(13, 0)))
    }
}

class BreathingScreenTest {

    @Test
    fun `radius index ping-pongs with holds`() {
        val seq = (0..27).map { BreathingScreen.radiusIndexFor(it) }
        assertEquals(0, seq.first())
        assertEquals(11, seq[11])
        assertEquals(11, seq[13]) // hold at max
        assertEquals(0, seq[25])
        assertEquals(0, seq[27]) // hold at min
    }

    @Test
    fun `extremes render`() {
        GoldenAscii.check("breathing_13_min", BreathingScreen.renderStep(13, 0), 13)
        GoldenAscii.check("breathing_13_max", BreathingScreen.renderStep(13, 11), 13)
        GoldenAscii.check("breathing_25_max", BreathingScreen.renderStep(25, 11), 25)
    }

    @Test
    fun `glyph touch toggles the animation`() {
        val h = TestHarness(13)
        val screen = BreathingScreen()
        screen.onActivate(h.context)
        assertNull(h.scheduler.tickerInterval)
        screen.onEvent(Events.CHANGE)
        assertEquals(500L, h.scheduler.tickerInterval) // pace 4 * 125 ms
        screen.onEvent(Events.CHANGE)
        assertNull(h.scheduler.tickerInterval)
    }
}

class TimerScreenTest {

    /** Rows of settled sand in column [x]: cells lit at (near) full brightness. */
    private fun sandRows(frame: IntArray, size: Int, x: Int): Int =
        (0 until size).count { y -> frame[y * size + x] >= MAX_BRIGHTNESS }

    private fun totalLit(frame: IntArray): Long = frame.sumOf { it.toLong() }

    @Test
    fun `states render`() {
        GoldenAscii.check("timer_13_idle", TimerScreen.renderIdle(13), 13)
        GoldenAscii.check("timer_13_quarter", TimerScreen.renderRunning(13, 0.25f, 0), 13)
        GoldenAscii.check("timer_13_half", TimerScreen.renderRunning(13, 0.5f, 0), 13)
        GoldenAscii.check("timer_13_done", TimerScreen.renderDone(13), 13)
        GoldenAscii.check("timer_13_pulse_off", TimerScreen.renderDonePulse(13, 2), 13)
        GoldenAscii.check("timer_13_paused", TimerScreen.renderPaused(13, 0.5f, 0), 13)
        GoldenAscii.check("timer_25_half", TimerScreen.renderRunning(25, 0.5f, 0), 25)
        GoldenAscii.check("timer_25_paused", TimerScreen.renderPaused(25, 0.5f, 0), 25)
    }

    @Test
    fun `idle is an empty vessel`() {
        for (size in intArrayOf(13, 25)) {
            val frame = TimerScreen.renderIdle(size)
            // No sand at all: nothing is lit at full brightness, and the frame
            // carries a tiny fraction of the light a finished timer does.
            assertTrue(frame.none { it >= MAX_BRIGHTNESS })
            assertTrue(totalLit(frame) * 20 < totalLit(TimerScreen.renderDone(size)))
        }
    }

    @Test
    fun `expiry fills every cell`() {
        for (size in intArrayOf(13, 25)) {
            assertTrue(TimerScreen.renderDone(size).all { it == MAX_BRIGHTNESS })
            // The final running frame is the done frame: the display completes,
            // it never drains.
            for (subframe in 0..5) {
                assertTrue(
                    TimerScreen.renderRunning(size, 1f, subframe)
                        .contentEquals(TimerScreen.renderDone(size))
                )
            }
        }
    }

    @Test
    fun `the fill only ever rises`() {
        for (size in intArrayOf(13, 25)) {
            // Per column, the settled sand never loses a row as time passes...
            val previous = IntArray(size) { -1 }
            for (step in 0..100) {
                val f = step / 100f
                val frame = TimerScreen.renderRunning(size, f, 0)
                for (x in 0 until size) {
                    val rows = sandRows(frame, size, x)
                    assertTrue("column $x drained at fraction $f on $size", rows >= previous[x])
                    previous[x] = rows
                }
            }
            // ...and the overall light strictly grows through the run.
            var lit = -1L
            for (f in floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
                val next = totalLit(TimerScreen.renderRunning(size, f, 0))
                assertTrue("fill did not grow at fraction $f on $size", next > lit)
                lit = next
            }
        }
    }

    @Test
    fun `the surface row carries sub-row brightness`() {
        // Two fractions close enough that no column gains a whole row: the rise
        // has to show up as brightness on the surface row, or a 13-row matrix
        // would visibly jerk one row at a time.
        val a = TimerScreen.renderRunning(13, 0.50f, 0)
        val b = TimerScreen.renderRunning(13, 0.51f, 0)
        for (x in 0 until 13) assertEquals(sandRows(a, 13, x), sandRows(b, 13, x))
        assertTrue(!a.contentEquals(b))
        assertTrue(totalLit(b) > totalLit(a))
    }

    @Test
    fun `the surface is a mound peaking at the centre`() {
        for (size in intArrayOf(13, 25)) {
            val frame = TimerScreen.renderRunning(size, 0.5f, 0)
            val centre = sandRows(frame, size, (size - 1) / 2)
            assertTrue(centre > sandRows(frame, size, (size - 1) / 4))
            assertTrue(sandRows(frame, size, (size - 1) / 4) > sandRows(frame, size, 0))
            // Half way through, the top of the display is still free of sand
            // (only falling grains live up there, and they are dimmer).
            assertTrue((0 until size).none { x -> frame[x] >= MAX_BRIGHTNESS })
            assertTrue(centre < size)
        }
    }

    @Test
    fun `grains fall and are reproducible`() {
        val a = TimerScreen.renderRunning(13, 0.2f, 3)
        assertTrue(a.contentEquals(TimerScreen.renderRunning(13, 0.2f, 3)))
        // Something above the sand moves between subframes.
        assertTrue((0..8).any { s -> !TimerScreen.renderRunning(13, 0.2f, s).contentEquals(a) })
    }

    @Test
    fun `full lifecycle with backstop alarm`() {
        val h = TestHarness(13)
        h.prefs.putInt(PrefKeys.TIMER_DURATION, 10)
        val screen = TimerScreen()
        screen.onActivate(h.context)
        assertTrue(h.lastFrame().contentEquals(TimerScreen.renderIdle(13)))

        screen.onEvent(Events.CHANGE)
        assertEquals(TimerScreen.TICK_MS, h.scheduler.tickerInterval)
        assertEquals(h.clock.now + 10_000, h.timer.scheduledAt)
        assertTrue(h.prefs.getLong(PrefKeys.TIMER_START, 0) > 0)

        h.scheduler.tick(80) // 80 x 125 ms = 10 s
        assertEquals(1, h.timer.chimeCount)
        assertNull(h.timer.scheduledAt) // backstop cancelled
        assertEquals(0L, h.prefs.getLong(PrefKeys.TIMER_START, -1))
        // Completion shows the full matrix, then flashes for a bounded while
        // and settles back on it with no ticker left running.
        assertTrue(h.lastFrame().contentEquals(TimerScreen.renderDone(13)))
        assertEquals(TimerScreen.TICK_MS, h.scheduler.tickerInterval)
        h.scheduler.tick(TimerScreen.PULSE_FRAMES)
        assertNull(h.scheduler.tickerInterval)
        assertTrue(h.lastFrame().contentEquals(TimerScreen.renderDone(13)))
    }

    @Test
    fun `stale start on re-entry shows done without chime`() {
        val h = TestHarness(13)
        h.prefs.putInt(PrefKeys.TIMER_DURATION, 10)
        h.prefs.putLong(PrefKeys.TIMER_START, h.clock.now - 60_000)
        val screen = TimerScreen()
        screen.onActivate(h.context)
        assertEquals(0, h.timer.chimeCount)
        assertEquals(0L, h.prefs.getLong(PrefKeys.TIMER_START, -1))
        assertTrue(h.lastFrame().contentEquals(TimerScreen.renderDone(13)))
        assertNull(h.scheduler.tickerInterval) // no flash for an old deadline
    }

    @Test
    fun `countdown survives screen switch and resumes`() {
        val h = TestHarness(13)
        h.prefs.putInt(PrefKeys.TIMER_DURATION, 20)
        val screen = TimerScreen()
        screen.onActivate(h.context)
        screen.onEvent(Events.CHANGE)
        assertNotNull(h.timer.scheduledAt)
        screen.onDeactivate() // user cycles away: alarm stays scheduled
        assertNotNull(h.timer.scheduledAt)
        h.clock.advance(5_000)
        screen.onActivate(h.context) // resumes from persisted start
        assertEquals(TimerScreen.TICK_MS, h.scheduler.tickerInterval)
        assertTrue(h.prefs.getLong(PrefKeys.TIMER_START, 0) > 0)
        // A quarter of the way in, a quarter of the light is on.
        val lit = totalLit(h.lastFrame())
        val full = totalLit(TimerScreen.renderDone(13))
        assertTrue(lit > full / 8 && lit < full / 2)
    }

    // ---------- pause / resume ----------

    /** Starts a [durationSec] timer and runs it for [runMs] (must be a whole number of ticks). */
    private fun runningTimer(h: TestHarness, durationSec: Int, runMs: Long): TimerScreen {
        h.prefs.putInt(PrefKeys.TIMER_DURATION, durationSec)
        val screen = TimerScreen()
        screen.onActivate(h.context)
        screen.onEvent(Events.CHANGE)
        h.scheduler.tick((runMs / TimerScreen.TICK_MS).toInt())
        return screen
    }

    @Test
    fun `pause freezes the fill however long the clock runs`() {
        val h = TestHarness(13)
        val screen = runningTimer(h, 60, 30_000) // half way
        screen.onEvent(Events.CHANGE) // pause

        assertEquals(TimerScreen.BLINK_TICK_MS, h.scheduler.tickerInterval)
        assertEquals(30_000L, h.prefs.getLong(PrefKeys.TIMER_PAUSED_ELAPSED, -1))
        assertEquals(0L, h.prefs.getLong(PrefKeys.TIMER_START, -1)) // no deadline any more
        val frozen = TimerScreen.renderPaused(13, 0.5f, 0)
        assertTrue(h.lastFrame().contentEquals(frozen))

        // An hour of wall clock plus a few blink ticks changes nothing.
        h.clock.advance(3_600_000)
        h.scheduler.tick(5) // one whole blink cycle: back on the lit subframe
        assertTrue(h.lastFrame().contentEquals(frozen))
        assertEquals(30_000L, h.prefs.getLong(PrefKeys.TIMER_PAUSED_ELAPSED, -1))
        assertEquals(0, h.timer.chimeCount)
    }

    @Test
    fun `resume continues from the same fraction losing no time`() {
        val h = TestHarness(13)
        val screen = runningTimer(h, 60, 20_000)
        screen.onEvent(Events.CHANGE) // pause at a third
        h.clock.advance(600_000) // ten minutes on the shelf
        screen.onEvent(Events.CHANGE) // resume

        assertEquals(TimerScreen.TICK_MS, h.scheduler.tickerInterval)
        assertEquals(0L, h.prefs.getLong(PrefKeys.TIMER_PAUSED_ELAPSED, -1))
        // The start was rewound by exactly the banked elapsed time...
        assertEquals(h.clock.now - 20_000, h.prefs.getLong(PrefKeys.TIMER_START, -1))
        // ...so the first frame after resuming is the frame we paused on.
        assertTrue(h.lastFrame().contentEquals(TimerScreen.renderRunning(13, 20_000f / 60_000f, 160)))

        // The remaining 40 s still take 40 s — no more, no less.
        h.scheduler.tick(319)
        assertEquals(0, h.timer.chimeCount)
        h.scheduler.tick(1)
        assertEquals(1, h.timer.chimeCount)
    }

    @Test
    fun `many pause resume cycles still total the full duration`() {
        val h = TestHarness(13)
        val screen = runningTimer(h, 60, 10_000)
        repeat(3) {
            screen.onEvent(Events.CHANGE) // pause
            h.clock.advance(900_000) // fifteen idle minutes each time
            screen.onEvent(Events.CHANGE) // resume
            h.scheduler.tick(80) // 10 s of running
            assertEquals(0, h.timer.chimeCount)
        }
        // 10 s + 3 x 10 s run so far: 20 s left, and the 45 minutes of pauses
        // must not have eaten any of them.
        h.scheduler.tick(159)
        assertEquals(0, h.timer.chimeCount)
        h.scheduler.tick(1)
        assertEquals(1, h.timer.chimeCount)
    }

    @Test
    fun `pausing cancels the backstop and resuming re-arms it for the remaining time`() {
        val h = TestHarness(13)
        val screen = runningTimer(h, 600, 150_000) // 10 min timer, 2.5 min in
        val originalDeadline = h.timer.scheduledAt
        assertEquals(h.clock.now - 150_000 + 600_000, originalDeadline)

        screen.onEvent(Events.CHANGE) // pause
        assertEquals(1, h.timer.cancelCount)
        assertNull("a paused timer must not keep an armed alarm", h.timer.scheduledAt)

        h.clock.advance(3_600_000) // an hour paused, well past the original deadline
        assertEquals(0, h.timer.chimeCount)

        screen.onEvent(Events.CHANGE) // resume
        // 7.5 minutes were left, so that is what the backstop is armed for —
        // NOT the deadline the run originally had.
        assertEquals(h.clock.now + 450_000, h.timer.scheduledAt)
        assertNotEquals(originalDeadline, h.timer.scheduledAt)
    }

    @Test
    fun `a timer paused longer than its duration neither completes nor chimes`() {
        val h = TestHarness(13)
        val screen = runningTimer(h, 60, 10_000)
        screen.onEvent(Events.CHANGE) // pause a sixth of the way in

        h.clock.advance(10 * 60_000) // ten times the whole duration
        h.scheduler.tick(40) // and keep blinking through it
        assertEquals(0, h.timer.chimeCount)
        assertNull(h.timer.scheduledAt)
        assertEquals(TimerScreen.BLINK_TICK_MS, h.scheduler.tickerInterval) // still paused, not done
        assertTrue(!h.lastFrame().contentEquals(TimerScreen.renderDone(13)))
        assertTrue(totalLit(h.lastFrame()) < totalLit(TimerScreen.renderDone(13)) / 2)

        screen.onEvent(Events.CHANGE) // resume: the full 50 s are still there
        assertEquals(h.clock.now + 50_000, h.timer.scheduledAt)
        h.scheduler.tick(399)
        assertEquals(0, h.timer.chimeCount)
        h.scheduler.tick(1)
        assertEquals(1, h.timer.chimeCount)
    }

    @Test
    fun `a paused timer survives process death`() {
        val h = TestHarness(13)
        val screen = runningTimer(h, 60, 20_000)
        screen.onEvent(Events.CHANGE) // pause at a third
        screen.onDeactivate()

        // New screen instance, hours later: only the prefs carried over.
        h.clock.advance(2 * 3_600_000)
        val revived = TimerScreen()
        revived.onActivate(h.context)

        assertEquals(TimerScreen.BLINK_TICK_MS, h.scheduler.tickerInterval)
        assertTrue(h.lastFrame().contentEquals(TimerScreen.renderPaused(13, 20_000f / 60_000f, 0)))
        // The "deadline passed while we were away" branch must not have fired.
        assertEquals(0, h.timer.chimeCount)
        assertTrue(!h.lastFrame().contentEquals(TimerScreen.renderDone(13)))
        assertEquals(20_000L, h.prefs.getLong(PrefKeys.TIMER_PAUSED_ELAPSED, -1))

        revived.onEvent(Events.CHANGE) // and it still resumes with 40 s left
        assertEquals(h.clock.now + 40_000, h.timer.scheduledAt)
    }

    @Test
    fun `the paused frame blinks`() {
        // Same 450 ms on / 300 ms off cadence as the menu selector, expressed
        // as three lit subframes and two dark ones of BLINK_TICK_MS.
        val lit = TimerScreen.renderPaused(13, 0.5f, 0)
        for (phase in intArrayOf(0, 1, 2, 5, 6, 7)) {
            assertTrue("phase $phase should be lit", TimerScreen.renderPaused(13, 0.5f, phase).contentEquals(lit))
        }
        for (phase in intArrayOf(3, 4, 8, 9)) {
            assertTrue("phase $phase should be dark", TimerScreen.renderPaused(13, 0.5f, phase).all { it == 0 })
        }
        assertTrue(totalLit(lit) > 0)

        // And the screen actually pushes both halves of the blink.
        val h = TestHarness(13)
        val screen = runningTimer(h, 60, 30_000)
        val before = h.frames.size
        screen.onEvent(Events.CHANGE) // pause
        h.scheduler.tick(6)
        val pausedFrames = h.frames.drop(before)
        assertTrue(pausedFrames.any { it.contentEquals(lit) })
        assertTrue(pausedFrames.any { f -> f.all { it == 0 } })
    }

    @Test
    fun `the paused fill still shows how far along the timer is`() {
        // Not just "something is on": the frozen level tracks the elapsed
        // fraction, so an almost-finished timer looks almost finished.
        val early = TimerScreen.renderPaused(13, 0.2f, 0)
        val late = TimerScreen.renderPaused(13, 0.8f, 0)
        assertTrue(totalLit(late) > totalLit(early))
        assertTrue(totalLit(early) > 0)
    }

    @Test
    fun `a press on the finished timer goes back to idle`() {
        val h = TestHarness(13)
        val screen = runningTimer(h, 10, 10_000) // straight to completion
        assertEquals(1, h.timer.chimeCount)

        screen.onEvent(Events.CHANGE) // dismiss
        assertNull(h.scheduler.tickerInterval)
        assertTrue(h.lastFrame().contentEquals(TimerScreen.renderIdle(13)))

        screen.onEvent(Events.CHANGE) // and from idle it starts a fresh run
        assertEquals(TimerScreen.TICK_MS, h.scheduler.tickerInterval)
        assertEquals(h.clock.now + 10_000, h.timer.scheduledAt)
    }
}
