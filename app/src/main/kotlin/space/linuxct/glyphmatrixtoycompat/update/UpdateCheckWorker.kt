package space.linuxct.glyphmatrixtoycompat.update

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import space.linuxct.glyphmatrixtoycompat.App
import space.linuxct.glyphmatrixtoycompat.Core
import space.linuxct.glyphmatrixtoycompat.R
import space.linuxct.glyphmatrixtoycompat.core.DebugLog
import space.linuxct.glyphmatrixtoycompat.core.PrefKeys
import java.util.concurrent.TimeUnit

/**
 * Daily background release check. Notifies at most once per new version;
 * tapping the notification opens the GitHub release page.
 */
class UpdateCheckWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        Core.init(applicationContext)
        val installed = try {
            applicationContext.packageManager.getPackageInfo(applicationContext.packageName, 0).versionName
        } catch (e: Exception) {
            null
        } ?: return Result.success()

        return when (val r = UpdateChecker.check(installed)) {
            is UpdateChecker.Result.UpdateAvailable -> {
                DebugLog.i("Update", "release ${r.version} available (installed $installed)")
                val notified = Core.prefs.getString(PrefKeys.UPDATE_NOTIFIED_VERSION, PrefKeys.UPDATE_NOTIFIED_VERSION_DEF)
                if (notified != r.version) {
                    postNotification(r)
                    Core.prefs.putString(PrefKeys.UPDATE_NOTIFIED_VERSION, r.version)
                }
                Result.success()
            }
            UpdateChecker.Result.UpToDate -> Result.success()
            is UpdateChecker.Result.Failed -> {
                DebugLog.w("Update", "check failed: ${r.reason}")
                Result.retry()
            }
        }
    }

    private fun postNotification(update: UpdateChecker.Result.UpdateAvailable) {
        val ctx = applicationContext
        if (ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        val open = PendingIntent.getActivity(
            ctx,
            0,
            Intent(Intent.ACTION_VIEW, Uri.parse(update.url)),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(ctx, App.CHANNEL_UPDATES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(ctx.getString(R.string.notif_update_title))
            .setContentText(ctx.getString(R.string.notif_update_body, update.version))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(ctx).notify(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val WORK_NAME = "update_check"
        private const val NOTIFICATION_ID = 2002 // Timer uses 2001

        /**
         * Idempotent daily schedule (KEEP preserves the existing cadence).
         * Called from MainActivity — never from Direct Boot paths, because
         * WorkManager's store lives in credential-encrypted storage.
         */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(1, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
