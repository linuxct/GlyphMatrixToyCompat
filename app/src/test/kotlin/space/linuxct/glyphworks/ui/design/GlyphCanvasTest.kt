package space.linuxct.glyphworks.ui.design

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import space.linuxct.glyphworks.matrix.PanelMask
import space.linuxct.glyphworks.ui.ILLUSTRATION_HEIGHT
import space.linuxct.glyphworks.ui.tutorialCamera
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

    // ---------- what the cameras actually show ----------

    // ---------- the tutorial's camera ----------

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
}
