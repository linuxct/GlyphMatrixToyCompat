package space.linuxct.glyphworks.core.ai

import kotlinx.serialization.json.JsonElement
import space.linuxct.glyphworks.core.design.Design

/**
 * The model's end of one turn, as the orchestrator needs it.
 *
 * **An interface, and that is the whole design of this file.** The tool loop is
 * the part of this feature most likely to be wrong — a call whose output is
 * appended in the wrong order, a round counter that lets a model loop forever, an
 * error result that ends the turn instead of being handed back for correction —
 * and it is also the part that a device test can only exercise by luck. Behind an
 * interface it is exercised by a fake with scripted answers, in milliseconds,
 * including the paths a real model produces once a month.
 *
 * `ai/GlyphAiClient` is the real implementation. Nothing else in `core/` knows
 * that HTTP exists.
 */
interface GlyphChatClient {
    /**
     * Sends [request] and returns what the model produced.
     *
     * [onTextDelta] is invoked for each fragment of visible text as it arrives,
     * in order, so a UI can stream it. It is **not** invoked during tool rounds'
     * silent portions — there simply are none — nor replayed for the final text.
     *
     * A declared refusal or server-side error comes back as
     * [ChatStreamResult.Failed]. A transport failure throws, because there is
     * nothing to report but the exception.
     */
    suspend fun respond(
        request: ChatRequest,
        onTextDelta: ((String) -> Unit)? = null,
    ): ChatStreamResult
}

/**
 * What the assistant is doing, for the UI to narrate.
 *
 * Structured rather than a sentence, following the convention `ai/GlyphAiViewModel`
 * already sets for [SignInFailure]: this app's user-facing copy lives in
 * `strings.xml`, and a translated string assembled in `core/` would be neither
 * translated nor findable. [defaultText] exists as the fallback for a tool name
 * the UI has no string for — which is what a *future* tool looks like to an
 * already-shipped screen.
 */
sealed interface ChatTrace {
    /** Waiting on the model's first answer. */
    data object Thinking : ChatTrace

    /** Running the named tool. */
    data class RunningTool(val name: String) : ChatTrace

    /** Tool results are in; waiting on the model to read them. */
    data object Processing : ChatTrace

    /** English, for a UI with no string for this step. */
    fun defaultText(): String = when (this) {
        Thinking -> "Thinking…"
        Processing -> "Reading the results…"
        is RunningTool -> when (name) {
            GlyphAiTools.GET_CURRENT_DESIGN -> "Reading your design…"
            GlyphAiTools.APPLY_DESIGN -> "Applying changes…"
            GlyphAiTools.VALIDATE_DESIGN -> "Checking the design…"
            else -> "Running ${name.replace('_', ' ')}…"
        }
    }
}

/**
 * Runs one turn of the design assistant: ask, run whatever tools come back, ask
 * again with the results, until the model answers in words.
 *
 * Adapted from `pulseloop/coach/CoachOrchestrator.kt`, with four departures, each
 * of which is a thing this feature needs and that one does not:
 *
 * 1. **It applies nothing.** `apply_design` hands back a validated [Design]; this
 *    class passes it to [applyDesign] and reports it in the result. The editor
 *    owns the canvas, the undo snapshot and the dirty flag, and none of those are
 *    concepts `core/` can see. If [applyDesign] reports a failure, the *tool
 *    result the model is shown* is rewritten as an error — because the tool's own
 *    JSON says "this is on the user's canvas now", and letting that stand after a
 *    failed apply would have the model describe a change nobody made.
 * 2. **The tool context follows the design.** After an accepted apply, later
 *    tools in the same turn see the new document. Otherwise a model that applied
 *    a change and then called `get_current_design` to check its work would be
 *    shown the design it had just replaced, and would "fix" a problem that no
 *    longer existed.
 * 3. **Running out of rounds is a failure with a reason**, not a turn that
 *    quietly produces no text. A model that has spent eight rounds is stuck, and
 *    the user needs to be told that rather than shown an empty bubble — but it is
 *    told *and* handed the best draft there was. See [salvage].
 * 4. **No response schema.** The reply is prose for a chat bubble; there is
 *    nothing to parse.
 *
 * Nothing in here throws. Every failure — transport, server error, a tool that
 * blew up, an exhausted round budget — becomes a [TurnResult.Failure] carrying
 * whatever was accomplished first, because a turn that applied a design and then
 * lost the connection must still tell its caller about the design.
 */
class GlyphAiOrchestrator(
    private val client: GlyphChatClient,
    private val tools: List<GlyphTool> = GlyphAiTools.build(),
    private val model: String = ChatWire.MODEL,
    /**
     * How many tool rounds one turn may take before it is cut off.
     *
     * pulseloop's eight. Generous for this feature's shape — read, validate,
     * apply, check is four — and the point of the cap is not efficiency but
     * termination: without it, a model that keeps failing validation would loop
     * against the user's account until something else broke.
     */
    private val maxRounds: Int = DEFAULT_MAX_ROUNDS,
    /** Null omits the `reasoning` field entirely, for a backend that rejects it. */
    private val reasoningEffort: String? = ChatWire.DEFAULT_REASONING_EFFORT,
    /**
     * Puts an accepted design on the canvas. Returns null on success, or the
     * reason it could not be applied — which is then what the model is told.
     *
     * Defaults to "applied", which is the right default for a test and for any
     * caller that only wants the design reported back to it.
     */
    private val applyDesign: (Design) -> String? = { null },
    private val onTrace: (ChatTrace) -> Unit = {},
    /**
     * Called the moment a tool call is resolved, with the same [ChatToolNote]
     * that will appear in [TurnResult.Success.toolNotes] at the end.
     *
     * [onTrace] narrates what is happening *now* and forgets it a moment later,
     * which is the whole of what a one-line status can carry. That is fine for a
     * turn that takes three seconds and actively misleading for one that takes
     * two minutes: a model that fails validation four times and redraws produces
     * four steps the user never sees, and the only visible symptom is a status
     * line that keeps saying the same thing. This is the accumulating half — one
     * note per finished call, in order, [ChatToolNote.ok] included — so a UI can
     * show the attempts piling up and make a retry read as progress rather than
     * as a stall.
     *
     * Emitted after the apply hook has run, so a design that validated but could
     * not be put on the canvas is reported as the failure it is.
     */
    private val onToolNote: (ChatToolNote) -> Unit = {},
) {

    /** What one turn produced. */
    sealed interface TurnResult {
        /** The model answered. [text] is what to show the user. */
        data class Success(
            val text: String,
            val rounds: Int,
            /** The last design [applyDesign] accepted this turn, or null. */
            val appliedDesign: Design?,
            val toolNotes: List<ChatToolNote>,
            /**
             * The full `input` array as it ended up, tool items included. Not
             * needed to continue the conversation — history is replayed from the
             * transcript — but it is the only complete record of what was sent,
             * and having it makes a failing turn diagnosable.
             */
            val items: List<ChatInputItem>,
        ) : TurnResult

        /**
         * The turn did not produce an answer. [appliedDesign] may still be
         * non-null: a design applied in round two is on the canvas whatever
         * happens in round three.
         */
        data class Failure(
            val reason: Reason,
            val detail: String,
            val rounds: Int,
            val appliedDesign: Design?,
            val toolNotes: List<ChatToolNote>,
        ) : TurnResult

        /** Why a turn failed, in the categories a UI would word differently. */
        enum class Reason {
            /** The connection failed, or the server would not answer. */
            TRANSPORT,

            /** The server declared an error or a refusal mid-stream. */
            SERVER,

            /** [maxRounds] tool rounds went by without an answer. */
            STUCK,

            /**
             * [maxRounds] tool rounds went by without an answer, **but a draft
             * that had passed validation earlier was applied on the way out**.
             *
             * A separate reason rather than a flag, so that a UI cannot show the
             * plain "it kept working and never answered" wording over a canvas
             * that has just changed. [TurnResult.Failure.appliedDesign] is
             * non-null exactly when this is the reason. See [salvage].
             */
            STUCK_SALVAGED,

            /** The stream completed carrying neither text nor a tool call. */
            EMPTY,
        }
    }

    /**
     * Runs a turn.
     *
     * @param instructions the system prompt, from `GlyphAiPrompt.build`
     * @param history past turns as input items, from `ChatTranscript.asInput`
     * @param message the user's new turn, text and any images
     * @param context the editor snapshot tools answer from, as of right now
     */
    suspend fun runTurn(
        instructions: String,
        history: List<ChatInputItem>,
        message: ChatMessageItem,
        context: GlyphToolContext,
        onTextDelta: ((String) -> Unit)? = null,
    ): TurnResult {
        val byName = tools.associateBy { it.name }
        val toolSpecs = ChatWire.toolSpecs(tools)
        val input = mutableListOf<ChatInputItem>()
        input += history
        input += message

        val notes = mutableListOf<ChatToolNote>()
        var ctx = context
        var applied: Design? = null
        /** The most recent document `validate_design` accepted. See [salvage]. */
        var validated: Design? = null
        var rounds = 0

        onTrace(ChatTrace.Thinking)
        var response = when (val first = send(instructions, input, toolSpecs, onTextDelta)) {
            is Sent.Ok -> first.response
            is Sent.Bad -> return fail(first.reason, first.detail, rounds, applied, notes)
        }

        while (response.functionCalls.isNotEmpty()) {
            if (rounds >= maxRounds) return salvage(rounds, applied, validated, notes)
            rounds++

            // Every call is echoed back before ANY of them is answered. The API
            // requires each `function_call_output` to follow its `function_call`,
            // and a model that emitted three calls in one response expects all
            // three items present; interleaving them would be rejected for a
            // parallel tool call, which is the case this ordering exists for.
            for (call in response.functionCalls) {
                input += ChatFunctionCallItem(
                    callId = call.callId,
                    name = call.name,
                    arguments = call.arguments,
                )
            }

            for (call in response.functionCalls) {
                onTrace(ChatTrace.RunningTool(call.name))
                var result = runTool(byName[call.name], call, ctx)

                // A dry run that passed is a legal drawing that nobody has been
                // asked to apply. Remembered as the fallback for a turn that runs
                // out of rounds — see [salvage] — and deliberately the LAST such
                // draft rather than the first, because a model that keeps
                // redrawing is usually converging.
                result.validated?.let { validated = it }

                val produced = result.design
                if (produced != null) {
                    // The tool's own JSON already claims the change is on the
                    // canvas, so the apply happens BEFORE that JSON is shown to
                    // the model — and if it does not happen, the claim is
                    // replaced rather than left standing. See this class's KDoc.
                    val problem = try {
                        applyDesign(produced)
                    } catch (e: Exception) {
                        e.message ?: e.javaClass.simpleName
                    }
                    if (problem == null) {
                        applied = produced
                        ctx = ctx.copy(design = produced)
                    } else {
                        result = GlyphToolResult(
                            json = APPLY_FAILED_JSON.format(problem.replace('"', '\'')),
                            isError = true,
                        )
                    }
                }

                val note = ChatToolNote(
                    name = call.name,
                    label = ChatToolNote.labelFor(call.name),
                    ok = !result.isError,
                    changedDesign = produced != null && !result.isError,
                )
                notes += note
                onToolNote(note)
                input += ChatFunctionCallOutputItem(callId = call.callId, output = result.json)
            }

            onTrace(ChatTrace.Processing)
            response = when (val next = send(instructions, input, toolSpecs, onTextDelta)) {
                is Sent.Ok -> next.response
                is Sent.Bad -> return fail(next.reason, next.detail, rounds, applied, notes)
            }
        }

        val text = response.outputText?.takeIf { it.isNotBlank() }
            ?: return fail(
                TurnResult.Reason.EMPTY,
                "The assistant finished without saying anything.",
                rounds,
                applied,
                notes,
            )

        return TurnResult.Success(
            text = text,
            rounds = rounds,
            appliedDesign = applied,
            toolNotes = notes,
            items = input.toList(),
        )
    }

    /**
     * One call, with an unknown name and a thrown exception both turned into
     * results the model can read and correct from.
     *
     * `GlyphAiTools` already refuses an unknown name, but the lookup is repeated
     * here because this class takes an arbitrary [tools] list — a caller with a
     * shorter list must get the same behaviour, and "the model named a tool that
     * is not in this conversation" has to be answerable without reaching into
     * whatever object built the list.
     */
    private fun runTool(
        tool: GlyphTool?,
        call: ChatFunctionCall,
        ctx: GlyphToolContext,
    ): GlyphToolResult {
        if (tool == null) {
            return GlyphToolResult(
                json = UNKNOWN_TOOL_JSON.format(
                    call.name.replace('"', '\''),
                    tools.joinToString(", ") { "\"${it.name}\"" },
                ),
                isError = true,
            )
        }
        return try {
            tool.run(call.arguments, ctx)
        } catch (e: Exception) {
            // GlyphAiTools promises never to throw, so reaching here is a bug in
            // a tool rather than bad model output. It is still handed back as a
            // result: one broken tool must not cost the user the whole turn.
            GlyphToolResult(
                json = TOOL_THREW_JSON.format(
                    call.name.replace('"', '\''),
                    (e.message ?: e.javaClass.simpleName).replace('"', '\''),
                ),
                isError = true,
            )
        }
    }

    /**
     * The end of a turn that ran out of tool rounds: **land the best draft there
     * was, rather than hand the user nothing.**
     *
     * ## Why this exists
     *
     * `validate_design` deliberately applies nothing, so a model is free to check
     * a drawing, dislike it, and redraw. Some do that until the budget is gone —
     * an image containing a plain "10" took eight attempts and then six more —
     * and the turn then ended as [Reason.STUCK] with an empty bubble, *despite
     * legal artwork having existed several rounds earlier*. The user waited two
     * minutes for a picture that had already been drawn and was thrown away by a
     * round counter. Something on the canvas beats nothing on the canvas: they can
     * see it, undo it in one tap, or ask for a change from there.
     *
     * ## The conditions, each of which is load-bearing
     *
     * - **Only when nothing was applied this turn.** A design that reached the
     *   canvas in round two is the model's own latest word; replacing it on the
     *   way out with an older draft would undo work the user has already watched
     *   happen.
     * - **Only a document that passed `validate_design`.** Never a draft that
     *   merely got written — [GlyphToolResult.validated] is set by the dry run
     *   alone, so what lands here is something this app's own codec accepted.
     * - **Only if the apply succeeds.** If the editor refuses it, this is exactly
     *   the failure it always was.
     *
     * ## And it is reported as what it is
     *
     * [Reason.STUCK_SALVAGED], not a [TurnResult.Success]: the model never
     * answered, the drawing is a draft it was still working on, and telling
     * somebody their request completed normally when it ran out of time would be a
     * lie the undo banner then contradicts. The tool note is emitted too, so the
     * step list shows the apply that actually happened.
     */
    private fun salvage(
        rounds: Int,
        applied: Design?,
        validated: Design?,
        notes: MutableList<ChatToolNote>,
    ): TurnResult.Failure {
        // Names the budget AND where to change it. Running out is not always a
        // fault to be reported — for a big animation it is simply the wrong
        // number, and a message that says only "it ran out" leaves the user with
        // no move except asking again and watching it run out identically.
        val outOfRounds = "The assistant used its $maxRounds tool rounds without answering. " +
            "A complex design may need more — raise the assistant's tool rounds in Settings."
        val draft = validated?.takeIf { applied == null }
            ?: return fail(TurnResult.Reason.STUCK, outOfRounds, rounds, applied, notes)

        val problem = try {
            applyDesign(draft)
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        }
        if (problem != null) {
            return fail(TurnResult.Reason.STUCK, outOfRounds, rounds, applied, notes)
        }

        val note = ChatToolNote(
            name = GlyphAiTools.APPLY_DESIGN,
            label = ChatToolNote.labelFor(GlyphAiTools.APPLY_DESIGN),
            ok = true,
            changedDesign = true,
        )
        notes += note
        onToolNote(note)
        return fail(
            TurnResult.Reason.STUCK_SALVAGED,
            "$outOfRounds The last draft that passed its checks was applied.",
            rounds,
            draft,
            notes,
        )
    }

    private sealed interface Sent {
        data class Ok(val response: ChatResponse) : Sent
        data class Bad(val reason: TurnResult.Reason, val detail: String) : Sent
    }

    private suspend fun send(
        instructions: String,
        input: List<ChatInputItem>,
        toolSpecs: List<JsonElement>,
        onTextDelta: ((String) -> Unit)?,
    ): Sent {
        val request = ChatRequest(
            model = model,
            instructions = instructions,
            input = input.toList(),
            tools = toolSpecs,
            reasoning = reasoningEffort?.let { ChatReasoning(it) },
        )
        return try {
            when (val result = client.respond(request, onTextDelta)) {
                is ChatStreamResult.Ok -> Sent.Ok(result.response)
                is ChatStreamResult.Failed -> Sent.Bad(TurnResult.Reason.SERVER, result.message)
            }
        } catch (e: Exception) {
            Sent.Bad(TurnResult.Reason.TRANSPORT, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun fail(
        reason: TurnResult.Reason,
        detail: String,
        rounds: Int,
        applied: Design?,
        notes: List<ChatToolNote>,
    ): TurnResult.Failure = TurnResult.Failure(
        reason = reason,
        detail = detail,
        rounds = rounds,
        appliedDesign = applied,
        toolNotes = notes.toList(),
    )

    companion object {
        /** See [maxRounds]. */
        const val DEFAULT_MAX_ROUNDS = 8

        // Literal JSON with one %s, not buildJsonObject, so that what the model
        // is shown is legible in this file — the same reasoning as the tool specs
        // in GlyphAiTools. The substituted text has its double quotes replaced
        // before it lands here, so it cannot break out of the string.
        private const val UNKNOWN_TOOL_JSON =
            """{"ok":false,"error":"There is no tool called \"%s\" in this conversation.","expected":"One of: %s."}"""

        private const val TOOL_THREW_JSON =
            """{"ok":false,"error":"The tool \"%s\" failed: %s","expected":"Try a different approach, or tell the user what went wrong."}"""

        private const val APPLY_FAILED_JSON =
            """{"ok":false,"error":"The document was valid, but the editor could not apply it: %s","expected":"Nothing was changed. Tell the user, or try again."}"""
    }
}
