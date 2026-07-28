package space.linuxct.glyphmatrixtoycompat.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.core.content.ContextCompat
import space.linuxct.glyphmatrixtoycompat.core.DebugLog

/**
 * Screen on/off awareness for auto-brightness. ACTION_SCREEN_ON/OFF are only
 * delivered to DYNAMICALLY registered receivers (a manifest entry never fires),
 * so this is registered for as long as a render session runs and torn down with
 * it — nothing polls the light sensor while no session exists.
 *
 * [start] also seeds the current state from PowerManager.isInteractive, since
 * the broadcasts only report transitions.
 */
class ScreenStateWatcher(
    private val app: Context,
    private val onScreenStateChanged: (screenOn: Boolean) -> Unit,
) {
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> onScreenStateChanged(true)
                Intent.ACTION_SCREEN_OFF -> onScreenStateChanged(false)
            }
        }
    }

    private var registered = false

    @Synchronized
    fun start() {
        if (!registered) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
            // Protected system broadcasts, so NOT_EXPORTED loses nothing and
            // satisfies the U+ registration rules.
            ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            registered = true
        }
        onScreenStateChanged(isInteractive())
    }

    @Synchronized
    fun stop() {
        if (!registered) return
        registered = false
        try {
            app.unregisterReceiver(receiver)
        } catch (e: IllegalArgumentException) {
            DebugLog.w(C, "receiver already gone: ${e.message}")
        }
    }

    /** True when the display is on (assumed on if PowerManager is unavailable). */
    fun isInteractive(): Boolean =
        app.getSystemService(PowerManager::class.java)?.isInteractive ?: true

    private companion object {
        const val C = "ScreenState"
    }
}
