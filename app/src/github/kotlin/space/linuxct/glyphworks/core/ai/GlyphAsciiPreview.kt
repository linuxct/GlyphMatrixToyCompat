package space.linuxct.glyphworks.core.ai

import space.linuxct.glyphworks.core.design.DesignFrames
import space.linuxct.glyphworks.core.design.PokemonCodename
import space.linuxct.glyphworks.matrix.PanelMask

/**
 * Renders a design frame as a text grid, so a language model can *see* the art
 * it just wrote.
 *
 * ## Why this exists
 *
 * A model writing a `cells` string is writing 169 or 625 base36 characters with
 * no feedback at all: it cannot look at the panel, and it cannot look at its own
 * output either. Every design hand-authored during this project's development
 * failed in the same way until the author started rendering the frame back as
 * ASCII after each edit — at which point "the eyes are one row too low" became
 * obvious in a glance instead of invisible.
 *
 * This is the same loop, given to the model. It is why every tool result in
 * [GlyphAiTools] carries a rendering beside the JSON.
 *
 * ## The blank border is the point
 *
 * The panel is a **disc** inside a square grid (see [PanelMask]), so the corner
 * cells of a frame are not LEDs. They must still be present in `cells` — the
 * length check is geometric — but nothing drawn on them will ever be seen.
 *
 * Rendering those cells as [OFF_PANEL] (a space) rather than as "dark" makes the
 * frame's true shape visible: a model that centred its art wrongly sees its
 * drawing running into an empty margin, which is exactly the mistake that is
 * otherwise silent. That is the single most valuable property of this renderer,
 * and it is why the mask is read from [PanelMask] instead of being re-derived
 * here — the illustration, the hardware and this preview must agree cell for
 * cell or the feedback would be a lie.
 *
 * ## The ramp
 *
 * [RAMP] runs dark to bright. Index 0 (`.`) is an LED that exists and is *off*,
 * which is deliberately distinct from [OFF_PANEL]: "this pixel is black" and
 * "this pixel does not exist" are different facts, and the model needs both.
 *
 * Pure Kotlin, total, and nothing here throws: a frame of the wrong length still
 * renders (missing cells come out blank) rather than costing the caller an
 * exception in the middle of a tool call.
 */
object GlyphAsciiPreview {

    /** A cell the panel has no LED for. Blank so the disc's outline is visible. */
    const val OFF_PANEL: Char = ' '

    /**
     * Dark-to-bright ramp for cells the panel *does* have.
     *
     * Index 0 is an unlit LED; indices 1..7 are the lit range, so any non-zero
     * brightness is visibly different from black no matter how dim. Eight steps
     * is more than the default three-entry palette needs and still reads as a
     * gradient at a glance.
     */
    const val RAMP: String = ".:-=+*#@"

    /** One line explaining the character set, for tool results and the prompt. */
    const val LEGEND: String =
        "legend: a space means the panel has no LED at that cell (it is outside the disc), " +
            "'.' means an LED that is off, and ':' '-' '=' '+' '*' '#' '@' are increasing brightness"

    /**
     * The character for a raw panel brightness (0..4095) at a cell that exists.
     *
     * Note the deliberate discontinuity at 1: a brightness of 0 is `.` and *any*
     * brightness above 0 is at least `:`. Bucketing linearly from zero would map
     * the dimmest palette entries onto the same character as off, and "I set
     * these cells to level 1 and the preview did not change" is precisely the
     * kind of false negative this renderer exists to prevent.
     */
    fun charFor(brightness: Int): Char {
        if (brightness <= 0) return RAMP[0]
        val v = brightness.coerceAtMost(DesignFrames.MAX_BRIGHTNESS)
        val steps = RAMP.length - 2
        val index = 1 + (v.toLong() * steps / DesignFrames.MAX_BRIGHTNESS).toInt()
        return RAMP[index.coerceIn(1, RAMP.length - 1)]
    }

    /**
     * [frame] (row-major brightnesses, as [DesignFrames.decode] returns) as
     * `size` lines of `size` characters, newline-separated with no trailing
     * newline.
     *
     * A short or over-long array is rendered rather than rejected — cells the
     * array does not cover come out blank — because this runs inside tool
     * results where a thrown exception would replace the model's only feedback
     * with nothing.
     */
    fun render(frame: IntArray, size: Int): String {
        if (size <= 0) return ""
        val sb = StringBuilder((size + 1) * size)
        for (y in 0 until size) {
            if (y > 0) sb.append('\n')
            for (x in 0 until size) {
                if (!PanelMask.contains(x, y, size)) {
                    sb.append(OFF_PANEL)
                    continue
                }
                val i = y * size + x
                sb.append(if (i < frame.size) charFor(frame[i]) else OFF_PANEL)
            }
        }
        return sb.toString()
    }

    /**
     * [cells] rendered against [levels], or null if the string is not a frame
     * this geometry can decode (wrong length, a character that is not base36, or
     * a palette index the design does not define).
     *
     * Null rather than a best-effort drawing: showing the model a picture of a
     * frame that the codec would refuse would teach it that the frame was fine.
     */
    fun renderCells(cells: String, levels: List<Int>, size: Int): String? {
        val decoded = DesignFrames.decode(cells, levels, size) ?: return null
        return render(decoded, size)
    }

    /** [cells] rendered for [codename]'s geometry. */
    fun renderCells(cells: String, levels: List<Int>, codename: PokemonCodename): String? =
        renderCells(cells, levels, codename.size)

    /**
     * The panel itself: `#` where an LED exists, blank where one does not.
     *
     * This is the picture that answers "which cells can I actually use?", and it
     * goes into the system prompt for every geometry the design carries.
     */
    fun panelMap(size: Int): String {
        if (size <= 0) return ""
        val sb = StringBuilder((size + 1) * size)
        for (y in 0 until size) {
            if (y > 0) sb.append('\n')
            for (x in 0 until size) {
                sb.append(if (PanelMask.contains(x, y, size)) '#' else OFF_PANEL)
            }
        }
        return sb.toString()
    }

    /**
     * For each row, the range of columns that have LEDs, or null for a row with
     * none (which no supported geometry actually has).
     *
     * The disc is convex, so a row's live cells are always one unbroken run and
     * a first/last pair describes it exactly. Written out in the prompt as a
     * table, this is what lets a model place a glyph without guessing: at 13x13
     * it says in numbers what the picture says in dots.
     */
    fun liveSpans(size: Int): List<IntRange?> = (0 until size).map { y ->
        val first = (0 until size).firstOrNull { PanelMask.contains(it, y, size) }
        val last = (size - 1 downTo 0).firstOrNull { PanelMask.contains(it, y, size) }
        if (first == null || last == null) null else first..last
    }

    /**
     * The [liveSpans] table as prompt text: one line per row, naming the live
     * column range and how many cells it holds.
     */
    fun liveSpanTable(size: Int): String = liveSpans(size).withIndex().joinToString("\n") { (y, span) ->
        if (span == null) "  row $y: no cells"
        else "  row $y: columns ${span.first}-${span.last} (${span.last - span.first + 1} cells)"
    }
}
