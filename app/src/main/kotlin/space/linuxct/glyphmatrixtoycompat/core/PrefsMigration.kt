package space.linuxct.glyphmatrixtoycompat.core

import kotlin.math.abs

/**
 * One-shot upgrade of the persisted store, run from Core.init BEFORE anything
 * reads a key (ScreenManager and SessionArbiter both read prefs as soon as
 * they exist). Pure Kotlin over [Prefs] so it is JVM-testable, and safe in
 * Direct Boot because [Prefs] is backed by device-protected storage only.
 *
 * Schema 2 carries the Timer toy across its rename: it used to be called "tea"
 * on disk as well as on screen, and its duration presets used to be a set of
 * seconds that the minute-based list can no longer display.
 *
 * Every step is a no-op when the legacy key is absent, so a fresh install goes
 * straight to the current version without writing anything else, and the
 * version guard means an upgraded store is never rewritten twice.
 */
object PrefsMigration {

    // Legacy (pre-rename) names. Frozen strings: they only ever exist on disk
    // in old installs, so they must NOT be derived from PrefKeys.
    private const val OLD_SCREEN_ID = "tea"
    private const val NEW_SCREEN_ID = "timer"
    private const val OLD_START = "teaStartMillis"
    private const val OLD_DURATION = "teaDurationSec"
    private const val OLD_CHIMED_FOR = "teaChimedFor"

    /** Returns true when the store was actually upgraded. */
    fun run(prefs: Prefs): Boolean {
        val from = prefs.getInt(PrefKeys.PREFS_VERSION, PrefKeys.PREFS_VERSION_DEF)
        if (from >= PrefKeys.PREFS_VERSION_CURRENT) return false
        renameTimerKeys(prefs)
        renameScreenId(prefs)
        snapTimerDuration(prefs)
        prefs.putInt(PrefKeys.PREFS_VERSION, PrefKeys.PREFS_VERSION_CURRENT)
        return true
    }

    /** teaStartMillis / teaDurationSec / teaChimedFor -> timer* equivalents. */
    private fun renameTimerKeys(prefs: Prefs) {
        moveLong(prefs, OLD_START, PrefKeys.TIMER_START, PrefKeys.TIMER_START_DEF)
        moveLong(prefs, OLD_CHIMED_FOR, PrefKeys.TIMER_CHIMED_FOR, PrefKeys.TIMER_CHIMED_FOR_DEF)
        moveInt(prefs, OLD_DURATION, PrefKeys.TIMER_DURATION, PrefKeys.TIMER_DURATION_DEF)
    }

    /** The screen id appears in three places on disk. */
    private fun renameScreenId(prefs: Prefs) {
        val oldEnabled = PrefKeys.screenEnabled(OLD_SCREEN_ID)
        moveBoolean(prefs, oldEnabled, PrefKeys.screenEnabled(NEW_SCREEN_ID), true)

        if (prefs.contains(PrefKeys.SCREEN_ORDER)) {
            val order = prefs.getString(PrefKeys.SCREEN_ORDER, PrefKeys.SCREEN_ORDER_DEF)
            val renamed = order.split(',')
                .joinToString(",") { if (it.trim() == OLD_SCREEN_ID) NEW_SCREEN_ID else it }
            if (renamed != order) prefs.putString(PrefKeys.SCREEN_ORDER, renamed)
        }

        if (prefs.contains(PrefKeys.CURRENT_SCREEN) &&
            prefs.getString(PrefKeys.CURRENT_SCREEN, PrefKeys.CURRENT_SCREEN_DEF) == OLD_SCREEN_ID
        ) {
            prefs.putString(PrefKeys.CURRENT_SCREEN, NEW_SCREEN_ID)
        }
    }

    /**
     * The duration presets moved from seconds (30/60/120/180/240) to minutes,
     * so a stored value can be one the radio list cannot show. Snap it to the
     * nearest offered duration instead of silently leaving it unselectable;
     * ties go to the LONGER one, so nobody's timer gets shortened by a coin
     * flip (the old 120 s becomes 3 min, not 1).
     */
    private fun snapTimerDuration(prefs: Prefs) {
        if (!prefs.contains(PrefKeys.TIMER_DURATION)) return
        val stored = prefs.getInt(PrefKeys.TIMER_DURATION, PrefKeys.TIMER_DURATION_DEF)
        if (stored in PrefKeys.TIMER_DURATION_OPTIONS) return
        val nearest = PrefKeys.TIMER_DURATION_OPTIONS
            .minWithOrNull(compareBy({ abs(it - stored) }, { -it }))
            ?: PrefKeys.TIMER_DURATION_DEF
        prefs.putInt(PrefKeys.TIMER_DURATION, nearest)
    }

    // Moves only when the old key exists and the new one does not, so a store
    // that has already been written by a new build is never clobbered.
    private inline fun move(prefs: Prefs, old: String, new: String, copy: () -> Unit) {
        if (!prefs.contains(old)) return
        if (!prefs.contains(new)) copy()
        prefs.remove(old)
    }

    private fun moveLong(prefs: Prefs, old: String, new: String, def: Long) =
        move(prefs, old, new) { prefs.putLong(new, prefs.getLong(old, def)) }

    private fun moveInt(prefs: Prefs, old: String, new: String, def: Int) =
        move(prefs, old, new) { prefs.putInt(new, prefs.getInt(old, def)) }

    private fun moveBoolean(prefs: Prefs, old: String, new: String, def: Boolean) =
        move(prefs, old, new) { prefs.putBoolean(new, prefs.getBoolean(old, def)) }
}
