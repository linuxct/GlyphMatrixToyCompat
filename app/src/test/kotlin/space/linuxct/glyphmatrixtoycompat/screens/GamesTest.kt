package space.linuxct.glyphmatrixtoycompat.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import space.linuxct.glyphmatrixtoycompat.GoldenAscii
import space.linuxct.glyphmatrixtoycompat.TestHarness
import space.linuxct.glyphmatrixtoycompat.core.Events
import space.linuxct.glyphmatrixtoycompat.core.RandomPort
import kotlin.math.abs
import kotlin.math.atan2

private val SIZES = intArrayOf(13, 25)

// ===================== Dino =====================

/**
 * Pins the spawner to one cactus variant and one gap. [DinoGame] asks for the
 * variant with bound VARIANTS.size and for the gap spread with bound
 * GAP_SPREAD_UNITS + 1, which are different numbers, so the two calls can be
 * told apart and driven independently.
 */
private class FixedRandom(private val variant: Int, private val gapExtra: Int = 0) : RandomPort {
    override fun nextInt(bound: Int): Int =
        if (bound == DinoGame.VARIANTS.size) variant else gapExtra

    override fun nextFloat(): Float = 0f
}

/** The smallest cactus at the widest spacing: nothing to trip over. */
private class EasyRandom : RandomPort {
    override fun nextInt(bound: Int): Int =
        if (bound == DinoGame.VARIANTS.size) 0 else bound - 1

    override fun nextFloat(): Float = 0f
}

class DinoScreenTest {
    private fun obst(x: Int, w: Int, h: Int) = DinoScreen.Companion.Obst(x, w, h)

    // ---------- goldens ----------

    @Test
    fun `idle running and game over render at both sizes`() {
        GoldenAscii.check("dino_13_idle", DinoScreen.renderIdle(13), 13)
        GoldenAscii.check("dino_25_idle", DinoScreen.renderIdle(25), 25)

        // Mid-run: grounded on stride A with a tall thin cactus and a wide low
        // one inbound (both real [DinoGame.VARIANTS], scaled to each matrix).
        GoldenAscii.check(
            "dino_13_run",
            DinoScreen.renderRun(13, 0, 0, 0, listOf(obst(7, 1, 2), obst(11, 2, 1))),
            13,
        )
        GoldenAscii.check(
            "dino_25_run",
            DinoScreen.renderRun(25, 0, 0, 0, listOf(obst(14, 2, 4), obst(21, 4, 2))),
            25,
        )
        // Mid-jump, legs tucked, right over a cactus.
        GoldenAscii.check(
            "dino_13_jump",
            DinoScreen.renderRun(13, 4, -1, 5, listOf(obst(3, 1, 2))),
            13,
        )
        GoldenAscii.check(
            "dino_25_jump",
            DinoScreen.renderRun(25, 8, -1, 5, listOf(obst(6, 2, 4))),
            25,
        )
        GoldenAscii.check("dino_13_over_42", DinoScreen.renderGameOver(13, 42, true), 13)
        GoldenAscii.check("dino_25_over_42", DinoScreen.renderGameOver(25, 42, true), 25)
    }

    // ---------- physics ----------

    /**
     * Measures the real jump arc by driving a real [DinoGame] (nothing is
     * re-derived here): the per-tick height in cells, from the impulse until
     * touchdown.
     */
    private fun jumpArc(size: Int): List<Int> {
        val g = DinoGame(size, EasyRandom())
        g.jump()
        val heights = ArrayList<Int>()
        while (g.isAirborne) {
            g.step()
            heights += g.jumpCells()
        }
        // One clean hop: off the ground on the first tick, back down on the last.
        assertTrue("the jump did not leave the ground", heights.first() > 0)
        assertEquals("the jump did not land", 0, heights.last())
        assertTrue("suspiciously short arc: $heights", heights.size > 8)
        return heights
    }

    @Test
    fun `a jump clears every cactus at both the fastest and the slowest scroll`() {
        for (size in SIZES) {
            val u = DinoScreen.unit(size)
            val arc = jumpArc(size)

            // The apex clears the tallest cactus outright.
            val tallest = DinoGame.MAX_OBSTACLE_H * u
            assertTrue("apex ${arc.max()} < $tallest on $size", arc.max() >= tallest)

            // ...and it stays high enough for long enough. A cactus overlaps the
            // character's box over (charW + cactusW) cells of travel, so the ticks
            // spent at or above its top must cover more ground than that. Note the
            // SLOWEST scroll is the hard case: the same overlap takes more ticks.
            for ((wu, hu) in DinoGame.VARIANTS) {
                val needed = hu * u
                val ticksAbove = arc.count { it >= needed }
                val overlapCells = DinoScreen.charW(size) + wu * u
                for (speedUnits in floatArrayOf(DinoGame.START_SPEED, DinoGame.MAX_SPEED)) {
                    val travelled = ticksAbove * speedUnits * u
                    assertTrue(
                        "cactus ${wu}x$hu on $size at $speedUnits units/tick: only " +
                            "$ticksAbove ticks at/above $needed cells = $travelled cells, " +
                            "need > $overlapCells",
                        travelled > overlapCells,
                    )
                }
            }
        }
    }

    // ---------- state machine ----------

    @Test
    fun `never jumping ends the run and a press restarts it`() {
        val h = TestHarness(13)
        val screen = DinoScreen()
        screen.onActivate(h.context)
        assertTrue(h.lastFrame().contentEquals(DinoScreen.renderIdle(13)))
        assertNull(h.scheduler.tickerInterval) // idle is one static frame

        screen.onEvent(Events.CHANGE) // start
        assertEquals(DinoScreen.TICK_MS, h.scheduler.tickerInterval)

        // Stand still and let the first cactus arrive: the run must end. How
        // many ticks that takes is a physics detail (a run opens with
        // DinoGame.INITIAL_LEAD_UNITS of clear ground), and a fixed count also
        // left the score blink on whichever phase it happened to land on — so
        // wait for the state change itself, which ends on the LIT half by
        // construction: the switch to the blink ticker fires its first tick
        // immediately.
        var guard = 0
        while (h.scheduler.tickerInterval == DinoScreen.TICK_MS && guard++ < 400) h.scheduler.tick()
        assertEquals(DinoScreen.BLINK_MS, h.scheduler.tickerInterval)
        val score = (0..999).first { s ->
            h.lastFrame().contentEquals(DinoScreen.renderGameOver(13, s, true))
        }
        // The score blinks: the very next tick is the blank half, the one after
        // is the score again.
        h.scheduler.tick()
        assertTrue(h.lastFrame().all { it == 0 })
        h.scheduler.tick()
        assertTrue(h.lastFrame().contentEquals(DinoScreen.renderGameOver(13, score, true)))

        // One press restarts: back on the fast ticker, running again.
        screen.onEvent(Events.CHANGE)
        assertEquals(DinoScreen.TICK_MS, h.scheduler.tickerInterval)
        h.scheduler.tick(3)
        assertTrue(h.lastFrame().any { it > 0 })
        assertTrue(!h.lastFrame().contentEquals(DinoScreen.renderGameOver(13, score, true)))
    }

    @Test
    fun `deactivating drops the game and re-activating shows the attract frame`() {
        val h = TestHarness(13)
        val screen = DinoScreen()
        screen.onActivate(h.context)
        screen.onEvent(Events.CHANGE)
        h.scheduler.tick(5)
        screen.onDeactivate()
        screen.onActivate(h.context)
        assertTrue(h.lastFrame().contentEquals(DinoScreen.renderIdle(13)))
    }
}

// ===================== Spin the Bottle =====================

class BottleScreenTest {
    /** Brightness-weighted aim of a frame, in the 0 = up, clockwise convention. */
    private fun aimOf(frame: IntArray, size: Int): Float {
        val c = size / 2
        var sx = 0.0
        var sy = 0.0
        for (y in 0 until size) for (x in 0 until size) {
            val v = frame[y * size + x]
            if (v > 0) {
                sx += v.toDouble() * (x - c)
                sy += v.toDouble() * (y - c)
            }
        }
        val a = Math.toDegrees(atan2(sx, -sy)).toFloat()
        return if (a < 0f) a + 360f else a
    }

    private fun angleGap(a: Float, b: Float): Float {
        val d = abs(a - b) % 360f
        return if (d > 180f) 360f - d else d
    }

    private fun litCells(frame: IntArray, size: Int) =
        (0 until size * size).filter { frame[it] > 0 }.map { (it % size) to (it / size) }

    // ---------- goldens ----------

    @Test
    fun `the idle sprite the pointer and the burst render at both sizes`() {
        // The sprite survives untouched, and only ever upright.
        GoldenAscii.check("bottle_13_idle", BottleScreen.renderIdle(13), 13)
        GoldenAscii.check("bottle_25_idle", BottleScreen.renderIdle(25), 25)
        for (size in SIZES) {
            // 0 and 90 are the easy angles. 37 and 113 are the awkward ones the
            // old rotated sprite turned into confetti; read these two as ASCII
            // when they change - they are the proof the pointer is legible.
            for (a in intArrayOf(0, 37, 90, 113)) {
                GoldenAscii.check(
                    "bottle_${size}_point_$a",
                    BottleScreen.renderPointer(size, a.toFloat()),
                    size,
                )
            }
            GoldenAscii.check(
                "bottle_${size}_handover",
                BottleScreen.renderSpin(size, 14f, BottleScreen.GHOST_V[0]),
                size,
            )
            GoldenAscii.check("bottle_${size}_burst", BottleScreen.renderResult(size, 40f, true), size)
        }
    }

    // ---------- the sprite ----------

    // ---------- the pointer ----------

    /**
     * The whole point of the rewrite: an awkward angle must be as legible as a
     * right angle. The rotated sprite this replaces failed every one of these.
     */
    @Test
    fun `the pointer aims where it is told at every whole degree`() {
        for (size in SIZES) {
            for (a in 0..359) {
                val frame = BottleScreen.renderPointer(size, a.toFloat())
                val aim = aimOf(frame, size)
                assertTrue(
                    "$size aims $aim when asked for $a",
                    angleGap(aim, a.toFloat()) <= 12f,
                )
            }
        }
    }

    // ---------- spin timing ----------

    // ---------- the screen ----------

    @Test
    fun `a press spins the pointer and it comes to rest after the burst`() {
        val h = TestHarness(13)
        val screen = BottleScreen()
        screen.onActivate(h.context)
        assertTrue(h.lastFrame().contentEquals(BottleScreen.renderIdle(13)))
        assertNull(h.scheduler.tickerInterval)

        screen.onEvent(Events.CHANGE)
        assertEquals(BottleScreen.SPIN_TICK_MS, h.scheduler.tickerInterval)
        // The bottle actually moves during the spin.
        val early = h.lastFrame()
        h.scheduler.tick(10)
        assertTrue(!h.lastFrame().contentEquals(early))

        // Run the spin out: the ticker slows to the burst period.
        var guard = 10 // the 10 ticks already spent above
        while (h.scheduler.tickerInterval == BottleScreen.SPIN_TICK_MS && guard++ < 400) {
            h.scheduler.tick()
        }
        assertEquals(BottleScreen.BURST_MS, h.scheduler.tickerInterval)
        // ~2.9 s of spin at 40 ms a frame.
        assertTrue("spin ran for $guard frames", guard in 70..77)

        // The burst is two alternating frames and nothing else, and it is
        // bounded: the ticker stops on its own.
        val burst = ArrayList<List<Int>>()
        burst += h.lastFrame().toList()
        guard = 0
        while (h.scheduler.tickerInterval != null && guard++ < 50) {
            h.scheduler.tick()
            burst += h.lastFrame().toList()
        }
        assertNull(h.scheduler.tickerInterval)
        assertEquals("burst must be a 2-phase pulse", 2, burst.distinct().size)
        assertEquals(BottleScreen.BURST_FRAMES, guard)

        // It settles on the bare resting pointer - the dark half of the pulse,
        // with the flourish gone - and never on the bottle sprite.
        val rest = h.lastFrame()
        val dark = burst.distinct().minByOrNull { p -> p.count { it > 0 } }!!
        assertEquals("resting frame still carries the burst", dark, rest.toList())
        assertTrue(
            "resting frame is not a pointer at any tenth of a degree",
            (0 until 3600).any { rest.contentEquals(BottleScreen.renderPointer(13, it / 10f)) },
        )
        assertTrue(!rest.contentEquals(BottleScreen.renderIdle(13)))
    }

    @Test
    fun `the resting angle comes from the random port`() {
        // Two different seeds must be able to disagree; and a spin from a rest
        // position never pops back to upright first.
        val h = TestHarness(25)
        val screen = BottleScreen()
        screen.onActivate(h.context)
        fun runOut() {
            var guard = 0
            while (h.scheduler.tickerInterval != null && guard++ < 500) h.scheduler.tick()
        }
        screen.onEvent(Events.CHANGE)
        runOut()
        val firstRest = h.lastFrame()
        assertNull(h.scheduler.tickerInterval)

        screen.onEvent(Events.CHANGE)
        // First frame of the new spin is still where it was resting, not
        // upright: no wind-up, no jump-cut back to vertical.
        assertTrue(h.lastFrame().contentEquals(firstRest))
        runOut()
        assertTrue(!h.lastFrame().contentEquals(firstRest))
    }
}

// ===================== Rock Paper Scissors =====================

class RpsScreenTest {
    // ---------- goldens ----------

    @Test
    fun `idle banner countdown and all three throws render at both sizes`() {
        for (size in SIZES) {
            GoldenAscii.check("rps_${size}_idle", RpsScreen.renderIdle(size), size)
            GoldenAscii.check("rps_${size}_banner", RpsScreen.renderBanner(size), size)
            GoldenAscii.check("rps_${size}_count3", RpsScreen.renderCountdown(size, 3, 0), size)
            GoldenAscii.check("rps_${size}_count1_bob", RpsScreen.renderCountdown(size, 1, 2), size)
            GoldenAscii.check("rps_${size}_rock", RpsScreen.renderThrow(size, RpsScreen.ROCK), size)
            GoldenAscii.check("rps_${size}_paper", RpsScreen.renderThrow(size, RpsScreen.PAPER), size)
            GoldenAscii.check(
                "rps_${size}_scissors",
                RpsScreen.renderThrow(size, RpsScreen.SCISSORS),
                size,
            )
        }
    }

    // ---------- the symbols ----------

    @Test
    fun `there are exactly three distinct throws and rock is the idle symbol`() {
        for (size in SIZES) {
            val throws = (0 until RpsScreen.THROWS).map { RpsScreen.renderThrow(size, it).toList() }
            assertEquals("throws must be distinct on $size", 3, throws.distinct().size)
            assertTrue(
                RpsScreen.renderThrow(size, RpsScreen.ROCK)
                    .contentEquals(RpsScreen.renderIdle(size)),
            )
            // Out-of-range ids fall back to rock rather than crashing or blanking.
            assertTrue(
                RpsScreen.renderThrow(size, 9).contentEquals(RpsScreen.renderIdle(size)),
            )
        }
    }

    /** Lit cells of a frame as (x, y). */
    private fun litCells(f: IntArray, size: Int) =
        (0 until size * size).filter { f[it] > 0 }.map { (it % size) to (it / size) }

    // ---------- the banner ----------

    // ---------- the countdown ----------

    // ---------- the state machine ----------

    /** Names the phase a pushed frame belongs to, or "?" if it matches nothing. */
    private fun classify(f: IntArray, size: Int): String = when {
        f.contentEquals(RpsScreen.renderBanner(size)) -> "banner"
        (0..24).any { b -> f.contentEquals(RpsScreen.renderCountdown(size, 3, b)) } -> "3"
        (0..24).any { b -> f.contentEquals(RpsScreen.renderCountdown(size, 2, b)) } -> "2"
        (0..24).any { b -> f.contentEquals(RpsScreen.renderCountdown(size, 1, b)) } -> "1"
        (0 until RpsScreen.THROWS).any { t -> f.contentEquals(RpsScreen.renderThrow(size, t)) } -> "reveal"
        else -> "?"
    }

    @Test
    fun `the sequence runs banner - 3 - 2 - 1 - reveal and then holds`() {
        for (size in SIZES) {
            val h = TestHarness(size)
            val screen = RpsScreen()
            screen.onActivate(h.context)
            assertTrue(h.lastFrame().contentEquals(RpsScreen.renderIdle(size)))
            assertNull(h.scheduler.tickerInterval) // idle is static

            screen.onEvent(Events.CHANGE)
            assertEquals(RpsScreen.TICK_MS, h.scheduler.tickerInterval)
            val from = h.frames.size - 1
            // 2800 ms of sequence at 70 ms per tick = 40 ticks; a couple more to
            // land on the reveal.
            h.scheduler.tick(44)
            assertNull("the reveal must stop the ticker", h.scheduler.tickerInterval)

            val phases = h.frames.drop(from).map { classify(it, size) }
            assertTrue("unclassified frame on $size: $phases", phases.none { it == "?" })
            var collapsed = ArrayList<String>()
            phases.forEach { if (collapsed.lastOrNull() != it) collapsed += it }
            assertEquals(listOf("banner", "3", "2", "1", "reveal"), collapsed)

            // The reveal holds: more time changes nothing.
            val held = h.lastFrame()
            h.scheduler.advanceTime(10_000)
            assertTrue(h.lastFrame().contentEquals(held))
            assertTrue(
                (0 until RpsScreen.THROWS).any { held.contentEquals(RpsScreen.renderThrow(size, it)) },
            )
        }
    }

    @Test
    fun `the throw comes from the random port`() {
        // Over several presses with the seeded fake, more than one throw shows
        // up — the pick is not hard-coded.
        val h = TestHarness(13)
        val screen = RpsScreen()
        screen.onActivate(h.context)
        val seen = HashSet<String>()
        repeat(12) {
            screen.onEvent(Events.CHANGE)
            h.scheduler.tick(44)
            seen += h.lastFrame().toList().toString()
        }
        assertTrue("only one throw ever came up", seen.size > 1)
    }
}
