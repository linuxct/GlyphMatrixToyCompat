package space.linuxct.glyphmatrixtoycompat.ui

import android.Manifest
import android.app.AlarmManager
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
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import space.linuxct.glyphmatrixtoycompat.Core
import space.linuxct.glyphmatrixtoycompat.R
import space.linuxct.glyphmatrixtoycompat.core.DebugLog
import space.linuxct.glyphmatrixtoycompat.core.PrefKeys
import space.linuxct.glyphmatrixtoycompat.core.SessionArbiter
import space.linuxct.glyphmatrixtoycompat.ui.theme.GmtcTheme
import space.linuxct.glyphmatrixtoycompat.ui.theme.NavPillColors
import space.linuxct.glyphmatrixtoycompat.ui.theme.navPill
import space.linuxct.glyphmatrixtoycompat.update.UpdateChecker
import space.linuxct.glyphmatrixtoycompat.update.UpdateCheckWorker
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Core.init(this)
        // Ask the panel for its top refresh rate while we are on screen. Before
        // the device gate on purpose: the unsupported-device screen is still our
        // UI, and the call is a no-op on any panel with nothing faster to offer.
        requestPeakRefreshRateWhileVisible()
        // Hard gate: the app is only meant for Nothing phones with a Glyph
        // Matrix (Phone (3) / (4a) Pro). uses-feature only filters store
        // installs, so sideloads on other devices land here and dead-end.
        if (!isNothingGlyphDevice(this)) {
            enableEdgeToEdge()
            setContent {
                GmtcTheme {
                    UnsupportedDeviceScreen()
                }
            }
            return
        }
        // Debug/replay hook — OnboardingActivity itself is not exported, so:
        // adb shell am start -n space.linuxct.glyphmatrixtoycompat/.ui.MainActivity --ez restart_onboarding true
        if (intent?.getBooleanExtra(EXTRA_RESTART_ONBOARDING, false) == true) {
            Core.prefs.putBoolean(PrefKeys.ONBOARDING_DONE, false)
        }
        if (!Core.prefs.getBoolean(PrefKeys.ONBOARDING_DONE, PrefKeys.ONBOARDING_DONE_DEF)) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }
        // Safe here (never reached in Direct Boot); keeps the daily
        // background release check alive.
        UpdateCheckWorker.schedule(this)
        enableEdgeToEdge()
        setContent {
            GmtcTheme {
                MainScreen()
            }
        }
    }

    companion object {
        const val EXTRA_RESTART_ONBOARDING = "restart_onboarding"
    }
}

private val DISPLAY_NAMES = mapOf(
    "ambient" to R.string.screen_ambient,
    "clock" to R.string.screen_clock,
    "eyes" to R.string.screen_eyes,
    "speed" to R.string.screen_speed,
    "battery" to R.string.screen_battery,
    "solar" to R.string.screen_solar,
    "moon" to R.string.screen_moon,
    "dice" to R.string.screen_dice,
    "coin" to R.string.screen_coin,
    "dino" to R.string.screen_dino,
    "bottle" to R.string.screen_bottle,
    "rps" to R.string.screen_rps,
    "counter" to R.string.screen_counter,
    "breathing" to R.string.screen_breathing,
    "timer" to R.string.screen_timer,
    "compass" to R.string.screen_compass,
    "level" to R.string.screen_level,
    "visualizer" to R.string.screen_visualizer,
)

private val CONFIGURABLE =
    setOf("ambient", "clock", "dice", "coin", "battery", "breathing", "timer", "visualizer")

private fun loadOrder(): List<String> {
    val stored = Core.prefs.getString(PrefKeys.SCREEN_ORDER, PrefKeys.SCREEN_ORDER_DEF)
        .split(',').map { it.trim() }.filter { it.isNotEmpty() && DISPLAY_NAMES.containsKey(it) }
    return stored + DISPLAY_NAMES.keys.filter { it !in stored }
}

// ---------- tabs + floating navigation ----------

/**
 * Breathing room every tab body adds *below* the bottom inset it is handed, so
 * scrolled-to-the-end content never sits flush under the floating pill. This is
 * pure slack — [NavOverlayPadding] already covers the whole pill, its margin and
 * the navigation-bar inset — so it can never be too small to prevent overlap,
 * only too mean or too generous to look right.
 */
private val NAV_PILL_CLEARANCE = 40.dp

/**
 * Gap between the pill's bottom edge and the top of the navigation-bar inset.
 *
 * Named because it is now load-bearing in two places that must agree: the pill's
 * own bottom padding, and the bottom inset [NavOverlayPadding] hands the pages
 * so their content can scroll clear of it. See that class for the arithmetic.
 */
private val NAV_PILL_MARGIN = 14.dp

/**
 * Gap between the pill's edge and its chips — **uniform on all four sides**.
 *
 * The pill and every chip are stadiums ([NAV_CHIP_SHAPE] resolves to
 * `minDimension / 2`, and every chip is at least as wide as it is tall), so for
 * a chip of height `h` inside a uniform padding `p`:
 *
 *     chipRadius = h / 2      pillRadius = (h + 2p) / 2 = chipRadius + p
 *
 * Concentric stadiums only *look* evenly inset when `chipRadius + gap ==
 * pillRadius`. Here the chips are 48 dp tall (see [NavChip]) → chipRadius 24,
 * pill 48 + 2×6 = 60 → pillRadius 30 = 24 + 6. ✓
 *
 * The identity holds for ANY chip height, so it survives large font scales —
 * but only while this stays a single all-sides value. A split padding (it used
 * to be horizontal = 10, vertical = 6) breaks it, and that is exactly what made
 * the selected chip look off-centre.
 */
private val NAV_PILL_GAP = 6.dp

/** Stadium: radius = half the shorter side, for both the pill and its chips. */
private val NAV_CHIP_SHAPE = RoundedCornerShape(percent = 50)

/**
 * The Scaffold's own [PaddingValues] with the floating nav pill added back onto
 * the bottom edge.
 *
 * ## Why this exists
 *
 * The pill used to live in the Scaffold's `bottomBar` slot, and Scaffold sets
 * `contentPadding.bottom = bottomBarHeight` — the slot's whole measured height.
 * That was the wrong place for it: the pill's width tracks the pager offset (see
 * [NavChip]), so it re-measured on every drag frame, and a `bottomBar` re-layout
 * marks Scaffold's `SubcomposeLayout` measure-pending, which re-runs its measure
 * policy, which **re-subcomposes the whole body** — `HorizontalPager` and every
 * resident page — from inside the layout pass. That was the swipe stutter. The
 * pill is now a sibling overlay, where its re-layout cannot reach the Scaffold.
 *
 * ## The arithmetic
 *
 * With no `bottomBar`, Scaffold falls through to
 * `contentWindowInsets.calculateBottomPadding()` — `systemBars ∪ displayCutout`,
 * whose bottom edge is the navigation-bar inset. The pill sits above that:
 *
 *     old bottom = height of the whole bottomBar slot
 *                = navBarInset + pillHeight + NAV_PILL_MARGIN
 *     new bottom = base.bottom + extraBottom()
 *                = navBarInset + (pillHeight + NAV_PILL_MARGIN)
 *
 * Identical, and the navigation-bar inset is counted exactly once: [base] is the
 * only thing that carries it, while `pillHeight` is measured on the pill's
 * `Surface` — *inside* its `navigationBarsPadding()`, so it is the capsule alone.
 *
 * ## Why the reads are deferred
 *
 * Scaffold hands out ONE `PaddingValues` instance and mutates it during its
 * measure pass, so snapshotting the values at construction would freeze the top
 * padding and break the collapsing app bar. Every call is forwarded instead,
 * which leaves each read in whichever page body actually asks — exactly where it
 * happened before. [extraBottom] is a lambda for the same reason: the pill's
 * height arrives a frame late (via `onSizeChanged`), and only the pages that read
 * the bottom edge should invalidate when it does.
 */
private class NavOverlayPadding(
    private val base: PaddingValues,
    private val extraBottom: () -> Dp,
) : PaddingValues {
    override fun calculateLeftPadding(layoutDirection: LayoutDirection): Dp =
        base.calculateLeftPadding(layoutDirection)

    override fun calculateTopPadding(): Dp = base.calculateTopPadding()

    override fun calculateRightPadding(layoutDirection: LayoutDirection): Dp =
        base.calculateRightPadding(layoutDirection)

    override fun calculateBottomPadding(): Dp = base.calculateBottomPadding() + extraBottom()
}

/**
 * The three pages. [caption] is the short label beside the nav-pill icon;
 * [title] is the (longer) page title in the app bar — they stay separate
 * because e.g. "Toys" heads the "Glyph Toys" page. Where the two are the same
 * word, one string serves both.
 */
private enum class Tab(val icon: ImageVector, val caption: Int, val title: Int) {
    TOYS(Icons.Default.Casino, R.string.nav_toys, R.string.screens_title),
    SETTINGS(Icons.Default.Settings, R.string.nav_settings, R.string.settings),
    TUTORIAL(Icons.Default.School, R.string.tut_section, R.string.tut_section),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen() {
    // The pager IS the selection: there is no separate `tab` index any more, so
    // there is nothing that can disagree with the scroll position mid-drag.
    // rememberPagerState is itself rememberSaveable-backed (a listSaver over
    // page + offset + count), so the selected tab still survives rotation and
    // process death exactly as the old rememberSaveable Int did.
    val pagerState = rememberPagerState(pageCount = { Tab.entries.size })
    val scope = rememberCoroutineScope()
    // Tapping a nav chip scrolls the pager instead of assigning an index —
    // scrolling is POSITION, hence spatial. The chip then follows the pager for
    // free, which is what keeps a TAP animated now that the chip's own springs
    // are gone (see [NavChip]).
    val pageSpec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    // The SAME spring settles a released swipe. Spelled out because foundation's
    // own default here is a hardcoded `spring(StiffnessMediumLow)` at damping
    // 1.0 — it is the one animation in this screen that MaterialTheme cannot
    // reach on its own, and leaving it would mean a page (and with it the nav
    // chip, which is now driven straight off the page offset) settling on a
    // never-overshooting foundation spring while everything else in the app
    // lands on MD3's under-damped expressive one.
    val fling = PagerDefaults.flingBehavior(state = pagerState, snapAnimationSpec = pageSpec)

    // Per-tab scroll state, hoisted OUT of the tab bodies. A pager only keeps
    // the pages inside its viewport window composed, so a tab-local
    // rememberScrollState/rememberLazyListState is destroyed the moment its
    // page scrolls out of range and the tab would silently jump back to the
    // top. Both factories are rememberSaveable-backed, so hoisting them here
    // also carries each tab's scroll position through a rotation.
    val toysListState = rememberLazyListState()
    val settingsScrollState = rememberScrollState()
    val tutorialScrollState = rememberScrollState()

    // The header's settle after a partial scroll. Spelled out rather than left
    // to the parameter default (which now resolves to the very same expressive
    // effects spring through the theme) so the choice is on the record:
    // EFFECTS, not spatial, even though a collapsing header is geometry. An
    // under-damped spatial spring would overshoot the collapsed height and
    // spring back, which on a full-width header reads as the app bar
    // bouncing off the status bar rather than snapping into place — this is
    // also why material3's own default for it is DefaultEffects.
    val headerSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(
        snapAnimationSpec = headerSpec,
    )

    // One collapsing app bar is SHARED by all three pages, so its collapse is
    // whatever the last-scrolled tab left behind: land on a tab that is sitting
    // at its top with a collapsed header and the page has a stub of a title
    // over a gap it cannot scroll away. Fix it where it happens — when the
    // pager settles on a page whose content is already at the top, expand the
    // header back. Deliberately keyed on settledPage, not currentPage: doing
    // this mid-drag would fight the finger.
    LaunchedEffect(pagerState, scrollBehavior) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            val atTop = when (Tab.entries[page]) {
                Tab.TOYS ->
                    toysListState.firstVisibleItemIndex == 0 &&
                        toysListState.firstVisibleItemScrollOffset == 0
                Tab.SETTINGS -> settingsScrollState.value == 0
                Tab.TUTORIAL -> tutorialScrollState.value == 0
            }
            if (atTop && scrollBehavior.state.heightOffset != 0f) {
                animate(
                    initialValue = scrollBehavior.state.heightOffset,
                    targetValue = 0f,
                    animationSpec = headerSpec,
                ) { value, _ -> scrollBehavior.state.heightOffset = value }
                // Same reset material3 does itself once a fling reaches the top:
                // contentOffset only feeds overlappedFraction, and leaving a
                // stale one behind makes the header think content is still
                // tucked under it.
                scrollBehavior.state.contentOffset = 0f
            }
        }
    }

    // The pill's own height, measured once (see [NavOverlayPadding]).
    //
    // Its WIDTH changes every drag frame — that is the whole point of [NavChip]
    // — but its HEIGHT cannot: the chip Row is `padding(12.dp)` around a
    // fixed-size Icon and a label Box whose `layout { }` shrinks only the
    // reported WIDTH and passes `placeable.height` straight through, so nothing
    // in the drag path touches the vertical axis. It changes on a font-scale or
    // configuration change and at no other time, which is why measuring it is
    // not a per-frame dependency in disguise.
    var pillHeight by remember { mutableStateOf(0.dp) }

    // The pill is a SIBLING of the Scaffold, not its `bottomBar` — see
    // [NavOverlayPadding] for the defect that put it here and for how the bottom
    // inset it used to provide is given back to the pages.
    Box(Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            // The whole page — body AND app-bar header — sits on the page
            // background (light gray / pure black). The app bar is SOLID in that
            // same colour (not transparent, or content scrolls visibly under the
            // collapsed header) so it stays opaque yet seamless with the body.
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                LargeTopAppBar(
                    title = {
                        // The header follows the pager, and its title CROSSFADES
                        // rather than sliding with the body.
                        //
                        // The app bar is a PERSISTENT container: it is not part of
                        // the surface that moves on the shared axis, it is the frame
                        // that surface slides underneath. Sliding its title too
                        // would claim it belongs to the page (it does not — it is
                        // one bar serving three), and it would have to slide inside
                        // a fixed, clipped slot whose own text is already animating
                        // between the expanded and collapsed type scales. Fading
                        // content in place is how a persistent container swaps what
                        // it is labelling, and a cut is the alternative — the one
                        // thing in the frame that would visibly jump.
                        //
                        // Driven by currentPage, so the swap happens as the drag
                        // crosses the halfway point and the incoming page owns most
                        // of the screen. Alpha is an effect → the effects spring,
                        // which never bounces.
                        Crossfade(
                            targetState = Tab.entries[pagerState.currentPage].title,
                            animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
                            label = "appBarTitle",
                        ) { title -> Text(stringResource(title)) }
                    },
                    scrollBehavior = scrollBehavior,
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        scrolledContainerColor = MaterialTheme.colorScheme.background,
                    ),
                )
            },
        ) { innerPadding ->
            // Scaffold's inset plus the pill that no longer contributes to it. Keyed
            // on `innerPadding`, which is one stable instance for the Scaffold's
            // lifetime, so this is remembered once; both of its inputs are read
            // lazily inside the pages. See [NavOverlayPadding].
            val pagePadding = remember(innerPadding) {
                NavOverlayPadding(innerPadding) { pillHeight + NAV_PILL_MARGIN }
            }
            // MD3 SHARED AXIS (X): the three tabs are swipe-navigable, so they have
            // a real spatial relationship — left of / right of each other — and the
            // transition has to be a literal horizontal slide that says so. This is
            // NOT a fade-through: that pattern is for peers with no spatial
            // relationship (a bottom bar you can only tap), and applying it here
            // stacked both pages in place and cross-faded them, which reads as one
            // screen "appearing" over another rather than moving aside.
            //
            // So there is deliberately no graphicsLayer on the pages at all.
            // HorizontalPager already lays page p out at (p - scrollPosition) *
            // width and slides it; the previous `translationX = size.width *
            // pageOffset` was the exact inverse of that placement and was undoing
            // the very motion we want. Both input paths get the slide for free: a
            // drag moves the pages with the finger, and a nav-chip tap runs
            // animateScrollToPage, which slides them on the expressive spatial
            // spring (see `fling` / `pageSpec` above).
            //
            // No alpha and no scale either. An alpha of 1 - |pageOffset| puts both
            // pages at half opacity across the middle of every swipe, which is the
            // ghosting that made this read as "appearing" in the first place; and a
            // uniform scale shrinks each page away from the edge it shares with its
            // neighbour, opening a strip of bare background between them mid-drag
            // (plus letterboxing above and below from scaleY). A plain slide beats
            // both.
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                flingBehavior = fling,
                // Keeps ONE page composed either side of the viewport, and this is
                // what makes tab switching symmetric.
                //
                // The pager's own cache window is deliberately one-directional:
                // PagerState installs a LazyLayoutCacheWindow whose ahead window is
                // a full page and whose BEHIND window is 0, where "ahead" means the
                // direction of travel. So the page you are moving towards is
                // pre-composed and pre-measured, while the page you just left is
                // disposed immediately — the two directions do genuinely different
                // things to the destination, which is exactly the shape of "scroll
                // resets one way but not the other".
                //
                // Hoisting the scroll states (above) was necessary but not
                // sufficient: a hoisted ScrollState still loses its position if any
                // measure pass reports a smaller extent, because ScrollState.maxValue
                // clamps `value` down to the new maximum on assignment. A page torn
                // down and rebuilt is a chance for that to happen; a page that is
                // never torn down is not.
                //
                // beyondViewportPageCount is applied SYMMETRICALLY by PagerMeasure
                // (currentFirstPage - n before, currentLastPage + n after), so at 1
                // every tab adjacent to the visible one is always composed, laid out
                // and scroll-stable, in both directions. Three light pages: cheap.
                beyondViewportPageCount = 1,
                // NO stretch overscroll on the pager. Compose implements stretch
                // by rendering the whole scrollable into an OFFSCREEN layer sized
                // to the content plus the stretch margin, applying a RenderEffect
                // and compositing it back — a second full render pass. Measured
                // on-device it was a 1426 x 2800 layer (the panel is 1260 wide;
                // the pager is fillMaxSize, its padding applied inside the pages)
                // drawn on ~half of all frames, and it was the single largest
                // RenderThread cost once the Scaffold re-subcompose was fixed:
                // `flush layers` plus a second `QueueSubmit` every frame.
                //
                // Only the PAGER loses it. The vertical scrollers inside each page
                // keep their own overscroll — theirs only builds a layer while you
                // are actually pulling past an end, and it is the affordance that
                // tells you a list has bottomed out. The pager still clamps at the
                // first and last tab; it just no longer rubber-bands there.
                overscrollEffect = null,
            ) { page ->
                when (Tab.entries[page]) {
                    Tab.TOYS -> ToysTab(pagePadding, toysListState)
                    Tab.SETTINGS -> SettingsTab(pagePadding, settingsScrollState)
                    Tab.TUTORIAL -> TutorialTab(pagePadding, tutorialScrollState)
                }
            }
        }
        FloatingNavBar(
            // The ONLY pager read in this file's composition, and it is a
            // DISCRETE one: targetPage is a derivedStateOf that names the page
            // the pager is committed to, so it changes at most a couple of times
            // per swipe and invalidates only this call.
            // (derivedStateOf re-evaluates against currentPageOffsetFraction
            // internally but notifies readers only when the Int itself changes,
            // so this does not subscribe the bar to the offset.)
            // It exists to drive the chip TINT, which is a discrete selection
            // change, not a movement.
            selected = pagerState.targetPage,
            // A LAMBDA for the continuous part, and one that is never invoked
            // during composition — every call site is a layout or draw lambda
            // inside [NavChip]. See that function's KDoc.
            position = { pagerState.currentPage + pagerState.currentPageOffsetFraction },
            onSelect = { i ->
                scope.launch { pagerState.animateScrollToPage(i, animationSpec = pageSpec) }
            },
            onPillHeight = { pillHeight = it },
            // Bottom of the overlay Box, i.e. exactly where Scaffold used to
            // place the bottomBar slot (`layoutHeight - bottomBarHeight`, full
            // width). Same pixels, different parent.
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/**
 * MD3-style floating pill navigation: a raised, centred capsule of one [NavChip]
 * per tab.
 *
 * Colours come from the theme's [NavPillColors] rather than the M3 `inverse*`
 * roles — those stay reserved for the tutorial's numbered-step bubbles, and
 * would make this pill a near-white slab in dark mode.
 *
 * This is an OVERLAY, a sibling of the Scaffold rather than its `bottomBar`, so
 * that its per-frame re-layout can never re-enter the Scaffold's subcomposition
 * — see [NavOverlayPadding]. [onPillHeight] reports the capsule's height back so
 * the pages can still be padded clear of it; it fires on measure, so it must
 * only ever be handed a setter that is cheap and idempotent.
 */
@Composable
private fun FloatingNavBar(
    selected: Int,
    position: () -> Float,
    onSelect: (Int) -> Unit,
    onPillHeight: (Dp) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pill = MaterialTheme.navPill
    val density = LocalDensity.current
    Box(
        modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = NAV_PILL_MARGIN),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            // Measured INSIDE the navigationBarsPadding above, so this is the
            // capsule alone — the one thing the Scaffold's own inset no longer
            // accounts for. Putting it on the outer Box instead would fold the
            // navigation-bar inset in and double-count it against the Scaffold's.
            // Only the height is taken: the width changes every drag frame and is
            // exactly what must not escape into composition.
            modifier = Modifier.onSizeChanged {
                onPillHeight(with(density) { it.height.toDp() })
            },
            // Percent radius, not a fixed dp: the pill's height follows the
            // user's font scale, and this keeps it a true capsule at any of
            // them (see [NAV_PILL_GAP] for why that matters).
            //
            // The pill wraps its content, so it now re-measures on every frame
            // of a drag as the chips' widths change. It stays CENTRED because
            // the Box above centres it, and it can never degenerate out of a
            // capsule: three chips are at least 3 × 48 dp wide against a 60 dp
            // height, so `percent = 50` always resolves on the height.
            shape = NAV_CHIP_SHAPE,
            color = pill.container,
            // Pinned, and it must be: [NavPillColors.container] is a raw literal
            // rather than a colour-scheme role, so `contentColorFor` cannot
            // resolve it and Surface falls through to `LocalContentColor`. That
            // used to be `onBackground`, inherited from the Surface Scaffold
            // wraps everything in; out here as a sibling it would be
            // LocalContentColor's own default, Color.Black. Nothing in the pill
            // draws with it — [NavChip] tints its icon and label explicitly —
            // and it is what a ripple with no configured colour would resolve
            // to. The chips no longer draw one (see [NoRipple] on the Row
            // below), so nothing depends on this today — but it is a one-word
            // change away from mattering again, and Color.Black on a near-black
            // pill is a silent failure, so the value stays stated rather than
            // inherited.
            contentColor = MaterialTheme.colorScheme.onBackground,
            shadowElevation = 8.dp,
        ) {
            // No ripple on the chips: the container fill and the label growing
            // out of the icon already track the gesture continuously, which is
            // far more feedback than a tap ripple carries. See [NoRipple].
            NoRipple {
                Row(
                    // Uniform, all four sides — see [NAV_PILL_GAP] before changing.
                    Modifier.padding(NAV_PILL_GAP),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Tab.entries.forEachIndexed { i, t ->
                        NavChip(
                            tab = t,
                            index = i,
                            selected = i == selected,
                            position = position,
                        ) { onSelect(i) }
                    }
                }
            }
        }
    }
}

/**
 * One tab chip: its icon, with the caption beside it — revealed in proportion
 * to how selected the chip is.
 *
 * ## Compose phases: [selectedness] must never be read during composition
 *
 * "How selected" is a FRACTION, not a flag: 1 when the pager is settled on this
 * chip's page, 0 once a whole page away, and everything in between while a
 * finger is dragging. It comes from [position], which reads
 * `currentPage + currentPageOffsetFraction` — snapshot state that mutates on
 * EVERY FRAME of a drag and of the settle animation.
 *
 * So [selectedness] is a function, not a value, and it is called only from
 * layout and draw lambdas:
 *
 * | property        | modifier                | phase  |
 * |-----------------|-------------------------|--------|
 * | container fill  | `drawBehind`            | draw   |
 * | label alpha     | `graphicsLayer { }`     | draw   |
 * | label width     | `layout { }`            | layout |
 *
 * Calling it in the composable body instead — which is what an earlier version
 * did, even with [position] passed as a lambda — subscribes the chip's
 * RECOMPOSE SCOPE to the pager's offset. Passing a lambda changes where the
 * read happens, not which phase, and the result was a full recomposition of all
 * three chips every frame (new `Color`, new background/alpha modifier instances,
 * new lambdas, re-run `stringResource`) on top of the relayout. That was the
 * stutter. The rule is the standard one: defer state reads to the latest phase
 * that needs them.
 *
 * The tint is the deliberate exception and does NOT track the drag: it animates
 * off the discrete [selected] flag on the effects spring, exactly as MD3's own
 * navigation items do. The user's requirement is about MOVEMENT ("as much as I
 * have dragged"), and movement is the three rows above; a colour that
 * interpolated per frame would only be re-tinting text nobody can read mid-swipe
 * at the cost of a recomposition per frame.
 *
 * Height 12 + 24 + 12 = **48 dp** → chip radius 24, pill 48 + 2×6 = 60 → pill
 * radius 30 = 24 + [NAV_PILL_GAP]. Concentric. At `selectedness` 0 the chip is
 * 12 + 24 + 12 = 48 dp wide too — a 48 × 48 target, and a perfect circle. It
 * only ever grows from there, so the touch target stays ≥ 48 dp and the stadium
 * radius stays 24 at every point of the drag: the shape never wobbles.
 */
@Composable
private fun NavChip(
    tab: Tab,
    index: Int,
    selected: Boolean,
    position: () -> Float,
    onClick: () -> Unit,
) {
    val pill = MaterialTheme.navPill
    // Discrete: [selected] changes at most twice per swipe, so this recomposes
    // the chip only around a selection change — never per drag frame.
    val tint by animateColorAsState(
        targetValue = if (selected) pill.selectedContent else pill.content,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "navChipTint",
    )
    val fill = pill.selectedContainer
    // NOT `val`. Calling this from the composable body would re-introduce the
    // per-frame recomposition described above — it is invoked only from the
    // draw and layout lambdas below.
    fun selectedness(): Float = (1f - abs(position() - index)).coerceIn(0f, 1f)
    Row(
        Modifier
            .clip(NAV_CHIP_SHAPE)
            // DRAW phase. Painted rather than set as a `background(...)` colour
            // so the per-frame value never reaches composition. Drawing the
            // opaque fill at `alpha = selectedness` is exactly `copy(alpha =)`
            // — it never interpolates towards Color.Transparent, which is
            // transparent *black* and would drag a light chip through grey.
            // The radius matches NAV_CHIP_SHAPE: half the shorter side.
            .drawBehind {
                drawRoundRect(
                    color = fill,
                    alpha = selectedness(),
                    cornerRadius = CornerRadius(size.minDimension / 2f),
                )
            }
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The icon always carries the name: the label below is present in the
        // layout even at zero width, and semantics do not care about width, so
        // it is cleared to stop TalkBack reading every chip's caption twice.
        Icon(tab.icon, contentDescription = stringResource(tab.caption), tint = tint)
        Box(
            Modifier
                .clearAndSetSemantics {}
                // DRAW phase — the layer block re-runs without recomposing.
                .graphicsLayer { alpha = selectedness() }
                // Clips at the width the layout below reports, so the label is
                // wiped in from the left instead of spilling out of the chip.
                .clipToBounds()
                // LAYOUT phase. The label is measured at its full width and then
                // reported at a fraction of it, which is what grows the chip —
                // and with it the Row and the wrap-content pill — continuously
                // as the finger moves. Placed at x = 0 so the reveal starts at
                // the icon. `measure` is passed the constraints unchanged, so
                // the Text itself re-measures only when it actually changes.
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    val width = (placeable.width * selectedness())
                        .roundToInt()
                        .coerceIn(0, placeable.width)
                    layout(width, placeable.height) { placeable.place(0, 0) }
                },
        ) {
            Text(
                stringResource(tab.caption),
                // Inside the clipped box, so the gap grows with the label
                // rather than opening up before it.
                modifier = Modifier.padding(start = 8.dp),
                style = MaterialTheme.typography.titleSmall,
                color = tint,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

// ---------- Glyph Toys tab ----------

@Composable
private fun ToysTab(innerPadding: PaddingValues, listState: LazyListState) {
    var dialogId by remember { mutableStateOf<String?>(null) }

    // The toy currently on the matrix: tracks the persisted current screen
    // live (cycled from the Essential Key outside this UI); the pref change
    // listener fires on the main thread.
    //
    // Both effects below can now run while this page is composed but OFF SCREEN:
    // the pager keeps a window of pages alive (the neighbour during a drag, and
    // the next page as a prefetch) rather than only the visible one. Both are
    // safe that way. There is still exactly ONE listener — a pager composes any
    // given page index at most once, so this cannot double-register — and all it
    // does is re-read a pref into local state, so an off-screen tab arrives
    // already up to date instead of catching up on its first frame. The
    // symmetric case, being DISPOSED when it falls out of that window, is why
    // the list's scroll position is hoisted into [MainScreen] instead of
    // remembered here.
    var currentToy by remember {
        mutableStateOf(Core.prefs.getString(PrefKeys.CURRENT_SCREEN, PrefKeys.CURRENT_SCREEN_DEF))
    }
    DisposableEffect(Unit) {
        val listener: (String) -> Unit = { key ->
            if (key == PrefKeys.CURRENT_SCREEN) {
                currentToy = Core.prefs.getString(PrefKeys.CURRENT_SCREEN, PrefKeys.CURRENT_SCREEN_DEF)
            }
        }
        Core.prefs.addChangeListener(listener)
        onDispose { Core.prefs.removeChangeListener(listener) }
    }
    LifecycleResumeEffect(Unit) {
        currentToy = Core.prefs.getString(PrefKeys.CURRENT_SCREEN, PrefKeys.CURRENT_SCREEN_DEF)
        onPauseOrDispose { }
    }

    // Play sets the toy as the currently active one: persist it and switch
    // the live session to it immediately. The pref-change listener above then
    // moves the highlight. If capture is off (no session), it still persists
    // and shows the next time a session runs.
    fun selectToy(id: String) {
        DebugLog.i("Ui", "set active toy '$id'")
        Core.arbiter.revive()
        Core.scheduler.run { Core.screenManager.selectScreen(id) }
    }

    val order = remember { mutableStateListOf<String>().apply { addAll(loadOrder()) } }
    fun persistOrder() = Core.prefs.putString(PrefKeys.SCREEN_ORDER, order.joinToString(","))
    val drag = remember { DragState() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        // Hoisted by [MainScreen] so this tab's scroll position outlives the
        // page being disposed off-screen.
        state = listState,
        // Extra breathing room below the last row (on top of the floating
        // nav) so the list never sits flush against the pill — widened for the
        // taller captioned pill.
        contentPadding = PaddingValues(
            top = innerPadding.calculateTopPadding(),
            bottom = innerPadding.calculateBottomPadding() + NAV_PILL_CLEARANCE,
        ),
    ) {
        item { HintText(stringResource(R.string.screens_reorder_hint)) }

        itemsIndexed(order, key = { _, id -> id }) { index, id ->
            DisplayRow(
                id = id,
                index = index,
                drag = drag,
                order = order,
                // The toy currently active on the matrix.
                shown = currentToy == id,
                // Displaced neighbours slide to their new slot; the dragged
                // row itself is positioned manually, so it must not fight
                // the placement animation.
                //
                // animateItem()'s own defaults are foundation's, not MD3's
                // (spring(StiffnessMediumLow), damping 1.0 — it cannot
                // overshoot), so the specs are passed explicitly: the slide to
                // the new slot is a POSITION change → spatial, while the
                // fades are alpha → effects, which must never bounce.
                placement = if (drag.draggingIndex == index) {
                    Modifier
                } else {
                    Modifier.animateItem(
                        fadeInSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
                        placementSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
                        fadeOutSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
                    )
                },
                onPersist = ::persistOrder,
                onSelect = { selectToy(id) },
                onSettings = { dialogId = id },
            )
        }
    }

    dialogId?.let { id ->
        ScreenSettingsDialog(id = id, onDismiss = { dialogId = null })
    }
}

// ---------- Settings tab (first-time setup + app settings) ----------

@Composable
private fun SettingsTab(innerPadding: PaddingValues, scrollState: ScrollState) {
    val context = LocalContext.current
    var refreshTick by remember { mutableIntStateOf(0) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { refreshTick++ }
    // Re-probe system state whenever the user returns from system Settings.
    //
    // This can now fire while the page is composed but not visible — it is the
    // pager's middle page, so it is in the live window from either neighbour.
    // That is the right trade rather than a leak: the probes are a handful of
    // synchronous permission/service checks, and running them before the page
    // is on screen means the checklist is already correct the instant it is
    // swiped to instead of visibly correcting itself on arrival.
    LifecycleResumeEffect(Unit) {
        refreshTick++
        onPauseOrDispose { }
    }

    // Scroll state hoisted by [MainScreen]; see [ToysTab].
    Column(Modifier.fillMaxSize().verticalScroll(scrollState)) {
        Spacer(Modifier.height(innerPadding.calculateTopPadding()))

        SectionHeader(stringResource(R.string.section_initial_setup))
        SectionCard {
            val a11yEnabled = remember(refreshTick) { isEssentialKeyServiceEnabled(context) }
            // Read through stringResource rather than context.getString: values
            // pulled off LocalContext do not follow a configuration change, and
            // Compose lints it as an error.
            val a11yOnText = stringResource(R.string.checklist_accessibility_on)
            val a11yOffText = stringResource(R.string.checklist_accessibility_off)
            val a11ySubtitle = remember(refreshTick, a11yOnText, a11yOffText) {
                if (a11yEnabled) {
                    val beat = Core.prefs.getLong(PrefKeys.SERVICE_HEARTBEAT, PrefKeys.SERVICE_HEARTBEAT_DEF)
                    val suffix = if (beat > 0) {
                        val mins = (System.currentTimeMillis() - beat) / 60_000
                        " (last activity ${if (mins < 1) "just now" else "$mins min ago"})"
                    } else {
                        ""
                    }
                    a11yOnText + suffix
                } else {
                    a11yOffText
                }
            }
            ChecklistRow(
                title = stringResource(R.string.checklist_accessibility),
                subtitle = a11ySubtitle,
                good = a11yEnabled,
            ) {
                context.startActivity(
                    if (a11yEnabled) {
                        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    } else {
                        Intent(context, DisclosureActivity::class.java)
                    },
                )
            }
            HorizontalDivider()
            // No system setting or SDK call exposes the selected always-on
            // toy, and the system binds the chosen toy LAZILY — often never,
            // because the accessibility-driven session does the day-to-day
            // rendering. So this is a latch: the system only ever binds or
            // messages the toy it has selected, and once that has happened
            // the selection is proven. (Deselection is equally invisible, so
            // the mark cannot clear itself — the row still opens the picker.)
            val toyOk = remember(refreshTick) {
                Core.arbiter.owner == SessionArbiter.Owner.TOY ||
                    Core.prefs.getLong(PrefKeys.TOY_LAST_BOUND, PrefKeys.TOY_LAST_BOUND_DEF) > 0L
            }
            ChecklistRow(
                title = stringResource(R.string.checklist_toy),
                subtitle = stringResource(if (toyOk) R.string.checklist_toy_on else R.string.checklist_toy_hint),
                good = if (toyOk) true else null,
            ) {
                if (!openGlyphToySettings(context)) {
                    Toast.makeText(context, R.string.glyph_settings_unavailable, Toast.LENGTH_SHORT).show()
                }
            }
            HorizontalDivider()
            PermissionRow(
                stringResource(R.string.checklist_notifications),
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                refreshTick,
            ) { permissionLauncher.launch(it) }
            HorizontalDivider()
            PermissionRow(
                stringResource(R.string.checklist_mic),
                arrayOf(Manifest.permission.RECORD_AUDIO),
                refreshTick,
            ) { permissionLauncher.launch(it) }
            HorizontalDivider()
            PermissionRow(
                stringResource(R.string.checklist_location),
                arrayOf(
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                ),
                refreshTick,
            ) { permissionLauncher.launch(it) }
            HorizontalDivider()
            val alarmsOk = remember(refreshTick) {
                context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true
            }
            ChecklistRow(
                title = stringResource(R.string.checklist_exact_alarm),
                subtitle = stringResource(
                    if (alarmsOk) R.string.checklist_granted else R.string.checklist_tap_to_grant,
                ),
                good = alarmsOk,
            ) {
                context.startActivity(
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")),
                )
            }
        }
        HintText(stringResource(R.string.checklist_hint_guides))

        SectionHeader(stringResource(R.string.section_app_settings))
        SectionCard {
            var master by remember(refreshTick) {
                mutableStateOf(Core.prefs.getBoolean(PrefKeys.MASTER_TOGGLE, PrefKeys.MASTER_TOGGLE_DEF))
            }
            SwitchRow(
                title = stringResource(R.string.master_toggle),
                subtitle = stringResource(R.string.master_toggle_summary),
                checked = master,
            ) {
                master = it
                Core.prefs.putBoolean(PrefKeys.MASTER_TOGGLE, it)
            }
            HorizontalDivider()
            var menuMode by remember(refreshTick) {
                mutableStateOf(Core.prefs.getBoolean(PrefKeys.MENU_MODE_ENABLED, PrefKeys.MENU_MODE_ENABLED_DEF))
            }
            SwitchRow(
                title = stringResource(R.string.pref_menu_mode),
                subtitle = stringResource(R.string.pref_menu_mode_summary),
                checked = menuMode,
            ) {
                menuMode = it
                Core.prefs.putBoolean(PrefKeys.MENU_MODE_ENABLED, it)
            }
            HorizontalDivider()
            var use12h by remember(refreshTick) {
                mutableStateOf(Core.prefs.getBoolean(PrefKeys.USE_12H, false))
            }
            SwitchRow(title = stringResource(R.string.pref_use12h), subtitle = null, checked = use12h) {
                use12h = it
                Core.prefs.putBoolean(PrefKeys.USE_12H, it)
            }
            HorizontalDivider()
            Column(Modifier.padding(16.dp, 12.dp)) {
                Text(stringResource(R.string.brightness), style = MaterialTheme.typography.titleMedium)
                var brightness by remember {
                    mutableFloatStateOf(Core.prefs.getFloat(PrefKeys.BRIGHTNESS, PrefKeys.BRIGHTNESS_DEF))
                }
                var auto by remember {
                    mutableStateOf(Core.prefs.getBoolean(PrefKeys.AUTO_BRIGHTNESS, PrefKeys.AUTO_BRIGHTNESS_DEF))
                }
                // Auto-brightness writes BRIGHTNESS from the render thread, so the
                // slider must follow the pref (not just local state) to show what
                // auto is doing. Pref-change listeners fire on the main thread.
                DisposableEffect(Unit) {
                    val listener: (String) -> Unit = { key ->
                        when (key) {
                            PrefKeys.BRIGHTNESS ->
                                brightness = Core.prefs.getFloat(PrefKeys.BRIGHTNESS, PrefKeys.BRIGHTNESS_DEF)
                            PrefKeys.AUTO_BRIGHTNESS ->
                                auto = Core.prefs.getBoolean(PrefKeys.AUTO_BRIGHTNESS, PrefKeys.AUTO_BRIGHTNESS_DEF)
                        }
                    }
                    Core.prefs.addChangeListener(listener)
                    onDispose { Core.prefs.removeChangeListener(listener) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // MD3's shape-morphing icon toggle: a full circle when auto
                    // is off, squaring off to a 12 dp-cornered rounded square
                    // when it is on, and pinching to 8 dp under the finger — the
                    // library's own `toggleableShapes`, animated by it on the
                    // theme's effects spring (never-bouncing, deliberately, so a
                    // toggle cannot wobble).
                    //
                    // This REPLACES a hand-rolled 1 dp outline that used to fade
                    // in when the button was off. The shape morph is the spec's
                    // affordance for "this is a toggle and it is on", it is
                    // animated by the component rather than by a local
                    // `animateColorAsState`, and it costs no layout: the button
                    // stays exactly 40 dp either way.
                    NoRipple {
                        FilledIconToggleButton(
                            checked = auto,
                            onCheckedChange = { on ->
                                auto = on
                                Core.prefs.putBoolean(PrefKeys.AUTO_BRIGHTNESS, on)
                            },
                            shapes = IconButtonDefaults.toggleableShapes(),
                            modifier = Modifier.size(40.dp),
                        ) {
                            Icon(
                                Icons.Default.BrightnessAuto,
                                contentDescription = stringResource(
                                    if (auto) R.string.auto_brightness_on else R.string.auto_brightness_off,
                                ),
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Slider(
                        value = brightness,
                        onValueChange = {
                            // Fiddling with the slider means "I'll do it myself":
                            // drop out of auto first, so the controller has stopped
                            // polling before the manual value lands.
                            if (auto) {
                                auto = false
                                Core.prefs.putBoolean(PrefKeys.AUTO_BRIGHTNESS, false)
                            }
                            brightness = it.coerceIn(0.05f, 1f)
                            Core.prefs.putFloat(PrefKeys.BRIGHTNESS, brightness)
                        },
                        valueRange = 0.05f..1f,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            HorizontalDivider()
            UpdateRow()
        }

        Spacer(Modifier.height(innerPadding.calculateBottomPadding() + NAV_PILL_CLEARANCE))
    }
}

// ---------- Tutorial tab ----------

private enum class TutorialTopic { KEY, HANDOVER, RESTRICTED }

@Composable
private fun TutorialTab(innerPadding: PaddingValues, scrollState: ScrollState) {
    val context = LocalContext.current
    var topic by remember { mutableStateOf<TutorialTopic?>(null) }

    // Scroll state hoisted by [MainScreen]; see [ToysTab].
    Column(Modifier.fillMaxSize().verticalScroll(scrollState)) {
        Spacer(Modifier.height(innerPadding.calculateTopPadding()))

        HintText(stringResource(R.string.tut_hint))
        SectionCard {
            SetupRow(
                title = stringResource(R.string.tut_title),
                subtitle = stringResource(R.string.tut_button_subtitle),
                good = null,
            ) { topic = TutorialTopic.KEY }
            HorizontalDivider()
            SetupRow(
                title = stringResource(R.string.tut_handover_title),
                subtitle = stringResource(R.string.tut_handover_subtitle),
                good = null,
            ) { topic = TutorialTopic.HANDOVER }
            HorizontalDivider()
            SetupRow(
                title = stringResource(R.string.tut_restricted_title),
                subtitle = stringResource(R.string.tut_restricted_subtitle),
                good = null,
            ) { topic = TutorialTopic.RESTRICTED }
        }

        Spacer(Modifier.height(innerPadding.calculateBottomPadding() + NAV_PILL_CLEARANCE))
    }

    when (topic) {
        TutorialTopic.KEY -> KeyTutorialDialog(onDismiss = { topic = null })
        TutorialTopic.HANDOVER -> TutorialInfoDialog(
            title = stringResource(R.string.tut_handover_title),
            intro = stringResource(R.string.tut_handover_intro),
            steps = listOf(
                stringResource(R.string.tut_handover_step1),
                stringResource(R.string.tut_handover_step2),
            ),
            note = stringResource(R.string.tut_handover_note),
            onDismiss = { topic = null },
        )
        TutorialTopic.RESTRICTED -> TutorialInfoDialog(
            title = stringResource(R.string.tut_restricted_title),
            intro = stringResource(R.string.tut_restricted_intro),
            steps = listOf(
                stringResource(R.string.tut_restricted_step1),
                stringResource(R.string.tut_restricted_step2),
                stringResource(R.string.tut_restricted_step3),
                stringResource(R.string.tut_restricted_step4),
            ),
            actionLabel = stringResource(R.string.tut_restricted_action),
            onAction = {
                context.startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:${context.packageName}"),
                    ),
                )
            },
            onDismiss = { topic = null },
        )
        null -> {}
    }
}

// ---------- drag & drop ----------

private class DragState {
    var draggingIndex by mutableIntStateOf(-1)

    /**
     * The finger's live offset from the row's laid-out slot. Deliberately NOT
     * animated: while a finger is down the row must track it exactly, and any
     * spring in this path shows up as the row lagging behind the touch point.
     */
    var offsetY by mutableFloatStateOf(0f)

    var rowHeightPx by mutableIntStateOf(0)

    /**
     * The row that has just been released and is springing back to its slot,
     * or -1 when nothing is settling. Only the RELEASE is animated — see
     * [offsetY] for why the drag itself is not.
     */
    var settlingIndex by mutableIntStateOf(-1)

    /** The settling row's animated leftover offset, driven towards 0. */
    val settleOffset = Animatable(0f)
}

@Composable
private fun DisplayRow(
    id: String,
    index: Int,
    drag: DragState,
    order: MutableList<String>,
    shown: Boolean,
    placement: Modifier,
    onPersist: () -> Unit,
    onSelect: () -> Unit,
    onSettings: (() -> Unit),
) {
    val dragging = drag.draggingIndex == index
    val settling = drag.settlingIndex == index
    val scope = rememberCoroutineScope()
    // The row springs home from wherever the finger let go. Spatial (it is a
    // position), default speed: the row is a full-width card, and the slight
    // overshoot is what makes the drop read as "dropped" rather than "teleported".
    val settleSpec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()

    /** Releases the drag and springs the row's leftover offset back to zero. */
    fun release() {
        val released = drag.draggingIndex
        val from = drag.offsetY
        drag.draggingIndex = -1
        drag.offsetY = 0f
        onPersist()
        scope.launch {
            drag.settlingIndex = released
            try {
                drag.settleOffset.snapTo(from)
                drag.settleOffset.animateTo(0f, settleSpec)
            } finally {
                // Also on cancellation: if the row is scrolled out of the lazy
                // list mid-settle its scope dies, and a stuck settlingIndex
                // would pin a stale translationY on whatever row lands in that
                // slot next.
                drag.settlingIndex = -1
            }
        }
    }

    // Container tint is a COLOUR, so it fades on the effects spring — a
    // bouncing fill would flicker. The two elevations are z-position, so they
    // ride the spatial spring instead, and fast because they are small
    // (0→6 dp) and must feel instant under the finger on pick-up.
    val color by animateColorAsState(
        targetValue = if (shown) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "toyRowContainer",
    )
    val tonal by animateDpAsState(
        targetValue = if (dragging) 8.dp else 1.dp,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "toyRowTonalElevation",
    )
    val shadow by animateDpAsState(
        targetValue = if (dragging) 6.dp else 0.dp,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "toyRowShadowElevation",
    )
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .then(placement)
            .zIndex(if (dragging || settling) 1f else 0f)
            .graphicsLayer {
                translationY = when {
                    dragging -> drag.offsetY
                    settling -> drag.settleOffset.value
                    else -> 0f
                }
            }
            .onSizeChanged { drag.rowHeightPx = it.height }
            .padding(horizontal = 16.dp, vertical = 3.dp),
        shape = RoundedCornerShape(20.dp),
        color = color,
        // Never let a bouncy spring drive elevation NEGATIVE: Surface rejects
        // a negative elevation, and the fast spatial spring is under-damped
        // (0.6) so 1 dp → 8 dp and back undershoots below zero.
        tonalElevation = tonal.coerceAtLeast(0.dp),
        shadowElevation = shadow.coerceAtLeast(0.dp),
    ) {
        Row(
            modifier = Modifier.padding(start = 8.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Menu,
                contentDescription = "Drag to reorder",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(8.dp)
                    .pointerInput(id) {
                        detectDragGestures(
                            onDragStart = {
                                drag.draggingIndex = order.indexOf(id)
                                drag.offsetY = 0f
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                drag.offsetY += amount.y
                                val i = drag.draggingIndex
                                val h = drag.rowHeightPx
                                if (h <= 0) return@detectDragGestures
                                if (drag.offsetY > h * 0.6f && i < order.lastIndex) {
                                    order.add(i + 1, order.removeAt(i))
                                    drag.draggingIndex = i + 1
                                    drag.offsetY -= h
                                } else if (drag.offsetY < -h * 0.6f && i > 0) {
                                    order.add(i - 1, order.removeAt(i))
                                    drag.draggingIndex = i - 1
                                    drag.offsetY += h
                                }
                            },
                            onDragEnd = ::release,
                            onDragCancel = ::release,
                        )
                    },
            )
            // Reserved slot for the "currently shown on the matrix" dot,
            // so the name never shifts when the marker appears.
            //
            // The dot pops in and out rather than cutting: scale is a SIZE, so
            // it takes the spatial spring, and fast because the dot is tiny —
            // fast spatial is damped 0.6, which gives it a real pop. Its alpha
            // is an effect, so it rides the (never-bouncing) effects spring on
            // its own; a bouncing alpha would flicker the dot as it lands.
            // The dot is always composed, so the row's layout never moves.
            val dotScale by animateFloatAsState(
                targetValue = if (shown) 1f else 0f,
                animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
                label = "shownDotScale",
            )
            val dotAlpha by animateFloatAsState(
                targetValue = if (shown) 1f else 0f,
                animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
                label = "shownDotAlpha",
            )
            Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(8.dp)
                        .graphicsLayer {
                            // coerceAtLeast: the under-damped spring undershoots
                            // past 0 on the way out, and a negative scale is a
                            // mirrored draw, not an absent one.
                            scaleX = dotScale.coerceAtLeast(0f)
                            scaleY = dotScale.coerceAtLeast(0f)
                            alpha = dotAlpha.coerceIn(0f, 1f)
                        }
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                )
            }
            Text(
                stringResource(DISPLAY_NAMES[id] ?: R.string.app_name),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            // "This toy is the one on the matrix" — the checked state, so it
            // gets MD3's shape morph for a toggle: circle when it is not the
            // active toy, 12 dp rounded square when it is, animated by the
            // component on the theme's effects spring. Nothing about the row's
            // drag machinery is involved.
            NoRipple {
                FilledIconToggleButton(
                    checked = shown,
                    onCheckedChange = { onSelect() },
                    shapes = IconButtonDefaults.toggleableShapes(),
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.set_active))
                }
            }
            if (id in CONFIGURABLE) {
                IconButton(onClick = onSettings) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = stringResource(R.string.settings),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            var enabled by remember(id) {
                mutableStateOf(Core.prefs.getBoolean(PrefKeys.screenEnabled(id), true))
            }
            NoRipple {
                Switch(checked = enabled, onCheckedChange = {
                    enabled = it
                    Core.prefs.putBoolean(PrefKeys.screenEnabled(id), it)
                })
            }
        }
    }
}

// ---------- shared building blocks ----------

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.tertiary,
        modifier = Modifier.padding(start = 24.dp, top = 24.dp, bottom = 8.dp),
    )
}

@Composable
private fun SectionCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column { content() }
    }
}

/**
 * Colours for a [ListItem] that carries a SELECTED state — the one treatment
 * shared by every such row in the app, so selection reads as a single idea.
 *
 * MD3's default `selectedContainerColor` is `secondaryContainer`. In this
 * theme's dark scheme that is #2E3138 painted over a #191C20 card — a lift of
 * roughly 9 L*, which lands as a bright grey highlight bar and is far too loud
 * for a strictly monochrome, deliberately low-contrast palette.
 *
 * `surfaceVariant` is the scheme's own "a surface, one step differentiated"
 * role, and it is the right amount of nothing: in dark it is #23262B, about
 * 4 L* above the card — half the lift; in light it is #EBEBEF against white,
 * within ~1.5 L* of the `secondaryContainer` these rows have always used, so
 * light mode is unchanged in practice. It is also already this app's tint for
 * exactly this job elsewhere (the onboarding sideload card).
 *
 * The selected CONTENT colours are pinned back to their unselected values.
 * Left alone they all promote to `onSecondaryContainer` — full ink — so a
 * selected row would brighten its supporting text *as well as* tinting its
 * container, and that second jump is most of what makes selection read heavy.
 *
 * What is left to signal selection: this gentle tint, the library's shape
 * morph, and the radio dot — which stays the primary signal.
 */
@Composable
internal fun selectedRowColors(): ListItemColors = ListItemDefaults.colors(
    // NO container tint in either state, for every radio row in the app (this
    // helper's only two callers are [ChoiceRow] and onboarding's mode picker,
    // and those are the app's only two RadioButtons). The filled radio button IS
    // the selection indicator; a shaded container behind it says the same thing
    // twice and reads as a pressed/disabled state on a surface this dark.
    //
    // Both are Transparent rather than a named role so a row always shows
    // exactly whatever it sits on — ListItem's own default is `surface`, which
    // is a *different* colour from the dialog's container and would leave every
    // UNSELECTED row faintly patched too.
    containerColor = Color.Transparent,
    selectedContainerColor = Color.Transparent,
    selectedContentColor = MaterialTheme.colorScheme.onSurface,
    selectedLeadingContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    selectedTrailingContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    selectedOverlineContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    selectedSupportingContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
)

/**
 * Runs [content] with M3's ripple switched off, for the app's TOGGLE controls —
 * switches, radio rows, segmented buttons, icon toggle buttons and the nav
 * chips. Every one of those already states its state with motion the expressive
 * scheme drives (a thumb that slides, a dot that fills, a shape that morphs, a
 * label that grows), so the ripple was saying the same thing a second time, and
 * on a switch it says it *badly*: the unbounded state layer around the thumb
 * lingers as a grey halo well after the finger is gone.
 *
 * `null` on [LocalRippleConfiguration] is material3's documented way to do this
 * ("To disable the ripple completely, provide `null`"). It is what reaches
 * `Switch` and `RadioButton`, which call `ripple(...)` DIRECTLY rather than
 * going through `LocalIndication` — so overriding the indication would not have
 * touched them.
 *
 * Deliberately NOT applied app-wide. Plain buttons, icon buttons and clickable
 * list rows have no state of their own to animate, so the ripple is their only
 * acknowledgement of a tap and it stays.
 */
@Composable
internal fun NoRipple(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalRippleConfiguration provides null, content = content)
}

/**
 * Breathing room between a dialog and the top and bottom edges of the screen.
 *
 * Applied as PADDING ON THE DIALOG'S WRAPPING BOX, never on its Surface, and
 * that distinction is the whole trick: `BasicAlertDialog` (and our own
 * `MotionDialog`) put the caller's modifier on a wrap-content Box around the
 * dialog surface, so this only ever lowers the MAXIMUM height the surface may
 * take. A short dialog does not reach that maximum, wraps its content and stays
 * centred, so it renders identically — no inner gap appears around its text. A
 * tall one, which would otherwise grow until it ran into the status bar and the
 * bottom edge, stops this far short of both and scrolls its content instead.
 *
 * Putting the same value inside the Surface would pad every dialog, which is
 * exactly what must not happen.
 */
internal val DIALOG_VERTICAL_MARGIN = 40.dp

@Composable
private fun HintText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
    )
}

/**
 * Title over a subtitle, the whole row clickable — a real MD3 [ListItem]
 * (headline `content` + `supportingContent`), not a `Column` with
 * `Modifier.clickable`.
 *
 * What that buys, for free and to spec: the row's press ripple and its shape
 * morph (rectangular at rest → 16 dp corners under the finger) run on
 * [MaterialTheme.motionScheme]'s fast spatial spring, the container/content
 * colours cross-fade on its effects spring, and the whole row is one merged
 * accessibility node with a ≥ 48 dp target instead of two loose Text nodes.
 *
 * The subtitle is animated because [UpdateRow] rewrites it live as the check
 * runs (Idle → Checking → UpToDate / Available / Failed) and every one of
 * those is a different length; the static callers simply never trigger it.
 */
@Composable
private fun SetupRow(title: String, subtitle: String, good: Boolean?, onClick: () -> Unit) {
    // Tint is a COLOUR → effects spring, so it settles without a bounce and
    // ahead of the geometry, as MD3 intends.
    val subtitleColor by animateColorAsState(
        targetValue = when (good) {
            true -> MaterialTheme.colorScheme.primary
            false -> MaterialTheme.colorScheme.error
            null -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "setupRowSubtitleTint",
    )
    // Hoisted: AnimatedContent's transitionSpec is NOT a composable lambda, so
    // MaterialTheme cannot be read from inside it.
    val fade = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    // The row's height changes with the new text's line count — a SIZE, hence
    // the spatial spring.
    val resize = MaterialTheme.motionScheme.defaultSpatialSpec<IntSize>()
    ListItem(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        supportingContent = {
            AnimatedContent(
                targetState = subtitle,
                transitionSpec = {
                    // Crossfade, not a slide: the two strings say the same kind
                    // of thing about the same row, so there is no direction to
                    // imply.
                    (fadeIn(fade) togetherWith fadeOut(fade))
                        .using(SizeTransform(clip = false) { _, _ -> resize })
                },
                label = "setupRowSubtitle",
            ) { text ->
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall,
                    color = subtitleColor,
                )
            }
        },
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
    }
}

/**
 * Setup-checklist row: a grey check mark on the left once the item is
 * configured and working, a grey question mark while it is not (or cannot
 * be verified).
 */
@Composable
private fun ChecklistRow(title: String, subtitle: String, good: Boolean?, onClick: () -> Unit) {
    // These rows re-probe on every resume, so the mark and the tint can both
    // change under the user (they grant a permission and come back).
    val subtitleColor by animateColorAsState(
        targetValue = when (good) {
            true -> MaterialTheme.colorScheme.primary
            false -> MaterialTheme.colorScheme.error
            null -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "checklistRowSubtitleTint",
    )
    // Hoisted — transitionSpec is not a composable lambda. The icon is a small
    // contained element, so both halves take the FAST variants: alpha on
    // effects, scale on spatial (damped 0.6, so the incoming mark pops).
    val iconFade = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    val iconScale = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    ListItem(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        // The mark is the list item's LEADING slot, so its tint (and the gap to
        // the text) now come from ListItemDefaults rather than being spelled
        // out here — the icon inherits `leadingContentColor`, which is the same
        // onSurfaceVariant it was hard-coded to.
        leadingContent = {
            AnimatedContent(
                targetState = good == true,
                transitionSpec = {
                    (fadeIn(iconFade) + scaleIn(iconScale, initialScale = 0.6f)) togetherWith
                        (fadeOut(iconFade) + scaleOut(iconScale, targetScale = 0.6f))
                },
                label = "checklistRowMark",
            ) { ok ->
                Icon(
                    if (ok) Icons.Default.Check else Icons.AutoMirrored.Filled.HelpOutline,
                    contentDescription = null,
                )
            }
        },
        supportingContent = {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = subtitleColor,
            )
        },
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun PermissionRow(title: String, permissions: Array<String>, refreshTick: Int, onRequest: (Array<String>) -> Unit) {
    val context = LocalContext.current
    val granted = remember(refreshTick) {
        permissions.any { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }
    ChecklistRow(
        title = title,
        subtitle = stringResource(if (granted) R.string.checklist_granted else R.string.checklist_tap_to_grant),
        good = granted,
    ) { onRequest(permissions) }
}

/**
 * A settings row whose trailing control is a [Switch].
 *
 * A real MD3 [ListItem] with the switch in its trailing slot. Deliberately the
 * NON-interactive [ListItem] overload: the toggleable overload would put
 * `Role.Checkbox` on the row and demote the switch to a passive graphic, which
 * is a step down from the `Role.Switch` this has always announced. The switch
 * keeps its own API (`checked` / `onCheckedChange`) and therefore its own
 * spec motion — the thumb's slide and squash already ride
 * [MaterialTheme.motionScheme] — while the row around it now gets MD3's list
 * metrics, slot spacing and merged semantics.
 */
@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        modifier = Modifier.fillMaxWidth(),
        supportingContent = subtitle?.let {
            {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        trailingContent = { NoRipple { Switch(checked = checked, onCheckedChange = onChange) } },
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
    }
}

// ---------- unsupported-device dead end ----------

/** Shown instead of the app on hardware without a Glyph Matrix. */
@Composable
private fun UnsupportedDeviceScreen() {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Column {
                Text(
                    stringResource(R.string.unsupported_title),
                    style = MaterialTheme.typography.headlineMedium,
                )
                Text(
                    stringResource(R.string.unsupported_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}

// ---------- update check ----------

private sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data object UpToDate : UpdateUiState
    data class Available(val version: String, val url: String) : UpdateUiState
    data class Failed(val reason: String) : UpdateUiState
}

@Composable
private fun UpdateRow() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val installed = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
        } catch (e: Exception) {
            "?"
        }
    }
    var state by remember { mutableStateOf<UpdateUiState>(UpdateUiState.Idle) }

    SetupRow(
        title = stringResource(R.string.update_check_title),
        subtitle = when (val s = state) {
            UpdateUiState.Idle -> stringResource(R.string.update_idle, installed)
            UpdateUiState.Checking -> stringResource(R.string.update_checking)
            UpdateUiState.UpToDate -> stringResource(R.string.update_up_to_date, installed)
            is UpdateUiState.Available -> stringResource(R.string.update_available, s.version)
            is UpdateUiState.Failed -> stringResource(R.string.update_failed, s.reason)
        },
        good = when (state) {
            is UpdateUiState.Available -> true
            is UpdateUiState.Failed -> false
            else -> null
        },
    ) {
        when (val s = state) {
            // Once an update is known, the row becomes the download link.
            is UpdateUiState.Available ->
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(s.url)))
            UpdateUiState.Checking -> {}
            else -> scope.launch {
                state = UpdateUiState.Checking
                val result = withContext(Dispatchers.IO) { UpdateChecker.check(installed) }
                state = when (result) {
                    is UpdateChecker.Result.UpdateAvailable ->
                        UpdateUiState.Available(result.version, result.url)
                    UpdateChecker.Result.UpToDate -> UpdateUiState.UpToDate
                    is UpdateChecker.Result.Failed -> UpdateUiState.Failed(result.reason)
                }
            }
        }
    }
}

// ---------- per-screen settings dialogs ----------

@Composable
private fun ScreenSettingsDialog(id: String, onDismiss: () -> Unit) {
    AlertDialog(
        // See [DIALOG_VERTICAL_MARGIN]. Only tall dialogs notice.
        modifier = Modifier.padding(vertical = DIALOG_VERTICAL_MARGIN),
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
        title = { Text(stringResource(DISPLAY_NAMES[id] ?: R.string.settings)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                when (id) {
                    "clock" -> IntChoiceGroup(
                        options = listOf("Plain digits", "Digits + battery bar", "Digits + battery ring"),
                        key = PrefKeys.CLOCK_THEME,
                        def = PrefKeys.CLOCK_THEME_DEF,
                    )
                    "dice" -> StringChoiceGroup(
                        options = listOf("D4", "D6", "D8", "D12", "D20"),
                        key = PrefKeys.SELECTED_DICE,
                        def = PrefKeys.SELECTED_DICE_DEF,
                    )
                    "coin" -> {
                        Text(stringResource(R.string.pref_coin_design), style = MaterialTheme.typography.labelLarge)
                        IntChoiceGroup(
                            options = listOf("Letters (H/T)", "Portrait & numeral"),
                            key = PrefKeys.COIN_DESIGN,
                            def = PrefKeys.COIN_DESIGN_DEF,
                        )
                    }
                    "battery" -> PrefSwitch(
                        stringResource(R.string.pref_battery_watts),
                        PrefKeys.BATTERY_SHOW_WATTS,
                        PrefKeys.BATTERY_SHOW_WATTS_DEF,
                    )
                    "breathing" -> {
                        Text(stringResource(R.string.pref_breathing_pace), style = MaterialTheme.typography.labelLarge)
                        StringChoiceGroup(
                            options = listOf("2", "3", "4", "6", "8"),
                            key = PrefKeys.BREATHING_PACE,
                            def = PrefKeys.BREATHING_PACE_DEF,
                        )
                    }
                    "timer" -> {
                        Text(stringResource(R.string.pref_timer_duration), style = MaterialTheme.typography.labelLarge)
                        IntValueChoiceGroup(
                            // Stored in seconds, labelled in minutes — never
                            // show a raw second count here.
                            options = PrefKeys.TIMER_DURATION_OPTIONS,
                            labels = listOf("1 min", "3 min", "5 min", "7 min", "10 min", "13 min"),
                            key = PrefKeys.TIMER_DURATION,
                            def = PrefKeys.TIMER_DURATION_DEF,
                        )
                    }
                    "visualizer" -> {
                        Text(stringResource(R.string.pref_visualizer_theme), style = MaterialTheme.typography.labelLarge)
                        IntChoiceGroup(
                            options = listOf("Bars", "Mirrored bars", "Palette"),
                            key = PrefKeys.VISUALIZER_THEME,
                            def = PrefKeys.VISUALIZER_THEME_DEF,
                        )
                        Text(
                            stringResource(R.string.pref_visualizer_tuning),
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                        IntValueChoiceGroup(
                            options = (1..6).toList(),
                            labels = listOf("1 — calmest", "2", "3", "4", "5", "6 — snappiest"),
                            key = PrefKeys.VISUALIZER_TUNING,
                            def = PrefKeys.VISUALIZER_TUNING_DEF,
                        )
                    }
                    "ambient" -> AmbientSettings()
                }
            }
        },
    )
}

@Composable
private fun AmbientSettings() {
    Text(stringResource(R.string.pref_ambient_background), style = MaterialTheme.typography.labelLarge)
    IntChoiceGroup(
        options = listOf(
            "Digital clock", "Analog clock", "Connection status", "Battery %",
            "Download speed", "Tilt ball", "Pixel clock (themed)",
            "Battery gauge", "Solar path", "Moon phase",
        ),
        key = PrefKeys.AMBIENT_BACKGROUND,
        def = PrefKeys.AMBIENT_BACKGROUND_DEF,
    )
    PrefSwitch(stringResource(R.string.pref_ambient_night), PrefKeys.AMBIENT_NIGHT_VISIBLE, PrefKeys.AMBIENT_NIGHT_VISIBLE_DEF)
    PrefSwitch(stringResource(R.string.pref_ambient_shake), PrefKeys.AMBIENT_SHAKE_ACTIVATE, PrefKeys.AMBIENT_SHAKE_ACTIVATE_DEF)
    PrefSwitch(stringResource(R.string.pref_ambient_charging), PrefKeys.AMBIENT_USE_CHARGING, PrefKeys.AMBIENT_USE_CHARGING_DEF)
    Text(
        stringResource(R.string.pref_ambient_charging_style),
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(top = 12.dp),
    )
    IntChoiceGroup(
        // Order is the persisted value — append only. "Charging wattage" is
        // ChargingRenderer.STYLE_WATTS and must stay at that index.
        options = listOf("Fill + wave", "Particles", "Battery + bolt", "Percent + bolt", "Charging wattage"),
        key = PrefKeys.AMBIENT_CHARGING_STYLE,
        def = PrefKeys.AMBIENT_CHARGING_STYLE_DEF,
    )
}

@Composable
private fun IntChoiceGroup(options: List<String>, key: String, def: Int) {
    var selected by remember(key) { mutableIntStateOf(Core.prefs.getInt(key, def)) }
    Column {
        options.forEachIndexed { i, label ->
            ChoiceRow(label, selected == i) {
                selected = i
                Core.prefs.putInt(key, i)
            }
        }
    }
}

@Composable
private fun IntValueChoiceGroup(options: List<Int>, labels: List<String>, key: String, def: Int) {
    var selected by remember(key) { mutableIntStateOf(Core.prefs.getInt(key, def)) }
    Column {
        options.forEachIndexed { i, value ->
            ChoiceRow(labels[i], selected == value) {
                selected = value
                Core.prefs.putInt(key, value)
            }
        }
    }
}

@Composable
private fun StringChoiceGroup(options: List<String>, key: String, def: String) {
    var selected by remember(key) { mutableStateOf(Core.prefs.getString(key, def)) }
    Column {
        options.forEach { value ->
            ChoiceRow(value, selected == value) {
                selected = value
                Core.prefs.putString(key, value)
            }
        }
    }
}

/**
 * One option in a per-toy settings dialog: a single-selection MD3 [ListItem],
 * which is the component for "one row out of a mutually exclusive set".
 *
 * Selection goes through the component's own `selected` / `onClick`, so the
 * library animates it: the container crossfades on the theme's effects spring
 * (to the restrained tint of [selectedRowColors], NOT the default's loud
 * `secondaryContainer`) and the row morphs to 16 dp corners as it becomes the
 * chosen one, on its fast spatial spring. The [RadioButton] is passive
 * (`onClick = null`) because the ROW now carries `Role.RadioButton` and the
 * ≥ 48 dp target — a clickable dot would be a second, redundant focus stop —
 * but its own dot still springs in and out on the same motion scheme.
 *
 * These sit in a dialog that already pads its content, so the horizontal
 * content padding is zeroed to keep the rows where they have always been.
 */
@Composable
private fun ChoiceRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    // Whole row, not just the RadioButton: the row IS the tap target here, so a
    // ripple sweeping it would be the loud feedback we are removing. The dot
    // filling on the expressive spring is the acknowledgement. See [NoRipple].
    NoRipple {
        ListItem(
            selected = selected,
            onClick = onSelect,
            modifier = Modifier.fillMaxWidth(),
            leadingContent = { RadioButton(selected = selected, onClick = null) },
            colors = selectedRowColors(),
            contentPadding = CHOICE_ROW_PADDING,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** See [ChoiceRow]: no horizontal inset, the dialog supplies it. */
private val CHOICE_ROW_PADDING = PaddingValues(horizontal = 0.dp, vertical = 2.dp)

/** See [PrefSwitch]; same reasoning as [CHOICE_ROW_PADDING]. */
private val PREF_SWITCH_PADDING = PaddingValues(horizontal = 0.dp, vertical = 4.dp)

/**
 * A per-toy boolean, as a [ListItem] with the [Switch] in its trailing slot.
 * Non-interactive row for the same reason as [SwitchRow]: the switch keeps
 * `Role.Switch` and its own spec motion.
 */
@Composable
private fun PrefSwitch(title: String, key: String, def: Boolean) {
    var checked by remember(key) { mutableStateOf(Core.prefs.getBoolean(key, def)) }
    ListItem(
        modifier = Modifier.fillMaxWidth(),
        trailingContent = {
            NoRipple {
                Switch(checked = checked, onCheckedChange = {
                    checked = it
                    Core.prefs.putBoolean(key, it)
                })
            }
        },
        contentPadding = PREF_SWITCH_PADDING,
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium)
    }
}
