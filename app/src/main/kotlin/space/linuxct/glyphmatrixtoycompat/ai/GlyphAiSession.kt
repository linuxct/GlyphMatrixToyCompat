package space.linuxct.glyphmatrixtoycompat.ai

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import space.linuxct.glyphmatrixtoycompat.Core
import space.linuxct.glyphmatrixtoycompat.R
import space.linuxct.glyphmatrixtoycompat.core.DebugLog
import space.linuxct.glyphmatrixtoycompat.core.PrefKeys
import space.linuxct.glyphmatrixtoycompat.core.aiMaxRounds
import space.linuxct.glyphmatrixtoycompat.core.aiReasoningEffort
import space.linuxct.glyphmatrixtoycompat.core.ai.ChatInputItem
import space.linuxct.glyphmatrixtoycompat.core.ai.ChatMessage
import space.linuxct.glyphmatrixtoycompat.core.ai.ChatMessageItem
import space.linuxct.glyphmatrixtoycompat.core.ai.ChatRole
import space.linuxct.glyphmatrixtoycompat.core.ai.ChatToolNote
import space.linuxct.glyphmatrixtoycompat.core.ai.ChatTrace
import space.linuxct.glyphmatrixtoycompat.core.ai.ChatTranscript
import space.linuxct.glyphmatrixtoycompat.core.ai.ChatWire
import space.linuxct.glyphmatrixtoycompat.core.ai.GlyphAiOrchestrator
import space.linuxct.glyphmatrixtoycompat.core.ai.GlyphAiPrompt
import space.linuxct.glyphmatrixtoycompat.core.ai.GlyphChatClient
import space.linuxct.glyphmatrixtoycompat.core.ai.GlyphToolContext
import space.linuxct.glyphmatrixtoycompat.core.ai.PendingApply
import space.linuxct.glyphmatrixtoycompat.core.ai.PendingApplyVerdict
import space.linuxct.glyphmatrixtoycompat.core.ai.SourceImage
import space.linuxct.glyphmatrixtoycompat.core.ai.pendingApplyVerdict
import space.linuxct.glyphmatrixtoycompat.core.design.Design
import kotlin.coroutines.CoroutineContext

/**
 * The conversation on disk, as the session needs it.
 *
 * An interface over [ChatStore] rather than the class itself, because the class
 * needs a `Context` and the session's lifecycle rules — which is the thing worth
 * proving — can then be exercised under plain JUnit against a store that is a
 * `HashMap`.
 */
interface TranscriptStore {
    suspend fun load(designId: String): ChatTranscript?
    suspend fun save(transcript: ChatTranscript)
    suspend fun delete(designId: String)
}

/** Deferred applies on disk, as the session needs them. See [PendingApplyStore]. */
interface PendingApplyRecords {
    /** Reads **and removes** the record for [designId]. */
    suspend fun take(designId: String): PendingApply?
    suspend fun put(record: PendingApply)
}

/**
 * The one fact the deferred-apply conflict rule needs from the design store.
 *
 * Narrow on purpose: the session has no business loading designs, and a
 * `DesignStore` in here would be a second route onto artwork that the editor
 * already owns.
 */
fun interface StoredDesignFacts {
    /** `Design.modifiedAt` as it is on disk, or null if there is no readable design. */
    suspend fun modifiedAt(designId: String): String?
}

/**
 * What holds the process up while a turn runs.
 *
 * An interface for the same reason as the rest of this file's seams — a test must
 * be able to assert that a turn starts it exactly once and stops it however the
 * turn ends, without a `Service` — and because it is genuinely optional
 * behaviour: a build without the foreground service still runs turns, it just
 * loses them to the low-memory killer, which is the bug this exists to fix.
 */
interface TurnForeground {
    /** A turn has begun on [designName]. Called on the main thread. */
    fun turnStarted(designId: String, designName: String)

    /** No turn is running. Called on the main thread, however the turn ended. */
    fun turnEnded()
}

/**
 * The sentences the app writes into a conversation *as the assistant* when the
 * assistant itself did not get to. See [GlyphAiSession.startTurn] and
 * [GlyphAiSession.applyDeferred].
 *
 * A seam rather than a `getString` in the middle of the session, for the usual
 * two reasons: the wording belongs in `strings.xml` and the session has no
 * `Context`, and a test that asserts *what the transcript ends up containing*
 * should not need a resource table to do it.
 *
 * Both members are corrections to the scrollback, arrived at from opposite
 * directions: [changedTheDesign] covers a turn that changed the canvas and never
 * said so, [deferredApplyDropped] a turn that said it had and then did not.
 */
interface TurnNotices {
    /** What to write down for a turn that ended as [reason] having changed the design. */
    fun changedTheDesign(reason: GlyphAiOrchestrator.TurnResult.Reason): String

    /**
     * What to write down when a change this conversation was already told about
     * is dropped rather than applied, for [verdict].
     *
     * Never called for [PendingApplyVerdict.APPLY]: a change that lands is
     * already accounted for by the reply that promised it.
     */
    fun deferredApplyDropped(verdict: PendingApplyVerdict): String
}

/** One turn, everything the runner needs to run it. */
class TurnRequest(
    val context: GlyphToolContext,
    val history: List<ChatInputItem>,
    val message: ChatMessageItem,
    val applyDesign: (Design) -> String?,
    val onTrace: (ChatTrace) -> Unit,
    val onToolNote: (ChatToolNote) -> Unit,
    val onTextDelta: (String) -> Unit,
)

/**
 * Runs one turn against the model.
 *
 * The production implementation builds a [GlyphAiOrchestrator]; a test scripts
 * the answers, including the answer "suspend forever", which is the only way to
 * observe what happens to a turn that is still in flight.
 */
fun interface TurnRunner {
    suspend fun run(request: TurnRequest): GlyphAiOrchestrator.TurnResult
}

/**
 * The assistant's turn, and the conversation it belongs to — **owned by the
 * process, not by a screen**.
 *
 * ## Why this is not in the ViewModel any more
 *
 * It was, and leaving the editor destroyed the work. `GlyphAiViewModel` is
 * activity-scoped, so `finish()` calls `onCleared()`, which cancelled
 * `viewModelScope` and with it the turn — a *deterministic* kill, nothing to do
 * with memory pressure. Somebody who asked for a drawing and then went to check a
 * message lost the drawing, every time, and the transcript showed their own
 * request with no answer under it.
 *
 * So the turn lives here, on a scope that is created once and never cancelled,
 * and [GlyphAiViewModel] is now a *view* onto it: it forwards the calls and
 * republishes [chat]. Closing the editor withdraws the canvas ([clearEditor]) and
 * nothing else. Backgrounding the app is covered by the other half of the fix —
 * see [TurnForeground] — because an application scope keeps a turn out of the
 * ViewModel's reach but does not keep the *process* alive.
 *
 * ## What did not change, and must not
 *
 * - **[stopTurn] is still the user's cancel**, and the only one. The composer's
 *   stop button is now the single thing in the app that abandons a turn.
 * - **[clearEditor] is still identity-checked.** A configuration change disposes
 *   the outgoing composition after the incoming one has registered, so an
 *   unconditional clear would unregister the live editor.
 * - **A turn that changed the design is explainable afterwards.** A turn with no
 *   answer stores nothing, which is right for a dropped connection and wrong for
 *   the one that left new artwork on the canvas; see [noticeFor].
 * - **A turn that *said* it changed the design and then did not is corrected.**
 *   The other half of the same principle, and the sharper one. A deferred apply
 *   is reported to the model as a success while it is only recorded, so the reply
 *   in the thread already claims the change; if the record is later dropped
 *   ([PendingApplyVerdict.CONFLICT], [PendingApplyVerdict.EXPIRED],
 *   [PendingApplyVerdict.MISSING]) the claim is a falsehood and the thread is
 *   told so. See [applyDeferred] and [ChatTranscript.withCorrection].
 * - **A message that reached the screen reaches the disk.** It used to take
 *   `Dispatchers.IO + NonCancellable` inside a scope that was about to die; now
 *   the scope does not die, and every read *and* write of a transcript goes
 *   through one serialised queue (see [persistQueue]) so a checkpoint can never
 *   land on top of the reply that superseded it, and a correction written before
 *   anybody opened the chat can never be read back as though it were not there.
 *
 * ## One conversation at a time, and a turn that outlives it
 *
 * [openChat] points this at a design. A turn started on that design goes on
 * running if the user opens a *different* one, but it stops touching the visible
 * state — see [viewEpoch] — and finishes by writing its reply to its own
 * transcript, which is what the next open of that design reads.
 */
class GlyphAiSession internal constructor(
    /**
     * Application-scoped and dispatched on Main, deliberately.
     *
     * Main because the two things a turn must do on the main thread are the ones
     * it does directly: reading the editor's live frame buffers
     * ([GlyphEditorBridge.snapshot]) and writing a design back into them. The
     * network is not one of them — `GlyphAiClient.respond` moves itself to IO —
     * and deltas arriving on that IO thread are safe because a
     * [MutableStateFlow] update is atomic from any thread.
     */
    private val scope: CoroutineScope,
    private val transcripts: TranscriptStore,
    private val pendingApplies: PendingApplyRecords,
    private val designs: StoredDesignFacts,
    private val foreground: TurnForeground,
    private val notices: TurnNotices,
    private val runner: TurnRunner,
    /** Where file work happens. A test passes the scope's own dispatcher. */
    private val ioContext: CoroutineContext = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
) {

    private val _chat = MutableStateFlow(GlyphChatState())
    val chat: StateFlow<GlyphChatState> = _chat.asStateFlow()

    /**
     * The conversation of record. [GlyphChatState.messages] mirrors it for the
     * UI; this is what is written to disk and what is replayed to the model.
     */
    private var transcript = ChatTranscript()

    /** The turn in flight, if any. One at a time; the composer is disabled meanwhile. */
    private var turn: Job? = null

    /** Set by the editor while it is on screen. See [GlyphEditorBridge]. */
    private var editor: GlyphEditorBridge? = null

    /** The document as it was before the most recent accepted apply. */
    private var revertSnapshot: Design? = null

    /** Which design [revertSnapshot] belongs to. See [openChat]. */
    private var revertOf: String = ""

    /** What [retry] would send again. */
    private var lastTurn: PendingTurn? = null

    /**
     * Bumped every time [openChat] actually points this at a new conversation.
     *
     * A turn captures it when it starts and stops writing to [chat] the moment it
     * no longer matches. That is the whole rule for "a turn is running on the
     * design I just navigated away from": the work continues and persists, and
     * the screen showing a *different* conversation never sees another
     * conversation's text delta appear in it.
     *
     * Reopening the same design's editor mid-turn does not bump it — [openChat]
     * returns early — which is exactly what makes the live turn still be on
     * screen when the user comes back to it.
     */
    private var viewEpoch = 0

    /**
     * The conversation being read in, if one is. See [correctDeferred].
     *
     * Held so that a decision which depends on *whether this design's thread is
     * in memory* can wait for the answer instead of racing it. Nothing else needs
     * it: every other caller either owns the state already or does not care.
     */
    private var openJob: Job? = null

    /**
     * Every read and every write of conversation storage, in the order it was
     * asked for.
     *
     * ## Why writes are serialised
     *
     * A turn writes a checkpoint every couple of seconds and then, at the end,
     * the real thing. Launching those independently onto [Dispatchers.IO] leaves
     * the order to the thread pool, and the ordering that loses is the one where
     * a checkpoint lands *after* the reply that replaced it — the user's answer
     * silently reverting to a half-sentence. One consumer, unbounded so a send
     * never blocks the main thread, and FIFO.
     *
     * ## Why *reads* joined them
     *
     * [correctDeferred] is a load-modify-save on a transcript that is usually not
     * the one in memory: it runs from [setEditor], and [openChat] does not happen
     * until the chat sheet composes. A load living outside this queue could
     * therefore read the file *between* the correction's read and its write, and
     * the in-memory copy that resulted would be one message short — and would
     * overwrite the corrected file on the next append. Putting [PersistOp.Load]
     * in the same queue makes that unrepresentable: a load either sees the whole
     * correction or precedes it entirely, and the second case is reconciled on
     * the way back. Nothing else changes for it; a load was already a suspending
     * hop onto [ioContext].
     */
    private val persistQueue = Channel<PersistOp>(Channel.UNLIMITED)

    init {
        scope.launch(ioContext) {
            for (op in persistQueue) {
                try {
                    when (op) {
                        is PersistOp.Save -> transcripts.save(op.transcript)
                        is PersistOp.Delete -> transcripts.delete(op.designId)
                        is PersistOp.Load -> op.answer(transcripts.load(op.designId))
                        is PersistOp.Correct -> op.answer(correctOnDisk(op))
                    }
                } catch (e: Exception) {
                    // The stores below already swallow their own failures; this
                    // is the belt to that, because one throw here would end the
                    // loop and silently stop persisting anything at all.
                    DebugLog.w(TAG, "could not persist: ${e.message}")
                } finally {
                    // An op that answers and threw before it could is the one way
                    // this loop can strand a coroutine forever — a chat that
                    // never opens. `complete` on a settled deferred is a no-op,
                    // so this is free in the ordinary case.
                    op.answer(null)
                }
            }
        }
    }

    /**
     * The load-modify-save half of a correction, on the queue's own thread.
     *
     * The whole point of it being *here* is that nothing else can read or write
     * this transcript in between. [ChatTranscript.withCorrection] carries the
     * rule about not creating one; see it for why a missing file means "say
     * nothing" rather than "start a thread with an apology in it".
     */
    private suspend fun correctOnDisk(op: PersistOp.Correct): ChatTranscript? {
        val stored = transcripts.load(op.designId)?.copy(designId = op.designId)
        val corrected = stored?.withCorrection(op.message) ?: return null
        transcripts.save(corrected)
        return corrected
    }

    // ---- the editor bridge ----

    /**
     * Registers the editor currently on screen, and hands it anything the
     * assistant finished while there was none.
     *
     * Replacing an existing registration is normal, not an error: that is exactly
     * what a rotation does, and a turn in flight will find the new one.
     */
    fun setEditor(bridge: GlyphEditorBridge) {
        editor = bridge
        scope.launch {
            try {
                applyDeferred(bridge)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Nothing below is expected to throw and none of it is worth the
                // process: a drawing that could not be handed over is a drawing
                // the user can ask for again, and this scope's exceptions reach
                // the thread's uncaught handler.
                DebugLog.w(TAG, "could not hand over a waiting change: ${e.message}")
            }
        }
    }

    /**
     * Withdraws [bridge], but **only if it is still the registered one**.
     *
     * A configuration change disposes the outgoing composition *after* the
     * incoming one has registered, so an unconditional clear would remove the
     * live editor and leave the assistant unable to reach the canvas.
     *
     * Note what this deliberately does **not** do: cancel the turn. That was the
     * old behaviour, by way of `onCleared`, and it is the bug this class exists
     * to fix.
     */
    fun clearEditor(bridge: GlyphEditorBridge) {
        if (editor === bridge) editor = null
    }

    // ---- the conversation ----

    /**
     * Loads the conversation about [designId], once.
     *
     * Called from the chat modal's first composition — **never from anything
     * `Core.init` touches**. This is the first thing in the process that forces
     * chat storage, and forcing it creates a credential-protected directory,
     * which cannot be done before the first unlock; see [ChatStore].
     *
     * The early return is what keeps a turn on screen: reopening the editor on
     * the same design mid-turn finds the conversation already loaded and leaves
     * every field of it — the streamed reply included — exactly where it is.
     */
    fun openChat(designId: String) {
        val current = _chat.value
        if (current.designId == designId && current.restored) return
        viewEpoch++
        _chat.value = GlyphChatState(
            designId = designId,
            // The banner is about the canvas, not about the conversation, and a
            // deferred apply lands before anybody opens the chat — so a change
            // there IS a way back from must still offer one here.
            canRevert = revertSnapshot != null && revertOf == designId,
        )
        openJob = scope.launch {
            // Through the queue rather than straight onto [ioContext]: a
            // correction may be mid-flight on the same file. See [persistQueue].
            val loaded = if (designId.isBlank()) null else awaitOp { PersistOp.Load(designId, it) }
            transcript = loaded?.copy(designId = designId) ?: ChatTranscript(designId = designId)
            _chat.update {
                if (it.designId != designId) it
                else it.copy(restored = true, messages = transcript.messages)
            }
        }
    }

    /**
     * Puts the op [build] makes on [persistQueue] and waits for its answer, or
     * null if the queue is gone — in which case the conversation reads as empty,
     * which is [ChatStore]'s contract for every other way a read can fail.
     */
    private suspend fun awaitOp(
        build: (CompletableDeferred<ChatTranscript?>) -> PersistOp,
    ): ChatTranscript? {
        val answer = CompletableDeferred<ChatTranscript?>()
        if (persistQueue.trySend(build(answer)).isFailure) return null
        return answer.await()
    }

    /**
     * Clears the conversation about the design being edited: the transcript on
     * disk, and everything the sheet is showing. See [GlyphAiViewModel.resetChat].
     */
    fun resetChat(): Boolean {
        val state = _chat.value
        if (!state.canReset()) return false
        val designId = state.designId
        transcript = ChatTranscript(designId = designId)
        lastTurn = null
        _chat.value = state.cleared()
        if (designId.isNotBlank()) persistQueue.trySend(PersistOp.Delete(designId))
        return true
    }

    // ---- the composer ----

    fun attached(image: AttachedImage) {
        _chat.update {
            if (it.attachments.size >= MAX_ATTACHMENTS) it
            else it.copy(attachments = it.attachments + image)
        }
    }

    fun attachFailed() {
        _chat.update { it.copy(attachFailed = true) }
    }

    fun removeAttachment(id: Long) {
        _chat.update { it.copy(attachments = it.attachments.filterNot { image -> image.id == id }) }
    }

    fun clearAttachError() {
        _chat.update { it.copy(attachFailed = false) }
    }

    fun dismissFailure() {
        _chat.update { it.copy(failure = null) }
    }

    /** How many attachments the composer will hold. */
    fun attachmentsFull(): Boolean = _chat.value.attachments.size >= MAX_ATTACHMENTS

    /** See [GlyphAiViewModel.send]. False means nothing was sent. */
    fun send(text: String): Boolean {
        val state = _chat.value
        val trimmed = text.trim()
        if (trimmed.isEmpty() && state.attachments.isEmpty()) return false
        if (state.sending) return false
        return startTurn(
            PendingTurn(
                text = trimmed,
                imageDataUrls = state.attachments.map { it.dataUrl },
                // The same photos, as pixels, for `image_to_grid`. Carried on the
                // pending turn rather than read from the composer at snapshot
                // time, so a retry converts the images that were actually sent
                // and not whatever happens to be attached now.
                images = state.attachments.mapNotNull { it.source },
            ),
            record = true,
        )
    }

    /** See [GlyphAiViewModel.retry]. */
    fun retry() {
        val pending = lastTurn ?: return
        if (_chat.value.sending) return
        startTurn(pending, record = false)
    }

    /**
     * Abandons the turn in flight — the user's explicit cancel, and the only
     * thing in the app that still ends a turn early.
     *
     * The socket read cannot be interrupted, so this frees the *user*, not the
     * connection. Anything the turn already applied stays applied and stays
     * revertible, and the checkpoint the turn may have written is cleared by the
     * turn's own ending (see [startTurn]) so a stopped turn leaves the transcript
     * as it was — which is what this app has always stored for a turn that
     * produced no answer.
     */
    fun stopTurn() {
        turn?.cancel()
        turn = null
        _chat.update { it.turnEnded() }
    }

    /**
     * The state a turn leaves behind: nothing in flight, and no half-narrated
     * progress.
     *
     * One function for all three endings — answered, failed, abandoned — because
     * the fields that must be reset together grew from two to five, and a turn
     * that cleared its trace but left its step list showing would keep narrating
     * work that finished minutes ago. The steps are not lost by this: a turn that
     * answered puts the same calls under its message as [ChatMessage.tools].
     */
    private fun GlyphChatState.turnEnded(): GlyphChatState =
        copy(sending = false, streaming = "", trace = null, steps = emptyList(), startedAtMs = 0L)

    /** Puts the design back as it was before the assistant's most recent change. */
    fun revertLastChange() {
        val snapshot = revertSnapshot ?: return
        if (revertOf != _chat.value.designId) return
        val bridge = editor ?: return
        if (bridge.apply(snapshot) is GlyphApplyResult.Applied) {
            revertSnapshot = null
            revertOf = ""
            _chat.update { it.copy(canRevert = false) }
        }
    }

    // ---- one turn ----

    private fun startTurn(pending: PendingTurn, record: Boolean): Boolean {
        if (turn?.isActive == true) return false
        val bridge = editor ?: run {
            // A turn has to start from an open editor: the model is answering a
            // question about a drawing, and with no bridge there is no drawing to
            // read. It may FINISH with none, which is the whole point of the
            // deferred apply below.
            DebugLog.w(TAG, "no editor is registered; nothing was sent")
            return false
        }
        // The canvas from the editor, the photos from the message being sent:
        // the bridge knows nothing about attachments and should not.
        val context = bridge.snapshot().copy(images = pending.images)
        val designId = _chat.value.designId
        // Captured BEFORE the new message is appended: the orchestrator takes the
        // new turn separately, and history that already contained it would send
        // it twice.
        val history = (if (record) transcript else transcript.withoutTrailingUser()).asInput()
        if (record) {
            appendMessage(
                ChatMessage(
                    role = ChatRole.USER,
                    text = pending.text,
                    atMs = now(),
                    imageCount = pending.imageDataUrls.size,
                ),
            )
        }
        lastTurn = pending
        _chat.update {
            it.copy(
                sending = true,
                streaming = "",
                trace = ChatTrace.Thinking,
                steps = emptyList(),
                startedAtMs = now(),
                failure = null,
                attachments = emptyList(),
            )
        }

        val epoch = viewEpoch
        val base = transcript
        foreground.turnStarted(designId, context.design.name)

        turn = scope.launch {
            val streamed = StringBuilder()
            val notes = mutableListOf<ChatToolNote>()
            var checkpointedAt = 0L
            var checkpointOnDisk = false
            var appended = false

            /** Writes what has arrived so far, so a killed process leaves a record. */
            fun checkpoint() {
                if (base.designId.isBlank()) return
                if (streamed.isEmpty() && notes.isEmpty()) return
                checkpointedAt = now()
                checkpointOnDisk = true
                persistQueue.trySend(
                    PersistOp.Save(
                        base.withPartial(
                            ChatMessage(
                                role = ChatRole.ASSISTANT,
                                text = streamed.toString(),
                                atMs = checkpointedAt,
                                tools = notes.toList(),
                                partial = true,
                            ),
                        ),
                    ),
                )
            }

            try {
                val result = runner.run(
                    TurnRequest(
                        context = context,
                        history = history,
                        message = ChatMessageItem.user(pending.text, pending.imageDataUrls),
                        applyDesign = { design -> applyFromModel(design, designId, epoch) },
                        onTrace = { trace ->
                            // The screen drops a preamble when a tool starts —
                            // see [onTrace] — so the checkpoint must drop it too,
                            // or a killed turn would be redisplayed with the
                            // thinking-out-loud the user was never shown.
                            if (trace is ChatTrace.RunningTool) streamed.setLength(0)
                            onTrace(trace, epoch)
                        },
                        onToolNote = { note ->
                            notes += note
                            updateFor(epoch) { it.copy(steps = it.steps + note) }
                            // Unthrottled: a tool note is rare, is the visible
                            // record of a slow turn, and is exactly the thing
                            // worth having survived if the process goes now.
                            checkpoint()
                        },
                        onTextDelta = { delta ->
                            streamed.append(delta)
                            updateFor(epoch) { it.copy(streaming = it.streaming + delta) }
                            // The first fragment writes at once and the rest are
                            // throttled: the moment a reply *starts* is when a
                            // record of it is worth most, and a turn killed three
                            // seconds in should not read as a turn that never
                            // began.
                            if (checkpointedAt == 0L || now() - checkpointedAt >= CHECKPOINT_INTERVAL_MS) {
                                checkpoint()
                            }
                        },
                    ),
                )
                when (result) {
                    is GlyphAiOrchestrator.TurnResult.Success -> {
                        commit(
                            base,
                            epoch,
                            ChatMessage(
                                role = ChatRole.ASSISTANT,
                                text = result.text,
                                atMs = now(),
                                tools = result.toolNotes,
                            ),
                        )
                        appended = true
                        updateFor(epoch) { it.turnEnded() }
                    }

                    is GlyphAiOrchestrator.TurnResult.Failure -> {
                        DebugLog.w(
                            TAG,
                            "turn failed (${result.reason}) after ${result.rounds} round(s): ${result.detail}",
                        )
                        // A turn that changed nothing stores nothing; a turn that
                        // changed the design leaves the note that explains it.
                        // See [noticeFor].
                        val notice = noticeFor(result)
                        if (notice != null) {
                            commit(base, epoch, notice)
                            appended = true
                        }
                        updateFor(epoch) {
                            it.turnEnded().copy(failure = ChatFailure(result.reason, result.detail))
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // `GlyphAiOrchestrator` promises never to throw, so reaching here
                // is a bug rather than a bad reply — but this coroutine no longer
                // belongs to a screen, and an exception escaping an
                // application-scoped `launch` reaches the thread's uncaught
                // handler and takes the process with it. It is reported as the
                // failure it is instead, so the composer comes back and the
                // detail is on screen where it can be copied.
                DebugLog.w(TAG, "turn threw: ${e.javaClass.simpleName}: ${e.message}")
                updateFor(epoch) {
                    it.turnEnded().copy(
                        failure = ChatFailure(
                            GlyphAiOrchestrator.TurnResult.Reason.TRANSPORT,
                            "${e.javaClass.simpleName}: ${e.message}",
                        ),
                    )
                }
            } finally {
                foreground.turnEnded()
                // The turn is over, so the checkpoint describes something that is
                // no longer happening. Either something above replaced it — the
                // reply, or the notice a turn that changed the design leaves; see
                // [noticeFor] — or the conversation goes back to what it was
                // before the turn, which is what this app stores for a turn that
                // produced neither an answer nor a change.
                if (checkpointOnDisk && !appended) {
                    persistQueue.trySend(PersistOp.Save(base.withoutPartial()))
                }
            }
        }
        return true
    }

    /**
     * The transcript entry a failed turn leaves behind, or null for one that
     * leaves none.
     *
     * ## The rule, and why it is this rule
     *
     * **A turn that changed something has to be explainable afterwards.** That is
     * the whole of it, and everything else follows: a turn that produced neither
     * an answer nor a change stores nothing, because a dropped connection or a
     * user pressing stop is not something the assistant *said* and a thread that
     * accumulated "Couldn't reach the service" would be a log, not a
     * conversation. A turn that put artwork on the canvas is a different thing
     * entirely — the user's design is not as they left it, and the only place
     * that can ever explain why is the thread they will scroll back through.
     *
     * The case that forced this was [GlyphAiOrchestrator.TurnResult.Reason.STUCK_SALVAGED]:
     * the turn runs out of tool rounds, the last draft that passed validation is
     * applied on the way out, and the design changes. The banner saying so is
     * dismissed with the sheet, so somebody reopening that design a day later
     * found artwork they did not draw and a conversation that did not mention it.
     * It is not special-cased, though — the discriminator is
     * [GlyphAiOrchestrator.TurnResult.Failure.appliedDesign], because "did this
     * turn change the design" is the question that matters, and a turn whose
     * connection died *after* an apply landed leaves exactly the same hole.
     *
     * ## What it is, in the thread
     *
     * An assistant message carrying [ChatMessage.error], which is precisely what
     * that flag is for: shown to the person, never replayed to the model
     * ([ChatTranscript.asInput] drops it), so the model is not taught that
     * narrating its own failures is a thing it does. The turn's tool notes ride
     * along, so the step list under it shows the apply that actually happened.
     */
    private fun noticeFor(result: GlyphAiOrchestrator.TurnResult.Failure): ChatMessage? {
        if (result.appliedDesign == null) return null
        return ChatMessage(
            role = ChatRole.ASSISTANT,
            text = notices.changedTheDesign(result.reason),
            atMs = now(),
            tools = result.toolNotes,
            error = true,
        )
    }

    /**
     * The orchestrator's apply hook.
     *
     * Reads [editor] afresh rather than closing over the bridge the turn started
     * with, so a design produced after a rotation lands on the editor that is
     * actually on screen. Returning a sentence rather than null makes the *model*
     * see a failed tool call — that string is not user-facing copy.
     *
     * ## With no editor open, the change is recorded rather than refused
     *
     * This used to answer "the design editor is no longer open, so nothing was
     * changed", which was a fair description of a turn that could not outlive its
     * screen. It can now, so that answer would throw away the drawing the user
     * asked for in the *ordinary* case of somebody closing the editor and
     * waiting. The design is written down instead and applied when that design is
     * next opened — see [applyDeferred] — and the model is told it succeeded,
     * because from its side it has: the change is accepted, recorded and on its
     * way to the canvas. Telling it otherwise would have it apologise and redraw,
     * burning the user's tool rounds on work that was already done.
     */
    private fun applyFromModel(design: Design, designId: String, epoch: Int): String? {
        val bridge = editor
        if (bridge == null) {
            if (designId.isBlank()) {
                return "There is no design open to change."
            }
            defer(design, designId)
            return null
        }
        return when (val outcome = bridge.apply(design)) {
            is GlyphApplyResult.Applied -> {
                revertSnapshot = outcome.previous
                revertOf = designId
                updateFor(epoch) { it.copy(canRevert = true) }
                null
            }

            is GlyphApplyResult.Refused -> outcome.reason
        }
    }

    /**
     * Records [design] for the next time [designId] is opened.
     *
     * The baseline is read here rather than at the start of the turn, and it is
     * read from *disk*: the editor writes on its way out, so the version this has
     * to compare against later is the one that close left behind, not the one the
     * turn was shown. See [PendingApply] for the conflict rule it feeds.
     */
    private fun defer(design: Design, designId: String) {
        scope.launch {
            try {
                val base = withContext(ioContext) { designs.modifiedAt(designId) }.orEmpty()
                val record = PendingApply(
                    designId = designId,
                    baseModifiedAt = base,
                    atMs = now(),
                    design = design,
                )
                withContext(ioContext) { pendingApplies.put(record) }
                DebugLog.i(TAG, "no editor open; $designId will take the change on next open")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DebugLog.w(TAG, "could not record a change for $designId: ${e.message}")
            }
        }
    }

    /**
     * Hands [bridge] whatever the assistant finished while there was no editor.
     *
     * The conflict rule is [pendingApplyVerdict], and the case it exists for is
     * the user going back into the editor themselves in the meantime: their
     * strokes are newer than the model's draft and are **not** overwritten by it.
     * That precedence is deliberate and stays — the model's drawing can be
     * re-requested in a sentence, the user's strokes cannot be recovered. A
     * record is consumed whichever way the verdict goes, so a draft that cannot
     * land does not re-offer itself on every subsequent open.
     *
     * An apply that does land sets the revert snapshot exactly as a live one
     * does, so the user opens their design, sees it changed, and has the same
     * one-tap way back they would have had if they had watched it happen.
     *
     * ## An apply that does not land is said out loud
     *
     * The three verdicts that drop the record used to leave a `DebugLog.i` and
     * nothing else — while the reply that recorded it is still sitting in the
     * thread saying the design was changed, because [applyFromModel] tells the
     * model it succeeded. The user was told a thing was done that silently was
     * not, which is worse than not being told at all. So the conversation is
     * corrected; see [correctDeferred].
     */
    private suspend fun applyDeferred(bridge: GlyphEditorBridge) {
        val designId = bridge.snapshot().design.id
        if (designId.isBlank()) return
        val record = withContext(ioContext) { pendingApplies.take(designId) } ?: return
        val current = withContext(ioContext) { designs.modifiedAt(designId) }
        val verdict = pendingApplyVerdict(record, current, now())
        if (verdict != PendingApplyVerdict.APPLY) {
            DebugLog.i(TAG, "a change waiting for $designId was dropped: $verdict")
            correctDeferred(designId, verdict)
            return
        }
        // The editor may have gone again while the two reads above were running.
        if (editor !== bridge) return
        when (val outcome = bridge.apply(record.design)) {
            is GlyphApplyResult.Applied -> {
                revertSnapshot = outcome.previous
                revertOf = designId
                _chat.update { if (it.designId == designId) it.copy(canRevert = true) else it }
                DebugLog.i(TAG, "applied the change that was waiting for $designId")
            }

            is GlyphApplyResult.Refused -> DebugLog.w(TAG, "the editor refused: ${outcome.reason}")
        }
    }

    /**
     * Tells the conversation about [designId] that the change it was already
     * promised is not coming, and why.
     *
     * ## The race, and how it is settled
     *
     * This runs from [setEditor], which is normally *before* [openChat] has read
     * that design's transcript — the chat sheet does not compose until somebody
     * taps sparkles. So this is a load-modify-save on a file the session does not
     * hold, against a reader that may start at any moment. Getting it wrong loses
     * a conversation, so it is settled twice over:
     *
     * 1. **Wait for a read already in flight** ([openJob]). The question below —
     *    "is this design's thread in memory?" — has no answer while one is
     *    arriving, and a wrong answer is the whole bug.
     * 2. **Then either append in memory, or go through the queue.** If the thread
     *    *is* the one on screen and loaded, the correction is an ordinary
     *    [appendMessage]: one step on this dispatcher, nothing to interleave with,
     *    and it is on screen at once rather than on the next open. Otherwise it is
     *    a [PersistOp.Correct], which reads and writes inside [persistQueue] where
     *    no [PersistOp.Load] can slip between the two halves.
     *
     * The reconciliation afterwards covers the last ordering: an [openChat] that
     * begins *during* the join queues its load ahead of the correction and would
     * otherwise show — and later save — a thread one message short. Its answer
     * lands first, because the queue is FIFO and so are the resumptions; adopting
     * the corrected transcript here therefore always wins.
     *
     * If the user never opens the chat at all, none of that runs and the file on
     * disk is already right.
     */
    private suspend fun correctDeferred(designId: String, verdict: PendingApplyVerdict) {
        val message = ChatMessage(
            role = ChatRole.ASSISTANT,
            text = notices.deferredApplyDropped(verdict),
            atMs = now(),
            // A notice, not something the assistant said: `asInput` drops it, so
            // the model is never taught that retracting its own work is a thing
            // it does. The same flag, for the same reason, as [noticeFor].
            error = true,
        )
        openJob?.join()
        val onScreen = _chat.value
        if (onScreen.designId == designId && onScreen.restored) {
            // Nothing to correct is not the same as nothing to say; see
            // [ChatTranscript.withCorrection] for why an empty thread is left
            // empty.
            if (transcript.messages.isEmpty()) return
            appendMessage(message)
            return
        }
        val corrected = awaitOp { PersistOp.Correct(designId, message, it) } ?: return
        if (_chat.value.designId != designId) return
        transcript = corrected
        _chat.update {
            if (it.designId == designId && it.restored) it.copy(messages = corrected.messages) else it
        }
    }

    // ---- state plumbing ----

    /**
     * Applies [block] only while the conversation on screen is still the one this
     * turn belongs to. See [viewEpoch].
     */
    private fun updateFor(epoch: Int, block: (GlyphChatState) -> GlyphChatState) {
        if (viewEpoch != epoch) return
        _chat.update(block)
    }

    private fun onTrace(trace: ChatTrace, epoch: Int) {
        updateFor(epoch) {
            when (trace) {
                // A model that thinks out loud before calling a tool has produced
                // text that is not the answer; leaving it would have the reply
                // appended to a preamble nobody was meant to read as one.
                is ChatTrace.RunningTool -> it.copy(trace = trace, streaming = "")
                else -> it.copy(trace = trace)
            }
        }
    }

    /** Appends to the conversation of record and writes it out. */
    private fun appendMessage(message: ChatMessage) {
        transcript = transcript.plus(message)
        _chat.update { it.copy(messages = transcript.messages) }
        if (transcript.designId.isNotBlank()) {
            persistQueue.trySend(PersistOp.Save(transcript))
        }
    }

    /**
     * Appends a turn's own message onto the conversation it started from, whether
     * or not that conversation is still the one on screen.
     *
     * [base] rather than [transcript] because the two can differ: the user may
     * have opened another design while this turn was running, and the reply still
     * belongs in the thread that asked for it. The screen is only updated when it
     * is still showing that thread.
     */
    private fun commit(base: ChatTranscript, epoch: Int, message: ChatMessage) {
        val next = base.withoutPartial().plus(message)
        if (viewEpoch == epoch) {
            transcript = next
            _chat.update { it.copy(messages = next.messages) }
        }
        if (next.designId.isNotBlank()) persistQueue.trySend(PersistOp.Save(next))
    }

    /** This transcript without a trailing user turn; see [retry]. */
    private fun ChatTranscript.withoutTrailingUser(): ChatTranscript =
        if (messages.lastOrNull()?.role == ChatRole.USER) copy(messages = messages.dropLast(1)) else this

    /**
     * A turn that has been composed, and can be composed again by [retry].
     *
     * [imageDataUrls] is what the *model* sees; [images] is the same pictures as
     * brightness grids, which is what `image_to_grid` converts. Both are held
     * per turn because an attachment travels with one message and is not stored
     * afterwards — see [GlyphToolContext.images].
     */
    private data class PendingTurn(
        val text: String,
        val imageDataUrls: List<String>,
        val images: List<SourceImage> = emptyList(),
    )

    /**
     * One unit of work on conversation storage. See [persistQueue].
     *
     * [Load] and [Correct] carry a [CompletableDeferred] because they have
     * something to say back — and because *waiting for the queue* is what makes
     * them ordered against the writes rather than merely dispatched near them.
     */
    private sealed interface PersistOp {
        data class Save(val transcript: ChatTranscript) : PersistOp
        data class Delete(val designId: String) : PersistOp
        data class Load(
            val designId: String,
            val answer: CompletableDeferred<ChatTranscript?>,
        ) : PersistOp

        data class Correct(
            val designId: String,
            val message: ChatMessage,
            val answer: CompletableDeferred<ChatTranscript?>,
        ) : PersistOp
    }

    /**
     * Answers [this] op with [value], if it is one of the two that answer at all.
     * Settling an already-settled deferred is a no-op, which is what lets the
     * consumer's `finally` be an unconditional safety net.
     */
    private fun PersistOp.answer(value: ChatTranscript?) {
        when (this) {
            is PersistOp.Load -> answer.complete(value)
            is PersistOp.Correct -> answer.complete(value)
            is PersistOp.Save, is PersistOp.Delete -> Unit
        }
    }

    companion object {
        private const val TAG = "GlyphAi"

        /**
         * How many images may ride on one message.
         *
         * Each is up to 1024 px of JPEG as base64 — a few hundred kilobytes of
         * request body — and the model is being asked to turn them into a 13x13
         * drawing. Four is already more reference than that task can use.
         */
        const val MAX_ATTACHMENTS = 4

        /**
         * The shortest gap between two checkpoints of a reply that is still
         * arriving.
         *
         * Text deltas land around thirty times a second, so writing on each would
         * be thirty file writes a second for the length of a turn — the battery
         * cost the editor's own debounce exists to avoid. Two seconds bounds what
         * a process death can lose to about a sentence, which is the resolution
         * this is useful at: the point is to show that the assistant *was*
         * answering and roughly what it said, not to reproduce the last word.
         */
        const val CHECKPOINT_INTERVAL_MS = 2_000L

        /**
         * The last line of defence for a scope that belongs to the process.
         *
         * A `viewModelScope` that let an exception escape took an Activity's
         * coroutine down with it; this one would reach the thread's uncaught
         * handler and take the *app* down, in a feature the user is not even
         * looking at. Everything launched here catches its own failures — this
         * exists so that forgetting to, once, is a log line rather than a crash
         * report from somebody whose phone was in their pocket.
         */
        private val crashGuard = CoroutineExceptionHandler { _, e ->
            DebugLog.w(TAG, "uncaught in the assistant's scope: ${e.javaClass.simpleName}: ${e.message}")
        }

        @Volatile
        private var instance: GlyphAiSession? = null

        /**
         * The process's one session.
         *
         * **Nothing reachable from `Core.init` may call this.** It builds
         * [ChatStore], [PendingApplyStore] and [TokenStore], all of which take the
         * credential-protected context, and credential-protected `filesDir`
         * cannot be created before the first unlock — which is exactly the state
         * `Core.init` runs in when `AodToyService` starts during Direct Boot.
         * Every one of those stores defers its own directory to a `by lazy`, so
         * constructing them here is safe on its own; this note is about not
         * moving the call.
         *
         * The client is `by lazy` on top of that, so a signed-out user never
         * builds one.
         */
        fun of(context: Context): GlyphAiSession {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }
        }

        private fun build(app: Context): GlyphAiSession {
            val chats = ChatStore(app) { Core.designStore.storedIds() }
            val pending = PendingApplyStore(app)
            val client: GlyphChatClient by lazy { GlyphAiClient(TokenStore(app)) }
            return GlyphAiSession(
                scope = CoroutineScope(
                    SupervisorJob() + Dispatchers.Main.immediate + crashGuard,
                ),
                transcripts = object : TranscriptStore {
                    override suspend fun load(designId: String) = chats.load(designId)
                    override suspend fun save(transcript: ChatTranscript) {
                        chats.save(transcript)
                    }

                    override suspend fun delete(designId: String) {
                        chats.delete(designId)
                    }
                },
                pendingApplies = object : PendingApplyRecords {
                    override suspend fun take(designId: String) = pending.take(designId)
                    override suspend fun put(record: PendingApply) {
                        pending.put(record)
                    }
                },
                designs = StoredDesignFacts { Core.designStore.load(it)?.modifiedAt },
                foreground = GlyphAiTurnNotifier(app),
                notices = object : TurnNotices {
                    override fun changedTheDesign(
                        reason: GlyphAiOrchestrator.TurnResult.Reason,
                    ): String = app.getString(
                        when (reason) {
                            GlyphAiOrchestrator.TurnResult.Reason.STUCK_SALVAGED ->
                                R.string.ai_chat_notice_salvaged

                            else -> R.string.ai_chat_notice_changed
                        },
                    )

                    override fun deferredApplyDropped(verdict: PendingApplyVerdict): String =
                        app.getString(
                            when (verdict) {
                                PendingApplyVerdict.CONFLICT ->
                                    R.string.ai_chat_notice_deferred_conflict

                                PendingApplyVerdict.EXPIRED ->
                                    R.string.ai_chat_notice_deferred_expired

                                // APPLY never reaches here — see [TurnNotices] —
                                // and if it somehow did, "I couldn't find it" is
                                // the safe thing to have said.
                                else -> R.string.ai_chat_notice_deferred_missing
                            },
                        )
                },
                runner = TurnRunner { request ->
                    GlyphAiOrchestrator(
                        client = client,
                        // Read HERE, per turn, rather than once when this session
                        // was built: the setting exists to rescue a broken model
                        // id, and a fix that only took effect after a restart
                        // would be a fix the user has no reason to believe worked.
                        model = ChatWire.resolveModel(
                            Core.prefs.getString(PrefKeys.AI_MODEL, PrefKeys.AI_MODEL_DEF),
                        ),
                        // Per turn, for the same reason the model is: somebody
                        // raises the budget precisely because the turn they just
                        // watched run out of rounds, and the next thing they do
                        // is ask again. A value that only applied after a restart
                        // would look like it had not worked.
                        maxRounds = Core.prefs.aiMaxRounds(),
                        // And per turn again, for the third time and the same
                        // reason: the levels above `high` are unverified (see
                        // [ReasoningEffort]), so the expected way to use this
                        // setting is to try one, watch the request fail, and pick
                        // another — a loop that only works if the change lands on
                        // the very next message.
                        reasoningEffort = Core.prefs.aiReasoningEffort().wire,
                        applyDesign = request.applyDesign,
                        onTrace = request.onTrace,
                        onToolNote = request.onToolNote,
                    ).runTurn(
                        instructions = GlyphAiPrompt.build(request.context.design),
                        history = request.history,
                        message = request.message,
                        context = request.context,
                        onTextDelta = request.onTextDelta,
                    )
                },
            )
        }
    }
}
