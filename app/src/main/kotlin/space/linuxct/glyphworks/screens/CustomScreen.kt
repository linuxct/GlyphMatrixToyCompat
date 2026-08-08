package space.linuxct.glyphworks.screens

import space.linuxct.glyphworks.core.Cancelable
import space.linuxct.glyphworks.core.Events
import space.linuxct.glyphworks.core.GlyphScreen
import space.linuxct.glyphworks.core.ScreenContext
import space.linuxct.glyphworks.core.design.DEFAULT_FRAME_DURATION_MS
import space.linuxct.glyphworks.core.design.Design
import space.linuxct.glyphworks.core.design.DesignFrames
import space.linuxct.glyphworks.core.design.DesignKind
import space.linuxct.glyphworks.core.design.KeyMode
import space.linuxct.glyphworks.core.design.PokemonCodename
import space.linuxct.glyphworks.matrix.Font3x5
import space.linuxct.glyphworks.matrix.MAX_BRIGHTNESS
import space.linuxct.glyphworks.matrix.MatrixCanvas
import space.linuxct.glyphworks.matrix.PanelMask

/**
 * Plays the user's selected design — the one toy on the matrix this app did not
 * draw.
 *
 * The design arrives through [space.linuxct.glyphworks.core.DesignPort]
 * because it is file data and this package may not touch android.*. It is read
 * once per activation, in [onActivate], which runs on the scheduler thread: file
 * I/O has no business in a ticker (it would stutter the animation it is feeding)
 * and none at all on `glyph-io`, where it would sit in front of the binder calls
 * that actually light the panel.
 *
 * **Playback.** A static design is one `pushFrame` and nothing else. A dynamic
 * one is a chain of one-shots rather than a ticker, because each frame carries
 * its own `durationMs` and there is exactly one ticker slot per screen with one
 * fixed interval — it cannot express "hold this frame for 500 ms, the next for
 * 40". The chain is modelled on `ScreenManager.scheduleBlink`: the [Cancelable]
 * is kept in a field, each callback pushes and then re-arms itself with the
 * newly-current frame's own delay, and the callback is guarded by the state it
 * depends on so a one-shot that outlives that state does nothing.
 *
 * **The two key modes** are the whole interaction vocabulary, and that is not a
 * simplification: only a single press ever reaches a screen (double and triple
 * presses belong to the carousel), so there is exactly one thing a user can
 * *say* to this toy. [Events.SHAKE] is a second way of saying it, not a second
 * thing to say — see [onEvent].
 *
 * **Nothing to play is a rendered state, not a blank one.** No design selected,
 * the file gone, or — the case that only shows up when a design crosses between
 * devices — a design carrying art for the *other* Pokémon codename and none for
 * this one, all render [renderPlaceholder]. A dark matrix would be
 * indistinguishable from the toy being broken.
 */
class CustomScreen : GlyphScreen {
    override val id = ID
    override val interactive = true

    private var ctx: ScreenContext? = null

    /** Decoded art for this device, empty when there is nothing to play. */
    private var frames: List<IntArray> = emptyList()

    /** Parallel to [frames]: how long each is held, in ms. */
    private var durations: IntArray = IntArray(0)

    private var keyMode = KeyMode.PLAY_PAUSE
    private var loop = false

    /** Index into [frames]; also the frame currently on the matrix. */
    private var index = 0

    /** True while the chain is armed. The one-shots read it and stop if it is false. */
    private var playing = false

    /** The armed link of the chain, so [onDeactivate] can break it. */
    private var pending: Cancelable? = null

    override fun onActivate(ctx: ScreenContext) {
        this.ctx = ctx
        cancelChain()
        load(ctx)
        index = 0
        // playOnce "rests on frame 0" by definition. playPause has no such
        // resting state in its name, and a dynamic design that sat motionless
        // until someone pressed the Essential Key would look broken on the
        // always-on display, where nobody is pressing anything — so it starts
        // playing and the first press is the pause.
        playing = animated() && keyMode == KeyMode.PLAY_PAUSE
        push()
        if (playing) arm()
    }

    override fun onDeactivate() {
        // Both belts, and both are needed. ScreenManager.deactivate() clears the
        // ticker and nothing else, so an armed one-shot of ours would still fire
        // — and the ScreenContext it would push into is a single shared instance
        // reused by whichever screen comes next, so that stray frame would paint
        // over another toy's art. Cancelling is the fix; nulling the ctx (which
        // every callback re-reads) is the guard for the race where the one-shot
        // was already in flight when we were cancelled.
        cancelChain()
        playing = false
        frames = emptyList()
        durations = IntArray(0)
        ctx = null
    }

    override fun onEvent(event: String) {
        // A shake is a press, exactly as it is for Coin, Dice, Rps and Bottle:
        // the toys that respond to a gesture all respond to both, and a Custom
        // design that ignored the one gesture its siblings honour would read as
        // broken rather than as different.
        if (event != Events.CHANGE && event != Events.SHAKE) return
        if (ctx == null || !animated()) return
        when (keyMode) {
            // Restart rather than ignore: a press during a run is the user asking
            // to see it again, and on a design with long frames "ignore" would
            // feel like a dead button for up to a minute. Same call DiceScreen
            // makes for a press mid-roll.
            KeyMode.PLAY_ONCE -> {
                cancelChain()
                index = 0
                playing = true
                push()
                arm()
            }
            KeyMode.PLAY_PAUSE -> if (playing) {
                cancelChain()
                playing = false
            } else {
                // Resume where it was paused — except at the very end of a
                // non-looping design, where "resume" would have nothing to play,
                // so the press starts it over.
                if (index >= frames.size - 1) index = 0
                playing = true
                push()
                arm()
            }
        }
    }

    /** Reads the selected design and decodes this device's variant, or falls back to nothing. */
    private fun load(ctx: ScreenContext) {
        frames = emptyList()
        durations = IntArray(0)
        keyMode = KeyMode.PLAY_PAUSE
        loop = false

        val design: Design = ctx.ports.design.selected() ?: return
        // An unknown panel size resolves to no codename, and a design authored
        // on the other device carries no variant for ours. Both are ordinary,
        // survivable situations — not errors — and both end as the placeholder.
        val codename = PokemonCodename.ofSize(ctx.size) ?: return
        val variant = design.variantFor(codename) ?: return
        if (variant.frames.isEmpty()) return

        val decoded = ArrayList<IntArray>(variant.frames.size)
        for (frame in variant.frames) {
            // DesignCodec proved every frame decodes before this design was
            // stored, so a null here means the design did not come through the
            // codec. Refuse the whole design rather than play the prefix of an
            // animation: half a design is not the design.
            decoded += DesignFrames.decode(frame.cells, design.levels, codename.size) ?: return
        }
        keyMode = design.keyMode
        loop = design.loop
        // `kind` is the author's declaration, so a design marked static shows its
        // first frame even if more are stored (the editor keeps them; the user
        // asked for a still image). Only a design that is both declared dynamic
        // and actually has somewhere to advance to animates.
        if (design.kind == DesignKind.DYNAMIC) {
            frames = decoded
            durations = IntArray(variant.frames.size) { variant.frames[it].durationMs }
        } else {
            frames = listOf(decoded.first())
            durations = intArrayOf(variant.frames.first().durationMs)
        }
    }

    private fun animated(): Boolean = frames.size > 1

    /** Pushes the current frame, or the placeholder when there is no art. */
    private fun push() {
        val c = ctx ?: return
        c.pushFrame(frames.getOrNull(index) ?: renderPlaceholder(c.size))
    }

    /**
     * Arms the next link of the chain: the delay is the duration of the frame
     * that is on the matrix *right now*, because that is how long it is meant to
     * be seen.
     */
    private fun arm() {
        val c = ctx ?: return
        val holdMs = durations.getOrElse(index) { DEFAULT_FRAME_DURATION_MS }.toLong()
        pending = c.scheduler.postDelayed(holdMs) {
            // The state check the chain depends on: a cancelled-but-already-
            // dispatched one-shot, or one that survived a pause, stops here.
            if (!playing || ctx == null) return@postDelayed
            advance()
        }
    }

    /** One step of the chain: move on, draw, and either re-arm or come to rest. */
    private fun advance() {
        if (index < frames.size - 1) {
            index++
            push()
            arm()
            return
        }
        // Past the last frame, and what happens next is the key mode's business.
        when (keyMode) {
            // "Plays through once and returns", so the rest position is frame 0
            // and it is drawn — the return is visible, not just internal state.
            KeyMode.PLAY_ONCE -> {
                index = 0
                playing = false
                push()
            }
            KeyMode.PLAY_PAUSE -> if (loop) {
                index = 0
                push()
                arm()
            } else {
                // Hold the last frame: nothing is pushed, so the design ends on
                // the image its author ended it on.
                playing = false
            }
        }
    }

    private fun cancelChain() {
        pending?.cancel()
        pending = null
    }

    companion object {
        /**
         * This screen's toy id.
         *
         * A constant rather than a literal because the Create tab now names this
         * screen from outside the roster — "show this design on the matrix" has
         * to select *this* toy — and a second spelling of "custom" in the UI
         * layer would be free to drift from the one the registry uses.
         */
        const val ID = "custom"

        /**
         * The placeholder's border. Dim on purpose and dim by design: it frames
         * the '?' without competing with it, and the frame's peak is the '?'
         * itself at full brightness, so the art obeys the house rule the
         * brightness audit enforces (brightest element at [MAX_BRIGHTNESS],
         * everything else a ratio of it).
         */
        const val PLACEHOLDER_BORDER = 700

        /**
         * "There is no design to play" as a picture: a dim border around a
         * full-brightness question mark, at either matrix size.
         *
         * **The border follows the panel, not the frame.** It used to be
         * `rect(0, 0, size, size)` — the outline of the square buffer — which at
         * 13x13 runs through twelve cells the round panel has no LED for (see
         * [PanelMask]), so the "frame" arrived on the matrix as four detached
         * arcs with gaps at every diagonal. Lighting the mask's own edge instead
         * gives a closed ring, one cell thick, every cell of which the hardware
         * can actually show — and it is the same ring the editor's illustration
         * draws the outermost cells of, so the preview and the panel agree.
         *
         * Pure and static, like every other screen's render function, so it is
         * golden-testable without a lifecycle.
         */
        fun renderPlaceholder(size: Int): IntArray {
            val canvas = MatrixCanvas(size)
            for (y in 0 until size) {
                for (x in 0 until size) {
                    if (PanelMask.isEdge(x, y, size)) canvas.set(x, y, PLACEHOLDER_BORDER)
                }
            }
            // Font3x5.HEIGHT is 5, so this is the vertical centre on both panels.
            Font3x5.drawStringCentered(canvas, "?", size / 2 - 2, MAX_BRIGHTNESS)
            return canvas.copyOut()
        }
    }
}
