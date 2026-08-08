package space.linuxct.glyphworks.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import space.linuxct.glyphworks.core.design.DEFAULT_LEVELS
import space.linuxct.glyphworks.core.design.DESIGN_FORMAT
import space.linuxct.glyphworks.core.design.DESIGN_FORMAT_VERSION
import space.linuxct.glyphworks.core.design.Design
import space.linuxct.glyphworks.core.design.DesignCodec
import space.linuxct.glyphworks.core.design.DesignFrame
import space.linuxct.glyphworks.core.design.DesignFrames
import space.linuxct.glyphworks.core.design.DesignVariant
import space.linuxct.glyphworks.core.design.PokemonCodename

/**
 * What a new design is made of, given the answer to "which phone is this for".
 *
 * This is the whole functional content of that third question — the dialog only
 * collects a `Set<PokemonCodename>` and hands it here — so it is where the choice
 * is worth pinning down. Three things are asserted, and each is a way the feature
 * could be quietly wrong on a device nobody testing it owns:
 *
 * 1. **Only the chosen sizes exist.** The variants present are what the editor
 *    later derives its switcher from, so an extra key is an extra 48 dp row on
 *    somebody's screen forever.
 * 2. **"Both" is byte-identical to what creation did before this choice
 *    existed.** The default path must not have moved.
 * 3. **"The other phone only" produces a design `DesignCodec` accepts.** That is
 *    the path with no frames anywhere, and the codec's `REASON_NO_VARIANTS` check
 *    is one map entry away from rejecting it.
 *
 * Plain JVM: [seedVariants] takes two enums and returns a map, and touches no
 * `android.*` and no `Core`. (Its caller reads `Core.glyphLink` for the home
 * device — which is exactly why the home device is a parameter here rather than
 * something this function looks up.)
 */
class DesignSeedingTest {
    private val bellsprout = PokemonCodename.BELLSPROUT
    private val arbok = PokemonCodename.ARBOK

    /** The variant the home device is seeded with: exactly one blank frame. */
    private fun blankFrame(codename: PokemonCodename) =
        DesignVariant(frames = listOf(DesignFrame(cells = DesignFrames.blank(codename))))

    // region the three choices

    @Test
    fun `this phone only seeds one variant, with a blank frame to draw on`() {
        val variants = seedVariants(setOf(bellsprout), home = bellsprout)
        assertEquals(setOf(bellsprout.codename), variants.keys)
        assertEquals(blankFrame(bellsprout), variants.getValue(bellsprout.codename))
    }

    /**
     * The interesting one. The user is on a Phone (4a) Pro drawing for a Phone
     * (3), so nothing is seeded with a frame at all — the arbok variant is the
     * format's "a blank canvas is waiting" state, which is what the Create tab
     * renders as "(empty)".
     */
    @Test
    fun `the other phone only seeds that phone, and nothing of this one`() {
        val variants = seedVariants(setOf(arbok), home = bellsprout)
        assertEquals(setOf(arbok.codename), variants.keys)
        assertEquals(DesignVariant(), variants.getValue(arbok.codename))
    }

    /**
     * Both is exactly what `CreateState.create` produced before the choice
     * existed: the device's own variant carries one blank frame, the other is
     * empty. Anything else would be a silent change to the default path.
     */
    @Test
    fun `both seeds the home variant with a frame and the other empty`() {
        val variants = seedVariants(PokemonCodename.entries.toSet(), home = bellsprout)
        assertEquals(listOf(bellsprout.codename, arbok.codename), variants.keys.toList())
        assertEquals(blankFrame(bellsprout), variants.getValue(bellsprout.codename))
        assertEquals(DesignVariant(), variants.getValue(arbok.codename))
    }

    /**
     * The dialog is a single-choice control and cannot produce this, but a design
     * with no variants is one `DesignCodec` refuses to save — so an empty set
     * falls back to the home device rather than becoming an unsaveable design.
     */
    @Test
    fun `an empty choice falls back to this phone rather than to nothing`() {
        assertEquals(setOf(bellsprout.codename), seedVariants(emptySet(), home = bellsprout).keys)
    }

    // endregion

    // region the codec accepts every one of them

    private fun design(targets: Set<PokemonCodename>, home: PokemonCodename) = Design(
        format = DESIGN_FORMAT,
        formatVersion = DESIGN_FORMAT_VERSION,
        id = "0123456789abcdef0123456789abcdef",
        name = "Slow Ember",
        createdAt = "2026-07-30T12:00:00Z",
        modifiedAt = "2026-07-30T12:00:00Z",
        createdWith = "GlyphWorks 2.0.0",
        levels = DEFAULT_LEVELS,
        variants = seedVariants(targets, home),
    )

    /**
     * The round trip every choice has to survive, through the real encoder and
     * the real validator — this is what "saveable" means, since `DesignStore`
     * writes `DesignCodec.encode` and reads `DesignCodec.decode`.
     */
    private fun assertSurvivesTheCodec(targets: Set<PokemonCodename>, home: PokemonCodename) {
        val original = design(targets, home)
        val result = DesignCodec.decode(DesignCodec.encode(original))
        assertTrue("rejected: $result", result is DesignCodec.Result.Ok)
        assertEquals(original.variants, (result as DesignCodec.Result.Ok).design.variants)
    }

    @Test
    fun `a design for this phone only is valid`() =
        assertSurvivesTheCodec(setOf(bellsprout), home = bellsprout)

    // endregion
}
