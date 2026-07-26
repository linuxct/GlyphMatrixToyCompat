package space.linuxct.glyphmatrixtoycompat.screens

import space.linuxct.glyphmatrixtoycompat.core.GlyphScreen
import space.linuxct.glyphmatrixtoycompat.core.ScreenContext
import space.linuxct.glyphmatrixtoycompat.matrix.Font3x5
import space.linuxct.glyphmatrixtoycompat.matrix.MatrixCanvas
import kotlin.math.roundToInt

/**
 * Compass: <=30 fps ticker over the fused azimuth, rounded to 5 degrees.
 * The needle points toward magnetic/true north on the display: a bright head
 * ray with a dim tail, inside a cardinal tick ring (N brightest).
 */
class CompassScreen : GlyphScreen {
    override val id = "compass"
    override val interactive = false

    private var ctx: ScreenContext? = null

    override fun onActivate(ctx: ScreenContext) {
        this.ctx = ctx
        ctx.scheduler.setTicker(33) { tick() }
    }

    override fun onDeactivate() {
        ctx = null
    }

    private fun tick() {
        val c = ctx ?: return
        c.pushFrame(renderFrame(c.size, c.ports.azimuth.azimuthDegrees()))
    }

    companion object {
        fun renderFrame(size: Int, azimuthDeg: Float?): IntArray {
            val canvas = MatrixCanvas(size)
            val center = size / 2
            val ringR = (size / 2).toFloat()

            // Cardinal ticks: N bright, E/S/W dim, intercardinals faint.
            for (deg in 0 until 360 step 45) {
                val v = when (deg) {
                    0 -> 4095
                    90, 180, 270 -> 1500
                    else -> 500
                }
                canvas.polar(center, center, deg.toFloat(), ringR, v)
            }

            if (azimuthDeg == null) {
                Font3x5.drawStringCentered(canvas, "?", size / 2 - 2, 1500)
                return canvas.copyOut()
            }

            val az5 = ((azimuthDeg / 5f).roundToInt() * 5 % 360).toFloat()
            // Rotating the device by azimuth deg means north sits at -azimuth
            // on the display.
            val northAngle = (360f - az5) % 360f
            val headLen = if (size >= 25) 9f else 4.6f
            val tailLen = if (size >= 25) 5f else 2.6f
            canvas.ray(center, center, northAngle, headLen, 4095)
            canvas.ray(center, center, northAngle + 180f, tailLen, 900)
            canvas.set(center, center, 2200)
            return canvas.copyOut()
        }
    }
}
