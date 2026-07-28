package space.linuxct.glyphmatrixtoycompat.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import space.linuxct.glyphmatrixtoycompat.GoldenAscii
import space.linuxct.glyphmatrixtoycompat.TestHarness
import space.linuxct.glyphmatrixtoycompat.core.Events
import space.linuxct.glyphmatrixtoycompat.core.RandomPort
import space.linuxct.glyphmatrixtoycompat.matrix.MAX_BRIGHTNESS
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

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

    @Test
    fun `the screen is interactive`() {
        assertTrue(DinoScreen().interactive)
        assertEquals("dino", DinoScreen().id)
    }

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

    @Test
    fun `the dark half of the game over blink is blank`() {
        for (size in SIZES) {
            assertTrue(DinoScreen.renderGameOver(size, 42, false).all { it == 0 })
            assertTrue(DinoScreen.renderGameOver(size, 42, true).any { it > 0 })
        }
    }

    @Test
    fun `the score is capped at three digits`() {
        // 1000 would need four digits; it renders as the 999 cap.
        assertTrue(
            DinoScreen.renderGameOver(13, 1000, true)
                .contentEquals(DinoScreen.renderGameOver(13, 999, true)),
        )
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

    @Test
    fun `the jump is a single jump - a second press mid-air does nothing`() {
        val g = DinoGame(13, EasyRandom())
        g.jump()
        g.step()
        val h1 = g.jumpCells()
        g.jump() // ignored: already airborne
        g.step()
        // Still on the original parabola: the second tick is higher than the
        // first by exactly one gravity step, not by a fresh impulse.
        assertTrue(g.jumpCells() - h1 <= 2)
    }

    // ---------- clearability of the real spawner ----------

    /**
     * Plays a real game with a "jump when the next cactus is [leadCells] or
     * fewer cells ahead" policy. Returns the game so the caller can inspect how
     * far it got.
     */
    private fun play(size: Int, leadCells: Int, ticks: Int, random: RandomPort): DinoGame {
        val g = DinoGame(size, random)
        val cx1 = DinoScreen.charX(size) + DinoScreen.charW(size) - 1
        repeat(ticks) {
            if (g.state != DinoGame.State.RUNNING) return g
            if (!g.isAirborne) {
                val next = g.obstacleCells().firstOrNull { it.x + it.w - 1 > cx1 }
                if (next != null && next.x - cx1 - 1 <= leadCells) g.jump()
            }
            g.step()
        }
        return g
    }

    private fun longestRun(sorted: List<Int>): Int {
        var best = 0
        var run = 0
        var prev = Int.MIN_VALUE
        sorted.forEach {
            run = if (it == prev + 1) run + 1 else 1
            prev = it
            if (run > best) best = run
        }
        return best
    }

    @Test
    fun `every cactus at every spacing and speed is clearable with reaction latitude`() {
        for (size in SIZES) {
            val u = DinoScreen.unit(size)
            // Leads (in units) that survive 1500 ticks, intersected over every
            // cactus variant and both extremes of the spawner's gap. A lead in
            // this set is a single reaction habit that never dies, whatever comes.
            var common = (0..16).toSet()
            for (vi in DinoGame.VARIANTS.indices) {
                for (gapExtra in intArrayOf(0, DinoGame.GAP_SPREAD_UNITS)) {
                    val ok = (0..16).filter { lead ->
                        play(size, lead * u, 1500, FixedRandom(vi, gapExtra)).state ==
                            DinoGame.State.RUNNING
                    }
                    assertTrue(
                        "cactus ${DinoGame.VARIANTS[vi]} at gap +$gapExtra on $size " +
                            "has no reaction window (surviving leads: $ok)",
                        longestRun(ok) >= 3,
                    )
                    common = common.intersect(ok.toSet())
                }
            }
            // A player needs latitude, not one magic pixel: at least three whole
            // units of window must work for every variant at once.
            val sorted = common.sorted()
            assertTrue("no common reaction window on $size (got $sorted)", longestRun(sorted) >= 3)

            // The surviving run really did ramp to the speed cap and stay there.
            val lead = sorted[sorted.size / 2]
            for (vi in DinoGame.VARIANTS.indices) {
                val g = play(size, lead * u, 1500, FixedRandom(vi))
                assertEquals(DinoGame.State.RUNNING, g.state)
                assertTrue("only scored ${g.score} on $size", g.score > 20)
                assertEquals(DinoGame.MAX_SPEED * u, g.speed(), 0.001f)
            }
            // ...and so does a real, mixed run off the seeded random port.
            val mixed = play(size, lead * u, 1500, TestHarness(size).random)
            assertEquals(DinoGame.State.RUNNING, mixed.state)
            assertTrue("mixed run only scored ${mixed.score}", mixed.score > 20)
        }
    }

    @Test
    fun `speed ramps with the score and then holds`() {
        val g = DinoGame(13, EasyRandom())
        assertEquals(DinoGame.START_SPEED, g.speed(), 0.001f)
        val fast = play(13, 2, 900, EasyRandom())
        assertTrue(fast.score > 0)
        assertTrue(fast.speed() > DinoGame.START_SPEED)
        assertTrue(fast.speed() <= DinoGame.MAX_SPEED + 0.001f)
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

    @Test
    fun `a press while running jumps instead of restarting`() {
        val h = TestHarness(13)
        val screen = DinoScreen()
        screen.onActivate(h.context)
        screen.onEvent(Events.CHANGE)
        h.scheduler.tick(4)
        val airborneFrames = (0..6).map {
            screen.onEvent(Events.CHANGE)
            h.scheduler.tick()
            h.lastFrame()
        }
        // Something left the ground: at least one frame differs from the pure
        // grounded run, and the ticker never switched to the game-over blink.
        assertEquals(DinoScreen.TICK_MS, h.scheduler.tickerInterval)
        assertTrue(airborneFrames.distinct().size > 1)
    }
}

// ===================== Spin the Bottle =====================

class BottleScreenTest {

    @Test
    fun `the screen is interactive`() {
        assertTrue(BottleScreen().interactive)
        assertEquals("bottle", BottleScreen().id)
    }

    // ---------- helpers ----------

    private fun litCount(frame: IntArray) = frame.count { it > 0 }

    /** The largest lit-cell count any pointer reaches on this matrix. */
    private fun pointerLitMax(size: Int) =
        (0..359).maxOf { litCount(BottleScreen.renderPointer(size, it.toFloat())) }

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

    /** True when every lit cell is reachable from every other, 8-connected. */
    private fun isOneBlob(frame: IntArray, size: Int): Boolean {
        val cells = litCells(frame, size).toHashSet()
        if (cells.isEmpty()) return false
        val seen = HashSet<Pair<Int, Int>>()
        val stack = ArrayDeque(listOf(cells.first()))
        while (stack.isNotEmpty()) {
            val cell = stack.removeLast()
            if (!seen.add(cell)) continue
            for (dx in -1..1) for (dy in -1..1) {
                val n = (cell.first + dx) to (cell.second + dy)
                if (n in cells && n !in seen) stack.addLast(n)
            }
        }
        return seen.size == cells.size
    }

    /** (along, |across|) of a cell in the frame rotated so the pointer aims +along. */
    private fun local(x: Int, y: Int, size: Int, angleDeg: Float): Pair<Float, Float> {
        val c = size / 2
        val rad = Math.toRadians(angleDeg.toDouble())
        val dx = sin(rad).toFloat()
        val dy = -cos(rad).toFloat()
        val ox = (x - c).toFloat()
        val oy = (y - c).toFloat()
        return (ox * dx + oy * dy) to abs(-ox * dy + oy * dx)
    }

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

    @Test
    fun `the upright bottle is taller than it is wide and centred`() {
        for (size in SIZES) {
            val cells = BottleScreen.bottleCells(size)
            val xs = cells.map { it.first }
            val ys = cells.map { it.second }
            val w = xs.max() - xs.min() + 1
            val h = ys.max() - ys.min() + 1
            assertTrue("bottle $w x $h is not upright on $size", h > w)
            // Nearly fills the matrix height, and is centred on the matrix centre.
            assertTrue("bottle only $h of $size rows tall", h >= size - 2)
            val c = (size - 1) / 2
            assertEquals("horizontal centre on $size", c, (xs.min() + xs.max()) / 2)
            assertEquals("vertical centre on $size", c, (ys.min() + ys.max()) / 2)
        }
    }

    @Test
    fun `the lower body carries a 50 percent dither and the upper body does not`() {
        for (size in SIZES) {
            val frame = BottleScreen.renderIdle(size)
            fun litInRow(y: Int) = (0 until size).count { frame[y * size + it] > 0 }
            // Pick a row in the upper body (just below the shoulders) and one in
            // the lower body: the dithered one has many more lit cells.
            val ys = BottleScreen.bottleCells(size).map { it.second }
            val bodyTop = ys.min() + (ys.max() - ys.min()) * 55 / 100
            val bodyLow = ys.max() - 2
            assertTrue(
                "no dither difference on $size (${litInRow(bodyTop)} vs ${litInRow(bodyLow)})",
                litInRow(bodyLow) > litInRow(bodyTop) + 1,
            )
            // The outline itself is only two cells per body row.
            assertEquals(2, litInRow(bodyTop))
        }
    }

    // ---------- the pointer ----------

    @Test
    fun `the pointer is a pure function of size and angle`() {
        for (size in SIZES) {
            for (a in 0..359 step 7) {
                val once = BottleScreen.renderPointer(size, a.toFloat())
                assertTrue(
                    "not deterministic at $a on $size",
                    once.contentEquals(BottleScreen.renderPointer(size, a.toFloat())),
                )
            }
            // Whole extra turns are the same frame.
            val up = BottleScreen.renderPointer(size, 0f)
            assertTrue(up.contentEquals(BottleScreen.renderPointer(size, 360f)))
            assertTrue(up.contentEquals(BottleScreen.renderPointer(size, 720f)))
        }
    }

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

    @Test
    fun `the pointer never disintegrates - one blob of near-constant size at every angle`() {
        for (size in SIZES) {
            val counts = (0..359).map { litCount(BottleScreen.renderPointer(size, it.toFloat())) }
            // A rotated thin sprite loses and regains cells wildly; this must not.
            assertTrue(
                "$size lit range ${counts.min()}..${counts.max()}",
                counts.min() >= counts.max() * 7 / 10,
            )
            for (a in 0..359) {
                assertTrue(
                    "$size is not one blob at $a",
                    isOneBlob(BottleScreen.renderPointer(size, a.toFloat()), size),
                )
            }
        }
    }

    @Test
    fun `the head and the tail can never be confused`() {
        for (size in SIZES) {
            for (a in 0..359) {
                val frame = BottleScreen.renderPointer(size, a.toFloat())
                val locals = litCells(frame, size).map { (x, y) -> local(x, y, size, a.toFloat()) }
                val head = locals.filter { it.first > 0f }
                val tail = locals.filter { it.first < 0f }
                // The head reaches at least twice as far as the tail stub...
                val reach = head.maxOf { it.first }
                val stub = -tail.minOf { it.first }
                assertTrue("$size at $a: head $reach, tail $stub", reach >= stub * 1.8f)
                // ...and only the head is a wedge: the tail is a 1-cell ray.
                assertTrue("$size at $a: head is not a wedge", head.maxOf { it.second } >= 1.3f)
                assertTrue("$size at $a: tail is not a stub", tail.maxOf { it.second } <= 1f)
                // The tail is also dimmer, so brightness says the same thing.
                val dim = litCells(frame, size).filter { (x, y) -> frame[y * size + x] < MAX_BRIGHTNESS }
                assertTrue(dim.isNotEmpty())
                assertTrue(
                    "$size at $a: dim cells are not all behind the centre",
                    dim.all { (x, y) -> local(x, y, size, a.toFloat()).first < 0f },
                )
            }
        }
    }

    @Test
    fun `the burst is a diamond checkerboard around a still-lit pointer`() {
        for (size in SIZES) {
            val plain = BottleScreen.renderPointer(size, 40f)
            val on = BottleScreen.renderResult(size, 40f, true)
            val off = BottleScreen.renderResult(size, 40f, false)
            // Off-phase is the bare pointer; on-phase adds light but never dims
            // or hides a pointer cell - not even the dim tail.
            assertTrue(off.contentEquals(plain))
            plain.forEachIndexed { i, v -> if (v > 0) assertEquals(v, on[i]) }
            assertTrue(on.count { it > 0 } > plain.count { it > 0 } * 2)
            // Diamond: the corners stay dark, the mid-edges do not.
            val c = (size - 1) / 2
            assertEquals(0, on[0])
            assertEquals(0, on[size - 1])
            assertEquals(0, on[(size - 1) * size])
            assertTrue((0 until size).any { on[c * size + it] > 0 })
        }
    }

    // ---------- spin timing ----------

    @Test
    fun `the spin lasts about three seconds and turns three to five times`() {
        assertTrue("spin is ${BottleScreen.SPIN_MS} ms", BottleScreen.SPIN_MS in 2800..3000)
        for (delta in intArrayOf(0, 1, 180, 359)) {
            val revs = BottleScreen.spinTotalDeg(delta) / 360f
            assertTrue("$revs revolutions for delta $delta", revs >= 3f && revs <= 5f)
            // Ends exactly on target and stays there.
            assertEquals(
                BottleScreen.spinTotalDeg(delta),
                BottleScreen.spinAngleAt(BottleScreen.SPIN_MS, delta),
                0.01f,
            )
            assertEquals(
                BottleScreen.spinTotalDeg(delta),
                BottleScreen.spinAngleAt(BottleScreen.SPIN_MS + 5_000, delta),
                0.01f,
            )
        }
    }

    @Test
    fun `the angle only ever advances and starts from upright with no wind-up`() {
        val delta = 137
        assertEquals(0f, BottleScreen.spinAngleAt(0, delta), 0.001f)
        var prev = -1f
        var t = 0L
        while (t <= BottleScreen.SPIN_MS) {
            val a = BottleScreen.spinAngleAt(t, delta)
            assertTrue("angle went backwards at $t ms: $a after $prev", a >= prev)
            prev = a
            t += 10
        }
        // No wind-up: the first 100 ms already move forward.
        assertTrue(BottleScreen.spinAngleAt(100, delta) > 0f)
    }

    @Test
    fun `the deceleration is ratcheted into discrete lengthening steps`() {
        val delta = 200
        val ratchetStart = BottleScreen.EASE_MS + BottleScreen.FAST_MS
        // Sample the whole ratchet: the angle takes exactly one value per step.
        val values = (ratchetStart until BottleScreen.SPIN_MS step 5)
            .map { BottleScreen.spinAngleAt(it, delta) }
        assertEquals(BottleScreen.RATCHET_DEG.size, values.distinct().size)
        // Each hold is longer than the one before it...
        val dwells = values.distinct().map { v -> values.count { it == v } }
        dwells.zipWithNext { a, b -> assertTrue("dwell $b not longer than $a", b > a) }
        // ...and each step turns less than the one before, by 16..23 degrees.
        val steps = values.distinct().let { listOf(it.first() - BottleScreen.spinAngleAt(ratchetStart - 1, delta)) + it.zipWithNext { a, b -> b - a } }
        steps.drop(1).forEach { assertTrue("step of $it deg", it >= 16f && it <= 23f) }
        BottleScreen.RATCHET_DEG.toList().zipWithNext { a, b -> assertTrue(b < a) }
    }

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
    fun `the first spin dissolves the sprite into the pointer and later spins do not`() {
        for (size in SIZES) {
            val h = TestHarness(size)
            val screen = BottleScreen()
            screen.onActivate(h.context)
            // The sprite is much denser than any pointer, so its presence in a
            // frame is unambiguous from the lit count alone.
            val ceiling = pointerLitMax(size)
            assertTrue(litCount(h.lastFrame()) > ceiling)

            // setTicker fires its first tick inline, so the spin's opening frame
            // is already on screen when onEvent returns.
            screen.onEvent(Events.CHANGE)
            val opening = listOf(h.lastFrame()) + (0 until BottleScreen.GHOST_V.size + 2).map {
                h.scheduler.tick()
                h.lastFrame()
            }
            // Exactly the ghost frames carry the sprite, each dimmer than the last.
            assertEquals(
                "sprite lingered on $size",
                BottleScreen.GHOST_V.size,
                opening.count { litCount(it) > ceiling },
            )
            BottleScreen.GHOST_V.forEachIndexed { i, v ->
                assertTrue("ghost $i on $size is not at $v", opening[i].any { it == v })
            }
            BottleScreen.GHOST_V.toList().zipWithNext { a, b -> assertTrue(b < a) }

            // Run the spin out; a second spin starts from the pointer, so there
            // is nothing left to dissolve and no frame is ever sprite-dense.
            var guard = 0
            while (h.scheduler.tickerInterval != null && guard++ < 500) h.scheduler.tick()
            screen.onEvent(Events.CHANGE)
            guard = 0
            while (h.scheduler.tickerInterval == BottleScreen.SPIN_TICK_MS && guard++ < 500) {
                h.scheduler.tick()
                // The tick that ends the spin flips straight into the burst.
                if (h.scheduler.tickerInterval != BottleScreen.SPIN_TICK_MS) break
                assertTrue("sprite came back on $size", litCount(h.lastFrame()) <= ceiling)
            }
            assertTrue("second spin ran for $guard frames on $size", guard > 60)
        }
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

    @Test
    fun `the screen is interactive`() {
        assertTrue(RpsScreen().interactive)
        assertEquals("rps", RpsScreen().id)
    }

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

    /** (minX, minY, maxX, maxY) of the lit cells. */
    private fun bbox(f: IntArray, size: Int): IntArray {
        val cells = litCells(f, size)
        assertTrue("blank frame on $size", cells.isNotEmpty())
        return intArrayOf(
            cells.minOf { it.first }, cells.minOf { it.second },
            cells.maxOf { it.first }, cells.maxOf { it.second },
        )
    }

    /** Lit cells inside the centred block of side [n]. */
    private fun coreLit(f: IntArray, size: Int, n: Int): Int {
        val lo = (size - n) / 2
        var c = 0
        for (y in lo until lo + n) for (x in lo until lo + n) if (f[y * size + x] > 0) c++
        return c
    }

    /** Cells where exactly one of the two frames is lit. */
    private fun differing(a: IntArray, b: IntArray) =
        a.indices.count { (a[it] > 0) != (b[it] > 0) }

    /**
     * The whole point of the symbols. The hand sprites these replace were three
     * roundish blobs facing right: they were not byte-identical, so "distinct"
     * was never the bar — the bar is that no two share both silhouette and
     * fill. Rock is a solid disc, paper a hollow square, scissors a sparse X,
     * and each of the four checks below separates a different pair on its own.
     */
    @Test
    fun `the three symbols are unmistakable - solid disc, hollow square, sparse X`() {
        for (size in SIZES) {
            val rock = RpsScreen.renderThrow(size, RpsScreen.ROCK)
            val paper = RpsScreen.renderThrow(size, RpsScreen.PAPER)
            val scissors = RpsScreen.renderThrow(size, RpsScreen.SCISSORS)
            val named = listOf("rock" to rock, "paper" to paper, "scissors" to scissors)
            val cells = size * size

            // 1. Every pair disagrees over a wide area of the panel, not in a
            //    handful of cells: a quarter of every LED there is.
            val floor = cells / 4
            for (i in named.indices) for (j in i + 1 until named.size) {
                val d = differing(named[i].second, named[j].second)
                assertTrue(
                    "${named[i].first} vs ${named[j].first} on $size differ in only " +
                        "$d of $cells cells (need > $floor)",
                    d > floor,
                )
            }

            // 2. Weight: the solid disc lights at least twice as many cells as
            //    either outline shape, and the X is the sparsest of the three.
            val (litRock, litPaper, litScissors) =
                Triple(rock.count { it > 0 }, paper.count { it > 0 }, scissors.count { it > 0 })
            assertTrue(
                "rock ($litRock) is not twice paper ($litPaper) on $size",
                litRock >= litPaper * 2,
            )
            assertTrue(
                "rock ($litRock) is not twice scissors ($litScissors) on $size",
                litRock >= litScissors * 2,
            )
            assertTrue("scissors ($litScissors) is not the sparsest on $size", litScissors < litPaper)

            // 3. Fill: in the centred block of half the panel the disc is solid,
            //    the square is empty, and the X crosses it but leaves it mostly
            //    dark. This is the check the old hand sprites could never pass.
            val n = size / 2 or 1 // 7 at 13, 13 at 25
            val core = n * n
            assertEquals("rock's core is not solid on $size", core, coreLit(rock, size, n))
            assertEquals("paper's core is not hollow on $size", 0, coreLit(paper, size, n))
            val xCore = coreLit(scissors, size, n)
            assertTrue(
                "scissors' core is $xCore of $core on $size - not a sparse cross",
                xCore in 1 until core / 2,
            )

            // 4. Shape: all three fill the panel to the same one-cell margin,
            //    but only the straight-edged ones reach into the corners of it.
            val margin = 1
            for ((name, f) in named) {
                val (x0, y0, x1, y1) = bbox(f, size).toList()
                assertEquals("$name is not inset on $size", margin, x0)
                assertEquals("$name is not inset on $size", margin, y0)
                assertEquals("$name is not inset on $size", size - 1 - margin, x1)
                assertEquals("$name is not inset on $size", size - 1 - margin, y1)
            }
            fun corner(f: IntArray) = f[margin * size + margin] > 0
            assertTrue("rock reaches its corner on $size - not round", !corner(rock))
            assertTrue("paper misses its corner on $size", corner(paper))
            assertTrue("scissors misses its corner on $size", corner(scissors))
        }
    }

    // ---------- the banner ----------

    @Test
    fun `the banner is a solid band with the word knocked out dark`() {
        for (size in SIZES) {
            val f = RpsScreen.renderBanner(size)
            fun row(y: Int) = (0 until size).map { f[y * size + it] }

            // The band's own rows: edge to edge at full brightness.
            val solid = (0 until size).filter { y -> row(y).all { it >= 2731 } }
            assertTrue("no solid band rows on $size (got $solid)", solid.size >= 2)
            val top = solid.first()
            val bottom = solid.last()
            assertTrue("band is not a band on $size", bottom - top >= 6)

            // Inverse text: inside the band there are dark cells with lit band
            // cells on both sides of them — a knocked-out word, not a gap.
            val knockedOut = (top + 1 until bottom).filter { y ->
                val r = row(y)
                val dark = (1 until size - 1).filter { r[it] == 0 }
                dark.isNotEmpty() &&
                    dark.all { d -> r.take(d).any { it >= 2731 } && r.drop(d + 1).any { it >= 2731 } }
            }
            assertEquals("the word must be 5 rows tall on $size", 5, knockedOut.size)
            // Every band row is full brightness or black — no dither inside it.
            (top..bottom).forEach { y -> assertTrue(row(y).none { it in 1..2730 }) }

            // A dithered bar sits below the band: mid-brightness, roughly half lit.
            val ditherRows = (bottom + 1 until size).filter { y -> row(y).any { it in 1..2730 } }
            assertTrue("no dither bar below the band on $size", ditherRows.isNotEmpty())
            ditherRows.forEach { y ->
                val lit = row(y).count { it > 0 }
                assertTrue("row $y is not a 50 % dither ($lit of $size)", lit in size / 3..size * 2 / 3)
            }

            // The round token peeks out above the band.
            assertTrue((0 until top).any { y -> row(y).any { it > 0 } })
        }
    }

    @Test
    fun `the banner word is spelled with letters the font actually has`() {
        // Font3x5 has no R and no Y, so "READY" is unspellable; a '?' fallback
        // would be worse than no word at all. Whatever word the banner uses must
        // be fully covered by the font.
        val scratch = space.linuxct.glyphmatrixtoycompat.matrix.MatrixCanvas(13)
        space.linuxct.glyphmatrixtoycompat.matrix.Font3x5
            .drawStringCentered(scratch, "?", 4, 4095)
        val question = scratch.copyOut()
        val banner = RpsScreen.renderBanner(13)
        // No '?' glyph shape can be found knocked out of the band.
        val holes = IntArray(13 * 13) { if (banner[it] == 0) 1 else 0 }
        var matches = 0
        question.forEachIndexed { i, v -> if (v > 0 && holes[i] == 1) matches++ }
        assertTrue(
            "the banner appears to render a '?' placeholder",
            matches < question.count { it > 0 },
        )
    }

    // ---------- the countdown ----------

    @Test
    fun `the countdown bobs and its shadow shrinks as the token rises`() {
        for (size in SIZES) {
            val frames = (0..5).map { RpsScreen.renderCountdown(size, 3, it) }
            // The bob really moves the token.
            assertTrue("no bob on $size", frames.map { it.toList() }.distinct().size >= 3)
            // Numerals differ.
            assertTrue(
                !RpsScreen.renderCountdown(size, 3, 0)
                    .contentEquals(RpsScreen.renderCountdown(size, 2, 0)),
            )
            // The shadow (the dim dither) is narrowest when the token is at the
            // top of its bob (step 0) and widest when it is at the bottom (step 2).
            fun dither(f: IntArray) = f.count { it in 1..2730 }
            val raised = dither(RpsScreen.renderCountdown(size, 3, 0))
            val lowered = dither(RpsScreen.renderCountdown(size, 3, 2))
            assertTrue(
                "shadow did not shrink as the hand rose on $size ($lowered -> $raised)",
                raised < lowered,
            )
        }
    }

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
    fun `a press during the sequence restarts it from the banner`() {
        val h = TestHarness(13)
        val screen = RpsScreen()
        screen.onActivate(h.context)
        screen.onEvent(Events.CHANGE)
        h.scheduler.tick(20) // 1400 ms in: the second numeral
        assertEquals("2", classify(h.lastFrame(), 13))
        screen.onEvent(Events.CHANGE)
        h.scheduler.tick()
        assertEquals("banner", classify(h.lastFrame(), 13))
        assertNotNull(h.scheduler.tickerInterval)
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
