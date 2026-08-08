package space.linuxct.glyphworks.screens

import space.linuxct.glyphworks.core.GlyphScreen
import space.linuxct.glyphworks.core.PrefKeys
import space.linuxct.glyphworks.core.ScreenContext
import space.linuxct.glyphworks.matrix.Font3x5
import space.linuxct.glyphworks.matrix.MatrixCanvas
import space.linuxct.glyphworks.matrix.PanelMask

/**
 * Clock. Three digital themes and one analog:
 *  0 - plain digits
 *  1 - digits + horizontal battery bar (fill = battery %)
 *  2 - digits + battery ring (arc = battery %)
 *  3 - analog hands, framed by the panel border
 *
 * 13x13: stacked HH (rows 1-5) / MM (rows 7-11) at x=3..9, theme extras in the
 * gap row / outer ring. 25x25: single centered HH:MM line. Content visibly
 * changes once per minute; a 12 h "PM" marker is a corner dot.
 *
 * Theme 3 leaves that layout behind entirely — see [renderAnalog], which is also
 * what the ambient toy's analog background draws, so the two cannot diverge.
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

        /** [CLOCK_THEME][PrefKeys.CLOCK_THEME] value that swaps digits for hands. */
        const val THEME_ANALOG = 3

        /** Hour hand: the one that must read at a glance, so full strength. */
        private const val HOUR_BRIGHT = 4095

        /** Minute hand at 0.6x, so a glance separates it from the hour hand. */
        private const val MINUTE_BRIGHT = 2457

        /**
         * The frame around the panel.
         *
         * Same value as [EXTRA_BRIGHT], and that is the point rather than a
         * coincidence: this screen already draws its secondary furniture — the
         * battery bar, the battery ring — at exactly this level, and the border is
         * furniture too. It has to be unmistakably present and never compete with
         * the hands, which is what 27 % of full buys.
         */
        private const val BORDER_BRIGHT = EXTRA_BRIGHT

        /**
         * Pure renderer, reused by the ambient pixel-clock background.
         *
         * On [THEME_ANALOG] this hands straight over to [renderAnalog] — nothing
         * below applies, including the 12 h corner dot, which has no meaning on a
         * dial whose hour hand already goes round twice a day.
         */
        fun renderFrame(c: ScreenContext): IntArray {
            val canvas = MatrixCanvas(c.size)
            val use12h = c.prefs.getBoolean(PrefKeys.USE_12H, false)
            val theme = c.prefs.getInt(PrefKeys.CLOCK_THEME, PrefKeys.CLOCK_THEME_DEF)
            if (theme == THEME_ANALOG) return renderAnalog(c)
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

        /**
         * The analog dial: hour hand at full strength, minute hand at 0.6x, and
         * the panel's own outline as a border. No second hand — but the minute
         * angle carries the seconds, so it steps visibly every second and the
         * clock never looks stopped.
         *
         * **One renderer, two toys.** This is the Clock screen's theme 3 *and* the
         * ambient toy's analog background (`BackgroundRenderers` index 1), which
         * delegates here rather than keeping its own copy. They were separate and
         * identical; the border is the sort of change that would have landed on one
         * of them.
         *
         * The border is [PanelMask.isEdge], not a rectangle and not an [arcRing]:
         * the panel is a disc, so its real outline is "on the panel, with a
         * 4-neighbour that is not". A rect would be clipped at the corners and an
         * arcRing would land a fraction of a cell off at one size or the other.
         * This closes all the way round at both 13 and 25 by construction.
         */
        fun renderAnalog(c: ScreenContext): IntArray {
            val canvas = MatrixCanvas(c.size)
            val center = c.size / 2

            // Hands stop one cell short of the border at both sizes: 13 gives them
            // 5 and 6 inside a radius-6 panel, 25 gives them 7 and 9 inside 12.
            val hourLen = if (c.size >= 25) 7f else 5f
            val minLen = if (c.size >= 25) 9f else 6f

            val hour = c.ports.clock.hourOfDay() % 12
            val minute = c.ports.clock.minute()
            val second = c.ports.clock.second()
            val hourAngle = (hour + minute / 60f) * 30f
            val minAngle = (minute + second / 60f) * 6f

            for (y in 0 until c.size) {
                for (x in 0 until c.size) {
                    if (PanelMask.isEdge(x, y, c.size)) canvas.set(x, y, BORDER_BRIGHT)
                }
            }
            canvas.ray(center, center, minAngle, minLen, MINUTE_BRIGHT)
            canvas.ray(center, center, hourAngle, hourLen, HOUR_BRIGHT)
            return canvas.copyOut()
        }
    }
}
