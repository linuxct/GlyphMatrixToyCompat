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

    private var transientId: String? = null
    private var activeScreen: GlyphScreen? = null
    private var lastPushed: IntArray? = null

    private val context: ScreenContext = ScreenContext(size, prefs, ports, scheduler) { frame ->
        val ceilinged = BrightnessCeiling.apply(
            frame,
            prefs.getFloat(PrefKeys.BRIGHTNESS, PrefKeys.BRIGHTNESS_DEF),
        )
        val last = lastPushed
        if (last != null && last.contentEquals(ceilinged)) return@ScreenContext
        lastPushed = ceilinged.copyOf()
        output(ceilinged)
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
        deactivate()
        transientId = null
        sessionLive = false
        lastPushed = null
    }

    fun next() = moveBy(1)

    fun home() {
        if (!sessionLive) return
        val homeScreen = enabledScreens().first()
        transientId = null
        if (activeScreen?.id == homeScreen.id) return
        prefs.putString(PrefKeys.CURRENT_SCREEN, homeScreen.id)
        switchTo(homeScreen)
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

    private fun switchTo(screen: GlyphScreen) {
        if (activeScreen === screen) return
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
    }
}
