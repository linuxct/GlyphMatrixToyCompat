package space.linuxct.glyphmatrixtoycompat.key

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.VibratorManager
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import space.linuxct.glyphmatrixtoycompat.Core
import space.linuxct.glyphmatrixtoycompat.core.DebugLog
import space.linuxct.glyphmatrixtoycompat.core.PrefKeys

/**
 * Captures the Nothing Essential Key globally (including lock screen / AOD /
 * before first unlock — the service is directBootAware and the framework
 * auto-rebinds enabled accessibility services at every boot).
 *
 * Mechanism: accessibility key-event filtering (flagRequestFilterKeyEvents in
 * the XML config, re-asserted at runtime in onServiceConnected — some builds
 * only honour the runtime flag). The Essential Key arrives as KEYCODE_UNKNOWN
 * with a vendor-specific SCAN code that differs between firmware/hardware
 * revisions (250 and 304 observed so far); we match against that explicit
 * set rather than keyCode 0, which would over-match other unmapped keys.
 * Unmatched keys are logged with their scan code so new revisions are easy
 * to add. While the master toggle is on, DOWN, UP and repeats are all
 * consumed so stock Essential Space never sees the press; toggle off means
 * full pass-through.
 *
 * NOTE: this class's component name is persisted by the system in
 * Settings.Secure — never rename or move it.
 */
class EssentialKeyService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val counter = ClickCounter()
    private var expiry: Runnable? = null
    @Volatile private var lastConsumedPressAt = 0L

    override fun onCreate() {
        super.onCreate()
        Core.init(this)
        DebugLog.i(C, "onCreate")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Core.init(this)
        logAndEnsureKeyFilterFlag()
        Core.prefs.putLong(PrefKeys.SERVICE_HEARTBEAT, System.currentTimeMillis())
        Core.arbiter.revive()
        DebugLog.i(C, "onServiceConnected: heartbeat written, session revived")
    }

    /**
     * The decisive attribute for receiving onKeyEvent at all. The XML config
     * requests it, but log the resolved state and re-assert it at runtime —
     * if flags lack FLAG_REQUEST_FILTER_KEY_EVENTS, onKeyEvent NEVER fires.
     */
    private fun logAndEnsureKeyFilterFlag() {
        val info = serviceInfo
        if (info == null) {
            DebugLog.w(C, "serviceInfo is null — cannot verify key-filter flag!")
            return
        }
        val hasFlag = info.flags and AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS != 0
        val hasCapability = info.capabilities and
            AccessibilityServiceInfo.CAPABILITY_CAN_REQUEST_FILTER_KEY_EVENTS != 0
        DebugLog.i(
            C,
            "serviceInfo: flags=0x${Integer.toHexString(info.flags)} " +
                "capabilities=0x${Integer.toHexString(info.capabilities)} " +
                "keyFilterCapability=$hasCapability " +
                "FLAG_REQUEST_FILTER_KEY_EVENTS=$hasFlag",
        )
        if (!hasFlag) {
            info.flags = info.flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            serviceInfo = info
            DebugLog.i(C, "re-applied FLAG_REQUEST_FILTER_KEY_EVENTS -> flags=0x${Integer.toHexString(serviceInfo?.flags ?: 0)}")
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val desc = "action=${event.action} keyCode=${event.keyCode} scanCode=${event.scanCode} " +
            "repeat=${event.repeatCount} device=${event.deviceId}"
        if (event.scanCode !in KNOWN_SCAN_CODES) {
            // Log everything: if the Essential Key arrives with yet another
            // scan code on a future firmware, this line reveals it.
            DebugLog.d(C, "key ignored (scanCode not in ${KNOWN_SCAN_CODES.contentToString()}): $desc")
            return super.onKeyEvent(event)
        }
        if (!Core.prefs.getBoolean(PrefKeys.MASTER_TOGGLE, PrefKeys.MASTER_TOGGLE_DEF)) {
            // User decision: toggle off = the key behaves completely normally,
            // even on the lock screen.
            DebugLog.i(C, "essential key PASS-THROUGH (master toggle off): $desc")
            return super.onKeyEvent(event)
        }

        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            vibrate()
            Core.prefs.putLong(PrefKeys.SERVICE_HEARTBEAT, System.currentTimeMillis())
            lastConsumedPressAt = SystemClock.uptimeMillis()
            val pressNumber = counter.onPress(SystemClock.uptimeMillis())
            DebugLog.i(C, "essential key DOWN consumed: press #$pressNumber in burst ($desc)")
            expiry?.let(mainHandler::removeCallbacks)
            val r = Runnable {
                expiry = null
                val clicks = counter.finish()
                DebugLog.i(C, "click window closed: $clicks click(s) -> routing")
                Core.router.execute(clicks)
            }
            expiry = r
            mainHandler.postDelayed(r, ClickCounter.WINDOW_MS)
        } else {
            DebugLog.d(C, "essential key consumed (up/repeat): $desc")
        }
        // Consume DOWN, UP and all repeats: an unconsumed UP is exactly the
        // "Essential Space still triggers" leak.
        return true
    }

    /**
     * Leak watchdog. On some firmware Nothing triggers Essential Space at the
     * input pipeline's QUEUEING stage, which runs before the accessibility
     * key filter — consuming the events cannot suppress that. The config
     * subscribes to window-state events from the Essential packages only;
     * when one of their windows appears shortly after a press we consumed,
     * dismiss it (BACK unlocked / HOME locked). The clean solution is the
     * system-side handoff (keep the apps enabled!): under Settings →
     * Intelligence Toolkit, enable Essential Key Settings → "Activate with
     * single tap before use" and disable Essential Voice → "Activate via
     * Essential Key"; the watchdog then only covers stragglers.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg !in ESSENTIAL_PACKAGES) return
        val sincePress = SystemClock.uptimeMillis() - lastConsumedPressAt
        DebugLog.i(C, "$pkg window appeared (${event.className}) ${sincePress} ms after last consumed press")
        if (!Core.prefs.getBoolean(PrefKeys.MASTER_TOGGLE, PrefKeys.MASTER_TOGGLE_DEF)) return
        if (lastConsumedPressAt == 0L || sincePress > LEAK_WINDOW_MS) {
            DebugLog.d(C, "not correlated with a consumed press; leaving it alone")
            return
        }
        val locked = getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked == true
        mainHandler.postDelayed({
            val action = if (locked) GLOBAL_ACTION_HOME else GLOBAL_ACTION_BACK
            DebugLog.i(C, "dismissing $pkg leak via ${if (locked) "HOME" else "BACK"}")
            performGlobalAction(action)
        }, 150)
    }

    private fun vibrate() {
        try {
            val vibrator = getSystemService(VibratorManager::class.java)?.defaultVibrator ?: return
            val clickSupported = vibrator.areAllEffectsSupported(VibrationEffect.EFFECT_CLICK) ==
                android.os.Vibrator.VIBRATION_EFFECT_SUPPORT_YES
            val effect = if (clickSupported) {
                VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
            } else {
                VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE)
            }
            vibrator.vibrate(effect)
        } catch (e: Exception) {
            DebugLog.w(C, "vibrate failed: ${e.message}")
        }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        DebugLog.w(C, "onUnbind (service disabled or system rebinding)")
        return super.onUnbind(intent)
    }

    companion object {
        private const val C = "KeySvc"

        /**
         * Essential Key scan codes seen in the wild: 250 (Phone 4a Pro,
         * firmware observed 2026-07) and 304 (earlier revisions). The key is
         * unmapped (KEYCODE_UNKNOWN), so the scan code is the only reliable
         * discriminator.
         */
        val KNOWN_SCAN_CODES = intArrayOf(250, 304)

        /**
         * Packages the system launches in reaction to the Essential Key:
         * Essential Space itself plus its screenshot/recorder component
         * (observed firing on the 4a Pro). Must stay in sync with
         * android:packageNames in accessibility_service_config.xml.
         */
        val ESSENTIAL_PACKAGES = setOf(
            "com.nothing.ntessentialspace",
            "com.nothing.ntessentialrecorder",
        )

        /** Essential windows appearing within this of a consumed press count as leaks. */
        const val LEAK_WINDOW_MS = 3000L
    }
}
