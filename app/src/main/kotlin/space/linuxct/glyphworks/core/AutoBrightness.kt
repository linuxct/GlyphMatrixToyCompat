package space.linuxct.glyphworks.core

import kotlin.math.abs
import kotlin.math.log10

/**
 * Opportunistic auto-brightness: samples the ambient light sensor on a slow
 * cadence and writes [PrefKeys.BRIGHTNESS], then asks for a re-render so the
 * change is visible even if the active toy is static.
 *
 * Pure Kotlin (no android.*) so the whole policy — curve, cadence, hysteresis —
 * is JVM-testable. The Android bits live elsewhere: sensors/LightSensor supplies
 * [LightPort], util/ScreenStateWatcher feeds [setScreenOn].
 *
 * Runs only while a render session is live AND [PrefKeys.AUTO_BRIGHTNESS] is on;
 * "opportunistic" because polling rides on the render scheduler's Handler, so
 * with the screen off the samples land whenever the device happens to be awake
 * (no wakelocks, no alarms — this feature is never worth waking a phone for).
 *
 * All state is confined to the scheduler thread; the public methods marshal
 * themselves, so they are safe to call from the main looper or a pref listener.
 */
class AutoBrightness(
    private val prefs: Prefs,
    private val light: LightPort,
    private val scheduler: RenderScheduler,
    /** Invoked after [PrefKeys.BRIGHTNESS] changed, to re-push the current frame. */
    private val onBrightnessChanged: () -> Unit,
) {
    private var sessionActive = false
    private var screenOn = true
    private var polling = false
    private var poll: Cancelable? = null
    private var warmup: Cancelable? = null

    /** Called when a render session starts. */
    fun start() = scheduler.run {
        sessionActive = true
        sync()
    }

    /** Called when the render session stops; cancels all pending work. */
    fun stop() = scheduler.run {
        sessionActive = false
        sync()
    }

    /** Called when [PrefKeys.AUTO_BRIGHTNESS] was written (either way). */
    fun onEnabledChanged() = scheduler.run { sync() }

    /** Screen state from the ACTION_SCREEN_ON/OFF receiver; picks the cadence. */
    fun setScreenOn(on: Boolean) = scheduler.run {
        if (screenOn == on) return@run
        screenOn = on
        DebugLog.d(C, "screen ${if (on) "ON" else "OFF"}")
        // Re-arm at the new cadence. Turning the screen on also samples right
        // away, so the matrix is already correct when the user looks at it.
        if (polling) {
            if (on) sample()
            schedule()
        }
    }

    private fun enabled() = prefs.getBoolean(PrefKeys.AUTO_BRIGHTNESS, PrefKeys.AUTO_BRIGHTNESS_DEF)

    private fun sync() {
        val should = sessionActive && enabled()
        if (should == polling) return
        polling = should
        DebugLog.i(C, "auto-brightness ${if (should) "START" else "STOP"}")
        if (should) {
            sample()
            schedule()
        } else {
            poll?.cancel(); poll = null
            warmup?.cancel(); warmup = null
        }
    }

    private fun schedule() {
        poll?.cancel()
        val interval = if (screenOn) POLL_SCREEN_ON_MS else POLL_SCREEN_OFF_MS
        poll = scheduler.postDelayed(interval) {
            if (!polling) return@postDelayed
            sample()
            schedule()
        }
    }

    /**
     * Touches the sensor, then reads it again after a short warm-up. LightSensor
     * registers lazily and TYPE_LIGHT only reports on change, so the first read
     * of a sample window comes back null; the second one has a fresh value.
     */
    private fun sample() {
        light.lux()
        warmup?.cancel()
        warmup = scheduler.postDelayed(WARMUP_MS) {
            if (!polling) return@postDelayed
            apply(light.lux())
        }
    }

    private fun apply(lux: Float?) {
        // No reading (no sensor, or nothing reported): hold the last good value.
        if (lux == null) return
        val target = luxToBrightness(lux)
        val current = prefs.getFloat(PrefKeys.BRIGHTNESS, PrefKeys.BRIGHTNESS_DEF)
        val delta = target - current
        // Dead band, and *only* a dead band: it decides whether to act at all,
        // and never how far to move. Below it the matrix would visibly hunt for
        // no gain.
        if (abs(delta) < HYSTERESIS) return
        // Once we do act, we land on the target exactly. Two reasons, and they
        // are why the old "move a fraction of the remaining distance" ease is
        // gone entirely:
        //  - Correctness. A fractional step only ever *approaches* the target,
        //    so FLOOR and 1.0 were unreachable by construction; the dead band
        //    then froze whatever residual offset was left. A blackout settled
        //    around 0.18 instead of 0.15 and daylight never hit full.
        //  - Responsiveness. Samples are a minute apart (see POLL_SCREEN_ON_MS),
        //    so easing spread a blackout over five-plus minutes of visible
        //    stepping. Covering the sensor must darken the matrix on the *next*
        //    sample, not eventually.
        // No mid-range ramp is worth keeping either: the dead band already
        // guarantees every step we take is at least HYSTERESIS, and anything
        // from there up to a genuine light change is a single small move on a
        // dim 13x13 matrix — imperceptible as a jump, and smoothing it would
        // reintroduce exactly the residual offset above.
        val next = target.coerceIn(FLOOR, 1f)
        DebugLog.i(C, "lux=$lux target=$target: $current -> $next")
        prefs.putFloat(PrefKeys.BRIGHTNESS, next)
        onBrightnessChanged()
    }

    companion object {
        private const val C = "AutoBright"

        /** Sample every minute while the user is looking at the phone. */
        const val POLL_SCREEN_ON_MS = 60_000L

        /** With the screen off nobody is watching: sample rarely, if at all. */
        const val POLL_SCREEN_OFF_MS = 15 * 60_000L

        /** Delay between touching the lazily-registered sensor and reading it. */
        const val WARMUP_MS = 1_500L

        /** Pitch dark still shows the matrix, just faintly. */
        const val FLOOR = 0.15f

        /** Illuminance at which brightness saturates at 1.0 (overcast daylight). */
        const val SATURATION_LUX = 10_000f

        /**
         * Dead band: ignore target moves smaller than this (≈4 % of the range).
         * Purely a "should we act?" gate — it never caps how far a move goes.
         */
        const val HYSTERESIS = 0.04f

        private val LOG_SPAN = log10(1f + SATURATION_LUX)

        /**
         * Illuminance (lux) → brightness 0..1, log-shaped because lux is wildly
         * non-linear: brightness = FLOOR + (1-FLOOR) * log10(1+lux)/log10(1+10000).
         *
         * Breakpoints (approx):
         *   0 lux    pitch dark          0.15  (the floor — never blank)
         *   1 lux    moonlit room        0.21
         *   10 lux   dim living room     0.37
         *   100 lux  hallway / dim room  0.58
         *   400 lux  office lighting     0.70
         *   1000 lux bright indoors      0.79
         *   10000+   daylight            1.00  (saturated)
         *
         * Monotonically non-decreasing, and clamped to [FLOOR, 1].
         */
        fun luxToBrightness(lux: Float): Float {
            val safe = if (lux.isNaN()) 0f else lux.coerceAtLeast(0f)
            val t = (log10(1f + safe) / LOG_SPAN).coerceIn(0f, 1f)
            return (FLOOR + (1f - FLOOR) * t).coerceIn(FLOOR, 1f)
        }
    }
}
