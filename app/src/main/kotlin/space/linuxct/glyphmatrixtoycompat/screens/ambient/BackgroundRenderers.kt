package space.linuxct.glyphmatrixtoycompat.screens.ambient

import space.linuxct.glyphmatrixtoycompat.core.ConnectionState
import space.linuxct.glyphmatrixtoycompat.core.PrefKeys
import space.linuxct.glyphmatrixtoycompat.core.ScreenContext
import space.linuxct.glyphmatrixtoycompat.matrix.Font3x5
import space.linuxct.glyphmatrixtoycompat.matrix.MatrixCanvas
import space.linuxct.glyphmatrixtoycompat.screens.BatteryScreen
import space.linuxct.glyphmatrixtoycompat.screens.ClockScreen
import space.linuxct.glyphmatrixtoycompat.screens.MoonMath
import space.linuxct.glyphmatrixtoycompat.screens.MoonScreen
import space.linuxct.glyphmatrixtoycompat.screens.SolarMath
import space.linuxct.glyphmatrixtoycompat.screens.SolarScreen
import space.linuxct.glyphmatrixtoycompat.screens.SpeedScreen

/**
 * The ambient background options (ambientBackground 0-9):
 * 0 digital text clock, 1 analog clock, 2 connection status, 3 battery %,
 * 4 download speed, 5 tilt ball, 6 pixel clock (honours clockTheme),
 * 7 battery gauge, 8 solar path, 9 moon phase.
 */
interface AmbientBackground {
    fun render(c: ScreenContext, nowMs: Long): IntArray
}

object BackgroundRenderers {
    const val COUNT = 10

    fun create(index: Int): AmbientBackground = when (index) {
        1 -> AnalogClockBackground()
        2 -> ConnectionBackground()
        3 -> BatteryTextBackground()
        4 -> SpeedBackground()
        5 -> TiltBallBackground()
        6 -> PixelClockBackground()
        7 -> BatteryGaugeBackground()
        8 -> SolarPathBackground()
        9 -> MoonPhaseBackground()
        else -> TextClockBackground()
    }
}

/** 0: plain digital clock — stacked HH/MM on 13x13, single line on 25x25; PM corner dot in 12 h. */
private class TextClockBackground : AmbientBackground {
    override fun render(c: ScreenContext, nowMs: Long): IntArray {
        val canvas = MatrixCanvas(c.size)
        val use12h = c.prefs.getBoolean(PrefKeys.USE_12H, false)
        val hour24 = c.ports.clock.hourOfDay()
        val hour = if (use12h) (hour24 % 12).let { if (it == 0) 12 else it } else hour24
        val hh = hour.toString().padStart(2, '0')
        val mm = c.ports.clock.minute().toString().padStart(2, '0')
        if (c.size >= 25) {
            Font3x5.drawStringCentered(canvas, "$hh:$mm", 10, 4095)
        } else {
            Font3x5.drawString(canvas, hh, 3, 1, 4095)
            Font3x5.drawString(canvas, mm, 3, 7, 4095)
        }
        if (use12h && hour24 >= 12) canvas.set(c.size - 1, 0, 1100)
        return canvas.copyOut()
    }
}

/**
 * 1: analog clock: hour hand len 5/7 at full brightness, minute hand len 6/9
 * at 0.6x. The minute-hand angle includes the seconds, so it visibly steps
 * every second. No second hand.
 */
private class AnalogClockBackground : AmbientBackground {
    override fun render(c: ScreenContext, nowMs: Long): IntArray {
        val canvas = MatrixCanvas(c.size)
        val center = c.size / 2
        val hourLen = if (c.size >= 25) 7f else 5f
        val minLen = if (c.size >= 25) 9f else 6f
        val hour = c.ports.clock.hourOfDay() % 12
        val minute = c.ports.clock.minute()
        val second = c.ports.clock.second()
        val hourAngle = (hour + minute / 60f) * 30f
        val minAngle = (minute + second / 60f) * 6f
        canvas.ray(center, center, minAngle, minLen, 2457) // 0.6 * 4095
        canvas.ray(center, center, hourAngle, hourLen, 4095)
        return canvas.copyOut()
    }
}

/** 2: connection status icon (Wi-Fi / cellular / airplane / none). */
private class ConnectionBackground : AmbientBackground {
    override fun render(c: ScreenContext, nowMs: Long): IntArray {
        val canvas = MatrixCanvas(c.size)
        val s = c.size
        when (c.ports.connectivity.state()) {
            ConnectionState.WIFI -> {
                val cx = s / 2f
                val cy = s * 3f / 4f
                canvas.discSoft(cx, cy, 0.8f, 4095)
                canvas.arcRing(cx, cy, 2.4f, 3.2f, 315f, 90f, 2600)
                canvas.arcRing(cx, cy, 4.4f, 5.2f, 315f, 90f, 1500)
                if (s >= 25) canvas.arcRing(cx, cy, 6.4f, 7.2f, 315f, 90f, 900)
            }
            ConnectionState.CELLULAR -> {
                val base = s - 3
                val xs = if (s >= 25) listOf(6, 10, 14, 18) else listOf(3, 5, 7, 9)
                xs.forEachIndexed { i, x ->
                    val h = (i + 1) * (if (s >= 25) 4 else 2)
                    for (y in base - h + 1..base) canvas.light(x, y, 1200 + i * 700)
                }
            }
            ConnectionState.AIRPLANE -> {
                val cx = s / 2
                canvas.line(cx, 2, cx, s - 3, 4095) // fuselage
                canvas.line(2, s / 2 - 1, s - 3, s / 2 - 1, 2600) // wings
                canvas.line(cx - 2, s - 4, cx + 2, s - 4, 1800) // tail
            }
            ConnectionState.NONE -> {
                val center = (s - 1) / 2f
                canvas.circle(s / 2, s / 2, s / 2 - 2, 1500)
                canvas.line(s - 4, 3, 3, s - 4, 3000)
                canvas.discSoft(center, center, 0.7f, 800)
            }
        }
        return canvas.copyOut()
    }
}

/** 3: battery percentage as text ("NN%", "100" when full). */
private class BatteryTextBackground : AmbientBackground {
    override fun render(c: ScreenContext, nowMs: Long): IntArray {
        val canvas = MatrixCanvas(c.size)
        val level = c.ports.battery.levelPercent().coerceIn(0, 100)
        val text = if (level >= 100) "100" else "$level%"
        Font3x5.drawStringCentered(canvas, text, c.size / 2 - 2, 4095)
        return canvas.copyOut()
    }
}

/** 4: download speed (same renderer as the standalone screen, own delta state). */
private class SpeedBackground : AmbientBackground {
    private var lastTotal = -1L
    private var lastSampleAt = 0L
    private var bytesPerSec = 0L

    override fun render(c: ScreenContext, nowMs: Long): IntArray {
        if (nowMs - lastSampleAt >= 1000) {
            val total = c.ports.speed.totalRxBytes()
            if (lastTotal >= 0 && nowMs > lastSampleAt) {
                bytesPerSec = ((total - lastTotal) * 1000 / (nowMs - lastSampleAt)).coerceAtLeast(0)
            }
            lastTotal = total
            lastSampleAt = nowMs
        }
        return SpeedScreen.renderFrame(c.size, bytesPerSec)
    }
}

/** 5: tilt-ball physics driven by the linear-acceleration sensor. */
private class TiltBallBackground : AmbientBackground {
    private var px = -1f
    private var py = -1f
    private var vx = 0f
    private var vy = 0f

    override fun render(c: ScreenContext, nowMs: Long): IntArray {
        val s = c.size
        if (px < 0) {
            px = (s - 1) / 2f
            py = (s - 1) / 2f
        }
        // Screen x grows right; sensor +x is device-left-tilted, so invert.
        vx += -c.ports.tilt.tiltX() * 0.06f
        vy += c.ports.tilt.tiltY() * 0.06f
        vx *= 0.92f
        vy *= 0.92f
        px += vx
        py += vy
        val min = 1f
        val max = s - 2f
        if (px < min) { px = min; vx = -vx * 0.6f }
        if (px > max) { px = max; vx = -vx * 0.6f }
        if (py < min) { py = min; vy = -vy * 0.6f }
        if (py > max) { py = max; vy = -vy * 0.6f }

        val canvas = MatrixCanvas(s)
        canvas.rect(0, 0, s, s, 300)
        canvas.discSoft(px, py, if (s >= 25) 2.2f else 1.3f, 4095)
        return canvas.copyOut()
    }
}

/** 6: pixel clock, honouring clockTheme (same renderer as the clock screen). */
private class PixelClockBackground : AmbientBackground {
    override fun render(c: ScreenContext, nowMs: Long): IntArray = ClockScreen.renderFrame(c)
}

/** 7: battery fill gauge with charging wave/bolt (same renderer as the battery screen). */
private class BatteryGaugeBackground : AmbientBackground {
    override fun render(c: ScreenContext, nowMs: Long): IntArray = BatteryScreen.renderFrame(
        c.size,
        c.ports.battery.levelPercent(),
        c.ports.battery.isCharging(),
        nowMs,
    )
}

/** 8: solar path (same renderer as the solar screen; sun times cached ~1 min). */
private class SolarPathBackground : AmbientBackground {
    private var cachedTimes: SolarMath.SunTimes? = null
    private var cachedAt = 0L

    override fun render(c: ScreenContext, nowMs: Long): IntArray {
        var times = cachedTimes
        if (times == null || nowMs - cachedAt >= 60_000) {
            cachedAt = nowMs
            val loc = c.ports.location.latLon()
            times = if (loc == null) {
                SolarMath.SunTimes(SolarScreen.FALLBACK_RISE, SolarScreen.FALLBACK_SET, SolarMath.Kind.NORMAL)
            } else {
                SolarMath.sunTimes(
                    c.ports.clock.dayOfYear(),
                    loc.first,
                    loc.second,
                    c.ports.clock.utcOffsetMinutes(),
                )
            }
            cachedTimes = times
        }
        val minutes = c.ports.clock.hourOfDay() * 60 + c.ports.clock.minute()
        val (rise, set) = when (times.kind) {
            SolarMath.Kind.POLAR_DAY -> 0 to 1440
            SolarMath.Kind.POLAR_NIGHT -> Int.MAX_VALUE to Int.MAX_VALUE
            SolarMath.Kind.NORMAL -> times.riseMin to times.setMin
        }
        return SolarScreen.renderFrame(c.size, minutes, rise, set)
    }
}

/** 9: moon phase (same renderer as the moon screen). */
private class MoonPhaseBackground : AmbientBackground {
    override fun render(c: ScreenContext, nowMs: Long): IntArray =
        MoonScreen.renderFrame(c.size, MoonMath.phaseFraction(nowMs))
}
