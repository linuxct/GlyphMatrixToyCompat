package space.linuxct.glyphworks.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import space.linuxct.glyphworks.FakePrefs

class PrefsMigrationTest {
    /** A store written by a pre-rename build, with a timer mid-run. */
    private fun legacyStore(): FakePrefs = FakePrefs().apply {
        putInt(PrefKeys.PREFS_VERSION, 1)
        putLong("teaStartMillis", 5_000L)
        putInt("teaDurationSec", 120)
        putLong("teaChimedFor", 4_000L)
        putBoolean("screen_enabled_tea", false)
        putString(PrefKeys.SCREEN_ORDER, "ambient,clock,tea,compass")
        putString(PrefKeys.CURRENT_SCREEN, "tea")
    }

    @Test
    fun `legacy store is fully translated`() {
        val prefs = legacyStore()
        assertTrue(PrefsMigration.run(prefs))

        // Values preserved, under the new key names.
        assertEquals(5_000L, prefs.getLong(PrefKeys.TIMER_START, -1L))
        assertEquals(4_000L, prefs.getLong(PrefKeys.TIMER_CHIMED_FOR, -1L))
        assertFalse(prefs.getBoolean(PrefKeys.screenEnabled("timer"), true))
        assertEquals("ambient,clock,timer,compass", prefs.getString(PrefKeys.SCREEN_ORDER, ""))
        assertEquals("timer", prefs.getString(PrefKeys.CURRENT_SCREEN, ""))
        // 120 s is not offered any more: snapped to the nearest minute preset.
        assertEquals(180, prefs.getInt(PrefKeys.TIMER_DURATION, -1))

        // No legacy keys left behind.
        assertTrue(prefs.map.keys.none { it.contains("tea") })
        assertEquals(PrefKeys.PREFS_VERSION_CURRENT, prefs.getInt(PrefKeys.PREFS_VERSION, -1))
    }

    @Test
    fun `fresh install writes nothing but the version`() {
        val prefs = FakePrefs()
        assertTrue(PrefsMigration.run(prefs))
        assertEquals(setOf(PrefKeys.PREFS_VERSION), prefs.map.keys)
        assertEquals(PrefKeys.PREFS_VERSION_CURRENT, prefs.getInt(PrefKeys.PREFS_VERSION, -1))
    }

    @Test
    fun `a partial legacy store neither crashes nor clobbers new values`() {
        val prefs = FakePrefs().apply {
            putInt(PrefKeys.PREFS_VERSION, 1)
            // Only one legacy key, no SCREEN_ORDER, no CURRENT_SCREEN.
            putLong("teaStartMillis", 7_000L)
            // ...and a value a newer build already wrote under the new name.
            putInt(PrefKeys.TIMER_DURATION, 600)
        }
        assertTrue(PrefsMigration.run(prefs))

        assertEquals(7_000L, prefs.getLong(PrefKeys.TIMER_START, -1L))
        assertEquals(600, prefs.getInt(PrefKeys.TIMER_DURATION, -1))
        assertFalse(prefs.contains(PrefKeys.SCREEN_ORDER))
        assertFalse(prefs.contains(PrefKeys.CURRENT_SCREEN))
        assertFalse(prefs.contains(PrefKeys.screenEnabled("timer")))
        assertTrue(prefs.map.keys.none { it.contains("tea") })
    }

    @Test
    fun `every legacy duration snaps onto an offered preset`() {
        for (legacy in intArrayOf(30, 60, 120, 180, 240, 5, 9_999)) {
            val prefs = FakePrefs().apply {
                putInt(PrefKeys.PREFS_VERSION, 1)
                putInt("teaDurationSec", legacy)
            }
            PrefsMigration.run(prefs)
            val snapped = prefs.getInt(PrefKeys.TIMER_DURATION, -1)
            assertTrue("$legacy -> $snapped", snapped in PrefKeys.TIMER_DURATION_OPTIONS)
        }
        // Spot-check the direction of the snap: nearest, ties to the longer.
        for ((legacy, expected) in listOf(30 to 60, 120 to 180, 240 to 300, 9_999 to 780)) {
            val prefs = FakePrefs().apply {
                putInt(PrefKeys.PREFS_VERSION, 1)
                putInt("teaDurationSec", legacy)
            }
            PrefsMigration.run(prefs)
            assertEquals(expected, prefs.getInt(PrefKeys.TIMER_DURATION, -1))
        }
    }
}
