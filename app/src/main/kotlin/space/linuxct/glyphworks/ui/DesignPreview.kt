package space.linuxct.glyphworks.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import space.linuxct.glyphworks.core.design.Design
import space.linuxct.glyphworks.core.design.DesignFrames
import space.linuxct.glyphworks.core.design.DesignKind
import space.linuxct.glyphworks.core.design.PokemonCodename
import space.linuxct.glyphworks.ui.design.MATRIX_DISC_COLOR
import space.linuxct.glyphworks.ui.design.ThumbnailCache
import space.linuxct.glyphworks.ui.design.drawMatrix
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The animated circular previews on the Create tab's design grid.
 *
 * ## Why this is a file of its own
 *
 * It is the only clock-driven thing in the app outside the editor, and the
 * project has a documented history with exactly this kind of code: a `Scaffold`
 * was taken off the scroll-heavy screens after a long investigation into a
 * scrolling stutter, and the design editor deliberately issues **no** frames when
 * it is idle. A grid of looping previews is the opposite of that by nature, so
 * the machinery that bounds it is written down in one place rather than scattered
 * through `CreateTab.kt`.
 *
 * ## The four things that keep it cheap
 *
 * 1. **One clock for the whole grid.** [PreviewClock] is a single
 *    `withFrameMillis` loop that walks a plain list of registered [PreviewPlayer]s
 *    once per frame. A `withFrameNanos` loop per card would mean one coroutine,
 *    one frame callback and one recomposition scope per visible cell.
 * 2. **Only what is composed animates.** A player registers from the card's
 *    `DisposableEffect` and unregisters when the cell leaves composition, and
 *    `LazyVerticalGrid` composes the visible window (plus whatever it is
 *    prefetching) and nothing else. Scrolling a design out of view stops its clock
 *    without anything having to notice.
 * 3. **Nothing ticks when nothing needs it.** [PreviewClock.animating] is
 *    snapshot state and false whenever every registered player is a still, so a
 *    page of static designs runs no frame loop at all — and the loop is
 *    additionally gated on the app being resumed and the tab being on screen. See
 *    `CreateTab`.
 * 4. **A cell is rasterised once, not once per displayed frame.** Each sampled
 *    frame owns a [ThumbnailCache], so the per-tick cost is a `drawImage` blit
 *    rather than 169 (bellsprout) or 625 (arbok) `drawRoundRect` calls.
 *
 * And the tick itself writes ONE `mutableIntStateOf` per card, read only from
 * inside the `Canvas` draw lambda — so a frame change invalidates that card's
 * draw and recomposes nothing at all. That is the same discipline
 * `FrameThumbnailArt` uses in the editor's timeline.
 */

// ---------- how many columns ----------

/**
 * The width one design cell aims for. Not a minimum and not a maximum: it is the
 * divisor that turns a window width into a column count, so cards end up somewhere
 * around it on every window rather than exactly at it on one.
 */
private val DESIGN_CELL_TARGET_WIDTH = 120.dp

/** Never fewer than this many columns, however narrow the window is. */
internal const val DESIGN_GRID_MIN_COLUMNS = 3

/**
 * Never more than this many, however wide it is. Past six the cards stop being
 * cards and start being a contact sheet, and the preview — the reason the grid
 * exists — is the thing that would shrink.
 */
internal const val DESIGN_GRID_MAX_COLUMNS = 6

/**
 * How many columns the design grid gets in a window [available] dp wide.
 *
 * **Three on a phone**, which is the count the user asked for, and adaptive above
 * that so a tablet or an unfolded foldable fills its width instead of showing
 * three enormous cards. The floor matters as much as the ratio: a 320 dp window
 * (a small phone, or a split-screen half) works out at two by arithmetic, and
 * silently dropping a column there would make the tab look like a different
 * feature on a narrow device.
 *
 * Pure, and separated from the composable that feeds it for the same reason
 * [dialogCardWidth] is: the window measurement is the part a unit test cannot run,
 * and the arithmetic is the part worth pinning down.
 */
internal fun designGridColumns(available: Dp): Int {
    if (!available.isSpecified || available <= 0.dp) return DESIGN_GRID_MIN_COLUMNS
    val fits = (available / DESIGN_CELL_TARGET_WIDTH).toInt()
    return fits.coerceIn(DESIGN_GRID_MIN_COLUMNS, DESIGN_GRID_MAX_COLUMNS)
}

// ---------- how tall a card is ----------

/**
 * The grid's margins: the full one against the window edge, the gutter between two
 * cells.
 *
 * They differ — 12 outside, 8 between — because at three columns the gutters are
 * paid for three times over and the edges only twice, and a 12 dp gutter on a
 * ~106 dp card is a visible slice of the preview.
 *
 * They live here rather than in `CreateTab.kt` because they are half of the
 * card-height arithmetic below, and that arithmetic is the part worth testing.
 */
internal val DESIGN_GRID_OUTER_MARGIN = 12.dp
internal val DESIGN_GRID_GUTTER = 8.dp

/**
 * The disc's own inset inside a card, before the column correction: **18 dp on all
 * three sides**.
 *
 * They are two constants rather than one because only the side inset is corrected
 * per column ([designDiscSideInset]) — the top is the same on every card in the
 * grid — so nothing in the card's geometry requires them to agree. Today they do.
 *
 * ## Why the top was 14 dp and is not any more
 *
 * The gap that has to be defended is **diagonal**: the overflow button sits over
 * the corner that a circle inscribed in a square leaves empty, so the honest
 * measurement is from the glyph's nearest ink to the circle's edge, not from one
 * bounding box to another. Nothing overlapped, and it still read as cramped,
 * which is what a diagonal gap measured as a box always does.
 *
 * On the 411 dp phone this app is built for, at three columns: the slot is 137 dp,
 * an outer cell hands 16 dp of it back to the grid, so the card is 121 dp and the
 * disc is `121 - 2*18` = 85 dp across. At a top inset of 14 the disc's centre sat
 * 36.5 dp left of the overflow glyph's and 32.5 dp below it — 48.9 dp apart, of
 * which 42.5 dp is radius, leaving 6.4 dp from the glyph's *centre* to the circle.
 * The lowest of the three dots reaches 6.0 dp in that direction, so the gap
 * anybody could actually see was **0.4 dp**. On a 360 dp window the same
 * arithmetic came out at **-3.0 dp**: the dots crossed the disc.
 *
 * At 18 dp, and with the button moved into the corner it belongs in (see
 * `DesignCard`, which is the other half of this change), that clearance is 8.5 dp
 * on the phone and 5.0 dp at 360 dp. It costs 4 dp of card height and takes
 * nothing off the preview, which is the one thing on this card that must not
 * shrink — an inset paid at the sides would have cost 8 dp of artwork for 1.4 dp
 * of gap, because a disc that is `fillMaxWidth().aspectRatio(1f)` and anchored to
 * the top moves its own centre *up* towards the button as it narrows.
 */
internal val DESIGN_DISC_SIDE_INSET = 18.dp
internal val DESIGN_DISC_TOP_INSET = 18.dp

/**
 * How much of its slot the cell in [column] gives up to margins.
 *
 * The full margin on whichever side faces the window edge, half a gutter on any
 * side that faces another cell — so the visible gaps come out even (12 at the
 * edges, 8 between) while the *cells* do not: an outer cell pays 12 + 4 and an
 * inner one pays 4 + 4. That 8 dp difference is the whole of the bug below.
 */
internal fun designCellInsetWidth(column: Int, columns: Int): Dp =
    (if (column == 0) DESIGN_GRID_OUTER_MARGIN else DESIGN_GRID_GUTTER / 2) +
        (if (column == columns - 1) DESIGN_GRID_OUTER_MARGIN else DESIGN_GRID_GUTTER / 2)

/** The largest [designCellInsetWidth] any column in a [columns]-wide grid pays. */
internal fun designWidestCellInset(columns: Int): Dp =
    if (columns <= 1) DESIGN_GRID_OUTER_MARGIN * 2 else DESIGN_GRID_OUTER_MARGIN + DESIGN_GRID_GUTTER / 2

/**
 * The side inset the disc in [column] takes, so that **every card in the grid is
 * the same height**.
 *
 * ## What was actually wrong
 *
 * The reported symptom was that the middle column's cards are taller, and the first
 * guess — a name wrapping to two lines — was not it: every line of text on a card
 * is `maxLines = 1` and always has been. The cause is [designCellInsetWidth]. Every
 * slot in a `Fixed(n)` grid is the same width, but an outer cell hands 16 dp of it
 * back and an inner cell hands back 8, so an inner *card* is 8 dp wider. The disc
 * is `fillMaxWidth().aspectRatio(1f)`, which is the right way to size it — it grows
 * with the column count instead of being a number that is correct on one window —
 * but it also turns that 8 dp of extra width into 8 dp of extra **height**, and a
 * `LazyVerticalGrid` row is as tall as its tallest cell. Hence a ragged middle
 * column on every single row, on every device, forever.
 *
 * ## The correction
 *
 * The extra width is absorbed as padding rather than spent on the artwork: a column
 * that pays less to the grid pays the difference to its disc, half on each side. So
 * every disc is exactly `slot - designWidestCellInset - 2 * DESIGN_DISC_SIDE_INSET`
 * across, whichever column it is in, and therefore exactly as tall.
 *
 * That is the fixed-height answer without a fixed height: no dp constant to be
 * right on a 411 dp phone and wrong on a tablet, no `aspectRatio` on the card
 * itself (which would have to guess how much room the text needs and would fight
 * the font scale), and nothing to keep in sync with the column count. The
 * invariant it is worth testing is one line —
 * `designCellInsetWidth(c, n) + 2 * designDiscSideInset(c, n)` is the same for
 * every `c` — and that is precisely "every card is the same height".
 *
 * The text is the other half of the guarantee, and is handled where it lives: all
 * three lines of a card are single-line and ellipsised, so no name, no frame count
 * and no author can add a row.
 */
internal fun designDiscSideInset(column: Int, columns: Int): Dp =
    DESIGN_DISC_SIDE_INSET + (designWidestCellInset(columns) - designCellInsetWidth(column, columns)) / 2

// ---------- which frames a preview plays ----------

/** One step of a preview loop: which frame of the design, and how long it is held. */
internal data class PreviewStep(val frameIndex: Int, val holdMs: Int)

/**
 * The most frames a preview will ever cycle through.
 *
 * A design may carry up to `DesignCodec.MAX_FRAMES` (240). Playing all of them in
 * a 74 dp disc is not an animation, it is a strobe — and it would also mean 240
 * cached bitmaps per visible card. Eight is enough to read the *shape* of a
 * movement, which is all a card-sized preview is for.
 */
internal const val PREVIEW_MAX_STEPS = 8

/**
 * The shortest a preview ever holds a frame, whatever the design says.
 *
 * `DesignCodec.MIN_DURATION_MS` is 20 ms — 50 fps, which on the panel is a
 * legitimate thing to draw and in a thumbnail is a flicker. 90 ms is a little over
 * 11 fps: still clearly moving, never painful, and it also caps how often the grid
 * asks for a redraw.
 */
internal const val PREVIEW_MIN_HOLD_MS = 90

/**
 * And the longest, for a step that stands for **one** frame. A design is allowed a
 * 60-second frame (`DesignCodec.MAX_DURATION_MS`); a card that sits on one frame
 * for a minute is indistinguishable from a card that is broken. 1.5 s is long
 * enough to read as a deliberate pause.
 *
 * This is the ceiling on a number the *author* chose, which is why it is the
 * generous one. When a step stands for several frames the number is ours, and the
 * tighter [PREVIEW_MAX_SAMPLED_HOLD_MS] applies instead.
 */
internal const val PREVIEW_MAX_HOLD_MS = 1_500

/**
 * The longest a **sampled** step is held: one that stands in for a whole span of
 * frames nobody will see.
 *
 * A sampled step's hold is the span's running time (see [previewSteps]), and on a
 * long design that arithmetic gets big fast — 30 frames of 40 ms is 1.2 s, and
 * 30 frames of half a second is fifteen. Held literally, a 240-frame design becomes
 * eight stills seconds apart: a card that, glanced at, is simply not moving. The
 * ceiling is what stops the correction overshooting into that.
 *
 * 600 ms is the same beat `PREVIEW_REST_MS` uses for the editor's floating preview,
 * and for the same reason: it is about the longest a picture can sit before it
 * stops reading as a pause in something and starts reading as a stop. Under it,
 * every card on the grid is still visibly alive.
 */
internal const val PREVIEW_MAX_SAMPLED_HOLD_MS = 600

/**
 * Which frames a card's preview cycles, and for how long each.
 *
 * The rules, in order:
 *
 * - **No frames at all** — an empty variant — is an empty plan. The card draws a
 *   bare disc; see [DesignPreviewArt.Empty].
 * - **A static design shows its one frame**, whatever else is in the file. `kind`
 *   is the design's own answer to "does this move", and a static design with
 *   several frames (which the format allows, and which is what a dynamic design
 *   switched back to static looks like) plays the first one, exactly as
 *   `CustomScreen` does on the panel.
 * - **A dynamic design of [maxSteps] frames or fewer plays all of them**, in
 *   order.
 * - **A longer one is sampled evenly**: the frames are cut into [maxSteps] equal
 *   spans at `i * count / steps`, and each step shows the **first** frame of its
 *   span. That starts at frame 0 and spreads the rest across the whole animation.
 *   For the 240-frame maximum that is frames 0, 30, 60 … 210 — a flip-through of
 *   the movement rather than the first eight frames of it, which for a slow
 *   animation would look like nothing happening at all.
 *
 * ## How long a step is held, and why that changed
 *
 * **A step is held for the running time of the span it stands in for**, clamped
 * into [PREVIEW_MIN_HOLD_MS]..[PREVIEW_MAX_SAMPLED_HOLD_MS].
 *
 * It used to be held for its own frame's duration alone, on the argument that
 * summing the span would be faithful to the design's real running time and would
 * turn a sampled 240-frame animation into a slideshow of eight stills seconds
 * apart. The first half of that is right and the second half was over-corrected
 * for: a step that skips six frames but advances at one frame's pace runs the
 * whole animation about six times too fast, and a 46-frame design on the grid
 * genuinely does look like a fast-forward of itself. Speed is not a neutral
 * simplification — it is the one property of an animation a preview is *for*.
 *
 * So the span is what sets the pace, and [PREVIEW_MAX_SAMPLED_HOLD_MS] is what
 * stops it becoming the slideshow the old note feared. The two together mean a
 * preview plays at the design's own speed for as long as that is legible and
 * degrades to "as slow as a card is allowed to get" beyond it — a long design
 * slows down, and no single card ever freezes. That ceiling only binds on a
 * *sampled* step; a step that stands for exactly one frame is showing a hold its
 * author actually drew, and keeps the more generous [PREVIEW_MAX_HOLD_MS].
 *
 * Pure, and takes a list of durations rather than a [Design], so the whole
 * heuristic is reachable from a plain JVM test.
 */
internal fun previewSteps(
    durationsMs: List<Int>,
    dynamic: Boolean,
    maxSteps: Int = PREVIEW_MAX_STEPS,
): List<PreviewStep> {
    if (durationsMs.isEmpty()) return emptyList()
    if (!dynamic || durationsMs.size == 1 || maxSteps <= 1) {
        return listOf(PreviewStep(0, previewHold(durationsMs[0].toLong(), spanned = 1)))
    }
    val frames = durationsMs.size
    val count = min(frames, maxSteps)
    return List(count) { i ->
        // The half-open span this step stands for. The end is the next step's
        // start, so the spans tile the animation exactly and their durations sum
        // to its full running time.
        val start = (i.toLong() * frames / count).toInt().coerceIn(0, frames - 1)
        val end = ((i + 1).toLong() * frames / count).toInt().coerceIn(start + 1, frames)
        // Summed as a Long: 240 frames of the format's 60 s maximum is 14.4
        // million, which fits, but only just, and the clamp is happier in the
        // wider type than the addition would be.
        var span = 0L
        for (f in start until end) span += durationsMs[f]
        PreviewStep(start, previewHold(span, spanned = end - start))
    }
}

/**
 * One step's hold: the running time it stands for, floored so it cannot flicker
 * and capped by whichever ceiling applies — see [PREVIEW_MAX_SAMPLED_HOLD_MS].
 */
private fun previewHold(totalMs: Long, spanned: Int): Int {
    val ceiling = if (spanned <= 1) PREVIEW_MAX_HOLD_MS else PREVIEW_MAX_SAMPLED_HOLD_MS
    return totalMs.coerceIn(PREVIEW_MIN_HOLD_MS.toLong(), ceiling.toLong()).toInt()
}

// ---------- the art a preview draws ----------

/**
 * A design's preview, ready to blit: the panel geometry, the sampled frames'
 * *encoded* pixels in play order, and the schedule that walks them.
 *
 * Everything here is indexed by *step*, not by the design's frame number — the
 * sampling has already been applied — so a player only ever holds one index and
 * the draw lambda only ever does one lookup.
 *
 * ## Why the pixels are decoded lazily
 *
 * This used to decode every sampled frame in [designPreviewArt], i.e. inside the
 * card's `remember`, i.e. **at composition**. A card shows one step at a time, so
 * seven eighths of that work was for pictures nobody had asked for yet — and it
 * was paid at the single worst moment. `MainScreen` gives the pager a
 * `beyondViewportPageCount` of 1, so the Create page composes while the user is
 * still on the Toys tab and again while the swipe is in flight: arriving on the
 * tab composes a whole screenful of cells (about fifteen at three columns) in a
 * few frames, on top of the pager's own slide. Eight eager decodes per card is
 * 8 x 169 character lookups at bellsprout and 8 x 625 at arbok, fifteen times
 * over, inside that window.
 *
 * So a step is decoded when it is first **drawn** and kept from then on. The first
 * paint of a card decodes exactly one frame; the other seven are decoded, if ever,
 * as the shared clock reaches them — each alongside a rasterisation that was
 * already going to happen on that tick, and never while the card is off screen.
 * Nothing about the picture, the schedule or the timing changes: [frame] returns
 * precisely what the eager decode returned for the same step, including the blank
 * array a frame that will not decode falls back to.
 *
 * The memo array is written only from the draw lambda, which is the UI thread, and
 * only ever with the one value a pure function of the inputs can produce — so the
 * class is still [Immutable] in the sense Compose cares about: no caller can
 * observe it holding two different answers.
 */
@Immutable
internal class DesignPreviewArt(
    val size: Int,
    val steps: List<PreviewStep>,
    /** One `cells` string per step, in play order. */
    private val sources: List<String>,
    /** The palette those strings index into — the design's own `levels`. */
    private val levels: List<Int>,
    /** `size * size`, and so the length of the blank frame a bad one falls back to. */
    private val cellCount: Int,
) {
    private val decoded = arrayOfNulls<IntArray>(sources.size)

    /** How many sampled frames this preview cycles: one per step. */
    val frameCount: Int get() = sources.size

    /**
     * Step [index]'s pixels, decoded on first ask and kept.
     *
     * Null for an index this preview does not have — which is how
     * [DesignPreviewArt.Empty] renders a bare disc, and is exactly what the eager
     * `frames.getOrNull(index)` used to answer.
     *
     * A frame that will not decode (a file whose `cells` are the wrong length for
     * the geometry, which `DesignCodec` rejects on import but which nothing
     * re-checks on the way out of storage) becomes a blank frame rather than an
     * exception: one damaged frame must not take out the whole tab. The blank is
     * memoised too, so a broken frame is not re-attempted on every tick.
     */
    fun frame(index: Int): IntArray? {
        if (index < 0 || index >= sources.size) return null
        decoded[index]?.let { return it }
        val cells = DesignFrames.decode(sources[index], levels, size) ?: IntArray(cellCount)
        decoded[index] = cells
        return cells
    }

    /**
     * How many steps have actually been decoded so far.
     *
     * Exists for `DesignPreviewTest`, which has to be able to say "composing a card
     * decodes nothing" and "drawing one step decodes one frame" — the whole point
     * of this class, and a property that is otherwise invisible from outside.
     */
    val decodedCount: Int get() = decoded.count { it != null }

    companion object {
        /**
         * A design with nothing to show for either panel. The card renders the
         * black disc and no pixels, which is what an empty variant *is* — and is
         * the case the grid must not crash on, since a design created for the
         * other phone legitimately carries no frames for this one.
         */
        val Empty = DesignPreviewArt(0, emptyList(), emptyList(), emptyList(), 0)
    }
}

/**
 * Which panel's artwork a card previews: **this phone's if the design has any**,
 * otherwise whichever one it does have.
 *
 * The preference matters because the two geometries are independent drawings that
 * are never scaled between (see `seedVariants`), so a design drawn for both is
 * two different pictures and the one worth showing is the one this phone would
 * put on its own matrix. The fallback matters because a design created for the
 * *other* phone is a perfectly normal thing to have in the list — imported from a
 * friend, or drawn ahead of time — and showing a blank disc for it would look
 * like a bug.
 *
 * [home] is nullable rather than defaulted (unlike [homeCodename], which has to
 * answer for the editor) because "this panel is one the format does not know" is
 * a real state here and its honest answer is "then any variant will do".
 */
internal fun previewCodename(design: Design, home: PokemonCodename?): PokemonCodename? {
    if (home != null && design.variantFor(home)?.frames?.isNotEmpty() == true) return home
    return PokemonCodename.entries.firstOrNull { design.variantFor(it)?.frames?.isNotEmpty() == true }
}

/**
 * Picks the frames [previewSteps] chose and hands them to [DesignPreviewArt],
 * which decodes each one the first time it is drawn.
 *
 * Only the sampled frames are carried — at most [PREVIEW_MAX_STEPS] of them — so a
 * 240-frame arbok design will do at most eight 625-cell string walks over its whole
 * time on screen, not 240; and this function itself now does **none** of them. It
 * runs inside the card's `remember`, which is composition, which during a swipe
 * onto the tab is the single most contended moment there is. See
 * [DesignPreviewArt] for what that buys and why the picture is unchanged.
 *
 * Nothing is copied: a frame's `cells` is a `String` already sitting in the
 * decoded design, so a step costs one reference.
 */
internal fun designPreviewArt(design: Design, home: PokemonCodename?): DesignPreviewArt {
    val codename = previewCodename(design, home) ?: return DesignPreviewArt.Empty
    val frames = design.variantFor(codename)?.frames.orEmpty()
    if (frames.isEmpty()) return DesignPreviewArt.Empty
    val steps = previewSteps(frames.map { it.durationMs }, design.kind == DesignKind.DYNAMIC)
    return DesignPreviewArt(
        size = codename.size,
        steps = steps,
        sources = steps.map { frames[it.frameIndex].cells },
        levels = design.levels,
        cellCount = codename.cellCount,
    )
}

// ---------- the shared clock ----------

/**
 * One card's position in its own loop.
 *
 * [step] is the only thing that changes as it plays, it is an `IntState`, and the
 * one place it is read is inside a draw lambda — so a tick costs a draw
 * invalidation on one node and no recomposition anywhere.
 *
 * Catching up is deliberately capped at one step per frame. The clock stops while
 * the app is paused or the tab is off screen, which leaves [nextAt] arbitrarily
 * far in the past; advancing by however many holds have "elapsed" would replay
 * the animation at infinite speed for a moment on the way back. One step and a
 * fresh deadline is what a preview owes anybody: it resumes, it does not catch up.
 */
@Stable
internal class PreviewPlayer(private val steps: List<PreviewStep>) {

    var step by mutableIntStateOf(0)
        private set

    private var nextAt = Long.MIN_VALUE

    /** Whether this player has anything to do. A still design never ticks. */
    val animated: Boolean get() = steps.size > 1

    fun advance(nowMs: Long) {
        if (!animated) return
        if (nextAt == Long.MIN_VALUE) {
            nextAt = nowMs + steps[step].holdMs
            return
        }
        if (nowMs < nextAt) return
        val next = (step + 1) % steps.size
        step = next
        nextAt = nowMs + steps[next].holdMs
    }
}

/**
 * The grid's single clock: every visible card's player, walked once per frame.
 *
 * A plain [MutableList] rather than snapshot state, because nothing composes
 * against the membership — [advance] is called from a frame callback, not from a
 * composition — and a snapshot list would invalidate every reader each time a cell
 * scrolled into view.
 *
 * [animating] is the one exception and the reason the loop can suspend: it is
 * snapshot-backed so `CreateTab` can watch it with `snapshotFlow` and stop asking
 * for frames entirely on a page of static designs.
 */
@Stable
internal class PreviewClock {

    private val players = mutableListOf<PreviewPlayer>()

    /** Whether any registered player has more than one frame to show. */
    var animating by mutableStateOf(false)
        private set

    fun register(player: PreviewPlayer) {
        players += player
        if (player.animated) animating = true
    }

    fun unregister(player: PreviewPlayer) {
        players -= player
        if (player.animated) animating = players.any { it.animated }
    }

    /** Indexed rather than iterated: this runs on every frame the grid is alive. */
    fun advance(nowMs: Long) {
        for (i in players.indices) players[i].advance(nowMs)
    }
}

/**
 * Registers [art]'s player with the shared clock for as long as this card is
 * composed, and hands it back.
 *
 * The `DisposableEffect` is what makes "animate only what is visible" true without
 * anything having to compute visibility: `LazyVerticalGrid` disposes the cells it
 * scrolls past, and disposal is unregistration.
 */
@Composable
internal fun rememberPreviewPlayer(art: DesignPreviewArt, clock: PreviewClock): PreviewPlayer {
    val player = remember(art) { PreviewPlayer(art.steps) }
    DisposableEffect(player, clock) {
        clock.register(player)
        onDispose { clock.unregister(player) }
    }
    return player
}

// ---------- drawing one ----------

/**
 * The largest square a preview frame is ever rasterised into.
 *
 * The cache buys its speed with memory, and the bill is per *frame*. Three
 * columns on a 411 x 919 dp phone means about fifteen cells on screen at once, of
 * up to [PREVIEW_MAX_STEPS] frames each; at that phone's 3x density an 88 dp disc
 * is 264 px, which is 279 kB a frame and **33 MB** across a full screen of dynamic
 * designs. That is exactly the sort of pressure that produces the GC-driven
 * stutter this whole file exists to avoid.
 *
 * Capping the raster at 160 px puts it at 102 kB a frame and about 12 MB for the
 * same worst case, for the cost of a 1.65x bilinear upscale on a 3x screen. These
 * are soft white dots on black glass: the stretch is not visible on them, and a
 * dropped frame is.
 *
 * The alternative — one cache per card, re-rendered whenever the step changes —
 * costs almost nothing in memory and would put roughly 120 rasterisations a second
 * (fifteen cards, ~8 steps a second each) of up to 625 `drawRoundRect` calls back
 * on the main thread, which is the very cost `ThumbnailCache` was written for.
 */
private const val PREVIEW_RASTER_MAX_PX = 160

/**
 * ## Why the unlit wash is NOT rasterised once and shared
 *
 * The obvious next optimisation is not available, and it is worth writing down so
 * nobody spends an afternoon rediscovering that. Rasterising a frame is one
 * `drawRoundRect` per LED — 137 at bellsprout, 489 at arbok — and the unlit ones
 * are identical for every card of a given panel and raster size, so a cached
 * "wash" bitmap blitted once, with only the lit cells drawn on top, would turn 137
 * operations into 1 + (a few dozen).
 *
 * **It cannot be made pixel-identical, so it is not done.** `drawMatrix` paints
 * exactly one square per cell, at `alpha = value / 4095` if the cell is lit and at
 * the unlit wash's alpha otherwise. Painting a lit square *over* a wash square
 * composites the two: the result is `a·W + (1-a)·(u·W + (1-u)·D)` rather than
 * `a·W + (1-a)·D`, which differs by `(1-a)·u·(W-D)` — about five percent of the
 * way to white on a half-bright cell, on every dim pixel of every preview. Nor can
 * the wash be erased first: the squares are anti-aliased, so a covering rectangle
 * leaves a residue of `(1-c)·c·u` white at every partially-covered edge pixel, and
 * a "top-up" alpha that would be exact in the interior (`t = 1 - (1-a)/(1-u)`) is
 * both wrong at those edges and negative for any cell dimmer than the wash — which
 * the format allows, since level 1 of 4095 is far below `UNLIT_ALPHA`.
 *
 * And there is no way to *prove* the composite anyway: the unit tests are plain
 * JVM (junit only, no Robolectric), so nothing on the test path can rasterise a
 * `drawRoundRect` and compare bitmaps. A change to the pixels that cannot be
 * tested is not a change this app makes.
 *
 * The cost is instead bounded from the other side: each frame is rasterised at
 * most once ([ThumbnailCache], one per step), and a card arriving on screen
 * rasterises exactly the one step it is showing.
 */

/**
 * The disc, with whichever sampled frame the shared clock currently has this card
 * on.
 *
 * Everything that changes is read **inside** the `Canvas` lambda — the player's
 * step, and through it the pixels — so a tick invalidates this node's draw and
 * nothing above it. The cache lookup is keyed on the step, so each of the (at
 * most eight) frames is turned into an [androidx.compose.ui.graphics.ImageBitmap]
 * once and blitted from then on.
 *
 * [DesignPreviewArt.Empty] renders the bare disc: black glass with no pixels,
 * which is the honest picture of a design that has no artwork for any panel.
 */
@Composable
internal fun DesignPreviewDisc(
    art: DesignPreviewArt,
    player: PreviewPlayer,
    modifier: Modifier = Modifier,
) {
    // One cache per sampled frame — the single-slot cache the timeline uses would
    // re-render on every tick, which is precisely the cost this is here to avoid.
    // At least one, so the empty case still has somewhere to keep its bare disc.
    val caches = remember(art) { List(art.frameCount.coerceAtLeast(1)) { ThumbnailCache() } }
    DisposableEffect(caches) {
        onDispose { caches.forEach { it.release() } }
    }
    Canvas(modifier) {
        val side = min(size.width, size.height).roundToInt()
        if (side <= 0) return@Canvas
        val raster = min(side, PREVIEW_RASTER_MAX_PX)
        val index = player.step.coerceIn(0, caches.lastIndex)
        val image = caches[index].get(
            // Constant: a cache holds exactly one frame of one design, and the
            // art object is replaced wholesale when the design changes.
            revision = 0,
            width = raster,
            height = raster,
            density = this,
            layoutDirection = layoutDirection,
        ) {
            val radius = min(this.size.width, this.size.height) / 2f
            drawCircle(MATRIX_DISC_COLOR, radius = radius, center = center)
            // Asked for INSIDE the render lambda, so a step's pixels are decoded
            // only when a bitmap for them is actually being made — never on a
            // cache hit, and never at all for a step the clock has not reached.
            // See [DesignPreviewArt].
            val cells = art.frame(index)
            if (cells != null) drawMatrix(center, radius, art.size, cells)
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
