package space.linuxct.glyphmatrixtoycompat.screens

import space.linuxct.glyphmatrixtoycompat.core.GlyphScreen
import space.linuxct.glyphmatrixtoycompat.core.ScreenContext
import space.linuxct.glyphmatrixtoycompat.matrix.MatrixCanvas

/**
 * Eyes: two eyes with wandering pupils and periodic blinks, 50 ms ticker.
 * Pupils drift toward a random target every 1.5-3.5 s; a 6-tick blink
 * (closing / closed / opening) runs every 2.5-5.5 s.
 */
class EyesScreen : GlyphScreen {
    override val id = "eyes"
    override val interactive = false

    private var ctx: ScreenContext? = null

    private var pupilX = 0f
    private var pupilY = 0f
    private var targetX = 0f
    private var targetY = 0f
    private var nextWanderAt = 0L
    private var nextBlinkAt = 0L
    private var blinkPhase = -1 // -1 = open; 0..5 = blink animation step

    override fun onActivate(ctx: ScreenContext) {
        this.ctx = ctx
        val now = ctx.ports.clock.nowMillis()
        nextWanderAt = now + 1500
        nextBlinkAt = now + 2500
        blinkPhase = -1
        pupilX = 0f; pupilY = 0f; targetX = 0f; targetY = 0f
        ctx.scheduler.setTicker(50) { tick() }
    }

    override fun onDeactivate() {
        ctx = null
    }

    private fun tick() {
        val c = ctx ?: return
        val now = c.ports.clock.nowMillis()

        if (now >= nextWanderAt) {
            targetX = (c.ports.random.nextInt(3) - 1).toFloat()
            targetY = (c.ports.random.nextInt(3) - 1).toFloat()
            nextWanderAt = now + 1500 + c.ports.random.nextInt(2000)
        }
        pupilX += (targetX - pupilX) * 0.25f
        pupilY += (targetY - pupilY) * 0.25f

        if (blinkPhase < 0 && now >= nextBlinkAt) blinkPhase = 0

        val canvas = MatrixCanvas(c.size)
        val big = c.size >= 25
        val scale = if (big) 2 else 1
        // Eye geometry (13x13): 3x4 whites at x=2..4 / 8..10, y=4..7.
        val eyeW = 3 * scale
        val eyeH = 4 * scale
        val eyeY = 4 * scale
        val leftX = 2 * scale
        val rightX = 8 * scale

        // Lid coverage in rows, from the top of the eye, per blink step.
        val cover = when (blinkPhase) {
            0, 4 -> eyeH / 2
            1, 3 -> eyeH - 1
            2 -> eyeH
            else -> 0
        }

        for (ex in listOf(leftX, rightX)) {
            if (cover >= eyeH) {
                // Fully closed: a single lid line at the eye's vertical middle.
                for (x in ex until ex + eyeW) canvas.light(x, eyeY + eyeH / 2, 4095)
            } else {
                canvas.fillRect(ex, eyeY + cover, eyeW, eyeH - cover, 700)
                val px = ex + eyeW / 2 + Math.round(pupilX) * (if (big) 2 else 1)
                val py = eyeY + eyeH / 2 + Math.round(pupilY) * (if (big) 2 else 1)
                for (dy in 0 until scale + (scale - 1)) {
                    for (dx in 0 until scale) {
                        val yy = py + dy - (scale - 1)
                        if (yy >= eyeY + cover) canvas.set(px + dx - scale / 2, yy, 4095)
                    }
                }
            }
        }

        if (blinkPhase >= 0) {
            blinkPhase++
            if (blinkPhase > 5) {
                blinkPhase = -1
                nextBlinkAt = now + 2500 + c.ports.random.nextInt(3000)
            }
        }

        c.pushFrame(canvas.copyOut())
    }
}
