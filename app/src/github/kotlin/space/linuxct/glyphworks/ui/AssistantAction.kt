package space.linuxct.glyphworks.ui

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import space.linuxct.glyphworks.R
import space.linuxct.glyphworks.ai.AiGate
import space.linuxct.glyphworks.ai.GlyphApplyResult
import space.linuxct.glyphworks.ai.GlyphEditorBridge
import space.linuxct.glyphworks.ai.aiGate
import space.linuxct.glyphworks.core.ai.GlyphToolContext
import space.linuxct.glyphworks.core.design.Design
import space.linuxct.glyphworks.ui.design.EditorState
import space.linuxct.glyphworks.ui.design.GlyphAiChatSheet
import space.linuxct.glyphworks.ui.design.GlyphAiConsentDialog
import space.linuxct.glyphworks.ui.design.GlyphAiSignInDialog
import space.linuxct.glyphworks.ui.design.glyphAiViewModel

/**
 * The design assistant, entire: the sparkles button, the gate, the bridge onto
 * the canvas and all three dialogs.
 *
 * ## Why it is one composable rather than several
 *
 * This used to be spread through `EditorScaffold` — a `rememberSaveable` flag
 * near the top, a ViewModel, a `GlyphEditorBridge` built with `remember`, a
 * `DisposableEffect` registering it, an `IconButton` in the app bar's `actions`
 * row, and a `when (aiGate(...))` block hundreds of lines further down beside
 * the other dialogs. Six places, all of them naming AI types, in a file the Play
 * flavour has to compile without the assistant.
 *
 * Gathering it here costs nothing, because **a dialog is its own window**:
 * `MotionDialog` adds to the window manager rather than to the layout, so
 * composing the chat from inside the app bar's `Row` puts it on screen exactly
 * where composing it from the `Scaffold` did. That is the whole trick that lets
 * the seam be one function, and it is why `main` never names
 * [GlyphEditorBridge], [GlyphApplyResult], [AiGate] or [GlyphToolContext].
 *
 * [onEdit] is the editor's ordinary "something changed" callback — the one that
 * arms the debounced save and claims the panel. The assistant deliberately goes
 * through it rather than around it: a design the assistant drew into is a design
 * that was edited, and every guarantee in `DesignEditorActivity`'s KDoc then
 * applies to an assistant's change unaltered.
 */
@Composable
internal fun RowScope.AssistantActionImpl(state: EditorState, onEdit: () -> Unit) {
    // Only the DIALOG's visibility lives here — the flow it starts is in an
    // activity-scoped ViewModel, so rotating the phone while the browser is in
    // front does not abandon a bound socket.
    //
    // `rememberSaveable` is the other half of surviving rotation: a sign-in
    // leaves for the browser and comes back minutes later, and the phone may
    // well have been turned over in between. A plain `remember` would keep the
    // JOB alive and still drop the dialog that reports it finished.
    var aiOpen by rememberSaveable { mutableStateOf(false) }

    // Held outside the chat modal so a turn survives the modal being closed, and
    // so the gate can be decided without composing anything.
    val ai = glyphAiViewModel()
    val aiState by ai.state.collectAsStateWithLifecycle()

    // How the assistant reads and writes the canvas.
    //
    // Registered rather than injected: the ViewModel outlives this composition,
    // and a turn that started before a rotation must apply its design to the
    // editor that exists *after* it. Keyed on the state, so a new editor replaces
    // the old registration; `clearEditor` is identity-checked for the frame in
    // which both compositions exist.
    val bridge = remember(state) {
        object : GlyphEditorBridge {
            override fun snapshot(): GlyphToolContext = GlyphToolContext(
                design = state.composed(),
                openVariant = state.codename,
                selectedFrameIndex = state.selectedIndex,
            )

            override fun apply(design: Design): GlyphApplyResult {
                // No `recordUndo`: the way back from a chat turn is the revert
                // banner on the message that caused it, which is what `previous`
                // is handed to. See `EditorState.replaceDesign` for why one
                // affordance rather than two.
                val previous = state.replaceDesign(design)
                    // Model-facing, not user-facing: the orchestrator hands this
                    // back as a failed tool result so the model does not go on to
                    // describe a change that did not happen.
                    ?: return GlyphApplyResult.Refused(
                        "The editor could not open that document, so nothing was changed.",
                    )
                onEdit()
                return GlyphApplyResult.Applied(previous)
            }
        }
    }
    DisposableEffect(bridge) {
        ai.setEditor(bridge)
        onDispose { ai.clearEditor(bridge) }
    }

    // Sparkles is the app-wide convention for "an assistant does this", so the
    // icon carries the meaning without a label. It sits where somebody is looking
    // when they realise placing 137 dots by hand is slow.
    IconButton(onClick = { aiOpen = true }) {
        Icon(
            Icons.Outlined.AutoAwesome,
            contentDescription = stringResource(R.string.ai_action),
        )
    }

    // The three doors, in the order [aiGate] puts them: nothing leaves the device
    // before the disclosure, and nothing reaches OpenAI before the sign-in. The
    // gate is re-evaluated on every state change, so accepting the disclosure
    // moves straight on to the sign-in and completing the sign-in opens the chat.
    if (aiOpen) {
        when (aiGate(consented = aiState.consented, signedIn = aiState.signedIn)) {
            AiGate.CONSENT -> GlyphAiConsentDialog(
                onAccept = { ai.acceptConsent() },
                onDismiss = { aiOpen = false },
            )

            AiGate.SIGN_IN -> GlyphAiSignInDialog(onDismiss = { aiOpen = false })

            AiGate.CHAT -> GlyphAiChatSheet(
                designId = state.design.id,
                onDismiss = { aiOpen = false },
            )
        }
    }
}
