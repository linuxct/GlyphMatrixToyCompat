package space.linuxct.glyphworks.key

import space.linuxct.glyphworks.core.DebugLog
import space.linuxct.glyphworks.core.Events
import space.linuxct.glyphworks.core.PrefKeys
import space.linuxct.glyphworks.core.Prefs
import space.linuxct.glyphworks.core.RenderScheduler
import space.linuxct.glyphworks.core.ScreenManager
import space.linuxct.glyphworks.core.SessionControl

/**
 * Click-count -> action mapping.
 *
 * Classic mode (menu mode OFF, the default):
 *   1 = Glyph Touch (EVENT_CHANGE) to the current screen (no-op on passive screens)
 *   2 = next screen
 *   3 = jump home (the ambient background screen)
 *
 * Menu mode ON, not in the menu:
 *   1 = Glyph Touch (interactive toys still work)
 *   2 = open the blinking selector, 3 = home.
 * Menu mode ON, in the menu:
 *   1 = cycle the blinking preview, 2 = commit (set + exit), 3 = home (exits).
 *
 *   4+ = ignored.
 * If no session is live when a burst lands, the press only revives the
 * session and the action is swallowed (no accidental dice roll on a dark
 * matrix).
 */
class KeyActionRouter(
    private val arbiter: SessionControl,
    private val screenManager: ScreenManager,
    private val scheduler: RenderScheduler,
    private val prefs: Prefs,
) {
    fun execute(clicks: Int) {
        DebugLog.i(C, "execute clicks=$clicks sessionShouldRun=${arbiter.sessionShouldRun}")
        if (clicks !in 1..3) {
            DebugLog.d(C, "ignored ($clicks clicks)")
            return
        }
        if (!arbiter.sessionShouldRun) {
            // Master toggle off with a live toy binding gone etc. — just try to
            // bring the session back; swallow the action.
            DebugLog.i(C, "no session owner -> revive and swallow")
            arbiter.revive()
            return
        }
        scheduler.run {
            if (!screenManager.sessionLive) {
                DebugLog.i(C, "session not live yet -> revive and swallow")
                arbiter.revive()
                return@run
            }
            val menu = prefs.getBoolean(PrefKeys.MENU_MODE_ENABLED, PrefKeys.MENU_MODE_ENABLED_DEF)
            when {
                menu && screenManager.inMenu -> when (clicks) {
                    1 -> {
                        DebugLog.i(C, "menu: 1 click -> cycle preview")
                        screenManager.menuNext()
                    }
                    2 -> {
                        DebugLog.i(C, "menu: 2 clicks -> commit")
                        screenManager.commitMenu()
                    }
                    3 -> {
                        DebugLog.i(C, "menu: 3 clicks -> home")
                        screenManager.home()
                    }
                }
                menu -> when (clicks) {
                    1 -> {
                        DebugLog.i(C, "1 click -> EVENT_CHANGE to '${screenManager.currentScreen().id}'")
                        screenManager.dispatchGlyphEvent(Events.CHANGE)
                    }
                    2 -> {
                        DebugLog.i(C, "2 clicks -> open menu")
                        screenManager.enterMenu()
                    }
                    3 -> {
                        DebugLog.i(C, "3 clicks -> home")
                        screenManager.home()
                    }
                }
                else -> when (clicks) {
                    1 -> {
                        DebugLog.i(C, "1 click -> EVENT_CHANGE to '${screenManager.currentScreen().id}'")
                        screenManager.dispatchGlyphEvent(Events.CHANGE)
                    }
                    2 -> {
                        DebugLog.i(C, "2 clicks -> next screen")
                        screenManager.next()
                    }
                    3 -> {
                        DebugLog.i(C, "3 clicks -> home")
                        screenManager.home()
                    }
                }
            }
        }
    }

    /**
     * A real Glyph Button long-press (Phone 3). Cycles the preview while the
     * menu is open (menu mode); otherwise behaves like a single Glyph Touch.
     */
    fun glyphButtonChange() {
        scheduler.run {
            val menu = prefs.getBoolean(PrefKeys.MENU_MODE_ENABLED, PrefKeys.MENU_MODE_ENABLED_DEF)
            if (menu && screenManager.inMenu) {
                DebugLog.i(C, "glyph button -> menu cycle preview")
                screenManager.menuNext()
            } else {
                DebugLog.i(C, "glyph button CHANGE -> current screen")
                screenManager.dispatchGlyphEvent(Events.CHANGE)
            }
        }
    }

    private companion object {
        const val C = "Router"
    }
}
