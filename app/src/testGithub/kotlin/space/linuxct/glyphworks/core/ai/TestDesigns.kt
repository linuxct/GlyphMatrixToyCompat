package space.linuxct.glyphworks.core.ai

import space.linuxct.glyphworks.core.design.DEFAULT_LEVELS
import space.linuxct.glyphworks.core.design.Design
import space.linuxct.glyphworks.core.design.DesignFrame
import space.linuxct.glyphworks.core.design.DesignKind
import space.linuxct.glyphworks.core.design.DesignVariant
import space.linuxct.glyphworks.core.design.KeyMode
import space.linuxct.glyphworks.core.design.PokemonCodename

/**
 * Designs the `core/ai` tests share, in the shape [space.linuxct.glyphworks.core.design.DesignCodec]
 * would hand back: canonical timestamps, a safe id, palette entries in range.
 *
 * That matters — several tests assert that a document goes through `apply_design`
 * and comes back *identical*, which is only meaningful if the fixture is already
 * normalised. A fixture the codec would rewrite would make those assertions
 * either fail or, worse, pass for the wrong reason.
 *
 * Following `Fakes.kt`'s idiom of keeping shared test material out of the test
 * classes themselves.
 */
object TestDesigns {

    /** Every cell at palette index 0 — an LED that exists and is off. */
    fun blank(codename: PokemonCodename): String = "0".repeat(codename.cellCount)

    /** Every cell at palette index 2 — full brightness under [DEFAULT_LEVELS]. */
    fun lit(codename: PokemonCodename): String = "2".repeat(codename.cellCount)

    fun design(
        variants: Map<String, DesignVariant>,
        kind: DesignKind = DesignKind.DYNAMIC,
        levels: List<Int> = DEFAULT_LEVELS,
    ): Design = Design(
        id = "abc123",
        name = "Slow Ember",
        author = "linuxct",
        createdAt = "2026-07-30T12:00:00Z",
        modifiedAt = "2026-07-30T12:34:56Z",
        createdWith = "GlyphWorks 2.0.0",
        kind = kind,
        keyMode = KeyMode.PLAY_PAUSE,
        loop = true,
        levels = levels,
        variants = variants,
    )

    fun frames(codename: PokemonCodename): DesignVariant = DesignVariant(
        listOf(
            DesignFrame(120, blank(codename)),
            DesignFrame(160, lit(codename)),
        ),
    )

    fun bellsproutOnly(): Design = design(
        mapOf(PokemonCodename.BELLSPROUT.codename to frames(PokemonCodename.BELLSPROUT)),
    )

    fun bothVariants(): Design = design(
        mapOf(
            PokemonCodename.BELLSPROUT.codename to frames(PokemonCodename.BELLSPROUT),
            PokemonCodename.ARBOK.codename to frames(PokemonCodename.ARBOK),
        ),
    )

    /** A design carrying artwork for no panel this build knows. Not editable. */
    fun noVariants(): Design = design(emptyMap())
}
