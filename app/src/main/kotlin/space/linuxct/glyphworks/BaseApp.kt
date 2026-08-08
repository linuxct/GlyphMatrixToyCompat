package space.linuxct.glyphworks

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

/**
 * Everything the two flavours' `Application` share.
 *
 * ## Why `App` itself is per-flavour
 *
 * It differs in two ways that cannot be papered over with a runtime check:
 *
 * 1. **`Configuration.Provider`.** WorkManager exists only for the update
 *    checker's daily job, so the Play build does not depend on the library at
 *    all (`githubImplementation`). An `Application` cannot conditionally
 *    implement an interface whose type is absent.
 * 2. **Which notification channels exist.** The updates and assistant channels
 *    belong to code the Play build does not ship, and a channel with no
 *    notification behind it is a row in the user's settings that does nothing.
 *
 * So the shared work lives here and each flavour supplies a three-line `App`.
 * The manifest names `.App` and both provide one.
 *
 * [optionalChannels] is the seam: called with the manager already fetched, after
 * the timer channel exists.
 */
abstract class BaseApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        Core.init(this)
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (_: Exception) {
            "?"
        }
        space.linuxct.glyphworks.core.DebugLog.i("App", "process started, version $version")
    }

    /** Channels this flavour adds beyond the timer. Default: none. */
    protected open fun optionalChannels(nm: NotificationManager) = Unit

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
        optionalChannels(nm)
    }

    companion object {
        /** The Timer's chime. The one channel both flavours have. */
        const val CHANNEL_TIMER = "timer"

        /** Pre-rename id of [CHANNEL_TIMER], deleted on first launch of this build. */
        private const val LEGACY_CHANNEL_TEA_TIME = "tea_time"
    }
}
