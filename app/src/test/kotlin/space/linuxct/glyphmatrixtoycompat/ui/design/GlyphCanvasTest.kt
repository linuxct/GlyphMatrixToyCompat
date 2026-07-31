package space.linuxct.glyphmatrixtoycompat.ui.design

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import space.linuxct.glyphmatrixtoycompat.matrix.PanelMask
import space.linuxct.glyphmatrixtoycompat.ui.ILLUSTRATION_HEIGHT
import space.linuxct.glyphmatrixtoycompat.ui.TUTORIAL_BODY_WIDTH
import space.linuxct.glyphmatrixtoycompat.ui.TUTORIAL_MARKER_X
import space.linuxct.glyphmatrixtoycompat.ui.tutorialCamera
import kotlin.math.abs
import kotlin.math.hypot

/**
 * [matrixCellAt] must be the exact inverse of [drawMatrix]'s cell placement. An
 * off-by-one here is not a crash and not a visual glitch — it is a drawing tool
 * that lights a different pixel from the one you touched, which reads as broken
 * rather than buggy and would be very hard to spot in a review.
 *
 * So the placement is re-derived here from `drawMatrix`'s own two expressions,
 * and every assertion is against a cell centre computed that way rather than
 * against a number typed out by hand. The tests then run the full round trip —
 * cell -> its drawn centre -> back to a cell — over *every* cell of *both*
 * shipping geometries, plus the corners the panel does not have and the sub-cell
 * boundaries.
 *
 * The panel's own shape ([PanelMask]) is checked here too, in cells and against
 * the drawn geometry, because "which cells exist" and "which cell did I touch"
 * are the same question asked twice and a drawing tool is only honest while they
 * agree.
 *
 * Phase 8b added the editor's pinch-zoom, which puts a scale and a pan between
 * the finger and the panel, so the same exhaustive round trip is run again over a
 * spread of scales and offsets — along with the pan clamp that makes it
 * impossible to lose the disc off the edge of the screen.
 */
class GlyphCanvasTest {

    /**
     * `drawMatrix`'s placement, transcribed. If this ever disagrees with the draw
     * loop the tests below are meaningless, so it is deliberately the same two
     * lines and nothing more.
     */
    private fun centerOf(cell: IntOffset, disc: Offset, radius: Float, size: Int): Offset {
        val pitch = matrixCellPitch(radius, size)
        val g0x = disc.x - pitch * (size - 1) / 2f
        val g0y = disc.y - pitch * (size - 1) / 2f
        return Offset(g0x + cell.x * pitch, g0y + cell.y * pitch)
    }

    /**
     * The panel mask, re-derived **in pixels** — deliberately not by calling
     * [PanelMask].
     *
     * The mask is defined in cell units (`dx² + dy² <= (size / 2)²`), and the
     * draw loop asks it by index. This is the same question asked of the drawn
     * geometry instead: is the cell's own centre, as `drawMatrix` places it,
     * within `GRID_EXTENT` of the disc radius? The two are the same statement —
     * `(size / 2) * pitch = GRID_EXTENT * radius`, the size cancels — so an
     * independent check that they agree is a check that the mask really does
     * describe the circle the illustration draws, at every radius and zoom the
     * tests below try.
     */
    private fun isDrawn(cell: IntOffset, disc: Offset, radius: Float, size: Int): Boolean {
        val c = centerOf(cell, disc, radius, size)
        return hypot(c.x - disc.x, c.y - disc.y) <= radius * PanelMask.GRID_EXTENT
    }

    private val disc = Offset(311.5f, 402.25f)
    private val radius = 268.75f

    /** LEDs per panel — see [theMaskIsThePanelsOwnLedLayout]. */
    private val leds = mapOf(13 to 137, 25 to 489)

    @Test
    fun everyDrawnCellRoundTripsThroughItsOwnCentre() {
        for (size in intArrayOf(13, 25)) {
            var drawn = 0
            for (row in 0 until size) {
                for (column in 0 until size) {
                    val cell = IntOffset(column, row)
                    val hit = matrixCellAt(centerOf(cell, disc, radius, size), disc, radius, size)
                    if (isDrawn(cell, disc, radius, size)) {
                        assertEquals("cell $cell at size $size", cell, hit)
                        drawn++
                    } else {
                        // Not an LED: the draw loop skipped it, so touching where
                        // it WOULD have been must paint nothing.
                        assertNull("masked cell $cell at size $size", hit)
                    }
                }
            }
            assertEquals("drawn cells at $size", leds.getValue(size), drawn)
        }
    }

    /**
     * **How many cells there are, spelled out.**
     *
     * These two numbers are the panel, not a preference, and they are the whole
     * reason the mask was rewritten: the 13x13 was photographed with every cell
     * lit and counted row by row — 5, 9, 11, 11, 13, 13, 13, 13, 13, 11, 11, 9, 5
     * = 137 — and 489 is the LED count Nothing publishes for the Phone (3). The
     * rule `dx² + dy² <= (size / 2)²` reproduces both.
     *
     * Asserted here, and against the per-row counts as well as the total, so that
     * a future change to the mask cannot quietly alter the shape of the canvas:
     * a total alone would pass for a mask that kept the right NUMBER of cells in
     * the wrong PLACES, which is exactly the failure being fixed (the old 0.90
     * rule offered eight cells at 6.708 that the hardware does not have, while
     * refusing others it does).
     */
    @Test
    fun theMaskIsThePanelsOwnLedLayout() {
        assertEquals(137, PanelMask.count(13))
        assertEquals(489, PanelMask.count(25))
        assertEquals(
            listOf(5, 9, 11, 11, 13, 13, 13, 13, 13, 11, 11, 9, 5),
            (0 until 13).map { row -> (0 until 13).count { PanelMask.contains(it, row, 13) } },
        )
        // ...and the illustration agrees with it, cell for cell, at both sizes.
        for (size in intArrayOf(13, 25)) {
            for (row in 0 until size) {
                for (column in 0 until size) {
                    assertEquals(
                        "cell ($column, $row) at $size",
                        isDrawn(IntOffset(column, row), disc, radius, size),
                        PanelMask.contains(column, row, size),
                    )
                }
            }
        }
    }

    /**
     * The corner rings at 13x13, named, in and out — the boundary the user
     * actually traces when they draw an outline round the edge of the matrix.
     *
     * The mask keeps cells out to 6.5 from the centre cell (6, 6), so:
     *
     * | offset | distance | on the panel |
     * |---|---|---|
     * | (±5,±4) / (±4,±5) | 6.403 | yes |
     * | (±6,±3) / (±3,±6) | 6.708 | **no** |
     * | (±5,±5) | 7.071 | no |
     * | (±6,±4) / (±4,±6) | 7.211 | no |
     *
     * The 6.708 ring is the one that matters. The old "0.90 of the radius" rule
     * kept it (it reached 6.96) and the hardware does not have it: eight cells
     * that could be painted and could never light. It is gone, and the ring just
     * inside it is present, which is what makes the shape the editor shows the
     * shape the panel is.
     */
    @Test
    fun theCornerRingsOfThirteenAreInOrOutByMeasurement() {
        val off = listOf(
            // 6.708 — the eight phantom cells the old rule offered.
            IntOffset(0, 3), IntOffset(0, 9), IntOffset(12, 3), IntOffset(12, 9),
            IntOffset(3, 0), IntOffset(9, 0), IntOffset(3, 12), IntOffset(9, 12),
            // 7.071, and 7.211 — the ring the user first reported as missing,
            // correctly absent because it is not an LED.
            IntOffset(1, 1), IntOffset(11, 1), IntOffset(1, 11), IntOffset(11, 11),
            IntOffset(0, 2), IntOffset(2, 0), IntOffset(12, 10), IntOffset(10, 12),
            // ...and the square's own corners, at 8.485.
            IntOffset(0, 0), IntOffset(12, 0), IntOffset(0, 12), IntOffset(12, 12),
        )
        for (cell in off) {
            assertNull("$cell", matrixCellAt(centerOf(cell, disc, radius, 13), disc, radius, 13))
        }
        // 6.403 — the outermost ring that IS on the panel, at all four diagonals,
        // plus the row/column extremes at 6.0.
        val on = listOf(
            IntOffset(1, 2), IntOffset(2, 1), IntOffset(11, 2), IntOffset(10, 1),
            IntOffset(1, 10), IntOffset(2, 11), IntOffset(11, 10), IntOffset(10, 11),
            IntOffset(0, 6), IntOffset(12, 6), IntOffset(6, 0), IntOffset(6, 12),
        )
        for (cell in on) {
            assertEquals(cell, matrixCellAt(centerOf(cell, disc, radius, 13), disc, radius, 13))
        }
    }

    /**
     * A touch anywhere inside a cell's own square lands on that cell, and one
     * just past the halfway line lands on the neighbour. This is the property
     * that makes painting feel accurate between the centres.
     */
    @Test
    fun touchesResolveToTheNearestCell() {
        val size = 13
        val pitch = matrixCellPitch(radius, size)
        val cell = IntOffset(6, 6)
        val c = centerOf(cell, disc, radius, size)
        for (dx in listOf(-0.49f, -0.25f, 0f, 0.25f, 0.49f)) {
            for (dy in listOf(-0.49f, -0.25f, 0f, 0.25f, 0.49f)) {
                assertEquals(
                    cell,
                    matrixCellAt(Offset(c.x + dx * pitch, c.y + dy * pitch), disc, radius, size),
                )
            }
        }
        assertEquals(
            IntOffset(7, 6),
            matrixCellAt(Offset(c.x + 0.51f * pitch, c.y), disc, radius, size),
        )
        assertEquals(
            IntOffset(6, 7),
            matrixCellAt(Offset(c.x, c.y + 0.51f * pitch), disc, radius, size),
        )
    }

    /** Row-major, `y * size + x` — the same order the panel and the format use. */
    @Test
    fun columnIsXAndRowIsY() {
        val size = 13
        val pitch = matrixCellPitch(radius, size)
        val centre = matrixCellAt(disc, disc, radius, size)
        assertEquals(IntOffset(6, 6), centre)
        // One pitch to the RIGHT is one greater in x.
        assertEquals(
            IntOffset(7, 6),
            matrixCellAt(Offset(disc.x + pitch, disc.y), disc, radius, size),
        )
        // One pitch DOWN is one greater in y.
        assertEquals(
            IntOffset(6, 7),
            matrixCellAt(Offset(disc.x, disc.y + pitch), disc, radius, size),
        )
    }

    /** Off the disc entirely, and degenerate geometry, are null rather than clamped. */
    @Test
    fun touchesOutsideTheGridAreRejected() {
        val size = 13
        assertNull(matrixCellAt(Offset(disc.x + radius * 4f, disc.y), disc, radius, size))
        assertNull(matrixCellAt(Offset(-500f, -500f), disc, radius, size))
        assertNull(matrixCellAt(disc, disc, 0f, size))
        assertNull(matrixCellAt(disc, disc, radius, 0))
        // The very centre is always a cell, at every size we ship.
        assertNotNull(matrixCellAt(disc, disc, radius, 25))
    }

    /**
     * The forward mapping — the one the guided demo puts a ghost fingertip on —
     * is the draw loop's own placement and nothing else.
     *
     * [matrixCellCenter] exists so the tour can point at the cell it is about to
     * paint, and if it drifted from the draw loop the demo would appear to paint
     * one pixel while lighting another, which is a worse lie than not
     * demonstrating it at all. Checked against [centerOf], which is the draw
     * loop transcribed, at both geometries and under the zoom — and then round
     * tripped back through [matrixCellAt], so the two directions are pinned to
     * each other as well as to the transcription.
     */
    @Test
    fun theForwardMappingIsTheDrawLoopsOwnPlacement() {
        val views = listOf(
            MatrixDisc(disc, radius),
            MatrixDisc(disc, radius).transformedBy(2.5f, Offset(-703f, -47.5f)),
            MatrixDisc(disc, radius).transformedBy(4f, Offset(-1400f, -1900f)),
        )
        for (size in intArrayOf(13, 25)) {
            for (view in views) {
                for (row in 0 until size) {
                    for (column in 0 until size) {
                        val cell = IntOffset(column, row)
                        val expected = centerOf(cell, view.center, view.radius, size)
                        val actual = matrixCellCenter(view.center, view.radius, size, column, row)
                        assertEquals("$cell.x at $size", expected.x, actual.x, 1e-3f)
                        assertEquals("$cell.y at $size", expected.y, actual.y, 1e-3f)
                        if (PanelMask.contains(column, row, size)) {
                            assertEquals(
                                "$cell at $size",
                                cell,
                                matrixCellAt(actual, view.center, view.radius, size),
                            )
                        }
                    }
                }
            }
        }
    }

    // ---------- under the editor's zoom and pan ----------

    /** Every cell the draw loop would actually draw, for a given disc. */
    private fun drawnCells(view: MatrixDisc, size: Int): Set<IntOffset> = buildSet {
        for (row in 0 until size) {
            for (column in 0 until size) {
                val cell = IntOffset(column, row)
                if (isDrawn(cell, view.center, view.radius, size)) add(cell)
            }
        }
    }

    /**
     * The Phase 5 round trip again, this time through the editor's pinch-zoom.
     *
     * 25x25 is only drawable *because* of that zoom, so the inverse now has to
     * hold at four times the scale and at every pan offset the clamp allows — and
     * an off-by-one under zoom would be worse than one at rest, because it would
     * appear only after the user had deliberately zoomed in to be precise.
     *
     * Two things are asserted, and the second is the one that is easy to forget:
     * every drawn cell still maps back to itself, **and the set of cells the
     * circular cull keeps is identical to the set it keeps at rest**. A zoom that
     * quietly admitted or dropped a rim cell would mean the panel you can paint
     * changes shape depending on how far in you are looking.
     */
    @Test
    fun everyCellRoundTripsUnderZoomAndPan() {
        val rest = MatrixDisc(disc, radius)
        // Scales across the whole 1x..4x range, each with a pan: centred zooms,
        // hard-against-a-corner zooms, and offsets big enough to put the disc's
        // coordinates in the thousands, where float rounding would show up.
        val views = listOf(
            1f to Offset.Zero,
            1.37f to Offset(-121.5f, -263.25f),
            2f to Offset(-311.5f, -402.25f),
            2.5f to Offset(-703f, -47.5f),
            3.1416f to Offset(-1010.9f, -1600.4f),
            4f to Offset.Zero,
            4f to Offset(-1400f, -1900f),
        )
        for (size in intArrayOf(13, 25)) {
            val expected = drawnCells(rest, size)
            for ((scale, offset) in views) {
                val view = rest.transformedBy(scale, offset)
                // Guards the whole loop against being vacuous: if the transform
                // ever became a no-op, every assertion below would still pass.
                assertEquals(
                    "the transform did nothing at ${scale}x",
                    scale * matrixCellPitch(rest.radius, size),
                    matrixCellPitch(view.radius, size),
                    1e-3f,
                )
                for (row in 0 until size) {
                    for (column in 0 until size) {
                        val cell = IntOffset(column, row)
                        val centre = centerOf(cell, view.center, view.radius, size)
                        val hit = matrixCellAt(centre, view.center, view.radius, size)
                        if (cell in expected) {
                            assertEquals("cell $cell at $size, ${scale}x $offset", cell, hit)
                        } else {
                            assertNull("culled cell $cell at $size, ${scale}x $offset", hit)
                        }
                    }
                }
                assertEquals(
                    "the cull changed shape at ${scale}x $offset",
                    expected,
                    drawnCells(view, size),
                )
            }
        }
    }

    /**
     * The between-the-centres property, at the zoom that exists to make 25x25
     * usable: a touch anywhere inside a cell's own square still lands on it, and
     * one past the halfway line still lands on the neighbour.
     *
     * This is what a zoom is FOR — at 1x a 25x25 cell is about 12 dp against a
     * fingertip patch of 8-10 mm, and at 4x it is 48 dp — so the mapping being
     * right in the middle of a cell is not enough; it has to be right out to the
     * edges of a target four times the size.
     */
    @Test
    fun touchesBetweenCentresStillResolveWhileZoomedIn() {
        val size = 25
        val view = MatrixDisc(disc, radius).transformedBy(4f, Offset(-900f, -1200f))
        val pitch = matrixCellPitch(view.radius, size)
        val cell = IntOffset(12, 12)
        val c = centerOf(cell, view.center, view.radius, size)
        for (dx in listOf(-0.49f, -0.25f, 0f, 0.25f, 0.49f)) {
            for (dy in listOf(-0.49f, -0.25f, 0f, 0.25f, 0.49f)) {
                assertEquals(
                    cell,
                    matrixCellAt(Offset(c.x + dx * pitch, c.y + dy * pitch), view.center, view.radius, size),
                )
            }
        }
        assertEquals(
            IntOffset(13, 12),
            matrixCellAt(Offset(c.x + 0.51f * pitch, c.y), view.center, view.radius, size),
        )
        assertEquals(
            IntOffset(12, 13),
            matrixCellAt(Offset(c.x, c.y + 0.51f * pitch), view.center, view.radius, size),
        )
    }

    /**
     * The pan clamp. A canvas a user cannot find their way back to is a canvas
     * they will assume the app lost, so the invariant is absolute: the magnified
     * content may never expose a gap at any edge, at any zoom, however hard it is
     * shoved.
     */
    @Test
    fun panningCanNeverPushTheDiscOffScreen() {
        val canvas = Size(1080f, 1600f)
        val middle = Offset(540f, 800f)
        val t = CanvasTransform()

        // At 1x there is nothing to pan: the content IS the canvas.
        t.onGesture(middle, Offset(400f, -900f), 1f, canvas)
        assertEquals(1f, t.scale, 0f)
        assertEquals(0f, t.offsetX, 0f)
        assertEquals(0f, t.offsetY, 0f)

        // A pinch far past the ceiling stops at the ceiling, and stops there
        // WITHOUT sliding sideways: the pivot arithmetic uses the zoom that was
        // applied, not the one that was asked for.
        t.onGesture(middle, Offset.Zero, 8f, canvas)
        assertEquals(MAX_CANVAS_SCALE, t.scale, 1e-4f)
        assertEquals(-1620f, t.offsetX, 1e-2f)
        assertEquals(-2400f, t.offsetY, 1e-2f)

        // Shoved as far as it will go in each direction, the edges of the
        // magnified canvas line up with the edges of the real one and go no
        // further.
        t.onGesture(Offset.Zero, Offset(9_000f, 9_000f), 1f, canvas)
        assertEquals(0f, t.offsetX, 1e-3f)
        assertEquals(0f, t.offsetY, 1e-3f)
        t.onGesture(Offset.Zero, Offset(-9_000f, -9_000f), 1f, canvas)
        assertEquals(-(MAX_CANVAS_SCALE - 1f) * canvas.width, t.offsetX, 1e-3f)
        assertEquals(-(MAX_CANVAS_SCALE - 1f) * canvas.height, t.offsetY, 1e-3f)

        // Pinching back out cannot go below 1x, and lands square on the canvas
        // rather than wherever the shove had left it.
        t.onGesture(middle, Offset.Zero, 0.01f, canvas)
        assertEquals(1f, t.scale, 0f)
        assertEquals(0f, t.offsetX, 1e-3f)
        assertEquals(0f, t.offsetY, 1e-3f)

        // And the reset control is the same place from anywhere.
        t.onGesture(middle, Offset(-300f, 120f), 3f, canvas)
        t.reset()
        assertEquals(1f, t.scale, 0f)
        assertEquals(0f, t.offsetX, 0f)
        assertEquals(0f, t.offsetY, 0f)
    }

    /**
     * A pinch keeps whatever is under the fingers under the fingers. Asserted
     * against the disc itself rather than against the offset arithmetic: the
     * cell beneath the centroid before the pinch must be the cell beneath it
     * after, which is the property the user actually experiences.
     */
    @Test
    fun pinchingKeepsTheCellUnderTheFingersInPlace() {
        val canvas = Size(1080f, 1600f)
        val size = 25
        val t = CanvasTransform()
        val base = MatrixDisc(disc, radius)
        // Somewhere off-centre on the panel, so a pivot mistake cannot cancel out.
        val centroid = centerOf(IntOffset(17, 8), base.center, base.radius, size)

        val before = matrixCellAt(centroid, base.center, base.radius, size)
        assertEquals(IntOffset(17, 8), before)
        for (step in listOf(1.5f, 1.5f, 1.2f)) {
            t.onGesture(centroid, Offset.Zero, step, canvas)
            val view = base.transformedBy(t.scale, t.offset)
            assertEquals(
                "the cell under the pinch moved at ${t.scale}x",
                before,
                matrixCellAt(centroid, view.center, view.radius, size),
            )
        }
    }

    /**
     * The grid's extent tracks the disc, so a differently-sized canvas maps the
     * same proportional touch to the same cell — the editor scales its canvas
     * with the window and must not shift the art when it does.
     */
    @Test
    fun mappingIsScaleInvariant() {
        val size = 25
        // All comfortably inside the mask, which at 25 keeps cells out to 12.5
        // from the centre cell (12, 12).
        val cells = listOf(IntOffset(12, 12), IntOffset(20, 7), IntOffset(4, 16), IntOffset(12, 0))
        for (r in listOf(50f, 268.75f, 900f)) {
            val d = Offset(r * 1.3f, r * 1.7f)
            for (cell in cells) {
                assertEquals("$cell at radius $r", cell, matrixCellAt(centerOf(cell, d, r, size), d, r, size))
            }
        }
    }

    // ---------- the illustration's crop ----------

    /**
     * Canvases the editor actually gets: a static design (no timeline, so a tall
     * canvas), a dynamic one (the timeline takes ~200 dp), a short window with a
     * large font scale, and a landscape one where the canvas is wider than it is
     * tall. All at the ~448 dp width this app runs at.
     */
    private val editorCanvases = listOf(
        Size(448f, 750f),
        Size(448f, 600f),
        Size(448f, 360f),
        Size(880f, 300f),
    )

    /**
     * The canvas the tutorial's illustration actually gets: the platform's 320 dp
     * dialog less the card's 2 x 20 dp of padding, by [ILLUSTRATION_HEIGHT] tall.
     *
     * Taken from the real constant rather than written down, and used by every
     * assertion below, because **the height is half of what these tests are
     * pinning**: the framing regression this suite exists to catch was a zoom and
     * an illustration height moving together, and a transcribed 225 would have
     * watched it happen.
     */
    private val tutorialCanvas = Size(280f, ILLUSTRATION_HEIGHT.value)

    /** Where a device y lands on the canvas, under a camera. */
    private fun Camera.y(v: Float, canvas: Size) = map(Offset(0f, v), canvas).y

    /** Where a device x lands on the canvas, under a camera. */
    private fun Camera.x(v: Float, canvas: Size) = map(Offset(v, 0f), canvas).x

    // ---------- the model is the device, not a framing ----------

    /**
     * **The relationships the model exists to state.** Numbers may be tuned; these
     * may not, because each one is the answer to a complaint made against a
     * previous version of this drawing.
     *
     * Asserted against [DeviceBack] rather than against any camera, because that is
     * the point of splitting the two: the device does not change when a canvas
     * does, and no framing anywhere may reach in and move a feature.
     */
    @Test
    fun theModelIsTheDeviceAndNotAFraming() {
        // **The plate is LONG, not square** — it spans almost the whole width of
        // the body and is a fifth again as wide as it is tall. Measured off the
        // device; asserted here because a wrong value for it (0.63 of the body,
        // aspect 1.10 — invented, never measured) survived four revisions and was
        // the sole cause of every framing complaint downstream of it.
        assertEquals(1.331f, DeviceBack.ISLAND_WIDTH / DeviceBack.ISLAND_HEIGHT, 1e-4f)
        assertTrue(
            "the plate is ${DeviceBack.ISLAND_WIDTH} of the body, not nearly full width",
            DeviceBack.ISLAND_WIDTH > 0.85f,
        )
        // The matrix's diameter is about two fifths of the plate's width: it is the
        // plate's largest feature but nowhere near spanning it.
        val diameter = DeviceBack.matrix.radius * 2f / DeviceBack.ISLAND_WIDTH
        assertTrue("matrix diameter is $diameter of the plate", diameter in 0.38f..0.46f)
        // It sits in the plate's right portion, clear of the plate's own edge...
        val rightRim = DeviceBack.matrix.center.x + DeviceBack.matrix.radius
        assertTrue("the matrix overhangs the plate", rightRim < DeviceBack.ISLAND_RIGHT)
        assertTrue(
            "the matrix is not in the plate's right portion",
            DeviceBack.matrix.center.x > DeviceBack.ISLAND_LEFT + DeviceBack.ISLAND_WIDTH * 0.6f,
        )
        // ...and clear of every other feature on it, which is what stops a
        // "tune the numbers" pass from quietly overlapping the hardware.
        for (shape in DeviceBack.plate) {
            if (shape !is DeviceShape.Dot || shape.tone == Tone.GLASS) continue
            val dx = shape.center.x - DeviceBack.matrix.center.x
            val dy = shape.center.y - DeviceBack.matrix.center.y
            assertTrue(
                "a lens at (${shape.center.x}, ${shape.center.y}) touches the matrix",
                hypot(dx, dy) > DeviceBack.matrix.radius + shape.radius,
            )
        }
        // Every feature is ON the plate.
        for (shape in DeviceBack.plate) {
            val (l, t, r, b) = when (shape) {
                is DeviceShape.Dot -> listOf(
                    shape.center.x - shape.radius, shape.center.y - shape.radius,
                    shape.center.x + shape.radius, shape.center.y + shape.radius,
                )
                is DeviceShape.Round -> listOf(shape.left, shape.top, shape.right, shape.bottom)
            }
            assertTrue("$shape off the plate", l >= DeviceBack.ISLAND_LEFT - 1e-4f)
            assertTrue("$shape off the plate", t >= DeviceBack.ISLAND_TOP - 1e-4f)
            assertTrue("$shape off the plate", r <= DeviceBack.ISLAND_RIGHT + 1e-4f)
            assertTrue("$shape off the plate", b <= DeviceBack.ISLAND_BOTTOM + 1e-4f)
        }
        // **The plate is at the TOP of the back and horizontally symmetric on it** —
        // equal margins left and right, not tucked into a corner. It is the matrix
        // that is off-centre, sitting in the plate's right third; that asymmetry is
        // the device's, and it is what the editor's framing falls out of.
        assertEquals("left margin", 0.045f, DeviceBack.ISLAND_LEFT, 1e-4f)
        assertEquals("right margin", 0.045f, 1f - DeviceBack.ISLAND_RIGHT, 1e-4f)
        assertEquals(
            "the plate is not horizontally centred on the back",
            0.5f,
            (DeviceBack.ISLAND_LEFT + DeviceBack.ISLAND_RIGHT) / 2f,
            1e-4f,
        )
        assertTrue(
            "the matrix is not well right of the body's centre",
            DeviceBack.matrix.center.x > 0.65f,
        )
        // **The main camera's left edge sits on the module's left edge.** The device
        // lines them up — both at 625 px in the press photograph and both at 309 px
        // in the product render — and it is the first thing a reader notices when it
        // is missing: a revision that drew a 0.138 barrel around the camera's 0.124
        // glass was reported as the camera being "skewed towards the left", from that
        // 0.014 alone. Nothing on the camera may be drawn wider than its glass.
        val cameraLeft = DeviceBack.plate.filterIsInstance<DeviceShape.Dot>()
            .filter { it.tone == Tone.LENS || it.tone == Tone.LENS_GLASS }
            .minOf { it.center.x - it.radius }
        val moduleLeft = DeviceBack.plate.filterIsInstance<DeviceShape.Round>()
            .first { it.tone == Tone.LENS }.left
        assertEquals("the camera and the module do not start at the same x", moduleLeft, cameraLeft, 2e-3f)

        // The module is an empty CAPSULE: nearly twice as wide as it is tall, and
        // with nothing drawn inside it. Both were asked for explicitly.
        val module = DeviceBack.plate.filterIsInstance<DeviceShape.Round>().first { it.tone == Tone.LENS }
        val moduleAspect = (module.right - module.left) / (module.bottom - module.top)
        assertTrue("the module is $moduleAspect wide-to-tall, not a capsule", moduleAspect > 1.8f)
        assertTrue(
            "something is drawn inside the two-camera module",
            DeviceBack.plate.filterIsInstance<DeviceShape.Dot>().none {
                it.center.x in module.left..module.right && it.center.y in module.top..module.bottom
            },
        )

        // The two sensor dots are STACKED, not diagonal: measured, they sit within
        // 0.01 plate widths of each other horizontally.
        val sensors = DeviceBack.plate.filterIsInstance<DeviceShape.Dot>()
            .filter { it.tone == Tone.LENS && it.radius < DeviceBack.ISLAND_WIDTH * 0.06f }
        assertEquals("expected exactly two sensor dots", 2, sensors.size)
        assertTrue(
            "the sensor dots are set diagonally, not stacked",
            abs(sensors[0].center.x - sensors[1].center.x) < DeviceBack.ISLAND_WIDTH * 0.02f,
        )
        // The lens cluster is an object of comparable weight to the matrix, not
        // two specks at the edge: 0.35 x 0.66 of the plate against 0.48 x 0.48.
        val cluster = DeviceBack.plate.filterIsInstance<DeviceShape.Round>()
            .first { it.tone == Tone.LENS }
        val clusterWidth = (cluster.right - cluster.left) / DeviceBack.ISLAND_WIDTH
        assertTrue("the two-lens module is $clusterWidth wide", clusterWidth > 0.30f)
    }

    /** `[l, t, r, b]` destructuring for the bounds check above. */
    private operator fun <T> List<T>.component4(): T = this[3]

    // ---------- the cameras ----------

    /**
     * **The editor's camera is the matrix's centre and a zoom, and nothing else.**
     *
     * This is the assertion that stops the bias creeping back. Three separate
     * fudges have lived on this screen — a `DISC_LEFT_BIAS` that shoved the disc
     * left so the plate's right edge would fit, a `DISC_BOTTOM_GAP` that anchored
     * it low so the plate's bottom edge would, and a `discIsCentred` that picked
     * between them by design kind — and every one of them was added to make one
     * canvas look acceptable and silently re-framed the other caller.
     *
     * So the property is stated directly: the focus IS the matrix's centre, and
     * the matrix's centre therefore lands exactly at the centre of the canvas, on
     * every canvas, in every layout. A bias of any kind fails this by construction.
     */
    @Test
    fun theEditorCameraIsCentredOnTheMatrixAndCarriesNoBias() {
        for (canvas in editorCanvases) {
            val camera = editorCamera(canvas)
            assertEquals("focus x on $canvas", DeviceBack.matrix.center.x, camera.focus.x, 0f)
            assertEquals("focus y on $canvas", DeviceBack.matrix.center.y, camera.focus.y, 0f)
            val disc = camera.matrixDisc(canvas)
            assertEquals("disc x on $canvas", canvas.width / 2f, disc.center.x, 1e-3f)
            assertEquals("disc y on $canvas", canvas.height / 2f, disc.center.y, 1e-3f)
            // ...and the zoom is the one thing that sets the panel's size, taken
            // from the canvas and the fill fraction alone.
            assertEquals(
                "radius on $canvas",
                minOf(canvas.width, canvas.height) * 0.35f,
                disc.radius,
                1e-3f,
            )
        }
    }

    /**
     * A pinch composed onto a camera is *another camera* — the same claim
     * [MatrixDisc.transformedBy] makes about a disc, one level up.
     *
     * It matters because the editor now folds the user's zoom into the camera
     * before drawing, so if the two disagreed the phone would slide out from under
     * the panel as soon as anybody pinched. Checked against the disc transform,
     * which is the one the hit test has always used.
     */
    @Test
    fun aPinchedCameraIsStillJustACamera() {
        val canvas = Size(448f, 600f)
        val base = editorCamera(canvas)
        val moves = listOf(
            1f to Offset.Zero,
            1.37f to Offset(-121.5f, -263.25f),
            2.5f to Offset(-703f, -47.5f),
            4f to Offset(-1400f, -1900f),
        )
        for ((scale, offset) in moves) {
            val viaDisc = base.matrixDisc(canvas).transformedBy(scale, offset)
            val viaCamera = base.transformedBy(scale, offset, canvas).matrixDisc(canvas)
            assertEquals("x at ${scale}x $offset", viaDisc.center.x, viaCamera.center.x, 1e-2f)
            assertEquals("y at ${scale}x $offset", viaDisc.center.y, viaCamera.center.y, 1e-2f)
            assertEquals("r at ${scale}x $offset", viaDisc.radius, viaCamera.radius, 1e-3f)
        }
    }

    // ---------- what the cameras actually show ----------

    /**
     * The phone **body** must run off the bottom of the canvas under either
     * camera — and it is the only part of the drawing that must.
     *
     * A closed shape with visible bottom corners reads as a small object floating
     * in a box, which is what a caller reported the first time the editor's
     * framing moved: everything below the matrix shifted up and rounded corners
     * arrived on screen. That is true of the BODY, whose corners would give the
     * whole picture away; it is not true of the camera plate, whose bottom edge is
     * a real feature of the hardware and is framed on purpose wherever there is
     * room for it.
     *
     * Checked against the body's DRAWN extent — `maxOf(BODY_LENGTH, cropBelow)`,
     * transcribed from [drawDeviceBack] — rather than against [cropBelow] alone,
     * and then against [cropBelow] alone as well, so that simplifying either away
     * later cannot silently uncrop the body on a layout nobody tested.
     */
    @Test
    fun thePhoneBodyStaysCroppedWhereverTheDiscIsPut() {
        val cameras = editorCanvases.map { it to editorCamera(it) } +
            listOf(tutorialCanvas, Size(540f, tutorialCanvas.height))
                .map { it to tutorialCamera(it) }
        for ((canvas, camera) in cameras) {
            val corner = DeviceBack.BODY_CORNER * camera.zoom
            val drawn = camera.y(maxOf(DeviceBack.BODY_LENGTH, cropBelow(canvas, camera)), canvas)
            assertTrue(
                "the body ended at $drawn on $canvas",
                drawn >= canvas.height + corner - 0.5f,
            )
            assertTrue(
                "cropBelow on $canvas",
                camera.y(cropBelow(canvas, camera), canvas) >= canvas.height + corner - 0.5f,
            )
        }
    }

    /**
     * **What "the plate has to END, on screen" costs, stated as arithmetic rather
     * than engineered around.**
     *
     * The complaint this answers is old and correct: a plate that runs off every
     * frame reads as continuing forever, which is what the very first version of
     * this drawing was reported to be. Previous phases satisfied it by anchoring
     * the disc low — a framing fudge, now deleted — and by shaving the plate's
     * height until the arithmetic closed, which is the model being bent to suit a
     * camera. Neither is allowed any more, so what is asserted is the honest
     * relation and both of its outcomes.
     *
     * The matrix is not in the middle of the plate: it sits `1.30` radii below the
     * plate's top edge and `2.15` radii above its bottom one. A camera centred on
     * the matrix therefore shows the bottom edge **iff the canvas is at least
     * `2 x 2.15 = 4.31` radii tall**, and the radius is fixed at `0.35` of the
     * canvas's shorter side by the 20 dp cell-pitch floor. On the ~448 dp canvas
     * this app runs on that is 723 dp of height.
     *
     * - The **static** editor (no timeline, ~750 dp of canvas) clears it, with
     *   37 dp of phone body under the plate.
     * - The **dynamic** editor (~600 dp) does not: the edge lands 38 dp below the
     *   frame. Buying it back would mean a radius of 128 dp instead of 157 — a
     *   13x13 cell pitch of **16.6 dp against a floor of 20** — spent on a piece of
     *   trim, so the trade is declined.
     *
     * These numbers have moved twice. Measuring the plate's interior took its aspect
     * from `1.22` to `1.294`; re-measuring the plate's own outline — its flat face
     * rather than the outer edge of its bevel — then took it to `1.331`. Each time
     * every island coordinate was rescaled with it. The lesson is the one this file
     * keeps re-learning: a framing outcome is a consequence of the model, so it is
     * re-derived when the model is measured, never defended.
     *
     * Every number is asserted, so none can drift unremarked.
     */
    @Test
    fun thePlatesBottomEdgeIsInFrameOnTheTallEditorOnly() {
        val radii = { v: Float -> (v - DeviceBack.matrix.center.y) / DeviceBack.matrix.radius }
        assertEquals("plate top above the matrix", -1.304f, radii(DeviceBack.ISLAND_TOP), 0.01f)
        assertEquals("plate bottom below the matrix", 2.154f, radii(DeviceBack.ISLAND_BOTTOM), 0.01f)

        // The plate really does end below the pill it was placed against.
        val pill = DeviceBack.plate.filterIsInstance<DeviceShape.Round>().first { it.corner < 0.04f }
        assertTrue("the plate ended above the pill", DeviceBack.ISLAND_BOTTOM > pill.bottom)

        // The tall canvas: in frame, with a strip of body beneath it.
        val tall = Size(448f, 750f)
        val tallCamera = editorCamera(tall)
        val tallBottom = tallCamera.y(DeviceBack.ISLAND_BOTTOM, tall)
        assertEquals("the plate's bottom edge on $tall", 712.7f, tallBottom, 1f)
        assertTrue("the plate's bottom edge was off frame at $tall ($tallBottom)", tallBottom < tall.height - 24f)
        assertTrue("no body strip under the plate at $tall", tall.height - tallBottom >= 24f)

        // The short canvas: it does not fit, and by how much is pinned here so
        // that a future change to the model or the zoom shows up as a number.
        val short = Size(448f, 600f)
        val shortCamera = editorCamera(short)
        assertEquals("the plate's bottom edge on $short", 637.7f, shortCamera.y(DeviceBack.ISLAND_BOTTOM, short), 1f)
        // The height that would be needed, and the pitch that buying it would cost.
        assertEquals(
            "the canvas height the bottom edge needs",
            723f,
            2f * (2.1538f * 0.35f * short.width + 24f),
            2f,
        )
        assertTrue(
            "the pitch that would buy it is above the floor, so it should have been bought",
            matrixCellPitch((short.height / 2f - 24f) / 2.1538f, 13) < 20f,
        )

        // The plate's TOP edge is in frame with body above it on both, which is
        // the half of the framing the vertical budget can afford.
        for ((canvas, camera) in listOf(tall to tallCamera, short to shortCamera)) {
            val top = camera.y(DeviceBack.ISLAND_TOP, canvas)
            assertTrue("the plate's top edge was off frame at $canvas ($top)", top > 24f)
            assertTrue(
                "no body strip above the plate at $canvas",
                top - maxOf(camera.y(0f, canvas), 0f) >= 24f,
            )
        }
    }

    /**
     * The 16 dp of breathing room between the canvas and the palette costs no cell
     * pitch on any window this app runs on.
     *
     * The radius is `ZOOM_TARGET * min(width, height)`, so height only enters the
     * arithmetic once the canvas is shorter than it is wide. On a phone in
     * portrait it is not, with or without a timeline — which is the whole reason
     * the gap was affordable, and the reason this is asserted rather than assumed.
     */
    @Test
    fun theCanvasPaletteGapCostsNoCellPitch() {
        val width = 448f
        for (height in listOf(750f, 600f, 500f)) {
            for (size in intArrayOf(13, 25)) {
                val before = matrixCellPitch(baseDisc(Size(width, height)).radius, size)
                val after = matrixCellPitch(baseDisc(Size(width, height - 16f)).radius, size)
                assertEquals("pitch at ${width}x$height, size $size", before, after, 1e-4f)
            }
        }
        // The numbers [MAX_CANVAS_SCALE]'s table states, still true after the gap.
        val disc = baseDisc(Size(width, 600f))
        assertEquals(20.3f, matrixCellPitch(disc.radius, 13), 0.1f)
        assertEquals(10.5f, matrixCellPitch(disc.radius, 25), 0.1f)
        // **The floor, spelled out.** 20 dp at 13x13 is the line the editor's zoom
        // may not cross, against a fingertip contact patch of 8-10 mm. It is what
        // makes the framing arithmetic in [thePlatesBottomEdgeIsInFrameWithBodyBeneathIt]
        // come out the way it does, and it is not for sale.
        assertTrue(
            "the 13x13 pitch fell below the 20 dp floor",
            matrixCellPitch(disc.radius, 13) >= 20f,
        )
        // The pitch depends on the canvas and the fill fraction and on NOTHING in
        // the model — which is what lets the device be redrawn without touching a
        // dp of drawing precision.
        assertEquals(
            "the pitch moved with the model",
            matrixCellPitch(0.35f * width, 13),
            matrixCellPitch(disc.radius, 13),
            1e-4f,
        )
    }

    /**
     * **The first complaint, as an assertion**: the camera plate must not look
     * infinite.
     *
     * It was once a shallow bar inset a hair from both sides of the body, which at
     * the editor's zoom put its right edge either off-frame or a sliver from the
     * canvas border — so the drawing arrived as one endless grey field with a hole
     * in it. The fix is in the model (the plate spans the LEFT two thirds of the
     * back, leaving 0.31 of body beyond it) and the check is of what the camera
     * makes of that: the plate's **top-right corner** on screen with **phone body
     * visible beyond its right edge**.
     *
     * Both halves matter. The corner alone could be produced by a plate that runs
     * off the top of the frame; body beyond it is what says the plate ENDS. And
     * the strip is asserted at 24 dp rather than at "more than zero", because a
     * two-pixel line between the plate and the canvas edge is exactly what the old
     * drawing had and exactly what was reported as wrong.
     */
    @Test
    fun theIslandsTopRightCornerIsInFrameWithBodyBeyondIt() {
        for (canvas in editorCanvases.filter { it.height >= it.width }) {
            val camera = editorCamera(canvas)
            val right = camera.x(DeviceBack.ISLAND_RIGHT, canvas)
            val top = camera.y(DeviceBack.ISLAND_TOP, canvas)
            // The plate's right edge lands at 0.930 of the canvas — in frame with
            // 31 px of body beyond it on the 448 dp canvas this app runs on. That
            // margin is what makes the plate read as a bounded object, and it is
            // narrow because the matrix sits 0.733 of the way across the plate, so a
            // camera centred on the matrix has little plate left to show.
            assertTrue("the plate's right edge was off frame at $canvas ($right)", right < canvas.width - 8f)
            assertTrue("the plate's top edge was off frame at $canvas ($top)", top > 0f)
            assertEquals("the plate's right edge, as a fraction of the canvas", 0.930f, right / canvas.width, 2e-3f)
            // ...and the body really does continue past that edge, rather than the
            // plate simply being the last thing drawn.
            val bodyRight = camera.x(1f, canvas)
            assertTrue("the body ended before the plate did at $canvas", bodyRight > right)
            // The disc is still the dominant element and still clears the canvas.
            val disc = camera.matrixDisc(canvas)
            assertTrue(
                "the disc's left rim was clipped at $canvas",
                disc.center.x - disc.radius > 16f,
            )
        }
    }

    /**
     * **What the editor's camera reaches on either side** — asymmetric, because the
     * hardware is.
     *
     * The matrix sits `0.733` of the way across the plate, which puts the device's
     * right edge `0.288` body widths from the matrix's centre — 452 px out on a
     * 448 px canvas, so **just off frame**. What the editor shows on that side is
     * the plate's own right edge at 417 px and a strip of body beyond it, which is
     * what makes the plate read as a bounded object on a phone that continues.
     *
     * This value has now been asserted three ways, and the history is the point.
     * A revision that modelled the plate at `0.63` of the body's width put the edge
     * 728 px out and declared it unreachable at any usable zoom. Correcting the
     * plate to `0.91` brought it to 435 px — in frame — and that was asserted as a
     * property. Measuring the plate's *interior* then moved the matrix from `0.771`
     * to `0.733`, and the edge went back out. Each assertion was a true
     * statement about the model of the day; none was a fact about the device. Where
     * the frame falls is a CONSEQUENCE, so it gets re-derived whenever the model
     * changes and is never carried forward as a constraint the model must satisfy.
     *
     * The lens cluster, on the other side, goes off the left edge — wholly, in the
     * main camera's case. That is correct: it is where the hardware has it.
     */
    @Test
    fun theBodysRightEdgeIsJustOffFrameAndTheLensClusterIsOffLeft() {
        val canvas = Size(448f, 600f)
        val camera = editorCamera(canvas)
        val bodyRight = camera.x(1f, canvas)
        val plateRight = camera.x(DeviceBack.ISLAND_RIGHT, canvas)
        assertEquals("the body's right edge", 452f, bodyRight, 2f)
        assertTrue("the body's right edge came into frame ($bodyRight)", bodyRight > canvas.width)
        assertEquals("the plate's right edge", 417f, plateRight, 2f)
        assertTrue("the plate's right edge left the frame ($plateRight)", plateRight < canvas.width)
        // The main camera lens is wholly off the left of the frame.
        val big = DeviceBack.plate.filterIsInstance<DeviceShape.Dot>().first()
        assertTrue(
            "the main lens is in frame",
            camera.map(big.center, canvas).x + big.radius * camera.zoom < 0f,
        )
    }

    /**
     * The recording indicator — the one hue in the app, and the detail a caller
     * asked for by name — is inside the frame on a canvas of either layout.
     *
     * Read off the model rather than transcribed from the draw code, which is what
     * [DeviceShape] being data buys: the dot's placement can be tuned without this
     * test quietly measuring the old one.
     */
    @Test
    fun theRecordingDotStaysInFrameInBothLayouts() {
        val dot = DeviceBack.plate.filterIsInstance<DeviceShape.Round>()
            .first { it.tone == Tone.RECORDING }
        for (canvas in listOf(Size(448f, 750f), Size(448f, 600f))) {
            val camera = editorCamera(canvas)
            assertTrue("dot right at $canvas", camera.x(dot.right, canvas) < canvas.width)
            assertTrue("dot bottom at $canvas", camera.y(dot.bottom, canvas) < canvas.height)
            assertTrue("dot left at $canvas", camera.x(dot.left, canvas) > 0f)
            assertTrue("dot top at $canvas", camera.y(dot.top, canvas) > 0f)
        }
    }

    // ---------- the tutorial's camera ----------

    /**
     * **The tutorial draws the phone body at 0.62 of the canvas's width and 0.89 as
     * wide as the body is visibly tall** — both measured off the illustration as it
     * was accepted, and both restored here after a revision changed them.
     *
     * A previous revision asserted `0.67` and stated that `0.89` "reads as a card,
     * not a phone", on the theory that the aspect was what four rejected attempts
     * had in common. **That was wrong, and it is worth keeping why.** The accepted
     * illustration measures `342 / 385 = 0.888`; the rejected ones measured the same.
     * The aspect was never the defect — the camera plate was modelled at `0.63` of
     * the body's width instead of `0.91`, and a plate that shape makes the drawing
     * look like a card at *any* aspect. Acting on the wrong diagnosis then grew
     * [ILLUSTRATION_HEIGHT] from 205 to 270 dp to force the ratio down, which bought
     * nothing but blank body under the key and was rejected on sight.
     *
     * The lesson kept here: a ratio that several rejected revisions share is not
     * thereby their cause. Fix the model, then re-measure — do not pin a number that
     * the artefact you are trying to reproduce does not itself have.
     *
     * The aspect uses the body's VISIBLE height — canvas height less the top
     * margin — because the body's real lower edge is off-frame by construction
     * (see [thePhoneBodyStaysCroppedWhereverTheDiscIsPut]), so what a reader sees
     * is bounded by the illustration, not by the device.
     */
    @Test
    fun theTutorialDrawsTheBodyAtSixTenthsOfTheCanvasWidth() {
        val camera = tutorialCamera(tutorialCanvas)
        val left = camera.x(0f, tutorialCanvas)
        val right = camera.x(1f, tutorialCanvas)
        val width = right - left
        assertEquals("the body's width, as a fraction of the canvas's", 0.62f, TUTORIAL_BODY_WIDTH, 1e-4f)
        assertEquals(
            "the body is ${width / tutorialCanvas.width} of the canvas wide",
            TUTORIAL_BODY_WIDTH, width / tutorialCanvas.width, 1e-3f,
        )
        // The top margin is a fact about the DEVICE — a fraction of a body width,
        // so the sliver of background above the phone is the same sliver whatever
        // the illustration's height — and not a fraction of the canvas, which is
        // what it used to be asserted as and would have moved with this phase.
        val top = camera.y(0f, tutorialCanvas)
        assertEquals("the body's top edge is ${top / width} of a body width down", 0.059f, top / width, 5e-3f)
        val visible = tutorialCanvas.height - top
        val aspect = width / visible
        assertEquals("the visible body is $aspect as wide as it is tall", 0.89f, aspect, 0.02f)
        // The illustration shows about 1.12 body widths of phone: the plate, the
        // key, and a short strip of body under it. Growing this is what made the
        // phone read as a slab; it is bounded here so it cannot creep back.
        val visibleBodyWidths = visible / width
        assertTrue(
            "the illustration shows $visibleBodyWidths body widths, more than the plate and key need",
            visibleBodyWidths in 1.05f..1.20f,
        )
    }

    /**
     * **Nothing the illustration draws falls outside the phone**, and specifically
     * not the press markers.
     *
     * The dots counting a gesture's presses used to hang 18 dp past the body's
     * right edge, out in the gutter — see `TUTORIAL_MARKER_X`. In every rejected
     * screenshot that showed up as a small dot floating in the white space beside
     * the key, which reads as a rendering fault rather than as an annotation, and
     * it got worse rather than better when the island became accurate: a stray mark
     * in a gutter with nothing else in it is all there is to look at there.
     *
     * The ripple is the deliberate exception and is not checked here: it is a
     * circle centred on the key, so half of it is over the gutter by construction
     * and it reads as something emanating from the nub. A filled dot sitting still
     * in empty space does not.
     *
     * Checked at every dialog width, because the marker is placed as a device point
     * now and the point of that is that it cannot drift out from under the phone at
     * a canvas size nobody looked at.
     */
    @Test
    fun theTutorialsPressMarkersLandOnTheDevice() {
        // Radius and vertical spacing of a marker, transcribed from
        // `drawTutorialPhone`; the tutorial's canvas is in dp, so these are too.
        val markerRadius = 3f
        val markerSpacing = 12f
        for (width in listOf(240f, 280f, 320f, 400f, 540f)) {
            val canvas = Size(width, tutorialCanvas.height)
            val camera = tutorialCamera(canvas)
            val x = camera.x(TUTORIAL_MARKER_X, canvas)
            assertTrue(
                "a marker at $x is off the body's right edge at $width",
                x + markerRadius < camera.x(1f, canvas),
            )
            assertTrue(
                "a marker at $x touches the key's nub at $width",
                x + markerRadius < camera.x(DeviceBack.KEY_LEFT, canvas),
            )
            // The plate spans almost the whole body, so the marker is inevitably
            // within its horizontal span; what keeps the marker off the hardware is
            // vertical, because it annotates the key and the key sits below the
            // plate. Checking x against the plate's right edge, as this did while
            // the plate was modelled a third too narrow, asserted nothing about the
            // real device.
            assertTrue(
                "a marker at $width overlaps the camera plate",
                camera.y(DeviceBack.KEY_TOP, canvas) - markerRadius >
                    camera.y(DeviceBack.ISLAND_BOTTOM, canvas),
            )
            // A triple press stacks three of them about the key's middle; the whole
            // stack has to stay on the body strip below the plate and in frame.
            val keyMiddle = camera.y(DeviceBack.KEY_TOP + DeviceBack.KEY_HEIGHT / 2f, canvas)
            assertTrue(
                "the marker stack rides up onto the plate at $width",
                keyMiddle - markerSpacing - markerRadius > camera.y(DeviceBack.ISLAND_BOTTOM, canvas),
            )
            assertTrue(
                "the marker stack is cropped at $width",
                keyMiddle + markerSpacing + markerRadius < canvas.height,
            )
        }
    }

    /**
     * **The tutorial shows the whole phone, filling its illustration area and
     * cropped by it** — the framing it had before the editor's tuning started
     * reaching into it, and the regression this phase undoes.
     *
     * The failure mode being asserted against is specific: the phone rendering as
     * a small card floating in the middle of the dialog with margins all round it.
     * So the body has to take most of the width, start at the top, and run off the
     * bottom; and the Essential Key has to be attached to its right edge, below the
     * plate, with room beside it for the press annotations.
     */
    @Test
    fun theTutorialFramesTheWholePhoneAndCropsIt() {
        val canvas = tutorialCanvas
        val camera = tutorialCamera(canvas)
        val left = camera.x(0f, canvas)
        val right = camera.x(1f, canvas)
        // The body takes three fifths of the width...
        assertTrue("the body is only ${right - left} of $canvas wide", right - left >= canvas.width * 0.60f)
        // ...with the gutters holding the key's ripple, and the wider one on the
        // key's side. It is not small: the ripple expands to 36 dp around a key
        // that is ON the body's right edge, so half of it is over the gutter. The
        // press markers are NOT out there — see
        // [theTutorialsPressMarkersLandOnTheDevice].
        assertTrue("a left margin of $left", left in 36f..60f)
        assertTrue("no room for the key's ripple", canvas.width - right in 44f..72f)
        // It starts at the top and is cropped at the bottom.
        assertTrue("the body's top edge at ${camera.y(0f, canvas)}", camera.y(0f, canvas) in 4f..20f)
        // The whole plate is in frame, and so is the key below it.
        assertTrue("the plate is cropped", camera.y(DeviceBack.ISLAND_BOTTOM, canvas) < canvas.height)
        val keyBottom = camera.y(DeviceBack.KEY_TOP + DeviceBack.KEY_HEIGHT, canvas)
        assertTrue("the Essential Key is cropped ($keyBottom)", keyBottom < canvas.height - 8f)
        // The key really is on the body's right edge and below the plate.
        assertTrue(
            "the key is not on the body's edge",
            camera.x(DeviceBack.KEY_LEFT, canvas) < right &&
                camera.x(DeviceBack.KEY_LEFT + DeviceBack.KEY_WIDTH, canvas) > right,
        )
        assertTrue("the key is not below the plate", DeviceBack.KEY_TOP > DeviceBack.ISLAND_BOTTOM)
    }

    /**
     * Whatever width the platform gives the dialog, the illustration still shows
     * the phone from its top edge down past the Essential Key.
     *
     * The zoom is width-bound on a phone-sized dialog and height-bound on a wide
     * one; the focus's y is derived from the zoom precisely so that the body's top
     * edge lands in the same place either way, which is the property that keeps a
     * tablet from cropping the key off the bottom.
     */
    @Test
    fun theTutorialKeepsTheKeyInFrameAtEveryDialogWidth() {
        for (width in listOf(240f, 280f, 320f, 400f, 540f)) {
            val canvas = Size(width, tutorialCanvas.height)
            val camera = tutorialCamera(canvas)
            assertTrue(
                "the body's top edge moved at $width (${camera.y(0f, canvas)})",
                camera.y(0f, canvas) in 4f..20f,
            )
            assertTrue(
                "the key was cropped at $width",
                camera.y(DeviceBack.KEY_TOP + DeviceBack.KEY_HEIGHT, canvas) < canvas.height,
            )
            assertTrue("the body ran off the right at $width", camera.x(1f, canvas) < width)
        }
    }
}
