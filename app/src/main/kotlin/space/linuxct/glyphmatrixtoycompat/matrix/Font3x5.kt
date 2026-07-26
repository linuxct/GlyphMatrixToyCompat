package space.linuxct.glyphmatrixtoycompat.matrix

/**
 * Original 3x5 dot font (1-cell narrow variants for ':' and '.').
 * Designed for the 13x13 matrix: three digits fit side by side with 2-cell
 * gaps (columns 0-2 / 5-7 / 10-12), and a stacked HH/MM clock fits in
 * 5+1+5 rows.
 */
object Font3x5 {
    const val HEIGHT = 5
    private const val SPACING = 1

    private val glyphs: Map<Char, List<String>> = mapOf(
        '0' to listOf("###", "#.#", "#.#", "#.#", "###"),
        '1' to listOf(".#.", "##.", ".#.", ".#.", "###"),
        '2' to listOf("###", "..#", "###", "#..", "###"),
        '3' to listOf("###", "..#", ".##", "..#", "###"),
        '4' to listOf("#.#", "#.#", "###", "..#", "..#"),
        '5' to listOf("###", "#..", "###", "..#", "###"),
        '6' to listOf("###", "#..", "###", "#.#", "###"),
        '7' to listOf("###", "..#", "..#", ".#.", ".#."),
        '8' to listOf("###", "#.#", "###", "#.#", "###"),
        '9' to listOf("###", "#.#", "###", "..#", "###"),
        'A' to listOf(".#.", "#.#", "###", "#.#", "#.#"),
        'D' to listOf("##.", "#.#", "#.#", "#.#", "##."),
        'E' to listOf("###", "#..", "##.", "#..", "###"),
        'H' to listOf("#.#", "#.#", "###", "#.#", "#.#"),
        'K' to listOf("#.#", "##.", "#..", "##.", "#.#"),
        'M' to listOf("#.#", "###", "#.#", "#.#", "#.#"),
        'N' to listOf("#.#", "###", "###", "#.#", "#.#"),
        'P' to listOf("##.", "#.#", "##.", "#..", "#.."),
        'S' to listOf(".##", "#..", ".#.", "..#", "##."),
        'T' to listOf("###", ".#.", ".#.", ".#.", ".#."),
        'W' to listOf("#.#", "#.#", "#.#", "###", "#.#"),
        '%' to listOf("#.#", "..#", ".#.", "#..", "#.#"),
        '-' to listOf("...", "...", "###", "...", "..."),
        '+' to listOf("...", ".#.", "###", ".#.", "..."),
        '?' to listOf("###", "..#", ".##", "...", ".#."),
        ':' to listOf(".", "#", ".", "#", "."),
        '.' to listOf(".", ".", ".", ".", "#"),
        ' ' to listOf(".", ".", ".", ".", "."),
    )

    fun has(c: Char): Boolean = glyphs.containsKey(c.uppercaseChar())

    fun width(c: Char): Int = glyphs[c.uppercaseChar()]?.first()?.length ?: 3

    fun stringWidth(s: String): Int {
        if (s.isEmpty()) return 0
        var w = 0
        s.forEach { w += width(it) + SPACING }
        return w - SPACING
    }

    /** Draws one glyph; returns the x advance (glyph width + spacing). */
    fun draw(canvas: MatrixCanvas, c: Char, x: Int, y: Int, v: Int): Int {
        val rows = glyphs[c.uppercaseChar()] ?: glyphs.getValue('?')
        canvas.blit(rows, x, y, v)
        return rows.first().length + SPACING
    }

    fun drawString(canvas: MatrixCanvas, s: String, x: Int, y: Int, v: Int) {
        var cx = x
        s.forEach { cx += draw(canvas, it, cx, y, v) }
    }

    /** Draws [s] horizontally centered on the canvas at row [y]. */
    fun drawStringCentered(canvas: MatrixCanvas, s: String, y: Int, v: Int) {
        drawString(canvas, s, (canvas.size - stringWidth(s)) / 2, y, v)
    }
}
