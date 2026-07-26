package space.linuxct.glyphmatrixtoycompat.core

/**
 * Process-wide diagnostic log with a pluggable sink so pure-Kotlin classes
 * (ScreenManager, screens) can log without touching android.util.Log — the
 * JVM test default is a no-op; Core.init installs the logcat sink under the
 * single tag "GlyphToyCompat" (filter with: adb logcat -s GlyphToyCompat).
 *
 * These logs are intentionally kept in RELEASE builds for field debugging —
 * see app/proguard-rules.pro. Do not strip them.
 */
object DebugLog {
    const val TAG = "GlyphToyCompat"

    enum class Level { DEBUG, INFO, WARN }

    @Volatile
    var sink: (level: Level, component: String, message: String) -> Unit = { _, _, _ -> }

    fun d(component: String, message: String) = sink(Level.DEBUG, component, message)
    fun i(component: String, message: String) = sink(Level.INFO, component, message)
    fun w(component: String, message: String) = sink(Level.WARN, component, message)
}
