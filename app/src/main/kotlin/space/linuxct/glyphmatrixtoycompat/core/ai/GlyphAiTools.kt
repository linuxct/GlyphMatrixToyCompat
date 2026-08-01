package space.linuxct.glyphmatrixtoycompat.core.ai

import kotlinx.serialization.json.Json
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
import space.linuxct.glyphmatrixtoycompat.core.design.DESIGN_FORMAT
import space.linuxct.glyphmatrixtoycompat.core.design.Design
import space.linuxct.glyphmatrixtoycompat.core.design.DesignCodec
import space.linuxct.glyphmatrixtoycompat.core.design.DesignFrames
import space.linuxct.glyphmatrixtoycompat.core.design.DesignKind
import space.linuxct.glyphmatrixtoycompat.core.design.DesignVariant
import space.linuxct.glyphmatrixtoycompat.core.design.KeyMode
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
 * The design assistant's tools: read the canvas, check a document, write it back.
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

    /** The argument every writing tool takes: a whole `glyph.design` document. */
    const val ARG_DESIGN = "design"

    /**
     * How many frames of a variant get an ASCII rendering in one tool result.
     *
     * Previews exist to be *read*, and a 240-frame `arbok` design would render
     * as 6 000 lines — a payload that pushes the conversation out of context and
     * that no model will study frame by frame anyway. Every design a person
     * draws by hand is well under this, so in practice the cap never fires; when
     * it does, the result says so explicitly (`previews_truncated`) rather than
     * letting the model believe it has seen everything.
     */
    const val MAX_PREVIEW_FRAMES = 16

    /** Every tool, in the order the model should meet them. */
    fun build(): List<GlyphTool> = listOf(
        GlyphTool(GET_CURRENT_DESIGN, SPEC_GET_CURRENT_DESIGN) { _, ctx -> getCurrentDesign(ctx) },
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
        val highest = levels.size - 1
        val legal = if (highest <= 9) {
            "'0'..'$highest'"
        } else {
            "'0'..'9' then 'a'..'${'a' + (highest - 10)}'"
        }
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

    private const val SPEC_VALIDATE_DESIGN =
        """{"type":"function","name":"validate_design","description":"Runs every check apply_design runs and changes NOTHING. Same arguments, same errors, same ASCII renderings - so it is a free look at what you are about to make, and it costs the user no undo. Use it whenever you are unsure about a document, then send the identical document to apply_design.","parameters":{"type":"object","properties":{"design":{"type":"string","description":"The complete glyph.design document, as JSON text."}},"required":["design"],"additionalProperties":false},"strict":true}"""

    // endregion
}
