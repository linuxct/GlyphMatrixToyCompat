package space.linuxct.glyphworks.core

/**
 * The "carousel": owns the ordered list of enabled screens, the current
 * position, and the live-session flag. Pure Kotlin; every method MUST be
 * called on the scheduler thread (callers marshal via RenderScheduler.run).
 *
 * Output frames have the global brightness scaling applied and byte-identical
 * consecutive frames are skipped (visible behaviour unchanged; avoids
 * redundant binder calls).
 */
class ScreenManager(
    private val allScreens: List<GlyphScreen>,
    private val prefs: Prefs,
    private val ports: Ports,
    private val scheduler: RenderScheduler,
    private val size: Int,
    private val output: (IntArray) -> Unit,
) {
    var sessionLive = false
        private set

    /** True while the blinking Essential-Key "menu" selector is open. */
    var inMenu = false
        private set

    /**
     * True while the design editor owns the matrix — see [beginLivePreview].
     *
     * `@Volatile` because this is the one value in this class that is read from
     * a thread other than the scheduler's: `TimerAlarmReceiver` renders on a
     * binder thread and pushes to `GlyphLink` *outside* this class, so the only
     * way it can respect the gate is by reading the flag itself. Every WRITE is
     * still scheduler-thread only, exactly like the rest of the state here.
     */
    @Volatile
    var livePreviewActive = false
        private set

    private var transientId: String? = null
    private var activeScreen: GlyphScreen? = null
    private var lastPushed: IntArray? = null

    // Menu-mode state: the previewed toy blinks (content <-> blank) until the
    // user commits (double press) or the auto-commit timer fires.
    private var blinkOn = true
    private var lastContentFrame: IntArray? = null

    /**
     * The last frame as the screen drew it, BEFORE the brightness scaling — the
     * source of truth for [reapplyBrightness]. Kept separately on purpose:
     * scaling rounds, so re-scaling an already-scaled frame compounds the loss
     * (`round(round(v * b) * b) != round(v * b)`) and repeated re-applies —
     * every 60 s under auto-brightness — would slowly dim the matrix.
     */
    private var lastRawFrame: IntArray? = null
    private val blank = IntArray(size * size)
    private var blink: Cancelable? = null
    private var commitTimer: Cancelable? = null

    private val context: ScreenContext = ScreenContext(size, prefs, ports, scheduler) { frame ->
        // The live-preview gate. Deactivating the active screen stops its
        // ticker, and that alone is NOT enough: a screen may have a postDelayed
        // one-shot in flight that fires after it was deactivated (CustomScreen's
        // KDoc calls out exactly that hazard), and this ScreenContext is a
        // shared singleton every screen holds a reference to for as long as it
        // likes. Dropping the frame *here* is what makes the editor's in-progress
        // drawing the only thing that can reach the panel — no screen, however
        // it is scheduled, can paint over it.
        if (livePreviewActive) return@ScreenContext
        val raw = lastRawFrame
        // Reuse the buffer: frames are fixed-size and this runs per pushed frame.
        if (raw != null && raw.size == frame.size) frame.copyInto(raw) else lastRawFrame = frame.copyOf()
        val scaled = BrightnessScale.scale(frame, brightness())
        lastContentFrame = scaled
        // While the menu is blinked "off", suppress the toy's frame with black.
        val toSend = if (inMenu && !blinkOn) blank else scaled
        val last = lastPushed
        if (last != null && last.contentEquals(toSend)) return@ScreenContext
        lastPushed = toSend.copyOf()
        output(toSend)
    }

    /** Roster in configured order, filtered to enabled; falls back to the full roster if all are disabled. */
    fun enabledScreens(): List<GlyphScreen> {
        val byId = allScreens.associateBy { it.id }
        val ordered = prefs.getString(PrefKeys.SCREEN_ORDER, PrefKeys.SCREEN_ORDER_DEF)
            .split(',')
            .mapNotNull { byId[it.trim()] }
        val known = ordered + allScreens.filter { s -> ordered.none { it.id == s.id } }
        val enabled = known.filter { prefs.getBoolean(PrefKeys.screenEnabled(it.id), true) }
        return enabled.ifEmpty { known }
    }

    fun currentScreen(): GlyphScreen {
        val screens = enabledScreens()
        // A transient (preview) id may point at a disabled screen on purpose;
        // the persisted current screen must never resurrect a disabled one.
        transientId?.let { t -> allScreens.firstOrNull { it.id == t }?.let { return it } }
        val id = prefs.getString(PrefKeys.CURRENT_SCREEN, PrefKeys.CURRENT_SCREEN_DEF)
        return screens.firstOrNull { it.id == id } ?: screens.first()
    }

    fun startSession() {
        if (sessionLive) return
        sessionLive = true
        lastPushed = null
        val screen = currentScreen()
        DebugLog.i(C, "session START on '${screen.id}'")
        activate(screen)
    }

    fun stopSession() {
        if (!sessionLive) return
        DebugLog.i(C, "session STOP (was '${activeScreen?.id}')")
        exitMenuState()
        deactivate()
        transientId = null
        sessionLive = false
        lastPushed = null
        lastContentFrame = null
        lastRawFrame = null
    }

    /**
     * Re-pushes the last drawn frame at the current brightness pref. Scaling is
     * otherwise only applied when a screen draws, and byte-identical frames
     * are dropped — so a background brightness change (auto-brightness) would
     * not reach a static toy until its next redraw (up to a minute for the
     * clock). Bypasses the dedup deliberately: the pref, not the frame, changed.
     *
     * Scheduler-thread only, like every other method here.
     */
    fun reapplyBrightness() {
        if (!sessionLive) return
        val raw = lastRawFrame ?: return
        // scale() returns its input unchanged when no rescale is needed; copy so
        // lastContentFrame never aliases the reused raw buffer.
        val scaled = BrightnessScale.scale(raw, brightness()).let { if (it === raw) raw.copyOf() else it }
        lastContentFrame = scaled
        // Blinked "off" inside the menu: the next blink-on pushes the new level.
        if (inMenu && !blinkOn) return
        lastPushed = scaled.copyOf()
        output(scaled)
    }

    private fun brightness() = prefs.getFloat(PrefKeys.BRIGHTNESS, PrefKeys.BRIGHTNESS_DEF)

    // ---------- live preview (the design editor) ----------
    //
    // Precedence here is absolute by design: while somebody is drawing, the
    // matrix shows what they are drawing and nothing else. That has to beat the
    // selected toy AND every compositing override — the ambient charging layer,
    // the audio visualizer — because the whole point of the preview is to answer
    // "what does this pixel look like on real hardware", and a question answered
    // by somebody else's animation is not answered.
    //
    // Two mechanisms, and both are needed:
    //
    //  - **Deactivation** stops the active screen's ticker, so nothing is
    //    generating frames on a clock any more. The ambient overrides live
    //    inside `AmbientScreen.composite()` and therefore die with it.
    //  - **The gate** ([livePreviewActive], enforced in the ScreenContext sink
    //    above) drops anything that arrives anyway. A pending one-shot, a
    //    sensor callback, a screen that kept the shared context: all of them
    //    reach the sink, and the sink is where they stop.
    //
    // The one push path this class does NOT own is `TimerAlarmReceiver`, which
    // takes its own GlyphLink lease and pushes straight to the hardware. It
    // reads [livePreviewActive] itself; see that file.

    /**
     * Hands the matrix to the editor: stops the current screen and closes the
     * gate. Idempotent.
     *
     * The Essential-Key menu is exited too, because its blink pushes to [output]
     * directly (it is a *suppression* of the toy's frame, not a frame from one)
     * and so would walk straight past the sink gate.
     */
    fun beginLivePreview() {
        if (livePreviewActive) return
        DebugLog.i(C, "live preview BEGIN (was '${activeScreen?.id}')")
        exitMenuState()
        livePreviewActive = true
        deactivate()
        // The outgoing screen's frame must not survive as the source for
        // reapplyBrightness: an auto-brightness tick before the first preview
        // frame lands would otherwise re-push the toy over the top of it.
        lastRawFrame = null
        lastContentFrame = null
    }

    /**
     * The only way past the gate.
     *
     * Goes through exactly the same [BrightnessScale] the toys do, so what the
     * user sees while drawing is what their brightness setting will actually
     * give them — a preview rendered at full scale would quietly lie about
     * every grey in the design. Takes ownership of [frame].
     */
    fun pushLivePreview(frame: IntArray) {
        if (!livePreviewActive) return
        // Kept as the pre-scale source of truth for reapplyBrightness, exactly
        // as the sink does — so dragging the brightness slider (or an
        // auto-brightness tick) re-levels the preview without the editor
        // having to notice.
        val raw = lastRawFrame
        if (raw != null && raw.size == frame.size) frame.copyInto(raw) else lastRawFrame = frame.copyOf()
        val scaled = BrightnessScale.scale(frame, brightness())
        lastContentFrame = scaled
        val last = lastPushed
        if (last != null && last.contentEquals(scaled)) return
        lastPushed = scaled.copyOf()
        output(scaled)
    }

    /**
     * Gives the matrix back: opens the gate and re-renders the current screen.
     *
     * [lastPushed] is cleared first so the dedup cannot swallow that re-render —
     * a toy whose frame happens to be byte-identical to the last preview frame
     * (a design being edited *is* the selected toy, so this is likely rather
     * than exotic) would otherwise leave the panel showing a frame nobody owns.
     */
    fun endLivePreview() {
        if (!livePreviewActive) return
        DebugLog.i(C, "live preview END")
        livePreviewActive = false
        lastPushed = null
        lastRawFrame = null
        lastContentFrame = null
        if (sessionLive) forceActivate(currentScreen())
    }

    /**
     * Re-runs the current screen's `onActivate`, so whatever it reads at
     * activation time — a file, a pref — is read again.
     *
     * ## The ordering this exists for
     *
     * `CustomScreen` loads the selected design **in `onActivate`**, and the
     * design editor writes that file on its way out. Those two interleave, and
     * the interleaving is the bug:
     *
     * ```
     * ON_PAUSE   endLivePreview()   -> forceActivate(currentScreen())
     *                                  CustomScreen re-reads the OLD file
     * ON_STOP    the save flush     -> the NEW file lands on disk
     *                                  ...and nothing activates anything again
     * ```
     *
     * The matrix then shows the pre-edit design for as long as that screen stays
     * current, which reads as "I edited it and my phone still shows the old one"
     * rather than as a race. A write therefore has to be able to say *"and now
     * re-read"*, and this is that call. It predates the live preview — the
     * preview only made it easy to hit.
     *
     * ## Three rules the caller inherits
     *
     * - **Only after a write that actually succeeded.** Refreshing after a
     *   failed save swaps one stale render for an identical stale render and
     *   hides the failure behind a picture that looks deliberate.
     * - **Not while the editor owns the matrix.** The gate would drop the frames
     *   this produces anyway, and re-arming a screen's ticker only to throw its
     *   output away is exactly the battery cost the preview was written to
     *   avoid. Nothing is lost by skipping it: [endLivePreview] force-activates
     *   on `ON_PAUSE`, and any save landing after that point (the `ON_STOP`
     *   flush) does reach here. Both hops are posted to the same scheduler
     *   handler, so they run in lifecycle order.
     * - Scheduler-thread only, like every other method here.
     */
    fun refreshCurrentScreen() {
        if (!sessionLive || livePreviewActive) return
        DebugLog.i(C, "refresh '${currentScreen().id}'")
        forceActivate(currentScreen())
    }

    /**
     * The last selected-design id this manager has seen, so an unchanged write
     * cannot be mistaken for a change. See [onSelectedDesignChanged].
     *
     * Seeded from the pref at construction rather than left null, so the first
     * write after process start is compared against what was actually selected
     * and not treated as a change by default.
     */
    private var lastSelectedDesignId =
        prefs.getString(PrefKeys.CUSTOM_DESIGN_ID, PrefKeys.CUSTOM_DESIGN_ID_DEF)

    /**
     * Somebody chose a different design for the design toy to play.
     *
     * [refreshCurrentScreen] is the mechanism; this is the *policy* around it,
     * and it lives here rather than at the call site because the call sites are
     * the problem. `CustomScreen` reads the selected design once, in
     * `onActivate`, so writing the pref changes nothing that is already running —
     * and `switchTo` early-returns on the screen that is already active, so even
     * re-selecting the toy would not re-read it. The matrix went on showing the
     * previous design until the user cycled away and back, which is the app
     * ignoring them.
     *
     * Wired to the **preference**, in `Core.init`'s change listener, and not to
     * the toy's settings dialog, because that dialog is one writer of several:
     * deleting the selected design falls back to another, the editor's "show this
     * on the Glyph Matrix" writes it, an import could, and whatever is added next
     * will. A fix at one call site silently misses the rest; a fix at the pref
     * covers every writer that has ever existed and every one that will. It is
     * the same reasoning that put the master toggle on that listener instead of
     * inside the settings switch.
     *
     * ## The two things it must not do
     *
     * - **Restart an unrelated toy.** [refreshCurrentScreen] re-activates
     *   whatever is current, so it is gated on [designScreenId] actually being
     *   current. Re-arming the clock's ticker because a design selection changed
     *   would be a new bug in place of the old one. (The id is passed in because
     *   `core` does not depend on `screens`; the caller names `CustomScreen.ID`.)
     * - **Restart on a write that changed nothing.** `SharedPreferences` does not
     *   notify for a value equal to the one already stored, and `AndroidPrefs` is
     *   a straight pass-through to it — but the check is made here anyway, so the
     *   behaviour is a property of this class rather than of whichever `Prefs`
     *   implementation is installed (the test fake notifies unconditionally).
     *   Re-selecting the design that is already playing must not jump a running
     *   animation back to frame 0.
     *
     * Refreshing while the editor holds the matrix, or with no live session, is
     * [refreshCurrentScreen]'s own business and it already declines both.
     *
     * Scheduler-thread only, like every other method here — the listener it is
     * called from fires on whichever thread did the write, so `Core` marshals.
     */
    fun onSelectedDesignChanged(designScreenId: String) {
        val selected = prefs.getString(PrefKeys.CUSTOM_DESIGN_ID, PrefKeys.CUSTOM_DESIGN_ID_DEF)
        if (selected == lastSelectedDesignId) return
        lastSelectedDesignId = selected
        // Deliberately after the store: the new selection has been observed
        // whether or not it is showing, and a screen activated later reads the
        // design for itself.
        if (currentScreen().id != designScreenId) return
        refreshCurrentScreen()
    }

    fun next() = moveBy(1)

    fun home() {
        if (!sessionLive) return
        val wasInMenu = inMenu
        exitMenuState()
        val homeScreen = enabledScreens().first()
        transientId = null
        prefs.putString(PrefKeys.CURRENT_SCREEN, homeScreen.id)
        if (wasInMenu) {
            // Exiting the menu: a mid-blink call may have left a blank frame, so
            // force a fresh render even if home was already active.
            forceActivate(homeScreen)
        } else {
            if (activeScreen?.id == homeScreen.id) return
            switchTo(homeScreen)
        }
    }

    private fun moveBy(delta: Int) {
        if (!sessionLive) return
        val screens = enabledScreens()
        val current = activeScreen ?: currentScreen()
        val idx = screens.indexOfFirst { it.id == current.id }
        val nextScreen = screens[((if (idx < 0) 0 else idx) + delta + screens.size) % screens.size]
        DebugLog.i(C, "cycle '${current.id}' -> '${nextScreen.id}'")
        transientId = null
        prefs.putString(PrefKeys.CURRENT_SCREEN, nextScreen.id)
        switchTo(nextScreen)
    }

    fun dispatchGlyphEvent(event: String) {
        if (!sessionLive) {
            DebugLog.d(C, "event '$event' dropped (session not live)")
            return
        }
        DebugLog.i(C, "event '$event' -> '${activeScreen?.id}'")
        activeScreen?.onEvent(event)
    }

    /**
     * Makes [id] the persisted current screen and switches to it immediately.
     *
     * The switch is skipped while the editor owns the matrix, for the same
     * reasons [refreshCurrentScreen] skips its re-activation: the gate would drop
     * every frame the newly-activated screen produced, and activating a screen
     * only to throw its output away arms a ticker (or, for `custom`, a one-shot
     * chain) that burns battery behind a preview nobody can see past. Nothing is
     * lost — the *pref* is written either way, and [endLivePreview] force-
     * activates whatever it names on `ON_PAUSE`. That is what lets the design
     * editor offer "show this on the Glyph Matrix" without having to know
     * anything about the preview it is holding.
     */
    fun selectScreen(id: String) {
        val screen = enabledScreens().firstOrNull { it.id == id }
            ?: allScreens.firstOrNull { it.id == id } ?: return
        DebugLog.i(C, "select '${screen.id}'")
        transientId = null
        prefs.putString(PrefKeys.CURRENT_SCREEN, screen.id)
        if (sessionLive && !livePreviewActive) switchTo(screen)
    }

    /** Shows [id] without persisting it as the current screen (in-app preview). */
    fun showTransient(id: String) {
        if (!sessionLive) {
            DebugLog.d(C, "transient '$id' dropped (session not live)")
            return
        }
        DebugLog.i(C, "transient preview '$id'")
        transientId = id
        val screen = allScreens.firstOrNull { it.id == id } ?: return
        switchTo(screen)
    }

    fun clearTransient() {
        if (transientId == null) return
        transientId = null
        if (sessionLive) switchTo(currentScreen())
    }

    // ---------- menu mode (blinking Essential-Key selector) ----------

    /** Opens the blinking selector previewing the current toy. */
    fun enterMenu() {
        if (!sessionLive || inMenu) return
        DebugLog.i(C, "menu ENTER on '${currentScreen().id}'")
        inMenu = true
        blinkOn = true
        transientId = currentScreen().id
        startBlink()
        armCommit()
    }

    /** Advances the blinking preview to the next enabled toy. */
    fun menuNext() {
        if (!inMenu) return
        val screens = enabledScreens()
        val cur = activeScreen ?: currentScreen()
        val idx = screens.indexOfFirst { it.id == cur.id }
        val nextScreen = screens[((if (idx < 0) 0 else idx) + 1) % screens.size]
        DebugLog.i(C, "menu NEXT '${cur.id}' -> '${nextScreen.id}'")
        transientId = nextScreen.id
        switchTo(nextScreen)
        armCommit()
    }

    /** Sets the previewed toy as current, stops blinking, and closes the menu. */
    fun commitMenu() {
        if (!inMenu) return
        val id = transientId ?: currentScreen().id
        DebugLog.i(C, "menu COMMIT '$id'")
        exitMenuState()
        transientId = null
        prefs.putString(PrefKeys.CURRENT_SCREEN, id)
        val screen = enabledScreens().firstOrNull { it.id == id }
            ?: allScreens.firstOrNull { it.id == id } ?: currentScreen()
        // Re-activate so the committed toy shows steady immediately (the blink
        // may have left a blank frame on screen).
        forceActivate(screen)
    }

    private fun startBlink() {
        blink?.cancel()
        scheduleBlink()
    }

    private fun scheduleBlink() {
        val delay = if (blinkOn) BLINK_ON_MS else BLINK_OFF_MS
        blink = scheduler.postDelayed(delay) {
            if (!inMenu) return@postDelayed
            blinkOn = !blinkOn
            val frame = if (blinkOn) lastContentFrame else blank
            if (frame != null) {
                lastPushed = frame.copyOf()
                output(frame)
            }
            scheduleBlink()
        }
    }

    private fun armCommit() {
        commitTimer?.cancel()
        commitTimer = scheduler.postDelayed(MENU_TIMEOUT_MS) { commitMenu() }
    }

    private fun exitMenuState() {
        blink?.cancel(); blink = null
        commitTimer?.cancel(); commitTimer = null
        inMenu = false
        blinkOn = true
    }

    private fun switchTo(screen: GlyphScreen) {
        if (activeScreen === screen) return
        deactivate()
        activate(screen)
    }

    /** Activate [screen] unconditionally (even if already active), forcing a fresh render. */
    private fun forceActivate(screen: GlyphScreen) {
        deactivate()
        activate(screen)
    }

    private fun activate(screen: GlyphScreen) {
        activeScreen = screen
        screen.onActivate(context)
    }

    private fun deactivate() {
        scheduler.clearTicker()
        activeScreen?.onDeactivate()
        activeScreen = null
    }

    private companion object {
        const val C = "Screens"
        const val MENU_TIMEOUT_MS = 5000L
        const val BLINK_ON_MS = 450L
        const val BLINK_OFF_MS = 300L
    }
}
