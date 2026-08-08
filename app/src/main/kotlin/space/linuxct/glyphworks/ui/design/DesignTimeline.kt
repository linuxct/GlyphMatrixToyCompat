package space.linuxct.glyphworks.ui.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import space.linuxct.glyphworks.R
import space.linuxct.glyphworks.core.design.DesignCodec
import space.linuxct.glyphworks.core.design.KeyMode
import space.linuxct.glyphworks.ui.HintText
import space.linuxct.glyphworks.ui.NoRipple
import space.linuxct.glyphworks.ui.TOGGLE_CONTAINER_SIZE
import space.linuxct.glyphworks.ui.offStateOutline
import androidx.compose.ui.graphics.Canvas as GraphicsCanvas
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The timeline: a horizontal strip of frame thumbnails you can tap to edit,
 * drag to reorder, and add to / duplicate / delete from — plus the per-frame
 * duration control and the two playback settings that only mean anything once a
 * design has more than one frame.
 *
 * ## Where the drag-reorder came from, and what had to be fixed
 *
 * The gesture is `MainActivity.DisplayRow`'s vertical toy reorder turned on its
 * side — `offsetY` -> `offsetX`, `amount.y` -> `amount.x`, `translationY` ->
 * `translationX`, `rowHeightPx` -> [TimelineDragState.itemWidthPx] — because
 * that code is already the app's house pattern for "drag an item past its
 * neighbours" and a second, differently-behaved reorder would be a bug farm.
 *
 * Three things it does NOT do are wrong here, and each is closed below:
 *
 * 1. **It never scrolls.** A toy list holds eighteen rows and is dragged inside
 *    a full-height `LazyColumn`, so the item you want is always on screen. A
 *    timeline is a hundred-and-something frames in a strip six wide, and without
 *    an auto-scroll "move this frame to position 40" is not a gesture that
 *    exists. See [autoScrollWhileDragging].
 * 2. **It keys on the item's identity-by-content.** `DisplayRow` keys on the toy
 *    id, which is unique because toys are. Frames are *duplicable by design* —
 *    see [TimelineEntry] for why content can never be the key, and what goes
 *    wrong if it is.
 * 3. **It is direction-blind.** `translationX` is raw pixels, so in an RTL
 *    locale — where item 2 is drawn to the *left* of item 1 — an unflipped drag
 *    walks the frame the wrong way. See [dragSign].
 *
 * ## Uniform thumbnails, and why the selected one is not bigger
 *
 * Every item is exactly [THUMB_SIZE] wide. That is not a style choice: the
 * reorder maths shares ONE width across all items (the 0.6 threshold, and the
 * `offset -= width` rebase after a swap), so a selected item that grew would
 * make the threshold wrong for its neighbours and leave the rebase off by the
 * difference — the frame would drift out from under the finger, a little more
 * with every swap. Selection is a border and a lift instead, which cost no
 * layout.
 */

/**
 * How far past a neighbour the dragged frame has to travel before they swap, as
 * a fraction of one item's width. Copied from `DisplayRow`, deliberately: 0.6
 * means a swap needs real intent, and the *same* number keeps the two reorders
 * feeling like one gesture.
 */
private const val REORDER_THRESHOLD = 0.6f

/** Side of a frame thumbnail. Uniform across every item — see this file's KDoc. */
private val THUMB_SIZE = 52.dp

/** Gap either side of a thumbnail, and so part of the shared item width. */
private val THUMB_GAP = 4.dp

// ---------- the maths, kept pure so it can be tested ----------

/**
 * How many places the dragged item should move, given how far it has travelled.
 *
 * Positive is towards a higher index. [offsetPx] is a *logical* offset — already
 * flipped by [dragSign], so it always grows in the direction the index does —
 * and the caller rebases it by one [itemWidthPx] per place moved.
 *
 * `DisplayRow` tests a single `if` per pointer event, which is enough when the
 * only thing moving the item is a finger that cannot outrun the input rate. The
 * edge auto-scroll can hand this several item-widths at once, so it returns a
 * count rather than a boolean.
 */
internal fun reorderShift(offsetPx: Float, itemWidthPx: Int, index: Int, lastIndex: Int): Int {
    if (itemWidthPx <= 0 || lastIndex <= 0) return 0
    val threshold = itemWidthPx * REORDER_THRESHOLD
    var offset = offsetPx
    var at = index
    var shift = 0
    while (offset > threshold && at < lastIndex) {
        at++
        shift++
        offset -= itemWidthPx
    }
    while (offset < -threshold && at > 0) {
        at--
        shift--
        offset += itemWidthPx
    }
    return shift
}

/**
 * +1 when the index order runs left-to-right on screen, -1 in an RTL locale.
 *
 * Pointer deltas and `translationX` are both raw, physically-left-to-right
 * pixels; list indices are not. One multiplication in each direction turns the
 * whole reorder into a direction-agnostic one, which is far safer than
 * sprinkling `if (rtl)` through the gesture handler.
 */
internal fun dragSign(rtl: Boolean): Int = if (rtl) -1 else 1

/** Moves one element. False if the move is a no-op or out of range. */
internal fun <T> moveItem(list: MutableList<T>, from: Int, to: Int): Boolean {
    if (from == to || from !in list.indices || to !in list.indices) return false
    list.add(to, list.removeAt(from))
    return true
}

/**
 * Where the selection lands after a frame moves from [from] to [to].
 *
 * The selection follows the *frame*, not the slot: dragging frame 9 to the front
 * leaves you still editing frame 9, and dragging some other frame past the one
 * you are editing shifts your index without changing what you are editing.
 */
internal fun selectionAfterMove(selected: Int, from: Int, to: Int): Int = when {
    selected == from -> to
    from < selected && to >= selected -> selected - 1
    from > selected && to <= selected -> selected + 1
    else -> selected
}

/**
 * Where the selection lands after the frame at [removed] is deleted from a list
 * that now has [sizeAfter] frames — the frame that took its place, or the last
 * one if there is nothing after it.
 */
internal fun selectionAfterDelete(selected: Int, removed: Int, sizeAfter: Int): Int {
    val shifted = if (removed < selected) selected - 1 else selected
    return shifted.coerceIn(0, (sizeAfter - 1).coerceAtLeast(0))
}

/**
 * The durations the +/- buttons walk through.
 *
 * A ladder rather than a slider or a text field, because the range the codec
 * accepts spans three and a half orders of magnitude (20 ms to 60 s) and neither
 * a linear slider nor free text can cover that without either being unusable at
 * the short end or letting through a value the file format would reject. **The
 * first rung IS `MIN_DURATION_MS` and the last IS `MAX_DURATION_MS`**, so this
 * control cannot produce a duration `DesignCodec` would refuse — the validation
 * rule and the UI agree by construction rather than by a second copy of the
 * bounds.
 *
 * The spacing is roughly perceptual: fine where a few milliseconds change the
 * frame rate visibly (20-150 ms is 50 fps down to 7), coarse where they do not.
 */
internal val DURATION_STEPS: IntArray = intArrayOf(
    20, 30, 40, 50, 60, 80, 100, 120, 150, 200, 250, 300, 400, 500, 750,
    1_000, 1_500, 2_000, 3_000, 5_000, 10_000, 20_000, 30_000, 60_000,
)

/** Any duration, forced into the range `DesignCodec` will accept. */
internal fun clampDuration(ms: Int): Int =
    ms.coerceIn(DesignCodec.MIN_DURATION_MS, DesignCodec.MAX_DURATION_MS)

/**
 * The next rung strictly above (or below) [ms]. Saturates at the ends rather
 * than wrapping, so holding the button down cannot loop a 5 s frame back to
 * 20 ms. A value that came from an imported file and is not on the ladder still
 * moves — the search is by comparison, not by index.
 */
internal fun stepDuration(ms: Int, up: Boolean): Int {
    val current = clampDuration(ms)
    return if (up) {
        DURATION_STEPS.firstOrNull { it > current } ?: DesignCodec.MAX_DURATION_MS
    } else {
        DURATION_STEPS.lastOrNull { it < current } ?: DesignCodec.MIN_DURATION_MS
    }
}

/**
 * A duration as the editor shows it: `120 ms`, `1.5 s`, `2 s`.
 *
 * Not a string resource, and that is on purpose: `ms` and `s` are SI symbols,
 * identical in every locale this app could ship in, and a `%d ms` resource would
 * invite a translation that is wrong. The *number* is what varies, and it is
 * formatted here rather than in three call sites.
 */
internal fun formatDurationValue(ms: Int): String {
    val v = clampDuration(ms)
    return when {
        v < 1_000 -> "$v ms"
        v % 1_000 == 0 -> "${v / 1_000} s"
        else -> "${v / 1_000}.${(v % 1_000) / 100} s"
    }
}

/**
 * A whole animation's length. Minutes appear past 60 s, because "94.5 s" is a
 * number you have to do arithmetic on before it means anything.
 */
internal fun formatTotalValue(ms: Int): String {
    if (ms < 60_000) return formatDurationValue(ms)
    val minutes = ms / 60_000
    val seconds = (ms % 60_000) / 1_000
    return "${minutes}m ${seconds}s"
}

// ---------- thumbnail caching ----------

/**
 * One frame's thumbnail, rendered once and kept until the pixels change.
 *
 * Drawing a thumbnail means one rounded rect per cell: 169 at bellsprout, **625
 * at arbok**. With a strip of eight visible that is up to 5 000 draw operations,
 * and the naive version pays them again every single frame the timeline is on
 * screen — which is every frame of every paint stroke, because the selected
 * thumbnail has to track what the finger is doing. It is comfortably the biggest
 * performance trap in this editor.
 *
 * So each frame renders itself into an [ImageBitmap] once and the strip blits
 * it. Invalidation is exact rather than time-based: the key is
 * [EditorFrame.revisionForDraw], which changes if and only if a cell changed, so
 * a stroke re-renders exactly the one thumbnail it touched and nothing else. The
 * pixel size is part of the key too, so a configuration change re-renders rather
 * than blitting a stale bitmap scaled.
 *
 * **Memory is bounded by the strip, not by the design.** The cache is dropped
 * when its item leaves composition (the `LazyRow` disposes what it scrolls past),
 * so a 240-frame design holds bitmaps for the handful on screen and not for the
 * other two hundred. Coming back re-renders — once.
 */
@Stable
internal class ThumbnailCache {
    private var bitmap: ImageBitmap? = null
    private var revision = Int.MIN_VALUE
    private var width = 0
    private var height = 0

    /**
     * The cached bitmap, rendering [draw] into a new one only if [revision] or
     * the size has changed. Call from a draw scope, which is where the revision
     * is legal to read.
     */
    fun get(
        revision: Int,
        width: Int,
        height: Int,
        density: Density,
        layoutDirection: LayoutDirection,
        draw: DrawScope.() -> Unit,
    ): ImageBitmap {
        val cached = bitmap
        if (cached != null && revision == this.revision && width == this.width && height == this.height) {
            return cached
        }
        val target = ImageBitmap(width, height)
        CanvasDrawScope().draw(
            density,
            layoutDirection,
            GraphicsCanvas(target),
            Size(width.toFloat(), height.toFloat()),
            draw,
        )
        bitmap = target
        this.revision = revision
        this.width = width
        this.height = height
        return target
    }

    /** Lets the bitmap go. Called when the thumbnail leaves the strip. */
    fun release() {
        bitmap = null
        revision = Int.MIN_VALUE
    }
}

// ---------- drag state ----------

/**
 * The live reorder, held outside the items so it survives them being recycled.
 *
 * Every offset in here is **logical**: it grows in the direction the frame index
 * grows, whichever way the locale draws the strip. [dragSign] converts at the
 * two boundaries — pointer deltas coming in, `translationX` going out — and
 * nothing in between has to think about it.
 */
@Stable
private class TimelineDragState {
    var draggingIndex by mutableIntStateOf(-1)

    /**
     * The finger's live offset from the item's laid-out slot. Deliberately NOT
     * animated: while a finger is down the frame must track it exactly, and any
     * spring in this path shows up as the thumbnail lagging behind the touch.
     */
    var offsetX by mutableFloatStateOf(0f)

    /**
     * How far the FINGER has travelled since the drag began, and nothing else.
     *
     * [offsetX] cannot answer "which way is the user pushing": it is rebased by
     * an item width on every swap and topped up by the auto-scroll, so it
     * oscillates around zero during a long drag. This one only ever accumulates
     * pointer deltas, which makes it the honest answer to that question — and the
     * edge auto-scroll needs it, or picking up a thumbnail that happens to be
     * sitting at the edge would start scrolling before the user had dragged
     * anywhere at all.
     */
    var pushX by mutableFloatStateOf(0f)

    /** One width for every item — see this file's KDoc for why that is required. */
    var itemWidthPx by mutableIntStateOf(0)

    /**
     * The item that has just been released and is springing back to its slot, or
     * -1 when nothing is settling. Only the RELEASE is animated.
     */
    var settlingIndex by mutableIntStateOf(-1)

    /** The settling item's animated leftover offset, driven towards 0. */
    val settleOffset = Animatable(0f)
}

/**
 * Applies however many swaps the accumulated offset has earned, rebasing the
 * offset by one item width each time so the frame stays under the finger.
 */
private fun applyReorder(state: EditorState, drag: TimelineDragState) {
    val width = drag.itemWidthPx
    val shift = reorderShift(drag.offsetX, width, drag.draggingIndex, state.frames.lastIndex)
    if (shift == 0) return
    val step = if (shift > 0) 1 else -1
    repeat(abs(shift)) {
        val from = drag.draggingIndex
        if (!state.moveFrame(from, from + step)) return
        drag.draggingIndex = from + step
        drag.offsetX -= step * width
    }
}

/**
 * How close to an edge the dragged frame has to get before the strip starts
 * scrolling under it, as a fraction of one item's width. Just under one item, so
 * the trigger zone is "the frame is the last one you can fully see".
 */
private const val EDGE_ZONE_FRACTION = 0.9f

/**
 * How fast the strip scrolls itself while a frame is held at the edge, in pixels
 * per millisecond — about one 60 dp thumbnail every 150 ms at 3x density. Fast
 * enough that crossing sixty frames is a couple of seconds; slow enough to stop
 * on the one you wanted.
 */
private const val EDGE_SCROLL_PX_PER_MS = 1.2f

/** Never advance more than this in one frame, however long the frame took. */
private const val EDGE_SCROLL_MAX_STEP = 60f

/**
 * Which way, if either, the strip should be scrolling itself: +1 towards higher
 * indices, -1 towards lower, 0 for neither.
 *
 * The dragged frame's live position is its laid-out slot plus the drag offset,
 * both of which are in the list's own scroll-axis coordinates — so this needs no
 * knowledge of where the finger is on screen, and no RTL handling: a horizontal
 * `LazyRow` already counts its scroll axis in the direction its indices run.
 *
 * Being near an edge is necessary but not sufficient: the finger has to be
 * pushing TOWARDS it ([TimelineDragState.pushX]). Without that, picking up the
 * leftmost visible thumbnail — which is by definition against the left edge —
 * would send the strip scrolling before the user had moved at all.
 */
private fun edgeDirection(listState: LazyListState, drag: TimelineDragState): Int {
    val info = listState.layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == drag.draggingIndex } ?: return 0
    val start = item.offset + drag.offsetX
    val end = start + item.size
    val zone = item.size * EDGE_ZONE_FRACTION
    return when {
        drag.pushX > 0f && end > info.viewportEndOffset - zone -> 1
        drag.pushX < 0f && start < info.viewportStartOffset + zone -> -1
        else -> 0
    }
}

// ---------- the strip ----------

@Composable
internal fun Timeline(state: EditorState, onChanged: () -> Unit) {
    val drag = remember { TimelineDragState() }
    val listState = rememberLazyListState()
    val sign = dragSign(LocalLayoutDirection.current == LayoutDirection.Rtl)

    // Edge auto-scroll: the strip scrolls itself while a dragged frame is held
    // near either end.
    //
    // This is the one place in the editor that runs a frame loop, and it exists
    // for exactly as long as a finger is down: the effect is keyed on whether a
    // drag is in progress, so an idle editor — even one with the timeline on
    // screen — never enters it and issues no frames at all. `dragging` is
    // DERIVED so the key flips when a drag starts or ends and not on every swap
    // along the way; `draggingIndex` changes once per neighbour passed, and
    // reading it here directly would recompose the whole strip each time for a
    // value only this boolean cares about.
    //
    // Each step scrolls the list and then adds what was actually consumed back
    // onto the drag offset. That is what keeps the frame pinned under a
    // stationary finger while the strip slides beneath it, and it is also what
    // drives the reorder: the growing offset crosses the swap threshold again
    // and again, so the frame walks past every neighbour the scroll brings under
    // it. Holding at the edge of a 60-frame timeline therefore carries the frame
    // all the way to the end in one uninterrupted gesture, which is the whole
    // point. `scrollBy` returning 0 means the list is already at that end; the
    // loop keeps spinning (the finger may still come back the other way) but
    // nothing moves.
    val dragging by remember { derivedStateOf { drag.draggingIndex >= 0 } }
    LaunchedEffect(dragging) {
        if (!dragging) return@LaunchedEffect
        var previous = 0L
        while (isActive) {
            val now = withFrameNanos { it }
            val elapsed = if (previous == 0L) 0f else (now - previous) / 1_000_000f
            previous = now
            val direction = edgeDirection(listState, drag)
            if (direction == 0 || elapsed <= 0f) continue
            val step = (elapsed * EDGE_SCROLL_PX_PER_MS).coerceAtMost(EDGE_SCROLL_MAX_STEP)
            val consumed = listState.scrollBy(direction * step)
            if (consumed != 0f) {
                drag.offsetX += consumed
                applyReorder(state, drag)
            }
        }
    }

    // Keep the selected frame reachable when the selection moves on its own —
    // adding, duplicating or deleting all land on a frame that may be off the
    // end of the strip. Never during a drag: the strip is already being scrolled
    // by the finger, and a competing animation would fight it.
    LaunchedEffect(state.selectedIndex, state.frames.size) {
        if (drag.draggingIndex >= 0) return@LaunchedEffect
        val info = listState.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == state.selectedIndex }
        val visible = item != null &&
            item.offset >= info.viewportStartOffset &&
            item.offset + item.size <= info.viewportEndOffset
        if (!visible) listState.animateScrollToItem(state.selectedIndex)
    }

    LazyRow(
        state = listState,
        modifier = Modifier.fillMaxWidth().selectableGroup(),
        contentPadding = PaddingValues(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(state.frames, key = { _, entry -> entry.id }) { index, entry ->
            // The dragged thumbnail is positioned by hand, so it must not also
            // be animated into place — the two would fight and it would lag the
            // finger. Everything else passes all THREE specs explicitly, because
            // animateItem's own defaults are foundation's rather than MD3's:
            // sliding to a new slot is a POSITION change and takes the spatial
            // spring, while the fades are alpha and take the effects spring,
            // which never bounces.
            val placement = if (drag.draggingIndex == index) {
                Modifier
            } else {
                Modifier.animateItem(
                    fadeInSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
                    placementSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
                    fadeOutSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
                )
            }
            FrameThumbnail(
                state = state,
                entry = entry,
                index = index,
                drag = drag,
                sign = sign,
                placement = placement,
                onChanged = onChanged,
            )
        }
    }

    FrameActionRow(state, onChanged)

    val hint = if (state.frames.size <= 1) {
        stringResource(R.string.editor_timeline_single)
    } else {
        stringResource(
            R.string.editor_timeline_status,
            state.selectedIndex + 1,
            state.frames.size,
            formatTotalValue(state.totalDurationMs),
        )
    }
    HintText(hint)
}

@Composable
private fun FrameThumbnail(
    state: EditorState,
    entry: TimelineEntry,
    index: Int,
    drag: TimelineDragState,
    sign: Int,
    placement: Modifier,
    onChanged: () -> Unit,
) {
    val dragging = drag.draggingIndex == index
    val settling = drag.settlingIndex == index
    val selected = state.selectedIndex == index
    val scope = rememberCoroutineScope()
    val settleSpec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    val label = stringResource(R.string.editor_frame_thumb, index + 1)

    // Let the bitmap go the moment this thumbnail is scrolled out of the strip;
    // see [ThumbnailCache] for why that is what bounds the editor's memory.
    DisposableEffect(entry) {
        onDispose { entry.thumbnail.release() }
    }

    /** Releases the drag and springs the leftover offset back to zero. */
    fun release() {
        val released = drag.draggingIndex
        val from = drag.offsetX
        drag.draggingIndex = -1
        drag.offsetX = 0f
        drag.pushX = 0f
        onChanged()
        scope.launch {
            drag.settlingIndex = released
            try {
                drag.settleOffset.snapTo(from)
                drag.settleOffset.animateTo(0f, settleSpec)
            } finally {
                // Also on cancellation: if the thumbnail is scrolled out of the
                // strip mid-settle its scope dies, and a stuck settlingIndex
                // would pin a stale translationX on whatever lands there next.
                drag.settlingIndex = -1
            }
        }
    }

    // The border says "this is the frame you are editing" and the lift says
    // "this one is in your hand". Both are monochrome by necessity and by
    // choice: there is no accent colour in this app to reach for, and a size
    // change is not available (see this file's KDoc).
    val borderWidth by animateDpAsState(
        targetValue = if (selected) 2.dp else 1.dp,
        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
        label = "frameBorderWidth",
    )
    val borderColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.outlineVariant
        },
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "frameBorderColor",
    )
    val shadow by animateDpAsState(
        targetValue = if (dragging) 6.dp else 0.dp,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "frameShadow",
    )

    Column(
        modifier = Modifier
            .then(placement)
            // Reported per position, not per frame id: the guided tour points at
            // "the third thumbnail", which is what a caption can talk about.
            .demoTarget(DemoTarget.FRAME, index)
            .zIndex(if (dragging || settling) 1f else 0f)
            .graphicsLayer {
                // Logical offset out to physical pixels — the RTL boundary.
                translationX = sign * when {
                    dragging -> drag.offsetX
                    settling -> drag.settleOffset.value
                    else -> 0f
                }
            }
            // BEFORE the padding, so the recorded width is the item's full
            // pitch — the distance from one thumbnail to the next, which is what
            // the reorder threshold and the offset rebase are measured in.
            .onSizeChanged { drag.itemWidthPx = it.width }
            .padding(horizontal = THUMB_GAP, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        NoRipple {
            Surface(
                modifier = Modifier
                    .size(THUMB_SIZE)
                    .selectable(
                        selected = selected,
                        role = Role.RadioButton,
                        onClick = { state.select(index) },
                    )
                    .semantics { contentDescription = label }
                    // Press-and-hold to drag, so a plain tap still selects. The
                    // long-press detector does not consume the initial down, and
                    // once it starts dragging it consumes the moves, which
                    // cancels the pending click — the same cooperation the paint
                    // canvas relies on between its two detectors.
                    .pointerInput(entry.id) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                // By id, not by the captured index: the strip may
                                // have been reordered since this item was last
                                // composed. A frame that has since been deleted
                                // simply does not start a drag.
                                val at = state.frames.indexOfFirst { it.id == entry.id }
                                if (at >= 0) {
                                    drag.draggingIndex = at
                                    drag.offsetX = 0f
                                    drag.pushX = 0f
                                    // Dragging a frame is working on it.
                                    state.select(at)
                                }
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                // Physical pixels in to a logical offset — the
                                // other half of the RTL boundary.
                                val logical = amount.x * sign
                                drag.offsetX += logical
                                drag.pushX += logical
                                applyReorder(state, drag)
                            },
                            onDragEnd = ::release,
                            onDragCancel = ::release,
                        )
                    },
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(borderWidth.coerceAtLeast(0.dp), borderColor),
                // Never let a bouncy spring drive elevation NEGATIVE: Surface
                // rejects it, and the fast spatial spring is under-damped.
                shadowElevation = shadow.coerceAtLeast(0.dp),
            ) {
                FrameThumbnailArt(entry)
            }
        }
        Text(
            text = "${index + 1}",
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

/**
 * The frame's pixels, blitted from [ThumbnailCache].
 *
 * Every snapshot value it reads — the revision, and through it the buffer — is
 * read *inside* the draw lambda, so painting invalidates this node's draw and
 * recomposes nothing. That is the same discipline the main canvas uses and the
 * reason a moving finger costs one thumbnail re-render and one blit rather than
 * a pass over the whole timeline.
 */
@Composable
private fun FrameThumbnailArt(entry: TimelineEntry) {
    Canvas(Modifier.size(THUMB_SIZE)) {
        val width = size.width.roundToInt()
        val height = size.height.roundToInt()
        if (width <= 0 || height <= 0) return@Canvas
        val image = entry.thumbnail.get(
            revision = entry.frame.revisionForDraw(),
            width = width,
            height = height,
            density = this,
            layoutDirection = layoutDirection,
        ) {
            val radius = min(size.width, size.height) / 2f
            drawCircle(MATRIX_DISC_COLOR, radius = radius, center = center)
            drawMatrix(center, radius, entry.frame.size, entry.frame.cellsForDraw())
        }
        drawImage(image)
    }
}

/**
 * Duration for the selected frame, and the three things you can do to it.
 *
 * One row, because all six controls act on the same thing — the frame the
 * timeline has selected — and because the editor is already spending most of a
 * phone screen on controls that are not the canvas.
 *
 * The two clusters are nested `Row`s rather than six children of one: the layout
 * is identical (a spacer with all the weight still separates them), and it makes
 * "how long this frame is" and "which frames there are" two things a reader — and
 * the guided demo, which spotlights one at a time — can name separately.
 */
@Composable
private fun FrameActionRow(state: EditorState, onChanged: () -> Unit) {
    val duration = state.selected.durationMs
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = { if (state.setSelectedDuration(stepDuration(duration, up = false))) onChanged() },
                modifier = Modifier.demoTarget(DemoTarget.DURATION, 0),
                enabled = duration > DesignCodec.MIN_DURATION_MS,
            ) {
                Icon(Icons.Outlined.Remove, contentDescription = stringResource(R.string.editor_duration_shorter))
            }
            // Fixed width so stepping through the ladder does not shuffle the
            // buttons either side of it as the text gets longer.
            Text(
                text = formatDurationValue(duration),
                modifier = Modifier.width(64.dp),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelLarge,
            )
            IconButton(
                onClick = { if (state.setSelectedDuration(stepDuration(duration, up = true))) onChanged() },
                modifier = Modifier.demoTarget(DemoTarget.DURATION, 1),
                enabled = duration < DesignCodec.MAX_DURATION_MS,
            ) {
                Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.editor_duration_longer))
            }
        }
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = { if (state.addFrame()) onChanged() },
                modifier = Modifier.demoTarget(DemoTarget.FRAME_ACTIONS, 0),
                enabled = !state.atFrameLimit,
            ) {
                Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.editor_frame_add))
            }
            IconButton(
                onClick = { if (state.duplicateFrame()) onChanged() },
                modifier = Modifier.demoTarget(DemoTarget.FRAME_ACTIONS, 1),
                enabled = !state.atFrameLimit,
            ) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.editor_frame_duplicate))
            }
            // Disabled on the last frame rather than converting the design to
            // static — see EditorState.deleteFrame for why.
            IconButton(
                onClick = { if (state.deleteFrame()) onChanged() },
                modifier = Modifier.demoTarget(DemoTarget.FRAME_ACTIONS, 2),
                enabled = state.frames.size > 1,
            ) {
                Icon(Icons.Outlined.DeleteOutline, contentDescription = stringResource(R.string.editor_frame_delete))
            }
        }
    }
}

// ---------- playback ----------

/**
 * What the Essential Key does, and — where it means anything — whether the
 * animation repeats. The two settings that decide how the design *behaves* on
 * the matrix rather than what it looks like.
 *
 * **Key mode** is a pick-one-of-two, so segmented buttons. The labels describe
 * the key press rather than naming the enum, because the only question the user
 * is answering is "what happens when I press it".
 *
 * **Repeat** is the music player's repeat button, as asked for, and its on/off
 * states are not a tint: the container fills and the shape morphs from a circle
 * to a rounded square (MD3's own `toggleableShapes`, the same affordance the
 * auto-brightness toggle uses), *and* the sentence beside it changes to say what
 * will actually happen. Nobody has to guess which state is which.
 *
 * ## Why Repeat is only shown for play / pause
 *
 * `CustomScreen.advance()`'s `PLAY_ONCE` arm never consults `loop` — "plays
 * through once and returns to frame 0" leaves nothing for a repeat to mean, and
 * the format says so explicitly. Offering the toggle in that mode gave the user a
 * control that moved, persisted, and changed nothing on the matrix. **A control
 * that lies about having an effect is worse than an absent one**, so it is shown
 * only where it has one.
 *
 * The stored value is deliberately **left alone** when it is not being offered.
 * Switching to play once does not clear `loop`, so switching back restores the
 * choice the user actually made rather than silently resetting it — and the
 * format needs no new state to express that, because `playOnce` already ignores
 * the field wherever it is read. A `playOnce` design carrying `loop: true` is
 * therefore normal and harmless, not an inconsistency to be repaired.
 *
 * Key mode comes **first** in the layout for the same reason: it is the control
 * that decides whether the other one exists, and a row that appears and
 * disappears *above* the thing you just tapped would move it under your finger.
 *
 * Shown inside the editor's settings dialog rather than on the drawing surface —
 * see `DesignSettings` for why — so it carries no horizontal inset of its own
 * and takes the padding of whatever contains it.
 */
@Composable
internal fun PlaybackRow(state: EditorState, onChanged: () -> Unit) {
    // The heading carries the context the labels used to: they read "Key: play
    // once" / "Key: play / pause", and the second of those was clipped at the
    // right edge of the settings dialog on a real device. A segment gets half a
    // dialog minus its own padding and check-mark — around 110 dp on a compact
    // window — which "Key: play / pause" does not fit at the default font scale,
    // let alone at the large ones. Saying it once above the pair is both shorter
    // and clearer than saying "Key:" twice, and it is the label that gives way
    // rather than the selection indicator.
    Text(
        stringResource(R.string.editor_key_label),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    NoRipple {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            KEY_MODES.forEachIndexed { i, (mode, label) ->
                SegmentedButton(
                    selected = state.design.keyMode == mode,
                    onClick = { if (state.setKeyMode(mode)) onChanged() },
                    modifier = Modifier.demoTarget(DemoTarget.KEY_MODE, i),
                    shape = SegmentedButtonDefaults.itemShape(index = i, count = KEY_MODES.size),
                ) {
                    SegmentLabel(stringResource(label))
                }
            }
        }
    }
    if (state.design.keyMode == KeyMode.PLAY_PAUSE) {
        Spacer(Modifier.height(8.dp))
        LoopRow(state, onChanged)
    }
}

/**
 * Repeat: the icon, the sentence that says what it means, and **one toggle
 * target across both of them**.
 *
 * ## Why the whole row, when [SwitchRow] deliberately does the opposite
 *
 * `SwitchRow` and `PrefSwitch` leave the row inert and put the gesture on the
 * `Switch`, and their KDoc argues for it: the toggleable `ListItem` overload
 * would demote the switch to a passive graphic and announce `Role.Checkbox`
 * where `Role.Switch` is the truth. That reasoning holds *there* and does not
 * hold here, and the difference is not stylistic:
 *
 * **A `Switch` looks like a control. A bare icon does not.** A switch is a track
 * with a thumb sitting on it — a shape whose only possible meaning is "flip me" —
 * so a row containing one needs no further affordance, and pointing the whole row
 * at it buys nothing. This control is a repeat *glyph* in a filled circle, which
 * reads as a status badge next to a line of explanatory text, and **four testers
 * never worked out it was a button**. The fix that matches what people actually
 * tried to do is to make the thing they tapped — the label — do the job.
 *
 * So: do not "fix" this back into the `SwitchRow` shape. It was that shape, and
 * that is the bug.
 *
 * ## One target, announced once
 *
 * The icon is [LoopIndicator] — a rendered *indicator*, not a button. That is
 * load-bearing rather than incidental: a `FilledIconToggleButton` inside a
 * toggleable row would be a nested clickable, which means two hit targets whose
 * pressed states can disagree, an inner one that swallows the gesture before the
 * row's own indication can respond, and a control TalkBack announces twice. The
 * row owns the gesture and nothing inside it is clickable, so there is exactly
 * one node: `Role.Switch`, merged, announced as *"Repeat, Repeats until you pause
 * it, switch, on"* — the icon's `contentDescription` naming it and the state
 * sentence merging in behind it, which is why neither needs a label of its own.
 *
 * ## A container, because a tappable rectangle has to look like one
 *
 * Making the row toggleable fixed where the gesture lands and nothing about what
 * the eye sees: an icon and a sentence on the bare dialog surface, with no edge
 * anywhere, still reads as a caption. So the whole row is a [Surface] now, and
 * **the container is exactly the touch target** — icon, label and all — because a
 * box smaller than the thing that responds teaches the wrong rectangle.
 *
 * `surfaceVariant`, for the reason `selectedRowColors` gives at length: it is this
 * scheme's "a surface, one step differentiated" role, and the containers that
 * sound louder (`secondaryContainer`, #2E3138 on a #191C20 dialog) are far too
 * loud for a strictly monochrome palette. What it is NOT is `tonalElevation`,
 * which this theme deliberately makes a no-op — `surfaceTint` equals the card
 * colour in both schemes (see `ui/theme/Theme.kt`), so a tonally elevated Surface
 * here is the same colour it started as.
 *
 * The honest arithmetic, since one step of grey is a small thing to bet on:
 * against the dialog's `surface`, `surfaceVariant` is #EBEBEF on white — 1.19:1 —
 * and #23262B on #191C20 — 1.13:1. That is a boundary you can see and not one you
 * could read text off, which is why it does not carry this alone:
 *
 *  - **Light** gets [LOOP_ROW_ELEVATION]'s shadow, which is what actually draws
 *    the edge when the fill is a 1.19:1 whisper. A shadow on white is the only
 *    one of the two cues that works on white.
 *  - **Dark** gets the fill, which is how a dark UI expresses elevation in the
 *    first place — a lighter block on a darker page. The shadow is invisible on
 *    near-black, and is left in place rather than branched on: it costs nothing
 *    and there is no such thing as a dark scheme where it hurts.
 *  - **Both** get the outlined circle inside ([LoopIndicator]) and a full-ink
 *    label, which is the third and loudest cue and the one that survives any
 *    display.
 *
 * The label is `onSurface` rather than the supporting `onSurfaceVariant` it used
 * to be, and that is contrast rather than taste: this ink on this container is
 * 4.38:1 in light and 4.17:1 in dark, both a shade under the 4.5:1 that body text
 * wants, because tinting the container moved the floor up under the text. Full
 * ink puts it back at 15:1 and 13:1 — and a control's own label is not supporting
 * prose anyway.
 *
 * ## Ripple: still off, but no longer without an answer
 *
 * [NoRipple] is the app's rule for toggles, on the argument that the control's own
 * state animation IS the feedback. That argument was sound while this was a bare
 * icon and it is only half sound now. The row is a full dialog width wide, so the
 * ripple this app rejects everywhere else would be an even worse offence here —
 * a wash across the whole settings sheet answering a tap on one row. But the only
 * state animation in it is a 40 dp indicator pinned to the left edge, and a finger
 * that lands on the sentence gets no acknowledgement anywhere near where it
 * touched. A container that looks pressable and answers nothing is its own bug.
 *
 * So: no ripple, and a **press state on the container instead** — MD3's pressed
 * state-layer opacity ([LOOP_ROW_PRESS_ALPHA]) of `onSurface` over the row's own
 * fill, which darkens in light and lightens in dark, covers the entire target
 * including the sentence, and settles on the effects spring like every other
 * colour in this app. It is the state layer, bounded to the control, without the
 * expanding wash. Reaching it needs the explicit `interactionSource`, which is
 * why `indication = null` is spelled out at the call site here rather than
 * inherited from a [NoRipple] wrapper — two ways of saying one thing, one of
 * which would be silently doing nothing.
 *
 * The 8 dp of vertical padding sits INSIDE the Surface, so the container and the
 * 56 dp target are the same rectangle, comfortably over the 48 dp minimum.
 */
@Composable
private fun LoopRow(state: EditorState, onChanged: () -> Unit) {
    val loop = state.design.loop
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val container by animateColorAsState(
        targetValue = if (pressed) {
            MaterialTheme.colorScheme.onSurface
                .copy(alpha = LOOP_ROW_PRESS_ALPHA)
                .compositeOver(MaterialTheme.colorScheme.surfaceVariant)
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "loopRowContainer",
    )
    Surface(
        // Tagged as a whole: the guided demo spotlights the icon AND the
        // sentence, because the sentence changing is half of what makes the two
        // states legible — and because the row being the target is now itself one
        // of the things the tour has to teach. The tag is on the same node as the
        // container, so the spotlight is cut around the block the user sees.
        modifier = Modifier
            .fillMaxWidth()
            .demoTarget(DemoTarget.LOOP)
            .toggleable(
                value = loop,
                interactionSource = interaction,
                indication = null,
                role = Role.Switch,
                onValueChange = { if (state.setLoop(it)) onChanged() },
            )
            // `toggleable` merges its descendants already; stated explicitly
            // because "announced once" is the requirement, not a side effect
            // of an implementation detail three libraries down.
            .semantics(mergeDescendants = true) {},
        shape = RoundedCornerShape(LOOP_ROW_CORNER),
        color = container,
        shadowElevation = LOOP_ROW_ELEVATION,
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LoopIndicator(loop)
            Text(
                text = stringResource(if (loop) R.string.editor_loop_on else R.string.editor_loop_off),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** The repeat row's container corner — a control-sized radius inside a 28 dp dialog. */
private val LOOP_ROW_CORNER = 16.dp

/**
 * The repeat row's shadow. Small on purpose: it is drawing an EDGE on white, not
 * lifting a card off the page, and this app's only other resting shadows are the
 * 8 dp ones under things that genuinely float above the page (the navigation pill,
 * the tour's caption). A row inside a dialog is not one of those.
 */
private val LOOP_ROW_ELEVATION = 2.dp

/** MD3's pressed state-layer opacity, which is all the press state is. */
private const val LOOP_ROW_PRESS_ALPHA = 0.12f

/**
 * The repeat icon in its on/off state — [FilledIconToggleButton]'s look without
 * its gesture.
 *
 * Hand-rolled, and only because the library has no non-interactive form of that
 * component: every `IconToggleButton` overload takes a non-null `onCheckedChange`
 * and is therefore a click target, which is the one thing this must not be (see
 * [LoopRow]). Passing an empty lambda would be worse than a nested clickable — it
 * would be a dead zone over the very icon people are already trying to press.
 *
 * What is reproduced is exactly what MD3's `toggleableShapes` gives the app's
 * other icon toggles, and nothing more: a full circle when off, squaring off to a
 * 12 dp-cornered rounded square when on, over the component's own
 * `filledIconToggleButtonColors`. Both animate on the **effects** spring, which
 * never bounces — the same choice, for the same reason, as the auto-brightness
 * toggle in `MainActivity`: a toggle that wobbled as it settled would read as
 * uncertainty about which state it had landed in.
 *
 * **Outlined while off**, by the same [offStateOutline] the app's other stateful
 * icon toggles carry. An unchecked `filledIconToggleButtonColors` container is the
 * `surfaceContainer` token, which this theme pins to the card colour in both
 * schemes — so before the row had a container of its own, "off" was a repeat
 * glyph floating on nothing. The row's container fixed half of that (a disc of
 * card-white now sits on the row's `surfaceVariant`, ~1.19:1 in light and ~1.13:1
 * in dark — the same whisper, one layer in) and the 1 dp ring fixes the rest, at
 * 3.14:1 and 3.23:1 against that container. The ring is `onSurfaceVariant` at
 * three quarters opacity, which is not an arbitrary dilution: at full strength
 * the user read it as a hard black circle drawn around the glyph rather than as
 * the container's own edge. Off is a circle with an edge; on is the filled
 * squircle (`primary`, and near-black on light / near-white on dark, so the state
 * change is unmissable). The ring rides its own progress value on the same
 * effects spring, so it runs the identical curve as the corner below and the two
 * stay concentric through the morph rather than cutting.
 *
 * **The size comes from [offStateOutline] too**, which is why there is no `size`
 * on the `Surface` below: the ring is drawn against [TOGGLE_CONTAINER_SIZE] and a
 * second, fixed size on the outside would win the measure and leave the ring
 * describing a box the container no longer fills.
 *
 * The pressed-shape pinch is the one thing not reproduced, and it cannot be: the
 * press belongs to the row now, not to this — and the row answers a press with
 * its own container, so nothing is lost.
 */
@Composable
private fun LoopIndicator(loop: Boolean) {
    val colors = IconButtonDefaults.filledIconToggleButtonColors()
    val container by animateColorAsState(
        targetValue = if (loop) colors.checkedContainerColor else colors.containerColor,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "loopContainer",
    )
    val corner by animateDpAsState(
        targetValue = if (loop) LOOP_CHECKED_CORNER else TOGGLE_CONTAINER_SIZE / 2,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "loopCorner",
    )
    Surface(
        modifier = Modifier.offStateOutline(loop),
        // The effects spring cannot overshoot, so this cannot go negative today.
        // Coerced anyway: a negative corner radius is not a legal shape, and the
        // spec on this line is one word away from being the under-damped one.
        shape = RoundedCornerShape(corner.coerceAtLeast(0.dp)),
        color = container,
        contentColor = if (loop) colors.checkedContentColor else colors.contentColor,
    ) {
        Box(contentAlignment = Alignment.Center) {
            // The only description in the row. See [LoopRow] on what is announced.
            Icon(Icons.Outlined.Repeat, contentDescription = stringResource(R.string.editor_loop))
        }
    }
}

/** The corner the circle squares off to when repeat is on — `toggleableShapes`'s own. */
private val LOOP_CHECKED_CORNER = 12.dp

/**
 * The label inside a segmented button, sized so it cannot be truncated.
 *
 * Both of the editor's segment rows carry text longer than the default styling
 * assumes — the key modes here, and the device names in `VariantRow` ("Nothing
 * Phone (4a) Pro") — and a segmented button clips rather than shrinks.
 *
 * **What is not spent to fix that: the check-mark.** In a
 * `SingleChoiceSegmentedButtonRow` the check is MD3's primary indicator of which
 * option is selected — the container fill reinforces it, it does not replace it —
 * and it is the affordance a user scanning the row actually reads. Dropping it
 * to buy ~26 dp of label width trades a legibility problem for a comprehension
 * one, and leaves these two rows inconsistent with every other segmented row in
 * the app. So every `SegmentedButton` keeps the default `icon`, and the width is
 * found in the label instead:
 *
 * - `labelMedium` (12 sp) rather than the 14 sp `labelLarge` the component
 *   provides by default;
 * - **up to three lines**. A segment is `(window - 32 dp) / 2` wide and the
 *   component spends 12 dp of padding, 18 dp of icon, 8 dp of gap and 12 dp of
 *   padding of its own, leaving ~130 dp of text on a 393 dp window *while
 *   selected* (the check collapses to zero width when it is not). "Nothing Phone
 *   (4a) Pro" is ~132 dp on one line at 12 sp, so it takes two — "Nothing Phone"
 *   / "(4a) Pro" — with room to spare. At the largest accessibility font scale
 *   the same 130 dp holds about 12 characters a line, so the same label needs
 *   three. Nothing beyond three is reachable by anything this app puts here.
 *
 * The 40 dp segment height is a MINIMUM, so a wrapped label makes the row taller
 * rather than being cut off by it. Growing is the intended failure mode; the row
 * is already taller at those font scales, and a user who has asked for large text
 * wants the whole label, not a tidy one.
 */
@Composable
internal fun SegmentLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        textAlign = TextAlign.Center,
        maxLines = 3,
    )
}

/** The two key modes in the order they are offered, with their labels. */
private val KEY_MODES = listOf(
    KeyMode.PLAY_ONCE to R.string.editor_key_once,
    KeyMode.PLAY_PAUSE to R.string.editor_key_toggle,
)
