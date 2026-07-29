package space.linuxct.glyphmatrixtoycompat.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import space.linuxct.glyphmatrixtoycompat.R
import kotlin.math.hypot

/**
 * Essential Key tutorial pop-up: a Nothing-settings-style illustration of the
 * phone lying face down — camera island, Glyph Matrix and the Essential Key on
 * the right edge just below the island — animated entirely in Compose (no
 * image or animation assets). Each step loops a small timeline showing what a
 * single, double or triple press does, in both regular and menu mode. The
 * blink cadence and the 5 s auto-set countdown match the real implementation.
 */
@Composable
fun KeyTutorialDialog(onDismiss: () -> Unit) {
    MotionDialog(onDismiss) { dismiss ->
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface) {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 18.dp),
            ) {
                Text(stringResource(R.string.tut_title), style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(14.dp))
                KeyTutorialContent()
                Row(Modifier.fillMaxWidth()) {
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = dismiss) { Text(stringResource(R.string.tut_close)) }
                }
            }
        }
    }
}

/**
 * The scale a dialog grows from / shrinks back to, per MD3's dialog motion —
 * a magnitude, not a duration: the spring that travels it is the theme's.
 */
private const val DIALOG_ENTER_SCALE = 0.85f

/**
 * A hand-rolled [Dialog] that enters and leaves with MD3 motion instead of
 * popping into place.
 *
 * The platform dialog WINDOW cannot be animated — it is added to and removed
 * from the window manager, and its scrim fades on the system's own schedule —
 * so the motion lives entirely on the content inside it: a
 * [MutableTransitionState] that starts `false` and is flipped to `true` as it
 * is constructed, which makes the very first composition an enter transition.
 *
 * The exit is the same transition run backwards, and it is why [content]
 * receives its own `dismiss` rather than calling the caller's [onDismiss]: the
 * window must not be torn down until the content has finished scaling out, so
 * dismissal means "start the exit", and the real [onDismiss] fires when the
 * transition idles at `false`. Back gestures and outside taps go through the
 * same path.
 *
 * Both halves take their springs from the theme's expressive motion scheme, the
 * same as every other animation in the app: scale is a SIZE → the (under-damped,
 * so it lands with a small pop) spatial spring; alpha is an effect → the effects
 * spring, which never bounces. Nothing here is a tween or a literal duration.
 */
@Composable
private fun MotionDialog(onDismiss: () -> Unit, content: @Composable (dismiss: () -> Unit) -> Unit) {
    val visible = remember { MutableTransitionState(false).apply { targetState = true } }
    // Guarded by `targetState`: at the first composition currentState is false
    // but the transition is already running towards true, so isIdle is false and
    // this cannot dismiss the dialog on the frame it opens.
    LaunchedEffect(visible.isIdle, visible.currentState) {
        if (visible.isIdle && !visible.currentState) onDismiss()
    }
    val fade = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val scale = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    Dialog(onDismissRequest = { visible.targetState = false }) {
        AnimatedVisibility(
            visibleState = visible,
            // Outside the Surface, so it caps how tall these dialogs may grow
            // without padding the short ones. See [DIALOG_VERTICAL_MARGIN].
            modifier = Modifier.padding(vertical = DIALOG_VERTICAL_MARGIN),
            enter = fadeIn(fade) + scaleIn(scale, initialScale = DIALOG_ENTER_SCALE),
            exit = fadeOut(fade) + scaleOut(scale, targetScale = DIALOG_ENTER_SCALE),
            label = "dialogMotion",
        ) {
            content { visible.targetState = false }
        }
    }
}

/**
 * A short numbered-steps guide pop-up (styled like the tutorial dialog):
 * title, intro, numbered steps, optional note and optional action button.
 */
@Composable
fun TutorialInfoDialog(
    title: String,
    intro: String,
    steps: List<String>,
    note: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    MotionDialog(onDismiss) { dismiss ->
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface) {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 18.dp),
            ) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(12.dp))
                Text(
                    intro,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(14.dp))
                steps.forEachIndexed { i, step ->
                    Row {
                        Box(
                            Modifier
                                .size(26.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.inverseSurface),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "${i + 1}",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.inverseOnSurface,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(
                            step,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (i != steps.lastIndex) Spacer(Modifier.height(12.dp))
                }
                note?.let {
                    Spacer(Modifier.height(14.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (actionLabel != null && onAction != null) {
                        // Unchanged: the action leaves for system Settings and
                        // deliberately does NOT close the dialog behind it.
                        TextButton(onClick = onAction) { Text(actionLabel) }
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = dismiss) { Text(stringResource(R.string.tut_close)) }
                }
            }
        }
    }
}

/**
 * The tutorial itself — mode chips plus the swipeable animated steps, shown
 * inside [KeyTutorialDialog].
 */
@Composable
private fun KeyTutorialContent(modifier: Modifier = Modifier) {
    var menuMode by remember { mutableStateOf(false) }
    val steps = if (menuMode) MENU_STEPS else CLASSIC_STEPS

    Column(modifier) {
        // "Regular mode" / "Menu mode" is a pick-ONE-of-two, which is exactly
        // what MD3 specifies segmented buttons for (2–5 mutually exclusive
        // options). Hence [SingleChoiceSegmentedButtonRow] rather than a
        // ButtonGroup of ToggleButtons: the single-choice row wraps its items in
        // a `selectableGroup()` and each button reports `Role.RadioButton`,
        // while a ToggleButton is a `Role.Checkbox` with no notion of its peers —
        // right for "bold on/off", wrong for "one of these two".
        //
        // Everything that used to be hand-rolled here now comes from the
        // library: the check mark wipes in on the theme's effects/fast-spatial
        // springs and pushes the label aside as it grows, and the container and
        // outline take their colours from SegmentedButtonDefaults (so this
        // theme's monochrome scheme flows through untouched).
        // A segmented button already animates its own selection; see [NoRipple].
        NoRipple {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = !menuMode,
                    onClick = { menuMode = false },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                ) {
                    Text(stringResource(R.string.onb_mode_regular))
                }
                SegmentedButton(
                    selected = menuMode,
                    onClick = { menuMode = true },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                ) {
                    Text(stringResource(R.string.onb_mode_menu))
                }
            }
        }
        Spacer(Modifier.height(8.dp))

        // Swipe through the selected mode's steps; key() recreates the
        // pager on mode change so it starts back at the first step.
        key(menuMode) {
            val pagerState = rememberPagerState(pageCount = { steps.size })
            HorizontalPager(
                state = pagerState,
                // Settle on MD3's expressive spatial spring, not foundation's
                // hardcoded `spring(StiffnessMediumLow)` default — a released
                // swipe here has to land like every other movement in the app,
                // and the step dots below are driven off this pager.
                flingBehavior = PagerDefaults.flingBehavior(
                    state = pagerState,
                    snapAnimationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
                ),
            ) { page ->
                TutorialPage(steps[page])
            }
            Spacer(Modifier.height(6.dp))

            val base = MaterialTheme.colorScheme.onSurface
            // Same treatment as the onboarding page indicator, so the two read
            // as one component: the selected dot stretches into a pill (a SIZE
            // → spatial) while its fill fades (a COLOUR → effects). Fast on
            // both counts — a step dot is a small contained element. These used
            // to have no animation at all, which made swiping the tutorial feel
            // unrelated to swiping onboarding.
            val dotWidthSpec = MaterialTheme.motionScheme.fastSpatialSpec<Dp>()
            val dotColorSpec = MaterialTheme.motionScheme.fastEffectsSpec<Color>()
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                steps.forEachIndexed { i, _ ->
                    val selected = i == pagerState.currentPage
                    val dotWidth by animateDpAsState(
                        targetValue = if (selected) 18.dp else 7.dp,
                        animationSpec = dotWidthSpec,
                        label = "stepDotWidth",
                    )
                    val dotColor by animateColorAsState(
                        targetValue = if (selected) base else base.copy(alpha = 0.2f),
                        animationSpec = dotColorSpec,
                        label = "stepDotColor",
                    )
                    Box(
                        Modifier
                            .padding(horizontal = 3.dp)
                            .height(7.dp)
                            // The under-damped spring undershoots below the
                            // 7 dp resting width; a negative width is not a
                            // legal constraint.
                            .width(dotWidth.coerceAtLeast(0.dp))
                            .background(dotColor, CircleShape),
                    )
                }
            }
        }
    }
}

/** One swipeable step: its looping animation plus title and description. */
@Composable
private fun TutorialPage(step: TutorialStep) {
    // Millisecond timeline looping over the step's duration; starts fresh each
    // time the page enters the pager's composition. Read only inside the
    // Canvas draw block, so each frame is a redraw, not a recomposition.
    var timeMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(step) {
        val t0 = withFrameNanos { it }
        while (true) {
            withFrameNanos { now -> timeMs = ((now - t0) / 1_000_000) % step.durationMs }
        }
    }

    val base = MaterialTheme.colorScheme.onSurface
    Column {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(205.dp)
                .clipToBounds(),
        ) {
            drawTutorialPhone(base, step, timeMs)
        }
        Spacer(Modifier.height(10.dp))
        Text(stringResource(step.titleRes), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(step.bodyRes),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.heightIn(min = 88.dp),
        )
    }
}

// ---------- step timelines ----------

private class MatrixFrame(val pattern: List<String>, val on: Boolean)

private class TutorialStep(
    val titleRes: Int,
    val bodyRes: Int,
    val durationMs: Long,
    val presses: List<Long>,
    val countdown: LongRange? = null,
    val matrix: (Long) -> MatrixFrame,
)

/** Key held down for this long per press. */
private const val PRESS_MS = 170L

/** Same cadence as ScreenManager's menu blink (450 ms on / 300 ms off). */
private fun blinkOn(sinceMs: Long) = sinceMs % 750 < 450

private val CLASSIC_STEPS = listOf(
    TutorialStep(R.string.tut_c1_title, R.string.tut_c1_body, 4200, listOf(600)) { t ->
        when {
            t < 950 -> MatrixFrame(DICE_5, true)
            t < 1850 -> MatrixFrame(ROLL[((t - 950) / 180).toInt() % ROLL.size], true)
            else -> MatrixFrame(DICE_5, true)
        }
    },
    // Two double-presses: dice -> clock -> compass, then the loop restarts on
    // dice — three toys, so the carousel reads as a cycle, not a toggle.
    TutorialStep(R.string.tut_c2_title, R.string.tut_c2_body, 5200, listOf(600, 960, 2600, 2960)) { t ->
        MatrixFrame(
            when {
                t < 1400 -> DICE_5
                t < 3400 -> CLOCK
                else -> COMPASS
            },
            true,
        )
    },
    TutorialStep(R.string.tut_c3_title, R.string.tut_c3_body, 4400, listOf(600, 960, 1320)) { t ->
        MatrixFrame(if (t < 1800) COMPASS else AMBIENT, true)
    },
)

private val MENU_STEPS = listOf(
    TutorialStep(R.string.tut_m1_title, R.string.tut_m1_body, 5600, listOf(600, 960)) { t ->
        if (t < 1400) MatrixFrame(CLOCK, true) else MatrixFrame(CLOCK, blinkOn(t - 1400))
    },
    // Two single presses: clock -> dice -> compass, all still blinking, before
    // the loop circles back to the clock.
    TutorialStep(R.string.tut_m2_title, R.string.tut_m2_body, 7200, listOf(1800, 3800)) { t ->
        when {
            t < 2100 -> MatrixFrame(CLOCK, blinkOn(t))
            t < 4100 -> MatrixFrame(DICE_5, blinkOn(t - 2100))
            else -> MatrixFrame(COMPASS, blinkOn(t - 4100))
        }
    },
    TutorialStep(R.string.tut_m3_title, R.string.tut_m3_body, 5600, listOf(1800, 2160)) { t ->
        if (t < 2600) MatrixFrame(DICE_5, blinkOn(t)) else MatrixFrame(DICE_5, true)
    },
    TutorialStep(
        R.string.tut_m4_title, R.string.tut_m4_body, 7400, emptyList(),
        countdown = 800L..5800L,
    ) { t ->
        if (t < 5800) MatrixFrame(DICE_5, blinkOn(t)) else MatrixFrame(DICE_5, true)
    },
    TutorialStep(R.string.tut_m5_title, R.string.tut_m5_body, 5400, listOf(1400, 1760, 2120)) { t ->
        if (t < 2600) MatrixFrame(DICE_5, blinkOn(t)) else MatrixFrame(AMBIENT, true)
    },
)

// ---------- the illustration ----------

/**
 * Side of a square LED as a fraction of the cell pitch: 80 %, leaving a 20 %
 * gap between neighbours, so the grid reads as a dot-matrix panel.
 */
private const val PIXEL_FRACTION = 0.80f

/** 13x13 grid extent as a fraction of the Glyph Matrix disc diameter. */
private const val GRID_EXTENT = 0.84f

private fun DrawScope.drawTutorialPhone(base: Color, step: TutorialStep, t: Long) {
    val body = base.copy(alpha = 0.26f)
    val island = base.copy(alpha = 0.13f)
    val lens = base.copy(alpha = 0.34f)
    val lensInner = base.copy(alpha = 0.5f)

    // Phone back, face down, cropped at the bottom like the system settings
    // illustrations. Camera island at the top, Glyph Matrix on its right.
    val bodyW = minOf(size.height * 0.98f, size.width * 0.62f)
    val bodyLeft = (size.width - bodyW) / 2f
    val bodyTop = size.height * 0.05f
    drawRoundRect(
        body,
        topLeft = Offset(bodyLeft, bodyTop),
        // Extend well past the canvas bottom (clipToBounds crops it) so the
        // bottom rounded corners never show: the crop reads as a zoomed-in
        // view of a taller device, with straight sides running off-frame.
        size = Size(bodyW, size.height * 1.5f),
        cornerRadius = CornerRadius(bodyW * 0.17f),
    )

    val m = bodyW * 0.055f
    val iL = bodyLeft + m
    val iT = bodyTop + m
    val iW = bodyW - 2 * m
    val iH = bodyW * 0.58f
    drawRoundRect(
        island,
        topLeft = Offset(iL, iT),
        size = Size(iW, iH),
        cornerRadius = CornerRadius(bodyW * 0.13f),
    )

    // Lenses: one large ringed circle, a lens pill below it, two small dots.
    val bigC = Offset(iL + iW * 0.24f, iT + iH * 0.32f)
    drawCircle(lens, radius = iH * 0.20f, center = bigC)
    drawCircle(lensInner, radius = iH * 0.115f, center = bigC)
    drawRoundRect(
        lens,
        topLeft = Offset(iL + iW * 0.10f, iT + iH * 0.60f),
        size = Size(iW * 0.36f, iH * 0.30f),
        cornerRadius = CornerRadius(iH * 0.15f),
    )
    drawCircle(lens, radius = iH * 0.045f, center = Offset(iL + iW * 0.47f, iT + iH * 0.16f))
    drawCircle(lens, radius = iH * 0.045f, center = Offset(iL + iW * 0.47f, iT + iH * 0.34f))

    // Glyph Matrix: black circle with a live 13x13 dot grid.
    val mc = Offset(iL + iW * 0.74f, iT + iH * 0.45f)
    val mr = iH * 0.335f
    drawCircle(Color(0xFF0E0E0E), radius = mr, center = mc)
    val frame = step.matrix(t)
    // Grid extent as a fraction of the disc diameter. Cell centres sit at
    // g0 + i * cell, so the outermost row/column is 6 cells (0.775 * mr) out;
    // with a half-pixel of 0.4 * cell its far corner reaches 0.85 * mr, which
    // keeps every lit LED clear of the rim. At the old 0.94 the corner pips
    // landed at 1.01 * mr and visibly spilled out of the black disc.
    val cell = mr * 2f * GRID_EXTENT / 13f
    val g0x = mc.x - cell * 6f
    val g0y = mc.y - cell * 6f
    // One square pixel size for lit and unlit alike — a hardware LED covers
    // the same area either way, only its brightness changes.
    val px = cell * PIXEL_FRACTION
    val pxSize = Size(px, px)
    val pxCorner = CornerRadius(px * 0.16f)
    for (r in 0 until 13) {
        val rowPattern = frame.pattern[r]
        for (c in 0 until 13) {
            val center = Offset(g0x + c * cell, g0y + r * cell)
            // Circular mask in pixel space. 0.90 culls the four diagonal
            // corner cells (6.71 cells is the furthest kept, 0.94 * mr once
            // the square's half-diagonal is added) so no pixel touches the rim.
            if (hypot(center.x - mc.x, center.y - mc.y) > mr * 0.90f) continue
            // Per-cell brightness so shaded patterns (the compass) render
            // like the real dimmed LEDs; '#' stays the full-white used
            // elsewhere.
            val level = when (rowPattern[c]) {
                '#' -> 1f
                '+' -> 0.55f
                ':' -> 0.25f
                else -> 0f
            }
            val topLeft = Offset(center.x - px / 2f, center.y - px / 2f)
            val alpha = if (frame.on && level > 0f) level else 0.10f
            drawRoundRect(Color.White.copy(alpha = alpha), topLeft, pxSize, pxCorner)
        }
    }

    // Auto-set countdown: a depleting ring around the matrix.
    step.countdown?.let { range ->
        if (t >= range.first) {
            val span = (range.last - range.first).toFloat()
            val fraction = 1f - ((t - range.first) / span).coerceIn(0f, 1f)
            if (fraction > 0f) {
                val pad = 7.dp.toPx()
                drawArc(
                    base.copy(alpha = 0.65f),
                    startAngle = -90f,
                    sweepAngle = 360f * fraction,
                    useCenter = false,
                    topLeft = Offset(mc.x - mr - pad, mc.y - mr - pad),
                    size = Size((mr + pad) * 2f, (mr + pad) * 2f),
                    style = Stroke(width = 3.dp.toPx()),
                )
            }
        }
    }

    // Essential Key: a nub on the right edge, just below the island's
    // bottom-right corner (where it sits on the real phone, face down).
    val keyW = bodyW * 0.038f
    val keyH = bodyW * 0.15f
    val keyTop = iT + iH + iH * 0.08f
    val pressed = step.presses.any { t in it..(it + PRESS_MS) }
    val keyLeft = bodyLeft + bodyW - keyW * 0.45f - (if (pressed) 3.dp.toPx() else 0f)
    drawRoundRect(
        if (pressed) base.copy(alpha = 0.85f) else base.copy(alpha = 0.45f),
        topLeft = Offset(keyLeft, keyTop),
        size = Size(keyW, keyH),
        cornerRadius = CornerRadius(keyW / 2f),
    )
    val keyCenter = Offset(bodyLeft + bodyW + 2.dp.toPx(), keyTop + keyH / 2f)

    // Expanding ripple per press.
    step.presses.forEach { p ->
        val dt = t - p
        if (dt in 0..480) {
            val progress = dt / 480f
            drawCircle(
                base.copy(alpha = (1f - progress) * 0.5f),
                radius = 10.dp.toPx() + 26.dp.toPx() * progress,
                center = keyCenter,
                style = Stroke(width = 2.dp.toPx()),
            )
        }
    }

    // Press counter: one small dot per press of the current gesture, lit as
    // each press lands. Presses > 600 ms apart are separate gestures (bursts);
    // the dots reset for each burst, so repeated gestures read as "x2, twice"
    // rather than one long chain.
    val bursts = mutableListOf<MutableList<Long>>()
    step.presses.forEach { p ->
        if (bursts.isEmpty() || p - bursts.last().last() > 600) {
            bursts += mutableListOf(p)
        } else {
            bursts.last() += p
        }
    }
    val burst = bursts.lastOrNull { t >= it.first() - 400 } ?: bursts.firstOrNull()
    val markerX = bodyLeft + bodyW + 18.dp.toPx()
    burst?.forEachIndexed { i, p ->
        val lit = t >= p + 90 && t <= step.durationMs - 250
        drawCircle(
            if (lit) base.copy(alpha = 0.85f) else base.copy(alpha = 0.18f),
            radius = 3.dp.toPx(),
            center = Offset(
                markerX,
                keyCenter.y + (i - (burst.size - 1) / 2f) * 12.dp.toPx(),
            ),
        )
    }
}

// ---------- 13x13 matrix patterns ----------
//
// [CLOCK], [AMBIENT] and [COMPASS] are transcribed from ASCII goldens of the
// real renderer's 13x13 output (app/src/test/resources/goldens/), so the
// tutorial shows what the hardware actually shows. Golden charset
// '#'/'+'/'.'/' ' maps to this file's '#'/'+'/':'/'.' (off).
//
// The DICE_* faces are the deliberate exception: hand-authored rather than
// golden-derived. On the real 13x13 matrix a D6 face is drawn a half cell
// up-and-left of centre, which is barely visible on the hardware but obvious
// in this much larger illustration. The faces below re-centre it, trading
// fidelity for legibility. See [DICE_2] for the placement and why it is what
// it is.
//
// Each row MUST be exactly 13 characters: the draw loop indexes
// pattern[r][c] for r,c in 0..12 with no bounds guard.

/**
 * Dice toy showing a 2.
 *
 * All four faces place their 2x2 pips on the row/column pairs {2,3} (low),
 * {5,6} (middle) and {9,10} (high), so every face's lit bounding box is 2..10
 * on both axes — margins 2|2 — and reads as centred on the disc.
 *
 * Not 3-wide pips at 1..3 / 5..7 / 9..11, which would be both symmetric and
 * evenly spaced: with `GRID_EXTENT` 0.84 the cell pitch is 0.1292 * mr and the
 * 0.90 * mr cull keeps cells only out to 6.96 cells, while cell (1,1) sits at
 * sqrt(50) = 7.07 cells, so the outer pips would render visibly notched. The
 * 2x2 placement's furthest cell (2,2) is at sqrt(32) = 5.66 cells = 0.731 * mr,
 * comfortably inside.
 */
private val DICE_2 = listOf(
    ".............",
    ".............",
    "..##.........",
    "..##.........",
    ".............",
    ".............",
    ".............",
    ".............",
    ".............",
    ".........##..",
    ".........##..",
    ".............",
    ".............",
)

/** Dice toy showing a 3; pip placement per [DICE_2]. */
private val DICE_3 = listOf(
    ".............",
    ".............",
    "..##.........",
    "..##.........",
    ".............",
    ".....##......",
    ".....##......",
    ".............",
    ".............",
    ".........##..",
    ".........##..",
    ".............",
    ".............",
)

/** Dice toy showing a 5; pip placement per [DICE_2]. */
private val DICE_5 = listOf(
    ".............",
    ".............",
    "..##.....##..",
    "..##.....##..",
    ".............",
    ".....##......",
    ".....##......",
    ".............",
    ".............",
    "..##.....##..",
    "..##.....##..",
    ".............",
    ".............",
)

/** Dice toy showing a 6; pip placement per [DICE_2]. */
private val DICE_6 = listOf(
    ".............",
    ".............",
    "..##.....##..",
    "..##.....##..",
    ".............",
    "..##.....##..",
    "..##.....##..",
    ".............",
    ".............",
    "..##.....##..",
    "..##.....##..",
    ".............",
    ".............",
)

private val ROLL = listOf(DICE_3, DICE_6, DICE_2, DICE_6, DICE_3)

/**
 * The Compass toy pointing north, taken from the real renderer's 13x13
 * output (the compass_13_north golden): '#' needle, ':' tail, cardinal ring
 * with '+' W/E/S markers and ':' intercardinal dots.
 */
private val COMPASS = listOf(
    "......#......",
    "......#......",
    "..:...#...:..",
    "......#......",
    "......#......",
    "......#......",
    "+.....+.....+",
    "......:......",
    "......:......",
    "......:......",
    "..:.......:..",
    ".............",
    "......+......",
)

/**
 * The Pixel Clock toy on its plain-digits theme reading 12:34, stacked "12"
 * over "34" — the clock_13_1234_t0 golden.
 */
private val CLOCK = listOf(
    ".............",
    "....#..###...",
    "...##....#...",
    "....#..###...",
    "....#..#.....",
    "...###.###...",
    ".............",
    "...###.#.#...",
    ".....#.#.#...",
    "....##.###...",
    ".....#...#...",
    "...###...#...",
    ".............",
)

/**
 * The Ambient toy — the *analog* clock background at 10:08 (hour and minute
 * hands), from the ambient_13_bg_analog_1008 golden. Deliberately not the
 * default digital background: digits here would look almost identical to
 * [CLOCK] and the "cycle between toys" animations would read as no change at
 * all.
 */
private val AMBIENT = listOf(
    ".............",
    ".............",
    "..........+..",
    "..#......+...",
    "...##...+....",
    ".....#.+.....",
    "......#......",
    ".............",
    ".............",
    ".............",
    ".............",
    ".............",
    ".............",
)
