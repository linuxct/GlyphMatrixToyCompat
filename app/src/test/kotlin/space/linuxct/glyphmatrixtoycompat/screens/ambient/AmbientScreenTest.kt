package space.linuxct.glyphmatrixtoycompat.screens.ambient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import space.linuxct.glyphmatrixtoycompat.GoldenAscii
import space.linuxct.glyphmatrixtoycompat.TestHarness
import space.linuxct.glyphmatrixtoycompat.core.ConnectionState
import space.linuxct.glyphmatrixtoycompat.core.PrefKeys
import space.linuxct.glyphmatrixtoycompat.screens.VisualizerScreen

class NightWindowTest {
    @Test
    fun `night is 2300 to 0559`() {
        assertTrue(NightWindow.isNight(23))
        assertTrue(NightWindow.isNight(0))
        assertTrue(NightWindow.isNight(5))
        assertFalse(NightWindow.isNight(6))
        assertFalse(NightWindow.isNight(12))
        assertFalse(NightWindow.isNight(22))
    }
}

class AmbientScreenTest {

    private fun harness(size: Int = 13): Pair<AmbientScreen, TestHarness> {
        val h = TestHarness(size)
        h.clock.hour = 12
        h.clock.min = 34
        h.spectrum.values = null // no mic by default -> audio layer inactive
        return AmbientScreen() to h
    }

    @Test
    fun `default background is the digital clock`() {
        val (screen, h) = harness()
        GoldenAscii.check("ambient_13_bg_textclock", screen.composite(h.context), 13)
    }

    @Test
    fun `background gallery goldens`() {
        val (screen, h) = harness()
        h.clock.hour = 10
        h.clock.min = 8
        h.clock.sec = 0
        h.prefs.putInt(PrefKeys.AMBIENT_BACKGROUND, 1)
        GoldenAscii.check("ambient_13_bg_analog_1008", screen.composite(h.context), 13)

        h.prefs.putInt(PrefKeys.AMBIENT_BACKGROUND, 2)
        h.connectivity.value = ConnectionState.WIFI
        GoldenAscii.check("ambient_13_bg_wifi", screen.composite(h.context), 13)
        h.connectivity.value = ConnectionState.CELLULAR
        GoldenAscii.check("ambient_13_bg_cellular", screen.composite(h.context), 13)
        h.connectivity.value = ConnectionState.AIRPLANE
        GoldenAscii.check("ambient_13_bg_airplane", screen.composite(h.context), 13)
        h.connectivity.value = ConnectionState.NONE
        GoldenAscii.check("ambient_13_bg_noconn", screen.composite(h.context), 13)

        h.prefs.putInt(PrefKeys.AMBIENT_BACKGROUND, 3)
        h.battery.level = 85
        GoldenAscii.check("ambient_13_bg_battery85", screen.composite(h.context), 13)

        h.prefs.putInt(PrefKeys.AMBIENT_BACKGROUND, 5)
        GoldenAscii.check("ambient_13_bg_tiltball", screen.composite(h.context), 13)
    }

    @Test
    fun `night gating hides background only when configured`() {
        val (screen, h) = harness()
        h.clock.hour = 23
        // Default: visible at night.
        assertFalse(screen.composite(h.context).all { it == 0 })
        h.prefs.putBoolean(PrefKeys.AMBIENT_NIGHT_VISIBLE, false)
        assertTrue(screen.composite(h.context).all { it == 0 })
        h.clock.hour = 7 // day again
        assertFalse(screen.composite(h.context).all { it == 0 })
    }

    @Test
    fun `shake activation shows background for 30s after a shake`() {
        val (screen, h) = harness()
        h.prefs.putBoolean(PrefKeys.AMBIENT_SHAKE_ACTIVATE, true)
        h.shake.millisSince = Long.MAX_VALUE
        assertTrue(screen.composite(h.context).all { it == 0 })
        h.shake.millisSince = 5_000
        assertFalse(screen.composite(h.context).all { it == 0 })
        h.shake.millisSince = 31_000
        assertTrue(screen.composite(h.context).all { it == 0 })
    }

    @Test
    fun `charging layer replaces background and stops at 100`() {
        val (screen, h) = harness()
        h.battery.charging = true
        h.battery.level = 65
        val frame = screen.composite(h.context)
        assertTrue(
            frame.contentEquals(ChargingRenderer.render(13, 0, 65, h.clock.now)),
        )
        GoldenAscii.check("ambient_13_charging_s0", frame, 13)
        h.prefs.putInt(PrefKeys.AMBIENT_CHARGING_STYLE, 2)
        GoldenAscii.check("ambient_13_charging_s2", screen.composite(h.context), 13)
        h.prefs.putInt(PrefKeys.AMBIENT_CHARGING_STYLE, 3)
        GoldenAscii.check("ambient_13_charging_s3", screen.composite(h.context), 13)

        h.battery.level = 100 // full: charging layer off, background shows
        h.prefs.putInt(PrefKeys.AMBIENT_CHARGING_STYLE, 0)
        val atFull = screen.composite(h.context)
        assertFalse(atFull.contentEquals(ChargingRenderer.render(13, 0, 100, h.clock.now)))
    }

    @Test
    fun `audio layer wins over charging and reverts on silence`() {
        val (screen, h) = harness()
        h.battery.charging = true
        h.battery.level = 65
        val ramp = FloatArray(32) { it / 31f }
        h.spectrum.values = ramp
        val frame = screen.composite(h.context)
        val expectedBands = h.spectrum.bands(13)!!
        assertTrue(frame.contentEquals(VisualizerScreen.renderFrame(13, expectedBands, 0)))

        h.spectrum.values = FloatArray(32) { 0.01f } // silence -> next tick reverts
        val next = screen.composite(h.context)
        assertTrue(next.contentEquals(ChargingRenderer.render(13, 0, 65, h.clock.now)))
    }

    @Test
    fun `disabled background with no other layer is dark`() {
        val (screen, h) = harness()
        h.prefs.putBoolean(PrefKeys.AMBIENT_USE_BACKGROUND, false)
        assertTrue(screen.composite(h.context).all { it == 0 })
    }

    @Test
    fun `pixel clock background honours theme`() {
        val (screen, h) = harness()
        h.prefs.putInt(PrefKeys.AMBIENT_BACKGROUND, 6)
        h.prefs.putInt(PrefKeys.CLOCK_THEME, 2)
        h.battery.level = 50
        GoldenAscii.check("ambient_13_bg_pixelclock_ring", screen.composite(h.context), 13)
    }

    @Test
    fun `25x25 composite`() {
        val (screen, h) = harness(25)
        GoldenAscii.check("ambient_25_bg_textclock", screen.composite(h.context), 25)
        assertEquals(25 * 25, screen.composite(h.context).size)
    }
}
