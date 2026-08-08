package space.linuxct.glyphworks.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import space.linuxct.glyphworks.core.DebugLog
import java.io.File

/**
 * Nothing-styled theme, MONOCHROME with three enumerated exceptions: black, white
 * and grays everywhere, and **almost no hue in anything that carries meaning** —
 * state, selection, errors and emphasis are contrast (full-strength ink vs muted
 * grays), never colour. Nothing's own headline typeface is used for page titles.
 *
 * The three exceptions are named, bounded, and are the whole list:
 *
 * 1. **The Create tab's `+` FAB**, painted in Nothing's brand red and blue — the
 *    one place the product signs its name. See [NothingLiquidRed] and
 *    `ui/LiquidFab.kt`.
 * 2. **The recording dot** on the device illustration
 *    (`ui/design/GlyphCanvas.kt`'s `RECORDING_DOT_COLOR`), which is not an accent
 *    at all but a picture of a red square that exists on the back of the phone.
 * 3. **The setup-attention badge** on the navigation bar's Settings chip
 *    ([NavPillColors.badgeContainer], drawn by `MainActivity`'s `AttentionBadge`)
 *    — a 16 dp red disc with a white `!` in it, shown only while the Initial
 *    setup checklist has an outstanding item. Requested explicitly, and the only
 *    one of the three where hue does carry meaning. It is bounded by being tiny,
 *    by being conditional, and by the mark: the `!` is what says "attention", so
 *    the badge still works read out by TalkBack, in greyscale, and to a
 *    colour-blind user. Colour is never the only signal.
 *
 * None of the three is a palette. There is no accent role in either scheme below,
 * nothing else may borrow those colours, and a fourth exception is a change to
 * this rule rather than an application of it.
 *
 * Both schemes mirror Nothing OS Settings: a light lavender-gray page with
 * white cards, and a pure-black page with #191C20 cards in dark mode (values
 * sampled from the device).
 *
 * The headline font (NType82-Regular) is loaded AT RUNTIME from the
 * firmware's font directories (the app runs on a Nothing phone, so the exact
 * settings-title font is already on disk — no need to redistribute it). Falls
 * back to the system serif family when no Nothing font is found; the chosen
 * file is logged under the "Theme" component.
 */

private val LightScheme = lightColorScheme(
    primary = Color(0xFF17171C),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE4E4EA),
    onPrimaryContainer = Color(0xFF17171C),
    secondary = Color(0xFF5A5A62),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE7E7EC),
    onSecondaryContainer = Color(0xFF17171C),
    tertiary = Color(0xFF5A5A62),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE4E4EA),
    onTertiaryContainer = Color(0xFF17171C),
    // Page background (behind everything, incl. the app-bar header): light
    // lavender-gray, matching Nothing OS Settings. Cards/chips sit on top in
    // pure white.
    background = Color(0xFFF2F2FA),
    onBackground = Color(0xFF17171C),
    surface = Color.White,
    onSurface = Color(0xFF17171C),
    surfaceVariant = Color(0xFFEBEBEF),
    onSurfaceVariant = Color(0xFF6C6C74),
    // All card/chip surface roles are pure white (Settings style).
    surfaceTint = Color.White,
    surfaceBright = Color.White,
    surfaceDim = Color(0xFFF2F2FA),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color.White,
    surfaceContainerHighest = Color.White,
    inverseSurface = Color(0xFF2E2E33),
    inverseOnSurface = Color(0xFFF2F2FA),
    outline = Color(0xFF9A9AA2),
    outlineVariant = Color(0xFFE4E4EC),
    error = Color(0xFF17171C),
    onError = Color.White,
    errorContainer = Color(0xFFDDDDE2),
    onErrorContainer = Color(0xFF17171C),
)

// ---- Nothing OS dark-mode palette, sampled from the device's Settings ----
// The greys are the firmware's own faintly cool neutrals (R/G/B within a few
// points of each other); nothing else with a hue is introduced.

/** Card / row surface — by far the dominant colour of the dark Settings page. */
private val DarkCard = Color(0xFF191C20)

/** One step above the cards: the floating navigation pill. */
private val DarkPill = Color(0xFF26292E)

/** Filled containers (chips, the highlighted "active toy" row). */
private val DarkContainer = Color(0xFF2E3138)

/**
 * Divider hairline. Nothing OS separates rows inside a card with what is
 * effectively the page showing through, so this is near-black rather than the
 * usual lighter-than-the-card line.
 */
private val DarkHairline = Color(0xFF0D0F12)

/** Primary text / full-strength ink. */
private val DarkInk = Color(0xFFEFF0F7)

/** Secondary text. */
private val DarkInkMuted = Color(0xFF84868C)

/** Brighter secondary tone (section headers, nav captions). */
private val DarkInkDim = Color(0xFFC5C6CC)

private val DarkScheme = darkColorScheme(
    primary = DarkInk,
    onPrimary = DarkCard,
    primaryContainer = DarkContainer,
    onPrimaryContainer = DarkInk,
    secondary = DarkInkDim,
    onSecondary = DarkCard,
    secondaryContainer = DarkContainer,
    onSecondaryContainer = DarkInk,
    tertiary = DarkInkDim,
    onTertiary = DarkCard,
    tertiaryContainer = DarkContainer,
    onTertiaryContainer = DarkInk,
    // Page background (behind everything, incl. the app-bar header): pure
    // black, matching Nothing OS Settings in dark mode. Cards/chips sit on top
    // in #191C20.
    background = Color.Black,
    onBackground = DarkInk,
    surface = DarkCard,
    onSurface = DarkInk,
    surfaceVariant = Color(0xFF23262B),
    onSurfaceVariant = DarkInkMuted,
    // Every card/chip surface role is spelled out at the sampled card colour:
    // any role left unset falls back to M3's hue-tinted baseline palette.
    // surfaceTint equals the card colour so Surface's tonal elevation is a
    // visual no-op (same trick as the light scheme's white tint) — elevated
    // rows keep the exact sampled grey instead of creeping lighter.
    surfaceTint = DarkCard,
    surfaceBright = DarkCard,
    surfaceDim = Color.Black,
    surfaceContainerLowest = DarkCard,
    surfaceContainerLow = DarkCard,
    surfaceContainer = DarkCard,
    surfaceContainerHigh = DarkCard,
    surfaceContainerHighest = DarkCard,
    // Kept LIGHT on purpose: the only user is the tutorial's numbered-step
    // bubbles (light bubble, dark digits). The nav pill uses [NavPillColors].
    inverseSurface = DarkInk,
    inverseOnSurface = DarkCard,
    outline = Color(0xFF5A5D63),
    outlineVariant = DarkHairline,
    error = DarkInk,
    onError = DarkCard,
    errorContainer = DarkContainer,
    onErrorContainer = DarkInk,
)

/**
 * Colours for the floating navigation pill **and everything that sits beside
 * it** — today that is the Create tab's `+` FAB, which is a sibling of the pill
 * rather than a top-bar action.
 *
 * Deliberately NOT the M3 `inverse*` pair: those are also the tutorial's
 * numbered-step bubbles, which must stay a light bubble with dark digits in
 * both modes — while an `inverseSurface` pill in dark mode is a near-white
 * slab across the bottom of a black page.
 *
 * The FAB pair lives here, next to the pill's own colours, for one reason: the
 * two are seen together, always, and a FAB that borrows a colour-scheme role
 * would drift out of tune with the pill the moment either is retuned. Keeping
 * every pill-adjacent colour in one small class makes "does the FAB still read
 * as a separate object?" a question you can answer by looking at four lines.
 */
@Immutable
data class NavPillColors(
    val container: Color,
    val content: Color,
    val selectedContainer: Color,
    val selectedContent: Color,
    /**
     * The `+` FAB's base: **Nothing's blue**, the same in both schemes, and one
     * of the theme's three enumerated exceptions to the monochrome rule (see the
     * file KDoc, and [NothingLiquidBlue] for why a brand colour does not vary
     * with the scheme).
     *
     * It is a *base*, not the whole button: what is actually seen is the liquid
     * red/blue shader `LiquidFabFill` draws over it. This value still matters for
     * three things — the elevation shadow, which behaves as it always has only
     * because the `Surface` under the shader stays opaque; the anti-aliased rim
     * of the circle; and the frame or two before the first shader draw.
     *
     * It cannot be [container], for the reason it never could: the pill is
     * near-black in *both* themes (#2E2E33 light, #26292E dark — roughly L\* 19
     * and L\* 17), so a FAB in the pill's own colour reads as a bulge on the
     * capsule rather than as a second object. The liquid answers that with hue
     * and with movement rather than with lightness, and its red is tuned to the
     * lightness the greys this replaced had — see [NothingLiquidRed].
     */
    val fabContainer: Color,
    /**
     * Ink on the FAB: near-white in both schemes, and ≥ 6:1 against **every**
     * colour the liquid fill can produce, not merely against [fabContainer] —
     * the fill moves, so the worst case is its lightest possible pixel. That is
     * the pure red end at 6.7:1, which `NothingBrandTest` walks the ramp to
     * confirm.
     */
    val fabContent: Color,
    /**
     * The attention badge's disc: **[NothingRed], the brand value unmodified**,
     * and the third of the theme's enumerated exceptions (see the file KDoc).
     *
     * The brand red rather than [NothingLiquidRed] (which is deliberately
     * darkened to the lightness of the grey FAB it replaced, and would read as
     * maroon at 16 dp) and rather than `RECORDING_DOT_COLOR` — that one is a
     * picture of hardware by its own KDoc's argument, and borrowing it for a UI
     * accent is exactly what that KDoc forbids. It also loses on the only number
     * that decides this: white on `#D71921` is **5.18:1**, white on `#E0392C` is
     * 4.38:1.
     *
     * The same value in both schemes, for the reason [fabContainer] is: a brand
     * colour does not change because the page behind it went dark. It does not
     * have to adapt, either — the disc is opaque, so the mark's contrast never
     * depends on what is underneath, and the disc's own separation is 2.6:1
     * against the near-black pill and 4.6:1 against the selected chip's
     * near-white fill.
     */
    val badgeContainer: Color,
    /**
     * The `!` inside the badge: pure white, at **5.18:1** on [badgeContainer] —
     * past WCAG AA for text and well past the 3:1 a graphical object needs, in
     * both schemes, because the disc under it is opaque and identical in both.
     *
     * Not the schemes' near-whites: those are tinted for reading long text on a
     * page, and this is a 2 dp stroke on a saturated red where every point of
     * contrast is worth having.
     */
    val badgeContent: Color,
)

/** Light mode: dark pill, light icons, light selected chip with a dark icon. */
private val LightNavPill = NavPillColors(
    container = Color(0xFF2E2E33),
    content = Color(0xFFF2F2FA).copy(alpha = 0.75f),
    selectedContainer = Color(0xFFF2F2FA),
    selectedContent = Color(0xFF2E2E33),
    // Nothing's blue, not a grey. The FAB is the theme's branded exception and is
    // the SAME in both schemes; see [NavPillColors.fabContainer].
    fabContainer = NothingLiquidBlue,
    fabContent = Color(0xFFF2F2FA),
    // Identical in both schemes; see [NavPillColors.badgeContainer].
    badgeContainer = NothingRed,
    badgeContent = Color.White,
)

/**
 * Dark mode: the pill sits one step above the cards, unselected items are
 * light grey, and the selected chip is a light fill with a dark icon so the
 * selection still reads at a glance without any near-white expanse.
 */
private val DarkNavPill = NavPillColors(
    container = DarkPill,
    content = DarkInkDim,
    selectedContainer = Color(0xFFDDDEE4),
    selectedContent = DarkCard,
    // The same brand blue as the light scheme, for the same reason. The selected
    // chip (#DDDEE4) is still the loudest thing in the nav area and must stay so:
    // the liquid's red tops out at L* 36, below the grey this replaced and well
    // below the chip, so the FAB gains hue without gaining rank.
    fabContainer = NothingLiquidBlue,
    fabContent = DarkInk,
    // The same disc and the same white as the light scheme. The badge is opaque
    // and small, so it does not need the scheme's softened ink to stop glaring.
    badgeContainer = NothingRed,
    badgeContent = Color.White,
)

private val LocalNavPillColors = staticCompositionLocalOf { LightNavPill }

/** Nav-pill colours for the active theme; see [NavPillColors]. */
val MaterialTheme.navPill: NavPillColors
    @Composable @ReadOnlyComposable get() = LocalNavPillColors.current

private val FONT_DIRS = listOf("/system/fonts", "/product/fonts", "/system_ext/fonts")

/**
 * The Settings-headline typeface: Nothing OS renders its large Settings/Glyph
 * titles in NType82-Regular (its lighter serif cut). Its sibling
 * NType82-Headline is a heavier display cut that reads too bold here.
 */
private val HEADLINE_FONT_NAMES = listOf("NType82-Regular")

/** Loads the Settings-headline font from the firmware; null if unavailable. */
private fun deviceHeadlineFont(): FontFamily? {
    val files = FONT_DIRS.flatMap { File(it).listFiles()?.toList() ?: emptyList() }
    val ordered = HEADLINE_FONT_NAMES.mapNotNull { base ->
        files.firstOrNull { it.name.equals("$base.otf", true) || it.name.equals("$base.ttf", true) }
    }
    for (file in ordered) {
        try {
            return FontFamily(Font(file)).also {
                DebugLog.i("Theme", "headline font loaded from ${file.path}")
            }
        } catch (e: Exception) {
            DebugLog.w("Theme", "failed to load ${file.path}: ${e.message}")
        }
    }
    return null
}

private fun buildTypography(headline: FontFamily): Typography = Typography().let { base ->
    base.copy(
        displaySmall = base.displaySmall.copy(fontFamily = headline),
        headlineLarge = base.headlineLarge.copy(fontFamily = headline),
        // LargeTopAppBar's EXPANDED title style. Sized 36sp/44sp to match the
        // Nothing OS Settings large title. Weight Normal + synthesis off so
        // NType82-Regular renders at its true natural weight (never faux-bold),
        // matching Settings exactly.
        headlineMedium = base.headlineMedium.copy(
            fontFamily = headline,
            fontSize = 36.sp,
            lineHeight = 44.sp,
            fontWeight = FontWeight.Normal,
            fontSynthesis = FontSynthesis.None,
        ),
        headlineSmall = base.headlineSmall.copy(
            fontFamily = headline, fontWeight = FontWeight.Normal, fontSynthesis = FontSynthesis.None,
        ),
        // titleLarge is the LargeTopAppBar's COLLAPSED title style — themed so
        // the app-bar title keeps the headline font and weight when scrolled.
        titleLarge = base.titleLarge.copy(
            fontFamily = headline, fontWeight = FontWeight.Normal, fontSynthesis = FontSynthesis.None,
        ),
    )
}

@Composable
fun GlyphWorksTheme(content: @Composable () -> Unit) {
    val typography = remember {
        val headline = deviceHeadlineFont() ?: run {
            DebugLog.i("Theme", "no Nothing headline font found; using system serif")
            FontFamily.Serif
        }
        buildTypography(headline)
    }
    // Single source of truth for light/dark, shared by the M3 scheme and the
    // extra nav-pill roles.
    val dark = isSystemInDarkTheme()
    CompositionLocalProvider(LocalNavPillColors provides if (dark) DarkNavPill else LightNavPill) {
        MaterialTheme(
            colorScheme = if (dark) DarkScheme else LightScheme,
            // MD3's EXPRESSIVE motion scheme: under-damped spatial springs
            // (0.8 default / 0.6 fast) that overshoot slightly and settle
            // back, and stiff, never-bouncing effects springs for colour and
            // alpha. Every stock M3 component (Switch, Slider, RadioButton,
            // AlertDialog, the app bar's snap) reads this through
            // MaterialTheme.motionScheme, so it lands app-wide for free.
            //
            // Deliberately plain MaterialTheme, NOT MaterialExpressiveTheme:
            // that one also swaps component shape and COLOUR defaults, which
            // would break this theme's strict monochrome palette. Motion only.
            motionScheme = MotionScheme.expressive(),
            typography = typography,
            content = content,
        )
    }
}
