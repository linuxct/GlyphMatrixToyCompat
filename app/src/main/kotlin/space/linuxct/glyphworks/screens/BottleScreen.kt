package space.linuxct.glyphworks.screens

import space.linuxct.glyphworks.core.Events
import space.linuxct.glyphworks.core.GlyphScreen
import space.linuxct.glyphworks.core.ScreenContext
import space.linuxct.glyphworks.matrix.MatrixCanvas
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Spin the Bottle: an upright 1-cell-outline bottle (cap, neck, shoulders, body,
 * with the lower half of the body dithered 50 % as liquid) that, on a press,
 * collapses into a procedural arrow which spins about the matrix centre and
 * comes to rest at a random angle, then celebrates with a pulsing diamond
 * checkerboard burst.
 *
 * Why two shapes. A 1-cell-wide outline of a 5x11 bottle has no legible
 * rasterisation at an arbitrary angle on a 13x13 grid — that is a resolution
 * limit, not a rounding bug, and at 37 deg the sprite fell apart into confetti.
 * So the sprite is used only where it rasterises perfectly and is seen most —
 * upright, at rest, before the first spin — and everything that involves an
 * angle is drawn procedurally instead: a Bresenham shaft plus a solid arrowhead
 * built from an inside-test in the rotated frame, both of which are exactly as
 * crisp at 37 deg as at 90 deg. See [drawPointer].
 *
 * The art is hand-authored here. Only the *timing* is modelled on the toy this
 * replaces: no wind-up, ~0.3 s of acceleration, ~1.3 s of fast spinning, then a
 * ratcheted deceleration of four discrete 17-23 deg steps whose dwell lengthens
 * (250/300/350/400 ms) — ~2.9 s and 4-5 revolutions in total.
 *
 * A spin starts from wherever the pointer currently rests (so a press never pops
 * it back upright); because the extra rotation past the whole revolutions is
 * uniform in 0..359, the resting angle is still uniformly random.
 */
class BottleScreen : GlyphScreen {
    override val id = "bottle"
    override val interactive = true

    private enum class Phase { REST, SPINNING, BURST }

    private var ctx: ScreenContext? = null
    private var phase = Phase.REST
    private var restAngle = 0f
    private var spinStartAngle = 0f
    private var spinDelta = 0
    private var spinStartedAt = 0L
    private var burstFrames = 0

    /** True while the bottle sprite is on screen, i.e. until the first spin. */
    private var showingSprite = true

    /** Remaining frames of the sprite-to-pointer dissolve; see [GHOST_V]. */
    private var ghostFrames = 0

    override fun onActivate(ctx: ScreenContext) {
        this.ctx = ctx
        phase = Phase.REST
        restAngle = 0f
        showingSprite = true
        ghostFrames = 0
        ctx.pushFrame(renderIdle(ctx.size))
    }

    override fun onDeactivate() {
        ctx = null
    }

    override fun onEvent(event: String) {
        if (event != Events.CHANGE && event != Events.SHAKE) return
        val c = ctx ?: return
        spinStartAngle = restAngle
        spinDelta = c.ports.random.nextInt(360)
        spinStartedAt = c.ports.clock.nowMillis()
        // Only the very first spin has a bottle to dissolve; afterwards the
        // resting frame is already the pointer, so there is nothing to hand over.
        ghostFrames = if (showingSprite) GHOST_V.size else 0
        showingSprite = false
        phase = Phase.SPINNING
        c.scheduler.setTicker(SPIN_TICK_MS) { tick() }
    }

    private fun tick() {
        val c = ctx ?: return
        when (phase) {
            Phase.SPINNING -> {
                val elapsed = c.ports.clock.nowMillis() - spinStartedAt
                val angle = spinStartAngle + spinAngleAt(elapsed, spinDelta)
                if (elapsed >= SPIN_MS) {
                    restAngle = norm(angle)
                    phase = Phase.BURST
                    burstFrames = 0
                    c.scheduler.setTicker(BURST_MS) { tick() }
                    return
                }
                val ghost = if (ghostFrames > 0) GHOST_V[GHOST_V.size - ghostFrames--] else 0
                c.pushFrame(renderSpin(c.size, angle, ghost))
            }
            Phase.BURST -> {
                if (burstFrames >= BURST_FRAMES) {
                    phase = Phase.REST
                    c.scheduler.clearTicker()
                    c.pushFrame(renderPointer(c.size, restAngle))
                    return
                }
                // Two alternating frames only, so a 230 ms ticker is nearly free
                // (ScreenManager drops byte-identical consecutive frames).
                c.pushFrame(renderResult(c.size, restAngle, burstFrames % 2 == 0))
                burstFrames++
            }
            Phase.REST -> c.scheduler.clearTicker()
        }
    }

    companion object {
        /** Frame interval while the bottle is turning. */
        const val SPIN_TICK_MS = 40L

        /** Half-period of the result burst (on / off). */
        const val BURST_MS = 230L

        /** Burst frames before settling on the plain resting bottle (~2.3 s). */
        const val BURST_FRAMES = 10

        // ---------- spin timing ----------

        const val EASE_MS = 300L
        const val FAST_MS = 1300L

        /** Ratcheted decel: shrinking steps with lengthening dwell. */
        val RATCHET_DEG = intArrayOf(23, 21, 19, 17)
        val RATCHET_DWELL_MS = longArrayOf(250, 300, 350, 400)

        /** Whole revolutions folded into every spin (4 + the random remainder). */
        const val REVS = 4

        /** Total spin duration: 300 + 1300 + 1300 = 2900 ms. */
        val SPIN_MS: Long = EASE_MS + FAST_MS + RATCHET_DWELL_MS.sum()

        private val RATCHET_TOTAL_DEG = RATCHET_DEG.sum()

        /** Degrees turned by a whole spin whose random remainder is [restDelta]. */
        fun spinTotalDeg(restDelta: Int): Float = 360f * REVS + restDelta

        /**
         * Degrees turned [elapsedMs] into a spin, relative to the starting
         * angle. Pure: quadratic (constant-acceleration) ease-in, constant fast
         * spin, then the ratchet's discrete holds. Reaches exactly
         * [spinTotalDeg] at [SPIN_MS] and stays there.
         */
        fun spinAngleAt(elapsedMs: Long, restDelta: Int): Float {
            val total = spinTotalDeg(restDelta)
            if (elapsedMs >= SPIN_MS) return total
            val t = elapsedMs.coerceAtLeast(0L)
            val easeS = EASE_MS / 1000f
            val fastS = FAST_MS / 1000f
            // The fast-phase rate absorbs the random remainder: the ease-in
            // covers w * easeS / 2 and the fast phase w * fastS.
            val w = (total - RATCHET_TOTAL_DEG) / (easeS / 2f + fastS)
            val easeDeg = w * easeS / 2f
            if (t < EASE_MS) {
                val u = t / EASE_MS.toFloat()
                return easeDeg * u * u
            }
            val fastDeg = easeDeg + w * ((t - EASE_MS) / 1000f)
            if (t < EASE_MS + FAST_MS) return fastDeg
            var cursor = EASE_MS + FAST_MS
            var deg = easeDeg + w * fastS
            for (i in RATCHET_DEG.indices) {
                deg += RATCHET_DEG[i]
                cursor += RATCHET_DWELL_MS[i]
                if (t < cursor) return deg
            }
            return total
        }

        private fun norm(deg: Float): Float {
            val m = deg % 360f
            return if (m < 0f) m + 360f else m
        }

        // ---------- art ----------

        /**
         * 13-wide x 23-row bottle for the 25x25 matrix: 3-cell cap (2 rows), a
         * neck of two verticals 4 cells apart (5 rows), flaring shoulders, then
         * an 11-cell-wide body with rounded bottom corners and a flat base.
         */
        private val BOTTLE_25 = listOf(
            ".....###.....",
            ".....###.....",
            "....#...#....",
            "....#...#....",
            "....#...#....",
            "....#...#....",
            "....#...#....",
            "...#.....#...",
            "..#.......#..",
            ".#.........#.",
            ".#.........#.",
            ".#.........#.",
            ".#.........#.",
            ".#.........#.",
            ".#.........#.",
            ".#.........#.",
            ".#.........#.",
            ".#.........#.",
            ".#.........#.",
            ".#.........#.",
            ".#.........#.",
            "..#.......#..",
            "...#######...",
        )

        /** The same bottle crushed to 7x13 for the 13x13 matrix. */
        private val BOTTLE_13 = listOf(
            "..###..",
            "..###..",
            "..#.#..",
            "..#.#..",
            "..#.#..",
            ".#...#.",
            "#.....#",
            "#.....#",
            "#.....#",
            "#.....#",
            "#.....#",
            ".#...#.",
            "..###..",
        )

        /** First sprite row whose interior is dithered liquid (~lower half of the body). */
        private const val LIQUID_ROW_25 = 16
        private const val LIQUID_ROW_13 = 9

        private fun art(size: Int) = if (size >= 25) BOTTLE_25 else BOTTLE_13

        private fun liquidRow(size: Int) = if (size >= 25) LIQUID_ROW_25 else LIQUID_ROW_13

        /**
         * Lit cells of the upright bottle as (x, y) pairs, centred so the
         * bottle's centre is the matrix centre. Built once per size: the outline
         * plus a 50 % checkerboard scanline-filled into the body below
         * [liquidRow].
         */
        private val cellCache = HashMap<Int, List<Pair<Int, Int>>>()

        fun bottleCells(size: Int): List<Pair<Int, Int>> = cellCache.getOrPut(size) {
            val rows = art(size)
            val dx = (size - rows[0].length) / 2
            val dy = (size - rows.size) / 2
            val cells = LinkedHashSet<Pair<Int, Int>>()
            rows.forEachIndexed { ry, row ->
                row.forEachIndexed { rx, ch -> if (ch == '#') cells += (dx + rx) to (dy + ry) }
            }
            for (ry in liquidRow(size) until rows.size) {
                val row = rows[ry]
                val left = row.indexOf('#')
                val right = row.lastIndexOf('#')
                if (left < 0 || right - left < 2) continue
                val y = dy + ry
                for (rx in left + 1 until right) {
                    val x = dx + rx
                    if ((x + y) % 2 == 0) cells += x to y
                }
            }
            cells.toList()
        }

        /**
         * The idle / attract frame: the upright bottle sprite, which is what
         * the toy shows before its first spin. Only ever drawn at 0 deg, where
         * the sprite rasterises exactly as authored.
         */
        fun renderIdle(size: Int): IntArray =
            drawBottle(MatrixCanvas(size), BOTTLE).copyOut()

        /**
         * The pointer aimed [angleDeg] clockwise from up. Pure function of
         * (size, angle) and, unlike a rotated sprite, legible at every angle.
         */
        fun renderPointer(size: Int, angleDeg: Float): IntArray =
            drawPointer(MatrixCanvas(size), angleDeg).copyOut()

        /**
         * A spinning frame: the pointer, plus the upright bottle sprite at
         * brightness [ghost] (0 = gone) for the first few frames of the very
         * first spin, so the sprite dissolves into the pointer instead of
         * snapping to it.
         */
        fun renderSpin(size: Int, angleDeg: Float, ghost: Int): IntArray {
            val canvas = MatrixCanvas(size)
            if (ghost > 0) drawBottle(canvas, ghost)
            return drawPointer(canvas, angleDeg).copyOut()
        }

        /**
         * Resting pointer inside the result flourish: a large diamond-shaped
         * 50 % checkerboard that pulses on and off around it. [burstOn] false is
         * the dark half of the pulse (pointer only).
         */
        fun renderResult(size: Int, angleDeg: Float, burstOn: Boolean): IntArray {
            val canvas = MatrixCanvas(size)
            if (burstOn) {
                val c = (size - 1) / 2
                val r = size * 3 / 4
                for (y in 0 until size) for (x in 0 until size) {
                    if (abs(x - c) + abs(y - c) <= r && (x + y) % 2 == 0) canvas.light(x, y, BURST)
                }
            }
            return drawPointer(canvas, angleDeg).copyOut()
        }

        /** Blits the upright sprite at brightness [v]. */
        private fun drawBottle(canvas: MatrixCanvas, v: Int): MatrixCanvas {
            bottleCells(canvas.size).forEach { (x, y) -> canvas.light(x, y, v) }
            return canvas
        }

        /**
         * The arrow. Three parts, none of which is a transformed sprite:
         *
         *  - a dim, short tail stub behind the centre, and
         *  - a bright 1-cell shaft from the centre out to the tip — both plain
         *    Bresenham rays, which stay connected and 1 cell wide at any angle;
         *  - a solid arrowhead, rasterised by testing every cell against the
         *    triangle in the *rotated* frame (`along`/`lateral` are the cell's
         *    coordinates along and across the pointing direction). Testing
         *    destination cells, rather than mapping source cells forward, is
         *    what keeps the head solid and symmetric at 37 deg: nothing can
         *    round onto a neighbour and leave a hole.
         *
         * Head and tail are told apart twice over — the head is 2.4x longer and
         * ends in a wedge 5 (13x13) or 7 (25x25) cells across, the tail is a
         * bare dim stub — so "points at Alice" can never read as "points at Bob".
         */
        private fun drawPointer(canvas: MatrixCanvas, angleDeg: Float): MatrixCanvas {
            val size = canvas.size
            val c = size / 2
            val big = size >= 25
            val tip = if (big) TIP_25 else TIP_13
            val depth = if (big) HEAD_DEPTH_25 else HEAD_DEPTH_13
            val tail = if (big) TAIL_LEN_25 else TAIL_LEN_13

            canvas.ray(c, c, angleDeg + 180f, tail, TAIL)
            canvas.ray(c, c, angleDeg, tip, POINTER)

            val rad = Math.toRadians(angleDeg.toDouble())
            val dx = sin(rad).toFloat()
            val dy = -cos(rad).toFloat()
            for (y in 0 until size) for (x in 0 until size) {
                val ox = (x - c).toFloat()
                val oy = (y - c).toFloat()
                val along = ox * dx + oy * dy
                if (along < tip - depth || along > tip + 0.5f) continue
                val lateral = abs(-ox * dy + oy * dx)
                // Half-width grows linearly back from the apex; the +0.5 is the
                // cell's own half-width, so a cell counts once its centre is
                // within half a cell of the triangle.
                if (lateral <= (tip - along) * HEAD_SLOPE + 0.5f) canvas.light(x, y, POINTER)
            }
            return canvas
        }

        // ---------- pointer geometry (cells from the matrix centre) ----------

        private const val TIP_13 = 5.2f
        private const val HEAD_DEPTH_13 = 2.6f
        private const val TAIL_LEN_13 = 2.2f

        private const val TIP_25 = 10.4f
        private const val HEAD_DEPTH_25 = 3.4f
        private const val TAIL_LEN_25 = 4.4f

        /** Arrowhead half-width per cell back from the apex. */
        private const val HEAD_SLOPE = 0.8f

        /**
         * Sprite brightness over the first 120 ms of the first spin. Ends above
         * zero and then cuts, which at 40 ms a frame reads as a dissolve rather
         * than a fade-out the eye can follow.
         */
        val GHOST_V = intArrayOf(2400, 1500, 800)

        /**
         * Ratios within a frame: bottle and pointer head 100 %, pointer tail
         * 44 %, burst checkerboard 34 %. The tail sits above the burst so the
         * flourish never swallows it.
         */
        private const val BOTTLE = 4095
        private const val POINTER = 4095
        private const val TAIL = 1800
        private const val BURST = 1400
    }
}
