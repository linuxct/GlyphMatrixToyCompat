package space.linuxct.glyphmatrixtoycompat.toy

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import com.nothing.ketchum.GlyphToy
import space.linuxct.glyphmatrixtoycompat.Core
import space.linuxct.glyphmatrixtoycompat.core.DebugLog

/**
 * The system-facing Glyph Toy (registered with aod_support=1 + longpress=1).
 * When the user selects it as the Always-on Glyph Toy, Nothing's system binds
 * it for AOD and our whole carousel renders through the toy binding:
 * rendering runs bind-to-unbind, the system gates physical output, and the
 * per-minute EVENT_AOD needs no handling because the compositor self-drives.
 *
 * On a Phone (3), the real Glyph Button long-press arrives here as
 * EVENT_CHANGE and feeds the same action pipeline as an Essential Key single
 * press. Component name is persisted by the system — never rename.
 */
class AodToyService : Service() {

    private val handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            if (msg.what != GlyphToy.MSG_GLYPH_TOY) {
                DebugLog.d(C, "message ignored (what=${msg.what})")
                super.handleMessage(msg)
                return
            }
            val event = msg.data?.getString(GlyphToy.MSG_GLYPH_TOY_DATA)
            DebugLog.i(C, "system toy message: '$event'")
            when (event) {
                GlyphToy.EVENT_CHANGE -> Core.router.glyphButtonChange()
                // The compositor self-drives, but screens that record the AOD
                // hint (Music Visualizer) still get to see the event.
                GlyphToy.EVENT_AOD -> Core.scheduler.run {
                    Core.screenManager.dispatchGlyphEvent(space.linuxct.glyphmatrixtoycompat.core.Events.AOD)
                }
            }
        }
    }

    private val messenger = Messenger(handler)

    override fun onCreate() {
        super.onCreate()
        Core.init(this)
    }

    override fun onBind(intent: Intent?): IBinder {
        DebugLog.i(C, "onBind (system selected us as the active toy)")
        Core.arbiter.setToyBound(true)
        return messenger.binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        DebugLog.i(C, "onUnbind")
        Core.arbiter.setToyBound(false)
        return false
    }

    override fun onDestroy() {
        DebugLog.i(C, "onDestroy")
        Core.arbiter.setToyBound(false)
        super.onDestroy()
    }

    private companion object {
        const val C = "Toy"
    }
}
