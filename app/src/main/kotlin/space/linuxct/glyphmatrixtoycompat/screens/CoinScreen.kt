package space.linuxct.glyphmatrixtoycompat.screens

import space.linuxct.glyphmatrixtoycompat.core.Events
import space.linuxct.glyphmatrixtoycompat.core.GlyphScreen
import space.linuxct.glyphmatrixtoycompat.core.PrefKeys
import space.linuxct.glyphmatrixtoycompat.core.ScreenContext
import space.linuxct.glyphmatrixtoycompat.matrix.Font3x5
import space.linuxct.glyphmatrixtoycompat.matrix.MatrixCanvas
import kotlin.math.abs
import kotlin.math.cos

/**
 * Coin Flip: Glyph Touch (or shake) restarts a ~1 s flip; the coin is drawn
 * as an ellipse whose height follows |cos| through three rotations (edge-on
 * at the zero crossings), then lands 50/50 on heads or tails.
 *
 * Two result designs (pref [PrefKeys.COIN_DESIGN]): 0 = ring + H/T letters,
 * 1 = ring + hand-authored coin art (monarch's profile / euro-style numeral).
 * The flip animation is shared; only the landed frame differs.
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
        c.pushFrame(renderResult(c.size, heads, c.prefs.getInt(PrefKeys.COIN_DESIGN, PrefKeys.COIN_DESIGN_DEF)))
    }

    companion object {
        const val FLIP_MS = 1000L
        const val ROTATIONS = 3

        /** Result designs, matching the order of the settings dialog's choices. */
        const val DESIGN_LETTERS = 0
        const val DESIGN_ART = 1

        /**
         * Monarch's profile facing right, 13x13. At this size a detailed
         * portrait is mud, so this is the minimum that still reads as a head:
         * a dome, a nose spike on the middle row, a mouth notch under it and a
         * chin bump, over a narrow neck. Seven rows tall and at most seven wide
         * — the largest sprite that keeps a full dark cell between the art and
         * the ring (inner radius 5.1) everywhere.
         */
        private val HEADS_13 = listOf(
            ".....###.....", // crown
            "....#####....", // forehead
            "....#####....", // brow / eye level
            "....######...", // nose
            "....####.....", // mouth, recessed behind the nose
            "....#####....", // chin
            ".....###.....", // neck
        )

        /**
         * Euro-style "1", 13x13: an angled flag off the apex, an upright 2-cell
         * stem and a foot bar wider than the stem — the character that tells it
         * apart from Font3x5's plain "1", at a size that clears the ring.
         */
        private val TAILS_13 = listOf(
            "......##.....", // apex
            ".....###.....", // flag, angling down-left
            "....####.....",
            "......##.....", // stem
            "......##.....",
            "......##.....",
            ".....####....", // foot bar
        )

        /**
         * Monarch's profile facing right, 25x25 (see [HEADS_13]) with an eye
         * notch and a neck. Thirteen rows, mirroring the 13x13 proportions
         * rather than filling the coin.
         */
        private val HEADS_25 = listOf(
            "..........#####..........", // crown
            "........#########........",
            ".......###########.......",
            ".......###########.......", // forehead
            ".......#########.#.......", // brow, with the eye notch
            ".......##########........", // eye socket, recessed
            ".......############......", // nose
            ".......##########........", // under the nose
            ".......#########.........", // mouth, recessed
            ".......###########.......", // chin
            "........#########........",
            ".........#######.........", // jaw
            ".........#######.........", // neck
        )

        /** Euro-style "1", 25x25 (see [TAILS_13]): 3-cell stem, 2-row foot bar. */
        private val TAILS_25 = listOf(
            "...........###...........", // apex
            "..........####...........", // flag
            ".........#####...........",
            "........######...........",
            "...........###...........", // stem
            "...........###...........",
            "...........###...........",
            "...........###...........",
            "...........###...........",
            "...........###...........",
            "...........###...........",
            ".........#######.........", // foot bar
            ".........#######.........",
        )

        fun renderResult(size: Int, heads: Boolean, design: Int = DESIGN_LETTERS): IntArray {
            val canvas = MatrixCanvas(size)
            val center = (size - 1) / 2f
            canvas.ring(center, center, size / 2f - 1.4f, size / 2f - 0.4f, 2200)
            if (design == DESIGN_ART) {
                val big = size >= 25
                val art = when {
                    heads && big -> HEADS_25
                    heads -> HEADS_13
                    big -> TAILS_25
                    else -> TAILS_13
                }
                // Every sprite is authored an even number of rows short of the
                // matrix, so centring is just the row remainder.
                canvas.blit(art, 0, (size - art.size) / 2, 4095)
            } else {
                val letterY = size / 2 - 2
                Font3x5.drawStringCentered(canvas, if (heads) "H" else "T", letterY, 4095)
            }
            return canvas.copyOut()
        }
    }
}
