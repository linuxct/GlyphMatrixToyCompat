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
import space.linuxct.glyphmatrixtoycompat.FakeDesignPort
import space.linuxct.glyphmatrixtoycompat.FakeIncline
import space.linuxct.glyphmatrixtoycompat.FakeLight
import space.linuxct.glyphmatrixtoycompat.FakeLocation
import space.linuxct.glyphmatrixtoycompat.FakeScheduler
import space.linuxct.glyphmatrixtoycompat.FakeShake
import space.linuxct.glyphmatrixtoycompat.FakeSpectrum
import space.linuxct.glyphmatrixtoycompat.FakeSpeed
import space.linuxct.glyphmatrixtoycompat.FakeTimer
import space.linuxct.glyphmatrixtoycompat.FakeTilt
import space.linuxct.glyphmatrixtoycompat.screens.CustomScreen

private class ProbeScreen(
    override val id: String,
    private val pixel: Int,
) : GlyphScreen {
    override val interactive = true
    var activations = 0
    var deactivations = 0
    val events = mutableListOf<String>()
    private var ctx: ScreenContext? = null

    /**
     * The context this screen was last given, deliberately NOT cleared on
     * deactivate — the misbehaving (or merely late) screen.
     *
     * The ScreenContext is a shared singleton and a screen can hold it for as
     * long as it likes, so "deactivated" does not mean "unable to push": a
     * postDelayed one-shot already in flight will fire afterwards and paint,
     * which is exactly the hazard CustomScreen's KDoc calls out. That is the
     * frame the live-preview gate has to stop, so the tests need a way to make
     * one.
     */
    private var retained: ScreenContext? = null

    override fun onActivate(ctx: ScreenContext) {
        this.ctx = ctx
        this.retained = ctx
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

    fun push() = pushVia(ctx)

    /** Pushes through the context this screen kept after being deactivated. */
    fun pushAfterDeactivate() = pushVia(retained)

    /** The shared context, for handing to another probe screen directly. */
    fun contextForTest(): ScreenContext = checkNotNull(retained)

    private fun pushVia(c: ScreenContext?) {
        if (c == null) return
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
        FakeLocation(), FakeTimer(), FakeDesignPort(),
    )
    private val output = mutableListOf<IntArray>()

    private val a = ProbeScreen("ambient", 1000)
    private val b = ProbeScreen("clock", 2000)
    private val c = ProbeScreen("dice", 3000)

    /**
     * Stands in for `CustomScreen` under its real id, so the design-selection
     * tests below name the same screen `Core`'s listener names.
     */
    private val custom = ProbeScreen(CustomScreen.ID, 4000)

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
    fun `brightness scaling and frame dedup are applied to output`() {
        prefs.putFloat(PrefKeys.BRIGHTNESS, 0.5f)
        val m = manager(a, b, c)
        m.startSession()
        assertEquals(1, output.size)
        // Multiplicative, NOT normalized to the setting: dim art stays dim.
        assertEquals(500, output[0][0])
        a.push() // identical frame: deduped
        assertEquals(1, output.size)
    }

    @Test
    fun `reapplyBrightness re-pushes at the new level without a redraw`() {
        prefs.putFloat(PrefKeys.BRIGHTNESS, 1.0f)
        val m = manager(a, b, c)
        m.startSession()
        assertEquals(1000, output[0][0])

        // Auto-brightness changes the pref in the background; the screen has not
        // drawn again (a static toy might not for a minute).
        prefs.putFloat(PrefKeys.BRIGHTNESS, 0.5f)
        m.reapplyBrightness()
        assertEquals(2, output.size)
        assertEquals(500, output[1][0])

        // Re-applying the SAME level repeatedly must not drift: the raw frame,
        // not the already-scaled one, is the source (scaling rounds, so scaling a
        // scaled frame would dim the display a little each pass).
        repeat(20) { m.reapplyBrightness() }
        assertEquals(500, output.last()[0])

        // Back up to full: no residual loss from the trip through 0.5.
        prefs.putFloat(PrefKeys.BRIGHTNESS, 1.0f)
        m.reapplyBrightness()
        assertEquals(1000, output.last()[0])
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

    // ---------- live preview (the design editor) ----------

    @Test
    fun `live preview drops a screen's frame and passes only its own`() {
        val m = manager(a, b, c)
        m.startSession()
        val before = output.size

        m.beginLivePreview()
        assertTrue(m.livePreviewActive)
        assertEquals(1, a.deactivations) // the ticker is stopped...

        // ...and that alone is not the guarantee: this screen kept the shared
        // context and pushes anyway, which is what a pending one-shot does.
        a.pushAfterDeactivate()
        assertEquals("a deactivated screen must not reach the panel", before, output.size)

        // The one path past the gate.
        val drawing = IntArray(13 * 13).also { it[7] = 4095 }
        m.pushLivePreview(drawing)
        assertEquals(before + 1, output.size)
        assertEquals(4095, output.last()[7])

        // Still gated after a preview frame has landed.
        a.pushAfterDeactivate()
        assertEquals(before + 1, output.size)
        assertEquals(4095, output.last()[7])
    }

    @Test
    fun `ending the preview hands the matrix back to the current screen`() {
        val m = manager(a, b, c)
        m.startSession()
        m.beginLivePreview()
        m.pushLivePreview(IntArray(13 * 13).also { it[7] = 4095 })

        m.endLivePreview()
        assertFalse(m.livePreviewActive)
        assertEquals("the current screen is re-rendered, not just re-enabled", 2, a.activations)
        assertEquals(1000, output.last()[0])
        assertEquals(0, output.last()[7]) // the preview frame is gone

        // Ordinary frames flow again.
        val n = output.size
        b.onActivate(a.contextForTest())
        assertEquals(n + 1, output.size)
        assertEquals(2000, output.last()[0])
    }

    @Test
    fun `the preview goes through the same brightness scaling as every toy`() {
        prefs.putFloat(PrefKeys.BRIGHTNESS, 0.5f)
        val m = manager(a, b, c)
        m.startSession()
        m.beginLivePreview()

        // A mid-grey cell must arrive mid-grey-times-brightness, exactly as a
        // toy's would: the preview exists to show what the SETTING will give.
        m.pushLivePreview(IntArray(13 * 13).also { it[3] = 2048 })
        assertEquals(1024, output.last()[3])

        // Byte-identical repeats are dropped, as everywhere else.
        val n = output.size
        m.pushLivePreview(IntArray(13 * 13).also { it[3] = 2048 })
        assertEquals(n, output.size)

        // And a background brightness change re-levels the PREVIEW, not the toy
        // underneath it.
        prefs.putFloat(PrefKeys.BRIGHTNESS, 1.0f)
        m.reapplyBrightness()
        assertEquals(2048, output.last()[3])
    }

    @Test
    fun `selecting a toy during the preview persists it without activating it`() {
        // The editor's "show this design on the Glyph Matrix" runs while the
        // editor still owns the matrix. The choice must land; the screen must
        // NOT start running behind the gate, where every frame it produced would
        // be dropped and its ticker would burn battery for nothing.
        val m = manager(a, b, c)
        m.startSession()
        m.beginLivePreview()
        val activations = b.activations

        m.selectScreen("clock")

        assertEquals("clock", prefs.getString(PrefKeys.CURRENT_SCREEN, PrefKeys.CURRENT_SCREEN_DEF))
        assertEquals("nothing may activate behind the preview gate", activations, b.activations)

        // Leaving the editor is what puts it on screen — which is exactly the
        // path the editor takes on ON_PAUSE.
        m.endLivePreview()
        assertEquals(activations + 1, b.activations)
        assertEquals(2000, output.last()[0])
    }

    @Test
    fun `beginLivePreview closes the Essential-Key menu`() {
        val m = manager(a, b, c)
        m.startSession()
        m.enterMenu()
        m.beginLivePreview()
        assertFalse(m.inMenu)
        // The blink pushes straight to the output and would walk past the sink
        // gate, so it has to have been cancelled rather than merely suppressed.
        val n = output.size
        scheduler.advanceTime(5000)
        assertEquals(n, output.size)
    }

    // ---------- refreshing after a design was rewritten ----------

    /**
     * The editor's fix for a stale matrix: after a design file is rewritten, the
     * screen rendering FROM that file has to be told to read it again, and
     * `onActivate` is where every screen does its reading.
     */
    @Test
    fun `refreshing re-runs onActivate on the current screen`() {
        val m = manager(a, b, c)
        m.startSession()
        assertEquals(1, a.activations)

        m.refreshCurrentScreen()
        assertEquals("the screen must be re-activated, not merely left alone", 2, a.activations)
        assertEquals("and torn down first, so its ticker and one-shots go", 1, a.deactivations)
        // Only the current one: a refresh is not a broadcast.
        assertEquals(0, b.activations)
        assertEquals(0, c.activations)

        // It follows the selection rather than remembering who was active.
        m.selectScreen("dice")
        m.refreshCurrentScreen()
        assertEquals(2, c.activations)
        assertEquals(2, a.activations)
    }

    /**
     * While the editor owns the matrix the gate would drop these frames anyway,
     * so the refresh is skipped rather than spent — and nothing is lost, because
     * `endLivePreview` re-activates on its way out.
     */
    @Test
    fun `refreshing is skipped while the live preview owns the matrix`() {
        val m = manager(a, b, c)
        m.startSession()
        m.beginLivePreview()
        m.refreshCurrentScreen()
        assertEquals("a refresh must not re-arm a screen behind the gate", 1, a.activations)
        m.endLivePreview()
        assertEquals(2, a.activations)
        // ...and once the matrix is back, a refresh works again — the ON_STOP
        // flush arrives after ON_PAUSE and this is the call that saves it.
        m.refreshCurrentScreen()
        assertEquals(3, a.activations)
    }

    // ---------- choosing a different design while it is on the matrix ----------

    /**
     * The reported bug, as a test: with the design toy live, picking a different
     * design from its settings changed the pref and nothing else, and the matrix
     * went on playing the previous design until the user cycled away and back.
     * `CustomScreen` reads its design in `onActivate`, so the fix is to make it
     * activate again — see `ScreenManager.onSelectedDesignChanged`.
     */
    @Test
    fun `choosing a different design re-activates the design toy`() {
        val m = manager(custom, b, c)
        prefs.putString(PrefKeys.CURRENT_SCREEN, CustomScreen.ID)
        m.startSession()
        assertEquals(1, custom.activations)

        prefs.putString(PrefKeys.CUSTOM_DESIGN_ID, "design-b")
        m.onSelectedDesignChanged(CustomScreen.ID)
        assertEquals("the design toy must re-read its design", 2, custom.activations)
        assertEquals("and be torn down first, so its frame chain is cancelled", 1, custom.deactivations)
    }

    /**
     * The editor owns the matrix, so this must be as quiet as every other refresh
     * while the gate is closed — `endLivePreview` re-activates on its way out and
     * `CustomScreen` reads the design there.
     */
    @Test
    fun `choosing a different design is skipped while the live preview owns the matrix`() {
        val m = manager(custom, b, c)
        prefs.putString(PrefKeys.CURRENT_SCREEN, CustomScreen.ID)
        m.startSession()
        m.beginLivePreview()

        prefs.putString(PrefKeys.CUSTOM_DESIGN_ID, "design-b")
        m.onSelectedDesignChanged(CustomScreen.ID)
        assertEquals("a design change must not re-arm a screen behind the gate", 1, custom.activations)

        m.endLivePreview()
        assertEquals(2, custom.activations)
    }

    /**
     * Re-selecting the design that is already playing is not a change, and must
     * not jump a running animation back to frame 0.
     *
     * `AndroidPrefs` is a pass-through to `SharedPreferences`, which does not
     * notify for a value equal to the one already stored — but `FakePrefs`
     * notifies unconditionally, which is exactly why the guard is in the manager
     * and this test writes through the fake.
     */
    @Test
    fun `re-selecting the design already playing does not restart it`() {
        // Selected before the manager exists, as it is at process start.
        prefs.putString(PrefKeys.CUSTOM_DESIGN_ID, "design-a")
        val m = manager(custom, b, c)
        prefs.putString(PrefKeys.CURRENT_SCREEN, CustomScreen.ID)
        m.startSession()
        assertEquals(1, custom.activations)

        // The same id again: the listener fires, the manager declines.
        prefs.putString(PrefKeys.CUSTOM_DESIGN_ID, "design-a")
        m.onSelectedDesignChanged(CustomScreen.ID)
        assertEquals(1, custom.activations)

        // A genuinely different one still gets through.
        prefs.putString(PrefKeys.CUSTOM_DESIGN_ID, "design-b")
        m.onSelectedDesignChanged(CustomScreen.ID)
        assertEquals(2, custom.activations)
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

    private fun persistedScreen() =
        prefs.getString(PrefKeys.CURRENT_SCREEN, PrefKeys.CURRENT_SCREEN_DEF)
}
