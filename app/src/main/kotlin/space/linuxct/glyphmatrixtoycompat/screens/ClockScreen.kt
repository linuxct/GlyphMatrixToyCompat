package space.linuxct.glyphmatrixtoycompat.screens

import space.linuxct.glyphmatrixtoycompat.core.GlyphScreen
import space.linuxct.glyphmatrixtoycompat.core.PrefKeys
import space.linuxct.glyphmatrixtoycompat.core.ScreenContext
import space.linuxct.glyphmatrixtoycompat.matrix.Font3x5
import space.linuxct.glyphmatrixtoycompat.matrix.MatrixCanvas

/**
 * Pixel Clock. All three themes are digital:
 *  0 - plain digits
 *  1 - digits + horizontal battery bar (fill = battery %)
 *  2 - digits + battery ring (arc = battery %)
 *
 * 13x13: stacked HH (rows 1-5) / MM (rows 7-11) at x=3..9, theme extras in the
 * gap row / outer ring. 25x25: single centered HH:MM line. Content visibly
 * changes once per minute; a 12 h "PM" marker is a corner dot.
 */
class ClockScreen : GlyphScreen {
    override val id = "clock"
    override val interactive = false

    private var ctx: ScreenContext? = null

    override fun onActivate(ctx: ScreenContext) {
        this.ctx = ctx
        ctx.scheduler.setTicker(50) { render() }
    }

    override fun onDeactivate() {
        ctx = null
    }

    private fun render() {
        val c = ctx ?: return
        c.pushFrame(renderFrame(c))
    }

    companion object {
        private const val DIGIT_BRIGHT = 4095
        private const val EXTRA_BRIGHT = 1100

        /** Pure renderer, reused by the ambient pixel-clock background. */
        fun renderFrame(c: ScreenContext): IntArray {
            val canvas = MatrixCanvas(c.size)
            val use12h = c.prefs.getBoolean(PrefKeys.USE_12H, false)
            val theme = c.prefs.getInt(PrefKeys.CLOCK_THEME, PrefKeys.CLOCK_THEME_DEF)
            val hour24 = c.ports.clock.hourOfDay()
            val minute = c.ports.clock.minute()
            val pm = hour24 >= 12
            val hour = if (use12h) {
                val h = hour24 % 12
                if (h == 0) 12 else h
            } else {
                hour24
            }
            val hh = hour.toString().padStart(2, '0')
            val mm = minute.toString().padStart(2, '0')

            if (c.size >= 25) {
                Font3x5.drawStringCentered(canvas, "$hh:$mm", 10, DIGIT_BRIGHT)
                when (theme) {
                    1 -> {
                        val fill = c.ports.battery.levelPercent() * c.size / 100
                        for (x in 0 until fill) canvas.light(x, 18, EXTRA_BRIGHT)
                    }
                    2 -> {
                        val center = (c.size - 1) / 2f
                        canvas.arcRing(
                            center, center, c.size / 2f - 1f, c.size / 2f,
                            0f, 360f * c.ports.battery.levelPercent() / 100f, EXTRA_BRIGHT,
                        )
                    }
                }
                if (use12h && pm) canvas.set(c.size - 1, 0, EXTRA_BRIGHT)
            } else {
                Font3x5.drawString(canvas, hh, 3, 1, DIGIT_BRIGHT)
                Font3x5.drawString(canvas, mm, 3, 7, DIGIT_BRIGHT)
                when (theme) {
                    1 -> {
                        val fill = c.ports.battery.levelPercent() * c.size / 100
                        for (x in 0 until fill) canvas.light(x, 6, EXTRA_BRIGHT)
                    }
                    2 -> {
                        canvas.arcRing(
                            6f, 6f, 6f, 6.4f,
                            0f, 360f * c.ports.battery.levelPercent() / 100f, EXTRA_BRIGHT,
                        )
                    }
                }
                if (use12h && pm) canvas.set(c.size - 1, 0, EXTRA_BRIGHT)
            }
            return canvas.copyOut()
        }
    }
}
