package space.linuxct.glyphworks.ui

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import space.linuxct.glyphworks.Core
import space.linuxct.glyphworks.R
import space.linuxct.glyphworks.core.PrefKeys
import space.linuxct.glyphworks.ui.theme.GlyphWorksTheme
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * First-run paged setup: Essential Key listener → always-on Glyph Toy →
 * optional permissions → key mode (only if the listener was enabled) → what to
 * put on the matrix → welcome. Every step is skippable with Next; MainActivity
 * launches this until ONBOARDING_DONE is set.
 */
class OnboardingActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Core.init(this)
        // Every activity the user can see makes the same request, so hopping
        // between them never shows a mode switch mid-transition.
        requestPeakRefreshRateWhileVisible()
        enableEdgeToEdge()
        setContent {
            GlyphWorksTheme {
                OnboardingFlow(
                    onFinished = { completeOnboarding() },
                    onStartDrawing = { completeOnboarding(MainActivity.createTabIntent(this)) },
                )
            }
        }
    }

    /**
     * Ends onboarding and opens the app.
     *
     * [destination] is how the Create page's button skips the last step without
     * skipping the flag: onboarding is DONE either way — leaving it unset would
     * send the user straight back here from `MainActivity`'s own gate — and the
     * only difference is which tab they land on.
     */
    private fun completeOnboarding(destination: Intent = Intent(this, MainActivity::class.java)) {
        Core.prefs.putBoolean(PrefKeys.ONBOARDING_DONE, true)
        startActivity(destination)
        finish()
    }
}

private enum class Page { KEY, TOY, PERMS, MODE, CREATE, DONE }

@Composable
private fun OnboardingFlow(onFinished: () -> Unit, onStartDrawing: () -> Unit) {
    val context = LocalContext.current
    var refreshTick by remember { mutableIntStateOf(0) }
    // Re-probe system state whenever the user returns from Settings.
    LifecycleResumeEffect(Unit) {
        refreshTick++
        onPauseOrDispose { }
    }
    val a11yOn = remember(refreshTick) { isEssentialKeyServiceEnabled(context) }

    // The mode-choice page only exists once the listener is actually on. The
    // Create page is unconditional: drawing needs no permission and no service,
    // so it is the one thing here that works whatever the user skipped.
    val pages = if (a11yOn) {
        listOf(Page.KEY, Page.TOY, Page.PERMS, Page.MODE, Page.CREATE, Page.DONE)
    } else {
        listOf(Page.KEY, Page.TOY, Page.PERMS, Page.CREATE, Page.DONE)
    }
    val pagerState = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()
    // "The first page has already introduced itself" — see [KeyPage].
    //
    // Hoisted HERE rather than remembered inside the page, because this pager
    // leaves `beyondViewportPageCount` at its default of 0 (MainActivity's sets
    // it; this one does not). Page 0 is therefore DISPOSED once a swipe settles
    // on page 1, and composed from scratch on the way back — a flag remembered
    // inside `KeyPage` would reset with it and replay the morph every single
    // time the user swiped back to the start. This composition lives as long as
    // the activity, and `rememberSaveable` carries the fact across rotation too.
    var keyArtMorphed by rememberSaveable { mutableStateOf(false) }

    // ---------- the opening sequence ----------
    //
    // See [INTRO_HOLD_MS] for the whole schedule and its arithmetic. Both flags
    // are hoisted here for exactly the reason `keyArtMorphed` is: page 0 is
    // disposed on the first swipe away, and the page dots and the Back/Next row
    // — which fade in with the page content — do not live on a page at all.
    //
    // Two flags, not one, because the travel and the fade are staggered: the art
    // starts moving home first, and the page appears once it is most of the way
    // there. One flag would fade a paragraph of text in underneath a 220 dp disc
    // still crossing the space it occupies.
    //
    // Only `introDone` is saveable. Rotating mid-sequence restores `false` and
    // replays the whole thing from the reveal — the same bargain `keyArtMorphed`
    // strikes: nobody who was interrupted before the end saw it, so nobody is
    // being shown it twice. Once it is `true` it stays true across rotation,
    // across `refreshTick` (a return from the accessibility Settings screen
    // recomposes this flow but does not restart `LaunchedEffect(Unit)`), and
    // across every swipe back to page 0.
    var introDone by rememberSaveable { mutableStateOf(false) }
    var introTravelling by remember { mutableStateOf(introDone) }
    LaunchedEffect(Unit) {
        if (!introDone) {
            delay(ART_INTRO_REVEAL_MS.toLong() + INTRO_HOLD_MS)
            introTravelling = true
            // **The icon becomes the key on the frame the disc starts moving.**
            // One event, not two timers: the journey up to the header and the
            // change of what is on the panel are the same beat — the logo has
            // said what the app is, and the disc carries the first instruction
            // with it. Driving the morph from its own delay (which is what this
            // replaced) meant the two could only agree by arithmetic, and any
            // change to the reveal's length silently pulled them apart.
            keyArtMorphed = true
            delay(INTRO_STAGGER_MS)
            introDone = true
        }
    }
    // A touch anywhere ends it at once — see the scrim below. It skips to the
    // same settled state, morph included: a user who cut the introduction short
    // still gets the page the introduction was leading to.
    val endIntro = {
        introTravelling = true
        keyArtMorphed = true
        introDone = true
    }
    // POSITION for the art, so spatial: it lands with the scheme's slight
    // overshoot, which is what makes it read as arriving rather than stopping.
    // ALPHA for everything else, so effects: a bouncing opacity flickers.
    // Both are handed down as lambdas rather than as values, so the per-frame
    // read happens in the layout/draw phase and neither page nor chrome
    // recomposes on the way.
    val introCentred = animateFloatAsState(
        targetValue = if (introTravelling) 0f else 1f,
        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
        label = "introCentred",
    )
    val introAlpha = animateFloatAsState(
        targetValue = if (introDone) 1f else 0f,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "introAlpha",
    )

    // Back/Next drive the pager programmatically. Scrolling is POSITION, so it
    // is spatial; animateScrollToPage's own default is a bare spring() (damped
    // 1.0, no overshoot). Default speed rather than slow: `slow` is meant for
    // large surfaces settling into place, and at stiffness 200 a tap on Next
    // takes long enough to feel like the button did not register.
    val pageSpec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.weight(1f),
                    // A SWIPE has to settle on the same spring a Next tap animates
                    // with. HorizontalPager's default snap is foundation's own
                    // hardcoded `spring(StiffnessMediumLow)` (damping 1.0), which
                    // is the one path MaterialTheme cannot reach by itself.
                    flingBehavior = PagerDefaults.flingBehavior(
                        state = pagerState,
                        snapAnimationSpec = pageSpec,
                    ),
                ) { i ->
                    when (pages[i]) {
                        Page.KEY -> KeyPage(
                            a11yOn = a11yOn,
                            morphed = keyArtMorphed,
                            artCentred = { introCentred.value },
                            contentAlpha = { introAlpha.value },
                            onMorphed = { keyArtMorphed = true },
                        )
                        Page.TOY -> ToyPage()
                        Page.PERMS -> PermsPage(refreshTick, onRefresh = { refreshTick++ })
                        Page.MODE -> ModePage()
                        Page.CREATE -> CreatePage(onStartDrawing)
                        Page.DONE -> DonePage(refreshTick)
                    }
                }

                Row(
                    Modifier
                        .fillMaxWidth()
                        // Fades in with the page it belongs to. The row is laid
                        // out at full size the whole time — hiding it by not
                        // composing it would give the pager a taller viewport
                        // during the sequence and then shrink it, which is
                        // exactly the reflow the art is not allowed to suffer.
                        .graphicsLayer { alpha = introAlpha.value }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (pagerState.currentPage > 0) {
                            TextButton(onClick = {
                                scope.launch {
                                    pagerState.animateScrollToPage(
                                        pagerState.currentPage - 1,
                                        animationSpec = pageSpec,
                                    )
                                }
                            }) {
                                Text(stringResource(R.string.onb_back))
                            }
                        }
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Both halves of the dot are animated, and on the matching
                        // MD3 spring: the width is a SIZE (spatial — fast, because
                        // a page dot is about as small and contained as an element
                        // gets, and fast spatial is damped 0.6 so the dot stretches
                        // out with a little life), the fill is a COLOUR (effects —
                        // never bouncing, and stiffer, so the tint has landed
                        // before the stretch finishes). The width used to spring on
                        // a Compose default while the colour cut between frames,
                        // which read as the dot changing shape and colour at two
                        // different moments.
                        val dotWidthSpec = MaterialTheme.motionScheme.fastSpatialSpec<Dp>()
                        val dotColorSpec = MaterialTheme.motionScheme.fastEffectsSpec<Color>()
                        val onDot = MaterialTheme.colorScheme.primary
                        val offDot = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
                        repeat(pages.size) { i ->
                            val selected = i == pagerState.currentPage
                            val dotWidth by animateDpAsState(
                                targetValue = if (selected) 22.dp else 8.dp,
                                animationSpec = dotWidthSpec,
                                label = "dotWidth",
                            )
                            val dotColor by animateColorAsState(
                                targetValue = if (selected) onDot else offDot,
                                animationSpec = dotColorSpec,
                                label = "dotColor",
                            )
                            Box(
                                Modifier
                                    .height(8.dp)
                                    // The under-damped width spring undershoots
                                    // below the 8 dp resting value on the way out;
                                    // a negative width is not a legal constraint.
                                    .width(dotWidth.coerceAtLeast(0.dp))
                                    .background(dotColor, CircleShape),
                            )
                        }
                    }
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                        val last = pagerState.currentPage == pages.lastIndex
                        Button(onClick = {
                            if (last) {
                                onFinished()
                            } else {
                                scope.launch {
                                    pagerState.animateScrollToPage(
                                        pagerState.currentPage + 1,
                                        animationSpec = pageSpec,
                                    )
                                }
                            }
                        }) {
                            Text(stringResource(if (last) R.string.onb_done else R.string.onb_next))
                        }
                    }
                }
            }

            // **The skip scrim.** Invisible, full-bleed, and only present while
            // the opening sequence is running.
            //
            // The alternative was to leave everything live and let the flow
            // collapse into its settled state on the first swipe. That loses on
            // one detail: for the whole sequence the Next button exists, is laid
            // out and is at alpha 0 — it has to be, or its appearance would
            // reflow the row — and an invisible button that still takes a tap is
            // a trap. Someone reaching for a black screen would land on page 2
            // of a flow they have not read page 1 of.
            //
            // So the first touch is spent on ending the sequence instead, and it
            // ends it immediately: the down is consumed on the INITIAL pass, so
            // neither the pager nor the page's own vertical scroll sees it, the
            // art and the content animate to their settled positions on the same
            // springs they would have used anyway, and the scrim is gone before
            // the finger lifts. The cost is one wasted swipe, only ever during
            // the first 1300 ms, and only for someone impatient enough to reach
            // for the screen inside it.
            //
            // It is pointer-input only and carries no semantics, so it does not
            // exist for TalkBack: an accessibility user explores and activates
            // the real controls underneath throughout, which is the right
            // outcome for a purely decorative hold.
            if (!introDone) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown(
                                    requireUnconsumed = false,
                                    pass = PointerEventPass.Initial,
                                ).consume()
                                endIntro()
                            }
                        },
                )
            }
        }
    }
}

// ---------- pages ----------

/**
 * **Opens on the app's own icon, which then dissolves into the key.**
 *
 * The first thing anyone sees is the thing they just tapped, held long enough
 * to connect the launcher to what is now on screen, and only then does the page
 * become about its subject. One transition, not a cycle — [CreatePage] cycles
 * because it is an advert for a drawing tool; this page states a fact and then
 * stays put.
 *
 * There is no morph code here for the same reason there is none there: handing
 * [MatrixArt] a different string *is* the transition. See [ART_ICON] for why
 * the icon can be drawn as itself rather than as a picture of itself.
 *
 * [morphed] is owned by [OnboardingFlow], not by this page — see the comment on
 * the flag for why remembering it here would replay the morph on every swipe
 * back. The one thing it deliberately does NOT suppress is a return *during*
 * the hold: leave before the morph fires and the coroutine is cancelled with
 * the flag still false, so coming back restarts the sequence. Nobody saw it, so
 * nobody is being shown it twice.
 *
 * ## The opening sequence runs on top of all of that, and changes none of it
 *
 * [artCentred] and [contentAlpha] stage this page's *first* 1300 ms — the disc
 * alone in the middle of an empty screen while the logo assembles, then the trip
 * up to the header while the page appears around it. See [INTRO_HOLD_MS].
 *
 * The morph timer below is untouched by it, and that is the point: it counts
 * from composition, so the icon still dissolves into the key at 1800 ms and the
 * key is still fully lit at 2600 ms, exactly as before the sequence existed. The
 * whole opening fits *inside* the hold that was already there rather than being
 * added in front of it. It also means a user who skips the sequence at 200 ms
 * does not get the morph at 200 ms — the icon they cut short still gets its full
 * beat at the top of the page.
 */
@Composable
private fun KeyPage(
    a11yOn: Boolean,
    morphed: Boolean,
    artCentred: () -> Float,
    contentAlpha: () -> Float,
    onMorphed: () -> Unit,
) {
    val context = LocalContext.current
    // **This page owns no timer.** [morphed] is flipped by `OnboardingFlow` on
    // the frame the disc starts travelling back to the header, so the change of
    // art and the change of position are one event rather than two schedules
    // that have to be kept in agreement.
    //
    // The reveal is slower while the logo is being assembled and ordinary once
    // it is the key: the opening is the one moment the dots appearing one by one
    // is the *point*, and [ART_REVEAL_MS] is paced for a picture the user is
    // waiting to read rather than one they are watching arrive.
    PageScaffold(
        if (morphed) ART_KEY else ART_ICON,
        stringResource(R.string.onb_key_title),
        artCentred = artCentred,
        contentAlpha = contentAlpha,
        revealMillis = if (morphed) ART_REVEAL_MS else ART_INTRO_REVEAL_MS,
    ) {
        BodyText(stringResource(R.string.onb_key_body))
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(if (a11yOn) R.string.onb_key_status_on else R.string.onb_key_status_off),
            style = MaterialTheme.typography.titleSmall,
            color = if (a11yOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Button(onClick = {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }) {
            Text(stringResource(R.string.onb_key_enable))
        }
        // Nothing in the Play build: a store install is never subject to the
        // restricted-setting block, so the card would explain a problem the user
        // cannot have. See ui/OptionalFeatures.kt.
        SideloadHelpCard()
    }
}

@Composable
private fun ToyPage() {
    val context = LocalContext.current
    // **Four real toys, cycling, for as long as this page is on screen.**
    //
    // The page is asking the user to hand their Glyph Matrix over to this app,
    // and the honest answer to "what will it put there" is: these. So it shows
    // four of them in turn rather than one drawing of a panel — see
    // [ART_TOY_COMPASS] for where the frames come from.
    //
    // **This one loops**, unlike [CreatePage]'s three-design sequence, which
    // settles on its first design and stops. The difference is what each is
    // saying: the Create page illustrates one idea and then gets out of the way
    // of the paragraph under it, while this page's whole claim is that there is
    // a *rotation* of toys, and a cycle that halted after one pass would quietly
    // contradict it.
    //
    // The loop costs nothing when the page is not being read: this pager leaves
    // `beyondViewportPageCount` at 0, so `ToyPage` is composed only while it is
    // the current page and `LaunchedEffect` is cancelled the moment a swipe
    // settles elsewhere. Coming back starts the rotation again from the compass.
    val toys = remember { listOf(ART_TOY_COMPASS, ART_TOY_CLOCK, ART_TOY_EYES, ART_TOY_DICE) }
    var toy by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(ART_CYCLE_HOLD_MS)
            toy = (toy + 1) % toys.size
        }
    }
    PageScaffold(toys[toy], stringResource(R.string.onb_toy_title)) {
        BodyText(stringResource(R.string.onb_toy_body))
        Spacer(Modifier.height(16.dp))
        Button(onClick = {
            if (!openGlyphToySettings(context)) {
                Toast.makeText(context, R.string.glyph_settings_unavailable, Toast.LENGTH_SHORT).show()
            }
        }) {
            Text(stringResource(R.string.onb_toy_open))
        }
        Spacer(Modifier.height(10.dp))
        Text(
            stringResource(R.string.onb_toy_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PermsPage(refreshTick: Int, onRefresh: () -> Unit) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { onRefresh() }

    PageScaffold(ART_LOCK, stringResource(R.string.onb_perms_title)) {
        BodyText(stringResource(R.string.onb_perms_body))
        Spacer(Modifier.height(16.dp))
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
            PermRow(
                R.string.onb_perm_notif,
                R.string.onb_perm_notif_why,
                granted = remember(refreshTick) { hasAny(context, Manifest.permission.POST_NOTIFICATIONS) },
            ) { launcher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) }
            HorizontalDivider()
            PermRow(
                R.string.onb_perm_mic,
                R.string.onb_perm_mic_why,
                granted = remember(refreshTick) { hasAny(context, Manifest.permission.RECORD_AUDIO) },
            ) { launcher.launch(arrayOf(Manifest.permission.RECORD_AUDIO)) }
            HorizontalDivider()
            PermRow(
                R.string.onb_perm_loc,
                R.string.onb_perm_loc_why,
                granted = remember(refreshTick) {
                    hasAny(
                        context,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                    )
                },
            ) {
                launcher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                    ),
                )
            }
            HorizontalDivider()
            PermRow(
                R.string.onb_perm_alarm,
                R.string.onb_perm_alarm_why,
                granted = remember(refreshTick) { canExactAlarm(context) },
            ) {
                context.startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                        Uri.parse("package:${context.packageName}"),
                    ),
                )
            }
        }
    }
}

@Composable
private fun ModePage() {
    var menuMode by remember {
        mutableStateOf(Core.prefs.getBoolean(PrefKeys.MENU_MODE_ENABLED, PrefKeys.MENU_MODE_ENABLED_DEF))
    }
    var showTutorial by remember { mutableStateOf(false) }
    fun select(enabled: Boolean) {
        menuMode = enabled
        Core.prefs.putBoolean(PrefKeys.MENU_MODE_ENABLED, enabled)
    }
    // **The illustrated switch throws itself, on and off, while the page is up.**
    //
    // A page that asks "how should the key behave" is showing a control, and a
    // control that never moves is a diagram. Throwing it makes the picture the
    // demonstration — and it costs nothing, because the frames are built once
    // (eleven strings, one per column of travel) and the loop is cancelled with
    // the page.
    //
    // **It touches no preference.** The two [ModeCard]s below own the real
    // choice; this is the drawing above them, and it would be a genuinely bad
    // bug for an illustration to flip a setting the user is mid-way through
    // making. It also does not follow the user's selection — a picture that
    // agreed with the cards would just be a third radio button.
    //
    // Smoothness comes from the frames, not from the dissolve: `revealMillis =
    // 0` snaps each one, so the knob *slides* instead of scattering out of one
    // end and into the other. See [toggleArt].
    val toggleFrames = remember { (TOGGLE_KNOB_OFF..TOGGLE_KNOB_ON).map { toggleArt(it) } }
    var toggleOn by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(TOGGLE_HOLD_MS)
            toggleOn = !toggleOn
        }
    }
    // POSITION, so the theme's spatial spring — the knob lands with the same
    // slight overshoot as every other moving thing here. The spring overshoots
    // past 1, so the index is clamped rather than trusted.
    val throwProgress by animateFloatAsState(
        targetValue = if (toggleOn) 1f else 0f,
        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
        label = "keyModeToggle",
    )
    val frame = toggleFrames[
        (throwProgress * (toggleFrames.size - 1)).roundToInt().coerceIn(toggleFrames.indices),
    ]
    PageScaffold(frame, stringResource(R.string.onb_mode_title), revealMillis = 0) {
        BodyText(stringResource(R.string.onb_mode_body))
        Spacer(Modifier.height(16.dp))
        ModeCard(
            selected = !menuMode,
            title = R.string.onb_mode_regular,
            desc = R.string.onb_mode_regular_desc,
        ) { select(false) }
        Spacer(Modifier.height(12.dp))
        ModeCard(
            selected = menuMode,
            title = R.string.onb_mode_menu,
            desc = R.string.onb_mode_menu_desc,
        ) { select(true) }
        Spacer(Modifier.height(8.dp))
        TextButton(
            onClick = { showTutorial = true },
            modifier = Modifier.align(Alignment.CenterHorizontally),
        ) {
            Text(stringResource(R.string.onb_mode_how))
        }
    }
    if (showTutorial) {
        KeyTutorialDialog(onDismiss = { showTutorial = false })
    }
}

/**
 * What to actually put on the matrix: the toys that ship with the app, and the
 * designs the user can draw themselves.
 *
 * ## What it says, and what it deliberately does not
 *
 * Two facts and a signpost. There is a set of ready-made toys; there is a Create
 * tab where you draw your own; and the Tutorials tab has the guides for anything
 * here that is not obvious — including a guided demo of the editor.
 *
 * **No step-by-step of how drawing works.** That is precisely what the demo
 * delivers when the user gets there, and it delivers it by acting the gestures
 * out on the real editor; a paragraph here would be both a worse explanation and
 * a spoiler for the better one. The same restraint the rest of this flow shows —
 * every page says what a thing is FOR and hands over the button that opens it.
 *
 * ## The button
 *
 * For the person who wants to start now rather than read the last page. It ends
 * onboarding properly (see `completeOnboarding`) and opens the app on the Create
 * tab, where the one-off offer to watch the demo is waiting — so "start
 * immediately" and "show me first" both land somewhere sensible.
 */
@Composable
private fun CreatePage(onStartDrawing: () -> Unit) {
    // **Three designs, each dissolving into the next, ending back on the first.**
    //
    // The page is an advert for a pixel editor, so it says what the editor is for
    // by being one: sparkles, then a flame, then a word. Only this page cycles —
    // the others illustrate a single idea each and would be restless.
    //
    // **There is no morph code here, and there does not need to be.** [MatrixArt]
    // already gives every cell a stable pseudo-random turn-on threshold and
    // replays its reveal from zero whenever `pattern` changes, so handing it a
    // different string dissolves the old design out and the new one in over
    // 800 ms, scattered rather than wiped. Changing the string *is* the
    // transition; a crossfade of two grids would be a second animation doing the
    // same job worse.
    //
    // It stops. `for` over a fixed list rather than a loop with a counter: three
    // steps, the last of which is [ART_DRAW] again, so the page settles on the
    // sparkles it opened with and then stays there. A permanently animating
    // illustration behind a paragraph of text is a thing to read *around*.
    var art by remember { mutableStateOf(ART_DRAW) }
    LaunchedEffect(Unit) {
        for (next in listOf(ART_FLAME, ART_HI, ART_DRAW)) {
            delay(ART_CYCLE_HOLD_MS)
            art = next
        }
    }
    PageScaffold(art, stringResource(R.string.onb_create_title)) {
        BodyText(stringResource(R.string.onb_create_body))
        Spacer(Modifier.height(16.dp))
        Button(onClick = onStartDrawing) {
            Text(stringResource(R.string.onb_create_action))
        }
        Spacer(Modifier.height(10.dp))
        Text(
            stringResource(R.string.onb_create_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DonePage(refreshTick: Int) {
    val context = LocalContext.current
    PageScaffold(ART_SMILE, stringResource(R.string.onb_done_title)) {
        BodyText(stringResource(R.string.onb_done_body))
        Spacer(Modifier.height(20.dp))
        val recap = remember(refreshTick) {
            listOf(
                R.string.onb_recap_key to isEssentialKeyServiceEnabled(context),
                R.string.onb_perm_notif to hasAny(context, Manifest.permission.POST_NOTIFICATIONS),
                R.string.onb_perm_mic to hasAny(context, Manifest.permission.RECORD_AUDIO),
                R.string.onb_perm_loc to hasAny(
                    context,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
                R.string.onb_perm_alarm to canExactAlarm(context),
            )
        }
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.padding(vertical = 6.dp)) {
                recap.forEach { (label, ok) ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(8.dp)
                                .background(
                                    if (ok) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)
                                    },
                                    CircleShape,
                                ),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(label), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

// ---------- building blocks ----------

/**
 * The frame every page in the flow shares: art, title, body.
 *
 * ## The two intro parameters
 *
 * Both default to today's behaviour, and only [KeyPage] passes anything else —
 * the other five pages get `artCentred = { 0f }` and `contentAlpha = { 1f }`,
 * which is a translation of zero and an opacity of one, i.e. the layout this
 * has always produced.
 *
 * [artCentred] is *how far down the page the art currently is*, as a fraction:
 * 1 puts it in the middle of the page, 0 leaves it in the header where it lives.
 * The distance between those two is measured here rather than assumed, because
 * only this composable is the size of a page: `onSizeChanged` sits outside
 * `verticalScroll`, so it reports the viewport rather than the scrolling
 * content, and the arithmetic is the art's centred top — `(page − ART_SIZE) / 2`
 * — minus the [ART_TOP_GAP] it already sits at.
 *
 * It is a **translation**, not a layout change. A second, centred copy of the
 * art handed off to the real one would have to match its geometry to the pixel
 * at the instant of the swap or visibly jump, and would need its own copy of
 * [MatrixArt]'s reveal machinery to be lit the same way at the same moment.
 * Translating the one real disc means there is nothing to match: it is the same
 * canvas, mid-reveal, moving.
 *
 * [contentAlpha] fades the title and the body. Alpha rather than absence
 * because the content stays measured and placed throughout — if it appeared by
 * being composed, the scrolling column would grow at that moment and the art,
 * still on its way home, would land somewhere it had not been aiming for.
 *
 * Both arrive as lambdas so the per-frame value is read inside `graphicsLayer`,
 * in the draw phase. Neither this scaffold nor any page recomposes while the
 * sequence runs.
 */
@Composable
private fun PageScaffold(
    art: String,
    title: String,
    artCentred: () -> Float = { 0f },
    contentAlpha: () -> Float = { 1f },
    revealMillis: Int = ART_REVEAL_MS,
    content: @Composable ColumnScope.() -> Unit,
) {
    val density = LocalDensity.current
    // NaN until the first layout pass. `onSizeChanged` writes it during layout
    // and the only reader is the draw phase below, so the value lands in the
    // same frame; the alpha guard covers the case where it does not, since a
    // faint 220 dp disc flashing at the top of an otherwise black screen for one
    // frame is precisely the thing this sequence exists to avoid.
    var travelPx by remember { mutableFloatStateOf(Float.NaN) }
    Column(
        Modifier
            .fillMaxSize()
            .onSizeChanged { size ->
                travelPx = with(density) {
                    ((size.height - ART_SIZE.toPx()) / 2f - ART_TOP_GAP.toPx()).coerceAtLeast(0f)
                }
            }
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
    ) {
        Spacer(Modifier.height(ART_TOP_GAP))
        MatrixArt(
            art,
            Modifier.graphicsLayer {
                val centred = artCentred()
                translationY = if (travelPx.isNaN()) 0f else centred * travelPx
                alpha = if (centred > 0f && travelPx.isNaN()) 0f else 1f
            },
            revealMillis = revealMillis,
        )
        Spacer(Modifier.height(24.dp))
        // One layer for the whole page body rather than one per element: the
        // title and the content have to arrive together, and a Column that
        // fills the width places its children exactly where the outer one did.
        Column(Modifier.fillMaxWidth().graphicsLayer { alpha = contentAlpha() }) {
            Text(title, style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(12.dp))
            content()
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun BodyText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * One permission row: name, why it is wanted, and its grant status — a real
 * clickable [ListItem] (headline / supporting / trailing slots) instead of a
 * `Column` with `Modifier.clickable`, so the press ripple, the shape morph
 * under the finger and the row's colour transitions all come from the library
 * on the theme's motion scheme.
 */
@Composable
private fun PermRow(title: Int, why: Int, granted: Boolean, onClick: () -> Unit) {
    ListItem(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        supportingContent = {
            Text(stringResource(why), style = MaterialTheme.typography.bodySmall)
        },
        trailingContent = {
            Text(
                stringResource(if (granted) R.string.checklist_granted else R.string.checklist_tap_to_grant),
                style = MaterialTheme.typography.labelMedium,
                // A status, not a decoration: granted reads at full ink
                // strength, pending stays muted. Monochrome, straight off the
                // scheme — the difference is contrast, never hue.
                color = if (granted) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        },
    ) {
        Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
    }
}

/** The rounded-card shape the two mode choices have always had. */
private val MODE_CARD_SHAPE = RoundedCornerShape(20.dp)

/**
 * One of the two key-mode choices.
 *
 * A real single-selection [ListItem] — the MD3 component for "one row out of a
 * mutually exclusive set" — rather than the Card + hand-animated border + fill
 * this used to be. Selection is now expressed through the component's own
 * `selected` / `onClick` API, which is what makes the library animate it: the
 * container crossfades on the theme's effects spring, the row morphs shape
 * under a press on its fast spatial spring, and the [RadioButton]'s dot
 * springs in on the same scheme. All of it reads
 * [MaterialTheme.motionScheme]; none of it is spelled out here.
 *
 * Colours come from [selectedRowColors] — the same restrained tint every
 * selected row in the app uses, rather than the default's loud
 * `secondaryContainer` — so selection reads as one idea across the app.
 *
 * The [RadioButton] is passive (`onClick = null`) on purpose: the row carries
 * the `Role.RadioButton` semantics and the ≥ 48 dp target for the whole
 * choice, so a clickable dot would be a second, redundant focus stop.
 *
 * Only the resting SHAPE is pinned, to the 20 dp these cards have always used;
 * the pressed/focused/hovered shapes stay the library's.
 */
@Composable
private fun ModeCard(selected: Boolean, title: Int, desc: Int, onClick: () -> Unit) {
    // Same treatment as the settings dialog's radio rows — see [NoRipple].
    NoRipple {
        ListItem(
            selected = selected,
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            leadingContent = { RadioButton(selected = selected, onClick = null) },
            supportingContent = {
                Text(stringResource(desc), style = MaterialTheme.typography.bodySmall)
            },
            shapes = ListItemDefaults.shapes(
                shape = MODE_CARD_SHAPE,
                selectedShape = MODE_CARD_SHAPE,
            ),
            colors = selectedRowColors(),
        ) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
        }
    }
}

private fun hasAny(context: Context, vararg permissions: String): Boolean =
    permissions.any { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

private fun canExactAlarm(context: Context): Boolean =
    context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true

// ---------- dot-matrix header art ----------

/**
 * Side of a square LED as a fraction of the cell pitch: 80 %, leaving a 20 %
 * gap between neighbours — the ratio that reads as a real dot-matrix panel.
 */
private const val PIXEL_FRACTION = 0.80f

/**
 * Side of the square [MatrixArt] canvas. Named rather than inlined because
 * [PageScaffold] has to know it to work out where the middle of a page is.
 */
private val ART_SIZE = 220.dp

/** The gap [PageScaffold] leaves above the art. Same reason it is named. */
private val ART_TOP_GAP = 24.dp

/**
 * How long [MatrixArt] takes to light every cell of a pattern.
 *
 * A dissolve schedule, not a motion curve — which is why it is a `tween` and not
 * one of the theme's springs. Cells do not travel; they switch on, in scattered
 * order, and the only thing being paced is how long the scatter takes.
 */
private const val ART_REVEAL_MS = 800

/**
 * Draws an ASCII pattern centered on a replica of the Glyph Matrix hardware:
 * a circular disc of 489 LEDs (a 25×25 grid under a circular mask), unlit
 * LEDs faintly visible, lit ones revealing in pseudo-random order on page
 * entry and then shimmering gently, like the matrix waking up.
 *
 * LEDs are square and share one size whether lit or not — on the real panel a
 * pixel occupies the same area either way, only its brightness changes — at
 * 80 % of the cell pitch, so the ~20 % gap reads as a dot-matrix display
 * rather than a field of sparse dots.
 */
@Composable
private fun MatrixArt(
    pattern: String,
    modifier: Modifier = Modifier,
    /**
     * How long the dissolve takes. A parameter rather than a constant because
     * the onboarding logo is assembled at a slower pace than every other
     * pattern: there, the dots arriving one by one is the thing being shown,
     * and at [ART_REVEAL_MS] the picture is complete before anybody has
     * registered that it was being drawn.
     */
    revealMillis: Int = ART_REVEAL_MS,
) {
    val rows = remember(pattern) { pattern.trim().lines() }
    val cols = remember(pattern) { rows.maxOf { it.length } }

    // Starts lit when there is no reveal to run, so a snapped pattern cannot
    // flash dark for the one frame between composition and the effect pass.
    val reveal = remember(pattern) { Animatable(if (revealMillis <= 0) 1f else 0f) }
    LaunchedEffect(pattern) {
        if (revealMillis <= 0) {
            reveal.snapTo(1f)
            return@LaunchedEffect
        }
        reveal.snapTo(0f)
        reveal.animateTo(1f, tween(durationMillis = revealMillis))
    }
    val shimmer by rememberInfiniteTransition(label = "shimmer").animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(3000, easing = LinearEasing)),
        label = "phase",
    )
    val lit = MaterialTheme.colorScheme.onSurface
    val unlit = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)

    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(ART_SIZE)) {
            val grid = 25
            val cell = size.minDimension / grid
            val rowOff = (grid - rows.size) / 2
            val colOff = (grid - cols) / 2
            val circleRadius = grid / 2f - 0.2f
            // One square pixel size for every LED, lit or not: state is carried
            // by brightness alone, exactly as on the hardware.
            val px = cell * PIXEL_FRACTION
            val pxSize = Size(px, px)
            val pxCorner = CornerRadius(px * 0.16f)
            for (r in 0 until grid) {
                for (c in 0 until grid) {
                    val dx = c + 0.5f - grid / 2f
                    val dy = r + 0.5f - grid / 2f
                    if (dx * dx + dy * dy > circleRadius * circleRadius) continue
                    val on = rows.getOrNull(r - rowOff)?.getOrNull(c - colOff) == '#'
                    val center = Offset((c + 0.5f) * cell, (r + 0.5f) * cell)
                    val topLeft = Offset(center.x - px / 2f, center.y - px / 2f)
                    // Each lit dot gets a stable pseudo-random turn-on threshold.
                    val turnOn = ((r * 7 + c * 13) % 29) / 29f
                    if (on && reveal.value > turnOn) {
                        val pulse = 0.85f + 0.15f * sin(shimmer + (r + c) * 0.6f)
                        drawRoundRect(lit.copy(alpha = pulse), topLeft, pxSize, pxCorner)
                    } else {
                        drawRoundRect(unlit, topLeft, pxSize, pxCorner)
                    }
                }
            }
        }
    }
}

// Patterns are drawn on square cells: shapes must be 1:1 (a circle needs
// equal width and height in dots) or they render stretched.

/**
 * **The app's launcher icon, drawn as itself.**
 *
 * `ic_launcher_foreground` is already a 3x3 dot-matrix motif — nine circles of
 * radius 5 at x,y ∈ {38, 54, 70} on the 108 viewport — so it does not have to
 * be *depicted* on a dot matrix, it can simply be laid out on one. Same
 * geometry, different grid.
 *
 * ## The two dim dots
 *
 * The icon's centre and bottom-right dots are drawn at `#40FFFFFF` rather than
 * `#FFFFFFFF`; that quarter-alpha asymmetry is the only thing distinguishing
 * this motif from any 3x3 grid of dots, so losing it would leave a shape that
 * is not recognisably *this* icon. But [MatrixArt] is binary — a cell is lit
 * (shimmering between 0.85 and 1.0 alpha) or it is unlit at 0.08 — and there is
 * no third brightness to spend.
 *
 * So the asymmetry is re-expressed as **size**: the seven bright dots are five
 * cells across, the two dim ones three. Less ink in the same place, which is
 * what "dimmer" looks like on a panel that cannot dim. The rejected
 * alternatives were flattening all nine to identical dots — that is the one
 * change that makes the motif generic — and leaving the dim pair unlit, which
 * turns a 3x3 grid into a seven-dot arrowhead and stops reading as a grid at
 * all. Shrinking keeps every one of the nine grid positions occupied, so the
 * lattice survives and the asymmetry rides on top of it.
 *
 * ## Metrics
 *
 * Five-cell dots on a seven-cell pitch (dot 0.71 of pitch, against the icon's
 * 10-in-16 = 0.63) gives 19 rows, and the icon's dots are *circles*, so each
 * has its four corner cells cut. That rounding is not only faithful, it is what
 * makes the figure fit: the disc culls anything past 12.3 cell radii, a square
 * corner here would sit at 12.7 and be clipped by the mask, and the rounded
 * corner's furthest lit cell sits at **12.04**. The largest design in the flow,
 * and every cell of it clears the edge by design rather than by luck.
 */
private const val ART_ICON = """
.###....###....###.
#####..#####..#####
#####..#####..#####
#####..#####..#####
.###....###....###.
...................
...................
.###...........###.
#####...###...#####
#####...###...#####
#####...###...#####
.###...........###.
...................
...................
.###....###........
#####..#####...###.
#####..#####...###.
#####..#####...###.
.###....###........
"""

/**
 * How long the app logo takes to assemble, against [ART_REVEAL_MS]'s 800 for
 * every other pattern.
 *
 * The opening is the one moment where the dots arriving one at a time IS the
 * content — everywhere else the reveal is a pleasant way for a picture to turn
 * up while the user reads the heading beside it. At 800 ms the logo is finished
 * before anybody has registered that it was being drawn, which spends the whole
 * effect on nothing.
 *
 * It buys its 500 ms back rather than adding them: the morph into [ART_KEY] used
 * to wait out a fixed 1800 ms hold of its own, and now fires on the frame the
 * disc starts travelling, so the sequence is shorter overall than the version
 * with the quicker reveal.
 */
private const val ART_INTRO_REVEAL_MS = 1300

/**
 * **The opening sequence: how long the finished logo stays in the middle of the
 * screen before the page arrives around it.**
 *
 * The app opens on nothing but the Glyph Matrix, centred, dark. Its cells light
 * one by one until they spell the launcher icon; it sits there a beat; then it
 * flies up to the header it lives in for the rest of the flow — **turning into the
 * key on the way** — while the title, the body, the page dots and the Back/Next
 * row fade in underneath it.
 *
 * ## Nothing here is a new animation
 *
 * The dot-by-dot assembly is [MatrixArt]'s own reveal, which has always run on
 * page entry; the sequence only stages *where* it happens, and slows it to
 * [ART_INTRO_REVEAL_MS] for the one pattern where being assembled is the point.
 * The trip home is `MaterialTheme.motionScheme.defaultSpatialSpec` — position, so
 * spatial, damped 0.8, so the disc overshoots its header slot by a hair and
 * settles back into it. The fades are `defaultEffectsSpec` — opacity, so effects,
 * damped 1.0, because a bouncing alpha flickers.
 *
 * ## The arithmetic, from launch
 *
 * | ms | what |
 * |---|---|
 * | 0 | disc centred, every cell unlit, page and chrome at alpha 0 |
 * | 0–1300 | [ART_INTRO_REVEAL_MS]: cells light in scattered order into [ART_ICON] |
 * | 1300–1600 | this constant: the finished logo, still, centred |
 * | 1600 | **the disc leaves for the header AND becomes [ART_KEY]** — one event |
 * | 1800 | [INTRO_STAGGER_MS] later, the page fades in (~170 ms) — **the user can act** |
 * | ~1950 | disc settled; the key finishes dissolving in at ~2400 |
 *
 * **The morph is not on a timer.** It used to wait out a fixed 1800 ms hold of its
 * own, which meant the change of art and the change of position agreed only by
 * arithmetic — and any edit to the reveal's length pulled them silently apart.
 * Now `OnboardingFlow` flips both on the same frame: the logo has said what the
 * app is, and the disc carries the first instruction up with it.
 *
 * That is also where the slower reveal is paid for. The old schedule reached its
 * settled state at 2600 ms; this one, with 500 ms more spent on the assembly,
 * reaches it at about 2400 — because the 700 ms that used to be dead hold between
 * the disc landing and the morph starting is gone.
 *
 * ## 300 ms, and not more
 *
 * The reveal's last cell lights at 28/29 of its duration — 1256 ms of
 * [ART_INTRO_REVEAL_MS] — so this is the pause *after* the logo is whole: long
 * enough to read as a held image rather than a waypoint, short enough that the
 * screen is never still for longer than it takes to notice it is still.
 * Everything before 1600 ms is skippable with a touch (see the scrim in
 * [OnboardingFlow]), so this is a ceiling on a wait, not a floor.
 *
 * Timings are unverified on hardware — this is pure animation and there is no
 * device here to watch it on. The two spring durations above are calculated from
 * the expressive scheme's tokens (spatial 0.8/380, effects 1.0/1600) against
 * Compose's 0.01 visibility threshold, not measured.
 */
private const val INTRO_HOLD_MS = 300L

/**
 * How long the art gets to itself after it starts moving, before the page fades
 * in behind it.
 *
 * Without it the two run together, and they should not: the disc's centred
 * position is *below* the title, so on the way up it crosses everything that is
 * appearing. The effects spring is also the quicker of the two (~170 ms against
 * ~350 ms), so a shared trigger has the text at full strength while a 220 dp
 * disc is still sliding through it.
 *
 * 200 ms puts the disc roughly two thirds of the way home before anything else
 * shows up, so the overlap left is brief and happens between two elements that
 * are both nearly where they belong. It also lines the two up to finish together
 * — travel settles at ~1450 ms, the fade completes at ~1470 — which is the point
 * of a stagger: not a queue, an offset.
 */
private const val INTRO_STAGGER_MS = 200L

private const val ART_KEY = """
..###............
.#...#...........
#.....#..........
#..#..###########
#.....#.....#..#.
.#...#......#..#.
..###............
"""

/**
 * **Four real toys, not four drawings of toys.**
 *
 * Every pattern below is a frame this app actually renders, lifted from the
 * golden files its screen tests compare against — `compass_25_north`,
 * `clock_25_1234_t2_ring`, `eyes_25_initial`, `dice_25_face5`. The page is
 * telling the user what selecting this app as their Glyph Toy will put on the
 * back of their phone, so showing anything hand-drawn would be showing them
 * something they will never see.
 *
 * They are the **25-wide** goldens because [MatrixArt] draws a 25x25 disc, so
 * these land at true scale and fill it the way they fill an arbok panel.
 *
 * One conversion was needed: a golden records four brightness levels (`#` full,
 * `+` mid, `.` dim, space off) and [MatrixArt] has only lit and unlit, so any
 * lit level becomes lit. That matters most for the Compass, whose bearing ticks
 * and cardinal marks are dim on the real panel and full strength here — its
 * silhouette is preserved, its shading is not.
 */

/** The Compass pointing north: the needle, the cardinal marks and the ring of
 * bearing ticks, exactly as `CompassScreen` draws them. */
private const val ART_TOY_COMPASS = """
............#............
.........................
.........................
............#............
....#.......#.......#....
............#............
............#............
............#............
............#............
............#............
............#............
............#............
#...........#...........#
............#............
............#............
............#............
............#............
............#............
.........................
.........................
....#...............#....
.........................
.........................
.........................
............#............
"""

/** The Clock in its ring theme — the minute ring closed around 12:34. */
private const val ART_TOY_CLOCK = """
............####.........
................##.......
..................##.....
....................#....
.....................#...
......................#..
......................#..
.......................#.
.......................#.
#.......................#
#....#..###...###.#.#...#
#...##....#.#...#.#.#...#
#....#..###....##.###...#
#....#..#...#...#...#...#
#...###.###...###...#...#
#.......................#
.#.....................#.
.#.....................#.
..#...................#..
..#...................#..
...#.................#...
....#...............#....
.....##...........##.....
.......##.......##.......
.........#######.........
"""

/** The Eyes toy, both open and looking straight ahead. */
private const val ART_TOY_EYES = """
.....###.........###.....
....#...#.......#...#....
...#.....#.....#.....#...
...#.....#.....#.....#...
..#.......#...#.......#..
..#..###..#...#..###..#..
..#..###..#...#..###..#..
..#..###..#...#..###..#..
..#.......#...#.......#..
...#.....#.....#.....#...
...#.....#.....#.....#...
....#...#.......#...#....
.....###.........###.....
"""

/** The Dice showing a five. A face with a centre pip reads as a die at a glance;
 * an even face is four blocks in the corners and could be anything. */
private const val ART_TOY_DICE = """
.....###.........###.....
.....###.........###.....
.....###.........###.....
.........................
.........................
.........................
...........###...........
...........###...........
...........###...........
.........................
.........................
.........................
.....###.........###.....
.....###.........###.....
.....###.........###.....
"""

private const val ART_LOCK = """
...######...
..#......#..
..#......#..
.##########.
.#........#.
.#........#.
.#...##...#.
.#...##...#.
.#....#...#.
.#....#...#.
.#........#.
.#........#.
.##########.
"""

/**
 * The key-mode switch, drawn with its knob at [centre].
 *
 * ## Why this is a generator and not two pictures
 *
 * The page asks the user to choose how the Essential Key behaves, and the
 * illustration is a switch — so the switch has to *throw*. Two patterns and
 * [MatrixArt]'s dissolve would have the knob vanish from one end and reappear at
 * the other, which reads as a cut, not as a movement. Eleven frames a column
 * apart, played back to back with the reveal switched off, read as a slide.
 *
 * The track is deliberately wider than the old static drawing: at 19 cells the
 * knob travels ten columns, which is a third of the disc, and a throw that small
 * on a 13-cell track was the reason this needed redrawing rather than animating.
 *
 * Only [TOGGLE_KNOB_OFF]..[TOGGLE_KNOB_ON] are ever asked for, and `ModePage`
 * builds all eleven once — so this runs at composition, never per frame.
 */
private fun toggleArt(centre: Int): String {
    val grid = TOGGLE_TRACK.map { it.toCharArray() }
    TOGGLE_KNOB.forEachIndexed { r, row ->
        row.forEachIndexed { c, ch ->
            val x = centre - TOGGLE_KNOB_RADIUS + c
            if (ch == '#' && x in grid[0].indices) grid[r + TOGGLE_KNOB_TOP][x] = '#'
        }
    }
    return grid.joinToString("\n") { String(it) }
}

/** The switch body: a 19x9 rounded track, drawn once and stamped into. */
private val TOGGLE_TRACK = listOf(
    "....###########....",
    "..##...........##..",
    ".#...............#.",
    "#.................#",
    "#.................#",
    "#.................#",
    ".#...............#.",
    "..##...........##..",
    "....###########....",
)

/** The knob: a 5x5 disc, the largest that clears the track's rounded ends. */
private val TOGGLE_KNOB = listOf(".###.", "#####", "#####", "#####", ".###.")

private const val TOGGLE_KNOB_RADIUS = 2
private const val TOGGLE_KNOB_TOP = 2

/** Knob column when the key keeps its stock behaviour. */
private const val TOGGLE_KNOB_OFF = 4

/** ...and when menu mode is on. Ten columns of travel. */
private const val TOGGLE_KNOB_ON = 14

/**
 * How long the switch rests at each end before throwing again.
 *
 * Long enough that the illustration is a still picture most of the time — the
 * page under it is a decision with two paragraphs to read, and a control
 * flicking back and forth continuously would compete with them. The throw
 * itself is the theme's spatial spring, so it lands with the same slight
 * overshoot every other moving thing in the app has.
 */
private const val TOGGLE_HOLD_MS = 1900L

// A pencil on the diagonal — the one page in this flow that is about MAKING
// something rather than about a setting. Two parallel strokes for the shaft so
// it reads as a tool and not as a bar, tapering into a tip at the bottom left.
/**
 * **Sparkles** — the ✨ composition: one large four-pointed star with two small
 * ones off its shoulder.
 *
 * ## Why this one is drawn and the others are traced
 *
 * Material's `AutoAwesome` is exactly this shape and is all straight lines, so
 * tracing it should have been easy. It is not: a four-pointed star's arms taper
 * to a single cell, and where a tip falls between two sample points it lands on
 * one side or neither. Rasterising the real path gave a star whose top arm was
 * a cell shorter than its bottom and whose left arm outran its right — faithful
 * to the glyph and visibly crooked at seventeen cells.
 *
 * **Symmetry beats fidelity here.** A star is judged by whether its arms match,
 * and nothing else; a viewer cannot compare it to Material's original but can
 * see instantly that one arm is longer. So this is laid out by hand: the large
 * star is mirrored about row 8 and column 6 exactly, and both small stars are
 * the same five-cell figure. The proportions still come from the glyph — one
 * large star, two small ones to its right, the small pair set outside the big
 * star's reach.
 *
 * Sixteen rows on the 25-wide grid [MatrixArt] centres it in; the furthest lit
 * cell sits 10.0 of the disc's 12.3 cell radius from the middle, so every arm
 * clears the edge.
 */
private const val ART_DRAW = """
..............#..
.............###.
......#.....#####
......#......###.
.....###......#..
.....###.........
....#####........
..#########......
#############....
..#########......
....#####........
.....###......#..
.....###.....###.
......#.....#####
......#......###.
..............#..
"""

/**
 * How long each design in [CreatePage]'s cycle holds before the next dissolves
 * in. Comfortably longer than [MatrixArt]'s own 800 ms reveal, so each design is
 * fully lit and still for a beat before it goes.
 */
private const val ART_CYCLE_HOLD_MS = 2600L

/**
 * A flame — Material's `LocalFireDepartment`, rasterised at **21 rows**.
 *
 * ## The size is the whole trick
 *
 * Drawn by hand at thirteen cells this read as a leaf. Tracing at thirteen was
 * no better: what makes either of Material's flame glyphs read as *fire* is the
 * **inner flame** cut out of the middle, and at that size the counter either
 * swallows the shape or disappears, leaving a lozenge. `Whatshot` and
 * `LocalFireDepartment` collapse identically.
 *
 * The fix was room, not redrawing. A flame is tall and narrow, and the disc is
 * far more generous to that than to a square: a 21x21 *block* would not fit
 * (its corners fall outside), but this figure's corners are empty, so its
 * furthest lit cell sits 10.8 of the disc's 12.3 cell radius from the middle and
 * every one of them clears. At 21 rows the inner flame survives and the thing
 * reads as fire at a glance.
 *
 * Traced verbatim, with no tidying: it is symmetric where the glyph is symmetric
 * and leans where the glyph leans, and hand-correcting either half would only
 * make it somebody else's flame.
 */
private const val ART_FLAME = """
..........#..........
........###..........
.......####..........
......#####..........
.....######..........
....#######...##.....
...##############....
...###############...
..#################..
..########.########..
.########...########.
.#######.....#######.
.######.......######.
.#####....#....#####.
.#####...###...#####.
..###...#####...###..
..###..#######..###..
..###..#######..###..
...##..#######..##...
....#...#####...#....
.........###.........
"""

/**
 * "hi!" — lower case, and drawn on the same metrics `MarqueeFont` uses.
 *
 * Two-cell strokes rather than one, because a single-cell stroke on this grid is
 * a hairline next to [ART_DRAW]'s arms and [ART_FLAME]'s mass, and the three
 * designs are seen in sequence where a change of weight reads as a mistake.
 *
 * Lower case is what makes it a *word* rather than a label: capitals on a
 * thirteen-row grid are six identical-height bars, while an ascender, a dot and
 * a bang give three different silhouettes. So the `h` runs the full height, the
 * `i` starts at the x-height six rows down with its dot floating above, and the
 * `!` takes the ascender line and drops its point to the baseline. The empty
 * band across the middle of the top third is not a gap to be filled — it is the
 * x-height, and it is what says these are lower-case letters.
 */
private const val ART_HI = """
##..........##
##..........##
##......##..##
##......##..##
##..........##
##..........##
######..##..##
##..##..##..##
##..##..##..##
##..##..##....
##..##..##....
##..##..##..##
##..##..##..##
"""

private const val ART_SMILE = """
....#######....
..##.......##..
.#...........#.
.#...........#.
#...##...##...#
#...##...##...#
#.............#
#.............#
#..#.......#..#
#...#.....#...#
#....#####....#
.#...........#.
.#...........#.
..##.......##..
....#######....
"""
