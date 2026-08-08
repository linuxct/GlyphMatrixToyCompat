package space.linuxct.glyphworks.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import space.linuxct.glyphworks.core.DebugLog
import space.linuxct.glyphworks.core.design.Design
import space.linuxct.glyphworks.core.design.DesignCodec
import java.io.File

/**
 * Moving designs in and out of the app: **export** to a file the user picks,
 * **import** from a file somebody else made, and **share** through the system
 * sheet.
 *
 * This is where the format stops being a private storage detail and becomes an
 * interchange format, so three things are load-bearing here:
 *
 * - **Nothing in this file validates a design.** `core/design/DesignCodec` is the
 *   trust boundary and it already does every check — size cap, magic string,
 *   format version, frame geometry, palette indices — and returns a sentence fit
 *   to show a user. A second validator here would be a second thing to keep
 *   correct, and the one that drifted would be the one the attacker used.
 * - **User text never becomes a path.** A design's name is arbitrary Unicode the
 *   user typed; [designFileName] reduces it to a token that cannot contain a
 *   separator, cannot be `..`, and cannot be empty. The `id` is a safe token by
 *   construction (see `newDesignId`) and is the fallback.
 * - **An import is always a new design.** [importedDesign] reassigns the id
 *   unconditionally, not just on collision: `DesignStore.save` overwrites by
 *   contract, so a file carrying the id of a design already on this phone would
 *   otherwise replace somebody's artwork with no warning and no undo.
 *
 * Everything here that touches a stream blocks. Callers are on `Dispatchers.IO`.
 */

/**
 * The MIME type of a design file, used for the SAF contracts and the share
 * intent alike. The format is JSON on purpose — it is meant to be posted in
 * gists and read by humans — so it gets JSON's type rather than a private one.
 */
internal const val DESIGN_MIME = "application/json"

/** Extension for an exported or shared file. Matches [DESIGN_MIME]. */
internal const val DESIGN_FILE_EXTENSION = ".json"

/**
 * Cap on the sanitised part of a filename.
 *
 * A design name is already capped at 64 characters by the codec, so this is not
 * the primary defence; it is here because the filename may be concatenated with
 * a provider's own suffixes (` (1)`, a numbered copy) inside path length limits
 * we do not control, and because a 64-character filename is unpleasant to read
 * in a file manager.
 */
private const val MAX_BASE_NAME = 48

/** Used only when a name AND an id both sanitise away to nothing. */
private const val FALLBACK_BASE_NAME = "design"

/** Subdirectory of the cache that the `FileProvider` exposes. Matches `res/xml/file_paths.xml`. */
private const val SHARE_DIR = "shared"

/**
 * How long a shared copy is allowed to sit in the cache.
 *
 * Sharing hands the receiving app a `content://` URI, and that app reads it
 * within seconds — messengers and mail clients copy the bytes into their own
 * storage as they attach it. A day is therefore enormous headroom for the actual
 * lifetime of the file while still being short enough that a year of sharing
 * cannot silently accumulate: the cache holds, at most, what was shared today.
 *
 * Not zero, and not "delete on the next share": the user may background the
 * chooser and come back to it, and deleting the file out from under a chooser
 * that is still on screen would produce a share that silently attaches nothing.
 */
private const val SHARE_CACHE_MAX_AGE_MS = 24L * 60 * 60 * 1000

private const val TAG = "DesignTransfer"

// ---------- naming ----------

/**
 * The filename an export or a share is offered under, e.g. `Slow Ember` →
 * `Slow-Ember.json`.
 *
 * The name is user text, so it is sanitised rather than trusted; if sanitising
 * leaves nothing (a name that is entirely punctuation, or entirely emoji) the id
 * stands in, and if that somehow fails too there is a constant. This function
 * cannot return an empty string, a string containing a path separator, or a
 * string beginning with a dot.
 */
internal fun designFileName(design: Design): String {
    val base = sanitiseFileBaseName(design.name)
        .ifEmpty { sanitiseFileBaseName(design.id) }
        .ifEmpty { FALLBACK_BASE_NAME }
    return base + DESIGN_FILE_EXTENSION
}

/**
 * Reduces arbitrary user text to something safe to hand a filesystem or a
 * document provider.
 *
 * **Allowlist, not denylist.** Letters and digits are kept — `isLetterOrDigit`
 * is Unicode-aware, so `Étoile` survives as `Étoile` rather than being mangled
 * into hyphens — and *everything else* becomes a single hyphen. That covers the
 * cases a denylist would have to enumerate and would eventually miss: `/` and
 * `\`, `..`, NUL and other control characters, leading dots (which hide a file
 * on POSIX), quotes, newlines, and the reserved characters of filesystems this
 * code has never heard of.
 *
 * Runs of rejected characters collapse to one hyphen, a hyphen is never the
 * first character (so the result never starts with punctuation either), and the
 * result is trimmed and capped at [MAX_BASE_NAME].
 */
internal fun sanitiseFileBaseName(raw: String): String {
    val out = StringBuilder(minOf(raw.length, MAX_BASE_NAME))
    var pendingSeparator = false
    for (ch in raw) {
        if (ch.isLetterOrDigit()) {
            // The separator is only materialised once a keepable character
            // follows it, which is what stops a leading run of punctuation from
            // becoming a leading hyphen.
            if (pendingSeparator && out.isNotEmpty()) out.append('-')
            pendingSeparator = false
            out.append(ch)
            if (out.length >= MAX_BASE_NAME) break
        } else {
            pendingSeparator = true
        }
    }
    return out.toString()
}

// ---------- import ----------

/**
 * The design that should be *stored* for an incoming [incoming] file, given a
 * [freshId] the store has allocated and the [importedAt] timestamp.
 *
 * Two deliberate omissions, both of which are the whole point:
 *
 * - **`author` is carried over untouched.** Importing somebody's design must not
 *   launder credit onto whoever imported it. This is the same rule
 *   `saveRespectingAuthor` enforces on every other write.
 * - **`createdAt` is carried over untouched.** The design was made when it was
 *   made; only *this phone's copy* is new, and that is what `modifiedAt` says.
 *   Rewriting `createdAt` would also re-order the list on a field that is
 *   supposed to be a fact about the artwork.
 *
 * Pure, so the rules above are asserted by a test rather than by reading a
 * composable.
 */
internal fun importedDesign(incoming: Design, freshId: String, importedAt: String): Design =
    incoming.copy(id = freshId, modifiedAt = importedAt)

/**
 * Reads and validates the design at [uri].
 *
 * **The size cap is enforced by the read itself**: `DesignCodec.decode` takes the
 * stream, not a string, and stops one byte past its limit — the file is never
 * fully read, let alone parsed, before it is rejected. Handing the codec a
 * `String` we read ourselves would be the JSON-bomb hole.
 *
 * Blocking. Failures come back as `DesignCodec.Result.Invalid` with the codec's
 * own reason, so the message the user sees is specific to what was actually
 * wrong with their file.
 */
internal fun readDesign(context: Context, uri: Uri): DesignCodec.Result = try {
    context.contentResolver.openInputStream(uri)?.use { DesignCodec.decode(it) }
    // A provider that hands back no stream at all (a revoked grant, a file that
    // vanished between the picker and here) is "could not be read", which is the
    // codec's own wording for exactly this.
        ?: DesignCodec.Result.Invalid(DesignCodec.REASON_UNREADABLE)
} catch (e: Exception) {
    DebugLog.w(TAG, "import from $uri failed: ${e.message}")
    DesignCodec.Result.Invalid(DesignCodec.REASON_UNREADABLE)
}

// ---------- export ----------

/**
 * Writes [design] to the document the user picked. Returns false if anything
 * went wrong, so the caller can say so — a silent failure here looks exactly
 * like a feature that does not work.
 *
 * Blocking.
 */
internal fun writeDesign(context: Context, uri: Uri, design: Design): Boolean = try {
    val bytes = DesignCodec.encode(design).toByteArray(Charsets.UTF_8)
    // "wt" truncates first. `CreateDocument` normally hands back a new, empty
    // document, but a picker may let the user overwrite an existing file, and
    // plain "w" on a longer old file would leave its tail behind — producing a
    // corrupt design that the codec would then correctly refuse to re-import.
    // Not every provider implements the mode, hence the fallback.
    val stream = try {
        context.contentResolver.openOutputStream(uri, "wt")
    } catch (e: Exception) {
        context.contentResolver.openOutputStream(uri)
    }
    if (stream == null) {
        false
    } else {
        stream.use {
            it.write(bytes)
            it.flush()
        }
        true
    }
} catch (e: Exception) {
    DebugLog.w(TAG, "export of ${design.id} failed: ${e.message}")
    false
}

// ---------- share ----------

/** The cache subdirectory the `FileProvider` is configured to expose. */
internal fun shareCacheDir(context: Context): File = File(context.cacheDir, SHARE_DIR)

/**
 * Writes a copy of [design] into the shared cache and returns a `content://` URI
 * for it, or null if the copy could not be written.
 *
 * The file is named after the design (via [designFileName]) because that name is
 * what the recipient sees in their messenger, not an opaque id. Two designs with
 * the same sanitised name overwrite one another here, which is harmless: the
 * cache is a transient hand-off, never storage.
 *
 * Blocking.
 */
internal fun writeShareCopy(context: Context, design: Design): Uri? = try {
    val dir = shareCacheDir(context)
    if (!dir.isDirectory && !dir.mkdirs()) {
        DebugLog.w(TAG, "could not create $dir")
        null
    } else {
        val file = File(dir, designFileName(design))
        file.writeText(DesignCodec.encode(design), Charsets.UTF_8)
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }
} catch (e: Exception) {
    DebugLog.w(TAG, "share copy of ${design.id} failed: ${e.message}")
    null
}

/**
 * The `ACTION_SEND` intent for an already-written share copy.
 *
 * The read grant is set two ways on purpose: the flag is what actually
 * authorises the receiving app, and the [ClipData] is what tells the system
 * *which* URI the flag applies to when a target reads the clip rather than the
 * extra. Without the clip some receivers get a `SecurityException` instead of
 * the file.
 */
internal fun shareIntent(context: Context, uri: Uri, design: Design): Intent =
    Intent(Intent.ACTION_SEND).apply {
        type = DESIGN_MIME
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_TITLE, design.name)
        clipData = ClipData.newUri(context.contentResolver, design.name, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

/**
 * Puts the system share sheet up for an already-written copy. Returns false if
 * there is nothing on the device that can receive it, which is rare but is not a
 * crash — it is a sentence.
 *
 * [context] is the Activity the Create tab is hosted in, so no `NEW_TASK` flag is
 * needed and the chooser is correctly parented to it.
 */
internal fun startShare(context: Context, uri: Uri, design: Design, chooserTitle: String): Boolean = try {
    context.startActivity(Intent.createChooser(shareIntent(context, uri, design), chooserTitle))
    true
} catch (e: Exception) {
    DebugLog.w(TAG, "share sheet for ${design.id} failed: ${e.message}")
    false
}

/**
 * Deletes shared copies older than [maxAgeMs] from [dir].
 *
 * Called when the Create tab loads. The cache is the one place this feature
 * leaves files the user did not ask for and cannot see, and Android only clears
 * a cache under storage pressure — which on a phone with 256 GB may be never —
 * so somebody who shares a design a week would otherwise accumulate every design
 * they have ever sent, forever.
 *
 * Takes `now` rather than reading the clock so the policy is testable, and
 * tolerates a missing directory (the common case: nothing has ever been shared).
 * Returns the number of files deleted.
 */
internal fun pruneSharedCache(
    dir: File,
    now: Long,
    maxAgeMs: Long = SHARE_CACHE_MAX_AGE_MS,
): Int {
    val files = dir.listFiles() ?: return 0
    var deleted = 0
    for (file in files) {
        // A file dated in the FUTURE (a clock change, a restored backup) would
        // never expire under a plain age comparison, so anything whose timestamp
        // is not within the window counts as stale.
        val age = now - file.lastModified()
        if (age < maxAgeMs && age >= 0) continue
        if (file.isFile && file.delete()) deleted++
    }
    return deleted
}
