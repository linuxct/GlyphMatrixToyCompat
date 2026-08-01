package space.linuxct.glyphmatrixtoycompat.core.ai

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import space.linuxct.glyphmatrixtoycompat.core.design.Design
import space.linuxct.glyphmatrixtoycompat.core.design.DesignCodec
import space.linuxct.glyphmatrixtoycompat.core.design.PokemonCodename
import java.io.IOException

/**
 * The tool loop, driven by a fake model.
 *
 * This is what the [GlyphChatClient] interface is *for*. Every scenario below is
 * one a real conversation produces — a model that answers straight away, one that
 * reads before it writes, one that gets an error back and corrects itself, one
 * that hallucinates a tool name, one that never stops — and none of them is
 * reachable on demand from a device. Scripting the model's side turns "run it and
 * see" into an assertion.
 *
 * The tools underneath are the *real* [GlyphAiTools], not fakes: the point of the
 * loop is that a tool result reaches the model in a form it can act on, and a
 * stubbed tool would prove that about the stub.
 */
class GlyphAiOrchestratorTest {

    private val design = TestDesigns.bellsproutOnly()
    private val context = GlyphToolContext(design = design, openVariant = PokemonCodename.BELLSPROUT)

    // region the ordinary turns

    @Test
    fun `a turn with no tool calls is one request and the model's words`() {
        val client = FakeClient(text("The panel is a 13x13 disc."))
        val result = run(GlyphAiOrchestrator(client))

        val success = result as GlyphAiOrchestrator.TurnResult.Success
        assertEquals("The panel is a 13x13 disc.", success.text)
        assertEquals(0, success.rounds)
        assertTrue(success.toolNotes.isEmpty())
        assertNull(success.appliedDesign)
        assertEquals(1, client.requests.size)
    }

    @Test
    fun `history and the new message are both sent, in order`() {
        val client = FakeClient(text("ok"))
        runBlocking {
            GlyphAiOrchestrator(client).runTurn(
                instructions = "system",
                history = listOf(
                    ChatMessageItem.user("hi"),
                    ChatMessageItem.assistant("hello"),
                ),
                message = ChatMessageItem.user("make it rounder"),
                context = context,
            )
        }

        val input = client.requests.single().input
        assertEquals(3, input.size)
        assertEquals("system", client.requests.single().instructions)
        assertEquals(
            listOf("hi", "hello", "make it rounder"),
            input.map { (it as ChatMessageItem).content.first().let(::textOf) },
        )
    }

    @Test
    fun `one tool round appends the call and its output, then re-sends everything`() {
        val client = FakeClient(
            call("call_1", GlyphAiTools.GET_CURRENT_DESIGN, "{}"),
            text("You have two frames."),
        )
        val traces = mutableListOf<ChatTrace>()

        val result = run(GlyphAiOrchestrator(client, onTrace = { traces += it }))

        val success = result as GlyphAiOrchestrator.TurnResult.Success
        assertEquals(1, success.rounds)
        assertEquals("You have two frames.", success.text)

        val second = client.requests[1].input
        // user message, function_call, function_call_output — in that order, and
        // the call must precede its output or the API rejects the array.
        assertEquals(3, second.size)
        val callItem = second[1] as ChatFunctionCallItem
        val outputItem = second[2] as ChatFunctionCallOutputItem
        assertEquals("call_1", callItem.callId)
        assertEquals("call_1", outputItem.callId)
        assertTrue(outputItem.output, outputItem.output.contains("\"allowed_variants\""))

        assertEquals(
            listOf(
                ChatTrace.Thinking,
                ChatTrace.RunningTool(GlyphAiTools.GET_CURRENT_DESIGN),
                ChatTrace.Processing,
            ),
            traces,
        )
        assertEquals("Read your design", success.toolNotes.single().label)
        assertTrue(success.toolNotes.single().ok)
        assertFalse(success.toolNotes.single().changedDesign)
    }

    @Test
    fun `an applied design is handed to the caller and never applied here`() {
        val renamed = design.copy(name = "Rounder")
        val applied = mutableListOf<Design>()
        val client = FakeClient(
            call("call_1", GlyphAiTools.APPLY_DESIGN, applyArgs(renamed)),
            text("Done."),
        )

        val result = run(
            GlyphAiOrchestrator(client, applyDesign = { applied += it; null }),
        )

        val success = result as GlyphAiOrchestrator.TurnResult.Success
        assertEquals("Rounder", applied.single().name)
        assertEquals("Rounder", success.appliedDesign?.name)
        // The id is the app's, never the model's, even though the model sent one.
        assertEquals(design.id, success.appliedDesign?.id)
        assertTrue(success.toolNotes.single().changedDesign)
    }

    @Test
    fun `a later tool in the same turn sees the design that was just applied`() {
        val renamed = design.copy(name = "Rounder")
        val client = FakeClient(
            call("call_1", GlyphAiTools.APPLY_DESIGN, applyArgs(renamed)),
            call("call_2", GlyphAiTools.GET_CURRENT_DESIGN, "{}"),
            text("Confirmed."),
        )

        run(GlyphAiOrchestrator(client))

        // Without the context following the apply, the model checking its own
        // work would be shown the document it had just replaced.
        val readBack = client.requests[2].input
            .filterIsInstance<ChatFunctionCallOutputItem>()
            .last()
        assertTrue(readBack.output, readBack.output.contains("\"Rounder\""))
    }

    // endregion

    // region recovery

    @Test
    fun `a tool error comes back as a result the model then corrects from`() {
        val broken = design.copy(
            variants = mapOf(PokemonCodename.BELLSPROUT.codename to TestDesigns.frames(PokemonCodename.ARBOK)),
        )
        val client = FakeClient(
            // Frames the wrong size for the panel they are filed under.
            call("call_1", GlyphAiTools.APPLY_DESIGN, applyArgs(broken)),
            call("call_2", GlyphAiTools.APPLY_DESIGN, applyArgs(design.copy(name = "Fixed"))),
            text("Fixed it."),
        )

        val result = run(GlyphAiOrchestrator(client))

        val success = result as GlyphAiOrchestrator.TurnResult.Success
        assertEquals(2, success.rounds)
        assertEquals("Fixed", success.appliedDesign?.name)

        val firstOutput = client.requests[1].input
            .filterIsInstance<ChatFunctionCallOutputItem>()
            .single().output
        assertTrue(firstOutput, firstOutput.contains("\"ok\":false"))
        // The error has to say what was expected, or the model has nothing to fix.
        assertTrue(firstOutput, firstOutput.contains("expected"))
        assertTrue(firstOutput, firstOutput.contains("625"))

        assertEquals(2, success.toolNotes.size)
        assertFalse(success.toolNotes[0].ok)
        assertTrue(success.toolNotes[1].ok)
    }

    /**
     * The steps a slow turn is made of, reported as they happen rather than only
     * at the end.
     *
     * This is the whole mechanism behind the live progress list. The turn that
     * prompted it took two minutes and showed nothing until it finished, because
     * `toolNotes` only exists once [GlyphAiOrchestrator.TurnResult] does — so the
     * four failed drafts in the middle were invisible while they were the only
     * thing happening. The assertions that matter are the *ordering* ones: a note
     * has to arrive before the next request goes out, or the UI is still narrating
     * the turn a round late.
     */
    @Test
    fun `each finished tool call is reported as it happens, not only at the end`() {
        val broken = design.copy(
            variants = mapOf(PokemonCodename.BELLSPROUT.codename to TestDesigns.frames(PokemonCodename.ARBOK)),
        )
        val client = FakeClient(
            call("call_1", GlyphAiTools.VALIDATE_DESIGN, applyArgs(broken)),
            call("call_2", GlyphAiTools.VALIDATE_DESIGN, applyArgs(design)),
            call("call_3", GlyphAiTools.APPLY_DESIGN, applyArgs(design.copy(name = "Note"))),
            text("Drew you a music note."),
        )
        val live = mutableListOf<Pair<ChatToolNote, Int>>()

        val result = run(
            GlyphAiOrchestrator(
                client,
                // The request count at the moment the note arrived: a note
                // reported after the next round has already been sent is a note
                // the user saw too late for it to have meant anything.
                onToolNote = { live += it to client.requests.size },
            ),
        )

        val success = result as GlyphAiOrchestrator.TurnResult.Success
        assertEquals(
            listOf(
                GlyphAiTools.VALIDATE_DESIGN,
                GlyphAiTools.VALIDATE_DESIGN,
                GlyphAiTools.APPLY_DESIGN,
            ),
            live.map { it.first.name },
        )
        // The first draft did not validate; the second did; the apply stuck.
        assertEquals(listOf(false, true, true), live.map { it.first.ok })
        assertEquals(listOf(1, 2, 3), live.map { it.second })
        // What arrived live is exactly what the finished turn reports.
        assertEquals(success.toolNotes, live.map { it.first })
    }

    /**
     * A document that validated but that the editor would not take is a failure
     * the user must see as one — otherwise the live list shows a tick beside a
     * change that never reached the canvas.
     */
    @Test
    fun `a step is reported as failed when the apply is refused after validation`() {
        val client = FakeClient(
            call("call_1", GlyphAiTools.APPLY_DESIGN, applyArgs(design.copy(name = "Nope"))),
            text("I could not change it."),
        )
        val live = mutableListOf<ChatToolNote>()

        run(GlyphAiOrchestrator(client, applyDesign = { "the editor is closed" }, onToolNote = { live += it }))

        assertFalse(live.single().ok)
        assertFalse(live.single().changedDesign)
    }

    @Test
    fun `a tool name that does not exist is answered with the ones that do`() {
        val client = FakeClient(
            call("call_1", "draw_a_cat", "{}"),
            text("Sorry, let me use the real tools."),
        )

        val result = run(GlyphAiOrchestrator(client))

        val output = client.requests[1].input
            .filterIsInstance<ChatFunctionCallOutputItem>()
            .single().output
        assertTrue(output, output.contains("draw_a_cat"))
        assertTrue(output, output.contains(GlyphAiTools.APPLY_DESIGN))
        assertTrue(result is GlyphAiOrchestrator.TurnResult.Success)
        assertFalse((result as GlyphAiOrchestrator.TurnResult.Success).toolNotes.single().ok)
    }

    @Test
    fun `a tool that throws costs the call, not the turn`() {
        val exploding = GlyphTool("boom", """{"type":"function","name":"boom"}""") { _, _ ->
            throw IllegalStateException("kaboom")
        }
        val client = FakeClient(call("call_1", "boom", "{}"), text("Recovered."))

        val result = run(GlyphAiOrchestrator(client, tools = listOf(exploding)))

        val output = client.requests[1].input
            .filterIsInstance<ChatFunctionCallOutputItem>()
            .single().output
        assertTrue(output, output.contains("kaboom"))
        assertEquals("Recovered.", (result as GlyphAiOrchestrator.TurnResult.Success).text)
    }

    @Test
    fun `an apply the editor refuses is reported as a failure, not as a success`() {
        val client = FakeClient(
            call("call_1", GlyphAiTools.APPLY_DESIGN, applyArgs(design.copy(name = "Nope"))),
            text("I could not change it."),
        )

        val result = run(
            GlyphAiOrchestrator(client, applyDesign = { "the editor is closed" }),
        )

        val success = result as GlyphAiOrchestrator.TurnResult.Success
        // The tool's own JSON says "this is on the user's canvas now". Letting
        // that stand would have the model describe a change nobody made.
        assertNull(success.appliedDesign)
        assertFalse(success.toolNotes.single().changedDesign)
        val output = client.requests[1].input
            .filterIsInstance<ChatFunctionCallOutputItem>()
            .single().output
        assertTrue(output, output.contains("the editor is closed"))
        assertTrue(output, output.contains("\"ok\":false"))
    }

    // endregion

    // region giving up

    @Test
    fun `a model that never stops calling tools is cut off with a reason`() {
        val client = FakeClient(
            *Array(10) { call("call_$it", GlyphAiTools.GET_CURRENT_DESIGN, "{}") },
        )

        val result = run(GlyphAiOrchestrator(client, maxRounds = 3))

        val failure = result as GlyphAiOrchestrator.TurnResult.Failure
        assertEquals(GlyphAiOrchestrator.TurnResult.Reason.STUCK, failure.reason)
        assertEquals(3, failure.rounds)
        assertTrue(failure.detail, failure.detail.contains("3"))
        // Four requests: the first, then one per round. Not a fifth.
        assertEquals(4, client.requests.size)
    }

    @Test
    fun `a design applied before the round budget ran out is still reported`() {
        val client = FakeClient(
            call("call_1", GlyphAiTools.APPLY_DESIGN, applyArgs(design.copy(name = "Landed"))),
            call("call_2", GlyphAiTools.GET_CURRENT_DESIGN, "{}"),
            call("call_3", GlyphAiTools.GET_CURRENT_DESIGN, "{}"),
        )

        val result = run(GlyphAiOrchestrator(client, maxRounds = 2))

        // The canvas changed in round one. A failure in round three does not
        // un-change it, and the caller owns the undo snapshot.
        val failure = result as GlyphAiOrchestrator.TurnResult.Failure
        assertEquals("Landed", failure.appliedDesign?.name)
        assertEquals(2, failure.toolNotes.size)
    }

    /**
     * The change this whole region exists for.
     *
     * `validate_design` applies nothing, so a model is free to check a drawing,
     * dislike it and redraw — and some do that until the budget is gone. On device
     * that was a plain "10" taking eight attempts and then six more, each of which
     * ended with the user being told the assistant had got stuck and being handed
     * NOTHING, despite legal artwork having existed several rounds earlier. The
     * last draft that passed is applied on the way out instead.
     */
    @Test
    fun `a draft that validated is applied when the rounds run out`() {
        val applied = mutableListOf<Design>()
        val client = FakeClient(
            call("call_1", GlyphAiTools.VALIDATE_DESIGN, applyArgs(design.copy(name = "First draft"))),
            call("call_2", GlyphAiTools.VALIDATE_DESIGN, applyArgs(design.copy(name = "Second draft"))),
            // ...and off it goes again, with no answer and no apply in sight.
            call("call_3", GlyphAiTools.VALIDATE_DESIGN, applyArgs(design.copy(name = "Third draft"))),
        )

        val result = run(
            GlyphAiOrchestrator(client, maxRounds = 2, applyDesign = { applied += it; null }),
        )

        val failure = result as GlyphAiOrchestrator.TurnResult.Failure
        // The LAST draft that passed, not the first: a model that keeps redrawing
        // is usually converging.
        assertEquals("Second draft", applied.single().name)
        assertEquals("Second draft", failure.appliedDesign?.name)
        // Reported as what it is — out of time, with a draft on the canvas — and
        // never as an ordinary success.
        assertEquals(GlyphAiOrchestrator.TurnResult.Reason.STUCK_SALVAGED, failure.reason)
        assertTrue(failure.detail, failure.detail.contains("2"))
        assertTrue(failure.detail, failure.detail.contains("draft"))
        // The apply that actually happened is in the record, so the step list does
        // not stop one line short of the thing the user is looking at.
        val last = failure.toolNotes.last()
        assertEquals(GlyphAiTools.APPLY_DESIGN, last.name)
        assertTrue(last.ok)
        assertTrue(last.changedDesign)
    }

    /**
     * Salvage is a fallback, not a rewrite of what the model did. A design that
     * reached the canvas in an earlier round is the model's own latest word, and
     * replacing it on the way out with the draft before it would undo work the
     * user has already watched happen.
     */
    @Test
    fun `a design already applied is never replaced by an older validated draft`() {
        val applied = mutableListOf<Design>()
        val client = FakeClient(
            call("call_1", GlyphAiTools.APPLY_DESIGN, applyArgs(design.copy(name = "Landed"))),
            call("call_2", GlyphAiTools.VALIDATE_DESIGN, applyArgs(design.copy(name = "Idea"))),
            call("call_3", GlyphAiTools.GET_CURRENT_DESIGN, "{}"),
        )

        val result = run(
            GlyphAiOrchestrator(client, maxRounds = 2, applyDesign = { applied += it; null }),
        )

        val failure = result as GlyphAiOrchestrator.TurnResult.Failure
        assertEquals(listOf("Landed"), applied.map { it.name })
        assertEquals("Landed", failure.appliedDesign?.name)
        assertEquals(GlyphAiOrchestrator.TurnResult.Reason.STUCK, failure.reason)
    }

    /** Nothing ever validated, so there is nothing to fall back on. */
    @Test
    fun `a turn with no validated draft still fails empty-handed`() {
        val broken = design.copy(
            variants = mapOf(PokemonCodename.BELLSPROUT.codename to TestDesigns.frames(PokemonCodename.ARBOK)),
        )
        val applied = mutableListOf<Design>()
        val client = FakeClient(
            call("call_1", GlyphAiTools.VALIDATE_DESIGN, applyArgs(broken)),
            call("call_2", GlyphAiTools.VALIDATE_DESIGN, applyArgs(broken)),
        )

        val result = run(
            GlyphAiOrchestrator(client, maxRounds = 1, applyDesign = { applied += it; null }),
        )

        val failure = result as GlyphAiOrchestrator.TurnResult.Failure
        assertEquals(GlyphAiOrchestrator.TurnResult.Reason.STUCK, failure.reason)
        assertNull(failure.appliedDesign)
        assertTrue(applied.isEmpty())
    }

    /** If the canvas will not take the draft, this is the failure it always was. */
    @Test
    fun `a salvage the editor refuses is reported as the plain failure`() {
        val client = FakeClient(
            call("call_1", GlyphAiTools.VALIDATE_DESIGN, applyArgs(design.copy(name = "Draft"))),
            call("call_2", GlyphAiTools.VALIDATE_DESIGN, applyArgs(design.copy(name = "Draft"))),
        )
        val notes = mutableListOf<ChatToolNote>()

        val result = run(
            GlyphAiOrchestrator(
                client,
                maxRounds = 1,
                applyDesign = { "the editor is closed" },
                onToolNote = { notes += it },
            ),
        )

        val failure = result as GlyphAiOrchestrator.TurnResult.Failure
        assertEquals(GlyphAiOrchestrator.TurnResult.Reason.STUCK, failure.reason)
        assertNull(failure.appliedDesign)
        // And no note claiming an apply that did not happen.
        assertTrue(notes.none { it.name == GlyphAiTools.APPLY_DESIGN })
    }

    @Test
    fun `a transport failure is a failure, not an exception`() {
        val client = FakeClient(throwing = IOException("no route to host"))

        val result = run(GlyphAiOrchestrator(client))

        val failure = result as GlyphAiOrchestrator.TurnResult.Failure
        assertEquals(GlyphAiOrchestrator.TurnResult.Reason.TRANSPORT, failure.reason)
        assertEquals("no route to host", failure.detail)
    }

    @Test
    fun `a server error mid-stream is surfaced with the server's wording`() {
        val client = FakeClient(ChatStreamResult.Failed("model_not_found"))

        val result = run(GlyphAiOrchestrator(client))

        val failure = result as GlyphAiOrchestrator.TurnResult.Failure
        assertEquals(GlyphAiOrchestrator.TurnResult.Reason.SERVER, failure.reason)
        assertEquals("model_not_found", failure.detail)
    }

    @Test
    fun `a turn that produces neither words nor a tool call is a failure`() {
        val client = FakeClient(
            ChatStreamResult.Ok(ChatResponse(id = "r", outputText = "   ", functionCalls = emptyList())),
        )

        val result = run(GlyphAiOrchestrator(client))

        assertEquals(
            GlyphAiOrchestrator.TurnResult.Reason.EMPTY,
            (result as GlyphAiOrchestrator.TurnResult.Failure).reason,
        )
    }

    @Test
    fun `a failure in a later round keeps the rounds already spent`() {
        val client = FakeClient(
            listOf(
                ChatStreamResult.Ok(
                    ChatResponse("r1", null, listOf(ChatFunctionCall("c1", GlyphAiTools.GET_CURRENT_DESIGN, "{}"))),
                ),
            ),
            throwing = IOException("dropped"),
        )

        val failure = run(GlyphAiOrchestrator(client)) as GlyphAiOrchestrator.TurnResult.Failure
        assertEquals(1, failure.rounds)
        assertEquals(1, failure.toolNotes.size)
    }

    // endregion

    // region streaming and the request shape

    @Test
    fun `text deltas are passed straight through to the caller`() {
        val client = FakeClient(text("done"), deltas = listOf("do", "ne"))
        val seen = mutableListOf<String>()

        runBlocking {
            GlyphAiOrchestrator(client).runTurn(
                instructions = "system",
                history = emptyList(),
                message = ChatMessageItem.user("go"),
                context = context,
                onTextDelta = { seen += it },
            )
        }

        assertEquals(listOf("do", "ne"), seen)
    }

    @Test
    fun `every request advertises the tools and the configured model`() {
        val client = FakeClient(text("ok"))
        run(GlyphAiOrchestrator(client, model = "some-other-model", reasoningEffort = null))

        val request = client.requests.single()
        assertEquals("some-other-model", request.model)
        assertNull(request.reasoning)
        assertEquals(3, request.tools.size)
        assertTrue(request.stream)
        assertFalse(request.store)
    }

    @Test
    fun `the default trace wording names what each tool is doing`() {
        assertEquals("Thinking…", ChatTrace.Thinking.defaultText())
        assertEquals(
            "Reading your design…",
            ChatTrace.RunningTool(GlyphAiTools.GET_CURRENT_DESIGN).defaultText(),
        )
        assertEquals(
            "Applying changes…",
            ChatTrace.RunningTool(GlyphAiTools.APPLY_DESIGN).defaultText(),
        )
        // A tool added after this build shipped still narrates as something.
        assertEquals("Running image to grid…", ChatTrace.RunningTool("image_to_grid").defaultText())
    }

    // endregion

    // region helpers

    private fun run(orchestrator: GlyphAiOrchestrator): GlyphAiOrchestrator.TurnResult =
        runBlocking {
            orchestrator.runTurn(
                instructions = "system",
                history = emptyList(),
                message = ChatMessageItem.user("make it rounder"),
                context = context,
            )
        }

    private fun applyArgs(design: Design): String =
        buildJsonObject { put(GlyphAiTools.ARG_DESIGN, DesignCodec.encode(design)) }.toString()

    private fun text(text: String) =
        ChatStreamResult.Ok(ChatResponse(id = "r", outputText = text, functionCalls = emptyList()))

    private fun call(callId: String, name: String, arguments: String) =
        ChatStreamResult.Ok(
            ChatResponse(
                id = "r",
                outputText = null,
                functionCalls = listOf(ChatFunctionCall(callId, name, arguments)),
            ),
        )

    private fun textOf(part: ChatContentPart): String = when (part) {
        is ChatInputText -> part.text
        is ChatOutputText -> part.text
        is ChatInputImage -> part.imageUrl
    }

    /**
     * A model with its answers written down in advance.
     *
     * Records every request so the tests can assert on what the loop *built* —
     * which is where its real behaviour is: the order of the input array, whether
     * the tool output made it back, whether the second request carried the first
     * request's context.
     */
    private class FakeClient(
        private val script: List<ChatStreamResult>,
        private val throwing: Throwable? = null,
        private val deltas: List<String> = emptyList(),
    ) : GlyphChatClient {

        constructor(
            vararg script: ChatStreamResult,
            deltas: List<String> = emptyList(),
        ) : this(script.toList(), null, deltas)

        constructor(throwing: Throwable) : this(emptyList(), throwing)

        val requests = mutableListOf<ChatRequest>()

        override suspend fun respond(
            request: ChatRequest,
            onTextDelta: ((String) -> Unit)?,
        ): ChatStreamResult {
            requests += request
            val index = requests.size - 1
            if (index >= script.size) {
                throwing?.let { throw it }
                error("the fake model ran out of scripted answers after ${script.size}")
            }
            deltas.forEach { onTextDelta?.invoke(it) }
            return script[index]
        }
    }

    // endregion
}
