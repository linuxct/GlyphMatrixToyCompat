package space.linuxct.glyphmatrixtoycompat.key

/**
 * Multi-click bookkeeping for the Essential Key (pure logic; the service owns
 * the Handler). Presses closer than [windowMs] apart accumulate; the caller
 * fires [finish] when the window elapses with no further press.
 */
class ClickCounter(private val windowMs: Long = WINDOW_MS) {

    private var count = 0
    private var lastPressAt = 0L

    /** Registers a press at [now]; returns the running count of the burst. */
    fun onPress(now: Long): Int {
        if (now - lastPressAt > windowMs) count = 0
        count++
        lastPressAt = now
        return count
    }

    /** Ends the burst: returns the final click count and resets. */
    fun finish(): Int {
        val c = count
        count = 0
        return c
    }

    companion object {
        const val WINDOW_MS = 400L
    }
}
