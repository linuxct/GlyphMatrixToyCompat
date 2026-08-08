package space.linuxct.glyphmatrixtoycompat.core

/**
 * One pref key, watched: [onValue] is handed the value as it stands at [start],
 * and again on every later change to that key, until [stop].
 *
 * ## Why the read and the subscription are the same call
 *
 * The obvious shape — seed a variable once, then register a change listener
 * somewhere else with its own lifetime — is a live bug, and it shipped here. A
 * `HorizontalPager` page that leaves the viewport window is **deactivated and
 * retained**, not destroyed: Compose's `deactivateCurrentGroup` clears every
 * `RememberObserver` in the group (so a `DisposableEffect` really does dispose,
 * and the listener really is removed) but leaves plain remembered values —
 * a `mutableStateOf` among them — untouched, and `LazyLayout` keeps up to seven
 * such slots per content type, so the page comes back to the *same* slot with
 * its old state intact. The two lifetimes are therefore different: the value
 * survives the gap, the subscription does not, and every write that lands
 * inside the gap is invisible for as long as the value survives.
 *
 * Nothing about that is specific to the pager. Any host that can pause a
 * subscription without discarding the value it feeds has the same hole, which is
 * why the rule lives here rather than in the composable that first needed it:
 * **subscribing IS reading**. A caller cannot subscribe without getting the
 * current value, so it cannot hold a value it did not get from a live
 * subscription.
 *
 * ## Ordering
 *
 * [start] registers BEFORE it reads. The other order has a window of its own —
 * a write that lands between the read and the registration is reported to
 * nobody — whereas registering first can only ever produce a duplicate
 * [onValue] with the same value, which every caller here is idempotent under.
 *
 * ## Why "no change" cannot be relied on
 *
 * `SharedPreferences` only reports keys whose value actually *differs* from
 * what is stored (`SharedPreferencesImpl.commitToMemory` skips equal values when
 * it builds `keysModified`), so re-writing the value that is already there
 * notifies nobody. A holder that has gone stale therefore cannot be repaired by
 * the user re-selecting what they can see is selected — which is exactly how
 * this surfaced: the Toys tab's play button wrote the id the store already held.
 * The test fake notifies unconditionally, so only a fake that models the real
 * semantics can reproduce that half; see `PrefWatchTest`.
 */
class PrefWatch<T>(
    private val prefs: Prefs,
    private val key: String,
    private val read: (Prefs) -> T,
    private val onValue: (T) -> Unit,
) {

    private var listener: ((String) -> Unit)? = null

    /** True while this watch holds a registration. */
    val watching: Boolean get() = listener != null

    /**
     * Registers, then delivers the current value. Idempotent: a second call
     * while already watching does nothing, so a host that starts twice cannot
     * leave a registration behind for [stop] to miss.
     */
    fun start() {
        if (listener != null) return
        val l: (String) -> Unit = { changed ->
            if (changed == key) {
                val v = read(prefs)
                DebugLog.d(C, "$key changed -> $v")
                onValue(v)
            }
        }
        listener = l
        prefs.addChangeListener(l)
        val seed = read(prefs)
        DebugLog.d(C, "watching $key, now $seed")
        onValue(seed)
    }

    /** Drops the registration. Idempotent. */
    fun stop() {
        val l = listener ?: return
        DebugLog.d(C, "released $key")
        listener = null
        prefs.removeChangeListener(l)
    }

    private companion object {
        /**
         * Traced deliberately, and kept.
         *
         * Two fixes for the Toys tab's stale highlight were reasoned out from the
         * Compose sources and both were wrong about *something*, because the one
         * question that decides it — was this key subscribed at the instant the
         * user tapped, and did the store report the write — cannot be answered by
         * reading code. These three lines answer it from a device in one pass:
         * a `watching`/`released` pair around every gap, and a `changed` for
         * every write that actually arrived.
         *
         * DEBUG level, so it is filterable, and `DebugLog` survives release
         * builds on purpose (see its KDoc and proguard-rules.pro) — the whole
         * point is that a user hitting this in the field can produce the trace.
         */
        const val C = "PrefWatch"
    }
}
