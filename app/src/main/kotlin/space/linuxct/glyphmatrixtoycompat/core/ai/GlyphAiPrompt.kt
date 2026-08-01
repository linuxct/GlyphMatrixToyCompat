package space.linuxct.glyphmatrixtoycompat.core.ai

import space.linuxct.glyphmatrixtoycompat.core.design.DEFAULT_LEVELS
import space.linuxct.glyphmatrixtoycompat.core.design.Design
import space.linuxct.glyphmatrixtoycompat.core.design.DesignCodec
import space.linuxct.glyphmatrixtoycompat.core.design.DesignFrames
import space.linuxct.glyphmatrixtoycompat.core.design.DesignKind
import space.linuxct.glyphmatrixtoycompat.core.design.PokemonCodename
import space.linuxct.glyphmatrixtoycompat.matrix.PanelMask

/**
 * Builds the system prompt for the design assistant.
 *
 * ## What this has to teach, and why
 *
 * The model on the other end has never seen this app, has never held the phone,
 * and cannot see the panel. Everything it needs is in this string, and the
 * ordering below is not cosmetic — the disc comes first and loudest because
 * **every** hand-authoring failure during this project traced to the same
 * mistake: art drawn to fill a 13x13 square, on hardware that is a 13-cell
 * circle. The corner cells accept characters and never light. A model that does
 * not understand that produces work that looks correct in its own head and
 * arrives on the user's phone with its edges missing.
 *
 * ## Why the numbers are read, not written
 *
 * Every limit quoted here comes from [DesignCodec] and [PanelMask] at build
 * time. A prompt that hardcoded "240 frames" would keep saying so after the
 * constant moved, and the model would be confidently wrong about the one thing
 * it cannot check for itself. The mask counts (137 and 489) are likewise
 * counted, not typed.
 *
 * ## Variant gating
 *
 * The prompt names **only the geometries this design actually carries**. A
 * bellsprout-only design produces a prompt in which the word `arbok` does not
 * appear at all: the model cannot ask for a panel it has never been told about,
 * which makes the tool-level refusal in [GlyphAiTools] a backstop rather than
 * the first line of defence. Adding a geometry to a design is a decision the
 * user makes in the editor, not one the assistant can take.
 *
 * The prompt is sent on every turn, so this distils `docs/glyph-design-format.md`
 * rather than reproducing it. The spec remains authoritative; if the two ever
 * disagree, the spec is right and this is a bug.
 */
object GlyphAiPrompt {

    /**
     * The mask rule, written exactly as the model should think about it.
     *
     * Kept as a constant because it is asserted by test: this sentence
     * disappearing from the prompt is a silent regression of the single most
     * important fact in it.
     */
    const val MASK_RULE: String = "dx*dx + dy*dy <= (size/2)*(size/2)"

    /**
     * The palette the worked example uses: off, and full.
     *
     * Two entries rather than the default three, deliberately. It is the shortest
     * possible demonstration of the fact the prose right above it states and that
     * models get wrong — **a cell character is an index into `levels`, not a
     * brightness**. With `[0, 4095]` the character `'1'` means 4095, which is
     * impossible to read as "1 is dim" and therefore impossible to
     * pattern-match wrongly.
     */
    val EXAMPLE_LEVELS: List<Int> = listOf(0, 4095)

    /**
     * Frame 0 of the worked example: a face with its eyes open, 13x13.
     *
     * Every lit cell is inside the disc and the drawing is centred on (6, 6) —
     * the example has to *demonstrate* the rule the prompt shouts about, not just
     * be legal. `GlyphAiPromptTest` asserts both, so it cannot drift into a
     * picture that teaches the opposite of the section above it.
     */
    const val EXAMPLE_CELLS_EYES_OPEN: String =
        // One source line per panel row, so the face is legible here too. The
        // model is shown the concatenation, which is what the format wants: 169
        // characters, row-major, no separators.
        "0000000000000" +
            "0000000000000" +
            "0000000000000" +
            "0000000000000" +
            "0000100010000" +
            "0000100010000" +
            "0000000000000" +
            "0001000001000" +
            "0000111110000" +
            "0000000000000" +
            "0000000000000" +
            "0000000000000" +
            "0000000000000"

    /** Frame 1: the same face mid-blink, the eyes shut to two short bars. */
    const val EXAMPLE_CELLS_EYES_SHUT: String =
        "0000000000000" +
            "0000000000000" +
            "0000000000000" +
            "0000000000000" +
            "0000000000000" +
            "0001110111000" +
            "0000000000000" +
            "0001000001000" +
            "0000111110000" +
            "0000000000000" +
            "0000000000000" +
            "0000000000000" +
            "0000000000000"

    /**
     * One complete, legal `glyph.design` document for [codename], or null if the
     * example's art cannot be centred on that geometry.
     *
     * ## Why an example at all
     *
     * Everything before it in the prompt *describes* the format. A model
     * pattern-matches a filled-in document far better than it reasons from a
     * description, and `cells` is an unusual enough encoding — base36 palette
     * indices, row-major, exactly `size*size` of them, corners included — that a
     * description alone leaves several plausible-but-wrong readings open. This
     * closes them: two real frames, real strings, and the renderings that go with
     * them.
     *
     * ## Why it is built per panel rather than written out once
     *
     * Because the prompt names **only the geometries this design carries** (see
     * this file's KDoc), and an example is text like any other: a hardcoded
     * `"bellsprout"` example in an `arbok`-only conversation would put the word
     * `bellsprout` in front of a model that must never be offered it. The art is
     * authored once at 13x13 and translated to the centre of any larger panel, so
     * there is still one drawing and one place to change it.
     *
     * ## Why a test can reach it
     *
     * So the example can be fed through the real [DesignCodec] and the real
     * tools. An example that quietly became illegal — a limit moved, a field
     * renamed — would be worse than no example at all, because it carries the
     * authority of the prompt: the model would faithfully reproduce a document
     * this app refuses. `GlyphAiPromptTest` fails the build instead.
     *
     * App-managed fields are omitted, which is itself part of the lesson: the
     * paragraph above it tells the model not to send them, so it must not.
     */
    fun workedExample(codename: PokemonCodename): String? {
        val open = centred(EXAMPLE_CELLS_EYES_OPEN, codename.size) ?: return null
        val shut = centred(EXAMPLE_CELLS_EYES_SHUT, codename.size) ?: return null
        return """
            {
              "name": "Blink",
              "kind": "dynamic",
              "keyMode": "playPause",
              "loop": true,
              "levels": [${EXAMPLE_LEVELS.joinToString(", ")}],
              "variants": {
                "${codename.codename}": {
                  "frames": [
                    { "durationMs": 900, "cells": "$open" },
                    { "durationMs": 120, "cells": "$shut" }
                  ]
                }
              }
            }
        """.trimIndent()
    }

    /**
     * The 13x13 art of [cells13] placed in the middle of a [size] x [size] frame.
     *
     * Translation, never scaling. The art is small, so on a larger panel it stays
     * exactly as drawn and simply sits in the middle with a wider margin — which
     * is a *correct* design rather than an interpolated one, and it keeps every
     * lit cell well inside the larger disc by construction (a cell within 6.5
     * cells of the centre at 13 is within 6.5 of the centre at 25 too).
     *
     * Null for a geometry the translation cannot centre on: smaller than the art,
     * or of the opposite parity, where "the middle" would be half a cell out.
     */
    private fun centred(cells13: String, size: Int): String? {
        val source = PokemonCodename.BELLSPROUT.size
        if (size < source || (size - source) % 2 != 0) return null
        if (size == source) return cells13
        val offset = (size - source) / 2
        val out = CharArray(size * size) { '0' }
        for (i in cells13.indices) {
            if (cells13[i] == '0') continue
            out[(i / source + offset) * size + (i % source + offset)] = cells13[i]
        }
        return String(out)
    }

    /**
     * The example at 13x13, which is the one every test asserts against. Empty
     * only if [centred] refused a geometry it is built to handle, which is a
     * failing test rather than a broken prompt.
     */
    val WORKED_EXAMPLE: String = workedExample(PokemonCodename.BELLSPROUT).orEmpty()

    /** The system prompt for a conversation about [design]. */
    fun build(design: Design): String {
        val carried = variantsPresent(design)
        return buildString {
            append(INTRO)
            append("\n\n")
            append(discSection(carried))
            append("\n\n")
            append(formatSection(carried))
            append("\n\n")
            append(thisDesignSection(design, carried))
            append("\n\n")
            append(WORKFLOW)
            append("\n\n")
            append(SIMPLIFY)
            append("\n\n")
            append(animationSection(carried))
            append("\n\n")
            append(STYLE)
        }
    }

    /**
     * The geometries [design] carries — the permitted edit scope, and the same
     * expression the editor uses for its variant switcher
     * (`DesignEditorActivity.EditorState.variantsPresent`).
     */
    fun variantsPresent(design: Design): List<PokemonCodename> =
        PokemonCodename.entries.filter { design.variantFor(it) != null }

    /**
     * The band of rows on a [size] x [size] panel that has an LED in **every**
     * column, or null for a geometry that has none.
     *
     * This is the one fact a marquee needs and cannot get from the mask rule
     * without arithmetic: art that stays inside this band never touches the
     * disc's curve, so it can be shifted horizontally to any offset without a
     * single cell falling off the panel. At 13x13 it is rows 4-8; at 25x25 rows
     * 9-15. Computed from [GlyphAsciiPreview.liveSpans], not typed, for the
     * reason this file's KDoc gives about every other number in the prompt.
     *
     * The disc is convex, so the qualifying rows are contiguous and a range
     * describes them exactly.
     */
    fun fullWidthRows(size: Int): IntRange? {
        val rows = GlyphAsciiPreview.liveSpans(size).withIndex()
            .filter { (_, span) -> span != null && span.first == 0 && span.last == size - 1 }
            .map { it.index }
        return if (rows.isEmpty()) null else rows.first()..rows.last()
    }

    private val INTRO = """
        You are the design assistant built into GMTC (Glyph Matrix Toy Compat), an Android app
        for Nothing phones. GMTC drives the Glyph Matrix: a small circular monochrome LED panel
        on the BACK of the phone, used as a second, glanceable display. There is no colour, no
        anti-aliasing and no sub-pixel anything — each cell is one white LED with a 12-bit
        brightness from 0 (off) to ${DesignFrames.MAX_BRIGHTNESS} (full).

        The user is editing one design in GMTC's design studio and is talking to you about it.
        You read what is on their canvas with get_current_design, and you change it by writing a
        complete glyph.design document back with apply_design. An applied change appears on
        their canvas immediately. They have a one-tap undo, so a mistake is recoverable — but it
        costs them a step, so check your work with validate_design first.
    """.trimIndent()

    /**
     * The disc, its rule, and a picture of each panel the design carries.
     *
     * The per-row column table is not redundant with the picture: the picture is
     * how a model *notices* the shape, the table is how it places a glyph on an
     * exact row without counting dots.
     */
    private fun discSection(carried: List<PokemonCodename>): String = buildString {
        append(
            """
            ========================================================================
            THE PANEL IS A DISC. THIS IS THE ONE THING THAT GOES WRONG.
            ========================================================================

            A frame is stored as a SQUARE grid, but the hardware is ROUND. The corner cells of
            that square DO NOT EXIST. There is no LED behind them. Anything you write into them
            is accepted, stored, and never seen by anybody.

            - Those cells must STILL appear in the cells string. The length check is geometric:
              a frame is exactly size*size characters or it is rejected outright. Put '0' there.
            - A cell (x, y) has an LED if and only if

                  $MASK_RULE

              where dx = x - (size-1)/2 and dy = y - (size-1)/2, measured in cells as decimals
              (so at size 13 the centre is (6, 6) and the radius is 6.5 cells).
            - Therefore: CENTRE YOUR ART, and draw for the inscribed circle rather than for the
              square. A shape that fills the square arrives with its corners amputated.

            Every preview you are shown blanks the cells that do not exist. If your drawing runs
            into that blank margin, it is being cut off. Read the preview after every change:
            it is the only way you can see what you actually made.

            ${GlyphAsciiPreview.LEGEND}
            """.trimIndent(),
        )
        // Built by appending rather than by interpolating into a raw string:
        // trimIndent() measures the string AFTER interpolation, so dropping a
        // multi-line panel map into an indented template would make the map's
        // own indentation the common prefix and leave the surrounding prose
        // indented. The pictures have to line up, so they are appended whole.
        for (codename in carried) {
            val size = codename.size
            append("\n\n---- ")
            append(codename.codename)
            append(": ${size}x$size, ${size * size} cells per frame, ")
            append("${PanelMask.count(size)} of them are real LEDs ----\n\n")
            append("The panel, '#' where an LED exists:\n\n")
            append(GlyphAsciiPreview.panelMap(size).prependIndent("  "))
            append("\n\nLive columns per row (x from 0 on the left, y from 0 at the top):\n\n")
            append(GlyphAsciiPreview.liveSpanTable(size))
            // Computed rather than written down: the same sentence was hardcoded
            // for 13x13 and had to be right for every geometry the animation
            // section quotes it back at.
            fullWidthRows(size)?.let { rows ->
                val band = "${rows.first} to ${rows.last}"
                append("\n\n")
                append(
                    """
                    Worth memorising for text and marquees at ${size}x$size: rows $band are the only
                    rows live across all $size columns, so a glyph ${rows.count()} rows tall or shorter,
                    placed inside them, is never clipped at any horizontal position - it can scroll
                    the whole way across without losing a cell.
                    """.trimIndent(),
                )
            }
        }
    }

    /**
     * The format, then one whole document that obeys it.
     *
     * Appended rather than interpolated for the reason [discSection] gives at
     * length: `trimIndent()` measures the string *after* interpolation, so
     * dropping a multi-line JSON document into an indented template would make
     * the document's own indentation the common prefix and leave the prose
     * indented instead. The example's shape is the point, so it goes in whole.
     */
    private fun formatSection(carried: List<PokemonCodename>): String = buildString {
        append(formatProse())
        // The example is drawn for a panel this design carries — the first of
        // them — so an arbok-only conversation is never shown the word
        // bellsprout. A design carrying nothing gets no example: it gets told it
        // cannot be edited instead.
        val codename = carried.firstOrNull() ?: return@buildString
        val example = workedExample(codename) ?: return@buildString
        val size = codename.size
        append("\n\n")
        append(
            """
            ========================================================================
            A COMPLETE EXAMPLE - COPY THIS SHAPE
            ========================================================================

            One whole document, exactly as apply_design wants it: a two-frame blink at ${size}x$size,
            centred on the disc.
            """.trimIndent(),
        )
        append("\n\n")
        append(example.prependIndent("  "))
        append("\n\n")
        append(
            """
            Note what that document does NOT contain: no format, no formatVersion, no id, no
            timestamps. The app fills those in.

            "levels" there has two entries, so the only legal characters in cells are '0' and
            '1' - and '1' means ${EXAMPLE_LEVELS.last()}, full brightness. The character is an
            INDEX into levels, never a brightness in its own right.

            Each cells string above is one unbroken run of $size * $size = ${size * size}
            characters: no spaces, no line breaks, no separators between rows.

            The renderings below carry each row's INDEX down the left, and a bar at the panel's
            left and right edge so the width is countable. Row 0 is the FIRST $size characters
            of the string and the TOP row of the picture; row ${size - 1} is the LAST $size and
            the bottom row. Those labels and bars exist to show you that mapping - they are not
            part of cells, and nothing like them ever goes into a cells string.

            Frame 0 (900 ms), rendered:
            """.trimIndent(),
        )
        append("\n\n")
        append(examplePreview(EXAMPLE_CELLS_EYES_OPEN, codename))
        append("\n\nFrame 1 (120 ms), rendered - the same face with its eyes shut:\n\n")
        append(examplePreview(EXAMPLE_CELLS_EYES_SHUT, codename))
        append("\n\n")
        append(
            """
            Both frames are symmetric and neither puts a lit cell where there is no LED. Note
            that this example is small and reserved - it is showing you the FORMAT, not the only
            good composition. Filling the disc, running to the rim and using the mid levels are
            all fine; see the style notes at the end.
            """.trimIndent(),
        )
    }

    /**
     * A frame of the worked example drawn by the *real* renderer, so the picture
     * and the string beside it cannot disagree. Empty rather than null-checked
     * loudly: a prompt is not a place to throw, and the test proves it renders.
     */
    private fun examplePreview(cells13: String, codename: PokemonCodename): String =
        centred(cells13, codename.size)
            ?.let { GlyphAsciiPreview.renderCells(it, EXAMPLE_LEVELS, codename) }
            ?.let { labelledRows(it) }
            .orEmpty()
            .prependIndent("  ")

    /**
     * A rendered picture with its row indices down the left and a bar at each end.
     *
     * ## Why the example is labelled rather than merely described
     *
     * The prompt says twice which way y runs, and a model still drew a "10"
     * upside down and then "rotated it 180 degrees" wrongly. A described mapping
     * is something to recall; a labelled one is something to read off. So the
     * worked example — the thing a model pattern-matches hardest — now shows the
     * index beside every row, and the reader can see that row 0 is the row at the
     * top rather than take it on trust.
     *
     * The bars matter for a second reason: an off-panel cell renders as a SPACE
     * (see [GlyphAsciiPreview.OFF_PANEL]), so the left and right ends of a row
     * are invisible without something to mark them, and a row of thirteen cells
     * whose first four are blank looks nine characters wide. The bars make the
     * width countable, which is what "is this glyph in the same columns as in the
     * last frame?" needs.
     *
     * Deliberately NOT part of [GlyphAsciiPreview]: the tool results feed a
     * preview back to the model dozens of times per turn, and adding six
     * characters to every row of a 25-row frame is real context spent on
     * decoration. The prompt shows this once, where it teaches something.
     */
    fun labelledRows(picture: String): String =
        picture.lines().withIndex().joinToString("\n") { (y, line) ->
            "row ${y.toString().padStart(2)} |$line|"
        }

    private fun formatProse(): String = """
        ========================================================================
        THE glyph.design FORMAT
        ========================================================================

        A design is one JSON object:

          {
            "format": "glyph.design",
            "formatVersion": 1,
            "name": "Slow Ember",
            "kind": "dynamic",
            "keyMode": "playPause",
            "loop": true,
            "levels": [0, 2048, 4095],
            "variants": {
              "<codename>": { "frames": [ { "durationMs": 120, "cells": "000..." } ] }
            }
          }

        cells - the pixels. ONE character per cell, and the character is a PALETTE INDEX in
          base36: '0'-'9' for 0-9 then 'a'-'z' for 10-35. Write lower-case. Cells run
          row-major, so the character at string position y * size + x is the cell at column x,
          row y, with (0, 0) at the TOP-LEFT. The length must be exactly size*size for that
          variant's panel. A cell does NOT carry a brightness; it carries an index into levels.

          $ROW_ZERO_IS_TOP
          The FIRST size characters of a cells string are the TOP row of the picture, the next
          size are the row under it, and the LAST size are the bottom row. Write the rows out
          in the order you would read them: top first, downwards. A design that arrives upside
          down was written bottom-up.

        levels - the palette: raw panel brightnesses, 0 to ${DesignFrames.MAX_BRIGHTNESS}, at most
          ${DesignFrames.MAX_PALETTE} entries. [0, 2048, 4095] means '0' is off, '1' is half and
          '2' is full. Every character in every cells string must index an entry that exists, so
          if levels has 3 entries the only legal characters are '0', '1' and '2'. Re-palettising a
          whole design (dimming every grey, say) is a one-line edit to levels.

        kind - "static" means exactly ONE frame; "dynamic" means an animation of two or more.
          A static design shows only its first frame, so sending several frames with kind
          "static" is REJECTED rather than silently losing them: if you write an animation,
          set kind to "dynamic" in the same document.

        loop - whether a playPause animation repeats. With loop false it holds its last frame,
          so end the animation on the image you want it to rest on.

        keyMode - what one press of the phone's Essential Key does while the design is showing.
          "playPause" (the usual choice) starts playing on its own and a press pauses/resumes.
          "playOnce" rests on frame 0 and a press plays through once and returns; loop is
          ignored in that mode.

        durationMs - how long one frame is held. ${DesignCodec.MIN_DURATION_MS} to
          ${DesignCodec.MAX_DURATION_MS} inclusive; out of range is rejected, not clamped.
          120 ms is a comfortable default (~8 fps).

        Limits, all enforced: at most ${DesignCodec.MAX_FRAMES} frames per variant; at most
        ${DesignCodec.MAX_BYTES} bytes for the whole document; name at most
        ${DesignCodec.MAX_NAME_LENGTH} characters.

        format, formatVersion, id, author, createdAt, createdWith and modifiedAt are managed by
        the app. You may omit them; if you send them they are ignored and the app's own values
        are kept. Everything else is yours to change.

        Leaving a key out means "do not change this", never "reset this": name, kind, keyMode,
        loop and levels each keep the value already on the canvas unless your document actually
        sets them, exactly as a variant you leave out keeps its frames. So changing only the
        art cannot blank the design's name or swap its palette out from under it. Send a field
        when you mean to change it.
    """.trimIndent()

    /**
     * The per-conversation injection: what the user is editing right now, and
     * the closed set of geometries this conversation is allowed to touch.
     */
    private fun thisDesignSection(design: Design, carried: List<PokemonCodename>): String = buildString {
        append(
            """
            ========================================================================
            THE DESIGN YOU ARE EDITING
            ========================================================================

            name: ${design.name.ifBlank { "(untitled)" }}
            kind: ${if (design.kind == DesignKind.STATIC) "static" else "dynamic"}
            loop: ${design.loop}
            levels: ${design.levels}
            """.trimIndent(),
        )
        append("\n\n")
        if (carried.isEmpty()) {
            append(
                """
                This design carries artwork for NO panel this build knows about. You cannot edit
                it. Say so and stop; do not attempt apply_design.
                """.trimIndent(),
            )
            return@buildString
        }
        val list = carried.joinToString(", ") { "\"${it.codename}\" (${it.size}x${it.size})" }
        append(
            """
            This design carries artwork for exactly ${carried.size} panel${if (carried.size == 1) "" else "s"}: $list.

            THAT LIST IS CLOSED. You may read and write those and nothing else. Do not invent a
            panel, do not add one, and do not write a variants key that is not in that list -
            the tool will refuse the whole apply and nothing will change. Only the user can add
            a panel to a design, from the editor.
            """.trimIndent(),
        )
        if (carried.size > 1) {
            append("\n\n")
            append(
                """
                The user has one of these open on screen at a time, and get_current_design tells
                you which. That is NOT a restriction on what you may edit: you may change any
                panel in the list above, including one that is not currently on screen. The user
                sees that change when they switch to it.

                Each panel is separate artwork. Nothing is ever scaled between them, so a change
                the user asks for "everywhere" means drawing it once per panel, at that panel's
                own size. If they do not say, ask, or change the one they are looking at.
                """.trimIndent(),
            )
        }
    }

    /**
     * The refusal to guess, stated as a sentence a test can hold onto.
     *
     * pulseloop's `CoachPromptBuilder` is blunt about this — "Always call tools
     * to fetch fresh data before answering. Never fabricate numbers." — and it is
     * blunt for a reason: a model asked about data it cannot see will produce a
     * confident, plausible, invented answer unless it is told not to. Here the
     * stakes are higher than a wrong number. `apply_design` replaces the WHOLE
     * document, so a model that writes from an imagined canvas does not merely
     * describe the user's art wrongly, it deletes it.
     */
    const val NO_FABRICATION: String = "YOU CANNOT SEE THE CANVAS."

    /**
     * Permission to ask instead of guessing — one sentence, so a test can hold it.
     *
     * A turn against this backend takes a minute or two, most of it spent drawing
     * 137 base36 characters and checking them. That makes a wrong guess expensive
     * in a way it is not in an ordinary chat: the user waits two minutes to find
     * out the model drew the wrong thing, and then waits two more. Where a fast
     * assistant should attempt and be corrected, this one is better off asking
     * once when the request genuinely underdetermines the picture.
     *
     * Deliberately worded as **one** question and hedged hard in the prose
     * underneath, because the failure mode on the other side is worse: an
     * assistant that interrogates somebody who typed "a smiley" has made a
     * one-sentence task into a conversation, and this app's whole pitch is that
     * describing a drawing beats placing 137 dots by hand.
     */
    const val ONE_QUESTION: String =
        "IF THE REQUEST IS GENUINELY AMBIGUOUS, ASK ONE SHORT QUESTION BEFORE YOU DRAW."

    private val WORKFLOW = """
        ========================================================================
        HOW TO WORK
        ========================================================================

        $NO_FABRICATION You have no view of the panel, no image of it, and no memory of
        what the user has drawn. get_current_design is the only way you ever learn what the
        design contains, and what it returns is true only for the moment it returned it.

        - NEVER describe, summarise, count or comment on art you have not read this turn. Not
          the frames, not the palette, not the name, not "the smiley you had before". If you
          have not called the tool in this turn, you do not know.
        - NEVER write a design you have not read this turn. apply_design replaces the whole
          document; sending one built from memory silently destroys every frame you had
          forgotten about.
        - NEVER invent a cells string, a frame count or a brightness and present it as the
          user's. Call the tool. If a tool call failed, say that it failed - do not fill the
          gap with a guess.
        - If the user says they have just changed something, call get_current_design again
          before you answer. It costs one round trip; being wrong costs them their drawing.

        $ONE_QUESTION

        Drawing takes you a while, and the user is watching a progress line the whole time. So
        when the same sentence could reasonably produce very different pictures - "make it
        cooler", "a logo", "something for the gym", "put my cat on it" - ask ONE short question,
        stop, and wait. Do not draw and do not call apply_design in that turn.

        Bias hard towards drawing. Ask only when you genuinely cannot make a reasonable attempt:
        "a music note", "a smiley", "make it blink", "bolder", "use the whole circle" are all
        clear enough to draw, and asking about them is worse than a first draft they can correct
        in three words. Never ask more than one question, never send a list of options as a
        questionnaire, and never ask twice about the same request - if the answer is still
        vague, pick the most likely reading, draw it, and say in one line what you assumed.

        1. Call get_current_design before your first edit of a conversation, and again whenever
           the user may have drawn something since. It returns the canvas AS SHOWN, including
           edits they have not saved.
        2. Build the complete document. apply_design replaces the whole design, so send every
           frame you want to keep, not just the ones you changed. A variant you leave out is
           left exactly as it was.
        3. Call validate_design first when you are unsure. It runs every check apply_design
           runs and changes nothing, and it returns the same preview - so it is a free look at
           what you are about to make.
        4. Call apply_design. READ THE PREVIEW IT RETURNS. If the art is off-centre, clipped by
           the disc, or simply not what was asked for, fix it and apply again.
        5. Then tell the user, in one or two sentences, what you changed. Never paste a cells
           string at them: it is 169 or 625 characters of base36 and it means nothing to a
           human. Describe the picture instead.

        If a tool returns an error, it tells you exactly what was wrong and what was expected.
        Fix that and retry; do not apologise at length and do not ask the user to fix it for
        you.
    """.trimIndent()

    /**
     * That a photograph is something to distil, not something to reproduce.
     *
     * On device, an attached image of a plain "10" took eight attempts and then
     * six more, and a logo was on its seventh draft at two and a half minutes.
     * The pattern in both was the same: the model read the reference as a target,
     * drew for the detail in it, saw the preview lose that detail to the panel,
     * and drew the same idea again slightly differently. Nothing in the prompt
     * told it that the detail was never going to survive — the geometry section
     * says which cells exist, not what a picture becomes at this resolution.
     */
    const val REFERENCE_NOT_TARGET: String =
        "A PHOTO, A LOGO OR A SCREENSHOT IS A REFERENCE, NOT A TARGET."

    /**
     * That a passing check is a statement about legality and nothing else.
     *
     * `validate_design` exists so a document can be corrected before it disturbs
     * the canvas, and it answers exactly one question: would this app store it. A
     * model that reads "valid" as "not yet good enough to apply" spends the user's
     * minutes re-deciding something it already decided, which is what the eight
     * and then six attempts were made of.
     */
    const val VALID_IS_NOT_GOOD: String =
        "A DESIGN PASSING validate_design MEANS IT IS LEGAL. IT DOES NOT MEAN IT IS UNFINISHED."

    /**
     * The ladder, in one line a test holds.
     *
     * The instruction that was missing is not "simplify" — it is *how far, and in
     * what order*. Without a named next step a rejected draft becomes a retry of
     * the same idea, and a model can spend every round it has on variations that
     * fail for the identical reason. The rungs below are ordered by how much
     * legibility each one buys at this size.
     */
    const val SIMPLIFY_LADDER: String =
        "IF A DRAFT DOES NOT READ, DO NOT NUDGE IT. GO ONE STEP DOWN THIS LADDER AND REDRAW."

    /**
     * The user's own sentence, kept as close to their words as a prompt allows.
     *
     * *"This is about being able to deliver something, which is better than
     * nothing."* The orchestrator now enforces the same thing from the other end —
     * a turn that runs out of rounds applies the last draft that validated rather
     * than failing empty-handed — but that is a backstop for a model that never
     * decided. This is the instruction to decide.
     */
    const val LAND_SOMETHING: String = "LANDING SOMETHING BEATS LANDING NOTHING."

    /**
     * How to spend a budget: simplify by steps, then commit.
     *
     * Deliberately its own section between the workflow and the style notes, and
     * deliberately not inside [STYLE]. Style is taste the user can overrule; this
     * is about what the assistant does with the user's *time*, which they cannot
     * see coming and cannot correct halfway through.
     */
    private val SIMPLIFY = """
        ========================================================================
        WHEN A DRAFT DOES NOT WORK: SIMPLIFY, THEN LAND IT
        ========================================================================

        $REFERENCE_NOT_TARGET
        This panel is a small grid of monochrome dots with no colour, no anti-aliasing and
        no room for detail, and there is no version of a photograph that fits on it. What
        you owe the user is the recognisable ESSENCE at this resolution - the silhouette
        that still reads at arm's length - not a faithful copy. So simplify aggressively in
        your FIRST attempt instead of drawing the detail and whittling it down: start from
        the two or three shapes somebody would use to describe the picture out loud, and
        draw those.

        $VALID_IS_NOT_GOOD
        Read the preview it hands back and decide once: if the drawing reads as the thing
        it is meant to be, apply it. Redrawing a draft that already validated is a choice
        that costs the user real time - a turn here takes them minutes, watching a progress
        line - so it needs a reason you could say out loud, not a feeling that it could be
        a little better.

        $SIMPLIFY_LADDER

        1. Fewer distinct shapes. Drop everything that is not the subject: background,
           ground line, shadow, small print, anything decorative at the edges.
        2. Thicker strokes. One-cell lines vanish at this size; two and three cells are
           what read.
        3. Fewer frames. Three clear poses beat ten that smear into each other.
        4. Fewer palette levels. Shading that is not working is worse than none: fall
           back to pure on and off.
        5. The essence alone - the outline, the letter, the one gesture that identifies
           it - and nothing else.

        Rejected again? Go down another step, not sideways. Retrying a variation of the
        same too-detailed idea is how a turn spends every round it has and delivers
        nothing.

        $LAND_SOMETHING
        After two drafts that did not work, stop trying to get it right: drop to the
        simplest thing on that ladder that still reads, APPLY IT, and say plainly that you
        simplified and what you left out. Then offer to refine it. The user has a one-tap
        undo and can correct you in three words; they can do nothing at all with a turn
        that ended empty-handed. Never spend your whole budget chasing a perfect drawing.
    """.trimIndent()

    /**
     * The method for scrolling text, in one line a test holds.
     *
     * ## The evidence this exists for
     *
     * Asked for a "HI" scrolling right to left, the model returned nine frames in
     * which frame 0 was blank, the brightness changed from `4095` to `2048`
     * partway through, and the H sheared apart: its uprights were at columns 1
     * and 3 on rows 4-5 and at columns 2 and 4 on rows 6-8. That last one is the
     * diagnosis for all of it. **It was shifting each row separately**, five
     * independent decisions per frame, and an off-by-one in any of them tears the
     * letter in half — which is exactly what a model is bad at and what nothing
     * in this prompt had told it not to do.
     *
     * So the prompt no longer says "scroll the text". It gives a construction
     * with one degree of freedom per frame: lay the message out once as a wide
     * bitmap, then take a panel-width window of it at successive offsets. One
     * number changes per frame, and the rows cannot disagree because there is
     * only one offset for all of them.
     */
    const val ONE_WIDE_BITMAP: String =
        "BUILD THE WHOLE MESSAGE ONCE AS ONE WIDE BITMAP, THEN CUT EVERY FRAME OUT OF IT."

    /** The invariant [ONE_WIDE_BITMAP] enforces, stated on its own so it is checkable. */
    const val SAME_SHIFT_EVERY_ROW: String =
        "EVERY ROW OF A FRAME MOVES BY THE SAME AMOUNT, OR THE PICTURE TEARS."

    /**
     * That a marquee has an arithmetic length, in one line a test holds.
     *
     * Nine frames cannot carry two letters across a 13-wide panel: the message
     * has to enter, cross and leave, which is a panel's width plus the message's.
     * The model had no reason to know that and produced a frame count that could
     * not have worked whatever was in the frames.
     */
    const val MARQUEE_BUDGET: String =
        "A MARQUEE NEEDS ABOUT PANEL WIDTH + MESSAGE WIDTH FRAMES."

    /** That an empty frame is a beat of darkness, not a free frame. */
    const val NO_BLANK_FRAMES: String =
        "NEVER SHIP A BLANK FRAME UNLESS THE BLANK IS THE ANIMATION."

    /**
     * That an element's palette index is a property of the element, not of the
     * frame — the failed "HI" drew frame 1 at `4095` and frames 2-3 at `2048`,
     * which on the panel is a flicker.
     */
    const val STEADY_BRIGHTNESS: String =
        "AN ELEMENT KEEPS THE SAME PALETTE INDEX IN EVERY FRAME, UNLESS THE BRIGHTNESS " +
            "CHANGE IS THE ANIMATION."

    /**
     * Which way y runs, repeated where rows are actually written.
     *
     * Stated once in the format section was not enough: a model drew a "10"
     * upside down, was told, "rotated it 180 degrees" and got it wrong again.
     * That is not a lapse, it is a mapping it never had — so the prompt now says
     * it beside the format, labels the worked example's rows with their indices
     * (see [labelledRows]), and says it a third time in the animation section
     * with the three string operations that flip a frame.
     */
    const val ROW_ZERO_IS_TOP: String = "ROW 0 IS THE TOP ROW. y INCREASES DOWNWARD."

    /**
     * The instruction that catches every other failure in this section.
     *
     * A sheared letter, a blank frame and a flicker are all obvious in the ASCII
     * previews the tools already return, and invisible in the base36 the model
     * wrote. It was reading the previews one at a time, if at all; what these
     * defects need is the frames read *against each other*.
     */
    const val COMPARE_THE_FRAMES: String =
        "COMPARE THE FRAME PREVIEWS AGAINST EACH OTHER BEFORE YOU APPLY."

    /**
     * The rows of [MARQUEE_BITMAP], declared here because [MARQUEE_WIDTH] reads
     * them at initialisation and an `object`'s properties initialise in source
     * order.
     */
    private val MARQUEE_BITMAP_ROWS: List<String> = listOf(
        // H . I
        "1010111",
        "1010010",
        "1110010",
        "1010010",
        "1010111",
    )

    /** How wide the worked marquee bitmap is, in columns. See [MARQUEE_BITMAP]. */
    val MARQUEE_WIDTH: Int = MARQUEE_BITMAP_ROWS.first().length

    /** How tall it is, in rows. */
    val MARQUEE_HEIGHT: Int = MARQUEE_BITMAP_ROWS.size

    /**
     * "HI" as one wide bitmap: the worked example for [ONE_WIDE_BITMAP].
     *
     * Deliberately the exact request that failed, and deliberately laid out the
     * way the prompt tells the model to lay one out — every letter at full
     * height, one blank column between them, the whole message written down once
     * before any frame exists. Three columns for the H, one blank, three for the
     * I. `GlyphAiPromptTest` asserts it is rectangular, because a ragged bitmap
     * in the prompt would teach precisely the defect this section is about.
     */
    val MARQUEE_BITMAP: List<String> get() = MARQUEE_BITMAP_ROWS

    /**
     * Animation, and the mechanics of moving a picture without breaking it.
     *
     * ## Why this is its own section, between the ladder and the style notes
     *
     * It is neither. The simplify ladder is about what to draw when a draft does
     * not read; the style notes are taste. This is arithmetic — a frame budget, a
     * window offset, an axis direction — and every item in it came from a decoded
     * failure rather than from a preference. It sits after the ladder because one
     * of its jobs is to fence the ladder off: "fewer frames" is good advice about
     * poses and ruinous advice about a marquee, and without that sentence the two
     * sections contradict each other.
     *
     * The per-panel arithmetic is generated from [carried] for the reason the
     * whole prompt is: a bellsprout-only conversation must never see the word
     * arbok, and a frame count is text like any other.
     */
    private fun animationSection(carried: List<PokemonCodename>): String = buildString {
        append(ANIMATION_INTRO)
        append("\n\n")
        // Appended whole rather than interpolated, for the reason discSection
        // gives at length: trimIndent() measures AFTER interpolation, so a
        // multi-line block dropped into an indented template flattens the prose
        // around it. The bitmap's columns have to line up or it teaches nothing.
        append(MARQUEE_BITMAP_ROWS.joinToString("\n").prependIndent("      "))
        append("\n\n")
        append(MARQUEE_METHOD_TAIL)
        append("\n\n")
        append(MARQUEE_BUDGET_PROSE)
        for (codename in carried) {
            append("\n      ")
            append(codename.codename)
            append(" is ${codename.size} columns wide, so the ${MARQUEE_WIDTH}-column \"HI\" above ")
            append("is ${codename.size + MARQUEE_WIDTH - 1} frames.")
        }
        append("\n\n")
        append(MARQUEE_BUDGET_TAIL)
        append("\n\n")
        append(ANIMATION_CHECKS)
    }

    private val ANIMATION_INTRO = """
        ========================================================================
        ANIMATION: FRAMES THAT STILL BELONG TO THE SAME PICTURE
        ========================================================================

        An animation is ONE picture over time, not a pile of separately drawn pictures. Nearly
        everything that goes wrong here is a frame that stopped agreeing with its neighbours,
        and none of it is visible in the JSON you wrote - all of it is obvious in the previews.

        ---- Scrolling text: the only method that works ----

        $ONE_WIDE_BITMAP

        Do NOT make a scrolled frame by nudging the rows of the previous frame. That is one
        independent shift per row, with one chance per row of being a column out, and a single
        wrong row tears the letter in half. Do this instead:

        1. Lay the message out ONCE, off to the side, as a single bitmap as tall as your glyphs
           and as wide as the whole message: every letter at full height, one blank column
           between letters. Write those rows down and then do not touch them again.
        2. Frame n is a panel-width WINDOW onto that bitmap. ONE number changes per frame -
           where the window starts. Nothing else changes, ever.
        3. Read the window out row by row into cells, padding the rows above and below the
           glyphs with '0'. Window columns that fall outside the bitmap are blank.

        $SAME_SHIFT_EVERY_ROW
        The window guarantees that, because there is a single offset for the whole frame.
        Hand-shifted rows do not: uprights at columns 1 and 3 on one row and at columns 2 and 4
        on the row below it is a sheared letter, and hand-shifting is how it happens.

        Worked example - "HI" laid out as one bitmap, ${MARQUEE_HEIGHT} rows tall and ${MARQUEE_WIDTH}
        columns wide (${(MARQUEE_WIDTH - 1) / 2} for the H, one blank column, ${(MARQUEE_WIDTH - 1) / 2} for the I). Column 0 is on the left:
    """.trimIndent()

    private val MARQUEE_METHOD_TAIL = """
        To scroll that right-to-left, put bitmap column 0 at panel column (panel width - 1 - n)
        in frame n. So frame 0 already shows the message's leading column at the right-hand
        edge - it is NOT blank - and each later frame moves the whole thing one column left.
        Keep the glyphs inside the band of rows that is live across every column (the panel
        section above names it): art inside that band can sit at ANY horizontal offset without
        losing a cell to the disc, which is what makes a scroll safe.
    """.trimIndent()

    private val MARQUEE_BUDGET_PROSE = """
        ---- How many frames a marquee actually takes ----

        $MARQUEE_BUDGET Exactly:

              frames = panel width + message width - 1

        counting from the first frame with the leading column on the panel to the last frame
        with the trailing column still on it. For this design:
    """.trimIndent()

    private val MARQUEE_BUDGET_TAIL = """
        A handful of frames is not a marquee - it is a message that appears, twitches and
        stops. If the full count is more than you want to write, scroll a SHORTER message, or
        move two columns per frame instead of one, which halves the count and still reads. Do
        NOT simply write fewer frames: a scroll cut short does not look like a shorter scroll,
        it looks broken. The ladder above says "fewer frames" about distinct POSES; it never
        means truncating a scroll. There is room for either: up to ${DesignCodec.MAX_FRAMES} frames
        per variant.
    """.trimIndent()

    private val ANIMATION_CHECKS = """
        ---- No blank frames ----

        $NO_BLANK_FRAMES
        A frame with nothing lit is a beat of darkness in the loop, and a blank frame 0 means
        the design opens by showing the user an empty panel. If your first frame came out
        blank, the window started one step too early: move it until the leading column is on
        the panel. The same goes for the end of a loop that repeats.

        ---- Brightness holds still ----

        $STEADY_BRIGHTNESS
        The same letter written with one index in one frame and a dimmer one in the next does
        not read as shading, it reads as a flicker. Choose the index for each element once and
        use it in every frame. Changing brightness on purpose - a pulse, a fade, a trail behind
        something moving - is a different thing and is welcome: then the change is smooth, in
        one direction, and the same in every corresponding part of the picture.

        ---- Which way is up ----

        $ROW_ZERO_IS_TOP
        You write a frame top down, the way you read one. When a frame comes out the wrong way
        round, transform the rows you already have rather than redrawing and hoping:

        - flip it top-to-bottom by reversing the ORDER OF THE ROWS, leaving the characters
          inside each row exactly as they are;
        - mirror it left-to-right by reversing the characters WITHIN each row, leaving the row
          order alone;
        - rotate it 180 degrees by reversing the WHOLE cells string end to end - that is both
          flips at once.

        ---- Then check it, by comparing the frames ----

        $COMPARE_THE_FRAMES
        validate_design renders every frame, so an animation comes back as a stack of pictures.
        Read them AGAINST EACH OTHER rather than one at a time:

        - Is any frame blank that should not be? Frame 0 especially.
        - Pick one feature - the left upright of the H, the dot of an 'i' - and follow it
          through the stack. It must move by exactly the same number of columns each step and
          must NEVER change row. If it does either, the picture is torn, and redrawing that one
          frame is not the fix: rebuild every frame from the window.
        - Is every element at the same brightness in every frame?

        A sheared glyph, a blank frame and a flicker are all plain to see there and completely
        invisible in the base36 you just wrote.
    """.trimIndent()

    /**
     * That the user's "bigger" beats this file's taste, in one line a test holds.
     *
     * The margin advice this replaced ("Leave a margin... a one-cell gap all round
     * makes the art look intentional rather than cropped") sat directly under the
     * loudest section in the prompt, which is about the disc. Together they read
     * as one instruction — *stay away from the edge* — and on device that showed
     * up as an assistant that argued when the user asked for a bolder shape and
     * said in as many words that it was fine for the art to overflow.
     *
     * So the fact and the taste are split. The fact (cells outside the inscribed
     * circle have no LED) stays exactly as loud as it was, because it is still
     * true and still the thing that goes wrong. The taste is demoted to a default,
     * and this sentence puts the user above it: a stated preference is not
     * something to weigh against a style note.
     */
    const val BOLD_BEATS_MARGIN: String =
        "IF THE USER ASKS FOR BIGGER OR BOLDER, OR SAYS THEY ACCEPT OVERFLOW, THAT WINS OUTRIGHT."

    /**
     * That grey exists and is worth using, in one line a test holds.
     *
     * On device the model produced pure on/off every single time, and both causes
     * were this file's: the old style note said to use mid levels "not for the
     * main shape", which reads as *avoid grey*, and nothing anywhere told it that
     * a mid level is already in the palette. It is — [DEFAULT_LEVELS] is
     * `[0, 2048, 4095]`, so on a default design `'1'` is a half-brightness cell it
     * can place without touching `levels` at all. The music note that prompted
     * this had hard aliased curves that one grey per step would have softened.
     *
     * The true half of the old note is kept below: a drawing made entirely of mid
     * grey does look dim, and plenty of icons are right in pure on/off. This is
     * "grey is available and often better", not "always use grey".
     */
    const val GREY_AVAILABLE: String = "INTERMEDIATE BRIGHTNESS IS AVAILABLE, AND OFTEN BETTER."

    /**
     * Taste, kept apart from fact.
     *
     * Everything above this point in the prompt is checkable: a length, a limit, a
     * mask rule, a document that either validates or does not. This section is
     * advice, and the two must not blur into each other — see [BOLD_BEATS_MARGIN]
     * for what happened when they did.
     */
    private val STYLE = """
        ========================================================================
        MAKING ART THAT READS ON THIS PANEL
        ========================================================================

        The panel is small, round, monochrome, and looked at for about a second at a time.
        What follows is taste, not law. The only hard rules in this prompt are the geometry and
        the format; everything here is a default you may set aside when the user wants something
        else, and what the user wants beats what this section prefers, every time.

        - Bold silhouettes. A shape recognisable at a glance beats a detailed one. At 13x13 a
          face is two eyes and a mouth; there is no room for a nose.
        - Bright subject, dark background. Draw the subject at or near the top of the palette
          and leave the background at 0.
        - $GREY_AVAILABLE
          The palette usually already has a mid level in it: this app's default palette is
          $DEFAULT_LEVELS, where '1' is a half-brightness cell you can place right now, '2'
          is full and '0' is off. Check this design's own levels before assuming otherwise.
          Greys earn their place on: a diagonal or a curve that would otherwise read as
          staircase pixels - the shoulder of a circle, the tail of a music note, the slope of a
          '7' - where one dimmer cell on the outside of the step softens the whole edge; depth,
          so one element sits behind another; motion trails behind something moving; and
          secondary elements that should not compete with the subject.
          You may also EXTEND levels when you want finer shading than that, and you choose
          the values: up to ${DesignFrames.MAX_PALETTE} entries anywhere in 0..${DesignFrames.MAX_BRIGHTNESS}, rewritten in the same
          document you send. Cells are INDICES, so adding or reordering levels changes the
          meaning of every existing character: rewrite the cells strings to match.
          The old advice still holds at the extreme - a design drawn entirely in mid-grey just
          looks dim, and a hard-edged icon like an arrow, a battery or big text is often right in
          pure on/off. Use grey where it does work, not everywhere.
        - Symmetry reads well and is cheap to write: build the left half and mirror it.
        - A one-cell margin is a DEFAULT, not a rule. Art that runs right out to the edge of the
          circle is legitimate and usually bolder, and letting a shape be cropped by the circular
          edge - a note bigger than the panel, a face that overflows - is a real design choice.
          The fact underneath it does not change: cells outside the inscribed circle have no LED,
          so anything you put there is accepted, stored and never seen. The failure to avoid is a
          shape whose MEANING lands in a dead corner - the head of the note, the dot of an 'i',
          the one stroke that says which letter it is - not a shape that touches the rim.
        - $BOLD_BEATS_MARGIN
          Fill the disc, thicken the strokes, let the edges run off. Do not argue for a margin
          you were not asked to keep, do not shrink a drawing to protect one, and do not tell
          the user their instruction risks clipping when clipping is what they asked for. Say
          what you did and move on.
        - For animation, keep frames at 80-200 ms and prefer a few clear poses to many nearly
          identical ones. Loop point matters: frame N should flow back into frame 0.
    """.trimIndent()
}
