package space.linuxct.glyphworks.ui.design

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import space.linuxct.glyphworks.R
import space.linuxct.glyphworks.core.design.Design
import space.linuxct.glyphworks.core.design.DesignFrame
import space.linuxct.glyphworks.core.design.DesignKind
import space.linuxct.glyphworks.core.design.DesignVariant
import space.linuxct.glyphworks.core.design.MarqueePlan
import space.linuxct.glyphworks.core.design.MarqueeText
import space.linuxct.glyphworks.ui.DIALOG_VERTICAL_MARGIN

/**
 * "Type a phrase, get it scrolling" — the editor's half of [MarqueeText].
 *
 * ## Why the editor gets its own door to the generator
 *
 * The alphabet and the scroll arithmetic were built for the assistant, which is
 * the wrong place for them to *only* live: scrolling text is the single most
 * asked-for thing a 13x13 panel does, and until now the way to get it was to
 * sign in to a language model and ask it nicely. This dialog calls exactly the
 * same [MarqueeText.plan] the `marquee_text` tool calls, with the same defaults,
 * so the two cannot drift into producing different animations from the same
 * phrase — and so a refusal here quotes the generator's own numbers rather than
 * a second opinion about how long a phrase may be.
 *
 * ## What it does to the design that is already open
 *
 * **It replaces it**, and the way back is the undo arrow.
 *
 * A marquee is a whole animation — kind, loop and every frame of the panel being
 * edited — so there is no way to "add" one to an existing drawing that is not
 * really a replacement wearing a politer word. This takes the path the assistant
 * already takes: build the document, hand it to [EditorState.replaceDesign], let
 * the ordinary debounced save write it. Everything downstream treats the result
 * as art the user drew: it is editable frame by frame, the timeline is the new
 * timeline, and the file is written through the same code as every other change.
 *
 * The difference from the assistant's use of that path is one argument:
 * `recordUndo`. The document this generated over goes onto the editor's
 * whole-document undo stack, so the tool row's undo arrow — the affordance the
 * user already has, two buttons to the left of the one they just pressed —
 * brings the artwork back, and redo puts the marquee back after that.
 *
 * **Which is why there is no confirmation step.** There used to be one, and it
 * said the replacement could not be undone. That was true when it was written and
 * is now false; a second tap that exists to repeat a false warning is worse than
 * no second tap, because it teaches people to dismiss the warnings that mean
 * something. What survives it is the *count* — how many drawn frames this is
 * about to replace — which was the only part of that dialog carrying information,
 * and it is now a line under the phrase where it can be read before the button is
 * pressed rather than after. See [MarqueeStatus].
 *
 * The panel being edited is the only one touched. A design that also carries the
 * other geometry keeps that artwork, exactly as `marquee_text` leaves it: the
 * two sizes are independent drawings everywhere else in this app and a text tool
 * is not the place to invent scaling between them.
 *
 * ## Only ever opened over an animation
 *
 * The tool-row button that opens this is composed only for a `DYNAMIC` design
 * (see `ToolRow`), because generating a marquee onto a static one would silently
 * convert the document. So the `kind` this writes below is already the design's
 * own kind on every path that reaches here from the editor; it is still written,
 * because [applyMarquee] is what makes the result a *marquee* and a document that
 * only accidentally satisfies its own invariant is one refactor from not.
 */
@Composable
internal fun MarqueeDialog(state: EditorState, onDismiss: () -> Unit, onGenerated: () -> Unit) {
    // rememberSaveable: the keyboard is up the whole time this dialog is open,
    // and on a phone that is the configuration change most likely to happen
    // while somebody is typing into it.
    var text by rememberSaveable { mutableStateOf("") }
    // Set only by "scroll it faster", from the number the refusal returned. null
    // means the generator's own default, which is what almost every phrase uses.
    var step by rememberSaveable { mutableStateOf<Int?>(null) }

    // **The faster step applies only while it is needed.** Held rather than
    // spent, because a phrase edited back and forth over the limit would
    // otherwise need the button pressing again on every crossing; dropped the
    // moment the default fits, because a scroll left running at twice the
    // designed speed for a phrase that no longer needs it is a defect nothing on
    // screen would explain.
    val plan = remember(state, text, step) {
        val default = marqueePlanFor(state, text)
        if (step == null || default is MarqueePlan.Ready) default else marqueePlanFor(state, text, step)
    }
    val ready = plan as? MarqueePlan.Ready
    // Counted once, as the dialog opens, and not observed afterwards: this is a
    // modal over the canvas, so nobody can draw while it is up, and walking every
    // cell of every frame on each keystroke would be work with no possible answer
    // but the one already in hand.
    val drawnFrames = remember(state) { drawnFrameCount(state) }

    AlertDialog(
        modifier = Modifier.padding(vertical = DIALOG_VERTICAL_MARGIN),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.marquee_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    // A marquee is one line by definition, so a newline is a
                    // space and nothing else needs saying about it. There is no
                    // length cap here on purpose: the only real limit is the
                    // frame budget, it depends on which letters were typed, and
                    // the generator already measures it exactly.
                    onValueChange = { text = it.replace('\n', ' ') },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.marquee_field)) },
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                MarqueeStatus(
                    plan = plan,
                    drawnFrames = drawnFrames,
                    onTrim = { text = it },
                    onFaster = { step = it },
                )
            }
        },
        confirmButton = {
            TextButton(
                // Driven by the plan, so the button and the message under the
                // field can never disagree about whether this phrase works.
                enabled = ready != null,
                onClick = {
                    val frames = ready?.frames
                    if (frames != null && applyMarquee(state, frames)) onGenerated()
                    onDismiss()
                },
            ) {
                Text(stringResource(R.string.marquee_generate))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.marquee_cancel)) }
        },
    )
}

/**
 * The lines under the field: what the phrase costs, what it costs the artwork
 * that is already there, or what is wrong with it and the one tap that fixes it.
 *
 * Both refusals come from [MarqueeText.plan] with their numbers attached, and
 * both of the too-long answers are offered as *buttons* rather than as advice.
 * A message that says "39 characters would fit" leaves the user counting
 * characters; a button that trims to the prefix the generator measured does the
 * counting, and a button that raises the step to the one it worked out keeps the
 * whole phrase for the price of a faster scroll.
 *
 * [drawnFrames] is what the confirmation dialog used to exist for. It is shown
 * beside the cost rather than behind a second tap, and **only when the phrase
 * actually works**: a number about what is going to be replaced is noise under a
 * message saying nothing is going to happen. It is silent on an untouched canvas,
 * for the reason it always was — every cell of every frame off means there is
 * nothing to replace, and telling somebody they are about to lose a blank canvas
 * teaches them to stop reading.
 */
@Composable
private fun MarqueeStatus(
    plan: MarqueePlan,
    drawnFrames: Int,
    onTrim: (String) -> Unit,
    onFaster: (Int) -> Unit,
) {
    val caption = MaterialTheme.typography.bodySmall
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    when (plan) {
        is MarqueePlan.Ready -> Column {
            Text(
                pluralStringResource(
                    R.plurals.marquee_ready,
                    plan.frames.size,
                    plan.frames.size,
                    formatTotalValue(plan.frames.sumOf { it.durationMs }),
                ),
                style = caption,
                color = muted,
            )
            if (drawnFrames > 0) {
                Text(
                    pluralStringResource(R.plurals.marquee_replaces, drawnFrames, drawnFrames),
                    style = caption,
                    color = muted,
                )
            }
        }

        is MarqueePlan.Unsupported -> Text(
            stringResource(
                R.string.marquee_unsupported,
                plan.characters.joinToString(" ") { "“$it”" },
            ),
            style = caption,
            color = muted,
        )

        is MarqueePlan.TooLong -> Column {
            Text(
                pluralStringResource(
                    R.plurals.marquee_too_long,
                    plan.framesNeeded,
                    plan.framesNeeded,
                    plan.maxFrames,
                ),
                style = caption,
                color = muted,
            )
            Row(Modifier.fillMaxWidth()) {
                if (plan.prefix.isNotEmpty()) {
                    TextButton(onClick = { onTrim(plan.prefix) }) {
                        Text(stringResource(R.string.marquee_trim, plan.prefix))
                    }
                }
                val faster = plan.stepThatFits
                if (faster != null) {
                    TextButton(onClick = { onFaster(faster) }) {
                        Text(stringResource(R.string.marquee_faster))
                    }
                }
            }
        }

        // The empty field, which is where this dialog opens, so it says what to
        // do rather than what went wrong.
        MarqueePlan.Blank -> Text(
            stringResource(R.string.marquee_hint),
            style = caption,
            color = muted,
        )
    }
}

/**
 * What [MarqueeText] would produce for [text] on the panel [state] has open.
 *
 * The one place the editor's choices are made, so that the plan the dialog shows
 * and the frames it applies are the same call with the same arguments:
 *
 * - **the geometry** is the open variant's, because that is the canvas on screen;
 * - **the brightness** is the swatch the user is painting with, when that is a
 *   lit one — a marquee generated while holding the mid-grey brush comes out
 *   mid-grey, which is the only reading of "the colour I picked" that is not a
 *   surprise. An `off` brush would produce 240 dark frames, so it falls back to
 *   the brightest level the design has, which is what `marquee_text` always uses;
 * - **everything else** is the generator's default, deliberately: speed, step and
 *   scale are the numbers [MarqueeText] documents, and a dialog that asked four
 *   questions to scroll a word would be a worse tool than one that asks one.
 *
 * [step] overrides the default only when the user has taken the "scroll it
 * faster" way out of a too-long phrase.
 */
internal fun marqueePlanFor(state: EditorState, text: String, step: Int? = null): MarqueePlan {
    val size = state.codename.size
    val brightest = state.design.levels.lastIndex
    // A palette with no lit level at all cannot draw a letter. Unrepresentable
    // through the editor, reachable through an imported file.
    if (brightest < 1) return MarqueePlan.Blank
    return MarqueeText.plan(
        text = text,
        size = size,
        paletteIndex = state.brushIndex.takeIf { it in 1..brightest } ?: brightest,
        step = step ?: MarqueeText.defaultStep(size),
    )
}

/**
 * Writes [frames] into the open variant as a looping animation, exactly as the
 * assistant's `apply_design` would.
 *
 * Built from [EditorState.composed] rather than from the last-saved design, so
 * strokes made a second ago on *another* variant are carried rather than
 * silently reverted, and handed to [EditorState.replaceDesign], which is the
 * single entry point for a whole-document change and the only thing that knows
 * how to rebuild the canvas, the timeline and the brush around one.
 *
 * `recordUndo` is the whole difference between this and the assistant's use of
 * that entry point, and it is what makes the tool row's undo arrow able to take
 * the marquee back. The step it pushes is the document as it was one line above —
 * the user's artwork, unsaved edits and all — so undo restores exactly what was
 * on screen when the button was pressed, and not the last thing written to disk.
 *
 * False only if the editor refused the document, which it does when there is no
 * artwork in it at all — impossible here, since these frames are the artwork.
 */
internal fun applyMarquee(state: EditorState, frames: List<DesignFrame>): Boolean {
    if (frames.isEmpty()) return false
    val current = state.composed()
    val variant = current.variantFor(state.codename) ?: DesignVariant()
    val next: Design = current.copy(
        // A marquee that plays once and stops is not a marquee. `kind` is already
        // dynamic on every path the editor's button can take (see this file's
        // KDoc); it is stated rather than assumed because it is this function's
        // own invariant, not a fact borrowed from the caller.
        kind = DesignKind.DYNAMIC,
        loop = true,
        variants = current.variants + (state.codename.codename to variant.copy(frames = frames)),
    )
    return state.replaceDesign(next, recordUndo = true) != null
}

/**
 * How many frames of the open variant have anything lit in them.
 *
 * The question the line under the phrase is really answering — "is there art here
 * to replace" — answered by looking rather than by trusting the frame count: a
 * design created a minute ago and not drawn on has one frame, and telling
 * somebody they are about to replace a blank canvas teaches them to stop reading.
 */
internal fun drawnFrameCount(state: EditorState): Int =
    state.frames.count { entry -> entry.frame.copyOfCells().any { it != 0 } }
