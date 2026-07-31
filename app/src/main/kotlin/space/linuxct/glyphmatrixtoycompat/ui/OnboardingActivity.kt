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
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
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
            GmtcTheme {
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
    // Back/Next drive the pager programmatically. Scrolling is POSITION, so it
    // is spatial; animateScrollToPage's own default is a bare spring() (damped
    // 1.0, no overshoot). Default speed rather than slow: `slow` is meant for
    // large surfaces settling into place, and at stiffness 200 a tap on Next
    // takes long enough to feel like the button did not register.
    val pageSpec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
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
                    Page.KEY -> KeyPage(a11yOn)
                    Page.TOY -> ToyPage()
                    Page.PERMS -> PermsPage(refreshTick, onRefresh = { refreshTick++ })
                    Page.MODE -> ModePage()
                    Page.CREATE -> CreatePage(onStartDrawing)
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
    var showTutorial by remember { mutableStateOf(false) }
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
    PageScaffold(ART_DRAW, stringResource(R.string.onb_create_title)) {
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
    val lit = MaterialTheme.colorScheme.onSurface
    val unlit = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)

    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(220.dp)) {
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

private const val ART_KEY = """
..###............
.#...#...........
#.....#..........
#..#..###########
#.....#.....#..#.
.#...#......#..#.
..###............
"""

private const val ART_MATRIX = """
....#######....
..##.......##..
.#...........#.
.#...........#.
#.............#
#.............#
#.....###.....#
#.....###.....#
#.....###.....#
#.............#
#.............#
.#...........#.
.#...........#.
..##.......##..
....#######....
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

private const val ART_TOGGLE = """
...#########.....
..#.........#....
.#...###.....#...
.#..#####....#...
.#..#####....#...
.#..#####....#...
.#...###.....#...
..#.........#....
...#########.....
"""

// A pencil on the diagonal — the one page in this flow that is about MAKING
// something rather than about a setting. Two parallel strokes for the shaft so
// it reads as a tool and not as a bar, tapering into a tip at the bottom left.
private const val ART_DRAW = """
.........###.
........#####
.......###.##
......###.##.
.....###.##..
....###.##...
...###.##....
..###.##.....
.###.##......
.#####.......
.####........
.##..........
.#...........
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
