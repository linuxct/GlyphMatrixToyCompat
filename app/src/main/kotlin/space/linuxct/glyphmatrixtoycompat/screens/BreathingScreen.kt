package space.linuxct.glyphmatrixtoycompat.screens

import space.linuxct.glyphmatrixtoycompat.core.Events
import space.linuxct.glyphmatrixtoycompat.core.GlyphScreen
import space.linuxct.glyphmatrixtoycompat.core.PrefKeys
import space.linuxct.glyphmatrixtoycompat.core.ScreenContext
import space.linuxct.glyphmatrixtoycompat.matrix.MatrixCanvas

/**
 * Guided breathing: Glyph Touch toggles the animation. A soft-edged disc
 * ping-pongs through 12 radius steps with 2-step holds at both extremes
 * (inhale / hold / exhale / hold). The pace pref scales the frame interval:
 * interval = pace * 125 ms, so the default pace "4" gives a 500 ms cadence.
 */
class BreathingScreen : GlyphScreen {
    override val id = "breathing"
    override val interactive = true

    private var ctx: ScreenContext? = null
    private var running = false
    private var step = 0

    override fun onActivate(ctx: ScreenContext) {
        this.ctx = ctx
        running = false
        step = 0
        pushIdle()
    }

    override fun onDeactivate() {
        running = false
        ctx = null
    }

    override fun onEvent(event: String) {
        if (event != Events.CHANGE) return
        val c = ctx ?: return
        running = !running
        if (running) {
            step = 0
            val pace = c.prefs.getString(PrefKeys.BREATHING_PACE, PrefKeys.BREATHING_PACE_DEF)
                .toIntOrNull()?.coerceIn(1, 20) ?: 4
            c.scheduler.setTicker(pace * 125L) { tick() }
        } else {
            c.scheduler.clearTicker()
            pushIdle()
        }
    }

    private fun tick() {
        val c = ctx ?: return
        c.pushFrame(renderStep(c.size, step))
        step++
    }

    private fun pushIdle() {
        val c = ctx ?: return
        val canvas = MatrixCanvas(c.size)
        val center = (c.size - 1) / 2f
        canvas.discSoft(center, center, minRadius(c.size), 1500)
        c.pushFrame(canvas.copyOut())
    }

    companion object {
        const val STEPS = 12
        private const val HOLD = 2

        private fun minRadius(size: Int) = if (size >= 25) 2.5f else 1.5f
        private fun maxRadius(size: Int) = if (size >= 25) 11.5f else 5.8f

        /** Cycle: STEPS up, HOLD at max, STEPS down, HOLD at min. */
        fun radiusIndexFor(step: Int): Int {
            val period = 2 * STEPS + 2 * HOLD
            val m = ((step % period) + period) % period
            return when {
                m < STEPS -> m
                m < STEPS + HOLD -> STEPS - 1
                m < 2 * STEPS + HOLD -> 2 * STEPS + HOLD - 1 - m
                else -> 0
            }
        }

        fun renderStep(size: Int, step: Int): IntArray {
            val canvas = MatrixCanvas(size)
            val center = (size - 1) / 2f
            val idx = radiusIndexFor(step)
            val r = minRadius(size) +
                (maxRadius(size) - minRadius(size)) * idx / (STEPS - 1)
            canvas.discSoft(center, center, r, 4095)
            return canvas.copyOut()
        }
    }
}
