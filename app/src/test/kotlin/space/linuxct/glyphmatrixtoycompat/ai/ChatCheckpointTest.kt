package space.linuxct.glyphmatrixtoycompat.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import space.linuxct.glyphmatrixtoycompat.core.ai.ChatMessage
import space.linuxct.glyphmatrixtoycompat.core.ai.ChatRole
import space.linuxct.glyphmatrixtoycompat.core.ai.ChatToolNote
import space.linuxct.glyphmatrixtoycompat.core.ai.ChatTranscript
import space.linuxct.glyphmatrixtoycompat.core.ai.ChatTranscriptCodec
import java.io.File

/**
 * Partial progress: the checkpoint a running turn leaves behind so that a
 * process death shows what arrived rather than an empty turn.
 *
 * Two properties, and the feature is worthless without either. The checkpoint has
 * to **round-trip through the store** — it is only ever read after the process
 * that wrote it is gone, so an encoder that dropped the flag would silently
 * redisplay a half sentence as a finished reply. And it has to be **replaced, not
 * accumulated**: a turn checkpoints every couple of seconds, and a transcript
 * that grew a message per checkpoint would turn a two-minute turn into forty
 * copies of the same paragraph.
 *
 * These are the pure halves of the same behaviour `GlyphAiSessionTest` exercises
 * through a running turn, written against the same file operations `ChatStore`
 * performs — `readTranscript` takes a `File`, so this can hand it a real one.
 */
class ChatCheckpointTest {
    private val user = ChatMessage(role = ChatRole.USER, text = "draw a cat", atMs = 1)

    private fun checkpoint(text: String, tools: List<ChatToolNote> = emptyList()) = ChatMessage(
        role = ChatRole.ASSISTANT,
        text = text,
        atMs = 2,
        tools = tools,
        partial = true,
    )

    // region replaced, not accumulated

    @Test
    fun `the first checkpoint is appended`() {
        val transcript = ChatTranscript(designId = "abc").plus(user)

        val checkpointed = transcript.withPartial(checkpoint("Draw"))

        assertEquals(2, checkpointed.messages.size)
        assertTrue(checkpointed.messages[1].partial)
    }

    @Test
    fun `every checkpoint after it replaces the one before`() {
        var transcript = ChatTranscript(designId = "abc").plus(user)

        repeat(40) { index -> transcript = transcript.withPartial(checkpoint("word ".repeat(index))) }

        assertEquals(2, transcript.messages.size)
        assertEquals("word ".repeat(39), transcript.messages[1].text)
    }

    @Test
    fun `dropping the checkpoint leaves the conversation as it was`() {
        val before = ChatTranscript(designId = "abc").plus(user)

        assertEquals(before, before.withPartial(checkpoint("Draw")).withoutPartial())
    }

    // endregion

    // region through the store

    @Test
    fun `a checkpoint survives a write and a read`() {
        val transcript = ChatTranscript(designId = "abc")
            .plus(user)
            .withPartial(
                checkpoint(
                    "Half a sen",
                    tools = listOf(ChatToolNote(name = "validate_design", label = "Checked")),
                ),
            )
        val file = File.createTempFile("chat", ".json")
        file.deleteOnExit()
        file.writeText(ChatTranscriptCodec.encode(transcript))

        val read = readTranscript(file)

        assertNotNull(read)
        assertEquals(2, read!!.messages.size)
        assertEquals("Half a sen", read.messages[1].text)
        assertTrue(read.messages[1].partial)
        assertEquals(1, read.messages[1].tools.size)
        assertFalse(read.messages[0].partial)
    }

    // endregion
}
