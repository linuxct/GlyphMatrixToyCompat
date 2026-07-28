package space.linuxct.glyphmatrixtoycompat.core

/**
 * Minimal settings store abstraction. The Android implementation wraps
 * SharedPreferences in DEVICE-PROTECTED storage (Direct Boot safe) — see
 * util/AndroidPrefs. Tests use an in-memory fake.
 */
interface Prefs {
    fun getBoolean(key: String, def: Boolean): Boolean
    fun getInt(key: String, def: Int): Int
    fun getLong(key: String, def: Long): Long
    fun getFloat(key: String, def: Float): Float
    fun getString(key: String, def: String): String
    /** True when [key] has a stored value (distinct from reading back a default). */
    fun contains(key: String): Boolean
    fun remove(key: String)
    fun putBoolean(key: String, v: Boolean)
    fun putInt(key: String, v: Int)
    fun putLong(key: String, v: Long)
    fun putFloat(key: String, v: Float)
    fun putString(key: String, v: String)
    fun addChangeListener(listener: (String) -> Unit)
    fun removeChangeListener(listener: (String) -> Unit)
}

/** Every persisted key with its default. */
object PrefKeys {
    const val PREFS_VERSION = "prefs_version"
    const val PREFS_VERSION_DEF = 1

    /**
     * Schema version [PrefsMigration] brings any older store up to. Note the
     * default above must stay 1: AndroidPrefs stamps it on the first launch of
     * ANY build (it cannot tell a fresh install from a pre-versioning one), so
     * version 1 means "possibly legacy" and the migration is what proves
     * otherwise.
     */
    const val PREFS_VERSION_CURRENT = 2

    const val MASTER_TOGGLE = "master_toggle"
    const val MASTER_TOGGLE_DEF = true

    /** Optional Essential-Key "menu mode": double-press opens a blinking toy selector. */
    const val MENU_MODE_ENABLED = "menuModeEnabled"
    const val MENU_MODE_ENABLED_DEF = false

    const val SCREEN_ORDER = "screen_order"
    const val SCREEN_ORDER_DEF =
        "ambient,clock,eyes,speed,battery,solar,moon,dice,coin,dino,bottle,rps,counter,breathing," +
            "timer,compass,level,visualizer"

    /** Per-screen enable flag: screen_enabled_<id>, default true. */
    fun screenEnabled(id: String) = "screen_enabled_$id"

    const val CURRENT_SCREEN = "current_screen"
    const val CURRENT_SCREEN_DEF = "ambient"

    const val BRIGHTNESS = "brightness"
    const val BRIGHTNESS_DEF = 1.0f

    /**
     * Opportunistic auto-brightness: while a render session is live the light
     * sensor is sampled periodically and [BRIGHTNESS] is written from it. Off by
     * default; touching the brightness slider turns it back off.
     */
    const val AUTO_BRIGHTNESS = "autoBrightness"
    const val AUTO_BRIGHTNESS_DEF = false

    const val USE_12H = "use12hClock" // default seeded from the system 24h setting

    const val CLOCK_THEME = "clockTheme"
    const val CLOCK_THEME_DEF = 0

    const val SELECTED_DICE = "diceType"
    const val SELECTED_DICE_DEF = "D6"

    /** Coin Flip result design: 0 = H/T letters, 1 = portrait & numeral art. */
    const val COIN_DESIGN = "coinDesign"
    const val COIN_DESIGN_DEF = 0

    /** Show charge power instead of the gauge while charging (Battery toy). */
    const val BATTERY_SHOW_WATTS = "batteryShowWatts"
    const val BATTERY_SHOW_WATTS_DEF = false

    const val BREATHING_PACE = "breathingPace"
    const val BREATHING_PACE_DEF = "4"

    const val COUNTER = "counterValue"
    const val COUNTER_DEF = 0

    const val TIMER_START = "timerStartMillis"
    const val TIMER_START_DEF = 0L

    const val TIMER_DURATION = "timerDurationSec"
    const val TIMER_DURATION_DEF = 60

    /**
     * Selectable timer durations, stored in SECONDS but only ever offered (and
     * shown) to the user in whole minutes: 1, 3, 5, 7, 10, 13 min. Shared with
     * [PrefsMigration], which snaps any legacy value onto this list so the
     * settings dialog can always show the stored one.
     */
    val TIMER_DURATION_OPTIONS = listOf(60, 180, 300, 420, 600, 780)

    /**
     * Elapsed milliseconds banked by a PAUSED countdown, 0 when not paused.
     *
     * [TIMER_START] alone cannot express "paused": a paused timer has no
     * deadline, so its start is cleared and idle and paused would both read as
     * start == 0. This pair makes the four states disjoint (see TimerScreen):
     * paused is exactly "this value > 0", and it takes precedence over
     * [TIMER_START] so a crash between the two writes still reads as paused.
     * Pausing banks at least 1 ms for that reason.
     */
    const val TIMER_PAUSED_ELAPSED = "timerPausedElapsedMillis"
    const val TIMER_PAUSED_ELAPSED_DEF = 0L

    /** Start timestamp the backstop receiver already chimed for (prevents double chimes). */
    const val TIMER_CHIMED_FOR = "timerChimedFor"
    const val TIMER_CHIMED_FOR_DEF = 0L

    const val AMBIENT_BACKGROUND = "ambientBackground"
    const val AMBIENT_BACKGROUND_DEF = 0

    const val AMBIENT_USE_BACKGROUND = "ambientUseBackground"
    const val AMBIENT_USE_BACKGROUND_DEF = true

    const val AMBIENT_NIGHT_VISIBLE = "ambientVisibleAtNight"
    const val AMBIENT_NIGHT_VISIBLE_DEF = true

    const val AMBIENT_SHAKE_ACTIVATE = "ambientShakeToShow"
    const val AMBIENT_SHAKE_ACTIVATE_DEF = false

    const val AMBIENT_USE_CHARGING = "ambientShowCharging"
    const val AMBIENT_USE_CHARGING_DEF = true

    const val AMBIENT_CHARGING_STYLE = "ambientChargingStyle"
    const val AMBIENT_CHARGING_STYLE_DEF = 0

    const val VISUALIZER_THEME = "visualizerTheme"
    const val VISUALIZER_THEME_DEF = 0

    /** FFT gain/decay tuning, 1..6 (higher = hotter response); defaults to the calmest. */
    const val VISUALIZER_TUNING = "visualizerTuning"
    const val VISUALIZER_TUNING_DEF = 1

    /** Set when the system delivers an AOD hint to the visualizer screen. */
    const val VISUALIZER_AOD_HINT = "visualizerAodHint"
    const val VISUALIZER_AOD_HINT_DEF = false

    const val SERVICE_HEARTBEAT = "service_heartbeat"
    const val SERVICE_HEARTBEAT_DEF = 0L

    /**
     * Last time the system bound (or messaged) the AOD toy service. There is
     * no queryable "selected always-on toy" setting and no SDK query API, and
     * the system binds the chosen toy lazily — so any recorded bind is taken
     * as lasting proof of the selection (a latch, not a freshness window).
     */
    const val TOY_LAST_BOUND = "toyLastBound"
    const val TOY_LAST_BOUND_DEF = 0L

    /** Release version the daily update check has already notified about. */
    const val UPDATE_NOTIFIED_VERSION = "updateNotifiedVersion"
    const val UPDATE_NOTIFIED_VERSION_DEF = ""

    /** First-run onboarding completed; MainActivity redirects there until set. */
    const val ONBOARDING_DONE = "onboardingDone"
    const val ONBOARDING_DONE_DEF = false
}
