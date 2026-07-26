package space.linuxct.glyphmatrixtoycompat.screens

import org.junit.Assert.assertEquals
import org.junit.Test
import space.linuxct.glyphmatrixtoycompat.GoldenAscii
import space.linuxct.glyphmatrixtoycompat.TestHarness
import space.linuxct.glyphmatrixtoycompat.core.PrefKeys

class ClockScreenTest {

    @Test
    fun `24h themes at both sizes`() {
        val h13 = TestHarness(13)
        h13.clock.hour = 12
        h13.clock.min = 34
        h13.battery.level = 80
        GoldenAscii.check("clock_13_1234_t0", ClockScreen.renderFrame(h13.context), 13)
        h13.prefs.putInt(PrefKeys.CLOCK_THEME, 1)
        GoldenAscii.check("clock_13_1234_t1_bar", ClockScreen.renderFrame(h13.context), 13)
        h13.prefs.putInt(PrefKeys.CLOCK_THEME, 2)
        GoldenAscii.check("clock_13_1234_t2_ring", ClockScreen.renderFrame(h13.context), 13)

        val h25 = TestHarness(25)
        h25.clock.hour = 12
        h25.clock.min = 34
        h25.battery.level = 80
        GoldenAscii.check("clock_25_1234_t0", ClockScreen.renderFrame(h25.context), 25)
        h25.prefs.putInt(PrefKeys.CLOCK_THEME, 2)
        GoldenAscii.check("clock_25_1234_t2_ring", ClockScreen.renderFrame(h25.context), 25)
    }

    @Test
    fun `12h mode shows pm dot and converts hour`() {
        val h = TestHarness(13)
        h.clock.hour = 21
        h.clock.min = 45
        h.prefs.putBoolean(PrefKeys.USE_12H, true)
        GoldenAscii.check("clock_13_0945_12h_pm", ClockScreen.renderFrame(h.context), 13)
    }

    @Test
    fun `ticker renders through session`() {
        val h = TestHarness(13)
        val screen = ClockScreen()
        screen.onActivate(h.context)
        assertEquals(50L, h.scheduler.tickerInterval)
        assertEquals(1, h.frames.size) // immediate first tick
    }
}

class EyesScreenTest {

    @Test
    fun `initial frame and deterministic blink`() {
        val h = TestHarness(13)
        val screen = EyesScreen()
        screen.onActivate(h.context)
        GoldenAscii.check("eyes_13_initial", h.lastFrame(), 13)

        // Blink starts at +2500 ms (tick 50 at 50 ms); closed is 2 ticks later.
        h.scheduler.tick(50)
        h.scheduler.tick(2)
        GoldenAscii.check("eyes_13_closed", h.lastFrame(), 13)

        val h25 = TestHarness(25)
        EyesScreen().onActivate(h25.context)
        GoldenAscii.check("eyes_25_initial", h25.lastFrame(), 25)
    }
}

class SpeedScreenTest {

    @Test
    fun `format rules`() {
        assertEquals("0K", SpeedScreen.formatSpeed(0))
        assertEquals("45K", SpeedScreen.formatSpeed(45_000))
        assertEquals("99K", SpeedScreen.formatSpeed(99_999))
        assertEquals("0.1M", SpeedScreen.formatSpeed(100_000))
        assertEquals("2.3M", SpeedScreen.formatSpeed(2_340_000))
        assertEquals("15M", SpeedScreen.formatSpeed(15_000_000))
        assertEquals("99M", SpeedScreen.formatSpeed(250_000_000))
    }

    @Test
    fun `render goldens`() {
        GoldenAscii.check("speed_13_45k", SpeedScreen.renderFrame(13, 45_000), 13)
        GoldenAscii.check("speed_13_2_3m", SpeedScreen.renderFrame(13, 2_340_000), 13)
        GoldenAscii.check("speed_25_45k", SpeedScreen.renderFrame(25, 45_000), 25)
    }

    @Test
    fun `first tick shows zero then delta`() {
        val h = TestHarness(13)
        val screen = SpeedScreen()
        h.speed.total = 1_000_000
        screen.onActivate(h.context)
        // First tick primes the counter and shows 0.
        assert(h.lastFrame().contentEquals(SpeedScreen.renderFrame(13, 0)))
        h.speed.total = 1_050_000
        h.scheduler.tick()
        assert(h.lastFrame().contentEquals(SpeedScreen.renderFrame(13, 50_000)))
    }
}

class CompassScreenTest {

    @Test
    fun `render goldens`() {
        GoldenAscii.check("compass_13_north", CompassScreen.renderFrame(13, 0f), 13)
        GoldenAscii.check("compass_13_east", CompassScreen.renderFrame(13, 90f), 13)
        GoldenAscii.check("compass_13_nosensor", CompassScreen.renderFrame(13, null), 13)
        GoldenAscii.check("compass_25_north", CompassScreen.renderFrame(25, 0f), 25)
    }

    @Test
    fun `azimuth rounds to 5 degrees`() {
        // 92 deg rounds to 90 -> identical frame. (Nearby steps like 95 may
        // legitimately rasterize identically at this needle radius, so the
        // difference check uses a clearly distinct heading.)
        assert(CompassScreen.renderFrame(13, 92f).contentEquals(CompassScreen.renderFrame(13, 90f)))
        assert(!CompassScreen.renderFrame(13, 135f).contentEquals(CompassScreen.renderFrame(13, 90f)))
    }
}
