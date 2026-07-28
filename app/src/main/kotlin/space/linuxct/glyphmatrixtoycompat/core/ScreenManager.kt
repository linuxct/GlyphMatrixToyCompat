package space.linuxct.glyphmatrixtoycompat.core

/**
 * The "carousel": owns the ordered list of enabled screens, the current
 * position, and the live-session flag. Pure Kotlin; every method MUST be
 * called on the scheduler thread (callers marshal via RenderScheduler.run).
 *
 * Output frames have the brightness ceiling applied and byte-identical
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

    private var transientId: String? = null
    private var activeScreen: GlyphScreen? = null
    private var lastPushed: IntArray? = null

    // Menu-mode state: the previewed toy blinks (content <-> blank) until the
    // user commits (double press) or the auto-commit timer fires.
    private var blinkOn = true
    private var lastContentFrame: IntArray? = null

    /**
     * The last frame as the screen drew it, BEFORE the brightness ceiling — the
     * source of truth for [reapplyBrightness]. Kept separately on purpose:
     * BrightnessCeiling max-normalizes with integer division, so re-ceilinging
     * an already-ceilinged frame rounds down a little each pass and repeated
     * re-applies (every 60 s under auto-brightness) would slowly dim the matrix.
     */
    private var lastRawFrame: IntArray? = null
    private val blank = IntArray(size * size)
    private var blink: Cancelable? = null
    private var commitTimer: Cancelable? = null

    private val context: ScreenContext = ScreenContext(size, prefs, ports, scheduler) { frame ->
        val raw = lastRawFrame
        // Reuse the buffer: frames are fixed-size and this runs per pushed frame.
        if (raw != null && raw.size == frame.size) frame.copyInto(raw) else lastRawFrame = frame.copyOf()
        val ceilinged = BrightnessCeiling.apply(frame, brightness())
        lastContentFrame = ceilinged
        // While the menu is blinked "off", suppress the toy's frame with black.
        val toSend = if (inMenu && !blinkOn) blank else ceilinged
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
     * Re-pushes the last drawn frame at the current brightness pref. The ceiling
     * is otherwise only applied when a screen draws, and byte-identical frames
     * are dropped — so a background brightness change (auto-brightness) would
     * not reach a static toy until its next redraw (up to a minute for the
     * clock). Bypasses the dedup deliberately: the pref, not the frame, changed.
     *
     * Scheduler-thread only, like every other method here.
     */
    fun reapplyBrightness() {
        if (!sessionLive) return
        val raw = lastRawFrame ?: return
        // apply() returns its input unchanged when no rescale is needed; copy so
        // lastContentFrame never aliases the reused raw buffer.
        val ceilinged = BrightnessCeiling.apply(raw, brightness()).let { if (it === raw) raw.copyOf() else it }
        lastContentFrame = ceilinged
        // Blinked "off" inside the menu: the next blink-on pushes the new level.
        if (inMenu && !blinkOn) return
        lastPushed = ceilinged.copyOf()
        output(ceilinged)
    }

    private fun brightness() = prefs.getFloat(PrefKeys.BRIGHTNESS, PrefKeys.BRIGHTNESS_DEF)

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

    /** Makes [id] the persisted current screen and switches to it immediately. */
    fun selectScreen(id: String) {
        val screen = enabledScreens().firstOrNull { it.id == id }
            ?: allScreens.firstOrNull { it.id == id } ?: return
        DebugLog.i(C, "select '${screen.id}'")
        transientId = null
        prefs.putString(PrefKeys.CURRENT_SCREEN, screen.id)
        if (sessionLive) switchTo(screen)
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
