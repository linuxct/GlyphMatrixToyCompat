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
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.BrightnessAuto
import androidx.compose.material.icons.outlined.BrightnessMedium
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.Casino
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DragIndicator
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Slideshow
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import space.linuxct.glyphworks.Core
import space.linuxct.glyphworks.R
import space.linuxct.glyphworks.core.DebugLog
import space.linuxct.glyphworks.core.PrefKeys
import space.linuxct.glyphworks.core.SessionArbiter
import space.linuxct.glyphworks.core.design.DesignCodec
import space.linuxct.glyphworks.ui.design.DemoTarget
import space.linuxct.glyphworks.ui.design.DesignDemoActivity
import space.linuxct.glyphworks.ui.design.demoTarget
import space.linuxct.glyphworks.ui.theme.GlyphWorksTheme
import space.linuxct.glyphworks.ui.theme.NavPillColors
import space.linuxct.glyphworks.ui.theme.navPill
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
                GlyphWorksTheme {
                    UnsupportedDeviceScreen()
                }
            }
            return
        }
        // Debug/replay hook — OnboardingActivity itself is not exported, so:
        // adb shell am start -n space.linuxct.glyphworks/.ui.MainActivity --ez restart_onboarding true
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
        // GitHub build only; a no-op in the Play flavour, which has no updater.
        scheduleUpdateCheck(this)
        enableEdgeToEdge()
        // Which page to open on. Coerced rather than trusted: this arrives in an
        // Intent, and a page index out of range would crash the pager.
        val startTab = intent?.getIntExtra(EXTRA_TAB, 0)?.coerceIn(Tab.entries.indices) ?: 0
        setContent {
            GlyphWorksTheme {
                MainScreen(startTab)
            }
        }
    }

    companion object {
        const val EXTRA_RESTART_ONBOARDING = "restart_onboarding"

        /** Which tab to land on, as a [Tab] ordinal. Absent means the first. */
        private const val EXTRA_TAB = "tab"

        /**
         * The way in for somebody who wants to start drawing immediately —
         * onboarding's "take me to Create" button and nothing else so far.
         *
         * The tab travels as an ordinal because that is what the pager wants, and
         * it is produced HERE from [Tab] rather than by the caller: the enum is
         * this file's private business (see [CREATE_TAB_INDEX]), and a caller
         * passing a number would be a second place that knows the tab order.
         */
        fun createTabIntent(context: Context): Intent =
            Intent(context, MainActivity::class.java).putExtra(EXTRA_TAB, Tab.CREATE.ordinal)
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
    "custom" to R.string.screen_custom,
)

private val CONFIGURABLE =
    setOf("ambient", "clock", "dice", "coin", "battery", "breathing", "timer", "visualizer", "custom")

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
internal val NAV_PILL_CLEARANCE = 40.dp

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
 * The pages, in nav order. [caption] is the short label beside the nav-pill
 * icon; [title] is the (longer) page title in the app bar — they stay separate
 * because e.g. "Toys" heads the "Glyph Toys" page. Where the two are the same
 * word, one string serves both.
 *
 * **The ordinal IS the pager page index**, so the declaration order here is the
 * on-screen order and inserting an entry moves everything after it. Nothing
 * persists the selected tab (`rememberPagerState` is `rememberSaveable`-backed
 * and dies with the process), so reordering needs no migration — but two
 * exhaustive `when (Tab.entries[page])` branches in [MainScreen] will stop
 * compiling until the new page is given a body and an "is it at the top?" rule,
 * which is exactly the reminder you want.
 *
 * ## Why this order
 *
 * **Toys, Create, Settings, Tutorials** — the two pages about *content* sit
 * together at the front, and the two about the *app* sit together behind them.
 * Create arrived last and was originally appended after Settings, which put the
 * app's configuration between a user's toys and a user's designs; the two halves
 * of "what is on my matrix" were then two swipes apart with a settings page in
 * the middle of them. They are now neighbours, and one swipe from Toys reaches
 * the design that feeds the `Custom` toy.
 *
 * Nothing else in this file may encode the order. Comments elsewhere that
 * describe which page neighbours which have gone stale twice now; if a rule
 * needs to know where a page sits, it asks this enum.
 */
private enum class Tab(val icon: ImageVector, val caption: Int, val title: Int) {
    TOYS(Icons.Outlined.Casino, R.string.nav_toys, R.string.screens_title),
    CREATE(Icons.Outlined.Brush, R.string.nav_create, R.string.create_title),
    SETTINGS(Icons.Outlined.Settings, R.string.nav_settings, R.string.settings),
    TUTORIAL(Icons.Outlined.School, R.string.tut_section, R.string.tut_section),
}

/**
 * Which chip the Create tab is, for the one caller outside this file that has to
 * draw the pill without a pager behind it: the guided demo, which shows the real
 * [FloatingNavBar] so it can point at the real `+`.
 *
 * Exported as a number rather than by making [Tab] internal, because the enum is
 * this file's private business — the ordinal IS the page index and nothing
 * outside `MainScreen` should be in a position to reason about that.
 */
internal val CREATE_TAB_INDEX: Int = Tab.CREATE.ordinal

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(startTab: Int = 0) {
    // The pager IS the selection: there is no separate `tab` index any more, so
    // there is nothing that can disagree with the scroll position mid-drag.
    // rememberPagerState is itself rememberSaveable-backed (a listSaver over
    // page + offset + count), so the selected tab still survives rotation and
    // process death exactly as the old rememberSaveable Int did — and
    // [startTab] therefore applies to the FIRST composition only, which is what
    // "open on this tab" means: a rotation must not throw the user back to it.
    val pagerState = rememberPagerState(initialPage = startTab, pageCount = { Tab.entries.size })
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
    // Declared in [Tab] order, so a reader can check at a glance that every page
    // has one.
    val toysListState = rememberLazyListState()
    val createListState = rememberLazyListState()
    val settingsScrollState = rememberScrollState()
    val tutorialScrollState = rememberScrollState()

    // The Create tab's DATA, hoisted for the same reason as the scroll states
    // above and for one more: it is backed by file I/O. A tab-local remember
    // would re-read the designs directory off the disk every time the page
    // scrolled back into the pager's window, and would blank the list to its
    // loading state while it did. Hoisted, the directory is read once per
    // process and re-read only when this UI itself changes it.
    //
    // It is also the bridge between the two halves of this feature that do not
    // share a subtree: the `+` FAB lives in [FloatingNavBar] (a sibling of the
    // Scaffold), while the dialog it opens and the list it appends to live in
    // [CreateTab] (inside the pager). The FAB sets a flag on this object; the
    // tab body renders the dialog.
    val createState = remember { CreateState() }

    // The Initial setup checklist, probed HERE rather than inside [SettingsTab],
    // because two things now read it and only one of them is on that page: the
    // checklist rows, and the attention badge on the Settings chip of the
    // [FloatingNavBar] — which is a SIBLING of the pager and is drawn whichever
    // tab is showing. Hoisting the probe is what makes those two the same answer
    // rather than two implementations of the same question; see [SetupStatus].
    //
    // It costs nothing to move: the six checks are synchronous permission/service
    // reads, they already ran on this schedule (the page is inside the pager's
    // live window from either neighbour, so it was re-probing while off screen
    // too), and now they run exactly once per resume for the whole screen instead
    // of once per resume of one page.
    val setupContext = LocalContext.current
    var setupTick by remember { mutableIntStateOf(0) }
    val setup = remember(setupTick, setupContext) { probeSetup(setupContext) }
    // The refresh mechanism the checklist has always used, moved up with the
    // probe: re-ask on every resume, which is what makes granting a permission or
    // enabling the accessibility service — both of which happen in another
    // activity — clear the badge and the row's question mark together, with no
    // restart. The in-app permission dialog does not pass through onResume in
    // every case, so [SettingsTab]'s launcher bumps the same counter directly.
    LifecycleResumeEffect(Unit) {
        setupTick++
        onPauseOrDispose { }
    }

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

    // One collapsing app bar is SHARED by every page, so its collapse is
    // whatever the last-scrolled tab left behind: land on a tab that is sitting
    // at its top with a collapsed header and the page has a stub of a title
    // over a gap it cannot scroll away. Fix it where it happens — when the
    // pager settles on a page whose content is already at the top, expand the
    // header back. Deliberately keyed on settledPage, not currentPage: doing
    // this mid-drag would fight the finger.
    // Which page's scroller is which, in one place, because the rule below needs
    // to ask each of them two questions and asking them in two `when`s is how the
    // Create tab's grid got read from the wrong state once already.
    fun atTopOf(tab: Tab): Boolean = when (tab) {
        Tab.TOYS ->
            toysListState.firstVisibleItemIndex == 0 &&
                toysListState.firstVisibleItemScrollOffset == 0
        // The Create tab is a LazyVerticalGrid, so its scroller is the grid
        // state that lives on [CreateState] and NOT the `createListState`
        // hoisted above with the other tabs' scrollers — that one is still
        // passed to [CreateTab], and unused. Reading it here would see a
        // permanent zero and re-expand the header every time the pager settled
        // on a scrolled Create tab.
        Tab.CREATE ->
            createState.gridState.firstVisibleItemIndex == 0 &&
                createState.gridState.firstVisibleItemScrollOffset == 0
        Tab.SETTINGS -> settingsScrollState.value == 0
        Tab.TUTORIAL -> tutorialScrollState.value == 0
    }

    /** Is the user's finger currently moving this page's content? */
    fun busy(tab: Tab): Boolean = when (tab) {
        Tab.TOYS -> toysListState.isScrollInProgress
        Tab.CREATE -> createState.gridState.isScrollInProgress
        Tab.SETTINGS -> settingsScrollState.isScrollInProgress
        Tab.TUTORIAL -> tutorialScrollState.isScrollInProgress
    }

    // Arriving on Create is what arms its one-off tutorial offer — see
    // [CreateState.visited] for why the tab cannot work this out for itself, and
    // [CreateTourOffer] for what is done with it.
    //
    // On **settledPage**, unlike the header rule below, and the difference is the
    // point: the header cares which page you are heading for, this cares which
    // page you actually reached. Armed at the halfway mark, a swipe begun towards
    // Create and pulled back would offer a tour of a tab the user never landed on.
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            if (Tab.entries[page] == Tab.CREATE) createState.visited = true
        }
    }

    // **The header re-expands as the page arrives, not after it, and any touch
    // wins.**
    //
    // Two costs used to run in series: the pager's slide, and then this
    // animation. Switching tabs therefore meant waiting out both before the new
    // page would scroll — and because `animate` cannot be stopped from outside,
    // a finger put down during it was ignored rather than obeyed. The nested
    // scroll connection and this animation both write `heightOffset`, so while
    // this was running it simply overwrote whatever the drag asked for.
    //
    // **Keyed on currentPage**, which crosses at the halfway mark, so the header
    // finishes with the slide instead of starting when the slide ends. The
    // earlier note here said settledPage was deliberate because "doing this
    // mid-drag would fight the finger" — that was the right worry and the wrong
    // trade. Nothing here fights a horizontal drag (this moves the header, the
    // finger moves the pages), and the serial wait it bought is the thing the
    // user actually felt. `collectLatest` abandons an in-flight expansion if the
    // page changes again, so a swipe pulled back leaves the header part-expanded
    // — a legal state the next scroll resolves, and a cheaper one than the wait.
    //
    // **Interruptible**, which is the half that matters most: the moment the
    // arrived page's own scroller starts moving, the expansion is cancelled and
    // the nested scroll owns the header again. Already scrolling when the page
    // lands? Then `first { it }` fires at once and the expansion never starts.
    // `contentOffset` is only cleared on a completed expansion — an interrupted
    // one has not reached the top, so claiming it had would leave the header
    // thinking no content is tucked under it.
    LaunchedEffect(pagerState, scrollBehavior) {
        snapshotFlow { pagerState.currentPage }.collectLatest { page ->
            val tab = Tab.entries[page]
            if (!atTopOf(tab)) return@collectLatest
            val from = scrollBehavior.state.heightOffset
            if (from == 0f) return@collectLatest
            coroutineScope {
                val expand = launch {
                    animate(
                        initialValue = from,
                        targetValue = 0f,
                        animationSpec = headerSpec,
                    ) { value, _ -> scrollBehavior.state.heightOffset = value }
                    // Same reset material3 does itself once a fling reaches the
                    // top: contentOffset only feeds overlappedFraction, and
                    // leaving a stale one behind makes the header think content
                    // is still tucked under it.
                    scrollBehavior.state.contentOffset = 0f
                }
                // Cancels the child, not this scope, so the collector survives to
                // handle the next page.
                val yieldToUser = launch {
                    snapshotFlow { busy(tab) }.first { it }
                    expand.cancel()
                }
                expand.join()
                yieldToUser.cancel()
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
                        // one bar serving every page), and it would have to slide inside
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
            // MD3 SHARED AXIS (X): the tabs are swipe-navigable, so they have
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
                // and scroll-stable, in both directions. A handful of light
                // pages: cheap.
                // EXPERIMENT, measured: every page stays composed, so a swipe
                // never recomposes one.
                //
                // At 1, the window covers only the adjacent pages, so moving
                // between Toys (0) and Settings (2) deactivates and later
                // re-composes whole pages — and `SettingsTab` is a
                // `Column(verticalScroll)`, so that means every row of it, not
                // just the visible ones. Measured on device: a Toys<->Settings
                // workload that never opens the Create tab spends 2.74% of its
                // frames over 33 ms with an `anim` p99 of 30 ms, against 0.38%
                // and 6 ms for scrolling the design grid. The cost is composing
                // pages, not drawing designs.
                //
                // [Tab] has four entries, so 3 keeps all of them alive at once.
                // They are light — the heavy one is a `LazyVerticalGrid` that
                // composes only its visible cells either way.
                beyondViewportPageCount = Tab.entries.size - 1,
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
                    Tab.CREATE -> CreateTab(pagePadding, createListState, createState)
                    Tab.SETTINGS -> SettingsTab(
                        pagePadding,
                        settingsScrollState,
                        setup,
                        setupTick,
                    ) { setupTick++ }
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
            // The `+` FAB belongs to ONE page, so its visibility is read off the
            // same DISCRETE targetPage as the chip tint — deliberately not off
            // `currentPageOffsetFraction`. The FAB changes the nav row's WIDTH,
            // and a width that tracked the drag offset would re-measure the pill
            // on every frame of every swipe in the app, which is precisely the
            // defect [NavOverlayPadding] documents. Discrete in, animated out.
            fabVisible = pagerState.targetPage == Tab.CREATE.ordinal,
            // One bit, from the SAME [SetupStatus] the checklist rows are drawn
            // from (see the probe above). Discrete and rare — it changes only
            // when the user actually finishes a setup item — so it costs the pill
            // one recomposition on the day it flips and nothing on any other.
            setupNeedsAttention = setup.needsAttention,
            onFabClick = { createState.newDesignRequested = true },
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
 * per tab, plus the Create tab's `+` [NavFab] as a SIBLING of the capsule.
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
 *
 * ## The FAB is beside the pill, not on the app bar
 *
 * `[pill] [gap] [FAB]`, laid out as a Row and centred AS A GROUP. That is the
 * relationship in the reference: one round button riding alongside the capsule,
 * vertically centred with it. Because the group is what is centred, the pill
 * re-centres on its own the moment the FAB is gone — there is no reserved slot
 * leaving the capsule permanently off-axis on the other three tabs.
 *
 * ## Width budget
 *
 * At the largest accessibility font scale the pill's chips grow taller (the
 * label's line height passes the 24 dp icon), and the ONE expanded label grows
 * wider. Worst case is the longest caption, "Tutorials", selected:
 *
 *     4 chips x 48 dp        192 dp   (a chip is a 48 dp circle at rest)
 *     + expanded label       ~117 dp  (9 chars at ~24 sp) + 8 dp lead-in
 *     + chip gaps / padding    28 dp  (3 x 4 dp between + 2 x 6 dp inside)
 *     + FAB slot               64 dp  (NAV_FAB_GAP + 56 dp)
 *     = ~409 dp against ~448 dp of usable width
 *
 * It fits, with ~20 dp of margin each side. The safety valve if it ever stops
 * fitting already exists and needs no code: only the SELECTED chip shows its
 * label, so the pill is at its widest for exactly one chip at a time.
 *
 * ## The badge
 *
 * [setupNeedsAttention] puts an [AttentionBadge] on the Settings chip when the
 * Initial setup checklist has an outstanding item. It is a plain Boolean rather
 * than anything resembling a badge model: there is one badgeable destination in
 * this app and one condition that badges it, and a general "badges per tab" API
 * would be four times the surface for no second caller. It defaults to `false`
 * so the guided demo — which draws this same pill with no pager and no
 * checklist behind it (see `DesignDemoActivity`) — is unaffected and shows the
 * nav bar in its ordinary state.
 *
 * It also costs the pill's LAYOUT nothing: the badge is drawn inside the chip's
 * existing 24 dp icon slot with an offset, so no width in the budget above moves
 * when it appears.
 */
@Composable
internal fun FloatingNavBar(
    selected: Int,
    position: () -> Float,
    fabVisible: Boolean,
    onFabClick: () -> Unit,
    onSelect: (Int) -> Unit,
    onPillHeight: (Dp) -> Unit,
    modifier: Modifier = Modifier,
    setupNeedsAttention: Boolean = false,
) {
    val pill = MaterialTheme.navPill
    val density = LocalDensity.current
    Box(
        modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = NAV_PILL_MARGIN),
        contentAlignment = Alignment.Center,
    ) {
        // `[pill] [gap] [FAB]`, centred AS A GROUP by the Box above. See this
        // function's KDoc.
        Row(verticalAlignment = Alignment.CenterVertically) {
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
                // capsule: four chips are at least 4 × 48 dp wide against a 60 dp
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
                                badge = setupNeedsAttention && t == Tab.SETTINGS,
                            ) { onSelect(i) }
                        }
                    }
                }
            }

            // Carries its own leading gap, so the pill is followed by exactly
            // nothing when the FAB is away.
            NavFab(visible = fabVisible, onClick = onFabClick)
        }
    }
}

/**
 * Gap between the pill's right edge and the FAB.
 *
 * Small on purpose: the two read as one control group riding together, not as a
 * button that happens to be nearby. Owned by [NavFab] rather than by the Row's
 * arrangement, because it has to vanish along with the button — an
 * `Arrangement.spacedBy` would leave 10 dp of nothing hanging off the pill on
 * every other tab, pushing the capsule permanently off centre.
 */
private val NAV_FAB_GAP = 10.dp

/** MD3's standard FAB diameter; named because the width budget is stated in it. */
private val NAV_FAB_SIZE = 56.dp

/**
 * The Create tab's `+`, riding beside the nav pill.
 *
 * ## Why it animates the way it does
 *
 * Three things have to happen together when the pager settles on (or leaves)
 * the Create tab, and **none of them may cut**:
 *
 * 1. the button fades in,
 * 2. the button scales up,
 * 3. the pill *slides* left to make room, because the two are centred as a group.
 *
 * (3) is the awkward one. `AnimatedVisibility` with a `fadeIn + scaleIn` enter
 * gives the child its FULL size from the first frame, so the Row would jump to
 * its final width instantly and the pill would teleport into its new position
 * while the button faded in on top of the result. `Modifier.animateContentSize`
 * on the Row would fix that and introduce a far worse bug: the pill's own width
 * changes on EVERY FRAME of every swipe (see [NavChip] — the selected chip's
 * label grows continuously), so an animated content size would spend the entire
 * app lagging behind the label instead of tracking it.
 *
 * So the reveal is done the same way [NavChip] reveals its label: the button is
 * measured at its true width and *reported* at a fraction of it, from a
 * `layout { }` block. The Row's width therefore grows continuously from
 * pill-only to pill + gap + button, the Box centres whatever it is handed, and
 * the pill slides across for free. The fraction is an [Animatable] driven by a
 * `LaunchedEffect` keyed on the DISCRETE [visible] flag — it animates for a few
 * hundred milliseconds per tab change and is idle at every other moment, which
 * is the difference between this and the per-frame layout the KDoc above warns
 * about.
 *
 * Phase discipline is the same rule as [NavChip]: `reveal` and `fade` are read
 * ONLY from the `layout` and `graphicsLayer` lambdas, never from the composable
 * body, so the animation costs a relayout and a redraw and not a recomposition.
 *
 * ## Presence
 *
 * [present] keeps the button composed for exactly as long as it is on screen,
 * including its exit. Leaving it composed permanently at zero width would leave
 * a full 56 dp of invisible, ripple-ing touch target parked next to the pill on
 * the other three tabs; removing it the instant [visible] goes false would cut
 * the exit. Flipping it once at each end of the transition costs two
 * recompositions per tab change.
 *
 * The ripple stays. This app strips ripples from TOGGLES ([NoRipple]), whose own
 * state animation already acknowledges the touch; a FAB is a plain button with
 * no state of its own, so the ripple is its only feedback.
 *
 * ## The fill
 *
 * It is the one coloured control in the app: Nothing's red and blue, moving, from
 * a shader. [LiquidFabFill] holds all of it — including the frame loop, which is
 * bounded by being composed inside [present] and so runs only while the button is
 * actually on screen. That is why the fill is a composable in the content slot
 * rather than a modifier on this button: composed-ness is the gate, and the
 * cheapest way to spell "stop when it leaves" is to leave the composition.
 */
@Composable
private fun NavFab(visible: Boolean, onClick: () -> Unit) {
    val pill = MaterialTheme.navPill
    // Width and scale are GEOMETRY → the spatial spring (under-damped, so the
    // button lands with a small pop). Alpha is an effect → the effects spring,
    // which never bounces; a bouncing alpha would flicker the button as it
    // settles. Same split as everything else in this file.
    val revealSpec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    val fadeSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val reveal = remember { Animatable(0f) }
    val fade = remember { Animatable(0f) }
    var present by remember { mutableStateOf(false) }
    LaunchedEffect(visible) {
        if (visible) present = true
        val target = if (visible) 1f else 0f
        // Both springs run concurrently and the flag is cleared only once BOTH
        // have finished. A cancellation (the user swiping straight back) throws
        // out of the coroutineScope, so the clear is skipped and the relaunched
        // effect takes over from wherever the values got to — the Animatables
        // are never snapped.
        coroutineScope {
            launch { fade.animateTo(target, fadeSpec) }
            launch { reveal.animateTo(target, revealSpec) }
        }
        if (!visible) present = false
    }
    if (!present) return

    Box(
        Modifier
            // LAYOUT phase. Measures gap + button at their true width and
            // reports a fraction of it, which is what slides the pill.
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                val width = (placeable.width * reveal.value)
                    .roundToInt()
                    .coerceIn(0, placeable.width)
                layout(width, placeable.height) { placeable.place(0, 0) }
            }
            // Inside the measured content, so the gap shrinks away with the
            // button instead of surviving it. See [NAV_FAB_GAP].
            .padding(start = NAV_FAB_GAP),
    ) {
        FloatingActionButton(
            onClick = onClick,
            modifier = Modifier
                .size(NAV_FAB_SIZE)
                // Inert unless a guided tour is hosting this pill; see
                // [demoTarget]. Placed before the graphicsLayer so the reported
                // bounds are the button's laid-out slot rather than a rectangle
                // that shrinks with the reveal animation.
                .demoTarget(DemoTarget.FAB)
                // DRAW phase — the layer block re-runs without recomposing.
                .graphicsLayer {
                    // coerceAtLeast, not coerceIn: the spatial spring is
                    // under-damped, so it undershoots below 0 on the way out
                    // (a negative scale is a MIRRORED draw, not an absent one)
                    // and overshoots past 1 on the way in, which is the pop we
                    // want and must not clip away.
                    val s = reveal.value.coerceAtLeast(0f)
                    scaleX = s
                    scaleY = s
                    alpha = fade.value.coerceIn(0f, 1f)
                },
            shape = CircleShape,
            // The liquid's own dark blue, and it stays OPAQUE — see
            // [NavPillColors.fabContainer]. The moving fill is drawn in the
            // content slot below rather than in place of this, which is what
            // leaves the shadow and the ripple exactly as they were.
            containerColor = pill.fabContainer,
            contentColor = pill.fabContent,
            // Matched to the pill's own 8 dp so the two sit at the same height
            // above the page. Shadow, not tonal: tonal elevation is a deliberate
            // visual no-op in this theme.
            elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 8.dp),
        ) {
            // The FAB's content slot is a centring Box, so this fills the button
            // and the icon lands on top of it. Order is the whole arrangement:
            // fill (content, drawn first) → icon (content) → ripple (drawn by the
            // clickable ABOVE its content, so it survives).
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                LiquidFabFill(Modifier.fillMaxSize())
                Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.create_new))
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
    badge: Boolean = false,
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
        //
        // The Box wraps the icon exactly — `offset` does not change a measured
        // size — so a badged chip is the same width as an unbadged one and the
        // pill's width budget (see [FloatingNavBar]) is untouched.
        Box {
            Icon(tab.icon, contentDescription = stringResource(tab.caption), tint = tint)
            if (badge) {
                AttentionBadge(
                    Modifier
                        .align(Alignment.TopEnd)
                        // Out past the icon's top-end corner, far enough to read
                        // as a badge ON the icon rather than as part of the
                        // glyph, and no further: the chip is `clip`ped to
                        // [NAV_CHIP_SHAPE], which at rest is a 48 dp circle, so
                        // anything that leaves a radius of 24 dp from the chip's
                        // centre is silently sliced. At this offset the badge's
                        // own disc reaches ~19 dp from that centre.
                        .offset(x = 4.dp, y = (-4).dp),
                )
            }
        }
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

/**
 * Diameter of the nav-bar attention badge.
 *
 * Sized by what it has to survive rather than by taste. The chip is `clip`ped to
 * [NAV_CHIP_SHAPE] — a 48 dp circle at rest — and the badge hangs off the corner
 * of a 24 dp icon centred in it, so the constraint is that the badge's disc stays
 * inside a radius of 24 dp from the chip's centre. At 16 dp with the 4 dp corner
 * offset [NavChip] applies, the disc's centre sits ~11 dp out and its rim ~19 dp
 * out: clear, with room for the chip to grow (it only ever gets *wider*, and
 * `percent = 50` resolves on the shorter side, so this margin is the worst case).
 *
 * 16 dp is also MD3's own size for a badge WITH content, which is what this is —
 * it carries the exclamation mark, and the mark is the point.
 */
private val NAV_BADGE_SIZE = 16.dp

/**
 * The red dot with a white `!` that marks the Settings destination while the
 * Initial setup checklist still has an outstanding item.
 *
 * ## The colour, which is the theme's third and last exception
 *
 * This app is monochrome by rule and the rule is kept honest by *enumerating* its
 * exceptions (see `ui/theme/Theme.kt`). This is the third: the FAB's brand
 * red/blue, the device illustration's recording dot, and this badge. It is the
 * only one of the three that uses hue to carry MEANING, which is why it needed
 * asking for rather than deriving — and why it stays this small and this rare.
 *
 * The red is `NothingRed`, the brand value already in the app, not a new shade
 * and not `RECORDING_DOT_COLOR` (whose own KDoc says it is a picture of hardware
 * and stops being a defensible exception the moment it is used as an accent). It
 * is also the better of the two on the number that matters here: the mark is
 * white, and white on `#D71921` is **5.18:1**, past WCAG AA for text and more
 * than double the 3:1 a graphical object needs, where white on `#E0392C` is only
 * 4.38:1.
 *
 * Because the disc is OPAQUE, that ratio is the whole legibility story on both
 * nav-bar surfaces — the pill is near-black in either scheme and the selected
 * chip's fill is near-white, and the mark's contrast is against the red in both
 * cases. The disc itself is the thing whose separation varies: 2.6:1 against the
 * pill, 4.6:1 against the selected chip's near-white fill, both comfortably
 * visible for a solid shape of this size.
 *
 * ## Why an exclamation mark and not a plain dot
 *
 * Colour is never the only signal in this app, and a red dot on a nav icon is
 * colour and nothing else — invisible to a colour-blind user against a grey
 * chip, and meaningless in a greyscale screenshot. The mark carries the meaning;
 * the red only makes it urgent. It is drawn rather than typed so that it keeps
 * its proportions inside a 16 dp disc at every font scale, which a `Text("!")`
 * would not.
 *
 * The [contentDescription] is the third channel: the chip merges its
 * descendants' semantics, so TalkBack announces the destination and then this.
 */
@Composable
private fun AttentionBadge(modifier: Modifier = Modifier) {
    val pill = MaterialTheme.navPill
    val label = stringResource(R.string.nav_setup_needs_attention)
    Canvas(
        modifier
            .size(NAV_BADGE_SIZE)
            .semantics { contentDescription = label },
    ) {
        val r = size.minDimension / 2f
        drawCircle(color = pill.badgeContainer, radius = r)
        // The mark: a bar and a dot, laid out symmetrically about the centre so
        // it reads as an exclamation rather than as a stripe. Stroke 0.28r; the
        // bar spans 0.5r..1.0r and the round caps take it to 0.36r..1.14r, the
        // dot sits at 1.5r with the same radius as the cap (0.14r) and so ends at
        // 1.64r — the same 0.36r of clearance top and bottom.
        val stroke = r * 0.28f
        val cx = size.width / 2f
        drawLine(
            color = pill.badgeContent,
            start = Offset(cx, r * 0.5f),
            end = Offset(cx, r * 1.0f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawCircle(
            color = pill.badgeContent,
            radius = stroke / 2f,
            center = Offset(cx, r * 1.5f),
        )
    }
}

// ---------- Glyph Toys tab ----------

/**
 * Makes [id] the toy on the matrix: persist it and switch the live session to it
 * immediately. The Toys tab's pref-change listener then moves the highlight. If
 * capture is off (no session), it still persists and shows the next time a
 * session runs.
 *
 * `internal` and top-level rather than local to [ToysTab] because it is now
 * *the* way anything in the UI puts a toy on the matrix — the Create tab's
 * "show this design on the Glyph Matrix" goes through it too (see
 * `showDesignOnMatrix`). A second copy of these two lines would be a second
 * place for the `revive()` that wakes a dormant session to be forgotten. Same
 * promotion the Create tab already made of [SectionCard] and [HintText].
 */
internal fun selectToy(id: String) {
    DebugLog.i("Ui", "set active toy '$id'")
    Core.arbiter.revive()
    Core.scheduler.run { Core.screenManager.selectScreen(id) }
}

@Composable
private fun ToysTab(innerPadding: PaddingValues, listState: LazyListState) {
    var dialogId by remember { mutableStateOf<String?>(null) }

    // The toy currently on the matrix: tracks the persisted current screen live,
    // including while this page is off screen and while the Essential Key cycles
    // it from outside this UI entirely.
    //
    // ## What this replaced, and why it had to
    //
    // This was a `remember { mutableStateOf(prefs.getString(...)) }` seeded once,
    // a `DisposableEffect` that registered a change listener, and a
    // `LifecycleResumeEffect` that re-read on every resume. The KDoc on it argued
    // that "a pager composes any given page index at most once, so this cannot
    // double-register". Double-registration was never the risk; the OPPOSITE was.
    //
    // This page is index 0 and `beyondViewportPageCount` is 1, so the moment the
    // user is on Settings (index 2) this page leaves the window — and a page that
    // leaves the window is DEACTIVATED AND RETAINED, not destroyed: Compose
    // clears the group's `RememberObserver`s (the `DisposableEffect` disposes, the
    // listener goes) and leaves plain remembered values alone (the
    // `mutableStateOf` keeps its old id). Enabling the accessibility service is a
    // Settings-tab journey, so the reported repro puts this page in exactly that
    // state, and any Essential-Key press while it is there — the first thing
    // anyone does after enabling the key — moved the current screen with nobody
    // listening. Coming back showed a highlight one toy behind reality, and
    // tapping the toy that was really current wrote the id the store already
    // held, which `SharedPreferences` does not report as a change at all. Hence
    // "the play button does nothing until I background the app": only the resume
    // re-read could repair it.
    //
    // [rememberPref] fixes the asymmetry at its root — it cannot hold a value it
    // did not get from a live subscription — so the resume re-read is gone with
    // it. Do not add one back; see [rememberPref] for why it is a mask.
    val currentToy by rememberPref(PrefKeys.CURRENT_SCREEN) {
        it.getString(PrefKeys.CURRENT_SCREEN, PrefKeys.CURRENT_SCREEN_DEF)
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

/** `POST_NOTIFICATIONS` — the Timer chime. */
private val SETUP_NOTIFICATION_PERMISSIONS = arrayOf(Manifest.permission.POST_NOTIFICATIONS)

/** `RECORD_AUDIO` — the music visualizer. */
private val SETUP_MICROPHONE_PERMISSIONS = arrayOf(Manifest.permission.RECORD_AUDIO)

/**
 * Location for the compass's magnetic-declination correction. Coarse is the only
 * grade the app declares: declination varies over hundreds of kilometres, so a
 * metre-accurate fix would buy nothing and cost a scarier permission prompt.
 *
 * Still an array because [probeSetup] takes one and asks for `any`.
 */
private val SETUP_LOCATION_PERMISSIONS = arrayOf(
    Manifest.permission.ACCESS_COARSE_LOCATION,
)

/**
 * Asks the system the six Initial setup questions, once.
 *
 * The **only** place any of them is asked. Both readers — the checklist rows and
 * the nav bar's [AttentionBadge] — take the [SetupStatus] this returns, which is
 * what stops the badge and the rows from drifting apart; see that class's KDoc
 * for why that mattered enough to hoist.
 *
 * Every line is a synchronous system call with no I/O behind it, so this is cheap
 * enough to run on each resume from the composition (which is what
 * [MainScreen] does).
 */
private fun probeSetup(context: Context): SetupStatus {
    fun anyGranted(permissions: Array<String>) =
        permissions.any { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    return SetupStatus(
        accessibility = isEssentialKeyServiceEnabled(context),
        // No system setting or SDK call exposes the selected always-on toy, and
        // the system binds the chosen toy LAZILY — often never, because the
        // accessibility-driven session does the day-to-day rendering. So this is
        // a latch: the system only ever binds or messages the toy it has
        // selected, and once that has happened the selection is proven.
        // (Deselection is equally invisible, so the mark cannot clear itself —
        // the row still opens the picker.)
        alwaysOnToy = Core.arbiter.owner == SessionArbiter.Owner.TOY ||
            Core.prefs.getLong(PrefKeys.TOY_LAST_BOUND, PrefKeys.TOY_LAST_BOUND_DEF) > 0L,
        notifications = anyGranted(SETUP_NOTIFICATION_PERMISSIONS),
        microphone = anyGranted(SETUP_MICROPHONE_PERMISSIONS),
        location = anyGranted(SETUP_LOCATION_PERMISSIONS),
        exactAlarms = context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true,
    )
}

/**
 * The Settings page: the Initial setup checklist, then App settings, then AI
 * settings.
 *
 * [setup] and [refreshTick] are both hoisted into [MainScreen]. The status is,
 * because the nav bar's badge reads it too and the pill is not in this subtree;
 * the tick rides along rather than being re-derived here so that there is exactly
 * one "re-ask the system" signal on this screen — the rows below, the app-settings
 * rows that re-read prefs, and the badge all move together on it. [onRefresh]
 * raises it from this page, which is what the in-app permission dialog needs.
 */
@Composable
private fun SettingsTab(
    innerPadding: PaddingValues,
    scrollState: ScrollState,
    setup: SetupStatus,
    refreshTick: Int,
    onRefresh: () -> Unit,
) {
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { onRefresh() }

    // Scroll state hoisted by [MainScreen]; see [ToysTab].
    Column(Modifier.fillMaxSize().verticalScroll(scrollState)) {
        Spacer(Modifier.height(innerPadding.calculateTopPadding()))

        // Initial setup is a section that closes, and App settings is not. That
        // asymmetry is the point: the checklist is a first-run job that is
        // finished and stays finished, so once it is all check marks it is six
        // rows of noise above the settings people actually come back for. App
        // settings is what they came for, so it is never a click away.
        //
        // It starts closed only when there is nothing to see. An unfinished item
        // is the one case where the collapsed section is hiding the answer to the
        // question that brought the user here — the badge on the nav chip (see
        // [AttentionBadge]) points at this page, and a page that then shows a
        // closed section is a dead end. So the section opens itself on arrival,
        // off the SAME [SetupStatus] the rows are drawn from.
        //
        // The initializer is what makes this the INITIAL state and nothing more.
        // rememberSaveable runs it once — on first composition, never on a
        // restore — so a user who closes the section keeps it closed while the
        // screen lives, through rotation and through the page being disposed and
        // rebuilt as the pager's window moves, even though the items are still
        // outstanding. Recomputing this on recomposition would re-open the
        // section under the finger that just shut it.
        //
        // Like the old fixed `false`, it deliberately does NOT persist across
        // launches: reopening the app is exactly when "is my setup still fine?"
        // is worth re-asking.
        var setupExpanded by rememberSaveable { mutableStateOf(setup.needsAttention) }
        CollapsibleSectionHeader(
            text = stringResource(R.string.section_initial_setup),
            expanded = setupExpanded,
            onToggle = { setupExpanded = !setupExpanded },
        )
        AnimatedVisibility(
            visible = setupExpanded,
            // The group's height is a SIZE and its opacity is not, so the two
            // ride the specs this app gives each: an under-damped spatial spring
            // for the expansion, a never-bouncing effects spring for the fade.
            enter = expandVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) +
                fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()),
            exit = shrinkVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) +
                fadeOut(MaterialTheme.motionScheme.defaultEffectsSpec()),
            label = "initialSetupSection",
        ) {
            // The hint belongs to the group, so it collapses with it.
            Column {
                SectionCard {
                    item {
                        val a11yEnabled = setup.accessibility
                        // Read through stringResource rather than
                        // context.getString: values pulled off LocalContext do
                        // not follow a configuration change, and Compose lints
                        // it as an error.
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
                    }
                    item {
                        val toyOk = setup.alwaysOnToy
                        ChecklistRow(
                            title = stringResource(R.string.checklist_toy),
                            subtitle = stringResource(
                                if (toyOk) R.string.checklist_toy_on else R.string.checklist_toy_hint,
                            ),
                            good = if (toyOk) true else null,
                        ) {
                            if (!openGlyphToySettings(context)) {
                                Toast.makeText(context, R.string.glyph_settings_unavailable, Toast.LENGTH_SHORT)
                                    .show()
                            }
                        }
                    }
                    item {
                        PermissionRow(
                            stringResource(R.string.checklist_notifications),
                            SETUP_NOTIFICATION_PERMISSIONS,
                            setup.notifications,
                        ) { permissionLauncher.launch(it) }
                    }
                    item {
                        PermissionRow(
                            stringResource(R.string.checklist_mic),
                            SETUP_MICROPHONE_PERMISSIONS,
                            setup.microphone,
                        ) { permissionLauncher.launch(it) }
                    }
                    item {
                        PermissionRow(
                            stringResource(R.string.checklist_location),
                            SETUP_LOCATION_PERMISSIONS,
                            setup.location,
                        ) { permissionLauncher.launch(it) }
                    }
                    item {
                        val alarmsOk = setup.exactAlarms
                        ChecklistRow(
                            title = stringResource(R.string.checklist_exact_alarm),
                            subtitle = stringResource(
                                if (alarmsOk) R.string.checklist_granted else R.string.checklist_tap_to_grant,
                            ),
                            good = alarmsOk,
                        ) {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                    Uri.parse("package:${context.packageName}"),
                                ),
                            )
                        }
                    }
                    // **The walkthrough, and deliberately NOT a checklist item.**
                    //
                    // Every row above answers a yes/no question about this phone,
                    // and [SetupStatus] is the six of them; the badge and the
                    // auto-expand are that record's `needsAttention`. This row has
                    // no state at all — there is no such thing as "the
                    // walkthrough is unsatisfied" — so it is deliberately absent
                    // from [SetupStatus], which is what keeps it out of both.
                    // Adding a seventh field for it would put a permanent
                    // exclamation mark on the Settings tab.
                    //
                    // A [PrefRow] rather than a [ChecklistRow] for the same
                    // reason: `ChecklistRow` draws a tick or a question mark, and
                    // a question mark here would say something is wrong.
                    //
                    // **It resets nothing.** It starts the walkthrough activity
                    // directly and writes no preference on the way in, so backing
                    // out of it leaves everything — including `ONBOARDING_DONE` —
                    // exactly as it was. That is the difference between this and
                    // the `restart_onboarding` debug hook at the top of this file,
                    // which clears the flag first.
                    item {
                        PrefRow(
                            lines = PrefRowLines.TWO,
                            leading = { PrefIcon(Icons.Outlined.Slideshow) },
                            onClick = {
                                context.startActivity(
                                    Intent(context, OnboardingActivity::class.java),
                                )
                            },
                        ) {
                            Text(
                                stringResource(R.string.checklist_walkthrough),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                stringResource(R.string.checklist_walkthrough_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                HintText(stringResource(R.string.checklist_hint_guides))
            }
        }

        SectionHeader(stringResource(R.string.section_app_settings))
        SectionCard {
            item {
                var master by remember(refreshTick) {
                    mutableStateOf(Core.prefs.getBoolean(PrefKeys.MASTER_TOGGLE, PrefKeys.MASTER_TOGGLE_DEF))
                }
                SwitchRow(
                    title = stringResource(R.string.master_toggle),
                    subtitle = stringResource(R.string.master_toggle_summary),
                    checked = master,
                    leading = Icons.Outlined.Key,
                ) {
                    master = it
                    Core.prefs.putBoolean(PrefKeys.MASTER_TOGGLE, it)
                }
            }
            item {
                var menuMode by remember(refreshTick) {
                    mutableStateOf(Core.prefs.getBoolean(PrefKeys.MENU_MODE_ENABLED, PrefKeys.MENU_MODE_ENABLED_DEF))
                }
                SwitchRow(
                    title = stringResource(R.string.pref_menu_mode),
                    subtitle = stringResource(R.string.pref_menu_mode_summary),
                    checked = menuMode,
                    leading = Icons.AutoMirrored.Outlined.List,
                ) {
                    menuMode = it
                    Core.prefs.putBoolean(PrefKeys.MENU_MODE_ENABLED, it)
                }
            }
            item {
                var use12h by remember(refreshTick) {
                    mutableStateOf(Core.prefs.getBoolean(PrefKeys.USE_12H, false))
                }
                SwitchRow(
                    title = stringResource(R.string.pref_use12h),
                    subtitle = null,
                    checked = use12h,
                    leading = Icons.Outlined.Schedule,
                ) {
                    use12h = it
                    Core.prefs.putBoolean(PrefKeys.USE_12H, it)
                }
            }
            item { BrightnessRow() }
            item { CreatorNameRow() }
            // Present only in the GitHub build — Play distributes its own
            // updates, and pointing users at an APK outside Play would breach the
            // Device and Network Abuse policy. See `ui/OptionalFeatures.kt`.
            item { UpdateSettingsRow() }
        }

        // The design assistant's own group — header, the three rows and the hint
        // — or nothing at all in the Play build, which ships without the
        // assistant. One seam rather than a flag: the code is not in that APK.
        AiSettingsSection()

        Spacer(Modifier.height(innerPadding.calculateBottomPadding() + NAV_PILL_CLEARANCE))
    }
}

/**
 * The Glyph's brightness: an auto toggle and a slider, under one title.
 *
 * A [PrefRow] like every other row of the group rather than a bare `Column`,
 * so its leading icon lands on the same 16 dp inset and the same 16 dp gap as
 * the rows above and below it. It declares itself THREE-line because that is
 * the padding (12 dp) the block has always had; nothing here fits in 88 dp, so
 * the minimum never binds.
 */
@Composable
private fun BrightnessRow() {
    PrefRow(lines = PrefRowLines.THREE, leading = { PrefIcon(Icons.Outlined.BrightnessMedium) }) {
        Text(stringResource(R.string.brightness), style = MaterialTheme.typography.titleMedium)
        // Auto-brightness writes BRIGHTNESS from the render thread, so the slider
        // must follow the PREF rather than a local copy of it — otherwise it
        // shows what the user last dragged instead of what auto is doing.
        //
        // Through [rememberPref] for the reason spelled out in [ToysTab]: this
        // page is index 2, so it leaves the pager's window whenever the user is
        // on Toys (index 0), and the hand-rolled seed-plus-listener this replaced
        // lost every auto-brightness step that landed while it was away — with
        // its own remembered value surviving to hide the fact. No local
        // assignment on either control now either; the pref is the single source
        // and the write comes straight back through the subscription on the same
        // (main) thread.
        val brightness by rememberPref(PrefKeys.BRIGHTNESS) {
            it.getFloat(PrefKeys.BRIGHTNESS, PrefKeys.BRIGHTNESS_DEF)
        }
        val auto by rememberPref(PrefKeys.AUTO_BRIGHTNESS) {
            it.getBoolean(PrefKeys.AUTO_BRIGHTNESS, PrefKeys.AUTO_BRIGHTNESS_DEF)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            // MD3's shape-morphing icon toggle: a full circle when auto
            // is off, squaring off to a 12 dp-cornered rounded square
            // when it is on, and pinching to 8 dp under the finger — the
            // library's own `toggleableShapes`, animated by it on the
            // theme's effects spring (never-bouncing, deliberately, so a
            // toggle cannot wobble).
            //
            // The morph once REPLACED a hand-rolled 1 dp outline that
            // faded in when the button was off. Both are here now, and
            // that is the user's own reversal after living with the
            // morph alone: on this card the unchecked container IS the
            // card (see [offStateOutline]), so until you switch it on
            // there was no shape on screen for the morph to be an
            // affordance for. Do not "restore" the morph-only version.
            // The outline costs no layout either: it is drawn inside the
            // container's own bounds, which the modifier sets — hence no
            // `size` here, and see [TOGGLE_CONTAINER_SIZE] for why the
            // control is 36 dp rather than 40.
            NoRipple {
                FilledIconToggleButton(
                    checked = auto,
                    onCheckedChange = { on -> Core.prefs.putBoolean(PrefKeys.AUTO_BRIGHTNESS, on) },
                    shapes = IconButtonDefaults.toggleableShapes(),
                    modifier = Modifier.offStateOutline(auto),
                ) {
                    Icon(
                        Icons.Outlined.BrightnessAuto,
                        contentDescription = stringResource(
                            if (auto) R.string.auto_brightness_on else R.string.auto_brightness_off,
                        ),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            // 4 dp, not the 12 this used to be. The gap you SEE is not
            // this number: the toggle's node is
            // `minimumInteractiveComponentSize`'s 48 dp square with a
            // 36 dp container centred in it (see [offStateOutline]), so
            // 6 dp of it is already empty, and the Slider keeps half a
            // thumb width inside its own bounds before the track starts.
            // 12 dp on top of those put ~18 dp of white between a toggle
            // and a slider that belong to each other, which the user
            // reported as comically wide. 4 then read as too tight, so
            // this is the middle of a range whose ends have both been
            // seen on device: ~16 dp optical, still visibly one control
            // pair rather than two neighbours. (Phase 17 did not touch
            // this line — the 12 had been here since the row was
            // written; what changed around it was the ring.)
            Spacer(Modifier.width(8.dp))
            Slider(
                value = brightness,
                onValueChange = {
                    // Fiddling with the slider means "I'll do it myself":
                    // drop out of auto first, so the controller has stopped
                    // polling before the manual value lands.
                    if (auto) Core.prefs.putBoolean(PrefKeys.AUTO_BRIGHTNESS, false)
                    Core.prefs.putFloat(PrefKeys.BRIGHTNESS, it.coerceIn(0.05f, 1f))
                },
                valueRange = 0.05f..1f,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * The name credited as `author` on designs made on this phone.
 *
 * An inline text field rather than a row that opens a dialog, following the
 * brightness block just above it: this is a value you glance at and occasionally
 * correct, not a decision that deserves its own surface.
 *
 * Written straight through to the pref on every keystroke, which is cheap
 * (`Prefs` is an async-committed SharedPreferences) and removes the entire class
 * of "I typed it but never pressed anything" bug — there is no Save button to
 * miss, and no half-entered state to reconcile on rotation.
 *
 * **It renames nothing.** `author` is stamped once, when a design is created,
 * and is immutable from then on (see `ui/CreateTab.kt`), so editing this affects
 * the NEXT design and no existing one. The summary string says so out loud,
 * because the opposite is a reasonable thing to assume.
 */
@Composable
private fun CreatorNameRow() {
    var creator by remember {
        mutableStateOf(Core.prefs.getString(PrefKeys.CREATOR_NAME, PrefKeys.CREATOR_NAME_DEF))
    }
    // THREE-line for the same reason as [BrightnessRow]: it is the 12 dp
    // padding this block has always had, and nothing in it fits in 88 dp.
    PrefRow(lines = PrefRowLines.THREE, leading = { PrefIcon(Icons.Outlined.Person) }) {
        Text(stringResource(R.string.pref_creator_name), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.pref_creator_name_summary),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = creator,
            onValueChange = {
                // Capped at the format's own author limit, and stripped of the
                // newlines a keyboard's paste can smuggle in: this string ends
                // up in a JSON file other people read.
                creator = it.replace('\n', ' ').take(DesignCodec.MAX_AUTHOR_LENGTH)
                Core.prefs.putString(PrefKeys.CREATOR_NAME, creator)
            },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            placeholder = { Text(stringResource(R.string.pref_creator_name_hint)) },
            singleLine = true,
        )
    }
}


// The topics that open a POP-UP, ordered as their rows are: the "how this works"
// guide first, then the two "the system is in your way" fixes.
//
// The Create guide is not in here. It is a full-screen guided demo of the real
// editor (`DesignDemoActivity`) rather than a dialog, because the thing it has to
// teach is a set of GESTURES — swipe to paint, hold a frame to move it — and a
// paragraph cannot teach either. Its row is presented like the others: title,
// one-line subtitle, one card of the same group, because that part was never the
// problem. (It used to say "same divider" — the group's rows are separated by a
// 2 dp gap of page background now, and nothing in this list draws a line. See
// [SectionCard].)
private enum class TutorialTopic { KEY, HANDOVER }

@Composable
private fun TutorialTab(innerPadding: PaddingValues, scrollState: ScrollState) {
    val context = LocalContext.current
    var topic by remember { mutableStateOf<TutorialTopic?>(null) }

    // Scroll state hoisted by [MainScreen]; see [ToysTab].
    Column(Modifier.fillMaxSize().verticalScroll(scrollState)) {
        Spacer(Modifier.height(innerPadding.calculateTopPadding()))

        HintText(stringResource(R.string.tut_hint))
        SectionCard {
            item {
                SetupRow(
                    title = stringResource(R.string.tut_title),
                    subtitle = stringResource(R.string.tut_button_subtitle),
                    good = null,
                ) { topic = TutorialTopic.KEY }
            }
            item {
                SetupRow(
                    title = stringResource(R.string.tut_create_title),
                    subtitle = stringResource(R.string.tut_create_subtitle),
                    good = null,
                ) { context.startActivity(DesignDemoActivity.intent(context)) }
            }
            item {
                SetupRow(
                    title = stringResource(R.string.tut_handover_title),
                    subtitle = stringResource(R.string.tut_handover_subtitle),
                    good = null,
                ) { topic = TutorialTopic.HANDOVER }
            }
            // Adds nothing in the Play build: a store install is never subject to
            // the restricted-setting block. It adds no `item`, so the group is
            // three rows there and the last one still gets the rounded corner.
            restrictedSettingsTutorialItem()
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
            // `DragIndicator`, not the hamburger `Menu` this carried: three
            // stacked bars is a *navigation drawer*, and it meant "drag me" here
            // only by the Holo-era convention that a list row's right-hand
            // hamburger is a grab handle. `DragIndicator` (the 2x3 dot grid) is
            // Material's own reorder-handle glyph and is what Android's own
            // reorderable lists use, so it says what this is without the
            // convention having to be known.
            Icon(
                Icons.Outlined.DragIndicator,
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
            //
            // **No off-state ring here**, and that is a decision rather than an
            // omission. Phase 17 added one on the argument that an off toggle on
            // a `surface` row is invisible; the user, who had asked for the ring
            // on the auto-brightness toggle and not on this, reported the result
            // immediately: nineteen rows each carrying an outlined circle turn a
            // list of toys into a wall of buttons, and every one of them looks
            // too big. The state this button carries is already spelled out
            // twice on the row — by the dot that fills beside the name and by
            // the row's own container — so the ring was the third telling and
            // the only one that cost the list its calm. See [offStateOutline].
            NoRipple {
                FilledIconToggleButton(
                    checked = shown,
                    onCheckedChange = { onSelect() },
                    shapes = IconButtonDefaults.toggleableShapes(),
                ) {
                    Icon(Icons.Outlined.PlayArrow, contentDescription = stringResource(R.string.set_active))
                }
            }
            if (id in CONFIGURABLE) {
                IconButton(onClick = onSettings) {
                    Icon(
                        Icons.Outlined.Settings,
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
internal fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.tertiary,
        modifier = Modifier.padding(start = SECTION_HEADER_START, top = 24.dp, bottom = 8.dp),
    )
}

/** [SectionHeader]'s text inset. Shared so the collapsible variant cannot drift. */
private val SECTION_HEADER_START = 24.dp

/**
 * A [SectionHeader] that opens and closes the group under it.
 *
 * **The whole row is the control**, not just the chevron: the user has reported
 * before that people do not realise a small icon is tappable, so the tap target
 * is the full width of the page and the chevron is only the *indicator* of what
 * that tap will do. It carries the content description (the header text is
 * already read out beside it), so a screen reader says "Initial setup, Collapse"
 * rather than naming the icon twice.
 *
 * The chevron rotates rather than swapping glyphs: a rotation is a POSITION
 * change, so it rides [MaterialTheme.motionScheme]'s spatial spring like every
 * other movement in this app, and 180° means the same arrow points at the thing
 * the tap will produce in both states.
 */
@Composable
private fun CollapsibleSectionHeader(text: String, expanded: Boolean, onToggle: () -> Unit) {
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
        label = "sectionHeaderChevron",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(start = SECTION_HEADER_START, end = 16.dp, top = 24.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.weight(1f),
        )
        Icon(
            Icons.Outlined.ExpandMore,
            contentDescription = stringResource(
                if (expanded) R.string.section_collapse else R.string.section_expand,
            ),
            tint = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.graphicsLayer { rotationZ = rotation },
        )
    }
}

/**
 * Where one card sits inside its group — the only thing its corner radii depend
 * on. See [SectionCard] for why a group is many cards rather than one.
 *
 * Pure enough to unit-test, and tested, because "the last item of a two-item
 * group must be LAST and not MIDDLE" is exactly the sort of off-by-one that is
 * invisible until a corner is wrong on a device.
 */
internal enum class SectionItemPosition { ONLY, FIRST, MIDDLE, LAST }

/** [SectionItemPosition] for item [index] of a group of [count]. */
internal fun sectionItemPosition(index: Int, count: Int): SectionItemPosition = when {
    count <= 1 -> SectionItemPosition.ONLY
    index <= 0 -> SectionItemPosition.FIRST
    index >= count - 1 -> SectionItemPosition.LAST
    else -> SectionItemPosition.MIDDLE
}

/**
 * The OUTER corner of a group: the four corners that face the page.
 *
 * Measured off Nothing's own Settings and Gallery (7 groups sampled at 1.25 px
 * per dp): 19–23 px, mean 16.5 dp. It replaces a 24 dp that was never measured.
 */
private val SECTION_OUTER_CORNER = 16.dp

/**
 * The corner between two cards of the same group: 2–4 px in the same sample.
 * Nearly square, but not square — the gap between two items reads as a seam
 * rather than as a cut.
 */
private val SECTION_INNER_CORNER = 3.dp

/**
 * The gap between two cards of a group. Measured at 2–3 px, and the gap pixels
 * are exactly the PAGE background — which is why this is a gap and not a
 * divider (see [SectionCard]).
 */
private val SECTION_ITEM_GAP = 2.dp

/** The page margin either side of a group. Unchanged; measured at 20 px = 16 dp. */
private val SECTION_HORIZONTAL_MARGIN = 16.dp

/** The shape of one card, given where it sits in its group. */
private fun SectionItemPosition.shape(): RoundedCornerShape = when (this) {
    SectionItemPosition.ONLY -> RoundedCornerShape(SECTION_OUTER_CORNER)
    SectionItemPosition.FIRST -> RoundedCornerShape(
        topStart = SECTION_OUTER_CORNER,
        topEnd = SECTION_OUTER_CORNER,
        bottomStart = SECTION_INNER_CORNER,
        bottomEnd = SECTION_INNER_CORNER,
    )
    SectionItemPosition.MIDDLE -> RoundedCornerShape(SECTION_INNER_CORNER)
    SectionItemPosition.LAST -> RoundedCornerShape(
        topStart = SECTION_INNER_CORNER,
        topEnd = SECTION_INNER_CORNER,
        bottomStart = SECTION_OUTER_CORNER,
        bottomEnd = SECTION_OUTER_CORNER,
    )
}

/** Collects a group's rows so [SectionCard] can count them. See its KDoc. */
internal class SectionCardScope internal constructor() {
    private val entries = mutableListOf<@Composable () -> Unit>()

    internal val items: List<@Composable () -> Unit> get() = entries

    /** One row of the group, and therefore one card. */
    fun item(content: @Composable () -> Unit) {
        entries += content
    }
}

/**
 * A group of settings rows, drawn the way Nothing OS draws one.
 *
 * **Each row is its own card.** This used to be a single [Card] with 24 dp
 * corners and `HorizontalDivider`s between its rows, which is Material's
 * grouping and not the system's. Measured off Nothing's Settings and Gallery
 * (7 groups, screenshots at 1.25 px per dp), theirs is:
 *
 * - one card per row, separated by [SECTION_ITEM_GAP] of bare page background —
 *   the separator is the gap, so **there are no dividers**;
 * - [SECTION_OUTER_CORNER] on the four corners that face the page;
 * - [SECTION_INNER_CORNER] on every corner that faces another row of the group.
 *
 * That is why this takes a builder ([SectionCardScope.item]) rather than plain
 * content: a card's corners depend on how many siblings it has and where it sits
 * among them, so the group has to be able to COUNT its rows before it draws the
 * first one. Callers never pass an index — see [sectionItemPosition].
 *
 * The rows are invoked in a loop, so their identity is positional: a caller that
 * adds or removes rows conditionally would move state between them. No caller
 * does today; every group is a fixed list.
 */
@Composable
internal fun SectionCard(content: SectionCardScope.() -> Unit) {
    val items = SectionCardScope().apply(content).items
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = SECTION_HORIZONTAL_MARGIN),
        verticalArrangement = Arrangement.spacedBy(SECTION_ITEM_GAP),
    ) {
        items.forEachIndexed { index, item ->
            key(index) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = sectionItemPosition(index, items.size).shape(),
                ) {
                    item()
                }
            }
        }
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
 * The hairline ring that makes an icon toggle **visible while it is OFF**: an
 * outlined circle when unchecked, gone when checked. For the app's STATEFUL icon
 * toggles — auto-brightness, onion skin — and for the editor's repeat indicator.
 *
 * It also sizes the control, at [TOGGLE_CONTAINER_SIZE], and that is not a
 * convenience: the ring is drawn against that number, so a caller that set its
 * own size would move the container out from under the ring. One value, applied
 * and drawn in the same place.
 *
 * ## Why this exists again
 *
 * The auto-brightness toggle used to have a hand-rolled 1 dp outline; Phase 1b
 * replaced it with MD3's `toggleableShapes` morph on the argument that the shape
 * IS the affordance. Having lived with it, the user asked for the outline back
 * **in addition to** the morph. That is a deliberate reversal, not a regression
 * to tidy away, and the reason is measurable rather than aesthetic: this theme
 * pins every `surfaceContainer*` role to the card colour (see `ui/theme/Theme.kt`),
 * and `filledIconToggleButtonColors()`'s UNCHECKED container is the
 * `surfaceContainer` token — so an off toggle sitting on a card is the card,
 * exactly, in both schemes; there is no faint edge to find. There
 * is nothing to morph the shape *of* until you switch it on, which is the one
 * moment the affordance needed to have done its work. The morph still carries
 * the on/off difference; this carries "there is a control here at all".
 *
 * **It is not for every icon toggle.** Phase 17 also put it on the Toys tab's
 * "set as active toy" button, which the user had not asked for and did not want:
 * nineteen rows each carrying an outlined circle read as a list of heavy buttons
 * rather than as a list of toys. A ring earns its place on a toggle that stands
 * alone in a row of plain controls, not on one that repeats down a list.
 *
 * ## Why it stopped drawing outside the button, and how
 *
 * The ring used to be visibly LARGER than the filled squircle it becomes, so the
 * control appeared to change size as you switched it on. The reason is in
 * material3: `Surface` — which every icon toggle is built from — applies the
 * caller's modifier and then `.minimumInteractiveComponentSize()`, and that
 * layout node reports **48 dp whatever the container measures**, centring the
 * smaller painted background inside it. A `drawWithContent` in the caller's
 * modifier therefore sees a 48 dp box while the visible container is 40 (now
 * [TOGGLE_CONTAINER_SIZE]), and drew a ring 4 dp proud of it on every side.
 *
 * So the ring is no longer drawn to `size`: it is drawn to a
 * [TOGGLE_CONTAINER_SIZE] square **centred in whatever box this node was given**,
 * which is exactly where `minimumInteractiveComponentSize` puts the container.
 * Off and on now occupy the identical footprint, and the 48 dp touch target is
 * untouched — it is the thing that was making the ring big, and it is the one
 * part of this that must not change.
 *
 * ## The colour
 *
 * `onSurfaceVariant` at [TOGGLE_OUTLINE_ALPHA] — the supporting-text ink these
 * toggles sit beside, three quarters opaque so it reads as the container's
 * boundary rather than as a drawn black circle, which is what the user reported
 * of the full-strength version. Over the card that every one of these toggles
 * sits on that composites to #919197 in light and #696C71 in dark: **3.14:1 and
 * 3.23:1**, down from 5.20:1 and 4.70:1 at full strength, and deliberately just
 * over the 3:1 WCAG asks of a non-text control boundary rather than comfortably
 * past it.
 * Deliberately NOT the `outline` role, which sounds right and is not: #9A9AA2 on
 * light and #5A5D63 on dark are ~2.3:1 against the surfaces this app puts toggles
 * on, and a boundary you have to look for is the bug this exists to fix.
 *
 * The weight stays at 1 dp: below a hairline the ring stops being drawn crisply
 * on any density, and the alpha is the honest place to spend the reduction.
 *
 * ## The motion
 *
 * ONE progress value on the theme's **effects** spring drives both the ring's
 * radius and its opacity, so the ring cannot be a circle at half opacity around
 * a container that has already squared off. Effects, never spatial, for the same
 * reason the morph itself is: an under-damped ring would wobble around a toggle
 * that had already settled. Off is a full circle (half the container); on is
 * [TOGGLE_CHECKED_CORNER], the corner `toggleableShapes()` uses — so the two run
 * the identical curve and stay concentric all the way through.
 *
 * ## Why a draw, not `Modifier.border`
 *
 * `Surface` applies the caller's modifier OUTSIDE its own background, so a
 * `border` in this position would be painted first and then covered by an opaque
 * container. Drawing after `drawContent()` is the only place the ring can land on
 * top. It is also a DRAW-phase read of the animation: the ring fading in
 * recomposes nothing.
 */
@Composable
internal fun Modifier.offStateOutline(checked: Boolean): Modifier {
    val ink = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = TOGGLE_OUTLINE_ALPHA)
    val progress = animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "offStateOutline",
    )
    return size(TOGGLE_CONTAINER_SIZE).drawWithContent {
        drawContent()
        // Read here, not in composition — see the KDoc.
        val t = progress.value.coerceIn(0f, 1f)
        if (t >= 1f) return@drawWithContent
        val stroke = TOGGLE_OUTLINE_WIDTH.toPx()
        // The container, centred in the node's own bounds — which are the 48 dp
        // touch target, not the container, whenever the toggle is a Surface. See
        // the KDoc: this centring IS the fix.
        val side = TOGGLE_CONTAINER_SIZE.toPx().coerceAtMost(size.minDimension)
        val left = (size.width - side) / 2f
        val top = (size.height - side) / 2f
        // Inset by half the stroke so the ring sits fully INSIDE the container's
        // bounds; a centred stroke would straddle its edge.
        val radius = lerp(side / 2f, TOGGLE_CHECKED_CORNER.toPx(), t)
        drawRoundRect(
            color = ink.copy(alpha = ink.alpha * (1f - t)),
            topLeft = Offset(left + stroke / 2f, top + stroke / 2f),
            size = Size(side - stroke, side - stroke),
            cornerRadius = CornerRadius((radius - stroke / 2f).coerceAtLeast(0f)),
            style = Stroke(stroke),
        )
    }
}

/** The off-state ring's width: a hairline. Thicker reads as an outlined BUTTON. */
private val TOGGLE_OUTLINE_WIDTH = 1.dp

/**
 * How opaque that ring is. See [offStateOutline]'s colour section for the
 * contrast this lands on in both schemes, and why it is deliberately close to
 * the 3:1 floor rather than comfortably over it.
 */
private const val TOGGLE_OUTLINE_ALPHA = 0.75f

/** The corner `IconButtonDefaults.toggleableShapes()` squares off to when checked. */
private val TOGGLE_CHECKED_CORNER = 12.dp

/**
 * The painted container of an outlined icon toggle: **36 dp, down from MD3's
 * small-icon-button 40**.
 *
 * The user asked for the whole control to come down a little after Phase 17 —
 * at 40 dp with a ring around it, it crowded the slider beside it and the tool
 * buttons either side of it. 36 dp is one 4 dp step down, still 12 dp clear of
 * the 24 dp icon inside it, and it changes nothing about reachability: the
 * toggle's touch target is `minimumInteractiveComponentSize`'s 48 dp square,
 * which is a layout the container sits centred inside and does not set.
 *
 * Applied by [offStateOutline] itself, which also draws the ring against it.
 */
internal val TOGGLE_CONTAINER_SIZE = 36.dp

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
internal fun HintText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
    )
}

/**
 * How tall a settings row is and how much padding it carries — Material3's own
 * one/two/three-line list metrics, restated here because [PrefRow] had to stop
 * using [ListItem]. See [PrefRow] for why.
 *
 * The numbers are material3's, read from its source (`ListItem.kt`):
 * `ListItemVerticalPadding` 8 dp, `ListItemThreeLineVerticalPadding` 12 dp, and
 * `ListTokens.Item{One,Two,Three}LineContainerHeight` 56/72/88 dp. Restating
 * them keeps every row exactly the height it was before the rewrite.
 */
internal enum class PrefRowLines { ONE, TWO, THREE }

internal val PrefRowLines.verticalPadding: Dp
    get() = if (this == PrefRowLines.THREE) 12.dp else 8.dp

internal val PrefRowLines.minHeight: Dp
    get() = when (this) {
        PrefRowLines.ONE -> 56.dp
        PrefRowLines.TWO -> 72.dp
        PrefRowLines.THREE -> 88.dp
    }

/**
 * Material3's `ListItemStartPadding` / `ListItemEndPadding`, and its
 * `LeadingContentEndPadding` / `TrailingContentStartPadding` — all four are
 * 16 dp, so one constant says it once. See [PrefRow].
 */
private val PREF_ROW_PADDING = 16.dp

/** Material3's `ListTokens.ItemLeadingIconSize`, which is also `Icon`'s default. */
internal val PREF_ROW_ICON_SIZE = 24.dp

/**
 * The layout every settings row uses: an optional leading icon, a stack of text,
 * an optional trailing control — **with the leading and trailing slots centred
 * vertically at every line count.**
 *
 * ## Why this is not a [ListItem]
 *
 * It was one, and the check marks in the Initial setup group were visibly skewed
 * upwards on some rows and not others. The cause is in material3's own layout
 * (`ListItem.kt`, `place()`): the leading and trailing slots are placed at
 * `if (isThreeLine) topPadding else CenterVertically.align(...)`, and
 * `ListItemType` counts a row as THREE-line as soon as its supporting text wraps
 * (`isSupportingMultiline`). So "Essential Key listener", whose subtitle wraps to
 * two lines, top-aligned its mark while its single-line neighbours centred
 * theirs — which is exactly the asymmetry that was reported. There is no public
 * API to override that alignment, so the row is laid out here instead.
 *
 * Everything else is deliberately unchanged: the same 16 dp insets and slot
 * gaps, the same `onSurfaceVariant` leading tint that `ListItemDefaults` was
 * providing, the same per-line-count heights (see [PrefRowLines]), the same
 * ripple, and one merged accessibility node (`Modifier.clickable` merges its
 * descendants) with a ≥ 48 dp target.
 *
 * **What was lost:** [ListItem]'s press SHAPE MORPH — the row no longer rounds
 * its corners under the finger. That is a property of the component, not
 * something a caller can lend to a `Row`, and the centred mark is worth more
 * than the morph. The ripple still acknowledges the press.
 *
 * [lines] is the caller's business because only the caller knows whether its
 * subtitle wrapped; the title+subtitle rows below measure it with `onTextLayout`
 * and hand it back, which is the same signal material3 derives internally.
 */
@Composable
internal fun PrefRow(
    lines: PrefRowLines,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = lines.minHeight)
            .padding(horizontal = PREF_ROW_PADDING, vertical = lines.verticalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            // The tint ListItemDefaults.colors() was giving the leading slot.
            CompositionLocalProvider(
                LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant,
                content = leading,
            )
            Spacer(Modifier.width(PREF_ROW_PADDING))
        }
        Column(Modifier.weight(1f), content = content)
        if (trailing != null) {
            Spacer(Modifier.width(PREF_ROW_PADDING))
            trailing()
        }
    }
}

/**
 * A settings row's leading icon: one size, one tint, one gap, for every row on
 * the Settings tab.
 *
 * `contentDescription` is deliberately absent rather than optional. Every one of
 * these sits beside a label that already names the setting, and [PrefRow] merges
 * the row into a single accessibility node — a description here would make a
 * screen reader say the same thing twice. This is the established convention in
 * this file; see [ChecklistRow]'s mark, which is silent for the same reason.
 */
@Composable
internal fun PrefIcon(icon: ImageVector) {
    Icon(icon, contentDescription = null, modifier = Modifier.size(PREF_ROW_ICON_SIZE))
}

/**
 * Title over a subtitle, the whole row clickable — a [PrefRow], and see its KDoc
 * for why that is no longer an MD3 [ListItem].
 *
 * The subtitle is animated because [UpdateRow] rewrites it live as the check
 * runs (Idle → Checking → UpToDate / Available / Failed) and every one of
 * those is a different length; the static callers simply never trigger it.
 */
@Composable
internal fun SetupRow(
    title: String,
    subtitle: String,
    good: Boolean?,
    leading: ImageVector? = null,
    onClick: () -> Unit,
) {
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
    // See [PrefRow]: the row's metrics follow whether the subtitle wrapped, and
    // only the laid-out text knows that.
    var subtitleLines by remember { mutableIntStateOf(1) }
    PrefRow(
        lines = if (subtitleLines > 1) PrefRowLines.THREE else PrefRowLines.TWO,
        leading = leading?.let { { PrefIcon(it) } },
        onClick = onClick,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
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
                onTextLayout = { subtitleLines = it.lineCount },
            )
        }
    }
}

/**
 * Setup-checklist row: a grey check mark on the left once the item is
 * configured and working, a grey question mark while it is not (or cannot
 * be verified).
 *
 * The mark is **vertically centred whatever the subtitle does**, which it was
 * not until this became a [PrefRow] — see that KDoc for the material3 layout
 * rule that used to shove it to the top of any row whose subtitle wrapped.
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
    // See [PrefRow]: the row's metrics follow whether the subtitle wrapped.
    var subtitleLines by remember { mutableIntStateOf(1) }
    PrefRow(
        lines = if (subtitleLines > 1) PrefRowLines.THREE else PrefRowLines.TWO,
        // The mark's tint (and the gap to the text) come from [PrefRow], which
        // provides the same `onSurfaceVariant` ListItemDefaults used to.
        leading = {
            AnimatedContent(
                targetState = good == true,
                transitionSpec = {
                    (fadeIn(iconFade) + scaleIn(iconScale, initialScale = 0.6f)) togetherWith
                        (fadeOut(iconFade) + scaleOut(iconScale, targetScale = 0.6f))
                },
                label = "checklistRowMark",
            ) { ok ->
                Icon(
                    if (ok) Icons.Outlined.Check else Icons.AutoMirrored.Outlined.HelpOutline,
                    contentDescription = null,
                    modifier = Modifier.size(PREF_ROW_ICON_SIZE),
                )
            }
        },
        onClick = onClick,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = subtitleColor,
            onTextLayout = { subtitleLines = it.lineCount },
        )
    }
}

/**
 * A checklist row for a runtime permission.
 *
 * [granted] is passed IN rather than probed here, and that is the whole point of
 * the refactor this row came out of: the answer now comes from the one
 * [SetupStatus] the badge also reads, so a row showing a check mark and a badge
 * saying otherwise cannot happen.
 */
@Composable
private fun PermissionRow(
    title: String,
    permissions: Array<String>,
    granted: Boolean,
    onRequest: (Array<String>) -> Unit,
) {
    ChecklistRow(
        title = title,
        subtitle = stringResource(if (granted) R.string.checklist_granted else R.string.checklist_tap_to_grant),
        good = granted,
    ) { onRequest(permissions) }
}

/**
 * A settings row whose trailing control is a [Switch].
 *
 * A [PrefRow] with the switch in its trailing slot, and deliberately NOT a
 * clickable row: the switch keeps its own API (`checked` / `onCheckedChange`)
 * and therefore `Role.Switch`, its own spec motion — the thumb's slide and
 * squash already ride [MaterialTheme.motionScheme] — and its own ≥ 48 dp target.
 * A clickable row wrapping it would announce a second, redundant action for the
 * same state. (This was an MD3 [ListItem] until the leading icons arrived; see
 * [PrefRow] for why every settings row had to stop being one.)
 */
@Composable
private fun SwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    leading: ImageVector? = null,
    onChange: (Boolean) -> Unit,
) {
    var subtitleLines by remember { mutableIntStateOf(1) }
    PrefRow(
        lines = when {
            subtitle == null -> PrefRowLines.ONE
            subtitleLines > 1 -> PrefRowLines.THREE
            else -> PrefRowLines.TWO
        },
        leading = leading?.let { { PrefIcon(it) } },
        trailing = { NoRipple { Switch(checked = checked, onCheckedChange = onChange) } },
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                onTextLayout = { subtitleLines = it.lineCount },
            )
        }
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
                    "custom" -> {
                        // One read of the design directory per opening of this
                        // dialog. The list is small and the store caches it; the
                        // Create tab is where designs are managed, this is only
                        // where one is chosen.
                        val designs = remember { Core.designStore.list() }
                        if (designs.isEmpty()) {
                            // Saying so beats an empty radio group, which reads
                            // as a dialog that failed to load.
                            Text(
                                stringResource(R.string.pref_custom_none),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        } else {
                            Text(
                                stringResource(R.string.pref_custom_design),
                                style = MaterialTheme.typography.labelLarge,
                            )
                            val unnamed = stringResource(R.string.pref_custom_unnamed)
                            // A stored id that no longer names a design (it was
                            // deleted) highlights the first design instead —
                            // matching what AndroidDesignPort actually plays, so
                            // the dialog never claims a selection the matrix
                            // disagrees with. The pref is only written on a tap.
                            var selected by remember {
                                mutableStateOf(
                                    Core.prefs.getString(PrefKeys.CUSTOM_DESIGN_ID, PrefKeys.CUSTOM_DESIGN_ID_DEF)
                                        .takeIf { id -> designs.any { it.id == id } }
                                        ?: designs.first().id,
                                )
                            }
                            designs.forEach { design ->
                                ChoiceRow(design.name.ifBlank { unnamed }, selected == design.id) {
                                    selected = design.id
                                    Core.prefs.putString(PrefKeys.CUSTOM_DESIGN_ID, design.id)
                                }
                            }
                        }
                    }
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
 *
 * `internal` rather than private because the new-design dialog in `CreateTab.kt`
 * asks its own pick-one-of-three (which phone the design is for) and must ask it
 * with the same row the per-toy dialogs use — the same promotion [SectionCard],
 * [HintText] and [NoRipple] already made, for the same reason.
 */
@Composable
internal fun ChoiceRow(label: String, selected: Boolean, onSelect: () -> Unit) {
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
