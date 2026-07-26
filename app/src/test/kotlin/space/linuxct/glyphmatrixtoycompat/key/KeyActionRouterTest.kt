package space.linuxct.glyphmatrixtoycompat.key

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import space.linuxct.glyphmatrixtoycompat.FakeAzimuth
import space.linuxct.glyphmatrixtoycompat.FakeBattery
import space.linuxct.glyphmatrixtoycompat.FakeClock
import space.linuxct.glyphmatrixtoycompat.FakeConnectivity
import space.linuxct.glyphmatrixtoycompat.FakeLocation
import space.linuxct.glyphmatrixtoycompat.FakePrefs
import space.linuxct.glyphmatrixtoycompat.FakeRandom
import space.linuxct.glyphmatrixtoycompat.FakeScheduler
import space.linuxct.glyphmatrixtoycompat.FakeShake
import space.linuxct.glyphmatrixtoycompat.FakeSpectrum
import space.linuxct.glyphmatrixtoycompat.FakeSpeed
import space.linuxct.glyphmatrixtoycompat.FakeTea
import space.linuxct.glyphmatrixtoycompat.FakeTilt
import space.linuxct.glyphmatrixtoycompat.core.Events
import space.linuxct.glyphmatrixtoycompat.core.GlyphScreen
import space.linuxct.glyphmatrixtoycompat.core.Ports
import space.linuxct.glyphmatrixtoycompat.core.PrefKeys
import space.linuxct.glyphmatrixtoycompat.core.ScreenContext
import space.linuxct.glyphmatrixtoycompat.core.ScreenManager
import space.linuxct.glyphmatrixtoycompat.core.SessionControl

private class RouterProbe(override val id: String) : GlyphScreen {
    override val interactive = true
    var activations = 0
    val events = mutableListOf<String>()
    private var ctx: ScreenContext? = null

    override fun onActivate(ctx: ScreenContext) {
        this.ctx = ctx
        activations++
        val f = IntArray(ctx.size * ctx.size)
        f[0] = 1000
        ctx.pushFrame(f)
    }

    override fun onDeactivate() { ctx = null }
    override fun onEvent(event: String) { events += event }
}

private class FakeSessionControl(var shouldRun: Boolean = true) : SessionControl {
    var reviveCount = 0
    override val sessionShouldRun get() = shouldRun
    override fun revive() { reviveCount++ }
}

class KeyActionRouterTest {

    private val clock = FakeClock()
    private val prefs = FakePrefs()
    private val scheduler = FakeScheduler(clock)
    private val ports = Ports(
        clock, FakeRandom(), FakeBattery(), FakeSpeed(), FakeSpectrum(),
        FakeAzimuth(), FakeShake(), FakeTilt(), FakeConnectivity(), FakeLocation(), FakeTea(),
    )
    private val output = mutableListOf<IntArray>()

    private val a = RouterProbe("ambient")
    private val b = RouterProbe("clock")
    private val c = RouterProbe("dice")

    private val screenManager = ScreenManager(
        listOf(a, b, c), prefs, ports, scheduler, 13,
    ) { output += it.copyOf() }

    private val arbiter = FakeSessionControl()

    private fun router(menuMode: Boolean, live: Boolean = true): KeyActionRouter {
        prefs.putString(PrefKeys.SCREEN_ORDER, "ambient,clock,dice")
        prefs.putBoolean(PrefKeys.MENU_MODE_ENABLED, menuMode)
        if (live) screenManager.startSession()
        return KeyActionRouter(arbiter, screenManager, scheduler, prefs)
    }

    private fun persisted() = prefs.getString(PrefKeys.CURRENT_SCREEN, PrefKeys.CURRENT_SCREEN_DEF)

    // ---------- classic mode (menu off) ----------

    @Test
    fun `classic single press dispatches glyph change to the active toy`() {
        val r = router(menuMode = false)
        r.execute(1)
        assertEquals(listOf(Events.CHANGE), a.events)
        assertFalse(screenManager.inMenu)
    }

    @Test
    fun `classic double press cycles to the next toy`() {
        val r = router(menuMode = false)
        r.execute(2)
        assertEquals("clock", persisted())
        assertFalse(screenManager.inMenu)
    }

    @Test
    fun `classic triple press jumps home`() {
        val r = router(menuMode = false)
        r.execute(2) // -> clock
        r.execute(3) // home
        assertEquals("ambient", persisted())
    }

    // ---------- menu mode, not yet in the menu ----------

    @Test
    fun `menu mode single press outside the menu still dispatches change`() {
        val r = router(menuMode = true)
        r.execute(1)
        assertEquals(listOf(Events.CHANGE), a.events)
        assertFalse(screenManager.inMenu)
    }

    @Test
    fun `menu mode double press opens the blinking selector instead of cycling`() {
        val r = router(menuMode = true)
        r.execute(2)
        assertTrue(screenManager.inMenu)
        assertEquals("ambient", persisted()) // opening the menu does not persist a cycle
    }

    // ---------- menu mode, inside the menu ----------

    @Test
    fun `menu mode single press inside the menu cycles the preview without persisting`() {
        val r = router(menuMode = true)
        r.execute(2) // open menu (preview ambient)
        r.execute(1) // cycle -> clock
        assertTrue(screenManager.inMenu)
        assertEquals(1, b.activations)
        assertEquals("ambient", persisted())
    }

    @Test
    fun `menu mode double press inside the menu commits the preview`() {
        val r = router(menuMode = true)
        r.execute(2) // open menu
        r.execute(1) // preview clock
        r.execute(2) // commit
        assertFalse(screenManager.inMenu)
        assertEquals("clock", persisted())
    }

    @Test
    fun `menu mode triple press inside the menu exits to ambient`() {
        val r = router(menuMode = true)
        r.execute(2) // open menu
        r.execute(1) // preview clock
        r.execute(3) // home
        assertFalse(screenManager.inMenu)
        assertEquals("ambient", persisted())
    }

    @Test
    fun `glyph button cycles the preview in the menu and dispatches change outside it`() {
        val r = router(menuMode = true)
        r.glyphButtonChange() // outside the menu -> glyph change
        assertEquals(listOf(Events.CHANGE), a.events)
        r.execute(2) // open menu
        r.glyphButtonChange() // inside the menu -> cycle preview
        assertTrue(screenManager.inMenu)
        assertEquals(1, b.activations)
    }

    // ---------- session gating ----------

    @Test
    fun `no session owner revives and swallows the action`() {
        val r = router(menuMode = false)
        arbiter.shouldRun = false
        a.events.clear()
        r.execute(1)
        assertEquals(1, arbiter.reviveCount)
        assertTrue(a.events.isEmpty())
    }

    @Test
    fun `should-run but not live revives and swallows the action`() {
        val r = router(menuMode = false, live = false)
        r.execute(1)
        assertEquals(1, arbiter.reviveCount)
        assertTrue(a.events.isEmpty())
        assertFalse(screenManager.sessionLive)
    }

    @Test
    fun `four or more clicks are ignored`() {
        val r = router(menuMode = true)
        r.execute(4)
        assertFalse(screenManager.inMenu)
        assertTrue(a.events.isEmpty())
    }
}
