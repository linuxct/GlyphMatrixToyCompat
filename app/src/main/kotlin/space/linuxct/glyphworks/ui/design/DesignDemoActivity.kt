package space.linuxct.glyphworks.ui.design

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import space.linuxct.glyphworks.Core
import space.linuxct.glyphworks.R
import space.linuxct.glyphworks.ui.CREATE_TAB_INDEX
import space.linuxct.glyphworks.ui.CreateEmptyState
import space.linuxct.glyphworks.ui.DIALOG_VERTICAL_MARGIN
import space.linuxct.glyphworks.ui.FloatingNavBar
import space.linuxct.glyphworks.ui.NewDesignFields
import space.linuxct.glyphworks.ui.dialogCardWidth
import space.linuxct.glyphworks.ui.homeCodename
import space.linuxct.glyphworks.ui.requestPeakRefreshRateWhileVisible
import space.linuxct.glyphworks.ui.theme.GlyphWorksTheme
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The guided demo: the real Create tab and the real editor, driving themselves,
 * with the explanation sitting on top of whatever is moving.
 *
 * It replaces a numbered-steps dialog that tried to describe a drawing tool in
 * prose. Prose cannot teach "drag across the matrix to paint" or "hold a frame to
 * move it" — the user's verdict on the attempt was one word, and it was right.
 *
 * ## What this activity is
 *
 * A host, and almost nothing else. It composes the app's own screens
 * ([CreateEmptyState] and [FloatingNavBar] for the way in, [NewDesignFields] for
 * the questions, [EditorScaffold] for the editor), hands them a sandbox to run in
 * (`ui/design/DesignDemo.kt`), plays a script over them and draws a spotlight and
 * a caption. There is no second editor and no screenshot of one.
 *
 * ## The three layers, in order
 *
 * 1. **The real screens**, inside a [LocalDemoTargets] provider so every tagged
 *    element reports where it is.
 * 2. **A layer that eats every touch.** The script is a script; a stray tap that
 *    opened Design settings or painted a cell would leave the caption describing
 *    something that is no longer on screen.
 * 3. **The overlay** — scrim with a hole in it, the caption, and the tour's own
 *    controls. It is composed last, so its buttons are hit-tested first and the
 *    swallowing layer never sees them, and so its `BackHandler` wins over the
 *    editor's.
 *
 * ## Rotation, and being sent to the background
 *
 * **Backgrounding holds its place.** The activity survives, the step's script is
 * suspended in `withFrameNanos`, and a clock that is not producing frames does
 * not advance it. Coming back resumes mid-gesture; nothing is lost and nothing
 * played to an empty room.
 *
 * **Rotation resumes on the same step**, which is not the same as surviving it:
 * the activity is recreated, so the sandbox is rebuilt and every earlier step is
 * replayed *instantly* (see [DemoActor]'s `instant` mode) before the current step
 * plays again. Only the step index is saved, because it is the only thing that
 * has to be — a tour whose state is the result of running steps 0..n can always
 * be reconstituted by running steps 0..n, and inventing a serialisation format
 * for a half-painted tutorial frame to avoid a few microseconds of replay would
 * be a strange trade. The same machinery is what makes **Back** work, which is
 * how it earns its place twice.
 */
class DesignDemoActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Core.init(this)
        // Same reason every other visible activity asks: the tour is a continuous
        // animation of a finger moving, and at 60 Hz it visibly steps.
        requestPeakRefreshRateWhileVisible()
        enableEdgeToEdge()
        setContent {
            GlyphWorksTheme {
                DesignDemoTour(onClose = ::finish)
            }
        }
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, DesignDemoActivity::class.java)
    }
}

@Composable
private fun DesignDemoTour(onClose: () -> Unit) {
    val home = remember { homeCodename() }
    val sandbox = remember(home) { DemoSandbox(home) }
    val targets = remember { DemoTargets() }
    val ghost = remember { DemoGhost() }

    // The ONLY saved state, and the whole of the tour's identity — see this
    // file's KDoc on rotation.
    var index by rememberSaveable { mutableIntStateOf(0) }
    val at = index.coerceIn(DEMO_STEPS.indices)
    val step = DEMO_STEPS[at]

    // Bring the sandbox up to this step, then play it.
    //
    // The two halves are one effect because they must not interleave: the script
    // is a delta on top of every step before it. `applied` says how many of those
    // are already in the sandbox — equal means this is a plain Next and the
    // editor keeps its identity (a thumbnail that just slid in stays where it
    // slid to), anything else (Back, a rotation, a Next that cut a script short)
    // means rebuild and replay.
    LaunchedEffect(at, sandbox) {
        if (sandbox.applied != at) {
            sandbox.reset()
            val replay = DemoActor(ghost, targets, instant = true)
            for (earlier in 0 until at) DEMO_STEPS[earlier].act(replay, sandbox)
            sandbox.applied = at
        }
        ghost.hide()
        // A script reaches for its target's position, and on the frame a stage
        // changes there is not one yet. Waiting for the first report beats
        // guessing a delay; the timeout is only there so a target that never
        // appears cannot wedge the tour.
        step.target?.let { target ->
            withTimeoutOrNull(TARGET_TIMEOUT_MS) {
                snapshotFlow { targets.unionOf(target) }.filterNotNull().first()
            }
        }
        step.act(DemoActor(ghost, targets, instant = false), sandbox)
        sandbox.applied = at + 1
        ghost.hide()
    }

    Box(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalDemoTargets provides targets) {
            DemoStageContent(step.stage, sandbox)
        }
        // Layer 2. Everything below this is a picture.
        Box(Modifier.fillMaxSize().swallowTouches())
        DemoOverlay(
            step = step,
            at = at,
            targets = targets,
            ghost = ghost,
            onBack = { if (at > 0) index = at - 1 },
            onNext = { if (at < DEMO_STEPS.lastIndex) index = at + 1 else onClose() },
            onSkip = onClose,
        )
    }
}

/** How long to wait for a step's target to report its position before giving up. */
private const val TARGET_TIMEOUT_MS = 800L

// ---------- the stages ----------

@Composable
private fun DemoStageContent(stage: DemoStage, sandbox: DemoSandbox) {
    // The move from the Create tab to the editor is an activity transition in the
    // app proper; here it is a cross-fade, because a cut is the one thing in this
    // app that never happens. Alpha is an effect, so: the effects spring.
    Crossfade(
        targetState = stage == DemoStage.EDITOR || stage == DemoStage.SETTINGS,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "demoStage",
    ) { editor ->
        if (editor) {
            // The real editor, over the sandbox state. `demo = true` is the whole
            // difference: no writes, no live preview. See [EditorScaffold].
            //
            // The store is handed over and never used — the demo flag
            // short-circuits the save path before it is reached — rather than
            // being made nullable, which would put a `?` on the production
            // editor's one dependency for the benefit of a tutorial.
            EditorScaffold(
                state = sandbox.state,
                store = Core.designStore,
                onClose = {},
                demo = true,
            )
        } else {
            DemoCreateStage()
        }
    }
    // The two pop-ups the tour has to be able to point INSIDE, each shown in this
    // composition rather than in a platform Dialog — see [DemoSheet].
    DemoSheet(visible = stage == DemoStage.DIALOG) { DemoNewDesignSheet(sandbox) }
    DemoSheet(visible = stage == DemoStage.SETTINGS) {
        // The real Design settings card, over the real editor state: the repeat
        // toggle the tour is about to demonstrate is the one the editor has.
        DesignSettingsCard(sandbox.state, onChanged = {}, onClose = {})
    }
}

/**
 * A pop-up that is not a window.
 *
 * Both of the tour's pop-ups exist because a real `Dialog` is a separate window
 * that would sit above the spotlight, leaving the captions pointing at things
 * nobody can see. Composed in place, they can be spotlit — and they still enter
 * and leave on MD3's dialog motion rather than appearing, because that is what
 * [MotionDialog] does to every other pop-up in this app: scale on the spatial
 * spring, alpha on the effects spring, which never bounces.
 *
 * **The sheet does not decide how wide its card is.** It used to, by omission —
 * a wrap-content card measured against the screen takes everything this Box
 * leaves it, which is how the tour's Design settings came out 43 dp wider than
 * the real one. The cards ask [dialogCardWidth] for their own width now, so the
 * padding here is only a floor under how close to the edges they may come, and
 * the vertical half is [DIALOG_VERTICAL_MARGIN] — the same margin every real
 * dialog in this app is held to, taken from the same constant rather than
 * spelled again.
 */
@Composable
private fun DemoSheet(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()) +
            scaleIn(MaterialTheme.motionScheme.defaultSpatialSpec(), initialScale = SHEET_ENTER_SCALE),
        exit = fadeOut(MaterialTheme.motionScheme.defaultEffectsSpec()) +
            scaleOut(MaterialTheme.motionScheme.defaultSpatialSpec(), targetScale = SHEET_ENTER_SCALE),
        label = "demoSheet",
    ) {
        Box(
            Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = DIALOG_VERTICAL_MARGIN),
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
    }
}

/** The scale a tour pop-up grows from, matching [MotionDialog]'s own. */
private const val SHEET_ENTER_SCALE = 0.85f

/**
 * The Create tab as somebody with no designs sees it, with the real floating pill
 * and the real `+` beside it.
 *
 * [FloatingNavBar] is driven by constants rather than by a pager because there is
 * nothing to swipe here: the tour is pointing at the `+`, and the pill is context
 * for where the `+` lives. The page title is drawn plainly rather than through a
 * `LargeTopAppBar` — the app bar is `MainActivity`'s, it collapses on scroll, and
 * a second one composed here would be chrome pretending to be shared.
 *
 * **A `Surface`, not a `Box` with a background colour**, and that is not a
 * stylistic preference: `Surface` is what publishes `LocalContentColor`, and in
 * the app proper the Scaffold provides one for every page. Drawn on a bare Box
 * the local falls through to its default — `Color.Black` — so the page title
 * rendered black on a black background and simply was not there in dark mode.
 * Anything inside a [SectionCard] was unaffected (a Card is a Surface), which is
 * why the title was the only casualty and why it looked like a missing string
 * rather than a missing colour.
 */
@Composable
private fun DemoCreateStage() {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                Text(
                    stringResource(R.string.create_title),
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(start = 20.dp, top = 24.dp, bottom = 8.dp),
                )
                CreateEmptyState(onStart = {}, onImport = {})
            }
            FloatingNavBar(
                selected = CREATE_TAB_INDEX,
                position = { CREATE_TAB_INDEX.toFloat() },
                fabVisible = true,
                onFabClick = {},
                onSelect = {},
                onPillHeight = {},
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

/**
 * The new-design questions, in the tour's own window.
 *
 * The **controls** are [NewDesignFields] — the same composable the real dialog
 * puts in its text slot, so the segmented row here is that segmented row and
 * cannot drift from it. The **window** is not a platform `Dialog`, and that is
 * the point: a real dialog is its own window and would sit above this tour's
 * spotlight, leaving the caption pointing at a rectangle nobody can see. So the
 * surface, the title and the two buttons are drawn here, at the same 28 dp radius
 * and with the same strings — and at the same [dialogCardWidth], which is what
 * the platform would have given the real `AlertDialog`'s window. Without it this
 * card is measured against the screen and comes out wider than the dialog it is
 * standing in for, which is the defect [DesignSettingsCard] carried.
 */
@Composable
private fun DemoNewDesignSheet(sandbox: DemoSandbox) {
    Surface(
        modifier = Modifier.width(dialogCardWidth()),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 20.dp)) {
            Text(
                stringResource(R.string.create_new),
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(16.dp))
            NewDesignFields(
                name = sandbox.name,
                onName = { sandbox.name = it },
                dynamic = sandbox.dynamic,
                onDynamic = { sandbox.dynamic = it },
                target = sandbox.target,
                onTarget = { sandbox.target = it },
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = {}) { Text(stringResource(R.string.create_cancel)) }
                TextButton(
                    onClick = {},
                    modifier = Modifier.demoTarget(DemoTarget.DIALOG_CREATE),
                ) {
                    Text(stringResource(R.string.create_create))
                }
            }
        }
    }
}

/**
 * Eats every pointer event, before anything under it gets a look.
 *
 * `PointerEventPass.Initial` is what makes it a *swallow* rather than a fallback:
 * consuming on the initial pass means the real screens below never see the
 * change at all, so a long-press on a thumbnail cannot start a drag the script
 * does not know about.
 */
private fun Modifier.swallowTouches(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
        }
    }
}

// ---------- the overlay ----------

/** How much room to leave around a spotlit element. */
private val SPOTLIGHT_PADDING = 10.dp

/** Corner radius of the hole cut in the scrim. */
private val SPOTLIGHT_RADIUS = 20.dp

/** Gap between the spotlight and the caption that points at it. */
private val CAPTION_GAP = 16.dp

/** Radius of the ghost fingertip. */
private val GHOST_RADIUS = 20.dp

@Composable
private fun DemoOverlay(
    step: DemoStep,
    at: Int,
    targets: DemoTargets,
    ghost: DemoGhost,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onSkip: () -> Unit,
) {
    // Back leaves the tour rather than stepping through it. Nothing here is a
    // navigation stack, and a tutorial that trapped the back gesture would be a
    // worse offence than one that ends a step early. Composed after the editor's
    // own BackHandler, so this is the enabled callback the dispatcher reaches
    // first.
    BackHandler(onBack = onSkip)

    val density = LocalDensity.current
    val padPx = with(density) { SPOTLIGHT_PADDING.toPx() }
    val insets = WindowInsets.safeDrawing
    val topInset = insets.getTop(density)
    val bottomInset = insets.getBottom(density)

    // Where the caption is pointing, in root coordinates — reported by the
    // element itself, never written down here.
    var focus by remember { mutableStateOf<Rect?>(null) }
    LaunchedEffect(step, targets) {
        val target = step.target
        if (target == null) {
            focus = null
            return@LaunchedEffect
        }
        snapshotFlow {
            step.targetIndex?.let { targets.boundsOf(target, it) } ?: targets.unionOf(target)
        }.collect { focus = it }
    }

    // The hole travels between steps on the spatial spring — it is a POSITION and
    // a SIZE, so it is spatial by the same rule everything else in this app
    // follows, and it is under-damped, which is why the draw normalises it.
    val hole = remember { Animatable(Rect.Zero, Rect.VectorConverter) }
    val holeSpec = MaterialTheme.motionScheme.defaultSpatialSpec<Rect>()
    LaunchedEffect(hole) {
        var placed = false
        // collectLatest: a target that moves while the spotlight is still
        // travelling (the timeline scrolling under a spotlit thumbnail) retargets
        // the animation instead of queueing a second one behind it.
        snapshotFlow { focus }.collectLatest { rect ->
            if (rect == null) {
                placed = false
                return@collectLatest
            }
            val padded = rect.inflate(padPx)
            if (placed) hole.animateTo(padded, holeSpec) else hole.snapTo(padded)
            placed = true
        }
    }

    val scrim = MaterialTheme.colorScheme.scrim.copy(alpha = SCRIM_ALPHA)
    val ring = MaterialTheme.colorScheme.surface
    val radiusPx = with(density) { SPOTLIGHT_RADIUS.toPx() }
    val ringPx = with(density) { 2.dp.toPx() }
    val ghostPx = with(density) { GHOST_RADIUS.toPx() }

    // The scrim and the ghost are two Canvases on purpose, and the reason is the
    // same one that cost this app a stutter once already.
    //
    // Punching a hole means BlendMode.Clear, which means an OFFSCREEN layer — the
    // whole window rendered into a second buffer and composited back. The pager's
    // stretch overscroll was removed for exactly that (a 1426 x 2800 layer on half
    // of all frames), so this one must not be invalidated per frame. It is not:
    // it reads only the spotlight, which moves once per step. The ghost moves 120
    // times a second and lives in its own plain Canvas above it, where a redraw is
    // three draw calls into an existing render node and no layer at all.
    Canvas(
        Modifier
            .fillMaxSize()
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
    ) {
        drawRect(scrim)
        // A DRAW-phase read of an animated rectangle, so a moving spotlight
        // invalidates this one draw scope and recomposes nothing — the same
        // discipline the editor's own canvas is built on (see [EditorFrame]).
        if (focus != null) {
            val spot = hole.value.sane()
            drawRoundRect(
                Color.Black,
                topLeft = spot.topLeft,
                size = spot.size,
                cornerRadius = CornerRadius(radiusPx),
                blendMode = BlendMode.Clear,
            )
            drawRoundRect(
                ring,
                topLeft = spot.topLeft,
                size = spot.size,
                cornerRadius = CornerRadius(radiusPx),
                style = Stroke(width = ringPx),
            )
        }
    }
    Canvas(Modifier.fillMaxSize()) {
        ghost.position?.let { drawGhost(it, ghost.press, ghostPx) }
    }

    Box(Modifier.fillMaxSize()) {
        DemoCaption(
            step = step,
            at = at,
            onBack = onBack,
            onNext = onNext,
            onSkip = onSkip,
            modifier = Modifier.layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                val available = constraints.maxHeight
                val gap = CAPTION_GAP.toPx()
                val rect = focus
                // Below the spotlight when there is room, above it otherwise, and
                // centred when there is nothing to point at (the last step).
                // Everything is in ROOT coordinates because this Box is
                // unpadded — the insets are applied by the clamp below rather
                // than by a parent, so that these two coordinate systems stay the
                // same one.
                val y = when {
                    rect == null -> (available - placeable.height) / 2
                    available - rect.bottom - bottomInset >= placeable.height + gap ->
                        (rect.bottom + gap).roundToInt()
                    else -> (rect.top - gap - placeable.height).roundToInt()
                }
                val lowest = (available - bottomInset - placeable.height).coerceAtLeast(0)
                layout(placeable.width, available) {
                    placeable.place(0, y.coerceIn(min(topInset, lowest), lowest))
                }
            },
        )
    }
}

/** How far the scrim dims what it is not pointing at. */
private const val SCRIM_ALPHA = 0.72f

/**
 * A rectangle that is safe to draw.
 *
 * The spatial spring is under-damped on purpose, so a shrinking spotlight can
 * overshoot far enough to put `left` past `right` for a frame or two — a negative
 * size, which is not a legal thing to hand a draw call. Same rule as every other
 * spring-driven dimension in this app, expressed as a swap rather than a
 * `coerceAtLeast` because both edges are meaningful.
 */
private fun Rect.sane(): Rect = Rect(
    left = min(left, right),
    top = min(top, bottom),
    right = max(left, right),
    bottom = max(top, bottom),
)

/**
 * The stand-in fingertip.
 *
 * Light fill, dark ring, and neither of them from the colour scheme — the same
 * reasoning [GlyphCanvas] gives for the LEDs. It has to read on a dimmed page
 * *and* inside the spotlight hole, which is undimmed and is whatever colour the
 * user's theme makes it, so a single theme role would disappear against one or
 * the other.
 *
 * [press] fills a ring around it, which is what a press-and-hold looks like when
 * there is no finger to watch — the same device `KeyTutorialDialog` uses for the
 * auto-set countdown.
 */
private fun DrawScope.drawGhost(at: Offset, press: Float, radius: Float) {
    drawCircle(Color.White.copy(alpha = 0.34f), radius = radius, center = at)
    drawCircle(
        Color.Black.copy(alpha = 0.55f),
        radius = radius,
        center = at,
        style = Stroke(width = radius * 0.12f),
    )
    if (press > 0f) {
        val r = radius * 1.35f
        drawArc(
            Color.White.copy(alpha = 0.9f),
            startAngle = -90f,
            sweepAngle = 360f * press.coerceIn(0f, 1f),
            useCenter = false,
            topLeft = Offset(at.x - r, at.y - r),
            size = Size(r * 2f, r * 2f),
            style = Stroke(width = radius * 0.16f),
        )
    }
}

/**
 * What the step says, and the three ways out of it.
 *
 * The controls live INSIDE the caption rather than pinned to the bottom of the
 * screen, so that the one thing guaranteed not to be covered by the tour's own
 * furniture is the element it is pointing at. Skip is always present — a tutorial
 * you cannot leave is a trap — and Back is disabled rather than hidden on the
 * first step, so the row does not change width under the finger.
 */
@Composable
private fun DemoCaption(
    step: DemoStep,
    at: Int,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val last = at == DEMO_STEPS.lastIndex
    Box(modifier.fillMaxWidth().padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
        Surface(
            modifier = Modifier.widthIn(max = CAPTION_MAX_WIDTH),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            // Shadow, not tonal: tonal elevation is a deliberate visual no-op in
            // this theme, and this card has to lift off a dimmed page.
            shadowElevation = 8.dp,
        ) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                Text(
                    stringResource(R.string.demo_step_of, at + 1, DEMO_STEPS.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Text(stringResource(step.caption), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onSkip) { Text(stringResource(R.string.demo_skip)) }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onBack, enabled = at > 0) {
                        Text(stringResource(R.string.demo_back))
                    }
                    TextButton(onClick = onNext) {
                        Text(stringResource(if (last) R.string.demo_done else R.string.demo_next))
                    }
                }
            }
        }
    }
}

/** Caption width cap: a readable measure on a tablet, the full width on a phone. */
private val CAPTION_MAX_WIDTH = 420.dp
