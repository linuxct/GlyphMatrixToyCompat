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
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_TEA_TIME,
                getString(R.string.channel_tea_time),
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
    }

    companion object {
        const val CHANNEL_TEA_TIME = "tea_time"
        const val CHANNEL_UPDATES = "app_updates"
    }
}
