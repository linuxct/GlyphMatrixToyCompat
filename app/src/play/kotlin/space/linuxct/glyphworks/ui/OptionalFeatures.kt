package space.linuxct.glyphworks.ui

import android.content.Context
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import space.linuxct.glyphworks.designs.DesignStore

/**
 * The `play` flavour's half of the optional-feature seam: nothing, on purpose.
 *
 * The Google Play build ships **without the design assistant and without the
 * update checker**, and not by disabling them — `src/github` holds `ai/`,
 * `core/ai/` and `update/`, and this source set does not, so none of that code
 * reaches the APK. That absence is what lets the Play listing declare no data
 * collected and no data shared, and it is checkable from the binary rather than
 * taken on trust:
 *
 * ```
 * aapt2 dump permissions app-play-release.apk | grep -c INTERNET   # 0
 * unzip -p … classes.dex | strings | grep -ciE 'openai|chatgpt'    # 0
 * ```
 *
 * Every declaration below mirrors one in `src/github/.../OptionalFeatures.kt`.
 * The signatures must stay identical; nothing checks that but a build of the
 * other flavour, which is why CI builds both.
 *
 * **Empty bodies rather than a missing call site.** `main` calls these
 * unconditionally, so the Play build is the same code path with the feature
 * absent, rather than a second layout that could drift from the one people use.
 */

/** No assistant in this build, so no sparkles button and no chat. */
@Composable
@Suppress("UNUSED_PARAMETER")
internal fun RowScope.AssistantAction(state: space.linuxct.glyphworks.ui.design.EditorState, onEdit: () -> Unit) = Unit

/** No AI settings section. */
@Composable
internal fun ColumnScope.AiSettingsSection() = Unit

/**
 * No update row.
 *
 * Play distributes the updates for this build, and pointing users at an APK
 * outside Play would breach the Device and Network Abuse policy — using
 * `ACTION_VIEW` rather than installing directly does not exempt it.
 */
@Composable
internal fun UpdateSettingsRow() = Unit

/** Nothing to schedule: there is no update checker in this build. */
@Suppress("UNUSED_PARAMETER")
internal fun scheduleUpdateCheck(context: Context) = Unit

/** No chats exist, so design deletion has nothing extra to clean up. */
@Suppress("UNUSED_PARAMETER")
internal fun installOptionalHooks(app: Context, store: DesignStore) = Unit

/**
 * No sideloading card on the onboarding key page.
 *
 * Android's "Restricted setting" block applies to apps installed outside a
 * store; a Play install is never in that state, so the instructions would be
 * both wrong and confusing here.
 */
@Composable
internal fun ColumnScope.SideloadHelpCard() = Unit

/**
 * No "Allow restricted settings" row in the Tutorials tab, for the same reason
 * as [SideloadHelpCard].
 *
 * Adds no `item`, so the group is three rows in this build and the last of them
 * still gets the bottom-rounded corner — the shape comes from the item count.
 */
internal fun SectionCardScope.restrictedSettingsTutorialItem() = Unit
