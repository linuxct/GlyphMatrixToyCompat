package space.linuxct.glyphmatrixtoycompat.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Base64
import space.linuxct.glyphmatrixtoycompat.core.DebugLog
import space.linuxct.glyphmatrixtoycompat.core.ai.ChatWire
import space.linuxct.glyphmatrixtoycompat.core.ai.ImageScale
import java.io.ByteArrayOutputStream

/**
 * One photo the user picked, ready to send and ready to show.
 *
 * [dataUrl] is built once, at attach time rather than at send time, for two
 * reasons: the work is a decode and a JPEG encode and doing it while the user is
 * still typing costs them nothing, and an image this app cannot read is then
 * reported the moment they pick it instead of failing the turn they were waiting
 * on. [thumbnail] is the same decode, kept small, so the chip in the input row
 * shows the picture rather than the word "photo".
 */
class AttachedImage(
    /** Unique within one composer; only ever used to remove the right chip. */
    val id: Long,
    /** `data:image/jpeg;base64,…`, as [ChatWire.imageDataUrl] builds it. */
    val dataUrl: String,
    val thumbnail: Bitmap?,
)

/**
 * Turns a picked image into something that can be put in a request body.
 *
 * ## Downscale, then re-encode, always
 *
 * The picker hands back a `content://` URI to whatever the camera wrote — 12
 * megapixels of HEIC or JPEG, often several megabytes. Base64 inflates by a
 * third on top of that, and the whole thing goes into one request the user is
 * watching a spinner for. [ImageScale] caps the long edge at 1024 px and the
 * result is re-encoded as JPEG, which also normalises HEIC, PNG and WebP into
 * the one format the wire format's data URL claims.
 *
 * The decode is subsampled ([ImageScale.sampleSize]) so the full-size bitmap is
 * never allocated: on a 4000x3000 photo that is the difference between 48 MB and
 * 3 MB of heap for an image that is about to be thrown away anyway.
 *
 * ## Orientation
 *
 * A phone camera stores portrait photos as landscape pixels plus an EXIF tag,
 * and `BitmapFactory` does not apply it. Sending the raw pixels would hand the
 * model a picture lying on its side — which, for the one job an attached photo
 * has here (being turned into art), is the difference between a face and a
 * puzzle. The tag is read and applied; a file with no readable EXIF is left
 * alone, which is the correct answer for a PNG or a screenshot.
 *
 * ## Nothing here throws
 *
 * A URI can be revoked between the picker returning and this running, a file can
 * be a zero-byte placeholder from a cloud provider, a decoder can simply refuse.
 * All of them are null, and the caller says "that image could not be attached" —
 * losing a photo must not lose the conversation.
 */
internal fun readAttachment(context: Context, uri: Uri, id: Long): AttachedImage? = try {
    val bounds = decodeBounds(context, uri)
    val bitmap = decodeScaled(context, uri, bounds)
    if (bitmap == null) {
        null
    } else {
        val oriented = applyExifRotation(context, uri, bitmap)
        val sized = scaleToCap(oriented)
        val base64 = ByteArrayOutputStream().use { out ->
            if (!sized.compress(Bitmap.CompressFormat.JPEG, ImageScale.JPEG_QUALITY, out)) {
                return null
            }
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }
        val thumb = thumbnailOf(sized)
        AttachedImage(id = id, dataUrl = ChatWire.imageDataUrl(base64), thumbnail = thumb)
    }
} catch (e: Exception) {
    DebugLog.w(TAG, "could not attach an image: ${e.javaClass.simpleName}: ${e.message}")
    null
} catch (e: OutOfMemoryError) {
    // Not an Exception, and the one error a decoder realistically raises. A photo
    // is never worth taking the editor down for.
    DebugLog.w(TAG, "out of memory decoding an attachment")
    null
}

/** The image's dimensions, without allocating its pixels. */
private fun decodeBounds(context: Context, uri: Uri): BitmapFactory.Options {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    return options
}

/** The pixels, subsampled during the decode so the full size is never held. */
private fun decodeScaled(context: Context, uri: Uri, bounds: BitmapFactory.Options): Bitmap? {
    val options = BitmapFactory.Options().apply {
        inSampleSize = ImageScale.sampleSize(bounds.outWidth, bounds.outHeight)
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    return context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, options)
    }
}

/**
 * [bitmap] turned the way the camera was held, or unchanged if that is unknown.
 *
 * Lint asks for `androidx.exifinterface` here, and the answer is no: it is a
 * whole dependency, this feature's standing constraint is **zero new
 * dependencies**, and what androidx buys — parsers for formats and for platform
 * bugs from before API 24 — is irrelevant at `minSdk` 33 for the one tag this
 * reads. Written fully qualified so the suppression covers the reference rather
 * than an import line, which an annotation cannot reach.
 */
@Suppress("ExifInterface")
private fun applyExifRotation(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
    val orientation = try {
        context.contentResolver.openInputStream(uri)?.use {
            android.media.ExifInterface(it).getAttributeInt(
                android.media.ExifInterface.TAG_ORIENTATION,
                android.media.ExifInterface.ORIENTATION_NORMAL,
            )
        } ?: android.media.ExifInterface.ORIENTATION_NORMAL
    } catch (e: Exception) {
        android.media.ExifInterface.ORIENTATION_NORMAL
    }
    val matrix = Matrix()
    when (orientation) {
        android.media.ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
        android.media.ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
        android.media.ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
        android.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
        android.media.ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
        else -> return bitmap
    }
    return try {
        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    } catch (e: Exception) {
        bitmap
    } catch (e: OutOfMemoryError) {
        bitmap
    }
}

/** The last step down to [ImageScale.MAX_EDGE]; subsampling only got within 2x. */
private fun scaleToCap(bitmap: Bitmap): Bitmap {
    if (!ImageScale.needsScaling(bitmap.width, bitmap.height)) return bitmap
    val (w, h) = ImageScale.targetSize(bitmap.width, bitmap.height)
    return Bitmap.createScaledBitmap(bitmap, w, h, true)
}

/** A chip-sized copy, or null if it cannot be made — the chip degrades, not the send. */
private fun thumbnailOf(bitmap: Bitmap): Bitmap? = try {
    val (w, h) = ImageScale.targetSize(bitmap.width, bitmap.height, THUMBNAIL_EDGE)
    Bitmap.createScaledBitmap(bitmap, w, h, true)
} catch (e: Exception) {
    null
} catch (e: OutOfMemoryError) {
    null
}

/** Long edge of the preview shown in the composer, in pixels. */
private const val THUMBNAIL_EDGE = 192

private const val TAG = "GlyphAiImages"
