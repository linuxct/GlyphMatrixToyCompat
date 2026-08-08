package space.linuxct.glyphworks.core.design

import space.linuxct.glyphworks.matrix.PanelMask

/**
 * Turns a phrase into a right-to-left scrolling animation in [MarqueeFont]'s
 * full-height letterforms.
 *
 * ## What this is for
 *
 * Scrolling text is the one thing a language model cannot draw by hand, and
 * `scroll_frames` only fixed half of it: it does the windowing, but the model
 * still has to draw every letter as a bitmap first, and a nine-row alphabet
 * hand-written per request is thirty characters of judgement per letter with no
 * error signal. This object holds the alphabet, so "scroll HELLO" stops being a
 * drawing problem and becomes a lookup.
 *
 * ## The whole message is laid out once
 *
 * [MarqueeFont.strip] produces the entire phrase as one array of columns, and a
 * frame is a window onto it at a single offset. **One number changes per
 * frame.** That is the same construction `scroll_frames` enforces and it is
 * enforced for the same reason: a frame built by nudging the rows of the
 * previous frame is one independent decision per row, and a single row a column
 * out tears the letter in half.
 *
 * ## Where the band sits, and what gets clipped
 *
 * The glyphs are nine rows tall (see [MarqueeFont]) and are centred vertically,
 * which at 13x13 puts them on rows 2-10. **A lower-case descender changes
 * nothing here**: the face's baseline is row 7 of its own nine, so `g` and `y`
 * hang into row 8 — a row a capital was already using — and there is no such
 * thing as a glyph cell outside the band to place. Measured against `PanelMask`, rows 2-10
 * are entirely live in eleven of the thirteen columns; in columns 0 and 12 the
 * disc cuts the top and bottom of the letter away. That clipping is the intended
 * effect — a letter growing out of the rim and shrinking back into it is what
 * reading "big" looks like on a disc — but a clipped cell must never be
 * **stored**: it would be invisible on the panel, would still cost bytes in the
 * file and would show up in the editor as art the user cannot reach. So
 * [frames] tests every cell against [PanelMask] before it writes one.
 *
 * ## Both panels look the same, and cost the same
 *
 * At 25x25 the letters are drawn at [scaleFor] 2 — every cell becomes 2x2 — so
 * they occupy 18 of 25 rows rather than 9, the same proportion of the panel as
 * at 13x13. Scaling the art alone would double the frame count for the same
 * phrase, so the scroll advances by [defaultStep] = the scale: two panel columns
 * per frame at 25x25, which is one *logical* column and therefore the same
 * apparent speed and the same frame count as at 13x13. The frame budget a phrase
 * has to fit is consequently identical on both geometries, which is what makes
 * [maxPrefixLength] answerable without asking which phone is in the user's hand.
 *
 * Pure Kotlin and total: nothing here throws, and unusable input comes back as
 * an empty frame list or a zero count rather than as an exception.
 */
object MarqueeText {

    /**
     * Milliseconds per frame when nobody says otherwise.
     *
     * Nothing's own full-height marquee is, in the user's words, "very readable
     * but very slow to both show and read", and this face is condensed
     * specifically to be faster. One logical column per frame at 80 ms carries a
     * five-column letter plus its gap across the panel in 480 ms — a little over
     * two letters a second, which is brisk without smearing. `scroll_frames`
     * defaults to 120 ms because it scrolls art of unknown width; this one knows
     * exactly how wide a letter is.
     */
    const val DEFAULT_DURATION_MS: Int = 80

    /**
     * How many panel cells one glyph cell becomes on a [size]-wide panel.
     *
     * `size / HEIGHT`, floored, never below 1: 1 at 13x13 and 2 at 25x25. The
     * point is that the letters occupy the same *fraction* of either panel, so a
     * design does not have to be redrawn to look right on the other phone.
     */
    fun scaleFor(size: Int): Int = maxOf(1, size / MarqueeFont.HEIGHT)

    /**
     * Panel columns the window moves per frame when nobody says otherwise: the
     * scale, i.e. exactly one *logical* column. See this object's KDoc.
     */
    fun defaultStep(size: Int): Int = scaleFor(size)

    /**
     * The panel row the top row of the glyphs sits on — vertically centred.
     *
     * Rows 2-10 at 13x13 with [scaleFor] 1, rows 3-20 at 25x25 with scale 2.
     * Centring is not a preference here: the disc is widest at its middle row,
     * so any other placement clips more of the letter for the same height.
     */
    fun topRow(size: Int, scale: Int): Int = (size - MarqueeFont.HEIGHT * scale) / 2

    /**
     * How many frames a [stripWidth]-column message takes to cross a [size]-wide
     * panel at [scale] and [step].
     *
     * The traverse runs from the frame in which the message's leading column is
     * on the panel's right-hand edge to the frame in which its trailing column
     * is still on the left-hand edge — panel width + message width - 1 columns
     * of travel at one column per frame, which is what `scroll_frames`'
     * `frame_count_note` states in the same words.
     *
     * **This is an upper bound, not the count [frames] returns.** The outermost
     * column of the disc has only five live rows, so a glyph column whose lit
     * cells all sit outside them — the top and bottom serifs of an `I`, say —
     * arrives on the panel as nothing at all. [frames] drops those leading and
     * trailing blanks, because a design that opens on a dark panel is the defect
     * the assistant's animation guidance calls out by name. The bound is what a
     * caller checks a frame budget against *before* laying anything out; the
     * result of [frames] is what it reports afterwards.
     */
    fun frameCount(size: Int, stripWidth: Int, scale: Int, step: Int): Int {
        if (size <= 0 || stripWidth <= 0 || scale < 1 || step < 1) return 0
        val travel = size + stripWidth * scale - 2
        return if (travel < 0) 1 else travel / step + 1
    }

    /** [frameCount] for [text] as this face lays it out. 0 if it cannot be laid out. */
    fun frameCount(text: String, size: Int, scale: Int, step: Int): Int =
        frameCount(size, MarqueeFont.stripWidth(text), scale, step)

    /**
     * The longest **prefix of [text]**, in characters, whose scroll fits in
     * [maxFrames].
     *
     * A count of characters rather than of columns, and a count taken from *this
     * text* rather than an average, because it is the number a caller has to act
     * on: the letters are proportional, so "39 characters" is true of one phrase
     * and false of another and only the phrase in hand can answer it. A refusal
     * that reports this can be answered by cutting the phrase there; a refusal
     * that reports a column budget cannot be answered at all without redoing the
     * layout by hand.
     *
     * Returns [text]'s length when the whole thing fits, and 0 when even the
     * first character does not.
     */
    fun maxPrefixLength(
        text: String,
        size: Int,
        scale: Int,
        step: Int,
        maxFrames: Int = DesignCodec.MAX_FRAMES,
    ): Int {
        if (size <= 0 || scale < 1 || step < 1 || maxFrames < 1) return 0
        var columns = 0
        var fits = 0
        for ((i, c) in text.withIndex()) {
            val width = MarqueeFont.width(c)
            if (width <= 0) return fits
            val next = if (i == 0) width else columns + MarqueeFont.GAP + width
            if (frameCount(size, next, scale, step) > maxFrames) return fits
            columns = next
            fits = i + 1
        }
        return fits
    }

    /**
     * The frames of the scroll, or an **empty list** if [text] cannot be drawn
     * at all — an empty string, a character outside [MarqueeFont]'s coverage, or
     * a nonsensical geometry.
     *
     * [paletteIndex] is the palette entry every lit cell takes, and it is one
     * index for the whole animation by construction: an element that changes
     * brightness between frames reads as a flicker, and there is no way to
     * express one here.
     *
     * Empty rather than null so a caller that ignores the failure renders
     * nothing rather than a design with one bad frame in it; callers that must
     * explain the failure ask [MarqueeFont.unsupported] first.
     *
     * Leading and trailing blank frames are dropped — see [frameCount] — so the
     * result is never longer than [frameCount] says and is usually a frame or
     * two shorter. A blank frame in the *middle* is left alone: it can only come
     * from a run of spaces wider than the panel, which is something the caller
     * asked for.
     */
    fun frames(
        text: String,
        size: Int,
        paletteIndex: Int = 1,
        durationMs: Int = DEFAULT_DURATION_MS,
        scale: Int = scaleFor(size),
        step: Int = scaleFor(size),
    ): List<DesignFrame> {
        if (size <= 0 || scale < 1 || step < 1) return emptyList()
        if (paletteIndex < 1 || paletteIndex >= DesignFrames.MAX_PALETTE) return emptyList()
        val strip = MarqueeFont.strip(text)
        if (strip.isEmpty()) return emptyList()
        val glyphHeight = MarqueeFont.HEIGHT * scale
        if (glyphHeight > size) return emptyList()

        val top = topRow(size, scale)
        val count = frameCount(size, strip.size, scale, step)
        if (count < 1 || count > DesignCodec.MAX_FRAMES) return emptyList()
        val lit = charOfIndex(paletteIndex)

        val out = ArrayList<DesignFrame>(count)
        for (f in 0 until count) {
            // THE one number that changes per frame. Every row of the frame is
            // read at this offset, so the rows cannot disagree and the letter
            // cannot shear.
            val offset = (size - 1) - f * step
            val cells = CharArray(size * size) { '0' }
            for (x in 0 until size) {
                // Which logical column of the message is under panel column x.
                val column = Math.floorDiv(x - offset, scale)
                if (column < 0 || column >= strip.size) continue
                val mask = strip[column]
                if (mask == 0) continue
                for (r in 0 until MarqueeFont.HEIGHT) {
                    if (mask and (1 shl r) == 0) continue
                    for (k in 0 until scale) {
                        val y = top + r * scale + k
                        // Clipped on generation, not on render: a cell the disc
                        // has no LED for is never written, so the file carries
                        // only art that exists.
                        if (y < 0 || y >= size) continue
                        if (!PanelMask.contains(x, y, size)) continue
                        cells[y * size + x] = lit
                    }
                }
            }
            out.add(DesignFrame(durationMs, String(cells)))
        }

        // Trim the dark beats off both ends. See [frameCount] for why they can
        // exist at all: a column that is all serif has nothing inside the disc's
        // five-row outermost slice, so it enters as an empty panel.
        val first = out.indexOfFirst { it.cells.any { c -> c != '0' } }
        if (first < 0) return emptyList()
        val last = out.indexOfLast { it.cells.any { c -> c != '0' } }
        return out.subList(first, last + 1).toList()
    }

    /**
     * The smallest [step] whose traverse of [text] fits in [maxFrames], or
     * **null** when even one column per frame across the whole panel would not.
     *
     * The other half of [maxPrefixLength]: between them they are the two ways
     * out of a phrase that is too long — say less, or move faster — and both are
     * answers a caller can act on without laying the text out again by hand.
     * Null rather than a number that would fail again, because a suggestion that
     * is refused on the next attempt is worse than no suggestion.
     */
    fun stepThatFits(
        text: String,
        size: Int,
        scale: Int = scaleFor(size),
        maxFrames: Int = DesignCodec.MAX_FRAMES,
    ): Int? {
        if (size <= 0 || scale < 1 || maxFrames < 2) return null
        val stripWidth = MarqueeFont.stripWidth(text)
        if (stripWidth <= 0) return null
        val travel = size + stripWidth * scale - 2
        val needed = if (travel <= 0) 1 else (travel + maxFrames - 2) / (maxFrames - 1)
        return needed.takeIf { it in 1..size }
    }

    /**
     * Everything that has to be decided about scrolling [text] on a [size]-wide
     * panel, decided **once**.
     *
     * [frames] answers "can this be drawn" with an empty list, which is all a
     * generator needs and nothing like enough for a screen: somebody typing a
     * phrase has to be told *which* character cannot be drawn, or how much of
     * their phrase fits and how much faster it would have to move. Those numbers
     * already exist — [MarqueeFont.unsupported], [maxPrefixLength],
     * [stepThatFits] — and this is the one place that puts them in order, so a
     * caller cannot invent a second length rule or a second definition of "too
     * long" by asking them in a different one.
     *
     * Data, not sentences: `core/` says nothing in English. Whoever shows a
     * [Refused] to a person writes the words in their own language resources.
     */
    fun plan(
        text: String,
        size: Int,
        paletteIndex: Int = 1,
        durationMs: Int = DEFAULT_DURATION_MS,
        scale: Int = scaleFor(size),
        step: Int = scaleFor(size),
        maxFrames: Int = DesignCodec.MAX_FRAMES,
    ): MarqueePlan {
        if (text.isEmpty()) return MarqueePlan.Blank
        val missing = MarqueeFont.unsupported(text)
        if (missing.isNotEmpty()) return MarqueePlan.Unsupported(missing)
        val stripWidth = MarqueeFont.stripWidth(text)
        val needed = frameCount(size, stripWidth, scale, step)
        if (needed > maxFrames) {
            return MarqueePlan.TooLong(
                framesNeeded = needed,
                maxFrames = maxFrames,
                prefix = text.take(maxPrefixLength(text, size, scale, step, maxFrames)),
                stepThatFits = stepThatFits(text, size, scale, maxFrames)?.takeIf { it > step },
            )
        }
        val frames = frames(
            text = text,
            size = size,
            paletteIndex = paletteIndex,
            durationMs = durationMs,
            scale = scale,
            step = step,
        )
        return if (frames.isEmpty()) MarqueePlan.Blank else MarqueePlan.Ready(frames)
    }

    /** Base36 palette index as [DesignFrames] writes it. */
    private fun charOfIndex(index: Int): Char =
        if (index < 10) ('0' + index) else ('a' + (index - 10))
}

/**
 * What [MarqueeText.plan] decided: the frames, or the reason there are none.
 *
 * A sealed answer rather than an empty list plus an out-parameter, because every
 * refusal here carries **numbers a caller has to show** — and a caller that has
 * to ask a second question to find out why it got nothing will eventually ask a
 * different question and get a different answer.
 */
sealed interface MarqueePlan {

    /** The scroll, ready to be written into a design exactly as it is. */
    data class Ready(val frames: List<DesignFrame>) : MarqueePlan

    /**
     * Characters this face has no letterform for, in the order they appear and
     * without repeats — all of them, so a phrase can be fixed in one pass.
     */
    data class Unsupported(val characters: List<Char>) : MarqueePlan

    /**
     * The scroll would take [framesNeeded] frames and a design holds
     * [maxFrames]. Both ways out come with it: [prefix] is the longest leading
     * part of the phrase that does fit, and [stepThatFits] is the smallest speed
     * that would carry the whole phrase, or null when nothing would.
     */
    data class TooLong(
        val framesNeeded: Int,
        val maxFrames: Int,
        val prefix: String,
        val stepThatFits: Int?,
    ) : MarqueePlan

    /**
     * Nothing to scroll: an empty phrase, or one whose every frame came out
     * dark. Distinct from [Unsupported] because there is no character to name.
     */
    data object Blank : MarqueePlan
}
