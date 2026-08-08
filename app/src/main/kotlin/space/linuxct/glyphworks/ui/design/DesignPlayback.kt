package space.linuxct.glyphworks.ui.design

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.util.lerp
import androidx.lifecycle.compose.LifecycleResumeEffect
import space.linuxct.glyphworks.R
import space.linuxct.glyphworks.core.design.DesignKind
import space.linuxct.glyphworks.core.design.KeyMode
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Playing a design back: on the panel on the back of the phone, and in a floating
 * disc on the front of it.
 *
 * ## Why the two are separate clocks
 *
 * They answer different questions and they are wanted at different times.
 *
 * - **The panel** is the truth. It is what the design will actually look like when
 *   the `Custom` toy plays it, so it honours each frame's own duration and the
 *   design's repeat rule ([designRepeats]) exactly — which is why it is a
 *   deliberate, toggled action ([nextPlaybackFrame] drives it from
 *   `LiveMatrixPreview`) and not something the editor does on its own.
 * - **The floating preview** is a legend. It runs whenever the editor is in front,
 *   it always cycles (a design that ends is given a beat on its last frame instead
 *   — see [PREVIEW_REST_MS]), and it exists so somebody drawing frame 14 can see
 *   the movement they are drawing without looking at the back of their phone.
 *
 * The two share [playbackHoldMs], so a frame is on screen for as long here as it
 * is on the panel and the preview cannot quietly disagree with the hardware about
 * the design's timing.
 *
 * ## What this costs when nothing is happening
 *
 * The editor's file KDoc used to promise no clock of any kind. That promise is now
 * narrower and is enforced here rather than by absence:
 *
 * - The panel loop exists only while playback is **on** and the activity is
 *   resumed; it is a `delay` chain, so it is one timer, not a frame callback.
 * - The preview's `withFrameMillis` loop exists only while the design is
 *   **dynamic**, has **more than one frame**, the widget is **composed**, and the
 *   activity is **resumed**. A static design, a one-frame animation and a
 *   backgrounded editor all run no clock at all.
 *
 * And a tick writes exactly one `IntState` that is read only from inside a `Canvas`
 * draw lambda, so it invalidates one node's draw and recomposes nothing — the same
 * discipline [EditorFrame] documents for a moving finger.
 */

// ---------- the schedule ----------

/**
 * Where playback goes after the frame at [index], or **null** when it has come to
 * rest.
 *
 * The whole of the animation's control flow, kept as one pure function because it
 * is the part that is wrong silently: a design that quietly refuses to loop, or one
 * that wraps when its author said it should hold, looks exactly like a design that
 * works until you sit and watch it.
 *
 * `CustomScreen.advance` is the same rule on the toy side, and this deliberately
 * matches it: past the last frame, a looping design starts again and a non-looping
 * one stops **on** the last frame rather than snapping back to the first. Ending
 * on the image its author ended it on is the whole point of switching repeat off.
 *
 * `count <= 1` is rest, not a loop: a one-frame design has nowhere to advance to,
 * and re-pushing the same frame forever would be a timer chain that changes nothing
 * on the panel.
 */
internal fun nextPlaybackFrame(index: Int, count: Int, loop: Boolean): Int? = when {
    count <= 1 -> null
    index < count - 1 -> index + 1
    loop -> 0
    else -> null
}

/**
 * Whether a design repeats — **the design's own rule, not just its `loop` field**.
 *
 * `playOnce` overrides repeat wherever repeat is read (`CustomScreen.advance` on
 * the toy side, and `PlaybackRow`, which hides the toggle in that mode and
 * deliberately leaves the stored value alone). A `playOnce` design carrying
 * `loop: true` is a normal, harmless file, so every reader of the pair has to
 * apply the same override or become the one place where it is not applied.
 */
internal fun designRepeats(loop: Boolean, keyMode: KeyMode): Boolean =
    loop && keyMode == KeyMode.PLAY_PAUSE

/**
 * How long a frame of [durationMs] is held, in milliseconds.
 *
 * The design's own number, with one floor: [PREVIEW_INTERVAL_MS]. `DesignCodec`
 * allows a 20 ms frame — 50 fps, which is a legitimate thing to draw — and pushing
 * at 50 Hz means 50 blocking binder round-trips a second to the Glyph service for
 * a panel that cannot show the difference. That floor is the same one the live
 * preview's throttle is set by, and for the same reason; see [PREVIEW_INTERVAL_MS].
 *
 * **There is deliberately no ceiling.** A 60-second frame is legal and a preview
 * that clamped it would be lying about the one thing playback exists to check.
 * (The Create tab's card previews *do* clamp, and correctly: those are a legend of
 * a design you are not editing, not a rehearsal of one you are.)
 */
internal fun playbackHoldMs(durationMs: Int): Long =
    durationMs.toLong().coerceAtLeast(PREVIEW_INTERVAL_MS)

/**
 * The extra beat the floating preview holds a non-looping design's last frame for
 * before starting again.
 *
 * The widget always cycles — a corner preview that played once and then sat still
 * for the rest of the session would read as broken — but a design whose author
 * switched repeat *off* does not wrap, and running its end straight into its
 * beginning would show a seam that will never exist on the hardware. A pause on
 * the last frame says "this is where it ends" without stopping.
 */
internal const val PREVIEW_REST_MS = 600L

/**
 * The spring the floating preview morphs on — **explicitly critically damped, and
 * deliberately not the theme's.**
 *
 * Every other animation in this app takes its spec from `MaterialTheme.motionScheme`
 * and should keep doing so. This one may not, and the reason is that its progress
 * drives the widget's **position** as well as its size. The theme's spatial spec is
 * under-damped on purpose — that is the small pop everything here lands with — and
 * a pop is charming on a size and wrong on a position: the disc arrives at the
 * corner it lives in, keeps going, and walks back. That was reported as a stagger
 * over the top-right, and it is not fixable by clamping the value, because the
 * rebound (`1 -> -0.15 -> +0.05 -> 0`) spends most of its excursion *inside* the
 * legal range.
 *
 * [Spring.DampingRatioNoBouncy] is `1.0` — critical damping. A critically damped
 * spring approaches its target monotonically and **cannot** overshoot; that is a
 * property of the solution, not a tuning choice, so it cannot be undone by a theme
 * retune or a library update. `DesignPlaybackTest` samples this spec densely in
 * both directions and fails if any sample leaves `0..1` or reverses direction, so
 * the guarantee is enforced by the build rather than asserted in a comment.
 *
 * Stiffness is the medium-low default: the same unhurried settle the rest of the
 * app has, minus the bounce.
 */
internal val PREVIEW_MORPH_SPEC: FiniteAnimationSpec<Float> = spring(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMediumLow,
)

/** [playbackHoldMs], plus the rest beat when this is where a non-looping design ends. */
internal fun previewHoldMs(durationMs: Int, last: Boolean, loop: Boolean): Long =
    playbackHoldMs(durationMs) + if (last && !loop) PREVIEW_REST_MS else 0L

/**
 * Where the floating preview has got to in its loop.
 *
 * [frameIndex] is the only thing that changes as it plays, it is an `IntState`, and
 * the one place it is read is inside a draw lambda — so a tick costs a draw
 * invalidation on one node and no recomposition anywhere.
 *
 * Catching up is capped at one frame per tick, deliberately. The clock stops while
 * the activity is paused, which leaves [nextAt] arbitrarily far in the past;
 * advancing by however many holds have "elapsed" would replay the animation at
 * infinite speed for a moment on the way back. A preview resumes, it does not catch
 * up.
 */
@Stable
internal class PreviewPlayback {

    var frameIndex by mutableIntStateOf(0)
        private set

    private var nextAt = Long.MIN_VALUE

    /** Back to the first frame, with the next deadline unset. */
    fun reset() {
        frameIndex = 0
        nextAt = Long.MIN_VALUE
    }

    /**
     * One frame callback's worth of progress, against a millisecond clock.
     *
     * The durations are reached through [durationMsOf] rather than handed over as a
     * list, for two reasons: this runs on **every frame** while the preview is up,
     * so building a 240-element list per callback would be the whole cost of the
     * widget; and the value is then read live, so lengthening a frame while the
     * preview is running takes effect on its next visit — as it does on the panel.
     */
    fun tick(nowMs: Long, count: Int, loop: Boolean, durationMsOf: (Int) -> Int) {
        if (count <= 0) return
        val index = frameIndex.coerceIn(0, count - 1)
        // A frame was deleted out from under the loop. Take the clamp and carry on
        // rather than waiting for the deadline of a frame that no longer exists.
        if (index != frameIndex) frameIndex = index
        if (nextAt == Long.MIN_VALUE) {
            nextAt = nowMs + hold(index, count, loop, durationMsOf)
            return
        }
        if (nowMs < nextAt) return
        // Always wraps: see this file's KDoc for why the widget cycles even when
        // the design does not. `loop` reaches the hold, not the step.
        val next = (index + 1) % count
        frameIndex = next
        nextAt = nowMs + hold(next, count, loop, durationMsOf)
    }

    private fun hold(index: Int, count: Int, loop: Boolean, durationMsOf: (Int) -> Int): Long =
        previewHoldMs(durationMsOf(index), last = index == count - 1, loop = loop)
}

// ---------- the floating preview ----------

/**
 * The resting diameter of the floating preview.
 *
 * 72 dp is two 36 dp touch targets across: big enough that a 13x13 panel's cells
 * are individually visible (5.5 dp of pitch — not drawable, but this is not a
 * drawing surface) and small enough to sit in the corner of the canvas without
 * covering artwork. At 25x25 it is 2.9 dp a cell, which reads as movement rather
 * than as pixels, which is exactly what a corner preview is for.
 */
private val FLOATING_PREVIEW_SMALL = 72.dp

/**
 * ...and never more than this much of the window's shorter side, so a small or
 * split-screen window does not get a preview that eats its canvas.
 */
private const val FLOATING_PREVIEW_SMALL_FRACTION = 0.28f

/**
 * How much of the shorter side the expanded preview takes: **near-fullscreen, not
 * fullscreen**.
 *
 * The gap is the whole affordance. A disc that filled the window would look like a
 * screen the editor had navigated to, and the way back would be a guess; leaving
 * the editor visible around it says "this is on top of what you were doing" and
 * makes the second tap obvious.
 */
private const val FLOATING_PREVIEW_LARGE_FRACTION = 0.86f

/**
 * And a ceiling, for a tablet or an unfolded foldable. Past this the preview stops
 * being a preview and becomes a wall of LEDs — the panel it is imitating is 4 cm
 * across.
 */
private val FLOATING_PREVIEW_LARGE_MAX = 440.dp

/** How far the resting preview sits from the corner it is pinned to. */
private val FLOATING_PREVIEW_MARGIN = 12.dp

/**
 * The largest square a preview frame is rasterised into.
 *
 * The cache buys its speed with memory and the bill is per frame. 320 px is a
 * little over the expanded disc at 1x and about a third of it at 3x, which on soft
 * white dots against black glass is not a visible stretch — and it is 410 kB a
 * frame rather than the 1.6 MB a full-resolution expanded frame would be.
 */
private const val FLOATING_PREVIEW_RASTER_PX = 320

/**
 * How many frames' bitmaps the preview keeps at once.
 *
 * A design may carry 240 frames and caching every one of them at
 * [FLOATING_PREVIEW_RASTER_PX] would be 98 MB, which is not a cache, it is a leak
 * with a plan. Sixteen covers the overwhelming majority of hand-drawn animations
 * outright — those pay one rasterisation per frame, ever — and a longer design
 * degrades gracefully to one rasterisation per *visit*, which is what an
 * uncached preview would pay on every tick anyway.
 */
private const val FLOATING_PREVIEW_CACHE_FRAMES = 16

/**
 * How big the floating preview is, in a window [available] dp across.
 *
 * Pure, and separated from the composable that measures the window for the same
 * reason `designGridColumns` is: the measurement is the part a unit test cannot
 * run and the arithmetic is the part worth pinning down.
 *
 * The expanded size is never allowed below the resting one — on a window narrow
 * enough for [FLOATING_PREVIEW_SMALL_FRACTION] to bind, "expand" would otherwise be
 * able to shrink it.
 */
internal fun floatingPreviewDiameter(available: DpSize, expanded: Boolean): Dp {
    if (!available.isSpecified) return FLOATING_PREVIEW_SMALL
    val shorter = minOf(available.width, available.height)
    if (shorter <= 0.dp) return FLOATING_PREVIEW_SMALL
    val small = minOf(FLOATING_PREVIEW_SMALL, shorter * FLOATING_PREVIEW_SMALL_FRACTION)
    if (!expanded) return small
    return maxOf(small, minOf(shorter * FLOATING_PREVIEW_LARGE_FRACTION, FLOATING_PREVIEW_LARGE_MAX))
}

/**
 * One [ThumbnailCache] per frame, with a bound on how many are alive.
 *
 * Keyed on [TimelineEntry.id] rather than on the frame's position, so reordering
 * the timeline moves a frame's bitmap with it instead of invalidating two.
 * Least-recently-used order, so what survives a long design is the window the loop
 * is currently walking.
 *
 * The timeline's own per-entry cache is deliberately not reused: it is rendered at
 * the strip's thumbnail size, and a single-slot cache asked alternately for two
 * sizes re-renders on every single draw — which is the exact cost `ThumbnailCache`
 * exists to remove.
 */
@Stable
internal class FramePreviewCaches(private val capacity: Int = FLOATING_PREVIEW_CACHE_FRAMES) {

    private val caches = object : LinkedHashMap<Long, ThumbnailCache>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ThumbnailCache>): Boolean {
            if (size <= capacity) return false
            eldest.value.release()
            return true
        }
    }

    /** The cache for one frame, created on first sight. Draw phase. */
    fun of(id: Long): ThumbnailCache = caches.getOrPut(id) { ThumbnailCache() }

    /** How many bitmaps are currently held. */
    val size: Int get() = caches.size

    /** Lets every bitmap go — the widget leaving composition. */
    fun release() {
        for (cache in caches.values) cache.release()
        caches.clear()
    }
}

/**
 * The design, playing, in a disc that floats over the editor.
 *
 * ## What it is for
 *
 * The canvas shows **one frame**, because that is what is being drawn on. Until
 * this existed, the only way to see the other twelve go past was to leave the
 * editor, or to look at the back of the phone. This is the animation, on the front,
 * while you draw it — and it is pinned to the corner rather than docked into the
 * layout because the editor's vertical space is rationed by the cell pitch (see
 * [MAX_CANVAS_SCALE]) and a row here would come straight off the drawing surface.
 *
 * ## Small, large, small
 *
 * Tapping it grows it to [FLOATING_PREVIEW_LARGE_FRACTION] of the window and
 * tapping it again puts it back. It is never dismissed, because a control that can
 * be lost has to be findable again and there is nowhere in this bar to put a
 * "bring the preview back" button that would be worth the space it took.
 *
 * The transition is one animated fraction driving a `BiasAlignment` and a diameter,
 * so the disc travels from the corner to the middle on the theme's spatial spring
 * rather than jumping. That fraction is read in composition, which recomposes this
 * widget for the ~300 ms the spring is running — bounded by a tap, not by a clock.
 *
 * ## Saying what it is
 *
 * A pill reading "PREVIEW" sits under the resting disc and fades out as it opens.
 * It is the only thing on the screen that tells a sighted user what the circle is;
 * see [FloatingPreviewBadge] for its colours and for why it goes away.
 *
 * ## Where the clock is bounded
 *
 * `animating` below: **dynamic**, **more than one frame**, and **resumed**. The
 * loop is a `LaunchedEffect` keyed on it, so all three are re-decided by the
 * composition and none of them can leave a frame callback behind. A static design
 * composes exactly the same widget and never asks for a frame.
 */
@Composable
internal fun FloatingLivePreview(state: EditorState, modifier: Modifier = Modifier) {
    val dynamic = state.design.kind == DesignKind.DYNAMIC
    // Structural reads: the frame count changes when a frame is added, duplicated
    // or deleted, which is a recomposition either way.
    val frameCount = state.frames.size

    // Owned by the lifecycle callback rather than by a key on it, so that a
    // backgrounded editor stops asking for frames on the lifecycle event itself
    // rather than on whatever recomposition happens to come next.
    var resumed by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        resumed = true
        onPauseOrDispose { resumed = false }
    }

    val playback = remember(state) { PreviewPlayback() }
    val caches = remember(state) { FramePreviewCaches() }
    DisposableEffect(caches) { onDispose { caches.release() } }

    val animating = dynamic && frameCount > 1 && resumed
    LaunchedEffect(animating, state) {
        if (!animating) return@LaunchedEffect
        // The one frame loop in this screen that outlives a gesture. It is a
        // `withFrameMillis` rather than a timer chain because it is driving a draw
        // and must be on the frame clock the draw lands on; the tick itself is a
        // comparison and, at most, one Int write.
        while (true) {
            withFrameMillis { now ->
                playback.tick(
                    nowMs = now,
                    count = state.frames.size,
                    loop = designRepeats(state.design.loop, state.design.keyMode),
                ) { state.frames[it].durationMs }
            }
        }
    }

    var expanded by rememberSaveable { mutableStateOf(false) }
    val spring by animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = PREVIEW_MORPH_SPEC,
        label = "floatingPreview",
    )

    /**
     * The spring's progress, **clamped to the journey it was asked to make**.
     *
     * The theme's spatial spring is deliberately under-damped, so it settles by
     * overshooting: it runs past 1.0 on the way out and dips below 0.0 on the way
     * back. Every geometric property here is a `lerp` on it, and `lerp`
     * EXTRAPOLATES rather than clamping — so those excursions do not read as a
     * bounce, they read as the widget leaving the states it has.
     *
     * At the open end the excess is invisible: a little past centre and a little
     * oversized, over a scrim, with nothing to measure it against. At the resting
     * end it is the bug that was reported. A dip to -0.15 puts the alignment bias
     * at 1.15 — **past the corner**, outside the box it is aligned in — while the
     * diameter lerps BELOW the resting size, so the disc shrinks smaller than it
     * lives, slides off the corner, and walks back. That asymmetry is exactly what
     * the screen recording shows: no bounce opening, a stagger over the top-right
     * closing.
     *
     * So the spring keeps its easing and loses its overshoot. A pop, if one is ever
     * wanted here, belongs on a property that is allowed to exceed its endpoints —
     * a scale — and not on a position and a size that are also the widget's resting
     * state. (The earlier crash, `Padding must be non-negative`, was the same root
     * cause reached from the other end; the `coerceAtLeast` calls below are now
     * redundant and kept only as a local guarantee.)
     */
    val fraction = spring.coerceIn(0f, 1f)

    val label = stringResource(
        if (expanded) R.string.editor_preview_collapse else R.string.editor_preview_expand,
    )
    val scrim = MaterialTheme.colorScheme.scrim

    BoxWithConstraints(modifier.fillMaxSize()) {
        val window = DpSize(maxWidth, maxHeight)
        val diameter = lerp(
            floatingPreviewDiameter(window, expanded = false),
            floatingPreviewDiameter(window, expanded = true),
            fraction,
        )
        // Clamped for the same reason the padding and the elevation are: the
        // spring overshoots past 1.0 and dips below 0.0, and an alpha outside
        // 0..1 is not a thing to hand a graphics layer.
        val badgeAlpha = (1f - fraction).coerceIn(0f, 1f)
        // The scrim is what stops the canvas being painted on through an expanded
        // preview: an overlay that did not take the touch would leave a finger
        // aimed at the disc drawing a line on the artwork behind it. It fades with
        // the same fraction and takes a tap as "put it back".
        if (fraction > 0f) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(scrim.copy(alpha = SCRIM_ALPHA * fraction))
                    .pointerInput(Unit) {
                        detectTapGestures { expanded = false }
                    },
            )
        }
        // The disc and its label travel together, which is the only reason this is
        // a Column and not two aligned children: the label has to stay under the
        // circle through the whole spring, not race it across the window.
        Column(
            modifier = Modifier
                .align(
                    BiasAlignment(
                        horizontalBias = lerp(1f, 0f, fraction),
                        verticalBias = lerp(-1f, 0f, fraction),
                    ),
                )
                // Clamped because [fraction] OVERSHOOTS. The theme's spatial
                // spring is deliberately under-damped — that is the small pop
                // everything in this app lands with — so an expand runs past 1.0
                // and a collapse dips below 0.0, and `lerp` extrapolates rather
                // than clamping. Unclamped, the first frame past 1.0 asks for a
                // NEGATIVE margin and `Modifier.padding` throws `Padding must be
                // non-negative`, taking the editor down with it.
                //
                // The overshoot is kept where it is wanted and free: [diameter] is
                // lerped on the raw fraction above, so the disc still springs past
                // its final size and settles back. Only the quantities that cannot
                // be negative are fenced.
                .padding(lerp(FLOATING_PREVIEW_MARGIN, 0.dp, fraction).coerceAtLeast(0.dp))
                // Inert unless a guided tour is hosting the editor; see
                // [demoTarget]. On the COLUMN rather than on the disc, and after
                // the padding, so the reported rectangle hugs the circle and the
                // pill together — the pill is the only thing on screen that names
                // the widget, and a spotlight that left it in the dimmed part
                // would be teaching the disc while hiding its label.
                .demoTarget(DemoTarget.LIVE_PREVIEW),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Surface(
                modifier = Modifier
                    .size(diameter.coerceAtLeast(0.dp))
                    .pointerInput(Unit) {
                        // A tap listener rather than `clickable`: the disc is black
                        // glass and a ripple over the LEDs would read as the panel
                        // doing something. The semantics below put the click back
                        // for accessibility services, which is what `clickable` was
                        // actually buying.
                        detectTapGestures { expanded = !expanded }
                    }
                    .semantics {
                        role = Role.Button
                        contentDescription = label
                        onClick(label) {
                            expanded = !expanded
                            true
                        }
                    },
                shape = CircleShape,
                color = MATRIX_DISC_COLOR,
                border = BorderStroke(FLOATING_PREVIEW_BORDER, MaterialTheme.colorScheme.outlineVariant),
                // Clamped for the same reason as the margin and the size above: the
                // spring undershoots below 0.0 on the way back, and 4.dp - 8.dp of
                // overshoot is a negative elevation.
                shadowElevation = lerp(FLOATING_PREVIEW_REST_SHADOW, FLOATING_PREVIEW_OPEN_SHADOW, fraction)
                    .coerceAtLeast(0.dp),
            ) {
                FloatingPreviewArt(state, playback, caches, dynamic)
            }
            FloatingPreviewBadge(alpha = badgeAlpha)
        }
    }
}

/** The gap between the disc and its label. Enough to read as separate, not as a caption. */
private val FLOATING_PREVIEW_BADGE_GAP = 6.dp

/** The pill's own padding. Small: the whole thing has to stay narrower than a 72 dp circle. */
private val FLOATING_PREVIEW_BADGE_PADDING_H = 8.dp
private val FLOATING_PREVIEW_BADGE_PADDING_V = 3.dp

/**
 * The word that says what the disc is.
 *
 * ## Why it exists
 *
 * Nothing else on the screen does. A 72 dp black circle in the corner of a drawing
 * canvas, quietly cycling, is a thing somebody has to be *told* is a preview — and
 * until they are told, the most natural readings of it are "part of the artwork" or
 * "a control I have not worked out yet". The description that TalkBack reads has
 * always said so; sighted users had nothing.
 *
 * ## Why these colours
 *
 * The pill is filled with `background` and lettered in `onBackground` — the page's
 * own paper and the page's own ink, both of which are the *opposite* of the disc's
 * `MATRIX_DISC_COLOR` glass. That is deliberate and it is the entire trick: a label
 * drawn in the panel's own palette would read as something the matrix is
 * displaying, i.e. as more artwork. Drawn in the page's palette it reads as a note
 * the app has stuck onto the artwork, which is what it is. Monochrome either way;
 * no hue is introduced.
 *
 * ## Why it fades on expand
 *
 * [alpha] is `1 - fraction`, so the pill is at full strength exactly where it is
 * needed and gone by the time the disc is open. Two reasons, and they point the
 * same way. An 86%-of-window disc, centred over a dimmed editor, playing the
 * animation, is not a thing anybody needs labelled — it has become the only thing
 * on screen, and a caption on it is noise. And a `background`-filled chip sitting
 * on top of a 55% scrim would be the brightest object in the window, competing with
 * the artwork it is supposed to be annotating.
 *
 * It keeps its space in the Column the whole way rather than being dropped from the
 * layout at the end, so the disc's centre does not jump 13 dp mid-spring. The cost
 * is that an expanded disc sits half a pill-height above the true centre, which at
 * this size is not a thing the eye finds.
 *
 * `clearAndSetSemantics {}` because it is decoration: the Surface above it already
 * announces itself as "Preview of the design. Tap to enlarge it.", and a screen
 * reader that then read "PREVIEW" would be saying the word twice.
 */
@Composable
private fun FloatingPreviewBadge(alpha: Float) {
    Text(
        text = stringResource(R.string.editor_preview_badge),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onBackground,
        maxLines = 1,
        modifier = Modifier
            .padding(top = FLOATING_PREVIEW_BADGE_GAP)
            .alpha(alpha)
            .background(MaterialTheme.colorScheme.background, CircleShape)
            .padding(
                horizontal = FLOATING_PREVIEW_BADGE_PADDING_H,
                vertical = FLOATING_PREVIEW_BADGE_PADDING_V,
            )
            .clearAndSetSemantics {},
    )
}

/** How dark the editor goes behind an expanded preview. */
private const val SCRIM_ALPHA = 0.55f

/**
 * The ring around the disc. The panel's glass is `#0E0E0E` and the editor's dark
 * page is not far off it, so without one line of `outlineVariant` the preview has
 * no edge at all on a dark theme.
 */
private val FLOATING_PREVIEW_BORDER = 1.dp

private val FLOATING_PREVIEW_REST_SHADOW = 4.dp
private val FLOATING_PREVIEW_OPEN_SHADOW = 12.dp

/**
 * The pixels, blitted from [FramePreviewCaches].
 *
 * Every value that changes is read **inside** the draw lambda — which frame the
 * loop is on, and through the revision the frame's buffer — so a tick and a
 * painted cell each invalidate this one node's draw and recompose nothing. Same
 * discipline as `FrameThumbnailArt` in the timeline, and the reason the widget
 * costs a `drawImage` per tick rather than 169 or 625 `drawRoundRect`s.
 *
 * A **static** design shows frame 0 whatever else the file carries, exactly as
 * `CustomScreen` does on the panel: `kind` is the author's declaration, and a
 * static design's extra frames are not reachable from this editor at all.
 */
@Composable
private fun FloatingPreviewArt(
    state: EditorState,
    playback: PreviewPlayback,
    caches: FramePreviewCaches,
    dynamic: Boolean,
) {
    Canvas(Modifier.fillMaxSize()) {
        val entries = state.frames
        if (entries.isEmpty()) return@Canvas
        val index = if (dynamic) playback.frameIndex.coerceIn(0, entries.lastIndex) else 0
        val entry = entries[index]
        val side = min(size.width, size.height).roundToInt()
        if (side <= 0) return@Canvas
        val raster = min(side, FLOATING_PREVIEW_RASTER_PX)
        val image = caches.of(entry.id).get(
            revision = entry.frame.revisionForDraw(),
            width = raster,
            height = raster,
            density = this,
            layoutDirection = layoutDirection,
        ) {
            val radius = min(this.size.width, this.size.height) / 2f
            drawCircle(MATRIX_DISC_COLOR, radius = radius, center = center)
            drawMatrix(center, radius, entry.frame.size, entry.frame.cellsForDraw())
        }
        drawImage(
            image = image,
            dstOffset = IntOffset(
                ((size.width - side) / 2f).roundToInt(),
                ((size.height - side) / 2f).roundToInt(),
            ),
            dstSize = IntSize(side, side),
        )
    }
}
