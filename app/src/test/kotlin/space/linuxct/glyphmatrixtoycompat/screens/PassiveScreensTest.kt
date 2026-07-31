package space.linuxct.glyphmatrixtoycompat.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import space.linuxct.glyphmatrixtoycompat.GoldenAscii
import space.linuxct.glyphmatrixtoycompat.TestHarness
import space.linuxct.glyphmatrixtoycompat.core.PrefKeys
import space.linuxct.glyphmatrixtoycompat.matrix.Font3x5
import space.linuxct.glyphmatrixtoycompat.matrix.MAX_BRIGHTNESS
import space.linuxct.glyphmatrixtoycompat.matrix.MatrixCanvas

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
        // The half-closed step proves the rim squints down with the lid instead
        // of leaving a fixed box around a shrinking eye.
        h.scheduler.tick(50)
        GoldenAscii.check("eyes_13_squint", h.lastFrame(), 13)
        h.scheduler.tick(2)
        GoldenAscii.check("eyes_13_closed", h.lastFrame(), 13)

        val h25 = TestHarness(25)
        EyesScreen().onActivate(h25.context)
        GoldenAscii.check("eyes_25_initial", h25.lastFrame(), 25)
    }

    @Test
    fun `the lid clips the outline into a lens instead of erasing rows`() {
        // Phase 0 is the half-closed step: the rim survives only between the
        // two lids, which follow the eye's own width at their row.
        GoldenAscii.check("eyes_25_squint", EyesScreen.renderFrame(25, 0f, 0f, 0), 25)
        // Phase 2 is fully closed: one line, the eye's widest chord.
        GoldenAscii.check("eyes_25_closed", EyesScreen.renderFrame(25, 0f, 0f, 2), 25)
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

class LevelScreenTest {

    /** Brightness-weighted centroid of the ball: the frame minus ring/ticks. */
    private fun ballCentroid(frame: IntArray, size: Int): Pair<Float, Float> {
        // The ball is the only element drawn away from the centre ring, so take
        // the centroid of everything at full brightness.
        var wx = 0f
        var wy = 0f
        var w = 0f
        for (y in 0 until size) for (x in 0 until size) {
            val v = frame[y * size + x]
            if (v < 4095) continue
            wx += x * v; wy += y * v; w += v
        }
        return (wx / w) to (wy / w)
    }

    @Test
    fun `render goldens`() {
        GoldenAscii.check("level_13_flat", LevelScreen.renderFrame(13, 0f, 0f), 13)
        GoldenAscii.check("level_13_right_low", LevelScreen.renderFrame(13, 0f, 20f), 13)
        GoldenAscii.check("level_13_top_low", LevelScreen.renderFrame(13, 25f, -10f), 13)
        GoldenAscii.check("level_13_nosensor", LevelScreen.renderFrame(13, null, null), 13)
        GoldenAscii.check("level_25_flat", LevelScreen.renderFrame(25, 0f, 0f), 25)
        GoldenAscii.check("level_25_right_low", LevelScreen.renderFrame(25, 0f, 20f), 25)
        GoldenAscii.check("level_25_nosensor", LevelScreen.renderFrame(25, null, null), 25)
    }

    @Test
    fun `flat centres the ball and lights the target`() {
        for (size in intArrayOf(13, 25)) {
            val c = (size - 1) / 2f
            val (bx, by) = ballCentroid(LevelScreen.renderFrame(size, 0f, 0f), size)
            assertEquals(c, bx, 0.01f)
            assertEquals(c, by, 0.01f)
            // A cell on the target ring straight above centre (radius 3 on 13,
            // 5 on 25), well clear of the ball at either inclination: full
            // brightness only while the reading is inside the tolerance.
            val probe = (size / 2 - (if (size >= 25) 5 else 3)) * size + size / 2
            assertEquals(4095, LevelScreen.renderFrame(size, 0f, 0f)[probe])
            assertEquals(4095, LevelScreen.renderFrame(size, 2f, 2f)[probe])
            // Rolled, not pitched: keeps the ball off the vertical probe.
            assertTrue(LevelScreen.renderFrame(size, 0f, 20f)[probe] < 4095)
            assertTrue(LevelScreen.renderFrame(size, 0f, 20f)[probe] > 0)
        }
    }

    @Test
    fun `tolerance is a few degrees around flat`() {
        assertTrue(LevelScreen.isLevel(0f, 0f))
        assertTrue(LevelScreen.isLevel(2f, 2f)) // hypot 2.83 <= 4
        assertTrue(LevelScreen.isLevel(0f, 4f)) // exactly on the tolerance
        assertTrue(!LevelScreen.isLevel(0f, 5f))
        assertTrue(!LevelScreen.isLevel(-5f, 0f))
        assertTrue(!LevelScreen.isLevel(3f, 3f)) // hypot 4.24
        // Still a level, not a "roughly flat" indicator: a clearly tilted desk
        // must not read level, whatever the tolerance is tuned to.
        assertTrue(!LevelScreen.isLevel(0f, 10f))
    }

    @Test
    fun `the ball rolls toward the low edge and stays on-matrix`() {
        for (size in intArrayOf(13, 25)) {
            val c = (size - 1) / 2f
            // Positive roll = right edge low -> ball moves right (+x), y unmoved.
            val right = ballCentroid(LevelScreen.renderFrame(size, 0f, 15f), size)
            assertTrue("right $right on $size", right.first > c + 0.5f)
            assertEquals(c, right.second, 0.01f)
            val left = ballCentroid(LevelScreen.renderFrame(size, 0f, -15f), size)
            assertTrue("left $left on $size", left.first < c - 0.5f)
            // Positive pitch = top edge low -> ball moves UP the matrix (-y).
            val top = ballCentroid(LevelScreen.renderFrame(size, 15f, 0f), size)
            assertTrue("top $top on $size", top.second < c - 0.5f)
            val bottom = ballCentroid(LevelScreen.renderFrame(size, -15f, 0f), size)
            assertTrue("bottom $bottom on $size", bottom.second > c + 0.5f)

            // Extreme angles clamp: the ball is whole, on-matrix, and identical
            // to the frame at the clamp threshold.
            val pinned = LevelScreen.renderFrame(size, 90f, 90f)
            GoldenAscii.assertFrameValid(pinned, size)
            assertTrue(pinned.contentEquals(LevelScreen.renderFrame(size, LevelScreen.MAX_TILT_DEG, LevelScreen.MAX_TILT_DEG)))
            val corner = ballCentroid(pinned, size)
            assertTrue("corner $corner on $size", corner.first > c && corner.second < c)
        }
    }

    @Test
    fun `a missing sensor draws a question mark, not a ball`() {
        for (size in intArrayOf(13, 25)) {
            val none = LevelScreen.renderFrame(size, null, null)
            GoldenAscii.assertFrameValid(none, size)
            // The "?" is the only thing at full brightness: no ball anywhere, and
            // the target ring stays at its idle level. (The mark itself IS full
            // brightness — it is the whole content of this state, and brightness
            // now scales the frame rather than stretching it to its own peak.)
            val mark = MatrixCanvas(size)
            Font3x5.drawStringCentered(mark, "?", size / 2 - 2, MAX_BRIGHTNESS)
            for (i in none.indices) {
                assertEquals(
                    "cell $i on $size",
                    mark.buf[i] == MAX_BRIGHTNESS,
                    none[i] == MAX_BRIGHTNESS,
                )
            }
            assertTrue(none.any { it > 0 })
            // A half-delivered reading is treated the same way.
            assertTrue(none.contentEquals(LevelScreen.renderFrame(size, 0f, null)))
            assertTrue(none.contentEquals(LevelScreen.renderFrame(size, null, 0f)))
        }
    }

    @Test
    fun `the ticker polls faster than the sensor idle timeout`() {
        val h = TestHarness(13)
        val screen = LevelScreen()
        screen.onActivate(h.context)
        assertEquals(66L, h.scheduler.tickerInterval)
        assertTrue(h.scheduler.tickerInterval!! < 5000L) // InclineSensor unregisters at 5 s
        assertEquals(1, h.frames.size)
        assertTrue(h.lastFrame().contentEquals(LevelScreen.renderFrame(13, 0f, 0f)))

        h.incline.roll = 20f
        h.scheduler.tick()
        assertTrue(h.lastFrame().contentEquals(LevelScreen.renderFrame(13, 0f, 20f)))

        h.incline.pitch = null
        h.incline.roll = null
        h.scheduler.tick()
        assertTrue(h.lastFrame().contentEquals(LevelScreen.renderFrame(13, null, null)))
    }
}
