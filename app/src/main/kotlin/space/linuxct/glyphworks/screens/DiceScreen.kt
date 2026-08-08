package space.linuxct.glyphworks.screens

import space.linuxct.glyphworks.core.Events
import space.linuxct.glyphworks.core.GlyphScreen
import space.linuxct.glyphworks.core.PrefKeys
import space.linuxct.glyphworks.core.ScreenContext
import space.linuxct.glyphworks.matrix.Font3x5
import space.linuxct.glyphworks.matrix.MAX_BRIGHTNESS
import space.linuxct.glyphworks.matrix.MatrixCanvas

/**
 * Dice: Glyph Touch (or shake) restarts an ~800 ms tumble animation, then a
 * uniformly random face is shown. D6 renders classic pip layouts; other dice
 * (D4/D8/D12/D20) render the rolled number inside a border.
 */
class DiceScreen : GlyphScreen {
    override val id = "dice"
    override val interactive = true

    private var ctx: ScreenContext? = null
    private var face = 6
    private var rollStartedAt = 0L

    private fun sides(c: ScreenContext): Int =
        c.prefs.getString(PrefKeys.SELECTED_DICE, PrefKeys.SELECTED_DICE_DEF)
            .removePrefix("D").toIntOrNull()?.coerceIn(2, 99) ?: 6

    override fun onActivate(ctx: ScreenContext) {
        this.ctx = ctx
        rollStartedAt = 0L
        face = face.coerceAtMost(sides(ctx))
        pushFace()
    }

    override fun onDeactivate() {
        ctx = null
    }

    override fun onEvent(event: String) {
        if (event == Events.CHANGE || event == Events.SHAKE) startRoll()
    }

    private fun startRoll() {
        val c = ctx ?: return
        rollStartedAt = c.ports.clock.nowMillis()
        c.scheduler.setTicker(33) { tickRoll() }
    }

    private fun tickRoll() {
        val c = ctx ?: return
        val elapsed = c.ports.clock.nowMillis() - rollStartedAt
        if (elapsed >= ROLL_MS) {
            face = c.ports.random.nextInt(sides(c)) + 1
            rollStartedAt = 0L
            c.scheduler.clearTicker()
            pushFace()
            return
        }
        // Tumble: scattered pips flickering at random grid spots.
        val canvas = MatrixCanvas(c.size)
        val cells = pipCenters(c.size)
        val count = 3 + c.ports.random.nextInt(4)
        repeat(count) { i ->
            val p = cells[c.ports.random.nextInt(cells.size)]
            val v = TUMBLE_MIN + c.ports.random.nextInt(MAX_BRIGHTNESS - TUMBLE_MIN + 1)
            // One pip per frame is always full brightness. Brightness is applied
            // by multiplying the frame, so with every pip randomised the frame's
            // peak — and with it the tumble's apparent brightness — flickered
            // frame to frame instead of pip to pip.
            drawPip(canvas, c.size, p, if (i == 0) MAX_BRIGHTNESS else v)
        }
        c.pushFrame(canvas.copyOut())
    }

    private fun pushFace() {
        val c = ctx ?: return
        c.pushFrame(renderFace(c.size, face, sides(c)))
    }

    companion object {
        const val ROLL_MS = 800L

        /** Dimmest a tumbling pip gets: 37 % of the full-brightness one. */
        private const val TUMBLE_MIN = 1500

        /** 3x3 grid of pip centers (13: 3/6/9, 25: 6/12/18). */
        private fun pipCenters(size: Int): List<Pair<Int, Int>> {
            val u = size / 4 // 3 on 13, 6 on 25
            val positions = listOf(u, 2 * u, 3 * u)
            return positions.flatMap { y -> positions.map { x -> x to y } }
        }

        private fun drawPip(canvas: MatrixCanvas, size: Int, center: Pair<Int, Int>, v: Int) {
            val (cx, cy) = center
            if (size >= 25) {
                canvas.fillRect(cx - 1, cy - 1, 3, 3, v)
            } else {
                canvas.fillRect(cx - 1, cy - 1, 2, 2, v)
            }
        }

        fun renderFace(size: Int, face: Int, sides: Int): IntArray {
            val canvas = MatrixCanvas(size)
            if (sides == 6) {
                val u = size / 4
                val l = u
                val m = 2 * u
                val r = 3 * u
                val pips: List<Pair<Int, Int>> = when (face) {
                    1 -> listOf(m to m)
                    2 -> listOf(l to l, r to r)
                    3 -> listOf(l to l, m to m, r to r)
                    4 -> listOf(l to l, r to l, l to r, r to r)
                    5 -> listOf(l to l, r to l, m to m, l to r, r to r)
                    else -> listOf(l to l, r to l, l to m, r to m, l to r, r to r)
                }
                pips.forEach { drawPip(canvas, size, it, 4095) }
            } else {
                canvas.rect(0, 0, size, size, 700)
                Font3x5.drawStringCentered(canvas, face.toString(), size / 2 - 2, 4095)
            }
            return canvas.copyOut()
        }
    }
}
