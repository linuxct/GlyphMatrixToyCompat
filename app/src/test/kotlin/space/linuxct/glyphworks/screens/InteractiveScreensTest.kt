package space.linuxct.glyphworks.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import space.linuxct.glyphworks.GoldenAscii
import space.linuxct.glyphworks.TestHarness
import space.linuxct.glyphworks.core.Events
import space.linuxct.glyphworks.core.PrefKeys

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
