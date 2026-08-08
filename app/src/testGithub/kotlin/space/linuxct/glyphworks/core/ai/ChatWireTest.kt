package space.linuxct.glyphworks.core.ai

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The SSE parse and the request body — the two halves of the wire format, and the
 * only two parts of the network path that can be executed without a network.
 *
 * The stream cases below are not hypothetical. Every one of them is a shape a
 * real Responses API stream produces: keep-alive comments between events, the
 * `event:` line that precedes every `data:` line, a response whose visible text
 * arrives in two output items, a function call whose arguments come in six
 * fragments, and — the case that costs a user their turn if it is wrong — a
 * connection that dies with half a JSON object on the wire.
 */
class ChatWireTest {
    // region text

    @Test
    fun `text deltas are streamed in order and the completed response wins`() {
        val seen = mutableListOf<String>()
        val result = assemble(
            lines(
                created("resp_1"),
                textDelta("msg_1", "Hel"),
                textDelta("msg_1", "lo, "),
                textDelta("msg_1", "world"),
                completed("resp_1", messageItem("Hello, world")),
                "data: [DONE]",
            ),
            onTextDelta = { seen += it },
        )

        val ok = result as ChatStreamResult.Ok
        assertEquals(listOf("Hel", "lo, ", "world"), seen)
        assertEquals("Hello, world", ok.response.outputText)
        assertEquals("resp_1", ok.response.id)
        assertTrue(ok.response.functionCalls.isEmpty())
    }

    @Test
    fun `a stream that ends mid-item still yields the text it delivered`() {
        val seen = mutableListOf<String>()
        val result = assemble(
            lines(
                created("resp_3"),
                textDelta("msg_1", "The panel is "),
                // The connection died here: half an event, no completed, no [DONE].
                """data: {"type":"response.output_text.delta","item_id":"msg_1","delta":"rou""",
            ),
            onTextDelta = { seen += it },
        )

        val ok = result as ChatStreamResult.Ok
        assertEquals("The panel is ", ok.response.outputText)
        assertEquals(listOf("The panel is "), seen)
    }

    // endregion

    // region function calls

    @Test
    fun `function call arguments are assembled from their fragments`() {
        val result = assemble(
            lines(
                created("resp_4"),
                """data: {"type":"response.output_item.added","item":{"id":"fc_1","type":"function_call","name":"apply_design","call_id":"call_1"}}""",
                argsDelta("fc_1", """{"desi"""),
                argsDelta("fc_1", """gn":"{}"""),
                argsDelta("fc_1", """"}"""),
                "data: [DONE]",
            ),
        )

        val calls = (result as ChatStreamResult.Ok).response.functionCalls
        assertEquals(1, calls.size)
        assertEquals("apply_design", calls[0].name)
        assertEquals("call_1", calls[0].callId)
        assertEquals("""{"design":"{}"}""", calls[0].arguments)
    }

    @Test
    fun `a call whose name never arrived is dropped rather than dispatched`() {
        val result = assemble(
            lines(
                """data: {"type":"response.function_call_arguments.delta","item_id":"fc_x","delta":"{}"}""",
                "data: [DONE]",
            ),
        )

        assertTrue((result as ChatStreamResult.Ok).response.functionCalls.isEmpty())
    }

    @Test
    fun `a completed response carries the call whole and defaults empty arguments`() {
        val result = assemble(
            lines(
                created("resp_5"),
                """data: {"type":"response.completed","response":{"id":"resp_5","output":[{"type":"reasoning","id":"rs_1","summary":[]},{"type":"function_call","id":"fc_1","call_id":"call_7","name":"get_current_design","arguments":""}]}}""",
                "data: [DONE]",
            ),
        )

        val response = (result as ChatStreamResult.Ok).response
        // The `reasoning` item is a type this build has no use for and must not
        // choke on — the array will grow more of them over time.
        assertEquals(1, response.functionCalls.size)
        assertEquals("get_current_design", response.functionCalls[0].name)
        assertEquals("{}", response.functionCalls[0].arguments)
        assertNull(response.outputText)
    }

    @Test
    fun `text and a function call in one response are both reported`() {
        val result = assemble(
            lines(
                created("resp_6"),
                """data: {"type":"response.completed","response":{"id":"resp_6","output":[{"type":"message","content":[{"type":"output_text","text":"Let me look."}]},{"type":"function_call","call_id":"c1","name":"get_current_design","arguments":"{}"}]}}""",
            ),
        )

        val response = (result as ChatStreamResult.Ok).response
        assertEquals("Let me look.", response.outputText)
        assertEquals(1, response.functionCalls.size)
    }

    // endregion

    // region failures and noise

    @Test
    fun `an error event fails the stream and stops reading`() {
        val result = assemble(
            lines(
                created("resp_7"),
                textDelta("msg_1", "partial"),
                """data: {"type":"error","message":"model_not_found"}""",
                completed("resp_7", messageItem("this should never be read")),
            ),
        )

        assertEquals("model_not_found", (result as ChatStreamResult.Failed).message)
    }

    @Test
    fun `blank comment and malformed lines are skipped rather than fatal`() {
        val seen = mutableListOf<String>()
        val result = assemble(
            lines(
                "",
                ": keep-alive",
                "event: response.created",
                created("resp_9"),
                "",
                "not an sse line at all",
                "data: ",
                "data: {this is not json}",
                """data: {"type":"response.output_text.delta"}""", // no delta field
                """data: ["an","array","not","an","object"]""",
                """data: {"type":"response.something.we.have.never.heard.of","x":1}""",
                "event: response.output_text.delta",
                textDelta("msg_1", "survived"),
                "data: [DONE]",
            ),
            onTextDelta = { seen += it },
        )

        assertEquals("survived", (result as ChatStreamResult.Ok).response.outputText)
        assertEquals(listOf("survived"), seen)
    }

    // endregion

    // region request

    @Test
    fun `a request carries the streaming and no-store flags it depends on`() {
        val body = ChatWire.encodeRequest(
            ChatRequest(
                instructions = "be helpful",
                input = listOf(ChatMessageItem.user("hello")),
                reasoning = ChatReasoning(),
            ),
        )

        // `stream` is a default, so a serializer that dropped defaults would
        // silently ask for a non-streaming response and the whole parse above
        // would see one JSON document and no events.
        assertTrue(body, body.contains("\"stream\":true"))
        assertTrue(body, body.contains("\"store\":false"))
        assertTrue(body, body.contains("\"model\":\"${ChatWire.MODEL}\""))
        assertTrue(body, body.contains("\"instructions\":\"be helpful\""))
        assertTrue(body, body.contains("\"effort\":\"medium\""))
        assertTrue(body, body.contains("\"type\":\"message\""))
        assertTrue(body, body.contains("\"type\":\"input_text\""))
    }

    @Test
    fun `tool call and tool output items serialize with the API's discriminators`() {
        val body = ChatWire.encodeRequest(
            ChatRequest(
                input = listOf(
                    ChatFunctionCallItem(callId = "c1", name = "apply_design", arguments = "{}"),
                    ChatFunctionCallOutputItem(callId = "c1", output = """{"ok":true}"""),
                ),
            ),
        )

        assertTrue(body, body.contains("\"type\":\"function_call\""))
        assertTrue(body, body.contains("\"type\":\"function_call_output\""))
        assertTrue(body, body.contains("\"call_id\":\"c1\""))
    }

    @Test
    fun `an assistant history turn uses output_text, which is what input accepts`() {
        val body = ChatWire.encodeRequest(
            ChatRequest(input = listOf(ChatMessageItem.assistant("I made it rounder."))),
        )

        assertTrue(body, body.contains("\"role\":\"assistant\""))
        assertTrue(body, body.contains("\"type\":\"output_text\""))
    }

    @Test
    fun `an attached image rides as a base64 data URL with the text first`() {
        val url = ChatWire.imageDataUrl("QUJD", "image/png")
        assertEquals("data:image/png;base64,QUJD", url)

        val message = ChatMessageItem.user("make this", listOf(url))
        assertEquals(2, message.content.size)
        assertTrue(message.content[0] is ChatInputText)
        assertEquals(url, (message.content[1] as ChatInputImage).imageUrl)

        val body = ChatWire.encodeRequest(ChatRequest(input = listOf(message)))
        assertTrue(body, body.contains("\"type\":\"input_image\""))
        assertTrue(body, body.contains("\"image_url\":\"data:image/png;base64,QUJD\""))
    }

    @Test
    fun `tool specs are embedded verbatim except for strict, which the backend rejects`() {
        val specs = ChatWire.toolSpecs(GlyphAiTools.build())

        // Counted from the tools rather than written down: adding one is a
        // routine change, and a literal here would fail for no reason.
        assertEquals(GlyphAiTools.build().size, specs.size)
        specs.forEach { assertTrue(it.toString(), !it.toString().contains("strict")) }
        assertTrue(specs.any { it.toString().contains("\"name\":\"apply_design\"") })

        // Kept when asked for, since the standard API requires it.
        val strict = ChatWire.toolSpecs(GlyphAiTools.build(), dropStrict = false)
        assertTrue(strict.any { it.toString().contains("strict") })
    }

    @Test
    fun `a tool spec that will not parse is skipped rather than crashing the turn`() {
        assertNull(ChatWire.toolSpec("{not json"))
        assertNull(ChatWire.toolSpec("[]"))
        assertNotNull(ChatWire.toolSpec("""{"type":"function","name":"x"}"""))
    }

    // endregion

    // region model

    /**
     * The whole point of the override is to rescue a signed-in user whose model
     * id has stopped being accepted, so the one outcome that must be impossible
     * is this function producing something the backend will reject on its own
     * account. Blank, spaces and a null (nothing stored at all) are the three
     * ways a text field arrives empty, and all three are a request for the
     * default rather than a request for `"model": ""`.
     */
    @Test
    fun `an empty override of any kind falls back to the built-in model`() {
        assertEquals(ChatWire.MODEL, ChatWire.resolveModel(""))
        assertEquals(ChatWire.MODEL, ChatWire.resolveModel("   "))
        assertEquals(ChatWire.MODEL, ChatWire.resolveModel("\t\n "))
        assertEquals(ChatWire.MODEL, ChatWire.resolveModel(null))
    }

    @Test
    fun `a real override is sent exactly as typed`() {
        assertEquals("gpt-5.4", ChatWire.resolveModel("gpt-5.4"))
        assertEquals("o4-mini", ChatWire.resolveModel("o4-mini"))
    }

    private fun assemble(
        lines: Sequence<String>,
        onTextDelta: ((String) -> Unit)? = null,
    ): ChatStreamResult = ChatWire.assemble(ChatWire.parseSse(lines), onTextDelta)

    private fun lines(vararg lines: String): Sequence<String> = lines.asSequence()

    private fun created(id: String) =
        """data: {"type":"response.created","response":{"id":"$id"}}"""

    private fun textDelta(itemId: String, delta: String) =
        """data: {"type":"response.output_text.delta","item_id":"$itemId","delta":"$delta"}"""

    private fun argsDelta(itemId: String, delta: String) =
        """data: {"type":"response.function_call_arguments.delta","item_id":"$itemId","delta":${
            JsonPrimitive(delta)
        }}"""

    private fun messageItem(text: String) =
        """{"type":"message","role":"assistant","content":[{"type":"output_text","text":"$text"}]}"""

    private fun completed(id: String, vararg items: String) =
        """data: {"type":"response.completed","response":{"id":"$id","output":[${items.joinToString(",")}]}}"""

    // endregion
}
