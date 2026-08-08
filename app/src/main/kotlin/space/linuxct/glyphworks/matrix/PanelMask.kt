package space.linuxct.glyphworks.matrix

/**
 * The round panel behind the square frame: which cells of a `size` x `size`
 * frame the hardware actually has an LED for.
 *
 * Every frame in this app is a full square — 169 cells at 13x13, 625 at 25x25 —
 * because that is what `MatrixCanvas` draws into and what the design format
 * stores. The panel itself is a **disc**, so the square's corner cells are not on
 * it. Two very different pieces of code have to agree, cell for cell, about which
 * those are:
 *
 * - the editor's illustration (`ui/design/GlyphCanvas.kt`), which must not offer
 *   a cell the hardware cannot light and must not refuse a touch on one it did
 *   draw — that equivalence is the whole reason `matrixCellAt` is trustworthy;
 * - art that is shaped like the **panel** rather than like the frame, which right
 *   now means `CustomScreen`'s placeholder border. Tracing the square's outline
 *   put its corners off the panel and the "frame" arrived as four detached arcs.
 *
 * `CustomScreen` cannot import the illustration (it is Android-free and
 * JVM-tested; `GlyphCanvas` is Compose), so the mask lives here beside
 * `MatrixCanvas` and the illustration reads it from here. One definition, two
 * callers, nothing to drift.
 */
object PanelMask {

    /**
     * Grid extent as a fraction of the disc DIAMETER — how much of the drawn
     * black disc the square grid of cell centres spans.
     *
     * Cell centres sit at `g0 + i * pitch`, so at 13 the outermost row/column is
     * 6 cells (0.775 * radius) out; with a half-pixel of 0.4 * pitch its far
     * corner reaches 0.85 * radius, which keeps every lit LED clear of the rim.
     * At the old 0.94 the corner pips landed at 1.01 * radius and visibly spilled
     * out of the black disc.
     *
     * It is worth knowing that this is also, exactly, where [contains] cuts: the
     * mask keeps cells out to `size / 2` cells, and `(size / 2) * pitch =
     * (size / 2) * (2 * GRID_EXTENT / size) * radius = GRID_EXTENT * radius`.
     * The size cancels, so the panel's rim lands at 0.84 * radius on the drawing
     * at every geometry — which is why the illustration needs no separate
     * cull fraction and no longer has one.
     */
    const val GRID_EXTENT = 0.84f

    /**
     * Is cell ([x], [y]) of a [size] x [size] frame on the panel?
     *
     * ## The rule is the grid's inscribed circle, and that is a measurement
     *
     * A cell is on the panel iff its centre is within **`size / 2` cells** of the
     * centre of the grid — `dx² + dy² <= (size / 2)²`, in cell units, with no
     * reference to how large anything is drawn.
     *
     * This is not an aesthetic choice about pixels touching a rim; it is the
     * hardware's LED layout, and it is checked, not inferred:
     *
     * - The 13x13 panel was **photographed with every cell lit** and counted row
     *   by row from the top: 5, 9, 11, 11, 13, 13, 13, 13, 13, 11, 11, 9, 5 =
     *   **137** LEDs. The rule above reproduces those thirteen row counts
     *   exactly.
     * - The same rule at 25x25 gives **489**, which is precisely the LED count
     *   Nothing publishes for the Phone (3) Glyph Matrix. Two independent
     *   geometries, two independent confirmations, one rule.
     *
     * ## What this replaced, and why the old rule was a defect
     *
     * The editor used to cull at "0.90 of the drawn disc radius", whose own KDoc
     * claimed it removed the four diagonal corner cells. It removed 24, and — far
     * worse — it removed the *wrong* 24: it kept the `(±6,±3)` / `(±3,±6)` ring
     * at 6.708 cells, which is outside 6.5 and **is not on the panel**. Eight
     * cells were paintable in the editor and physically incapable of lighting, so
     * anything drawn on them went into the void, while the boundary the editor
     * showed was a different shape from the boundary the panel has — which is why
     * an outline traced along the edge of the matrix came out looking broken.
     *
     * The cells the user first reported as missing, at `(±6,±4)` (7.211 cells),
     * are correctly absent for the same reason: they are not LEDs. What was wrong
     * was the *shape*, not the count.
     *
     * A future panel with a different layout changes this one function, and
     * `GlyphCanvasTest` asserts 137 and 489 explicitly so it cannot change
     * quietly.
     */
    fun contains(x: Int, y: Int, size: Int): Boolean {
        if (size <= 0 || x < 0 || y < 0 || x >= size || y >= size) return false
        val c = (size - 1) / 2f
        val dx = x - c
        val dy = y - c
        val r = size / 2f
        return dx * dx + dy * dy <= r * r
    }

    /**
     * A cell that is on the panel and has a 4-neighbour that is not — i.e. the
     * outline of the real panel, one cell thick and closed all the way round.
     * This is what "a border" means on a disc; see `CustomScreen`.
     */
    fun isEdge(x: Int, y: Int, size: Int): Boolean = contains(x, y, size) && (
        !contains(x - 1, y, size) || !contains(x + 1, y, size) ||
            !contains(x, y - 1, size) || !contains(x, y + 1, size)
        )

    /**
     * How many LEDs a [size] x [size] panel has: **137** of 169 at 13, **489** of
     * 625 at 25. Asserted rather than merely reported — see `GlyphCanvasTest`.
     */
    fun count(size: Int): Int {
        var n = 0
        for (y in 0 until size) {
            for (x in 0 until size) if (contains(x, y, size)) n++
        }
        return n
    }
}
