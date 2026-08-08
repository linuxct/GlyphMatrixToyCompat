package space.linuxct.glyphworks.screens

import space.linuxct.glyphworks.core.Events
import space.linuxct.glyphworks.core.GlyphScreen
import space.linuxct.glyphworks.core.RandomPort
import space.linuxct.glyphworks.core.ScreenContext
import space.linuxct.glyphworks.matrix.Font3x5
import space.linuxct.glyphworks.matrix.MatrixCanvas
import kotlin.math.roundToInt

/**
 * Dino: an endless runner. Our own design — the character, the cacti and the
 * physics are hand-authored here, nothing is copied from anywhere.
 *
 * Three states, all driven by the single [Events.CHANGE] press:
 *  - IDLE / attract: the character stands on the ground, waiting. One frame, no ticker.
 *  - RUNNING: the character runs in place at a fixed column while the world
 *    scrolls right-to-left underneath it (the only way an endless runner fits
 *    13 columns); a press jumps.
 *  - OVER: the run ended on a collision; the score blinks until the next press,
 *    which starts a fresh run.
 *
 * Deliberately **no score while running**: five-row digits plus a jumping
 * three-row character do not both fit in 13 rows, so the score is the game-over
 * payoff instead.
 *
 * Scoring rule: **one point per obstacle cleared** (an obstacle scores the
 * moment it scrolls off the left edge), capped at 999 so it always fits three
 * [Font3x5] digits.
 *
 * Everything is expressed in "units" — 1 unit = 1 cell on the 13x13 matrix and
 * 2 cells on the 25x25 one ([unit]) — so a single set of physics constants
 * serves both sizes and the 25x25 game plays identically, just bigger.
 */
class DinoScreen : GlyphScreen {
    override val id = "dino"
    override val interactive = true

    private var ctx: ScreenContext? = null
    private var game: DinoGame? = null
    private var blinkOn = true

    override fun onActivate(ctx: ScreenContext) {
        this.ctx = ctx
        game = null
        ctx.pushFrame(renderIdle(ctx.size))
    }

    override fun onDeactivate() {
        ctx = null
        game = null
    }

    override fun onEvent(event: String) {
        if (event != Events.CHANGE) return
        val c = ctx ?: return
        val g = game
        when {
            g == null -> start(c)
            g.state == DinoGame.State.RUNNING -> g.jump()
            else -> start(c) // game over: one press restarts
        }
    }

    private fun start(c: ScreenContext) {
        game = DinoGame(c.size, c.ports.random)
        blinkOn = true
        c.scheduler.setTicker(TICK_MS) { tick() }
    }

    private fun tick() {
        val c = ctx ?: return
        val g = game ?: return
        if (g.state == DinoGame.State.RUNNING) {
            g.step()
            if (g.state == DinoGame.State.RUNNING) {
                c.pushFrame(renderRun(c.size, g.jumpCells(), g.legPhase(), g.groundPhase(), g.obstacleCells()))
            } else {
                // Just died: hand over to the slow score blink. setTicker fires
                // its first tick immediately, which pushes the lit score frame.
                blinkOn = false
                c.scheduler.setTicker(BLINK_MS) { tick() }
            }
            return
        }
        blinkOn = !blinkOn
        c.pushFrame(renderGameOver(c.size, g.score, blinkOn))
    }

    companion object {
        /** Physics/step interval while running. */
        const val TICK_MS = 50L

        /** Score blink half-period on game over. */
        const val BLINK_MS = 300L

        /** An obstacle: [x] is its left column, [w]/[h] its size in cells. */
        data class Obst(val x: Int, val w: Int, val h: Int)

        /** Cells per game unit: 1 on the 13x13 matrix, 2 on the 25x25 one. */
        fun unit(size: Int): Int = if (size >= 25) 2 else 1

        /** Row of the lit ground line (the very bottom row). */
        fun groundRow(size: Int): Int = size - 1

        /** Row the character's feet and the obstacles' bases rest on. */
        fun standRow(size: Int): Int = size - 2

        /** Fixed column the character runs at. */
        fun charX(size: Int): Int = 2 * unit(size)

        fun charW(size: Int): Int = 3 * unit(size)

        fun charH(size: Int): Int = 3 * unit(size)

        // ---------- character art ----------

        /** 13x13 character, 3x3: head + snout top-right, body, two legs. */
        private val CHAR_13_STAND = listOf(".##", "###", "#.#")
        private val CHAR_13_RUN_A = listOf(".##", "###", "##.")
        private val CHAR_13_RUN_B = listOf(".##", "###", ".##")
        private val CHAR_13_JUMP = listOf(".##", "###", ".#.")

        /**
         * 25x25 character, 6x6: a little dino facing right — a raised tail on the
         * left, a snout with a knocked-out eye top-right, thick neck, chunky body
         * and two legs. Only the last row changes between poses.
         */
        private val CHAR_25_STAND = listOf(
            "#..###",
            ".#.#.#",
            "..####",
            "..####",
            ".#####",
            ".#..#.",
        )
        private val CHAR_25_RUN_A = listOf(
            "#..###",
            ".#.#.#",
            "..####",
            "..####",
            ".#####",
            "#...##",
        )
        private val CHAR_25_RUN_B = listOf(
            "#..###",
            ".#.#.#",
            "..####",
            "..####",
            ".#####",
            ".##..#",
        )
        private val CHAR_25_JUMP = listOf(
            "#..###",
            ".#.#.#",
            "..####",
            "..####",
            ".#####",
            "..##..",
        )

        /**
         * [legPhase] < 0 means airborne (legs tucked); 0/1 are the two running
         * strides; anything else is the standing pose.
         */
        private fun charArt(size: Int, legPhase: Int): List<String> = if (size >= 25) {
            when (legPhase) {
                -1 -> CHAR_25_JUMP
                0 -> CHAR_25_RUN_A
                1 -> CHAR_25_RUN_B
                else -> CHAR_25_STAND
            }
        } else {
            when (legPhase) {
                -1 -> CHAR_13_JUMP
                0 -> CHAR_13_RUN_A
                1 -> CHAR_13_RUN_B
                else -> CHAR_13_STAND
            }
        }

        // ---------- renderers ----------

        /**
         * Attract frame: the character stands on a solid (dim) ground line with
         * a faint dotted track ahead of it. Static — one frame, no ticker.
         */
        fun renderIdle(size: Int): IntArray {
            val canvas = MatrixCanvas(size)
            val g = groundRow(size)
            for (x in 0 until size) canvas.light(x, g, GROUND_IDLE)
            // Dotted track ahead: hints "something will come from over there".
            val u = unit(size)
            var x = charX(size) + charW(size) + u
            while (x < size) {
                canvas.light(x, g - 1, TRACK)
                x += 2 * u
            }
            canvas.blit(charArt(size, 2), charX(size), standRow(size) - charH(size) + 1, CHAR)
            return canvas.copyOut()
        }

        /**
         * Running frame. [jumpCells] is how far the character is off the ground,
         * [legPhase] its stride (see [charArt]), [groundPhase] the scroll offset
         * of the dashed ground, [obstacles] the visible cacti.
         */
        fun renderRun(
            size: Int,
            jumpCells: Int,
            legPhase: Int,
            groundPhase: Int,
            obstacles: List<Obst>,
        ): IntArray {
            val canvas = MatrixCanvas(size)
            val g = groundRow(size)
            val u = unit(size)
            // Dashed, scrolling ground: the only motion cue while the character
            // itself stays in its column.
            val period = 3 * u
            for (x in 0 until size) {
                val phase = ((x + groundPhase) % period + period) % period
                canvas.light(x, g, if (phase < 2 * u) GROUND_DASH else GROUND_GAP)
            }
            obstacles.forEach { o ->
                canvas.fillRect(o.x, standRow(size) - o.h + 1, o.w, o.h, OBSTACLE)
            }
            canvas.blit(
                charArt(size, legPhase),
                charX(size),
                standRow(size) - jumpCells - charH(size) + 1,
                CHAR,
            )
            return canvas.copyOut()
        }

        /**
         * Game over: the score in [Font3x5] digits, blinking. [on] false is the
         * dark half of the blink — only two distinct frames, so the 300 ms
         * ticker costs almost nothing (ScreenManager drops repeats).
         */
        fun renderGameOver(size: Int, score: Int, on: Boolean): IntArray {
            val canvas = MatrixCanvas(size)
            if (on) {
                Font3x5.drawStringCentered(canvas, score.coerceIn(0, 999).toString(), size / 2 - 2, CHAR)
            }
            return canvas.copyOut()
        }

        /**
         * Brightness ratios inside a frame, against the 4095 the character and
         * obstacles own — brightness multiplies the finished frame, so these
         * ratios are what survives to the panel at any setting:
         * character/score 100 %, obstacles 100 %, lit ground dash
         * 44 %, ground gap 12 %, idle ground 22 %, idle track dots 12 %.
         */
        private const val CHAR = 4095
        private const val OBSTACLE = 4095
        private const val GROUND_DASH = 1800
        private const val GROUND_GAP = 500
        private const val GROUND_IDLE = 900
        private const val TRACK = 500
    }
}

/**
 * The Dino simulation, kept free of rendering and of the scheduler so tests can
 * drive it tick by tick (see GamesTest: a "perfect player" run proves every
 * obstacle is clearable at every speed, and the spacing never traps the player).
 *
 * Units: horizontal distances and heights are in cells; time is in ticks
 * ([DinoScreen.TICK_MS] apart). Constants are authored for the 13x13 matrix and
 * scaled by [DinoScreen.unit] so the 25x25 game is the same game, twice as big.
 */
class DinoGame(val size: Int, private val random: RandomPort) {

    enum class State { RUNNING, OVER }

    var state = State.RUNNING
        private set

    var score = 0
        private set

    /** Ticks elapsed in this run; drives the stride and the ground scroll. */
    private var ticks = 0

    /** Height above the stand row, in cells; 0 while grounded. */
    private var height = 0f
    private var vy = 0f

    /** True from the jump impulse until the character touches down again. */
    var isAirborne = false
        private set

    private var scrolled = 0f
    private val obstacles = ArrayList<Obstacle>()

    /**
     * Cells still to scroll before the next cactus enters from the right. Kept as
     * a distance rather than as "how far is the last one" so that spacing is
     * measured spawn-to-spawn: an obstacle leaving the matrix must not let the
     * next one appear early (which is exactly what would drop a fresh cactus in
     * front of a mid-air character).
     */
    private var untilSpawn = 0f

    private class Obstacle(var x: Float, val w: Int, val h: Int)

    init {
        // The first cactus spawns far beyond the right edge, not just off it,
        // so every run — the first one AND every restart, since a restart is
        // simply a fresh DinoGame (see DinoScreen.start) — opens with a few
        // seconds of empty ground to find your footing on.
        val lead = INITIAL_LEAD_UNITS * u
        obstacles += newObstacle(lead)
        // The second cactus has to wait out the first one's head start too. Its
        // countdown measures spawn-to-spawn distance from the RIGHT EDGE, so
        // without the `lead - size` term it would enter while the first is still
        // off-screen — landing to the LEFT of it, out of order, and dropping a
        // surprise cactus on a player who had not seen either yet.
        untilSpawn = (lead - size) + spawnGap()
    }

    // ---------- input ----------

    /** Upward impulse; ignored while already off the ground (single jump). */
    fun jump() {
        if (state != State.RUNNING || isAirborne) return
        vy = JUMP_V0 * u
        isAirborne = true
    }

    // ---------- simulation ----------

    fun step() {
        if (state != State.RUNNING) return
        ticks++

        if (isAirborne) {
            height += vy
            vy -= GRAVITY * u
            if (height <= 0f) {
                height = 0f
                vy = 0f
                isAirborne = false
            }
        }

        val v = speed()
        scrolled += v
        obstacles.forEach { it.x -= v }
        // Score the ones that have scrolled off the left edge.
        // Off-screen once the rounded right edge has left column 0.
        val gone = obstacles.count { it.x + it.w - 1 < -0.5f }
        if (gone > 0) {
            obstacles.subList(0, gone).clear()
            score = (score + gone).coerceAtMost(MAX_SCORE)
        }
        untilSpawn -= v
        if (untilSpawn <= 0f) {
            obstacles += newObstacle(size.toFloat())
            // Accumulate rather than reset, so the average spacing stays exact.
            untilSpawn += spawnGap()
        }

        if (collides()) state = State.OVER
    }

    /** Cells scrolled per tick; ramps with the score and then holds at the cap. */
    fun speed(): Float =
        ((START_SPEED + score * SPEED_RAMP).coerceAtMost(MAX_SPEED)) * u

    fun jumpCells(): Int = height.roundToInt()

    /** -1 while airborne (tucked legs), else the two-frame running stride. */
    fun legPhase(): Int = if (isAirborne) -1 else (ticks / STRIDE_TICKS) % 2

    fun groundPhase(): Int = -scrolled.toInt()

    fun obstacleCells(): List<DinoScreen.Companion.Obst> =
        obstacles.map { DinoScreen.Companion.Obst(it.x.roundToInt(), it.w, it.h) }

    /** True while any obstacle overlaps the character's box. */
    fun collides(): Boolean {
        val cx0 = DinoScreen.charX(size)
        val cx1 = cx0 + DinoScreen.charW(size) - 1
        val bottom = DinoScreen.standRow(size) - jumpCells()
        return obstacles.any { o ->
            val ox0 = o.x.roundToInt()
            val ox1 = ox0 + o.w - 1
            if (ox1 < cx0 || ox0 > cx1) return@any false
            // Obstacle top row; the character clears it only if its lowest row
            // is strictly above that.
            bottom >= DinoScreen.standRow(size) - o.h + 1
        }
    }

    // ---------- spawning ----------

    private fun newObstacle(x: Float): Obstacle {
        val v = VARIANTS[random.nextInt(VARIANTS.size)]
        // Widths/heights are whole cells (they are drawn with fillRect); only
        // the horizontal position is fractional, for sub-cell scrolling.
        return Obstacle(x, v.first * cellsPerUnit, v.second * cellsPerUnit)
    }

    /**
     * Distance (in cells) the previous obstacle must have travelled before the
     * next one enters from the right. The floor is [MIN_GAP_UNITS] units, which
     * is longer than a full jump covers at [MAX_SPEED] — so a landed player
     * always has time to jump again.
     */
    private fun spawnGap(): Float =
        (MIN_GAP_UNITS + random.nextInt(GAP_SPREAD_UNITS + 1)) * u

    /** Cells per game unit as a whole number (sprite and obstacle scaling). */
    private val cellsPerUnit: Int get() = DinoScreen.unit(size)

    /** The same factor for the float physics. */
    private val u: Float get() = cellsPerUnit.toFloat()

    companion object {
        /**
         * Jump impulse and gravity, in units/tick and units/tick^2, integrated
         * semi-implicitly (exactly as [step] does): the arc peaks at 5 units and
         * lasts 20 ticks (~1.0 s at a 50 ms tick), of which 19 ticks are at least
         * 1 unit up and 17 are at least 2 units up.
         *
         * The binding constraint is the SLOWEST scroll, not the fastest. The
         * character is 3 units wide, so a cactus overlaps it over
         * (3 + cactusWidth) units of travel — which takes the *most* ticks when
         * the world scrolls slowest. The player's reaction window, in units of
         * how early the jump may be pressed, is therefore
         * `ticksHighEnough * speed - (3 + cactusWidth)`, which shrinks as the
         * speed drops: ~3.5 units at [START_SPEED] and ~9 at [MAX_SPEED].
         * GamesTest asserts a window of at least 3 whole units at both ends.
         */
        const val JUMP_V0 = 0.95f
        const val GRAVITY = 0.10f

        /** Scroll speed in units/tick: starts gentle, ramps a step per point. */
        const val START_SPEED = 0.45f
        const val MAX_SPEED = 0.75f
        const val SPEED_RAMP = 0.02f

        /** Ticks per stride frame of the two-pose run cycle. */
        const val STRIDE_TICKS = 2

        /**
         * Obstacle spacing in units: floor plus a random 0..spread. The floor is
         * longer than a whole jump covers at [MAX_SPEED] (20 ticks x 0.75 = 15
         * units), so a landed player always has time to line up the next one.
         */
        const val MIN_GAP_UNITS = 20
        const val GAP_SPREAD_UNITS = 10

        /**
         * How far out the FIRST cactus of a run starts, in units. It used to
         * spawn at `size` — one unit off the right edge — so the run began with
         * a cactus already inbound and the player had under a second to react.
         *
         * The character's box ends at 4 units, so the first cactus travels
         * 36 - 4 = 32 units before it can touch anything; at [START_SPEED]
         * (0.45 units/tick, [DinoScreen.TICK_MS] = 50 ms → 9 units/s) that is
         * **~3.5 seconds** of clear running. Being in units, it is the same 3.5
         * seconds on the 25x25 matrix.
         */
        const val INITIAL_LEAD_UNITS = 36

        const val MAX_SCORE = 999

        /**
         * Cactus variants as (width, height) in units: a small one, a tall thin
         * one and a wide low one. Nothing is both widest and tallest — that
         * combination is not clearable at the slowest scroll on 13 columns.
         */
        val VARIANTS = listOf(1 to 1, 1 to 2, 2 to 1)

        /** Tallest cactus, in units — what a jump has to clear. */
        val MAX_OBSTACLE_H = VARIANTS.maxOf { it.second }

        /** Widest cactus, in units — what sets the overlap window. */
        val MAX_OBSTACLE_W = VARIANTS.maxOf { it.first }
    }
}
