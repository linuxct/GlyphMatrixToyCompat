package space.linuxct.glyphworks.ui.design

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.FormatColorFill
import androidx.compose.material.icons.outlined.FormatColorReset
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.ZoomOutMap
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import space.linuxct.glyphworks.Core
import space.linuxct.glyphworks.R
import space.linuxct.glyphworks.ui.AssistantAction
import space.linuxct.glyphworks.core.design.DEFAULT_FRAME_DURATION_MS
import space.linuxct.glyphworks.core.design.Design
import space.linuxct.glyphworks.core.design.DesignCodec
import space.linuxct.glyphworks.core.design.DesignFrame
import space.linuxct.glyphworks.core.design.DesignFrames
import space.linuxct.glyphworks.core.design.DesignKind
import space.linuxct.glyphworks.core.design.DesignVariant
import space.linuxct.glyphworks.core.design.KeyMode
import space.linuxct.glyphworks.core.design.PokemonCodename
import space.linuxct.glyphworks.core.design.nowIsoUtc
import space.linuxct.glyphworks.designs.DesignStore
import space.linuxct.glyphworks.ui.MotionDialog
import space.linuxct.glyphworks.ui.dialogCardWidth
import space.linuxct.glyphworks.ui.displayNameRes
import space.linuxct.glyphworks.ui.homeCodename
import space.linuxct.glyphworks.ui.NoRipple
import space.linuxct.glyphworks.ui.offStateOutline
import space.linuxct.glyphworks.ui.requestPeakRefreshRateWhileVisible
import space.linuxct.glyphworks.ui.saveRespectingAuthor
import space.linuxct.glyphworks.ui.showDesignOnMatrix
import space.linuxct.glyphworks.ui.ShowOnMatrix
import space.linuxct.glyphworks.ui.theme.GlyphWorksTheme
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The pixel editor: one Glyph Matrix, drawn on with a finger, one frame at a time.
 *
 * ## Nothing here is ever lost
 *
 * A drawing is somebody's work and this app has no "discard changes?" dialog, so
 * the guarantee is structural rather than a question:
 *
 * 1. **Every completed gesture schedules a save.** A stroke, a tap, an undo, a
 *    redo, a clear, a fill, a duration change, a frame added / duplicated /
 *    deleted / reordered and a variant switch each mark the design dirty and arm
 *    [SaveScheduler]. The write lands [SAVE_DEBOUNCE_MS] after the last of them.
 * 2. **Leaving writes immediately.** Back, up, the save action and `ON_STOP`
 *    (home, recents, a task switch) *flush* — they cancel the pending timer and
 *    write there and then, synchronously with respect to `finish()`. No leave
 *    path can be reached without the file being written first.
 * 3. **Back does not race the save.** `finish()` runs *after* the write
 *    completes, from inside the same coroutine, so the activity — and with it
 *    the composition scope the write is running in — cannot be torn down
 *    mid-write. A save that FAILS does not close the editor at all: the drawing
 *    stays on screen where it can be tried again, which is the only honest
 *    response to a file that would not write.
 * 4. **The write itself is atomic** (`DesignStore` writes a temp file and then
 *    renames), so even a battery pull leaves either the previous design or the
 *    new one — never half of each.
 *
 * The one window this leaves open is a *foreground* process death inside the
 * debounce interval, which on Android means a crash — and a crash loses the
 * unsaved gesture, not the design. See [SAVE_DEBOUNCE_MS] for why that trade is
 * the right one now that a file can be a hundred and fifty kilobytes.
 *
 * A write that lands also tells the matrix to re-read (`refreshCurrentScreen`),
 * because the `Custom` toy loads the design file in `onActivate` and would
 * otherwise go on showing the pre-edit drawing after the editor closed. See that
 * method for the lifecycle ordering, and [SaveOutcome] for why only a write that
 * genuinely happened is allowed to trigger it.
 *
 * Saving goes through [saveRespectingAuthor], which pins `author` back to
 * whatever is already on disk: opening somebody else's imported design and
 * touching it up must not quietly put this phone's creator name on their work.
 * `createdAt` is never touched, and `modifiedAt` is restamped on every save.
 *
 * ## What this phase edits
 *
 * **The selected frame of the selected variant.** Every tool — the brush, undo,
 * redo, clear and fill — acts on whichever frame the timeline has selected, and
 * the undo history is *per frame* (see [TimelineEntry]) so undoing after moving
 * along the timeline can never rewrite a frame the user is no longer looking at.
 * A static design has one frame and no timeline; a dynamic one gets the timeline,
 * per-frame durations, onion skin, loop and key mode.
 *
 * ## What is on the surface, and what is not
 *
 * The canvas is `weight(1f)`: it gets exactly the height the controls leave, and
 * at 13x13 the cell pitch is a straight linear function of that height. So the
 * screen is rationed by **how often a control is touched** — palette, tools and
 * timeline stay, while loop, key mode and the variant explanation live behind the
 * app bar in [DesignSettings]. See that function for the measurements behind the
 * split.
 *
 * The variant switcher is rationed by whether it can do anything at all: it is on
 * the surface only for a design that actually carries more than one size, which
 * is derived from the design's own variants and never from a stored field. The
 * new-design dialog chooses what to seed; [DesignSettings] carries the way to add
 * the other size later.
 *
 * ## Live preview
 *
 * While this activity is resumed the real Glyph Matrix mirrors this design, with
 * hard precedence over the selected toy and over every compositing override — the
 * frame being drawn, or the animation running, depending on the app bar's
 * play/pause. See [LiveMatrixPreview] for what each of those shows and costs, and
 * `ScreenManager.beginLivePreview` for how precedence is actually enforced.
 * Opening a design also makes it the design the `Custom` toy plays, silently, so
 * that closing the editor leaves the drawing on the panel rather than whatever was
 * there before.
 *
 * ## Performance
 *
 * See [EditorFrame] for the phase discipline that keeps a moving finger out of
 * recomposition, and [ThumbnailCache] for the one that keeps the timeline out of
 * it.
 *
 * **What redraws on a clock, and what does not.** This screen used to promise
 * none of it. It now has exactly two clocks and each one is bounded by something
 * the user did:
 *
 * 1. **The floating preview's frame loop** ([FloatingLivePreview]) — running only
 *    while the design is dynamic, has more than one frame, the widget is composed
 *    and the activity is resumed. A tick writes one Int that is read from a draw
 *    lambda, so it invalidates one node's draw and recomposes nothing.
 * 2. **Playback on the panel**, while play is switched on: a `delay` chain at the
 *    design's own frame rate, not a frame callback, and it stops with the toggle
 *    or with the activity.
 *
 * Everything else is unchanged and still holds: the reorder drag's frame loop dies
 * with the finger (see `DesignTimeline`), and the paused live preview is a
 * `snapshotFlow` that suspends unless a pixel changed. **An idle editor showing a
 * static design still issues no frames and no pushes at all**, and an idle one
 * showing an animation issues frames for one small disc and nothing else.
 * [requestPeakRefreshRateWhileVisible] is called for the same reason every other
 * visible activity calls it — so a drag is sampled and presented at 120 Hz instead
 * of at whatever the display policy would otherwise park us on.
 */
class DesignEditorActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Core.init(this)
        requestPeakRefreshRateWhileVisible()
        enableEdgeToEdge()
        val designId = intent.getStringExtra(EXTRA_DESIGN_ID).orEmpty()
        setContent {
            GlyphWorksTheme {
                DesignEditor(designId = designId, onClose = ::finish)
            }
        }
    }

    companion object {
        private const val EXTRA_DESIGN_ID = "designId"

        /**
         * The id, and nothing else. The editor re-reads the design from the store
         * rather than being handed a parcelled copy, so it can never write a
         * stale version over one that changed since the list was drawn.
         */
        fun intent(context: Context, designId: String): Intent =
            Intent(context, DesignEditorActivity::class.java).putExtra(EXTRA_DESIGN_ID, designId)
    }
}

@Composable
private fun DesignEditor(designId: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val store = remember { Core.designStore }
    val openFailed = stringResource(R.string.editor_open_failed)

    // Null until the file has been read. Renders nothing rather than a spinner,
    // exactly as the Create tab does for its first directory read: this is a
    // couple of milliseconds off one small file, and a spinner that flashes for a
    // single frame is worse than a screen that simply arrives.
    var state by remember { mutableStateOf<EditorState?>(null) }
    LaunchedEffect(designId) {
        val design = withContext(Dispatchers.IO) { store.load(designId) }
        if (design == null) {
            Toast.makeText(context, openFailed, Toast.LENGTH_SHORT).show()
            onClose()
        } else {
            state = EditorState(design, openingCodename(design, homeCodename()))
        }
    }

    state?.let { EditorScaffold(it, store, onClose) }
}

/**
 * How long the editor waits, after the last change, before writing the file.
 *
 * Phase 5 wrote on **every completed gesture**: correct, and cheap while a design
 * was one 169-cell frame. A 240-frame arbok design is ~150 kB of cell characters,
 * and re-encoding and re-writing all of it after every stroke of a drawing
 * session is exactly the battery cost the editor was told not to have.
 *
 * 750 ms is chosen against the two things it sits between:
 *
 * - **Longer than a drawing burst's rhythm.** Lifting a finger, repositioning and
 *   putting it down again is a few hundred milliseconds, so a run of strokes
 *   collapses into one write instead of one write per stroke — which is the whole
 *   saving.
 * - **Shorter than "I've stopped drawing".** Someone who pauses to look at what
 *   they have made has the file on disk before they have finished looking.
 *
 * It never weakens the guarantee, because every way *out* of the editor flushes
 * first — see this file's KDoc, point (2). The debounce only ever delays a write
 * that nothing is yet waiting on.
 */
private const val SAVE_DEBOUNCE_MS = 750L

/**
 * What a save attempt actually did.
 *
 * Three states rather than a Boolean, because two callers want two different
 * distinctions out of the same call. Every *exit* path asks "is the file safe to
 * leave" — [WRITTEN] and [UNCHANGED] both are. The matrix refresh asks the
 * narrower question "did the bytes on disk just change", and only [WRITTEN] may
 * answer yes: refreshing after [UNCHANGED] would restart an animated design from
 * frame 0 for nothing, and refreshing after [FAILED] would replace a stale render
 * with an equally stale one while hiding the failure.
 */
internal enum class SaveOutcome {
    /** The file on disk is new. */
    WRITTEN,

    /** Nothing had changed since the last write; no I/O was done. */
    UNCHANGED,

    /** The write was attempted and did not land. The design stays dirty. */
    FAILED,
}

/**
 * Collapses a burst of edits into one write, and gets out of the way the moment
 * somebody leaves.
 *
 * [schedule] is the fire-and-forget path used by the editing controls; [flush] is
 * the path every exit takes. The interesting case is the two overlapping: `flush`
 * cancels the pending timer, but a save that has already *started* runs under
 * [NonCancellable] so it cannot be torn in half, and `EditorState.saveIfDirty`
 * serialises on its own mutex — so the flush waits for the in-flight write and
 * then finds nothing left to do. There is no ordering in which a change is
 * written by neither.
 */
@Stable
private class SaveScheduler(
    private val scope: CoroutineScope,
    private val save: suspend () -> Boolean,
) {
    private var pending: Job? = null

    /** Arms (or re-arms) the idle timer. Returns at once. */
    fun schedule() {
        pending?.cancel()
        pending = scope.launch {
            delay(SAVE_DEBOUNCE_MS)
            withContext(NonCancellable) { save() }
        }
    }

    /** Writes now, cancelling any pending timer. [onDone] reports the outcome. */
    fun flush(onDone: (Boolean) -> Unit = {}) {
        pending?.cancel()
        pending = null
        scope.launch { onDone(save()) }
    }
}

/**
 * The editor, over a state that is already loaded.
 *
 * [demo] is the guided tour's sandbox flag and it buys exactly two things — the
 * two side effects this screen has on the world outside its own window:
 *
 * 1. **Nothing is written.** The save scheduler's action becomes a no-op, so
 *    `DesignStore` is never asked to save, `refreshCurrentScreen` is never
 *    posted, and [store] is not touched at all.
 * 2. **Nothing takes the matrix.** [LiveMatrixPreview] is not composed, so no
 *    preview lease, no `setPreviewActive`, no `beginLivePreview` — the panel goes
 *    on showing whatever toy the user left on it.
 *
 * It buys nothing else, and that is the constraint it was written under: the tour
 * is worth having only if it demonstrates *this* editor, so anything the flag
 * changed beyond keeping its hands to itself would be the tour teaching a screen
 * that does not exist. Every control, gesture, animation and piece of state below
 * is the same one the real editor runs.
 *
 * See `ui/design/DesignDemo.kt` for the rest of the sandbox — the design with no
 * id, and the layer that stops the user reaching any of this.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EditorScaffold(
    state: EditorState,
    store: DesignStore,
    onClose: () -> Unit,
    demo: Boolean = false,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val saveFailed = stringResource(R.string.create_save_failed)
    val unnamed = stringResource(R.string.pref_custom_unnamed)

    val saver = remember(state, store, demo) {
        SaveScheduler(scope) {
            // Conditional one of two. The demo's design has no id and must never
            // reach the store, so the whole write path — including the matrix
            // refresh that follows a successful one — is short-circuited HERE, at
            // its single entrance, rather than at each of the dozen controls that
            // arm the scheduler. `true` because every exit path asks this the
            // question "is it safe to leave now", and it always is: there is
            // nothing to lose.
            if (demo) return@SaveScheduler true
            when (state.saveIfDirty(store)) {
                // The bytes on disk changed, so anything rendering FROM those
                // bytes is now showing something that no longer exists. In
                // practice that is the `Custom` toy, which reads the design file
                // in `onActivate` and would otherwise keep the pre-edit picture
                // on the matrix indefinitely — see `ScreenManager.refreshCurrentScreen`
                // for the lifecycle ordering that makes this necessary and for
                // why it is a no-op while this editor is resumed.
                //
                // Unconditional: no check that the design being edited is the one
                // the toy is showing. That check means reading CUSTOM_DESIGN_ID
                // and deciding whether "custom" is even the current screen, which
                // is knowledge that already lives in ScreenManager and would
                // become a second copy of it here, free to drift. The refresh
                // costs one `onActivate` on one screen, and if the frame it
                // produces is the one already on the panel then ScreenManager's
                // dedup drops it and no binder call happens at all.
                SaveOutcome.WRITTEN -> {
                    Core.scheduler.run { Core.screenManager.refreshCurrentScreen() }
                    true
                }
                // Nothing was dirty. Refreshing here would restart an animated
                // design from frame 0 for no reason at all.
                SaveOutcome.UNCHANGED -> true
                SaveOutcome.FAILED -> {
                    Toast.makeText(context, saveFailed, Toast.LENGTH_SHORT).show()
                    false
                }
            }
        }
    }

    // **Has the user asked for this design, as opposed to merely opened it?**
    // Latches false → true and never goes back; it is what makes the design the
    // active toy (see the `LaunchedEffect` below), so a design that was only
    // *looked at* leaves the panel alone.
    //
    // Two things set it, and they are the two ways of saying "this one":
    //   - **any edit** — somebody who drew on it wants to see what they drew;
    //   - **play** — an explicit "run this on the hardware", which is a claim on
    //     the panel even though it changes not one pixel of the document.
    //
    // A plain `remember`, keyed on the state like everything else that describes
    // this session rather than this document. A rotation is neither an edit nor a
    // press, but it also must not un-latch one — and it does not, because the
    // composition it survives is the same [EditorState].
    val claimed = remember(state) { mutableStateOf(false) }

    /**
     * Every mutating control goes through here, not through [saver] directly:
     * "something changed" has two consequences now — the debounced write, and the
     * activation — and a control that armed only one of them would be a bug that
     * looks like a typo. Cheap after the first call: [MutableState] does not
     * notify when the value it already holds is written again.
     */
    fun edit() {
        claimed.value = true
        saver.schedule()
    }

    var settingsOpen by remember { mutableStateOf(false) }

    // The scrolling-text dialog. `rememberSaveable`, unlike `settingsOpen`: the
    // keyboard is up for the whole life of this one, and turning the phone with a
    // half-typed phrase in it must not throw the phrase away — see
    // [MarqueeDialog], which saves the text for the same reason.
    var marqueeOpen by rememberSaveable { mutableStateOf(false) }


    // Hoisted to here, not held inside the canvas, because the reset control
    // lives in the tool row — the canvas is the wrong place to put a button when
    // every pixel of it is a drawing target. Deliberately NOT reset on a variant
    // switch: the disc is in the same place at both geometries, so a zoom set up
    // to draw arbok comfortably is still the right zoom after switching back and
    // forth to compare.
    val transform = remember { CanvasTransform() }

    // Is the animation running on the panel? Held here because two things need
    // it: the app-bar toggle that writes it, and [LiveMatrixPreview], which is
    // what a `true` actually means. **Paused is the resting state** and a plain
    // `remember` is deliberate — a rotation puts the editor back the way it was
    // in every other respect, but silently resuming a timer chain that is
    // driving the hardware is not something a configuration change should do.
    var playing by remember(state) { mutableStateOf(false) }
    // A design can lose its animation while it is open — the assistant replaces
    // the whole document (see [EditorState.replaceDesign]) and may hand back a
    // static one. Playback that survived that would be a timer chain with no
    // visible control left to stop it, because the toggle is composed only for a
    // dynamic design. (The other way a frame list changes wholesale, a variant
    // switch, is handled where the switch is.)
    val dynamic = state.design.kind == DesignKind.DYNAMIC
    LaunchedEffect(dynamic) { if (!dynamic) playing = false }

    // Is the floating preview on screen? **Shown by default** — it is the reason
    // the widget exists, and a preview somebody has to go and switch on is a
    // preview they will not know is there.
    //
    // Hidden is nonetheless a real want: the disc sits over the top-right of the
    // drawing area, and the one thing it can obscure is the artwork being drawn
    // underneath it. So this is an escape hatch, not a preference — `rememberSaveable`
    // so a rotation does not bring back something deliberately dismissed, and NOT
    // persisted, so the next design opens with the preview where it belongs.
    var previewShown by rememberSaveable(state) { mutableStateOf(true) }

    // The matrix on the back of the phone mirrors the frame being drawn, for as
    // long as this screen is resumed. Emits nothing.
    //
    // Conditional two of two. The guided tour must not light the user's panel or
    // fight the toy that is on it: a tutorial that took the matrix would break
    // the feature while explaining it. Not composing it is what keeps the lease
    // and the preview gate out of the demo's reach entirely.
    if (!demo) {
        LiveMatrixPreview(state, playing = playing, onRest = { playing = false })

        // **Editing a design makes it the design the panel plays.**
        //
        // Somebody who has just drawn on something wants the phone showing what
        // they drew when they put it down. Somebody who *opened* it to look — to
        // check which design this was, to read its settings, to close it again —
        // asked for nothing and gets nothing: the toy that was on the matrix is
        // still on it. Opening used to be enough, and that made browsing a
        // destructive act, silently replacing the user's chosen toy with whatever
        // they last glanced at.
        //
        // This is the same call the Create tab's "Show" menu item makes, so there
        // is exactly one implementation of "make this the active toy" and no
        // second copy of its side effects.
        //
        // **Silent, on purpose.** The Create tab toasts because a tap deserves an
        // answer; this is not a tap, and a toast on the first brush stroke would
        // be a notification of something the user did not ask for and cannot turn
        // off. The panel itself is the acknowledgement — on the way out, when the
        // preview lease is dropped and the design starts playing for real.
        //
        // Keyed on [claimed], which latches once, so this runs at most once per
        // opened design and does nothing at all until the first edit or the first
        // press of play. It does not wait for a change to reach the disk: what is
        // being selected is the design *file*, which already exists — and the
        // debounced write that an edit arms alongside this will land long before
        // anyone puts the phone down.
        LaunchedEffect(state, claimed.value) {
            if (!claimed.value) return@LaunchedEffect
            when (showDesignOnMatrix(state.design)) {
                // Nothing was touched: `showDesignOnMatrix` returns this BEFORE
                // it writes a preference, so a design with no artwork for this
                // phone's panel leaves the matrix exactly as it found it. That is
                // the whole reason this branches on the result rather than
                // ignoring it — opening a bellsprout-only design on an arbok must
                // not swap a working toy for a blank Custom screen.
                is ShowOnMatrix.NoArt -> Unit
                // Selected. `Shown` and `ShownWhenEnabled` differ only in whether
                // the toy is being driven right now, which is a distinction worth
                // a sentence when somebody asked for it and worth nothing at all
                // here — either way, this is now the design the panel plays.
                ShowOnMatrix.Shown, ShowOnMatrix.ShownWhenEnabled -> Unit
            }
        }
    }

    // Point (3) of this file's KDoc: leave only once the write has landed.
    fun saveAndClose() = saver.flush { ok -> if (ok) onClose() }

    // Point (2): home, recents and a task switch never reach the back handler,
    // and must not be allowed to leave a debounced write un-fired.
    LifecycleStartEffect(state) {
        onStopOrDispose { saver.flush() }
    }

    BackHandler { saveAndClose() }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(state.design.name.ifBlank { unnamed }) },
                navigationIcon = {
                    IconButton(onClick = { saveAndClose() }) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.editor_close),
                        )
                    }
                },
                actions = {
                    // Wrapped in a Row purely so the guided tour can spotlight the
                    // actions as **one** thing. `TopAppBar` lays its actions out in
                    // a Row anyway, so this changes no measurement; what it adds is
                    // a single node with a single set of bounds, which is what the
                    // tour needs to say "this is the bar, here is what is on it"
                    // instead of spending a step per icon. The individual buttons
                    // are deliberately untagged — see [DemoTarget.TOP_BAR].
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.demoTarget(DemoTarget.TOP_BAR),
                    ) {
                    // **Watch it run, on the hardware, without leaving.**
                    //
                    // The editor holds the matrix for as long as it is resumed, so
                    // until this existed the only way to see an animation actually
                    // play was to leave — and the timing of an animation is
                    // precisely the thing you cannot judge from a still frame.
                    //
                    // Only for a design that HAS an animation. A static design has
                    // one frame and a play button on it would be a control that
                    // can never do anything, which is the same rule the onion-skin
                    // toggle and the variant switcher are rationed by.
                    //
                    // It is in the bar rather than on the timeline (where an
                    // earlier note in [LiveMatrixPreview] proposed it) because the
                    // timeline's row is already six controls wide at 13x13 and
                    // every dp of it comes off the cell pitch — and because this
                    // acts on the whole design, like everything else up here, not
                    // on the selected frame, like everything down there.
                    //
                    // Paused is the resting state and nothing about the editor's
                    // behaviour changes until it is pressed; see [LiveMatrixPreview].
                    if (dynamic) {
                        IconButton(
                            onClick = {
                                playing = !playing
                                // Pressing play is an explicit "run THIS on the
                                // hardware", so it claims the panel exactly like
                                // an edit does — see [claimed]. Set on the press,
                                // not on `playing == true`, because pausing is
                                // still a statement about this design: the frame
                                // it leaves on the matrix is this design's.
                                claimed.value = true
                            },
                        ) {
                            Icon(
                                if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                                contentDescription = stringResource(
                                    if (playing) R.string.editor_pause else R.string.editor_play,
                                ),
                            )
                        }
                        // Hide or show the floating preview. Dynamic only, and for
                        // the same reason play is: on a static design the widget
                        // is not composed at all, so a button to dismiss it would
                        // be a control that can never do anything.
                        IconButton(onClick = { previewShown = !previewShown }) {
                            Icon(
                                if (previewShown) {
                                    Icons.Outlined.Visibility
                                } else {
                                    Icons.Outlined.VisibilityOff
                                },
                                contentDescription = stringResource(
                                    if (previewShown) {
                                        R.string.editor_preview_hide
                                    } else {
                                        R.string.editor_preview_show
                                    },
                                ),
                            )
                        }
                    }
                    // The design assistant, if this build has one.
                    //
                    // A seam, not a feature flag: `github` renders the sparkles
                    // button and everything behind it, `play` renders nothing and
                    // does not ship the code at all. See `ui/OptionalFeatures.kt`,
                    // which exists once per flavour.
                    //
                    // The whole feature is inside this one call — the button, the
                    // gate, the bridge onto the canvas and all three dialogs —
                    // which works because a dialog is its own window and does not
                    // care that it was composed from inside this `Row`.
                    AssistantAction(state, onEdit = { edit() })
                    // Loop, key mode and the variant explanation live behind
                    // this, not on the drawing surface — see [DesignSettings].
                    IconButton(
                        onClick = { settingsOpen = true },
                        modifier = Modifier.demoTarget(DemoTarget.SETTINGS_ACTION),
                    ) {
                        Icon(
                            Icons.Outlined.Tune,
                            contentDescription = stringResource(R.string.editor_settings),
                        )
                    }
                    }
                    // **There is deliberately no save action.** There used to be
                    // one, on the argument that a drawing tool with no visible
                    // save control asks the user to take saving on faith — but it
                    // could only ever *acknowledge* a write that was already
                    // guaranteed, and it cost a permanent slot in a four-action
                    // bar to do it. Points (1) and (2) of this file's KDoc are
                    // what actually make the guarantee: every mutating control
                    // arms [SAVE_DEBOUNCE_MS], and back, up and `ON_STOP` each
                    // flush before the editor can be left. The back arrow's label
                    // says so out loud ("Save and close"), which is where somebody
                    // looking for reassurance looks anyway.
                },
                // The whole page sits on the page background, and the bar is
                // SOLID in that same colour so it stays opaque yet seamless —
                // the same treatment MainActivity's header uses.
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { innerPadding ->
        // Deliberately NOT scrollable: a vertical scroller wrapping the canvas
        // would compete with the paint drag for the same gesture. Everything is
        // sized to fit instead, with the canvas taking whatever the controls
        // leave.
        //
        // Which is why WHAT IS IN HERE IS RATIONED. Every row below takes its
        // own height off the canvas, and while the canvas is the shorter of its
        // two dimensions that is `0.35 * 1.68 / 13 = 0.045` dp of cell pitch per
        // dp of row — a 100 dp row costs about 4.5 dp of pitch, against a
        // fingertip contact patch of 8-10 mm. Only controls used *continuously
        // while drawing* earn a permanent place: the palette, the tools and the
        // timeline. Loop, key mode and the variant explanation are set a handful
        // of times per design and live in [DesignSettings] instead.
        //
        // The variant switcher earns its place CONDITIONALLY, which is the third
        // answer this rationing has: it is used continuously by somebody drawing
        // both sizes and is dead weight for somebody drawing one, so it is shown
        // when — and only when — the design actually has more than one variant.
        // The one thing that is NOT rationed by the paragraphs above, because it
        // does not take a single dp off the canvas: the floating preview is an
        // overlay, pinned into the corner of the drawing area that the disc — a
        // circle inscribed in the shorter side — cannot reach. See
        // [FloatingLivePreview]. The `Box` it needs costs one layout node; an
        // empty overlay does not consume touches, so the canvas underneath is
        // still painted on everywhere the disc itself is not.
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            Column(Modifier.fillMaxSize()) {
                EditorCanvas(
                    state = state,
                    transform = transform,
                    onStrokeEnd = { edit() },
                    modifier = Modifier.fillMaxWidth().weight(1f).demoTarget(DemoTarget.CANVAS),
                )
                Spacer(Modifier.height(CANVAS_PALETTE_GAP))
                PaletteRow(state)
                Spacer(Modifier.height(4.dp))
                ToolRow(
                    state,
                    transform,
                    // The same Boolean the play/pause action and the floating
                    // preview are gated on, read once above: three controls that
                    // mean "this design is an animation" cannot be allowed to
                    // disagree about whether it is.
                    dynamic = dynamic,
                    onChanged = { edit() },
                    onMarquee = { marqueeOpen = true },
                )
                // Only when there is something to switch BETWEEN. A design drawn for
                // one phone would otherwise spend ~56 dp (the row plus its gap) on a
                // control with one reachable state, and by the arithmetic above that
                // is ~2.7 dp of cell pitch at 13x13 and ~1.4 dp at 25x25 — every time
                // the canvas is the shorter of its two dimensions, which is where the
                // pitch is worst and the drawing hardest.
                //
                // Driven by [EditorState.variantsPresent], so it is a fact about the
                // artwork and not about a stored preference: add the second size from
                // [DesignSettings], or import a design that already carries both, and
                // the row appears with no other code involved.
                if (state.variantsPresent.size > 1) {
                    Spacer(Modifier.height(8.dp))
                    VariantRow(
                        state,
                        onSwitched = {
                            edit()
                            // The other size cannot be previewed on this panel at
                            // all (see [LiveMatrixPreview]), so a switch releases
                            // the matrix — and a toggle still reading "pause"
                            // over a panel that has gone back to its toy would be
                            // claiming something that is not happening.
                            playing = false
                        },
                    )
                }
                // The timeline and the per-frame duration are the core dynamic
                // workflow — frames are switched constantly — so they stay on the
                // surface. A static design has one frame and no timeline.
                if (state.design.kind == DesignKind.DYNAMIC) {
                    Spacer(Modifier.height(4.dp))
                    Timeline(state, onChanged = { edit() })
                }
                Spacer(Modifier.height(12.dp))
            }
            // Composed LAST so it sits above the canvas and the controls; it
            // positions itself (top-right at rest) and covers the whole content
            // area only while it is expanded.
            //
            // DYNAMIC ONLY, and gated here rather than inside the widget so a
            // static design pays nothing at all — not the overlay `Box`, not the
            // layout node, not the state. The widget exists to show MOVEMENT
            // somebody is drawing: on a one-frame design it is a second, smaller
            // copy of the canvas already filling the screen behind it, which is
            // what made it read as clutter rather than as a tool.
            //
            // [previewShown] is the app bar's eye, and it gates the widget from
            // out here for the same reason the kind does: hidden must cost
            // nothing, not merely draw nothing. Composing it and setting its
            // alpha to zero would leave the frame clock running to animate a disc
            // nobody can see.
            if (dynamic && previewShown) FloatingLivePreview(state)
        }
    }

    if (settingsOpen) {
        DesignSettings(
            state = state,
            onChanged = { edit() },
            onDismiss = { settingsOpen = false },
        )
    }

    // Scrolling text. `onGenerated` is the ordinary [edit] — a marquee is a
    // change like any other, so it marks the design dirty, activates it and is
    // written by the same debounced save as a stroke. See [MarqueeDialog] for
    // what it does to the artwork that was already there.
    if (marqueeOpen) {
        MarqueeDialog(
            state = state,
            onDismiss = { marqueeOpen = false },
            onGenerated = {
                edit()
                // The frame list has just been rebuilt underneath it; a preview
                // still driving the panel from the old one would be animating
                // frames that no longer exist. Same rule the variant switch and
                // a whole-document apply already follow.
                playing = false
            },
        )
    }
}

/**
 * The design-level settings: loop, what the Essential Key does, what the two
 * variants actually are — and the way to acquire the second one.
 *
 * **Why these are behind a dialog and the palette is not.** The editor has one
 * scarce resource and it is vertical space: the canvas takes what the controls
 * leave, and at 13x13 the cell pitch is a linear function of it. Phase 6 put
 * loop and key mode permanently on the surface and the pitch fell into the
 * mid-teens — 4 mm or so against a fingertip contact patch of 8-10 mm, which
 * makes a *drawing* tool miss the cell you are looking at. Moving these two
 * rows and the variant sentence off the surface puts it back above 22 dp.
 *
 * The controls in here are touched a handful of times in a design's life; the
 * canvas is touched thousands. Rationing the surface by how often something is
 * used is the only ordering that does not pay a constant cost for an occasional
 * convenience — and a design-level dialog is where the rest of the design's
 * metadata (name, author, export) naturally belongs later.
 *
 * [MotionDialog] rather than a bare `Dialog` for the same reason every other
 * pop-up in this app uses it — it enters and leaves on the theme's springs
 * instead of appearing.
 *
 * ## "Add ... artwork", and why there is no "remove"
 *
 * A design created for one phone shows no variant switcher, so this dialog is
 * where the other size is reachable from. It belongs here rather than anywhere
 * else because this is already the place that explains the dual-size rule — the
 * action sits directly under the sentence that motivates it — and because it is a
 * design-level decision, which is what everything else in here is.
 *
 * **The reverse deliberately does not exist**, and the two are not symmetric.
 * Adding a variant creates an empty canvas: one map entry, no artwork touched,
 * and ignoring it costs nothing but the switcher staying on screen. Removing one
 * would delete frames somebody drew by hand, with no undo and possibly no other
 * copy. This app has exactly one destructive action — deleting a whole design —
 * and it is guarded by a confirmation that names the design; an unguarded second
 * one, two taps inside a dialog people open to toggle repeat, is a different
 * class of risk for a much smaller prize. The worst an unwanted variant does is
 * leave a 48 dp row on screen, and somebody who genuinely wants a single-size
 * file can create one. Nobody can un-delete art.
 */
@Composable
private fun DesignSettings(state: EditorState, onChanged: () -> Unit, onDismiss: () -> Unit) {
    MotionDialog(onDismiss) { dismiss ->
        DesignSettingsCard(state, onChanged, onClose = dismiss)
    }
}

/**
 * The settings themselves, without the window around them.
 *
 * Split from [DesignSettings] for the guided demo, which shows this card in its
 * own composition rather than in a platform `Dialog` — a dialog is a separate
 * window and would sit *above* the tour's spotlight, so the captions could not
 * point at anything inside it. That mattered enough to be worth the split:
 * **four testers never found the repeat toggle**, which lives in here and nowhere
 * else, and a tutorial that stopped at the app-bar icon would have left them
 * exactly where they were.
 *
 * The controls are shared, so the tour cannot demonstrate a repeat button that
 * has drifted from this one.
 *
 * ## Why the width is asked for rather than accepted
 *
 * Sharing the controls was not enough to make the two look alike, because the two
 * contexts *measure* differently: in a dialog window this card is capped at the
 * platform's preferred dialog width, and in the tour's composition it is not
 * capped at all, so it grew to fill the sheet — 363 dp against the real 320 on
 * this phone. [dialogCardWidth] is that one cap, resolved from the platform in
 * both places, and it is applied HERE rather than by the callers: a caller can
 * forget, and the two would be free to drift again the moment one did.
 */
@Composable
internal fun DesignSettingsCard(state: EditorState, onChanged: () -> Unit, onClose: () -> Unit) {
    Surface(
        modifier = Modifier.width(dialogCardWidth()),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 18.dp),
        ) {
            Text(
                stringResource(R.string.editor_settings),
                style = MaterialTheme.typography.titleLarge,
            )
            // Loop and key mode describe an *animation*. A static design has
            // neither question to answer, so it is not asked them.
            if (state.design.kind == DesignKind.DYNAMIC) {
                Spacer(Modifier.height(16.dp))
                PlaybackRow(state, onChanged)
            }
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.editor_variant_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Present only while there is a size to add, so it disappears the
            // moment it has done its job. Named by the MISSING device, which
            // is the whole information content of the button.
            state.missingVariant?.let { missing ->
                TextButton(
                    // Dismisses on success: what the user has actually asked
                    // for is the variant switcher, and that is behind this
                    // dialog. Staying open would show them a button that had
                    // vanished and nothing else.
                    onClick = {
                        if (state.addVariant(missing)) onChanged()
                        onClose()
                    },
                    modifier = Modifier.demoTarget(DemoTarget.ADD_VARIANT),
                    contentPadding = ADD_VARIANT_PADDING,
                ) {
                    Text(
                        stringResource(
                            R.string.editor_add_variant,
                            stringResource(missing.displayNameRes()),
                        ),
                    )
                }
            }
            Row(Modifier.fillMaxWidth()) {
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onClose) { Text(stringResource(R.string.tut_close)) }
            }
        }
    }
}

/**
 * The "Add ... artwork" button's content padding: no horizontal inset, so its
 * label starts on the same left edge as the sentence directly above it. A
 * `TextButton`'s default 24 dp would leave it visibly indented from the paragraph
 * it belongs to.
 */
private val ADD_VARIANT_PADDING = PaddingValues(horizontal = 0.dp, vertical = 8.dp)

/**
 * The variant the editor opens on: this device's own panel if the design has one,
 * otherwise whichever it does have.
 *
 * **The fallback is load-bearing, not politeness.** `EditorState.composed()`
 * writes the open variant back into `design.variants`, so opening on a geometry
 * the design does not carry would *create* it on the first save — quietly undoing
 * "this phone only" for anybody who drew a design for their friend's phone and
 * then opened it on their own. A design always has at least one variant
 * (`DesignCodec` rejects one with none), so the final fallback is unreachable and
 * exists only so this returns a value.
 *
 * Pure, and separate from [homeCodename] for that reason: which panel this phone
 * has needs a `Core`, and the rule for choosing between them is what is worth
 * testing.
 */
internal fun openingCodename(design: Design, home: PokemonCodename): PokemonCodename = when {
    design.variantFor(home) != null -> home
    else -> PokemonCodename.entries.firstOrNull { design.variantFor(it) != null } ?: home
}

// ---------- the live preview ----------

/**
 * The shortest gap between two pushes to the hardware, in milliseconds.
 *
 * ~30 pushes a second. The number is not about what the eye can follow, it is
 * about what the push COSTS: every frame is a synchronous binder round-trip to
 * `com.nothing.thirdparty.GlyphService` on the `glyph-io` thread (see
 * `GlyphLink`'s threading KDoc), issued with `flags=0` through an AIDL we do not
 * control. Pointer samples arrive at the input rate — 120 Hz on this display,
 * and a paint drag produces one per sample — so pushing per event would be four
 * times this rate of blocking IPC for a panel that cannot show the difference.
 *
 * `ScreenManager` already drops byte-identical frames, and that is deliberately
 * NOT what is relied on here: its dedup saves the *transaction* only once the
 * frame has already been built and handed over, and the traffic being coalesced
 * is mostly frames that genuinely differ (a stroke changes a cell per sample).
 * The saving has to happen before the call is made, which is what the throttle
 * below does.
 */
internal const val PREVIEW_INTERVAL_MS = 33L

/**
 * Mirrors the design onto the real Glyph Matrix, with hard precedence over
 * everything else on it: the frame being edited while [playing] is false, and the
 * animation itself while it is true.
 *
 * ## Paused — the resting state, and the default
 *
 * **The frame being edited.** The preview exists to answer one question, "what
 * does this pixel actually look like on the hardware", and an animation playing
 * through is the one thing that cannot answer it: the pixel under the finger
 * would be on screen for a fraction of a second in every cycle, greys would be
 * impossible to judge against their neighbours, and the panel would be busiest
 * exactly when the user is trying to look at it. That is why playback is a
 * deliberate action and not what an opened editor does on its own.
 *
 * ## Playing
 *
 * The whole animation, frame by frame, honouring each frame's `durationMs` and
 * the design's repeat rule — [nextPlaybackFrame], [playbackHoldMs] and
 * [designRepeats] are the schedule, shared with the floating preview so the two
 * cannot disagree about the timing, and matching `CustomScreen.advance` so what
 * plays here is what the toy will play.
 *
 * A design that does not repeat plays through, holds its last frame for that
 * frame's own duration, and then **turns playback off**: [onRest] flips the
 * toggle, which puts the icon back to "play" and hands the panel to the paused
 * pump above — so the panel ends up showing the frame being edited, which is what
 * "paused" means everywhere else in this editor. Leaving the animation's final
 * frame up instead would be a third state, indistinguishable on the panel from a
 * playback that had got stuck.
 *
 * **Painting does not pause it, deliberately.** A stroke is not a request to stop
 * watching, and stopping on one would make the toggle fight the finger — press
 * play, touch the canvas, playback is over. It costs nothing in freshness either:
 * the cells are copied at *push* time, one frame at a time, so an edit to any
 * frame is on the panel the next time the loop reaches it, which for a design
 * anybody is drawing is well under a second. What the canvas shows is unchanged
 * in both states: the frame being edited.
 *
 * ## Cost while playing
 *
 * One `delay` chain, no frame callbacks, and one push per frame at the design's
 * own rate — floored at [PREVIEW_INTERVAL_MS], because a 20 ms frame is legal and
 * 50 blocking binder round-trips a second is not something to do for a panel that
 * cannot show the difference. It exists only while [playing] **and** the activity
 * is resumed: the effect is keyed on both, so leaving the editor for any reason
 * cancels it, and returning finds it paused only if the toggle was turned off.
 *
 * ## The other variant
 *
 * Only the variant matching this device's own panel can be previewed: a 25x25
 * arbok frame is not a thing a 13x13 bellsprout can show, and half of one is
 * worse than none. Switching to the other geometry therefore *releases* the
 * matrix — the gate opens, the toy comes back, the lease is dropped — and
 * switching back takes it again.
 *
 * ## Cost when nothing is happening
 *
 * Zero, while paused. The pump is a `snapshotFlow` over the frame's revision
 * counter, so it is suspended unless a pixel actually changed. No frame loop, no
 * polling; an idle *paused* editor issues no pushes and holds no timers. Reading
 * the revision from a `snapshotFlow` also keeps Phase 5's phase discipline
 * intact: the flow installs its own snapshot observer, so a moving finger
 * notifies this coroutine and the `Canvas` draw scopes, and recomposes nothing.
 */
@Composable
private fun LiveMatrixPreview(state: EditorState, playing: Boolean, onRest: () -> Unit) {
    // Reading `codename` here (a composable body) is correct and cheap: it
    // changes only on a variant switch, which is already a structural change.
    val previewable = Core.glyphLink.isSupported && state.codename.size == Core.glyphLink.size
    if (!previewable) return

    // Owned by the lifecycle callbacks rather than by a DisposableEffect keyed
    // on them, because release must be certain: `onPauseOrDispose` runs on the
    // lifecycle event itself, whereas anything driven by a snapshot write would
    // not take effect until the next recomposition — which is a bad thing to
    // depend on in a window that is going away.
    var resumed by remember { mutableStateOf(false) }
    LifecycleResumeEffect(state) {
        // Order matters. setPreviewActive comes first so the session and the
        // shared GlyphLink lease exist (SessionArbiter starts the session on the
        // scheduler thread), and beginLivePreview is posted after it so it runs
        // after startSession has activated a screen — otherwise the activation
        // would land on an already-open gate.
        Core.arbiter.setPreviewActive(true)
        Core.scheduler.run { Core.screenManager.beginLivePreview() }
        resumed = true
        onPauseOrDispose {
            // The mirror image: stop pushing, hand the matrix back, then let go
            // of the preview ownership so the lease can be released. Holding a
            // lease past this point keeps the Glyph service bound and the panel
            // lit behind the user's back.
            resumed = false
            Core.scheduler.run { Core.screenManager.endLivePreview() }
            Core.arbiter.setPreviewActive(false)
        }
    }

    LaunchedEffect(state, resumed, playing) {
        if (!resumed) return@LaunchedEffect
        if (playing) {
            // The animation. One index, one push, one delay, and the schedule
            // decides the rest — see [nextPlaybackFrame], which is the same rule
            // `CustomScreen.advance` follows on the toy side.
            //
            // Everything is re-read per step rather than captured: the frame list
            // is a `SnapshotStateList` the user is still editing, so a frame added,
            // deleted or reordered mid-playback is picked up on the next step
            // instead of playing out of a stale copy. Plain reads, from a
            // coroutine and not from composition, so nothing subscribes to them.
            var index = 0
            while (true) {
                val frames = state.frames
                if (frames.isEmpty()) break
                // Deletion can put the cursor past the end. Start the pass again
                // rather than stopping: the design still has an animation.
                if (index > frames.lastIndex) index = 0
                val entry = frames[index]
                // Copied on the main thread, for the same reason the pump below
                // copies there: the buffer is mutated by the pointer handler.
                val cells = entry.frame.copyOfCells()
                Core.scheduler.run { Core.screenManager.pushLivePreview(cells) }
                delay(playbackHoldMs(entry.durationMs))
                val repeats = designRepeats(state.design.loop, state.design.keyMode)
                index = nextPlaybackFrame(index, state.frames.size, repeats) ?: break
            }
            // Came to rest on the last frame (repeat off, or the frames went away
            // under it). Hand the toggle back so the bar stops claiming to be
            // playing; that flips `playing`, which re-runs this effect and lets
            // the pump below take the panel again.
            onRest()
            return@LaunchedEffect
        }
        snapshotFlow { state.previewToken() }
            // The throttle. `conflate` drops everything that arrives while the
            // collector is inside its delay and keeps only the fact that
            // something changed — the cells themselves are re-read at push time,
            // so what lands on the panel is always the CURRENT drawing and never
            // a stale sample. A change arriving during the delay still gets its
            // push afterwards, so the last state of a stroke always reaches the
            // matrix.
            .conflate()
            .collect {
                // Copied on the main thread. The buffer is mutated by the
                // pointer handler, also on the main thread, so this is the only
                // place a consistent snapshot can be taken without racing a
                // finger that is still moving.
                val frame = state.copyOfSelectedCells()
                Core.scheduler.run { Core.screenManager.pushLivePreview(frame) }
                delay(PREVIEW_INTERVAL_MS)
            }
    }
}

// ---------- the canvas ----------

/**
 * The gap between the drawing surface and the palette.
 *
 * The illustration is a solid wash that runs to the bottom edge of the canvas (it
 * is cropped there on purpose — see [drawDeviceBack]), so with no gap at all the
 * swatch circles butt straight into the phone body and read as *part of the
 * illustration* rather than as the controls that act on it. 16 dp is MD3's
 * standard separation between a surface and the controls beneath it, and it is
 * the smallest value at which the palette clearly belongs to the toolbar below
 * rather than to the picture above.
 *
 * **It costs no cell pitch on any window this app runs on.** The panel's radius
 * is `ZOOM_TARGET * min(canvasWidth, canvasHeight)`, and the canvas is
 * width-capped on a phone in portrait — around 448 dp wide against 600-750 dp of
 * canvas height even with the timeline present — so the height this takes comes
 * out of slack that `min` was never going to look at. The pitch stays at 20.3 dp
 * at 13x13 and 10.5 dp at 25x25, exactly as [MAX_CANVAS_SCALE]'s table states.
 * The regime where it would cost something is the one that table's second half
 * describes (a large font scale, three-button navigation, a short window), and
 * there it is 16 x 0.045 = 0.72 dp at 13x13 and 0.38 dp at 25x25 — under a
 * twentieth of a cell, against a control row that could not otherwise be told
 * apart from the artwork.
 */
private val CANVAS_PALETTE_GAP = 16.dp

/**
 * How much of the canvas's shorter side the Glyph Matrix takes up — **the editor
 * camera's zoom, and the editor camera's only parameter.**
 *
 * On the device the matrix is a small disc on a camera plate (`DeviceBack`), which
 * drawn at natural size would leave about 8 dp between neighbouring cells at 13x13
 * and half that at 25x25. Unusable as a target. So the editor looks at the phone
 * through a camera zoomed until the panel fills a chosen fraction of the drawing
 * area: `radius = ZOOM_TARGET * min(width, height)`.
 *
 * **The cell pitch is therefore a function of this number and the canvas, and of
 * nothing else.** Not of how tall the camera plate is, not of where the island
 * sits on the back, not of anything the model says. That decoupling is the point
 * of having a model and a camera rather than one tangle: the device can be
 * redrawn to match the hardware without a single dp of drawing precision moving.
 *
 * | | 1x | 2x | 4x |
 * |---|---|---|---|
 * | 13x13 (bellsprout) | **20.3 dp** | 40.5 dp | 81.1 dp |
 * | 25x25 (arbok) | 10.5 dp | 21.1 dp | 42.1 dp |
 *
 * **20 dp at 13x13 is the floor and 0.35 is what clears it**, on the ~448 dp
 * canvas this app runs on. Phase 7 treated the mid-teens as a defect against a
 * fingertip contact patch of 8-10 mm and that judgement stands, so this constant
 * cannot come down to buy framing — which is exactly what earlier versions kept
 * spending it on. What the camera can and cannot show at 0.35 is now simply a fact
 * about the device's proportions, reported by `GlyphCanvasTest` rather than
 * engineered around.
 *
 * That limit binds the RESTING view and only the resting view. The user's own
 * pinch ([CanvasTransform]) goes well past it, and may: once somebody has
 * deliberately zoomed in to place a pixel they are looking at a panel, not at a
 * picture of a phone, and there is nothing left for the illustration to tell them
 * that they did not just ask to stop being told.
 */
private const val ZOOM_TARGET = 0.35f

/**
 * How finely a drag is resampled, as a fraction of the cell pitch.
 *
 * Pointer samples arrive at the input rate, not the pixel rate: a fast flick can
 * put two consecutive positions four cells apart, and painting only the sampled
 * points would draw a dotted line. Every segment is walked at half-cell steps,
 * which cannot skip a cell because no cell is narrower than one pitch.
 */
private const val STROKE_STEP_FRACTION = 0.5f

/**
 * How far in the user may pinch.
 *
 * The number comes from the panel it exists to make drawable, not from taste. The
 * cell pitch is `2 x GRID_EXTENT / size` of the disc radius and the radius is
 * [ZOOM_TARGET] of the canvas's shorter side, so on the ~448 dp canvas this app
 * runs on:
 *
 * | | 1x | 2x | 4x |
 * |---|---|---|---|
 * | 13x13 (bellsprout) | 20.3 dp | 40.5 dp | 81.1 dp |
 * | 25x25 (arbok) | **10.5 dp** | 21.1 dp | **42.1 dp** |
 *
 * 10 dp is about 3 mm against a fingertip contact patch of 8-10 mm — arbok is not
 * drawable at rest, and no layout change could fix it because the pitch is
 * width-capped (Phase 7 had already reclaimed every dp of height there was). 4x
 * puts it at 42 dp, which is a comfortable target with room to see the cell under
 * the finger. Past that the disc stops fitting on the screen in any useful way
 * and the pan clamp does all the work, so there is nothing to buy.
 *
 * **The table is the width-capped case, which is the good one.** `min(width,
 * height)` is the whole story: while the canvas is at least as tall as it is
 * wide, the numbers above are what the panel gets and no row below the canvas can
 * change them. Height only binds when the controls have eaten enough of it — a
 * large accessibility font scale, three-button navigation, a short or split
 * window — and there the pitch is `canvasHeight * 0.35 * 1.68 / size`, i.e.
 * 0.045 dp per dp at 13x13 and 0.024 at 25x25. That is the regime hiding the
 * variant switcher pays into: ~56 dp reclaimed is up to +2.5 dp and +1.3 dp
 * respectively, and nothing at all once the canvas is back to being square or
 * taller. It can never go the other way, which is the property that matters.
 */
internal const val MAX_CANVAS_SCALE = 4f

/**
 * The zoom and pan the user has applied over the disc, and the clamps that make
 * it impossible to lose the canvas.
 *
 * ## Why this is snapshot state and where it may be read
 *
 * A pinch produces a value per pointer sample — 120 a second on this display —
 * exactly like a paint drag, so it is under exactly the same phase discipline
 * (see [EditorFrame]): the transform must cause a **redraw, not a
 * recomposition**. [scale], [offsetX] and [offsetY] are therefore read from the
 * `Canvas` draw lambda and from the pointer-input coroutine, and from nowhere
 * else. Reading them in a composable body would put the whole editor — palette,
 * tool row, timeline, app bar — through recomposition on every sample of a
 * gesture that changed nothing but where the disc is drawn.
 *
 * The one composable that has to care whether we are zoomed at all is the reset
 * control, and it goes through a `derivedStateOf` so it recomposes when the 1x
 * boundary is crossed rather than when the scale changes; see [ToolRow].
 *
 * ## The transform itself
 *
 * A uniform scale about the canvas origin plus a translation: `p -> p * scale +
 * offset`. Nothing more expressive is wanted — [MatrixDisc.transformedBy]
 * explains why keeping it to a similarity transform is what lets `matrixCellAt`
 * stay an exact inverse under it.
 */
@Stable
internal class CanvasTransform {

    var scale by mutableFloatStateOf(1f)
        private set

    var offsetX by mutableFloatStateOf(0f)
        private set

    var offsetY by mutableFloatStateOf(0f)
        private set

    val offset: Offset get() = Offset(offsetX, offsetY)

    /**
     * Folds one two-finger sample in: [zoomChange] and [pan] as the gesture
     * reported them, about [centroid] (the point between the fingers, in canvas
     * pixels).
     *
     * The pivot arithmetic keeps whatever content is under the centroid *under
     * the centroid*, which is the only pinch that feels like handling an object
     * rather than operating a control. Solving `centroid = content * scale +
     * offset` for the new offset gives the line below — and it uses the zoom that
     * was **actually applied** after the range clamp, not the requested one, so
     * pinching past 4x does not quietly slide the canvas sideways.
     */
    fun onGesture(centroid: Offset, pan: Offset, zoomChange: Float, canvas: Size) {
        if (canvas.width <= 0f || canvas.height <= 0f) return
        val next = (scale * zoomChange).coerceIn(1f, MAX_CANVAS_SCALE)
        val applied = if (scale > 0f) next / scale else 1f
        val x = centroid.x + pan.x - (centroid.x - offsetX) * applied
        val y = centroid.y + pan.y - (centroid.y - offsetY) * applied
        scale = next
        // The clamp: the magnified canvas may never expose a gap at any edge, so
        // the offset runs from "bottom-right corners aligned" to "top-left
        // corners aligned" and no further. At 1x that collapses to exactly zero —
        // there is nothing to pan when the content IS the canvas — and the disc
        // therefore cannot be dragged off-screen and lost at any zoom level. A
        // user who cannot find their canvas again assumes the app broke.
        //
        // Both bounds are forced non-positive before use: `canvas - canvas *
        // next` is negative for every scale above 1, but the expression must not
        // be able to hand `coerceIn` an inverted range if it ever isn't.
        val minX = (canvas.width - canvas.width * next).coerceAtMost(0f)
        val minY = (canvas.height - canvas.height * next).coerceAtMost(0f)
        offsetX = x.coerceIn(minX, 0f)
        offsetY = y.coerceIn(minY, 0f)
    }

    /** Straight back to 1x, whole-disc-visible. See [ToolRow] for the control. */
    fun reset() {
        scale = 1f
        offsetX = 0f
        offsetY = 0f
    }
}

/**
 * **The editor's camera**, and the whole of the editor's framing.
 *
 * Two lines, because a camera is a zoom and a focus point (see [Camera]) and this
 * screen wants exactly one of each:
 *
 * - **Zoom until the matrix fills the drawing area** — a radius of [ZOOM_TARGET]
 *   of the canvas's shorter side. That, and the canvas, is all the cell pitch
 *   depends on.
 * - **Focus on the matrix**, so its centre lands at the centre of the canvas.
 *
 * "Centred" means *the viewport is centred on the matrix*, which is not the same
 * thing as putting the matrix in the middle of the phone. The device is then
 * off-centre in the frame — the plate's right edge and a strip of body come into
 * view on one side while the lens cluster goes off the other — and that is
 * correct: it is what looking closely at a real object does. Every version of this
 * before it tried to improve on that with a left bias, a low anchor and a
 * per-design-kind centring rule, and each one moved the subject to make one canvas
 * look better and re-framed the other caller in the process. There is no third
 * parameter here and there must not be one; if a framing looks wrong, the model is
 * wrong.
 *
 * A plain function of the size and nothing else, because the draw lambda and the
 * pointer handler both reach it and they *must* agree to the pixel or the user
 * paints a cell they did not touch. Two call sites, one expression.
 */
internal fun editorCamera(canvas: Size): Camera {
    val radius = min(canvas.width, canvas.height) * ZOOM_TARGET
    return Camera(zoom = radius / DeviceBack.matrix.radius, focus = DeviceBack.matrix.center)
}

/** Where the matrix lands on a resting editor canvas — [editorCamera]'s disc. */
internal fun baseDisc(canvas: Size): MatrixDisc = editorCamera(canvas).matrixDisc(canvas)

/**
 * The camera *as the user is currently looking through it* — the resting camera
 * with the pinch folded in.
 *
 * That a pinched camera is still just a camera is what keeps this to one line; see
 * [Camera.transformedBy] and [MatrixDisc.transformedBy], which are the same
 * argument made twice.
 */
private fun editorCamera(canvas: Size, transform: CanvasTransform): Camera =
    editorCamera(canvas).transformedBy(transform.scale, transform.offset, canvas)

/**
 * Where cell ([x], [y]) sits on screen, given the canvas's own bounds as the
 * layout reported them — the guided demo's way of putting a ghost finger on a
 * pixel it is about to paint.
 *
 * It goes through [baseDisc] rather than repeating it, which is what stops the
 * demo's finger and the editor's hit test from describing two different grids. No
 * [CanvasTransform] is involved because the tour never pinches: the resting disc
 * IS the disc, and if that ever stops being true the demo would paint the cell it
 * points at anyway — it calls `state.paint` with the cell, not with the
 * coordinate.
 */
internal fun demoCellCenter(canvasBounds: Rect, state: EditorState, x: Int, y: Int): Offset {
    val disc = baseDisc(canvasBounds.size)
    val local = matrixCellCenter(disc.center, disc.radius, state.selected.frame.size, x, y)
    return local + canvasBounds.topLeft
}

/**
 * The drawing surface: one Glyph Matrix, one finger painting on it and two
 * fingers moving it around.
 *
 * ## One pointer stream, two meanings
 *
 * Painting and pinching share a pointer stream and will fight if they are handled
 * separately — a `detectDragGestures` and a `detectTransformGestures` in
 * neighbouring `pointerInput`s both see every event, neither knows what the other
 * decided, and the result is a stroke smeared across the canvas while it zooms.
 * So there is exactly ONE gesture loop, it counts pressed pointers itself, and
 * the meaning of a gesture is decided once and never revisited:
 *
 * - **Nothing is painted on the down.** A gesture is a paint only once the finger
 *   has committed to being one finger drawing: it has moved past the touch slop
 *   (a drag) or it has lifted (a tap). Two fingers never land at the same
 *   instant, and the earlier one always jitters slightly before the later one
 *   arrives, so painting on the down would put a cell down at the start of every
 *   single pinch. This is what "a pinch must never paint a cell" costs, and it
 *   costs nothing visible: a drag still paints from the down position, one frame
 *   later, and a tap paints on release.
 * - **A second finger ends the stroke.** Not cancels it — *ends* it. Everything
 *   painted up to that instant stays, and it stays as ONE undo step, because the
 *   pre-stroke snapshot was pushed on the first cell the stroke actually changed
 *   (see [EditorState.paint]) and is still exactly one entry. The alternative,
 *   letting the stroke run while the canvas moves under it, paints a line the
 *   user never drew.
 * - **It does not come back.** Once a gesture has had two fingers on it, lifting
 *   one leaves a gesture that transforms and nothing more, until every finger is
 *   off the glass. Resuming the stroke from wherever the surviving finger happens
 *   to be is a line nobody asked for.
 *
 * ## Where the zoom is read
 *
 * In the draw lambda and in the pointer coroutine, both of which are outside
 * composition — see [CanvasTransform] and [EditorFrame]. A pinch invalidates this
 * one draw scope and recomposes nothing.
 */
@Composable
private fun EditorCanvas(
    state: EditorState,
    transform: CanvasTransform,
    onStrokeEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val base = MaterialTheme.colorScheme.onSurface
    val canvasDescription = stringResource(R.string.editor_canvas)
    Canvas(
        modifier
            .clipToBounds()
            .semantics { contentDescription = canvasDescription }
            // Keyed on the STATE, not on the selected frame: `state.paint` looks
            // the current frame up when it is called, so moving along the
            // timeline must not tear down and rebuild the gesture loop.
            .pointerInput(state, transform) {
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    // Re-read per gesture, not once for the life of the node:
                    // `pointerInput` is not restarted when the layout resizes, so
                    // a size cached out here would outlive the canvas it measured
                    // and paint the wrong cell after any relayout.
                    val canvas = size.toSize()
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // Has this gesture committed to painting, and has it been
                    // disqualified from ever doing so? Both latch.
                    var painting = false
                    var transforming = false
                    var last = down.position

                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        if (pressed == 0) break

                        if (pressed >= 2 || transforming) {
                            if (painting) {
                                painting = false
                                if (state.endStroke()) onStrokeEnd()
                            }
                            transforming = true
                            // calculateZoom/Pan report 1f/Zero across a pointer
                            // going down or up, so the frame a finger joins or
                            // leaves on cannot jolt the canvas.
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            if (zoom != 1f || pan != Offset.Zero) {
                                val centroid = event.calculateCentroid(useCurrent = false)
                                transform.onGesture(centroid, pan, zoom, canvas)
                            }
                            event.changes.forEach { if (it.pressed) it.consume() }
                            continue
                        }

                        val change = event.changes.firstOrNull { it.pressed } ?: continue
                        if (!painting) {
                            // Still undecided: jitter under the slop is not a
                            // stroke, it is a finger waiting for its partner.
                            if ((change.position - down.position).getDistance() <= slop) continue
                            painting = true
                            state.beginStroke()
                            // From the DOWN position, so the cell the gesture
                            // started on is painted even though the decision to
                            // paint came later.
                            paintSegment(state, transform, canvas, down.position, change.position)
                        } else {
                            paintSegment(state, transform, canvas, last, change.position)
                        }
                        last = change.position
                        change.consume()
                    }

                    when {
                        painting -> if (state.endStroke()) onStrokeEnd()
                        // Never moved, never grew a second finger: a tap, which
                        // paints the single cell it was on.
                        !transforming -> {
                            state.beginStroke()
                            paintSegment(state, transform, canvas, down.position, down.position)
                            if (state.endStroke()) onStrokeEnd()
                        }
                    }
                }
            },
    ) {
        // A DRAW-phase read of the zoom (see [CanvasTransform]): a pinch
        // invalidates this draw scope and recomposes nothing.
        //
        // The user's pinch needs no second transform here and gets none: it is
        // folded into the camera, and the composition of a pinch with a camera is
        // another camera. So the body, the island and the lenses pan and zoom with
        // the panel for free, and `drawDeviceBack` cannot tell the difference.
        val camera = editorCamera(size, transform)
        val disc = drawDeviceBack(base, camera)
        // The LEDs are drawn OUTSIDE the transform, straight onto the final disc,
        // so their corner radii and the gaps between them are not multiplied by
        // the zoom — a pixel grid magnified 3x would have 3x the rounding.
        //
        // Every read below is a DRAW-phase read (the selected index, the frame
        // buffer's revision, the onion-skin flag), which subscribes this node's
        // draw scope and nothing else — see [EditorFrame].
        val cells = state.cellsForDraw()
        drawMatrix(disc.center, disc.radius, state.frameSizeForDraw(), cells)
        state.onionCellsForDraw()?.let { ghost ->
            drawMatrixGhost(disc.center, disc.radius, state.frameSizeForDraw(), ghost, cells)
        }
    }
}

/**
 * Paints every cell the segment [from] -> [to] passes through, [from] and [to]
 * being raw canvas pixels as the pointer reported them.
 *
 * Runs on the pointer-input coroutine, i.e. off the composition path entirely —
 * see [EditorFrame]. Reading [transform] here is a read from that coroutine and
 * not from a composable body, which is what makes it legal; see [CanvasTransform].
 *
 * The zoom needs no inverse-mapping step of its own: [editorCamera] hands back the
 * camera as the user is looking through it, and `matrixCellAt` against *its* disc
 * is the inverse of what was drawn onto it. The step length is the transformed
 * pitch, so a zoomed-in stroke is resampled more finely in canvas pixels and
 * still cannot skip a cell.
 */
private fun paintSegment(
    state: EditorState,
    transform: CanvasTransform,
    canvas: Size,
    from: Offset,
    to: Offset,
) {
    val disc = editorCamera(canvas, transform).matrixDisc(canvas)
    val size = state.selected.frame.size
    val pitch = matrixCellPitch(disc.radius, size)
    if (pitch <= 0f) return
    val steps = max(1, ceil((to - from).getDistance() / (pitch * STROKE_STEP_FRACTION)).toInt())
    for (i in 0..steps) {
        val t = i.toFloat() / steps
        val point = Offset(from.x + (to.x - from.x) * t, from.y + (to.y - from.y) * t)
        val cell = matrixCellAt(point, disc.center, disc.radius, size) ?: continue
        state.paint(cell.x, cell.y)
    }
}

// ---------- controls ----------

/** Diameter of one palette swatch: a ≥ 48 dp target with room to show the level. */
private val SWATCH_SIZE = 52.dp

/** Half-width of the ring around the selected swatch. */
private val SELECTION_RING = 2.dp

/**
 * The edge drawn round a light-page swatch. White on `#F2F2FA` is 1.11:1, which
 * is a real step but not a boundary; one device-independent pixel of
 * `colorScheme.outline` makes it one without adding a second visual weight next
 * to the selection ring.
 */
private val HAIRLINE = 1.dp

/**
 * How much of a swatch the panel's black glass covers, and how much of THAT the
 * LED does, when the swatch is drawn on a light page.
 *
 * See [PaletteRow] for why a light page needs its own pair at all. The second
 * number is `0.29 / 0.62 = 0.47`, i.e. the LED keeps the same share of its glass
 * that it has in the dark-page swatch (0.46), so the two renderings differ in
 * how much glass is shown and in nothing else.
 */
private const val LIGHT_SWATCH_GLASS = 0.62f
private const val LIGHT_SWATCH_LED = 0.29f

/** The LED's share of a swatch drawn on a dark page, where the glass is the swatch. */
private const val DARK_SWATCH_LED = 0.46f

/**
 * Off / grey / white — read out of the design's own `levels` palette rather than
 * hardcoded, because `levels` is *data*. The format carries a palette precisely
 * so a design can use a different (or longer) set of brightnesses, and an editor
 * that painted a literal 2048 regardless would write cells the file does not
 * describe. Three is what the default palette offers and what fits here; a longer
 * palette shows its first three until a later phase gives it a proper picker.
 *
 * Each swatch is a scale model of what it paints: a white dot at that level's own
 * alpha, on the panel's own black. That is why the glass and the LED have no
 * theme colour in them — see `GlyphCanvas` for why the panel is deliberately
 * theme-independent.
 *
 * ## Why the swatch is built differently on a light page
 *
 * The selection ring is `colorScheme.onSurface` and always has been, which is
 * near-white in dark mode and near-black in light. Drawn where it used to be —
 * inside the rim of a glass-black disc — that made it a **near-black ring on a
 * near-black disc**: 1.03:1, invisible, so in light mode there was no way to tell
 * which shade was selected. That is the bug this addresses.
 *
 * The ring cannot simply move outward on its own, because on a light page it
 * would then be a near-black ring sitting a hairline away from a near-black disc,
 * and the two would read as one blob. What has to change is the ground the ring
 * is drawn on, so:
 *
 * - **Dark page: unchanged.** Glass fills the swatch, LED at [DARK_SWATCH_LED],
 *   ring inset at the rim. `#EFF0F7` on `#0E0E0E` is 17.0:1.
 * - **Light page:** the swatch's outer disc is `Color.White` with a hairline in
 *   `colorScheme.outline` (2.8:1 against the `#F2F2FA` background, which is all a
 *   1 dp boundary needs), and the glass shrinks to [LIGHT_SWATCH_GLASS] inside
 *   it. The ring is then near-black on white: **17.9:1**, and unmistakable.
 *
 * **The trap in "make the surround white", and why the glass survives.** The LED
 * is white at the level's own alpha, so it is not only the 100 % swatch that
 * would vanish on a white surround — 50 % white over white is white, and so is
 * 10 %. All three would disappear, not one. Keeping a black glass under the LED
 * is what makes the shades legible on a light page at all, and it keeps the
 * swatch *true*: you still pick a white dot and still paint a white pixel, which
 * an inked-in swatch (dark dot = bright LED) would have quietly inverted. The
 * three levels measure 1.3:1, 5.3:1 and 19.3:1 against the glass in both themes —
 * unlit, mid and full, in that order, and the unlit one is meant to be faint
 * because that is what an unlit LED looks like.
 */
@Composable
private fun PaletteRow(state: EditorState) {
    val ring = MaterialTheme.colorScheme.onSurface
    // The page, not the swatch, decides which of the two constructions is drawn:
    // what the ring needs is somewhere light to be black against (or somewhere
    // dark to be white against). Asked of the page's own luminance rather than of
    // `isSystemInDarkTheme()`, because the question really is "is the ground under
    // this row light" — one read, no second source of truth about the theme.
    val light = MaterialTheme.colorScheme.background.luminance() > 0.5f
    val hairline = MaterialTheme.colorScheme.outline
    val off = stringResource(R.string.editor_brush_off)
    // A pick-one-of-three, so: a selectable group of Role.RadioButton targets.
    // Each states its selection with a ring, which is what the ripple would
    // otherwise be saying a second time — see [NoRipple].
    NoRipple {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).selectableGroup(),
            horizontalArrangement = Arrangement.Center,
        ) {
            state.brushIndices.forEach { index ->
                val level = state.levelAt(index)
                val selected = index == state.brushIndex
                val percent = (level * 100f / DesignFrames.MAX_BRIGHTNESS).roundToInt()
                val label = if (level == 0) off else stringResource(R.string.editor_brush_level, percent)
                Box(
                    Modifier
                        .padding(horizontal = 8.dp)
                        .size(SWATCH_SIZE)
                        // Tagged per swatch rather than once for the row: the
                        // tour needs a tap point for each shade, and the union of
                        // the three is a tighter highlight than the full-width
                        // row they are centred in. See [DemoTargets.unionOf].
                        .demoTarget(DemoTarget.PALETTE, index)
                        .selectable(
                            selected = selected,
                            role = Role.RadioButton,
                            onClick = { state.brushIndex = index },
                        )
                        .semantics { contentDescription = label },
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        val radius = size.minDimension / 2f
                        // On a light page the swatch sits on a white disc with a
                        // hairline edge, and the panel's glass is an inner lens on
                        // it; on a dark one the glass IS the swatch, exactly as
                        // before. See [PaletteRow] for the contrast arithmetic.
                        val glass = if (light) radius * LIGHT_SWATCH_GLASS else radius
                        val led = radius * if (light) LIGHT_SWATCH_LED else DARK_SWATCH_LED
                        if (light) {
                            drawCircle(Color.White, radius = radius, center = center)
                            drawCircle(
                                hairline,
                                radius = radius - HAIRLINE.toPx() / 2f,
                                center = center,
                                style = Stroke(width = HAIRLINE.toPx()),
                            )
                        }
                        drawCircle(MATRIX_DISC_COLOR, radius = glass, center = center)
                        // The same brightness-to-alpha mapping the panel uses, so
                        // what you pick is literally what lights up.
                        val alpha = level / DesignFrames.MAX_BRIGHTNESS.toFloat()
                        drawCircle(
                            Color.White.copy(alpha = if (level == 0) UNLIT_SWATCH_ALPHA else alpha),
                            radius = led,
                            center = center,
                        )
                        if (selected) {
                            val half = SELECTION_RING.toPx()
                            drawCircle(
                                ring,
                                radius = radius - half,
                                center = center,
                                style = Stroke(width = half * 2f),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** The "off" swatch shows an unlit LED, at the same alpha the matrix draws one. */
private const val UNLIT_SWATCH_ALPHA = 0.10f

@Composable
private fun ToolRow(
    state: EditorState,
    transform: CanvasTransform,
    dynamic: Boolean,
    onChanged: () -> Unit,
    onMarquee: () -> Unit,
) {
    // The one read of the zoom outside a draw lambda in the whole editor, and it
    // goes through `derivedStateOf` for that reason: a pinch writes `scale` on
    // every pointer sample, and reading it directly here would recompose this row
    // 120 times a second to answer a question whose answer changed once. The
    // derived Boolean only notifies its reader when it actually flips, so this
    // row recomposes exactly when the 1x boundary is crossed. Same phase
    // discipline as [EditorFrame], enforced with the tool built for it.
    val zoomed by remember(transform) { derivedStateOf { transform.scale > 1f } }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Plain icon buttons, so they keep their ripple: unlike the swatches
        // these have no state of their own to animate, and the ripple is their
        // only acknowledgement of a tap.
        IconButton(
            onClick = { if (state.undo()) onChanged() },
            modifier = Modifier.demoTarget(DemoTarget.TOOLS, 0),
            enabled = state.canUndo,
        ) {
            Icon(Icons.AutoMirrored.Outlined.Undo, contentDescription = stringResource(R.string.editor_undo))
        }
        IconButton(
            onClick = { if (state.redo()) onChanged() },
            modifier = Modifier.demoTarget(DemoTarget.TOOLS, 1),
            enabled = state.canRedo,
        ) {
            Icon(Icons.AutoMirrored.Outlined.Redo, contentDescription = stringResource(R.string.editor_redo))
        }
        // **A bucket pair, not a grid pair.** These two used to be `GridOff` and
        // `GridOn`, which is wrong twice over. Wrong in meaning: in Material those
        // two are "hide gridlines" / "show gridlines", a *view* toggle, and these
        // buttons repaint every cell. And wrong in form: side by side they were
        // the same 4x4 lattice differing only by a diagonal stroke, so the row's
        // two most destructive controls were also its two least distinguishable —
        // which is exactly the "these all look the same" complaint they drew.
        //
        // `FormatColorReset` (a struck-through drop: no paint) and
        // `FormatColorFill` (a tipped bucket pouring) are one family, read as
        // opposites at 24 dp, and describe what actually happens — this is a
        // painting tool and both buttons are a paint verb applied to the whole
        // canvas.
        IconButton(
            onClick = { if (state.fillAll(0)) onChanged() },
            modifier = Modifier.demoTarget(DemoTarget.TOOLS, 2),
        ) {
            Icon(Icons.Outlined.FormatColorReset, contentDescription = stringResource(R.string.editor_clear))
        }
        // Fills with the SELECTED brush, not with white: "fill" means "everything
        // becomes what I am painting with", which also makes a mid-grey wash one
        // tap away.
        IconButton(
            onClick = { if (state.fillAll(state.brushValue())) onChanged() },
            modifier = Modifier.demoTarget(DemoTarget.TOOLS, 3),
        ) {
            Icon(Icons.Outlined.FormatColorFill, contentDescription = stringResource(R.string.editor_fill))
        }
        // **Appended, and appended for a reason.** The guided tour taps this row
        // by index — 0 undo, 1 redo, 2 clear, 3 fill — so a new tool goes on the
        // end or every caption in the script points one button to the left. It
        // being conditional costs those indices nothing, because it is the last
        // of the numbered ones: 0 to 3 are above and neither of the two controls
        // below is a demo target.
        //
        // **DYNAMIC ONLY.** A marquee IS an animation, so on a static design this
        // button could only work by converting the document — the generator sets
        // `kind` itself — and `kind` is a decision the user made when they created
        // the design, is called irreversible everywhere else in this file (see
        // [EditorState.deleteFrame]), and has nothing on this screen to change it
        // back with. A button whose real effect is "turn this into a different
        // sort of design" is not a text tool, so it is not offered here. The
        // promotion still exists on the assistant's `marquee_text`, where it is
        // asked for in words and answered in a message that says what it did.
        //
        // `TextFields` rather than `Abc`, `Title` or `FormatSize`, all of which
        // are on the classpath. It is Material's own icon for "a text field goes
        // here", it is drawn as a large letter beside a small one — which is the
        // "Aa" idea, and now literally what this button produces, since the face
        // has both cases — and its strokes are solid bars at the same weight as
        // the paint bucket and the arrows beside it. `Abc` draws three lower-case
        // letterforms in hairlines: at 24 dp in this row it reads as spellcheck
        // and as a lighter icon than its neighbours, which is the "these all look
        // the same" complaint in reverse. `Title` and `FormatSize` are a heading
        // and a type ramp, and this button changes neither.
        if (dynamic) {
            IconButton(
                onClick = onMarquee,
                modifier = Modifier.demoTarget(DemoTarget.TOOLS, 4),
            ) {
                Icon(Icons.Outlined.TextFields, contentDescription = stringResource(R.string.marquee_tool))
            }
        }
        // The way back to 1x. It appears only while there is a zoom to undo, the
        // same rule the onion-skin toggle below follows — and it is a button
        // rather than a double-tap because on a paint canvas a double-tap is two
        // taps, and two taps paint. It doubles as the only affordance the pinch
        // has: seeing it arrive is how you learn the gesture exists.
        if (zoomed) {
            IconButton(onClick = { transform.reset() }) {
                Icon(
                    Icons.Outlined.ZoomOutMap,
                    contentDescription = stringResource(R.string.editor_zoom_reset),
                )
            }
        }
        // Onion skin appears only once there is a previous frame to ghost, which
        // is why it was not in the last phase: a toggle that can never do
        // anything is worse than an absent one. It changes no pixels, so it does
        // NOT mark the design dirty.
        //
        // Outlined while OFF, like every other icon toggle in the app: this is
        // the one STATEFUL control in a row of momentary icon buttons, and with
        // its unchecked container equal to the surface behind it (see
        // [offStateOutline]) it looked like a fifth plain button.
        if (state.canOnionSkin) {
            NoRipple {
                FilledIconToggleButton(
                    checked = state.onionSkin,
                    onCheckedChange = { state.onionSkin = it },
                    shapes = IconButtonDefaults.toggleableShapes(),
                    modifier = Modifier.offStateOutline(state.onionSkin),
                ) {
                    Icon(
                        Icons.Outlined.Layers,
                        contentDescription = stringResource(
                            if (state.onionSkin) R.string.editor_onion_on else R.string.editor_onion_off,
                        ),
                    )
                }
            }
        }
    }
}

/**
 * Which device you are drawing for — a pick-ONE-of-two, which is what MD3
 * specifies segmented buttons for and the same control the Create tab's
 * static/dynamic switch uses.
 *
 * **Labelled by product name, never by codename.** These used to read
 * "bellsprout" and "arbok", on the argument that somebody comparing the editor
 * against a JSON file they were sent needs the two to say the same thing. They
 * do — but this is the switch that tells a user which of their phones the
 * drawing on screen is for, and no user knows which phone "arbok" is. The
 * codenames stay in the file and in the format spec, which is where the people
 * who need them look; see `ui/DeviceNames.kt`.
 *
 * The names are long enough that the default segment styling clips them, so the
 * label is [SegmentLabel] — which finds the width in the type scale and in a
 * second line rather than in the selection check-mark. See there.
 *
 * **One segment per variant the design actually has**, and composed at all only
 * when there is more than one of them (the caller's condition — whether a row
 * exists is a layout question). A segment for a size the design does not carry
 * would be a switch that *creates* artwork, because `EditorState.composed` writes
 * whichever variant is open back into the design; adding a size is a decision,
 * and it is made in [DesignSettings], deliberately somewhere else.
 */
@Composable
private fun VariantRow(state: EditorState, onSwitched: () -> Unit) {
    val present = state.variantsPresent
    NoRipple {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            present.forEachIndexed { i, codename ->
                SegmentedButton(
                    selected = codename == state.codename,
                    onClick = { if (state.switchTo(codename)) onSwitched() },
                    shape = SegmentedButtonDefaults.itemShape(index = i, count = present.size),
                ) {
                    SegmentLabel(stringResource(codename.displayNameRes()))
                }
            }
        }
    }
}

// ---------- state ----------

/**
 * How many strokes back the editor can go, **per frame**.
 *
 * Not unbounded: an undo entry is a whole frame, so at 25x25 each is 2.5 kB and
 * a long session would grow without limit. 32 is deep enough that no plausible
 * "undo until it looks right" run reaches the floor. It is now a per-frame bound
 * rather than a per-editor one, so a 240-frame design could in principle hold a
 * great deal of history — but only for frames that have actually been edited
 * 32 times each, which is not a session anybody has.
 */
private const val UNDO_DEPTH = 32

/**
 * How many **whole-document** steps back the editor can go — the other stack the
 * undo arrow drives, see [EditorState.undo].
 *
 * Four rather than [UNDO_DEPTH]'s 32, because these entries are a different size
 * of thing: a per-frame snapshot is one frame, one of these is an entire design,
 * and a 240-frame arbok animation encodes to well over a megabyte. Four is what
 * the tools that produce them are actually used like — a marquee is generated,
 * looked at, and either kept or taken back — and the alternative to a shallow
 * bound here is not a deeper history, it is an editor that holds tens of
 * megabytes of designs nobody is going to ask for.
 */
private const val DOCUMENT_UNDO_DEPTH = 4

/**
 * The frame being drawn on, and the reason a moving finger does not recompose
 * this screen.
 *
 * An `IntArray` is not snapshot state, so mutating a cell notifies nobody. The
 * obvious fix — holding the array in a `mutableStateOf` and reassigning a copy
 * per pointer event — works, and is exactly the mistake `NavChip`'s KDoc in
 * `MainActivity` documents at length: it publishes a new value into COMPOSITION
 * on every sample of a gesture, recomposing a whole subtree per frame for a
 * change only the draw phase cares about. That was the nav-pill stutter, and a
 * paint canvas is the same shape of problem with more events.
 *
 * So the buffer stays a plain array and [revision] — one Int of snapshot state —
 * carries the invalidation. [cellsForDraw] and [revisionForDraw] are the only
 * readers that touch it, they are called **only from inside a `Canvas` draw
 * lambda** (the editor canvas, and each timeline thumbnail), and a draw-phase
 * snapshot read subscribes the DRAW scope and nothing else. Painting a cell
 * therefore schedules a redraw of the canvas and of that frame's thumbnail; it
 * does not recompose the palette, the tool row, the app bar, the timeline or the
 * segmented buttons, none of which changed.
 *
 * Everything else reaches the pixels through [copyOfCells] (undo snapshots, and
 * the encode on the way to disk), which never touches [revision] — so no other
 * caller can subscribe to the buffer by accident.
 */
@Stable
internal class EditorFrame(val size: Int, private val cells: IntArray) {

    /** Bumped by every mutation; see this class's KDoc for who may read it. */
    private var revision by mutableIntStateOf(0)

    /**
     * The live buffer. **Draw phase only** — calling this from a composable body
     * would subscribe that composable to every painted pixel.
     */
    @Suppress("UNUSED_EXPRESSION")
    fun cellsForDraw(): IntArray {
        revision
        return cells
    }

    /**
     * A number that changes whenever the pixels do, for cache invalidation.
     * **Draw phase only**, for the same reason as [cellsForDraw].
     */
    fun revisionForDraw(): Int = revision

    /**
     * The same number, for a snapshot observer that is **not** the composition —
     * in practice the live preview's `snapshotFlow`, which installs an observer
     * of its own and resumes a coroutine rather than invalidating anything.
     *
     * Named apart from [revisionForDraw] so that every legal reader of
     * [revision] stays greppable and each one carries the rule that makes it
     * legal. Reading it from a composable body is still the mistake this class
     * exists to prevent.
     */
    fun revisionForSnapshot(): Int = revision

    /** A detached copy, for undo snapshots and for encoding a save. */
    fun copyOfCells(): IntArray = cells.copyOf()

    /** Writes one cell. Returns false — and invalidates nothing — if unchanged. */
    fun set(x: Int, y: Int, value: Int): Boolean {
        if (x < 0 || y < 0 || x >= size || y >= size) return false
        val i = y * size + x
        if (cells[i] == value) return false
        cells[i] = value
        revision++
        return true
    }

    /** Sets every cell. False if the frame already looked like that. */
    fun fill(value: Int): Boolean {
        if (cells.all { it == value }) return false
        cells.fill(value)
        revision++
        return true
    }

    /** Puts a previous snapshot back (undo/redo). */
    fun restore(snapshot: IntArray): Boolean {
        if (snapshot.size != cells.size || snapshot.contentEquals(cells)) return false
        snapshot.copyInto(cells)
        revision++
        return true
    }
}

/**
 * One frame on the timeline: its pixels, how long it is shown, its own undo
 * history, and its cached thumbnail.
 *
 * ## The id is the point
 *
 * [id] is a monotonic counter handed out by [EditorState], and it is what the
 * timeline's `LazyRow` keys on. Nothing about the frame's *content* can serve
 * that purpose: duplicating a frame is a first-class operation, so two
 * byte-identical frames with identical durations are a completely normal thing to
 * have, and keying on content would make Compose treat them as the same item —
 * `animateItem` would then cross-fade one frame's thumbnail into another's slot
 * during a reorder, which looks like the editor swapping the user's art around.
 * The id is per frame *object*: a duplicate gets a new one, and it travels with
 * the frame through every reorder.
 *
 * ## Undo is per frame, deliberately
 *
 * The history lives here rather than on [EditorState] because the alternative is
 * a real trap: with one shared stack, drawing on frame 3, moving to frame 7 and
 * pressing undo would silently restore frame 3's pixels — into frame 7 if the
 * stack is applied to "the current frame", or invisibly if it is applied to the
 * frame it came from. Both are the editor changing something the user is not
 * looking at. Attaching the stack to the frame makes undo mean "undo what I did
 * *here*", which is the only reading that is never surprising.
 */
@Stable
internal class TimelineEntry(
    val id: Long,
    val frame: EditorFrame,
    durationMs: Int = DEFAULT_FRAME_DURATION_MS,
) {
    /** Always inside the codec's legal range — see [clampDuration]. */
    var durationMs by mutableIntStateOf(clampDuration(durationMs))
        private set

    var canUndo by mutableStateOf(false)
        private set

    var canRedo by mutableStateOf(false)
        private set

    /** The rendered thumbnail; see [ThumbnailCache] for when it is thrown away. */
    val thumbnail = ThumbnailCache()

    private val undoStack = ArrayDeque<IntArray>()
    private val redoStack = ArrayDeque<IntArray>()

    /** False if the value did not change once clamped. */
    fun setDuration(ms: Int): Boolean {
        val next = clampDuration(ms)
        if (next == durationMs) return false
        durationMs = next
        return true
    }

    fun pushUndo(snapshot: IntArray) {
        undoStack.addLast(snapshot)
        if (undoStack.size > UNDO_DEPTH) undoStack.removeFirst()
        // A new edit forks the history: whatever had been undone is now
        // unreachable, and keeping it would let redo paste in a frame from a
        // branch the user has left.
        redoStack.clear()
        refreshFlags()
    }

    fun undo(): Boolean {
        val previous = undoStack.removeLastOrNull() ?: return false
        redoStack.addLast(frame.copyOfCells())
        if (redoStack.size > UNDO_DEPTH) redoStack.removeFirst()
        frame.restore(previous)
        refreshFlags()
        return true
    }

    fun redo(): Boolean {
        val next = redoStack.removeLastOrNull() ?: return false
        undoStack.addLast(frame.copyOfCells())
        if (undoStack.size > UNDO_DEPTH) undoStack.removeFirst()
        frame.restore(next)
        refreshFlags()
        return true
    }

    private fun refreshFlags() {
        canUndo = undoStack.isNotEmpty()
        canRedo = redoStack.isNotEmpty()
    }
}

/**
 * Everything the editor is editing: the design, which variant is open, the frames
 * of that variant, which one is selected, the brush and the histories.
 *
 * The `Design` is the model of record and [frames] is the working copy of the
 * open variant's frame list. [composed] folds one back into the other and is the
 * only place that happens, so a variant switch and a save can never disagree
 * about what the art currently is.
 *
 * **Undo has two grains and one button.** A stroke is a frame's business and its
 * history lives on the [TimelineEntry] it was made on; a tool that rewrites the
 * whole document — the scrolling-text generator — cannot be expressed that way at
 * all, so it puts a whole `Design` on [documentUndo] instead. Both are reached
 * through [undo] and [redo], because the user has one idea of what the arrow in
 * the tool row does.
 */
@Stable
internal class EditorState(design: Design, codename: PokemonCodename) {

    var design by mutableStateOf(design)
        private set

    var codename by mutableStateOf(codename)
        private set

    /**
     * Handed out to every [TimelineEntry] ever made by this editor and never
     * reused, including across a variant switch. Monotonic is all the timeline
     * needs; it is not persisted and means nothing outside this instance.
     */
    private var nextFrameId = 0L

    /**
     * The open variant's frames, in play order. A `SnapshotStateList` because
     * adding, deleting and reordering are structural changes the timeline must
     * recompose for — unlike painting, which must not (see [EditorFrame]).
     */
    val frames = mutableStateListOf<TimelineEntry>().apply {
        addAll(loadFrames(design, codename))
    }

    var selectedIndex by mutableIntStateOf(0)
        private set

    /** A drawing aid, not content: never persisted, off on every open. */
    var onionSkin by mutableStateOf(false)

    /**
     * Which palette entry the finger paints, defaulting to the brightest swatch.
     * Changed by a tap and never by a drag, so reading it during composition
     * costs nothing.
     */
    var brushIndex by mutableIntStateOf(design.levels.lastIndex.coerceIn(0, MAX_SWATCHES - 1))

    /**
     * The frame as it was when the current stroke started, held until the stroke
     * actually changes something. This is what makes one stroke one undo step
     * rather than one per cell — and what stops a stroke that repainted white
     * cells white from leaving a do-nothing entry in the history.
     */
    private var strokeBase: IntArray? = null
    private var strokeChanged = false

    private var dirty = false

    /**
     * The whole-document half of undo: the design as it stood before a
     * [replaceDesign] that asked to be undoable, and the ones taken back off it.
     *
     * **Two stacks, one button.** These sit behind the same `undo` / `redo`
     * controls the per-frame stacks drive (see [undo]), because the user has one
     * idea of what undo is and it is not "undo, except for the tools that change
     * more than one frame". A step is a whole [Design] — kind, loop, palette and
     * every variant's frames — which is the only snapshot that can express what a
     * document replacement did.
     *
     * **Shallow on purpose.** A per-frame snapshot is one frame's cells; one of
     * these is an entire design, up to 240 encoded frames of it, so
     * [UNDO_DEPTH]'s 32 would be tens of megabytes of history for a tool that is
     * used a handful of times per design. [DOCUMENT_UNDO_DEPTH] is what "I did
     * that by mistake" actually needs.
     *
     * `ArrayDeque` is not snapshot state, so the two depths beside it are: the
     * tool row reads [canUndo] during composition and has to recompose when a
     * marquee lands, not on whatever unrelated change happens next.
     */
    private val documentUndo = ArrayDeque<Design>()
    private val documentRedo = ArrayDeque<Design>()
    private var documentUndoDepth by mutableIntStateOf(0)
    private var documentRedoDepth by mutableIntStateOf(0)

    /** Serialises saves, so a debounced write cannot overtake a flushed one. */
    private val saveMutex = Mutex()

    /** The frame every tool acts on. Never out of range: [frames] is never empty. */
    val selected: TimelineEntry get() = frames[selectedIndex.coerceIn(0, frames.lastIndex)]

    /**
     * Whether the tool row's undo arrow has anything to do: a stroke on the
     * selected frame, or a whole-document step behind it. See [undo] for which of
     * the two a press actually takes.
     */
    val canUndo: Boolean get() = selected.canUndo || documentUndoDepth > 0

    val canRedo: Boolean get() = selected.canRedo || documentRedoDepth > 0

    /** Onion skin needs a previous frame to ghost, and an animation to be part of. */
    val canOnionSkin: Boolean
        get() = design.kind == DesignKind.DYNAMIC && frames.size > 1

    /** True while the timeline is at the format's frame ceiling. */
    val atFrameLimit: Boolean get() = frames.size >= DesignCodec.MAX_FRAMES

    /** How long one pass of the animation runs, in milliseconds. */
    val totalDurationMs: Int get() = frames.sumOf { it.durationMs }

    /** The palette indices offered as swatches. */
    val brushIndices: List<Int> get() = (0 until min(design.levels.size, MAX_SWATCHES)).toList()

    fun levelAt(index: Int): Int =
        design.levels.getOrElse(index) { 0 }.coerceIn(0, DesignFrames.MAX_BRIGHTNESS)

    fun brushValue(): Int = levelAt(brushIndex)

    // ---- draw-phase readers ----
    //
    // Three one-liners rather than letting the canvas reach through `selected`
    // itself, so that every read of a mutable value the pointer path writes is
    // named, greppable, and documented as draw-phase-only.

    /** **Draw phase only.** The selected frame's live buffer. */
    fun cellsForDraw(): IntArray = selected.frame.cellsForDraw()

    /** **Draw phase only.** The selected frame's grid size. */
    fun frameSizeForDraw(): Int = selected.frame.size

    /**
     * **Draw phase only.** The frame to ghost beneath the selected one, or null.
     *
     * The previous frame is the one before this in play order — except at frame
     * 0 of a **looping** design, where the frame that actually precedes it on the
     * matrix is the last one. Getting that right is the difference between being
     * able to close a loop cleanly and having to guess at the seam.
     */
    fun onionCellsForDraw(): IntArray? {
        if (!onionSkin || !canOnionSkin) return null
        val previous = if (selectedIndex > 0) {
            frames[selectedIndex - 1]
        } else if (design.loop) {
            frames.last()
        } else {
            return null
        }
        return previous.frame.cellsForDraw()
    }

    // ---- the live preview ----

    /**
     * **Snapshot-observer only** (the preview's `snapshotFlow` — never a
     * composable body, for [EditorFrame]'s reasons).
     *
     * A value that changes if and only if what the matrix should be showing
     * changes: the pixels of the selected frame, or which frame is selected. A
     * `Pair` rather than a packed number on purpose — the flow compares
     * consecutive values with `equals`, and a hash that happened to collide
     * would silently swallow a push and leave the panel a frame behind.
     */
    fun previewToken(): Pair<Long, Int> {
        val entry = selected
        return entry.id to entry.frame.revisionForSnapshot()
    }

    /** A detached copy of the frame the matrix should be showing. */
    fun copyOfSelectedCells(): IntArray = selected.frame.copyOfCells()

    // ---- painting ----

    fun beginStroke() {
        strokeBase = selected.frame.copyOfCells()
        strokeChanged = false
    }

    fun paint(x: Int, y: Int) {
        val entry = selected
        if (!entry.frame.set(x, y, brushValue())) return
        // Pushed on the FIRST cell this stroke actually changes, then nulled, so
        // the remaining hundred cells of the same drag add nothing to the stack.
        strokeBase?.let { base ->
            strokeBase = null
            entry.pushUndo(base)
        }
        strokeChanged = true
        markEdited()
    }

    /** Ends the stroke; true if it changed anything and so is worth saving. */
    fun endStroke(): Boolean {
        strokeBase = null
        val changed = strokeChanged
        strokeChanged = false
        return changed
    }

    // ---- tools ----

    fun fillAll(value: Int): Boolean {
        val entry = selected
        val base = entry.frame.copyOfCells()
        if (!entry.frame.fill(value)) return false
        entry.pushUndo(base)
        markEdited()
        return true
    }

    /**
     * One step back, over whichever kind of step is the innermost one.
     *
     * **The selected frame's own history first, the document behind it.** Read as
     * a rule that is: *undo the strokes you made here, then undo the thing that
     * made this canvas.* A marquee arrives having rebuilt the frame list, so every
     * frame's history is empty and the first press takes the document step; draw
     * two strokes on the marquee afterwards and the first two presses take those,
     * because they are edits *to* the marquee and cannot be undone from underneath
     * it.
     *
     * That ordering is exact for the frame being looked at and approximate across
     * frames — a stroke made on frame 5 and then abandoned for frame 0 is behind a
     * document step that predates it. It is not lost by the wrong choice being
     * made: undoing the marquee discards the marquee's frames, and a stroke drawn
     * onto one of them goes with the frame it was drawn on. There is no reading of
     * "put the canvas back the way it was" that keeps it.
     */
    fun undo(): Boolean {
        if (selected.undo()) {
            dirty = true
            return true
        }
        val previous = documentUndo.removeLastOrNull() ?: return false
        push(documentRedo, composed())
        adopt(previous)
        refreshDocumentFlags()
        return true
    }

    /** The mirror of [undo], down to which stack is consulted first. */
    fun redo(): Boolean {
        if (selected.redo()) {
            dirty = true
            return true
        }
        val next = documentRedo.removeLastOrNull() ?: return false
        push(documentUndo, composed())
        adopt(next)
        refreshDocumentFlags()
        return true
    }

    /**
     * Marks the design changed, and forks the whole-document history the way
     * [TimelineEntry.pushUndo] forks the per-frame one.
     *
     * A redo step is only reachable while nothing has happened since the undo that
     * created it. Left alone, "undo the marquee, draw something, redo" would paste
     * the marquee over the drawing — a branch the user has left, which is exactly
     * what the per-frame stack already refuses to do.
     */
    private fun markEdited() {
        dirty = true
        if (documentRedo.isNotEmpty()) {
            documentRedo.clear()
            refreshDocumentFlags()
        }
    }

    private fun push(stack: ArrayDeque<Design>, step: Design) {
        stack.addLast(step)
        if (stack.size > DOCUMENT_UNDO_DEPTH) stack.removeFirst()
    }

    private fun refreshDocumentFlags() {
        documentUndoDepth = documentUndo.size
        documentRedoDepth = documentRedo.size
    }

    // ---- the timeline ----

    fun select(index: Int) {
        selectedIndex = index.coerceIn(0, frames.lastIndex)
    }

    /** Per-frame duration, on the selected frame. */
    fun setSelectedDuration(ms: Int): Boolean {
        if (!selected.setDuration(ms)) return false
        markEdited()
        return true
    }

    /**
     * Inserts a blank frame after the selected one and selects it — the way a
     * new frame is almost always wanted: keep drawing, one step further on.
     */
    fun addFrame(): Boolean {
        if (atFrameLimit) return false
        val at = selectedIndex + 1
        frames.add(
            at,
            TimelineEntry(
                id = nextFrameId++,
                frame = EditorFrame(codename.size, IntArray(codename.cellCount)),
                // Inherits the selected frame's timing rather than resetting to
                // the default: an animation being built at 60 ms a frame should
                // not suddenly step to 120 halfway through.
                durationMs = selected.durationMs,
            ),
        )
        selectedIndex = at
        markEdited()
        return true
    }

    /**
     * Copies the selected frame in place after itself — the tweening workflow
     * this whole feature exists for: duplicate, nudge a few pixels, repeat.
     *
     * The copy gets a **fresh id** and an empty history: it is a different frame
     * that happens to look the same, which is exactly what the timeline's `key`
     * has to be able to tell apart.
     */
    fun duplicateFrame(): Boolean {
        if (atFrameLimit) return false
        val source = selected
        val at = selectedIndex + 1
        frames.add(
            at,
            TimelineEntry(
                id = nextFrameId++,
                frame = EditorFrame(source.frame.size, source.frame.copyOfCells()),
                durationMs = source.durationMs,
            ),
        )
        selectedIndex = at
        markEdited()
        return true
    }

    /**
     * Deletes the selected frame.
     *
     * **The last remaining frame cannot be deleted.** The alternative —
     * converting the design to static — was considered and rejected: `kind` is
     * chosen deliberately at creation, it decides how the Essential Key behaves
     * and whether loop and key mode mean anything, and silently changing it as a
     * side effect of a delete would make an unrelated decision on the user's
     * behalf. There is also nowhere to change it back from. `clear` empties a
     * frame, which is what "delete" is reaching for at that point, so nothing is
     * unreachable — the button is simply disabled, with a hint saying why.
     */
    fun deleteFrame(): Boolean {
        if (frames.size <= 1) return false
        val removed = selectedIndex
        frames.removeAt(removed)
        selectedIndex = selectionAfterDelete(selectedIndex, removed, frames.size)
        markEdited()
        return true
    }

    /**
     * Moves a frame one (or more) places along the timeline, keeping the
     * selection on whichever frame it was already on.
     */
    fun moveFrame(from: Int, to: Int): Boolean {
        if (!moveItem(frames, from, to)) return false
        selectedIndex = selectionAfterMove(selectedIndex, from, to)
        markEdited()
        return true
    }

    // ---- playback ----

    fun setLoop(loop: Boolean): Boolean {
        if (design.loop == loop) return false
        design = design.copy(loop = loop)
        markEdited()
        return true
    }

    /**
     * Changes the key mode, and **deliberately leaves `loop` alone**.
     *
     * `loop` means nothing in [KeyMode.PLAY_ONCE] — `CustomScreen` never reads it
     * there — so the editor stops offering the control (see `PlaybackRow`). It
     * does not follow that the stored value should be cleared: switching back to
     * play / pause must restore the repeat setting the user chose, not a reset
     * one. Not touching it is both the least surprising behaviour and the one
     * that needs no extra state to remember.
     */
    fun setKeyMode(mode: KeyMode): Boolean {
        if (design.keyMode == mode) return false
        design = design.copy(keyMode = mode)
        markEdited()
        return true
    }

    // ---- variants ----

    /**
     * The devices this design has artwork for, in [PokemonCodename] declaration
     * order.
     *
     * **Derived from the variants present, never from a stored "target devices"
     * field**, and that is the design decision rather than an implementation
     * detail. The new-design dialog only chooses what gets *seeded*; from that
     * moment on the file's own keys are the answer, so an imported design
     * carrying both sizes gets the switcher with no special-casing, the format
     * needs no new field and no version bump, and there is no second source of
     * truth that could disagree with the artwork.
     *
     * Read from composition — `design` is snapshot state and [addVariant] and
     * [switchTo] both reassign it, so the switcher appears the frame a variant
     * is added.
     */
    val variantsPresent: List<PokemonCodename>
        get() = PokemonCodename.entries.filter { design.variantFor(it) != null }

    /**
     * A device this design has no artwork for at all, or null when it has every
     * one this build knows.
     *
     * This is what the "Add ... artwork" action in [DesignSettings] offers, and
     * naming the *missing* device is the point: the user picked one phone at
     * creation and is now being told, by name, which one they can still add.
     */
    val missingVariant: PokemonCodename?
        get() = PokemonCodename.entries.firstOrNull { design.variantFor(it) == null }

    /**
     * Gives this design a blank canvas for [target]. False if it already had one.
     *
     * The escape hatch for "this phone only": without it, the choice made in the
     * new-design dialog would be a trapdoor, since the variant switcher — the only
     * other way to reach a second geometry — is hidden precisely when there is
     * only one. `kind` is irreversible because `loop` and `keyMode` depend on it;
     * nothing depends on this, so it is not a one-way door.
     *
     * An **empty** variant, not one with a blank frame, exactly as a second size
     * has always been seeded: "a blank canvas is waiting for that size" is a
     * state the whole app already reads correctly (the Create tab marks it
     * "(empty)", `showDesignOnMatrix` declines it by name, `CustomScreen` shows
     * its placeholder), whereas one all-dark frame would claim art that nobody
     * has drawn. [loadFrames] invents the frame to draw on when the user
     * actually switches to it.
     *
     * The live frames are deliberately NOT folded in here: nothing is being left,
     * so `composed()` at save time still writes the open variant over the top of
     * this map and the new key survives alongside it.
     */
    fun addVariant(target: PokemonCodename): Boolean {
        if (design.variantFor(target) != null) return false
        design = design.copy(variants = design.variants + (target.codename to DesignVariant()))
        markEdited()
        return true
    }

    /**
     * Opens the other geometry's frames. False if it was already open.
     *
     * The live frames are folded back into [design] first, so the variant being
     * left keeps every pixel of it: **editing one variant never touches the
     * other**, which is the whole point of the format's blank-canvas rule. Art is
     * never scaled between panel sizes, so the two are independent drawings that
     * happen to share a name — and independent *timelines*, since a 13x13
     * animation and a 25x25 one need not have the same number of frames.
     *
     * Every undo history goes with them, and has to — its snapshots are arrays of
     * the outgoing geometry's length, and restoring a 169-cell frame into a
     * 625-cell one is not an operation.
     *
     * ## Undo does not survive a switch, and that is the decision
     *
     * [frames] is rebuilt from [design], so the outgoing [TimelineEntry] objects
     * are dropped and coming back gives fresh ones with empty stacks. Keeping
     * both variants' entries alive instead was considered and **rejected**:
     *
     *  - It costs the memory of a whole second editor, held for as long as the
     *    activity lives. Not the frames — those are already in [design] — but up
     *    to [UNDO_DEPTH] snapshots *per frame*, at 2.5 kB each on arbok. The
     *    format allows 240 frames.
     *  - It puts [composed] in the position of having to reconcile two live
     *    frame lists against one `Design`, when the entire reason that function
     *    exists is to be the single point where the working copy and the model
     *    of record meet. A second live list is a second thing that can disagree
     *    with the file, which is the class of bug this state object is shaped to
     *    prevent.
     *  - The user-visible cost is small and matches the mental model the rest of
     *    this KDoc argues for: the two variants are *independent drawings*.
     *    Opening the other one and finding you cannot undo strokes you made in
     *    the first is what opening a different drawing does.
     *
     * The art itself is never at risk either way — it is folded into [design]
     * before the swap, on the line below. What is lost is the ability to step
     * backwards through edits made before the switch, and only that.
     *
     * **The whole-document steps go too**, and by the same rule rather than by
     * necessity: a [Design] carries both variants, so one of those snapshots
     * *could* be restored from over here. It should not be. Undoing a marquee
     * generated on the panel you have left would silently take back everything
     * drawn on the panel you are now looking at, because the snapshot predates
     * both — a step whose effect is invisible from the screen it is pressed on.
     * "Opening the other variant is opening a different drawing" is the rule, and
     * it is worth more than the one press it costs.
     */
    fun switchTo(target: PokemonCodename): Boolean {
        if (target == codename) return false
        design = composed()
        dirty = true
        documentUndo.clear()
        documentRedo.clear()
        refreshDocumentFlags()
        codename = target
        frames.clear()
        frames.addAll(loadFrames(design, target))
        selectedIndex = 0
        return true
    }

    // ---- whole-document replacement ----

    /**
     * Replaces the entire document with [incoming], and returns the one it
     * replaced.
     *
     * This is the assistant's only way onto the canvas, and it is a *whole
     * document* on purpose: the model may change `kind`, `loop`, `keyMode`,
     * `levels` and the frames of **every variant the design carries**, not just
     * the one on screen. Anything narrower would mean the editor deciding which
     * of the model's changes it was willing to accept, which is a second set of
     * rules to disagree with `GlyphAiTools`.
     *
     * ## What follows from that
     *
     * - **The open variant's frames are rebuilt** through [loadFrames], so the
     *   canvas shows the new art immediately and the timeline is the new
     *   timeline. A variant the user is *not* looking at needs nothing done to
     *   it: it is already in [design] and [loadFrames] will find it on the next
     *   switch, which is precisely how [addVariant] already works.
     * - **A `kind` change lands for free.** The timeline and onion skin are
     *   composed from `design.kind`, so an animation appearing or disappearing
     *   mid-session is a recomposition rather than a special case.
     * - **A `levels` change re-clamps the brush.** `cells` are palette *indices*;
     *   a document that arrives with a two-entry palette while the brush points
     *   at swatch three would leave a selection with no swatch under it and a
     *   brush that paints index 2 into a palette that has none.
     * - **Per-frame undo histories are dropped**, exactly as [switchTo] drops them
     *   and for the same reason: their snapshots are arrays of the old geometry
     *   and the old palette.
     *
     * ## Whether the user can undo it, and who decides
     *
     * [recordUndo] is that decision, and it belongs to the caller because the two
     * callers answer it differently:
     *
     *  - **A tool in the editor passes true.** The document this replaced goes on
     *    [documentUndo] and the tool row's undo arrow lights up, because a user
     *    who has just pressed a button in the editor has exactly one idea of how
     *    to take it back and it is that arrow. See [undo] for what a press does.
     *  - **The assistant passes false** (the default), and keeps holding the
     *    `Design` this returns for its own revert banner. That is a *narrower*
     *    affordance, not a missing one: a chat turn can rewrite the whole document
     *    including the other panel, so the way back is offered beside the message
     *    that caused it, where it can be labelled with what it reverts. Two
     *    controls for the same step would be two chances to press the wrong one.
     *
     * A `false` replacement also **clears both document stacks**, which is the
     * same rule [switchTo] follows: a step from before a wholesale change nobody
     * else knows about would restore a document that never had that change in it.
     *
     * ## What the model may not change
     *
     * Identity. `id` names the file, `author` and `createdAt` describe a person
     * and a moment, and `format`/`formatVersion` are the app's to declare — all
     * are pinned from the current design here, on top of `GlyphAiTools` already
     * merging onto it, so neither layer alone is load-bearing. `modifiedAt` is
     * restamped, because the design genuinely just changed.
     *
     * Returns null and changes nothing if [incoming] carries no artwork at all,
     * which no validated document does — the check is here so that "the editor
     * refused" is a state the caller can report to the model rather than a
     * blank canvas nobody explains.
     */
    fun replaceDesign(incoming: Design, recordUndo: Boolean = false): Design? {
        if (incoming.variants.isEmpty()) return null
        val previous = composed()
        if (recordUndo) {
            push(documentUndo, previous)
            // A new step forks the history, for [markEdited]'s reason.
            documentRedo.clear()
        } else {
            documentUndo.clear()
            documentRedo.clear()
        }
        refreshDocumentFlags()
        adopt(
            incoming.copy(
                format = previous.format,
                formatVersion = previous.formatVersion,
                id = previous.id,
                author = previous.author,
                createdAt = previous.createdAt,
                createdWith = previous.createdWith,
            ),
        )
        return previous
    }

    /**
     * Puts [document] on screen: the model of record, the canvas, the timeline and
     * the brush, all from one design.
     *
     * Split out of [replaceDesign] so that stepping *back* to a document — [undo],
     * [redo] — is the identical operation rather than a second implementation of
     * it that could come to disagree about, say, the brush clamp. It records
     * nothing: the caller owns the history, which is what stops an undo from
     * pushing its own result onto the stack it just popped.
     */
    private fun adopt(document: Design) {
        val next = document.copy(modifiedAt = nowIsoUtc())
        design = next
        // Only reachable if a document arrived without the variant being edited,
        // which the tools do not allow. Falling back rather than trusting it is
        // what stops `composed()` from re-creating the variant on the next save.
        if (next.variantFor(codename) == null) codename = openingCodename(next, codename)
        frames.clear()
        frames.addAll(loadFrames(next, codename))
        selectedIndex = 0
        brushIndex = brushIndex.coerceIn(0, max(0, min(next.levels.size, MAX_SWATCHES) - 1))
        dirty = true
    }

    // ---- saving ----

    /**
     * Writes the design if anything has changed since the last write, and says
     * which of the three things happened — see [SaveOutcome].
     *
     * [composed] and the encode inside it run on the CALLER's thread — the main
     * thread — on purpose. The frame buffers are mutated from the UI thread by
     * the pointer handler, so reading them from an IO dispatcher would be a data
     * race against a finger that is still moving. Building the immutable snapshot
     * here and handing only that to [Dispatchers.IO] keeps the file write off the
     * main thread without ever touching a live buffer from another one; the
     * encode is a few hundred characters per frame, and the write is what needed
     * moving.
     */
    suspend fun saveIfDirty(store: DesignStore): SaveOutcome = saveMutex.withLock {
        if (!dirty) return@withLock SaveOutcome.UNCHANGED
        val snapshot = composed()
        // Cleared BEFORE the write, so a stroke landing during the I/O marks the
        // design dirty again rather than being swallowed by this save's success.
        dirty = false
        val ok = withContext(Dispatchers.IO) { saveRespectingAuthor(store, snapshot) }
        if (ok) design = snapshot else dirty = true
        if (ok) SaveOutcome.WRITTEN else SaveOutcome.FAILED
    }

    /**
     * The design with the live frames written back into the open variant, and
     * `modifiedAt` restamped. `createdAt` and `author` are never touched here —
     * see the file KDoc for where `author` is actually enforced.
     *
     * **Internal, not private, and that is a deliberate widening.** This is
     * exactly "the design as it is on screen, unsaved edits included", which is
     * what the assistant has to be shown — a model answering from the last thing
     * written to disk would describe strokes the user made a second ago as though
     * they had never happened. Reproducing the fold anywhere else would be a
     * second answer to "what is the art right now", free to disagree with this
     * one; see [replaceDesign] for the other half of the same argument.
     */
    fun composed(): Design {
        val encoded = ArrayList<DesignFrame>(frames.size)
        for (entry in frames) {
            // An encode failure means the palette cannot express a cell, which
            // this editor cannot produce — it only ever paints palette entries.
            // Bailing out with the design unchanged is still the right response:
            // writing a partial frame list would delete art.
            val cells = DesignFrames.encode(entry.frame.copyOfCells(), design.levels, codename.size)
                ?: return design
            encoded.add(DesignFrame(durationMs = entry.durationMs, cells = cells))
        }
        val variant = design.variantFor(codename) ?: DesignVariant()
        return design.copy(
            modifiedAt = nowIsoUtc(),
            variants = design.variants + (codename.codename to variant.copy(frames = encoded)),
        )
    }

    /**
     * [codename]'s frames as editable entries — never anything derived from the
     * other geometry.
     *
     * A variant that has never been drawn on has no frames at all (the second
     * geometry starts as a blank canvas by design), and a frame whose cells will
     * not decode is replaced by a blank one rather than dropped: losing a frame
     * silently would renumber every frame after it.
     *
     * Not `private` to a companion because it reads [nextFrameId].
     */
    private fun loadFrames(design: Design, codename: PokemonCodename): List<TimelineEntry> {
        val stored = design.variantFor(codename)?.frames.orEmpty()
        val loaded = stored.map { saved ->
            TimelineEntry(
                id = nextFrameId++,
                frame = EditorFrame(
                    codename.size,
                    DesignFrames.decode(saved.cells, design.levels, codename.size)
                        ?: IntArray(codename.cellCount),
                ),
                durationMs = saved.durationMs,
            )
        }
        return loaded.ifEmpty {
            listOf(
                TimelineEntry(
                    id = nextFrameId++,
                    frame = EditorFrame(codename.size, IntArray(codename.cellCount)),
                ),
            )
        }
    }

    private companion object {
        /** Off / grey / white. */
        const val MAX_SWATCHES = 3
    }
}
