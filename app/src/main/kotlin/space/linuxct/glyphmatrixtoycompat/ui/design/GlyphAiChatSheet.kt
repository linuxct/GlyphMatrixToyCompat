package space.linuxct.glyphmatrixtoycompat.ui.design

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import space.linuxct.glyphmatrixtoycompat.R
import space.linuxct.glyphmatrixtoycompat.ai.AttachedImage
import space.linuxct.glyphmatrixtoycompat.ai.ChatFailure
import space.linuxct.glyphmatrixtoycompat.ai.canReset
import space.linuxct.glyphmatrixtoycompat.core.ai.ChatMessage
import space.linuxct.glyphmatrixtoycompat.core.ai.ChatRole
import space.linuxct.glyphmatrixtoycompat.core.ai.ChatToolNote
import space.linuxct.glyphmatrixtoycompat.core.ai.ChatTrace
import space.linuxct.glyphmatrixtoycompat.core.ai.GlyphAiOrchestrator
import space.linuxct.glyphmatrixtoycompat.core.ai.GlyphAiTools
import space.linuxct.glyphmatrixtoycompat.ui.DIALOG_VERTICAL_MARGIN
import space.linuxct.glyphmatrixtoycompat.ui.MotionDialog

/**
 * The conversation about one design: the assistant, full screen.
 *
 * ## Why full screen, when every other pop-up here is a card
 *
 * A card is the right shape for a question with two answers. This is a thread
 * that scrolls, a keyboard, an image picker and a reply arriving a word at a
 * time — on a phone that is a screen's worth of content, and a 320 dp card would
 * spend most of its height on the editor showing through behind it. It is still
 * a [MotionDialog], on the same springs as everything else, with `fullScreen`
 * asking for the window to be measured against the display rather than against
 * `config_prefDialogWidth` — and, with it, for the window to stop fitting system
 * windows so that the keyboard arrives here as an inset instead of as a resize.
 * The [safeDrawingPadding] below is then the only thing accounting for the IME;
 * see `MotionDialog`'s `fullScreen` for what a second accounting looked like.
 *
 * It is a dialog and not an Activity for the reason `GlyphAiConsentDialog` gives:
 * pushing an activity would tear down the editor beneath it — flushing a save,
 * dropping the live matrix preview, reloading the design on the way back — and
 * the assistant's whole job is to change the canvas that is on screen *now*.
 *
 * ## Nothing durable lives here
 *
 * The transcript, the turn in flight, the attachments and the undo snapshot are
 * all in the activity-scoped `GlyphAiViewModel`. This function holds two pieces
 * of throwaway UI state, both in a `rememberSaveable`: the half-typed message,
 * and whether the reset confirmation is up. So rotating the phone while
 * the model is answering keeps the answer arriving, keeps the attached photos and
 * keeps the sentence being typed — and closing the modal mid-turn does not
 * cancel it either, which matters because the turn may be *applying a design*.
 *
 * ## Monochrome
 *
 * No new hues, as everywhere else in this app: the user's turn is
 * `surfaceVariant`, the assistant's is the page, the trace and the tool notes are
 * `onSurfaceVariant`, and the only other colour is the theme's `error` on a
 * failure. The distinction that carries the meaning is alignment and weight, not
 * hue.
 */
@Composable
internal fun GlyphAiChatSheet(designId: String, onDismiss: () -> Unit) {
    val viewModel = glyphAiViewModel()
    // Held as the `State` as well as through the delegate: the auto-scroll below
    // reads the conversation from inside a `snapshotFlow`, which needs a state
    // object to observe rather than a value captured at composition time.
    val chatState = viewModel.chat.collectAsStateWithLifecycle()
    val chat by chatState
    val context = LocalContext.current

    // The one piece of state this composable owns. `rememberSaveable` so a
    // rotation mid-sentence does not eat the sentence.
    var draft by rememberSaveable { mutableStateOf("") }

    // Reading the conversation is the first thing in the process to touch
    // credential-protected chat storage, and it is deliberately here — in a
    // composable that only ever runs from an Activity — rather than anywhere
    // `Core.init` can reach. See `ChatStore` on Direct Boot.
    LaunchedEffect(designId) { viewModel.openChat(designId) }

    // No permission, no dialog, no manifest entry: the photo picker returns a
    // one-shot read grant for exactly the image the user chose.
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uri?.let { viewModel.attach(it) } }

    MotionDialog(onDismiss = onDismiss, fullScreen = true) { dismiss ->
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                // Whether the "reset this chat?" confirmation is up. Saveable
                // like the draft: a rotation with the question on screen should
                // leave the question on screen, not silently answer it.
                var resetAsked by rememberSaveable { mutableStateOf(false) }

                ChatHeader(
                    onClose = dismiss,
                    canReset = chat.canReset(),
                    onReset = { resetAsked = true },
                    onSignOut = {
                        // Closed outright rather than through the exit
                        // transition: the moment the token goes, the gate falls
                        // back to the sign-in door and would replace this window
                        // with the sign-in dialog mid-animation — so somebody who
                        // asked to sign out would be looking at a "Sign in"
                        // button. Cutting straight out is the answer they asked
                        // for; sparkles offers the sign-in again whenever they
                        // want it.
                        viewModel.signOut()
                        onDismiss()
                    },
                )

                // Its own window, above this one, exactly as the design list's
                // delete confirmation is: a destructive action gets a question
                // with the answer spelled out, and this one's job is to say the
                // thing a person is right to worry about — the artwork stays.
                if (resetAsked) {
                    ResetChatDialog(
                        onDismiss = { resetAsked = false },
                        onConfirm = {
                            resetAsked = false
                            viewModel.resetChat()
                        },
                    )
                }

                val listState = rememberLazyListState()
                // How tall the pinned bottom stack currently is, in pixels, as it
                // last measured. See [LIST_VERTICAL_PADDING].
                var bottomBarPx by remember { mutableIntStateOf(0) }
                val bottomBarHeight = with(LocalDensity.current) { bottomBarPx.toDp() }

                // Two effects, because the two things that grow the thread grow it
                // at different rates. A new message, a step or a failure is an
                // event and is worth animating to. A text delta is thirty events a
                // second, and animating to each would be thirty animations
                // cancelling one another — so those *snap*.
                //
                // Both land on the END of the thread rather than on the top of its
                // last item, and for the same reason: a reply being written is
                // read at its last line, and so is a live step list that is still
                // growing. See [ThreadEnd] for why both are driven by a
                // [snapshotFlow] rather than by the effect's keys.
                //
                // [bottomBarPx] is one of the trigger fields because the bottom
                // stack changing height moves the floor out from under a list that
                // is already scrolled to its end: the undo banner appearing, or the
                // composer growing a line, would otherwise leave the newest line
                // sitting behind them until the next message arrived.
                LaunchedEffect(listState) {
                    snapshotFlow {
                        val info = listState.layoutInfo
                        val state = chatState.value
                        ThreadEnd(
                            lastIndex = info.totalItemsCount - 1,
                            viewportHeight = info.viewportSize.height,
                            messages = state.messages.size,
                            steps = state.steps.size,
                            trace = state.trace,
                            failure = state.failure,
                            bottomBarPx = bottomBarPx,
                        )
                    }.collectLatest { end ->
                        if (end.lastIndex < 0) return@collectLatest
                        // Past the end, and let the list clamp. See [ThreadEnd].
                        listState.animateScrollToItem(end.lastIndex, end.viewportHeight)
                        // ...and then SETTLE, because the trigger above cannot fire
                        // a second time for this growth. Every step row lives in ONE
                        // list item, so appending one leaves the item COUNT alone
                        // and only makes that item taller. By the time the taller
                        // row is measured, every field of [ThreadEnd] is identical
                        // to the one just emitted — `steps` included — so no further
                        // emission is possible, and the scroll that already ran was
                        // aimed at the shorter layout. The growth then pushes the
                        // live line back under the composer with nothing left to
                        // correct it, which is the "still not scrolling" report.
                        //
                        // Waiting a frame lets that growth measure;
                        // `canScrollForward` then answers the only question that
                        // matters — is anything still below? — without this needing
                        // to know what grew or by how much. It costs nothing when
                        // the content was already stable: the first check is false
                        // and the loop leaves immediately.
                        var settles = 0
                        while (settles++ < SETTLE_PASSES) {
                            withFrameNanos {}
                            if (!listState.canScrollForward) break
                            listState.animateScrollToItem(
                                listState.layoutInfo.totalItemsCount - 1,
                                end.viewportHeight,
                            )
                        }
                    }
                }
                // The same construction for the reply arriving a word at a time.
                // The streaming text lives in ONE item, so its arrival does not
                // change the item count — only its own height — which is why it
                // needs a trigger of its own. The count still has to be the
                // measured one: the first delta of a reply lands in the same
                // moment the item is created, which is exactly when a directly
                // read `totalItemsCount` is a frame behind.
                LaunchedEffect(listState) {
                    snapshotFlow {
                        listState.layoutInfo.totalItemsCount - 1 to chatState.value.streaming.length
                    }.collectLatest { (lastIndex, streamed) ->
                        if (lastIndex < 0 || streamed == 0) return@collectLatest
                        listState.scrollToItem(lastIndex, PAST_THE_END)
                    }
                }

                Box(Modifier.fillMaxWidth().weight(1f)) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = LIST_HORIZONTAL_PADDING,
                            end = LIST_HORIZONTAL_PADDING,
                            top = LIST_VERTICAL_PADDING,
                            // The measured height of what is pinned below,
                            // plus the ordinary gap. See [LIST_VERTICAL_PADDING].
                            bottom = LIST_VERTICAL_PADDING + bottomBarHeight,
                        ),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (chat.restored && chat.messages.isEmpty() && !chat.sending) {
                            item("empty") { ChatEmptyState() }
                        }
                        // Keyed by position rather than by content: the list only
                        // ever grows at the end, and two identical messages are two
                        // items that must not share a key.
                        chat.messages.forEachIndexed { index, message ->
                            item(key = "m$index") { ChatBubble(message) }
                        }
                        // The steps come before the reply and before the live line,
                        // because that is the order they happened in: the assistant
                        // draws and checks, then it says what it did.
                        if (chat.steps.isNotEmpty()) {
                            item("steps") { StepList(chat.steps) }
                        }
                        if (chat.streaming.isNotEmpty()) {
                            item("streaming") { AssistantText(chat.streaming) }
                        }
                        chat.trace?.let { trace ->
                            item("trace") { TraceRow(trace, chat.startedAtMs) }
                        }
                        chat.failure?.let { failure ->
                            item("failure") {
                                FailureCard(
                                    failure = failure,
                                    onRetry = { viewModel.retry() },
                                    onCopy = { copyToClipboard(context, failure.detail) },
                                    onDismiss = { viewModel.dismissFailure() },
                                )
                            }
                        }
                    }

                    Column(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            // Opaque, and the same colour as the page: the list
                            // now runs underneath this rather than beside it, so
                            // a message scrolled up must not show through the
                            // composer's shoulders.
                            .background(MaterialTheme.colorScheme.background)
                            .onSizeChanged { bottomBarPx = it.height },
                    ) {
                        // Pinned above the composer rather than left in the
                        // scrollback: the change it undoes happened on the canvas
                        // behind this window, and an affordance for it must not be
                        // somewhere the user has to scroll to find. One step only —
                        // see `revertLastChange`.
                        if (chat.canRevert) {
                            RevertBanner(onRevert = { viewModel.revertLastChange() })
                        }

                        if (chat.attachFailed) {
                            NoticeRow(
                                text = stringResource(R.string.ai_chat_attach_failed),
                                onDismiss = { viewModel.clearAttachError() },
                            )
                        }

                        if (chat.attachments.isNotEmpty()) {
                            AttachmentRow(
                                attachments = chat.attachments,
                                onRemove = { viewModel.removeAttachment(it) },
                            )
                        }

                        Composer(
                            draft = draft,
                            onDraftChange = { draft = it },
                            sending = chat.sending,
                            // Never before the transcript has been read: a turn
                            // sent in that window would be sent with no history at
                            // all, and the window is a few milliseconds off one
                            // small file.
                            canSend = chat.restored &&
                                (draft.isNotBlank() || chat.attachments.isNotEmpty()),
                            onAttach = {
                                picker.launch(
                                    PickVisualMediaRequest(
                                        ActivityResultContracts.PickVisualMedia.ImageOnly,
                                    ),
                                )
                            },
                            // The draft is cleared only if the turn actually
                            // started — otherwise the sentence somebody typed would
                            // vanish into a refusal they never saw.
                            onSend = { if (viewModel.send(draft)) draft = "" },
                            onStop = { viewModel.stopTurn() },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Close, the title, and everything else behind one overflow.
 *
 * ## Why a menu rather than a third action in the row
 *
 * "Sign out" was a text button here while it was the only action, and a second
 * one beside it would have left the title — "Design with an assistant", the
 * longest string in this screen — competing with two words of chrome on either
 * side at every font scale. This app already has the answer for a row with more
 * than one action on it: the design list's card uses [Icons.Outlined.MoreVert] and
 * a [DropdownMenu], and both of the items below are things somebody does rarely
 * and on purpose. So the row keeps one icon, and the title keeps the width.
 *
 * Reset comes first because it is about *this* conversation, which is what the
 * screen is; signing out is about the account behind every conversation.
 * [canReset] greys the first one out rather than hiding it, so the action does
 * not appear and vanish depending on whether a turn happens to be running.
 */
@Composable
private fun ChatHeader(
    onClose: () -> Unit,
    canReset: Boolean,
    onReset: () -> Unit,
    onSignOut: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) {
            Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.tut_close))
        }
        Text(
            stringResource(R.string.ai_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f),
        )
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(
                    Icons.Outlined.MoreVert,
                    contentDescription = stringResource(R.string.ai_chat_menu),
                )
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                // The same 16 dp container the design list's overflow menu takes,
                // and for the same reason — `MenuDefaults.shape` is still the
                // pre-expressive 4 dp token. See `CreateTab`'s menu for the
                // argument; the two menus must not disagree about what a menu is.
                shape = MaterialTheme.shapes.large,
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.ai_chat_reset)) },
                    leadingIcon = {
                        Icon(
                            Icons.Outlined.DeleteSweep,
                            contentDescription = null,
                            modifier = Modifier.size(MenuDefaults.LeadingIconSize),
                        )
                    },
                    enabled = canReset,
                    onClick = {
                        menuOpen = false
                        onReset()
                    },
                )
                // The only way out of a sign-in once there is one: the sign-in
                // dialog is no longer reachable when the gate opens straight
                // onto this.
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.ai_sign_out)) },
                    leadingIcon = {
                        Icon(
                            Icons.AutoMirrored.Outlined.Logout,
                            contentDescription = null,
                            modifier = Modifier.size(MenuDefaults.LeadingIconSize),
                        )
                    },
                    onClick = {
                        menuOpen = false
                        onSignOut()
                    },
                )
            }
        }
    }
}

/**
 * The confirmation in front of clearing a conversation.
 *
 * The same shape as `CreateTab`'s `DeleteDesignDialog` — a plain MD3
 * [AlertDialog] with the vertical margin every dialog in this app is capped by —
 * because it is the same kind of moment and there is nothing here worth a second
 * idiom for. What differs is what the body has to say: deleting a design and
 * resetting its chat sit one menu apart, and the sentence that matters is the one
 * promising that this one leaves the drawing exactly where it is.
 */
@Composable
private fun ResetChatDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        modifier = Modifier.padding(vertical = DIALOG_VERTICAL_MARGIN),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ai_chat_reset_title)) },
        text = { Text(stringResource(R.string.ai_chat_reset_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.ai_chat_reset_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.ai_cancel)) }
        },
    )
}

/**
 * What an empty thread says.
 *
 * A prompt rather than a blank: the assistant can do something quite specific and
 * unusual, and somebody who has just drawn 137 dots by hand has no reason to
 * guess that "make it a smiley" is a sentence it understands.
 */
@Composable
private fun ChatEmptyState() {
    Column(Modifier.fillMaxWidth().padding(top = 24.dp)) {
        Text(
            stringResource(R.string.ai_chat_empty_title),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.ai_chat_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** One stored turn: the user's on the right in a bubble, the assistant's on the page. */
@Composable
private fun ChatBubble(message: ChatMessage) {
    when (message.role) {
        ChatRole.USER -> Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.widthIn(max = BUBBLE_MAX_WIDTH),
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    if (message.text.isNotBlank()) {
                        Text(
                            message.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // The images themselves are NOT stored — `ChatTranscript`
                    // keeps a count and nothing else, because several photos per
                    // design in credential-protected storage adds up and the
                    // model has already seen them. So a scrolled-back turn says
                    // how many there were, which is all there is to say truthfully.
                    if (message.imageCount > 0) {
                        if (message.text.isNotBlank()) Spacer(Modifier.height(4.dp))
                        Text(
                            pluralStringResource(
                                R.plurals.ai_chat_photo_count,
                                message.imageCount,
                                message.imageCount,
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        ChatRole.ASSISTANT -> Column(Modifier.fillMaxWidth()) {
            AssistantText(message.text)
            message.tools.forEachIndexed { index, note ->
                Spacer(Modifier.height(4.dp))
                ToolNoteRow(note, attemptOf(message.tools, index))
            }
            // A checkpoint that outlived the process that wrote it — see
            // `ChatTranscript.withPartial`. Said rather than hidden: a reply that
            // stops mid-sentence is otherwise indistinguishable from a model that
            // trailed off, and the honest reading is "the app was killed", which
            // also explains why the design may not have changed.
            if (message.partial) {
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.ai_chat_interrupted),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AssistantText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * The steps of the turn in flight, as they happen.
 *
 * ## Why this exists at all
 *
 * A turn against a reasoning model routinely takes a minute or two, and almost
 * all of that is the model drawing 137 base36 characters, running them past
 * `validate_design`, finding they do not fit, and drawing again. Before this
 * list, none of that was visible: the status line said "Checking the design…"
 * three times in a row with long silences between, which is indistinguishable
 * from a hang. Now each attempt lands as its own line the moment it resolves, so
 * the wait has a *shape* — and a failed check reads as the assistant correcting
 * itself rather than as the same thing going wrong repeatedly.
 *
 * The heading follows the last step for the same reason: when a draft has just
 * failed, saying so is the single most useful sentence on the screen.
 */
@Composable
private fun StepList(steps: List<ChatToolNote>) {
    Column(
        Modifier.fillMaxWidth().animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec()),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            stringResource(
                if (steps.last().ok) R.string.ai_steps_title else R.string.ai_steps_title_retrying,
            ),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        steps.forEachIndexed { index, note -> ToolNoteRow(note, attemptOf(steps, index)) }
    }
}

/**
 * What one tool call did, in the live list and again in the scrollback.
 *
 * This is where "Updated your design" appears — the visible record that the
 * assistant changed something, sitting under the message that announced it, with
 * the revert affordance pinned above the composer.
 *
 * The leading glyph carries the outcome without a second colour: a tick for a
 * step that stuck, a refresh arrow for one that did not. **A refresh arrow rather
 * than a cross**, because a failed validation is not an error the user has to act
 * on — it is the assistant redrawing, and marking it as a failure would make a
 * working turn look like a broken one.
 */
@Composable
private fun ToolNoteRow(note: ChatToolNote, attempt: Int) {
    val text = if (note.ok) okText(note, attempt) else failedText(note, attempt)
    if (text.isBlank()) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (note.ok) Icons.Outlined.Check else Icons.Outlined.Refresh,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * A step that stuck — which for a *check* means the draft was legal, and nothing
 * more than that.
 *
 * The wording matters because of what the list looked like without it: "Checked
 * the drawing — it fits" followed by "Checked draft 7 — didn't fit, redrawing"
 * reads as our own validator approving something and the assistant overruling it.
 * Neither line was wrong; the first one simply implied completion it could not
 * promise. A passing check says the document is legal, not that it is good, and
 * the assistant may look at the preview it gets back and draw another. So the
 * success is numbered like the failure and says so.
 */
@Composable
private fun okText(note: ChatToolNote, attempt: Int): String {
    val res = toolNoteRes(note.name)
    return when {
        note.changedDesign -> stringResource(R.string.ai_tool_applied)
        // A name this build has no string for was written by a newer one; its own
        // stored wording is the only truthful thing left to show.
        res == 0 -> note.label
        else -> {
            val arg = stepOkArg(note, attempt)
            if (arg == null) stringResource(res) else stringResource(res, arg)
        }
    }
}

/** A step that did not, worded so the retry after it reads as progress. */
@Composable
private fun failedText(note: ChatToolNote, attempt: Int): String {
    val res = stepFailureRes(note.name)
    val arg = stepFailureArg(note, attempt)
    return if (arg == null) stringResource(res) else stringResource(res, arg)
}

/**
 * The live "what is it doing" line, with a spinner and, once the wait is long
 * enough to be worth naming, the time it has taken.
 */
@Composable
private fun TraceRow(trace: ChatTrace, startedAtMs: Long) {
    val arg = trace.messageArg()
    val res = trace.messageRes()
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        Text(
            if (arg == null) stringResource(res) else stringResource(res, arg),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        ElapsedLabel(startedAtMs)
    }
}

/**
 * How long the turn has been running, as `m:ss`.
 *
 * ## Why it is quiet
 *
 * The brief for this was "visibly running, not a flashing distraction". So it is
 * a label in the same weight and colour as the status beside it, it ticks once a
 * second rather than animating, and it does not appear at all until
 * [ELAPSED_AFTER_SECONDS] have passed — a three-second turn should not put a
 * stopwatch on the screen, and a turn that is going to take two minutes has
 * already declared itself by then.
 *
 * The elapsed time is derived from a start instant held in the ViewModel rather
 * than counted here, so rotating the phone, or closing and reopening the modal
 * mid-turn, shows the true age of the turn instead of restarting the clock.
 */
@Composable
private fun ElapsedLabel(startedAtMs: Long) {
    if (startedAtMs <= 0L) return
    var now by remember(startedAtMs) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startedAtMs) {
        while (true) {
            delay(TICK_MS)
            now = System.currentTimeMillis()
        }
    }
    val seconds = ((now - startedAtMs) / 1_000L).coerceAtLeast(0L)
    if (seconds < ELAPSED_AFTER_SECONDS) return
    Text(
        stringResource(R.string.ai_elapsed, seconds / 60L, seconds % 60L),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * A turn that did not produce an answer.
 *
 * **The detail is shown verbatim, and that is the point of this card.** Nothing
 * in this repository has ever run against the Codex responses endpoint: the model
 * id, the `originator` header and the token are three unverified things that all
 * fail as "it didn't work", and only the HTTP status and the server's own
 * sentence tell them apart. The headline says which *category* of failure it was;
 * the line under it is what the server said; and "Copy" is there because the
 * person who has to act on it is usually not the person holding the phone.
 */
@Composable
private fun FailureCard(
    failure: ChatFailure,
    onRetry: () -> Unit,
    onCopy: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                stringResource(failure.reason.messageRes()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            if (failure.detail.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    failure.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(2.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onCopy) { Text(stringResource(R.string.ai_chat_copy)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.ai_chat_dismiss)) }
                TextButton(onClick = onRetry) { Text(stringResource(R.string.ai_retry)) }
            }
        }
    }
}

/** "The assistant changed your design" + the one-tap way back. */
@Composable
private fun RevertBanner(onRevert: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.ai_chat_changed),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onRevert) { Text(stringResource(R.string.ai_chat_undo)) }
    }
}

/** A one-line notice with a way to acknowledge it. */
@Composable
private fun NoticeRow(text: String, onDismiss: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.ai_chat_dismiss)) }
    }
}

/** The photos waiting to be sent, each with a way to take it back off. */
@Composable
private fun AttachmentRow(attachments: List<AttachedImage>, onRemove: (Long) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        attachments.forEach { image ->
            Box {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.size(THUMBNAIL_SIZE),
                ) {
                    image.thumbnail?.let { bitmap ->
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                IconButton(
                    onClick = { onRemove(image.id) },
                    modifier = Modifier.align(Alignment.TopEnd).size(24.dp),
                ) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.ai_chat_remove_photo),
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
    }
}

/**
 * Type, attach, send — or stop, while a turn is running.
 *
 * ## The two icons are a matched pair, and neither part of that was free
 *
 * The row is centred, not bottom-aligned. `Alignment.Bottom` lines the icon
 * *buttons'* bottom edges up with the field's, and an [IconButton] is a 48 dp
 * touch target around a 24 dp glyph while the field is 56 dp tall — so both icons
 * sat four pixels low against the one line of text they flank. Centring is what
 * makes them read as beside the field rather than under it, and it is what the
 * rest of this file already does with every row of its own.
 *
 * The stop glyph is [Icons.Outlined.StopCircle] rather than `Stop` for the second
 * half of the same problem. Material's `Stop` is a bare 12x12 square inside a
 * 24 dp box — half the ink of the send arrow it replaces, so the control appeared
 * to shrink at the exact moment it became the only one that does anything.
 * `StopCircle` fills the box the way its neighbours do, and it is still a square
 * inside it, which is what the icon has to say.
 */
@Composable
private fun Composer(
    draft: String,
    onDraftChange: (String) -> Unit,
    sending: Boolean,
    canSend: Boolean,
    onAttach: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onAttach, enabled = !sending) {
            Icon(
                Icons.Outlined.AddPhotoAlternate,
                contentDescription = stringResource(R.string.ai_chat_attach),
            )
        }
        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text(stringResource(R.string.ai_chat_hint)) },
            enabled = !sending,
            // Grows to a few lines and then scrolls: a description of a drawing
            // is a sentence or two, and a field that could swallow the thread
            // would be a field that hid it.
            maxLines = 4,
            shape = RoundedCornerShape(24.dp),
        )
        // One button, two jobs. While a turn is running the only useful action is
        // to stop waiting — the socket read cannot be interrupted, but the user
        // can be released from it; see `stopTurn`.
        if (sending) {
            IconButton(onClick = onStop) {
                Icon(
                    Icons.Outlined.StopCircle,
                    contentDescription = stringResource(R.string.ai_chat_stop),
                )
            }
        } else {
            IconButton(onClick = onSend, enabled = canSend) {
                Icon(
                    Icons.AutoMirrored.Outlined.Send,
                    contentDescription = stringResource(R.string.ai_chat_send),
                )
            }
        }
    }
}

/**
 * The step the assistant is on, as a string resource.
 *
 * **Exhaustive over [ChatTrace] on purpose.** `core/ai` deliberately describes
 * what is happening as a *structure* rather than a sentence — see [ChatTrace] —
 * and this is the one place that structure becomes English. Written as a `when`
 * over a sealed interface with no `else`, so adding a trace kind in `core/`
 * breaks this build rather than shipping a blank line where a status should be.
 *
 * The tool *names* inside [ChatTrace.RunningTool] cannot get the same guarantee —
 * they are strings, and a tool added by a future build is exactly the case that
 * has to keep working — so an unknown one falls through to a format string that
 * says the name, and [messageArg] supplies it.
 */
internal fun ChatTrace.messageRes(): Int = when (this) {
    ChatTrace.Thinking -> R.string.ai_trace_thinking
    ChatTrace.Processing -> R.string.ai_trace_processing
    is ChatTrace.RunningTool -> when (name) {
        GlyphAiTools.GET_CURRENT_DESIGN -> R.string.ai_trace_reading
        GlyphAiTools.APPLY_DESIGN -> R.string.ai_trace_applying
        GlyphAiTools.VALIDATE_DESIGN -> R.string.ai_trace_checking
        else -> R.string.ai_trace_running
    }
}

/**
 * The format argument [messageRes] needs, or null when it needs none.
 *
 * Only the unknown-tool case takes one. Underscores become spaces because the
 * name is a wire identifier and this is a sentence.
 */
internal fun ChatTrace.messageArg(): String? = when (this) {
    is ChatTrace.RunningTool -> when (name) {
        GlyphAiTools.GET_CURRENT_DESIGN,
        GlyphAiTools.APPLY_DESIGN,
        GlyphAiTools.VALIDATE_DESIGN,
        -> null

        else -> name.replace('_', ' ')
    }

    else -> null
}

/**
 * What a completed tool call is called, past tense, or 0 for a tool this build
 * does not know — in which case the transcript's own stored label is used, since
 * a conversation written by a newer build must still say something true.
 */
internal fun toolNoteRes(name: String): Int = when (name) {
    GlyphAiTools.GET_CURRENT_DESIGN -> R.string.ai_tool_read
    GlyphAiTools.APPLY_DESIGN -> R.string.ai_tool_applied
    GlyphAiTools.VALIDATE_DESIGN -> R.string.ai_tool_checked
    else -> 0
}

/**
 * What a tool call that *failed* is called.
 *
 * Separate from [toolNoteRes] rather than a suffix on it, because the two say
 * different kinds of thing. A step that stuck is named after the tool ("Checked
 * the drawing"); a step that did not is named after what happens next ("Draft 3
 * didn't pass its checks — redrawing"), because a run of failed checks is the
 * assistant working, and copy that reported it as three identical errors would
 * make a turn that is going fine look like one that is stuck.
 *
 * Unlike [toolNoteRes] there is no 0 case: a tool this build has never heard of
 * still failed, and the generic wording carries the stored label.
 */
internal fun stepFailureRes(name: String): Int = when (name) {
    GlyphAiTools.GET_CURRENT_DESIGN -> R.string.ai_step_read_failed
    GlyphAiTools.APPLY_DESIGN -> R.string.ai_step_apply_failed
    GlyphAiTools.VALIDATE_DESIGN -> R.string.ai_step_check_failed
    else -> R.string.ai_tool_failed
}

/**
 * The format argument [toolNoteRes] needs for a step that *succeeded*, or null
 * when it needs none.
 *
 * Only the check takes one, and it takes the same number its failure would: a
 * turn that reads "Draft 3 — fits the panel, may still be redrawn" and then
 * "Checked draft 4 — didn't fit, redrawing" is legible as a sequence of drafts,
 * which is what it is and what it did not look like before. Applying is
 * not numbered here at all — a successful apply is [ChatToolNote.changedDesign]
 * and says "Updated your design", which needs no attempt number to be understood
 * — and reading the design is not a draft.
 */
internal fun stepOkArg(note: ChatToolNote, attempt: Int): Any? = when (note.name) {
    GlyphAiTools.VALIDATE_DESIGN -> attempt
    else -> null
}

/**
 * The format argument [stepFailureRes] needs, or null when it needs none.
 *
 * The two drawing tools take the attempt number, which is the whole point of the
 * wording; reading the design takes none, since re-reading is not a draft; and an
 * unknown tool takes whatever the transcript called it.
 */
internal fun stepFailureArg(note: ChatToolNote, attempt: Int): Any? = when (note.name) {
    GlyphAiTools.GET_CURRENT_DESIGN -> null
    GlyphAiTools.APPLY_DESIGN, GlyphAiTools.VALIDATE_DESIGN -> attempt
    else -> note.label
}

/**
 * Which attempt the note at [index] is, counting only calls to the same tool.
 *
 * "Draft 3 didn't pass its checks" is the sentence that turns a stalled-looking
 * wait into visible progress, and the number behind it is not stored anywhere —
 * [ChatToolNote] is deliberately a record of what happened, not of how many times
 * it had happened before. It is derived here instead, from a list that is already
 * in order, so the live list and the scrollback of the same turn agree by
 * construction.
 */
internal fun attemptOf(notes: List<ChatToolNote>, index: Int): Int =
    notes.take(index + 1).count { it.name == notes[index].name }

/**
 * Why a turn produced no answer, in the categories the orchestrator distinguishes
 * — each of which the user can do something different about.
 *
 * Exhaustive over the enum, for [messageRes]'s reason.
 *
 * The two stuck cases are worded apart on purpose: one of them left the canvas
 * exactly as it was, and the other has just changed it. Telling somebody the
 * assistant "kept working without answering" while the undo banner sits over a
 * drawing that was not there a minute ago is the confusing half of a true
 * sentence.
 */
internal fun GlyphAiOrchestrator.TurnResult.Reason.messageRes(): Int = when (this) {
    GlyphAiOrchestrator.TurnResult.Reason.TRANSPORT -> R.string.ai_chat_error_transport
    GlyphAiOrchestrator.TurnResult.Reason.SERVER -> R.string.ai_chat_error_server
    GlyphAiOrchestrator.TurnResult.Reason.STUCK -> R.string.ai_chat_error_stuck
    GlyphAiOrchestrator.TurnResult.Reason.STUCK_SALVAGED -> R.string.ai_chat_error_stuck_salvaged
    GlyphAiOrchestrator.TurnResult.Reason.EMPTY -> R.string.ai_chat_error_empty
}

/**
 * Where the end of the thread is, **as the list last measured it**, plus
 * everything that should send the view back to it.
 *
 * ## What was wrong with `animateScrollToItem(last)`
 *
 * That call aligns the item's *top* with the top of the viewport. For a short
 * bubble the difference never shows, but the live block of a tool turn is one
 * item — heading, a step list that grows a line at a time, the status row and the
 * elapsed clock — and once it is taller than what is left of the screen, putting
 * its top at the top leaves its bottom under the composer. That is the half-cut
 * "Reading what came back… 0:50" the screenshot caught, and why dragging the list
 * a few pixels fixed it: the user was finishing a scroll that had stopped early.
 *
 * ## Asking for a position past the end, and letting the list clamp
 *
 * The `scrollOffset` argument moves the target *up* by that many pixels, so a
 * request larger than what remains is a request to scroll beyond the end of the
 * content — which `LazyListState` resolves by landing exactly on the end,
 * including the bottom `contentPadding` that accounts for the pinned composer.
 * So the last line ends up clear of the bottom bar whether the last item is a
 * tall live block or a one-line message, and it can never over-scroll into blank
 * space: past the end simply *is* the end.
 *
 * ## Why one viewport and not [PAST_THE_END]
 *
 * The streaming effect snaps, so it can hand `scrollToItem` an absurd offset and
 * pay nothing for it. The animated one is given the distance as its target: with
 * 100,000 px the spring crosses the whole screen inside the first frame and the
 * "animation" becomes a jump. One viewport is the smallest request that is always
 * past the end — nothing follows the last item but the list's own padding — and
 * it springs across a screen the way every other motion in this app does.
 *
 * ## THE BUG THIS TYPE EXISTS FOR: `layoutInfo` is a measurement, not a plan
 *
 * All of the above was already true and the list still stopped one item short,
 * because the numbers it was given were the *previous* frame's. `layoutInfo` is
 * written by the measure pass, and a `LaunchedEffect` keyed on the conversation
 * runs as soon as that conversation changes — before the new step row has been
 * measured, and often before it has been composed. So `totalItemsCount - 1` named
 * the item *before* the new one, the scroll landed one row high, and the live
 * status line stayed under the composer until the user dragged it. Reading
 * `viewportSize` from the same stale snapshot has the same flaw.
 *
 * The fix is to stop treating an event as the trigger and let the **layout** be
 * the trigger. Both effects build this record inside a `snapshotFlow`, which
 * re-runs its block whenever any state it read is invalidated and emits only when
 * the result differs from the last one it emitted. `LazyListState.layoutInfo` is
 * a `mutableStateOf(…, neverEqualPolicy())` assigned at the end of every measure,
 * so every measure invalidates the block and the block always re-reads the
 * newest measurement. A stale count therefore cannot be the *last* thing the flow
 * says: appending an item forces a measure, the measure forces an emission, and
 * that emission carries the count the list actually has. [lastIndex] and
 * [viewportHeight] come out of one `layoutInfo` read, so they are two facts about
 * one measurement rather than two reads that might straddle a frame.
 *
 * The conversation's own counters stay in the record because they are what should
 * *provoke* a scroll — a trace line changing without changing the item count still
 * moves the last row. Their emission may carry a not-yet-remeasured count, and
 * that is harmless: the measure that follows emits again, `collectLatest` cancels
 * the half-finished animation and retargets, and the final target is always the
 * measured one. That is also what makes a burst of step rows behave — every row
 * retargets the same spring instead of queueing an animation behind it.
 *
 * Only the fields below are extracted from `layoutInfo`, and neither of them
 * changes while a scroll is running (scrolling moves offsets, not the item count
 * or the viewport). So the animation cannot feed itself a new emission and
 * restart forever.
 */
private data class ThreadEnd(
    /** The index of the last item, or -1 when the list has nothing in it yet. */
    val lastIndex: Int,
    /** The viewport, which is the smallest offset that is always past the end. */
    val viewportHeight: Int,
    val messages: Int,
    val steps: Int,
    val trace: ChatTrace?,
    val failure: ChatFailure?,
    /** The measured height of the pinned bottom stack. See [LIST_HORIZONTAL_PADDING]. */
    val bottomBarPx: Int,
)

/**
 * Puts [text] on the clipboard.
 *
 * The platform manager rather than Compose's `LocalClipboardManager`, which is
 * deprecated in this Compose version, or `LocalClipboard`, whose replacement API
 * is `suspend` and would need a scope to copy one string. Android 13 and up shows
 * its own confirmation, so there is nothing to say afterwards.
 */
private fun copyToClipboard(context: Context, text: String) {
    val manager = context.getSystemService(ClipboardManager::class.java) ?: return
    manager.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, text))
}

private const val CLIP_LABEL = "Glyph assistant error"

/**
 * The thread's own padding — and the reason its bottom half is *measured* rather
 * than written down.
 *
 * The list runs the whole height of the window with the composer, the attachment
 * chips and the "Undo AI change" banner pinned over its foot, so its last line is
 * only visible if it is padded clear of them. None of those three has a height
 * this file can know: the banner and the chips come and go, and the composer grows
 * a line at a time as somebody types and again at every font scale. A written-down
 * number was 12 dp against a stack four times that, which is the few pixels of the
 * live status line the user saw cut off.
 *
 * So the stack reports its size ([onSizeChanged], converted through the density)
 * and the list adds it to this. **Deliberately not a `Scaffold`**, which is the
 * obvious way to get the same thing: `Scaffold` is a `SubcomposeLayout` that
 * subcomposes its content inside its own measure block, and that was traced to a
 * serious scrolling stutter and removed from every scroll-heavy screen in this
 * app. Measuring one bar and passing a `Dp` costs a layout pass and no
 * subcomposition at all.
 */
private val LIST_HORIZONTAL_PADDING = 16.dp

/** The gap above the first message, and below the last one. See [LIST_HORIZONTAL_PADDING]. */
private val LIST_VERTICAL_PADDING = 12.dp

/** A bubble never spans the full width; the alignment is what says who spoke. */
private val BUBBLE_MAX_WIDTH = 300.dp

private val THUMBNAIL_SIZE = 56.dp

/**
 * A scroll offset larger than any bubble, used to land on the *end* of the item
 * being streamed into rather than its start. `LazyListState` clamps it to the end
 * of the content, which is exactly the intent.
 */
private const val PAST_THE_END = 100_000

/**
 * How many times a pin-to-the-bottom may re-aim itself while the content is still
 * growing underneath it.
 *
 * A bound rather than `while (canScrollForward)` on its own: this runs inside a
 * `collectLatest`, so a loop that could never satisfy its condition — a list that
 * reports more content than it will scroll to, at some future layout bug — would
 * spin for as long as the turn lasts rather than failing visibly. Four passes is
 * far more than the one the real case needs (a single step row appearing after the
 * scroll was aimed), and anything still growing after four has produced another
 * event of its own by then, which re-triggers the whole effect anyway.
 */
private const val SETTLE_PASSES = 4

/** How often the elapsed label re-reads the clock. Once a second, to the second. */
private const val TICK_MS = 1_000L

/**
 * How long a turn must have run before its age is shown.
 *
 * Short enough that the slow turns this exists for are covered from early on,
 * long enough that an ordinary quick answer never flashes a stopwatch on and off
 * again.
 */
private const val ELAPSED_AFTER_SECONDS = 3L
