package space.linuxct.glyphworks.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import space.linuxct.glyphworks.FakeClock
import space.linuxct.glyphworks.FakeLight
import space.linuxct.glyphworks.FakePrefs
import space.linuxct.glyphworks.FakeScheduler

class AutoBrightnessTest {
    private val clock = FakeClock()
    private val prefs = FakePrefs()
    private val scheduler = FakeScheduler(clock)
    private val light = FakeLight()
    private var reapplies = 0

    private val auto = AutoBrightness(prefs, light, scheduler) { reapplies++ }

    private fun brightness() = prefs.getFloat(PrefKeys.BRIGHTNESS, PrefKeys.BRIGHTNESS_DEF)

    /** Runs out the warm-up delay so the sample started by the caller lands. */
    private fun settleWarmup() = scheduler.advanceTime(AutoBrightness.WARMUP_MS)

    private fun enable() {
        prefs.putBoolean(PrefKeys.AUTO_BRIGHTNESS, true)
        auto.start()
    }

    // ---------- lux -> brightness curve ----------

    @Test
    fun `pitch dark returns exactly the floor and never blanks`() {
        assertEquals(AutoBrightness.FLOOR, AutoBrightness.luxToBrightness(0f), 1e-6f)
        assertEquals(AutoBrightness.FLOOR, AutoBrightness.luxToBrightness(-5f), 1e-6f)
        assertTrue(AutoBrightness.luxToBrightness(0f) > 0f)
    }

    @Test
    fun `bright daylight saturates at full brightness`() {
        assertEquals(1f, AutoBrightness.luxToBrightness(AutoBrightness.SATURATION_LUX), 1e-4f)
        assertEquals(1f, AutoBrightness.luxToBrightness(50_000f), 1e-6f)
        assertEquals(1f, AutoBrightness.luxToBrightness(Float.MAX_VALUE), 1e-6f)
    }

    @Test
    fun `curve is monotonically non-decreasing in lux`() {
        var previous = AutoBrightness.luxToBrightness(0f)
        var lux = 0f
        while (lux < 20_000f) {
            lux += 7.3f
            val v = AutoBrightness.luxToBrightness(lux)
            assertTrue("dropped at $lux: $previous -> $v", v >= previous - 1e-6f)
            previous = v
        }
    }

    // ---------- controller ----------

    @Test
    fun `disabled pref means no polling at all`() {
        auto.start()
        scheduler.advanceTime(AutoBrightness.POLL_SCREEN_ON_MS * 5)
        assertEquals(0, light.polls)
        assertEquals(0, reapplies)
    }

    @Test
    fun `enabling samples immediately and then at the screen-on interval`() {
        light.lux = 10f
        enable()
        settleWarmup()
        // 1.0 -> target 0.37, in one go.
        val afterFirst = brightness()
        assertTrue("$afterFirst", afterFirst < 1f && afterFirst > AutoBrightness.FLOOR)
        assertEquals(1, reapplies)

        // Nothing happens between polls, even though the light has moved.
        light.lux = 1_000f
        scheduler.advanceTime(AutoBrightness.POLL_SCREEN_ON_MS - AutoBrightness.WARMUP_MS - 1)
        assertEquals(afterFirst, brightness(), 1e-6f)

        // The next poll (plus its warm-up) picks the new level up.
        scheduler.advanceTime(1)
        settleWarmup()
        assertTrue("${brightness()} should be above $afterFirst", brightness() > afterFirst)
        assertEquals(2, reapplies)
    }

    @Test
    fun `screen off switches to the slow interval`() {
        light.lux = 10f
        enable()
        settleWarmup()
        // Counting sensor polls, not pref writes: the cadence is what is under
        // test here, independent of the hysteresis policy.
        var polls = light.polls

        auto.setScreenOn(false)
        // The screen-on cadence would have fired several times by now.
        scheduler.advanceTime(AutoBrightness.POLL_SCREEN_ON_MS * 5)
        settleWarmup()
        assertEquals("no sample on the fast cadence while the screen is off", polls, light.polls)

        scheduler.advanceTime(AutoBrightness.POLL_SCREEN_OFF_MS)
        settleWarmup()
        assertTrue("the slow cadence must still sample", light.polls > polls)
        polls = light.polls

        // Coming back on samples right away and restores the fast cadence.
        auto.setScreenOn(true)
        settleWarmup()
        assertTrue("screen-on samples immediately", light.polls > polls)
        polls = light.polls
        scheduler.advanceTime(AutoBrightness.POLL_SCREEN_ON_MS)
        settleWarmup()
        assertTrue("back on the fast cadence", light.polls > polls)
    }

    @Test
    fun `a null reading holds the last known brightness`() {
        light.lux = 10f
        enable()
        settleWarmup()
        val held = brightness()

        light.lux = null // sensor absent / nothing reported
        repeat(3) {
            scheduler.advanceTime(AutoBrightness.POLL_SCREEN_ON_MS)
            settleWarmup()
        }
        assertTrue("the sensor was still polled", light.polls > 2)
        assertEquals("brightness must be held, not guessed", held, brightness(), 1e-6f)
    }

    @Test
    fun `stop cancels all pending work`() {
        light.lux = 10f
        enable()
        settleWarmup()
        val n = reapplies
        val value = brightness()

        auto.stop()
        scheduler.advanceTime(AutoBrightness.POLL_SCREEN_ON_MS * 10)
        settleWarmup()
        assertEquals(n, reapplies)
        assertEquals(value, brightness(), 1e-6f)
    }

    @Test
    fun `a change below the hysteresis threshold is not written`() {
        light.lux = 400f
        val target = AutoBrightness.luxToBrightness(400f)
        // Start a hair away from the target: less than the threshold, so no write.
        prefs.putFloat(PrefKeys.BRIGHTNESS, target - AutoBrightness.HYSTERESIS / 2f)
        val before = brightness()
        enable()
        settleWarmup()
        assertEquals(before, brightness(), 1e-6f)
        assertEquals("no re-render for an imperceptible change", 0, reapplies)

        // A clearly bigger gap does get written.
        prefs.putFloat(PrefKeys.BRIGHTNESS, 1f)
        scheduler.advanceTime(AutoBrightness.POLL_SCREEN_ON_MS)
        settleWarmup()
        assertTrue(brightness() < 1f)
        assertEquals(1, reapplies)
    }

    @Test
    fun `converges toward the target without overshooting`() {
        light.lux = 0f // pitch dark: target is the floor
        enable()
        repeat(20) {
            settleWarmup()
            scheduler.advanceTime(AutoBrightness.POLL_SCREEN_ON_MS)
        }
        assertEquals(AutoBrightness.FLOOR, brightness(), AutoBrightness.HYSTERESIS)
        assertTrue("never dimmer than the floor", brightness() >= AutoBrightness.FLOOR)
    }

    // ---------- convergence ----------
}
