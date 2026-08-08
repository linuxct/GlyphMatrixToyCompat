package space.linuxct.glyphworks.util

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The R8 rule that keeps `AndroidPrefs.spListener` alive.
 *
 * ## Why a test asserts on a text file
 *
 * Because the bug it guards is invisible everywhere else. `SharedPreferences`
 * holds change listeners in a `WeakHashMap`, so `AndroidPrefs` must hold the only
 * strong reference to the one it registers — and R8 deleted that field as unused
 * while keeping the registration, leaving the listener weakly reachable. One GC
 * later, every preference notification in the process stopped for good. It
 * shipped as a Toys tab whose highlight silently froze until the app was
 * restarted, it was rare because it needed a GC at the wrong moment, and it could
 * not reproduce in a debug build at all, because debug builds do not run R8.
 *
 * Nothing in the Kotlin source was ever wrong, so no ordinary test could see it —
 * a JVM unit test does not run R8, and the class behaves perfectly when it does
 * not. What can be checked cheaply and here is that the rule still exists.
 *
 * **This does not prove the field survives**, and must not be read as doing so.
 * `mapping.txt` omits members kept under their own name, so it cannot answer the
 * question either. The only real check is the shipped DEX:
 *
 * ```
 * unzip -p app-release.apk classes.dex | strings | grep -c spListener
 * ```
 *
 * 1 is correct, 0 is the bug. This test guards the rule that makes it 1.
 */
class AndroidPrefsKeepRuleTest {

    private fun proguardRules(): File {
        // Unit tests run with the module directory as the working directory in
        // some invocations and the repository root in others, so try both rather
        // than pinning one and failing on the other machine.
        val candidates = listOf(
            File("proguard-rules.pro"),
            File("app/proguard-rules.pro"),
            File("../app/proguard-rules.pro"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("proguard-rules.pro not found from ${File(".").absolutePath}")
    }

    @Test
    fun theListenerFieldIsKeptFromR8() {
        val rules = proguardRules().readText()
        assertTrue(
            "The -keep rule for AndroidPrefs.spListener is gone. Without it R8 deletes " +
                "the field, SharedPreferences' WeakHashMap drops the listener at the next " +
                "GC, and every preference change stops being delivered — permanently, and " +
                "only in release builds. See AndroidPrefs.spListener.",
            rules.contains("spListener"),
        )
        assertTrue(
            "The keep rule no longer names AndroidPrefs, so it cannot be matching the field.",
            rules.contains("space.linuxct.glyphworks.util.AndroidPrefs"),
        )
    }

    /**
     * The rule that would silently undo the diagnostic logging this app relies on
     * in the field. Called out in `proguard-rules.pro`'s own header; asserted here
     * because a comment cannot fail a build.
     */
    @Test
    fun logCallsAreNotStripped() {
        // DIRECTIVES ONLY. The file's own header warns against
        // `-assumenosideeffects` in prose, so a plain `contains` over the whole
        // text matches the warning and fails on a correct file — which is exactly
        // what it did the first time this was written. A ProGuard directive is a
        // line whose first non-blank character is `-`; a comment starts with `#`.
        val directives = proguardRules().readLines()
            .map { it.trim() }
            .filter { it.startsWith("-") }
        assertTrue(
            "-assumenosideeffects would strip DebugLog, which release builds keep on " +
                "purpose so field issues can be traced with `adb logcat -s GlyphWorks`.",
            directives.none { it.startsWith("-assumenosideeffects") },
        )
    }
}
