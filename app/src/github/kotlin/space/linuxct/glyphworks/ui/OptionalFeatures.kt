package space.linuxct.glyphworks.ui

import android.content.Context
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import space.linuxct.glyphworks.ai.DesignChatCleanup
import space.linuxct.glyphworks.designs.DesignStore
import space.linuxct.glyphworks.update.UpdateCheckWorker

/**
 * Everything the `play` flavour does not ship, behind one seam per entry point.
 *
 * ## Why a seam rather than a flag
 *
 * The Play build must not merely *disable* the assistant and the update checker —
 * their code must not be in the APK at all, because that is what turns three Play
 * filings into nothing: no foreground-service justification, no data-collection
 * entry on the Data Safety form, and no reviewer credentials for a sign-in. A
 * runtime flag leaves the classes, the strings and the `INTERNET` permission in
 * the binary, and a reviewer reads the binary.
 *
 * So each of these is declared **twice**, with the same signature: really here,
 * and as nothing in `src/play`. `main` calls them and never names
 * `GlyphEditorBridge`, `GlyphApplyResult`, `AiGate`, `GlyphToolContext`,
 * `ChatWire`, `ReasoningEffort` or `UpdateChecker`.
 *
 * Keep the two files in step. There is no compiler check that they agree — a
 * signature that drifts fails only when the other flavour is built, which is why
 * CI builds both.
 */

/**
 * The assistant's entry point in the editor's top bar: the sparkles button and,
 * behind it, the whole feature.
 *
 * **One seam, not two**, even though the button lives in the app bar and the
 * chat is a full-screen surface. `MotionDialog` is a platform `Dialog`, so it
 * renders in its own window regardless of where it is composed — which means the
 * button, the open/closed state, the ViewModel, the `GlyphEditorBridge`
 * registration and all three dialogs can sit inside this one call, in the `Row`.
 * Splitting it would have put an AI-typed bridge in `main`.
 */
@Composable
internal fun RowScope.AssistantAction(state: space.linuxct.glyphworks.ui.design.EditorState, onEdit: () -> Unit) {
    AssistantActionImpl(state, onEdit)
}

/** The Settings page's "AI settings" section: header, card and hint. */
@Composable
internal fun ColumnScope.AiSettingsSection() {
    AiSettingsSectionImpl()
}

/** The Settings page's "Check for updates" row. */
@Composable
internal fun UpdateSettingsRow() {
    UpdateSettingsRowImpl()
}

/** Arms the daily background release check. Called once from `MainActivity`. */
internal fun scheduleUpdateCheck(context: Context) {
    UpdateCheckWorker.schedule(context)
}

/**
 * Wires design deletion to chat deletion.
 *
 * The arrow was already inverted for this: `designs/` exposes a deletion
 * listener so it never imports `ai/`, and `DesignChatCleanup`'s KDoc says in as
 * many words that removing the assistant should cost one line in `Core.init`.
 * This is that line.
 */
internal fun installOptionalHooks(app: Context, store: DesignStore) {
    DesignChatCleanup.install(app, store)
}

/**
 * The onboarding key page's "Sideloaded this APK?" card, explaining Android's
 * restricted-setting block and offering App info.
 */
@Composable
internal fun ColumnScope.SideloadHelpCard() {
    SideloadHelpCardImpl()
}

/** The Tutorials tab's "Allow restricted settings" row and its walkthrough. */
internal fun SectionCardScope.restrictedSettingsTutorialItem() {
    item { RestrictedSettingsTutorialRow() }
}
