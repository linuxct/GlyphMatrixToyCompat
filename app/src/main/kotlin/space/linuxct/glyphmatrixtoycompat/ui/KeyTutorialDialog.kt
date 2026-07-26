package space.linuxct.glyphmatrixtoycompat.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import space.linuxct.glyphmatrixtoycompat.R
import kotlin.math.hypot

/**
 * "How it works" tutorial pop-up: a Nothing-settings-style illustration of the
 * phone lying face down — camera island, Glyph Matrix and the Essential Key on
 * the right edge just below the island — animated entirely in Compose (no
 * image or animation assets). Each step loops a small timeline showing what a
 * single, double or triple press does, in both regular and menu mode. The
 * blink cadence and the 5 s auto-set countdown match the real implementation.
 */
@Composable
fun KeyTutorialDialog(onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
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
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.tut_close)) }
                }
            }
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
    Dialog(onDismissRequest = onDismiss) {
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
                        TextButton(onClick = onAction) { Text(actionLabel) }
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.tut_close)) }
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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ModeChip(stringResource(R.string.onb_mode_regular), !menuMode, Modifier.weight(1f)) {
                menuMode = false
            }
            ModeChip(stringResource(R.string.onb_mode_menu), menuMode, Modifier.weight(1f)) {
                menuMode = true
            }
        }
        Spacer(Modifier.height(8.dp))

        // Swipe through the selected mode's steps; key() recreates the
        // pager on mode change so it starts back at the first step.
        key(menuMode) {
            val pagerState = rememberPagerState(pageCount = { steps.size })
            HorizontalPager(state = pagerState) { page ->
                TutorialPage(steps[page])
            }
            Spacer(Modifier.height(6.dp))

            val base = MaterialTheme.colorScheme.onSurface
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                steps.forEachIndexed { i, _ ->
                    Box(
                        Modifier
                            .padding(horizontal = 3.dp)
                            .size(7.dp)
                            .background(
                                if (i == pagerState.currentPage) base else base.copy(alpha = 0.2f),
                                CircleShape,
                            ),
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

@Composable
private fun ModeChip(text: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier
            .clip(shape)
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            )
            .then(
                if (selected) {
                    Modifier.border(BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary), shape)
                } else {
                    Modifier
                },
            )
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.titleSmall)
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
    // Two double-presses: dice -> clock -> eyes, then the loop restarts on
    // dice — three toys, so the carousel reads as a cycle, not a toggle.
    TutorialStep(R.string.tut_c2_title, R.string.tut_c2_body, 5200, listOf(600, 960, 2600, 2960)) { t ->
        MatrixFrame(
            when {
                t < 1400 -> DICE_5
                t < 3400 -> CLOCK
                else -> EYES
            },
            true,
        )
    },
    TutorialStep(R.string.tut_c3_title, R.string.tut_c3_body, 4400, listOf(600, 960, 1320)) { t ->
        MatrixFrame(if (t < 1800) EYES else AMBIENT, true)
    },
)

private val MENU_STEPS = listOf(
    TutorialStep(R.string.tut_m1_title, R.string.tut_m1_body, 5600, listOf(600, 960)) { t ->
        if (t < 1400) MatrixFrame(CLOCK, true) else MatrixFrame(CLOCK, blinkOn(t - 1400))
    },
    // Two single presses: clock -> dice -> eyes, all still blinking, before
    // the loop circles back to the clock.
    TutorialStep(R.string.tut_m2_title, R.string.tut_m2_body, 7200, listOf(1800, 3800)) { t ->
        when {
            t < 2100 -> MatrixFrame(CLOCK, blinkOn(t))
            t < 4100 -> MatrixFrame(DICE_5, blinkOn(t - 2100))
            else -> MatrixFrame(EYES, blinkOn(t - 4100))
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
    val cell = mr * 2f * 0.94f / 13f
    val g0x = mc.x - cell * 6f
    val g0y = mc.y - cell * 6f
    for (r in 0 until 13) {
        val rowPattern = frame.pattern[r]
        for (c in 0 until 13) {
            val center = Offset(g0x + c * cell, g0y + r * cell)
            if (hypot(center.x - mc.x, center.y - mc.y) > mr * 0.93f) continue
            val lit = frame.on && rowPattern[c] == '#'
            if (lit) {
                drawCircle(Color.White, radius = cell * 0.36f, center = center)
            } else {
                drawCircle(Color.White.copy(alpha = 0.10f), radius = cell * 0.15f, center = center)
            }
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

private val DICE_6 = listOf(
    ".............",
    "..##.....##..",
    "..##.....##..",
    ".............",
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

/** A pair of eyes with pupils, like the Eyes toy. */
private val EYES = listOf(
    ".............",
    ".............",
    ".............",
    ".............",
    "..###...###..",
    ".#...#.#...#.",
    ".#.#.#.#.#.#.",
    ".#...#.#...#.",
    "..###...###..",
    ".............",
    ".............",
    ".............",
    ".............",
)

/** Stacked "12" / "34" like the pixel clock toy. */
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
    "...###.###...",
    ".....#...#...",
    "...###...#...",
    ".............",
)

/** Analog-clock ring, the Ambient background's default look. */
private val AMBIENT = listOf(
    "....#####....",
    "..##.....##..",
    ".#.........#.",
    ".#.........#.",
    "#.....#.....#",
    "#.....#.....#",
    "#.....###...#",
    "#...........#",
    "#...........#",
    ".#.........#.",
    ".#.........#.",
    "..##.....##..",
    "....#####....",
)
