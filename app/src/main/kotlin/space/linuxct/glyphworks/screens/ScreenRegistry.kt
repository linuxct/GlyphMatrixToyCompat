package space.linuxct.glyphworks.screens

import space.linuxct.glyphworks.core.GlyphScreen
import space.linuxct.glyphworks.screens.ambient.AmbientScreen

/**
 * Full screen roster in canonical cycle order (also the default of the
 * screen_order pref): the ambient background first, then the toys.
 *
 * ## Taking a toy out
 *
 * This list is what the Essential Key cycles through and what actually renders,
 * but it is **not** what the Toys tab lists — that is `DISPLAY_NAMES` in
 * ui/MainActivity.kt, a second roster keyed by the same ids. Removing a toy from
 * one and not the other leaves it half-gone: still cycling with no row to
 * disable it, or a row that toggles a pref no screen reads. Both, or neither.
 *
 * Nothing else needs touching. `enabledScreens()` resolves the stored
 * `screen_order` CSV through `associateBy { it.id }` and `mapNotNull`, so an id
 * with no screen behind it is dropped rather than crashing, and `currentScreen()`
 * falls back to the first enabled screen — a user sitting on the removed toy is
 * moved off it. `SCREEN_ORDER_DEF` deliberately still names it; see there.
 */
object ScreenRegistry {
    fun create(): List<GlyphScreen> = listOf(
        AmbientScreen(),
        ClockScreen(),
        EyesScreen(),
        SpeedScreen(),
        BatteryScreen(),
        SolarScreen(),
        MoonScreen(),
        DiceScreen(),
        CoinScreen(),
        DinoScreen(),
        BottleScreen(),
        // TEMPORARILY DISABLED — Rock Paper Scissors. RpsScreen and its tests are
        // untouched; this line and the matching one in DISPLAY_NAMES are the whole
        // switch. Restore both together.
        // RpsScreen(),
        CounterScreen(),
        BreathingScreen(),
        TimerScreen(),
        CompassScreen(),
        LevelScreen(),
        VisualizerScreen(),
        CustomScreen(),
    )
}
