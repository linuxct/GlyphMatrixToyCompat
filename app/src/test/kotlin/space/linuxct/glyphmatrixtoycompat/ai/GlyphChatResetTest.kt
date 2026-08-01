package space.linuxct.glyphmatrixtoycompat.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import space.linuxct.glyphmatrixtoycompat.core.ai.ChatMessage
import space.linuxct.glyphmatrixtoycompat.core.ai.ChatRole
import space.linuxct.glyphmatrixtoycompat.core.ai.ChatToolNote
import space.linuxct.glyphmatrixtoycompat.core.ai.ChatTrace
import space.linuxct.glyphmatrixtoycompat.core.ai.GlyphAiOrchestrator

/**
 * "Reset this chat": when it is offered, and what it leaves behind.
 *
 * `GlyphAiViewModel` needs an `Application` and cannot be built under plain
 * JUnit, so the two rules worth proving were written as pure functions over
 * [GlyphChatState] — the same shape `ChatStore` uses for the rules that destroy
 * data. `resetChat()` is then three lines of plumbing over them.
 *
 * Both rules exist because of something that would otherwise go wrong silently:
 * a reset accepted mid-turn would be undone the moment the turn appended its
 * reply, and a reset that took the revert snapshot with it would destroy the
 * artwork the confirmation dialog promises not to touch.
 */
class GlyphChatResetTest {

    // region when it is offered

    @Test
    fun `a thread with something in it may be reset`() {
        assertTrue(loaded(messages = listOf(userMessage)).canReset())
    }

    @Test
    fun `an empty thread offers nothing to clear`() {
        // The empty state is already on screen; an enabled action that visibly
        // did nothing would be worse than a greyed-out one.
        assertFalse(loaded().canReset())
    }

    /**
     * The guard the brief asks for. A turn ends by appending its reply to the
     * transcript, so a conversation cleared while one is running would refill
     * itself a minute later — and the file written with it would be a
     * conversation the user believed they had deleted.
     */
    @Test
    fun `a turn in flight may not be reset`() {
        val running = loaded(messages = listOf(userMessage)).copy(
            sending = true,
            trace = ChatTrace.RunningTool("apply_design"),
            startedAtMs = 1_000L,
        )

        assertFalse(running.canReset())
    }

    @Test
    fun `a transcript that has not been read yet may not be reset`() {
        // A few milliseconds on the way in, but clearing inside them would empty
        // the screen and then have the file land on top of it.
        assertFalse(GlyphChatState(designId = "abc123", restored = false).canReset())
    }

    // endregion

    // region what it leaves behind

    @Test
    fun `clearing empties everything the conversation put on screen`() {
        val busy = loaded(messages = listOf(userMessage, assistantMessage)).copy(
            streaming = "half a sentence",
            steps = listOf(ChatToolNote(name = "validate_design", label = "Checked")),
            startedAtMs = 5_000L,
            attachFailed = true,
            failure = ChatFailure(GlyphAiOrchestrator.TurnResult.Reason.SERVER, "400"),
        )

        val cleared = busy.cleared()

        assertEquals(emptyList<ChatMessage>(), cleared.messages)
        assertEquals("", cleared.streaming)
        assertEquals(emptyList<ChatToolNote>(), cleared.steps)
        assertEquals(emptyList<AttachedImage>(), cleared.attachments)
        assertEquals(0L, cleared.startedAtMs)
        assertFalse(cleared.sending)
        assertFalse(cleared.attachFailed)
        assertNull(cleared.trace)
        assertNull(cleared.failure)
    }

    /**
     * **The decision this test exists to pin down.** The undo banner is not part
     * of the conversation: it is the one route back from a change to the
     * *artwork*, and the artwork is exactly what a chat reset promises to leave
     * alone. Clearing the messages that explain the change and the change itself
     * are different acts, and only one of them was asked for.
     */
    @Test
    fun `clearing the conversation keeps the way back from a design change`() {
        val afterAnApply = loaded(messages = listOf(userMessage, assistantMessage))
            .copy(canRevert = true)

        assertTrue("the undo banner must survive a chat reset", afterAnApply.cleared().canRevert)
    }

    @Test
    fun `clearing stays on the same design, still loaded`() {
        val cleared = loaded(messages = listOf(userMessage)).cleared()

        assertEquals("abc123", cleared.designId)
        // Empty because it was cleared is a different state from not yet read —
        // the second would show a blank screen rather than the empty-state copy.
        assertTrue(cleared.restored)
    }

    /** A cleared conversation is offered no second reset. */
    @Test
    fun `clearing leaves nothing further to clear`() {
        assertFalse(loaded(messages = listOf(userMessage)).cleared().canReset())
    }

    // endregion

    private val userMessage =
        ChatMessage(role = ChatRole.USER, text = "draw a smiley", atMs = 1L)

    private val assistantMessage =
        ChatMessage(role = ChatRole.ASSISTANT, text = "Here you go.", atMs = 2L)

    private fun loaded(messages: List<ChatMessage> = emptyList()) =
        GlyphChatState(designId = "abc123", restored = true, messages = messages)
}
