package space.linuxct.glyphmatrixtoycompat.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import space.linuxct.glyphmatrixtoycompat.FakeClock
import space.linuxct.glyphmatrixtoycompat.FakePrefs
import space.linuxct.glyphmatrixtoycompat.FakeRandom
import space.linuxct.glyphmatrixtoycompat.FakeAzimuth
import space.linuxct.glyphmatrixtoycompat.FakeBattery
import space.linuxct.glyphmatrixtoycompat.FakeConnectivity
import space.linuxct.glyphmatrixtoycompat.FakeIncline
import space.linuxct.glyphmatrixtoycompat.FakeLight
import space.linuxct.glyphmatrixtoycompat.FakeLocation
import space.linuxct.glyphmatrixtoycompat.FakeScheduler
import space.linuxct.glyphmatrixtoycompat.FakeShake
import space.linuxct.glyphmatrixtoycompat.FakeSpectrum
import space.linuxct.glyphmatrixtoycompat.FakeSpeed
import space.linuxct.glyphmatrixtoycompat.FakeTimer
import space.linuxct.glyphmatrixtoycompat.FakeTilt

private class ProbeScreen(
    override val id: String,
    private val pixel: Int,
) : GlyphScreen {
    override val interactive = true
    var activations = 0
    var deactivations = 0
    val events = mutableListOf<String>()
    private var ctx: ScreenContext? = null

    override fun onActivate(ctx: ScreenContext) {
        this.ctx = ctx
        activations++
        push()
    }

    override fun onDeactivate() {
        deactivations++
        ctx = null
    }

    override fun onEvent(event: String) {
        events += event
    }

    fun push() {
        val c = ctx ?: return
        val frame = IntArray(c.size * c.size)
        frame[0] = pixel
        c.pushFrame(frame)
    }
}

class ScreenManagerTest {

    private val clock = FakeClock()
    private val prefs = FakePrefs()
    private val scheduler = FakeScheduler(clock)
    private val ports = Ports(
        clock, FakeRandom(), FakeBattery(), FakeSpeed(), FakeSpectrum(),
        FakeAzimuth(), FakeShake(), FakeTilt(), FakeIncline(), FakeLight(), FakeConnectivity(),
        FakeLocation(), FakeTimer(),
    )
    private val output = mutableListOf<IntArray>()

    private val a = ProbeScreen("ambient", 1000)
    private val b = ProbeScreen("clock", 2000)
    private val c = ProbeScreen("dice", 3000)

    private fun manager(vararg screens: GlyphScreen) = ScreenManager(
        screens.toList(), prefs, ports, scheduler, 13,
    ) { output += it.copyOf() }.also {
        prefs.putString(PrefKeys.SCREEN_ORDER, "ambient,clock,dice")
    }

    @Test
    fun `session starts on persisted screen and cycles with wraparound`() {
        val m = manager(a, b, c)
        m.startSession()
        assertEquals(1, a.activations)

        m.next()
        assertEquals(1, a.deactivations)
        assertEquals(1, b.activations)
        assertEquals("clock", prefs.getString(PrefKeys.CURRENT_SCREEN, ""))

        m.next()
        m.next() // wraps back to ambient
        assertEquals(2, a.activations)
        assertEquals("ambient", prefs.getString(PrefKeys.CURRENT_SCREEN, ""))
    }

    @Test
    fun `home jumps to first enabled screen`() {
        val m = manager(a, b, c)
        m.startSession()
        m.next()
        m.next()
        m.home()
        assertEquals("ambient", prefs.getString(PrefKeys.CURRENT_SCREEN, ""))
        assertEquals(2, a.activations)
        m.home() // already home: no re-activation
        assertEquals(2, a.activations)
    }

    @Test
    fun `disabled screens are skipped`() {
        prefs.putBoolean(PrefKeys.screenEnabled("clock"), false)
        val m = manager(a, b, c)
        m.startSession()
        m.next()
        assertEquals(0, b.activations)
        assertEquals(1, c.activations)
    }

    @Test
    fun `events reach only the active screen of a live session`() {
        val m = manager(a, b, c)
        m.dispatchGlyphEvent(Events.CHANGE) // no session yet
        assertTrue(a.events.isEmpty())
        m.startSession()
        m.dispatchGlyphEvent(Events.CHANGE)
        assertEquals(listOf(Events.CHANGE), a.events)
        assertTrue(b.events.isEmpty())
    }

    @Test
    fun `stopSession deactivates and blocks cycling`() {
        val m = manager(a, b, c)
        m.startSession()
        m.stopSession()
        assertEquals(1, a.deactivations)
        assertFalse(m.sessionLive)
        m.next() // dead session: cycling is a no-op
        assertEquals(0, b.activations)
        assertEquals(0, c.activations)
    }

    @Test
    fun `brightness ceiling and frame dedup are applied to output`() {
        prefs.putFloat(PrefKeys.BRIGHTNESS, 0.5f)
        val m = manager(a, b, c)
        m.startSession()
        assertEquals(1, output.size)
        // ProbeScreen pushes 1000 as max -> normalized to 0.5 * 4095 = 2048.
        assertEquals(2048, output[0][0])
        a.push() // identical frame: deduped
        assertEquals(1, output.size)
    }

    @Test
    fun `reapplyBrightness re-pushes at the new ceiling without a redraw`() {
        prefs.putFloat(PrefKeys.BRIGHTNESS, 1.0f)
        val m = manager(a, b, c)
        m.startSession()
        assertEquals(4095, output[0][0])

        // Auto-brightness changes the pref in the background; the screen has not
        // drawn again (a static toy might not for a minute).
        prefs.putFloat(PrefKeys.BRIGHTNESS, 0.5f)
        m.reapplyBrightness()
        assertEquals(2, output.size)
        assertEquals(2048, output[1][0])

        // Re-applying the SAME level repeatedly must not drift: the raw frame,
        // not the already-normalized one, is the source (integer rounding in
        // max-normalization would otherwise dim the display a little each pass).
        repeat(20) { m.reapplyBrightness() }
        assertEquals(2048, output.last()[0])

        // Back up to full: no residual loss from the trip through 0.5.
        prefs.putFloat(PrefKeys.BRIGHTNESS, 1.0f)
        m.reapplyBrightness()
        assertEquals(4095, output.last()[0])
    }

    @Test
    fun `reapplyBrightness is a no-op without a live session`() {
        val m = manager(a, b, c)
        m.reapplyBrightness()
        assertTrue(output.isEmpty())
        m.startSession()
        m.stopSession()
        val n = output.size
        m.reapplyBrightness()
        assertEquals(n, output.size)
    }

    @Test
    fun `transient preview does not persist current screen`() {
        val m = manager(a, b, c)
        m.startSession()
        m.showTransient("dice")
        assertEquals(1, c.activations)
        assertEquals("ambient", prefs.getString(PrefKeys.CURRENT_SCREEN, PrefKeys.CURRENT_SCREEN_DEF))
        m.clearTransient()
        assertEquals(2, a.activations)
    }

    @Test
    fun `selectScreen persists and switches immediately`() {
        val m = manager(a, b, c)
        m.startSession() // activates ambient (a) once
        m.selectScreen("dice")
        assertEquals(1, c.activations)
        assertEquals("dice", prefs.getString(PrefKeys.CURRENT_SCREEN, PrefKeys.CURRENT_SCREEN_DEF))
        // The selection sticks: a fresh session restarts on dice, not ambient.
        m.stopSession()
        m.startSession()
        assertEquals(2, c.activations)
        assertEquals(1, a.activations) // only the initial start, never re-activated
        assertEquals(0, b.activations)
    }

    @Test
    fun `selectScreen before a session only persists`() {
        val m = manager(a, b, c)
        m.selectScreen("clock")
        assertEquals(0, b.activations)
        assertEquals("clock", prefs.getString(PrefKeys.CURRENT_SCREEN, PrefKeys.CURRENT_SCREEN_DEF))
        m.startSession()
        assertEquals(1, b.activations)
    }

    // ---------- menu mode ----------

    @Test
    fun `enterMenu blinks the previewed toy between content and blank`() {
        val m = manager(a, b, c)
        m.startSession()
        assertEquals(1, output.size) // steady content frame
        m.enterMenu()
        assertTrue(m.inMenu)

        // After BLINK_ON_MS the toy is blinked off: an all-zero frame.
        scheduler.advanceTime(450)
        assertTrue("blink-off frame should be blank", output.last().all { it == 0 })

        // After BLINK_OFF_MS the content returns.
        scheduler.advanceTime(300)
        assertTrue("blink-on frame should have content", output.last().any { it != 0 })
    }

    @Test
    fun `menuNext previews the next toy without persisting current screen`() {
        val m = manager(a, b, c)
        m.startSession()
        m.enterMenu() // previews ambient
        m.menuNext() // -> clock
        assertEquals(1, b.activations)
        assertEquals("ambient", persistedScreen())
        m.menuNext() // -> dice
        assertEquals(1, c.activations)
        assertEquals("ambient", persistedScreen())
        m.menuNext() // wraps back to ambient
        assertEquals(2, a.activations)
        assertEquals("ambient", persistedScreen())
    }

    @Test
    fun `menu auto-commits the preview after the timeout and stops blinking`() {
        val m = manager(a, b, c)
        m.startSession()
        m.enterMenu()
        m.menuNext() // preview clock
        assertEquals("ambient", persistedScreen())

        scheduler.advanceTime(5000) // no press within the window
        assertFalse(m.inMenu)
        assertEquals("clock", persistedScreen())

        // Blinking has stopped: no further frames are produced by advancing time.
        val n = output.size
        scheduler.advanceTime(5000)
        assertEquals(n, output.size)
    }

    @Test
    fun `a press before the timeout re-arms auto-commit`() {
        val m = manager(a, b, c)
        m.startSession()
        m.enterMenu() // preview ambient; commit timer armed at t+5000
        scheduler.advanceTime(4000)
        m.menuNext() // preview clock; timer re-armed to t+9000
        assertTrue(m.inMenu)
        scheduler.advanceTime(4000) // t=8000: original 5s would have fired; re-armed one has not
        assertTrue(m.inMenu)
        assertEquals("ambient", persistedScreen())
        scheduler.advanceTime(1000) // t=9000: re-armed timer fires -> commits clock
        assertFalse(m.inMenu)
        assertEquals("clock", persistedScreen())
    }

    @Test
    fun `commitMenu sets the previewed toy immediately and shows it steady`() {
        val m = manager(a, b, c)
        m.startSession()
        m.enterMenu()
        m.menuNext() // preview clock
        m.commitMenu()
        assertFalse(m.inMenu)
        assertEquals("clock", persistedScreen())
        assertTrue("committed toy shows steady content", output.last().any { it != 0 })

        val n = output.size
        scheduler.advanceTime(5000)
        assertEquals(n, output.size) // no lingering blink
    }

    @Test
    fun `home from within the menu exits and jumps to ambient`() {
        val m = manager(a, b, c)
        m.startSession()
        m.next() // on clock
        m.enterMenu()
        m.menuNext() // preview dice
        m.home()
        assertFalse(m.inMenu)
        assertEquals("ambient", persistedScreen())

        val n = output.size
        scheduler.advanceTime(5000)
        assertEquals(n, output.size) // menu blink cancelled on exit
    }

    @Test
    fun `stopSession cancels the menu`() {
        val m = manager(a, b, c)
        m.startSession()
        m.enterMenu()
        assertTrue(m.inMenu)
        m.stopSession()
        assertFalse(m.inMenu)
        val n = output.size
        scheduler.advanceTime(5000) // no blink, no auto-commit after stop
        assertEquals(n, output.size)
    }

    @Test
    fun `menu methods are no-ops when the menu is not open`() {
        val m = manager(a, b, c)
        m.startSession()
        m.menuNext() // ignored: not in menu
        m.commitMenu() // ignored: not in menu
        assertFalse(m.inMenu)
        assertEquals(1, a.activations) // nothing re-activated
        assertEquals("ambient", persistedScreen())
    }

    private fun persistedScreen() =
        prefs.getString(PrefKeys.CURRENT_SCREEN, PrefKeys.CURRENT_SCREEN_DEF)
}
