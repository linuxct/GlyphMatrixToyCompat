package space.linuxct.glyphmatrixtoycompat.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The FAB's colour derivation, and the one thing about it that is a promise rather
 * than a preference: the `+` has to stay readable against a fill that **moves**.
 *
 * None of this restates a hex. The brand values are the user's and are inputs; what
 * is asserted is the arithmetic applied to them — that darkening the red left the
 * hue where it was, that it landed on the lightness it was aimed at, and that every
 * colour the shader can produce clears the contrast bar the ink's own KDoc claims.
 */
class NothingBrandTest {
    /** Both FAB inks (light and dark scheme); near-white, and near enough alike. */
    private val inks = listOf(Color(0xFFF2F2FA), Color(0xFFEFF0F7))

    // ---------- the transfer functions ----------

    @Test
    fun `the sRGB transfer functions are inverses`() {
        var c = 0f
        while (c <= 1f) {
            assertEquals(c, linearToSrgb(srgbToLinear(c)), 1e-4f)
            c += 1f / 64f
        }
    }

    // ---------- the derived red ----------

    // ---------- what the icon sits on ----------

    @Test
    fun `every colour the liquid can produce clears 6 to 1 against the ink`() {
        // The fill moves, so this is the whole ramp and not its endpoints: the
        // claim on NavPillColors.fabContent is about every frame of the animation.
        for (ink in inks) {
            for (step in 0..40) {
                val t = step / 40f
                val ratio = contrastRatio(liquidMix(t), ink)
                assertTrue("t=$t gave $ratio:1", ratio >= 6f)
            }
        }
    }

    @Test
    fun `the mix is monotone and hits both brand colours at its ends`() {
        assertEquals(NothingLiquidBlue, liquidMix(0f))
        assertEquals(NothingLiquidRed, liquidMix(1f))
        var previous = liquidMix(0f).luminance()
        for (step in 1..40) {
            val next = liquidMix(step / 40f).luminance()
            assertTrue(next > previous)
            previous = next
        }
    }
}
