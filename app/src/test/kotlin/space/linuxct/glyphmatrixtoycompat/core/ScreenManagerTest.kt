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
import space.linuxct.glyphmatrixtoycompat.FakeLocation
import space.linuxct.glyphmatrixtoycompat.FakeScheduler
import space.linuxct.glyphmatrixtoycompat.FakeShake
import space.linuxct.glyphmatrixtoycompat.FakeSpectrum
import space.linuxct.glyphmatrixtoycompat.FakeSpeed
import space.linuxct.glyphmatrixtoycompat.FakeTea
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
        FakeAzimuth(), FakeShake(), FakeTilt(), FakeConnectivity(), FakeLocation(), FakeTea(),
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
}
