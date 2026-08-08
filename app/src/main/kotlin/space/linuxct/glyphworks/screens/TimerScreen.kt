package space.linuxct.glyphworks.screens

import space.linuxct.glyphworks.core.Events
import space.linuxct.glyphworks.core.GlyphScreen
import space.linuxct.glyphworks.core.PrefKeys
import space.linuxct.glyphworks.core.ScreenContext
import space.linuxct.glyphworks.matrix.MAX_BRIGHTNESS
import space.linuxct.glyphworks.matrix.MatrixCanvas
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Timer. Glyph Touch drives the whole toy with one press: idle starts the
 * countdown, running pauses it, paused resumes it, and a finished timer is
 * dismissed back to idle.
 *
 * State lives entirely in two prefs, so it survives screen switches AND process
 * death, and the four states are disjoint:
 *
 *  - idle:    pausedElapsed == 0 && start == 0
 *  - running: pausedElapsed == 0 && start >  0   (start = epoch ms of the run)
 *  - paused:  pausedElapsed >  0                 (start is cleared)
 *  - done:    both cleared, plus the in-memory [done] latch until the next press
 *
 * pausedElapsed is checked FIRST, so it alone decides "paused" — that is what
 * makes a crash between the two writes of a pause (or a resume) land on
 * "paused", the state that can never chime spuriously. Pausing banks at least
 * 1 ms so an instant pause is never mistaken for idle.
 *
 * Completion is driven primarily by the in-process ticker (final frame + chime
 * + backstop-alarm cancel); TimerAlarmReceiver covers process death. Re-entering
 * after the deadline shows the done state WITHOUT replaying the chime and clears
 * the persisted start (also covers reboots, where alarms are lost; no boot
 * receiver needed). A paused timer has no deadline at all: its backstop alarm is
 * cancelled on pause and re-scheduled for the REMAINING time on resume, so
 * pausing at 50 % still reads 50 % an hour later.
 *
 * Rendering is an hourglass with no hourglass in it: the matrix itself is the
 * vessel. Grains fall through the empty space at the top and the settled sand
 * rises across the whole display, so the fill level IS the elapsed fraction
 * and expiry lights every cell. The countdown is always read off the clock —
 * the ticker rate only sets how smooth the fall looks. Paused keeps the sand
 * exactly where it stopped (no falling grains: nothing is falling) and blinks
 * the whole frame.
 */
class TimerScreen : GlyphScreen {
    override val id = "timer"
    override val interactive = true

    private var ctx: ScreenContext? = null
    private var donePhase = 0
    private var pausePhase = 0

    /** Finished and still showing it: the next press dismisses to idle. */
    private var done = false

    private fun startMillis(c: ScreenContext) = c.prefs.getLong(PrefKeys.TIMER_START, PrefKeys.TIMER_START_DEF)
    private fun pausedElapsed(c: ScreenContext) =
        c.prefs.getLong(PrefKeys.TIMER_PAUSED_ELAPSED, PrefKeys.TIMER_PAUSED_ELAPSED_DEF)

    private fun durationSec(c: ScreenContext) =
        c.prefs.getInt(PrefKeys.TIMER_DURATION, PrefKeys.TIMER_DURATION_DEF).coerceAtLeast(5)

    private fun durationMs(c: ScreenContext) = durationSec(c) * 1000L

    override fun onActivate(ctx: ScreenContext) {
        this.ctx = ctx
        done = false
        val paused = pausedElapsed(ctx)
        if (paused > 0) {
            // Paused wins over every clock comparison: a paused timer has no
            // deadline, so the "deadline passed while we were away" branch below
            // must never see it, however long the pause lasted.
            startPauseBlink()
            return
        }
        val start = startMillis(ctx)
        if (start > 0) {
            val elapsedSec = (ctx.ports.clock.nowMillis() - start) / 1000
            if (elapsedSec >= durationSec(ctx)) {
                // Deadline passed while we were away (or across a reboot):
                // show done, clear state, no chime replay (and no alert pulse —
                // the moment to celebrate is long gone).
                ctx.prefs.putLong(PrefKeys.TIMER_START, 0L)
                ctx.prefs.putLong(PrefKeys.TIMER_CHIMED_FOR, 0L)
                ctx.ports.timer.cancelAlarm()
                done = true
                ctx.pushFrame(renderDone(ctx.size))
            } else {
                startTicker()
            }
        } else {
            ctx.pushFrame(renderIdle(ctx.size))
        }
    }

    override fun onDeactivate() {
        ctx = null
    }

    override fun onEvent(event: String) {
        if (event != Events.CHANGE) return
        val c = ctx ?: return
        when {
            done -> dismissDone(c)
            pausedElapsed(c) > 0 -> resume(c)
            startMillis(c) > 0 -> pause(c)
            else -> start(c)
        }
    }

    private fun start(c: ScreenContext) {
        val now = c.ports.clock.nowMillis()
        c.prefs.putLong(PrefKeys.TIMER_START, now)
        c.prefs.putLong(PrefKeys.TIMER_CHIMED_FOR, 0L)
        c.ports.timer.scheduleAlarm(now + durationMs(c))
        startTicker()
    }

    /**
     * Banks the elapsed time and drops the deadline. Order matters: the pause
     * pref is written BEFORE the start is cleared, so a process death in
     * between reads back as paused (with the elapsed time intact) rather than
     * as a running timer with no bank. Cancelling the backstop is not optional —
     * left armed it would chime at the original deadline while the user thinks
     * the timer is stopped.
     */
    private fun pause(c: ScreenContext) {
        val elapsed = (c.ports.clock.nowMillis() - startMillis(c)).coerceIn(1L, durationMs(c))
        c.prefs.putLong(PrefKeys.TIMER_PAUSED_ELAPSED, elapsed)
        c.prefs.putLong(PrefKeys.TIMER_START, 0L)
        c.prefs.putLong(PrefKeys.TIMER_CHIMED_FOR, 0L)
        c.ports.timer.cancelAlarm()
        startPauseBlink()
    }

    /**
     * Rewinds the start by the banked elapsed time, which is what makes a
     * pause/resume cycle lose no time and gain none however often it repeats.
     * The backstop is re-armed for the REMAINING time only (start + duration ==
     * now + remaining). Order mirrors [pause]: the start is written first and
     * the bank cleared second, so a crash in between stays paused.
     */
    private fun resume(c: ScreenContext) {
        val start = c.ports.clock.nowMillis() - pausedElapsed(c)
        c.prefs.putLong(PrefKeys.TIMER_START, start)
        c.prefs.putLong(PrefKeys.TIMER_CHIMED_FOR, 0L)
        c.prefs.putLong(PrefKeys.TIMER_PAUSED_ELAPSED, 0L)
        c.ports.timer.scheduleAlarm(start + durationMs(c))
        startTicker()
    }

    /** Press on a finished timer: stop the flash and go back to the empty vessel. */
    private fun dismissDone(c: ScreenContext) {
        done = false
        c.scheduler.clearTicker()
        c.pushFrame(renderIdle(c.size))
    }

    private fun startTicker() {
        ctx?.scheduler?.setTicker(TICK_MS) { tick() }
    }

    private fun tick() {
        val c = ctx ?: return
        val start = startMillis(c)
        if (start <= 0) {
            c.scheduler.clearTicker()
            c.pushFrame(renderIdle(c.size))
            return
        }
        val durationMs = durationMs(c)
        val elapsedMs = c.ports.clock.nowMillis() - start
        if (elapsedMs >= durationMs) {
            c.prefs.putLong(PrefKeys.TIMER_START, 0L)
            c.ports.timer.cancelAlarm()
            // Skip the chime if the backstop receiver already sounded for this
            // run (long-doze race) — never double-chime.
            if (c.prefs.getLong(PrefKeys.TIMER_CHIMED_FOR, PrefKeys.TIMER_CHIMED_FOR_DEF) != start) {
                c.ports.timer.chime()
            }
            c.prefs.putLong(PrefKeys.TIMER_CHIMED_FOR, 0L)
            startDonePulse()
            return
        }
        c.pushFrame(
            renderRunning(
                c.size,
                elapsedMs.toFloat() / durationMs,
                (elapsedMs / TICK_MS).toInt(),
            )
        )
    }

    /**
     * A full matrix could be mistaken for "the glyph is simply on", so the
     * finished timer flashes for a few seconds before settling on the solid
     * frame. Bounded on purpose: nothing keeps ticking afterwards.
     */
    private fun startDonePulse() {
        donePhase = 0
        done = true
        ctx?.scheduler?.setTicker(TICK_MS) { pulseTick() }
    }

    private fun pulseTick() {
        val c = ctx ?: return
        val phase = donePhase++
        if (phase >= PULSE_FRAMES) {
            c.scheduler.clearTicker()
            c.pushFrame(renderDone(c.size))
            return
        }
        c.pushFrame(renderDonePulse(c.size, phase))
    }

    /**
     * Paused: nothing advances, so the ticker only exists to blink and runs at
     * the blink cadence ([BLINK_TICK_MS]) rather than the running frame rate.
     * The frame itself only changes twice per 750 ms cycle — ScreenManager
     * drops the identical repeats, so nothing reaches the device in between.
     */
    private fun startPauseBlink() {
        pausePhase = 0
        ctx?.scheduler?.setTicker(BLINK_TICK_MS) { pauseTick() }
    }

    private fun pauseTick() {
        val c = ctx ?: return
        val paused = pausedElapsed(c)
        if (paused <= 0) { // resumed or cleared underneath us
            c.scheduler.clearTicker()
            return
        }
        c.pushFrame(renderPaused(c.size, paused.toFloat() / durationMs(c), pausePhase++))
    }

    companion object {
        /**
         * Fall-animation tick. Fast enough that grains read as falling; the
         * elapsed fraction and the expiry test come from the clock, so accuracy
         * does not depend on it. Static frames are free — ScreenManager drops
         * byte-identical consecutive pushes.
         */
        const val TICK_MS = 125L

        /** Subframes of the completion flash (24 * 125 ms = 3 s, 6 blinks). */
        const val PULSE_FRAMES = 24

        /** Subframes per blink: 2 lit, 2 dark. */
        private const val PULSE_PERIOD = 4

        /**
         * Paused blink, borrowed wholesale from the Essential-Key menu selector
         * (ScreenManager.BLINK_ON_MS / BLINK_OFF_MS = 450 / 300): the app should
         * not have a third blink rhythm. Expressed as a tick of their gcd,
         * 150 ms, with 3 subframes lit and 2 dark.
         */
        const val BLINK_TICK_MS = 150L
        private const val BLINK_PERIOD = 5
        private const val BLINK_ON_FRAMES = 3

        /**
         * Peak half-amplitude of the sand mound, as a fraction of the matrix
         * height. Must stay below 1/4 so every column's height is monotonic in
         * the elapsed fraction (see [sandHeight]).
         */
        private const val MOUND = 0.22f

        private const val GRAIN_MIN_V = 1100
        private const val GRAIN_SPAN_V = 900

        /** One grain per this many free rows above the mound. */
        private const val GRAIN_SPACING = 3

        /**
         * Height of the settled sand at column [x], in rows measured up from
         * the bottom edge.
         *
         * The mean over the columns is `fraction * size`, so the lit area
         * tracks elapsed time; the triangular term peaks at the centre column
         * and tapers to the edges, which is how sand actually piles under a
         * stream. That term vanishes at both ends of the run, so the pile
         * starts as a small central cone and flattens into a completely full
         * matrix exactly at expiry.
         *
         * Because the amplitude factor `4f(1-f)` has slope at most 4 in
         * magnitude and [MOUND] < 1/4, every column's height is strictly
         * increasing in [fraction] — the display only ever completes, it never
         * drains.
         */
        private fun sandHeight(size: Int, fraction: Float, x: Int): Float {
            val f = fraction.coerceIn(0f, 1f)
            val c = (size - 1) / 2f
            val u = if (c <= 0f) 0f else abs(x - c) / c // 0 at the centre, 1 at the edges
            val amp = MOUND * size * 4f * f * (1f - f)
            return (f * size + amp * (1f - 2f * u)).coerceIn(0f, size.toFloat())
        }

        /**
         * Settled sand with sub-row precision: fully submerged rows go to full
         * brightness and the row holding the surface is lit in proportion to
         * how much of it the sand covers. On 13 rows a whole row would jerk
         * past every ~4.6 s of a 60 s timer; partial rows make the rise read as
         * continuous.
         */
        private fun drawSand(canvas: MatrixCanvas, size: Int, fraction: Float) {
            for (x in 0 until size) {
                val h = sandHeight(size, fraction, x)
                for (y in 0 until size) {
                    // Row y spans heights (size - 1 - y) .. (size - y).
                    val cover = (h - (size - 1 - y)).coerceIn(0f, 1f)
                    if (cover > 0f) canvas.light(x, y, (MAX_BRIGHTNESS * cover).roundToInt())
                }
            }
        }

        /**
         * Deterministic scramble. Grain placement must be reproducible for the
         * goldens, so the fall is a pure function of the subframe rather than a
         * draw from a RandomPort.
         */
        private fun hash(n: Int): Int {
            var h = n * -1640531527 // golden-ratio odd constant
            h = h xor (h ushr 15)
            h *= 0x27d4eb2d
            h = h xor (h ushr 13)
            return h and 0x7fffffff
        }

        /**
         * Grains falling through the empty space above the mound. The free
         * height shrinks as the sand rises, so the stream thins out on its own
         * and disappears once the matrix is full.
         */
        private fun drawGrains(canvas: MatrixCanvas, size: Int, fraction: Float, subframe: Int) {
            val centre = (size - 1) / 2
            // Free rows above the highest point of the mound.
            val span = (size - sandHeight(size, fraction, centre)).toInt()
            if (span <= 0) return
            val lateral = if (size >= 25) 2 else 1
            val maxGrains = if (size >= 25) 6 else 3
            // One grain per few free rows, twice as dense on the wider matrix
            // where the stream has more lanes to spread across.
            val grains = (span * lateral / GRAIN_SPACING).coerceIn(1, maxGrains)
            for (i in 0 until grains) {
                val pos = subframe + i * GRAIN_SPACING
                val y = Math.floorMod(pos, span)
                // New lane (and brightness) every time a grain wraps to the top.
                val seed = hash(Math.floorDiv(pos, span) * 31 + i)
                val x = centre + (seed % (2 * lateral + 1)) - lateral
                canvas.light(x, y, GRAIN_MIN_V + (seed / 7) % GRAIN_SPAN_V)
            }
        }

        /** The dim outline of the vessel itself. Shared by idle and paused. */
        private fun drawRim(canvas: MatrixCanvas, size: Int) {
            val centre = (size - 1) / 2f
            canvas.ring(centre, centre, size / 2f - 1f, size / 2f - 0.2f, 600)
        }

        /** Waiting to be started: the vessel is empty, only its rim hints at it. */
        fun renderIdle(size: Int): IntArray {
            val canvas = MatrixCanvas(size)
            drawRim(canvas, size)
            return canvas.copyOut()
        }

        /**
         * Running: sand settled up to [fraction] of the matrix height, grains
         * falling above it. At [fraction] 1 this is the completely full matrix,
         * identical to [renderDone].
         */
        fun renderRunning(size: Int, fraction: Float, subframe: Int): IntArray {
            val canvas = MatrixCanvas(size)
            drawSand(canvas, size, fraction)
            drawGrains(canvas, size, fraction, subframe)
            return canvas.copyOut()
        }

        /**
         * Paused: the settled sand stays exactly where it stopped, so the user
         * can still read how far along the timer is, and the whole frame blinks
         * on the menu selector's cadence. No falling grains — nothing is
         * falling. The idle rim is drawn under the sand so that pausing in the
         * first few percent still blinks something visible instead of black
         * against black. The off subframes go to black rather than dim: a blink
         * has to be unambiguous, and black is the only value that cannot be
         * mistaken for the vessel's own dim rim.
         */
        fun renderPaused(size: Int, fraction: Float, phase: Int): IntArray {
            val canvas = MatrixCanvas(size)
            if (Math.floorMod(phase, BLINK_PERIOD) < BLINK_ON_FRAMES) {
                drawRim(canvas, size)
                drawSand(canvas, size, fraction)
            }
            return canvas.copyOut()
        }

        /** Finished: every cell lit. */
        fun renderDone(size: Int): IntArray = renderDonePulse(size, 0)

        /**
         * Completion flash. Phase 0 is the solid full frame, so the first frame
         * of the pulse is exactly [renderDone]. Blinks to black rather than to a
         * dimmer grey so the flash reads at every brightness setting: at a low
         * one, full-white and dim-grey are only a few levels apart.
         */
        fun renderDonePulse(size: Int, phase: Int): IntArray {
            val canvas = MatrixCanvas(size)
            if (Math.floorMod(phase, PULSE_PERIOD) < PULSE_PERIOD / 2) canvas.fill(MAX_BRIGHTNESS)
            return canvas.copyOut()
        }
    }
}
