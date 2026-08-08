package space.linuxct.glyphworks

import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.work.Configuration

/**
 * The GitHub build's `Application`: everything in [BaseApp], plus the two things
 * only this flavour has.
 *
 * `Configuration.Provider` together with the `WorkManagerInitializer` removal in
 * this flavour's manifest defers WorkManager init until the first
 * `getInstance()` call (from `MainActivity`). This process also starts in Direct
 * Boot, where WorkManager's credential-encrypted store must not be touched.
 */
class App : BaseApp(), Configuration.Provider {

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()

    override fun optionalChannels(nm: NotificationManager) {
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_UPDATES,
                getString(R.string.channel_updates),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
        // IMPORTANCE_LOW: this one is the required notice for a foreground
        // service the user started themselves and is waiting on. It has to be
        // visible — it is the only way back to the design being worked on — and
        // it must not make a sound, because it appears on every single turn.
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_AI,
                getString(R.string.channel_ai),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    companion object {
        const val CHANNEL_UPDATES = "app_updates"

        /** The assistant working on a design. See `ai/GlyphAiTurnService`. */
        const val CHANNEL_AI = "ai_turn"
    }
}
