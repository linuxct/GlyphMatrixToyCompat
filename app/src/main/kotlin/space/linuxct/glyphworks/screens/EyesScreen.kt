package space.linuxct.glyphworks.screens

import space.linuxct.glyphworks.core.GlyphScreen
import space.linuxct.glyphworks.core.ScreenContext
import space.linuxct.glyphworks.matrix.MatrixCanvas

/**
 * Eyes: two hollow eyes with wandering pupils and periodic blinks, 50 ms ticker.
 * Pupils drift toward a random target every 1.5-3.5 s; a 6-tick blink
 * (closing / closed / opening) runs every 2.5-5.5 s.
 *
 * Each eye is an *outline* only — a rim with an unlit interior and a single
 * bright pupil, the way the 👀 emoji reads. Nothing is lit between the rim and
 * the pupil, so the middle of the eye stays dark. The rim is taller than it is
 * wide (see [eyeStencil]): a circular eye reads as a disc, not an eye.
 */
class EyesScreen : GlyphScreen {
    override val id = "eyes"
    override val interactive = false

    private var ctx: ScreenContext? = null

    private var pupilX = 0f
    private var pupilY = 0f
    private var targetX = 0f
    private var targetY = 0f
    private var nextWanderAt = 0L
    private var nextBlinkAt = 0L
    private var blinkPhase = -1 // -1 = open; 0..5 = blink animation step

    override fun onActivate(ctx: ScreenContext) {
        this.ctx = ctx
        val now = ctx.ports.clock.nowMillis()
        nextWanderAt = now + 1500
        nextBlinkAt = now + 2500
        blinkPhase = -1
        pupilX = 0f; pupilY = 0f; targetX = 0f; targetY = 0f
        ctx.scheduler.setTicker(50) { tick() }
    }

    override fun onDeactivate() {
        ctx = null
    }

    private fun tick() {
        val c = ctx ?: return
        val now = c.ports.clock.nowMillis()

        if (now >= nextWanderAt) {
            targetX = (c.ports.random.nextInt(3) - 1).toFloat()
            targetY = (c.ports.random.nextInt(3) - 1).toFloat()
            nextWanderAt = now + 1500 + c.ports.random.nextInt(2000)
        }
        pupilX += (targetX - pupilX) * 0.25f
        pupilY += (targetY - pupilY) * 0.25f

        if (blinkPhase < 0 && now >= nextBlinkAt) blinkPhase = 0

        c.pushFrame(renderFrame(c.size, pupilX, pupilY, blinkPhase))

        if (blinkPhase >= 0) {
            blinkPhase++
            if (blinkPhase > 5) {
                blinkPhase = -1
                nextBlinkAt = now + 2500 + c.ports.random.nextInt(3000)
            }
        }
    }

    companion object {
        /**
         * Two levels only — there is no sclera fill any more.
         *
         *   PUPIL 4095 = 100 %  drawn with set(), so it stays crisp
         *   RIM   2600 =  63 %  the 1-cell outline and the blink lids
         *
         * The two land in different golden ASCII buckets ('#' / '+') so the
         * hierarchy is reviewable. Brightness is applied by multiplying the
         * finished frame, so these are absolute levels and a fully closed frame —
         * all RIM, with the pupil covered — is genuinely 63 % as bright as an
         * open eye. That is the right way round for a blink; lids that outshone
         * the pupil would not be.
         */
        const val PUPIL = 4095
        const val RIM = 2600

        /** Dark columns kept between the two eyes. */
        private fun eyeGap(size: Int) = if (size >= 25) 3 else 1

        /**
         * The eye outline, hand-authored per matrix size.
         *
         * These used to be [MatrixCanvas.circle] stencils — 5x5 at 13 and 9x9 at
         * 25 — i.e. perfectly circular, which read as a pair of discs rather
         * than eyes. The 👀 emoji's eyes are vertically ELONGATED, so both are
         * now taller than they are wide: 5x7 at 13 (one row added top and
         * bottom) and 9x13 at 25 (two each, keeping the aspect ratio).
         *
         * Written out by hand rather than rasterised: at 5 and 9 cells wide a
         * generic ellipse routine produces lumpy, asymmetric caps, and there is
         * no ellipse primitive on [MatrixCanvas] to begin with. The shapes below
         * are symmetric top-to-bottom and left-to-right by inspection, and every
         * step of the contour is 8-connected, so the outline is closed and the
         * interior is guaranteed hollow.
         *
         * Rows must all be the same length; the height gets to be anything.
         */
        private fun eyeStencil(size: Int): List<String> = if (size >= 25) EYE_25 else EYE_13

        private val EYE_13 = listOf(
            " ### ",
            "#   #",
            "#   #",
            "#   #",
            "#   #",
            "#   #",
            " ### ",
        )

        private val EYE_25 = listOf(
            "   ###   ",
            "  #   #  ",
            " #     # ",
            " #     # ",
            "#       #",
            "#       #",
            "#       #",
            "#       #",
            "#       #",
            " #     # ",
            " #     # ",
            "  #   #  ",
            "   ###   ",
        )

        /**
         * The stencil as a lookup surface. The canvas is square at the eye's
         * HEIGHT (the larger dimension), so the unused columns past the eye's
         * width stay dark and are never read — every loop below runs `0 until w`.
         */
        private fun eyeMask(stencil: List<String>): MatrixCanvas {
            val m = MatrixCanvas(stencil.size)
            m.blit(stencil, 0, 0, RIM)
            return m
        }

        /**
         * @param pupilX / [pupilY] eased wander offsets in -1..1 (eye-widths).
         * @param blinkPhase -1 = open, 0..5 = the blink steps.
         */
        fun renderFrame(size: Int, pupilX: Float, pupilY: Float, blinkPhase: Int): IntArray {
            val canvas = MatrixCanvas(size)
            val stencil = eyeStencil(size)
            val w = stencil[0].length
            val h = stencil.size
            val gap = eyeGap(size)
            val leftX = (size - (2 * w + gap)) / 2
            val topY = (size - h) / 2
            val mask = eyeMask(stencil)

            // Lid travel, in rows off the top of the eye. The lower lid rises at
            // half that rate, so the aperture closes toward the eye's middle row
            // instead of sliding down the face. Measured against the eye's
            // HEIGHT, so the taller eye squints by the same proportion.
            val cover = when (blinkPhase) {
                0, 4 -> h / 4
                1, 3 -> h / 2
                2 -> h // sentinel: fully closed
                else -> 0
            }
            // Pupil offset in cells; the 25x25 eye is twice as wide, so it looks
            // twice as far. Diagonal looks get pulled in until the pupil clears
            // the rim (see the shrink loop below). The taller eye gives the
            // pupil real vertical room, so a look up or down is now visible
            // instead of a dot parked in the middle.
            val step = if (size >= 25) 2 else 1
            var offX = Math.round(pupilX) * step
            var offY = Math.round(pupilY) * step
            val pr = if (size >= 25) 1 else 0 // pupil half-size: 3x3 vs a single cell
            while ((offX != 0 || offY != 0) && pupilHitsRim(mask, w, h, w / 2 + offX, h / 2 + offY, pr)) {
                offX -= Integer.signum(offX)
                offY -= Integer.signum(offY)
            }

            for (ex in intArrayOf(leftX, leftX + w + gap)) {
                drawEye(canvas, mask, ex, topY, w, h, cover, offX, offY, pr)
            }
            return canvas.copyOut()
        }

        private fun pupilHitsRim(mask: MatrixCanvas, w: Int, h: Int, cx: Int, cy: Int, pr: Int): Boolean {
            for (yy in cy - pr..cy + pr) for (xx in cx - pr..cx + pr) {
                if (xx !in 0 until w || yy !in 0 until h) return true
                if (mask.get(xx, yy) > 0) return true
            }
            return false
        }

        /**
         * Draws one eye at ([ox],[oy]) with [cover] rows of lid.
         *
         * The lid never erases rows: it *clips* the outline to the still-visible
         * band and caps that band with a lid line spanning exactly the eye's
         * width at that row, so a half-closed eye is the bottom of the rim under
         * a straight lid, and the fully closed eye is the single widest chord.
         */
        private fun drawEye(
            canvas: MatrixCanvas,
            mask: MatrixCanvas,
            ox: Int,
            oy: Int,
            w: Int,
            h: Int,
            cover: Int,
            offX: Int,
            offY: Int,
            pr: Int,
        ) {
            val firstRow = cover
            val lastRow = h - 1 - cover / 2
            if (firstRow >= lastRow) {
                lidLine(canvas, mask, ox, oy, w, h / 2)
                return
            }
            for (yy in firstRow..lastRow) for (xx in 0 until w) {
                if (mask.get(xx, yy) > 0) canvas.light(ox + xx, oy + yy, RIM)
            }
            if (cover > 0) {
                lidLine(canvas, mask, ox, oy, w, firstRow)
                lidLine(canvas, mask, ox, oy, w, lastRow)
            }

            // Pupil, kept strictly inside the lids and off the rim.
            val loY = firstRow + 1
            val hiY = lastRow - 1
            if (loY > hiY) return
            val cx = w / 2 + offX
            val cy = (h / 2 + offY).coerceIn(loY, hiY)
            for (yy in cy - pr..cy + pr) for (xx in cx - pr..cx + pr) {
                if (yy < loY || yy > hiY || xx !in 0 until w) continue
                if (mask.get(xx, yy) > 0) continue
                canvas.set(ox + xx, oy + yy, PUPIL)
            }
        }

        /** Fills the eye's full width at [row] — a lid resting on the rim. */
        private fun lidLine(canvas: MatrixCanvas, mask: MatrixCanvas, ox: Int, oy: Int, w: Int, row: Int) {
            var lo = -1
            var hi = -1
            for (xx in 0 until w) if (mask.get(xx, row) > 0) {
                if (lo < 0) lo = xx
                hi = xx
            }
            if (lo < 0) return
            for (xx in lo..hi) canvas.light(ox + xx, oy + row, RIM)
        }
    }
}
