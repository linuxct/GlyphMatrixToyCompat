package space.linuxct.glyphmatrixtoycompat

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.work.Configuration

// Configuration.Provider + the WorkManagerInitializer removal in the manifest
// defer WorkManager init until first getInstance() call (from MainActivity):
// this process also starts in Direct Boot, where WorkManager's
// credential-encrypted store must not be touched.
class App : Application(), Configuration.Provider {

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        Core.init(this)
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (_: Exception) {
            "?"
        }
        space.linuxct.glyphmatrixtoycompat.core.DebugLog.i("App", "process started, version $version")
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        // The Timer channel used to be called "tea_time"; drop the stale one so
        // upgraders don't see two channels in system settings.
        nm.deleteNotificationChannel(LEGACY_CHANNEL_TEA_TIME)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_TIMER,
                getString(R.string.channel_timer),
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )
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
        const val CHANNEL_TIMER = "timer"
        const val CHANNEL_UPDATES = "app_updates"

        /** The assistant working on a design. See `ai/GlyphAiTurnService`. */
        const val CHANNEL_AI = "ai_turn"

        /** Pre-rename id of [CHANNEL_TIMER], deleted on first launch of this build. */
        private const val LEGACY_CHANNEL_TEA_TIME = "tea_time"
    }
}
