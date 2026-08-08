package space.linuxct.glyphmatrixtoycompat.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import space.linuxct.glyphmatrixtoycompat.Core
import space.linuxct.glyphmatrixtoycompat.core.PrefWatch
import space.linuxct.glyphmatrixtoycompat.core.Prefs

/**
 * A pref key as observable Compose state: the value now, and every later value,
 * for as long as this stays composed.
 *
 * **The one supported way to read a pref that something outside this
 * composition can write.** The hand-rolled version — `remember { mutableStateOf(
 * Core.prefs.getX(...)) }` seeded once, plus a `DisposableEffect` that registers
 * a change listener — looks equivalent and is not: the seed and the
 * subscription have different lifetimes, and a `HorizontalPager` page that
 * leaves the viewport window is deactivated with its remembered state kept and
 * its `DisposableEffect`s disposed. Every write during that gap was lost, and
 * because the state survived, nothing on screen ever showed that it had been.
 * See [PrefWatch] for the mechanism in full; it holds the rule (subscribing is
 * reading) so that this is the only place Compose has to get right.
 *
 * The seed here is deliberately *not* the value the caller ends up trusting —
 * [PrefWatch.start] overwrites it from the store on the effect pass of the very
 * frame this is first composed on (and of every frame it is re-activated on),
 * which is what closes the gap. It exists only so the first composition has
 * something to draw.
 *
 * There is therefore no resume-time re-read anywhere near a pref-backed value,
 * and there must not be one added back. A `LifecycleResumeEffect` that re-reads
 * is not a fix, it is a *mask*: it repairs the holder at the next
 * background/foreground and so turns "this value is wrong" into "this value is
 * wrong until the user happens to leave the app", which is precisely the shape
 * that let the Toys tab ship with a stale highlight.
 *
 * [read] is not a key of the subscription — it is expected to be a pure read of
 * [key] and nothing else, so its identity changing on recomposition (it is a
 * lambda literal at every call site) must not tear the registration down and
 * build it back up.
 */
@Composable
internal fun <T> rememberPref(key: String, read: (Prefs) -> T): State<T> {
    val state = remember(key) { mutableStateOf(read(Core.prefs)) }
    DisposableEffect(key) {
        val watch = PrefWatch(Core.prefs, key, read) { state.value = it }
        watch.start()
        onDispose { watch.stop() }
    }
    return state
}
