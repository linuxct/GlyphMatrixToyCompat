package space.linuxct.glyphmatrixtoycompat.toy

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.util.Log
import space.linuxct.glyphmatrixtoycompat.App
import space.linuxct.glyphmatrixtoycompat.R
import space.linuxct.glyphmatrixtoycompat.core.TimerSignalPort

/**
 * Timer side effects. The exact alarm is only a BACKSTOP for process death
 * — the in-process ticker is the primary completion path. Exact alarms
 * are denied by default on Android 14+: when not granted we degrade to
 * setWindow (1 min slack). The chime plays directly via RingtoneManager so it
 * works even when POST_NOTIFICATIONS is denied; the notification is a bonus.
 */
class AndroidTimerSignal(private val app: Context) : TimerSignalPort {

    private fun pendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        app,
        REQUEST_CODE,
        Intent(app, TimerAlarmReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    override fun scheduleAlarm(atEpochMillis: Long) {
        val am = app.getSystemService(AlarmManager::class.java) ?: return
        val pi = pendingIntent()
        // Slack past the nominal deadline so the in-process ticker always
        // wins while the app is alive; the backstop only matters after process
        // death or a long doze.
        val at = atEpochMillis + BACKSTOP_SLACK_MS
        try {
            if (am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.setWindow(AlarmManager.RTC_WAKEUP, at, WINDOW_MS, pi)
            }
        } catch (e: SecurityException) {
            // Permission revoked between check and call.
            Log.w(TAG, "exact alarm denied, using window", e)
            am.setWindow(AlarmManager.RTC_WAKEUP, at, WINDOW_MS, pi)
        }
    }

    override fun cancelAlarm() {
        app.getSystemService(AlarmManager::class.java)?.cancel(pendingIntent())
    }

    override fun chime() {
        try {
            RingtoneManager.getRingtone(
                app,
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
            )?.play()
        } catch (e: Exception) {
            Log.w(TAG, "chime failed", e)
        }
        if (app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            try {
                val nm = app.getSystemService(NotificationManager::class.java) ?: return
                val n = Notification.Builder(app, App.CHANNEL_TIMER)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(app.getString(R.string.notif_timer_title))
                    .setContentText(app.getString(R.string.notif_timer_body))
                    .setAutoCancel(true)
                    .build()
                nm.notify(NOTIFICATION_ID, n)
            } catch (e: Exception) {
                Log.w(TAG, "notification failed", e)
            }
        }
    }

    private companion object {
        const val TAG = "TimerSignal"
        const val REQUEST_CODE = 1001
        const val NOTIFICATION_ID = 2001
        const val WINDOW_MS = 60_000L
        const val BACKSTOP_SLACK_MS = 3_000L
    }
}
