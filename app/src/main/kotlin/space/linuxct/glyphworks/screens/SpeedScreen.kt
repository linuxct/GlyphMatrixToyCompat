package space.linuxct.glyphworks.screens

import space.linuxct.glyphworks.core.GlyphScreen
import space.linuxct.glyphworks.core.ScreenContext
import space.linuxct.glyphworks.core.SpeedPort
import space.linuxct.glyphworks.matrix.Font3x5
import space.linuxct.glyphworks.matrix.MatrixCanvas

/**
 * Download Speed: 1 s ticker over the cumulative RX byte counter.
 * Display rules sized for 13 columns: "NNK" below 100 KB/s, "N.NM" below
 * 10 MB/s, "NNM" above (clamped to 99M).
 */
class SpeedScreen : GlyphScreen {
    override val id = "speed"
    override val interactive = false

    private var ctx: ScreenContext? = null
    private var lastTotal = -1L

    override fun onActivate(ctx: ScreenContext) {
        this.ctx = ctx
        lastTotal = -1L
        ctx.scheduler.setTicker(1000) { tick() }
    }

    override fun onDeactivate() {
        ctx = null
    }

    private fun tick() {
        val c = ctx ?: return
        val total = c.ports.speed.totalRxBytes()
        val delta = if (lastTotal < 0) 0L else (total - lastTotal).coerceAtLeast(0)
        lastTotal = total
        c.pushFrame(renderFrame(c.size, delta))
    }

    companion object {
        fun formatSpeed(bytesPerSec: Long): String {
            val kb = bytesPerSec / 1000
            return when {
                kb < 100 -> "${kb}K"
                bytesPerSec < 10_000_000 -> {
                    val tenths = bytesPerSec / 100_000 // MB * 10
                    "${tenths / 10}.${tenths % 10}M"
                }
                else -> "${(bytesPerSec / 1_000_000).coerceAtMost(99)}M"
            }
        }

        fun renderFrame(size: Int, bytesPerSec: Long): IntArray {
            val canvas = MatrixCanvas(size)
            val center = size / 2
            // Down arrow: shaft + chevron tip.
            val arrowTop = if (size >= 25) 3 else 0
            val arrowLen = if (size >= 25) 5 else 3
            for (y in arrowTop until arrowTop + arrowLen - 1) canvas.light(center, y, 2200)
            val tipY = arrowTop + arrowLen - 1
            canvas.light(center - 1, tipY - 1, 2200)
            canvas.light(center + 1, tipY - 1, 2200)
            canvas.light(center, tipY, 2200)

            val textY = if (size >= 25) 12 else 6
            Font3x5.drawStringCentered(canvas, formatSpeed(bytesPerSec), textY, 4095)
            return canvas.copyOut()
        }
    }
}
