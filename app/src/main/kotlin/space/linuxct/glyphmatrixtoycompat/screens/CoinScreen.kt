package space.linuxct.glyphmatrixtoycompat.screens

import space.linuxct.glyphmatrixtoycompat.core.Events
import space.linuxct.glyphmatrixtoycompat.core.GlyphScreen
import space.linuxct.glyphmatrixtoycompat.core.ScreenContext
import space.linuxct.glyphmatrixtoycompat.matrix.Font3x5
import space.linuxct.glyphmatrixtoycompat.matrix.MatrixCanvas
import kotlin.math.abs
import kotlin.math.cos

/**
 * Coin Flip: Glyph Touch (or shake) restarts a ~1 s flip; the coin is drawn
 * as an ellipse whose height follows |cos| through three rotations (edge-on
 * at the zero crossings), then lands 50/50 on H or T.
 */
class CoinScreen : GlyphScreen {
    override val id = "coin"
    override val interactive = true

    private var ctx: ScreenContext? = null
    private var heads = true
    private var flipStartedAt = 0L

    override fun onActivate(ctx: ScreenContext) {
        this.ctx = ctx
        flipStartedAt = 0L
        pushResult()
    }

    override fun onDeactivate() {
        ctx = null
    }

    override fun onEvent(event: String) {
        if (event == Events.CHANGE || event == Events.SHAKE) startFlip()
    }

    private fun startFlip() {
        val c = ctx ?: return
        flipStartedAt = c.ports.clock.nowMillis()
        c.scheduler.setTicker(33) { tickFlip() }
    }

    private fun tickFlip() {
        val c = ctx ?: return
        val elapsed = c.ports.clock.nowMillis() - flipStartedAt
        if (elapsed >= FLIP_MS) {
            heads = c.ports.random.nextInt(2) == 0
            flipStartedAt = 0L
            c.scheduler.clearTicker()
            pushResult()
            return
        }
        val t = elapsed.toFloat() / FLIP_MS
        val squash = abs(cos(t * ROTATIONS * 2f * Math.PI.toFloat()))
        val canvas = MatrixCanvas(c.size)
        val center = (c.size - 1) / 2f
        val r = c.size / 2f - 0.8f
        // Ellipse outline: parametric plot with vertical squash.
        var deg = 0
        while (deg < 360) {
            val rad = Math.toRadians(deg.toDouble())
            val x = center + r * Math.sin(rad)
            val y = center - r * squash * Math.cos(rad)
            canvas.light(Math.round(x).toInt(), Math.round(y).toInt(), 4095)
            deg += 5
        }
        c.pushFrame(canvas.copyOut())
    }

    private fun pushResult() {
        val c = ctx ?: return
        c.pushFrame(renderResult(c.size, heads))
    }

    companion object {
        const val FLIP_MS = 1000L
        const val ROTATIONS = 3

        fun renderResult(size: Int, heads: Boolean): IntArray {
            val canvas = MatrixCanvas(size)
            val center = (size - 1) / 2f
            canvas.ring(center, center, size / 2f - 1.4f, size / 2f - 0.4f, 2200)
            val letterY = size / 2 - 2
            Font3x5.drawStringCentered(canvas, if (heads) "H" else "T", letterY, 4095)
            return canvas.copyOut()
        }
    }
}
