package space.linuxct.glyphmatrixtoycompat.ai

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import space.linuxct.glyphmatrixtoycompat.App
import space.linuxct.glyphmatrixtoycompat.R
import space.linuxct.glyphmatrixtoycompat.core.DebugLog
import space.linuxct.glyphmatrixtoycompat.ui.design.DesignEditorActivity

/**
 * Keeps the process alive for as long as a turn is running, and says so.
 *
 * ## Why the app grew its first foreground service for this
 *
 * Moving the turn out of the ViewModel (see [GlyphAiSession]) stops the *editor*
 * killing it. It does nothing about the *process*: a turn against a reasoning
 * model runs for a minute or two, the user puts the phone in their pocket while
 * it does, and an app with no foreground service, no wake lock and no scheduled
 * job is a plain background process — one the platform is free to reclaim as soon
 * as something else wants the memory. The turn then dies with no notice, no
 * message and, before the checkpointing in [GlyphAiSession], nothing on disk.
 *
 * A foreground service is what makes the process ineligible for that, and the
 * notification is not a formality: it is the only thing on screen telling
 * somebody who left the editor that their request is still being worked on, and
 * the only way back to the design it belongs to.
 *
 * ## `dataSync`, and why not one of the others
 *
 * A turn is an HTTPS request/response exchange with OpenAI carrying the user's
 * message and their artwork — network data transfer that the user explicitly
 * asked for and is waiting on, which is what `dataSync` describes.
 *
 * The alternative worth weighing is `shortService`, which needs no permission and
 * no type-specific justification. It is rejected on its **three-minute ceiling**:
 * a turn that redraws and revalidates several times routinely runs longer than
 * that, `shortService` cannot be extended, and the platform stops the service —
 * and can ANR the app — when the timer runs out. A cap that fires precisely on
 * the slow turns this exists to protect is the wrong cap.
 *
 * A user-initiated data-transfer *job* would be the platform's current preference
 * over `dataSync`, and it is not available here: it is a JobScheduler/WorkManager
 * construct, and WorkManager's automatic initialiser is deliberately removed from
 * this app's manifest because it touches credential-encrypted storage in a
 * process that starts during Direct Boot.
 *
 * The Android 15 cumulative cap on `dataSync` — six hours a day — is not reachable
 * by this feature: the service exists only between a turn beginning and that same
 * turn ending, and [GlyphAiSession] stops it in a `finally`, so every ending
 * including the user's cancel and a crash inside the orchestrator releases it.
 *
 * ## Direct Boot
 *
 * Not `directBootAware`, and nothing that starts it is reachable before the first
 * unlock: it is started from a running turn, a turn is started from the chat, and
 * the chat is only ever composed inside an Activity. See [ChatStore] for why that
 * rule exists.
 */
class GlyphAiTurnService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val designId = intent?.getStringExtra(EXTRA_DESIGN_ID).orEmpty()
        val designName = intent?.getStringExtra(EXTRA_DESIGN_NAME).orEmpty()
        try {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(designId, designName),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException, and anything else the
            // platform raises for "not from here, not now". The turn is already
            // running and must not be taken down by the thing that was meant to
            // protect it — it simply goes back to being an ordinary background
            // process, which is where it was before this class existed.
            DebugLog.w(TAG, "could not go foreground: ${e.javaClass.simpleName}: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    /**
     * There is no work to resume without the session that started it, and that
     * session lives in the process that just died. Restarting would put a
     * notification on screen for a turn that no longer exists.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        stopSelf()
    }

    private fun buildNotification(designId: String, designName: String): Notification {
        val name = designName.ifBlank { getString(R.string.pref_custom_unnamed) }
        val builder = Notification.Builder(this, App.CHANNEL_AI)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_ai_turn_title))
            .setContentText(getString(R.string.notif_ai_turn_body, name))
            .setOngoing(true)
            // Indeterminate: a turn's length is decided by the model, and a
            // percentage nobody can compute would be a lie with a bar on it.
            .setProgress(0, 0, true)
        // Straight back to the design being worked on, not to the app's home:
        // whoever taps this wants to watch the reply arrive.
        if (designId.isNotBlank()) {
            builder.setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    DesignEditorActivity.intent(this, designId)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        }
        return builder.build()
    }

    companion object {
        private const val TAG = "GlyphAiTurn"

        /** Timer uses 2001 and the update check 2002. */
        private const val NOTIFICATION_ID = 2003

        private const val EXTRA_DESIGN_ID = "designId"
        private const val EXTRA_DESIGN_NAME = "designName"

        fun intent(context: Context, designId: String, designName: String): Intent =
            Intent(context, GlyphAiTurnService::class.java)
                .putExtra(EXTRA_DESIGN_ID, designId)
                .putExtra(EXTRA_DESIGN_NAME, designName)
    }
}

/**
 * [TurnForeground] over [GlyphAiTurnService].
 *
 * Every call is wrapped, because neither end of this may take a turn down with
 * it: a foreground service that will not start is a turn with less protection,
 * not a turn that failed, and the platform has several reasons to refuse one
 * (a background start, a restricted bucket, a user who revoked notifications).
 */
class GlyphAiTurnNotifier(private val app: Context) : TurnForeground {

    override fun turnStarted(designId: String, designName: String) {
        try {
            app.startForegroundService(GlyphAiTurnService.intent(app, designId, designName))
        } catch (e: Exception) {
            DebugLog.w("GlyphAiTurn", "could not start: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    override fun turnEnded() {
        try {
            app.stopService(GlyphAiTurnService.intent(app, "", ""))
        } catch (e: Exception) {
            DebugLog.w("GlyphAiTurn", "could not stop: ${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
