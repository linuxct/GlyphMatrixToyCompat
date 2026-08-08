package space.linuxct.glyphworks.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The liquid FAB, measured rather than looked at.
 *
 * This file is unusual for a unit test suite in that most of it is a **simulation**
 * — it samples the shader's field over the whole disc across the whole loop — and
 * that is on purpose. There is no device in this project's CI, the AGSL has never
 * been compiled on a GPU, and every one of this feature's three rewrites was caused
 * by a property that is arithmetic but was being judged by eye:
 *
 * - *"the current predominant colour never takes 100% of the fab button surface"* —
 *   the old amplitude was set **below** the clamp bound on purpose, so a smear of
 *   the outgoing colour always survived at one edge and quietly advertised where
 *   the next one would come from. That is [coverage] below, and it is asserted with
 *   **zero tolerance**: `smoothstep` clamps, so "100%" is exact or it is a bug.
 * - *"a rotation like a sphere is not in my definition of random"* — the headings
 *   were eight fixed compass points on a single turning normal. That is [headings].
 * - *"transitions are mostly clean almost straight lines"* — the swell's only
 *   octave had a wavelength several times the disc, so inside the crop the user can
 *   see it was a tilt. That is [curvature], measured **over the visible disc only**,
 *   because measuring the field as a whole is exactly what let it through.
 *
 * Each of those was true of a version that passed the tests of its day, which is
 * why the properties are measured here instead of being described in a KDoc.
 */
class LiquidFabTest {
    private val twoPi = (2.0 * PI).toFloat()

    // ================= the phase =================

    @Test
    fun `the phase stays inside one turn`() {
        // Sampled across a whole day of uptime, because "it grows forever" is the
        // bug this rules out and it takes hours to become visible.
        var t = 0L
        while (t < 24L * 60 * 60 * 1000) {
            val phase = liquidPhase(t)
            assertTrue("t=$t gave $phase", phase >= 0f && phase < twoPi)
            t += 137
        }
    }

    @Test
    fun `the loop closes exactly`() {
        for (offset in listOf(0L, 1L, 1234L, LIQUID_PERIOD_MS - 1)) {
            assertEquals(liquidPhase(offset), liquidPhase(offset + LIQUID_PERIOD_MS), 1e-4f)
            assertEquals(liquidPhase(offset), liquidPhase(offset + 100 * LIQUID_PERIOD_MS), 1e-3f)
        }
    }

    // ================= coverage: the disc really is 100% one colour =================

    @Test
    fun coverage() {
        // ZERO tolerance. `smoothstep` clamps, so every sampled pixel is exactly 0f
        // or exactly 1f when the tide has carried the front clear, and "a trace of
        // the other colour" is not a small number here, it is a failure.
        for (k in 0 until LIQUID_SLOTS) {
            // A takeover boundary is the flattest instant there is: |tide| is at its
            // maximum and the arriving colour has just finished.
            val spread = spreadOverDisc(liquidFrame(slotPhase(k, 0f)), fine = true)
            assertTrue(
                "slot $k is not flat at its boundary: mix ranged ${spread.lo}..${spread.hi}",
                spread.pure,
            )
            assertEquals("slot $k deviates from pure", 0f, spread.deviation, 0f)
        }
    }

    // ================= the headings =================

    @Test
    fun headings() {
        // The property the user asked for, stated as the two ways it can fail.
        // "Nearly the same heading twice" is a wipe repeated; "180 degrees apart" is
        // a pendulum, and it is the one described exactly: if blue left to the left,
        // red arrived from the right, and blue coming BACK from the left is the next
        // heading landing half a turn from the last. So the accepted band is away
        // from 0 AND away from pi -- which is NOT "as far apart as possible".
        val maxTurn = PI.toFloat() - LIQUID_MIN_TURN
        for (k in 0 until LIQUID_SLOTS) {
            val turn = liquidSeparation(liquidArrivalAngle(k), liquidArrivalAngle(k + 1))
            assertTrue(
                "slots $k -> ${k + 1} turned ${deg(turn)}°, outside [${deg(LIQUID_MIN_TURN)}, ${deg(maxTurn)}]",
                turn in LIQUID_MIN_TURN..maxTurn,
            )
        }
    }

    @Test
    fun `red and blue alternate, and each arrives from its own heading`() {
        // A takeover's normal points at the red side, so an even (red) slot floods
        // in FROM its heading and an odd (blue) slot floods in from the same heading
        // with the normal flipped. Checked by where the arriving colour actually
        // shows up first.
        for (k in 0 until LIQUID_SLOTS) {
            val heading = liquidArrivalAngle(k)
            val frame = liquidFrame(slotPhase(k, 0.5f))
            val arriving = liquidMixAt(0.85f * cos(heading), 0.85f * sin(heading), frame)
            val leaving = liquidMixAt(-0.85f * cos(heading), -0.85f * sin(heading), frame)
            if (k % 2 == 0) {
                assertTrue("slot $k should be red arriving: $arriving vs $leaving", arriving > leaving)
            } else {
                assertTrue("slot $k should be blue arriving: $arriving vs $leaving", arriving < leaving)
            }
        }
    }

    // ================= the front is a curve, not a chord =================

    @Test
    fun curvature() {
        // "Right now transitions are mostly clean almost straight lines." Measured
        // OVER THE VISIBLE DISC ONLY, because the long octave bends the front
        // beautifully across the whole plane and hardly at all across the 56 dp the
        // user can see -- judging the field as a whole is what let that through.
        //
        // The metric: how far the boundary departs from the straight chord through
        // its own two rim endpoints, as a fraction of the disc's DIAMETER.
        val deviations = mutableListOf<Float>()
        for (k in 0 until LIQUID_SLOTS) {
            for (step in 0..8) {
                val u = 0.30f + step / 8f * 0.40f
                boundaryDeviation(liquidFrame(slotPhase(k, u)))?.let { deviations += it }
            }
        }
        assertTrue("only ${deviations.size} usable instants", deviations.size > 100)
        val mean = deviations.average().toFloat()
        val peak = deviations.max()
        // Reference points, both measured with this same code: the single long
        // octave this replaced managed 1.8% mean and 4.9% peak, which is the "almost
        // a straight line" being complained about.
        assertTrue("mean departure from a chord is only ${pct(mean)} of the diameter", mean > 0.045f)
        assertTrue("peak departure is only ${pct(peak)} of the diameter", peak > 0.12f)
    }

    // ================= the fallback =================

    @Test
    fun `the fallback brush covers the disc whenever the shader does`() {
        // The fallback is the shader's own field with the swell deleted, so its
        // gradient is entirely off the disc past |tide| = G + E. That is a SMALLER
        // number than the shader's bound, which is the direction it has to be: a
        // device that cannot compile the AGSL still gets a fully flat brand colour
        // for at least as long, never less.
        assertEquals(LIQUID_FRONT_GRADIENT + LIQUID_EDGE, LIQUID_FALLBACK_CLAMP, 1e-6f)
        assertTrue(LIQUID_FALLBACK_CLAMP < LIQUID_CLAMP_BOUND)
        assertTrue(LIQUID_TIDE_AMPLITUDE > LIQUID_FALLBACK_CLAMP)
    }

    // ================= the one thing about the AGSL a JVM can check =================

    @Test
    fun `every constant interpolated into the shader is a legal AGSL literal`() {
        // The shader's numbers come from the Kotlin constants so the two cannot
        // drift — but `Float.toString` answers "1.0E-5" for a small enough value,
        // which AGSL will not parse, and the only symptom would be a FAB silently on
        // the fallback brush forever. This is the whole cost of interpolating.
        val exponent = Regex("""\d[eE][-+]?\d""").find(LIQUID_AGSL)
        assertTrue("scientific notation in the shader: ${exponent?.value}", exponent == null)
        // And nothing here may be formatted for a locale.
        assertTrue(!Regex("""\d,\d""").containsMatchIn(LIQUID_AGSL))
        // A cheap smoke test that the interpolation happened at all.
        assertTrue(LIQUID_AGSL.contains("$LIQUID_TIDE_AMPLITUDE") || LIQUID_AGSL.contains("$LIQUID_SWELL_AMOUNT"))
        assertTrue(!LIQUID_AGSL.contains("$"))
        // Everything that varies per takeover is a uniform, so the shader has no
        // hashing and no branching in it. That is what makes the Kotlin mirror below
        // a mirror rather than a second implementation.
        for (uniform in listOf("uSize", "uDir", "uPhase", "uOrigin", "uTide", "uLow", "uHigh")) {
            assertTrue("$uniform is not declared", LIQUID_AGSL.contains("uniform") && LIQUID_AGSL.contains(uniform))
        }
        assertTrue("the shader branches", !LIQUID_AGSL.contains("if ("))
    }

    // ================= harness =================

    /** The phase [u] of the way through takeover [k]. */
    private fun slotPhase(k: Int, u: Float): Float =
        ((k + u) / LIQUID_SLOTS * twoPi).mod(twoPi)

    private fun deg(radians: Float): String = "%.1f".format(radians * 180f / PI.toFloat())

    private fun pct(fraction: Float): String = "%.2f%%".format(fraction * 100f)

    private class Spread(val lo: Float, val hi: Float) {
        val pure: Boolean get() = lo >= 1f || hi <= 0f

        /** How much of the other colour is showing; 0 when the disc is one colour. */
        val deviation: Float
            get() = when {
                lo >= 1f -> 1f - lo
                hi <= 0f -> hi
                else -> min(1f - lo, hi)
            }
    }

    /** The range of the mix parameter over the whole disc, rim included. */
    private fun spreadOverDisc(frame: LiquidFrame, fine: Boolean): Spread {
        val n = if (fine) 90 else 34
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        for (iy in -n..n) {
            val y = iy / n.toFloat()
            for (ix in -n..n) {
                val x = ix / n.toFloat()
                if (x * x + y * y > 1f) continue
                val mix = liquidMixAt(x, y, frame)
                if (mix < lo) lo = mix
                if (mix > hi) hi = mix
            }
        }
        // The rim explicitly: a grid can step straight over a one-pixel crescent.
        val rimPoints = if (fine) 720 else 180
        for (i in 0 until rimPoints) {
            val a = i / rimPoints.toFloat() * twoPi
            for (r in listOf(1f, 0.997f)) {
                val mix = liquidMixAt(r * cos(a), r * sin(a), frame)
                if (mix < lo) lo = mix
                if (mix > hi) hi = mix
            }
        }
        return Spread(lo, hi)
    }

    /**
     * The boundary's departure from the straight chord through its two rim
     * endpoints, sampled across the visible disc and given as a fraction of the
     * DIAMETER. Null when the front is not crossing enough of the disc to have a
     * shape worth measuring.
     */
    private fun boundaryDeviation(frame: LiquidFrame): Float? =
        boundaryProfile(frame)?.let { residual -> residual.maxOf { abs(it) } / 2f }

    /**
     * The boundary as a curve, with the straight chord subtracted — the silhouette,
     * in disc radii, sampled on a fixed transverse grid so two takeovers can be
     * compared. Null unless the front crosses at least 70% of the disc.
     */
    private fun boundaryProfile(frame: LiquidFrame): FloatArray? {
        val nx = -frame.dirY
        val ny = frame.dirX
        val n = 40
        val offsets = FloatArray(2 * n + 1) { Float.NaN }
        for (i in -n..n) {
            val t = i / (n + 1f)
            val half = sqrt(1f - t * t)
            var previous = liquidField(nx * t - frame.dirX * half, ny * t - frame.dirY * half, frame)
            for (step in 1..200) {
                val u = (step / 100f - 1f) * half
                val f = liquidField(nx * t + frame.dirX * u, ny * t + frame.dirY * u, frame)
                if (previous * f <= 0f && previous != f) {
                    val w = previous / (previous - f)
                    val uPrev = ((step - 1) / 100f - 1f) * half
                    offsets[i + n] = uPrev + w * (u - uPrev)
                    break
                }
                previous = f
            }
        }
        val first = offsets.indexOfFirst { !it.isNaN() }
        val last = offsets.indexOfLast { !it.isNaN() }
        if (first < 0 || last - first < 0.7f * offsets.size) return null
        // A gap in the middle would mean the interpolation below is meaningless.
        for (i in first..last) if (offsets[i].isNaN()) return null
        val residual = FloatArray(offsets.size)
        for (i in first..last) {
            val along = (i - first).toFloat() / (last - first)
            residual[i] = offsets[i] - (offsets[first] + (offsets[last] - offsets[first]) * along)
        }
        return residual
    }
}
