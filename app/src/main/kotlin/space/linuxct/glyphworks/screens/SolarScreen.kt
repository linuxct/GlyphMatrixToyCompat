package space.linuxct.glyphworks.screens

import space.linuxct.glyphworks.core.GlyphScreen
import space.linuxct.glyphworks.core.ScreenContext
import space.linuxct.glyphworks.matrix.MatrixCanvas
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Solar Path: the sun's position along its daily arc over a horizon, from
 * sunrise (left) to sunset (right), computed for the device's last known
 * location. At night the sun travels a mirrored arc below the horizon
 * (drawn dim) under a few faint stars. Without a location fix it falls back
 * to a 06:00/18:00 equinox day. Sun times refresh at most once a minute.
 */
class SolarScreen : GlyphScreen {
    override val id = "solar"
    override val interactive = false

    private var ctx: ScreenContext? = null
    private var cachedTimes: SolarMath.SunTimes? = null
    private var cachedAt = 0L

    override fun onActivate(ctx: ScreenContext) {
        this.ctx = ctx
        cachedTimes = null
        cachedAt = 0L
        ctx.scheduler.setTicker(1000) { tick() }
    }

    override fun onDeactivate() {
        ctx = null
    }

    private fun sunTimes(c: ScreenContext): SolarMath.SunTimes {
        val now = c.ports.clock.nowMillis()
        val cached = cachedTimes
        if (cached != null && now - cachedAt < 60_000) return cached
        cachedAt = now
        val loc = c.ports.location.latLon()
        val times = if (loc == null) {
            SolarMath.SunTimes(FALLBACK_RISE, FALLBACK_SET, SolarMath.Kind.NORMAL)
        } else {
            SolarMath.sunTimes(
                c.ports.clock.dayOfYear(),
                loc.first,
                loc.second,
                c.ports.clock.utcOffsetMinutes(),
            )
        }
        cachedTimes = times
        return times
    }

    private fun tick() {
        val c = ctx ?: return
        val minutes = c.ports.clock.hourOfDay() * 60 + c.ports.clock.minute()
        val times = sunTimes(c)
        val (rise, set) = when (times.kind) {
            SolarMath.Kind.POLAR_DAY -> 0 to 1440
            SolarMath.Kind.POLAR_NIGHT -> Int.MAX_VALUE to Int.MAX_VALUE // sun never above horizon
            SolarMath.Kind.NORMAL -> times.riseMin to times.setMin
        }
        c.pushFrame(renderFrame(c.size, minutes, rise, set))
    }

    companion object {
        const val FALLBACK_RISE = 6 * 60
        const val FALLBACK_SET = 18 * 60

        fun renderFrame(size: Int, minutesLocal: Int, riseMin: Int, setMin: Int): IntArray {
            val canvas = MatrixCanvas(size)
            val horizonY = if (size >= 25) 16 else 8
            val cx = size / 2
            val r = min(horizonY - 2, size / 2 - 1).toFloat()

            // Ground and horizon.
            canvas.fillRect(0, horizonY + 1, size, size - horizonY - 1, 700)
            for (x in 0 until size) canvas.light(x, horizonY, 1600)

            val day = riseMin != Int.MAX_VALUE && minutesLocal in riseMin until setMin
            if (day) {
                val f = (minutesLocal - riseMin).toFloat() / (setMin - riseMin).coerceAtLeast(1)
                val px = cx - r * cos(Math.PI * f).toFloat()
                val py = horizonY - r * sin(Math.PI * f).toFloat()
                canvas.discSoft(px, py, if (size >= 25) 1.8f else 1.2f, 4095)
            } else {
                // Night: stars above, sun dim on the mirrored arc below.
                canvas.light(2, 2, 900)
                canvas.light(size - 4, 1, 900)
                canvas.light(size / 2 + 1, 4, 900)
                if (riseMin != Int.MAX_VALUE) {
                    val nightLen = (1440 - (setMin - riseMin)).coerceAtLeast(1)
                    val sinceSet = ((minutesLocal - setMin) + 1440) % 1440
                    val f = sinceSet.toFloat() / nightLen
                    // The mirrored arc is squashed into the few rows below the
                    // horizon so the sun stays on-canvas all night.
                    val rBelow = (size - 2 - horizonY).toFloat().coerceAtMost(r)
                    val px = cx + r * cos(Math.PI * f).toFloat()
                    val py = horizonY + rBelow * sin(Math.PI * f).toFloat()
                    // Brighter than the ground fill (700) so it shows through
                    // the max-blend, still clearly dimmer than the day sun.
                    canvas.discSoft(px, py, 1.0f, 1600)
                }
            }
            return canvas.copyOut()
        }
    }
}
