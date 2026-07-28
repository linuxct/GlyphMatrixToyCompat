package space.linuxct.glyphmatrixtoycompat.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import space.linuxct.glyphmatrixtoycompat.GoldenAscii
import space.linuxct.glyphmatrixtoycompat.TestHarness
import space.linuxct.glyphmatrixtoycompat.core.PrefKeys
import space.linuxct.glyphmatrixtoycompat.screens.ambient.AmbientScreen
import kotlin.math.abs

class SolarMathTest {

    @Test
    fun `equator equinox is roughly 6 to 18`() {
        val t = SolarMath.sunTimes(dayOfYear = 80, latDeg = 0.0, lonDeg = 0.0, utcOffsetMin = 0)
        assertEquals(SolarMath.Kind.NORMAL, t.kind)
        assertTrue("rise ${t.riseMin}", abs(t.riseMin - 360) <= 15)
        assertTrue("set ${t.setMin}", abs(t.setMin - 1080) <= 15)
    }

    @Test
    fun `polar summer and winter`() {
        assertEquals(
            SolarMath.Kind.POLAR_DAY,
            SolarMath.sunTimes(172, 80.0, 0.0, 0).kind,
        )
        assertEquals(
            SolarMath.Kind.POLAR_NIGHT,
            SolarMath.sunTimes(355, 80.0, 0.0, 0).kind,
        )
    }

    @Test
    fun `mid latitude summer day is long`() {
        val june = SolarMath.sunTimes(172, 48.0, 11.0, 120) // ~Munich, CEST
        assertEquals(SolarMath.Kind.NORMAL, june.kind)
        val dayLen = june.setMin - june.riseMin
        assertTrue("day length $dayLen", dayLen in 900..1020) // ~16 h
    }
}

class MoonMathTest {

    private val newMoonEpoch = 947_182_440_000L
    private val synodicMs = 2_551_442_877L

    @Test
    fun `anchor new moon is phase zero`() {
        assertTrue(MoonMath.phaseFraction(newMoonEpoch) < 0.001)
    }

    @Test
    fun `half a synodic month later is full`() {
        val full = MoonMath.phaseFraction(newMoonEpoch + synodicMs / 2)
        assertTrue("phase $full", abs(full - 0.5) < 0.02)
    }

    @Test
    fun `pre-epoch timestamps normalize`() {
        val f = MoonMath.phaseFraction(newMoonEpoch - synodicMs / 4)
        assertTrue("phase $f", abs(f - 0.75) < 0.02)
    }
}

class BatteryScreenTest {

    @Test
    fun `gauge goldens`() {
        GoldenAscii.check("battery_13_60", BatteryScreen.renderFrame(13, 60, false, 1_000_000), 13)
        GoldenAscii.check("battery_13_60_charging", BatteryScreen.renderFrame(13, 60, true, 1_000_000), 13)
        GoldenAscii.check("battery_25_60_charging", BatteryScreen.renderFrame(25, 60, true, 1_000_000), 25)
    }

    @Test
    fun `fill scales with level`() {
        val empty = BatteryScreen.renderFrame(13, 0, false, 0)
        assertTrue(empty.all { it == 0 })
        val full = BatteryScreen.renderFrame(13, 100, false, 0)
        assertTrue(full.none { it == 0 })
    }

    @Test
    fun `wattage formatting rounds and clamps`() {
        assertEquals("5W", BatteryScreen.formatWatts(4.6f))
        assertEquals("45W", BatteryScreen.formatWatts(45.2f))
        assertEquals("120W", BatteryScreen.formatWatts(119.7f))
        assertEquals("1W", BatteryScreen.formatWatts(0.2f)) // never a bare "0W"
        assertEquals("999W", BatteryScreen.formatWatts(4000f))
    }

    @Test
    fun `wattage goldens`() {
        GoldenAscii.check("battery_13_watts_7", BatteryScreen.renderFrame(13, 60, true, 1_000_000, 7.4f), 13)
        GoldenAscii.check("battery_13_watts_45", BatteryScreen.renderFrame(13, 60, true, 1_000_000, 45f), 13)
        // Three digits do not fit beside the unit on 13 columns: stacked.
        GoldenAscii.check("battery_13_watts_120", BatteryScreen.renderFrame(13, 60, true, 1_000_000, 120f), 13)
        GoldenAscii.check("battery_25_watts_45", BatteryScreen.renderFrame(25, 60, true, 1_000_000, 45f), 25)
        GoldenAscii.check("battery_25_watts_120", BatteryScreen.renderFrame(25, 60, true, 1_000_000, 120f), 25)
    }

    @Test
    fun `wattage needs both charging and a reading`() {
        for (size in intArrayOf(13, 25)) {
            val gauge = BatteryScreen.renderFrame(size, 60, true, 1_000_000)
            // Default argument, an absent reading, and not-charging all keep the
            // gauge byte-identical.
            assertTrue(gauge.contentEquals(BatteryScreen.renderFrame(size, 60, true, 1_000_000, null)))
            val idle = BatteryScreen.renderFrame(size, 60, false, 1_000_000)
            assertTrue(idle.contentEquals(BatteryScreen.renderFrame(size, 60, false, 1_000_000, 45f)))
            assertTrue(!gauge.contentEquals(BatteryScreen.renderFrame(size, 60, true, 1_000_000, 45f)))
        }
    }

    @Test
    fun `the toy only shows wattage when the pref is on`() {
        val h = TestHarness(13)
        h.battery.level = 60
        h.battery.charging = true
        h.battery.watts = 45f
        val screen = BatteryScreen()
        screen.onActivate(h.context)
        assertTrue(h.lastFrame().contentEquals(BatteryScreen.renderFrame(13, 60, true, h.clock.now)))

        h.prefs.putBoolean(PrefKeys.BATTERY_SHOW_WATTS, true)
        h.scheduler.tick()
        assertTrue(h.lastFrame().contentEquals(BatteryScreen.renderWattage(13, 45f)))

        // Unplugged: back to the gauge even with the pref on.
        h.battery.charging = false
        h.scheduler.tick()
        assertTrue(h.lastFrame().contentEquals(BatteryScreen.renderFrame(13, 60, false, h.clock.now)))
    }
}

class SolarScreenTest {

    @Test
    fun `arc positions render`() {
        GoldenAscii.check("solar_13_morning", SolarScreen.renderFrame(13, 9 * 60, 6 * 60, 18 * 60), 13)
        GoldenAscii.check("solar_13_noon", SolarScreen.renderFrame(13, 12 * 60, 6 * 60, 18 * 60), 13)
        GoldenAscii.check("solar_13_night", SolarScreen.renderFrame(13, 0, 6 * 60, 18 * 60), 13)
        GoldenAscii.check("solar_25_noon", SolarScreen.renderFrame(25, 12 * 60, 6 * 60, 18 * 60), 25)
    }

    @Test
    fun `polar night keeps the sun below the horizon`() {
        val frame = SolarScreen.renderFrame(13, 12 * 60, Int.MAX_VALUE, Int.MAX_VALUE)
        // No bright sun anywhere above the horizon (row < 8).
        for (y in 0 until 8) for (x in 0 until 13) {
            assertTrue(frame[y * 13 + x] < 2000)
        }
    }
}

class MoonScreenTest {

    @Test
    fun `phase goldens`() {
        GoldenAscii.check("moon_13_new", MoonScreen.renderFrame(13, 0.0), 13)
        GoldenAscii.check("moon_13_firstquarter", MoonScreen.renderFrame(13, 0.25), 13)
        GoldenAscii.check("moon_13_full", MoonScreen.renderFrame(13, 0.5), 13)
        GoldenAscii.check("moon_13_waning75", MoonScreen.renderFrame(13, 0.75), 13)
        GoldenAscii.check("moon_25_full", MoonScreen.renderFrame(25, 0.5), 25)
    }

    @Test
    fun `new is faint and full is bright and textured`() {
        val new = MoonScreen.renderFrame(13, 0.0)
        val full = MoonScreen.renderFrame(13, 0.5)
        // New moon: only faint earthshine, well below full's highlands.
        assertTrue("new max ${new.max()}", new.max() < 600)
        // Full moon: bright highlands present.
        assertTrue("full max ${full.max()}", full.max() > 3500)
        // Textured, not a flat disc: a real spread of brightness levels among
        // lit cells (maria dim, highlands bright).
        val litLevels = full.filter { it > 200 }.map { it / 400 }.toSet()
        assertTrue("distinct levels ${litLevels.size}", litLevels.size >= 5)
    }
}

class SkyAmbientBackgroundsTest {

    @Test
    fun `ambient backgrounds 7-9 delegate to the screen renderers`() {
        val h = TestHarness(13)
        h.spectrum.values = null
        h.battery.level = 60
        h.battery.charging = true
        // Disable the compositor's own charging LAYER so the background is
        // what reaches the output (the gauge shows charging state itself).
        h.prefs.putBoolean(PrefKeys.AMBIENT_USE_CHARGING, false)
        val screen = AmbientScreen()

        h.prefs.putInt(PrefKeys.AMBIENT_BACKGROUND, 7)
        assertTrue(
            screen.composite(h.context)
                .contentEquals(BatteryScreen.renderFrame(13, 60, true, h.clock.now)),
        )
        // Background 7 is the gauge, full stop: the Battery toy's wattage pref
        // and an available reading must not leak into the ambient background.
        h.battery.watts = 45f
        h.prefs.putBoolean(PrefKeys.BATTERY_SHOW_WATTS, true)
        assertTrue(
            screen.composite(h.context)
                .contentEquals(BatteryScreen.renderFrame(13, 60, true, h.clock.now)),
        )
        h.prefs.putBoolean(PrefKeys.BATTERY_SHOW_WATTS, false)
        h.battery.watts = null

        h.battery.charging = false
        h.prefs.putInt(PrefKeys.AMBIENT_BACKGROUND, 9)
        assertTrue(
            screen.composite(h.context)
                .contentEquals(MoonScreen.renderFrame(13, MoonMath.phaseFraction(h.clock.now))),
        )

        h.prefs.putInt(PrefKeys.AMBIENT_BACKGROUND, 8)
        h.clock.hour = 12
        h.clock.min = 0
        // Equator/lon 0/offset 0, doy 80: close to a 6/18 day; just assert it
        // matches the screen renderer with the same computed times.
        val times = SolarMath.sunTimes(h.clock.doy, 0.0, 0.0, 0)
        assertTrue(
            screen.composite(h.context)
                .contentEquals(SolarScreen.renderFrame(13, 12 * 60, times.riseMin, times.setMin)),
        )
    }
}
