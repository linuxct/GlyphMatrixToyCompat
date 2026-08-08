package space.linuxct.glyphmatrixtoycompat.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import space.linuxct.glyphmatrixtoycompat.core.design.DEFAULT_LEVELS
import space.linuxct.glyphmatrixtoycompat.core.design.Design
import space.linuxct.glyphmatrixtoycompat.core.design.DesignFrame
import space.linuxct.glyphmatrixtoycompat.core.design.DesignFrames
import space.linuxct.glyphmatrixtoycompat.core.design.DesignKind
import space.linuxct.glyphmatrixtoycompat.core.design.DesignVariant
import space.linuxct.glyphmatrixtoycompat.core.design.PokemonCodename
import kotlin.math.abs

/**
 * The three pieces of arithmetic the Create grid is built on: how many columns a
 * window gets, how tall a card comes out, and which frames of a design its card
 * actually plays.
 *
 * All three are deliberately pure functions rather than expressions buried in a
 * composable, and for the same reason: they are the parts that are wrong
 * *silently*. A grid that gives a small phone two columns instead of three, a
 * middle column whose cards are 8 dp taller than their neighbours', a preview that
 * races a 46-frame animation past at six times its own speed — all three render
 * perfectly happily, and the failure is only visible to somebody holding the right
 * device with the right design on it. Every one of them was in fact found that way,
 * on a screenshot, after shipping.
 */
class DesignPreviewTest {
    // ---------- columns ----------

    /** The 411 x 919 dp window of the phone this app is built for. */
    private val phoneWindow = 411.dp

    @Test
    fun `a phone gets three columns`() {
        assertEquals(3, designGridColumns(phoneWindow))
    }

    @Test
    fun `a wider window gets more`() {
        // The adaptive half of the rule: a tablet fills its width instead of
        // showing three enormous cards.
        assertEquals(5, designGridColumns(600.dp))
        assertEquals(6, designGridColumns(800.dp))
    }

    @Test
    fun `an unmeasured window falls back to the phone count`() {
        // Exactly one composition long, on the frame before the window has
        // measured itself. Answered rather than divided by.
        assertEquals(DESIGN_GRID_MIN_COLUMNS, designGridColumns(Dp.Unspecified))
        assertEquals(DESIGN_GRID_MIN_COLUMNS, designGridColumns(0.dp))
    }

    // ---------- how tall a card is ----------

    /**
     * The invariant that IS "every card in the grid is the same height".
     *
     * A card's disc is `fillMaxWidth().aspectRatio(1f)`, so the disc's diameter —
     * and therefore the card's height — is `slot - cellInset - 2 * discInset`. The
     * slot is the same for every column in a `Fixed(n)` grid, so the cards are the
     * same height exactly when `cellInset + 2 * discInset` is constant across
     * columns. Anything that changes one of the two margins without the other
     * brings the ragged middle column back.
     */
    private fun cardChrome(column: Int, columns: Int): Dp =
        designCellInsetWidth(column, columns) + designDiscSideInset(column, columns) * 2

    @Test
    fun `every column's card is the same height`() {
        for (columns in DESIGN_GRID_MIN_COLUMNS..DESIGN_GRID_MAX_COLUMNS) {
            val expected = cardChrome(0, columns)
            for (column in 0 until columns) {
                assertEquals(
                    "column $column of $columns",
                    expected.value,
                    cardChrome(column, columns).value,
                    0.01f,
                )
            }
        }
    }

    // ---------- which frames a preview plays ----------

    @Test
    fun `a static design shows one frame`() {
        // Even when the file carries several — which is what a dynamic design
        // switched back to static looks like. `kind` is the design's own answer to
        // "does this move", and CustomScreen plays frame 0 on the panel for the
        // same reason.
        val steps = previewSteps(listOf(120, 120, 120), dynamic = false)
        assertEquals(1, steps.size)
        assertEquals(0, steps[0].frameIndex)
    }

    @Test
    fun `a design with no frames has nothing to play`() {
        // An empty variant. The card draws a bare disc rather than crashing.
        assertTrue(previewSteps(emptyList(), dynamic = true).isEmpty())
    }

    @Test
    fun `a short animation plays every frame in order`() {
        val steps = previewSteps(listOf(100, 200, 300), dynamic = true)
        assertEquals(listOf(0, 1, 2), steps.map { it.frameIndex })
        // Each frame keeps its own timing.
        assertEquals(listOf(100, 200, 300), steps.map { it.holdMs })
    }

    @Test
    fun `the format's maximum is capped and evenly spaced`() {
        // 240 frames is DesignCodec.MAX_FRAMES. Playing all of them in a 74 dp
        // disc is a strobe, and would mean 240 cached bitmaps per visible card.
        val steps = previewSteps(List(240) { 40 }, dynamic = true)
        assertEquals(PREVIEW_MAX_STEPS, steps.size)
        // Evenly spaced across the WHOLE animation, starting at frame 0 — not the
        // first eight frames, which for a slow animation would look like nothing
        // happening at all.
        assertEquals(listOf(0, 30, 60, 90, 120, 150, 180, 210), steps.map { it.frameIndex })
    }

    @Test
    fun `a sampled preview runs at roughly the design's own speed`() {
        // The property that actually matters, stated as the user would: the whole
        // loop takes about as long as the design does. 46 frames of 80 ms is
        // 3.68 s; the old rule gave 8 x 80 = 640 ms, nearly six times too fast.
        val durations = List(46) { 80 }
        val real = durations.sum()
        val loop = previewSteps(durations, dynamic = true).sumOf { it.holdMs }
        assertTrue("$loop should be within a fifth of $real", abs(loop - real) * 5 <= real)
    }

    @Test
    fun `the fastest legal frame is slowed to the floor`() {
        // DesignCodec.MIN_DURATION_MS is 20 ms. That is a legitimate thing to draw
        // for the panel and a flicker in a thumbnail.
        val steps = previewSteps(listOf(20, 20, 20), dynamic = true)
        assertTrue(steps.all { it.holdMs == PREVIEW_MIN_HOLD_MS })
    }

    @Test
    fun `a minute-long frame is held to the ceiling`() {
        // DesignCodec.MAX_DURATION_MS is 60 s. A card that sits on one frame for a
        // minute is indistinguishable from a card that is broken.
        val steps = previewSteps(listOf(60_000, 100), dynamic = true)
        assertEquals(PREVIEW_MAX_HOLD_MS, steps[0].holdMs)
        assertEquals(100, steps[1].holdMs)
    }

    // ---------- decoding a preview's pixels ----------

    /**
     * A frame of plausible art for [codename]: one base36 character per cell,
     * indexing the default three-entry palette, varied enough that a decode which
     * lost or shifted a cell would show up as a mismatch rather than as two
     * identically blank arrays.
     */
    private fun cellsFor(codename: PokemonCodename, seed: Int): String {
        val sb = StringBuilder(codename.cellCount)
        for (i in 0 until codename.cellCount) sb.append('0' + (i * 7 + seed * 13) % 3)
        return sb.toString()
    }

    private fun designOf(
        codename: PokemonCodename,
        cells: List<String>,
        kind: DesignKind = DesignKind.DYNAMIC,
    ) = Design(
        id = "art-${codename.codename}",
        modifiedAt = "2026-07-30T12:00:00Z",
        kind = kind,
        levels = DEFAULT_LEVELS,
        variants = mapOf(
            codename.codename to DesignVariant(cells.map { DesignFrame(durationMs = 120, cells = it) }),
        ),
    )

    /** Exactly what [designPreviewArt] used to compute up front, for one step. */
    private fun eagerFrame(design: Design, codename: PokemonCodename, frameIndex: Int): IntArray =
        DesignFrames.decode(
            design.variantFor(codename)!!.frames[frameIndex].cells,
            design.levels,
            codename.size,
        ) ?: IntArray(codename.cellCount)

    @Test
    fun `lazy decoding yields exactly the frames eager decoding did`() {
        // The whole safety argument for decoding on first draw instead of at
        // composition: same pixels, same order, at both geometries and at both
        // ends of the sampling rule (every frame played, and 240 frames sampled
        // down to eight).
        for (codename in PokemonCodename.entries) {
            for (count in listOf(1, 5, PREVIEW_MAX_STEPS, 240)) {
                val design = designOf(codename, List(count) { cellsFor(codename, it) })
                val art = designPreviewArt(design, codename)
                assertEquals(codename.size, art.size)
                assertEquals(art.steps.size, art.frameCount)
                for ((step, plan) in art.steps.withIndex()) {
                    assertArrayEquals(
                        "${codename.codename} x$count step $step",
                        eagerFrame(design, codename, plan.frameIndex),
                        art.frame(step),
                    )
                }
            }
        }
    }

    @Test
    fun `composing a card decodes nothing`() {
        // The point of the change. `designPreviewArt` runs inside the grid item's
        // remember — i.e. at composition, i.e. during the swipe onto the tab that
        // was reported as stuttering — and a card shows one step at a time.
        val codename = PokemonCodename.BELLSPROUT
        val art = designPreviewArt(designOf(codename, List(8) { cellsFor(codename, it) }), codename)
        assertEquals(0, art.decodedCount)
    }

    @Test
    fun `a step is decoded once, on first ask, and kept`() {
        val codename = PokemonCodename.BELLSPROUT
        val art = designPreviewArt(designOf(codename, List(8) { cellsFor(codename, it) }), codename)
        val first = art.frame(0)
        assertEquals(1, art.decodedCount)
        // The same array, not an equal one: the draw path asks on every
        // rasterisation, and re-decoding 169 or 625 cells there would be the cost
        // this is here to remove.
        assertSame(first, art.frame(0))
        assertEquals(1, art.decodedCount)
        art.frame(3)
        assertEquals(2, art.decodedCount)
    }

    @Test
    fun `a frame that will not decode becomes a blank frame`() {
        // A file whose cells are the wrong length for the geometry: DesignCodec
        // rejects it on import, nothing re-checks it on the way out of storage,
        // and one damaged frame must not take out the whole tab.
        val codename = PokemonCodename.BELLSPROUT
        val design = designOf(codename, listOf(cellsFor(codename, 0), "nonsense", cellsFor(codename, 2)))
        val art = designPreviewArt(design, codename)
        val blank = art.frame(1)!!
        assertEquals(codename.cellCount, blank.size)
        assertTrue(blank.all { it == 0 })
        // Its neighbours are unharmed, and the blank is kept rather than retried
        // on every tick.
        assertArrayEquals(eagerFrame(design, codename, 2), art.frame(2))
        assertSame(blank, art.frame(1))
    }

    @Test
    fun `a design with no artwork has no frames to ask for`() {
        // The bare-disc case: a design created for the other phone, or one whose
        // only variant is empty. The card must render rather than crash.
        assertEquals(0, DesignPreviewArt.Empty.frameCount)
        assertNull(DesignPreviewArt.Empty.frame(0))
        val empty = Design(id = "empty", variants = mapOf("bellsprout" to DesignVariant(emptyList())))
        val art = designPreviewArt(empty, PokemonCodename.BELLSPROUT)
        assertEquals(0, art.frameCount)
        assertTrue(art.steps.isEmpty())
    }

    @Test
    fun `a static design carries only the frame it plays`() {
        // The sampling is applied before anything is carried, so a static design
        // with a hundred frames holds one string and can decode one frame — not a
        // hundred, and not eight.
        val codename = PokemonCodename.ARBOK
        val design = designOf(codename, List(100) { cellsFor(codename, it) }, kind = DesignKind.STATIC)
        val art = designPreviewArt(design, codename)
        assertEquals(1, art.frameCount)
        assertArrayEquals(eagerFrame(design, codename, 0), art.frame(0))
    }

    @Test
    fun `a design drawn for the other phone still previews`() {
        // previewCodename's fallback, which the lazy form must carry the geometry
        // and palette of — a blank frame sized for the wrong panel would be the
        // wrong length for drawMatrix.
        val other = PokemonCodename.ARBOK
        val design = designOf(other, listOf(cellsFor(other, 1)))
        val art = designPreviewArt(design, PokemonCodename.BELLSPROUT)
        assertEquals(other.size, art.size)
        assertArrayEquals(eagerFrame(design, other, 0), art.frame(0))
    }
}
