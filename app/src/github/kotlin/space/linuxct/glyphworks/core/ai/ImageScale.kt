package space.linuxct.glyphworks.core.ai

/**
 * How large an attached photo is allowed to reach the model, and the two pieces
 * of arithmetic that get it there.
 *
 * ## Why any of this is in `core/`
 *
 * The Android half of attaching a photo is three calls — `BitmapFactory.decode`,
 * `createScaledBitmap`, `compress` — and every one of them is an empty stub in
 * the android.jar unit tests compile against. The *decisions* around them are not
 * calls at all: how much to subsample while decoding, and what the final pixel
 * dimensions should be. Both are integer arithmetic over two numbers, both are
 * exactly where an off-by-one produces either a blown-up request or a blurred
 * photo, and both run here under plain JUnit. `ai/ImageAttachments` is left as
 * the three calls and nothing else.
 *
 * ## Why a cap at all
 *
 * A modern phone camera produces 4000x3000 pixels. Base64 of that as JPEG is
 * several megabytes of request body, per image, on a connection the user is
 * waiting on — and the model reads it downsampled anyway. 1024 px on the long
 * edge is the size the Responses API's own `detail: "high"` tiling works in, so
 * anything beyond it is bytes spent to be thrown away at the far end.
 */
object ImageScale {

    /** The longest edge, in pixels, that an attached image is sent at. */
    const val MAX_EDGE = 1024

    /**
     * JPEG quality for the re-encode.
     *
     * A photograph, not artwork: 85 is the point on the curve where the next
     * quality step costs noticeably more bytes than it returns in detail, and
     * the model is looking for shapes and brightness rather than grain.
     */
    const val JPEG_QUALITY = 85

    /**
     * The `inSampleSize` to decode with: the largest power of two that still
     * leaves the long edge at or above [maxEdge].
     *
     * Subsampling during the decode is what keeps the *full* image out of
     * memory — a 4000x3000 photo is 48 MB as ARGB_8888 and this app has no
     * business allocating that to send a picture of a cat. Never overshooting
     * [maxEdge] matters just as much: decoding straight to something smaller
     * than the target and then scaling up would send an upsampled, soft image
     * while claiming full size.
     *
     * Powers of two only, because that is the contract `BitmapFactory` honours —
     * any other value is rounded down to one by the decoder, so computing
     * anything else here would be computing a number that is then ignored.
     */
    fun sampleSize(width: Int, height: Int, maxEdge: Int = MAX_EDGE): Int {
        val longest = maxOf(width, height)
        if (longest <= 0 || maxEdge <= 0) return 1
        var sample = 1
        while (longest / (sample * 2) >= maxEdge) sample *= 2
        return sample
    }

    /**
     * The pixel size to send at: [width] x [height] shrunk so that its long edge
     * is at most [maxEdge], with the aspect ratio kept and neither side ever
     * reaching zero.
     *
     * An image already inside the cap is returned untouched — re-encoding a small
     * image at a "target" size would resample it for nothing.
     *
     * Integer arithmetic in `Long`, deliberately: `w * maxEdge` overflows `Int`
     * at a little over two million pixels of width, which is not a photograph but
     * is very much a screenshot of a panorama, and the failure would be a
     * negative dimension rather than a large one.
     */
    fun targetSize(width: Int, height: Int, maxEdge: Int = MAX_EDGE): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return width to height
        val longest = maxOf(width, height)
        if (longest <= maxEdge) return width to height
        val w = (width.toLong() * maxEdge / longest).toInt().coerceAtLeast(1)
        val h = (height.toLong() * maxEdge / longest).toInt().coerceAtLeast(1)
        return w to h
    }

    /** Whether [targetSize] would change anything. */
    fun needsScaling(width: Int, height: Int, maxEdge: Int = MAX_EDGE): Boolean =
        width > 0 && height > 0 && maxOf(width, height) > maxEdge
}
