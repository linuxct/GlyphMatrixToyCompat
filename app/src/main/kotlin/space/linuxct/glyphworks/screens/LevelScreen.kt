package space.linuxct.glyphworks.screens

import space.linuxct.glyphworks.core.GlyphScreen
import space.linuxct.glyphworks.core.InclinePort
import space.linuxct.glyphworks.core.ScreenContext
import space.linuxct.glyphworks.matrix.Font3x5
import space.linuxct.glyphworks.matrix.MatrixCanvas
import kotlin.math.hypot

/**
 * Level: a bubble level. The ball centres inside a target ring when the device
 * lies flat on its back and rolls toward the low edge as the device tilts,
 * reaching the matrix edge at [MAX_TILT_DEG]. Within [TOLERANCE_DEG] of flat
 * the target ring lights up to full brightness AND the ball is pinned dead
 * centre — one tolerance, so the two halves of the "it's level" call always
 * agree.
 *
 * Ticks at 66 ms: the inclination sensor unregisters itself after ~5 s without
 * a poll, so the toy has to keep asking to keep the readings alive.
 */
class LevelScreen : GlyphScreen {
    override val id = "level"
    override val interactive = false

    private var ctx: ScreenContext? = null

    override fun onActivate(ctx: ScreenContext) {
        this.ctx = ctx
        ctx.scheduler.setTicker(66) { tick() }
    }

    override fun onDeactivate() {
        ctx = null
    }

    private fun tick() {
        val c = ctx ?: return
        c.pushFrame(renderFrame(c.size, c.ports.incline.pitchDegrees(), c.ports.incline.rollDegrees()))
    }

    companion object {
        /** Inclination that pins the ball against the matrix edge. */
        const val MAX_TILT_DEG = 30f

        /**
         * Combined pitch/roll magnitude still counted as flat. Serves BOTH the
         * lit ring ([isLevel]) and the ball's dead zone, so the two can never
         * contradict each other.
         *
         * NOT the fix for "it never centres" — that was
         * [space.linuxct.glyphworks.core.InclineMath] reading 180 deg
         * on each axis with the phone face down (the only way the matrix can be
         * looked at), which pinned the ball at a saturated diagonal offset no
         * dead zone could ever reach. With the derivation corrected this
         * tolerance became reachable at all; it is 4 deg only so a slightly
         * imperfect desk still reads level. Do not widen it further to "fix"
         * centring problems — this is a level, and a wide dead zone makes it
         * useless.
         */
        const val TOLERANCE_DEG = 4f

        /**
         * [pitchDeg] / [rollDeg] follow [InclinePort]'s convention: positive
         * means that edge of the device is the LOW one. Either being null (no
         * sensor, or no reading yet) draws the frame plus a "?", never a ball at
         * a made-up angle.
         */
        fun renderFrame(size: Int, pitchDeg: Float?, rollDeg: Float?): IntArray {
            val canvas = MatrixCanvas(size)
            val c = (size - 1) / 2f
            val ci = size / 2

            // Faint edge ticks at the four mid-edges: a fixed frame of
            // reference, so a ball parked at an edge still reads as "off".
            canvas.light(ci, 0, EDGE)
            canvas.light(ci, size - 1, EDGE)
            canvas.light(0, ci, EDGE)
            canvas.light(size - 1, ci, EDGE)

            if (pitchDeg == null || rollDeg == null) {
                canvas.ring(c, c, ringInner(size), ringOuter(size), TARGET_IDLE)
                // Brighter than the idle ring, or the two mush together into an
                // unreadable blob at 13 columns.
                Font3x5.drawStringCentered(canvas, "?", size / 2 - 2, NO_READING)
                return canvas.copyOut()
            }

            val target = if (isLevel(pitchDeg, rollDeg)) TARGET_LEVEL else TARGET_IDLE
            canvas.ring(c, c, ringInner(size), ringOuter(size), target)

            // Positive roll = right edge low, so the ball rolls to +x. Positive
            // pitch = top edge low, and matrix rows grow downward, so it rolls
            // to -y.
            //
            // Dead zone, and it is the whole point of this screen working: a
            // real inclination sensor never reads exactly 0, so mapping the raw
            // angle puts the ball a fraction of a cell off centre and
            // [MatrixCanvas.discSoft] renders that as a lopsided, anti-aliased
            // blob — while the ring, which asks [isLevel], has already lit up.
            // Ball and ring disagreeing is what read as "it never centres".
            // So: inside the SAME [TOLERANCE_DEG] the ring uses, the ball is
            // pinned to the exact centre cell (a symmetric disc on one cell);
            // outside it, the tolerance is subtracted from the tilt magnitude so
            // the ball eases out of the dead zone instead of jumping a whole
            // tolerance's worth the moment it leaves. The remaining travel is
            // spread over TOLERANCE_DEG..[MAX_TILT_DEG], so the ball still pins
            // to the edge at exactly MAX_TILT_DEG as documented above.
            //
            // The magnitude is the combined hypot (not per-axis), again to match
            // isLevel — and as a bonus the deflection can no longer exceed
            // `reach` diagonally, which per-axis clamping allowed.
            val r = ballRadius(size)
            val reach = c - r - 0.5f
            val mag = hypot(pitchDeg.toDouble(), rollDeg.toDouble()).toFloat()
            // Cells of deflection per degree of tilt; 0 inside the dead zone.
            val perDeg = if (mag <= TOLERANCE_DEG) {
                0f
            } else {
                clampUnit((mag - TOLERANCE_DEG) / (MAX_TILT_DEG - TOLERANCE_DEG)) * reach / mag
            }
            val dx = rollDeg * perDeg
            val dy = -pitchDeg * perDeg
            canvas.discSoft(c + dx, c + dy, r, BALL)
            return canvas.copyOut()
        }

        private fun clampUnit(v: Float) = v.coerceIn(-1f, 1f)

        private fun ballRadius(size: Int) = if (size >= 25) 3.2f else 1.8f

        /** Target ring sits just outside the ball, so "level" reads as a snug fit. */
        private fun ringInner(size: Int) = if (size >= 25) 4.6f else 2.6f

        private fun ringOuter(size: Int) = if (size >= 25) 5.4f else 3.2f

        /**
         * Ball 100 %, lit target ring 100 %, the no-reading "?" 100 %, idle
         * target ring 22 %, edge ticks 12 %.
         *
         * Every state needs one element at 100 %, because brightness reaches the
         * panel as a multiplication of the finished frame: whatever a state's
         * brightest element is, that is as bright as the state can ever get.
         * [NO_READING] is at full scale for that reason — in the state that draws
         * it there is nothing else, so the "?" IS the content — and it is still
         * far brighter than the [TARGET_IDLE] ring it must not mush into.
         */
        private const val BALL = 4095
        private const val TARGET_LEVEL = 4095
        private const val NO_READING = 4095
        private const val TARGET_IDLE = 900
        private const val EDGE = 500

        /** Inside the flat tolerance? The single source of the "level" call. */
        fun isLevel(pitchDeg: Float, rollDeg: Float): Boolean =
            hypot(pitchDeg.toDouble(), rollDeg.toDouble()) <= TOLERANCE_DEG
    }
}
