package space.linuxct.glyphmatrixtoycompat.core.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import space.linuxct.glyphmatrixtoycompat.core.design.DEFAULT_FRAME_DURATION_MS
import space.linuxct.glyphmatrixtoycompat.core.design.DESIGN_FORMAT
import space.linuxct.glyphmatrixtoycompat.core.design.Design
import space.linuxct.glyphmatrixtoycompat.core.design.DesignCodec
import space.linuxct.glyphmatrixtoycompat.core.design.DesignFrame
import space.linuxct.glyphmatrixtoycompat.core.design.DesignFrames
import space.linuxct.glyphmatrixtoycompat.core.design.DesignKind
import space.linuxct.glyphmatrixtoycompat.core.design.DesignVariant
import space.linuxct.glyphmatrixtoycompat.core.design.KeyMode
import space.linuxct.glyphmatrixtoycompat.core.design.MarqueeFont
import space.linuxct.glyphmatrixtoycompat.core.design.MarqueeText
import space.linuxct.glyphmatrixtoycompat.core.design.PokemonCodename
import space.linuxct.glyphmatrixtoycompat.matrix.PanelMask

/**
 * The editor state one tool call is answered from.
 *
 * A **snapshot supplied by the caller**, not a handle on the editor: nothing in
 * `core/` may reach into `ui/`, and making the tools pure functions of
 * `(arguments, context)` is what lets every rule in this file be tested without
 * an Android runtime. The orchestrator builds a fresh context per round, which
 * is also how "the design *as shown*, unsaved edits included" is honoured — the
 * caller passes `EditorState.composed()`, so what the model reads is the canvas,
 * not the last thing written to disk.
 */
data class GlyphToolContext(
    /** The design as currently shown, including edits the user has not saved. */
    val design: Design,
    /** The geometry on screen, or null if the editor has none open. */
    val openVariant: PokemonCodename? = null,
    /** Which frame of [openVariant] the timeline has selected. */
    val selectedFrameIndex: Int = 0,
    /**
     * The photos riding on **this turn's** message, in the order the user
     * attached them, as brightness grids.
     *
     * The pixels themselves are Android's — `BitmapFactory`, an EXIF tag, a
     * `content://` URI — and none of that may appear in `core/`. So the decode
     * stays in `ai/ImageAttachments`, which already does it to build the data
     * URL, and hands the result across this seam as a [SourceImage]: two
     * integers and an `IntArray`. Everything downstream of here — the framing,
     * the contrast, the threshold, the mask — is [ImageQuantiser], which is pure
     * and tested under plain JUnit.
     *
     * Deliberately per-turn rather than per-conversation. An attachment is sent
     * with one message and is not stored (see `ChatMessage.imageCount`), so a
     * photo from three turns ago is genuinely not available and pretending
     * otherwise would have `image_to_grid` convert the wrong picture.
     */
    val images: List<SourceImage> = emptyList(),
) {
    /**
     * The geometries this conversation may read and write: exactly the ones the
     * design **carries**.
     *
     * The same expression as `DesignEditorActivity.EditorState.variantsPresent`,
     * and deliberately *not* "the variant currently open". With both panels
     * present the model may rewrite `arbok` while the user is looking at
     * `bellsprout`; with only `bellsprout` present it may not so much as read
     * `arbok`. Adding a geometry to a design is a decision the user takes in the
     * editor — a model that could add one could quietly double the size of
     * somebody's file.
     */
    val allowedVariants: List<PokemonCodename>
        get() = PokemonCodename.entries.filter { design.variantFor(it) != null }
}

/**
 * What one tool call produced: the JSON the model is shown, and — for
 * `apply_design` alone — the validated design the caller should put on the
 * canvas.
 *
 * [design] is the entire mechanism by which a tool changes anything. Nothing in
 * `core/ai/` mutates state: `apply_design` hands back a [Design] that
 * [DesignCodec] has already accepted, and the caller owns the decision to apply
 * it. `validate_design` therefore returns null here **by construction** rather
 * than by promise — there is no value for a caller to accidentally apply.
 *
 * The caller must apply [design] *before* returning [json] to the model, since
 * [json] says the change was made. If applying fails, replace the result with an
 * error rather than reporting a success that did not happen.
 */
data class GlyphToolResult(
    val json: String,
    val isError: Boolean = false,
    val design: Design? = null,
    /**
     * A document that passed every check but that **nothing is being asked to
     * apply** — `validate_design`'s output, and only ever that.
     *
     * A second field rather than a flag on [design] precisely so the paragraph
     * above stays true: a caller that applies [design] cannot accidentally apply a
     * dry run, because a dry run still puts nothing there.
     *
     * It exists because a turn can otherwise end with nothing to show for itself.
     * A model that validates a good draft, decides it can do better and redraws
     * until the round budget is gone leaves the user with an error message,
     * despite legal artwork having existed several rounds earlier. This is that
     * artwork, kept so [GlyphAiOrchestrator] can fall back on it rather than fail
     * empty-handed.
     */
    val validated: Design? = null,
)

/**
 * One callable tool: its name, the schema advertised to the model, and the
 * function that answers a call.
 *
 * Mirrors `pulseloop/coach/tools/CoachTool.kt`, minus `suspend`: every tool here
 * is pure computation over a snapshot, so making callers hop a coroutine
 * boundary would buy nothing. A tool that needed I/O would be a tool that did
 * not belong in `core/`.
 */
data class GlyphTool(
    val name: String,
    val specJson: String,
    val run: (arguments: String, ctx: GlyphToolContext) -> GlyphToolResult,
)

/**
 * The design assistant's tools: read the canvas, check a document, write it back
 * — and, for the one job a model cannot do by hand, do the arithmetic for it.
 *
 * ## Some tools take a decision; some take a calculation
 *
 * `get_current_design`, `validate_design` and `apply_design` are all the same
 * shape: the model decides what the art should be and this object says whether
 * the app will have it. [scrollFrames] and [imageToGrid] are the exceptions and
 * are here for the opposite reason. Nothing was wrong with the model's
 * *judgement* about a marquee; what it could not do was hold 5 rows x 19 frames
 * x 169 characters mutually consistent with no error signal. Nothing is wrong
 * with its judgement about a photograph either; what it cannot do is tell you
 * what a JPEG averages to at cell (7, 4). Both of those moved into Kotlin, where
 * they are loops. See [scrollFrames] for the decoded failure that put the first
 * one there.
 *
 * [marqueeText] is the same argument taken one step further, and is the answer
 * to the half of a marquee [scrollFrames] left behind. Windowing is arithmetic
 * and moved; drawing a nine-row alphabet is not arithmetic, but it is *settled*
 * — an `S` has one right shape and re-deriving it per request can only lose. So
 * the letterforms moved too, and what stays with the model is the phrase.
 *
 * [setFrames] is a third shape again: a decision the model still takes, made
 * *cheap*. `apply_design` replaces the whole document, so changing frame 7 of a
 * 240-frame arbok animation meant re-sending ~150 kB of base36 — slow, expensive
 * and, worse, a fresh chance to corrupt a frame that was already right.
 *
 * ## Nothing here throws
 *
 * Every input to this object is language-model output, which means every input
 * is malformed sooner or later: arguments that are not JSON, a `cells` string
 * one character short, `'!'` where a base36 digit belongs, a palette index past
 * the end of `levels`, 300 frames, a `durationMs` of 5, a variant this design
 * does not carry. **All of those are results, not exceptions.** An exception
 * would surface to the user as "something went wrong" and end the turn; a result
 * goes back to the model, which reads what was wrong and what was expected and
 * fixes it on the next round. That is the whole difference between an assistant
 * that recovers and one that gives up, so error messages here are written for a
 * reader who must act on them: what was wrong, where, and what was expected
 * instead.
 *
 * ## Every result carries a picture
 *
 * A model writing base36 cannot see its own output. [GlyphAsciiPreview] renders
 * each frame back with the disc mask applied, and that rendering rides along
 * with every success — it is the feedback loop that made this project's
 * hand-authored designs work, and without it the model is drawing blindfolded.
 *
 * ## Variant gating
 *
 * [GlyphToolContext.allowedVariants] is the closed set. A rejection always names
 * it, because a model that is merely refused will try again the same way, while
 * a model told "you may only write bellsprout" will write bellsprout.
 */
object GlyphAiTools {

    const val GET_CURRENT_DESIGN = "get_current_design"
    const val APPLY_DESIGN = "apply_design"
    const val VALIDATE_DESIGN = "validate_design"
    const val SCROLL_FRAMES = "scroll_frames"
    const val MARQUEE_TEXT = "marquee_text"
    const val IMAGE_TO_GRID = "image_to_grid"
    const val SET_FRAMES = "set_frames"

    /** The argument every writing tool takes: a whole `glyph.design` document. */
    const val ARG_DESIGN = "design"

    /** Which panel to work on. Shared by every tool that touches one variant. */
    const val ARG_VARIANT = "variant"

    // [SCROLL_FRAMES]'s arguments. Only [ARG_SOURCE_ROWS] carries a decision the
    // model has to make; every other one may be null and takes a default that is
    // chosen to make one of this tool's guarantees true.
    const val ARG_SOURCE_ROWS = "source_rows"
    const val ARG_TOP_ROW = "top_row"
    const val ARG_START_COLUMN = "start_column"
    const val ARG_STEP = "step"
    const val ARG_FRAMES = "frames"
    const val ARG_DURATION_MS = "duration_ms"

    /**
     * [MARQUEE_TEXT]'s phrase — the only argument it has that carries a
     * decision. It shares [ARG_VARIANT], [ARG_STEP] and [ARG_DURATION_MS] with
     * [SCROLL_FRAMES] on purpose: they mean exactly the same things, and a
     * second spelling of "columns per frame" would be a second thing to learn.
     */
    const val ARG_TEXT = "text"

    /** How many panel cells one glyph cell becomes. See [MarqueeText.scaleFor]. */
    const val ARG_SCALE = "scale"

    /**
     * Which palette entry the letters are lit at.
     *
     * [SCROLL_FRAMES] has no equivalent because there the model draws the source
     * bitmap and picks the index cell by cell. Here the app draws the letters,
     * so the choice has to be an argument or it would be the app's taste.
     */
    const val ARG_PALETTE_INDEX = "palette_index"

    // [IMAGE_TO_GRID]'s arguments. The three knobs are the whole of what the
    // model may steer, and each of them is here because the conversion has one
    // honest answer the app cannot know: which picture, where the light/dark cut
    // goes, how hard to push, and whether the subject is the dark half.
    const val ARG_IMAGE_INDEX = "image_index"
    const val ARG_THRESHOLD = "threshold"
    const val ARG_CONTRAST = "contrast"
    const val ARG_INVERT = "invert"

    // [SET_FRAMES]'s arguments.
    const val ARG_MODE = "mode"
    const val ARG_AT = "at"
    const val ARG_COUNT = "count"

    /**
     * [SET_FRAMES]' array of new frames.
     *
     * Spelled `frames` like [ARG_FRAMES], and meaning something else — a list
     * there, a count here. They live in different schemas, so nothing can
     * confuse them mechanically, and calling this anything but `frames` would be
     * the more confusing choice: it is a list of frames.
     */
    const val ARG_FRAME_LIST = "frames"

    /** The three things [SET_FRAMES] can do to a range. */
    const val MODE_REPLACE = "replace"
    const val MODE_INSERT = "insert"
    const val MODE_DELETE = "delete"

    /** The key [SCROLL_FRAMES] and [IMAGE_TO_GRID] return a ready-to-apply document under. */
    const val KEY_APPLY_THIS = "apply_this"

    /**
     * How many frames of a variant get an ASCII rendering in one tool result.
     *
     * Previews exist to be *read*, and a 240-frame `arbok` design would render
     * as 6 000 lines — a payload that pushes the conversation out of context and
     * that no model will study frame by frame anyway. Every design a person
     * draws by hand is well under this, so in practice the cap never fires; when
     * it does, the result says so explicitly (`previews_truncated`) rather than
     * letting the model believe it has seen everything.
     *
     * ## Why it survived [SET_FRAMES], which was expected to remove it
     *
     * The reason it looked removable was that a model editing frame 40 of a
     * 60-frame design could never *see* frame 40 — previews stopped at 16 — and
     * that is a real gap. But the cap is not what causes it: the cap is a budget,
     * and a 240-frame `arbok` design is still 150 000 characters of pictures
     * whether or not there is a tool that writes ranges. Raising it would spend
     * the conversation's context on the frames nobody asked about, in the one
     * situation where there are hundreds of them.
     *
     * What [setFrames] changes is that the frames past the cap became
     * *addressable*: it renders the range it wrote and the frames either side of
     * the join, so frame 200 is now visible — by being written, which is the only
     * time anybody was looking at it. The cap stays; the blind spot does not.
     */
    const val MAX_PREVIEW_FRAMES = 16

    /**
     * How many frames of a *scroll* get a rendering. Higher than
     * [MAX_PREVIEW_FRAMES] on purpose.
     *
     * A marquee's frame count is arithmetic rather than taste — a 7-column
     * message across a 13-wide panel is 19 frames, and that is the shortest
     * honest version of it — so the general cap would truncate the previews of
     * the very case this tool exists for, at exactly the moment the prompt is
     * telling the model to read the frames *against each other*. It is still a
     * cap, because a 240-frame scroll of arbok would be 150 000 characters of
     * pictures, and the result says so when it fires.
     */
    const val MAX_SCROLL_PREVIEW_FRAMES = 24

    /**
     * Every tool, in the order the model should meet them: read the canvas,
     * build the three things that cannot be built by hand, change a range of
     * frames, check a whole document, write one.
     *
     * [MARQUEE_TEXT] is listed before [SCROLL_FRAMES] deliberately. They overlap
     * — a marquee is a scroll — and the overlap has one right answer: if the
     * thing scrolling is *words*, the app's own letterforms beat any the model
     * draws, so the specialised tool should be the one met first.
     */
    fun build(): List<GlyphTool> = listOf(
        GlyphTool(GET_CURRENT_DESIGN, SPEC_GET_CURRENT_DESIGN) { _, ctx -> getCurrentDesign(ctx) },
        GlyphTool(IMAGE_TO_GRID, SPEC_IMAGE_TO_GRID) { args, ctx -> imageToGrid(args, ctx) },
        GlyphTool(MARQUEE_TEXT, SPEC_MARQUEE_TEXT) { args, ctx -> marqueeText(args, ctx) },
        GlyphTool(SCROLL_FRAMES, SPEC_SCROLL_FRAMES) { args, ctx -> scrollFrames(args, ctx) },
        GlyphTool(SET_FRAMES, SPEC_SET_FRAMES) { args, ctx -> setFrames(args, ctx) },
        GlyphTool(VALIDATE_DESIGN, SPEC_VALIDATE_DESIGN) { args, ctx -> validateDesign(args, ctx) },
        GlyphTool(APPLY_DESIGN, SPEC_APPLY_DESIGN) { args, ctx -> applyDesign(args, ctx) },
    )

    /**
     * Dispatches a call by name. An unknown name is an error result too: models
     * hallucinate tool names, and the fix is to tell them which ones exist.
     */
    fun run(name: String, arguments: String, ctx: GlyphToolContext): GlyphToolResult {
        val tool = build().firstOrNull { it.name == name }
            ?: return failure(
                "There is no tool called \"$name\".",
            ) {
                putJsonArray("available_tools") { build().forEach { add(it.name) } }
            }
        return tool.run(arguments, ctx)
    }

    // region tools

    /**
     * The canvas as shown: the document's own fields, every carried variant with
     * its cells *and* a rendering of each frame, and the editor context the model
     * needs to make sense of "change this frame".
     */
    private fun getCurrentDesign(ctx: GlyphToolContext): GlyphToolResult {
        val design = ctx.design
        val allowed = ctx.allowedVariants
        return success(
            buildJsonObject {
                put("name", design.name)
                put("kind", kindName(design.kind))
                put("keyMode", keyModeName(design))
                put("loop", design.loop)
                putJsonArray("levels") { design.levels.forEach { add(it) } }
                putJsonObject("editor") {
                    // Null rather than a default: "no variant is open" is a real
                    // state, and inventing one would have the model edit a panel
                    // the user is not looking at while believing otherwise.
                    if (ctx.openVariant != null && allowed.contains(ctx.openVariant)) {
                        put("open_variant", ctx.openVariant.codename)
                    } else {
                        put("open_variant", JsonNull)
                    }
                    put("selected_frame_index", ctx.selectedFrameIndex)
                }
                putJsonArray("allowed_variants") { allowed.forEach { add(it.codename) } }
                put("variants", variantsJson(design, allowed, includeCells = true))
                put("legend", GlyphAsciiPreview.LEGEND)
            },
        )
    }

    /** Every check [applyDesign] makes, guaranteed to change nothing. */
    private fun validateDesign(arguments: String, ctx: GlyphToolContext): GlyphToolResult =
        when (val prepared = prepare(arguments, ctx)) {
            is Prepared.Bad -> prepared.result
            is Prepared.Ok -> success(
                buildJsonObject {
                    put("valid", true)
                    put("applied", false)
                    put(
                        "note",
                        "Nothing was changed. This is what apply_design would produce; " +
                            "check the previews, then call apply_design with the same document.",
                    )
                    putSummary(prepared.design, ctx)
                },
                // Deliberately no design: a dry run must have nothing a caller
                // could apply by mistake. It is reported as *validated* instead —
                // see [GlyphToolResult.validated] — which is a record of what
                // passed, not an instruction to put it anywhere.
                validated = prepared.design,
            )
        }

    /**
     * Validates a whole document and hands the accepted [Design] to the caller.
     *
     * The result claims the change is on the canvas, so the caller applies
     * [GlyphToolResult.design] before showing [GlyphToolResult.json] to the model.
     */
    private fun applyDesign(arguments: String, ctx: GlyphToolContext): GlyphToolResult =
        when (val prepared = prepare(arguments, ctx)) {
            is Prepared.Bad -> prepared.result
            is Prepared.Ok -> success(
                buildJsonObject {
                    put("applied", true)
                    put(
                        "note",
                        "This is on the user's canvas now. Read the previews: if the art is " +
                            "off-centre, clipped by the disc or not what was asked for, fix it and " +
                            "apply again.",
                    )
                    putSummary(prepared.design, ctx)
                },
                design = prepared.design,
            )
        }

    // endregion

    // region scroll_frames

    /**
     * Windows one wide bitmap into a scrolled animation.
     *
     * ## The failure this exists to make impossible
     *
     * Asked for "HI" scrolling right to left, the model produced nine frames in
     * which frame 0 was blank, the brightness fell from full to half partway
     * through, and the H sheared apart — its uprights at columns 1 and 3 on rows
     * 4-5 and at columns 2 and 4 on rows 6-8. Three of those four are decisions,
     * and [GlyphAiPrompt] can and does argue with them. The shear is not: keeping
     * 5 rows x 19 frames x 169 characters mutually consistent is sixteen thousand
     * characters of bookkeeping with no error signal, and a model executing that
     * by hand will be wrong some of the time however it is instructed.
     *
     * So the arithmetic moves here. The model draws **one still picture** — the
     * whole message, once, as a rectangle — and this cuts the frames out of it:
     *
     * - **Shear is unrepresentable.** There is a single `offset` per frame and
     *   every row is read at that same offset. There is no expression in this
     *   function that could move one row and not another.
     * - **A blank frame 0 cannot be the default.** [ARG_START_COLUMN] defaults to
     *   `firstLit - (width - 1)`, which puts the message's first *lit* column on
     *   the panel's right-hand edge in frame 0. An explicit start that does blank
     *   frames is honoured and *reported*, never emitted silently.
     * - **Brightness cannot drift.** Cells are copied out of [ARG_SOURCE_ROWS] as
     *   characters. Nothing in here writes a palette index that was not already
     *   in the source, and the model is handed a finished document to pass on
     *   rather than a picture to transcribe.
     * - **The frame count is not the model's to get wrong.** It is computed, and
     *   the default is the full traverse.
     *
     * ## Why it does not apply
     *
     * It returns [KEY_APPLY_THIS] — a complete document ready for
     * [APPLY_DESIGN] — and hands back no [GlyphToolResult.design]. Two reasons.
     * The pictures are the whole point of this project's feedback loop, and a
     * tool that applied would put nineteen frames on somebody's canvas before
     * anyone had looked at one of them; and the model still has to *decide* about
     * the things this cannot know — whether the message is the right message,
     * whether it should loop, whether the art wanted to be two cells taller. The
     * arithmetic is what was being got wrong, so the arithmetic is what moved.
     * The judgement stays where it was.
     *
     * ## The disc
     *
     * The panel is round, so a glyph is only safe at *every* horizontal offset if
     * it sits in the band of rows that is live across all columns
     * ([GlyphAiPrompt.fullWidthRows]; rows 4-8 at 13x13). [ARG_TOP_ROW] defaults
     * into that band. A caller that puts art outside it still gets its frames —
     * clipping by the rim is a legitimate design choice — but gets a warning
     * with them, because that is the one defect the ASCII of a *single* frame
     * cannot show: the cells are lost as the art moves, not where it starts.
     */
    /**
     * A phrase, in full-height letterforms, scrolling right to left.
     *
     * ## Why this exists when [scrollFrames] already does
     *
     * [scrollFrames] moved the *windowing* into Kotlin and left the drawing with
     * the model, which was the right split for a moving picture and only half of
     * one for text. A nine-row alphabet is thirty-odd characters of judgement per
     * letter, re-derived from nothing on every request, and the failure it
     * produces is not a torn letter — it is a letter that is merely *bad*: an `S`
     * that reads as a `5`, a `G` with no crossbar, a `W` two columns too narrow
     * to be a `W`. Nothing catches that. The previews show it faithfully and it
     * still ships, because it is not wrong enough to look wrong one frame at a
     * time.
     *
     * So the letterforms moved into the app as well ([MarqueeFont]), and what is
     * left for the model is the phrase. That is the whole argument list worth
     * having: [ARG_TEXT] carries a decision, and every other argument may be
     * null.
     *
     * ## Why it does not apply
     *
     * Same shape as [scrollFrames] — it returns [KEY_APPLY_THIS] and no
     * [GlyphToolResult.design] — and for the same two reasons, which are stronger
     * here rather than weaker. The point of the tool is the *letterforms*, so a
     * version that applied would put a hundred frames on somebody's canvas before
     * anyone had read a word of it; and the phrase itself is exactly the thing
     * the app cannot check. A typo, a phrase that wanted to be shorter, a
     * marquee that should have been a static word — all of those are judgements,
     * all are visible in `strip`, and none of them is arithmetic.
     *
     * [setFrames] applies because its whole purpose is to *avoid* re-sending a
     * document; that reasoning does not reach here, because a marquee **is** the
     * document. What comes back is one variant's frames entire, which is
     * precisely the shape [APPLY_DESIGN] takes.
     *
     * ## What it owns and what it leaves alone
     *
     * [KEY_APPLY_THIS] carries `kind`, `loop` and one variant's `frames`, and
     * nothing else. `kind` is `dynamic` because a design still marked static
     * would have [APPLY_DESIGN] refuse every frame but the first; `loop` is true
     * because a marquee that plays once and stops is not a marquee. `keyMode`,
     * `levels`, `name` and the other panel are absent from the document, and
     * [APPLY_DESIGN] treats an absent key as "do not change this" — so a design
     * that carries both geometries keeps the one this did not write.
     *
     * ## The frame budget, and refusing usefully
     *
     * [DesignCodec.MAX_FRAMES] is 240 and a full-height letter is around six
     * columns, so roughly forty characters fit. A bare "too long" would send the
     * model into shortening the phrase a word at a time, so the refusal reports
     * the frames the phrase needs, the longest **prefix of that phrase** that
     * fits (measured with [MarqueeText.maxPrefixLength], on this text, not on an
     * average), and the [ARG_STEP] that would make the whole thing fit — three
     * answers, each of which can be acted on in one move.
     */
    private fun marqueeText(arguments: String, ctx: GlyphToolContext): GlyphToolResult {
        val args = parseObject(arguments)
            ?: return failure("The tool arguments were not a JSON object.") {
                put("expected", "{\"$ARG_TEXT\": \"HELLO WORLD\"}")
            }

        val allowed = ctx.allowedVariants
        if (allowed.isEmpty()) {
            return failure("This design carries no artwork for any panel this app knows, so it cannot be edited.")
        }

        val codename = when (val chosen = chooseVariant(args, ctx, allowed)) {
            is Chosen.Bad -> return chosen.result
            is Chosen.Ok -> chosen.codename
        }
        val size = codename.size

        val levels = ctx.design.levels
        if (levels.isEmpty()) {
            return failure("This design's \"levels\" is empty, so no cell could mean anything.") {
                put("expected", "Give it a palette with $APPLY_DESIGN first, e.g. [0, 2048, 4095].")
            }
        }

        val raw = args[ARG_TEXT]
        val text = (raw as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: return failure("\"$ARG_TEXT\" is missing, or is not a string.") {
                put("expected", "The phrase to scroll, e.g. \"HELLO WORLD\". It is the only argument that has to be set.")
            }
        if (text.isEmpty()) {
            return failure("\"$ARG_TEXT\" is empty, so there is nothing to scroll.")
        }
        // Reported whole rather than one at a time: somebody fixing the phrase
        // should be able to see everything wrong with it in one result.
        val missing = MarqueeFont.unsupported(text)
        if (missing.isNotEmpty()) {
            return failure(
                "This face cannot draw ${missing.joinToString(", ") { "'$it'" }}.",
            ) {
                putJsonArray("unsupported_characters") { missing.forEach { add(it.toString()) } }
                put(
                    "expected",
                    "Letters A-Z and a-z, digits 0-9, a space, and the printable ASCII symbols " +
                        "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~ . Both cases are drawn, and accents are dropped " +
                        "automatically (\"café\" scrolls as cafe), so anything listed above is genuinely " +
                        "not renderable at this size. Replace it, or draw that part by hand and scroll it with " +
                        "$SCROLL_FRAMES.",
                )
            }
        }

        val maxScale = size / MarqueeFont.HEIGHT
        val scale = when (val a = intArg(args, ARG_SCALE)) {
            is IntArg.Bad -> return a.result
            is IntArg.Ok -> a.value
        } ?: MarqueeText.scaleFor(size)
        if (scale < 1 || scale > maxScale) {
            return failure(
                "\"$ARG_SCALE\" is $scale, and a ${MarqueeFont.HEIGHT}-row letter at that scale is " +
                    "${MarqueeFont.HEIGHT * scale} rows on a $size-row panel.",
            ) {
                put(
                    "expected",
                    "1 to $maxScale on ${codename.codename}. null means ${MarqueeText.scaleFor(size)}, which " +
                        "fills the same fraction of the panel on every geometry.",
                )
            }
        }

        val step = when (val a = intArg(args, ARG_STEP)) {
            is IntArg.Bad -> return a.result
            is IntArg.Ok -> a.value
        } ?: MarqueeText.defaultStep(size)
        if (step < 1 || step > size) {
            return failure("\"$ARG_STEP\" is $step.") {
                put(
                    "expected",
                    "1 to $size columns per frame. null means ${MarqueeText.defaultStep(size)} on " +
                        "${codename.codename} — one letter-cell at scale $scale, which is the smoothest step " +
                        "that is not wasted. Doubling it halves the frame count and still reads.",
                )
            }
        }

        val durationMs = when (val a = intArg(args, ARG_DURATION_MS)) {
            is IntArg.Bad -> return a.result
            is IntArg.Ok -> a.value
        } ?: MarqueeText.DEFAULT_DURATION_MS
        if (durationMs < DesignCodec.MIN_DURATION_MS || durationMs > DesignCodec.MAX_DURATION_MS) {
            return failure("\"$ARG_DURATION_MS\" is $durationMs.") {
                put(
                    "expected",
                    "${DesignCodec.MIN_DURATION_MS} to ${DesignCodec.MAX_DURATION_MS} inclusive (out of " +
                        "range is rejected, not clamped). null means ${MarqueeText.DEFAULT_DURATION_MS}, which " +
                        "is a little over two letters a second. Higher is slower and easier to read.",
                )
            }
        }

        val brightest = levels.size - 1
        val paletteIndex = when (val a = intArg(args, ARG_PALETTE_INDEX)) {
            is IntArg.Bad -> return a.result
            is IntArg.Ok -> a.value
        } ?: brightest
        if (paletteIndex < 1 || paletteIndex > brightest) {
            return failure(
                if (paletteIndex == 0) {
                    "\"$ARG_PALETTE_INDEX\" is 0, which is the off level, so every frame would be blank."
                } else {
                    "\"$ARG_PALETTE_INDEX\" is $paletteIndex, but this design's \"levels\" defines only " +
                        "${levels.size} entr${if (levels.size == 1) "y" else "ies"}."
                },
            ) {
                put(
                    "expected",
                    if (brightest < 1) {
                        "A palette with at least one lit level. Add one to \"levels\" with $APPLY_DESIGN first."
                    } else {
                        "1 to $brightest. null means $brightest, the brightest this design has."
                    },
                )
            }
        }

        val stripWidth = MarqueeFont.stripWidth(text)
        val frameCount = MarqueeText.frameCount(size, stripWidth, scale, step)
        if (frameCount > DesignCodec.MAX_FRAMES) {
            // Three separate answers, each actionable in one move: cut the
            // phrase here, or move faster, or make the letters smaller.
            val prefix = MarqueeText.maxPrefixLength(text, size, scale, step)
            // Smallest step whose traverse fits, or absent when even one column
            // per panel width would not — reported as a number the model can
            // pass straight back, never as a number that would fail again. The
            // arithmetic lives in the generator so that this refusal and the
            // editor's own say the same number.
            val neededStep = MarqueeText.stepThatFits(text, size, scale)
            return failure(
                "\"$ARG_TEXT\" is ${text.length} characters, which lays out $stripWidth columns wide and " +
                    "takes $frameCount frames on ${codename.codename}.",
            ) {
                put("frames_needed", frameCount)
                put("max_frames", DesignCodec.MAX_FRAMES)
                put("longest_text_that_fits", text.take(prefix))
                put("longest_text_that_fits_length", prefix)
                if (neededStep != null) put("step_that_would_fit", neededStep)
                put(
                    "expected",
                    buildString {
                        append("At most ${DesignCodec.MAX_FRAMES} frames per panel. ")
                        append("Scroll \"${text.take(prefix)}\" ($prefix characters) instead")
                        if (neededStep != null) {
                            append("; or keep the whole phrase and set \"$ARG_STEP\" to $neededStep, which ")
                            append("moves faster and still reads")
                        }
                        if (scale > 1) append("; or drop \"$ARG_SCALE\" to ${scale - 1} for smaller letters")
                        append(". Do NOT ask for fewer frames — a scroll cut short looks broken, not shorter.")
                    },
                )
            }
        }

        val frames = MarqueeText.frames(
            text = text,
            size = size,
            paletteIndex = paletteIndex,
            durationMs = durationMs,
            scale = scale,
            step = step,
        )
        if (frames.isEmpty()) {
            return failure("That text produced no frames on ${codename.codename}.") {
                put("expected", "A phrase with at least one letter, digit or symbol in it.")
            }
        }

        // The same defence [scrollFrames] ends on, and the thing that makes
        // "hand this straight to apply_design" a promise: the frames go through
        // the codec apply_design finishes at, with only this variant offered so
        // that a different panel already broken on the canvas cannot fail a call
        // that had nothing to do with it.
        val probe = ctx.design.copy(
            kind = DesignKind.DYNAMIC,
            loop = true,
            variants = mapOf(codename.codename to DesignVariant(frames)),
        )
        val checked = DesignCodec.validate(probe)
        if (checked is DesignCodec.Result.Invalid) {
            return failure("The frames this produced would not be accepted: ${checked.reason}")
        }

        val document = buildJsonObject {
            put("kind", "dynamic")
            put("loop", true)
            putJsonObject("variants") {
                putJsonObject(codename.codename) {
                    putJsonArray("frames") {
                        for (frame in frames) {
                            add(
                                buildJsonObject {
                                    put("durationMs", frame.durationMs)
                                    put("cells", frame.cells)
                                },
                            )
                        }
                    }
                }
            }
        }.toString()

        val shown = minOf(frames.size, MAX_SCROLL_PREVIEW_FRAMES)
        val picture = MarqueeFont.picture(text)
        return success(
            buildJsonObject {
                put("ok", true)
                put("applied", false)
                put(
                    "note",
                    "Nothing has changed yet. READ \"strip\" FIRST — it is the whole phrase in one picture, " +
                        "and it is where a wrong letter is actually visible. Then send \"$KEY_APPLY_THIS\" to " +
                        "$APPLY_DESIGN EXACTLY as it came back. Do not retype the cells and do not rebuild the " +
                        "frames by hand.",
                )
                put("variant", codename.codename)
                put("panel_width", size)
                put(ARG_TEXT, text)
                // What was actually drawn, which is not always what was asked
                // for: accents are dropped, and a model that was not told would
                // keep sending the accented spelling. Asked of the face rather
                // than restated here, so this cannot describe a folding rule the
                // face does not apply — it said `uppercase()` for as long as the
                // face was upper-case only, and the day it grew a lower case
                // that answer became a lie.
                put("drawn_as", MarqueeFont.drawnAs(text))
                put("strip", picture.joinToString("\n"))
                put("strip_width", stripWidth)
                put("glyph_height", MarqueeFont.HEIGHT * scale)
                put("top_row", MarqueeText.topRow(size, scale))
                put(ARG_SCALE, scale)
                put(ARG_STEP, step)
                put(ARG_PALETTE_INDEX, paletteIndex)
                put(ARG_DURATION_MS, durationMs)
                put("frame_count", frames.size)
                put("total_ms", frames.size * durationMs)
                put(
                    "frame_count_note",
                    "The full traverse: panel width + message width - 1 = $size + ${stripWidth * scale} - 1 = " +
                        "${size + stripWidth * scale - 1} columns of travel, $frameCount frames at $step per " +
                        "frame" +
                        if (frames.size == frameCount) {
                            ". Neither the first frame nor the last is blank."
                        } else {
                            ", of which ${frameCount - frames.size} were blank at the ends and were dropped, " +
                                "leaving ${frames.size}. The outermost column of the disc is only five rows " +
                                "tall, so a letter column that is all serif arrives as an empty panel; the " +
                                "animation now opens and closes on something lit."
                        },
                )
                // Only ever a run of spaces wider than the panel, which is
                // something the caller asked for — reported, not refused.
                val darkInside = frames.count { it.cells.all { c -> c == '0' } }
                if (darkInside > 0) {
                    put("blank_frames_inside", darkInside)
                    put(
                        "blank_frames_note",
                        "$darkInside frame${if (darkInside == 1) " is" else "s are"} completely dark, which " +
                            "means the text has a run of spaces wider than the panel. Shorten it unless the " +
                            "pause is what you wanted.",
                    )
                }
                put(
                    "clipping_note",
                    "The letters are ${MarqueeFont.HEIGHT * scale} rows tall on a $size-row panel, so the " +
                        "disc cuts their tops and bottoms in the outermost columns as they enter and leave. " +
                        "That is deliberate and is what makes them read as big; the clipped cells are dropped " +
                        "when the frames are built, so nothing outside the panel is stored.",
                )
                put(
                    "document_note",
                    "\"$KEY_APPLY_THIS\" sets kind to dynamic and loop to true, and writes only " +
                        "${codename.codename}'s frames. keyMode, levels, name and any other panel are left " +
                        "exactly as they are.",
                )
                if (shown < frames.size) {
                    put("previews_truncated", true)
                    put("previewed_frames", shown)
                }
                put(KEY_APPLY_THIS, document)
                putJsonArray("frames") {
                    for (i in frames.indices) {
                        add(
                            buildJsonObject {
                                put("index", i)
                                put("durationMs", frames[i].durationMs)
                                if (i < shown) {
                                    // Cells deliberately not repeated here: they
                                    // are in apply_this, once.
                                    val preview = GlyphAsciiPreview.renderCells(frames[i].cells, levels, codename)
                                    put(
                                        "preview",
                                        preview?.let { JsonPrimitive(it) }
                                            ?: JsonPrimitive("(this frame does not decode, so it cannot be drawn)"),
                                    )
                                }
                            },
                        )
                    }
                }
                put("legend", GlyphAsciiPreview.LEGEND)
            },
        )
    }

    private fun scrollFrames(arguments: String, ctx: GlyphToolContext): GlyphToolResult {
        val args = parseObject(arguments)
            ?: return failure("The tool arguments were not a JSON object.") {
                put("expected", "{\"$ARG_SOURCE_ROWS\": [\"1010111\", \"1010010\", …]}")
            }

        val allowed = ctx.allowedVariants
        if (allowed.isEmpty()) {
            return failure("This design carries no artwork for any panel this app knows, so it cannot be edited.")
        }

        val codename = when (val chosen = chooseVariant(args, ctx, allowed)) {
            is Chosen.Bad -> return chosen.result
            is Chosen.Ok -> chosen.codename
        }
        val size = codename.size

        val levels = ctx.design.levels
        if (levels.isEmpty()) {
            return failure("This design's \"levels\" is empty, so no cell could mean anything.") {
                put("expected", "Give it a palette with $APPLY_DESIGN first, e.g. [0, 2048, 4095].")
            }
        }

        val rows = when (val read = sourceRows(args)) {
            is Rows.Bad -> return read.result
            is Rows.Ok -> read.rows
        }
        val height = rows.size
        val width = rows[0].length
        for (r in 1 until height) {
            if (rows[r].length != width) {
                return failure("\"$ARG_SOURCE_ROWS\" row $r is ${rows[r].length} characters but row 0 is $width.") {
                    put(
                        "expected",
                        "Every row exactly the same length. The source is ONE rectangle — the whole " +
                            "message drawn once — and a ragged one has no single width to scroll.",
                    )
                }
            }
        }
        if (height > size) {
            return failure("The source is $height rows tall, but ${codename.codename} is only $size rows.") {
                put("expected", scrollHeightAdvice(codename))
            }
        }

        // Character check and lit extent in one pass. The extent is what makes
        // frame 0 non-blank by default: the window is started so the first lit
        // column is already on the panel, not so column 0 is.
        var firstLit = -1
        var lastLit = -1
        for (r in 0 until height) {
            val row = rows[r]
            for (c in 0 until width) {
                val ch = row[c]
                val index = base36(ch)
                if (index < 0) {
                    return failure(
                        "\"$ARG_SOURCE_ROWS\" row $r column $c uses the character '$ch', which is not a base36 digit.",
                    ) {
                        put(
                            "expected",
                            "A palette index in base36: '0'-'9' then 'a'-'z'. This design defines " +
                                "${levels.size} level${if (levels.size == 1) "" else "s"}, so the only legal " +
                                "characters are ${legalChars(levels)}.",
                        )
                    }
                }
                if (index >= levels.size) {
                    return failure(
                        "\"$ARG_SOURCE_ROWS\" row $r column $c uses palette index $index ('$ch'), but this " +
                            "design's \"levels\" defines only ${levels.size} " +
                            "entr${if (levels.size == 1) "y" else "ies"}.",
                    ) {
                        put(
                            "expected",
                            "Either use ${legalChars(levels)}, or add the level you meant to \"levels\" " +
                                "with $APPLY_DESIGN first.",
                        )
                    }
                }
                if (index > 0) {
                    if (firstLit < 0) firstLit = c
                    if (c < firstLit) firstLit = c
                    if (c > lastLit) lastLit = c
                }
            }
        }
        if (firstLit < 0) {
            return failure("\"$ARG_SOURCE_ROWS\" has no lit cell, so every frame it produced would be blank.") {
                put("expected", "At least one character above '0' — the message itself.")
            }
        }

        val band = GlyphAiPrompt.fullWidthRows(size)
        val defaultTop = if (band != null && height <= band.count()) {
            band.first + (band.count() - height) / 2
        } else {
            (size - height) / 2
        }
        val topRow = when (val a = intArg(args, ARG_TOP_ROW)) {
            is IntArg.Bad -> return a.result
            is IntArg.Ok -> a.value
        } ?: defaultTop
        if (topRow < 0 || topRow + height > size) {
            return failure("\"$ARG_TOP_ROW\" $topRow would put a $height-row source outside a ${size}-row panel.") {
                put("expected", "0 to ${size - height} for a source $height rows tall. ${scrollHeightAdvice(codename)}")
            }
        }

        val step = when (val a = intArg(args, ARG_STEP)) {
            is IntArg.Bad -> return a.result
            is IntArg.Ok -> a.value
        } ?: 1
        if (step < 1) {
            return failure("\"$ARG_STEP\" is $step.") {
                put("expected", "At least 1 column per frame. 1 is smoothest; 2 halves the frame count and still reads.")
            }
        }
        if (step > size) {
            return failure(
                "\"$ARG_STEP\" is $step, wider than the ${size}-column panel, so whole columns of the " +
                    "message would never be shown at all.",
            ) { put("expected", "1 to $size.") }
        }

        val defaultStart = firstLit - (size - 1)
        val startColumn = when (val a = intArg(args, ARG_START_COLUMN)) {
            is IntArg.Bad -> return a.result
            is IntArg.Ok -> a.value
        } ?: defaultStart
        // Bounded, but with room to be deliberately early or late: a start that
        // produces blank frames is a thing to *report* (see [scrollWarnings]),
        // not a thing to refuse. Past this the window never touches the message
        // at any offset, so there is nothing to report about.
        val startLimit = size + width
        if (startColumn < -startLimit || startColumn > startLimit) {
            return failure(
                "\"$ARG_START_COLUMN\" $startColumn is so far outside the message that the panel would " +
                    "show nothing at all.",
            ) {
                put(
                    "expected",
                    "${-startLimit} to $startLimit. $defaultStart is the default and puts the message's " +
                        "leading column on the panel's right-hand edge in frame 0.",
                )
            }
        }

        val fullTraverse = if (lastLit >= startColumn) (lastLit - startColumn) / step + 1 else 1
        val askedFrames = when (val a = intArg(args, ARG_FRAMES)) {
            is IntArg.Bad -> return a.result
            is IntArg.Ok -> a.value
        }
        val frameCount = askedFrames ?: fullTraverse
        if (frameCount < 1) {
            return failure("\"$ARG_FRAMES\" is $frameCount.") {
                put("expected", "At least 1, or null for the full traverse ($fullTraverse frames from here).")
            }
        }
        if (frameCount > DesignCodec.MAX_FRAMES) {
            return failure("That would be $frameCount frames.") {
                put(
                    "expected",
                    "At most ${DesignCodec.MAX_FRAMES} per panel. Scroll a shorter message, or raise " +
                        "\"$ARG_STEP\" — 2 columns per frame halves the count and still reads.",
                )
            }
        }

        val durationMs = when (val a = intArg(args, ARG_DURATION_MS)) {
            is IntArg.Bad -> return a.result
            is IntArg.Ok -> a.value
        } ?: DEFAULT_FRAME_DURATION_MS
        if (durationMs < DesignCodec.MIN_DURATION_MS || durationMs > DesignCodec.MAX_DURATION_MS) {
            return failure("\"$ARG_DURATION_MS\" is $durationMs.") {
                put(
                    "expected",
                    "${DesignCodec.MIN_DURATION_MS} to ${DesignCodec.MAX_DURATION_MS} inclusive (out of " +
                        "range is rejected, not clamped). 80-200 ms reads well for a scroll.",
                )
            }
        }

        // The windowing itself. ONE offset per frame, read by every row: this is
        // the line that makes a sheared glyph unrepresentable.
        val frames = ArrayList<DesignFrame>(frameCount)
        for (n in 0 until frameCount) {
            val offset = startColumn + n * step
            val cells = CharArray(size * size) { '0' }
            for (r in 0 until height) {
                val row = rows[r]
                val base = (topRow + r) * size
                for (x in 0 until size) {
                    val sourceColumn = offset + x
                    if (sourceColumn in 0 until width) cells[base + x] = row[sourceColumn]
                }
            }
            frames.add(DesignFrame(durationMs, String(cells)))
        }

        // Defence in depth, and the thing that makes "hand this straight to
        // apply_design" a promise rather than a hope: the frames are put through
        // the same codec apply_design ends at. Only this variant is offered to
        // it, so a *different* panel that is already broken on the canvas cannot
        // fail a call that had nothing to do with it.
        val probe = ctx.design.copy(
            kind = DesignKind.DYNAMIC,
            variants = mapOf(codename.codename to DesignVariant(frames)),
        )
        val checked = DesignCodec.validate(probe)
        if (checked is DesignCodec.Result.Invalid) {
            return failure("The frames this produced would not be accepted: ${checked.reason}")
        }

        val warnings = scrollWarnings(
            frames = frames,
            defaultStart = defaultStart,
            startColumn = startColumn,
            step = step,
            frameCount = frameCount,
            fullTraverse = fullTraverse,
            lastLit = lastLit,
            rows = rows,
            topRow = topRow,
            band = band,
            codename = codename,
        )

        val document = buildJsonObject {
            // Sent because a scroll is an animation, and a design still marked
            // static would have apply_design refuse every frame but the first.
            put("kind", "dynamic")
            putJsonObject("variants") {
                putJsonObject(codename.codename) {
                    putJsonArray("frames") {
                        for (frame in frames) {
                            add(
                                buildJsonObject {
                                    put("durationMs", frame.durationMs)
                                    put("cells", frame.cells)
                                },
                            )
                        }
                    }
                }
            }
        }.toString()

        val shown = minOf(frameCount, MAX_SCROLL_PREVIEW_FRAMES)
        return success(
            buildJsonObject {
                put("ok", true)
                put("applied", false)
                put(
                    "note",
                    "Nothing has changed yet. Read the pictures below against each other — the glyph must " +
                        "move by exactly $step column${if (step == 1) "" else "s"} per frame and never " +
                        "change row — then send \"$KEY_APPLY_THIS\" to $APPLY_DESIGN EXACTLY as it came " +
                        "back. Do not retype the cells and do not rebuild the frames by hand.",
                )
                put("variant", codename.codename)
                put("panel_width", size)
                putJsonObject("source") {
                    put("width", width)
                    put("height", height)
                    put("first_lit_column", firstLit)
                    put("last_lit_column", lastLit)
                }
                put(ARG_TOP_ROW, topRow)
                put(ARG_START_COLUMN, startColumn)
                put(ARG_STEP, step)
                put("frame_count", frameCount)
                put(ARG_DURATION_MS, durationMs)
                put(
                    "frame_count_note",
                    if (askedFrames == null) {
                        "The full traverse: panel width + source width - 1 = $size + $width - 1 = " +
                            "${size + width - 1} columns of travel, $frameCount frames at $step per frame."
                    } else {
                        "You asked for $frameCount. The full traverse from column $startColumn at $step " +
                            "per frame is $fullTraverse frames."
                    },
                )
                putJsonArray("warnings") { warnings.forEach { add(it) } }
                if (shown < frameCount) {
                    put("previews_truncated", true)
                    put("previewed_frames", shown)
                }
                put(KEY_APPLY_THIS, document)
                putJsonArray("frames") {
                    for (i in frames.indices) {
                        add(
                            buildJsonObject {
                                put("index", i)
                                put("durationMs", frames[i].durationMs)
                                put("source_column_at_panel_left", startColumn + i * step)
                                if (i < shown) {
                                    // The cells are deliberately NOT repeated here.
                                    // They are in apply_this, once, and the one
                                    // thing that must not happen to them is being
                                    // read out and written back.
                                    val preview = GlyphAsciiPreview.renderCells(frames[i].cells, levels, codename)
                                    put(
                                        "preview",
                                        preview?.let { JsonPrimitive(it) }
                                            ?: JsonPrimitive("(this frame does not decode, so it cannot be drawn)"),
                                    )
                                }
                            },
                        )
                    }
                }
                put("legend", GlyphAsciiPreview.LEGEND)
            },
        )
    }

    /** The panel a single-variant tool is working on, or why it could not be settled. */
    private sealed interface Chosen {
        data class Ok(val codename: PokemonCodename) : Chosen
        data class Bad(val result: GlyphToolResult) : Chosen
    }

    /**
     * Which panel to work on: what was asked for, else the one on screen, else
     * the only one there is.
     *
     * Never a *guess* between two carried panels — a marquee written onto the
     * panel the user is not looking at would appear to have done nothing, and so
     * would a photograph or a replaced frame.
     *
     * Shared by [scrollFrames], [imageToGrid] and [setFrames] so that being
     * refused by one of them teaches the model the same thing it would learn
     * from the others.
     */
    private fun chooseVariant(
        args: JsonObject,
        ctx: GlyphToolContext,
        allowed: List<PokemonCodename>,
    ): Chosen {
        val raw = args[ARG_VARIANT]
        if (raw == null || raw is JsonNull) {
            val implied = ctx.openVariant?.takeIf { allowed.contains(it) } ?: allowed.singleOrNull()
            return implied?.let { Chosen.Ok(it) } ?: Chosen.Bad(
                failure(
                    "This design carries ${allowed.size} panels and none of them is open, so \"$ARG_VARIANT\" " +
                        "cannot be left null.",
                ) { putAllowed(allowed) },
            )
        }
        val text = (raw as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: return Chosen.Bad(failure("\"$ARG_VARIANT\" is not a panel codename.") { putAllowed(allowed) })
        val codename = PokemonCodename.ofCodename(text)
            ?: return Chosen.Bad(failure("There is no panel called \"$text\".") { putAllowed(allowed) })
        if (!allowed.contains(codename)) {
            return Chosen.Bad(
                failure(
                    "This design carries no \"$text\" artwork, so you cannot write it. " +
                        "Only the user can add a panel to a design, from the editor.",
                ) { putAllowed(allowed) },
            )
        }
        return Chosen.Ok(codename)
    }

    /** The message bitmap, or why there is none. */
    private sealed interface Rows {
        data class Ok(val rows: List<String>) : Rows
        data class Bad(val result: GlyphToolResult) : Rows
    }

    /**
     * [ARG_SOURCE_ROWS] as a list of rows.
     *
     * An array is what the schema asks for; a single newline-separated string is
     * what a model sends often enough to be worth accepting, for the same reason
     * [prepare] accepts a document as an object as well as as text. Both are
     * unambiguous, and refusing one costs a round trip and teaches nothing.
     */
    private fun sourceRows(args: JsonObject): Rows {
        val raw = args[ARG_SOURCE_ROWS]
            ?: return Rows.Bad(
                failure("Missing the \"$ARG_SOURCE_ROWS\" argument.") { put("expected", SOURCE_ROWS_EXPECTED) },
            )
        val rows: List<String> = when {
            raw is JsonArray -> {
                val out = ArrayList<String>(raw.size)
                for (i in raw.indices) {
                    val text = (raw[i] as? JsonPrimitive)?.takeIf { it.isString }?.content
                        ?: return Rows.Bad(
                            failure("\"$ARG_SOURCE_ROWS\" entry $i is not a string.") {
                                put("expected", SOURCE_ROWS_EXPECTED)
                            },
                        )
                    out.add(text)
                }
                out
            }

            raw is JsonPrimitive && raw.isString -> raw.content.trim().lines().map { it.trim() }

            else -> return Rows.Bad(
                failure("\"$ARG_SOURCE_ROWS\" is neither an array of strings nor one string.") {
                    put("expected", SOURCE_ROWS_EXPECTED)
                },
            )
        }
        if (rows.isEmpty() || rows.all { it.isEmpty() }) {
            return Rows.Bad(
                failure("\"$ARG_SOURCE_ROWS\" is empty.") { put("expected", SOURCE_ROWS_EXPECTED) },
            )
        }
        return Rows.Ok(rows)
    }

    /**
     * Everything about this scroll that is legal, was asked for, and is probably
     * not what the caller meant.
     *
     * Warnings rather than refusals throughout: a clipped marquee and a scroll
     * that stops halfway are both things somebody might want, and this file is
     * not the place to overrule them. But they are also all invisible in a single
     * frame's picture — a cell lost to the rim is lost *while it moves* — so
     * saying nothing would leave the model unable to see them at all.
     */
    private fun scrollWarnings(
        frames: List<DesignFrame>,
        defaultStart: Int,
        startColumn: Int,
        step: Int,
        frameCount: Int,
        fullTraverse: Int,
        lastLit: Int,
        rows: List<String>,
        topRow: Int,
        band: IntRange?,
        codename: PokemonCodename,
    ): List<String> {
        val warnings = ArrayList<String>(3)

        val blank = frames.indices.filter { i -> frames[i].cells.all { it == '0' } }
        if (blank.isNotEmpty()) {
            warnings.add(
                buildString {
                    append(blank.size)
                    append(" frame")
                    if (blank.size != 1) append("s")
                    append(" (")
                    append(blank.take(10).joinToString(", "))
                    if (blank.size > 10) append(", …")
                    append(if (blank.size == 1) ") is" else ") are")
                    append(" completely blank, because the window is off the message there. That is a ")
                    append("beat of darkness on the panel")
                    if (blank.first() == 0) {
                        append(", and a blank frame 0 means the design opens by showing an empty panel")
                    }
                    append(". Leave \"")
                    append(ARG_START_COLUMN)
                    append("\" null — it would be ")
                    append(defaultStart)
                    append(", which puts the message's leading column on the panel in frame 0.")
                },
            )
        }

        val clipped = rows.indices.filter { r ->
            rows[r].any { base36(it) > 0 } && (band == null || (topRow + r) !in band)
        }
        if (clipped.isNotEmpty()) {
            val panelRows = clipped.map { topRow + it }
            warnings.add(
                "Panel row${if (panelRows.size == 1) "" else "s"} " +
                    "${panelRows.joinToString(", ")} carr${if (panelRows.size == 1) "ies" else "y"} lit " +
                    "cells but ${if (panelRows.size == 1) "is" else "are"} not live across every column of " +
                    "${codename.codename}, so those cells WILL be clipped by the disc as the art scrolls — " +
                    "at the start and the end of the travel, not in the middle, which is why no single " +
                    "frame shows it. " + scrollHeightAdvice(codename),
            )
        }

        if (startColumn + (frameCount - 1) * step < lastLit) {
            warnings.add(
                "The last frame still has the message crossing the panel, so this scroll stops rather than " +
                    "finishes. A scroll cut short does not look like a shorter scroll, it looks broken. " +
                    "The full traverse from here is $fullTraverse frames; leave \"$ARG_FRAMES\" null for it.",
            )
        }
        return warnings
    }

    /** Where a scrolling glyph can live on [codename] without losing a cell. */
    private fun scrollHeightAdvice(codename: PokemonCodename): String {
        val band = GlyphAiPrompt.fullWidthRows(codename.size)
            ?: return "${codename.codename} has no row that is live across every column."
        return "Rows ${band.first} to ${band.last} are the only rows of ${codename.codename} live across " +
            "all ${codename.size} columns, so a source ${band.count()} rows tall or shorter, placed in " +
            "that band, keeps every cell at every horizontal offset."
    }

    private const val SOURCE_ROWS_EXPECTED: String =
        "An array of equal-length strings, one per row of the message, in the same base36 palette-index " +
            "encoding as cells — the WHOLE message drawn once, as wide as it needs to be. Row 0 is the " +
            "top row. \"HI\" is [\"1010111\", \"1010010\", \"1110010\", \"1010010\", \"1010111\"]."

    /** An optional integer argument, or why it could not be read. */
    private sealed interface IntArg {
        /** Null means the key was absent or explicitly null: take the default. */
        data class Ok(val value: Int?) : IntArg
        data class Bad(val result: GlyphToolResult) : IntArg
    }

    private fun intArg(args: JsonObject, key: String): IntArg {
        val raw = args[key] ?: return IntArg.Ok(null)
        if (raw is JsonNull) return IntArg.Ok(null)
        val value = (raw as? JsonPrimitive)?.content?.toIntOrNull()
            ?: return IntArg.Bad(
                failure("\"$key\" is not a whole number.") {
                    put("expected", "An integer, or null to take the default.")
                },
            )
        return IntArg.Ok(value)
    }

    /** An optional fractional argument, or why it could not be read. */
    private sealed interface DoubleArg {
        data class Ok(val value: Double?) : DoubleArg
        data class Bad(val result: GlyphToolResult) : DoubleArg
    }

    private fun doubleArg(args: JsonObject, key: String): DoubleArg {
        val raw = args[key] ?: return DoubleArg.Ok(null)
        if (raw is JsonNull) return DoubleArg.Ok(null)
        val value = (raw as? JsonPrimitive)?.content?.toDoubleOrNull()
            ?: return DoubleArg.Bad(
                failure("\"$key\" is not a number.") {
                    put("expected", "A number, or null to take the default.")
                },
            )
        // NaN and the infinities parse happily and then poison every comparison
        // downstream, which would surface as a blank frame rather than as an
        // error. Caught here, once, for both callers.
        if (!value.isFinite()) {
            return DoubleArg.Bad(
                failure("\"$key\" is $value.") { put("expected", "A finite number, or null.") },
            )
        }
        return DoubleArg.Ok(value)
    }

    /** An optional boolean argument, or why it could not be read. */
    private sealed interface BoolArg {
        data class Ok(val value: Boolean?) : BoolArg
        data class Bad(val result: GlyphToolResult) : BoolArg
    }

    private fun boolArg(args: JsonObject, key: String): BoolArg {
        val raw = args[key] ?: return BoolArg.Ok(null)
        if (raw is JsonNull) return BoolArg.Ok(null)
        val value = when ((raw as? JsonPrimitive)?.content) {
            "true" -> true
            "false" -> false
            else -> return BoolArg.Bad(
                failure("\"$key\" is not true or false.") {
                    put("expected", "true, false, or null to take the default.")
                },
            )
        }
        return BoolArg.Ok(value)
    }

    // endregion

    // region image_to_grid

    /**
     * Turns an attached photograph into one frame of art.
     *
     * ## The failure this exists to make impossible
     *
     * "Draw this on my panel", with a photo attached, was the weakest thing this
     * assistant did. The model can see the JPEG, so the *judgement* — what the
     * picture is of, what would survive at 13x13 — was never the problem. What
     * it then had to do was write 169 base36 characters approximating an image
     * it could only eyeball, and no reader on earth can say what a photograph
     * averages to at cell (7, 4). An image of a plain "10" took eight attempts
     * and then six more.
     *
     * So the mechanical half moves here, exactly as it did for [scrollFrames]:
     * the app downscales the attachment to the panel, box-averages it, applies
     * [PanelMask] so nothing is drawn on a cell that has no LED, and quantises
     * to the design's own `levels`. The arithmetic is [ImageQuantiser], which is
     * pure and separately tested; this function is the argument checking and the
     * report.
     *
     * ## Why it does not apply
     *
     * Same bargain as [scrollFrames]. It returns [KEY_APPLY_THIS] and no
     * [GlyphToolResult.design], because a literal downsample of a photograph is
     * a *starting point* and quite often not the answer — the model has to look
     * at the picture that came back and decide whether it reads at this size or
     * whether the honest thing is to draw the silhouette by hand (which is what
     * [GlyphAiPrompt.REFERENCE_NOT_TARGET] tells it). Applying automatically
     * would put a grey smear on somebody's canvas and call it done.
     *
     * ## The knobs, and why there are only three
     *
     * [ARG_THRESHOLD], [ARG_CONTRAST] and [ARG_INVERT]. Everything else the
     * conversion decides — fit rather than crop, normalise to the image's own
     * range, box-average, mask — has one defensible answer and is not the
     * model's to get wrong; see [ImageQuantiser]. These three do not: where the
     * light/dark cut belongs is a judgement about the *subject*, and a logo
     * photographed on white paper needs [ARG_INVERT] or it comes back as a
     * silhouette of the paper. Three knobs is enough to iterate with and few
     * enough to iterate *through*.
     */
    private fun imageToGrid(arguments: String, ctx: GlyphToolContext): GlyphToolResult {
        val args = parseObject(arguments)
            ?: return failure("The tool arguments were not a JSON object.") {
                put("expected", "{\"$ARG_IMAGE_INDEX\": 0, \"$ARG_THRESHOLD\": null, \"$ARG_CONTRAST\": null, \"$ARG_INVERT\": null}")
            }

        val allowed = ctx.allowedVariants
        if (allowed.isEmpty()) {
            return failure("This design carries no artwork for any panel this app knows, so it cannot be edited.")
        }

        val codename = when (val chosen = chooseVariant(args, ctx, allowed)) {
            is Chosen.Bad -> return chosen.result
            is Chosen.Ok -> chosen.codename
        }
        val size = codename.size

        val levels = ctx.design.levels
        if (levels.isEmpty()) {
            return failure("This design's \"levels\" is empty, so no cell could mean anything.") {
                put("expected", "Give it a palette with $APPLY_DESIGN first, e.g. [0, 2048, 4095].")
            }
        }
        if (levels.size < 2) {
            return failure(
                "This design's \"levels\" has one entry, so every cell it can address is palette index 0 " +
                    "and the whole picture would be off.",
            ) {
                put(
                    "expected",
                    "A palette with something lit in it — [0, 4095] for pure on/off, [0, 2048, 4095] for " +
                        "one grey. Set it with $APPLY_DESIGN first.",
                )
            }
        }

        // An attachment rides on ONE message. A photo sent three turns ago is
        // genuinely not here, and saying so is the difference between the model
        // asking for it again and the model inventing what it looked like.
        if (ctx.images.isEmpty()) {
            return failure("No image is attached to the message you are answering.") {
                put(
                    "expected",
                    "Ask the user to attach the picture to their next message. An attachment only travels " +
                        "with the message it was sent on, so a photo from an earlier turn is not available " +
                        "here — and you must not draw one from memory.",
                )
            }
        }
        val index = when (val a = intArg(args, ARG_IMAGE_INDEX)) {
            is IntArg.Bad -> return a.result
            is IntArg.Ok -> a.value
        } ?: 0
        if (index < 0 || index >= ctx.images.size) {
            return failure(
                "There is no image $index on this message: ${ctx.images.size} " +
                    "${if (ctx.images.size == 1) "image was" else "images were"} attached.",
            ) {
                put(
                    "expected",
                    "0 to ${ctx.images.size - 1}, counting in the order they were attached. null means 0, " +
                        "the first one.",
                )
            }
        }
        val image = ctx.images[index]

        val threshold = when (val a = doubleArg(args, ARG_THRESHOLD)) {
            is DoubleArg.Bad -> return a.result
            is DoubleArg.Ok -> a.value
        }
        if (threshold != null && (threshold < 0.0 || threshold > ImageQuantiser.MAX_THRESHOLD)) {
            return failure("\"$ARG_THRESHOLD\" is $threshold.") {
                put(
                    "expected",
                    "0.0 to ${ImageQuantiser.MAX_THRESHOLD}, or null to have it chosen for you — which is " +
                        "usually better, because it is picked from this image's own histogram. Higher keeps " +
                        "only the brightest cells; lower lights more of the picture.",
                )
            }
        }
        val contrast = when (val a = doubleArg(args, ARG_CONTRAST)) {
            is DoubleArg.Bad -> return a.result
            is DoubleArg.Ok -> a.value
        } ?: ImageQuantiser.DEFAULT_CONTRAST
        if (contrast < ImageQuantiser.MIN_CONTRAST || contrast > ImageQuantiser.MAX_CONTRAST) {
            return failure("\"$ARG_CONTRAST\" is $contrast.") {
                put(
                    "expected",
                    "${ImageQuantiser.MIN_CONTRAST} to ${ImageQuantiser.MAX_CONTRAST}. " +
                        "${ImageQuantiser.DEFAULT_CONTRAST} leaves the image as it is; above it pushes light " +
                        "and dark apart, which is what a flat-looking photo needs.",
                )
            }
        }
        val invert = when (val a = boolArg(args, ARG_INVERT)) {
            is BoolArg.Bad -> return a.result
            is BoolArg.Ok -> a.value
        } ?: false

        val quantised = ImageQuantiser.quantise(
            image = image,
            size = size,
            levelCount = levels.size,
            threshold = threshold,
            contrast = contrast,
            invert = invert,
        )
        val done = when (quantised) {
            is ImageQuantiser.Result.Unusable -> return failure(
                "That image could not be read: it has no pixels this app can measure.",
            ) { put("expected", "Ask the user for a different picture.") }

            is ImageQuantiser.Result.Flat -> return failure(
                "That image is almost a flat field — its brightest and darkest cell differ by only " +
                    "${quantised.range} of 255 — so there is no picture in it to draw.",
            ) {
                put(
                    "expected",
                    "Nothing was produced, deliberately: stretching that would light cells at random and " +
                        "call it art. Either the wrong image was attached, or the subject fills so little of " +
                        "the frame that it disappeared. Ask the user for a closer or higher-contrast photo, " +
                        "or draw the thing yourself.",
                )
            }

            is ImageQuantiser.Result.Ok -> quantised
        }

        val frame = DesignFrame(DEFAULT_FRAME_DURATION_MS, done.cells)

        // Defence in depth, exactly as scroll_frames does it: the frame goes
        // through the codec that apply_design ends at, with only this variant
        // offered, so "hand this straight on" is a promise rather than a hope.
        val probe = ctx.design.copy(variants = mapOf(codename.codename to DesignVariant(listOf(frame))))
        val checked = DesignCodec.validate(probe)
        if (checked is DesignCodec.Result.Invalid) {
            return failure("The frame this produced would not be accepted: ${checked.reason}")
        }

        val warnings = imageWarnings(done, invert, contrast)
        val document = buildJsonObject {
            // No "kind": one frame is legal for a static design and for a dynamic
            // one alike, and changing it here would be changing something nobody
            // asked about. A model that wants this as a still says so itself.
            putJsonObject("variants") {
                putJsonObject(codename.codename) {
                    putJsonArray("frames") {
                        add(
                            buildJsonObject {
                                put("durationMs", frame.durationMs)
                                put("cells", frame.cells)
                            },
                        )
                    }
                }
            }
        }.toString()

        return success(
            buildJsonObject {
                put("ok", true)
                put("applied", false)
                put(
                    "note",
                    "Nothing has changed yet. This is the photograph itself, downscaled to the panel and " +
                        "masked to the disc — a literal conversion, not a drawing. LOOK AT THE PICTURE " +
                        "BELOW: if it reads as the thing it is meant to be, send \"$KEY_APPLY_THIS\" to " +
                        "$APPLY_DESIGN as it came back, or put its \"cells\" into one frame with $SET_FRAMES. " +
                        "If it does not read, adjust \"$ARG_THRESHOLD\", \"$ARG_CONTRAST\" or " +
                        "\"$ARG_INVERT\" and call again — or give up on the literal version and draw the " +
                        "silhouette yourself, which at this size is often the better answer.",
                )
                put("variant", codename.codename)
                put("size", size)
                putJsonObject("image") {
                    put("index", index)
                    put("attached", ctx.images.size)
                    put("width", image.width)
                    put("height", image.height)
                }
                put(ARG_THRESHOLD, done.threshold)
                put(
                    "threshold_note",
                    if (done.automatic) {
                        "Chosen from this image's own histogram (the cut that best separates its light and " +
                            "dark cells). Pass it back as \"$ARG_THRESHOLD\" to reproduce this exactly, or " +
                            "nudge it up to keep less and down to keep more."
                    } else {
                        "The value you asked for. Leave \"$ARG_THRESHOLD\" null to have it chosen from the " +
                            "image's own histogram."
                    },
                )
                put(ARG_CONTRAST, contrast)
                put(ARG_INVERT, invert)
                putJsonArray("levels") { levels.forEach { add(it) } }
                put("lit_cells", done.lit)
                put("sampled_cells", done.sampled)
                put("live_leds", PanelMask.count(size))
                put(
                    "framing_note",
                    "The whole image was scaled to fit and centred, aspect ratio kept, so nothing was " +
                        "cropped away — a picture that is not square leaves dark cells at two edges.",
                )
                putJsonArray("warnings") { warnings.forEach { add(it) } }
                put("cells", done.cells)
                put(KEY_APPLY_THIS, document)
                put(
                    "preview",
                    GlyphAsciiPreview.renderCells(done.cells, levels, codename)
                        ?.let { JsonPrimitive(it) }
                        ?: JsonPrimitive("(this frame does not decode, so it cannot be drawn)"),
                )
                put("legend", GlyphAsciiPreview.LEGEND)
            },
        )
    }

    /**
     * Everything about a conversion that is legal and probably not what anybody
     * wanted.
     *
     * All three are about the *cut*, which is the one thing that can go wrong
     * invisibly: a frame that is 96 % lit and a frame that is 4 % lit both look
     * like progress in a JSON payload and like nothing at all on the panel. Each
     * names the knob and the direction, because "that did not work" without a
     * next step is how a turn spends its whole budget.
     */
    private fun imageWarnings(
        done: ImageQuantiser.Result.Ok,
        invert: Boolean,
        contrast: Double,
    ): List<String> {
        val warnings = ArrayList<String>(2)
        val lit = done.lit
        val sampled = done.sampled.coerceAtLeast(1)
        if (lit == 0) {
            warnings.add(
                "Every cell came out off, so this frame is blank. \"$ARG_THRESHOLD\" " +
                    "${round2(done.threshold)} is above everything in the picture: lower it, or leave it " +
                    "null to have it chosen from the image itself.",
            )
        } else if (lit * 100 / sampled >= MOSTLY_LIT_PERCENT) {
            warnings.add(
                "$lit of the $sampled cells the picture covers are lit — nearly all of them — so the art " +
                    "has no outline and will read as a bright blob. Raise \"$ARG_THRESHOLD\" above " +
                    "${round2(done.threshold)}" +
                    (if (invert) ", or drop \"$ARG_INVERT\": a light background inverts to a lit panel." else ".") +
                    " Raising \"$ARG_CONTRAST\" above $contrast separates the subject further.",
            )
        } else if (lit * 100 / sampled <= BARELY_LIT_PERCENT) {
            warnings.add(
                "Only $lit of the $sampled cells the picture covers are lit, so almost nothing will be " +
                    "visible. Lower \"$ARG_THRESHOLD\" below ${round2(done.threshold)}" +
                    (if (invert) "." else ", or set \"$ARG_INVERT\" true if the subject is dark on a light background.") +
                    " Raising \"$ARG_CONTRAST\" above $contrast also helps a flat photograph.",
            )
        }
        return warnings
    }

    /** Above this proportion of the picture's own cells, a frame is a blob. */
    private const val MOSTLY_LIT_PERCENT = 90

    /** Below it, there is nothing to see. */
    private const val BARELY_LIT_PERCENT = 4

    /**
     * A fraction with two decimals, without `String.format` — which is
     * locale-sensitive and would write "0,42" for a French user, in a payload
     * that is then parsed back as an argument.
     */
    private fun round2(value: Double): String {
        val hundredths = kotlin.math.round(value * 100).toInt()
        return "${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0')}"
    }

    // endregion

    // region set_frames

    /**
     * Replaces, inserts or deletes a **range** of frames in one variant, leaving
     * every other frame exactly as it was.
     *
     * ## Why a second writing tool
     *
     * [APPLY_DESIGN] replaces the whole document. That is the right primitive
     * and it stays, but it means changing frame 7 of a long animation costs a
     * re-send of every frame: 240 frames of `arbok` is ~150 kB of base36, which
     * is slow, expensive, and — the part that actually bites — a fresh
     * opportunity to mistype a frame that was already correct. The model has no
     * way to see that it dropped a character in frame 112 while retyping it to
     * change frame 7.
     *
     * So this writes a window. Nothing outside `[at, at + n)` is read, rewritten
     * or even parsed, which makes "leave the rest alone" a property of the code
     * rather than an instruction the model has to execute perfectly.
     *
     * ## It applies, and that is a deliberate difference from [scrollFrames]
     *
     * [scrollFrames] and [imageToGrid] return [KEY_APPLY_THIS] and change
     * nothing, because what they hand back is a *whole variant* that
     * [APPLY_DESIGN] can carry unaided. This cannot work that way: a document
     * expressing "frames 7 to 9 of 240 changed" would have to contain all 240,
     * which is the exact cost this tool exists to avoid. So it hands back a
     * [GlyphToolResult.design] and the caller puts it on the canvas — it is
     * [APPLY_DESIGN] with a narrower argument, and it is checked by precisely
     * the same rules.
     *
     * ## The one thing it changes that was not asked for
     *
     * A design whose `kind` is `static` may hold exactly one frame, so inserting
     * a second into one would be refused with "set kind to dynamic" — and there
     * is no way to set `kind` from here, which would leave the model stuck in a
     * loop against a tool that cannot do what it is telling it to do. So a
     * static design that ends up with more than one frame is promoted to
     * `dynamic`, and the result *says so*. It is never demoted: dropping back to
     * one frame is not evidence that anybody wanted a still.
     */
    private fun setFrames(arguments: String, ctx: GlyphToolContext): GlyphToolResult {
        val args = parseObject(arguments)
            ?: return failure("The tool arguments were not a JSON object.") {
                put("expected", "{\"$ARG_MODE\": \"$MODE_REPLACE\", \"$ARG_AT\": 0, \"$ARG_FRAME_LIST\": [{\"durationMs\": 120, \"cells\": \"…\"}]}")
            }

        val allowed = ctx.allowedVariants
        if (allowed.isEmpty()) {
            return failure("This design carries no artwork for any panel this app knows, so it cannot be edited.")
        }

        val codename = when (val chosen = chooseVariant(args, ctx, allowed)) {
            is Chosen.Bad -> return chosen.result
            is Chosen.Ok -> chosen.codename
        }

        val levels = ctx.design.levels
        if (levels.isEmpty()) {
            return failure("This design's \"levels\" is empty, so no cell could mean anything.") {
                put("expected", "Give it a palette with $APPLY_DESIGN first, e.g. [0, 2048, 4095].")
            }
        }

        val existing = ctx.design.variantFor(codename)?.frames.orEmpty()

        val modeRaw = args[ARG_MODE]
        if (modeRaw == null || modeRaw is JsonNull) {
            return failure("Missing the \"$ARG_MODE\" argument.") { putModes() }
        }
        val mode = (modeRaw as? JsonPrimitive)?.takeIf { it.isString }?.content?.lowercase()
            ?: return failure("\"$ARG_MODE\" is not one of the three modes.") { putModes() }
        if (mode != MODE_REPLACE && mode != MODE_INSERT && mode != MODE_DELETE) {
            return failure("There is no \"$mode\" mode.") { putModes() }
        }

        val at = when (val a = intArg(args, ARG_AT)) {
            is IntArg.Bad -> return a.result
            is IntArg.Ok -> a.value
        } ?: return failure("Missing the \"$ARG_AT\" argument.") {
            put(
                "expected",
                "The frame index the change starts at, counting from 0. " +
                    "${codename.codename} has ${existing.size} " +
                    "frame${if (existing.size == 1) "" else "s"} right now" +
                    if (existing.isEmpty()) "." else " (0 to ${existing.size - 1}).",
            )
        }

        val count = when (val a = intArg(args, ARG_COUNT)) {
            is IntArg.Bad -> return a.result
            is IntArg.Ok -> a.value
        }
        val supplied = args[ARG_FRAME_LIST]?.takeIf { it !is JsonNull }

        if (mode == MODE_DELETE) {
            if (supplied != null) {
                return failure("\"$ARG_MODE\" is \"$MODE_DELETE\" but you also sent \"$ARG_FRAME_LIST\".") {
                    put(
                        "expected",
                        "A delete takes \"$ARG_AT\" and \"$ARG_COUNT\" and nothing else. To swap frames for " +
                            "different ones, use \"$MODE_REPLACE\".",
                    )
                }
            }
        } else if (count != null) {
            return failure("\"$ARG_COUNT\" only applies to \"$MODE_DELETE\", and \"$ARG_MODE\" is \"$mode\".") {
                put(
                    "expected",
                    "For \"$MODE_REPLACE\" and \"$MODE_INSERT\" the number of frames is however many you put " +
                        "in \"$ARG_FRAME_LIST\". Send \"$ARG_COUNT\" as null.",
                )
            }
        }

        val incoming: List<DesignFrame> = if (mode == MODE_DELETE) {
            emptyList()
        } else {
            when (val read = readFrames(supplied, codename, mode)) {
                is Frames.Bad -> return read.result
                is Frames.Ok -> read.frames
            }
        }

        // The window, per mode. Each of these is the place an off-by-one would
        // silently eat a frame, so each is checked against the CURRENT list and
        // reported with the numbers the model needs to correct it.
        val removed: Int
        when (mode) {
            MODE_REPLACE -> {
                if (existing.isEmpty()) {
                    return failure("${codename.codename} has no frames yet, so there is nothing to replace.") {
                        put("expected", "Use \"$MODE_INSERT\" with \"$ARG_AT\" 0 to put the first frames in.")
                    }
                }
                if (at < 0 || at > existing.size - 1) {
                    return failure(outOfRange(at, codename, existing.size))
                }
                if (at + incoming.size > existing.size) {
                    return failure(
                        "Replacing ${incoming.size} frame${if (incoming.size == 1) "" else "s"} from index " +
                            "$at would need frames $at to ${at + incoming.size - 1}, but ${codename.codename} " +
                            "only has ${existing.size} (0 to ${existing.size - 1}).",
                    ) {
                        put(
                            "expected",
                            "Replace at most ${existing.size - at} frame${if (existing.size - at == 1) "" else "s"} " +
                                "from index $at, or use \"$MODE_INSERT\" to add frames past the end.",
                        )
                    }
                }
                removed = incoming.size
            }

            MODE_INSERT -> {
                // `at == size` is an append and is the normal way to extend an
                // animation, so the bound is deliberately inclusive here and
                // exclusive for the other two.
                if (at < 0 || at > existing.size) {
                    return failure(
                        "\"$ARG_AT\" $at is outside ${codename.codename}, which has ${existing.size} " +
                            "frame${if (existing.size == 1) "" else "s"}.",
                    ) {
                        put(
                            "expected",
                            "0 to ${existing.size} for an insert: the new frames go BEFORE the frame that is " +
                                "at that index now, and ${existing.size} appends them at the end.",
                        )
                    }
                }
                removed = 0
            }

            else -> {
                if (existing.isEmpty()) {
                    return failure("${codename.codename} has no frames, so there is nothing to delete.")
                }
                if (at < 0 || at > existing.size - 1) {
                    return failure(outOfRange(at, codename, existing.size))
                }
                val asked = count ?: 1
                if (asked < 1) {
                    return failure("\"$ARG_COUNT\" is $asked.") {
                        put("expected", "At least 1, or null to delete the single frame at \"$ARG_AT\".")
                    }
                }
                if (at + asked > existing.size) {
                    return failure(
                        "Deleting $asked frames from index $at would run past the end: ${codename.codename} " +
                            "has ${existing.size} (0 to ${existing.size - 1}).",
                    ) {
                        put("expected", "At most ${existing.size - at} from index $at.")
                    }
                }
                removed = asked
            }
        }

        val updated = ArrayList<DesignFrame>(existing.size - removed + incoming.size)
        updated.addAll(existing.subList(0, at))
        updated.addAll(incoming)
        updated.addAll(existing.subList(at + removed, existing.size))

        // Checked here as well as in `precisely`, because the arithmetic is the
        // useful part of the answer: "238 + 5 = 243" tells the model how many to
        // drop, and "at most 240 frames per panel" does not.
        if (updated.size > DesignCodec.MAX_FRAMES) {
            return failure(
                "That would leave ${codename.codename} with ${updated.size} frames: ${existing.size} now, " +
                    "$removed removed, ${incoming.size} added.",
            ) {
                put(
                    "expected",
                    "At most ${DesignCodec.MAX_FRAMES} per panel. Add at most " +
                        "${DesignCodec.MAX_FRAMES - existing.size + removed} here, or delete some frames first.",
                )
            }
        }

        var merged = ctx.design.copy(
            variants = ctx.design.variants + (codename.codename to DesignVariant(updated)),
        )
        // See this function's KDoc: a static design that gains a second frame
        // would otherwise be refused by a rule this tool cannot let the model
        // satisfy.
        val promoted = merged.kind == DesignKind.STATIC && updated.size > 1
        if (promoted) merged = merged.copy(kind = DesignKind.DYNAMIC)

        precisely(merged, ctx)?.let { return it.result }
        val design = when (val result = DesignCodec.validate(merged)) {
            is DesignCodec.Result.Ok -> result.design
            is DesignCodec.Result.Invalid -> return failure(result.reason)
        }

        val warnings = ArrayList<String>(2)
        if (updated.isEmpty()) {
            warnings.add(
                "${codename.codename} now has NO frames at all, so that panel will show nothing. If that " +
                    "was not the intention, put a frame back with \"$MODE_INSERT\".",
            )
        }
        if (promoted) {
            warnings.add(
                "This design was \"static\", which may hold only one frame, so it is now \"dynamic\" — " +
                    "otherwise ${updated.size} frames could not be stored. Tell the user; it changes how " +
                    "the design plays.",
            )
        }

        // The window that was touched, plus the frame either side of it: the
        // join is where a wrong `at` shows up, and it is invisible in a picture
        // of the new frames alone.
        val from = (at - 1).coerceAtLeast(0)
        val to = (at + incoming.size).coerceAtMost(updated.size - 1)
        val shown = if (updated.isEmpty()) 0 else minOf(to - from + 1, MAX_PREVIEW_FRAMES)

        return success(
            buildJsonObject {
                put("ok", true)
                put("applied", true)
                put(
                    "note",
                    "This is on the user's canvas now, and ONLY the frames listed below were touched — every " +
                        "other frame of ${codename.codename} is byte for byte what it was. Read the pictures: " +
                        "they include the frame either side of the change, so you can see that the animation " +
                        "still joins up.",
                )
                put("variant", codename.codename)
                put(ARG_MODE, mode)
                put(ARG_AT, at)
                put("removed", removed)
                put("inserted", incoming.size)
                put("frame_count_before", existing.size)
                put("frame_count_after", updated.size)
                put("kind", kindName(design.kind))
                if (promoted) put("kind_changed", true)
                putJsonArray("warnings") { warnings.forEach { add(it) } }
                putJsonArray("frames") {
                    for (i in from until from + shown) {
                        add(
                            buildJsonObject {
                                put("index", i)
                                put("durationMs", updated[i].durationMs)
                                put("changed", i >= at && i < at + incoming.size)
                                put(
                                    "preview",
                                    GlyphAsciiPreview.renderCells(updated[i].cells, design.levels, codename)
                                        ?.let { JsonPrimitive(it) }
                                        ?: JsonPrimitive("(this frame does not decode, so it cannot be drawn)"),
                                )
                            },
                        )
                    }
                }
                put("legend", GlyphAsciiPreview.LEGEND)
            },
            design = design,
        )
    }

    private fun outOfRange(at: Int, codename: PokemonCodename, size: Int): String =
        "\"$ARG_AT\" $at is outside ${codename.codename}, which has $size " +
            "frame${if (size == 1) "" else "s"} (0 to ${size - 1})."

    private fun JsonObjectBuilder.putModes() {
        putJsonArray("modes") {
            add(MODE_REPLACE)
            add(MODE_INSERT)
            add(MODE_DELETE)
        }
        put(
            "expected",
            "\"$MODE_REPLACE\" swaps the frames starting at \"$ARG_AT\" for the ones you send, " +
                "\"$MODE_INSERT\" adds yours before the frame at \"$ARG_AT\" without removing anything, " +
                "and \"$MODE_DELETE\" removes \"$ARG_COUNT\" frames from \"$ARG_AT\".",
        )
    }

    /** The frames to write, or why there are none. */
    private sealed interface Frames {
        data class Ok(val frames: List<DesignFrame>) : Frames
        data class Bad(val result: GlyphToolResult) : Frames
    }

    /**
     * [ARG_FRAME_LIST] as frames.
     *
     * An entry may be `{"durationMs": …, "cells": "…"}` or just the cells
     * string, for the same reason [prepare] accepts a document as an object as
     * well as as text: both are unambiguous, models send both, and refusing one
     * costs a round trip and teaches nothing. A bare string takes the default
     * duration, which is what a model that omitted it meant.
     *
     * The cells themselves are NOT checked here. They go through [precisely]
     * with the rest of the design, so a frame that is one character short is
     * reported in the same words `apply_design` would use.
     */
    private fun readFrames(raw: JsonElement?, codename: PokemonCodename, mode: String): Frames {
        if (raw == null) {
            return Frames.Bad(
                failure("\"$ARG_MODE\" is \"$mode\" but there are no frames to write.") {
                    put("expected", framesExpected(codename))
                },
            )
        }
        if (raw !is JsonArray) {
            return Frames.Bad(
                failure("\"$ARG_FRAME_LIST\" is not an array.") { put("expected", framesExpected(codename)) },
            )
        }
        if (raw.isEmpty()) {
            return Frames.Bad(
                failure("\"$ARG_FRAME_LIST\" is empty, so a \"$mode\" would change nothing.") {
                    put(
                        "expected",
                        if (mode == MODE_REPLACE) {
                            "At least one frame. To remove frames rather than change them, use \"$MODE_DELETE\"."
                        } else {
                            framesExpected(codename)
                        },
                    )
                },
            )
        }
        val out = ArrayList<DesignFrame>(raw.size)
        for (i in raw.indices) {
            val entry = raw[i]
            when {
                entry is JsonPrimitive && entry.isString ->
                    out.add(DesignFrame(DEFAULT_FRAME_DURATION_MS, entry.content))

                entry is JsonObject -> {
                    val cells = (entry["cells"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                        ?: return Frames.Bad(
                            failure("\"$ARG_FRAME_LIST\" entry $i has no \"cells\" string.") {
                                put("expected", framesExpected(codename))
                            },
                        )
                    val duration = when (val d = entry["durationMs"]) {
                        null, is JsonNull -> DEFAULT_FRAME_DURATION_MS
                        else -> (d as? JsonPrimitive)?.content?.toIntOrNull()
                            ?: return Frames.Bad(
                                failure("\"$ARG_FRAME_LIST\" entry $i has a durationMs that is not a whole number.") {
                                    put(
                                        "expected",
                                        "${DesignCodec.MIN_DURATION_MS} to ${DesignCodec.MAX_DURATION_MS} " +
                                            "milliseconds, or leave it out for $DEFAULT_FRAME_DURATION_MS.",
                                    )
                                },
                            )
                    }
                    out.add(DesignFrame(duration, cells))
                }

                else -> return Frames.Bad(
                    failure("\"$ARG_FRAME_LIST\" entry $i is neither a frame object nor a cells string.") {
                        put("expected", framesExpected(codename))
                    },
                )
            }
        }
        return Frames.Ok(out)
    }

    private fun framesExpected(codename: PokemonCodename): String =
        "An array of {\"durationMs\": <ms>, \"cells\": \"<${codename.cellCount} base36 characters>\"} — or " +
            "just the cells string on its own, which takes $DEFAULT_FRAME_DURATION_MS ms. " +
            "${codename.codename} is ${codename.size}x${codename.size}, so every cells string is exactly " +
            "${codename.cellCount} characters, row-major, corners included."

    // endregion

    // region validation

    /** A prepared document, or the error result explaining why there is none. */
    private sealed interface Prepared {
        data class Ok(val design: Design) : Prepared
        data class Bad(val result: GlyphToolResult) : Prepared
    }

    /**
     * Turns the model's `design` argument into a [Design] this app would accept,
     * or into an error precise enough to correct from.
     *
     * The order matters. Variant gating runs **before** decoding, because
     * [DesignCodec] silently *drops* variants for codenames it does not know: a
     * model that wrote `"pikachu"` would otherwise be told its design contained
     * no artwork, or worse, be told nothing at all while its work vanished. The
     * per-frame checks then run before [DesignCodec.validate] even though the
     * codec repeats them, because the codec answers a user ("this design has a
     * frame that is the wrong size for its device") and the model needs an answer
     * it can act on ("variants.bellsprout frame 0 has 168 cells; expected 169").
     * The codec still gets the last word — it is the authority on what this app
     * will store, and a rule added there must never be bypassed here.
     *
     * The merge is **by presence, not by value**: a key the document omits keeps
     * the canvas's value, for a top-level field exactly as for a whole variant.
     * An explicit `null` counts as omitted. See the comment on `merged` and
     * [supplies].
     */
    private fun prepare(arguments: String, ctx: GlyphToolContext): Prepared {
        val args = parseObject(arguments)
            ?: return bad(
                "The tool arguments were not a JSON object.",
            ) { put("expected", "{\"$ARG_DESIGN\": \"<the whole glyph.design document as JSON text>\"}") }

        val raw = args[ARG_DESIGN]
            ?: return bad("Missing the \"$ARG_DESIGN\" argument.") {
                put("expected", "The complete glyph.design document, as JSON text.")
            }

        // A string is what the schema asks for; an object is what models send
        // roughly half the time. Both are unambiguous, so both are accepted —
        // refusing the object form would cost a round trip and teach nothing.
        val root: JsonObject = when {
            raw is JsonPrimitive && raw.isString -> {
                val text = raw.content
                if (text.length > DesignCodec.MAX_CHARS) {
                    return bad("That document is ${text.length} characters.") {
                        put("expected", "At most ${DesignCodec.MAX_CHARS} characters.")
                    }
                }
                parseObject(text) ?: return bad(
                    "The \"$ARG_DESIGN\" argument is not valid JSON.",
                ) { put("expected", "A single JSON object: the whole glyph.design document.") }
            }

            raw is JsonObject -> raw

            else -> return bad("The \"$ARG_DESIGN\" argument is neither JSON text nor a JSON object.") {
                put("expected", "The complete glyph.design document, as JSON text.")
            }
        }

        val allowed = ctx.allowedVariants
        if (allowed.isEmpty()) {
            return bad("This design carries no artwork for any panel this app knows, so it cannot be edited.")
        }

        // Gating first: see the KDoc above.
        val written = root[DesignKey.VARIANTS]
        if (written != null && written !is JsonObject) {
            return bad("\"variants\" is not a JSON object.") {
                put("expected", "An object keyed by panel codename, e.g. {\"${allowed.first().codename}\": {\"frames\": []}}.")
            }
        }
        val writtenVariants = (written as? JsonObject).orEmpty()
        for (key in writtenVariants.keys) {
            val codename = PokemonCodename.ofCodename(key)
            if (codename == null) {
                return bad(
                    "There is no panel called \"$key\".",
                ) { putAllowed(allowed) }
            }
            if (!allowed.contains(codename)) {
                return bad(
                    "This design carries no \"$key\" artwork, so you cannot write it. " +
                        "Only the user can add a panel to a design, from the editor.",
                ) { putAllowed(allowed) }
            }
        }

        val decoded: Design = try {
            LENIENT.decodeFromJsonElement(Design.serializer(), root)
        } catch (e: Exception) {
            return bad("A field of the document has the wrong type (${e.message ?: e.javaClass.simpleName}).") {
                put(
                    "expected",
                    "levels is an array of integers, loop is a boolean, frames is an array of " +
                        "{durationMs, cells}, durationMs is an integer and cells is a string.",
                )
            }
        }

        // Fields the app owns are taken from the design on screen, never from the
        // model: an id is a filename, and a rewritten createdAt would relabel a
        // drawing the user made last month.
        //
        // Everything else is *merged*, and merged by the same rule throughout: a
        // key the model left out is a key the model is not changing. A variant it
        // left out is kept exactly as it was, which is what makes "change only the
        // arbok frames" a sentence the model can act on without re-sending 150 kB
        // of bellsprout — and a top-level field it left out has to be kept for the
        // same reason, because a model asked to change only the art quite
        // reasonably sends only "variants". Reading [decoded] unconditionally
        // would hand that model [Design]'s *defaults* instead of its own document:
        // a blank name, a design forced to static, and `levels` reset to three
        // entries — which, since cells are palette *indices*, silently re-lights
        // every pixel in the drawing.
        val merged = ctx.design.copy(
            format = DESIGN_FORMAT,
            name = if (root.supplies(DesignKey.NAME)) decoded.name else ctx.design.name,
            kind = if (root.supplies(DesignKey.KIND)) decoded.kind else ctx.design.kind,
            keyMode = if (root.supplies(DesignKey.KEY_MODE)) decoded.keyMode else ctx.design.keyMode,
            loop = if (root.supplies(DesignKey.LOOP)) decoded.loop else ctx.design.loop,
            levels = if (root.supplies(DesignKey.LEVELS)) decoded.levels else ctx.design.levels,
            variants = ctx.design.variants + writtenVariants.keys.associateWith {
                decoded.variants[it] ?: DesignVariant()
            },
        )

        precisely(merged, ctx)?.let { return it }

        return when (val result = DesignCodec.validate(merged)) {
            is DesignCodec.Result.Ok -> Prepared.Ok(result.design)
            // Everything the codec rejects that this file checks first has
            // already been reported in the model's own terms; reaching here means
            // a rule only the codec knows, so its sentence is the honest answer.
            is DesignCodec.Result.Invalid -> bad(result.reason)
        }
    }

    /**
     * The first thing wrong with [design], phrased for whoever has to fix it, or
     * null if there is nothing.
     *
     * One problem at a time on purpose: a list of forty complaints about the same
     * off-by-one is noise, and the model will re-send the whole document anyway.
     */
    private fun precisely(design: Design, ctx: GlyphToolContext): Prepared.Bad? {
        if (design.name.length > DesignCodec.MAX_NAME_LENGTH) {
            return bad("The name is ${design.name.length} characters.") {
                put("expected", "At most ${DesignCodec.MAX_NAME_LENGTH} characters.")
            }
        }
        if (design.levels.isEmpty()) {
            return bad("\"levels\" is empty, so no cell could mean anything.") {
                put("expected", "At least one brightness, e.g. [0, 2048, 4095].")
            }
        }
        if (design.levels.size > DesignFrames.MAX_PALETTE) {
            return bad("\"levels\" has ${design.levels.size} entries.") {
                put("expected", "At most ${DesignFrames.MAX_PALETTE} — one base36 character addresses no more.")
            }
        }

        for (codename in ctx.allowedVariants) {
            val frames = design.variantFor(codename)?.frames ?: continue
            val where = "variants.${codename.codename}"

            if (frames.size > DesignCodec.MAX_FRAMES) {
                return bad("$where has ${frames.size} frames.") {
                    put("expected", "At most ${DesignCodec.MAX_FRAMES} frames per panel.")
                }
            }
            if (design.kind == DesignKind.STATIC && frames.size > 1) {
                return bad(
                    "\"kind\" is \"static\" but $where has ${frames.size} frames. A static design is " +
                        "exactly one frame, and the rest would never be shown.",
                ) {
                    put("expected", "Either set \"kind\" to \"dynamic\", or send a single frame.")
                }
            }

            for ((index, frame) in frames.withIndex()) {
                if (frame.durationMs < DesignCodec.MIN_DURATION_MS ||
                    frame.durationMs > DesignCodec.MAX_DURATION_MS
                ) {
                    return bad("$where frame $index has durationMs ${frame.durationMs}.") {
                        put(
                            "expected",
                            "${DesignCodec.MIN_DURATION_MS} to ${DesignCodec.MAX_DURATION_MS} " +
                                "inclusive (out of range is rejected, not clamped).",
                        )
                    }
                }
                if (frame.cells.length != codename.cellCount) {
                    return bad(
                        "$where frame $index has ${frame.cells.length} cells.",
                    ) {
                        put(
                            "expected",
                            "Exactly ${codename.cellCount} characters — ${codename.codename} is " +
                                "${codename.size}x${codename.size}, one base36 palette index per cell, " +
                                "row-major. The corner cells outside the disc must be present too; " +
                                "write '0' there.",
                        )
                    }
                }
                cellProblem(frame.cells, design.levels, codename)?.let { problem ->
                    return bad("$where frame $index ${problem.first}") { put("expected", problem.second) }
                }
            }
        }
        return null
    }

    /**
     * The first cell of [cells] that will not decode, as (what happened, what was
     * expected), or null if every character is a palette index this design
     * defines.
     *
     * Position is reported as a column and a row as well as an offset: `"the
     * character at 84"` is not something anybody can find in a 169-character
     * string, whereas `(column 6, row 6)` is the middle of the panel.
     */
    private fun cellProblem(
        cells: String,
        levels: List<Int>,
        codename: PokemonCodename,
    ): Pair<String, String>? {
        val legal = legalChars(levels)
        for (i in cells.indices) {
            val c = cells[i]
            val index = base36(c)
            val at = "at position $i (column ${i % codename.size}, row ${i / codename.size})"
            if (index < 0) {
                return "uses the character '$c' $at, which is not a base36 digit." to
                    "A palette index in base36: '0'-'9' then 'a'-'z'. This design defines " +
                    "${levels.size} level${if (levels.size == 1) "" else "s"}, so the only legal " +
                    "characters are $legal."
            }
            if (index >= levels.size) {
                return "uses palette index $index ('$c') $at, but \"levels\" defines only " +
                    "${levels.size} entr${if (levels.size == 1) "y" else "ies"}." to
                    "Either use $legal, or add the level you meant to \"levels\"."
            }
        }
        return null
    }

    /**
     * The characters a design with this [levels] may legally use, spelled the way
     * an error message should say it.
     *
     * Shared by [cellProblem] and [scrollFrames] so a model told off by one of
     * them and then by the other is told the same thing twice, not two things.
     */
    private fun legalChars(levels: List<Int>): String {
        val highest = levels.size - 1
        return if (highest <= 9) "'0'..'$highest'" else "'0'..'9' then 'a'..'${'a' + (highest - 10)}'"
    }

    private fun base36(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'z' -> c - 'a' + 10
        in 'A'..'Z' -> c - 'A' + 10
        else -> -1
    }

    // endregion

    // region json

    /**
     * Forgiving in exactly the ways [DesignCodec]'s reader is, and for the same
     * reason: a document with one unexpected key, or `"kind": "kaleidoscope"`,
     * should reach the *specific* checks above rather than dying as "wrong type".
     */
    private val LENIENT = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private fun parseObject(text: String): JsonObject? = try {
        LENIENT.parseToJsonElement(text) as? JsonObject
    } catch (e: Exception) {
        null
    }

    /**
     * The JSON key each top-level [Design] field is written under.
     *
     * Derived from the serializer rather than spelled out as string literals,
     * because [prepare] decides whether to keep the canvas's value by asking
     * whether the model supplied *that key*. A `@SerialName` added to [Design]
     * later would rename the key in the file while leaving a literal here
     * pointing at a name nothing writes any more — every question would quietly
     * answer "not supplied", and every document would stop being able to change
     * anything. Deriving them means a rename moves this file with it.
     *
     * Each key is read back out of a document in which that one field, and only
     * that one, differs from its default: with `encodeDefaults` off, the single
     * key that survives *is* the field's serial name.
     */
    private object DesignKey {
        /** Declared first: the vals below run at class-init in source order. */
        private val PROBE = Json { encodeDefaults = false }

        val NAME = keyOf("name") { it.copy(name = "probe") }
        val KIND = keyOf("kind") { it.copy(kind = DesignKind.DYNAMIC) }
        val KEY_MODE = keyOf("keyMode") { it.copy(keyMode = KeyMode.PLAY_ONCE) }
        val LOOP = keyOf("loop") { it.copy(loop = true) }
        val LEVELS = keyOf("levels") { it.copy(levels = listOf(1)) }
        val VARIANTS = keyOf("variants") { it.copy(variants = mapOf("probe" to DesignVariant())) }

        /**
         * The key [alter]'s one change lands under, or [fallback] if the probe
         * ever stops naming exactly one field — a field turned `@Transient`, say.
         * Falling back to today's spelling is no worse than having hardcoded it,
         * and nothing in this file throws, class initialisation least of all.
         */
        private fun keyOf(fallback: String, alter: (Design) -> Design): String = try {
            PROBE.encodeToJsonElement(Design.serializer(), alter(Design()))
                .jsonObject.keys.singleOrNull() ?: fallback
        } catch (e: Exception) {
            fallback
        }
    }

    /**
     * Whether the model actually wrote [key] — with an explicit `null` deliberately
     * counting as **not written**.
     *
     * `{"name": null}` is a model declining to say what the name is, not one
     * asking for it to be blanked; there is no way to express "erase this" that
     * is not just sending the erased value (`""`), and the app always holds a
     * sane current value either way. Erroring on it would be worse still: it
     * would fail a document over a key that changes nothing. Note that a null
     * cannot be honoured *literally* even if we wanted to — [LENIENT] coerces it
     * into [Design]'s default, so "respecting" it would blank a name via a key
     * that says nothing. Same rule for every field, and the same rule variants
     * already follow: say nothing, change nothing.
     */
    private fun JsonObject.supplies(key: String): Boolean {
        val value = this[key]
        return value != null && value !is JsonNull
    }

    private fun success(
        obj: JsonObject,
        design: Design? = null,
        validated: Design? = null,
    ): GlyphToolResult =
        GlyphToolResult(json = obj.toString(), isError = false, design = design, validated = validated)

    /**
     * Named `failure` rather than `error` so it cannot be confused with — or
     * resolved against — Kotlin's built-in `error(message)`, which throws.
     * Nothing in this file throws.
     */
    private fun failure(message: String, extras: JsonObjectBuilder.() -> Unit = {}): GlyphToolResult =
        GlyphToolResult(
            json = buildJsonObject {
                put("ok", false)
                put("error", message)
                extras()
            }.toString(),
            isError = true,
        )

    private fun bad(message: String, extras: JsonObjectBuilder.() -> Unit = {}): Prepared.Bad =
        Prepared.Bad(failure(message, extras))

    private fun JsonObjectBuilder.putAllowed(allowed: List<PokemonCodename>) {
        putJsonArray("allowed_variants") { allowed.forEach { add(it.codename) } }
        put(
            "expected",
            "You may only write ${allowed.joinToString(" and ") { "\"${it.codename}\"" }} — " +
                "the panels this design already carries.",
        )
    }

    /** The shared tail of an apply/validate success: what the document became. */
    private fun JsonObjectBuilder.putSummary(design: Design, ctx: GlyphToolContext) {
        put("name", design.name)
        put("kind", kindName(design.kind))
        put("keyMode", keyModeName(design))
        put("loop", design.loop)
        putJsonArray("levels") { design.levels.forEach { add(it) } }
        putJsonArray("allowed_variants") { ctx.allowedVariants.forEach { add(it.codename) } }
        put("variants", variantsJson(design, ctx.allowedVariants, includeCells = false))
        put("legend", GlyphAsciiPreview.LEGEND)
    }

    /**
     * Each carried variant as JSON, with a rendering of every frame (up to
     * [MAX_PREVIEW_FRAMES]).
     *
     * [includeCells] is false on the way *back* to the model: it just sent those
     * characters, and echoing 169 or 625 of them per frame would double a
     * message that is already the largest thing in the conversation. It is true
     * on the way *out*, where the cells are the point.
     */
    private fun variantsJson(
        design: Design,
        allowed: List<PokemonCodename>,
        includeCells: Boolean,
    ): JsonObject = buildJsonObject {
        for (codename in allowed) {
            val frames = design.variantFor(codename)?.frames.orEmpty()
            putJsonObject(codename.codename) {
                put("size", codename.size)
                put("cells_length", codename.cellCount)
                put("live_leds", PanelMask.count(codename.size))
                put("frame_count", frames.size)
                val shown = minOf(frames.size, MAX_PREVIEW_FRAMES)
                if (shown < frames.size) {
                    put("previews_truncated", true)
                    put("previewed_frames", shown)
                }
                put(
                    "frames",
                    buildJsonArray {
                        for (i in frames.indices) {
                            val frame = frames[i]
                            add(
                                buildJsonObject {
                                    put("index", i)
                                    put("durationMs", frame.durationMs)
                                    if (includeCells) put("cells", frame.cells)
                                    if (i < shown) {
                                        val preview = GlyphAsciiPreview.renderCells(
                                            frame.cells,
                                            design.levels,
                                            codename,
                                        )
                                        // Null only for a frame this app would
                                        // refuse anyway; say so rather than
                                        // drawing a picture of something illegal.
                                        put(
                                            "preview",
                                            preview?.let { JsonPrimitive(it) }
                                                ?: JsonPrimitive("(this frame does not decode, so it cannot be drawn)"),
                                        )
                                    }
                                },
                            )
                        }
                    },
                )
            }
        }
    }

    private fun kindName(kind: DesignKind): String =
        if (kind == DesignKind.STATIC) "static" else "dynamic"

    private fun keyModeName(design: Design): String =
        if (design.keyMode == KeyMode.PLAY_ONCE) "playOnce" else "playPause"

    private fun JsonObject?.orEmpty(): JsonObject = this ?: JsonObject(emptyMap())

    // endregion

    // region specs

    // Written as literal JSON, like pulseloop's, so what the model is shown is
    // exactly what is in this file — a schema assembled at runtime is a schema
    // nobody can read in review. `GlyphAiToolsTest` parses each of these, so a
    // stray quote is a failing test rather than a broken conversation.

    private const val SPEC_GET_CURRENT_DESIGN =
        """{"type":"function","name":"get_current_design","description":"Returns the design exactly as it appears on the user's canvas right now, including edits they have not saved: its name, kind, keyMode, loop and levels, every panel it carries with each frame's cells, an ASCII rendering of each frame with the round panel mask applied, and which panel and frame the editor has open. Call this before your first edit, and again whenever the user may have drawn something since.","parameters":{"type":"object","properties":{},"required":[],"additionalProperties":false},"strict":true}"""

    private const val SPEC_APPLY_DESIGN =
        """{"type":"function","name":"apply_design","description":"Replaces the user's design with the document you supply; it appears on their canvas immediately. Send the COMPLETE glyph.design document as JSON text - every frame you want to keep, not just the ones you changed. A panel you omit entirely is left exactly as it was, and so is any of name, kind, keyMode, loop or levels that you omit - leaving a key out means 'do not change this', never 'reset this'. You may only write panels the design already carries; you cannot add one. format, formatVersion, id, author, createdAt, createdWith and modifiedAt are managed by the app and ignored if you send them. The result contains an ASCII rendering of every frame that was applied: read it, because it is the only way to see whether your art is centred on the disc rather than clipped by it.","parameters":{"type":"object","properties":{"design":{"type":"string","description":"The complete glyph.design document, as JSON text."}},"required":["design"],"additionalProperties":false},"strict":true}"""

    // Every argument but source_rows is nullable AND required: strict function
    // calling insists that `required` name every property, so "you may leave this
    // out" has to be spelled as "you may send null". The defaults are where three
    // of this tool's four guarantees live, so null is the answer the model should
    // usually give.
    private const val SPEC_SCROLL_FRAMES =
        """{"type":"function","name":"scroll_frames","description":"Turns ONE wide bitmap into a scrolling animation, doing the windowing arithmetic for you. You draw the whole message once - as tall as your glyphs and as wide as the message - and this cuts a panel-width window out of it at each successive offset, pads the rows above and below with '0', works out how many frames the traverse takes, and returns every frame with an ASCII picture of it. USE THIS FOR ANY SCROLLING TEXT OR MOVING IMAGE. Windowing by hand is how a glyph shears apart (one row shifted a column further than the row above it), how frame 0 comes out blank, how an element changes brightness halfway through and how a marquee ends up with a third of the frames it needs; none of those four is expressible here. It changes NOTHING: read the pictures, then send the \"apply_this\" document it returns to apply_design exactly as it came back, without retyping the cells. The message scrolls right to left; to scroll it the other way, reverse the order of the frames before you apply them.","parameters":{"type":"object","properties":{"source_rows":{"type":"array","items":{"type":"string"},"description":"The whole message as ONE bitmap: equal-length strings, one per row, in the same base36 palette-index encoding as cells. Row 0 is the top row. \"HI\" is [\"1010111\",\"1010010\",\"1110010\",\"1010010\",\"1010111\"] - three columns for the H, one blank column, three for the I."},"variant":{"type":["string","null"],"description":"Which panel to build the frames for. null means the one the editor has open, or the only one the design carries."},"top_row":{"type":["integer","null"],"description":"The panel row source row 0 sits on. null centres the art in the band of rows that is live across every column, which is the only placement that keeps every cell at every horizontal offset. Art outside that band still works and is warned about."},"start_column":{"type":["integer","null"],"description":"The source column shown at the panel's LEFT edge in frame 0; negative means the message is still entering from the right. null starts so the message's leading column is already on the panel, which is what stops frame 0 being blank. A start that does produce blank frames is honoured and reported, not silently emitted."},"step":{"type":["integer","null"],"description":"Columns moved per frame. null means 1, which is smoothest. 2 halves the frame count and still reads."},"frames":{"type":["integer","null"],"description":"How many frames to generate. null means the full traverse - panel width + message width - 1 at one column per frame - which is what a marquee actually needs. Fewer stops the scroll mid-message and is warned about."},"duration_ms":{"type":["integer","null"],"description":"How long each frame is held. null means 120. 80-200 reads well for a scroll."}},"required":["source_rows","variant","top_row","start_column","step","frames","duration_ms"],"additionalProperties":false},"strict":true}"""

    // Only `text` carries a decision; everything else may be null and null is
    // almost always right. The description names the symbol set in full, because
    // "printable ASCII" is a claim the model would otherwise have to test one
    // character at a time against a tool that refuses.
    private const val SPEC_MARQUEE_TEXT =
        """{"type":"function","name":"marquee_text","description":"Scrolls a phrase right to left in the app's own full-height letterforms. USE THIS FOR ANY SCROLLING WORDS - prefer it over scroll_frames, because here you do not draw the letters at all: the app has a nine-row proportional alphabet built in, upper and lower case, so an S cannot come back looking like a 5 and a W cannot come back two columns too narrow to be a W. The letters fill the panel (9 of 13 rows at 13x13, 18 of 25 at 25x25) and the round rim cuts their tops and bottoms as they enter and leave, which is deliberate and is most of why they read as BIG. It changes NOTHING. Read \"strip\" first - it is the entire phrase as one nine-row picture, and it is the only place a wrong letter is actually visible - then send the \"apply_this\" document to apply_design EXACTLY as it came back, without retyping the cells. apply_this sets kind to dynamic and loop to true and writes one panel's frames; keyMode, levels, name and any other panel are left untouched. Around 40 characters fit inside the 240-frame limit, and a phrase that does not fit is refused with the longest prefix that does AND the step that would make the whole phrase fit, so a refusal is answerable in one move. Reach for scroll_frames instead only when the thing scrolling is a picture rather than words, or when you want letterforms of your own.","parameters":{"type":"object","properties":{"text":{"type":"string","description":"The phrase to scroll. Letters A-Z and a-z, digits 0-9, a space, and the printable ASCII symbols !\"#${'$'}%&'()*+,-./:;<=>?@[\\]^_`{|}~ . Both cases are drawn - the lower case has its own x-height, ascenders and descenders - and accents are dropped automatically (\"café\" scrolls as cafe). Leading or trailing spaces become a gap before the loop repeats. This is the only argument you have to think about."},"variant":{"type":["string","null"],"description":"Which panel to build the frames for. null means the one the editor has open, or the only one the design carries."},"scale":{"type":["integer","null"],"description":"How many panel cells one letter cell becomes. null means 1 at 13x13 and 2 at 25x25, so the letters fill the same fraction of either panel. Lowering it makes the letters smaller and lets a longer phrase fit."},"step":{"type":["integer","null"],"description":"Panel columns moved per frame. null means the scale - exactly one letter-cell - which is the smoothest step that is not wasted and gives the same frame count on both panels. Doubling it halves the frame count and still reads."},"duration_ms":{"type":["integer","null"],"description":"How long each frame is held. null means 80, a little over two letters a second. Raise it to slow the scroll down; Nothing's own big-letter marquee is slower than this and reads as sluggish."},"palette_index":{"type":["integer","null"],"description":"Which entry of this design's levels the letters are lit at, the same in every frame. null means the brightest one, which is what a marquee wants. 0 is the off level and is refused."}},"required":["text","variant","scale","step","duration_ms","palette_index"],"additionalProperties":false},"strict":true}"""

    // Every argument is nullable AND required, for the reason spelled out above
    // SPEC_SCROLL_FRAMES: strict function calling insists `required` names every
    // property, so "leave this out" is spelled "send null".
    private const val SPEC_IMAGE_TO_GRID =
        """{"type":"function","name":"image_to_grid","description":"Converts an image the user attached to THIS message into one frame of art, doing the downscaling for you: the whole picture is scaled to fit the panel with its aspect ratio kept, box-averaged down to one value per cell, masked so nothing lands on a cell that has no LED, and quantised to this design's own levels. USE THIS FOR ANY 'put this photo/logo/screenshot on my panel' REQUEST - you can see the image, but you cannot say what it averages to at cell (7, 4), and hand-writing 169 base36 characters from a photograph is how a request for a picture takes fourteen attempts. It changes NOTHING: it returns the frame, its cells and an ASCII picture of it. LOOK AT THE PICTURE. If it reads, apply the \"apply_this\" document or put its cells into a frame with set_frames; if it does not, change threshold, contrast or invert and call again, or abandon the literal conversion and draw the silhouette yourself - at this size that is often the better answer. An attachment only travels with the message it was sent on, so this cannot reach a photo from an earlier turn.","parameters":{"type":"object","properties":{"image_index":{"type":["integer","null"],"description":"Which attached image to convert, counting from 0 in the order they were attached. null means 0, the first one."},"variant":{"type":["string","null"],"description":"Which panel to convert it for. null means the one the editor has open, or the only one the design carries."},"threshold":{"type":["number","null"],"description":"Where the cut between off and lit goes, 0.0 to 0.95, after the image has been stretched onto its own darkest and brightest cell. null picks the cut that best separates this image's light and dark cells, which is usually better than a number - the value used is reported back so you can nudge it. Higher keeps only the brightest cells; lower lights more of the picture."},"contrast":{"type":["number","null"],"description":"Gain around the mid-point, 0.25 to 4.0, applied after the image is normalised. null means 1.0, which changes nothing. Above 1 pushes light and dark apart, which is what a flat or hazy photograph needs."},"invert":{"type":["boolean","null"],"description":"Swap light and dark. null means false. Set it true when the subject is DARK on a LIGHT background - a logo on white paper, a screenshot, printed text - or the panel lights the background instead of the subject."}},"required":["image_index","variant","threshold","contrast","invert"],"additionalProperties":false},"strict":true}"""

    private const val SPEC_SET_FRAMES =
        """{"type":"function","name":"set_frames","description":"Changes a RANGE of frames in one panel and leaves every other frame untouched - use this instead of apply_design whenever you are editing part of an animation. apply_design replaces the whole document, so changing frame 7 of a 240-frame design means re-sending every frame: slow, and every re-send is a chance to corrupt a frame that was already right. This applies IMMEDIATELY, like apply_design, and is checked by exactly the same rules (cells length, base36 palette indices, frame durations, the frame limit, and the panels this design carries). It returns pictures of the frames it wrote AND of the frame either side, so you can see that the animation still joins up. A static design that ends up with more than one frame is switched to dynamic, and the result says so.","parameters":{"type":"object","properties":{"variant":{"type":["string","null"],"description":"Which panel to change. null means the one the editor has open, or the only one the design carries."},"mode":{"type":"string","enum":["replace","insert","delete"],"description":"replace: swap the frames starting at 'at' for the ones you send, one for one. insert: add yours BEFORE the frame at 'at', removing nothing; 'at' equal to the frame count appends. delete: remove 'count' frames from 'at'."},"at":{"type":"integer","description":"The frame index the change starts at, counting from 0. For insert it may equal the current frame count, which appends."},"count":{"type":["integer","null"],"description":"How many frames to delete. Only for mode delete; null means 1. Send null for replace and insert - there the number of frames is however many you put in 'frames'."},"frames":{"type":["array","null"],"items":{"type":"object","properties":{"durationMs":{"type":["integer","null"]},"cells":{"type":"string"}},"required":["durationMs","cells"],"additionalProperties":false},"description":"The frames to write, for replace and insert. Each cells string is exactly size*size base36 palette indices, row-major, corners included. durationMs may be null for 120. Send null for mode delete."}},"required":["variant","mode","at","count","frames"],"additionalProperties":false},"strict":true}"""

    private const val SPEC_VALIDATE_DESIGN =
        """{"type":"function","name":"validate_design","description":"Runs every check apply_design runs and changes NOTHING. Same arguments, same errors, same ASCII renderings - so it is a free look at what you are about to make, and it costs the user no undo. Use it whenever you are unsure about a document, then send the identical document to apply_design.","parameters":{"type":"object","properties":{"design":{"type":"string","description":"The complete glyph.design document, as JSON text."}},"required":["design"],"additionalProperties":false},"strict":true}"""

    // endregion
}
