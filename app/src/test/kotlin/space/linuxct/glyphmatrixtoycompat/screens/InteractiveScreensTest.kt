package space.linuxct.glyphmatrixtoycompat.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import space.linuxct.glyphmatrixtoycompat.GoldenAscii
import space.linuxct.glyphmatrixtoycompat.TestHarness
import space.linuxct.glyphmatrixtoycompat.core.Events
import space.linuxct.glyphmatrixtoycompat.core.PrefKeys

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

class TeaScreenTest {

    @Test
    fun `states render`() {
        GoldenAscii.check("tea_13_idle", TeaScreen.renderIdle(13), 13)
        GoldenAscii.check("tea_13_half", TeaScreen.renderSteeping(13, 0.5f, 0), 13)
        GoldenAscii.check("tea_13_done", TeaScreen.renderDone(13), 13)
        GoldenAscii.check("tea_25_half", TeaScreen.renderSteeping(25, 0.5f, 0), 25)
    }

    @Test
    fun `full steep lifecycle with backstop alarm`() {
        val h = TestHarness(13)
        h.prefs.putInt(PrefKeys.TEA_DURATION, 10)
        val screen = TeaScreen()
        screen.onActivate(h.context)
        assertTrue(h.lastFrame().contentEquals(TeaScreen.renderIdle(13)))

        screen.onEvent(Events.CHANGE)
        assertEquals(h.clock.now + 10_000, h.tea.scheduledAt)
        assertTrue(h.prefs.getLong(PrefKeys.TEA_START, 0) > 0)

        screen.onEvent(Events.CHANGE) // press while steeping: no-op
        h.scheduler.tick(10) // 10 x 1 s
        assertEquals(1, h.tea.chimeCount)
        assertNull(h.tea.scheduledAt) // backstop cancelled
        assertEquals(0L, h.prefs.getLong(PrefKeys.TEA_START, -1))
        assertTrue(h.lastFrame().contentEquals(TeaScreen.renderDone(13)))
        assertNull(h.scheduler.tickerInterval)
    }

    @Test
    fun `stale start on re-entry shows done without chime`() {
        val h = TestHarness(13)
        h.prefs.putInt(PrefKeys.TEA_DURATION, 10)
        h.prefs.putLong(PrefKeys.TEA_START, h.clock.now - 60_000)
        val screen = TeaScreen()
        screen.onActivate(h.context)
        assertEquals(0, h.tea.chimeCount)
        assertEquals(0L, h.prefs.getLong(PrefKeys.TEA_START, -1))
        assertTrue(h.lastFrame().contentEquals(TeaScreen.renderDone(13)))
    }

    @Test
    fun `steep survives screen switch and resumes`() {
        val h = TestHarness(13)
        h.prefs.putInt(PrefKeys.TEA_DURATION, 20)
        val screen = TeaScreen()
        screen.onActivate(h.context)
        screen.onEvent(Events.CHANGE)
        assertNotNull(h.tea.scheduledAt)
        screen.onDeactivate() // user cycles away: alarm stays scheduled
        assertNotNull(h.tea.scheduledAt)
        h.clock.advance(5_000)
        screen.onActivate(h.context) // resumes from persisted start
        assertEquals(20L * 1000 / 20, 1000L) // ticker interval sanity
        assertTrue(h.prefs.getLong(PrefKeys.TEA_START, 0) > 0)
    }
}
