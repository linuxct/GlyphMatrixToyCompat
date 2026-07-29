package space.linuxct.glyphmatrixtoycompat.core

/** Handle for a scheduled one-shot action. */
interface Cancelable {
    fun cancel()
}

/**
 * Render scheduling abstraction. The Android implementation runs everything
 * on a single background HandlerThread ("compositor-worker"); the actual SDK
 * push hops to a second background looper ("glyph-io") inside GlyphLink.
 * Tests use a manually-advanced fake.
 *
 * Threading contract: ScreenManager and all screens run exclusively on the
 * scheduler thread; external callers marshal in via [run].
 */
interface RenderScheduler {
    /** Replaces the active repeating ticker. First tick fires immediately. */
    fun setTicker(intervalMs: Long, tick: () -> Unit)
    fun clearTicker()
    fun postDelayed(delayMs: Long, action: () -> Unit): Cancelable
    /** Executes [action] on the scheduler thread (inline if already on it). */
    fun run(action: () -> Unit)
}

/** Everything a screen needs to render: matrix size, settings, data ports, ticking, and the frame sink. */
class ScreenContext(
    val size: Int,
    val prefs: Prefs,
    val ports: Ports,
    val scheduler: RenderScheduler,
    private val sink: (IntArray) -> Unit,
) {
    fun pushFrame(frame: IntArray) = sink(frame)
}

/**
 * One matrix display ("toy"). Lifecycle: [onActivate] when it becomes the
 * visible screen of a live session, [onDeactivate] when the session stops or
 * cycles away (must stop tickers via the scheduler and drop state as needed).
 * [onEvent] receives [Events] strings; only interactive screens act on CHANGE.
 */
interface GlyphScreen {
    val id: String
    val interactive: Boolean
    fun onActivate(ctx: ScreenContext)
    fun onDeactivate()
    fun onEvent(event: String) {}
}
