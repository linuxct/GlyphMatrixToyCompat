package space.linuxct.glyphmatrixtoycompat.ui

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
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.launch
import space.linuxct.glyphmatrixtoycompat.Core
import space.linuxct.glyphmatrixtoycompat.R
import space.linuxct.glyphmatrixtoycompat.core.PrefKeys
import space.linuxct.glyphmatrixtoycompat.ui.theme.GmtcTheme
import kotlin.math.PI
import kotlin.math.sin

/**
 * First-run paged setup: Essential Key listener → always-on Glyph Toy →
 * optional permissions → key mode (only if the listener was enabled) →
 * welcome. Every step is skippable with Next; MainActivity launches this
 * until ONBOARDING_DONE is set.
 */
class OnboardingActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Core.init(this)
        enableEdgeToEdge()
        setContent {
            GmtcTheme {
                OnboardingFlow(onFinished = ::completeOnboarding)
            }
        }
    }

    private fun completeOnboarding() {
        Core.prefs.putBoolean(PrefKeys.ONBOARDING_DONE, true)
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}

private enum class Page { KEY, TOY, PERMS, MODE, DONE }

@Composable
private fun OnboardingFlow(onFinished: () -> Unit) {
    val context = LocalContext.current
    var refreshTick by remember { mutableIntStateOf(0) }
    // Re-probe system state whenever the user returns from Settings.
    LifecycleResumeEffect(Unit) {
        refreshTick++
        onPauseOrDispose { }
    }
    val a11yOn = remember(refreshTick) { isEssentialKeyServiceEnabled(context) }

    // The mode-choice page only exists once the listener is actually on.
    val pages = if (a11yOn) {
        listOf(Page.KEY, Page.TOY, Page.PERMS, Page.MODE, Page.DONE)
    } else {
        listOf(Page.KEY, Page.TOY, Page.PERMS, Page.DONE)
    }
    val pagerState = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            HorizontalPager(state = pagerState, modifier = Modifier.weight(1f)) { i ->
                when (pages[i]) {
                    Page.KEY -> KeyPage(a11yOn)
                    Page.TOY -> ToyPage()
                    Page.PERMS -> PermsPage(refreshTick, onRefresh = { refreshTick++ })
                    Page.MODE -> ModePage()
                    Page.DONE -> DonePage(refreshTick)
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (pagerState.currentPage > 0) {
                        TextButton(onClick = {
                            scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
                        }) {
                            Text(stringResource(R.string.onb_back))
                        }
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    repeat(pages.size) { i ->
                        val selected = i == pagerState.currentPage
                        val dotWidth by animateDpAsState(if (selected) 22.dp else 8.dp, label = "dot")
                        Box(
                            Modifier
                                .height(8.dp)
                                .width(dotWidth)
                                .background(
                                    if (selected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
                                    },
                                    CircleShape,
                                ),
                        )
                    }
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    val last = pagerState.currentPage == pages.lastIndex
                    Button(onClick = {
                        if (last) {
                            onFinished()
                        } else {
                            scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                        }
                    }) {
                        Text(stringResource(if (last) R.string.onb_done else R.string.onb_next))
                    }
                }
            }
        }
    }
}

// ---------- pages ----------

@Composable
private fun KeyPage(a11yOn: Boolean) {
    val context = LocalContext.current
    PageScaffold(ART_KEY, stringResource(R.string.onb_key_title)) {
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
        Spacer(Modifier.height(20.dp))
        Card(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    stringResource(R.string.onb_key_sideload_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.onb_key_sideload_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:${context.packageName}"),
                        ),
                    )
                }) {
                    Text(stringResource(R.string.onb_key_appinfo))
                }
            }
        }
    }
}

@Composable
private fun ToyPage() {
    val context = LocalContext.current
    PageScaffold(ART_MATRIX, stringResource(R.string.onb_toy_title)) {
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
                        Manifest.permission.ACCESS_FINE_LOCATION,
                    )
                },
            ) {
                launcher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                        Manifest.permission.ACCESS_FINE_LOCATION,
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
    fun select(enabled: Boolean) {
        menuMode = enabled
        Core.prefs.putBoolean(PrefKeys.MENU_MODE_ENABLED, enabled)
    }
    PageScaffold(ART_TOGGLE, stringResource(R.string.onb_mode_title)) {
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
                    Manifest.permission.ACCESS_FINE_LOCATION,
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

@Composable
private fun PageScaffold(art: String, title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        MatrixArt(art)
        Spacer(Modifier.height(24.dp))
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))
        content()
        Spacer(Modifier.height(24.dp))
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

@Composable
private fun PermRow(title: Int, why: Int, granted: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp, 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                stringResource(if (granted) R.string.checklist_granted else R.string.checklist_tap_to_grant),
                style = MaterialTheme.typography.labelMedium,
                color = if (granted) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(why),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ModeCard(selected: Boolean, title: Int, desc: Int, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onClick).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = onClick)
            Column(Modifier.padding(start = 4.dp)) {
                Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(2.dp))
                Text(
                    stringResource(desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun hasAny(context: Context, vararg permissions: String): Boolean =
    permissions.any { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

private fun canExactAlarm(context: Context): Boolean =
    context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true

// ---------- dot-matrix header art ----------

/**
 * Draws an ASCII pattern as a glyph-matrix-style dot grid: unlit LEDs faintly
 * visible, lit ones revealing in pseudo-random order on page entry and then
 * shimmering gently, like the hardware matrix waking up.
 */
@Composable
private fun MatrixArt(pattern: String) {
    val rows = remember(pattern) { pattern.trim().lines() }
    val cols = remember(pattern) { rows.maxOf { it.length } }

    val reveal = remember(pattern) { Animatable(0f) }
    LaunchedEffect(pattern) {
        reveal.snapTo(0f)
        reveal.animateTo(1f, tween(durationMillis = 800))
    }
    val shimmer by rememberInfiniteTransition(label = "shimmer").animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(3000, easing = LinearEasing)),
        label = "phase",
    )
    val lit = MaterialTheme.colorScheme.primary
    val unlit = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)

    Canvas(Modifier.fillMaxWidth().height(150.dp)) {
        val cell = minOf(size.width / cols, size.height / rows.size, 16.dp.toPx())
        val x0 = (size.width - cell * cols) / 2f
        val y0 = (size.height - cell * rows.size) / 2f
        rows.forEachIndexed { r, line ->
            for (c in 0 until cols) {
                val center = Offset(x0 + (c + 0.5f) * cell, y0 + (r + 0.5f) * cell)
                val on = line.getOrNull(c) == '#'
                // Each lit dot gets a stable pseudo-random turn-on threshold.
                val turnOn = ((r * 7 + c * 13) % 29) / 29f
                if (on && reveal.value > turnOn) {
                    val pulse = 0.75f + 0.25f * sin(shimmer + (r + c) * 0.6f)
                    drawCircle(lit.copy(alpha = pulse), radius = cell * 0.32f, center = center)
                } else {
                    drawCircle(unlit, radius = cell * 0.18f, center = center)
                }
            }
        }
    }
}

private const val ART_KEY = """
...####..........
..#....#.........
.#..##..#........
.#..##..#########
..#....#....#..#.
...####.....#..#.
"""

private const val ART_MATRIX = """
.....#######.....
...##.......##...
..#...........#..
.#.............#.
.#.....###.....#.
.#.....###.....#.
.#.....###.....#.
.#.............#.
..#...........#..
...##.......##...
.....#######.....
"""

private const val ART_LOCK = """
...######...
..#......#..
..#......#..
.##########.
.#........#.
.#...##...#.
.#...##...#.
.#....#...#.
.#........#.
.##########.
"""

private const val ART_TOGGLE = """
..#########......
.#.........#.....
#...###.....#....
#..#####....#....
#..#####....#....
#...###.....#....
.#.........#.....
..#########......
"""

private const val ART_SMILE = """
.....#######.....
...##.......##...
..#...........#..
.#...##...##...#.
.#...##...##...#.
.#.............#.
.#..#.......#..#.
..#..#######..#..
...##.......##...
.....#######.....
"""
