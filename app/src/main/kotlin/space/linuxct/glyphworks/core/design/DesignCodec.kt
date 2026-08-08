package space.linuxct.glyphworks.core.design

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.InputStream
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Reads and writes `glyph.design` files, and is the single place that decides
 * whether a design file is acceptable.
 *
 * **Every input here is hostile.** Design files are meant to be shared — posted
 * in gists, attached to issues, passed between strangers — so a file reaching
 * this object is attacker-controlled by default. The rules below are therefore
 * not tidiness, they are the trust boundary:
 *
 * - **Size is capped before anything is parsed.** [decode] on an [InputStream]
 *   reads through a bounded reader and gives up past [MAX_BYTES]; the `String`
 *   overload rejects on length first. Parsing a 200 MB JSON document to then
 *   discover it was too large is the JSON-bomb footgun this exists to avoid.
 * - **Nothing throws.** Like `update/UpdateChecker`, this returns a sealed
 *   [Result] with an explicit failure arm carrying a reason fit to show a user,
 *   and wraps the parse in a blanket catch. A malformed file must produce a
 *   sentence, never a crash and never a silent no-op.
 * - **Unknown keys are ignored, unknown codenames are dropped, and neither is
 *   fatal** — a file written by a later version, or on a device we have never
 *   heard of, still loads whatever we do understand.
 * - **Structural mistakes are rejected, not repaired.** A frame of the wrong
 *   length or a cell referencing a palette entry that does not exist is a broken
 *   file; padding, truncating or clamping it would hand the user back art that
 *   is not what they made, with no indication anything happened.
 *
 * The places we *are* lenient are the two where a value has exactly one sensible
 * reading and no structural consequence: [Design.levels], whose entries are
 * coerced into 0..4095, and the timestamps, which are rewritten into the
 * format's canonical UTC form (see [normalisedInstant]). A frame duration of 0
 * is also out of range, but it would become a busy-loop on the render scheduler,
 * so that one is rejected rather than clamped.
 */
object DesignCodec {

    /**
     * Hard ceiling on a design file, checked before parsing.
     *
     * A legal design is nowhere near this: 240 frames of arbok is ~150 KB of
     * `cells` plus JSON overhead. 1 MB leaves generous headroom for a longer
     * format while still being a size we are happy to hold in memory twice.
     */
    const val MAX_BYTES = 1024 * 1024

    /**
     * Because UTF-8 encodes every character in at least one byte, a string
     * longer than [MAX_BYTES] characters is certainly over the byte cap. This
     * lets the `String` overload reject huge input without re-encoding it.
     */
    const val MAX_CHARS = MAX_BYTES

    /** Frames per variant. At the 20 ms floor that is still nearly five seconds of animation. */
    const val MAX_FRAMES = 240

    /** 20 ms is one 50 Hz step; anything faster is invisible and just burns binder calls. */
    const val MIN_DURATION_MS = 20

    /** A minute on one frame; beyond that a design is a static image with extra steps. */
    const val MAX_DURATION_MS = 60_000

    /** Fits a list row without truncation on the narrowest supported width. */
    const val MAX_NAME_LENGTH = 64
    const val MAX_AUTHOR_LENGTH = 64

    /** `createdWith` is diagnostic text from another build — cap it like any other free string. */
    const val MAX_CREATED_WITH_LENGTH = 64

    /** The id becomes a filename, so it is length-capped as well as character-restricted. */
    const val MAX_ID_LENGTH = 64

    /**
     * The id is the only user-visible value that ever reaches the filesystem, so
     * it is restricted to a token that cannot escape a directory or confuse a
     * path: no separators, no dots (so no `..`), no NUL, no spaces, no Unicode.
     */
    private val SAFE_ID = Regex("[A-Za-z0-9_-]{1,$MAX_ID_LENGTH}")

    /** The one field name this object needs to know before deserialising. */
    private const val FIELD_FORMAT = "format"

    /** Outcome of reading a design file. Mirrors `UpdateChecker.Result`'s shape. */
    sealed interface Result {
        data class Ok(val design: Design) : Result

        /** [reason] is a complete, user-facing sentence — never "import failed". */
        data class Invalid(val reason: String) : Result
    }

    // Rejection reasons are constants so the tests can assert the exact reason a
    // given hostile file produces, rather than merely that it was rejected.
    const val REASON_TOO_LARGE = "This file is too large to be a Glyph design."
    const val REASON_NOT_JSON = "This file is not valid JSON."
    const val REASON_NOT_A_DESIGN = "This is not a Glyph design file."
    const val REASON_NEWER_VERSION = "This design was made with a newer version of the app."
    const val REASON_OLDER_VERSION = "This design declares a format version this app cannot read."
    const val REASON_BAD_ID = "This design has an unusable id."
    const val REASON_NAME_TOO_LONG = "This design's name is too long."
    const val REASON_AUTHOR_TOO_LONG = "This design's author name is too long."
    const val REASON_CREATED_WITH_TOO_LONG = "This design's originating app name is too long."
    const val REASON_BAD_TIMESTAMP = "This design has an unreadable timestamp."
    const val REASON_EMPTY_PALETTE = "This design has no brightness levels."
    const val REASON_PALETTE_TOO_LONG = "This design has too many brightness levels."
    const val REASON_NO_VARIANTS = "This design contains no artwork for any known device."
    const val REASON_TOO_MANY_FRAMES = "This design has too many frames."
    const val REASON_BAD_DURATION = "This design has a frame duration outside 20 ms to 60 s."
    const val REASON_BAD_FRAME_SIZE = "This design has a frame that is the wrong size for its device."
    const val REASON_BAD_FRAME_CELL = "This design has a frame using a brightness level it does not define."
    const val REASON_UNREADABLE = "This design file could not be read."

    /**
     * Decoding is deliberately forgiving about *shape* and strict about
     * *content*, and the two settings below are what draws that line.
     *
     * `ignoreUnknownKeys` is forward compatibility: a field added in format
     * version 2 must not stop version 1 reading the rest of the file.
     *
     * `coerceInputValues` turns a null or an unrecognised enum constant into the
     * property's default instead of an exception, so `"kind": "kaleidoscope"`
     * from some future build degrades to a static design rather than making the
     * file unopenable. Every property in [Design] has a default precisely so
     * this is possible.
     */
    private val reader = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    /**
     * Pretty-printed on purpose. This format is meant to be read, diffed and
     * reviewed by people — a design in a pull request should show which frame
     * changed. `encodeDefaults` is required, or a design whose fields happen to
     * equal their defaults would be written *without* `format`, `formatVersion`
     * or `levels`, and would no longer be self-describing.
     */
    private val writer = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    /**
     * Serialises [design] to the on-disk / on-the-wire form.
     *
     * `decode(encode(d))` returns a design equal to `d` for any design this
     * codec has validated, which is what makes the storage format and the export
     * format safely the same thing.
     *
     * Both directions name `Design.serializer()` explicitly rather than using
     * the reified overloads: the serializer is then resolved by the compiler
     * plugin at build time, with no runtime type lookup for R8 to have to keep
     * working. That is why `proguard-rules.pro` needs nothing for this library.
     */
    fun encode(design: Design): String = writer.encodeToString(Design.serializer(), design)

    /**
     * Reads a design from [stream], never reading more than [MAX_BYTES] + 1
     * bytes. The stream is *not* closed — the caller owns it.
     *
     * This is the path every file read should take. Reading the whole file into
     * a string and then measuring it defeats the point of the size cap.
     */
    fun decode(stream: InputStream): Result {
        val text = try {
            readBounded(stream) ?: return Result.Invalid(REASON_TOO_LARGE)
        } catch (e: Exception) {
            return Result.Invalid(REASON_UNREADABLE + " (" + (e.message ?: e.javaClass.simpleName) + ")")
        }
        return decode(text)
    }

    /**
     * Reads a design from an already-materialised string.
     *
     * The length check runs first and before any parsing, for the same reason
     * the stream overload bounds its read.
     */
    fun decode(text: String): Result {
        if (text.length > MAX_CHARS) return Result.Invalid(REASON_TOO_LARGE)

        // Parsed to a tree first, purely so the magic string can be checked
        // BEFORE the document is mapped onto [Design]. Every property of
        // [Design] has a default (it must, or a slightly wrong file would throw
        // instead of producing a reason), which means a decode of `{}` would
        // otherwise inherit `format = "glyph.design"` and claim any JSON object
        // in the world as one of ours. Asking the tree whether the key is
        // actually there is the difference between "this is not a design file"
        // and a misleading complaint about its id.
        val root: JsonObject = try {
            reader.parseToJsonElement(text) as? JsonObject
                ?: return Result.Invalid(REASON_NOT_A_DESIGN)
        } catch (e: Exception) {
            // Malformed or truncated JSON. The exception text is not shown: it
            // is kotlinx's internal wording and would leak file contents.
            return Result.Invalid(REASON_NOT_JSON)
        }

        val magic = (root[FIELD_FORMAT] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (magic != DESIGN_FORMAT) return Result.Invalid(REASON_NOT_A_DESIGN)

        val raw: Design = try {
            reader.decodeFromJsonElement(Design.serializer(), root)
        } catch (e: Exception) {
            // A field of the wrong type. Structurally this is still not a
            // document we can read.
            return Result.Invalid(REASON_NOT_JSON)
        }

        return try {
            validate(raw)
        } catch (e: Exception) {
            // Defence in depth. Validation is written not to throw, but this
            // object's contract to its callers is that nothing escapes it.
            Result.Invalid(REASON_UNREADABLE)
        }
    }

    /**
     * Applies every rule in this file's contract to an already-parsed design and
     * returns the *normalised* design: timestamps rewritten into canonical UTC,
     * palette entries coerced into range and variants for unknown devices
     * dropped.
     *
     * Exposed for tests and for the editor, which validates before saving so a
     * design we wrote can never be one we would refuse to read back.
     */
    fun validate(raw: Design): Result {
        if (raw.format != DESIGN_FORMAT) return Result.Invalid(REASON_NOT_A_DESIGN)
        if (raw.formatVersion > DESIGN_FORMAT_VERSION) return Result.Invalid(REASON_NEWER_VERSION)
        if (raw.formatVersion < 1) return Result.Invalid(REASON_OLDER_VERSION)

        if (!SAFE_ID.matches(raw.id)) return Result.Invalid(REASON_BAD_ID)

        if (raw.name.length > MAX_NAME_LENGTH) return Result.Invalid(REASON_NAME_TOO_LONG)
        if (raw.author.length > MAX_AUTHOR_LENGTH) return Result.Invalid(REASON_AUTHOR_TOO_LONG)
        if (raw.createdWith.length > MAX_CREATED_WITH_LENGTH) {
            return Result.Invalid(REASON_CREATED_WITH_TOO_LONG)
        }

        // The list UI sorts on these strings without parsing them, so they are
        // not merely checked here — they are REWRITTEN into the one form that
        // makes that sort correct. See [normalisedInstant].
        val createdAt = normalisedInstant(raw.createdAt) ?: return Result.Invalid(REASON_BAD_TIMESTAMP)
        val modifiedAt = normalisedInstant(raw.modifiedAt) ?: return Result.Invalid(REASON_BAD_TIMESTAMP)

        if (raw.levels.isEmpty()) return Result.Invalid(REASON_EMPTY_PALETTE)
        if (raw.levels.size > DesignFrames.MAX_PALETTE) return Result.Invalid(REASON_PALETTE_TOO_LONG)
        val levels = raw.levels.map { it.coerceIn(0, DesignFrames.MAX_BRIGHTNESS) }

        // Variants for devices we do not know are dropped rather than carried
        // through. We cannot check the geometry of a panel whose size we do not
        // know, and copying unvalidated attacker-supplied cell data into our own
        // storage to preserve a variant we can neither render nor edit is a worse
        // trade than losing it.
        val variants = LinkedHashMap<String, DesignVariant>(raw.variants.size)
        for ((key, variant) in raw.variants) {
            val codename = PokemonCodename.ofCodename(key) ?: continue
            if (variant.frames.size > MAX_FRAMES) return Result.Invalid(REASON_TOO_MANY_FRAMES)
            for (frame in variant.frames) {
                if (frame.durationMs < MIN_DURATION_MS || frame.durationMs > MAX_DURATION_MS) {
                    return Result.Invalid(REASON_BAD_DURATION)
                }
                if (frame.cells.length != codename.cellCount) {
                    return Result.Invalid(REASON_BAD_FRAME_SIZE)
                }
                // Proves every character indexes into the palette. Decoding is
                // the check: there is no cheaper way to be sure, and doing it
                // here means the renderer's later decode cannot fail.
                if (DesignFrames.decode(frame.cells, levels, codename.size) == null) {
                    return Result.Invalid(REASON_BAD_FRAME_CELL)
                }
            }
            variants[codename.codename] = variant
        }
        if (variants.isEmpty()) return Result.Invalid(REASON_NO_VARIANTS)

        return Result.Ok(
            raw.copy(
                createdAt = createdAt,
                modifiedAt = modifiedAt,
                levels = levels,
                variants = variants,
            ),
        )
    }

    /** True if [id] is safe to use as a filename. Public so the store can assert it. */
    fun isSafeId(id: String): Boolean = SAFE_ID.matches(id)

    /**
     * Reads at most [MAX_BYTES] bytes of UTF-8 from [stream], or null if the
     * stream has more to give.
     *
     * Bounded by construction: it asks for one byte past the limit and treats
     * getting it as the failure, so an endless stream cannot exhaust memory.
     */
    private fun readBounded(stream: InputStream): String? {
        val buffer = ByteArray(8 * 1024)
        val out = java.io.ByteArrayOutputStream(minOf(MAX_BYTES, 64 * 1024))
        var total = 0
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            total += read
            if (total > MAX_BYTES) return null
            out.write(buffer, 0, read)
        }
        return out.toString(Charsets.UTF_8.name())
    }

    /**
     * [value] as the format's canonical timestamp — `yyyy-MM-ddTHH:mm:ssZ` — or
     * null if it is not a timestamp this format can carry.
     *
     * **This is what makes the lexicographic-sort invariant true rather than
     * hoped for.** The design list orders itself on `modifiedAt` as a *string*,
     * never parsing it, which is the stated reason the format uses ISO-8601 at
     * all. But `Instant.parse` accepts more than one spelling of the same
     * instant, and two of those spellings sort wrongly against the canonical one:
     *
     * - **An explicit offset.** `2026-07-30T12:00:00+02:00` is 10:00 UTC, yet as
     *   characters it sorts after `2026-07-30T11:00:00Z`, which is later. An
     *   imported file from another timezone would land somewhere arbitrary in
     *   the user's list.
     * - **Sub-second precision.** `…T12:00:00.500Z` sorts *before* `…T12:00:00Z`,
     *   because `.` is below `Z` in ASCII — exactly backwards.
     *
     * Both are fixed the same way and losslessly in meaning: `Instant.parse`
     * has already resolved the text to an absolute instant, so re-formatting
     * that instant in the one canonical form changes only the spelling. Whole
     * seconds is the precision GlyphWorks itself writes ([nowIsoUtc]), and sub-second
     * detail on "when was this drawing last edited" is noise in a file people
     * read and diff by hand.
     *
     * Normalising rather than rejecting is deliberate: a valid ISO-8601
     * timestamp should never be the reason somebody cannot open a file they were
     * sent.
     *
     * The length check is the one thing here that *is* a rejection, and it is
     * the invariant's last hole. `Instant.toString()` widens the year field
     * outside 0..9999 (`+12026-07-30T12:00:00Z`, `-0001-…`), and a variable-width
     * prefix cannot sort as characters at all. Twenty characters is the canonical
     * form exactly; anything else is a timestamp this format has no way to order,
     * and there is no honest normalisation of it.
     */
    private fun normalisedInstant(value: String): String? {
        val canonical = try {
            Instant.parse(value).truncatedTo(ChronoUnit.SECONDS).toString()
        } catch (e: Exception) {
            return null
        }
        return canonical.takeIf { it.length == CANONICAL_TIMESTAMP_LENGTH }
    }

    /** `yyyy-MM-ddTHH:mm:ssZ`. See [normalisedInstant]. */
    private const val CANONICAL_TIMESTAMP_LENGTH = 20
}
