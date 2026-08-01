package space.linuxct.glyphmatrixtoycompat.core.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The wire format for the OpenAI Responses API, as far as this app uses it, and
 * the **pure** parse of the Server-Sent Events stream it answers with.
 *
 * ## Why this is in `core/`
 *
 * Everything here is a data class or a function over a sequence of strings, so
 * every branch of it runs under plain JUnit with no device, no network and no
 * fixtures. That matters more here than anywhere else in the feature: a streaming
 * protocol is exactly the kind of code that looks right, works against the happy
 * path, and then loses the last word of a reply — or drops a function call — the
 * first time a chunk boundary lands somewhere new. `ai/GlyphAiClient` is
 * therefore reduced to "open a connection, hand it these lines", which is the
 * part that cannot be tested, and it is a dozen statements long.
 *
 * ## No `org.json`
 *
 * `pulseloop-android`'s `data/network/OpenAIClient.kt` is the reference for the
 * event shapes and for the accumulate-by-item-id logic below, and it is built
 * entirely on `org.json`. That class is an **empty stub** in the android.jar unit
 * tests compile against — every method throws — so copying it would have meant a
 * protocol parser that no unit test could execute. kotlinx.serialization is
 * already a dependency of this app and works identically on both sides.
 *
 * ## Tolerant by construction
 *
 * [parseSse] never throws. A stream is a network artefact: it can be cut in half
 * mid-object, carry comment lines, carry keep-alives, carry event types added
 * after this build shipped, or simply stop. Every one of those is a line this
 * function skips rather than an exception the user reads as "something went
 * wrong". The only failure it reports is one the *server* declared — an `error`
 * event — because that is the only case where the request genuinely did not
 * happen.
 */
object ChatWire {

    /**
     * The model this app asks for, unless the user has named another one.
     *
     * One constant, named, because it is **unverified**: it is the id the OAuth
     * backend is believed to expose, and nothing in this repository can confirm
     * it — the first proof is a real request from a signed-in device. If it is
     * wrong, every call fails identically. (pulseloop defaults to `gpt-5.4`
     * against the same backend, which is the evidence that the id is a moving
     * target rather than a constant of nature.)
     *
     * That is why it is no longer only this line: a wrong id here used to mean a
     * feature that could not work until the next release, so the Settings tab
     * exposes an override (`PrefKeys.AI_MODEL`) and this is the default it falls
     * back to. See [resolveModel].
     */
    const val MODEL = "gpt-5.6-sol"

    /**
     * The model id to actually send, given whatever is stored in the preference.
     *
     * Pure and parameterised rather than reading prefs itself, because `core/`
     * has no Android in it — the caller (`ai/GlyphAiViewModel`) does the reading,
     * once per turn, so editing the preference takes effect on the next message
     * rather than on the next launch.
     *
     * Blank is the *normal* value here, not an error: the preference ships empty
     * and stays empty for everybody the default works for, and a user clearing
     * the field is asking for the default back. Every such case yields [MODEL],
     * so a request is never sent with `"model": ""` — which the backend would
     * reject with the same opaque failure the override exists to escape.
     */
    fun resolveModel(stored: String?): String {
        val trimmed = stored?.trim().orEmpty()
        return if (trimmed.isEmpty()) MODEL else trimmed
    }

    /**
     * How much of a typed model id the settings field keeps.
     *
     * Model ids are short by nature; this is only here so that a paste from a
     * chat window cannot put a paragraph into a preference. Same reasoning as
     * the creator-name field's cap, and in `core/` for the same reason as
     * [resolveModel] — the UI enforces a rule the domain owns.
     */
    const val MODEL_MAX_LENGTH = 64

    /** Reasoning effort asked of the backend; see [ChatReasoning]. */
    const val DEFAULT_REASONING_EFFORT = "medium"

    // region request

    /**
     * Lenient on the way in, complete on the way out.
     *
     * `encodeDefaults` is on because `stream` and `store` are defaults this app
     * relies on being *sent* — a request that omitted `"stream": true` would come
     * back as one JSON document and the whole of [parseSse] would see nothing.
     * `explicitNulls` is off so an absent [ChatRequest.reasoning] is an absent
     * key rather than `"reasoning": null`, which some backends reject.
     */
    val json: Json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** [request] as the JSON body to POST. */
    fun encodeRequest(request: ChatRequest): String =
        json.encodeToString(ChatRequest.serializer(), request)

    /**
     * A tool specification (as [GlyphTool.specJson] carries it) as a
     * [JsonElement] the request can embed, or null if it does not parse.
     *
     * [dropStrict] mirrors pulseloop: the OAuth backend rejects `"strict"` on a
     * function tool, while the standard API requires it for structured output.
     * The specs in [GlyphAiTools] are written for the standard shape and the key
     * is removed here, so there is one spelling of each schema in the repository.
     */
    fun toolSpec(specJson: String, dropStrict: Boolean = true): JsonElement? {
        val parsed = try {
            json.parseToJsonElement(specJson)
        } catch (e: Exception) {
            return null
        }
        val obj = parsed as? JsonObject ?: return null
        return if (dropStrict) JsonObject(obj - "strict") else obj
    }

    /** Every tool in [tools] as request-ready JSON, skipping any that will not parse. */
    fun toolSpecs(tools: List<GlyphTool>, dropStrict: Boolean = true): List<JsonElement> =
        tools.mapNotNull { toolSpec(it.specJson, dropStrict) }

    /**
     * An image as the `image_url` a content part wants: a base64 data URL.
     *
     * The Responses API takes either a public URL or an inline data URL, and this
     * app has no server to host a photo on, so inline is the only option. It
     * lives here rather than in the image-picking code because it is part of the
     * wire format, and because putting it here means the later image work has a
     * tested function to call rather than a string template to get wrong.
     */
    fun imageDataUrl(base64: String, mimeType: String = "image/jpeg"): String =
        "data:$mimeType;base64,$base64"

    // endregion

    // region SSE

    /**
     * Turns raw stream lines into the events this app understands, lazily.
     *
     * Lazy on purpose: the caller passes `reader.lineSequence()` straight from
     * the socket, so text deltas reach the UI as they arrive rather than after
     * the response completes. Nothing here buffers the stream.
     *
     * The rules, all of which exist because a real stream has produced them:
     *
     * - A line that is not a `data:` line is skipped. That covers the blank line
     *   between events, `event:` lines, `:` keep-alive comments, and HTTP
     *   framing noise.
     * - `data: [DONE]` ends the sequence. Anything after it is not read.
     * - A payload that is not a JSON object — a truncated final chunk, most
     *   often — is skipped. A cut stream must degrade to "the reply stopped
     *   early", which the caller can still show, not to an exception that
     *   discards everything already received.
     * - An event `type` this build does not know is skipped, so a backend adding
     *   `response.reasoning_summary.delta` does not break the client.
     */
    fun parseSse(lines: Sequence<String>): Sequence<SseEvent> = sequence {
        for (raw in lines) {
            val line = raw.trimEnd('\r')
            if (!line.startsWith(DATA_PREFIX)) continue
            val payload = line.removePrefix(DATA_PREFIX).trim()
            if (payload.isEmpty()) continue
            if (payload == DONE) {
                yield(SseEvent.Done)
                return@sequence
            }
            val event = parseEvent(payload) ?: continue
            yield(event)
        }
    }

    /** One `data:` payload as an event, or null if it is not one worth reporting. */
    private fun parseEvent(payload: String): SseEvent? {
        val obj = try {
            json.parseToJsonElement(payload) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: return null

        return when (obj.str("type")) {
            "response.created" ->
                SseEvent.Created(obj.obj("response")?.str("id").orEmpty())

            "response.output_item.added" -> {
                val item = obj.obj("item") ?: return null
                if (item.str("type") != "function_call") return null
                SseEvent.FunctionCallAdded(
                    itemId = item.str("id").orEmpty(),
                    callId = item.str("call_id").orEmpty(),
                    name = item.str("name").orEmpty(),
                )
            }

            "response.output_text.delta" -> {
                val delta = obj.str("delta") ?: return null
                if (delta.isEmpty()) return null
                SseEvent.TextDelta(itemId = obj.str("item_id").orEmpty(), delta = delta)
            }

            "response.function_call_arguments.delta" -> {
                val delta = obj.str("delta") ?: return null
                if (delta.isEmpty()) return null
                SseEvent.FunctionArgumentsDelta(
                    itemId = obj.str("item_id").orEmpty(),
                    callId = obj.str("call_id").orEmpty(),
                    name = obj.str("name").orEmpty(),
                    delta = delta,
                )
            }

            "response.completed" -> {
                val response = obj.obj("response") ?: return null
                SseEvent.Completed(
                    responseId = response.str("id").orEmpty(),
                    output = parseOutput(response["output"] as? JsonArray),
                )
            }

            // Not in pulseloop, and cheap to be right about: the backend reports
            // a refusal or a mid-stream abort as `response.failed`, not as
            // `error`. Without this the stream would simply stop and the turn
            // would be reported as "no text", which tells the user nothing.
            "response.failed" -> SseEvent.Failed(
                obj.obj("response")?.obj("error")?.str("message")
                    ?: obj.str("message")
                    ?: "The model stopped before answering.",
            )

            "error" -> SseEvent.Failed(
                obj.str("message") ?: obj.obj("error")?.str("message") ?: payload,
            )

            else -> null
        }
    }

    /**
     * The `output` array of a completed response, reduced to the two item kinds
     * this app acts on.
     *
     * Read by hand rather than through a sealed `@Serializable` hierarchy because
     * the array also carries `reasoning` items, `web_search_call` items and
     * whatever the backend adds next, and kotlinx's polymorphic decoder throws on
     * a discriminator it was not told about. Skipping the unknown is the required
     * behaviour, so the parse that skips by construction is the right one.
     */
    private fun parseOutput(output: JsonArray?): ChatOutput {
        if (output == null) return ChatOutput(null, emptyList())
        var text: String? = null
        val calls = mutableListOf<ChatFunctionCall>()
        for (element in output) {
            val item = element as? JsonObject ?: continue
            when (item.str("type")) {
                "message" -> {
                    val content = item["content"] as? JsonArray ?: continue
                    for (part in content) {
                        val partObj = part as? JsonObject ?: continue
                        val partType = partObj.str("type")
                        if (partType != "output_text" && partType != "text") continue
                        val t = partObj.str("text")?.takeIf { it.isNotBlank() } ?: continue
                        // Last non-blank wins, as pulseloop does: a response with
                        // several message items ends on the one addressed to the
                        // user.
                        text = t
                    }
                }

                "function_call" -> calls += ChatFunctionCall(
                    callId = item.str("call_id").orEmpty(),
                    name = item.str("name").orEmpty(),
                    arguments = item.str("arguments")?.takeIf { it.isNotBlank() } ?: "{}",
                )
            }
        }
        return ChatOutput(text, calls)
    }

    /**
     * Folds a stream of [SseEvent] into the one answer a caller wants.
     *
     * ## Accumulate by item id, then prefer the completed response
     *
     * The API sends the same content twice: once incrementally, as deltas tagged
     * with the id of the output item they belong to, and once whole, inside
     * `response.completed`. Both are kept. The deltas are what [onTextDelta]
     * streams to the UI and what makes the reply appear a word at a time; the
     * completed array is authoritative for the *result*, because it is the only
     * form guaranteed to be well-formed — function-call arguments in particular
     * arrive as an arbitrary number of fragments and a stream that ends one
     * fragment short would otherwise produce JSON the tool cannot parse.
     *
     * When `response.completed` never arrives — a dropped connection, a stream
     * cut mid-item — the accumulated deltas are used instead. That turn is
     * degraded but not lost, which is the difference between "the model replied
     * and was cut off" and an error dialog.
     *
     * ## [onTextDelta] is called once per delta, in order
     *
     * And never for the completed text, so a UI that appends deltas does not end
     * the turn with the reply printed twice.
     */
    fun assemble(
        events: Sequence<SseEvent>,
        onTextDelta: ((String) -> Unit)? = null,
    ): ChatStreamResult {
        var responseId = ""
        var completed: ChatOutput? = null
        val textByItem = LinkedHashMap<String, StringBuilder>()
        val callsByItem = LinkedHashMap<String, PartialCall>()

        for (event in events) {
            when (event) {
                is SseEvent.Created -> if (event.responseId.isNotEmpty()) responseId = event.responseId

                is SseEvent.FunctionCallAdded ->
                    callsByItem[event.itemId] = PartialCall(event.callId, event.name)

                is SseEvent.TextDelta -> {
                    textByItem.getOrPut(event.itemId) { StringBuilder() }.append(event.delta)
                    onTextDelta?.invoke(event.delta)
                }

                is SseEvent.FunctionArgumentsDelta ->
                    // getOrPut, not get: `response.output_item.added` is not
                    // guaranteed to have been seen — it can be lost to a
                    // reconnect, and some backends omit it entirely — and losing
                    // the arguments because the announcement went missing would
                    // silently drop a tool call.
                    callsByItem.getOrPut(event.itemId) { PartialCall(event.callId, event.name) }
                        .arguments.append(event.delta)

                is SseEvent.Completed -> {
                    if (event.responseId.isNotEmpty()) responseId = event.responseId
                    completed = event.output
                }

                is SseEvent.Failed -> return ChatStreamResult.Failed(event.message)

                SseEvent.Done -> Unit
            }
        }

        val output = completed?.takeIf { it.text != null || it.functionCalls.isNotEmpty() }
            ?: ChatOutput(
                text = textByItem.values
                    .map { it.toString() }
                    .lastOrNull { it.isNotBlank() },
                functionCalls = callsByItem.values
                    // A call whose name never arrived cannot be dispatched, and
                    // passing it on would only produce an "unknown tool" round
                    // trip at the model's expense.
                    .filter { it.name.isNotEmpty() }
                    .map {
                        ChatFunctionCall(
                            callId = it.callId,
                            name = it.name,
                            arguments = it.arguments.toString().takeIf { a -> a.isNotBlank() } ?: "{}",
                        )
                    },
            )

        return ChatStreamResult.Ok(
            ChatResponse(
                id = responseId,
                outputText = output.text,
                functionCalls = output.functionCalls,
            ),
        )
    }

    /** A function call still being assembled from `…arguments.delta` fragments. */
    private class PartialCall(
        val callId: String,
        val name: String,
        val arguments: StringBuilder = StringBuilder(),
    )

    private const val DATA_PREFIX = "data:"
    private const val DONE = "[DONE]"

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    // endregion
}

// region request models

/**
 * One Responses API request.
 *
 * Only the subset this app sends. `previous_response_id` is deliberately absent:
 * with [store] false there is no server-side conversation to continue, and the
 * whole transcript is replayed in [input] each turn — which is also what makes a
 * conversation survive the app being killed, since the only copy that matters is
 * the one on disk.
 */
@Serializable
data class ChatRequest(
    val model: String = ChatWire.MODEL,
    /** The system prompt; see `GlyphAiPrompt`. */
    val instructions: String = "",
    val input: List<ChatInputItem> = emptyList(),
    val tools: List<JsonElement> = emptyList(),
    /** Always true in this app — see [ChatWire.json]. */
    val stream: Boolean = true,
    /**
     * False, always. The user's designs and messages are their own; asking the
     * backend to retain the conversation would be a promise this app has not
     * made to anybody.
     */
    val store: Boolean = false,
    val reasoning: ChatReasoning? = null,
)

/** How hard the backend should think. Omitted entirely on backends that reject it. */
@Serializable
data class ChatReasoning(val effort: String = ChatWire.DEFAULT_REASONING_EFFORT)

/**
 * One item of the `input` array.
 *
 * A sealed hierarchy with `type` as its discriminator, which is exactly the
 * shape the API documents — so the encoder needs no custom serializer and a new
 * item kind is a new subclass and nothing else.
 */
@Serializable
sealed interface ChatInputItem

/** A turn of the conversation: `user` or `assistant`, with typed content parts. */
@Serializable
@SerialName("message")
data class ChatMessageItem(
    val role: String,
    val content: List<ChatContentPart>,
) : ChatInputItem {
    companion object {
        /** A plain user turn. */
        fun user(text: String): ChatMessageItem =
            ChatMessageItem(ROLE_USER, listOf(ChatInputText(text)))

        /**
         * A user turn with images.
         *
         * The text comes first: a model reads the instruction and then looks,
         * and an image with no accompanying text produces a description rather
         * than an action.
         */
        fun user(text: String, imageDataUrls: List<String>): ChatMessageItem =
            ChatMessageItem(
                ROLE_USER,
                buildList {
                    if (text.isNotBlank()) add(ChatInputText(text))
                    imageDataUrls.forEach { add(ChatInputImage(it)) }
                },
            )

        /**
         * A past assistant turn, replayed as history.
         *
         * Note the content part is [ChatOutputText], not [ChatInputText]: on the
         * input side the API types content by who produced it, and an assistant
         * message carrying `input_text` is rejected.
         */
        fun assistant(text: String): ChatMessageItem =
            ChatMessageItem(ROLE_ASSISTANT, listOf(ChatOutputText(text)))

        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
    }
}

/** One piece of a message's content. */
@Serializable
sealed interface ChatContentPart

/** Text the user wrote. */
@Serializable
@SerialName("input_text")
data class ChatInputText(val text: String) : ChatContentPart

/** Text the assistant produced, when replaying history. */
@Serializable
@SerialName("output_text")
data class ChatOutputText(val text: String) : ChatContentPart

/**
 * An image the user attached, inline.
 *
 * [imageUrl] is a base64 data URL built by [ChatWire.imageDataUrl]. Whether this
 * backend accepts image input at all is one of the feature's open runtime
 * questions; the format is here so the answer is a one-line change either way.
 */
@Serializable
@SerialName("input_image")
data class ChatInputImage(
    @SerialName("image_url") val imageUrl: String,
    val detail: String = "auto",
) : ChatContentPart

/** A tool call being replayed back to the model, verbatim as it made it. */
@Serializable
@SerialName("function_call")
data class ChatFunctionCallItem(
    @SerialName("call_id") val callId: String,
    val name: String,
    val arguments: String,
) : ChatInputItem

/** What that tool call produced. [output] is the tool's JSON, as text. */
@Serializable
@SerialName("function_call_output")
data class ChatFunctionCallOutputItem(
    @SerialName("call_id") val callId: String,
    val output: String,
) : ChatInputItem

// endregion

// region response models

/** One event of the SSE stream, already understood. */
sealed interface SseEvent {
    /** `response.created` — the response id, which the rest of the turn quotes. */
    data class Created(val responseId: String) : SseEvent

    /** `response.output_text.delta` — one fragment of the visible reply. */
    data class TextDelta(val itemId: String, val delta: String) : SseEvent

    /** `response.output_item.added` for a `function_call` item. */
    data class FunctionCallAdded(
        val itemId: String,
        val callId: String,
        val name: String,
    ) : SseEvent

    /** `response.function_call_arguments.delta` — one fragment of a call's arguments. */
    data class FunctionArgumentsDelta(
        val itemId: String,
        val callId: String,
        val name: String,
        val delta: String,
    ) : SseEvent

    /** `response.completed` — the authoritative output array. */
    data class Completed(val responseId: String, val output: ChatOutput) : SseEvent

    /** An `error` or `response.failed` event: the server says this turn did not happen. */
    data class Failed(val message: String) : SseEvent

    /** `[DONE]`. Nothing follows it. */
    data object Done : SseEvent
}

/** The two things an output array can contain that this app acts on. */
data class ChatOutput(
    val text: String?,
    val functionCalls: List<ChatFunctionCall>,
)

/** One tool call the model wants run. [arguments] is JSON text, possibly malformed. */
data class ChatFunctionCall(
    val callId: String,
    val name: String,
    val arguments: String,
)

/** A completed turn of the model. */
data class ChatResponse(
    val id: String,
    val outputText: String?,
    val functionCalls: List<ChatFunctionCall>,
)

/**
 * What a stream produced.
 *
 * A sealed result rather than an exception because a declared server error is
 * not an exceptional condition here — it is one of the two ordinary outcomes of
 * asking a model something, and the caller shows it to the user either way. The
 * *transport* still throws, since a socket that will not open is genuinely
 * exceptional and there is nothing to show.
 */
sealed interface ChatStreamResult {
    data class Ok(val response: ChatResponse) : ChatStreamResult
    data class Failed(val message: String) : ChatStreamResult
}

// endregion
