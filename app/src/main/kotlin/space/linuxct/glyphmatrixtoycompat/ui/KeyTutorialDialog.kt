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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
    var menuMode by remember { mutableStateOf(false) }
    var stepIndex by remember { mutableIntStateOf(0) }
    val steps = if (menuMode) MENU_STEPS else CLASSIC_STEPS
    val step = steps[stepIndex.coerceIn(0, steps.lastIndex)]

    // Millisecond timeline looping over the step's duration; restarts whenever
    // the shown step changes. Read only inside the Canvas draw block, so each
    // frame is a redraw, not a recomposition.
    var timeMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(step) {
        val t0 = withFrameNanos { it }
        while (true) {
            withFrameNanos { now -> timeMs = ((now - t0) / 1_000_000) % step.durationMs }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface) {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 18.dp),
            ) {
                Text(stringResource(R.string.tut_title), style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(14.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModeChip(stringResource(R.string.onb_mode_regular), !menuMode, Modifier.weight(1f)) {
                        menuMode = false
                        stepIndex = 0
                    }
                    ModeChip(stringResource(R.string.onb_mode_menu), menuMode, Modifier.weight(1f)) {
                        menuMode = true
                        stepIndex = 0
                    }
                }
                Spacer(Modifier.height(8.dp))

                val base = MaterialTheme.colorScheme.onSurface
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .height(205.dp)
                        .clipToBounds(),
                ) {
                    drawTutorialPhone(base, step, timeMs)
                }
                Spacer(Modifier.height(6.dp))

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
                                    if (i == stepIndex) base else base.copy(alpha = 0.2f),
                                    CircleShape,
                                ),
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))

                Text(stringResource(step.titleRes), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(step.bodyRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.heightIn(min = 72.dp),
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.tut_close)) }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = {
                        stepIndex = (stepIndex - 1 + steps.size) % steps.size
                    }) { Text(stringResource(R.string.onb_back)) }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        stepIndex = (stepIndex + 1) % steps.size
                    }) { Text(stringResource(R.string.onb_next)) }
                }
            }
        }
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
    TutorialStep(R.string.tut_c2_title, R.string.tut_c2_body, 4000, listOf(600, 960)) { t ->
        MatrixFrame(if (t < 1400) DICE_5 else CLOCK, true)
    },
    TutorialStep(R.string.tut_c3_title, R.string.tut_c3_body, 4400, listOf(600, 960, 1320)) { t ->
        MatrixFrame(if (t < 1800) CLOCK else AMBIENT, true)
    },
)

private val MENU_STEPS = listOf(
    TutorialStep(R.string.tut_m1_title, R.string.tut_m1_body, 5600, listOf(600, 960)) { t ->
        if (t < 1400) MatrixFrame(CLOCK, true) else MatrixFrame(CLOCK, blinkOn(t - 1400))
    },
    TutorialStep(R.string.tut_m2_title, R.string.tut_m2_body, 5600, listOf(1800)) { t ->
        if (t < 2100) MatrixFrame(CLOCK, blinkOn(t)) else MatrixFrame(DICE_5, blinkOn(t - 2100))
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
        size = Size(bodyW, size.height),
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

    // Press counter: one small dot per press, lit as each press lands.
    val markerX = bodyLeft + bodyW + 18.dp.toPx()
    step.presses.forEachIndexed { i, p ->
        val lit = t >= p + 90 && t <= step.durationMs - 250
        drawCircle(
            if (lit) base.copy(alpha = 0.85f) else base.copy(alpha = 0.18f),
            radius = 3.dp.toPx(),
            center = Offset(
                markerX,
                keyCenter.y + (i - (step.presses.size - 1) / 2f) * 12.dp.toPx(),
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
