package space.linuxct.glyphmatrixtoycompat.screens

import space.linuxct.glyphmatrixtoycompat.core.Events
import space.linuxct.glyphmatrixtoycompat.core.GlyphScreen
import space.linuxct.glyphmatrixtoycompat.core.ScreenContext
import space.linuxct.glyphmatrixtoycompat.matrix.Anim
import space.linuxct.glyphmatrixtoycompat.matrix.Font3x5
import space.linuxct.glyphmatrixtoycompat.matrix.MatrixCanvas

/**
 * Rock Paper Scissors: the matrix throws, you throw with your actual hand.
 * There is no opponent and no scoring — the toy is the hand.
 *
 * Press once and the sequence runs: a "ready" banner, a bobbing 3-2-1 countdown,
 * then the throw, held indefinitely until the next press restarts it.
 *
 * The **banner** is a solid lit band with a word knocked out dark, the token
 * peeking above it and a dithered bar below. [Font3x5] has no R and no Y, so
 * "READY" is unspellable and rendering '?' placeholders would be worse than
 * useless; the band spells **"SET"** instead (S, E and T are all in the font,
 * and it fits 13 columns), which reads as the same beat of "ready, set, throw".
 *
 * **The three throws are abstract symbols, not hands.** Hand silhouettes were
 * tried first and do not survive this panel: at 13x13 a fist and an open hand
 * are both a roundish blob facing right and cannot be told apart, and a
 * scissors hand reads as noise. Letters are no escape hatch either — [Font3x5]
 * has no R. So each throw is a shape chosen to differ from the other two on
 * *two* axes at once, round-vs-straight and solid-vs-hollow, which is what
 * makes them readable at a glance with almost no pixels to spend:
 *
 *  - **rock** — a solid disc: round, filled, and by far the heaviest frame;
 *  - **paper** — a hollow square outline: straight-edged and empty inside;
 *  - **scissors** — a clean X of two crossed diagonals: sparse, and the only
 *    shape with no horizontal or vertical edge at all.
 *
 * All three shapes are OUR OWN DESIGN; none of them reproduces the original
 * toy, and they must not be taken for one. (Of the hand sprites they replace,
 * only the scissors hand had ever been checked against real footage — the only
 * captured play resolved to scissors — so no fidelity that existed is lost.)
 */
class RpsScreen : GlyphScreen {
    override val id = "rps"
    override val interactive = true

    private enum class Phase { IDLE, PLAYING, REVEAL }

    private var ctx: ScreenContext? = null
    private var phase = Phase.IDLE
    private var startedAt = 0L
    private var thrown = ROCK

    override fun onActivate(ctx: ScreenContext) {
        this.ctx = ctx
        phase = Phase.IDLE
        ctx.pushFrame(renderIdle(ctx.size))
    }

    override fun onDeactivate() {
        ctx = null
    }

    override fun onEvent(event: String) {
        if (event != Events.CHANGE && event != Events.SHAKE) return
        val c = ctx ?: return
        thrown = c.ports.random.nextInt(THROWS)
        startedAt = c.ports.clock.nowMillis()
        phase = Phase.PLAYING
        c.scheduler.setTicker(TICK_MS) { tick() }
    }

    private fun tick() {
        val c = ctx ?: return
        if (phase != Phase.PLAYING) {
            c.scheduler.clearTicker()
            return
        }
        val elapsed = c.ports.clock.nowMillis() - startedAt
        if (elapsed < BANNER_MS) {
            c.pushFrame(renderBanner(c.size))
            return
        }
        val intoCount = elapsed - BANNER_MS
        val step = (intoCount / COUNT_MS).toInt()
        if (step < 3) {
            c.pushFrame(renderCountdown(c.size, 3 - step, (intoCount / BOB_MS).toInt()))
            return
        }
        // Reveal holds forever: one frame, no ticker.
        phase = Phase.REVEAL
        c.scheduler.clearTicker()
        c.pushFrame(renderThrow(c.size, thrown))
    }

    companion object {
        const val TICK_MS = 70L

        /** Banner hold, then one beat per countdown numeral (700 + 3 x 700 ms). */
        const val BANNER_MS = 700L
        const val COUNT_MS = 700L

        /** Bob (the "shake") advances a step this often. */
        const val BOB_MS = 140L

        const val ROCK = 0
        const val PAPER = 1
        const val SCISSORS = 2
        const val THROWS = 3

        /** Total sequence length before the reveal. */
        val SEQUENCE_MS: Long = BANNER_MS + 3 * COUNT_MS

        private fun unit(size: Int) = if (size >= 25) 2 else 1

        // ---------- the three symbols ----------

        /** Centre of the matrix in cell coordinates: 6.0 at 13, 12.0 at 25. */
        private fun mid(size: Int) = (size - 1) / 2f

        /**
         * Every symbol is inscribed in the same box: the whole matrix less a
         * one-cell dark margin all round. That is as large as a shape can be
         * drawn and still read as a shape rather than as a lit edge.
         */
        private const val INSET = 1

        /** Disc radius that lands on that box: 5.5 at 13, 11.5 at 25. */
        private fun discRadius(size: Int) = size / 2f - INSET

        /**
         * ROCK — a solid disc. [MatrixCanvas.discSoft] rather than
         * [MatrixCanvas.fillCircle]: at these radii the integer circle leaves a
         * single-cell spike at each of the four compass points, which reads as
         * a lump, while the soft rim reads as a genuine curve.
         */
        private fun drawRock(canvas: MatrixCanvas, v: Int) {
            val c = mid(canvas.size)
            canvas.discSoft(c, c, discRadius(canvas.size), v)
        }

        /**
         * PAPER — a hollow square outline, stroke [unit] cells thick (1 at 13,
         * 2 at 25). Straight where the disc is round and empty where the disc
         * is full, so it cannot be mistaken for rock on either axis.
         */
        private fun drawPaper(canvas: MatrixCanvas, v: Int) {
            val size = canvas.size
            for (d in 0 until unit(size)) {
                val a = INSET + d
                canvas.rect(a, a, size - 2 * a, size - 2 * a, v)
            }
        }

        /**
         * SCISSORS — a clean X: the two diagonals of the same box, each drawn
         * as parallel strokes either side of the true diagonal (1 cell wide at
         * 13, 3 at 25). The only symbol with no orthogonal edge, and the
         * sparsest of the three.
         */
        private fun drawScissors(canvas: MatrixCanvas, v: Int) {
            val a = INSET
            val b = canvas.size - 1 - a
            for (d in 0 until unit(canvas.size)) {
                canvas.line(a + d, a, b, b - d, v) // "\" shifted right
                canvas.line(a, a + d, b - d, b, v) // "\" shifted down
                canvas.line(b - d, a, a, b - d, v) // "/" shifted left
                canvas.line(b, a + d, a + d, b, v) // "/" shifted down
            }
        }

        /**
         * The small disc the banner and the countdown share — the same round
         * token as rock, shrunk so it can sit beside a numeral or half-sink
         * behind the band (7 cells across at 13, 13 at 25).
         */
        private fun tokenRadius(size: Int) = if (size >= 25) 6f else 3f

        // ---------- renderers ----------

        /** The three throws; [throwId] is [ROCK], [PAPER] or [SCISSORS]. */
        fun renderThrow(size: Int, throwId: Int): IntArray {
            val canvas = MatrixCanvas(size)
            when (throwId) {
                PAPER -> drawPaper(canvas, SYMBOL)
                SCISSORS -> drawScissors(canvas, SYMBOL)
                else -> drawRock(canvas, SYMBOL) // rock, and the safe fallback
            }
            return canvas.copyOut()
        }

        /**
         * Idle: the rock disc, centred and still — the same frame the ROCK
         * reveal shows, exactly as the old idle fist was the old rock. One
         * frame, no ticker.
         */
        fun renderIdle(size: Int): IntArray = renderThrow(size, ROCK)

        /**
         * The "ready" banner: the round token peeking over a solid band with
         * "SET" knocked out dark, and a dithered bar under it. See the class
         * doc for why the word is "SET" and not "READY".
         */
        fun renderBanner(size: Int): IntArray {
            val canvas = MatrixCanvas(size)
            val big = size >= 25
            val bandTop = if (big) 12 else 3
            val bandH = if (big) 9 else 7
            val textY = if (big) 14 else 4
            val tokenCy = if (big) 12f else 3f
            val ditherTop = if (big) 22 else 11

            // Half the token rises above the band; the band swallows the rest.
            canvas.discSoft(mid(size), tokenCy, tokenRadius(size), SYMBOL)

            // Solid band, laid over whatever it covers (set, not light).
            for (y in bandTop until (bandTop + bandH).coerceAtMost(size)) {
                for (x in 0 until size) canvas.set(x, y, BAND)
            }
            // Inverse text: render the word to a scratch surface and punch those
            // cells back out to black. Font3x5 draws with max-blend, so it can
            // only ever add light — knocking out has to happen here.
            val scratch = MatrixCanvas(size)
            Font3x5.drawStringCentered(scratch, BANNER_WORD, textY, MAXV)
            for (y in 0 until size) for (x in 0 until size) {
                if (scratch.get(x, y) > 0) canvas.set(x, y, 0)
            }
            // Dithered bar below the band.
            for (y in ditherTop until (ditherTop + 2).coerceAtMost(size)) {
                for (x in 0 until size) if ((x + y) % 2 == 0) canvas.set(x, y, DITHER)
            }
            return canvas.copyOut()
        }

        /**
         * Countdown frame for numeral [n] (3, 2 or 1): the round token sits
         * left of centre with the small numeral to its right and bobs
         * vertically ([bobStep] advances the shake), over a 2-row dithered
         * shadow patch that narrows as the token rises.
         */
        fun renderCountdown(size: Int, n: Int, bobStep: Int): IntArray {
            val canvas = MatrixCanvas(size)
            val u = unit(size)
            val big = size >= 25
            val bob = (Anim.pingPong(bobStep, 3) - 1) * u
            val tokenCx = if (big) 8f else 4f
            val tokenCy = (if (big) 10 else 5) + bob
            canvas.discSoft(tokenCx, tokenCy.toFloat(), tokenRadius(size), SYMBOL)

            // Flush right: on 13 columns that is the only way to leave a dark
            // gutter between the token and the numeral.
            val digitX = size - Font3x5.width('0')
            Font3x5.draw(canvas, ('0' + n), digitX, if (big) 10 else 4, SYMBOL)

            // Shadow: fixed rows, shrinking with the token's height off it.
            val shadowTop = if (big) 19 else 10
            val lift = if (bob < 0) u else 0
            val x0 = (if (big) 2 else 0) + lift
            val x1 = (if (big) 15 else 8) - lift
            for (y in shadowTop until (shadowTop + 2 * u).coerceAtMost(size)) {
                for (x in x0..x1) if ((x + y) % 2 == 0) canvas.light(x, y, DITHER)
            }
            return canvas.copyOut()
        }

        private const val BANNER_WORD = "SET"

        /**
         * Ratios within a frame: symbol and band 100 %, dither 44 %. The
         * knocked-out word is true black, which is what makes the band read as
         * inverse text.
         */
        private const val SYMBOL = 4095
        private const val BAND = 4095
        private const val DITHER = 1800
        private const val MAXV = 4095
    }
}
