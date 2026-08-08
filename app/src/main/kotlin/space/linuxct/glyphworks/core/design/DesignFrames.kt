package space.linuxct.glyphworks.core.design

import kotlin.math.abs
import space.linuxct.glyphworks.matrix.MAX_BRIGHTNESS as PANEL_MAX_BRIGHTNESS

/**
 * Conversion between a frame's `cells` string and the `IntArray` the renderer
 * pushes to the matrix.
 *
 * **The encoding.** One character per cell, the character being the *palette
 * index* in base36 — `0`-`9` then `a`-`z`. Cells run row-major, so the
 * character at position `y * size + x` is the cell at (x, y): 169 characters for
 * bellsprout, 625 for arbok.
 *
 * Storing a palette index rather than the brightness itself is what makes the
 * format legible: a frame reads as `0001110000...` in a diff, and re-palettising
 * a whole design (say, dimming every grey) is a one-line change to `levels`
 * instead of a rewrite of every frame. Base36 keeps it to exactly one character
 * per cell for any palette a hand editor could plausibly offer, so `cells.length`
 * is a hard geometric check rather than a parse.
 *
 * Pure Kotlin and total: nothing here throws, and every function reports
 * malformed input by returning null so [DesignCodec] can attach a specific
 * reason.
 */
object DesignFrames {

    /**
     * Largest palette the one-character-per-cell encoding can address. Also the
     * cap [DesignCodec] enforces on `levels`, so the two can never disagree.
     */
    const val MAX_PALETTE = 36

    /**
     * Maximum per-cell brightness of the Glyph Matrix (12-bit, white only).
     *
     * An alias, not a second declaration: this is a hardware fact, and the
     * codec clamping to one number while the renderer draws to another would be
     * a silent mismatch nothing would catch. `matrix` is where the panel's
     * properties live (`core` already reaches into it from `BrightnessScale`),
     * so the constant is defined there and re-exported here under the name this
     * package's callers already use. Imported aliased, because inside this
     * object the member would otherwise shadow the import it is initialised
     * from.
     */
    const val MAX_BRIGHTNESS = PANEL_MAX_BRIGHTNESS

    /**
     * Decodes [cells] into a `size * size` brightness array, row-major
     * (`y * size + x`), values 0..4095.
     *
     * Returns null if [cells] is not exactly `size * size` characters, or if any
     * character is not a base36 digit that indexes into [levels]. The caller
     * must not paper over either case — a frame that is the wrong length is a
     * frame we cannot place, and padding or truncating it would silently corrupt
     * somebody's art rather than telling them their file is broken.
     *
     * Decoding accepts upper-case letters as well as lower-case (files get typed
     * by hand and pasted through tools that change case); [encode] only ever
     * writes lower-case.
     */
    fun decode(cells: String, levels: List<Int>, size: Int): IntArray? {
        if (size <= 0) return null
        val count = size * size
        if (cells.length != count) return null
        if (levels.isEmpty()) return null
        val out = IntArray(count)
        for (i in 0 until count) {
            val index = indexOfChar(cells[i])
            if (index < 0 || index >= levels.size) return null
            out[i] = levels[index].coerceIn(0, MAX_BRIGHTNESS)
        }
        return out
    }

    /**
     * Encodes a brightness array back into a `cells` string.
     *
     * Each value is written as the index of the *nearest* palette entry. Exact
     * matches are the normal case (the editor paints straight from the palette),
     * but making the mapping total rather than partial means a frame that has
     * been through brightness scaling, or a design being re-saved against a
     * shorter palette, still saves instead of failing — the worst case is a cell
     * snapping to the nearest available level, which is what the user sees on the
     * panel anyway.
     *
     * Returns null only if the geometry or the palette is unusable.
     */
    fun encode(frame: IntArray, levels: List<Int>, size: Int): String? {
        if (size <= 0) return null
        if (frame.size != size * size) return null
        if (levels.isEmpty() || levels.size > MAX_PALETTE) return null
        val sb = StringBuilder(frame.size)
        for (value in frame) {
            sb.append(charOfIndex(nearestLevel(value, levels)))
        }
        return sb.toString()
    }

    /** A blank (all palette index 0) frame for [codename]. */
    fun blank(codename: PokemonCodename): String = "0".repeat(codename.cellCount)

    /** Index of the palette entry closest to [value]; ties go to the lower index. */
    fun nearestLevel(value: Int, levels: List<Int>): Int {
        var best = 0
        var bestDistance = Int.MAX_VALUE
        for (i in levels.indices) {
            val d = abs(levels[i].coerceIn(0, MAX_BRIGHTNESS) - value)
            if (d < bestDistance) {
                bestDistance = d
                best = i
            }
        }
        return best
    }

    /**
     * ASCII-only base36 digit value, or -1.
     *
     * Deliberately *not* `Character.digit`, which also accepts non-ASCII decimal
     * digits (Arabic-Indic, Devanagari, ...). Two visually different files
     * decoding to the same frame is exactly the kind of ambiguity a format meant
     * for byte-level diffing should not have.
     */
    private fun indexOfChar(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'z' -> c - 'a' + 10
        in 'A'..'Z' -> c - 'A' + 10
        else -> -1
    }

    private fun charOfIndex(index: Int): Char =
        if (index < 10) ('0' + index) else ('a' + (index - 10))
}
