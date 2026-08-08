package space.linuxct.glyphmatrixtoycompat.core

import space.linuxct.glyphmatrixtoycompat.core.ai.ChatWire
import space.linuxct.glyphmatrixtoycompat.core.ai.GlyphAiOrchestrator
import space.linuxct.glyphmatrixtoycompat.core.ai.ReasoningEffort

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

    /**
     * Appending an id here needs no [PrefsMigration] bump: a store written by an
     * older build simply does not mention the new screen, and
     * `ScreenManager.enabledScreens()` appends every roster screen missing from
     * the stored CSV to the end of the order. A migration would only be needed to
     * rename or remove an id, which is a different operation entirely.
     */
    const val SCREEN_ORDER_DEF =
        "ambient,clock,eyes,speed,battery,solar,moon,dice,coin,dino,bottle,rps,counter,breathing," +
            "timer,compass,level,visualizer,custom"

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

    /**
     * Id of the user design the Custom toy plays, or "" for none chosen yet.
     *
     * Only the id is persisted — the art itself lives in a file (see
     * `designs/DesignStore`) and reaches the screen through
     * [space.linuxct.glyphmatrixtoycompat.core.DesignPort]. An id that no longer
     * names a stored design is not an error state: both the port and the
     * settings dialog fall back to the first available design, so deleting the
     * selected one leaves the toy showing art rather than a placeholder.
     */
    const val CUSTOM_DESIGN_ID = "customDesignId"
    const val CUSTOM_DESIGN_ID_DEF = ""

    /**
     * The name stamped into `author` on designs this phone creates, or "" if the
     * user has never set one.
     *
     * Empty is a perfectly good value and is the default on purpose: attribution
     * is opt-in, and a design with no author is still a valid, shareable file.
     * It is only ever read when a design is CREATED — `author` is immutable once
     * set (see `ui/CreateTab.kt`), so changing this later renames nothing that
     * already exists, which is the whole point of the field.
     */
    const val CREATOR_NAME = "creatorName"
    const val CREATOR_NAME_DEF = ""

    /**
     * The model id the design assistant talks to, or "" to use the built-in one.
     *
     * Here rather than beside the OAuth token because it is not a secret — it is
     * the name of a model, the same kind of fact as [CREATOR_NAME] — and the
     * credential-protected stores are unreadable before the first unlock, which
     * is a cost with nothing to buy.
     *
     * The default is [ChatWire.MODEL] itself rather than a second copy of the
     * literal, so "what does this app ask for" has exactly one answer. Empty is
     * a perfectly good stored value: [ChatWire.resolveModel] turns it back into
     * the built-in default, which is what makes clearing the field a reset.
     */
    const val AI_MODEL = "aiModel"
    const val AI_MODEL_DEF = ChatWire.MODEL

    /**
     * How many tool rounds the design assistant may take before a turn is cut
     * short and salvaged.
     *
     * Configurable because the built-in eight is a budget, not a safety limit,
     * and it is the wrong budget for the task the user actually has: a request
     * like "animate this across twenty frames" spends rounds reading, writing and
     * re-reading the canvas, and running out mid-way produces a half-drawn design
     * and a turn that has to explain itself. The ceiling still exists — a model
     * that has stopped converging will loop until something stops it, and every
     * round costs a request — so this widens the budget rather than removing it.
     *
     * Clamped on read by [aiMaxRounds] rather than trusted, because it is a
     * stored integer and the only thing standing between a corrupt value and an
     * unbounded loop is the code that reads it.
     */
    const val AI_MAX_ROUNDS = "aiMaxRounds"
    const val AI_MAX_ROUNDS_DEF = GlyphAiOrchestrator.DEFAULT_MAX_ROUNDS
    const val AI_MAX_ROUNDS_MIN = 4
    const val AI_MAX_ROUNDS_MAX = 40

    /**
     * The granularity the UI offers: 4, 8, 12 … 40, ten positions rather than
     * thirty-seven.
     *
     * A presentation constant that lives here because the range does. One detent
     * per round drew a rail of 35 tick marks — unreadable as anything but noise,
     * and it implied a precision this number does not have: nobody knows that
     * their animation needs 23 rounds rather than 24. Four is the coarsest step
     * that still divides both bounds and the default, so every endpoint the code
     * cares about is a position the slider can actually land on.
     *
     * Not enforced on read. A value between the detents is perfectly valid and
     * [aiMaxRounds] will honour it — this governs what the slider offers, not
     * what the setting accepts, so an older stored value or a future control with
     * finer granularity is not invalidated by it.
     */
    const val AI_MAX_ROUNDS_STEP = 4

    /**
     * How hard the design assistant is asked to think before it answers, as the
     * lowercase token that goes on the wire.
     *
     * The token rather than the enum's name, and rather than an ordinal: this is
     * the exact string the request carries, so what is stored is what is sent and
     * there is no table in the middle to get out of step. An ordinal would also
     * silently re-point at a different level the first time the list is reordered.
     *
     * The default is [ChatWire.DEFAULT_REASONING_EFFORT] itself, not a second copy
     * of the literal — same reasoning as [AI_MODEL_DEF] — so a store with nothing
     * in it behaves exactly as this app did before the setting existed.
     *
     * Read through [aiReasoningEffort], which maps an unknown token back to a
     * known level. Not all six levels are known to be accepted by the backend;
     * see [space.linuxct.glyphmatrixtoycompat.core.ai.ReasoningEffort] for which
     * are documented, which are plausible and which are guesses.
     */
    const val AI_REASONING_EFFORT = "aiReasoningEffort"
    const val AI_REASONING_EFFORT_DEF = ChatWire.DEFAULT_REASONING_EFFORT

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

    /**
     * Whether the "would you like to watch the tutorial?" offer has ever been put
     * up on the Create tab. **Prompted, not answered** — it is written the moment
     * the dialog goes on screen, so a process death with it open cannot bring it
     * back, and a "no" and a swipe away are the same thing as far as this key is
     * concerned. There is deliberately no way to reset it from the UI: the tour
     * itself is a row in the Tutorials tab, which is what the follow-up message
     * points at.
     */
    const val CREATE_TOUR_PROMPTED = "createTourPrompted"
    const val CREATE_TOUR_PROMPTED_DEF = false
}

/**
 * The assistant's tool-round budget, clamped into [PrefKeys.AI_MAX_ROUNDS_MIN] …
 * [PrefKeys.AI_MAX_ROUNDS_MAX].
 *
 * A function rather than a raw `getInt` at the call site, and the clamp is the
 * reason: the stored value is an integer the user typed, and the thing it
 * controls is the only bound on a loop that issues a network request per
 * iteration. A zero or a negative — from a corrupt store, or from an editing
 * state that briefly reads as empty — would end the turn before it began; an
 * absurdly large one turns a misbehaving model into a long, expensive spin. So
 * the ceiling is enforced where it is read, not where it is written, because
 * that is the only place that cannot be bypassed.
 */
fun Prefs.aiMaxRounds(): Int =
    getInt(PrefKeys.AI_MAX_ROUNDS, PrefKeys.AI_MAX_ROUNDS_DEF)
        .coerceIn(PrefKeys.AI_MAX_ROUNDS_MIN, PrefKeys.AI_MAX_ROUNDS_MAX)

/**
 * The assistant's reasoning effort, as a level that can always be displayed.
 *
 * The same shape as [aiMaxRounds] and for the same reason: the guard belongs at
 * the read, because that is the only place it cannot be bypassed. The failure it
 * guards against is different, though — this is a *string*, so the danger is not
 * an out-of-range number but a token nothing recognises: one written by a build
 * that offered a level this one does not, or edited by hand. Degrading it to
 * [ReasoningEffort.DEFAULT] means the settings row can always draw itself, which
 * is what makes the setting recoverable from the UI rather than only by clearing
 * app data.
 *
 * It is deliberately NOT a write-back. Reading does not repair the store, so a
 * value this build does not know survives an upgrade that reintroduces it.
 */
fun Prefs.aiReasoningEffort(): ReasoningEffort =
    ReasoningEffort.fromWire(getString(PrefKeys.AI_REASONING_EFFORT, PrefKeys.AI_REASONING_EFFORT_DEF))
