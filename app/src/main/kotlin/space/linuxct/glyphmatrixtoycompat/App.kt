package space.linuxct.glyphmatrixtoycompat

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class App : Application() {

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
    }

    companion object {
        const val CHANNEL_TEA_TIME = "tea_time"
    }
}
