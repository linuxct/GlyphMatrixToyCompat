package space.linuxct.glyphmatrixtoycompat.screens

import space.linuxct.glyphmatrixtoycompat.core.GlyphScreen
import space.linuxct.glyphmatrixtoycompat.core.ScreenContext
import space.linuxct.glyphmatrixtoycompat.matrix.MatrixCanvas

/**
 * Eyes: two hollow eyes with wandering pupils and periodic blinks, 50 ms ticker.
 * Pupils drift toward a random target every 1.5-3.5 s; a 6-tick blink
 * (closing / closed / opening) runs every 2.5-5.5 s.
 *
 * Each eye is an *outline* only — a round rim with an unlit interior and a
 * single bright pupil, the way the 👀 emoji reads. Nothing is lit between the
 * rim and the pupil, so the middle of the eye stays dark.
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
         * Two levels only — there is no sclera fill any more. ScreenManager
         * max-normalises every frame, so only the *ratio* matters: the frame's
         * peak always ends up at the user's brightness setting.
         *
         *   PUPIL 4095 = 100 %  drawn with set(), so it stays crisp
         *   RIM   2600 =  63 %  the 1-cell outline and the blink lids
         *
         * The two land in different golden ASCII buckets ('#' / '+') so the
         * hierarchy is reviewable. A fully closed frame is all RIM, which
         * normalisation then lifts back to 100 % on the device.
         */
        const val PUPIL = 4095
        const val RIM = 2600

        /**
         * Eye radius in cells: r=2 gives a 5x5 squircle on the 13x13 matrix
         * (midpoint circle at r=2 is exactly a rounded square), r=4 gives a
         * proper 9x9 circle on 25x25.
         */
        private fun eyeRadius(size: Int) = if (size >= 25) 4 else 2

        /** Dark columns kept between the two eyes. */
        private fun eyeGap(size: Int) = if (size >= 25) 3 else 1

        /**
         * The eye outline as a (2r+1)^2 stencil, built with the canvas' own
         * midpoint circle so the shape is consistent with the rest of the app.
         * Lit cells are the rim; everything inside is hollow.
         */
        private fun eyeMask(r: Int): MatrixCanvas {
            val m = MatrixCanvas(2 * r + 1)
            m.circle(r, r, r, RIM)
            return m
        }

        /**
         * @param pupilX / [pupilY] eased wander offsets in -1..1 (eye-widths).
         * @param blinkPhase -1 = open, 0..5 = the blink steps.
         */
        fun renderFrame(size: Int, pupilX: Float, pupilY: Float, blinkPhase: Int): IntArray {
            val canvas = MatrixCanvas(size)
            val r = eyeRadius(size)
            val d = 2 * r + 1
            val gap = eyeGap(size)
            val leftX = (size - (2 * d + gap)) / 2
            val topY = (size - d) / 2
            val mask = eyeMask(r)

            // Lid travel, in rows off the top of the eye. The lower lid rises at
            // half that rate, so the aperture closes toward the eye's middle row
            // instead of sliding down the face.
            val cover = when (blinkPhase) {
                0, 4 -> d / 4
                1, 3 -> d / 2
                2 -> d // sentinel: fully closed
                else -> 0
            }
            // Pupil offset in cells; the 25x25 eye is twice as wide, so it looks
            // twice as far. Diagonal looks get pulled in until the pupil clears
            // the rim (see the shrink loop below).
            val step = if (size >= 25) 2 else 1
            var offX = Math.round(pupilX) * step
            var offY = Math.round(pupilY) * step
            val pr = if (size >= 25) 1 else 0 // pupil half-size: 3x3 vs a single cell
            while ((offX != 0 || offY != 0) && pupilHitsRim(mask, d, d / 2 + offX, d / 2 + offY, pr)) {
                offX -= Integer.signum(offX)
                offY -= Integer.signum(offY)
            }

            for (ex in intArrayOf(leftX, leftX + d + gap)) {
                drawEye(canvas, mask, ex, topY, d, cover, offX, offY, pr)
            }
            return canvas.copyOut()
        }

        private fun pupilHitsRim(mask: MatrixCanvas, d: Int, cx: Int, cy: Int, pr: Int): Boolean {
            for (yy in cy - pr..cy + pr) for (xx in cx - pr..cx + pr) {
                if (xx !in 0 until d || yy !in 0 until d) return true
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
            d: Int,
            cover: Int,
            offX: Int,
            offY: Int,
            pr: Int,
        ) {
            val firstRow = cover
            val lastRow = d - 1 - cover / 2
            if (firstRow >= lastRow) {
                lidLine(canvas, mask, ox, oy, d, d / 2)
                return
            }
            for (yy in firstRow..lastRow) for (xx in 0 until d) {
                if (mask.get(xx, yy) > 0) canvas.light(ox + xx, oy + yy, RIM)
            }
            if (cover > 0) {
                lidLine(canvas, mask, ox, oy, d, firstRow)
                lidLine(canvas, mask, ox, oy, d, lastRow)
            }

            // Pupil, kept strictly inside the lids and off the rim.
            val loY = firstRow + 1
            val hiY = lastRow - 1
            if (loY > hiY) return
            val cx = d / 2 + offX
            val cy = (d / 2 + offY).coerceIn(loY, hiY)
            for (yy in cy - pr..cy + pr) for (xx in cx - pr..cx + pr) {
                if (yy < loY || yy > hiY || xx !in 0 until d) continue
                if (mask.get(xx, yy) > 0) continue
                canvas.set(ox + xx, oy + yy, PUPIL)
            }
        }

        /** Fills the eye's full width at [row] — a lid resting on the rim. */
        private fun lidLine(canvas: MatrixCanvas, mask: MatrixCanvas, ox: Int, oy: Int, d: Int, row: Int) {
            var lo = -1
            var hi = -1
            for (xx in 0 until d) if (mask.get(xx, row) > 0) {
                if (lo < 0) lo = xx
                hi = xx
            }
            if (lo < 0) return
            for (xx in lo..hi) canvas.light(ox + xx, oy + row, RIM)
        }
    }
}
