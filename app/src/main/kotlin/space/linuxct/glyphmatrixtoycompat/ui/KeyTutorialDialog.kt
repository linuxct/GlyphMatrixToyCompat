package space.linuxct.glyphmatrixtoycompat.ui

import android.annotation.SuppressLint
import android.content.Context
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import space.linuxct.glyphmatrixtoycompat.R
import space.linuxct.glyphmatrixtoycompat.ui.design.Camera
import space.linuxct.glyphmatrixtoycompat.ui.design.DeviceBack
import space.linuxct.glyphmatrixtoycompat.ui.design.drawDeviceBack
import space.linuxct.glyphmatrixtoycompat.ui.design.drawMatrix

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
internal fun MotionDialog(
    onDismiss: () -> Unit,
    /**
     * Lets the content take the whole window instead of a dialog-sized card.
     *
     * Exactly three things change, and all of them are about the *window* rather
     * than the motion:
     *
     * - `usePlatformDefaultWidth` is turned off, so the window is measured against
     *   the display rather than against `config_prefDialogWidth` (see
     *   [dialogCardWidth] for what that cap normally does for us);
     * - `decorFitsSystemWindows` is turned off with it — see below;
     * - the vertical margin that keeps a card off the status bar is dropped,
     *   because a full-screen surface handles its own insets.
     *
     * ## Why `decorFitsSystemWindows` has to go with it
     *
     * **A dialog is its own window, and it does not inherit the activity's
     * edge-to-edge.** Every Activity in this app calls `enableEdgeToEdge()`; a
     * `Dialog` opened from one still gets a window whose decor fits system windows,
     * which means the *window* is resized or panned when the IME appears and the
     * insets are consumed before the content ever sees them. Full-screen content
     * that then applies `safeDrawingPadding()` — which includes the IME — pays for
     * the keyboard twice: the window shrinks by the keyboard's height AND the
     * content pads by it again, leaving the composer squashed against the top of
     * the screen with an empty list under it. That is exactly the jump the
     * assistant's chat showed the moment its input was tapped, and why it appeared
     * to fix itself on the first keystroke: the next recomposition re-measured
     * against insets that had by then settled.
     *
     * Turning it off makes the content the only thing accounting for the keyboard.
     * The library does the whole job from this one flag — `setDecorFitsSystemWindows(false)`,
     * a non-floating window theme, `fitInsetsTypes = 0`, and a soft-input mode of
     * `ADJUST_NOTHING` (S and above; `ADJUST_RESIZE` below it) — applied while the
     * window is being built rather than poked at afterwards through
     * `DialogWindowProvider`. Compose's own KDoc recommends the pair, in as many
     * words: use `decorFitsSystemWindows = false` when `usePlatformDefaultWidth` is
     * false, "to support using the entire screen and avoiding UI glitches on some
     * devices when the IME animates in".
     *
     * Both stay ON for the card dialogs, which are floating windows sized by the
     * platform and correct as they are.
     *
     * A parameter rather than a second implementation so that every pop-up in
     * this app still enters and leaves on the same springs. The one full-screen
     * caller is the assistant's chat, which is a conversation and not a card.
     */
    fullScreen: Boolean = false,
    content: @Composable (dismiss: () -> Unit) -> Unit,
) {
    val visible = remember { MutableTransitionState(false).apply { targetState = true } }
    // Guarded by `targetState`: at the first composition currentState is false
    // but the transition is already running towards true, so isIdle is false and
    // this cannot dismiss the dialog on the frame it opens.
    LaunchedEffect(visible.isIdle, visible.currentState) {
        if (visible.isIdle && !visible.currentState) onDismiss()
    }
    val fade = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val scale = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    Dialog(
        onDismissRequest = { visible.targetState = false },
        properties = DialogProperties(
            usePlatformDefaultWidth = !fullScreen,
            decorFitsSystemWindows = !fullScreen,
        ),
    ) {
        AnimatedVisibility(
            visibleState = visible,
            // Outside the Surface, so it caps how tall these dialogs may grow
            // without padding the short ones. See [DIALOG_VERTICAL_MARGIN].
            modifier = if (fullScreen) Modifier else Modifier.padding(vertical = DIALOG_VERTICAL_MARGIN),
            enter = fadeIn(fade) + scaleIn(scale, initialScale = DIALOG_ENTER_SCALE),
            exit = fadeOut(fade) + scaleOut(scale, targetScale = DIALOG_ENTER_SCALE),
            label = "dialogMotion",
        ) {
            content { visible.targetState = false }
        }
    }
}

/**
 * How wide a dialog card is — **in both of the places this app draws one**.
 *
 * ## The drift this exists to stop
 *
 * Design settings is shown two ways. In the app it is a [MotionDialog], i.e. a
 * platform `Dialog` window; in the guided tour it is the same card composed
 * *in place*, because a real dialog is its own window and would sit above the
 * tour's spotlight (see `DesignSettingsCard`). Those two contexts measure a
 * wrap-content card completely differently:
 *
 * - **In a window.** `usePlatformDefaultWidth` leaves the dialog window
 *   `WRAP_CONTENT`, and `ViewRootImpl.measureHierarchy` measures a wrap-content
 *   window at `AT_MOST(config_prefDialogWidth)` before it will consider the full
 *   display width — 320 dp on a phone, 580 dp at sw600dp. That cap is why every
 *   dialog in this app, ours and material3's `AlertDialog` alike, comes out the
 *   same width.
 * - **In the tour.** There is no window and no cap: the card is measured against
 *   the screen, and a `Text` takes every dp it is offered. On the 411 dp window
 *   this app runs on, the tour's copy measured **363 dp** (411 minus the sheet's
 *   2 x 24 dp) against the real dialog's **320** — visibly wider, which is
 *   exactly what was reported.
 *
 * So the card asks for a width instead of accepting one, and both contexts ask
 * *here*. Nothing is copied into the tour: [dialogCardWidth] is applied inside
 * the shared card composable itself, so a caller cannot forget it and the two
 * cannot disagree.
 *
 * ## What it resolves to
 *
 * The platform's own preferred dialog width, clamped into material3's
 * [DIALOG_MIN_WIDTH]..[DIALOG_MAX_WIDTH] and never wider than the window can
 * hold. Taking it from the platform rather than writing 320 dp down keeps the
 * windowed case a no-op — the card asks for precisely the width the window was
 * going to give it — on tablets and foldables as well as on this phone.
 *
 * `config_prefDialogWidth` is a framework resource with no public id, hence the
 * lookup by name and the fallback: if it ever disappears, [FALLBACK_DIALOG_WIDTH]
 * is what it has been on every phone-sized device since it was introduced, and
 * the two contexts still agree with each other, which is the property that
 * matters.
 */
@Composable
internal fun dialogCardWidth(): Dp {
    val context = LocalContext.current
    // The WINDOW, and deliberately the same window in both contexts: Compose
    // derives this from the ACTIVITY (`calculateWindowSize` unwraps to it), so a
    // card composed inside a dialog window reads the task's width here rather
    // than the dialog's own — which is what stops this from being circular.
    val available = LocalWindowInfo.current.containerDpSize.width
    val preferred = remember(context) { platformDialogWidth(context) }
    return dialogCardWidth(preferred, available)
}

/**
 * The clamp itself, pure so it can be tested: the platform's [preferred] width,
 * held inside MD3's own bounds and inside the window.
 *
 * The order matters at both ends. The MD3 clamp comes first because it is about
 * the dialog (a 700 dp one is a slab, a 200 dp one is a column of hyphenated
 * words); the window clamp comes last because it is about physics — on a window
 * narrower than [DIALOG_MIN_WIDTH] the minimum has to give way, and a card wider
 * than the window it is centred in would be cut off at both edges.
 *
 * An [available] width that is [Dp.Unspecified] or zero means the window has not
 * measured itself yet, which is a state exactly one composition long. It is
 * answered with the unclamped width rather than with a guess, because that is
 * the value the following frame will settle on anyway.
 */
internal fun dialogCardWidth(preferred: Dp, available: Dp): Dp {
    val bounded = preferred.coerceIn(DIALOG_MIN_WIDTH, DIALOG_MAX_WIDTH)
    if (!available.isSpecified || available <= 0.dp) return bounded
    return bounded.coerceAtMost((available - DIALOG_HORIZONTAL_MARGIN * 2).coerceAtLeast(0.dp))
}

/** MD3's own dialog width bounds — the pair `AlertDialog` applies internally. */
internal val DIALOG_MIN_WIDTH = 280.dp
internal val DIALOG_MAX_WIDTH = 560.dp

/**
 * The least breathing room a dialog keeps at each side of the window, matching
 * [DIALOG_VERTICAL_MARGIN]'s job on the other axis. It only ever binds on a
 * window too narrow for the platform's preferred width.
 */
private val DIALOG_HORIZONTAL_MARGIN = 24.dp

/** What [platformDialogWidth] falls back to: the AOSP value for a phone. */
private val FALLBACK_DIALOG_WIDTH = 320.dp

/**
 * `config_prefDialogWidth`, the width the window manager measures a wrap-content
 * dialog window at. Not public API — read by name, and defaulted if absent.
 */
@SuppressLint("DiscouragedApi")
private fun platformDialogWidth(context: Context): Dp {
    val resources = context.resources
    val id = resources.getIdentifier("config_prefDialogWidth", "dimen", "android")
    if (id == 0) return FALLBACK_DIALOG_WIDTH
    val px = runCatching { resources.getDimension(id) }.getOrNull() ?: return FALLBACK_DIALOG_WIDTH
    if (px <= 0f) return FALLBACK_DIALOG_WIDTH
    return (px / resources.displayMetrics.density).dp
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

/**
 * How tall the phone illustration is — **and the number that decides whether the
 * drawing reads as a phone at all.**
 *
 * The tutorial's camera is width-bound on a dialog (see [tutorialCamera]), so the
 * body's *width* is [TUTORIAL_BODY_WIDTH] of the canvas whatever this says. What
 * this sets is the body's visible *height*: how much phone the reader sees below
 * the plate.
 *
 * At **205 dp**, the 280 dp canvas a phone-width dialog gives makes the body 174 dp
 * wide over 195 dp of visible height — **1.12 body widths** of phone, which is the
 * plate, the Essential Key, and a short strip of body under it. That is the whole
 * job: the illustration exists to show where the matrix and the key are.
 *
 * **A note against repeating a mistake.** A previous revision raised this to 270 dp
 * on the reasoning that the drawing's *visible aspect* (0.89 wide-to-tall) was what
 * four rejected revisions had in common, and that no zoom could fix it in a 205 dp
 * frame. Both halves were wrong. The accepted drawing has that same 0.89, so it was
 * never the defect; the defect was [DeviceBack]'s island width, modelled at `0.63`
 * of the body instead of the real `0.91`. That KDoc even recorded the disproof and
 * misread it — it observed that the original drawing's island was `0.89` of the
 * body's width and called it "oversized" against "the real device's `0.63`", when
 * `0.89` was very nearly the correct figure and `0.63` was the invention. Once the
 * island was corrected, 205 dp read as a phone immediately, and 270 dp read as a
 * slab with blank body under the key.
 *
 * It is deliberately not taller. At this height the dialog's content comes to
 * roughly 510 dp — 36 padding + 28 title + 14 + 40 switcher + 8 + (205 + 126 of
 * page) + 6 + 7 dots + 40 button — against a window height less the 2 x
 * [DIALOG_VERTICAL_MARGIN] the surface is capped at, so on any phone-shaped window
 * the mode switcher above the illustration and the caption below it are both on
 * screen at once. The `verticalScroll` that makes a taller illustration affordable
 * at all is then what it was for: large font scales and short windows, not ordinary
 * use.
 *
 * The floor is [TUTORIAL_SPAN_Y] body widths of drawing — 180 dp at this canvas —
 * below which the camera would stop being width-bound and the phone would simply
 * stop growing. The height and the aspect it produces are both pinned by
 * `GlyphCanvasTest`; the aspect is the assertion that matters, and is the one
 * nobody was making while this was being rejected.
 *
 * `205` shows 1.12 body widths of phone on a phone-sized dialog: the plate, the key
 * and a short strip of body below it, and nothing more. A revision that raised this
 * to `270` was rejected on sight — the extra 65 dp bought no content, only blank
 * body under the key, and the phone read as a slab. The number to change when the
 * drawing needs more room is the camera's, not this one.
 */
internal val ILLUSTRATION_HEIGHT = 205.dp

/**
 * **The tutorial's camera**: the whole phone, filling the illustration area and
 * cropped by it.
 *
 * The other of the app's two views of `DeviceBack` — see `GlyphCanvas`'s KDoc for
 * why the framing is a per-caller parameter and the drawing is not. This one is
 * zoomed *out*: the reader has to recognise a phone lying face down and find the
 * key on its right edge, so the subject is the device, not the panel.
 *
 * - **[TUTORIAL_BODY_WIDTH]** — the whole scale of the drawing, and the number this
 *   has been got wrong at three times: **the body is 0.62 of the canvas's width**,
 *   with the remaining 0.38 the gutters the Essential Key's ripple expands into —
 *   it is a canvas annotation rather than a part of the phone and needs somewhere
 *   to be. At `0.83` — five sixths, which is where a previous phase put it — the
 *   body was a third wider without being any taller, and the phone read as a squat
 *   block instead of a device. **[TUTORIAL_SPAN_X]** is its reciprocal, because a
 *   camera is expressed in how much of the *device* fits across the frame.
 * - **[TUTORIAL_SPAN_Y]** — how many body widths must fit down it, from the top of
 *   the body to just past the key: [TUTORIAL_TOP_MARGIN] plus the key's lower edge,
 *   plus clearance. It only binds on a dialog wide enough that the phone would
 *   otherwise outgrow the illustration's height, i.e. a tablet's. It is derived
 *   from [DeviceBack], not guessed: when the plate's true width put its bottom edge
 *   0.16 body widths further down, the key it sits above went with it, and a span
 *   fixed at `0.95` cropped the key off a 540 dp dialog.
 * - **[TUTORIAL_FOCUS_X]** — the point of the device at the centre of the frame,
 *   horizontally. `0.53` rather than `0.5` puts a little more of the gutter on the
 *   right, which is the side the key's annotations live on.
 * - the focus's **y** is derived so that the body's top edge always lands
 *   [TUTORIAL_TOP_MARGIN] below the top of the canvas, whatever the zoom came out
 *   at — 5 % of the illustration's height, at the dialog width a phone gives. The
 *   phone therefore starts at the top and runs off the bottom at every dialog size,
 *   which is what makes it read as a device continuing past the frame rather than
 *   as a card floating in one.
 *
 * Those four numbers and [ILLUSTRATION_HEIGHT] between them fix the one thing a
 * reader actually judges — **the visible body is 0.62 of the canvas wide and 0.67
 * as wide as it is tall** — and both are asserted, because this illustration has
 * been rejected four times for getting exactly that wrong. The aspect is
 * [ILLUSTRATION_HEIGHT]'s to set, not this function's: no zoom can produce it, and
 * four attempts to find one failed. See its KDoc.
 */
internal fun tutorialCamera(canvas: Size): Camera {
    val zoom = minOf(canvas.width / TUTORIAL_SPAN_X, canvas.height / TUTORIAL_SPAN_Y)
    if (zoom <= 0f) return Camera(0f, Offset.Zero)
    return Camera(
        zoom = zoom,
        focus = Offset(TUTORIAL_FOCUS_X, canvas.height / (2f * zoom) - TUTORIAL_TOP_MARGIN),
    )
}

/**
 * **The body takes 0.62 of the canvas's width.** See [tutorialCamera]; pinned by
 * `GlyphCanvasTest.theTutorialDrawsTheBodyAtSixTenthsOfTheCanvasWidth`.
 */
internal const val TUTORIAL_BODY_WIDTH = 0.62f

/**
 * Where the press markers sit, **in device coordinates**: on the body, `0.09` of a
 * body width in from its right edge, level with the Essential Key.
 *
 * They used to hang 18 dp *past* that edge, out in the gutter, and that is the
 * "small dot floating in the white space beside the key" the last screenshot was
 * rejected for. A mark outside the device has nothing to belong to and reads as a
 * rendering fault rather than as an annotation — and the accurate, smaller island
 * made it worse, because in a gutter that is now mostly empty a stray dot is the
 * only thing in it. On the body, beside the key it is counting, it reads as what
 * it is.
 *
 * `0.91` rather than anything closer to the edge because the key's nub straddles
 * `1.0` — it is [DeviceBack.KEY_WIDTH] wide with 45 % of that inside the body — so
 * a marker further right would touch it. Expressed as a device point and mapped
 * through the camera, like every other annotation here, so it cannot drift out from
 * under the phone when a framing changes; the gutter offset it replaces could not
 * say the same. Pinned by `GlyphCanvasTest`.
 */
internal const val TUTORIAL_MARKER_X = 0.91f

private const val TUTORIAL_SPAN_X = 1f / TUTORIAL_BODY_WIDTH
private const val TUTORIAL_FOCUS_X = 0.53f
private const val TUTORIAL_TOP_MARGIN = 0.059f
private const val TUTORIAL_SPAN_Y =
    TUTORIAL_TOP_MARGIN + DeviceBack.KEY_TOP + DeviceBack.KEY_HEIGHT + 0.034f

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
                .height(ILLUSTRATION_HEIGHT)
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

/**
 * One tutorial frame: the matrix contents, and whether the panel is lit at all
 * (the menu-mode blink turns everything down to the unlit level without changing
 * the pattern, so the two are separate).
 */
private class MatrixFrame(val cells: IntArray, val on: Boolean)

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

/** The Glyph Matrix this tutorial illustrates: the Phone (4a) Pro's 13x13. */
private const val TUTORIAL_MATRIX_SIZE = 13

private fun DrawScope.drawTutorialPhone(base: Color, step: TutorialStep, t: Long) {
    val camera = tutorialCamera(size)
    // The phone, key included: the key is a feature of the device and lives in
    // `DeviceBack` with the rest of it, so all this page contributes is whether it
    // is being pressed at this instant. Everything below is annotation drawn
    // *over* the device — a countdown ring, a ripple, press markers — and is
    // positioned by mapping device points through the camera rather than by
    // resolving any geometry of its own.
    val pressed = step.presses.any { t in it..(it + PRESS_MS) }
    val disc = drawDeviceBack(base, camera, keyPressed = pressed)
    val mc = disc.center
    val mr = disc.radius

    val frame = step.matrix(t)
    // The blink's "off" phase is an ALL-DARK panel, not a hidden pattern: the
    // real matrix drops every LED to nothing and the illustration draws each
    // unlit cell at its resting alpha. Handing [drawMatrix] a blank frame is
    // therefore the same picture the old `frame.on` branch drew, one code path
    // fewer.
    drawMatrix(mc, mr, TUTORIAL_MATRIX_SIZE, if (frame.on) frame.cells else BLANK_MATRIX)

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

    // Where the Essential Key ended up on screen: the middle of the nub itself,
    // mapped like everything else here rather than the body's edge plus a nudge.
    // The ripple is centred on it and the press markers sit level with it.
    val keyCenter = camera.map(
        Offset(
            DeviceBack.KEY_LEFT + DeviceBack.KEY_WIDTH / 2f,
            DeviceBack.KEY_TOP + DeviceBack.KEY_HEIGHT / 2f,
        ),
        size,
    )

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
    // each press lands, stacked ON the body beside the key — see
    // [TUTORIAL_MARKER_X], which is where the stray dot in the gutter came from.
    // Presses > 600 ms apart are separate gestures (bursts); the dots reset for
    // each burst, so repeated gestures read as "x2, twice" rather than one long
    // chain.
    val bursts = mutableListOf<MutableList<Long>>()
    step.presses.forEach { p ->
        if (bursts.isEmpty() || p - bursts.last().last() > 600) {
            bursts += mutableListOf(p)
        } else {
            bursts.last() += p
        }
    }
    val burst = bursts.lastOrNull { t >= it.first() - 400 } ?: bursts.firstOrNull()
    val markerX = camera.map(Offset(TUTORIAL_MARKER_X, 0f), size).x
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
// Each row MUST be exactly 13 characters: [charsetFrame] indexes rows[r][c] for
// r,c in 0..12 with no bounds guard.

/** An unlit panel — see the blink handling in [drawTutorialPhone]. */
private val BLANK_MATRIX = IntArray(TUTORIAL_MATRIX_SIZE * TUTORIAL_MATRIX_SIZE)

/**
 * These patterns' four shading levels, as the 0..4095 brightnesses
 * [drawMatrix] speaks — resolved ONCE per pattern, at class-init, so the
 * animation loop allocates nothing per frame.
 *
 * The illustration used to compute an alpha per character directly (1 / 0.55 /
 * 0.25 / off) and these are those alphas expressed as panel levels, which is
 * *nearly* but not exactly a lossless conversion: `drawMatrix` divides by 4095,
 * so `2252` is alpha 0.549939 rather than 0.55 and `1024` is 0.250061 rather
 * than 0.25. It makes no difference to a single rendered pixel. Compose packs an
 * sRGB colour as 8-bit channels — `(alpha * 255 + 0.5).toInt()` — and both pairs
 * land on the same byte (140 and 64), so every LED in this dialog draws the
 * exact colour it drew before. `#` is 4095, i.e. alpha 1.0 exactly.
 */
private fun charsetFrame(rows: List<String>): IntArray {
    val size = rows.size
    val out = IntArray(size * size)
    for (r in 0 until size) {
        val row = rows[r]
        for (c in 0 until size) {
            out[r * size + c] = when (row[c]) {
                '#' -> 4095
                '+' -> 2252
                ':' -> 1024
                else -> 0
            }
        }
    }
    return out
}

/**
 * Dice toy showing a 2.
 *
 * All four faces place their 2x2 pips on the row/column pairs {2,3} (low),
 * {5,6} (middle) and {9,10} (high), so every face's lit bounding box is 2..10
 * on both axes — margins 2|2 — and reads as centred on the disc.
 *
 * Not 3-wide pips at 1..3 / 5..7 / 9..11, which would be both symmetric and
 * evenly spaced: the panel is a disc and only has LEDs out to 6.5 cells from the
 * centre (`PanelMask` — the grid's inscribed circle, counted off a photograph of
 * the real panel), while cell (1,1) sits at sqrt(50) = 7.07 cells, so the outer
 * pips would render visibly notched. The 2x2 placement's furthest cell (2,2) is
 * at sqrt(32) = 5.66 cells, comfortably inside.
 */
private val DICE_2 = charsetFrame(
    listOf(
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
    ),
)

/** Dice toy showing a 3; pip placement per [DICE_2]. */
private val DICE_3 = charsetFrame(
    listOf(
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
    ),
)

/** Dice toy showing a 5; pip placement per [DICE_2]. */
private val DICE_5 = charsetFrame(
    listOf(
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
    ),
)

/** Dice toy showing a 6; pip placement per [DICE_2]. */
private val DICE_6 = charsetFrame(
    listOf(
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
    ),
)

private val ROLL = listOf(DICE_3, DICE_6, DICE_2, DICE_6, DICE_3)

/**
 * The Compass toy pointing north, taken from the real renderer's 13x13
 * output (the compass_13_north golden): '#' needle, ':' tail, cardinal ring
 * with '+' W/E/S markers and ':' intercardinal dots.
 */
private val COMPASS = charsetFrame(
    listOf(
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
    ),
)

/**
 * The Pixel Clock toy on its plain-digits theme reading 12:34, stacked "12"
 * over "34" — the clock_13_1234_t0 golden.
 */
private val CLOCK = charsetFrame(
    listOf(
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
    ),
)

/**
 * The Ambient toy — the *analog* clock background at 10:08 (hour and minute
 * hands), from the ambient_13_bg_analog_1008 golden. Deliberately not the
 * default digital background: digits here would look almost identical to
 * [CLOCK] and the "cycle between toys" animations would read as no change at
 * all.
 */
private val AMBIENT = charsetFrame(
    listOf(
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
    ),
)
