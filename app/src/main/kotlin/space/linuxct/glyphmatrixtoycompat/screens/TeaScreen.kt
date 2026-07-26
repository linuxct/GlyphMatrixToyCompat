package space.linuxct.glyphmatrixtoycompat.screens

import space.linuxct.glyphmatrixtoycompat.core.Events
import space.linuxct.glyphmatrixtoycompat.core.GlyphScreen
import space.linuxct.glyphmatrixtoycompat.core.PrefKeys
import space.linuxct.glyphmatrixtoycompat.core.ScreenContext
import space.linuxct.glyphmatrixtoycompat.matrix.MatrixCanvas
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Tea Time. Glyph Touch starts a steep timer (persisted via teaTimeStart so
 * it survives screen switches and process death); pressing while steeping is
 * a no-op. Completion is driven primarily by the in-process 1 s ticker
 * (final frame + chime + backstop-alarm cancel); TeaTimeAlarmReceiver covers
 * process death. Re-entering after the deadline shows the done state WITHOUT
 * replaying the chime and clears the persisted start (also covers reboots,
 * where alarms are lost; no boot receiver needed).
 */
class TeaScreen : GlyphScreen {
    override val id = "tea"
    override val interactive = true

    private var ctx: ScreenContext? = null

    private fun startMillis(c: ScreenContext) = c.prefs.getLong(PrefKeys.TEA_START, PrefKeys.TEA_START_DEF)
    private fun durationSec(c: ScreenContext) =
        c.prefs.getInt(PrefKeys.TEA_DURATION, PrefKeys.TEA_DURATION_DEF).coerceAtLeast(5)

    override fun onActivate(ctx: ScreenContext) {
        this.ctx = ctx
        val start = startMillis(ctx)
        if (start > 0) {
            val elapsedSec = (ctx.ports.clock.nowMillis() - start) / 1000
            if (elapsedSec >= durationSec(ctx)) {
                // Deadline passed while we were away (or across a reboot):
                // show done, clear state, no chime replay.
                ctx.prefs.putLong(PrefKeys.TEA_START, 0L)
                ctx.prefs.putLong(PrefKeys.TEA_CHIMED_FOR, 0L)
                ctx.ports.tea.cancelAlarm()
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
        if (startMillis(c) > 0) return // already steeping
        val now = c.ports.clock.nowMillis()
        c.prefs.putLong(PrefKeys.TEA_START, now)
        c.prefs.putLong(PrefKeys.TEA_CHIMED_FOR, 0L)
        c.ports.tea.scheduleAlarm(now + durationSec(c) * 1000L)
        startTicker()
    }

    private fun startTicker() {
        ctx?.scheduler?.setTicker(1000) { tick() }
    }

    private fun tick() {
        val c = ctx ?: return
        val start = startMillis(c)
        if (start <= 0) {
            c.scheduler.clearTicker()
            c.pushFrame(renderIdle(c.size))
            return
        }
        val duration = durationSec(c)
        val elapsedSec = (c.ports.clock.nowMillis() - start) / 1000
        if (elapsedSec >= duration) {
            c.prefs.putLong(PrefKeys.TEA_START, 0L)
            c.ports.tea.cancelAlarm()
            // Skip the chime if the backstop receiver already sounded for this
            // steep (long-doze race) — never double-chime.
            if (c.prefs.getLong(PrefKeys.TEA_CHIMED_FOR, PrefKeys.TEA_CHIMED_FOR_DEF) != start) {
                c.ports.tea.chime()
            }
            c.prefs.putLong(PrefKeys.TEA_CHIMED_FOR, 0L)
            c.scheduler.clearTicker()
            c.pushFrame(renderDone(c.size))
            return
        }
        c.pushFrame(renderSteeping(c.size, elapsedSec.toFloat() / duration, (elapsedSec % 6).toInt()))
    }

    companion object {
        private fun bagX(size: Int, wobble: Int): Int {
            val center = size / 2
            // 6-subframe wobble cycle: 0,+1,+1,0,-1,-1
            val dx = (sin(wobble / 6.0 * 2.0 * Math.PI) * 1.4).roundToInt().coerceIn(-1, 1)
            return center + dx
        }

        private fun drawBag(canvas: MatrixCanvas, size: Int, x: Int, v: Int) {
            val top = if (size >= 25) 4 else 1
            val bagTop = if (size >= 25) 10 else 5
            canvas.line(size / 2, top, x, bagTop - 1, v / 2)
            canvas.fillRect(x - 1, bagTop, 3, 3, v)
            canvas.set(x, bagTop + 1, v / 3) // tag dot for texture
        }

        fun renderIdle(size: Int): IntArray {
            val canvas = MatrixCanvas(size)
            val center = (size - 1) / 2f
            canvas.ring(center, center, size / 2f - 1f, size / 2f - 0.2f, 600)
            drawBag(canvas, size, size / 2, 2500)
            return canvas.copyOut()
        }

        fun renderSteeping(size: Int, fraction: Float, subframe: Int): IntArray {
            val canvas = MatrixCanvas(size)
            val center = (size - 1) / 2f
            canvas.ring(center, center, size / 2f - 1f, size / 2f - 0.2f, 500)
            canvas.arcRing(
                center, center, size / 2f - 1f, size / 2f - 0.2f,
                0f, 360f * fraction.coerceIn(0f, 1f), 4095,
            )
            drawBag(canvas, size, bagX(size, subframe), 2500)
            return canvas.copyOut()
        }

        fun renderDone(size: Int): IntArray {
            val canvas = MatrixCanvas(size)
            val center = (size - 1) / 2f
            canvas.ring(center, center, size / 2f - 1f, size / 2f - 0.2f, 4095)
            drawBag(canvas, size, size / 2, 3000)
            // Steam: rising dots above the bag.
            val steamBase = if (size >= 25) 7 else 3
            canvas.set(size / 2 - 2, steamBase, 1200)
            canvas.set(size / 2 + 2, steamBase - 1, 1200)
            return canvas.copyOut()
        }
    }
}
