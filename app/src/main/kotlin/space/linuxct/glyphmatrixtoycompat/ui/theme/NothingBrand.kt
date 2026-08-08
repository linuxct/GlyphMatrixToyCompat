package space.linuxct.glyphmatrixtoycompat.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The two brand hues this app paints with, and the arithmetic that makes them
 * safe to use.
 *
 * ## Why there is any hue here at all
 *
 * The theme is monochrome by rule (see `Theme.kt`), and the rule earns its keep by
 * having its exceptions *enumerated* rather than by being absolute. There are
 * three, and this file is one of them: the Create tab's `+` FAB is painted in **Nothing's
 * own brand red and blue**, because it is the button that makes a thing for a
 * Nothing device and it is the one place the product is allowed to sign its name.
 * (The second exception is `RECORDING_DOT_COLOR` in `ui/design/GlyphCanvas.kt`,
 * which is not a UI accent at all — it is a picture of a red square that exists on
 * the back of the phone. The third is the navigation bar's setup-attention badge,
 * which reuses [NothingRed] from this file — see `NavPillColors.badgeContainer`.)
 *
 * **This file is still not a palette.** It offers exactly the two brand hues and
 * the arithmetic that derives the painted red; there is nothing here to expand and
 * no general-purpose accent. The badge is an exception that had to be argued for
 * and written down, which is the only way a new one is ever added. Anything else
 * that carries meaning — state, selection, emphasis — still does it with
 * contrast.
 *
 * ## Why the red is not the brand red
 *
 * [NothingBlue] is very dark: L\* 9.8, darker than the nav pill it sits beside.
 * [NothingRed] at full strength is L\* 46, four and a half times its luminance,
 * and a 56 dp disc split between them reads as a bright red blob with a shadow in
 * it rather than as two colours moving. So the red is *derived* rather than
 * dropped in: [NothingLiquidRed] is exactly [NothingRed] scaled in **linear
 * light** — every channel multiplied by one number, so the hue and the ratio
 * between channels are untouched — until it lands at [LIQUID_RED_LSTAR].
 *
 * That number is not a taste call either. The FAB used to be a flat mid-grey
 * (#5A5A62 light, #4E5157 dark — L\* 38.5 and 34.4), chosen so the button reads as
 * a separate object from the near-black pill without becoming the brightest thing
 * in the nav area (the selected chip is, and must stay so). Landing the red's
 * lightness in the middle of that pair keeps every one of those judgements intact
 * and changes only the hue.
 *
 * ## The contrast argument, which is the part that must not rot
 *
 * The `+` is near-white in both schemes and the fill *moves*, so "does the icon
 * read?" is a question about every colour the animation can produce, not about one
 * pair. [liquidMix] is that set — it mirrors the AGSL `mix()` in `ui/LiquidFab.kt`
 * exactly — and its lightest possible output is [NothingLiquidRed] itself, at
 * **6.7:1** against the ink. `NothingBrandTest` walks the whole ramp rather than
 * trusting that sentence.
 */

/** Nothing's red, as the user specified it. The brand value, unmodified. */
internal val NothingRed = Color(0xFFD71921)

/** Nothing's blue, as the user specified it. The brand value, unmodified. */
internal val NothingBlue = Color(0xFF110E56)

/**
 * The lightness [NothingLiquidRed] is scaled to, in CIE L\*: the midpoint of the
 * two greys the liquid FAB replaces (34.4 and 38.5). See the file KDoc.
 */
internal const val LIQUID_RED_LSTAR = 36f

/**
 * The red the FAB is actually painted in: **#AA1118**, [NothingRed] darkened to
 * [LIQUID_RED_LSTAR].
 *
 * Derived at class-init from the brand value rather than pasted in as a hex, so
 * that the relationship survives — change the target lightness and the red follows
 * it, still on the same line from black through Nothing's red.
 */
internal val NothingLiquidRed: Color = NothingRed.scaledToLuminance(luminanceOfLstar(LIQUID_RED_LSTAR))

/**
 * The FAB's base: the brand blue, unmodified, and the same in both schemes.
 *
 * A brand colour does not change because the page behind it went dark, and unlike
 * the greys it replaces this one does not have to: at L\* 9.8 it is quiet enough
 * for a black page, and the red moving through it is what keeps it visible on a
 * light one.
 */
internal val NothingLiquidBlue = NothingBlue

// ---------- the arithmetic ----------

/**
 * The sRGB electro-optical transfer function: one 0..1 channel, encoded → linear.
 *
 * Spelled out rather than taken from the colour space attached to a [Color]
 * because its inverse ([linearToSrgb]) is needed too, and a pair of transfer
 * functions that do not agree is the sort of thing that produces a *slightly*
 * wrong colour nobody can explain.
 */
internal fun srgbToLinear(channel: Float): Float =
    if (channel <= 0.04045f) channel / 12.92f else ((channel + 0.055f) / 1.055f).pow(2.4f)

/** The inverse of [srgbToLinear]: linear → encoded, clamped to the 0..1 gamut. */
internal fun linearToSrgb(linear: Float): Float {
    val c = linear.coerceIn(0f, 1f)
    return if (c <= 0.0031308f) c * 12.92f else 1.055f * c.pow(1f / 2.4f) - 0.055f
}

/**
 * The relative luminance a CIE lightness of [lStar] corresponds to — the inverse
 * of `L* = 116·Y^⅓ − 16`, which is the form of it that matters here because the
 * lightness is the input and the colour is the output.
 *
 * Only the cube-root branch: this is used for mid-lightness colours, an order of
 * magnitude above the linear segment near black where the two branches differ.
 */
internal fun luminanceOfLstar(lStar: Float): Float = ((lStar + 16f) / 116f).pow(3)

/**
 * This colour with every **linear** channel multiplied by one factor, so that its
 * relative luminance becomes [target].
 *
 * Scaling in linear light is what makes this a *dimmer of the same colour* rather
 * than a different colour: hue and saturation are ratios between the linear
 * channels, and multiplying all three leaves every ratio alone. Doing the same
 * thing to the encoded values — the obvious `Color(r * f, g * f, b * f)` — would
 * bend the colour towards whichever channel the gamma curve happens to favour.
 *
 * A [target] the colour cannot reach without clipping is clamped by
 * [linearToSrgb], which is a lightening request no dark hue can satisfy; the
 * result stays in gamut and simply stops getting brighter.
 */
internal fun Color.scaledToLuminance(target: Float): Color {
    val current = luminance()
    if (current <= 0f) return this
    val factor = target / current
    return Color(
        red = linearToSrgb(srgbToLinear(red) * factor),
        green = linearToSrgb(srgbToLinear(green) * factor),
        blue = linearToSrgb(srgbToLinear(blue) * factor),
        alpha = alpha,
    )
}

/**
 * WCAG 2.x contrast between two opaque colours, 1.0 (identical) to 21.0 (black on
 * white). Order does not matter.
 */
internal fun contrastRatio(a: Color, b: Color): Float {
    val la = a.luminance()
    val lb = b.luminance()
    return (max(la, lb) + 0.05f) / (min(la, lb) + 0.05f)
}

/**
 * The colour the liquid FAB shows where its field sits at [t] — 0 all blue, 1 all
 * red.
 *
 * A channel-wise mix of the *encoded* values, because that is what the shader
 * does: AGSL's `mix()` operates on `layout(color)` uniforms already converted to
 * the destination colour space, and the destination here is an ordinary sRGB
 * surface. Interpolating in linear light or Oklab instead (which is what
 * Compose's own `lerp(Color, Color, Float)` would give) would describe a ramp the
 * GPU is not drawing, and the contrast test would then be testing the wrong
 * colours.
 */
internal fun liquidMix(t: Float): Color {
    val f = t.coerceIn(0f, 1f)
    return Color(
        red = NothingLiquidBlue.red + (NothingLiquidRed.red - NothingLiquidBlue.red) * f,
        green = NothingLiquidBlue.green + (NothingLiquidRed.green - NothingLiquidBlue.green) * f,
        blue = NothingLiquidBlue.blue + (NothingLiquidRed.blue - NothingLiquidBlue.blue) * f,
    )
}
