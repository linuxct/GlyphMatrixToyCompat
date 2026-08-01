package space.linuxct.glyphmatrixtoycompat.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The arithmetic behind attaching a photo.
 *
 * Everything Android does with an image here — decode, scale, compress — is a
 * stub under plain JUnit, so [ImageScale] exists to hold the parts that are
 * decisions rather than calls. They are worth holding: a phone camera hands this
 * twelve megapixels, the request body is base64 of whatever comes out, and the
 * two failure modes are a multi-megabyte upload and a photo that arrives soft
 * because it was decoded smaller than the target and then scaled back up.
 */
class ImageScaleTest {

    // region the target size

    @Test
    fun `a photo from a phone camera comes down to the cap on its long edge`() {
        val (w, h) = ImageScale.targetSize(4032, 3024)
        assertEquals(ImageScale.MAX_EDGE, w)
        assertEquals(768, h)
    }

    @Test
    fun `a portrait photo comes down on its own long edge`() {
        val (w, h) = ImageScale.targetSize(3024, 4032)
        assertEquals(768, w)
        assertEquals(ImageScale.MAX_EDGE, h)
    }

    /**
     * The property that actually matters, over a spread of real and absurd
     * shapes: nothing leaves here longer than the cap, nothing has a side of
     * zero, and the shape is the shape that went in.
     */
    @Test
    fun `nothing exceeds the cap, collapses to zero, or changes shape`() {
        val sizes = listOf(
            4032 to 3024, 3024 to 4032, 8000 to 6000, 1024 to 1024, 1025 to 1,
            1 to 1025, 12000 to 9, 9 to 12000, 2000 to 2000, 1080 to 1920,
        )
        for ((width, height) in sizes) {
            val (w, h) = ImageScale.targetSize(width, height)
            val what = "${width}x$height -> ${w}x$h"
            assertTrue(what, maxOf(w, h) <= ImageScale.MAX_EDGE)
            assertTrue(what, w >= 1 && h >= 1)
            // The aspect is only meaningful while the one-pixel floor is not
            // binding. A 12000x9 banner cannot be 1024 px long and keep its
            // ratio without a sub-pixel side, and a side of zero is not an
            // image — the floor wins, deliberately, and the assertion above is
            // the one that matters for that shape.
            if (minOf(w, h) > 1) {
                val before = width.toDouble() / height
                val after = w.toDouble() / h
                // Integer pixels cannot hold an exact ratio; 2% is well inside
                // "the same picture" and well outside a transposed or squashed
                // one.
                assertTrue("$what distorted", abs(before - after) / before < 0.02)
            }
        }
    }

    @Test
    fun `an image already within the cap is left exactly as it is`() {
        assertEquals(800 to 600, ImageScale.targetSize(800, 600))
        assertEquals(1024 to 1024, ImageScale.targetSize(1024, 1024))
        assertFalse(ImageScale.needsScaling(1024, 1024))
        assertTrue(ImageScale.needsScaling(1025, 10))
    }

    @Test
    fun `a size that has not been measured yet is passed through untouched`() {
        // What `BitmapFactory` reports for a stream it could not read at all.
        assertEquals(-1 to -1, ImageScale.targetSize(-1, -1))
        assertEquals(0 to 0, ImageScale.targetSize(0, 0))
        assertFalse(ImageScale.needsScaling(0, 0))
    }

    /**
     * `width * maxEdge` overflows `Int` on a wide enough image, and the symptom
     * would be a negative dimension handed to `createScaledBitmap` rather than
     * anything as loud as an exception.
     */
    @Test
    fun `an absurdly wide image does not overflow into a negative size`() {
        val (w, h) = ImageScale.targetSize(3_000_000, 4)
        assertEquals(ImageScale.MAX_EDGE, w)
        assertTrue(h >= 1)
    }

    // endregion

    // region the decode's sample size

    @Test
    fun `sampling never lands below the target, so nothing is ever scaled up`() {
        val sizes = listOf(4032 to 3024, 8000 to 6000, 2049 to 100, 2048 to 100, 1024 to 1024)
        for ((width, height) in sizes) {
            val sample = ImageScale.sampleSize(width, height)
            val decodedLongEdge = maxOf(width, height) / sample
            assertTrue(
                "${width}x$height sampled by $sample decodes to $decodedLongEdge",
                decodedLongEdge >= ImageScale.MAX_EDGE || maxOf(width, height) < ImageScale.MAX_EDGE,
            )
        }
    }

    @Test
    fun `sampling is always a power of two, which is all the decoder honours`() {
        val samples = listOf(4032 to 3024, 8000 to 6000, 800 to 600, 20000 to 20000)
            .map { ImageScale.sampleSize(it.first, it.second) }
        samples.forEach { assertEquals("$it is not a power of two", 0, it and (it - 1)) }
    }

    @Test
    fun `an image at or below the cap is decoded whole`() {
        assertEquals(1, ImageScale.sampleSize(1024, 768))
        assertEquals(1, ImageScale.sampleSize(640, 480))
        assertEquals(1, ImageScale.sampleSize(0, 0))
    }

    @Test
    fun `a large photo is subsampled rather than decoded at full size`() {
        assertEquals(2, ImageScale.sampleSize(4032, 3024))
        assertEquals(4, ImageScale.sampleSize(8000, 6000))
    }

    // endregion

    /**
     * The other half of the send path, and the one thing about it a test can see:
     * the wire format's data URL. A missing comma or a stray space here is a
     * request the backend rejects with no explanation worth reading.
     */
    @Test
    fun `the data url is well formed`() {
        val url = ChatWire.imageDataUrl("QUJD")
        assertEquals("data:image/jpeg;base64,QUJD", url)
        assertTrue(url.startsWith("data:image/jpeg;base64,"))
        assertEquals("QUJD", url.substringAfter("base64,"))
        assertEquals(
            "data:image/png;base64,QUJD",
            ChatWire.imageDataUrl("QUJD", "image/png"),
        )
    }
}
