package space.linuxct.glyphworks.core.ai

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import space.linuxct.glyphworks.core.design.Design

/**
 * A design the assistant produced while no editor was open, waiting for the one
 * that can accept it.
 *
 * ## Why this record exists
 *
 * A turn used to die with the editor, so "the model finished a drawing and there
 * is nowhere to put it" was unreachable. Now that a turn outlives the screen it
 * started on, it is the *ordinary* ending for anybody who closes the editor and
 * waits — and the old answer, telling the model "the design editor is no longer
 * open, so nothing was changed", quietly throws away the picture the user asked
 * for.
 *
 * So the design is written down here, and the next editor to open that design
 * applies it. The apply is a whole-document replace exactly as a live one is, and
 * it arrives with the same one-tap way back — see `ai/GlyphAiSession`.
 *
 * ## What makes it safe to apply later
 *
 * [baseModifiedAt] is the `modifiedAt` of the design **as it was on disk at the
 * moment this was recorded**, and it is the whole of the conflict rule. The user
 * may have gone back into the editor in between and drawn something; their work
 * is newer than the model's and must not be silently replaced by it. See
 * [pendingApplyVerdict].
 */
@Serializable
data class PendingApply(
    val format: String = PENDING_APPLY_FORMAT,
    val formatVersion: Int = PENDING_APPLY_FORMAT_VERSION,
    /** The design this is waiting for; the same id that names the file. */
    val designId: String = "",
    /**
     * `Design.modifiedAt` as stored when this was recorded, or blank if the
     * design could not be read at that moment. Blank never applies — a record
     * with no baseline has nothing to prove the design is untouched.
     */
    val baseModifiedAt: String = "",
    /** Wall clock at which the turn deferred this. Used only to expire it. */
    val atMs: Long = 0L,
    /** The document to put on the canvas. Already validated by `apply_design`. */
    val design: Design = Design(),
)

/** Magic string every deferred-apply file carries as its `format`. */
const val PENDING_APPLY_FORMAT = "glyph.pendingapply"

/** The newest deferred-apply schema this build can read. */
const val PENDING_APPLY_FORMAT_VERSION = 1

/**
 * How long a deferred apply is worth keeping.
 *
 * A week. The record is only ever consumed by opening the design it belongs to,
 * and somebody who has not opened it in a week has moved on: putting a drawing
 * they asked for last Tuesday onto a canvas they opened today would read as the
 * app changing their design by itself. Short enough to be recognisable as "the
 * thing I asked for", long enough to survive a weekend.
 */
const val PENDING_APPLY_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

/** What should happen to a recorded [PendingApply] when its design is opened. */
enum class PendingApplyVerdict {
    /** Put it on the canvas. */
    APPLY,

    /**
     * The design has been edited since the record was made. The user's own work
     * is newer; the model's draft is dropped rather than laid over it.
     */
    CONFLICT,

    /** Recorded too long ago to be what the user is expecting. */
    EXPIRED,

    /** The design is gone, or was unreadable when the record was made. */
    MISSING,
}

/**
 * What to do with [record], given the design's `modifiedAt` as it is on disk
 * right now ([currentModifiedAt], null if there is no readable design) and the
 * wall clock.
 *
 * Pure and total, and outside any class, for the reason `ChatStore`'s orphan rule
 * is: this is the one decision in the deferred-apply path that can *destroy*
 * something — either a drawing the model made or, if it got the rule backwards,
 * a drawing the user made — and it is worth being able to prove rather than
 * reason about.
 *
 * The order of the arms matters. A missing design is checked first because there
 * is no baseline to compare against; expiry before conflict because an ancient
 * record should be reported as old rather than as a clash, which is the more
 * useful thing to find in a log.
 */
fun pendingApplyVerdict(
    record: PendingApply,
    currentModifiedAt: String?,
    nowMs: Long,
): PendingApplyVerdict = when {
    currentModifiedAt == null || record.baseModifiedAt.isBlank() -> PendingApplyVerdict.MISSING
    nowMs - record.atMs > PENDING_APPLY_MAX_AGE_MS -> PendingApplyVerdict.EXPIRED
    currentModifiedAt != record.baseModifiedAt -> PendingApplyVerdict.CONFLICT
    else -> PendingApplyVerdict.APPLY
}

/**
 * Reads and writes [PendingApply] JSON, and never throws.
 *
 * Same contract as [ChatTranscriptCodec], for the same reason: this is read on
 * the way into the editor, so every failure mode has to degrade to "there is
 * nothing waiting" rather than to a screen that will not open.
 */
object PendingApplyCodec {

    /**
     * The largest record that will be read: one design plus its wrapper.
     * `DesignCodec.MAX_BYTES` is the design's own ceiling; the slack above it is
     * the handful of scalar fields around it.
     */
    const val MAX_BYTES = 2 * 1024 * 1024

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
    }

    fun encode(record: PendingApply): String =
        json.encodeToString(
            PendingApply.serializer(),
            record.copy(
                format = PENDING_APPLY_FORMAT,
                formatVersion = PENDING_APPLY_FORMAT_VERSION,
            ),
        )

    /**
     * [text] as a record, or null if it is not one this build should act on:
     * oversized, unparsable, not a `glyph.pendingapply` document, or written by a
     * build newer than this one.
     */
    fun decode(text: String): PendingApply? {
        if (text.length > MAX_BYTES) return null
        val parsed = try {
            json.decodeFromString(PendingApply.serializer(), text)
        } catch (e: Exception) {
            return null
        }
        if (parsed.format != PENDING_APPLY_FORMAT) return null
        if (parsed.formatVersion > PENDING_APPLY_FORMAT_VERSION) return null
        return parsed
    }
}
