package space.linuxct.glyphmatrixtoycompat.core

/**
 * Event vocabulary delivered to [GlyphScreen.onEvent].
 *
 * CHANGE/AOD/ACTION_DOWN/ACTION_UP mirror the string values of the SDK's
 * com.nothing.ketchum.GlyphToy constants (kept literal here so screens stay
 * free of Android/SDK imports and unit-testable on the JVM). SHAKE is our own
 * addition, produced by the shake detector while a session is live.
 */
object Events {
    const val CHANGE = "change"
    const val AOD = "aod"
    const val ACTION_DOWN = "action_down"
    const val ACTION_UP = "action_up"
    const val SHAKE = "compat_shake"
}
