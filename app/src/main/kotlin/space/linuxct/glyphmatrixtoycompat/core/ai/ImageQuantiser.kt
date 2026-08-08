package space.linuxct.glyphmatrixtoycompat.core.ai

import space.linuxct.glyphmatrixtoycompat.matrix.PanelMask
import kotlin.math.roundToInt

/**
 * One attached photo, reduced to the only thing this panel can carry.
 *
 * ## Why brightness and nothing else
 *
 * The Glyph Matrix is white LEDs with a 12-bit level and no colour at all, so
 * hue is information that has nowhere to go. Reducing to luminance at the point
 * the image is decoded — rather than carrying pixels around and reducing later —
 * is also what keeps this type free of `android.graphics`: it is an `IntArray`
 * and two dimensions, which is exactly as much as [ImageQuantiser] needs and
 * exactly as much as a JUnit test can construct by hand.
 *
 * ## The seam
 *
 * The Android half is `ai/ImageAttachments.readAttachment`, which already
 * decodes, subsamples and orients the picked photo for the wire; it takes one
 * more pass over the bitmap it is holding anyway and produces one of these. This
 * side owns every decision — the framing, the contrast, the threshold, the
 * palette — and none of it needs a device to run.
 *
 * [luminance] is row-major, `width * height` entries, each 0 (black) to 255
 * (white).
 */
class SourceImage(
    val width: Int,
    val height: Int,
    val luminance: IntArray,
) {
    /** False for an image with no pixels, or one whose array does not match its geometry. */
    val isUsable: Boolean
        get() = width > 0 && height > 0 && luminance.size == width * height
}

/**
 * Turns a photograph into a frame this app would store: downscaled to the panel,
 * masked to the disc, and quantised to a palette.
 *
 * ## Why this is not the model's job
 *
 * "Put this photo on my panel" was the weakest thing the assistant did. The
 * model can *see* an attached JPEG, but turning it into art meant eyeballing it
 * and then hand-writing 169 base36 characters — the same mechanical bookkeeping
 * that `GlyphAiTools.scrollFrames` exists to take away, with the added handicap
 * that nobody, model or human, can look at a photograph and say what its pixel
 * at (7, 4) averages to. So the arithmetic moves here, where it is a loop, and
 * the model is left with the part it is good at: looking at the result and
 * deciding whether it reads.
 *
 * ## The three decisions this makes, and why each is the one it is
 *
 * **Framing: fit, never crop.** The whole image is scaled to fit inside the
 * square with its aspect ratio kept, and the cells left over are dark. Cropping
 * to fill would be prettier for a portrait and catastrophic for the case that
 * actually turns up — a logo, a screenshot, a photo of some text — where the
 * thing being asked for is at the edges and would be silently cut off. Nothing
 * in a reference is thrown away without the model being able to see that it was.
 *
 * **Contrast: normalise to the image's own range.** A naive fixed threshold on
 * raw luminance turns most photographs into a grey smear, because a photograph
 * of anything is mostly mid-tones: the panel has 137 cells and no dynamic range
 * to spare, so the picture is stretched onto its own darkest and brightest
 * *sampled* cell first. Sampled, not overall: the corners of the square are not
 * LEDs, and letting a bright sky in a dead corner set the white point would dim
 * everything that is actually on the panel.
 *
 * **Threshold: chosen, not assumed.** With the range normalised, where to put
 * the on/off cut is still the difference between a silhouette and a blob, and
 * 0.5 is only right for an image whose subject happens to fill half the frame.
 * [otsu] picks the cut that best separates the sampled cells into two groups,
 * which is the classical answer and, more to the point, is *deterministic* — the
 * model can read the number back, disagree, and pass its own.
 *
 * ## Nothing here throws, and nothing here is Android
 *
 * Same contract as the rest of `core/ai`: this is called from inside a tool
 * call, where an exception would replace the model's only feedback with
 * "something went wrong". Degenerate input comes back as [Result.Unusable] or
 * [Result.Flat] so the tool can say which, in a sentence the model can act on.
 */
object ImageQuantiser {

    /**
     * The longest edge, in pixels, that the Android side should hand a
     * [SourceImage] over at.
     *
     * The picture is about to become 13 or 25 cells, so resolution past this
     * buys nothing at all: each cell of a 13x13 panel already averages about
     * fourteen pixels square, which is far more than enough to be a stable
     * average rather than a sample of where the noise was. It is a memory
     * number, not a quality one — the attachment itself is capped at
     * [ImageScale.MAX_EDGE], and holding four of those as `IntArray` luminance
     * for the length of a turn would be 16 MB for a picture of a cat.
     */
    const val SOURCE_EDGE = 192

    /**
     * A cell no source pixel landed on: outside the disc, or in the letterbox
     * beside a picture that is not square. Distinct from a *dark* cell, because
     * these must take no part in the range or the threshold — an image that is
     * mostly letterbox would otherwise set its own black point to the letterbox.
     */
    const val NO_SAMPLE = -1

    /**
     * How much spread the sampled cells must have before stretching them is
     * honest, out of 255.
     *
     * Below this the "picture" is a flat field — a blank wall, a solid
     * background, a photo of the sky — and normalising it would amplify JPEG
     * noise to full brightness and hand back a panel of confetti while claiming
     * to have drawn something. Eight levels out of 255 is comfortably below any
     * real subject and comfortably above the compression artefacts in a
     * uniform area.
     */
    const val MIN_RANGE = 8

    /** Gain around the mid-point. 1.0 changes nothing; below 1 flattens. */
    const val MIN_CONTRAST = 0.25
    const val MAX_CONTRAST = 4.0
    const val DEFAULT_CONTRAST = 1.0

    /**
     * The highest cut that still leaves somewhere for a lit cell to be. At 1.0
     * every cell would be below the threshold and the frame would be blank,
     * which is a result nobody asked for and the tool would only have to refuse.
     */
    const val MAX_THRESHOLD = 0.95

    /** What one conversion produced, or why there is nothing to show. */
    sealed interface Result {
        data class Ok(
            /** The frame, `size * size` base36 palette indices, row-major. */
            val cells: String,
            /** The cut actually used, whether it was passed in or chosen here. */
            val threshold: Double,
            /** True if [threshold] came from [otsu] rather than from the caller. */
            val automatic: Boolean,
            /** Cells above palette index 0. */
            val lit: Int,
            /** Cells a source pixel landed on: the picture's actual footprint. */
            val sampled: Int,
            /** Spread of the sampled cells before normalisation, out of 255. */
            val range: Int,
        ) : Result

        /** The image has a picture in it, but no contrast to draw with. See [MIN_RANGE]. */
        data class Flat(val range: Int) : Result

        /** No pixels, a broken geometry, or a palette with nothing to light. */
        data object Unusable : Result
    }

    /**
     * The panel-sized brightness grid [image] reduces to: one average per cell,
     * or [NO_SAMPLE] for a cell no part of the picture reaches.
     *
     * Box-averaged rather than point-sampled. At 13x13 a single source pixel per
     * cell would be picking one pixel out of about six thousand, which makes the
     * output depend on where the noise happened to be; averaging the whole box
     * is what makes a downscale look like the picture instead of like a
     * dithering accident.
     */
    fun sample(image: SourceImage, size: Int): IntArray {
        val out = IntArray(maxOf(size, 0) * maxOf(size, 0)) { NO_SAMPLE }
        if (size <= 0 || !image.isUsable) return out

        // Fit, not fill: the long edge lands on the panel and the short edge is
        // centred with dark either side. See this object's KDoc.
        val scale = minOf(size.toDouble() / image.width, size.toDouble() / image.height)
        val gw = (image.width * scale).roundToInt().coerceIn(1, size)
        val gh = (image.height * scale).roundToInt().coerceIn(1, size)
        val ox = (size - gw) / 2
        val oy = (size - gh) / 2

        for (cy in 0 until gh) {
            val py = oy + cy
            // Source rows this cell covers. Computed from the cell index rather
            // than accumulated, so no rounding error can build up across the
            // grid and leave the last row reading one pixel of the next one.
            val y0 = cy * image.height / gh
            val y1 = maxOf(y0 + 1, (cy + 1) * image.height / gh)
            for (cx in 0 until gw) {
                val px = ox + cx
                if (!PanelMask.contains(px, py, size)) continue
                val x0 = cx * image.width / gw
                val x1 = maxOf(x0 + 1, (cx + 1) * image.width / gw)
                var sum = 0L
                var n = 0L
                for (y in y0 until minOf(y1, image.height)) {
                    val row = y * image.width
                    for (x in x0 until minOf(x1, image.width)) {
                        sum += image.luminance[row + x].coerceIn(0, 255)
                        n++
                    }
                }
                if (n > 0) out[py * size + px] = (sum / n).toInt()
            }
        }
        return out
    }

    /**
     * [image] as a `cells` string for a [size] x [size] panel with [levelCount]
     * palette entries.
     *
     * @param threshold where the cut between off and lit goes, 0.0 to
     *   [MAX_THRESHOLD], on the normalised scale. Null asks [otsu] for it.
     * @param contrast gain around the mid-point, applied after normalisation.
     * @param invert flips light and dark, for a subject that is dark on a light
     *   background — a logo on white, a screenshot, a photo of printed text.
     *   The letterbox stays dark either way: it is not part of the picture.
     */
    fun quantise(
        image: SourceImage,
        size: Int,
        levelCount: Int,
        threshold: Double? = null,
        contrast: Double = DEFAULT_CONTRAST,
        invert: Boolean = false,
    ): Result {
        if (size <= 0 || levelCount < 2 || !image.isUsable) return Result.Unusable

        val samples = sample(image, size)
        var low = Int.MAX_VALUE
        var high = Int.MIN_VALUE
        var sampled = 0
        for (v in samples) {
            if (v == NO_SAMPLE) continue
            sampled++
            if (v < low) low = v
            if (v > high) high = v
        }
        if (sampled == 0) return Result.Unusable

        val range = high - low
        if (range < MIN_RANGE) return Result.Flat(range)

        val gain = contrast.coerceIn(MIN_CONTRAST, MAX_CONTRAST)
        val normalised = DoubleArray(samples.size)
        for (i in samples.indices) {
            if (samples[i] == NO_SAMPLE) continue
            var v = (samples[i] - low).toDouble() / range
            if (invert) v = 1.0 - v
            // Gain about the mid-point, so contrast pushes light and dark apart
            // rather than making the whole picture brighter.
            normalised[i] = (0.5 + (v - 0.5) * gain).coerceIn(0.0, 1.0)
        }

        val automatic = threshold == null
        // Chosen from the values as they will actually be judged — after the
        // inversion and the gain — so the number reported back can be handed
        // straight in again as `threshold` and reproduce this frame exactly.
        val cut = (threshold ?: otsu(samples, normalised)).coerceIn(0.0, MAX_THRESHOLD)

        var lit = 0
        val cells = CharArray(size * size) { '0' }
        for (i in samples.indices) {
            if (samples[i] == NO_SAMPLE) continue
            val level = levelFor(normalised[i], cut, levelCount)
            if (level > 0) lit++
            cells[i] = base36(level)
        }
        return Result.Ok(
            cells = String(cells),
            threshold = cut,
            automatic = automatic,
            lit = lit,
            sampled = sampled,
            range = range,
        )
    }

    /**
     * The palette index a normalised brightness becomes: 0 below [cut], then the
     * rest of the range spread evenly over indices 1..`levelCount - 1`.
     *
     * The discontinuity at [cut] is the point. A cell that is *just* light
     * enough to be on should be visibly on — the dimmest palette entry — rather
     * than fading in from black, because at this size a nearly-off cell reads as
     * off and the shape loses its edge.
     */
    fun levelFor(value: Double, cut: Double, levelCount: Int): Int {
        if (levelCount < 2) return 0
        if (value < cut) return 0
        val span = 1.0 - cut
        if (span <= 0.0) return levelCount - 1
        val step = ((value - cut) / span * (levelCount - 1)).toInt()
        return (1 + step).coerceIn(1, levelCount - 1)
    }

    /**
     * The threshold that best splits the sampled cells in two — Otsu's method,
     * over 256 buckets of the normalised values.
     *
     * It maximises the variance *between* the two groups, which is the same
     * thing as minimising the variance within them: the cut lands in the valley
     * between the subject and the background rather than at an arbitrary 0.5.
     * That is what makes a dark photo produce a silhouette instead of a black
     * panel, and it is why the default is this rather than a number.
     *
     * [samples] only says which cells count; the values judged are [normalised].
     */
    fun otsu(samples: IntArray, normalised: DoubleArray): Double {
        val histogram = IntArray(BUCKETS)
        var total = 0
        for (i in samples.indices) {
            if (samples[i] == NO_SAMPLE) continue
            val bucket = (normalised[i] * (BUCKETS - 1)).toInt().coerceIn(0, BUCKETS - 1)
            histogram[bucket]++
            total++
        }
        if (total == 0) return 0.5

        var sum = 0.0
        for (b in 0 until BUCKETS) sum += b.toDouble() * histogram[b]

        var backgroundWeight = 0
        var backgroundSum = 0.0
        var best = 0.0
        var bestBucket = BUCKETS / 2
        for (b in 0 until BUCKETS - 1) {
            backgroundWeight += histogram[b]
            if (backgroundWeight == 0) continue
            val foregroundWeight = total - backgroundWeight
            if (foregroundWeight == 0) break
            backgroundSum += b.toDouble() * histogram[b]
            val backgroundMean = backgroundSum / backgroundWeight
            val foregroundMean = (sum - backgroundSum) / foregroundWeight
            val between = backgroundWeight.toDouble() * foregroundWeight *
                (backgroundMean - foregroundMean) * (backgroundMean - foregroundMean)
            if (between > best) {
                best = between
                bestBucket = b
            }
        }
        // Everything in buckets up to and including [bestBucket] is background,
        // so the cut sits just above it: `value < threshold` is then exactly
        // "this cell is in the background half".
        return ((bestBucket + 1).toDouble() / (BUCKETS - 1)).coerceIn(0.0, MAX_THRESHOLD)
    }

    private const val BUCKETS = 256

    private fun base36(index: Int): Char =
        if (index < 10) ('0' + index) else ('a' + (index - 10))
}
