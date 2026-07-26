package space.linuxct.glyphmatrixtoycompat.screens

import space.linuxct.glyphmatrixtoycompat.core.GlyphScreen
import space.linuxct.glyphmatrixtoycompat.core.ScreenContext
import space.linuxct.glyphmatrixtoycompat.matrix.MatrixCanvas
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Battery gauge: the matrix fills bottom-up to the battery level, with a
 * brighter edge row marking the level. While charging, a rising wave sweeps
 * through the fill and a pulsing bolt overlays the centre — level and
 * charging status at a glance.
 */
class BatteryScreen : GlyphScreen {
    override val id = "battery"
    override val interactive = false

    private var ctx: ScreenContext? = null

    override fun onActivate(ctx: ScreenContext) {
        this.ctx = ctx
        ctx.scheduler.setTicker(1000) { tick() }
    }

    override fun onDeactivate() {
        ctx = null
    }

    private fun tick() {
        val c = ctx ?: return
        c.pushFrame(
            renderFrame(
                c.size,
                c.ports.battery.levelPercent(),
                c.ports.battery.isCharging(),
                c.ports.clock.nowMillis(),
            ),
        )
    }

    companion object {
        private val BOLT = listOf(
            "..#",
            ".#.",
            "###",
            ".#.",
            "#..",
        )

        fun renderFrame(size: Int, levelPercent: Int, charging: Boolean, nowMs: Long): IntArray {
            val canvas = MatrixCanvas(size)
            val level = levelPercent.coerceIn(0, 100)
            val fillRows = (size * level / 100).coerceIn(0, size)

            for (y in size - fillRows until size) {
                val rowFromBottom = size - 1 - y
                val wave = charging && rowFromBottom == (nowMs / 150 % size).toInt()
                val v = if (wave) 3600 else 1400
                for (x in 0 until size) canvas.light(x, y, v)
            }
            if (fillRows in 1..size) {
                val y = (size - fillRows).coerceAtLeast(0)
                for (x in 0 until size) canvas.light(x, y, 2900)
            }
            if (charging) {
                val pulse = (2400 + 1600 * sin(nowMs / 200.0)).roundToInt().coerceIn(800, 4095)
                val boltY = if (size >= 25) size / 2 - 3 else size / 2 - 2
                // Overwrite (not max-blend) so the bolt stays visible inside the
                // fill: brighter than it at pulse-high, carved dark at pulse-low.
                BOLT.forEachIndexed { by, row ->
                    row.forEachIndexed { bx, ch ->
                        if (ch == '#') canvas.set(size / 2 - 1 + bx, boltY + by, pulse)
                    }
                }
            }
            return canvas.copyOut()
        }
    }
}
