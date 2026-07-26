package space.linuxct.glyphmatrixtoycompat.screens

import space.linuxct.glyphmatrixtoycompat.core.Events
import space.linuxct.glyphmatrixtoycompat.core.GlyphScreen
import space.linuxct.glyphmatrixtoycompat.core.PrefKeys
import space.linuxct.glyphmatrixtoycompat.core.ScreenContext
import space.linuxct.glyphmatrixtoycompat.matrix.Font3x5
import space.linuxct.glyphmatrixtoycompat.matrix.MatrixCanvas

/**
 * Counter, laid out natively for 13x13: up to three 3x5 digits right-aligned
 * at fixed columns 0-2 / 5-7 / 10-12, rows 4-8. Glyph Touch increments
 * (999 wraps to 0) and persists; shake resets with a double-blink
 * confirmation. Event-driven — no ticker.
 */
class CounterScreen : GlyphScreen {
    override val id = "counter"
    override val interactive = true

    private var ctx: ScreenContext? = null

    override fun onActivate(ctx: ScreenContext) {
        this.ctx = ctx
        push()
    }

    override fun onDeactivate() {
        ctx = null
    }

    override fun onEvent(event: String) {
        val c = ctx ?: return
        when (event) {
            Events.CHANGE -> {
                val v = (c.prefs.getInt(PrefKeys.COUNTER, PrefKeys.COUNTER_DEF) + 1) % 1000
                c.prefs.putInt(PrefKeys.COUNTER, v)
                push()
            }
            Events.SHAKE -> {
                c.prefs.putInt(PrefKeys.COUNTER, 0)
                blinkConfirm()
            }
        }
    }

    private fun blinkConfirm() {
        val c = ctx ?: return
        push()
        c.scheduler.postDelayed(150) { ctx?.pushFrame(IntArray(c.size * c.size)) }
        c.scheduler.postDelayed(300) { push() }
    }

    private fun push() {
        val c = ctx ?: return
        c.pushFrame(renderFrame(c.size, c.prefs.getInt(PrefKeys.COUNTER, PrefKeys.COUNTER_DEF)))
    }

    companion object {
        fun renderFrame(size: Int, value: Int): IntArray {
            val canvas = MatrixCanvas(size)
            val text = value.coerceIn(0, 999).toString()
            if (size >= 25) {
                val columns = listOf(4, 11, 18)
                val y = 10
                drawAtColumns(canvas, text, columns, y)
            } else {
                val columns = listOf(0, 5, 10)
                val y = 4
                drawAtColumns(canvas, text, columns, y)
            }
            return canvas.copyOut()
        }

        /** Right-aligns [text] on the fixed digit columns (units at the last column). */
        private fun drawAtColumns(canvas: MatrixCanvas, text: String, columns: List<Int>, y: Int) {
            val start = columns.size - text.length
            text.forEachIndexed { i, ch ->
                Font3x5.draw(canvas, ch, columns[start + i], y, 4095)
            }
        }
    }
}
