package space.linuxct.glyphmatrixtoycompat.ui.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import space.linuxct.glyphmatrixtoycompat.core.ai.ChatToolNote
import space.linuxct.glyphmatrixtoycompat.core.ai.ChatTrace
import space.linuxct.glyphmatrixtoycompat.core.ai.GlyphAiOrchestrator
import space.linuxct.glyphmatrixtoycompat.core.ai.GlyphAiTools

/**
 * The seam between `core/ai`'s structured states and this app's copy.
 *
 * `core/` deliberately contains no user-facing English: what the assistant is
 * doing is a [ChatTrace], and why a turn failed is a
 * [GlyphAiOrchestrator.TurnResult.Reason]. Every one of those has to become a
 * string somewhere, and if one of them silently does not, the user watches a
 * blank line where a status should be — which is invisible in review and
 * invisible on device until the exact turn that produces it.
 *
 * The compiler catches *new* cases: both mappings are `when` expressions over a
 * sealed interface and an enum with no `else`, so adding a trace kind or a
 * failure reason in `core/` fails this build. What the compiler cannot catch is a
 * case mapped to the wrong string, or two mapped to the same one, which is what
 * these assert.
 *
 * Resource ids resolve here because the R class is on the unit-test classpath;
 * their *text* is not, which is fine — this is about coverage and distinctness,
 * not wording.
 */
class GlyphAiChatCopyTest {
    private val knownTools = listOf(
        GlyphAiTools.GET_CURRENT_DESIGN,
        GlyphAiTools.APPLY_DESIGN,
        GlyphAiTools.VALIDATE_DESIGN,
    )

    /** Every trace this build can produce, including one for a tool it does not know. */
    private val everyTrace: List<ChatTrace> = buildList {
        add(ChatTrace.Thinking)
        add(ChatTrace.Processing)
        knownTools.forEach { add(ChatTrace.RunningTool(it)) }
        add(ChatTrace.RunningTool("some_future_tool"))
    }

    @Test
    fun `every trace has copy of its own`() {
        val ids = everyTrace.map { it.messageRes() }
        ids.forEach { assertNotEquals("a trace mapped to no string", 0, it) }
        assertEquals("two traces share a string", ids.size, ids.toSet().size)
    }

    @Test
    fun `every known tool has a past-tense label and unknown ones fall back`() {
        val ids = knownTools.map { toolNoteRes(it) }
        ids.forEach { assertNotEquals("a tool mapped to no label", 0, it) }
        assertEquals("two tools share a label", ids.size, ids.toSet().size)
        // 0 is the signal to use the label the transcript itself stored, which is
        // whatever the build that wrote it called the tool.
        assertEquals(0, toolNoteRes("set_frames"))
    }

    /**
     * A step that failed must not be worded like one that succeeded — the live
     * list exists precisely so a run of failed checks reads as the assistant
     * redrawing rather than as a stall, and that only works if the two states
     * look different at a glance.
     */
    @Test
    fun `a failed step has copy of its own, distinct from the success`() {
        knownTools.forEach { name ->
            val failed = stepFailureRes(name)
            assertNotEquals("$name has no failure copy", 0, failed)
            assertNotEquals("$name reads the same whether it worked or not", toolNoteRes(name), failed)
        }
        val ids = knownTools.map { stepFailureRes(it) }
        assertEquals("two tools share failure copy", ids.size, ids.toSet().size)
    }

    /**
     * The drawing tools are the ones whose repetition needs explaining, so those
     * are the ones that take an attempt number. Re-reading the design is not a
     * draft and gets no number.
     */
    @Test
    fun `only the drawing tools are numbered by attempt`() {
        assertEquals(3, stepFailureArg(note(GlyphAiTools.VALIDATE_DESIGN), attempt = 3))
        assertEquals(2, stepFailureArg(note(GlyphAiTools.APPLY_DESIGN), attempt = 2))
        assertNull(stepFailureArg(note(GlyphAiTools.GET_CURRENT_DESIGN), attempt = 4))
    }

    private fun note(name: String) = ChatToolNote(name = name, label = ChatToolNote.labelFor(name))

    /**
     * The four failure categories exist because the recovery differs — retry,
     * sign in again, rephrase, wait. Sharing copy between two of them would
     * silently merge two different pieces of advice.
     */
    @Test
    fun `every failure reason has copy of its own`() {
        val ids = GlyphAiOrchestrator.TurnResult.Reason.entries.map { it.messageRes() }
        ids.forEach { assertNotEquals("a failure reason mapped to no string", 0, it) }
        assertEquals("two failure reasons share a string", ids.size, ids.toSet().size)
    }
}
