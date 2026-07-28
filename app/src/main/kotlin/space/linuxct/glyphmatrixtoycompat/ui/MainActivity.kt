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
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.material3.LargeTopAppBar
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.IntSize
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
import space.linuxct.glyphmatrixtoycompat.ui.theme.Md3Motion
import space.linuxct.glyphmatrixtoycompat.ui.theme.NavPillColors
import space.linuxct.glyphmatrixtoycompat.ui.theme.navPill
import space.linuxct.glyphmatrixtoycompat.update.UpdateChecker
import space.linuxct.glyphmatrixtoycompat.update.UpdateCheckWorker

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Core.init(this)
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
 * Breathing room every tab body adds *below* the Scaffold's own bottom inset,
 * so scrolled-to-the-end content never sits flush under the floating pill.
 * This is pure slack — the Scaffold's inset already covers the whole measured
 * bottom bar, pill height included — so it can never be too small to prevent
 * overlap, only too mean or too generous to look right.
 */
private val NAV_PILL_CLEARANCE = 40.dp

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
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
        // The whole page — body AND app-bar header — sits on the page
        // background (light gray / pure black). The app bar is SOLID in that
        // same colour (not transparent, or content scrolls visibly under the
        // collapsed header) so it stays opaque yet seamless with the body.
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(Tab.entries[tab].title)) },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        bottomBar = { FloatingNavBar(selected = tab) { tab = it } },
    ) { innerPadding ->
        when (Tab.entries[tab]) {
            Tab.TOYS -> ToysTab(innerPadding)
            Tab.SETTINGS -> SettingsTab(innerPadding)
            Tab.TUTORIAL -> TutorialTab(innerPadding)
        }
    }
}

/**
 * MD3-style floating pill navigation: a raised, centred capsule of one [NavChip]
 * per tab.
 *
 * Colours come from the theme's [NavPillColors] rather than the M3 `inverse*`
 * roles — those stay reserved for the tutorial's numbered-step bubbles, and
 * would make this pill a near-white slab in dark mode.
 */
@Composable
private fun FloatingNavBar(selected: Int, onSelect: (Int) -> Unit) {
    val pill = MaterialTheme.navPill
    Box(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            // Percent radius, not a fixed dp: the pill's height follows the
            // user's font scale, and this keeps it a true capsule at any of
            // them (see [NAV_PILL_GAP] for why that matters).
            shape = NAV_CHIP_SHAPE,
            color = pill.container,
            shadowElevation = 8.dp,
        ) {
            Row(
                // Uniform, all four sides — see [NAV_PILL_GAP] before changing.
                Modifier.padding(NAV_PILL_GAP),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Tab.entries.forEachIndexed { i, t ->
                    val sel = i == selected
                    // MD3 animates its selection indicator; both of these used
                    // to snap between frames, which read as the pill's motion
                    // "not matching" far more than the width spring did.
                    // EFFECTS spring, never the spatial one: bouncing a colour
                    // is a flicker (see [Md3Motion]).
                    //
                    // The unselected fill fades the SAME colour out to alpha 0,
                    // not to Color.Transparent — transparent is transparent
                    // *black*, so lerping to it drags a light chip through grey
                    // on the way out.
                    val container by animateColorAsState(
                        targetValue = pill.selectedContainer.copy(alpha = if (sel) 1f else 0f),
                        animationSpec = Md3Motion.effects(),
                        label = "navChipContainer",
                    )
                    val tint by animateColorAsState(
                        targetValue = if (sel) pill.selectedContent else pill.content,
                        animationSpec = Md3Motion.effects(),
                        label = "navChipTint",
                    )
                    NavChip(
                        tab = t,
                        selected = sel,
                        tint = tint,
                        modifier = Modifier
                            .clip(NAV_CHIP_SHAPE)
                            .background(container)
                            .clickable { onSelect(i) },
                    )
                }
            }
        }
    }
}

/**
 * One tab chip: its icon, with the caption beside it on the SELECTED chip only —
 * unselected chips are the bare icon.
 *
 * Height 12 + 24 + 12 = **48 dp** → chip radius 24, pill 48 + 2×6 = 60 → pill
 * radius 30 = 24 + [NAV_PILL_GAP]. Concentric. An unselected chip is 12 + 24 +
 * 12 = 48 dp wide too — a 48 × 48 target, and a perfect circle. The selected
 * chip is wider (never shorter than 48 dp), so the stadium radius stays 24
 * throughout the width animation and the shape never wobbles.
 */
@Composable
private fun NavChip(tab: Tab, selected: Boolean, tint: Color, modifier: Modifier) {
    Row(
        modifier
            // The chip's width changes when the selection moves, and THIS is
            // what animates the whole pill: the chip reports an animated width
            // every frame, the Row and the wrap-content Surface above it
            // re-measure to match, and the centring Box re-centres — so the
            // capsule outline itself grows/shrinks symmetrically from the
            // middle. Deliberately not placed on the Surface or the pill's Row:
            // an animation node clips its content to the animated size, so up
            // there it would shave the chips against the capsule edge instead
            // of resizing the capsule (and two nested nodes chase each other,
            // since the outer one's target is the inner one's animated value).
            // It sits INSIDE clip+background (which arrive in `modifier`) so
            // the chip's own fill resizes with it too.
            //
            // The spec is explicit: animateContentSize()'s own default is
            // `spring(stiffness = StiffnessMediumLow)`, and spring()'s default
            // damping is DampingRatioNoBouncy — 1.0, which cannot overshoot.
            // MD3's spatial spring is under-damped (0.9) and stiffer (700), so
            // the pill settles faster AND with the small bounce it should have.
            // See [Md3Motion] for why the tokens are copied rather than read.
            .animateContentSize(Md3Motion.spatial(IntSize.VisibilityThreshold))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Unselected chips have no visible label, so the icon has to carry it.
        Icon(
            tab.icon,
            contentDescription = if (selected) null else stringResource(tab.caption),
            tint = tint,
        )
        if (selected) {
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(tab.caption),
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
private fun ToysTab(innerPadding: PaddingValues) {
    var dialogId by remember { mutableStateOf<String?>(null) }

    // The toy currently on the matrix: tracks the persisted current screen
    // live (cycled from the Essential Key outside this UI); the pref change
    // listener fires on the main thread.
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
                placement = if (drag.draggingIndex == index) Modifier else Modifier.animateItem(),
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
private fun SettingsTab(innerPadding: PaddingValues) {
    val context = LocalContext.current
    var refreshTick by remember { mutableIntStateOf(0) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { refreshTick++ }
    // Re-probe system state whenever the user returns from system Settings.
    LifecycleResumeEffect(Unit) {
        refreshTick++
        onPauseOrDispose { }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Spacer(Modifier.height(innerPadding.calculateTopPadding()))

        SectionHeader(stringResource(R.string.section_initial_setup))
        SectionCard {
            val a11yEnabled = remember(refreshTick) { isEssentialKeyServiceEnabled(context) }
            val a11ySubtitle = remember(refreshTick) {
                if (a11yEnabled) {
                    val beat = Core.prefs.getLong(PrefKeys.SERVICE_HEARTBEAT, PrefKeys.SERVICE_HEARTBEAT_DEF)
                    val suffix = if (beat > 0) {
                        val mins = (System.currentTimeMillis() - beat) / 60_000
                        " (last activity ${if (mins < 1) "just now" else "$mins min ago"})"
                    } else {
                        ""
                    }
                    context.getString(R.string.checklist_accessibility_on) + suffix
                } else {
                    context.getString(R.string.checklist_accessibility_off)
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
                    // Filled when on, outlined + muted when off (monochrome: the
                    // difference is contrast, never colour).
                    FilledIconToggleButton(
                        checked = auto,
                        onCheckedChange = { on ->
                            auto = on
                            Core.prefs.putBoolean(PrefKeys.AUTO_BRIGHTNESS, on)
                        },
                        modifier = Modifier
                            .size(40.dp)
                            .then(
                                if (auto) {
                                    Modifier
                                } else {
                                    Modifier.border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                                },
                            ),
                    ) {
                        Icon(
                            Icons.Default.BrightnessAuto,
                            contentDescription = stringResource(
                                if (auto) R.string.auto_brightness_on else R.string.auto_brightness_off,
                            ),
                            modifier = Modifier.size(20.dp),
                        )
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
private fun TutorialTab(innerPadding: PaddingValues) {
    val context = LocalContext.current
    var topic by remember { mutableStateOf<TutorialTopic?>(null) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
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
    var offsetY by mutableFloatStateOf(0f)
    var rowHeightPx by mutableIntStateOf(0)
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
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .then(placement)
            .zIndex(if (dragging) 1f else 0f)
            .graphicsLayer { translationY = if (dragging) drag.offsetY else 0f }
            .onSizeChanged { drag.rowHeightPx = it.height }
            .padding(horizontal = 16.dp, vertical = 3.dp),
        shape = RoundedCornerShape(20.dp),
        color = if (shown) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        tonalElevation = if (dragging) 8.dp else 1.dp,
        shadowElevation = if (dragging) 6.dp else 0.dp,
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
                            onDragEnd = {
                                drag.draggingIndex = -1
                                drag.offsetY = 0f
                                onPersist()
                            },
                            onDragCancel = {
                                drag.draggingIndex = -1
                                drag.offsetY = 0f
                                onPersist()
                            },
                        )
                    },
            )
            // Reserved slot for the "currently shown on the matrix" dot,
            // so the name never shifts when the marker appears.
            Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) {
                if (shown) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .background(MaterialTheme.colorScheme.primary, CircleShape),
                    )
                }
            }
            Text(
                stringResource(DISPLAY_NAMES[id] ?: R.string.app_name),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            FilledIconToggleButton(checked = shown, onCheckedChange = { onSelect() }) {
                Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.set_active))
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
            Switch(checked = enabled, onCheckedChange = {
                enabled = it
                Core.prefs.putBoolean(PrefKeys.screenEnabled(id), it)
            })
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

@Composable
private fun HintText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
    )
}

@Composable
private fun SetupRow(title: String, subtitle: String, good: Boolean?, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp, 12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = when (good) {
                true -> MaterialTheme.colorScheme.primary
                false -> MaterialTheme.colorScheme.error
                null -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

/**
 * Setup-checklist row: a grey check mark on the left once the item is
 * configured and working, a grey question mark while it is not (or cannot
 * be verified).
 */
@Composable
private fun ChecklistRow(title: String, subtitle: String, good: Boolean?, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp, 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (good == true) Icons.Default.Check else Icons.AutoMirrored.Filled.HelpOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = when (good) {
                    true -> MaterialTheme.colorScheme.primary
                    false -> MaterialTheme.colorScheme.error
                    null -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
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

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(16.dp, 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
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
        options = listOf("Fill + wave", "Particles", "Battery + bolt", "Percent + bolt"),
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

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onSelect).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun PrefSwitch(title: String, key: String, def: Boolean) {
    var checked by remember(key) { mutableStateOf(Core.prefs.getBoolean(key, def)) }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = {
            checked = it
            Core.prefs.putBoolean(key, it)
        })
    }
}
