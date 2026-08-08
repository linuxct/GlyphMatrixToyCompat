package space.linuxct.glyphworks.ui

import android.graphics.RuntimeShader
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.flow.collectLatest
import space.linuxct.glyphworks.core.DebugLog
import space.linuxct.glyphworks.ui.theme.NothingLiquidBlue
import space.linuxct.glyphworks.ui.theme.NothingLiquidRed
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The Create tab's `+`, filled with moving liquid in Nothing's own two colours.
 *
 * ## Why AGSL and not a `Brush`
 *
 * The brief was *liquid* — irregular, non-repeating, morphing — and Compose's
 * gradients cannot be that. A `Brush` is a ramp along a line, a circle or a sweep;
 * animating its stops or its angle gives a ramp that slides, which reads as a
 * shine passing over a solid button. What makes something look like liquid is that
 * the *boundary between the colours* is a curve that changes shape, and that is a
 * per-pixel function — a shader. `minSdk` is 33, [RuntimeShader] arrived in 33, so
 * there is no version gate to write and no compatibility path to maintain.
 *
 * There is still a fallback ([liquidFallbackBrush]) because shader compilation is
 * the one thing here that can fail at runtime on a device this was never run on,
 * and a `+` button that draws nothing is a broken app.
 *
 * ## The shape of the animation: takeovers, not a cycle
 *
 * The loop is a chain of **takeovers**. One takeover is: the disc is 100% one
 * brand colour and rests there for about a second and a third; then the *other*
 * colour floods in from a heading picked for that takeover alone, crosses in a
 * little over two seconds, and takes the disc completely. Then it is the resting
 * colour, and the next takeover arrives from somewhere else.
 *
 * Three separate things had to be true at once for that to read the way it does,
 * and each has been got wrong at least once in this file's history:
 *
 * - **the disc genuinely reaches 100%.** Not "within a few percent" — every pixel,
 *   including the rim. See [LIQUID_TIDE_AMPLITUDE]; this is the requirement that
 *   most of the other constants are sized against.
 * - **the arrival heading is unpredictable.** Not a rotation. See
 *   [LIQUID_HEADINGS].
 * - **the front is a curve, not a chord**, over the few dozen pixels of the disc
 *   the user can actually see. See [LIQUID_SHORT_WEIGHT].
 *
 * ## Why 100% coverage is the load-bearing requirement
 *
 * The field is `G·dot(p, dir) + S·swell + tide`, and the colour is a `smoothstep`
 * of it across ±[LIQUID_EDGE]. Over the unit disc `|dot(p, dir)| ≤ 1` and
 * `|swell| ≤ 1`, so **everything except the tide is bounded by
 * [LIQUID_FRONT_GRADIENT]` + `[LIQUID_SWELL_AMOUNT]` + `[LIQUID_EDGE]` = 2.14`**.
 * A tide beyond that pushes the whole `smoothstep` argument past its clamp and the
 * disc is exactly one colour — not nearly, exactly, because `smoothstep` clamps.
 *
 * The previous two tunings both sat *below* that sum on purpose, to keep the
 * button moving. That was the wrong trade and the user named the symptom
 * precisely: the front never clears the disc, so a smudge of the outgoing colour
 * stays parked against one edge, and that smudge is a **preview of where the next
 * colour will come from**. The randomness above is worthless if the arrival is
 * legible a second in advance. So the amplitude now clears the sum by
 * `[LIQUID_TIDE_AMPLITUDE] − 2.14 = 0.61`, and it is the overshoot — not any
 * easing — that produces the rest between takeovers.
 *
 * ## Why the tide is a straight line now
 *
 * It used to be a cosine put through a cubic that was flat at its ends, so the
 * front would ease as it turned around. That easing is now pointless: the
 * turnaround happens while the disc is clamped to one colour, where nothing about
 * it is visible. All it did was stretch the rest. The tide is therefore a plain
 * constant-speed ramp, and the two knobs finally separate cleanly:
 *
 * - [LIQUID_TIDE_AMPLITUDE] alone sets **how much of each takeover is rest** — the
 *   fraction is `1 − (bound / amplitude)`, nothing else enters it.
 * - [LIQUID_SLOT_MS] alone sets **the tempo**, and does not change that fraction.
 *
 * ## What is drawn: two masses and one edge
 *
 * Not a texture. At any instant the button is mostly one colour with a single soft
 * irregular front crossing it. The liquid quality lives in the shape of that one
 * edge, not in a field of competing blobs — an early version summed four sines
 * into a marbled churn and read as busy and mechanical at the same time.
 *
 * - **one edge.** The field is dominated by `dot(p, dir)`, a plane ramp whose zero
 *   set is a single line. Everything added to it has a smaller gradient than that
 *   ramp does, which makes the field strictly monotone along `dir`; a strictly
 *   monotone function crosses zero at most once along every line parallel to
 *   `dir`, so there is provably exactly one front and never a detached blob. That
 *   is an inequality between constants — stated and checked in
 *   [LIQUID_SWELL_AMOUNT] — not a hope about how the sines happen to line up.
 * - **liquid.** The front is bent by a swell of two octaves, the shorter of which
 *   is what makes the edge curve *within the disc* rather than across the whole
 *   plane. See [LIQUID_SHORT_WEIGHT].
 * - **tide.** [liquidTide] slides that front bodily across the button.
 *
 * ## Time is a phase, not a clock
 *
 * The phase is wrapped every [LIQUID_PERIOD_MS] rather than growing. That matters
 * for more than tidiness — `sin` of a large float loses precision fast on a GPU's
 * 32-bit ALUs, and an app left on the Create tab overnight would otherwise hand
 * the shader a number in the millions. See [liquidPhase].
 *
 * Everything that varies per takeover ([liquidFrame]) is computed on the **CPU**
 * and handed over as uniforms. That is deliberate: it means the AGSL contains no
 * hashing and no branching, and it makes "the shader and the Kotlin agree" true by
 * construction rather than by two implementations being kept in step. The one
 * thing still written twice is the per-pixel field, and [liquidField] mirrors it so
 * that a JVM test can measure coverage, curvature and the single-front property
 * without a GPU.
 *
 * ## The icon
 *
 * Nothing here is tuned for icon contrast and it does not need to be: the shader
 * cannot produce a colour outside `mix(blue, red)`, and `NothingBrandTest` walks
 * that whole ramp against both inks. What the coverage requirement changed is that
 * the button now spends over a third of its time at one *end* of that ramp rather
 * than in the middle of it, which makes the endpoint contrast — the worst case,
 * 6.6:1 — the case that matters most.
 *
 * ## What stops it running
 *
 * This project has fought idle cost before, so the bounds are worth stating
 * exactly. The frame loop exists only while **both**:
 *
 * 1. this composable is composed. It lives inside `NavFab`'s `present` flag, which
 *    is true only from the moment the `+` starts appearing on the Create tab to
 *    the moment its exit animation ends. On the other three tabs there is no
 *    composable, so there is no loop.
 * 2. the app is **resumed**. `LifecycleResumeEffect` flips a snapshot flag and
 *    `collectLatest` cancels the loop on the way out, so a backgrounded app or a
 *    dark screen leaves the Create tab asking for nothing.
 *
 * And the phase is a `mutableFloatStateOf` read **only inside the draw lambda**,
 * so a tick invalidates one node's draw and recomposes nothing — the same phase
 * discipline `NavFab` and `NavChip` are built on.
 */

private const val TWO_PI = (2.0 * PI).toFloat()

// ---------- the tempo ----------

/**
 * How long one takeover lasts: rest on a flat colour, then the other colour
 * crossing. **The tempo knob, and only that** — it scales the rest and the
 * crossing together and cannot change the balance between them.
 *
 * The balance is [LIQUID_TIDE_AMPLITUDE]'s job, and separating the two is the
 * lesson of the previous two attempts: the loop was retimed twice when what was
 * wrong was the travel.
 *
 * At 3.6 s this measures out as about 1.3 s of flat colour and 2.3 s of movement.
 */
internal const val LIQUID_SLOT_MS = 3_600L

/**
 * How many takeovers before the whole thing repeats.
 *
 * Only a *repeat* length, not a rhythm — every takeover picks its heading and its
 * swell shape from [LIQUID_HEADINGS] and [liquidFrame], and 24 of them is simply
 * where the sequence is allowed to loop so the phase can wrap. At
 * [LIQUID_SLOT_MS] apiece that is a minute and a half, far longer than the `+` is
 * ever on screen, so nobody sees the seam. Even, so red and blue alternate across
 * the wrap.
 */
internal const val LIQUID_SLOTS = 24

/** How long one full loop takes. A consequence of the two constants above. */
internal const val LIQUID_PERIOD_MS = LIQUID_SLOT_MS * LIQUID_SLOTS

// ---------- the constants the shader and the JVM must agree on ----------
//
// These are interpolated into LIQUID_AGSL below rather than typed twice, so the
// Kotlin mirror ([liquidField]) cannot drift from the GLSL the GPU runs.

/** The plane ramp that *is* the front. The single largest gradient in the field. */
internal const val LIQUID_FRONT_GRADIENT = 1.15f

/**
 * How far the swell is allowed to bend the front — and, through the inequality
 * below, the constant that keeps there being exactly **one** front.
 *
 * Write `s` for the swell (`|s| ≤ 1`) and `L` for the largest its gradient can be.
 * The field is `G·dot(p, dir) + S·s(q(p)) + tide`; the tide is constant in `p`, so
 * the field is strictly monotone along `dir` — one zero crossing per line, one
 * front, never a detached blob — as soon as
 *
 * ```
 * S · L · (1 + WARP·√2·L_long) < G
 * ```
 *
 * where the bracket is the worst the domain warp can stretch the swell's own
 * gradient. `L` is a weighted sum over the two octaves: half a wave's frequency
 * magnitude each, `½·|(1.15, 0.95)| = 0.746` for the long one and
 * `½·|(2.9, 3.35)| = 2.216` for the short. At the values in this file that is
 *
 * ```
 * 0.44 · (0.5·0.746 + 0.5·2.216) · (1 + 0.3·√2·0.746) = 0.858  <  1.15
 * ```
 *
 * — 75% of the way to the bound, so it holds with room. `LiquidFabTest` computes
 * that inequality rather than trusting this paragraph, and also counts sign
 * changes along the sweep axis empirically, which is the property the inequality
 * exists to buy.
 */
internal const val LIQUID_SWELL_AMOUNT = 0.44f

/**
 * How much of the swell is the **short** octave — the constant that decides
 * whether the front reads as a curve or as a line.
 *
 * The long octave's wavelength is several disc diameters, so across the button it
 * is nearly a straight tilt: it bends the front beautifully over the whole plane
 * and almost not at all over the part anybody sees. That is exactly what the user
 * reported — "mostly clean almost straight lines, but sometimes they seem curved".
 * The short octave completes about one wave across the disc, which is what puts a
 * bend *inside* the crop.
 *
 * Measured over the visible disc as the boundary's largest departure from the
 * straight chord through its two rim endpoints, as a fraction of the diameter:
 * **1.8% mean / 4.9% peak** with the long octave alone, **6.3% mean / 16.4% peak**
 * at the half-and-half mix here. That is the whole change, and it is bought
 * against [LIQUID_SWELL_AMOUNT]'s inequality — a short octave has a large gradient
 * for its amplitude, which is why [LIQUID_WARP] had to come down to pay for it.
 */
internal const val LIQUID_SHORT_WEIGHT = 0.50f

/** The rest of the swell: the long, slow octave. */
internal const val LIQUID_LONG_WEIGHT = 1f - LIQUID_SHORT_WEIGHT

/** The long octave's spatial frequencies, in radians per disc radius. */
internal const val LIQUID_LONG_FX = 1.15f
internal const val LIQUID_LONG_FY = 0.95f

/**
 * The short octave's, chosen so a little over one wave spans the disc: fewer and
 * it is another tilt, more and it is ripple rather than shape — and its gradient
 * grows with frequency while the curvature it buys does not, so past about one
 * wave it costs the single-front inequality for nothing.
 */
internal const val LIQUID_SHORT_FX = 2.90f
internal const val LIQUID_SHORT_FY = 3.35f

/**
 * How far the swell displaces its own input, using the long octave only.
 *
 * Domain warping is what turns a sine into something organic, and it used to be
 * the *only* source of irregularity here, at 0.55. It is 0.3 now because the
 * gradient budget in [LIQUID_SWELL_AMOUNT] is shared: the warp multiplies the
 * whole swell's gradient by `1 + 0.3·√2·0.746 = 1.32`, and spending less there is
 * what leaves room for the short octave, which was measured to buy about three
 * times as much visible curvature per unit of gradient. Warping with the long
 * octave alone also keeps the displacement smooth, so the short octave's shape is
 * distorted rather than scrambled.
 */
internal const val LIQUID_WARP = 0.30f

/**
 * Half the width of the `smoothstep`, in field units — how soft the fade between
 * the two colours is.
 *
 * The band spans `2·`[LIQUID_EDGE]` / `[LIQUID_FRONT_GRADIENT] of the button's
 * *diameter*, so this value is readable as a fraction: at 0.55 the blur is a
 * little under half the button wide. It was 0.28 — half this — and the user asked
 * for a bigger fade, so the two colours meet across a broad soft gradient rather
 * than at a defined edge.
 *
 * It is not free: it is a full quarter of the 2.14 the tide has to clear for the
 * disc to be one colour, so a softer edge is directly a larger
 * [LIQUID_TIDE_AMPLITUDE].
 */
internal const val LIQUID_EDGE = 0.55f

/**
 * The tide's travel, in field units — and so the one knob that decides how much of
 * each takeover is spent at rest.
 *
 * `G + S + E = 2.14` is the largest the rest of the field can be anywhere on the
 * disc. While `|tide|` exceeds that, every pixel is past the `smoothstep`'s clamp
 * and the disc is **exactly** one brand colour, rim included. The tide is a
 * constant-speed ramp from `−A` to `+A`, so the fraction of a takeover spent there
 * is just `1 − 2.14/A = 22%` guaranteed, which is 0.8 s.
 *
 * Measured rather than bounded it is more, because the swell does not actually
 * reach ±1 everywhere at once: the disc is in fact pure for **0.96–1.53 s**
 * (1.32 s mean, 37% of the loop), leaving 2.28 s of visible movement. That is the
 * "a second, or 1.5 s" the user asked for, with movement still the majority.
 *
 * The history is the useful part. This was 1.8, then 1.3 — both *below* 2.14, on
 * the theory that a button which never quite settles reads as livelier. It does
 * not; it reads as a button with a stain on one side, because the front never
 * clears the disc and the leftover shows where the next colour is about to come
 * from. Raising it above the bound is the whole point of this constant now, and
 * anything below 2.14 reintroduces the defect.
 */
internal const val LIQUID_TIDE_AMPLITUDE = 2.75f

/** How many times the long octave drifts through a full cycle per loop. */
internal const val LIQUID_LONG_CYCLES = 5

/** And the short one. Coprime with the above, so their beat does not repeat. */
internal const val LIQUID_SHORT_CYCLES = 8

// ---------- the phase ----------

/**
 * The shader's phase for a frame at [timeMs], in radians, wrapped into `[0, 2π)`.
 *
 * Takes an **absolute** frame time rather than an elapsed one, and that is
 * deliberate: because the image is exactly periodic, a phase read straight off the
 * monotonic clock is continuous across every start and stop the loop makes. There
 * is no origin to remember, so pausing and resuming cannot produce the one
 * artefact this would otherwise have — a visible jump when the animation restarts
 * from zero while the button is still on screen showing the shape it had.
 *
 * `mod`, not `%`: the frame clock is monotonic so a negative input should not
 * happen, but `%` would answer a negative phase if one ever did, and a shader
 * given one would still draw — slightly differently, once, in a way nobody would
 * ever reproduce.
 */
internal fun liquidPhase(timeMs: Long): Float =
    timeMs.mod(LIQUID_PERIOD_MS).toFloat() / LIQUID_PERIOD_MS * TWO_PI

/** Position within the takeover sequence at [phase]: a slot index plus a fraction. */
private fun liquidSlotPosition(phase: Float): Float =
    (phase / TWO_PI * LIQUID_SLOTS).coerceIn(0f, LIQUID_SLOTS - 1e-5f)

/** Which takeover is running at [phase], `0..`[LIQUID_SLOTS]`-1`. */
internal fun liquidSlot(phase: Float): Int = liquidSlotPosition(phase).toInt()

/** How far through that takeover [phase] is, `0..1`. */
internal fun liquidSlotProgress(phase: Float): Float =
    liquidSlotPosition(phase).let { it - it.toInt() }

/**
 * Which colour a takeover brings in: red on even slots, blue on odd.
 *
 * Expressed as the sign the front's normal and the tide are both multiplied by,
 * because that is the whole of it — flipping both turns "red floods in from θ"
 * into "blue floods in from θ" with the same arithmetic. It also means the flip
 * itself happens at a slot boundary, where `|tide|` is at its maximum and the disc
 * is clamped, so the sign change is invisible.
 */
private fun liquidSlotSign(slot: Int): Float = if (slot % 2 == 0) 1f else -1f

/**
 * How far the front has travelled at [phase], in field units.
 *
 * A constant-speed ramp from `−`[LIQUID_TIDE_AMPLITUDE] to `+` across a takeover,
 * times [liquidSlotSign] so that consecutive takeovers run the other way and the
 * value is continuous across every boundary. Positive is red.
 */
internal fun liquidTide(phase: Float): Float {
    val slot = liquidSlot(phase)
    return LIQUID_TIDE_AMPLITUDE * liquidSlotSign(slot) * (2f * liquidSlotProgress(phase) - 1f)
}

// ---------- the headings, which are the effect ----------

/**
 * The smallest turn between the headings of consecutive takeovers, and the
 * smallest distance either of them may sit from a straight **reversal**.
 *
 * Both matter and they are different failures. Two arrivals from nearly the same
 * heading is a wipe repeated; two arrivals 180° apart is a pendulum, and it is the
 * one the user described exactly: *"if the fab is red and blue went away from the
 * left, it shouldn't be the case it reappears from that same spot again"*. Blue
 * leaving to the left means red arrived from the right; blue coming back from the
 * left is therefore the next heading landing 180° from the last one. So the
 * accepted band for `separation(θₖ, θₖ₊₁)` is `[40°, 140°]` — away from 0 and away
 * from π, which is *not* the same as "as far apart as possible".
 */
internal const val LIQUID_MIN_TURN = (40.0 * PI / 180.0).toFloat()

/** How far apart headings must stay within [LIQUID_HEADING_WINDOW] of each other. */
internal const val LIQUID_MIN_REPEAT = (25.0 * PI / 180.0).toFloat()

/** How many takeovers back a heading has to stay clear of. */
internal const val LIQUID_HEADING_WINDOW = 4

/** How many hashes to try before settling for the least bad candidate. */
private const val LIQUID_HEADING_TRIES = 64

private val LIQUID_SEED_TURN = 0x9E3779B1.toInt()
private val LIQUID_SEED_SALT = 0x85EBCA6B.toInt()
private const val LIQUID_SEED_BASE = 0x2545F491
private const val LIQUID_SEED_SLOT = 0x27D4EB2D
private const val LIQUID_SEED_LONG = 0x165667B1
private val LIQUID_SEED_SHORT = 0x9E3779B9.toInt()
private val LIQUID_SEED_ORIGIN_X = 0xC2B2AE35.toInt()
private const val LIQUID_SEED_ORIGIN_Y = 0x27D4EB2F

/**
 * A 32-bit integer hash — Wang-style xor-shift-multiply, the `lowbias32` constants.
 *
 * **Not** [kotlin.random.Random] and not the clock, on purpose. Everything about
 * this animation that is supposed to look arbitrary is a pure function of the
 * takeover index, so the sequence is the same on every device and every run, a
 * test can assert its properties, and there is no state to carry across the
 * composable being disposed and recomposed.
 */
internal fun liquidHash(seed: Int): Int {
    var h = seed
    h = h xor (h ushr 16)
    h *= 0x7feb352d
    h = h xor (h ushr 15)
    h *= 0x846ca68b.toInt()
    h = h xor (h ushr 16)
    return h
}

/** [liquidHash] mapped onto `[0, 1)`, off the high bits, which mix best. */
internal fun liquidUnit(seed: Int): Float =
    (liquidHash(seed) ushr 8).toFloat() / (1 shl 24).toFloat()

/** Smallest angle between two headings, in radians, `0..π`. */
internal fun liquidSeparation(a: Float, b: Float): Float {
    val d = (a - b).mod(TWO_PI)
    return min(d, TWO_PI - d)
}

/**
 * The heading each takeover's colour arrives from — the thing the user was
 * objecting to, and the reason this file no longer has a closed-form angle in it.
 *
 * What was here before walked eight fixed compass points 135° apart, and the
 * verdict was *"making an animation which simply moves the colours in a rotation
 * like a sphere is not in my definition of random"*. It was not random; it was a
 * single rotating normal, and any construction that closes a loop out of one
 * continuously turning angle will read that way.
 *
 * So the headings are **drawn** instead, by hashing the takeover index, and only
 * the ones that clear [LIQUID_MIN_TURN] and [LIQUID_MIN_REPEAT] against their
 * neighbours are kept — rejection sampling, up to [LIQUID_HEADING_TRIES] tries,
 * keeping the least bad candidate if every one is rejected. The checks are
 * *circular*, so slot 23's neighbours include slot 0 and the sequence is still
 * legal across the wrap.
 *
 * Deterministic, so it is the same list on every device, and a test can assert the
 * properties rather than eyeballing the animation: consecutive turns land between
 * 44° and 132°, no two headings within four takeovers of each other are closer
 * than 28°, all eight octants are used, and the largest empty arc on the dial is
 * 44°.
 *
 * Building this at class-init costs one pass of at most 24 × 64 hashes.
 */
internal val LIQUID_HEADINGS: FloatArray = buildLiquidHeadings()

private fun buildLiquidHeadings(): FloatArray {
    val out = FloatArray(LIQUID_SLOTS) { Float.NaN }
    for (k in 0 until LIQUID_SLOTS) {
        var best = 0f
        var bestScore = -Float.MAX_VALUE
        for (salt in 0 until LIQUID_HEADING_TRIES) {
            val candidate =
                liquidUnit(k * LIQUID_SEED_TURN + salt * LIQUID_SEED_SALT + LIQUID_SEED_BASE) * TWO_PI
            // How comfortably this candidate clears every constraint that binds it;
            // negative means it breaks one.
            var score = Float.MAX_VALUE
            for (j in 0 until LIQUID_SLOTS) {
                if (out[j].isNaN()) continue
                val gap = min((j - k).mod(LIQUID_SLOTS), (k - j).mod(LIQUID_SLOTS))
                if (gap == 0 || gap > LIQUID_HEADING_WINDOW) continue
                val apart = liquidSeparation(candidate, out[j])
                val margin = if (gap == 1) {
                    // Neither a repeat nor a reversal: inside the band, both ends.
                    min(apart - LIQUID_MIN_TURN, (PI.toFloat() - LIQUID_MIN_TURN) - apart)
                } else {
                    apart - LIQUID_MIN_REPEAT
                }
                if (margin < score) score = margin
            }
            if (score > bestScore) {
                bestScore = score
                best = candidate
            }
            if (score >= 0f) break
        }
        out[k] = best
    }
    return out
}

/** The heading the colour taking over in slot [k] arrives from, in `[0, 2π)`. */
internal fun liquidArrivalAngle(k: Int): Float = LIQUID_HEADINGS[k.mod(LIQUID_SLOTS)]

// ---------- the per-frame state ----------

/**
 * Everything about one frame that varies, and every uniform the shader is given
 * beyond the geometry.
 *
 * Computing this on the CPU is what makes the AGSL branch-free and hash-free, and
 * what makes "the shader, the fallback brush and the tests agree" a fact rather
 * than a convention: there is one definition of where the front is and what shape
 * it has, and all three read it from here.
 */
internal data class LiquidFrame(
    /** The front's normal, pointing at the red side. Unit length. */
    val dirX: Float,
    val dirY: Float,
    /** How far the front has been pushed along that normal. */
    val tide: Float,
    /** The long octave's phase, and the short one's. */
    val longPhase: Float,
    val shortPhase: Float,
    /** Where in the swell's field this takeover is sampled from. */
    val originX: Float,
    val originY: Float,
)

/**
 * The frame at [phase].
 *
 * The heading, the two swell phases and the swell origin are all constant across a
 * takeover and jump at its boundary. That is only safe because of the coverage
 * requirement: at a boundary `|tide|` is at its maximum, the disc is clamped to a
 * single colour, and a discontinuity in the shape of a front nobody can see is not
 * a discontinuity in the image. It is also *why* the shape can vary per takeover
 * at all — a continuously-varying swell is the thing that made every arrival look
 * like the same curve turned round.
 *
 * The origin is the second half of that. Two takeovers with the same swell phases
 * but a different origin are looking at different parts of the same field, which
 * is a different silhouette rather than the same one shifted; ±4 radii is several
 * wavelengths of both octaves.
 */
internal fun liquidFrame(phase: Float): LiquidFrame {
    val slot = liquidSlot(phase)
    val sign = liquidSlotSign(slot)
    val heading = liquidArrivalAngle(slot)
    val seed = slot * LIQUID_SEED_SLOT
    return LiquidFrame(
        dirX = sign * cos(heading),
        dirY = sign * sin(heading),
        tide = LIQUID_TIDE_AMPLITUDE * sign * (2f * liquidSlotProgress(phase) - 1f),
        longPhase = (LIQUID_LONG_CYCLES * phase + liquidUnit(seed + LIQUID_SEED_LONG) * TWO_PI)
            .mod(TWO_PI),
        shortPhase = (LIQUID_SHORT_CYCLES * phase + liquidUnit(seed + LIQUID_SEED_SHORT) * TWO_PI)
            .mod(TWO_PI),
        originX = liquidUnit(seed + LIQUID_SEED_ORIGIN_X) * 8f - 4f,
        originY = liquidUnit(seed + LIQUID_SEED_ORIGIN_Y) * 8f - 4f,
    )
}

// ---------- the field, mirrored so a JVM can measure it ----------

/** One octave: a pair of crossed sines, `|wave| ≤ 1`, gradient `≤ ½·|(fx, fy)|`. */
private fun liquidWave(qx: Float, qy: Float, fx: Float, fy: Float, phase: Float): Float =
    0.5f * (sin(qx * fx + phase) + sin(qy * fy - phase))

/**
 * The field at `(px, py)` — the disc is `|p| ≤ 1` — in the same field units the
 * tide is measured in. Negative is blue, positive is red, and the front is the
 * zero set.
 *
 * An exact mirror of [LIQUID_AGSL]'s `main`, built from the same constants. It
 * exists so `LiquidFabTest` can sample the whole disc and measure the three things
 * that used to need a GPU and a pair of eyes: that a takeover really does reach
 * 100% coverage, that the front is a curve over the visible crop, and that there
 * is only ever one of it.
 */
internal fun liquidField(px: Float, py: Float, frame: LiquidFrame): Float {
    val sx = px + frame.originX
    val sy = py + frame.originY
    val warpX = LIQUID_WARP *
        liquidWave(sx + 0.7f, sy - 1.3f, LIQUID_LONG_FX, LIQUID_LONG_FY, frame.longPhase)
    val warpY = LIQUID_WARP *
        liquidWave(sx - 1.1f, sy + 0.6f, LIQUID_LONG_FX, LIQUID_LONG_FY, frame.longPhase + 2.1f)
    val qx = sx + warpX
    val qy = sy + warpY
    val swell =
        LIQUID_LONG_WEIGHT * liquidWave(qx, qy, LIQUID_LONG_FX, LIQUID_LONG_FY, frame.longPhase) +
            LIQUID_SHORT_WEIGHT *
            liquidWave(qx, qy, LIQUID_SHORT_FX, LIQUID_SHORT_FY, frame.shortPhase)
    return LIQUID_FRONT_GRADIENT * (px * frame.dirX + py * frame.dirY) +
        LIQUID_SWELL_AMOUNT * swell +
        frame.tide
}

/**
 * How red the pixel at `(px, py)` is: exactly `0` for pure blue, exactly `1` for
 * pure red, and the `smoothstep` between.
 *
 * The exactness is the point — `smoothstep` clamps, so "100% of the surface is one
 * colour" is a statement a test can make about every sampled pixel with no
 * tolerance at all, which is what [LIQUID_TIDE_AMPLITUDE] is sized to deliver.
 */
internal fun liquidMixAt(px: Float, py: Float, frame: LiquidFrame): Float {
    val t = ((liquidField(px, py, frame) + LIQUID_EDGE) / (2f * LIQUID_EDGE)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/**
 * The one bound the whole coverage argument rests on: the largest the field can be
 * anywhere on the disc with the tide taken out, plus the `smoothstep`'s half-width.
 *
 * `|dot(p, dir)| ≤ 1` on the disc and `|swell| ≤ 1` by construction, so a
 * `|tide|` past this clamps every pixel.
 */
internal const val LIQUID_CLAMP_BOUND =
    LIQUID_FRONT_GRADIENT + LIQUID_SWELL_AMOUNT + LIQUID_EDGE

// ---------- the shader ----------

/**
 * The front, the warp, the two octaves and the tide.
 *
 * The constants a JVM test or the fallback brush also needs are interpolated
 * rather than repeated. That buys one silent failure mode, which is why this is
 * `internal` and `LiquidFabTest` reads it: a constant whose `Float` rendering is
 * not a plain decimal — `1.0E-5` for a small one — is not a legal AGSL literal,
 * the whole shader fails to compile, and the only symptom is the FAB quietly using
 * [liquidFallbackBrush] forever on every device.
 *
 * There is no hashing and no branching here; everything that varies per takeover
 * arrives as a uniform from [liquidFrame]. This has never been compiled on a real
 * GPU.
 */
internal val LIQUID_AGSL = """
uniform float2 uSize;
uniform float2 uDir;
uniform float2 uPhase;
uniform float2 uOrigin;
uniform float uTide;
layout(color) uniform half4 uLow;
layout(color) uniform half4 uHigh;

// One octave: crossed sines, bounded by 1, gradient bounded by half the frequency
// magnitude. Two of these, one long and one short, are the whole swell.
float wave(float2 q, float2 f, float ph) {
    return 0.5 * (sin(q.x * f.x + ph) + sin(q.y * f.y - ph));
}

half4 main(float2 fragCoord) {
    float radius = 0.5 * min(uSize.x, uSize.y);
    float2 p = (fragCoord - 0.5 * uSize) / max(radius, 1.0);

    // This takeover's window onto the swell field.
    float2 s = p + uOrigin;
    float2 fLong = float2(${LIQUID_LONG_FX}, ${LIQUID_LONG_FY});
    float2 fShort = float2(${LIQUID_SHORT_FX}, ${LIQUID_SHORT_FY});

    // The domain warp: the long octave displacing the swell's own input, which is
    // what keeps the curve organic rather than sinusoidal.
    float2 q = s + ${LIQUID_WARP} * float2(
        wave(s + float2(0.7, -1.3), fLong, uPhase.x),
        wave(s + float2(-1.1, 0.6), fLong, uPhase.x + 2.1));

    // Long octave for the sweep of the curve, short octave for the bend that is
    // actually visible inside a 56 dp disc.
    float swell = ${LIQUID_LONG_WEIGHT} * wave(q, fLong, uPhase.x)
                + ${LIQUID_SHORT_WEIGHT} * wave(q, fShort, uPhase.y);

    // The plane ramp dominates: its gradient is larger than the swell term's, so f
    // is monotone along uDir and there is exactly one front on screen. Past
    // |uTide| = ${LIQUID_CLAMP_BOUND} the smoothstep clamps and the disc is one colour.
    float f = ${LIQUID_FRONT_GRADIENT} * dot(p, uDir) + ${LIQUID_SWELL_AMOUNT} * swell + uTide;
    return mix(uLow, uHigh, half(smoothstep(-${LIQUID_EDGE}, ${LIQUID_EDGE}, f)));
}
"""

/**
 * The liquid, filling whatever this modifier is given — in practice the `+` FAB's
 * whole 56 dp, drawn behind its icon.
 *
 * Sits in the FAB's **content** slot rather than replacing its `containerColor`,
 * which is what keeps the two things the button already had: its shadow (the
 * `Surface` stays opaque, in [NothingLiquidBlue], so the elevation behaves exactly
 * as it did) and its ripple (drawn by the `clickable` above the content, so it
 * still washes over the fill).
 */
@Composable
internal fun LiquidFabFill(modifier: Modifier = Modifier) {
    // One shader for the life of the composition. Compilation happens here, once;
    // a failure is logged once and drops this to the fallback brush forever after.
    val shader = remember {
        runCatching { RuntimeShader(LIQUID_AGSL) }
            .onSuccess {
                it.setColorUniform("uLow", NothingLiquidBlue.toArgb())
                it.setColorUniform("uHigh", NothingLiquidRed.toArgb())
            }
            .onFailure { DebugLog.w("LiquidFab", "AGSL unavailable, falling back: ${it.message}") }
            .getOrNull()
    }
    // The brush wraps the shader by reference, so the per-frame uniform writes
    // below are what the next draw picks up; nothing is reallocated per frame.
    val brush = remember(shader) { shader?.let { ShaderBrush(it) } }

    val phase = remember { mutableFloatStateOf(liquidPhase(0L)) }

    var resumed by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        resumed = true
        onPauseOrDispose { resumed = false }
    }
    // The frame loop. Exists exactly while the app is resumed AND this composable
    // is composed — which `NavFab` makes true only while the `+` is on screen —
    // and `collectLatest` cancels it the moment the first stops holding.
    LaunchedEffect(Unit) {
        snapshotFlow { resumed }.collectLatest { running ->
            if (!running) return@collectLatest
            while (true) {
                withFrameMillis { phase.floatValue = liquidPhase(it) }
            }
        }
    }

    Spacer(
        modifier.drawBehind {
            // The ONLY read of the phase, and it is in the draw lambda: a tick
            // invalidates this node's draw and nothing above it.
            val frame = liquidFrame(phase.floatValue)
            if (shader != null && brush != null) {
                shader.setFloatUniform("uSize", size.width, size.height)
                shader.setFloatUniform("uDir", frame.dirX, frame.dirY)
                shader.setFloatUniform("uPhase", frame.longPhase, frame.shortPhase)
                shader.setFloatUniform("uOrigin", frame.originX, frame.originY)
                shader.setFloatUniform("uTide", frame.tide)
                drawCircle(brush)
            } else {
                drawCircle(liquidFallbackBrush(frame, size.width, size.height))
            }
        },
    )
}

/**
 * The no-shader path: the same two colours, the same headings, the same tide —
 * with a straight edge instead of a curved one.
 *
 * It is the shader's own field with the swell deleted, solved for where the
 * `smoothstep` starts and ends: `f = G·dot(p, dir) + tide` hits `−`[LIQUID_EDGE]
 * at `dot(p, dir) = (−E − tide)/G` and `+E` at `(E − tide)/G`, and those two
 * points, in radius units along the front's normal, are exactly the gradient's
 * ends. So this is not a hand-tuned lookalike; it is the same arithmetic with one
 * term removed, and it inherits the property that matters — past
 * `|tide| = G + E` the gradient's whole visible span is off the disc and Compose's
 * clamp tile mode paints one flat brand colour, which is the coverage requirement
 * met on a device that could not compile the shader.
 *
 * It settles slightly *sooner* than the shader does, by the swell's amplitude, and
 * so rests a little longer between takeovers. That is the honest consequence of
 * having no swell to wait for.
 */
private fun liquidFallbackBrush(frame: LiquidFrame, width: Float, height: Float): Brush {
    val cx = width / 2f
    val cy = height / 2f
    val radius = (min(width, height) / 2f).coerceAtLeast(1f)
    val blueEnd = (-LIQUID_EDGE - frame.tide) / LIQUID_FRONT_GRADIENT * radius
    val redEnd = (LIQUID_EDGE - frame.tide) / LIQUID_FRONT_GRADIENT * radius
    return Brush.linearGradient(
        colors = listOf(NothingLiquidBlue, NothingLiquidRed),
        start = Offset(cx + frame.dirX * blueEnd, cy + frame.dirY * blueEnd),
        end = Offset(cx + frame.dirX * redEnd, cy + frame.dirY * redEnd),
    )
}

/**
 * Where the fallback brush's gradient is entirely off the disc, so that path is a
 * flat brand colour too. Exposed for the test that keeps the two in step.
 */
internal const val LIQUID_FALLBACK_CLAMP = LIQUID_FRONT_GRADIENT + LIQUID_EDGE
