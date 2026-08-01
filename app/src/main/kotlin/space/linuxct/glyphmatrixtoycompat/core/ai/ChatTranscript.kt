package space.linuxct.glyphmatrixtoycompat.core.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The conversation about one design, as it is stored and as it is redisplayed.
 *
 * ## One transcript per design, and it is not the wire format
 *
 * A conversation belongs to a drawing, not to the app: opening a design a week
 * later should resume the thread about *that* drawing, and deleting the drawing
 * should take the thread with it. So the file is keyed by design id, and
 * `ai/ChatStore` is the only thing that knows where it lives.
 *
 * Nothing here is a Responses API model. [ChatWire]'s types describe what goes
 * over the network — content parts, function-call items, call ids — and this
 * describes what a person sees when they scroll up. Keeping them apart is what
 * lets the transport change without rewriting everybody's saved history, and it
 * is why a tool call is recorded as a *sentence* ([ChatToolNote.label]) rather
 * than as its arguments: 150 kB of base36 was never going to be redisplayed, and
 * "Applied a change" is the whole of what the user needs to see.
 *
 * ## Versioning
 *
 * [format] and [formatVersion] follow `core/design/Design.kt` exactly, for the
 * same reasons and with the same rule: a file declaring a version this build does
 * not know is **declined**, not parsed optimistically, because half-understanding
 * a conversation is worse than starting a new one. Unlike a design, declining
 * costs nothing irreplaceable — see [ChatTranscriptCodec.decode].
 *
 * Every field has a default, so a file written by an older build that lacked a
 * field decodes rather than throwing, and an unknown field written by a newer one
 * is ignored.
 */

/** Magic string every transcript file carries as its `format`. */
const val CHAT_FORMAT = "glyph.chat"

/** The newest transcript schema this build can read. */
const val CHAT_FORMAT_VERSION = 1

/** Who said it. */
@Serializable
enum class ChatRole {
    @SerialName("user")
    USER,

    @SerialName("assistant")
    ASSISTANT,
}

/**
 * What one tool call did, in a form worth showing a human.
 *
 * [name] is kept beside [label] so a future build can relabel a tool — or
 * translate it — without the stored history being frozen in one wording, while
 * an *unknown* tool name from a future build still redisplays as whatever that
 * build called it. [ok] false renders differently: "couldn't read your design"
 * is a materially different thing to have happened.
 */
@Serializable
data class ChatToolNote(
    val name: String = "",
    val label: String = "",
    val ok: Boolean = true,
    /** True only for a call that actually changed the canvas. */
    val changedDesign: Boolean = false,
) {
    companion object {
        /**
         * English for what a tool *did*, past tense — the form a scrolled-back
         * thread reads in.
         *
         * The present-continuous twin of this lives on [ChatTrace.defaultText],
         * because "Applying changes…" is a status and "Applied a change" is a
         * record, and a thread narrated in the present tense reads as though it
         * were still happening. A name this build does not know is spelled out
         * rather than dropped: a transcript written by a newer build must still
         * say something truthful.
         */
        fun labelFor(name: String): String = when (name) {
            GlyphAiTools.GET_CURRENT_DESIGN -> "Read your design"
            GlyphAiTools.APPLY_DESIGN -> "Applied a change"
            GlyphAiTools.VALIDATE_DESIGN -> "Checked a design"
            else -> name.replace('_', ' ').replaceFirstChar { it.uppercase() }
        }
    }
}

/** One message in the thread. */
@Serializable
data class ChatMessage(
    val role: ChatRole = ChatRole.USER,
    val text: String = "",
    /** Wall-clock ms. Used for day separators and for ordering ties, not for sorting. */
    val atMs: Long = 0L,
    /** The tool calls this turn made, in order. Empty for a user message. */
    val tools: List<ChatToolNote> = emptyList(),
    /**
     * How many images the user attached. The images themselves are **not**
     * stored: a conversation with six photos in it would be megabytes of
     * credential-protected storage per design, and the model has already seen
     * them. The count is enough to redisplay "you sent 2 photos".
     */
    val imageCount: Int = 0,
    /** True when this assistant turn is a failure notice rather than a reply. */
    val error: Boolean = false,
)

/** The whole conversation about one design. */
@Serializable
data class ChatTranscript(
    val format: String = CHAT_FORMAT,
    val formatVersion: Int = CHAT_FORMAT_VERSION,
    /** The design this belongs to; the same id that names the file. */
    val designId: String = "",
    val messages: List<ChatMessage> = emptyList(),
) {
    /** This transcript with [message] appended, trimmed to [ChatTranscriptCodec.MAX_MESSAGES]. */
    fun plus(message: ChatMessage): ChatTranscript =
        copy(messages = (messages + message).takeLast(ChatTranscriptCodec.MAX_MESSAGES))

    /**
     * The last [count] turns as Responses API input items — the conversation the
     * model is given as context.
     *
     * Tool notes are dropped here deliberately. Replaying a `function_call`
     * without its `function_call_output` is a protocol error, replaying both
     * would mean storing every tool payload, and the model does not need last
     * week's `get_current_design` — it will call it again, and the answer will be
     * current, which is the point. What it needs is what was *said*.
     *
     * Blank and error turns are skipped: an empty `content` array is rejected by
     * the API, and replaying "Network error" as though the assistant had said it
     * teaches the model that this is a thing it says.
     */
    fun asInput(count: Int = ChatTranscriptCodec.HISTORY_TURNS): List<ChatInputItem> =
        messages.asSequence()
            .filter { it.text.isNotBlank() && !it.error }
            .toList()
            .takeLast(count)
            .map {
                when (it.role) {
                    ChatRole.USER -> ChatMessageItem.user(it.text)
                    ChatRole.ASSISTANT -> ChatMessageItem.assistant(it.text)
                }
            }
}

/**
 * Reads and writes [ChatTranscript] JSON, and never throws.
 *
 * The asymmetry with `core/design/DesignCodec` is intentional. A design that
 * fails to decode is somebody's artwork and its rejection carries a *reason* to
 * show them. A transcript that fails to decode is chat history: there is nothing
 * useful to say about it and no action to offer, so [decode] returns null and the
 * editor opens on an empty thread. Losing a conversation is a disappointment;
 * failing to open the design because of one is a bug.
 */
object ChatTranscriptCodec {

    /**
     * How many messages one transcript keeps.
     *
     * A hard cap rather than a byte budget because the cost that matters is the
     * *read*: this file is parsed on every editor open, and an unbounded thread
     * on a design somebody talks to daily would grow without limit. 400 messages
     * is far more than any real conversation about one drawing and still parses
     * in single-digit milliseconds.
     */
    const val MAX_MESSAGES = 400

    /**
     * How many past turns are replayed to the model by default.
     *
     * pulseloop sends six. The same number here, and for a stronger reason: a
     * turn in this feature can carry a whole design through its tool round, so
     * context is spent much faster than in a coaching chat. The model is not
     * meant to work from history anyway — `get_current_design` is always more
     * accurate than remembering.
     */
    const val HISTORY_TURNS = 6

    /**
     * The largest transcript file that will be read.
     *
     * [MAX_MESSAGES] bounds what this app *writes*; this bounds what it *reads*,
     * which is a different thing — the file lives in the app's own storage but a
     * bug, a restore or a full disk can still produce something absurd, and the
     * editor's launch path must not be where that is discovered.
     */
    const val MAX_BYTES = 4 * 1024 * 1024

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        // A `role` this build does not know decodes to the enum's default rather
        // than throwing, which keeps one odd message from voiding a whole thread.
        coerceInputValues = true
    }

    /** [transcript] as the bytes to store, always with the current version stamped on. */
    fun encode(transcript: ChatTranscript): String =
        json.encodeToString(
            ChatTranscript.serializer(),
            transcript.copy(format = CHAT_FORMAT, formatVersion = CHAT_FORMAT_VERSION),
        )

    /**
     * [text] as a transcript, or null if it is not one this build should show:
     * unparsable, truncated, not a `glyph.chat` document, or written by a build
     * newer than this one.
     */
    fun decode(text: String): ChatTranscript? {
        if (text.length > MAX_BYTES) return null
        val parsed = try {
            json.decodeFromString(ChatTranscript.serializer(), text)
        } catch (e: Exception) {
            return null
        }
        if (parsed.format != CHAT_FORMAT) return null
        if (parsed.formatVersion > CHAT_FORMAT_VERSION) return null
        return parsed.copy(messages = parsed.messages.takeLast(MAX_MESSAGES))
    }
}
