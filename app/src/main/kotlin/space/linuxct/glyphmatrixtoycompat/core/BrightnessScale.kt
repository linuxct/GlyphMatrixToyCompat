package space.linuxct.glyphmatrixtoycompat.core

import space.linuxct.glyphmatrixtoycompat.matrix.MAX_BRIGHTNESS
import kotlin.math.roundToInt

/**
 * Global brightness: multiplies every cell of the finished frame by the
 * brightness setting. Applied once per frame, after compositing, before the
 * push.
 *
 * Multiplicative, deliberately — this used to max-normalize (rescale so the
 * frame's BRIGHTEST cell landed on `brightness01 * 4095`), and that was wrong
 * in two ways that only a plain multiply fixes:
 *
 *  - **Grey was unreachable.** A frame whose brightest cell was 50 % grey got
 *    scaled *up* until it was white, so mid-grey art rendered identically to
 *    full-white art. Relative values *inside* a frame survived, but the frame
 *    as a whole always saturated.
 *  - **A frame's brightness depended on its own peak.** Frames were normalized
 *    independently, so any element that pulses (the Battery bolt, a rising
 *    charge particle) dragged the apparent brightness of everything else with
 *    it, and a ramp or fade spread across frames was flattened frame by frame.
 *
 * For a frame that already contains a 4095 cell the two are the *same*
 * function: `target / max` reduces to exactly `brightness01`. That is why the
 * house rule for screen art is "the brightest element is 4095, everything else
 * is a ratio of it" — art written that way renders exactly as it did before
 * this change and gains correct grey scaling for free. `ScreenBrightnessAudit`
 * in the tests holds every screen to it.
 */
object BrightnessScale {

    /**
     * The smallest value this function will ever emit for a cell that was lit.
     *
     * It is a property of the *scaling*, not of the art and not of the eye: the
     * one thing multiplying by a fraction must never do is round a lit cell down
     * to 0, because that deletes part of a picture rather than dimming it. One
     * count is the least that is still not zero, so it is the whole of what this
     * rule needs. It applies only on the scaling path — at full brightness
     * nothing is being scaled down, so there is nothing to protect against and
     * the frame passes through untouched (see [scale]).
     *
     * **This is deliberately not a perceptual floor**, and an earlier version
     * conflated the two: it floored every lit cell at `MAX_BRIGHTNESS / 64` = 63
     * at *all* brightnesses, which did two wrong things at once. It never fired
     * for the case its own comment described (a mid-grey cell at 10 % computes
     * to ≈205, comfortably above 63), and it fired constantly for a case nobody
     * asked for — anti-aliasing. `MatrixCanvas.discSoft`, `ring`, `arcRing` and
     * `TimerScreen`'s coverage-based sand surface all emit values in the low
     * tens at the soft end of an edge, and lifting those to 63 turned a smooth
     * fade into a hard 1.5 % step in art that was not being scaled at all.
     *
     * A perceptual floor — "keep a mid-grey cell visible when the panel is at
     * 10 %" — is a real and separate question. It would be a substantially
     * larger value, applied only at *low* brightness, and it cannot be derived
     * here: it is a property of the hardware and has to be tuned on-device by
     * eye. Nothing in this file attempts it, and nothing should until somebody
     * has looked at the panel in the dark.
     */
    private const val MIN_LIT = 1

    /**
     * How close to 1.0 counts as "no scaling at all". Derived rather than
     * guessed: the largest possible cell value is [MAX_BRIGHTNESS], so once
     * `1 - brightness01` drops below `0.5 / MAX_BRIGHTNESS` even that cell's
     * product rounds back to itself and the multiply is a provable no-op for
     * every possible input.
     */
    private const val UNITY_EPSILON = 0.5f / MAX_BRIGHTNESS

    /**
     * Returns [frame] scaled to [brightness01], with every cell that was lit
     * kept lit at [MIN_LIT] or above.
     *
     * **At full brightness the input is returned as-is** — the same object, not
     * a copy, and byte-identical by construction rather than by arithmetic that
     * happens to be an identity. Nothing is being scaled there, so there is
     * nothing for this function to decide, and the art reaches the panel exactly
     * as its author wrote it: soft edges, coverage fades and all. It is also the
     * overwhelmingly common case, which keeps the per-frame path allocation-free.
     *
     * A dark cell stays dark at every level: the floor lifts lit cells, it
     * never lights new ones. There is deliberately no special case for
     * `brightness01 == 0`, which is not a reachable setting — the slider bottoms
     * out at 0.05 and auto-brightness at [AutoBrightness.FLOOR] — and a lit cell
     * going dark is precisely what the floor exists to prevent.
     */
    fun scale(frame: IntArray, brightness01: Float): IntArray {
        val b = brightness01.coerceIn(0f, 1f)
        if (b >= 1f - UNITY_EPSILON) return frame
        val out = IntArray(frame.size)
        for (i in frame.indices) {
            val src = frame[i]
            if (src <= 0) continue
            val v = (src * b).roundToInt()
            out[i] = if (v < MIN_LIT) MIN_LIT else v
        }
        return out
    }
}
