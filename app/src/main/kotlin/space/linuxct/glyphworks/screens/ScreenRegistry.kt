package space.linuxct.glyphworks.screens

import space.linuxct.glyphworks.core.GlyphScreen
import space.linuxct.glyphworks.screens.ambient.AmbientScreen

/**
 * Full screen roster in canonical cycle order (also the default of the
 * screen_order pref): the ambient background first, then the toys.
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
        RpsScreen(),
        CounterScreen(),
        BreathingScreen(),
        TimerScreen(),
        CompassScreen(),
        LevelScreen(),
        VisualizerScreen(),
        CustomScreen(),
    )
}
