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

    const val MASTER_TOGGLE = "master_toggle"
    const val MASTER_TOGGLE_DEF = true

    const val SCREEN_ORDER = "screen_order"
    const val SCREEN_ORDER_DEF = "ambient,clock,eyes,speed,battery,solar,moon,dice,coin,counter,breathing,tea,compass,visualizer"

    /** Per-screen enable flag: screen_enabled_<id>, default true. */
    fun screenEnabled(id: String) = "screen_enabled_$id"

    const val CURRENT_SCREEN = "current_screen"
    const val CURRENT_SCREEN_DEF = "ambient"

    const val BRIGHTNESS = "brightness"
    const val BRIGHTNESS_DEF = 1.0f

    const val USE_12H = "use12hClock" // default seeded from the system 24h setting

    const val CLOCK_THEME = "clockTheme"
    const val CLOCK_THEME_DEF = 0

    const val SELECTED_DICE = "diceType"
    const val SELECTED_DICE_DEF = "D6"

    const val BREATHING_PACE = "breathingPace"
    const val BREATHING_PACE_DEF = "4"

    const val COUNTER = "counterValue"
    const val COUNTER_DEF = 0

    const val TEA_START = "teaStartMillis"
    const val TEA_START_DEF = 0L

    const val TEA_DURATION = "teaDurationSec"
    const val TEA_DURATION_DEF = 60

    /** Start timestamp the backstop receiver already chimed for (prevents double chimes). */
    const val TEA_CHIMED_FOR = "teaChimedFor"
    const val TEA_CHIMED_FOR_DEF = 0L

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

    /** FFT gain/decay tuning, 1..6 (higher = hotter response). */
    const val VISUALIZER_TUNING = "visualizerTuning"
    const val VISUALIZER_TUNING_DEF = 6

    /** Set when the system delivers an AOD hint to the visualizer screen. */
    const val VISUALIZER_AOD_HINT = "visualizerAodHint"
    const val VISUALIZER_AOD_HINT_DEF = false

    const val SERVICE_HEARTBEAT = "service_heartbeat"
    const val SERVICE_HEARTBEAT_DEF = 0L
}
