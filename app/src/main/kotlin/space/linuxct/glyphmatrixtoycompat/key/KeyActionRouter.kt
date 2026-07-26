package space.linuxct.glyphmatrixtoycompat.key

import space.linuxct.glyphmatrixtoycompat.core.DebugLog
import space.linuxct.glyphmatrixtoycompat.core.Events
import space.linuxct.glyphmatrixtoycompat.core.RenderScheduler
import space.linuxct.glyphmatrixtoycompat.core.ScreenManager
import space.linuxct.glyphmatrixtoycompat.core.SessionArbiter

/**
 * Click-count -> action mapping:
 *   1 = Glyph Touch (EVENT_CHANGE) to the current screen (no-op on passive screens)
 *   2 = next screen
 *   3 = jump home (the ambient background screen)
 *   4+ = ignored
 * If no session is live when a burst lands, the press only revives the
 * session and the action is swallowed (no accidental dice roll on a dark
 * matrix).
 */
class KeyActionRouter(
    private val arbiter: SessionArbiter,
    private val screenManager: ScreenManager,
    private val scheduler: RenderScheduler,
) {
    fun execute(clicks: Int) {
        DebugLog.i(C, "execute clicks=$clicks sessionShouldRun=${arbiter.sessionShouldRun} owner=${arbiter.owner}")
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
            when (clicks) {
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

    /** A real Glyph Button long-press (Phone 3) — same as a single click. */
    fun glyphButtonChange() {
        DebugLog.i(C, "glyph button CHANGE -> current screen")
        scheduler.run { screenManager.dispatchGlyphEvent(Events.CHANGE) }
    }

    private companion object {
        const val C = "Router"
    }
}
