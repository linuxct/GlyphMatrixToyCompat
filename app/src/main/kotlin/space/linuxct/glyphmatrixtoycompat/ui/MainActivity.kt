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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.zIndex
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
import space.linuxct.glyphmatrixtoycompat.ui.theme.GmtcTheme
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
    "counter" to R.string.screen_counter,
    "breathing" to R.string.screen_breathing,
    "tea" to R.string.screen_tea,
    "compass" to R.string.screen_compass,
    "visualizer" to R.string.screen_visualizer,
)

private val CONFIGURABLE = setOf("ambient", "clock", "dice", "breathing", "tea", "visualizer")

private fun loadOrder(): List<String> {
    val stored = Core.prefs.getString(PrefKeys.SCREEN_ORDER, PrefKeys.SCREEN_ORDER_DEF)
        .split(',').map { it.trim() }.filter { it.isNotEmpty() && DISPLAY_NAMES.containsKey(it) }
    return stored + DISPLAY_NAMES.keys.filter { it !in stored }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen() {
    val context = LocalContext.current
    var refreshTick by remember { mutableIntStateOf(0) }
    var dialogId by remember { mutableStateOf<String?>(null) }
    var showTutorial by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { refreshTick++ }

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

    // Play sets the toy as the currently active one: persist it and switch
    // the live session to it immediately. The pref-change listener above then
    // moves the highlight. If capture is off (no session), it still persists
    // and shows the next time a session runs.
    fun selectToy(id: String) {
        DebugLog.i("Ui", "set active toy '$id'")
        Core.arbiter.revive()
        Core.scheduler.run { Core.screenManager.selectScreen(id) }
    }

    LifecycleResumeEffect(Unit) {
        refreshTick++
        currentToy = Core.prefs.getString(PrefKeys.CURRENT_SCREEN, PrefKeys.CURRENT_SCREEN_DEF)
        onPauseOrDispose { }
    }

    val order = remember { mutableStateListOf<String>().apply { addAll(loadOrder()) } }
    fun persistOrder() = Core.prefs.putString(PrefKeys.SCREEN_ORDER, order.joinToString(","))

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
        // The whole page — body AND app-bar header — sits on the gray page
        // background. The app bar is SOLID in that same gray (not transparent,
        // or content scrolls visibly under the collapsed header) so it stays
        // opaque yet seamless with the body.
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.main_title)) },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { innerPadding ->
        val drag = remember { DragState() }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            // Extra breathing room below the last row (on top of the nav-bar
            // inset) so the list never sits flush against the bottom edge.
            contentPadding = PaddingValues(
                top = innerPadding.calculateTopPadding(),
                bottom = innerPadding.calculateBottomPadding() + 32.dp,
            ),
        ) {
            if (!Core.glyphLink.isSupported) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(16.dp, 8.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                        ),
                    ) {
                        Text(
                            stringResource(R.string.checklist_unsupported),
                            Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }

            item { SectionHeader(stringResource(R.string.checklist_title)) }
            item {
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
                    SetupRow(
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
                    SetupRow(
                        title = stringResource(R.string.checklist_toy),
                        subtitle = stringResource(R.string.checklist_toy_hint),
                        good = null,
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
                    SetupRow(
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
            }
            item { HintText(stringResource(R.string.checklist_essential_space)) }
            item { HintText(stringResource(R.string.checklist_restricted)) }

            item { SectionHeader(stringResource(R.string.settings)) }
            item {
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
                        Slider(
                            value = brightness,
                            onValueChange = {
                                brightness = it.coerceIn(0.05f, 1f)
                                Core.prefs.putFloat(PrefKeys.BRIGHTNESS, brightness)
                            },
                            valueRange = 0.05f..1f,
                        )
                    }
                    HorizontalDivider()
                    UpdateRow()
                }
            }

            item { SectionHeader(stringResource(R.string.tut_section)) }
            item {
                SectionCard {
                    SetupRow(
                        title = stringResource(R.string.tut_button_title),
                        subtitle = stringResource(R.string.tut_button_subtitle),
                        good = null,
                    ) { showTutorial = true }
                }
            }

            item { SectionHeader(stringResource(R.string.screens_title)) }
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
        if (showTutorial) {
            KeyTutorialDialog(onDismiss = { showTutorial = false })
        }
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

@Composable
private fun PermissionRow(title: String, permissions: Array<String>, refreshTick: Int, onRequest: (Array<String>) -> Unit) {
    val context = LocalContext.current
    val granted = remember(refreshTick) {
        permissions.any { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }
    SetupRow(
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
                    "breathing" -> {
                        Text(stringResource(R.string.pref_breathing_pace), style = MaterialTheme.typography.labelLarge)
                        StringChoiceGroup(
                            options = listOf("2", "3", "4", "6", "8"),
                            key = PrefKeys.BREATHING_PACE,
                            def = PrefKeys.BREATHING_PACE_DEF,
                        )
                    }
                    "tea" -> {
                        Text(stringResource(R.string.pref_tea_duration), style = MaterialTheme.typography.labelLarge)
                        IntValueChoiceGroup(
                            options = listOf(30, 60, 120, 180, 240),
                            labels = listOf("30 s", "60 s", "120 s", "180 s", "240 s"),
                            key = PrefKeys.TEA_DURATION,
                            def = PrefKeys.TEA_DURATION_DEF,
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
