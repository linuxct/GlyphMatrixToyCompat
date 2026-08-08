package space.linuxct.glyphmatrixtoycompat.ui.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import kotlinx.coroutines.delay
import space.linuxct.glyphmatrixtoycompat.R
import space.linuxct.glyphmatrixtoycompat.core.design.DEFAULT_LEVELS
import space.linuxct.glyphmatrixtoycompat.core.design.DESIGN_FORMAT
import space.linuxct.glyphmatrixtoycompat.core.design.DESIGN_FORMAT_VERSION
import space.linuxct.glyphmatrixtoycompat.core.design.Design
import space.linuxct.glyphmatrixtoycompat.core.design.DesignKind
import space.linuxct.glyphmatrixtoycompat.core.design.KeyMode
import space.linuxct.glyphmatrixtoycompat.core.design.PokemonCodename
import space.linuxct.glyphmatrixtoycompat.core.design.nowIsoUtc
import space.linuxct.glyphmatrixtoycompat.ui.generateDesignName
import space.linuxct.glyphmatrixtoycompat.ui.seedVariants

/**
 * The guided demo's script, sandbox and target registry — everything the tour
 * knows that is not a pixel on screen. [DesignDemoActivity] draws it.
 *
 * ## Why this is not a set of replica screens
 *
 * Because the app already paid for that lesson once. Phase 5 pulled the phone
 * illustration out of the Essential Key tutorial into [GlyphCanvas] precisely so
 * that the tutorial and the editor could not drift into two drawings of the same
 * hardware. A tutorial that reimplemented the editor would be that mistake again,
 * on the surface that changes most often — the tab order and the variant switcher
 * both moved on the day this was written.
 *
 * So the tour runs the **real** composables: the real `+` beside the real nav
 * pill, the real new-design fields, the real `EditorScaffold` over a real
 * [EditorState]. What it supplies is a sandbox for them to run in and a script
 * that drives them, and both of those live here.
 *
 * ## How the sandbox stays a sandbox
 *
 * - **The design is invented in memory and has no id** ([demoDesign]). It never
 *   reaches `DesignStore`, and could not: every write goes through
 *   `saveRespectingAuthor`, and the demo never calls it — `EditorScaffold`'s
 *   `demo` flag short-circuits its save scheduler to a no-op before a store is
 *   ever asked for anything.
 * - **Nothing takes the live-preview lease.** The same flag skips
 *   `LiveMatrixPreview` entirely, so `setPreviewActive` and `beginLivePreview`
 *   are never called and the real matrix goes on showing whatever toy the user
 *   left on it. A tutorial that hijacked the panel would be teaching the feature
 *   by breaking it.
 * - **No preference is read or written.** The one thing the tour asks the rest of
 *   the app is which panel this phone has, and even that is only used to pick the
 *   geometry it draws.
 * - **The user cannot touch any of it.** [DesignDemoActivity] parks a
 *   touch-swallowing layer between the real UI and the tour's own controls, so a
 *   stray tap cannot take a branch the script did not intend.
 *
 * ## Targets, and why no coordinate is written down
 *
 * Captions point at real elements by asking the elements where they are:
 * [Modifier.demoTarget] is an inert tag — the moral equivalent of `testTag` —
 * that reports a node's bounds into [DemoTargets] whenever a tour is hosting the
 * screen, and compiles to `this` when one is not. Hardcoded rectangles would have
 * been wrong the first time a row moved, which in these files is roughly weekly.
 */

// ---------- the registry ----------

/**
 * An element the tour can point at.
 *
 * Deliberately coarse: these name *controls a user has to find*, not composables.
 * Anything finer would make the enum a second, lagging description of the
 * editor's layout — the exact failure mode hardcoded coordinates have.
 */
internal enum class DemoTarget {
    /** The `+` riding beside the floating navigation pill. */
    FAB,

    /**
     * The new-design dialog's static / dynamic segmented row. Indexed: 0 static,
     * 1 dynamic.
     *
     * The **only** thing spotlit in that dialog, and the only one worth a hole:
     * the name field and the device rows are a text box with a suggestion already
     * in it and a pick-one list, both of which say what they are, while this is
     * the one answer that cannot be changed afterwards.
     */
    DIALOG_KIND,

    /** The dialog's confirm button. Pressed, never spotlit. */
    DIALOG_CREATE,

    /** The drawing surface. */
    CANVAS,

    /**
     * One palette swatch. Indexed by palette entry: 0 off, 1 grey, 2 white.
     *
     * Pressed, never spotlit: picking a shade is taught inside the [CANVAS] step,
     * where the shade the finger has just chosen is what the next stroke paints.
     * A hole around three swatches would have been a step of its own for a
     * pick-one-of-three that shows its own selection.
     */
    PALETTE,

    /**
     * One tool button. Indexed: 0 undo, 1 redo, 2 clear, 3 fill.
     *
     * Pressed, never spotlit, and for the reason the whole tour was shortened by:
     * the row is four icons that mean what they draw. What is *not* guessable is
     * that undo steps back a whole stroke rather than a cell, so the drawing step
     * presses undo and redo over its own stroke and says so in a clause.
     */
    TOOLS,

    /** One frame thumbnail on the timeline, indexed by frame position. */
    FRAME,

    /**
     * The add / duplicate / delete cluster. Indexed: 0 add, 1 duplicate, 2 delete.
     *
     * Pressed, never spotlit. The spotlight for the whole timeline step is
     * [FRAME] — the strip — because the strip is where every one of these buttons
     * shows its result.
     */
    FRAME_ACTIONS,

    /** The per-frame duration cluster. Indexed: 0 shorter, 1 longer. Pressed, never spotlit. */
    DURATION,

    /**
     * The app bar's Design settings action.
     *
     * Pressed, never spotlit — it sits *inside* [TOP_BAR], so the summary step's
     * own hole is already around it when the ghost presses it to open the card.
     */
    SETTINGS_ACTION,

    /** Inside Design settings: the key-mode row. Indexed: 0 play once, 1 play / pause. */
    KEY_MODE,

    /** Inside Design settings: the repeat toggle and the sentence beside it. */
    LOOP,

    /** Inside Design settings: "Add ... artwork" for the other panel size. */
    ADD_VARIANT,

    /**
     * The floating preview: the disc that plays the animation over the canvas, and
     * the pill under it that says what it is.
     *
     * Composed for a dynamic design only, and gated on nothing else — it is an
     * on-screen widget that reads [EditorState] and rasterises frames, so it takes
     * no preview lease and writes nothing. It therefore runs in the tour exactly
     * as it does in the editor, playing the frames the tour has just drawn.
     *
     * The **only** thing on this screen the tour still spends a whole step on that
     * is not a control: it is the one element whose behaviour cannot be guessed
     * from its appearance, because tapping a preview to enlarge it is a gesture
     * nothing else in the app uses.
     */
    LIVE_PREVIEW,

    /**
     * The app bar's actions, **as a group** — play/pause, the preview's eye, the
     * assistant's sparkles and Design settings, inside the one `Row` that holds
     * them.
     *
     * One target rather than four, and that is the tutorial's own lesson about
     * itself: a step per icon made the tour longer than the editor is hard, and
     * length is what stops a tutorial being finished. Four icons that each take a
     * sentence do not need four spotlights — they need one spotlight and four
     * sentences, which is what [R.string.demo_cap_top_bar] is.
     *
     * [SETTINGS_ACTION] survives alongside it because settings is the one action
     * the tour *opens* — but it is no longer a step of its own: it is inside this
     * row, so the summary's hole is already around it and the same step that
     * names the four buttons presses the fourth. Everything else up here is
     * described in the summary and nowhere else — none of play, the eye or the
     * sparkles is individually tagged, and none of them can be, because a `Row`
     * reports its own bounds and the tour asks the target for them.
     *
     * The other three are *not* pressed, and could not be. Play would have no
     * panel to play on (the sandbox does not compose `LiveMatrixPreview`), the
     * eye and the disc own state held inside `EditorScaffold` that the script
     * cannot reach, and the sparkles lead to a sheet needing a session, a network
     * and a ViewModel a sandbox that writes nothing does not have.
     */
    TOP_BAR,
}

/** One reported element: a [DemoTarget], plus which of them when there are several. */
private data class DemoKey(val target: DemoTarget, val index: Int)

/**
 * Where the tour's targets actually are, in the coordinates of the composition
 * root that hosts both them and the overlay.
 *
 * Written from layout (`onGloballyPositioned` fires on every layout pass), read
 * from the overlay's animation coroutine — so the writes are guarded on equality:
 * a relayout that puts a node back where it already was must not invalidate the
 * spotlight.
 */
@Stable
internal class DemoTargets {

    private val bounds = mutableStateMapOf<DemoKey, Rect>()

    fun report(target: DemoTarget, index: Int, rect: Rect) {
        val key = DemoKey(target, index)
        if (bounds[key] != rect) bounds[key] = rect
    }

    fun forget(target: DemoTarget, index: Int) {
        bounds.remove(DemoKey(target, index))
    }

    /** One element's bounds, or null if it is not on screen. */
    fun boundsOf(target: DemoTarget, index: Int): Rect? = bounds[DemoKey(target, index)]

    /** Where the tap should land: the centre of one element. */
    fun centerOf(target: DemoTarget, index: Int): Offset? = boundsOf(target, index)?.center

    /**
     * Every reported element of [target], as one rectangle.
     *
     * The union is what makes a *cluster* spotlightable without inventing a
     * wrapper composable for it: three palette swatches tagged individually give
     * both the three tap points the script needs and a rectangle that hugs the
     * three of them, which is a tighter and truer highlight than the full-width
     * row they sit in.
     */
    fun unionOf(target: DemoTarget): Rect? {
        var union: Rect? = null
        for ((key, rect) in bounds) {
            if (key.target != target) continue
            union = union?.let {
                Rect(
                    left = minOf(it.left, rect.left),
                    top = minOf(it.top, rect.top),
                    right = maxOf(it.right, rect.right),
                    bottom = maxOf(it.bottom, rect.bottom),
                )
            } ?: rect
        }
        return union
    }
}

/**
 * The registry the screens report into, or **null** — which is what it is in the
 * app proper.
 *
 * `compositionLocalOf` rather than `staticCompositionLocalOf`: the value is
 * provided exactly once, around the tour's content, and never changes, so the
 * cost of the dynamic form is a read and the benefit is that a null default is
 * an honest "nobody is watching" rather than a fake registry collecting bounds
 * for nothing on every screen in the app.
 */
internal val LocalDemoTargets = compositionLocalOf<DemoTargets?> { null }

/**
 * Reports this element's position to a tour, if one is running. Inert otherwise.
 *
 * The whole cost in the app proper is one composition-local read returning null.
 * That is the point: the alternative — passing a demo flag down through the
 * editor to decide whether to attach an `onGloballyPositioned` — would put a
 * conditional in a production layout for the benefit of a tutorial, and there
 * would be one per tagged element rather than one in total.
 */
@Composable
internal fun Modifier.demoTarget(target: DemoTarget, index: Int = 0): Modifier {
    // Returning early is safe here even though composables must keep a stable
    // call sequence: the local is provided once, around the whole tour, and is
    // null everywhere else — so this branch is decided per call site for the life
    // of the composition and cannot flip under a recomposition.
    val targets = LocalDemoTargets.current ?: return this
    // A timeline thumbnail is reported by POSITION, and positions disappear when
    // frames are deleted or scrolled out of the strip. Forgetting on dispose is
    // what stops [DemoTargets.unionOf] from spotlighting a rectangle around
    // something that is no longer there.
    DisposableEffect(targets, target, index) {
        onDispose { targets.forget(target, index) }
    }
    return onGloballyPositioned { targets.report(target, index, it.boundsInRoot()) }
}

// ---------- the ghost finger ----------

/**
 * The tour's stand-in for a fingertip: where it is, and how far through a
 * press-and-hold it is.
 *
 * Written by the script on a `withFrameNanos` loop and read **only from the
 * overlay's `Canvas` draw lambda**, which is the same phase discipline
 * [EditorFrame] documents for a real moving finger: a ghost that recomposed the
 * tour on every sample would be teaching the editor while breaking the rule the
 * editor is built on.
 */
@Stable
internal class DemoGhost {

    var position by mutableStateOf<Offset?>(null)
        private set

    /** 0 while gliding, ramping to 1 across a press-and-hold. */
    var press by mutableFloatStateOf(0f)
        private set

    fun moveTo(point: Offset) {
        position = point
    }

    fun pressTo(fraction: Float) {
        press = fraction.coerceIn(0f, 1f)
    }

    fun hide() {
        position = null
        press = 0f
    }
}

/**
 * What a step's script can do: move the ghost, tap with it, and wait.
 *
 * ## `instant`
 *
 * Every step is written once and run two ways. Played, it animates. **Replayed**
 * — which is how the tour rebuilds its sandbox after a step is skipped early,
 * after Back, and after a rotation — every wait and every glide returns
 * immediately and only the state changes land. One body, so the animation and the
 * end state cannot describe different things.
 *
 * Note what `instant` must NOT do: call `delay` or `withFrameNanos`. A replay
 * runs inside an effect that may already have been cancelled, and a suspending
 * call there would throw before the step's state changes were applied. Returning
 * without suspending lets the whole body run to completion synchronously, which
 * is exactly what a replay is for.
 */
@Stable
internal class DemoActor(
    private val ghost: DemoGhost,
    private val targets: DemoTargets,
    private val instant: Boolean,
) {

    fun centerOf(target: DemoTarget, index: Int = 0): Offset? = targets.centerOf(target, index)

    fun boundsOf(target: DemoTarget, index: Int = 0): Rect? = targets.boundsOf(target, index)

    /** A pause with nothing happening in it. */
    suspend fun beat(ms: Long) {
        if (!instant) delay(ms)
    }

    /** Glides the ghost to [point] over [ms], easing in and out. */
    suspend fun glideTo(point: Offset, ms: Long = GLIDE_MS) {
        if (instant) return
        val from = ghost.position
        if (from == null) {
            ghost.moveTo(point)
            return
        }
        animate(ms) { t ->
            val e = ease(t)
            ghost.moveTo(Offset(from.x + (point.x - from.x) * e, from.y + (point.y - from.y) * e))
        }
    }

    /** Moves to an element and taps it. Does nothing if it is not on screen. */
    suspend fun tap(target: DemoTarget, index: Int = 0) {
        val point = centerOf(target, index) ?: return
        glideTo(point)
        if (instant) return
        animate(TAP_MS) { t -> ghost.pressTo(if (t < 0.5f) t * 2f else (1f - t) * 2f) }
        ghost.pressTo(0f)
        beat(TAP_SETTLE_MS)
    }

    /**
     * Presses and *holds* on an element — the gesture the timeline's reorder is
     * behind, and the one gesture in the editor that a still screenshot cannot
     * express at all. The ring filling up is the hold.
     */
    suspend fun holdOn(target: DemoTarget, index: Int = 0) {
        val point = centerOf(target, index) ?: return
        glideTo(point)
        if (instant) return
        animate(HOLD_MS) { t -> ghost.pressTo(t) }
        ghost.pressTo(1f)
    }

    /** Ends a press-and-hold. */
    suspend fun release() {
        if (instant) return
        ghost.pressTo(0f)
        beat(TAP_SETTLE_MS)
    }

    fun hide() = ghost.hide()

    /**
     * Drives [block] from 0 to 1 over [ms] of real frames.
     *
     * `withFrameNanos`, not a `LaunchedEffect` + `animateFloatAsState`, for the
     * same reason `KeyTutorialDialog` uses it: this is a scripted timeline with
     * several things moving to one clock, and it must stop dead the moment the
     * step does. The loop lives inside the step's own effect and dies with it.
     */
    private suspend inline fun animate(ms: Long, block: (Float) -> Unit) {
        val span = ms.coerceAtLeast(1L)
        val t0 = withFrameNanos { it }
        while (true) {
            val t = withFrameNanos { now -> ((now - t0) / 1_000_000f) / span }
            block(t.coerceIn(0f, 1f))
            if (t >= 1f) return
        }
    }

    private companion object {
        const val GLIDE_MS = 460L
        const val TAP_MS = 220L
        const val TAP_SETTLE_MS = 260L
        const val HOLD_MS = 520L

        /** Smoothstep. A finger does not start or stop at full speed. */
        fun ease(t: Float): Float = t * t * (3f - 2f * t)
    }
}

// ---------- the sandbox ----------

/**
 * The throwaway design the tour draws on.
 *
 * **One variant, not both**, and the reason is that the tour has just shown the
 * new-design dialog answering that very question with its default: the phone in
 * the user's hand. An editor that then carried both sizes would contradict the
 * step before it, and would put a variant switcher on screen that the user's own
 * next design will not have — 15b hides it for single-size designs, which is what
 * the default produces. The dual-size rule is not skipped; it is explained at the
 * Design settings step, next to the "Add ... artwork" button that creates it,
 * which is the place someone will actually need it.
 *
 * **Dynamic**, because half of what needs teaching is the timeline. The kind step
 * says so out loud rather than quietly picking for the user.
 *
 * The id is deliberately **empty**. `DesignStore` names files by id, so a design
 * with no id has nowhere to be written even if some future edit forgot the demo
 * flag — the sandbox does not rely solely on a Boolean staying true.
 */
internal fun demoDesign(home: PokemonCodename, name: String): Design = Design(
    format = DESIGN_FORMAT,
    formatVersion = DESIGN_FORMAT_VERSION,
    id = "",
    name = name,
    author = "",
    createdAt = nowIsoUtc(),
    modifiedAt = nowIsoUtc(),
    createdWith = "",
    kind = DesignKind.DYNAMIC,
    keyMode = KeyMode.PLAY_PAUSE,
    loop = true,
    levels = DEFAULT_LEVELS,
    // The real seeding function, so the tour's design is built exactly the way
    // the `+` builds one for the same answers.
    variants = seedVariants(setOf(home), home),
)

/**
 * Everything the tour's steps mutate: the new-design dialog's answers, and the
 * editor's own state.
 *
 * [reset] is what makes the tour rewindable. Steps are pure deltas — each one
 * assumes the ones before it have run — so going backwards, or landing on step
 * seven after a rotation, means starting from a fresh sandbox and replaying the
 * earlier steps instantly. That is cheaper than it sounds: a 13x13 design is 169
 * ints, and a replay is a handful of function calls with every wait skipped.
 */
@Stable
internal class DemoSandbox(private val home: PokemonCodename) {

    /** Regenerated per sandbox, by the same generator the real dialog uses. */
    private val suggestedName = generateDesignName(emptySet())

    var name by mutableStateOf(suggestedName)

    var dynamic by mutableStateOf(false)

    var target by mutableStateOf(setOf(home))

    var state by mutableStateOf(EditorState(demoDesign(home, suggestedName), home))
        private set

    /**
     * How many steps' effects are currently in the sandbox. The tour compares it
     * with the step it is entering: equal means the state is already right and
     * the editor keeps its identity (so a thumbnail that just slid in stays slid
     * in), anything else means replay from scratch.
     */
    var applied = 0

    fun reset() {
        name = suggestedName
        dynamic = false
        target = setOf(home)
        state = EditorState(demoDesign(home, suggestedName), home)
        applied = 0
    }
}

// ---------- the script ----------

/** Which screen a step is played on. */
internal enum class DemoStage {
    /** The Create tab, with the floating pill and its `+`. */
    CREATE,

    /** The same, with the new-design questions in front of it. */
    DIALOG,

    /** The editor. */
    EDITOR,

    /** The editor, with Design settings open over it. */
    SETTINGS,
}

/**
 * One step: what it says, what it points at, and what it does.
 *
 * [target] is nullable because the last step points at nothing — it is the tour
 * saying it is over, and a spotlight on an arbitrary control would be a lie about
 * where to look.
 */
internal class DemoStep(
    val caption: Int,
    val stage: DemoStage,
    val target: DemoTarget? = null,
    /**
     * Which element of [target] the caption is about, or null to spotlight the
     * whole cluster. Only affects the highlight; the script taps whatever it
     * likes.
     */
    val targetIndex: Int? = null,
    val act: suspend DemoActor.(DemoSandbox) -> Unit = {},
)

/**
 * The tour, in order.
 *
 * The order is the order somebody actually does it in: make a design, draw on it,
 * animate it, watch it run, put it on the phone — and only then the assistant that
 * could have done the drawing, which is an offer that means nothing until you know
 * what it is offering to save you.
 *
 * ## One step per *place*, not one per control
 *
 * This list was eighteen steps and is ten, and the rule that took it there is the
 * one [DemoTarget.TOP_BAR] was written for: **a control earns a spotlight only
 * when what it does cannot be read off it.** A `+` beside a strip of frames adds a
 * frame; a segmented pair of shades is a pick-one; undo is undo. None of those is
 * worth stopping a reader for, and stopping a reader eleven times is how a
 * tutorial gets skipped — which teaches nothing at all.
 *
 * So the middle of the tour is now three steps, each a *place* with one hole in
 * the scrim over it and one caption naming everything in it:
 *
 * - **the canvas** — the shades, the drag, the pinch, and the tool row (was four);
 * - **the timeline** — duplicate, add, the press-and-hold reorder, the per-frame
 *   duration (was four);
 * - **the app bar** — the four actions, ending with the one it opens (was two).
 *
 * What did **not** get merged away is the *acting*. A merged step still performs
 * every gesture its old steps did, in sequence: the drawing step still undoes and
 * redoes its own stroke, the timeline step still duplicates, nudges, adds and
 * drags a frame to the front, and the settings steps still make the repeat row
 * vanish and come back. That is the tour's whole advantage over a paragraph, and
 * shortening it means dropping *narration*, never demonstration.
 *
 * The finger therefore leaves the hole sometimes — to reach a swatch, a tool, a
 * frame button — and that is deliberate: the spotlight sits where the **result**
 * appears, which for all three of those is the surface being changed rather than
 * the button doing the changing.
 *
 * The detail — what `playOnce` means, what the format carries, how to import
 * somebody else's design — belongs in the README and in the design-format
 * document; a tour that tried to carry it would be the wall of text this replaced.
 *
 * Two of the steps point at things the tour deliberately does not operate: the
 * floating preview, whose expand is its own state and cannot be driven from a
 * sandbox whose touches are swallowed, and the assistant, whose sheet needs a
 * network and a session. Both say so at their step; neither has a stand-in.
 */
internal val DEMO_STEPS: List<DemoStep> = listOf(
    DemoStep(
        caption = R.string.demo_cap_fab,
        stage = DemoStage.CREATE,
        target = DemoTarget.FAB,
    ) {
        beat(500)
        tap(DemoTarget.FAB)
    },
    DemoStep(
        // One step for the whole dialog. The hole is on the kind row because that
        // is the only answer here that cannot be changed later; the name and the
        // device rows are named in the caption and left to speak for themselves,
        // which between them is two steps this tour no longer spends.
        caption = R.string.demo_cap_new,
        stage = DemoStage.DIALOG,
        target = DemoTarget.DIALOG_KIND,
    ) { sandbox ->
        beat(500)
        // The real segmented button, so the check-mark wipes in and pushes the
        // label aside exactly as it does under a real finger.
        tap(DemoTarget.DIALOG_KIND, index = 1)
        sandbox.dynamic = true
        beat(600)
        tap(DemoTarget.DIALOG_CREATE)
    },
    DemoStep(
        // The canvas and everything under it: was palette, paint and tools.
        // The hole stays on the canvas throughout, because the canvas is where
        // all three show their result — the shade the swatch selected is the
        // shade the stroke paints, and the stroke is what undo takes away.
        caption = R.string.demo_cap_draw,
        stage = DemoStage.EDITOR,
        target = DemoTarget.CANVAS,
    ) { sandbox ->
        beat(300)
        tap(DemoTarget.PALETTE, index = 1)
        sandbox.state.brushIndex = 1
        beat(260)
        tap(DemoTarget.PALETTE, index = 2)
        sandbox.state.brushIndex = 2
        paintStroke(sandbox, SMILE)
        // The one thing about the tool row that cannot be read off it: undo is a
        // WHOLE STROKE. Seven cells vanish at once and come back, which is the
        // sentence in the caption being demonstrated rather than repeated.
        tap(DemoTarget.TOOLS, index = 0)
        sandbox.state.undo()
        beat(600)
        tap(DemoTarget.TOOLS, index = 1)
        sandbox.state.redo()
    },
    DemoStep(
        // The whole timeline: was duplicate, add, reorder and duration. Every one
        // of those gestures still happens, in this order, under one caption — the
        // strip is the spotlight because the strip is where each of them lands.
        caption = R.string.demo_cap_frames,
        stage = DemoStage.EDITOR,
        target = DemoTarget.FRAME,
    ) { sandbox ->
        beat(300)
        tap(DemoTarget.FRAME_ACTIONS, index = 1)
        sandbox.state.duplicateFrame()
        beat(240)
        // The nudge that makes a duplicate worth having: the copy is not the
        // frame before it any more.
        paintStroke(sandbox, BLINK)
        tap(DemoTarget.FRAME_ACTIONS, index = 0)
        sandbox.state.addFrame()
        beat(400)
        val from = sandbox.state.frames.lastIndex
        holdOn(DemoTarget.FRAME, index = from)
        beat(240)
        centerOf(DemoTarget.FRAME, index = 0)?.let { glideTo(it, ms = 700) }
        // The move itself is the real one, so the strip re-lays out on the
        // theme's spatial spring and the thumbnails slide the way they do under
        // a real drag. This is the one gesture in the editor with no visual
        // affordance at all, which is why it survived the merge intact.
        sandbox.state.moveFrame(from, 0)
        release()
        beat(300)
        tap(DemoTarget.DURATION, index = 1)
        sandbox.state.setSelectedDuration(
            stepDuration(sandbox.state.selected.durationMs, up = true),
        )
    },
    DemoStep(
        caption = R.string.demo_cap_preview,
        stage = DemoStage.EDITOR,
        target = DemoTarget.LIVE_PREVIEW,
    ) {
        // Deliberately AFTER the timeline step, and the reason is that the widget
        // only runs on a design with more than one frame: pointed at any earlier
        // it would be a still disc while the caption called it an animation. By
        // here the tour has drawn three frames and set a duration, so what is
        // inside the spotlight is those three frames cycling at those timings —
        // the caption is checkable by looking at it.
        //
        // Pointed at, never tapped, by the same rule as the play action: the
        // tour's touch-swallowing layer means a ghost finger on the disc could not
        // actually expand it, and the widget owns that state itself. A finger
        // tapping a control that visibly does nothing teaches the wrong thing
        // about the control, so the caption carries the gesture instead.
        hide()
    },
    DemoStep(
        caption = R.string.demo_cap_top_bar,
        stage = DemoStage.EDITOR,
        target = DemoTarget.TOP_BAR,
    ) {
        // One step for the whole bar — see [DemoTarget.TOP_BAR]. It used to be
        // three, one per icon, plus a step of its own for settings; consecutive
        // spotlights on adjacent buttons is where this tour got long enough to be
        // skipped, and a skipped tutorial teaches nothing at all.
        //
        // It ENDS by opening Design settings, which is why the settings step is
        // gone: the sliders are the fourth icon in this same row, so the hole is
        // already around the button and the caption is already naming it. The
        // card itself arrives with the next step, exactly as it did when a
        // separate step did the pressing.
        //
        // The other three are not pressed. See [DemoTarget.TOP_BAR] for why none
        // of them can be, which is a different reason for each of them and the
        // same outcome for all.
        beat(1200)
        tap(DemoTarget.SETTINGS_ACTION)
    },
    DemoStep(
        caption = R.string.demo_cap_key_mode,
        stage = DemoStage.SETTINGS,
        target = DemoTarget.KEY_MODE,
    ) { sandbox ->
        beat(500)
        // Switching to play once makes the repeat row VANISH, which is the
        // fastest possible answer to "why can I not find repeat" — and the tour
        // switches straight back, so the demonstration costs the sandbox nothing
        // and the next step still has a control to point at.
        tap(DemoTarget.KEY_MODE, index = 0)
        sandbox.state.setKeyMode(KeyMode.PLAY_ONCE)
        beat(900)
        tap(DemoTarget.KEY_MODE, index = 1)
        sandbox.state.setKeyMode(KeyMode.PLAY_PAUSE)
    },
    DemoStep(
        caption = R.string.demo_cap_loop,
        stage = DemoStage.SETTINGS,
        target = DemoTarget.LOOP,
    ) { sandbox ->
        beat(500)
        // Both states, because the difference between them is the shape morph
        // AND the sentence beside it, and one without the other reads as
        // decoration. Ends where it started.
        //
        // The tag is on the whole row, so the ghost lands in the middle of it —
        // over the SENTENCE, not over the icon. That is the demonstration: the
        // icon reads as a badge and four testers never pressed it, so the row is
        // the toggle now (see `LoopRow`) and the tour shows the row being
        // tapped rather than teaching a target that no longer exists alone.
        tap(DemoTarget.LOOP)
        sandbox.state.setLoop(false)
        beat(1100)
        tap(DemoTarget.LOOP)
        sandbox.state.setLoop(true)
    },
    DemoStep(
        caption = R.string.demo_cap_add_variant,
        stage = DemoStage.SETTINGS,
        target = DemoTarget.ADD_VARIANT,
    ) {
        // Pointed at, never pressed: pressing it would create the second variant
        // and put a switcher on screen that the tour has just finished explaining
        // this design does not have.
        hide()
    },
    DemoStep(
        caption = R.string.demo_cap_done,
        stage = DemoStage.EDITOR,
    ) {
        hide()
    },
)

/**
 * Paints a run of cells the way a finger does: begin, walk the path, end — so it
 * is one stroke, one undo step, and it goes through exactly the calls the pointer
 * handler makes.
 *
 * The ghost is carried to each cell's own centre via [matrixCellCenter], resolved
 * against the canvas's *reported* bounds. Nothing here knows where the canvas is;
 * it asks.
 */
private suspend fun DemoActor.paintStroke(sandbox: DemoSandbox, path: List<Pair<Int, Int>>) {
    val state = sandbox.state
    val canvas = boundsOf(DemoTarget.CANVAS)
    state.beginStroke()
    for ((i, cell) in path.withIndex()) {
        if (canvas != null) {
            val point = demoCellCenter(canvas, state, cell.first, cell.second)
            glideTo(point, ms = if (i == 0) GLIDE_TO_CANVAS_MS else STROKE_STEP_MS)
        }
        state.paint(cell.first, cell.second)
    }
    state.endStroke()
    beat(240)
}

/** How long the ghost takes to reach the first cell of a stroke, and each one after. */
private const val GLIDE_TO_CANVAS_MS = 460L
private const val STROKE_STEP_MS = 90L

/**
 * The mouth. Seven cells across the middle of a 13x13 panel, well inside
 * `PanelMask`'s rim, so every one of them is a cell the panel actually has.
 */
private val SMILE = listOf(3 to 5, 4 to 6, 5 to 7, 6 to 7, 7 to 7, 8 to 6, 9 to 5)

/** The nudge on the duplicated frame: two eyes, so the copy differs from its source. */
private val BLINK = listOf(4 to 4, 8 to 4)
