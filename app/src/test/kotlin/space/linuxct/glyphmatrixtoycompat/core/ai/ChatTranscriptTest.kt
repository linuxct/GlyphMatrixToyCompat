package space.linuxct.glyphmatrixtoycompat.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stored conversation: that it survives a round trip, that a file from a
 * future build does not take the editor down with it, and that the history it
 * replays to the model is history the API will accept.
 *
 * The forward-compatibility cases matter more here than the round trip. A
 * transcript is read on the way *into* the design editor, so anything this codec
 * throws on is a design the user cannot open — which is why the only two outcomes
 * of [ChatTranscriptCodec.decode] are a transcript and null.
 */
class ChatTranscriptTest {
    private val transcript = ChatTranscript(
        designId = "abc123",
        messages = listOf(
            ChatMessage(role = ChatRole.USER, text = "make it rounder", atMs = 1_000L, imageCount = 2),
            ChatMessage(
                role = ChatRole.ASSISTANT,
                text = "Done — the corners were being clipped.",
                atMs = 2_000L,
                tools = listOf(
                    ChatToolNote(GlyphAiTools.GET_CURRENT_DESIGN, "Read your design"),
                    ChatToolNote(GlyphAiTools.APPLY_DESIGN, "Applied a change", changedDesign = true),
                ),
            ),
        ),
    )

    @Test
    fun `a transcript round-trips through serialization unchanged`() {
        val decoded = ChatTranscriptCodec.decode(ChatTranscriptCodec.encode(transcript))

        assertEquals(transcript, decoded)
    }

    @Test
    fun `the encoded form is self-describing`() {
        val json = ChatTranscriptCodec.encode(transcript)

        assertTrue(json, json.contains("\"format\":\"$CHAT_FORMAT\""))
        assertTrue(json, json.contains("\"formatVersion\":$CHAT_FORMAT_VERSION"))
        assertTrue(json, json.contains("\"designId\":\"abc123\""))
        assertTrue(json, json.contains("\"changedDesign\":true"))
    }

    @Test
    fun `an unknown future field is ignored rather than fatal`() {
        val json = """
            {
              "format": "$CHAT_FORMAT",
              "formatVersion": $CHAT_FORMAT_VERSION,
              "designId": "abc123",
              "pinnedAt": 1234,
              "messages": [
                {"role": "user", "text": "hi", "atMs": 1, "reactions": ["heart"]}
              ]
            }
        """.trimIndent()

        val decoded = ChatTranscriptCodec.decode(json)

        assertNotNull(decoded)
        assertEquals("hi", decoded!!.messages.single().text)
    }

    @Test
    fun `an unknown role degrades to the default instead of voiding the thread`() {
        val json = """{"format":"$CHAT_FORMAT","messages":[{"role":"oracle","text":"hm"}]}"""

        val decoded = ChatTranscriptCodec.decode(json)

        assertEquals(ChatRole.USER, decoded!!.messages.single().role)
    }

    @Test
    fun `a file from a newer build is declined, not half-understood`() {
        val json = """{"format":"$CHAT_FORMAT","formatVersion":${CHAT_FORMAT_VERSION + 1}}"""

        assertNull(ChatTranscriptCodec.decode(json))
    }

    @Test
    fun `something that is not a transcript at all is declined`() {
        assertNull(ChatTranscriptCodec.decode(""))
        assertNull(ChatTranscriptCodec.decode("not json"))
        assertNull(ChatTranscriptCodec.decode("[]"))
        assertNull(ChatTranscriptCodec.decode("""{"format":"glyph.design","formatVersion":1}"""))
    }

    @Test
    fun `a thread is capped so it cannot grow without limit`() {
        var grown = ChatTranscript(designId = "abc123")
        repeat(ChatTranscriptCodec.MAX_MESSAGES + 50) {
            grown = grown.plus(ChatMessage(text = "message $it"))
        }

        assertEquals(ChatTranscriptCodec.MAX_MESSAGES, grown.messages.size)
        // The cap keeps the NEWEST, which is the half a conversation is about.
        assertEquals("message ${ChatTranscriptCodec.MAX_MESSAGES + 49}", grown.messages.last().text)
    }

    @Test
    fun `history replayed to the model is the last few turns, roles intact`() {
        val long = ChatTranscript(
            designId = "abc123",
            messages = (1..10).map {
                ChatMessage(
                    role = if (it % 2 == 1) ChatRole.USER else ChatRole.ASSISTANT,
                    text = "turn $it",
                )
            },
        )

        val input = long.asInput(count = 4)

        assertEquals(4, input.size)
        assertEquals("turn 7", (input[0] as ChatMessageItem).content.single().let(::textOf))
        assertEquals(ChatMessageItem.ROLE_USER, (input[0] as ChatMessageItem).role)
        assertEquals(ChatMessageItem.ROLE_ASSISTANT, (input[1] as ChatMessageItem).role)
        // An assistant turn on the INPUT side must be output_text; input_text is
        // rejected by the API for that role.
        assertTrue((input[1] as ChatMessageItem).content.single() is ChatOutputText)
        assertTrue((input[0] as ChatMessageItem).content.single() is ChatInputText)
    }

    @Test
    fun `blank and failed turns are not replayed to the model`() {
        val messy = ChatTranscript(
            messages = listOf(
                ChatMessage(role = ChatRole.USER, text = "hi"),
                ChatMessage(role = ChatRole.ASSISTANT, text = "   "),
                ChatMessage(role = ChatRole.ASSISTANT, text = "Network error", error = true),
                ChatMessage(role = ChatRole.USER, text = "still there?"),
            ),
        )

        val input = messy.asInput()

        // An empty content array is rejected outright, and replaying an error as
        // though the assistant had said it teaches it to say that.
        assertEquals(2, input.size)
        assertEquals("still there?", (input[1] as ChatMessageItem).content.single().let(::textOf))
    }

    /**
     * The rule behind `ai/GlyphAiSession`'s deferred-apply correction: a turn
     * that *said* it changed the design and then did not must be corrected —
     * but only where the claim actually is.
     */
    @Test
    fun `a correction is appended to the conversation that made the claim`() {
        val corrected = transcript.withCorrection(
            ChatMessage(role = ChatRole.ASSISTANT, text = "Actually, it didn't land.", error = true),
        )

        assertNotNull(corrected)
        assertEquals(3, corrected!!.messages.size)
        assertEquals("Actually, it didn't land.", corrected.messages.last().text)
        // Never replayed: the model is not taught to retract its own work.
        assertEquals(2, corrected.asInput().size)
    }

    @Test
    fun `tool labels read as a record, not as a status`() {
        assertEquals("Read your design", ChatToolNote.labelFor(GlyphAiTools.GET_CURRENT_DESIGN))
        assertEquals("Applied a change", ChatToolNote.labelFor(GlyphAiTools.APPLY_DESIGN))
        assertEquals("Checked a design", ChatToolNote.labelFor(GlyphAiTools.VALIDATE_DESIGN))
        // A tool this build has never heard of still redisplays as something.
        assertEquals("Image to grid", ChatToolNote.labelFor("image_to_grid"))
    }

    private fun textOf(part: ChatContentPart): String = when (part) {
        is ChatInputText -> part.text
        is ChatOutputText -> part.text
        is ChatInputImage -> part.imageUrl
    }
}
