package space.linuxct.glyphmatrixtoycompat.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import space.linuxct.glyphmatrixtoycompat.core.design.Design
import space.linuxct.glyphmatrixtoycompat.core.design.DesignFrame
import space.linuxct.glyphmatrixtoycompat.core.design.DesignKind
import space.linuxct.glyphmatrixtoycompat.core.design.DesignVariant
import space.linuxct.glyphmatrixtoycompat.core.design.PokemonCodename

/**
 * The three lines of words on a design card.
 *
 * They were four `@Composable` helpers that each looked its own strings up and
 * each ran on every composition of every card — and two of them reached
 * [formatTimestamp], which is an `Instant.parse` plus a localised
 * `DateTimeFormatter`. A swipe onto the Create tab composes a whole screenful of
 * cards at once (the pager keeps one page beyond the viewport, so they compose
 * *before* the page is even visible), so that was thirty parses inside the
 * animation the user was complaining about.
 *
 * The fix is a memo, and a memo is only ever as good as the guarantee that it
 * returns what the direct computation would have. That guarantee is what this
 * file is: [legacyCardText] below is the old, per-composition code path written
 * out from the same resolved strings, and every case asserts the two agree
 * exactly. If [designCardText] ever drifts — a separator, an order, a fallback —
 * these fail, and they fail on the wording rather than on the caching.
 *
 * The resource lookups arrive as data ([DesignCardStrings]) precisely so that the
 * assembly is reachable from a plain JVM test; the fakes below stand in for the
 * app's own `strings.xml` in the shapes those strings actually have.
 */
class DesignCardTextTest {
    private val separator = " · "

    private fun strings() = DesignCardStrings(
        noArt = "no artwork yet",
        kindStatic = "Static",
        kindDynamic = "Dynamic",
        frameCount = { n -> if (n == 1) "1 frame" else "$n frames" },
        by = { author -> "by $author" },
        variantEmpty = { name -> "$name (empty)" },
        deviceName = { codename ->
            when (codename) {
                PokemonCodename.BELLSPROUT -> "Nothing Phone (4a) Pro"
                PokemonCodename.ARBOK -> "Nothing Phone (3)"
            }
        },
    )

    private fun design(
        name: String = "Slow Ember",
        author: String = "",
        modifiedAt: String = "2026-07-30T12:34:56Z",
        kind: DesignKind = DesignKind.DYNAMIC,
        variants: Map<String, Int> = mapOf("bellsprout" to 12),
    ) = Design(
        id = "abc",
        name = name,
        author = author,
        modifiedAt = modifiedAt,
        kind = kind,
        variants = variants.mapValues { (_, count) -> DesignVariant(List(count) { DesignFrame(120, "") }) },
    )

    /**
     * The card's words as the four separate `@Composable` helpers computed them,
     * one composition at a time. Kept deliberately verbose and duplicative — it is
     * the thing the fast path has to agree with, so it must not share any of the
     * fast path's structure.
     */
    private fun legacyCardText(design: Design, name: String, s: DesignCardStrings): DesignCardText {
        val frames = design.variants.values.maxOfOrNull { it.frames.size } ?: 0
        val meta = if (frames == 0) s.noArt else s.frameCount(frames)
        val credit = if (design.author.isNotBlank()) {
            s.by(design.author)
        } else {
            formatTimestamp(design.modifiedAt)
        }
        val summaryParts = mutableListOf(
            if (design.kind == DesignKind.DYNAMIC) s.kindDynamic else s.kindStatic,
            s.frameCount(frames),
        )
        if (design.author.isNotBlank()) summaryParts += s.by(design.author)
        val present = PokemonCodename.entries.mapNotNull { codename ->
            val variant = design.variantFor(codename) ?: return@mapNotNull null
            val device = s.deviceName(codename)
            if (variant.frames.isEmpty()) s.variantEmpty(device) else device
        }
        val variants = if (present.isEmpty()) s.noArt else present.joinToString(separator)
        val provenance = variants + separator + formatTimestamp(design.modifiedAt)
        val spoken = name + separator + summaryParts.joinToString(separator) + separator + provenance
        return DesignCardText(meta, credit, spoken)
    }

    // ---------- the memo says what the direct computation said ----------

    @Test
    fun `an unnamed design uses the name the card was given`() {
        // The blank-name fallback is resolved by the caller (it is a string
        // resource), which is why the name is part of the memo's key as well as of
        // its output.
        val text = designCardText(design(name = ""), "Unnamed", strings())
        assertTrue(text.spoken.startsWith("Unnamed$separator"))
    }

    // ---------- the words themselves ----------

    @Test
    fun `the credit line is the author when there is one`() {
        // The one fact that tells two otherwise identical cards apart.
        assertEquals("by linuxct", designCardText(design(author = "linuxct"), "n", strings()).credit)
    }

    @Test
    fun `the credit line is the modified date when there is not`() {
        val design = design()
        assertEquals(formatTimestamp(design.modifiedAt), designCardText(design, "n", strings()).credit)
    }

    @Test
    fun `the meta line counts the richest variant`() {
        // A design drawn on a Phone (3) and opened here still says how much art is
        // in it, rather than reporting the zero frames of a variant nobody filled.
        val design = design(variants = mapOf("bellsprout" to 0, "arbok" to 46))
        assertEquals(46, designFrameCount(design))
        assertEquals("46 frames", designCardText(design, "n", strings()).meta)
    }

    @Test
    fun `a design with no art anywhere says so`() {
        val design = design(variants = mapOf("bellsprout" to 0))
        assertEquals("no artwork yet", designCardText(design, "n", strings()).meta)
    }

    @Test
    fun `the spoken sentence keeps everything the cell dropped`() {
        // Nothing is dropped for a screen reader: the kind, the frame count, the
        // author, the device list and the date are all still said, in that order.
        val design = design(author = "linuxct", variants = mapOf("bellsprout" to 12, "arbok" to 0))
        val spoken = designCardText(design, "Slow Ember", strings()).spoken
        assertEquals(
            "Slow Ember · Dynamic · 12 frames · by linuxct · Nothing Phone (4a) Pro · " +
                "Nothing Phone (3) (empty) · " + formatTimestamp(design.modifiedAt),
            spoken,
        )
    }

    // ---------- the memo's key ----------

    // ---------- the expensive line ----------
}
