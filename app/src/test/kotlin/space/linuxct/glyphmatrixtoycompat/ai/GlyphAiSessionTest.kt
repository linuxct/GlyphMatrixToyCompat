package space.linuxct.glyphmatrixtoycompat.ai

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import space.linuxct.glyphmatrixtoycompat.core.ai.ChatMessage
import space.linuxct.glyphmatrixtoycompat.core.ai.ChatRole
import space.linuxct.glyphmatrixtoycompat.core.ai.ChatToolNote
import space.linuxct.glyphmatrixtoycompat.core.ai.ChatTranscript
import space.linuxct.glyphmatrixtoycompat.core.ai.GlyphAiOrchestrator
import space.linuxct.glyphmatrixtoycompat.core.ai.GlyphToolContext
import space.linuxct.glyphmatrixtoycompat.core.ai.PendingApply
import space.linuxct.glyphmatrixtoycompat.core.ai.PendingApplyVerdict
import space.linuxct.glyphmatrixtoycompat.core.design.Design
import space.linuxct.glyphmatrixtoycompat.core.design.DesignVariant
import space.linuxct.glyphmatrixtoycompat.core.design.PokemonCodename

/**
 * The turn holder's lifecycle: what a turn does to the screen, to the disk and
 * to the foreground service, on each of the four ways one can end.
 *
 * ## Why this can be a plain JUnit test at all
 *
 * That was the point of the seams. [GlyphAiSession] takes its scope, its stores,
 * its foreground hook and its *runner* from outside, so this file supplies a
 * scope that runs everything on the calling thread ([Dispatchers.Unconfined]), a
 * store that is a `HashMap` and a runner that answers on command — including the
 * answer "never", which is the only way to observe a turn that is still in
 * flight. There is no `Context`, no dispatcher rule, no test-coroutines
 * dependency, and no waiting.
 *
 * The behaviour under test is the thing the whole change is for: **closing the
 * editor must not end a turn**, and the only thing that still does is the user
 * asking. The old arrangement made that unprovable — the cancel lived in
 * `onCleared`, which needs a real ViewModel host.
 */
class GlyphAiSessionTest {
    // region fakes

    private class FakeTranscripts : TranscriptStore {
        val saved = LinkedHashMap<String, ChatTranscript>()
        val deleted = mutableListOf<String>()
        var writes = 0

        /**
         * Set by a test to stop the *next* read in its tracks, so a load can be
         * left genuinely in flight while something else happens. Completing it
         * lets that read finish. This is the only asynchrony in this file that is
         * not straight-line, and it exists for exactly one question: what a
         * correction does when the conversation it belongs to is being read at
         * that moment.
         */
        var holdNextLoad: CompletableDeferred<Unit>? = null

        override suspend fun load(designId: String): ChatTranscript? {
            holdNextLoad?.let {
                holdNextLoad = null
                it.await()
            }
            return saved[designId]
        }

        override suspend fun save(transcript: ChatTranscript) {
            writes++
            saved[transcript.designId] = transcript
        }

        override suspend fun delete(designId: String) {
            deleted += designId
            saved.remove(designId)
        }
    }

    private class FakePending : PendingApplyRecords {
        val records = LinkedHashMap<String, PendingApply>()

        override suspend fun take(designId: String): PendingApply? = records.remove(designId)

        override suspend fun put(record: PendingApply) {
            records[record.designId] = record
        }
    }

    private class FakeForeground : TurnForeground {
        var starts = 0
        var stops = 0
        var lastName = ""

        override fun turnStarted(designId: String, designName: String) {
            starts++
            lastName = designName
        }

        override fun turnEnded() {
            stops++
        }
    }

    /** An editor that accepts everything and remembers what it was handed. */
    private class FakeEditor(var design: Design) : GlyphEditorBridge {
        val applied = mutableListOf<Design>()

        override fun snapshot(): GlyphToolContext =
            GlyphToolContext(design = design, openVariant = PokemonCodename.BELLSPROUT)

        override fun apply(design: Design): GlyphApplyResult {
            val previous = this.design
            this.design = design
            applied += design
            return GlyphApplyResult.Applied(previous)
        }
    }

    /**
     * A turn that answers when this test tells it to.
     *
     * [awaiting] is completed by the test, so a turn can be left running for as
     * long as the assertions need it — which is what "the editor closed while a
     * turn was in flight" means.
     */
    private class ScriptedRunner : TurnRunner {
        val awaiting = CompletableDeferred<GlyphAiOrchestrator.TurnResult>()
        var request: TurnRequest? = null

        override suspend fun run(request: TurnRequest): GlyphAiOrchestrator.TurnResult {
            this.request = request
            return awaiting.await()
        }
    }

    private fun design(id: String = DESIGN_ID, modifiedAt: String = MODIFIED_AT) = Design(
        id = id,
        name = "Smiley",
        modifiedAt = modifiedAt,
        variants = mapOf(PokemonCodename.BELLSPROUT.codename to DesignVariant()),
    )

    private fun success(text: String, notes: List<ChatToolNote> = emptyList()) =
        GlyphAiOrchestrator.TurnResult.Success(
            text = text,
            rounds = 1,
            appliedDesign = null,
            toolNotes = notes,
            items = emptyList(),
        )

    private fun failure(
        detail: String,
        reason: GlyphAiOrchestrator.TurnResult.Reason =
            GlyphAiOrchestrator.TurnResult.Reason.TRANSPORT,
        appliedDesign: Design? = null,
        notes: List<ChatToolNote> = emptyList(),
    ) = GlyphAiOrchestrator.TurnResult.Failure(
        reason = reason,
        detail = detail,
        rounds = 1,
        appliedDesign = appliedDesign,
        toolNotes = notes,
    )

    /** A salvaged turn: out of rounds, with the last checked draft applied. */
    private fun salvaged() = failure(
        detail = "out of rounds",
        reason = GlyphAiOrchestrator.TurnResult.Reason.STUCK_SALVAGED,
        appliedDesign = design(modifiedAt = "2026-04-04T00:00:00Z"),
        notes = listOf(ChatToolNote(name = "apply_design", label = "Applied a change", changedDesign = true)),
    )

    private class Fixture(
        val runner: ScriptedRunner = ScriptedRunner(),
        val transcripts: FakeTranscripts = FakeTranscripts(),
        val pending: FakePending = FakePending(),
        val foreground: FakeForeground = FakeForeground(),
        var storedModifiedAt: String? = MODIFIED_AT,
        var clock: Long = 1_000L,
    ) {
        val session = GlyphAiSession(
            // Unconfined, so every launch runs to its first real suspension on
            // this thread: the whole test is then straight-line code.
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            transcripts = transcripts,
            pendingApplies = pending,
            designs = StoredDesignFacts { storedModifiedAt },
            foreground = foreground,
            // The production wording lives in strings.xml; what this file cares
            // about is which reason produced which note, so it says so.
            notices = object : TurnNotices {
                override fun changedTheDesign(
                    reason: GlyphAiOrchestrator.TurnResult.Reason,
                ): String = "notice:$reason"

                override fun deferredApplyDropped(verdict: PendingApplyVerdict): String =
                    "dropped:$verdict"
            },
            runner = runner,
            // The scope's dispatcher, so file work is not shunted onto a real
            // thread pool this test would then have to wait for.
            ioContext = Dispatchers.Unconfined,
            now = { clock },
        )
    }

    /**
     * A conversation on disk that already says the assistant changed the design
     * — which is what a deferred apply leaves behind, because the model is told
     * the change succeeded the moment it is *recorded*.
     */
    private fun claimed(fixture: Fixture) {
        fixture.transcripts.saved[DESIGN_ID] = ChatTranscript(
            designId = DESIGN_ID,
            messages = listOf(
                ChatMessage(role = ChatRole.USER, text = "draw a cat", atMs = 1L),
                ChatMessage(role = ChatRole.ASSISTANT, text = "Done — I drew you a cat.", atMs = 2L),
            ),
        )
        // Seeded, not written: the fixture's write count is what the tests below
        // assert against.
        fixture.transcripts.writes = 0
    }

    /** A drawing waiting for [DESIGN_ID], recorded against [MODIFIED_AT]. */
    private fun defer(fixture: Fixture, atMs: Long = fixture.clock) {
        fixture.pending.records[DESIGN_ID] = PendingApply(
            designId = DESIGN_ID,
            baseModifiedAt = MODIFIED_AT,
            atMs = atMs,
            design = design(modifiedAt = "2026-02-02T00:00:00Z"),
        )
    }

    /** A session with a conversation open and an editor registered. */
    private fun opened(fixture: Fixture = Fixture()): Pair<Fixture, FakeEditor> {
        val editor = FakeEditor(design())
        fixture.session.openChat(DESIGN_ID)
        fixture.session.setEditor(editor)
        return fixture to editor
    }

    // endregion

    // region starting

    @Test
    fun `sending records the user's message, goes foreground and reports as sending`() {
        val (fixture, _) = opened()

        assertTrue(fixture.session.send("draw a cat"))

        val state = fixture.session.chat.value
        assertTrue(state.sending)
        assertEquals(1, state.messages.size)
        assertEquals(ChatRole.USER, state.messages[0].role)
        assertEquals("draw a cat", state.messages[0].text)
        assertEquals(1, fixture.foreground.starts)
        assertEquals(0, fixture.foreground.stops)
        // On disk immediately, because it is on screen immediately.
        assertEquals(1, fixture.transcripts.saved[DESIGN_ID]?.messages?.size)
    }

    @Test
    fun `only one turn runs at a time`() {
        val (fixture, _) = opened()
        fixture.session.send("first")

        assertFalse(fixture.session.send("second"))
        assertEquals(1, fixture.foreground.starts)
    }

    @Test
    fun `nothing is sent with no editor registered`() {
        val fixture = Fixture()
        fixture.session.openChat(DESIGN_ID)

        assertFalse(fixture.session.send("draw a cat"))
        assertEquals(0, fixture.foreground.starts)
    }

    // endregion

    // region completing

    @Test
    fun `a finished turn appends the reply, releases the service and clears the trace`() {
        val (fixture, _) = opened()
        fixture.session.send("draw a cat")

        fixture.runner.awaiting.complete(success("Here you go."))

        val state = fixture.session.chat.value
        assertFalse(state.sending)
        assertEquals("", state.streaming)
        assertNull(state.trace)
        assertEquals(2, state.messages.size)
        assertEquals(ChatRole.ASSISTANT, state.messages[1].role)
        assertEquals("Here you go.", state.messages[1].text)
        assertEquals(1, fixture.foreground.stops)
        assertEquals(2, fixture.transcripts.saved[DESIGN_ID]?.messages?.size)
    }

    @Test
    fun `a failed turn is not written to the transcript but does release the service`() {
        val (fixture, _) = opened()
        fixture.session.send("draw a cat")

        fixture.runner.awaiting.complete(failure("HTTP 400"))

        val state = fixture.session.chat.value
        assertFalse(state.sending)
        assertEquals("HTTP 400", state.failure?.detail)
        // The user's message only. A failure is not something the assistant said.
        assertEquals(1, state.messages.size)
        assertEquals(1, fixture.transcripts.saved[DESIGN_ID]?.messages?.size)
        assertEquals(1, fixture.foreground.stops)
    }

    /**
     * The exception to the rule above, and the reason the rule is now written as
     * "a turn that changed nothing stores nothing".
     *
     * A salvaged turn ran out of rounds and applied its last checked draft on the
     * way out, so the design is not as the user left it. The banner that says so
     * goes with the sheet; without a message in the thread, reopening that design
     * tomorrow shows artwork nobody drew and a conversation that never mentions it.
     */
    @Test
    fun `a salvaged turn leaves a note in the transcript explaining the change`() {
        val (fixture, _) = opened()
        fixture.session.send("draw a cat")

        fixture.runner.awaiting.complete(salvaged())

        val stored = fixture.transcripts.saved[DESIGN_ID]!!
        assertEquals(2, stored.messages.size)
        val note = stored.messages[1]
        assertEquals(ChatRole.ASSISTANT, note.role)
        assertEquals(
            "notice:${GlyphAiOrchestrator.TurnResult.Reason.STUCK_SALVAGED}",
            note.text,
        )
        // The turn's steps ride along, so the note is followed by the apply that
        // actually happened.
        assertEquals(1, note.tools.size)
        // On screen as well as on disk, and the banner still says what failed.
        assertEquals(2, fixture.session.chat.value.messages.size)
        assertEquals(
            GlyphAiOrchestrator.TurnResult.Reason.STUCK_SALVAGED,
            fixture.session.chat.value.failure?.reason,
        )
    }

    // endregion

    // region the two cancels

    /**
     * The bug this whole change exists for. Leaving the editor used to reach
     * `onCleared`, which cancelled the scope the turn was running in — a
     * deterministic kill of work the user was waiting on.
     */
    @Test
    fun `closing the editor does not end the turn`() {
        val (fixture, editor) = opened()
        fixture.session.send("draw a cat")

        fixture.session.clearEditor(editor)

        assertTrue(fixture.session.chat.value.sending)
        assertEquals(0, fixture.foreground.stops)
        assertFalse(fixture.runner.awaiting.isCompleted)

        // ...and it still finishes, into the transcript nobody is looking at.
        fixture.runner.awaiting.complete(success("Done while you were away."))
        assertEquals(2, fixture.transcripts.saved[DESIGN_ID]?.messages?.size)
        assertEquals(1, fixture.foreground.stops)
    }

    @Test
    fun `stopping is the users cancel and leaves the transcript as it was`() {
        val (fixture, _) = opened()
        fixture.session.send("draw a cat")
        // Something arrived and was checkpointed before the user gave up.
        fixture.runner.request!!.onTextDelta("Drawing a c")
        fixture.clock += GlyphAiSession.CHECKPOINT_INTERVAL_MS
        fixture.runner.request!!.onTextDelta("at…")
        assertTrue(fixture.transcripts.saved[DESIGN_ID]!!.messages.last().partial)

        fixture.session.stopTurn()

        val state = fixture.session.chat.value
        assertFalse(state.sending)
        assertEquals("", state.streaming)
        assertEquals(1, fixture.foreground.stops)
        // The half-sentence goes with it: a stopped turn produced no answer, and
        // that is what this app has always stored for one.
        val stored = fixture.transcripts.saved[DESIGN_ID]!!
        assertEquals(1, stored.messages.size)
        assertEquals(ChatRole.USER, stored.messages[0].role)
    }

    // endregion

    // region partial progress

    @Test
    fun `a reply still arriving is checkpointed, and replaced by the real one`() {
        val (fixture, _) = opened()
        fixture.session.send("draw a cat")

        fixture.runner.request!!.onTextDelta("Here")
        val checkpoint = fixture.transcripts.saved[DESIGN_ID]!!.messages.last()
        assertTrue(checkpoint.partial)
        assertEquals("Here", checkpoint.text)

        fixture.runner.awaiting.complete(success("Here you go."))

        val finished = fixture.transcripts.saved[DESIGN_ID]!!
        assertEquals(2, finished.messages.size)
        assertFalse(finished.messages.last().partial)
        assertEquals("Here you go.", finished.messages.last().text)
    }

    @Test
    fun `a finished tool call is checkpointed the moment it lands`() {
        val (fixture, _) = opened()
        fixture.session.send("draw a cat")

        fixture.runner.request!!.onToolNote(ChatToolNote(name = "apply_design", label = "Applied"))

        val checkpoint = fixture.transcripts.saved[DESIGN_ID]!!.messages.last()
        assertTrue(checkpoint.partial)
        assertEquals(1, checkpoint.tools.size)
        // And on screen, as the live step list.
        assertEquals(1, fixture.session.chat.value.steps.size)
    }

    @Test
    fun `a checkpoint read back on the next open is the reply that arrived`() {
        val (fixture, _) = opened()
        fixture.session.send("draw a cat")
        fixture.runner.request!!.onTextDelta("Half a sen")
        // The process dies here: nothing else runs, and the file stays as it is.

        val reopened = Fixture(transcripts = fixture.transcripts)
        reopened.session.openChat(DESIGN_ID)

        val messages = reopened.session.chat.value.messages
        assertEquals(2, messages.size)
        assertEquals("Half a sen", messages[1].text)
        assertTrue(messages[1].partial)
    }

    // endregion

    // region the deferred apply

    @Test
    fun `an apply with no editor open is recorded rather than refused`() {
        val (fixture, editor) = opened()
        fixture.session.send("draw a cat")
        fixture.session.clearEditor(editor)

        val refusal = fixture.runner.request!!.applyDesign(design())

        assertNull("the model is told it worked, because it did", refusal)
        val record = fixture.pending.records[DESIGN_ID]
        assertNotNull(record)
        assertEquals(MODIFIED_AT, record!!.baseModifiedAt)
        assertEquals(fixture.clock, record.atMs)
    }

    @Test
    fun `the recorded design lands when that design is next opened`() {
        val fixture = Fixture()
        fixture.pending.records[DESIGN_ID] = PendingApply(
            designId = DESIGN_ID,
            baseModifiedAt = MODIFIED_AT,
            atMs = fixture.clock,
            design = design(modifiedAt = "2026-02-02T00:00:00Z"),
        )
        val editor = FakeEditor(design())

        fixture.session.setEditor(editor)

        assertEquals(1, editor.applied.size)
        // Consumed, so it cannot land a second time on the next open.
        assertTrue(fixture.pending.records.isEmpty())
        // ...and there is a way back from it, exactly as for a live apply.
        fixture.session.openChat(DESIGN_ID)
        assertTrue(fixture.session.chat.value.canRevert)
    }

    @Test
    fun `a design the user has edited since is not overwritten`() {
        val fixture = Fixture(storedModifiedAt = "2026-03-03T00:00:00Z")
        fixture.pending.records[DESIGN_ID] = PendingApply(
            designId = DESIGN_ID,
            baseModifiedAt = MODIFIED_AT,
            atMs = fixture.clock,
            design = design(),
        )
        val editor = FakeEditor(design())

        fixture.session.setEditor(editor)

        assertEquals(0, editor.applied.size)
        // Still consumed: a draft that cannot land must not re-offer itself on
        // every subsequent open of the design.
        assertTrue(fixture.pending.records.isEmpty())
    }

    // endregion

    // region a deferred apply that never lands

    /**
     * The bug this region exists for.
     *
     * A turn that finishes with no editor open records its drawing and tells the
     * model it worked — so the reply above it in the thread says the design was
     * changed, and it is committed at that moment. When the record is later
     * dropped rather than applied, that reply becomes an active falsehood: the
     * user was told a thing was done that silently was not. Each of the three
     * dropping verdicts therefore has to say so, in its own words, because "why"
     * is the whole of what the user needs.
     */
    @Test
    fun `a draft dropped for the user's own edits corrects the conversation`() {
        val fixture = Fixture(storedModifiedAt = "2026-03-03T00:00:00Z")
        claimed(fixture)
        defer(fixture)

        fixture.session.setEditor(FakeEditor(design()))

        val stored = fixture.transcripts.saved[DESIGN_ID]!!
        assertEquals(3, stored.messages.size)
        val correction = stored.messages.last()
        assertEquals(ChatRole.ASSISTANT, correction.role)
        assertEquals("dropped:${PendingApplyVerdict.CONFLICT}", correction.text)
        assertEquals(fixture.clock, correction.atMs)
    }

    /**
     * The other half of the rule, and the one a regression would hide behind: a
     * draft that lands is already accounted for by the reply that promised it,
     * so correcting it would be the app contradicting something that is true.
     */
    @Test
    fun `a draft that lands adds nothing to the conversation`() {
        val fixture = Fixture()
        claimed(fixture)
        defer(fixture)
        val editor = FakeEditor(design())

        fixture.session.setEditor(editor)

        assertEquals(1, editor.applied.size)
        assertEquals(2, fixture.transcripts.saved[DESIGN_ID]!!.messages.size)
        assertEquals(0, fixture.transcripts.writes)
    }

    /**
     * The interleaving that would lose a conversation: the transcript is being
     * read at the very moment the correction is written.
     *
     * The read is held open here, which is what a slow disk does for real. The
     * correction must not decide "this thread is not in memory" while the answer
     * to that is still arriving, and the copy that ends up in memory must not be
     * the pre-correction one — or the next append would write the conversation
     * back a message short.
     */
    @Test
    fun `a correction and an open that races it lose no messages`() {
        val fixture = Fixture(storedModifiedAt = "2026-03-03T00:00:00Z")
        claimed(fixture)
        defer(fixture)
        val gate = CompletableDeferred<Unit>()
        fixture.transcripts.holdNextLoad = gate

        // The chat sheet composes and its read stalls...
        fixture.session.openChat(DESIGN_ID)
        assertFalse("the read is still in flight", fixture.session.chat.value.restored)
        // ...and the editor registers, finds the waiting draft and drops it.
        fixture.session.setEditor(FakeEditor(design()))
        // Nothing has been written yet: the correction is waiting for the read.
        assertEquals(2, fixture.transcripts.saved[DESIGN_ID]!!.messages.size)

        gate.complete(Unit)

        // On screen and on disk, once, with the earlier conversation intact.
        val onScreen = fixture.session.chat.value.messages
        assertEquals(3, onScreen.size)
        assertEquals("draw a cat", onScreen[0].text)
        assertEquals("dropped:${PendingApplyVerdict.CONFLICT}", onScreen[2].text)
        assertEquals(onScreen, fixture.transcripts.saved[DESIGN_ID]!!.messages)
    }

    // endregion

    // region a turn that outlives the conversation on screen

    @Test
    fun `opening another design leaves the turn running and stops it writing to the screen`() {
        val (fixture, _) = opened()
        fixture.session.send("draw a cat")

        val other = FakeEditor(design(id = OTHER_ID))
        fixture.session.openChat(OTHER_ID)
        fixture.session.setEditor(other)
        fixture.runner.request!!.onTextDelta("a cat, arriving")

        // The other conversation is untouched by it...
        assertEquals("", fixture.session.chat.value.streaming)
        assertFalse(fixture.session.chat.value.sending)
        // ...and the reply still lands in the thread that asked for it.
        fixture.runner.awaiting.complete(success("Here is your cat."))
        val stored = fixture.transcripts.saved[DESIGN_ID]!!
        assertEquals(2, stored.messages.size)
        assertEquals("Here is your cat.", stored.messages[1].text)
    }

    // endregion

    // region reset

    @Test
    fun `resetting removes the transcript and leaves the revert banner`() {
        val (fixture, _) = opened()
        fixture.session.send("draw a cat")
        fixture.runner.request!!.applyDesign(design())
        fixture.runner.awaiting.complete(success("Done."))
        assertTrue(fixture.session.chat.value.canRevert)

        assertTrue(fixture.session.resetChat())

        assertEquals(listOf(DESIGN_ID), fixture.transcripts.deleted)
        assertTrue(fixture.session.chat.value.messages.isEmpty())
        assertTrue("the artwork is not what a reset touches", fixture.session.chat.value.canRevert)
    }

    @Test
    fun `a turn in flight may not be reset`() {
        val (fixture, _) = opened()
        fixture.session.send("draw a cat")

        assertFalse(fixture.session.resetChat())
        assertTrue(fixture.transcripts.deleted.isEmpty())
    }

    // endregion

    private companion object {
        const val DESIGN_ID = "abc123"
        const val OTHER_ID = "def456"
        const val MODIFIED_AT = "2026-01-01T00:00:00Z"
    }
}
