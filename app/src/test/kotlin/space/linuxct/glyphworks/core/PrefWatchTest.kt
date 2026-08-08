package space.linuxct.glyphworks.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [PrefWatch] — the rule that a value can only be held by something with a live
 * subscription to it.
 *
 * ## The defect this pins down
 *
 * The Toys tab's "currently active toy" highlight went stale and stayed stale
 * until the user backgrounded the app. It was a `mutableStateOf` seeded once
 * from the store plus a `DisposableEffect` that registered a change listener,
 * and those two have different lifetimes: a `HorizontalPager` page that leaves
 * the viewport window is deactivated with its remembered values kept and its
 * `DisposableEffect`s disposed, so the listener went away while the value it fed
 * lived on. Writes during the gap were lost with nothing on screen to show it.
 *
 * None of the Compose half is reachable from a JVM unit test (this module has no
 * Robolectric and no `compose-ui-test`), so what is tested here is the piece the
 * fix put the rule in: **re-subscribing must deliver the current value**, not
 * merely resume delivery of future ones. `resubscribing catches up on what it
 * missed` is the regression test proper — it fails if the read is ever dropped
 * from `PrefWatch.start()`, which is the old shape restored.
 *
 * ## Why the fake is not [space.linuxct.glyphworks.FakePrefs]
 *
 * The shared fake notifies on every write. Real `SharedPreferences` does not: it
 * only reports keys whose value differs from what is stored. That difference is
 * the second half of the bug — once the holder is stale, the user re-selecting
 * what they can see selected writes the value that is already there and notifies
 * nobody — so it has to be modelled to be tested at all.
 */
class PrefWatchTest {
    /** A [Prefs] with `SharedPreferences`' notification semantics: equal writes are silent. */
    private class DedupingPrefs : Prefs {
        private val map = mutableMapOf<String, Any>()
        private val listeners = mutableListOf<(String) -> Unit>()

        /** How many listeners are registered — the leak check. */
        val listenerCount: Int get() = listeners.size

        override fun getBoolean(key: String, def: Boolean) = map[key] as? Boolean ?: def
        override fun getInt(key: String, def: Int) = map[key] as? Int ?: def
        override fun getLong(key: String, def: Long) = map[key] as? Long ?: def
        override fun getFloat(key: String, def: Float) = map[key] as? Float ?: def
        override fun getString(key: String, def: String) = map[key] as? String ?: def
        override fun contains(key: String) = map.containsKey(key)

        override fun remove(key: String) {
            if (map.remove(key) != null) notify(key)
        }

        private fun put(key: String, v: Any) {
            if (map[key] == v) return // SharedPreferencesImpl.commitToMemory skips equal values
            map[key] = v
            notify(key)
        }

        private fun notify(key: String) = listeners.toList().forEach { it(key) }

        override fun putBoolean(key: String, v: Boolean) = put(key, v)
        override fun putInt(key: String, v: Int) = put(key, v)
        override fun putLong(key: String, v: Long) = put(key, v)
        override fun putFloat(key: String, v: Float) = put(key, v)
        override fun putString(key: String, v: String) = put(key, v)

        override fun addChangeListener(listener: (String) -> Unit) {
            listeners += listener
        }

        override fun removeChangeListener(listener: (String) -> Unit) {
            listeners -= listener
        }
    }

    private val key = PrefKeys.CURRENT_SCREEN
    private val def = PrefKeys.CURRENT_SCREEN_DEF

    private fun watchOf(prefs: Prefs, seen: MutableList<String>) =
        PrefWatch(prefs, key, { it.getString(key, def) }) { seen += it }

    @Test
    fun `starting delivers the value as it stands`() {
        val prefs = DedupingPrefs()
        prefs.putString(key, "clock")
        val seen = mutableListOf<String>()
        watchOf(prefs, seen).start()
        assertEquals(listOf("clock"), seen)
    }

    @Test
    fun `changes arrive while watching`() {
        val prefs = DedupingPrefs()
        val seen = mutableListOf<String>()
        watchOf(prefs, seen).start()
        prefs.putString(key, "clock")
        prefs.putString(key, "eyes")
        assertEquals(listOf(def, "clock", "eyes"), seen)
    }

    @Test
    fun `other keys are ignored`() {
        val prefs = DedupingPrefs()
        val seen = mutableListOf<String>()
        watchOf(prefs, seen).start()
        prefs.putString(PrefKeys.SCREEN_ORDER, "dice,clock")
        prefs.putFloat(PrefKeys.BRIGHTNESS, 0.5f)
        assertEquals(listOf(def), seen)
    }

    @Test
    fun `stopping ends delivery`() {
        val prefs = DedupingPrefs()
        val seen = mutableListOf<String>()
        val watch = watchOf(prefs, seen)
        watch.start()
        watch.stop()
        prefs.putString(key, "clock")
        assertEquals(listOf(def), seen)
    }

    /**
     * THE regression test. A pager page deactivated and re-activated is a stop
     * followed by a start on a holder whose value survived in between; if the
     * start only resumes delivery, that holder keeps showing the value from
     * before the gap for as long as it lives.
     */
    @Test
    fun `resubscribing catches up on what it missed`() {
        val prefs = DedupingPrefs()
        val seen = mutableListOf<String>()
        val watch = watchOf(prefs, seen)

        watch.start()
        assertEquals(listOf(def), seen)

        // The page leaves the pager's viewport window.
        watch.stop()
        // The Essential Key cycles the toy while it is away.
        prefs.putString(key, "clock")
        assertEquals("nothing may be delivered while stopped", listOf(def), seen)

        // The page comes back to the same retained slot.
        watch.start()
        assertEquals(listOf(def, "clock"), seen)
    }
}
