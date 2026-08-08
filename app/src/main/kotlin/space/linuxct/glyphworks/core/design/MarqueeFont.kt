package space.linuxct.glyphworks.core.design

import java.text.Normalizer

/**
 * The letterform set a marquee is built from: **nine rows tall**, proportional,
 * upper-case, covering every printable ASCII character.
 *
 * ## Why nine rows, measured rather than chosen
 *
 * The obvious answer is five. `PanelMask` says rows 4-8 are the only rows of a
 * 13x13 panel that have an LED in *every* column, so a five-row glyph can sit at
 * any horizontal offset and never lose a cell to the disc. That is a true fact
 * and it is the wrong fact to design a marquee around: it describes the band
 * that is never clipped **anywhere**, which is a different question from how
 * tall a letter may be to be *readable where it is read*.
 *
 * A scrolling letter is read at the middle of the panel, not at the edge. The
 * mask's live row-span per column, measured from `PanelMask.contains`:
 *
 * ```
 *   13x13   col  0: rows 4-8    (5)      25x25   col  0: rows  9-15   (7)
 *           col  1: rows 2-10   (9)              col  1: rows  7-17  (11)
 *           col  2: rows 1-11  (11)              col  2: rows  5-19  (15)
 *           col  3: rows 1-11  (11)              col  3: rows  4-20  (17)
 *           cols 4-8: rows 0-12 (13)             cols 9-15: rows 0-24 (25)
 * ```
 *
 * Nine rows placed at rows 2-10 is therefore the tallest band that is **whole in
 * eleven of the thirteen columns** — every column except the two extreme ones,
 * where the top and bottom of the letter are cut by the curve exactly as it
 * enters and leaves. Eleven rows would be whole in only nine columns, and five
 * rows wastes 60 % of the panel's height to protect a moment nobody reads. Nine
 * is the measurement; [HEIGHT] is that number and nothing else depends on it
 * being pretty.
 *
 * Clipping at the edges is not a defect here, it is the effect: it is what
 * Nothing's own full-height marquee does, and it is a large part of why that one
 * reads as *big*. [MarqueeText] drops the clipped cells at generation time so
 * nothing outside the mask is ever stored.
 *
 * ## Why condensed
 *
 * The panel is thirteen columns wide. A letter as wide as it is tall would put
 * 1.4 letters on screen at a time, which is Nothing's marquee and is, in the
 * user's words, "very readable but very slow to both show and read". Five
 * columns at nine rows is a 1.8:1 condensed face: a little over two letters
 * visible at once, which is where a short phrase stops being spelled out one
 * character at a time and starts being read. `M` and `W` get seven columns
 * because at five they stop being `M` and `W`; `I` gets three, with serifs, so
 * it cannot be confused with `|` or with the stem of `1`.
 *
 * ## Case, and where the lower-case sits
 *
 * The face draws **both cases**, and the two do not share a baseline. They
 * cannot: a capital already occupies all nine rows, so its baseline is the last
 * row there is and a descender has nowhere to go. Rather than shrink every
 * capital — which would make the common all-caps marquee smaller to pay for a
 * letter it does not contain — the lower-case is fitted *inside* the same nine
 * rows with its own metrics, stated here as constants rather than left implicit
 * in seventy-odd string literals:
 *
 * ```
 *   row 0  ---- ascender line ---- (b d f h k l, and the capitals' cap line)
 *   row 3  ---- x-height ---------
 *   row 7  ---- baseline --------- (every letter that does not descend)
 *   row 8  ---- descender line --- (g j p q y)
 * ```
 *
 * [LOWER_X_HEIGHT] is five of the capitals' nine — 0.56, which is where a
 * humanist face puts it — the ascenders reach eight of nine, and the descender
 * is one row deep. **Nothing is stored outside the nine rows**, so [MarqueeText]
 * places the band exactly as it did before and a descender is clipped by the
 * disc no more than a capital's bottom row already was.
 *
 * The cost is one row and it is stated rather than hidden: a capital's foot
 * hangs [CAP_OVERHANG] row below the lower-case baseline, because the capital
 * still owns row 8. One row is the smallest inconsistency that buys real
 * descenders, and it is spent on mixed case — `Hello` — while `HELLO` is
 * unchanged. The alternative, folding the lower-case onto the capitals, is what
 * this face used to do, and it made every phrase SHOUT.
 *
 * ## Characters that are not in the table
 *
 * Accented Latin letters are stripped to their base letter (`Á` -> `A`, `ñ` ->
 * `N`) rather than refused, because an accent is one row tall and there is no
 * row to put it on; a stripped accent is legible and a missing word is not.
 * Anything left after that which is not in the table — CJK, emoji, box drawing —
 * is genuinely not renderable here, and [glyph] returns null for it so
 * [MarqueeText] can name it rather than silently drawing something else.
 *
 * Pure Kotlin, like everything in this package: the table is data and the
 * layout is arithmetic, both exercised by plain JVM unit tests.
 */
object MarqueeFont {

    /** Rows in every glyph. See this object's KDoc for why it is nine. */
    const val HEIGHT: Int = 9

    /**
     * The row a lower-case letter that does not descend **sits on** — its last
     * lit row.
     *
     * Seven, not eight, and that is the one decision the whole lower-case set
     * follows from: row 8 has to be free for `g j p q y` to descend into, and a
     * descender that cannot descend is a small capital. See this object's KDoc.
     */
    const val LOWER_BASELINE: Int = 7

    /** The top row of a lower-case letter with no ascender — `a c e m n o`. */
    const val LOWER_X_HEIGHT_TOP: Int = 3

    /** Rows from [LOWER_X_HEIGHT_TOP] to [LOWER_BASELINE] inclusive: five of nine. */
    const val LOWER_X_HEIGHT: Int = LOWER_BASELINE - LOWER_X_HEIGHT_TOP + 1

    /**
     * How far below [LOWER_BASELINE] a descender reaches: one row, which is
     * every row there is. [HEIGHT] is not negotiable here — see the KDoc.
     */
    const val LOWER_DESCENDER: Int = HEIGHT - 1 - LOWER_BASELINE

    /**
     * How far a capital's foot hangs below [LOWER_BASELINE], in rows.
     *
     * Not a defect to be fixed later: it is the arithmetic of a nine-row band
     * whose capitals fill it. Named so that a reader of `Hello` on the panel can
     * find the sentence that explains what they are looking at.
     */
    const val CAP_OVERHANG: Int = HEIGHT - 1 - LOWER_BASELINE

    /**
     * Blank columns inserted between two adjacent glyphs.
     *
     * One, not two: at nine rows tall the letters are visually separated by
     * their own height, and a second blank column costs a frame per letter out
     * of a budget of [DesignCodec.MAX_FRAMES]. A word space is a three-column
     * glyph, so a gap between words comes out five columns wide — clearly a word
     * break and not a wide letter gap.
     */
    const val GAP: Int = 1

    /**
     * The widest glyph in the table, in columns.
     *
     * Derived, not typed, so that widening a letter cannot leave a stale bound
     * behind. Callers use it to reason about the worst case before laying text
     * out.
     */
    val MAX_WIDTH: Int get() = maxWidth

    /** Every character the table can draw, in code-point order. */
    val COVERAGE: List<Char> get() = coverage

    /**
     * The rows of [c]'s glyph, top row first, `#` for a lit cell and `.` for an
     * unlit one — or **null** if this face cannot draw it.
     *
     * Both cases are drawn. [c] is folded only where it has to be: an accented
     * Latin letter falls back to its base letter **in the case it was typed in**
     * (`é` -> `e`, `É` -> `E`), and a letter with no lower-case form of its own
     * falls back to the upper-case one. Null therefore means "there is no
     * letterform for this at all".
     */
    fun glyph(c: Char): List<String>? = GLYPHS[fold(c)]

    /**
     * [text] as this face will actually draw it — the folding of [glyph] applied
     * to every character.
     *
     * Exists so that a caller reporting "here is what I drew" reports what was
     * *drawn* rather than a second guess at the same rule. Characters with no
     * letterform are left as they are; ask [unsupported] about those.
     */
    fun drawnAs(text: String): String = buildString(text.length) {
        for (c in text) append(if (supports(c)) fold(c) else c)
    }

    /** True if [glyph] would return a letterform for [c]. */
    fun supports(c: Char): Boolean = glyph(c) != null

    /** How many columns [c] occupies, excluding [GAP], or 0 if it cannot be drawn. */
    fun width(c: Char): Int = glyph(c)?.first()?.length ?: 0

    /**
     * The characters of [text] this face cannot draw, in the order they appear
     * and without repeats.
     *
     * Returned rather than thrown, and returned *whole* rather than
     * first-offender-only: a caller reporting this to somebody who must fix the
     * text should be able to name every character at once instead of one per
     * attempt.
     */
    fun unsupported(text: String): List<Char> {
        val out = LinkedHashSet<Char>()
        for (c in text) if (!supports(c)) out.add(c)
        return out.toList()
    }

    /**
     * How wide [text] is once laid out, in columns — glyph widths plus one [GAP]
     * between each adjacent pair, and no gap at either end.
     *
     * Returns 0 for text this face cannot draw completely; check [unsupported]
     * first. A width that silently skipped the characters it could not draw
     * would produce a marquee missing letters and a frame count that matched it,
     * so nothing downstream could tell.
     */
    fun stripWidth(text: String): Int {
        if (text.isEmpty()) return 0
        var w = 0
        for (c in text) {
            val glyph = glyph(c) ?: return 0
            w += glyph.first().length + GAP
        }
        return w - GAP
    }

    /**
     * [text] laid out as one bitmap: **one entry per column**, each a bit mask
     * whose bit `r` is set when row `r` of that column is lit.
     *
     * A column-major bit mask rather than [HEIGHT] row strings, because the
     * scroll consumes it one column at a time and because a mask makes "is this
     * cell lit" a shift instead of a character comparison. The important
     * property is structural, though, and it is the same one `scroll_frames`
     * exists to guarantee: the whole message is laid out **once**, so every row
     * of a later frame is necessarily displaced by the same amount and a glyph
     * cannot shear.
     *
     * Empty if [text] is empty or contains anything [supports] rejects.
     */
    fun strip(text: String): IntArray {
        val width = stripWidth(text)
        if (width <= 0) return IntArray(0)
        val out = IntArray(width)
        var x = 0
        for (c in text) {
            val glyph = glyph(c) ?: return IntArray(0)
            for (column in 0 until glyph.first().length) {
                var mask = 0
                for (r in 0 until HEIGHT) if (glyph[r][column] == '#') mask = mask or (1 shl r)
                out[x++] = mask
            }
            x += GAP
        }
        return out
    }

    /**
     * [text] laid out as [HEIGHT] rows of `#` and `.` — the whole message as one
     * picture, before any frame exists.
     *
     * This is the single most useful thing to hand back to whoever asked for a
     * marquee: nine short lines that show every letterform at once, against
     * which "is that an 8 or a B" is answerable, where the same judgement made
     * from a stack of panel-width frames is not. Empty for text this face cannot
     * draw completely.
     */
    fun picture(text: String): List<String> {
        val strip = strip(text)
        if (strip.isEmpty()) return emptyList()
        return (0 until HEIGHT).map { r ->
            buildString(strip.size) {
                for (mask in strip) append(if (mask and (1 shl r) != 0) '#' else '.')
            }
        }
    }

    /**
     * The accent- and case-folding [glyph] applies before it looks anything up.
     *
     * **The character itself is tried first**, which is the whole of the
     * lower-case change: `e` is in the table now, so it is drawn as `e`.
     *
     * `Normalizer` decomposes `é` into `e` plus a combining acute, and dropping
     * every combining mark leaves the base letter — which generalises to every
     * accented Latin letter instead of listing the ones somebody remembered.
     * The decomposition is done on `c` as typed, so `é` comes back as `e` and
     * `É` as `E`; case is only forced as the last resort, for a letter this face
     * draws in one case and not the other.
     *
     * `java.text` is JDK, not Android, so it is fair game in `core/`.
     */
    private fun fold(c: Char): Char {
        if (c in GLYPHS) return c
        val decomposed = Normalizer.normalize(c.toString(), Normalizer.Form.NFD)
        val base = decomposed.firstOrNull { !it.isNonSpacingMark() } ?: c
        if (base in GLYPHS) return base
        return base.uppercaseChar()
    }

    private fun Char.isNonSpacingMark(): Boolean =
        Character.getType(this) == Character.NON_SPACING_MARK.toInt()

    /**
     * The table itself, `#` lit and `.` unlit, row 0 at the top.
     *
     * Written as pictures rather than as hex so that a letter can be *read* in
     * the source and fixed where it is wrong; `MarqueeFontTest` asserts every
     * entry is exactly [HEIGHT] rows of one consistent width and that only `#`
     * and `.` appear, so a typo is a test failure rather than a torn letter.
     */
    private val GLYPHS: Map<Char, List<String>> = buildMap {
        // ---- letters ----
        put('A', listOf(".###.", "#...#", "#...#", "#...#", "#####", "#...#", "#...#", "#...#", "#...#"))
        put('B', listOf("####.", "#...#", "#...#", "#...#", "####.", "#...#", "#...#", "#...#", "####."))
        put('C', listOf(".###.", "#...#", "#....", "#....", "#....", "#....", "#....", "#...#", ".###."))
        put('D', listOf("####.", "#...#", "#...#", "#...#", "#...#", "#...#", "#...#", "#...#", "####."))
        put('E', listOf("#####", "#....", "#....", "#....", "####.", "#....", "#....", "#....", "#####"))
        put('F', listOf("#####", "#....", "#....", "#....", "####.", "#....", "#....", "#....", "#...."))
        put('G', listOf(".###.", "#...#", "#....", "#....", "#.###", "#...#", "#...#", "#...#", ".###."))
        put('H', listOf("#...#", "#...#", "#...#", "#...#", "#####", "#...#", "#...#", "#...#", "#...#"))
        // Serifed, and three columns rather than one: a bare upright at this
        // height is indistinguishable from '|' and from the stem of '1'.
        put('I', listOf("###", ".#.", ".#.", ".#.", ".#.", ".#.", ".#.", ".#.", "###"))
        put('J', listOf("..###", "....#", "....#", "....#", "....#", "....#", "#...#", "#...#", ".###."))
        put('K', listOf("#...#", "#..#.", "#.#..", "##...", "#....", "##...", "#.#..", "#..#.", "#...#"))
        put('L', listOf("#....", "#....", "#....", "#....", "#....", "#....", "#....", "#....", "#####"))
        put('M', listOf("#.....#", "##...##", "#.#.#.#", "#..#..#", "#.....#", "#.....#", "#.....#", "#.....#", "#.....#"))
        put('N', listOf("#...#", "##..#", "##..#", "#.#.#", "#.#.#", "#.#.#", "#..##", "#..##", "#...#"))
        put('O', listOf(".###.", "#...#", "#...#", "#...#", "#...#", "#...#", "#...#", "#...#", ".###."))
        put('P', listOf("####.", "#...#", "#...#", "#...#", "####.", "#....", "#....", "#....", "#...."))
        put('Q', listOf(".###.", "#...#", "#...#", "#...#", "#...#", "#...#", "#...#", "#..#.", ".####"))
        put('R', listOf("####.", "#...#", "#...#", "#...#", "####.", "#.#..", "#..#.", "#...#", "#...#"))
        put('S', listOf(".###.", "#...#", "#....", "#....", ".###.", "....#", "....#", "#...#", ".###."))
        put('T', listOf("#####", "..#..", "..#..", "..#..", "..#..", "..#..", "..#..", "..#..", "..#.."))
        put('U', listOf("#...#", "#...#", "#...#", "#...#", "#...#", "#...#", "#...#", "#...#", ".###."))
        put('V', listOf("#...#", "#...#", "#...#", "#...#", "#...#", "#...#", ".#.#.", ".#.#.", "..#.."))
        // The vertical mirror of M, by construction rather than by eye.
        put('W', listOf("#.....#", "#.....#", "#.....#", "#.....#", "#.....#", "#..#..#", "#.#.#.#", "##...##", "#.....#"))
        put('X', listOf("#...#", "#...#", ".#.#.", ".#.#.", "..#..", ".#.#.", ".#.#.", "#...#", "#...#"))
        put('Y', listOf("#...#", "#...#", ".#.#.", ".#.#.", "..#..", "..#..", "..#..", "..#..", "..#.."))
        put('Z', listOf("#####", "....#", "...#.", "...#.", "..#..", ".#...", ".#...", "#....", "#####"))

        // ---- lower-case ----
        // Rows 0-2 are the ascender, rows 3-7 the x-height, row 8 the descender;
        // see [LOWER_BASELINE] and this object's KDoc for why the baseline is
        // row 7 rather than row 8. The stroke is one cell, exactly as the
        // capitals' is, so a lower-case word does not read lighter than the
        // capital beside it — the letters are shorter, not thinner.
        //
        // Widths follow the capitals' own rule: five columns, seven for 'm' and
        // 'w' because at five they stop being 'm' and 'w', three for 'i', 'j'
        // and 'l' because they are one stem and paying five columns for one is
        // what makes a proportional face look like a monospaced one.
        put('a', listOf(".....", ".....", ".....", ".###.", "....#", ".####", "#...#", ".####", "....."))
        put('b', listOf("#....", "#....", "#....", "####.", "#...#", "#...#", "#...#", "####.", "....."))
        put('c', listOf(".....", ".....", ".....", ".###.", "#...#", "#....", "#...#", ".###.", "....."))
        put('d', listOf("....#", "....#", "....#", ".####", "#...#", "#...#", "#...#", ".####", "....."))
        put('e', listOf(".....", ".....", ".....", ".###.", "#...#", "#####", "#....", ".###.", "....."))
        put('f', listOf("..###", ".##..", ".#...", "####.", ".#...", ".#...", ".#...", ".#...", "....."))
        // A closed bowl plus a tail, so it cannot be read as an 'o' — the tail
        // is the only row below the baseline it gets, and it sweeps left the way
        // 'j' and 'y' do, which is what makes the three read as one family.
        put('g', listOf(".....", ".....", ".....", ".###.", "#...#", "#...#", "#...#", ".####", "###.."))
        put('h', listOf("#....", "#....", "#....", "####.", "#...#", "#...#", "#...#", "#...#", "....."))
        // The dot sits on row 1 with row 2 clear: touching the stem it would be
        // an 'l' with a thick top, which at this size is what it would look like.
        put('i', listOf("...", ".#.", "...", ".#.", ".#.", ".#.", ".#.", ".#.", "..."))
        put('j', listOf("...", "..#", "...", "..#", "..#", "..#", "..#", "..#", "###"))
        put('k', listOf("#....", "#....", "#....", "#...#", "#..#.", "###..", "#..#.", "#...#", "....."))
        // A tail rather than a bare stem: 'l' and '1' and 'I' and '|' are four
        // uprights, and this is the one that has to be told apart from them
        // without a serif, because a serif here would read as a 't'.
        put('l', listOf(".#.", ".#.", ".#.", ".#.", ".#.", ".#.", ".#.", ".##", "..."))
        put('m', listOf(".......", ".......", ".......", ".##.##.", "#..#..#", "#..#..#", "#..#..#", "#..#..#", "......."))
        put('n', listOf(".....", ".....", ".....", "####.", "#...#", "#...#", "#...#", "#...#", "....."))
        put('o', listOf(".....", ".....", ".....", ".###.", "#...#", "#...#", "#...#", ".###.", "....."))
        put('p', listOf(".....", ".....", ".....", "####.", "#...#", "#...#", "#...#", "####.", "#...."))
        put('q', listOf(".....", ".....", ".....", ".####", "#...#", "#...#", "#...#", ".####", "....#"))
        put('r', listOf(".....", ".....", ".....", "####.", "#...#", "#....", "#....", "#....", "....."))
        put('s', listOf(".....", ".....", ".....", ".####", "#....", ".###.", "....#", "####.", "....."))
        // Shorter than an ascender and taller than the x-height, which is what a
        // 't' is; the foot turns right so it is not an 'l' with a crossbar.
        put('t', listOf(".....", ".#...", ".#...", "####.", ".#...", ".#...", ".#...", ".###.", "....."))
        put('u', listOf(".....", ".....", ".....", "#...#", "#...#", "#...#", "#...#", ".####", "....."))
        put('v', listOf(".....", ".....", ".....", "#...#", "#...#", "#...#", ".#.#.", "..#..", "....."))
        put('w', listOf(".......", ".......", ".......", "#.....#", "#.....#", "#..#..#", "#.#.#.#", ".#...#.", "......."))
        put('x', listOf(".....", ".....", ".....", "#...#", ".#.#.", "..#..", ".#.#.", "#...#", "....."))
        // A 'v' with a tail, not a 'u' with one: 'u' is the letter it would be
        // confused with if the join were flat, and the join is the difference.
        put('y', listOf(".....", ".....", ".....", "#...#", "#...#", "#...#", ".#.#.", "..#..", "###.."))
        put('z', listOf(".....", ".....", ".....", "#####", "...#.", "..#..", ".#...", "#####", "....."))

        // ---- digits ----
        // '0' carries the diagonal that separates it from 'O'; nothing else does.
        put('0', listOf(".###.", "#...#", "#..##", "#..##", "#.#.#", "##..#", "##..#", "#...#", ".###."))
        put('1', listOf("..#..", ".##..", "#.#..", "..#..", "..#..", "..#..", "..#..", "..#..", "#####"))
        put('2', listOf(".###.", "#...#", "....#", "....#", "...#.", "..#..", ".#...", "#....", "#####"))
        put('3', listOf(".###.", "#...#", "....#", "....#", "..##.", "....#", "....#", "#...#", ".###."))
        put('4', listOf("...#.", "..##.", ".#.#.", "#..#.", "#..#.", "#####", "...#.", "...#.", "...#."))
        put('5', listOf("#####", "#....", "#....", "#....", "####.", "....#", "....#", "#...#", ".###."))
        put('6', listOf("..##.", ".#...", "#....", "#....", "####.", "#...#", "#...#", "#...#", ".###."))
        put('7', listOf("#####", "....#", "....#", "...#.", "...#.", "..#..", "..#..", ".#...", ".#..."))
        put('8', listOf(".###.", "#...#", "#...#", "#...#", ".###.", "#...#", "#...#", "#...#", ".###."))
        put('9', listOf(".###.", "#...#", "#...#", "#...#", ".####", "....#", "....#", "...#.", ".##.."))

        // ---- space and the printable ASCII symbols, in code-point order ----
        put(' ', listOf("...", "...", "...", "...", "...", "...", "...", "...", "..."))
        // Two columns, so its dot has the same weight as the full stop's; the
        // stroke is bolder than a letter's, which is what an exclamation is for.
        put('!', listOf("##", "##", "##", "##", "##", "##", "..", "##", "##"))
        put('"', listOf("#.#", "#.#", "#.#", "...", "...", "...", "...", "...", "..."))
        put('#', listOf(".#.#.", ".#.#.", "#####", ".#.#.", ".#.#.", ".#.#.", "#####", ".#.#.", ".#.#."))
        put('$', listOf("..#..", ".###.", "#.#.#", "#.#..", ".###.", "..#.#", "#.#.#", ".###.", "..#.."))
        put('%', listOf("##..#", "##..#", "...#.", "...#.", "..#..", ".#...", ".#...", "#..##", "#..##"))
        put('&', listOf(".##..", "#..#.", "#..#.", "#..#.", ".##..", "#.#.#", "#..#.", "#..#.", ".##.#"))
        put('\'', listOf("#", "#", "#", ".", ".", ".", ".", ".", "."))
        put('(', listOf("..#", ".#.", "#..", "#..", "#..", "#..", "#..", ".#.", "..#"))
        put(')', listOf("#..", ".#.", "..#", "..#", "..#", "..#", "..#", ".#.", "#.."))
        put('*', listOf("#.#.#", ".###.", "#####", ".###.", "#.#.#", ".....", ".....", ".....", "....."))
        put('+', listOf(".....", ".....", "..#..", "..#..", "#####", "..#..", "..#..", ".....", "....."))
        put(',', listOf("..", "..", "..", "..", "..", "..", "##", "##", "#."))
        put('-', listOf(".....", ".....", ".....", ".....", "#####", ".....", ".....", ".....", "....."))
        put('.', listOf("..", "..", "..", "..", "..", "..", "..", "##", "##"))
        put('/', listOf("....#", "....#", "...#.", "...#.", "..#..", ".#...", ".#...", "#....", "#...."))
        put(':', listOf("..", "..", "##", "##", "..", "..", "##", "##", ".."))
        put(';', listOf("..", "..", "##", "##", "..", "..", "##", "##", "#."))
        put('<', listOf("...#", "...#", "..#.", ".#..", "#...", ".#..", "..#.", "...#", "...#"))
        put('=', listOf(".....", ".....", "#####", ".....", ".....", ".....", "#####", ".....", "....."))
        put('>', listOf("#...", "#...", ".#..", "..#.", "...#", "..#.", ".#..", "#...", "#..."))
        put('?', listOf(".###.", "#...#", "....#", "....#", "...#.", "..#..", "..#..", ".....", "..#.."))
        put('@', listOf(".###.", "#...#", "#.###", "#.#.#", "#.#.#", "#.###", "#....", "#...#", ".###."))
        put('[', listOf("###", "#..", "#..", "#..", "#..", "#..", "#..", "#..", "###"))
        put('\\', listOf("#....", "#....", ".#...", ".#...", "..#..", "...#.", "...#.", "....#", "....#"))
        put(']', listOf("###", "..#", "..#", "..#", "..#", "..#", "..#", "..#", "###"))
        put('^', listOf("..#..", ".#.#.", "#...#", ".....", ".....", ".....", ".....", ".....", "....."))
        put('_', listOf(".....", ".....", ".....", ".....", ".....", ".....", ".....", ".....", "#####"))
        put('`', listOf("#.", "#.", ".#", "..", "..", "..", "..", "..", ".."))
        put('{', listOf("..##", ".#..", ".#..", ".#..", "##..", ".#..", ".#..", ".#..", "..##"))
        put('|', listOf("#", "#", "#", "#", "#", "#", "#", "#", "#"))
        put('}', listOf("##..", "..#.", "..#.", "..#.", "..##", "..#.", "..#.", "..#.", "##.."))
        put('~', listOf(".....", ".....", ".....", ".#..#", "#.#.#", "#..#.", ".....", ".....", "....."))
    }

    private val maxWidth: Int = GLYPHS.values.maxOf { it.first().length }

    private val coverage: List<Char> = GLYPHS.keys.sorted()
}
