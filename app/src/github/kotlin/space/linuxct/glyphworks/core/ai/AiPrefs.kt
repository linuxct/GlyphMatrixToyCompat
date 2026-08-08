package space.linuxct.glyphworks.core.ai

import space.linuxct.glyphworks.core.Prefs

/**
 * The assistant's own preference keys, and the guarded reads for them.
 *
 * ## Why these are not in `PrefKeys`
 *
 * They were, and it made `core/Prefs.kt` — a file every screen and every port
 * depends on — import `core/ai`. Three of the defaults were *expressions* over
 * AI types (`ChatWire.MODEL`, `GlyphAiOrchestrator.DEFAULT_MAX_ROUNDS`,
 * `ChatWire.DEFAULT_REASONING_EFFORT`) and `Prefs.aiReasoningEffort()` returned
 * [ReasoningEffort] outright, so the shared settings store could not be compiled
 * without the assistant present.
 *
 * That was wrong on its own merits — a `core` file has no business knowing a
 * feature package exists — and it is also what makes the assistant removable:
 * the Play flavour ships without `core/ai`, and `PrefKeys` no longer notices.
 *
 * The **key strings are unchanged** (`aiModel`, `aiMaxRounds`,
 * `aiReasoningEffort`), so a store written by an earlier build reads back
 * identically. Only where the constants live has moved.
 */
object AiPrefKeys {

    /**
     * The model id the design assistant talks to, or "" to use the built-in one.
     *
     * Not beside the OAuth token because it is not a secret — it is the name of a
     * model — and the credential-protected stores are unreadable before the first
     * unlock, which is a cost with nothing to buy.
     *
     * The default is [ChatWire.MODEL] itself rather than a second copy of the
     * literal, so "what does this app ask for" has exactly one answer. Empty is a
     * perfectly good stored value: [ChatWire.resolveModel] turns it back into the
     * built-in default, which is what makes clearing the field a reset.
     */
    const val MODEL = "aiModel"
    const val MODEL_DEF = ChatWire.MODEL

    /**
     * How many tool rounds a turn may take before it is cut short and salvaged.
     *
     * Configurable because the built-in eight is a budget, not a safety limit,
     * and it is the wrong budget for the task the user actually has: "animate
     * this across twenty frames" spends rounds reading, writing and re-reading
     * the canvas, and running out mid-way produces a half-drawn design and a turn
     * that has to explain itself. The ceiling still exists — a model that has
     * stopped converging will loop until something stops it, and every round
     * costs a request — so this widens the budget rather than removing it.
     *
     * Clamped on read by [Prefs.aiMaxRounds] rather than trusted: it is a stored
     * integer, and the only thing between a corrupt value and an unbounded loop
     * is the code that reads it.
     */
    const val MAX_ROUNDS = "aiMaxRounds"
    const val MAX_ROUNDS_DEF = GlyphAiOrchestrator.DEFAULT_MAX_ROUNDS
    const val MAX_ROUNDS_MIN = 4
    const val MAX_ROUNDS_MAX = 40

    /**
     * The granularity the UI offers: 4, 8, 12 … 40, ten positions rather than
     * thirty-seven.
     *
     * A presentation constant, here because the range is. One detent per round
     * drew a rail of 35 tick marks — unreadable as anything but noise, and it
     * implied a precision this number does not have: nobody knows their animation
     * needs 23 rounds rather than 24. Four is the coarsest step that divides both
     * bounds *and* the default, so every endpoint the code cares about is a
     * position the slider can land on.
     *
     * Not enforced on read. A value between detents is valid and
     * [Prefs.aiMaxRounds] honours it — this governs what the slider offers, not
     * what the setting accepts.
     */
    const val MAX_ROUNDS_STEP = 4

    /**
     * How hard the assistant is asked to think, as the lowercase token that goes
     * on the wire.
     *
     * The token rather than the enum's name, and rather than an ordinal: this is
     * the exact string the request carries, so what is stored is what is sent and
     * there is no table in the middle to get out of step. An ordinal would also
     * silently re-point at a different level the first time the list is reordered.
     *
     * The default is [ChatWire.DEFAULT_REASONING_EFFORT] itself, so a store with
     * nothing in it behaves exactly as the app did before the setting existed.
     *
     * Read through [Prefs.aiReasoningEffort], which maps an unknown token back to
     * a known level. Not all six levels are known to be accepted by the backend —
     * see [ReasoningEffort] for which are documented, which are plausible and
     * which are guesses.
     */
    const val REASONING_EFFORT = "aiReasoningEffort"
    const val REASONING_EFFORT_DEF = ChatWire.DEFAULT_REASONING_EFFORT
}

/**
 * The assistant's tool-round budget, clamped into
 * [AiPrefKeys.MAX_ROUNDS_MIN] … [AiPrefKeys.MAX_ROUNDS_MAX].
 *
 * A function rather than a raw `getInt` at the call site, and the clamp is the
 * reason: the stored value is an integer the user typed, and the thing it
 * controls is the only bound on a loop that issues a network request per
 * iteration. A zero or a negative — from a corrupt store, or an editing state
 * that briefly reads as empty — would end the turn before it began; an absurdly
 * large one turns a misbehaving model into a long, expensive spin. The ceiling
 * is enforced where it is read, because that is the only place that cannot be
 * bypassed.
 */
fun Prefs.aiMaxRounds(): Int =
    getInt(AiPrefKeys.MAX_ROUNDS, AiPrefKeys.MAX_ROUNDS_DEF)
        .coerceIn(AiPrefKeys.MAX_ROUNDS_MIN, AiPrefKeys.MAX_ROUNDS_MAX)

/**
 * The stored reasoning effort, degraded to [ReasoningEffort.DEFAULT] if the
 * token is one this build does not know.
 *
 * Same shape of guard as [aiMaxRounds], different danger: this is a *string*, so
 * the risk is not an out-of-range number but a token nothing recognises — one
 * written by a build offering a level this one does not, or edited by hand.
 * Degrading means the settings row can always draw itself, which is what makes
 * the setting recoverable from the UI rather than only by clearing app data.
 *
 * Deliberately **not** a write-back: reading does not repair the store, so a
 * value this build does not know survives an upgrade that reintroduces it.
 */
fun Prefs.aiReasoningEffort(): ReasoningEffort =
    ReasoningEffort.fromWire(getString(AiPrefKeys.REASONING_EFFORT, AiPrefKeys.REASONING_EFFORT_DEF))
