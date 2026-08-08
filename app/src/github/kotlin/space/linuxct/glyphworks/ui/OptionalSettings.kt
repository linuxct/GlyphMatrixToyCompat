package space.linuxct.glyphworks.ui

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BrightnessAuto
import androidx.compose.material.icons.outlined.BrightnessMedium
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.Casino
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DragIndicator
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Slideshow
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import space.linuxct.glyphworks.Core
import space.linuxct.glyphworks.R
import space.linuxct.glyphworks.core.DebugLog
import space.linuxct.glyphworks.core.PrefKeys
import space.linuxct.glyphworks.core.ai.AiPrefKeys
import space.linuxct.glyphworks.core.ai.aiMaxRounds
import space.linuxct.glyphworks.core.ai.aiReasoningEffort
import space.linuxct.glyphworks.core.SessionArbiter
import space.linuxct.glyphworks.core.ai.ChatWire
import space.linuxct.glyphworks.core.ai.ReasoningEffort
import space.linuxct.glyphworks.core.design.DesignCodec
import space.linuxct.glyphworks.ui.design.DemoTarget
import space.linuxct.glyphworks.ui.design.DesignDemoActivity
import space.linuxct.glyphworks.ui.design.demoTarget
import space.linuxct.glyphworks.ui.theme.GlyphWorksTheme
import space.linuxct.glyphworks.ui.theme.NavPillColors
import space.linuxct.glyphworks.ui.theme.navPill
import space.linuxct.glyphworks.update.UpdateChecker
import space.linuxct.glyphworks.update.UpdateCheckWorker
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The model id the design assistant is asked for.
 *
 * ## Why this is a setting at all
 *
 * The id in [ChatWire.MODEL] is a guess about somebody else's backend, and it is
 * a moving target — when it stops being accepted, every message fails with the
 * same opaque error and the feature is simply dead until the next release. This
 * row is the escape hatch: a working id typed here revives it on the next
 * message, with no update and no restart.
 *
 * ## Why it lives in Settings
 *
 * The obvious alternative is the chat sheet's own overflow — closer to the
 * failure. But the chat is *inside* the design editor, behind sign-in and the
 * disclosure, and the state this fixes is one where the chat is the thing that
 * does not work. Settings is where a stuck user looks and it is reachable from a
 * cold start. It used to sit beside the creator name, among the app's general
 * rows; it now heads the AI settings group with the other two assistant knobs,
 * which is the same argument one step further — a stuck user wants one place to
 * go, not three rows to find.
 *
 * Written through on every keystroke and read fresh per turn (see
 * `ai/GlyphAiViewModel`) — same no-Save-button reasoning as [CreatorNameRow].
 * Empty is the reset: [ChatWire.resolveModel] turns it back into the built-in
 * default, which is what both the placeholder and the supporting line say.
 */
@Composable
private fun AiModelRow() {
    var model by remember {
        mutableStateOf(Core.prefs.getString(AiPrefKeys.MODEL, AiPrefKeys.MODEL_DEF))
    }
    // THREE-line for the same reason as [CreatorNameRow].
    PrefRow(lines = PrefRowLines.THREE, leading = { PrefIcon(Icons.Outlined.AutoAwesome) }) {
        Text(stringResource(R.string.pref_ai_model), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.pref_ai_model_summary),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = model,
            onValueChange = {
                // Newlines stripped and length capped for the same reason as the
                // creator name: this is a single token that ends up in a request
                // body, and a paste can carry anything.
                model = it.replace('\n', ' ').take(ChatWire.MODEL_MAX_LENGTH)
                Core.prefs.putString(AiPrefKeys.MODEL, model)
            },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            placeholder = { Text(stringResource(R.string.pref_ai_model_hint, ChatWire.MODEL)) },
            // The one thing the field itself cannot show: what a blank — or an
            // all-spaces — box actually sends. Resolved by the same function the
            // request uses, so it can never disagree with it.
            supportingText = {
                Text(stringResource(R.string.pref_ai_model_current, ChatWire.resolveModel(model)))
            },
            singleLine = true,
        )
    }
}

/**
 * How many tool rounds the assistant may take before a turn is cut short.
 *
 * A slider rather than a text field, unlike [AiModelRow] directly above it: a
 * model id is an opaque token that only the user knows, so it has to be typed,
 * whereas this is a small integer with a floor and a ceiling — and a field would
 * have to defend itself against "", "-1" and "99999" while the slider simply
 * cannot express them. [aiMaxRounds] still clamps on read; that guard is for a
 * store this control is not the only writer of, not for this control.
 *
 * Through [rememberPref] like everything else on this page — see [BrightnessRow]
 * for what the hand-rolled alternative costs.
 *
 * The slider writes on every step rather than on release. Each write is a pref
 * put, the value is read once per turn (never mid-turn), and the assistant is
 * not running while the user is dragging a slider in Settings.
 *
 * **`Route`, not `Repeat`.** The two-arrow loop this used to carry says "do the
 * same thing again", which is what it means one screen away — `DesignTimeline`
 * uses `Repeat` for the editor's actual loop toggle, so the app was spending one
 * glyph on two unrelated ideas. This setting is not a loop: it is a *bounded
 * path*, "how many steps may this turn take before it has to answer", and
 * `Route` (a track with a start and an end) is the one glyph on the classpath
 * that says exactly that.
 */
@Composable
private fun AiRoundsRow() {
    val rounds by rememberPref(AiPrefKeys.MAX_ROUNDS) { it.aiMaxRounds() }
    PrefRow(lines = PrefRowLines.THREE, leading = { PrefIcon(Icons.Outlined.Route) }) {
        Text(stringResource(R.string.pref_ai_rounds), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.pref_ai_rounds_summary),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            // The default is called out where it is the current value, because
            // "8 rounds" alone does not tell somebody whether they have already
            // changed this — which is the only question a reader of a settings
            // screen is actually asking about a number they do not recognise.
            stringResource(
                if (rounds == AiPrefKeys.MAX_ROUNDS_DEF) {
                    R.string.pref_ai_rounds_default
                } else {
                    R.string.pref_ai_rounds_value
                },
                rounds,
            ),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
        Slider(
            value = rounds.toFloat(),
            onValueChange = {
                Core.prefs.putInt(AiPrefKeys.MAX_ROUNDS, it.roundToInt())
            },
            valueRange = AiPrefKeys.MAX_ROUNDS_MIN.toFloat()..AiPrefKeys.MAX_ROUNDS_MAX.toFloat(),
            // Buckets of [AiPrefKeys.MAX_ROUNDS_STEP], not one per round. Ten
            // positions, so `steps` — which counts the detents BETWEEN the two
            // ends, not the selectable values — is eight. One per round drew 35
            // ticks and read as a smear.
            steps = (AiPrefKeys.MAX_ROUNDS_MAX - AiPrefKeys.MAX_ROUNDS_MIN) /
                AiPrefKeys.MAX_ROUNDS_STEP - 1,
            // **Ticks on the empty half of the track only**, which is how Nothing's
            // own volume panel draws them.
            //
            // Material3 puts a tick at every detent along the WHOLE track and
            // colours the two halves differently — `activeTickColor` over the
            // filled part, `inactiveTickColor` over the rest. Nothing draws the
            // filled part as one solid bar and leaves the dots to describe only
            // what is still available, which reads as a level rather than as a
            // ruler with a bar on top of it.
            //
            // Done by making the active ticks transparent rather than by supplying
            // a custom `track`: the geometry, the thumb gap and the inside corner
            // radius are all things material3 already gets right, and a hand-rolled
            // track would be a copy of them free to drift. The disabled pair goes
            // with it so the rule does not quietly reappear if this row is ever
            // greyed out.
            colors = SliderDefaults.colors(
                activeTickColor = Color.Transparent,
                disabledActiveTickColor = Color.Transparent,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * The display name of one reasoning level.
 *
 * A mapping from the `core/` enum onto `strings.xml`, in that order and never the
 * other way round: the **token** is the enum's ([ReasoningEffort.wire]) and the
 * **words** are the resource table's, so a label can be reworded or translated
 * without touching what goes on the wire, and a wire token cannot be changed by
 * editing copy. It is `internal` and not a composable so a unit test can prove it
 * total — every level has a string, and no two share one.
 *
 * `when` without an `else`, deliberately: adding a level to the enum then fails
 * to compile here rather than shipping a row with no name.
 */
internal fun ReasoningEffort.labelRes(): Int = when (this) {
    ReasoningEffort.LOW -> R.string.pref_ai_effort_low
    ReasoningEffort.MEDIUM -> R.string.pref_ai_effort_medium
    ReasoningEffort.HIGH -> R.string.pref_ai_effort_high
    ReasoningEffort.XHIGH -> R.string.pref_ai_effort_xhigh
    ReasoningEffort.MAX -> R.string.pref_ai_effort_max
    ReasoningEffort.ULTRA -> R.string.pref_ai_effort_ultra
}

/**
 * The caution pictogram beside a reasoning level this app cannot show the
 * backend accepts. See [ReasoningEffort.unverified] — which values carry it is
 * the enum's decision, not this file's.
 *
 * A **warning**, not an error: the outlined triangle rather than
 * `Icons.Outlined.ErrorOutline`, because these levels are unproven rather than
 * broken, and somebody may well be running one of them happily.
 *
 * Tinted `onSurfaceVariant` — the supporting-ink role this page already uses —
 * and explicitly not red. This app's colour rule allows exactly three enumerated
 * exceptions to its monochrome palette (the recording dot, the `+` FAB, the
 * nav-bar setup badge) and a settings hint is not a fourth: red here would read
 * as "this option is dangerous", which overstates a value that may simply work.
 *
 * The content description is the one place words are unavoidable: a pictogram is
 * invisible to a screen reader, and this is a menu of six items where two are
 * qualified. Both call sites pass it, so it is spoken with the option's name in
 * the menu and again on the field when a flagged level is the one selected.
 */
@Composable
private fun UnverifiedEffortIcon() {
    Icon(
        Icons.Outlined.WarningAmber,
        contentDescription = stringResource(R.string.pref_ai_effort_unverified),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(20.dp),
    )
}

/**
 * How hard the assistant is asked to think before it answers.
 *
 * A dropdown rather than the slider [AiRoundsRow] uses, because this is a short
 * list of *named* levels and not a number on a scale: nothing sits between "high"
 * and "extra-high", and a slider would invent a continuum the protocol does not
 * have. It is a read-only [OutlinedTextField] anchoring an
 * `ExposedDropdownMenuBox` — MD3's own idiom for exactly this, and the same
 * control shape as the two text fields above it, so the group reads as one thing.
 *
 * Through [rememberPref] like everything else on this page; see [BrightnessRow]
 * for what the hand-rolled alternative costs. Written straight to the pref on
 * selection and read fresh per turn (`ai/GlyphAiSession`), so a change applies to
 * the very next message.
 *
 * ## Two of the six may not exist
 *
 * [ReasoningEffort] carries the detail and the flag; here it only matters that
 * the marked ones are still ordinary, selectable options. The failure mode is
 * mild and visible: an unknown `effort` is rejected by the *request*, so the chat
 * shows the server's own words verbatim (`ChatFailure`, which exists precisely to
 * never paraphrase them) and the supporting line under this field names the token
 * that was sent, which is the string to look for in that error. The hint under
 * the section says which three levels are documented, so there is always a way
 * back that does not involve guessing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AiEffortRow() {
    val effort by rememberPref(AiPrefKeys.REASONING_EFFORT) { it.aiReasoningEffort() }
    var open by remember { mutableStateOf(false) }

    // **Give focus back when the menu closes.**
    //
    // `ExposedDropdownMenuBox` focuses its anchor to open the menu and does not
    // unfocus it afterwards, so a field that has been tapped once stays focused
    // for the rest of the visit — including after a tap *outside*, which
    // dismisses the menu and leaves the anchor looking like the active control
    // on a page where nothing is active. The colours below make that invisible;
    // this makes it untrue, which is the half that also fixes the keyboard's
    // idea of where it is.
    //
    // Guarded on a real true → false transition rather than keyed on `open`
    // alone: the effect runs on first composition too, and an unconditional
    // clear there would steal focus from whatever the user was typing in — the
    // model field one row up, on any recomposition that rebuilt this page.
    val focusManager = LocalFocusManager.current
    var wasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(open) {
        if (wasOpen && !open) focusManager.clearFocus()
        wasOpen = open
    }
    // THREE-line for the same reason as the rows above it: it is the 12 dp
    // padding this block has always had, and nothing in it fits in 88 dp.
    PrefRow(lines = PrefRowLines.THREE, leading = { PrefIcon(Icons.Outlined.Psychology) }) {
        Text(stringResource(R.string.pref_ai_effort), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.pref_ai_effort_summary),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val label = stringResource(effort.labelRes())
        ExposedDropdownMenuBox(
            expanded = open,
            onExpandedChange = { open = it },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) {
            OutlinedTextField(
                // The default is called out where it IS the current value, for
                // the same reason [AiRoundsRow] does it: "Medium" alone does not
                // tell somebody whether they have already changed this.
                value = if (effort == ReasoningEffort.DEFAULT) {
                    stringResource(R.string.pref_ai_effort_default, label)
                } else {
                    label
                },
                // Read-only rather than disabled: disabled would grey the text
                // and drop the field out of the tab order, and this IS the
                // control — the menu opens from it.
                onValueChange = {},
                readOnly = true,
                modifier = Modifier
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth(),
                // The warning survives the menu closing. Without this, choosing
                // Ultra would show a caution mark for as long as the menu was
                // open and then a field that looks like every other setting.
                leadingIcon = if (effort.unverified) {
                    { UnverifiedEffortIcon() }
                } else {
                    null
                },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
                // The exact token the request will carry. The label is copy and
                // may be translated; this is the string that appears in a server
                // error, so it is the one worth being able to read off.
                supportingText = {
                    Text(stringResource(R.string.pref_ai_effort_current, effort.wire))
                },
                singleLine = true,
                // **Stock MD3 colours, deliberately — including the near-black
                // focused rim.**
                //
                // There was a `focusedBorderColor` override here for one
                // revision, greying the rim down to match the resting one. That
                // was the wrong fix for the right complaint: the rim looked
                // wrong not because black is wrong but because it *persisted*
                // after the menu closed, so the field advertised a focus the
                // user had already left. Fixing the focus made the colour
                // correct again, and the override then only made a focused
                // dropdown look unfocused — the same disguise, one layer down.
                //
                // So this field matches [AiModelRow]'s exactly: grey hairline at
                // rest, near-black while it holds focus, and it holds focus only
                // while its menu is open. See the `LaunchedEffect` above.
            )
            ExposedDropdownMenu(
                expanded = open,
                onDismissRequest = { open = false },
                // 16 dp, not the 4 dp `MenuDefaults.shape` hands out. That default
                // still reads `MenuTokens.ContainerShape` — the token file stamped
                // `VERSION: v0_210`, i.e. the pre-expressive baseline. The library
                // itself already carries the current one: `SegmentedMenuTokens`
                // (`VERSION: 24.1.2`) sets a menu's `ContainerShape` to
                // `CornerLarge` = 16 dp, and nothing routes it to `DropdownMenu`
                // yet. `shapes.large` IS `ShapeTokens.CornerLarge`, so this is the
                // spec value, not a taste value — see the same override on the
                // Create tab's overflow menu and the assistant's.
                shape = MaterialTheme.shapes.large,
            ) {
                // Declaration order is the order they are offered, which is
                // ascending effort — the enum is the single list, so the menu
                // cannot fall out of step with what the wire accepts.
                ReasoningEffort.entries.forEach { level ->
                    DropdownMenuItem(
                        text = { Text(stringResource(level.labelRes())) },
                        trailingIcon = if (level.unverified) {
                            { UnverifiedEffortIcon() }
                        } else {
                            null
                        },
                        onClick = {
                            // The token, never the enum name or its ordinal: what
                            // is stored is what is sent. See [AiPrefKeys.REASONING_EFFORT].
                            Core.prefs.putString(AiPrefKeys.REASONING_EFFORT, level.wire)
                            open = false
                        },
                        // The spec'd insets for an EXPOSED dropdown's items,
                        // which are not `DropdownMenuItem`'s own defaults: the
                        // menu is anchored to a text field and its items line up
                        // with that field's text, not with a floating menu's.
                        // Without it the labels sit a few dp left of the value
                        // they replace, and the list reads as a separate object
                        // that happens to be nearby.
                        contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                    )
                }
            }
        }
    }
}


// ---------- update check ----------


private sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data object UpToDate : UpdateUiState
    data class Available(val version: String, val url: String) : UpdateUiState
    data class Failed(val reason: String) : UpdateUiState
}

@Composable
private fun UpdateRow() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val installed = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
        } catch (e: Exception) {
            "?"
        }
    }
    var state by remember { mutableStateOf<UpdateUiState>(UpdateUiState.Idle) }

    SetupRow(
        title = stringResource(R.string.update_check_title),
        subtitle = when (val s = state) {
            UpdateUiState.Idle -> stringResource(R.string.update_idle, installed)
            UpdateUiState.Checking -> stringResource(R.string.update_checking)
            UpdateUiState.UpToDate -> stringResource(R.string.update_up_to_date, installed)
            is UpdateUiState.Available -> stringResource(R.string.update_available, s.version)
            is UpdateUiState.Failed -> stringResource(R.string.update_failed, s.reason)
        },
        good = when (state) {
            is UpdateUiState.Available -> true
            is UpdateUiState.Failed -> false
            else -> null
        },
        leading = Icons.Outlined.SystemUpdate,
    ) {
        when (val s = state) {
            // Once an update is known, the row becomes the download link.
            is UpdateUiState.Available ->
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(s.url)))
            UpdateUiState.Checking -> {}
            else -> scope.launch {
                state = UpdateUiState.Checking
                val result = withContext(Dispatchers.IO) { UpdateChecker.check(installed) }
                state = when (result) {
                    is UpdateChecker.Result.UpdateAvailable ->
                        UpdateUiState.Available(result.version, result.url)
                    UpdateChecker.Result.UpToDate -> UpdateUiState.UpToDate
                    is UpdateChecker.Result.Failed -> UpdateUiState.Failed(result.reason)
                }
            }
        }
    }
}

/**
 * The AI settings group: header, card, hint.
 *
 * Lifted wholesale out of `SettingsTab`, where the three rows were composed
 * inline. They came apart badly as they multiplied — a model id, a round budget
 * and a reasoning level read as three unrelated rows between the creator name
 * and the update check, tied together only by each title starting with
 * "Assistant". They are one feature with three knobs, and two of them are only
 * ever touched when that feature is misbehaving, so grouping them also makes
 * "where do I go when the assistant stops working" a single answer.
 *
 * Ordered as they are used: the model is what a stuck user changes first, the
 * round budget is what a user with a big animation changes next, and the effort
 * is the one with an unproven range — hence the hint under it, which is the one
 * place that says out loud which levels are known to work.
 */
@Composable
internal fun ColumnScope.AiSettingsSectionImpl() {
    SectionHeader(stringResource(R.string.section_ai_settings))
    SectionCard {
        item { AiModelRow() }
        item { AiRoundsRow() }
        item { AiEffortRow() }
    }
    HintText(stringResource(R.string.pref_ai_effort_hint))
}

/** The "Check for updates" row. GitHub build only. */
@Composable
internal fun UpdateSettingsRowImpl() {
    UpdateRow()
}
