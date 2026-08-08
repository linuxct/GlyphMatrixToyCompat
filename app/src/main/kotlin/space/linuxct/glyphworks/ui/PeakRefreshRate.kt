package space.linuxct.glyphworks.ui

import android.os.Build
import android.view.Display
import android.view.View
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import space.linuxct.glyphworks.core.DebugLog

/**
 * Asks the panel for its highest refresh rate for as long as one of our
 * activities is VISIBLE, and releases the request the moment it is not.
 *
 * WHY THIS EXISTS: the device happily drives other apps at 120 Hz but settles
 * this one at 90 (sometimes 60). Nothing in the app ever expressed a refresh
 * rate preference, so the choice was left entirely to the display policy's own
 * heuristics, and those heuristics are free to park a "quiet" app — one whose
 * layer mostly shows still content between gestures — on a lower divisor of the
 * panel rate. The fix is not to fight the heuristic but to vote: a window that
 * states a preferred mode is counted, and the scrolling/springing UI this app is
 * built out of is exactly the kind of content that should be counted.
 *
 * WHY VISIBLE AND NOT FOCUSED: the request is bound to ON_START/ON_STOP rather
 * than ON_RESUME/ON_PAUSE on purpose. In split-screen, or behind a transparent
 * dialog or a translucent activity, our window is still being composited and
 * still animating — it has merely lost focus. Dropping the vote there would
 * hand the user a visible 120 → 90 step in the middle of an interaction.
 *
 * ON THE EXPLICIT RELEASE: a window's mode vote is already inherently scoped to
 * visibility. SurfaceFlinger only tallies votes from layers that are actually
 * on screen, so a stopped activity's preference stops counting on its own, and
 * the platform drops it outright when the window is removed. The ON_STOP reset
 * to [NO_PREFERRED_MODE] is therefore belt-and-braces: it costs one LayoutParams
 * update and it makes the scoping legible in this file instead of leaving it as
 * an implicit property of the compositor that a future reader has to already
 * know. It also means the pin cannot outlive the activity through any path we
 * did not anticipate (a retained window, a warm restart into a stopped state).
 *
 * RESOLUTION IS NEVER TOUCHED: `preferredDisplayModeId` pins a whole mode, and a
 * mode is a (width, height, refreshRate) triple. Some panels — including ones in
 * this device family — publish several resolutions, so picking "the fastest mode"
 * naively can silently drop the user into a lower-resolution mode that happens to
 * clock higher. Candidate modes are filtered to exactly the current mode's
 * physical width and height before the fastest one is chosen; the only thing this
 * file is ever allowed to change is Hz.
 *
 * Call once from `onCreate`. The observer registers on the activity's own
 * lifecycle and unregisters itself on ON_DESTROY, so it cannot outlive it.
 */
fun ComponentActivity.requestPeakRefreshRateWhileVisible() {
    lifecycle.addObserver(PeakRefreshRateObserver(this))
}

/** Log component, matching the short names the rest of the app uses. */
private const val C = "RefreshRate"

/**
 * The documented "no preference" value for
 * `WindowManager.LayoutParams.preferredDisplayModeId` — writing it back hands
 * the choice of mode to the system again.
 */
private const val NO_PREFERRED_MODE = 0

/**
 * How much faster a mode has to be before it is worth pinning, in Hz.
 *
 * Refresh rates are floats reported by the driver and are rarely the round
 * numbers the marketing says (120 Hz is typically 119.99…, 90 Hz 89.53…), so an
 * exact `>` comparison would treat float noise between two spellings of the same
 * rate as an upgrade. One whole Hz is far below the gap between any two real
 * modes on this panel (60 / 90 / 120 / 144) and far above that noise.
 */
private const val REFRESH_RATE_EPSILON_HZ = 1f

/**
 * The fastest rate we are willing to ask for, in Hz — NOT the panel's peak.
 *
 * This panel publishes 30 / 60 / 90 / 120 / 144 Hz at 1260x2800 (confirmed with
 * `adb shell dumpsys display`), so an unbounded "take the maximum" pick lands on
 * 144. That is the wrong trade for this app twice over:
 *
 *  - **We cannot fill it.** 144 Hz is a 6.94 ms budget. The worst UI-thread
 *    frames measured on-device after the Scaffold fix were 7.21 ms, so pinning
 *    144 would guarantee misses on exactly the drag frames we just spent this
 *    whole investigation making fit. 120 Hz is 8.33 ms, which they clear.
 *  - **Nothing else on the device runs there.** 120 Hz is the display's own
 *    default mode (dumpsys display: mDefaultModeId=1), which is why every other
 *    app sits there. 144 buys no visual parity, only power draw.
 *
 * If the frame cost ever drops far enough that 144 is genuinely fillable, this is
 * the one number to change — the selection logic needs no other edit.
 */
private const val CEILING_HZ = 120f

/**
 * Holds the mode decision for one activity instance and applies/releases it
 * across ON_START/ON_STOP.
 *
 * The decision is resolved ONCE, on the first ON_START, and cached. Re-deriving
 * it on every start would be actively wrong: `display.mode` reports the mode the
 * display is running RIGHT NOW, which after our first pin is the peak mode we
 * asked for. A second pass would then compare the peak against itself, conclude
 * there was nothing to gain, and quietly stop asking — the request would work
 * exactly once per process and then evaporate.
 */
private class PeakRefreshRateObserver(
    private val activity: ComponentActivity,
) : LifecycleEventObserver {

    /** Set on the first ON_START; null means "resolved, and there is nothing to do". */
    private var target: Display.Mode? = null

    /**
     * The resolution [target] was chosen against; 0x0 until the first ON_START.
     * Kept separately from [target] so a null decision ("nothing faster here")
     * is still re-examined if the resolution changes underneath it.
     */
    private var resolvedWidth = 0
    private var resolvedHeight = 0

    override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
        when (event) {
            Lifecycle.Event.ON_START -> onStart()
            Lifecycle.Event.ON_STOP -> onStop()
            // Lifecycle keeps a strong reference to observers, so drop ours as
            // soon as the activity is gone rather than relying on the registry
            // being collected with it.
            Lifecycle.Event.ON_DESTROY -> source.lifecycle.removeObserver(this)
            else -> Unit
        }
    }

    private fun onStart() {
        val current = activity.display?.mode
        // Re-resolve only when the RESOLUTION has changed since the decision was
        // made (the display-size setting, or a resizable/foldable display moving
        // us to another panel). A modeId names a resolution as well as a rate, so
        // re-pinning one picked against a resolution the display has since left
        // would drag it back to the old one.
        //
        // The rate the display happens to be running at is NOT a re-resolve
        // trigger, and cannot be: see [resolveTarget], which never looks at it.
        if (current == null ||
            current.physicalWidth != resolvedWidth ||
            current.physicalHeight != resolvedHeight
        ) {
            target = resolveTarget()
            resolvedWidth = current?.physicalWidth ?: 0
            resolvedHeight = current?.physicalHeight ?: 0
        }
        // UNCONDITIONAL, and deliberately before the mode pin. These hints are what
        // stop the platform's power-saving logic halving us mid-scroll, and they
        // are worth asking for even when there is no mode worth pinning or no
        // display to ask about. An earlier version returned early on a null
        // target and so dropped them silently along with the pin, which is how a
        // build that merely failed to reach 120 Hz ALSO went back to sagging to
        // 60 — two symptoms from one missing line.
        applyFrameRateCategory(peak = true)
        val mode = target ?: return
        applyPreferredMode(mode.modeId)
    }

    private fun onStop() {
        applyFrameRateCategory(peak = false)
        if (target == null) return
        applyPreferredMode(NO_PREFERRED_MODE)
    }

    /**
     * Picks the fastest supported mode at the CURRENT resolution that is at or
     * below [CEILING_HZ], or null when the panel publishes no such mode at all.
     * Logs either outcome, with the full mode list:
     *
     *     adb logcat -s GlyphWorks
     */
    private fun resolveTarget(): Display.Mode? {
        // Activity.getDisplay() rather than the deprecated
        // windowManager.defaultDisplay: the latter answers for the default
        // display regardless of where the activity actually is, which is the
        // wrong display on an external screen or a desktop-windowing session.
        // It is nullable — the activity may not be attached to a display yet.
        val display = activity.display
        if (display == null) {
            DebugLog.w(C, "no display attached; leaving refresh rate to the system")
            return null
        }
        val current = display.mode
        val best = display.supportedModes
            // Same physical resolution ONLY. See the file KDoc: pinning a modeId
            // pins width and height too, and the user's resolution is not ours
            // to change.
            .filter {
                it.physicalWidth == current.physicalWidth &&
                    it.physicalHeight == current.physicalHeight
            }
            // Highest AT OR BELOW the ceiling, not the panel's outright peak. See
            // [CEILING_HZ] — a faster panel mode than we can fill is worse than
            // not asking for it.
            .filter { it.refreshRate <= CEILING_HZ + REFRESH_RATE_EPSILON_HZ }
            .maxByOrNull { it.refreshRate }
        // NOTHING is compared against `current.refreshRate`, and that omission is
        // the point of this function.
        //
        // ON_START fires during the launch transition, while the display is still
        // on its DEFAULT mode — 120 Hz on this panel (dumpsys display:
        // mDefaultModeId=1). A "is the target faster than what we're on?" guard
        // therefore reads 120, sees the 120 Hz target, concludes there is nothing
        // to gain and pins nothing — after which the system settles the window
        // down to 90 and we have achieved precisely the opposite of the goal. It
        // survived earlier only because a 144 Hz target happened to clear it.
        //
        // The purpose here is to HOLD a rate against a display that switches modes
        // continuously, not to upgrade from one instantaneous sample of it. So the
        // only reason to give up is that there is no candidate mode at all.
        if (best == null) {
            DebugLog.i(
                C,
                "no mode at or below ${CEILING_HZ}Hz at " +
                    "${current.physicalWidth}x${current.physicalHeight}; " +
                    "supported ${display.supportedModes.joinToString { describe(it) }}",
            )
            return null
        }
        DebugLog.i(
            C,
            "pinning ${best.refreshRate}Hz (modeId=${best.modeId}) " +
                "at ${current.physicalWidth}x${current.physicalHeight}; " +
                "display was on ${current.refreshRate}Hz (modeId=${current.modeId}); " +
                "supported ${display.supportedModes.joinToString { describe(it) }}",
        )
        return best
    }

    /**
     * Writes the mode preference onto the window. `window.attributes` hands back
     * the live LayoutParams instance, so the mutation alone changes nothing that
     * is visible to WindowManager — the assignment back is what pushes the new
     * params through to the window session.
     */
    private fun applyPreferredMode(modeId: Int) {
        activity.window.attributes = activity.window.attributes.also {
            it.preferredDisplayModeId = modeId
        }
    }

    /**
     * Android 15's frame-rate voting sits ON TOP of the pinned mode: even with
     * the display locked to 120 Hz, a layer the platform judges to be "not
     * demanding" is presented at a divisor of that (60, 40, 30…), which is
     * precisely the symptom the mode pin alone does not cure. These three calls
     * are the public, non-reflective way to say "this window's content is the
     * demanding kind" and are all API 35 (verified present in the compileSdk 37
     * android.jar), hence the single version guard.
     *
     * @param peak true on start (ask for the top of the range), false on stop
     * (hand the decision back to the platform's defaults).
     */
    private fun applyFrameRateCategory(peak: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
        val window = activity.window
        // Applied to the decor view so it covers the whole window's content:
        // categories propagate down the hierarchy, and every animating surface
        // in this app (pager, springs, the matrix preview) is a descendant.
        window.decorView.requestedFrameRate = if (peak) {
            View.REQUESTED_FRAME_RATE_CATEGORY_HIGH
        } else {
            View.REQUESTED_FRAME_RATE_CATEGORY_DEFAULT
        }
        // The "balanced" power-savings mode is what lets the platform quietly
        // shave our presentation rate for battery. Off while we are on screen,
        // back to the platform default (true) the instant we are not — the
        // saving is only ever given up for a window the user is looking at.
        window.setFrameRatePowerSavingsBalanced(!peak)
        // Touch boost is already the platform default; only correct it if some
        // theme or OEM policy turned it off. Not restored on stop: unlike the
        // two above it has no effect on a window nobody can touch, and it is
        // scoped to THIS activity's window, which is torn down with the
        // activity — there is nothing global left behind to release.
        if (peak && !window.frameRateBoostOnTouchEnabled) {
            window.setFrameRateBoostOnTouchEnabled(true)
        }
    }

    private fun describe(mode: Display.Mode): String =
        "${mode.physicalWidth}x${mode.physicalHeight}@${mode.refreshRate}(id=${mode.modeId})"
}
