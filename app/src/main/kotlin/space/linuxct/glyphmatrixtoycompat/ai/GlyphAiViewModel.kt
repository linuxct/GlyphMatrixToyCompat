package space.linuxct.glyphmatrixtoycompat.ai

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import space.linuxct.glyphmatrixtoycompat.Core
import space.linuxct.glyphmatrixtoycompat.core.DebugLog
import space.linuxct.glyphmatrixtoycompat.core.PrefKeys
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
import space.linuxct.glyphmatrixtoycompat.core.design.Design
import java.io.IOException
import java.net.BindException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * What went wrong, as a *cause* rather than as a sentence.
 *
 * The ViewModel does not build user-facing text: a message assembled here would
 * be an untranslated string in a file with no other strings in it, and the UI
 * layer is where this app's copy lives. Each of these maps to one entry in
 * `strings.xml`, and the four are distinguished because the recovery differs —
 * "try again", "close the other sign-in", "install a browser", "check the
 * connection".
 */
enum class SignInFailure {
    /** Nobody came back to the callback server before it gave up waiting. */
    TIMED_OUT,

    /** Port 1455 is already held — a previous attempt, or another app. */
    PORT_BUSY,

    /** No app on the device will handle an `http` VIEW intent. */
    NO_BROWSER,

    /** Anything else: transport, a rejected code, an unparsable token reply. */
    FAILED,
}

/**
 * The sign-in as the dialog sees it.
 *
 * [signedIn] is the durable half — it reflects [TokenStore] — while [busy] and
 * [failure] describe the attempt in flight. They are one object rather than
 * three flows so the dialog can never render a torn combination such as "signed
 * in" and "waiting for the browser" at the same time.
 */
data class GlyphAiAuthState(
    val signedIn: Boolean,
    val busy: Boolean = false,
    val failure: SignInFailure? = null,
    /** Technical detail shown under the message; null when there is nothing useful to add. */
    val detail: String? = null,
    /**
     * Whether the one-off disclosure has been accepted. Part of *this* object
     * rather than a flow of its own because the sparkles button asks one question
     * — "which door do I open" — and [aiGate] answers it from both halves at
     * once; two flows would let it read a stale half and open the wrong one.
     */
    val consented: Boolean = false,
)

/**
 * How the assistant reaches the canvas.
 *
 * The editor is the only thing that knows what is being drawn, and it is also the
 * only thing allowed to change it. Rather than handing the ViewModel a reference
 * to the editor's state — which would be a rotation away from pointing at a
 * destroyed composition — the editor **registers** an implementation of this and
 * withdraws it when it goes away. The ViewModel calls whichever one is registered
 * *at the moment it needs it*, so a turn started before a rotation applies its
 * design to the editor that exists after it.
 *
 * Both methods are called on the main thread, and must be: [GlyphToolContext] is
 * built from live frame buffers that the pointer handler writes from that same
 * thread.
 */
interface GlyphEditorBridge {
    /** The design as shown — unsaved edits included — and what the editor has open. */
    fun snapshot(): GlyphToolContext

    /** Puts a model's design on the canvas. */
    fun apply(design: Design): GlyphApplyResult
}

/** What [GlyphEditorBridge.apply] did. */
sealed interface GlyphApplyResult {
    /**
     * It is on the canvas. [previous] is the document as it was a moment before,
     * which is the entire mechanism behind "Undo AI change".
     */
    data class Applied(val previous: Design) : GlyphApplyResult

    /**
     * Nothing changed. [reason] is shown to the **model**, not to the user — the
     * orchestrator rewrites the tool result with it so that a model whose apply
     * failed does not go on to describe a change nobody made.
     */
    data class Refused(val reason: String) : GlyphApplyResult
}

/** A turn that did not produce an answer, as the chat shows it. */
data class ChatFailure(
    val reason: GlyphAiOrchestrator.TurnResult.Reason,
    /**
     * The server's own words, or the transport's. **Never replaced with a
     * friendlier sentence**: this app has never once run against
     * `chatgpt.com/backend-api/codex/responses`, and a wrong model id, a rejected
     * `originator` header and an expired token are three different bugs with
     * three different fixes that all present as "it didn't work". The HTTP status
     * and the body are the only things on screen that can tell them apart.
     */
    val detail: String,
)

/**
 * The conversation, as the modal draws it.
 *
 * [messages] is the persisted history; [streaming] is the reply currently
 * arriving, which is deliberately *not* a message yet — it becomes one when the
 * turn finishes, so nothing half-written is ever written to disk.
 */
data class GlyphChatState(
    /** The design this conversation belongs to; blank before the first open. */
    val designId: String = "",
    /** True once the transcript has been read (or found not to exist). */
    val restored: Boolean = false,
    val messages: List<ChatMessage> = emptyList(),
    /** The assistant's reply so far, empty when nothing is arriving. */
    val streaming: String = "",
    /** What the assistant is doing right now, for the trace line. */
    val trace: ChatTrace? = null,
    /**
     * Every tool call this turn has finished, oldest first — the visible record
     * of *why* a slow turn is slow.
     *
     * [trace] alone is a single line that changes every few seconds, so a turn
     * spent drawing, failing validation and redrawing four times looks
     * indistinguishable from a hang. This list is what the user watches instead:
     * one line per attempt, with [ChatToolNote.ok] saying whether it stuck. It
     * lives in the ViewModel rather than in the modal so it survives a rotation
     * mid-turn, and it is cleared when the turn ends — at which point the same
     * calls reappear under the finished message as [ChatMessage.tools], so
     * nothing is actually lost by clearing it.
     */
    val steps: List<ChatToolNote> = emptyList(),
    /**
     * Wall clock at which the turn in flight began, or 0 when none is.
     *
     * The elapsed time is derived rather than counted, so a rotation — or the
     * modal being closed and reopened, which does not cancel the turn — does not
     * restart the clock.
     */
    val startedAtMs: Long = 0L,
    val sending: Boolean = false,
    val attachments: List<AttachedImage> = emptyList(),
    /** An image the picker returned that could not be read. Cleared by the UI. */
    val attachFailed: Boolean = false,
    val failure: ChatFailure? = null,
    /** True while there is a design change that "Undo AI change" would restore. */
    val canRevert: Boolean = false,
)

/**
 * Whether "Reset this chat" is offered right now.
 *
 * Two conditions, and both are about not lying to the user. There must be a
 * conversation to clear — on an empty thread the action would appear to do
 * something and do nothing — and **no turn may be in flight**, because a running
 * turn ends by appending its reply to the transcript and would write a
 * conversation straight back into one that had just been emptied. Stopping first
 * is one tap away and is already the composer's only control while a turn runs;
 * silently cancelling somebody's turn from a menu item labelled "reset" would be
 * a second, unannounced destruction.
 *
 * [restored] is in here because the transcript is read asynchronously on the way
 * in: clearing before it has arrived would empty the screen and then have the
 * file land on top of it.
 *
 * Pure, and outside the ViewModel, so the rule can be proven under plain JUnit —
 * `GlyphAiViewModel` needs an `Application` and cannot be built there.
 */
internal fun GlyphChatState.canReset(): Boolean =
    restored && !sending && messages.isNotEmpty()

/**
 * This conversation, emptied — everything a turn or a transcript put on screen.
 *
 * **[canRevert] survives, deliberately.** The banner is not part of the
 * conversation: it is a one-tap way back from a change to the *artwork*, the
 * artwork is explicitly not what a reset touches, and a chat reset that silently
 * threw away the only route back to the drawing somebody had a minute ago would
 * destroy the very thing the confirmation promises to leave alone. The banner
 * says what it does on it ("The assistant changed your design"), so it still
 * reads truthfully with the explaining messages gone.
 *
 * [designId] and [restored] survive for a duller reason: the sheet is still open
 * on the same design and its transcript is still loaded — it is now empty, which
 * is a different thing from not having been read yet.
 */
internal fun GlyphChatState.cleared(): GlyphChatState =
    GlyphChatState(designId = designId, restored = restored, canRevert = canRevert)

/**
 * Holds the OpenAI sign-in for the design editor.
 *
 * ## Why a ViewModel at all
 *
 * The OAuth flow is *long*: it opens a browser, leaves the app, and then waits on
 * a `ServerSocket` for as long as the user takes to type a password — up to the
 * callback server's ten-minute timeout. Rotating the phone, or the editor being
 * recreated while the browser is in front, would destroy a composition-scoped
 * coroutine and leave the socket bound with nobody listening. Scoped to the
 * activity, the job outlives every configuration change and is cancelled exactly
 * once, in [onCleared].
 *
 * ## Cancelling means closing the socket, not just the job
 *
 * `waitForOAuthCode` blocks in `ServerSocket.accept()`, and a blocking socket
 * accept is **not** interruptible by coroutine cancellation: `job.cancel()`
 * returns immediately, the IO thread stays parked in `accept()`, and port 1455
 * stays bound — so the *next* sign-in attempt would fail to bind it. That is the
 * "cancel, then try again" path, i.e. the most likely thing a user does after a
 * failure, so it has to work.
 *
 * [releaseCallbackPort] is what makes it work: after cancelling, it opens a
 * throwaway loopback connection to the port. `accept()` returns, the callback
 * server's `use` block closes the `ServerSocket` on its way out, and the port is
 * free. The connection is answered with nothing and its state does not match, so
 * it can never be mistaken for a real callback.
 *
 * It runs on a thread of its own rather than in [viewModelScope] deliberately —
 * [onCleared] cancels that scope before the body of this class would get to run,
 * and the one case where the port MUST be released is the editor being destroyed
 * mid-login.
 */
class GlyphAiViewModel(app: Application) : AndroidViewModel(app) {

    /**
     * Credential-protected — see [TokenStore]. Built from the Application, which
     * is the ordinary context; nothing here may use the device-protected one.
     */
    private val tokens = TokenStore(app)

    /** Also credential-protected, also from the ordinary context. */
    private val consent: AiConsentStorage = AiConsentStore(app)

    /**
     * The conversation store, **lazily**, and that is load-bearing rather than
     * tidy — see [ChatStore]'s own KDoc. Nothing may force this during
     * construction: this ViewModel is only ever built from an Activity, long
     * after unlock, but the rule that keeps Direct Boot working is "chat storage
     * is touched when the user opens the chat", and it is kept here too.
     */
    private val chats: ChatStore by lazy { ChatStore(app) { Core.designStore.storedIds() } }

    /** Lazily, so a signed-out user never builds one. */
    private val client: GlyphChatClient by lazy { GlyphAiClient(tokens) }

    private val _state = MutableStateFlow(
        GlyphAiAuthState(signedIn = tokens.isSignedIn, consented = consent.accepted),
    )
    val state: StateFlow<GlyphAiAuthState> = _state.asStateFlow()

    private val _chat = MutableStateFlow(GlyphChatState())
    val chat: StateFlow<GlyphChatState> = _chat.asStateFlow()

    private var job: Job? = null

    /** The turn in flight, if any. One at a time; the composer is disabled meanwhile. */
    private var turn: Job? = null

    /**
     * The conversation of record. [GlyphChatState.messages] mirrors it for the
     * UI; this is what is written to disk and what is replayed to the model.
     */
    private var transcript = ChatTranscript()

    /** Set by the editor while it is on screen. See [GlyphEditorBridge]. */
    private var editor: GlyphEditorBridge? = null

    /** The document as it was before the most recent accepted apply. */
    private var revertSnapshot: Design? = null

    /** What [retry] would send again. */
    private var lastTurn: PendingTurn? = null

    private var nextAttachmentId = 0L

    /**
     * Starts a sign-in: builds the PKCE flow, hands the authorize URL to
     * [openBrowser], and waits for the redirect to come back to the loopback
     * callback server.
     *
     * [openBrowser] is invoked **synchronously and then forgotten**. It closes
     * over an Activity, and the coroutine below can live for ten minutes; keeping
     * it as a field, or capturing it inside the job, would hold a destroyed
     * Activity for the whole of that. Everything after the launch is context-free.
     */
    fun signIn(openBrowser: (String) -> Unit) {
        if (job?.isActive == true) return

        val flow = try {
            createOAuthFlow()
        } catch (e: Exception) {
            fail(SignInFailure.FAILED, e)
            return
        }

        _state.value = authState(signedIn = false, busy = true)

        try {
            openBrowser(flow.url)
        } catch (e: Exception) {
            // ActivityNotFoundException, and anything else the platform raises
            // for "nothing can show this": either way there is no browser.
            fail(SignInFailure.NO_BROWSER, e)
            return
        }

        job = viewModelScope.launch {
            try {
                val code = waitForOAuthCode(flow.state)
                val issued = exchangeAuthorizationCode(code, flow.verifier)
                withContext(Dispatchers.IO) { tokens.save(issued) }
                _state.value = authState(signedIn = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Cancellation unblocks `accept()` by connecting to it (see
                // [releaseCallbackPort]), which surfaces here as an ordinary
                // exception on an already-cancelled coroutine. It is not a
                // failure the user asked about — they closed the dialog.
                if (!isActive) return@launch
                DebugLog.w("GlyphAi", "sign-in failed: ${e.javaClass.simpleName}: ${e.message}")
                _state.value = authState(
                    signedIn = tokens.isSignedIn,
                    failure = classify(e),
                    detail = e.message,
                )
            }
        }
    }

    /**
     * Abandons an attempt in flight. Safe to call when there is none, which is
     * what lets the dialog's dismissal path call it unconditionally.
     */
    fun cancelSignIn() {
        val running = job ?: return
        job = null
        running.cancel()
        releaseCallbackPort()
        _state.value = authState(signedIn = tokens.isSignedIn)
    }

    /** Clears the stored credentials. Cancels any attempt in flight first. */
    fun signOut() {
        cancelSignIn()
        viewModelScope.launch {
            withContext(Dispatchers.IO + NonCancellable) { tokens.clear() }
            _state.value = authState(signedIn = false)
        }
    }

    // ---- consent ----

    /**
     * Records that the disclosure was read and accepted.
     *
     * Written off the main thread and `NonCancellable`, because the very next
     * thing that happens is the sign-in — and an acceptance that lost a race with
     * the activity going away would show the disclosure again after the user had
     * already agreed and signed in. Declining has no counterpart here: see
     * [AiConsentStore].
     */
    fun acceptConsent() {
        if (_state.value.consented) return
        viewModelScope.launch {
            withContext(Dispatchers.IO + NonCancellable) { consent.accept() }
            _state.value = _state.value.copy(consented = true)
        }
    }

    // ---- the editor bridge ----

    /**
     * Registers the editor currently on screen. The editor calls this from a
     * `DisposableEffect` and withdraws with [clearEditor].
     *
     * Replacing an existing registration is normal, not an error: that is exactly
     * what a rotation does, and a turn in flight will find the new one.
     */
    fun setEditor(bridge: GlyphEditorBridge) {
        editor = bridge
    }

    /**
     * Withdraws [bridge], but **only if it is still the registered one**.
     *
     * A configuration change disposes the outgoing composition *after* the
     * incoming one has registered, so an unconditional clear would remove the
     * live editor and leave the assistant unable to reach the canvas.
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
     * [chats], and forcing it creates a credential-protected directory, which
     * cannot be done before the first unlock; see [ChatStore].
     *
     * A transcript that will not read is not an error the user is told about:
     * [ChatStore.load] returns null for a truncated file, a future format or a
     * design id that could not name a file, and the thread simply starts empty.
     */
    fun openChat(designId: String) {
        val current = _chat.value
        if (current.designId == designId && current.restored) return
        _chat.value = GlyphChatState(designId = designId)
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                if (designId.isBlank()) null else chats.load(designId)
            }
            transcript = loaded?.copy(designId = designId) ?: ChatTranscript(designId = designId)
            _chat.update { it.copy(restored = true, messages = transcript.messages) }
        }
    }

    /**
     * Clears the conversation about the design being edited: the transcript on
     * disk, and everything the sheet is showing.
     *
     * **The design is not touched, and that is the point of the action.** Nothing
     * here goes near [editor] or [revertSnapshot]: a reset forgets what was said,
     * it is not an undo of what was drawn, and the two are separate on purpose —
     * see [cleared] for why the revert banner outlives this.
     *
     * Returns false when the conversation may not be cleared right now — see
     * [canReset], which the menu item is disabled by, so a false here is the
     * belt to that dialog's braces rather than something a user can provoke.
     *
     * The file is removed rather than rewritten empty: a design nobody has talked
     * to has no transcript, and one somebody talked to and then reset is in
     * exactly that state. `NonCancellable`, and not a child of the turn, because
     * a conversation the user asked to be rid of must not come back on the next
     * open just because the editor closed on the same frame.
     */
    fun resetChat(): Boolean {
        val state = _chat.value
        if (!state.canReset()) return false
        val designId = state.designId
        transcript = ChatTranscript(designId = designId)
        // Otherwise the failure card's "Try again" — dismissed by the line below,
        // but only from the screen — could resend a message that is no longer in
        // any transcript, and the model would answer a turn with no history.
        lastTurn = null
        _chat.value = state.cleared()
        if (designId.isNotBlank()) {
            viewModelScope.launch(Dispatchers.IO + NonCancellable) { chats.delete(designId) }
        }
        return true
    }

    /**
     * Reads a picked image and holds it ready to send.
     *
     * The decode and the JPEG re-encode happen here rather than at send time so
     * that an unreadable image is reported while the user is still composing, and
     * so the send itself is a request and nothing else. See [readAttachment].
     */
    fun attach(uri: Uri) {
        if (_chat.value.attachments.size >= MAX_ATTACHMENTS) return
        val id = nextAttachmentId++
        val context = getApplication<Application>()
        viewModelScope.launch {
            val image = withContext(Dispatchers.IO) { readAttachment(context, uri, id) }
            if (image == null) {
                _chat.update { it.copy(attachFailed = true) }
            } else {
                _chat.update {
                    if (it.attachments.size >= MAX_ATTACHMENTS) it
                    else it.copy(attachments = it.attachments + image)
                }
            }
        }
    }

    fun removeAttachment(id: Long) {
        _chat.update { it.copy(attachments = it.attachments.filterNot { image -> image.id == id }) }
    }

    /** Acknowledges the "couldn't attach that" notice. */
    fun clearAttachError() {
        _chat.update { it.copy(attachFailed = false) }
    }

    /** Dismisses the failure card without retrying. */
    fun dismissFailure() {
        _chat.update { it.copy(failure = null) }
    }

    /**
     * Sends [text] with whatever is attached, and runs the turn. **False means
     * nothing was sent**, and the caller must keep what the user typed.
     *
     * The refusals are silent — nothing to send, a turn already running, no
     * editor registered — because each is a state the composer already reflects
     * and a toast saying "you have not typed anything" is noise. Returning the
     * answer rather than swallowing it is what stops the sentence being cleared
     * out of the box it was typed in when nothing left with it.
     */
    fun send(text: String): Boolean {
        val state = _chat.value
        val trimmed = text.trim()
        if (trimmed.isEmpty() && state.attachments.isEmpty()) return false
        if (state.sending) return false
        return startTurn(
            PendingTurn(
                text = trimmed,
                imageDataUrls = state.attachments.map { it.dataUrl },
            ),
            record = true,
        )
    }

    /**
     * Sends the last turn again.
     *
     * The user's message is already in the transcript, so it is **not** appended
     * a second time — and it is dropped from the replayed history too, because
     * the orchestrator takes it as the new message and a turn carrying the same
     * user text twice reads to the model as somebody repeating themselves.
     */
    fun retry() {
        val pending = lastTurn ?: return
        if (_chat.value.sending) return
        startTurn(pending, record = false)
    }

    /**
     * Abandons the turn in flight.
     *
     * The socket read cannot be interrupted — it is a blocking read on an IO
     * thread — so this frees the *user*, not the connection: the composer comes
     * back, and the response, if it ever arrives, is dropped on a cancelled
     * coroutine. Anything the turn already applied stays applied, and stays
     * revertible.
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

    /**
     * Puts the design back as it was before the assistant's most recent change.
     *
     * One step, not a stack. A whole-document swap cannot be expressed as the
     * editor's per-frame undo (`TimelineEntry`), so this is a snapshot of the
     * entire [Design] taken immediately before each accepted apply — and keeping
     * every such snapshot for the life of the activity would be keeping a
     * megabyte of arbok frames per turn for an affordance that is only ever used
     * on the change the user just watched happen.
     */
    fun revertLastChange() {
        val snapshot = revertSnapshot ?: return
        val bridge = editor ?: return
        if (bridge.apply(snapshot) is GlyphApplyResult.Applied) {
            revertSnapshot = null
            _chat.update { it.copy(canRevert = false) }
        }
    }

    /**
     * One turn, start to finish.
     *
     * ## Everything here runs on the main thread, deliberately
     *
     * [viewModelScope] dispatches on Main, and the two things that must happen
     * there are the ones this coroutine does directly: reading the editor's live
     * frame buffers ([GlyphEditorBridge.snapshot], via [GlyphToolContext]) and
     * writing a design back to them. The network is not one of them —
     * [GlyphAiClient.respond] moves itself to IO — and text deltas arrive on that
     * IO thread, which is safe because a [MutableStateFlow] update is atomic from
     * any thread and Compose collects on Main.
     *
     * The tools in between (JSON, ASCII previews of up to 16 frames per panel)
     * are main-thread work, and are on the order of a millisecond; putting them
     * on another thread would mean reading a frame buffer a finger might still be
     * painting into.
     */
    private fun startTurn(pending: PendingTurn, record: Boolean): Boolean {
        if (turn?.isActive == true) return false
        val bridge = editor ?: run {
            // Unreachable in practice — the chat is composed inside the editor
            // that registers the bridge — but a turn with nothing to read the
            // canvas from would have no design to talk about at all.
            DebugLog.w("GlyphAi", "no editor is registered; nothing was sent")
            return false
        }
        val context = bridge.snapshot()
        // Captured BEFORE the new message is appended: the orchestrator takes the
        // new turn separately, and history that already contained it would send
        // it twice.
        val history = (if (record) transcript else transcript.withoutTrailingUser()).asInput()
        if (record) {
            appendMessage(
                ChatMessage(
                    role = ChatRole.USER,
                    text = pending.text,
                    atMs = System.currentTimeMillis(),
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
                startedAtMs = System.currentTimeMillis(),
                failure = null,
                // The images have gone into the request; leaving the chips in the
                // composer would invite sending them again with the next message.
                attachments = emptyList(),
            )
        }

        turn = viewModelScope.launch {
            val orchestrator = GlyphAiOrchestrator(
                client = client,
                // Read HERE, per turn, rather than once when this ViewModel was
                // built: the setting exists to rescue a broken model id, and a
                // fix that only took effect after a restart would be a fix the
                // user has no reason to believe worked. Resolving is
                // [ChatWire]'s job — `core/` owns the "blank means the default"
                // rule, this side only owns the prefs read.
                model = ChatWire.resolveModel(
                    Core.prefs.getString(PrefKeys.AI_MODEL, PrefKeys.AI_MODEL_DEF),
                ),
                applyDesign = ::applyFromModel,
                onTrace = ::onTrace,
                onToolNote = ::onToolNote,
            )
            val result = orchestrator.runTurn(
                instructions = GlyphAiPrompt.build(context.design),
                history = history,
                message = ChatMessageItem.user(pending.text, pending.imageDataUrls),
                context = context,
                onTextDelta = { delta -> _chat.update { it.copy(streaming = it.streaming + delta) } },
            )
            when (result) {
                is GlyphAiOrchestrator.TurnResult.Success -> {
                    appendMessage(
                        ChatMessage(
                            role = ChatRole.ASSISTANT,
                            text = result.text,
                            atMs = System.currentTimeMillis(),
                            tools = result.toolNotes,
                        ),
                    )
                    _chat.update { it.turnEnded() }
                }

                is GlyphAiOrchestrator.TurnResult.Failure -> {
                    // Deliberately NOT written to the transcript. A failed turn is
                    // not something the assistant said, and replaying "the service
                    // returned 400" as history teaches the model that this is a
                    // thing it says. The card is transient and offers the two
                    // actions that exist: try again, or copy the detail.
                    DebugLog.w(
                        "GlyphAi",
                        "turn failed (${result.reason}) after ${result.rounds} round(s): ${result.detail}",
                    )
                    _chat.update {
                        it.turnEnded().copy(failure = ChatFailure(result.reason, result.detail))
                    }
                }
            }
        }
        return true
    }

    /**
     * The orchestrator's apply hook.
     *
     * Reads [editor] afresh rather than closing over the bridge the turn started
     * with, so a design produced after a rotation lands on the editor that is
     * actually on screen. Returning a sentence rather than null makes the *model*
     * see a failed tool call — that string is not user-facing copy.
     */
    private fun applyFromModel(design: Design): String? {
        val bridge = editor ?: return "The design editor is no longer open, so nothing was changed."
        return when (val outcome = bridge.apply(design)) {
            is GlyphApplyResult.Applied -> {
                revertSnapshot = outcome.previous
                _chat.update { it.copy(canRevert = true) }
                null
            }

            is GlyphApplyResult.Refused -> outcome.reason
        }
    }

    /**
     * Narrates a step.
     *
     * Streamed text is cleared when a tool starts, because a model that thinks
     * out loud before calling a tool has already produced text that is *not* the
     * answer; leaving it in place would have the final reply appended to a
     * preamble the user was never meant to read as the reply.
     */
    private fun onTrace(trace: ChatTrace) {
        _chat.update {
            when (trace) {
                is ChatTrace.RunningTool -> it.copy(trace = trace, streaming = "")
                else -> it.copy(trace = trace)
            }
        }
    }

    /**
     * Records a finished step, so the user can watch the turn work.
     *
     * Append-only for the length of the turn, and never trimmed: the case this
     * exists for is the model failing validation several times over, and a list
     * that dropped the earlier attempts would hide exactly the thing worth
     * seeing. A turn cannot produce more than
     * [GlyphAiOrchestrator.DEFAULT_MAX_ROUNDS] rounds' worth, so it is bounded
     * without needing to be capped here.
     */
    private fun onToolNote(note: ChatToolNote) {
        _chat.update { it.copy(steps = it.steps + note) }
    }

    /** Appends to the conversation of record and writes it out. */
    private fun appendMessage(message: ChatMessage) {
        transcript = transcript.plus(message)
        _chat.update { it.copy(messages = transcript.messages) }
        val snapshot = transcript
        if (snapshot.designId.isBlank()) return
        // Not a child of viewModelScope's job: a message that reached the screen
        // must reach the disk even if the editor is closing on the same frame.
        viewModelScope.launch(Dispatchers.IO + NonCancellable) { chats.save(snapshot) }
    }

    /** This transcript without a trailing user turn; see [retry]. */
    private fun ChatTranscript.withoutTrailingUser(): ChatTranscript =
        if (messages.lastOrNull()?.role == ChatRole.USER) copy(messages = messages.dropLast(1)) else this

    /** A turn that has been composed, and can be composed again by [retry]. */
    private data class PendingTurn(val text: String, val imageDataUrls: List<String>)

    override fun onCleared() {
        job?.cancel()
        job = null
        turn?.cancel()
        turn = null
        releaseCallbackPort()
        super.onCleared()
    }

    private fun fail(failure: SignInFailure, e: Exception) {
        DebugLog.w("GlyphAi", "sign-in failed: ${e.javaClass.simpleName}: ${e.message}")
        _state.value = authState(
            signedIn = tokens.isSignedIn,
            failure = failure,
            detail = e.message,
        )
    }

    /**
     * A state carrying the consent flag forward.
     *
     * Every transition above rebuilds the whole object rather than copying, which
     * is deliberate — it makes "signed in and still waiting for the browser"
     * unrepresentable — but consent is orthogonal to all of them and must not be
     * dropped by any. One function, so it cannot be forgotten in the next one.
     */
    private fun authState(
        signedIn: Boolean,
        busy: Boolean = false,
        failure: SignInFailure? = null,
        detail: String? = null,
    ): GlyphAiAuthState = GlyphAiAuthState(
        signedIn = signedIn,
        busy = busy,
        failure = failure,
        detail = detail,
        consented = _state.value.consented,
    )

    private fun classify(e: Exception): SignInFailure = when (e) {
        is SocketTimeoutException -> SignInFailure.TIMED_OUT
        is BindException -> SignInFailure.PORT_BUSY
        else -> SignInFailure.FAILED
    }

    /**
     * Unblocks `ServerSocket.accept()` so the callback server can close its
     * socket and free port 1455. See this class's KDoc for why this is the only
     * way to do it, and why it is not a coroutine.
     */
    private fun releaseCallbackPort() {
        val port = callbackPort ?: return
        Thread({
            try {
                Socket().use { it.connect(InetSocketAddress(LOOPBACK, port), POKE_TIMEOUT_MS) }
            } catch (_: IOException) {
                // Nothing was listening, which is the outcome this wants anyway.
            }
        }, "oauth-callback-release").start()
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val POKE_TIMEOUT_MS = 1_000

        /**
         * How many images may ride on one message.
         *
         * Each is up to 1024 px of JPEG as base64 — a few hundred kilobytes of
         * request body — and the model is being asked to turn them into a 13x13
         * drawing. Four is already more reference than that task can use, and the
         * cap is what stops a long-press multi-select from building a ten-megabyte
         * request on a phone connection.
         */
        const val MAX_ATTACHMENTS = 4

        /**
         * Read out of [OAUTH_REDIRECT_URI] rather than written down again: the
         * port the server binds and the port the redirect names are the same
         * fact, and a second copy of it is a second thing to get wrong.
         */
        val callbackPort: Int? = runCatching { Uri.parse(OAUTH_REDIRECT_URI).port.takeIf { it > 0 } }
            .getOrNull()
    }
}
