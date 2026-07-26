package space.linuxct.glyphmatrixtoycompat.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import space.linuxct.glyphmatrixtoycompat.core.DebugLog
import java.io.File

/**
 * Nothing-styled theme, strictly MONOCHROME: black, white and grays only —
 * no hue anywhere. Attention states rely on contrast (full-strength ink vs
 * muted grays), and Nothing's own headline typeface is used for page titles.
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

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFEDEDED),
    onPrimary = Color(0xFF17171C),
    primaryContainer = Color(0xFF2A2A2E),
    onPrimaryContainer = Color(0xFFEDEDED),
    secondary = Color(0xFFB5B5BC),
    onSecondary = Color(0xFF17171C),
    secondaryContainer = Color(0xFF2A2A2E),
    onSecondaryContainer = Color(0xFFEDEDED),
    tertiary = Color(0xFFB5B5BC),
    onTertiary = Color(0xFF17171C),
    tertiaryContainer = Color(0xFF2A2A2E),
    onTertiaryContainer = Color(0xFFEDEDED),
    background = Color(0xFF0D0D0F),
    onBackground = Color(0xFFEDEDED),
    surface = Color(0xFF17171A),
    onSurface = Color(0xFFEDEDED),
    surfaceVariant = Color(0xFF242428),
    onSurfaceVariant = Color(0xFF9A9AA2),
    // Explicit neutral surface roles (see light scheme).
    surfaceTint = Color(0xFFEDEDED),
    surfaceBright = Color(0xFF38383C),
    surfaceDim = Color(0xFF0D0D0F),
    surfaceContainerLowest = Color(0xFF090909),
    surfaceContainerLow = Color(0xFF17171A),
    surfaceContainer = Color(0xFF1B1B1E),
    surfaceContainerHigh = Color(0xFF252528),
    surfaceContainerHighest = Color(0xFF303034),
    inverseSurface = Color(0xFFEDEDED),
    inverseOnSurface = Color(0xFF2E2E33),
    outline = Color(0xFF5A5A62),
    outlineVariant = Color(0xFF33333A),
    error = Color(0xFFEDEDED),
    onError = Color(0xFF17171C),
    errorContainer = Color(0xFF33333A),
    onErrorContainer = Color(0xFFEDEDED),
)

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
fun GmtcTheme(content: @Composable () -> Unit) {
    val typography = remember {
        val headline = deviceHeadlineFont() ?: run {
            DebugLog.i("Theme", "no Nothing headline font found; using system serif")
            FontFamily.Serif
        }
        buildTypography(headline)
    }
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkScheme else LightScheme,
        typography = typography,
        content = content,
    )
}
