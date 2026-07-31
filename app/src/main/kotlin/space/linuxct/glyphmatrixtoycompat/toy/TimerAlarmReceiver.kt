package space.linuxct.glyphmatrixtoycompat.toy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import space.linuxct.glyphmatrixtoycompat.Core
import space.linuxct.glyphmatrixtoycompat.core.BrightnessScale
import space.linuxct.glyphmatrixtoycompat.core.PrefKeys
import space.linuxct.glyphmatrixtoycompat.screens.TimerScreen

/**
 * Backstop for Timer completion when the process was dead or the timer
 * screen's ticker was not running (user cycled away / long doze). The
 * in-process ticker is the primary path: it clears timerStartMillis and cancels
 * this alarm, making the receiver a no-op; the alarm itself fires with a few
 * seconds of slack so the ticker always wins while alive.
 *
 * The receiver deliberately does NOT clear the persisted start — TimerScreen's
 * stale-start path shows the done state on re-entry and clears it there. It
 * records which start it chimed for so that path (and a resumed ticker)
 * never double-chimes. Device selection happens inside GlyphLink from the
 * runtime matrix length, so the receiver renders correctly on any Glyph
 * Matrix device.
 */
class TimerAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Core.init(context)
        val start = Core.prefs.getLong(PrefKeys.TIMER_START, PrefKeys.TIMER_START_DEF)
        if (start == 0L) return // primary path already completed
        if (Core.prefs.getLong(PrefKeys.TIMER_CHIMED_FOR, PrefKeys.TIMER_CHIMED_FOR_DEF) == start) return

        Core.prefs.putLong(PrefKeys.TIMER_CHIMED_FOR, start)
        Core.ports.timer.chime()

        // Render the done frame only when no live session would overwrite it
        // within a tick anyway (e.g. process was dead and is cold-starting) —
        // and never while the design editor is previewing.
        //
        // The preview check is NOT redundant with the session one, and it is not
        // belt-and-braces either. This is the app's ONE push path that does not
        // go through ScreenManager: it takes its own GlyphLink lease and calls
        // BrightnessScale + pushFrame directly, so ScreenManager's live-preview
        // gate cannot see it, let alone stop it. A timer expiring mid-stroke
        // would paint the done frame over the drawing on the panel. Reading the
        // volatile flag here is the only place that can be prevented.
        //
        // (A timer that fires in the microseconds between the flag being set and
        // this read can still slip through; the editor's next push — at most
        // ~33 ms later — takes the panel straight back, which is the honest
        // limit of a check made from another thread.)
        if (!Core.screenManager.sessionLive && !Core.screenManager.livePreviewActive) {
            val pending = goAsync()
            val lease = Core.glyphLink.acquire("timer-alarm")
            val frame = BrightnessScale.scale(
                TimerScreen.renderDone(Core.glyphLink.size),
                Core.prefs.getFloat(PrefKeys.BRIGHTNESS, PrefKeys.BRIGHTNESS_DEF),
            )
            Core.glyphLink.pushFrame(frame)
            // Give the async Glyph bind + render a moment, then wind down (well
            // under the 10 s broadcast budget).
            Handler(Looper.getMainLooper()).postDelayed({
                lease.release()
                pending.finish()
            }, 4000)
        }
    }
}
