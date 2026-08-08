package space.linuxct.glyphworks.screens

import space.linuxct.glyphworks.core.GlyphScreen
import space.linuxct.glyphworks.core.ScreenContext
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Lunar phase from the mean synodic month (29.530588853 days) anchored at
 * the new moon of 2000-01-06 18:14 UTC. Accurate to a few hours — plenty for
 * a 13x13 rendering.
 */
object MoonMath {
    private const val NEW_MOON_EPOCH_MS = 947_182_440_000L
    private const val SYNODIC_MS = 2_551_442_876.9

    /** 0 = new, 0.25 = first quarter, 0.5 = full, 0.75 = last quarter. */
    fun phaseFraction(epochMillis: Long): Double {
        val elapsed = (epochMillis - NEW_MOON_EPOCH_MS).toDouble()
        var f = (elapsed % SYNODIC_MS) / SYNODIC_MS
        if (f < 0) f += 1.0
        return f
    }
}

/**
 * Moon Phase: a textured lunar disc showing the real near-side surface
 * (maria as dim regions, highlands bright) with the current phase carved out
 * by a soft terminator. The dark side glows faintly (earthshine) so the full
 * disc stays readable. The surface brightness maps below are our own coarse
 * downsample of the real Moon's near-side — an astronomical fact, north up.
 */
class MoonScreen : GlyphScreen {
    override val id = "moon"
    override val interactive = false

    private var ctx: ScreenContext? = null

    override fun onActivate(ctx: ScreenContext) {
        this.ctx = ctx
        ctx.scheduler.setTicker(60_000) { tick() }
    }

    override fun onDeactivate() {
        ctx = null
    }

    private fun tick() {
        val c = ctx ?: return
        c.pushFrame(renderFrame(c.size, MoonMath.phaseFraction(c.ports.clock.nowMillis())))
    }

    companion object {
        private const val REF_MAX = 147 // brightest cell in the surface maps
        private const val EARTHSHINE = 0.09f // faint glow on the unlit side
        private const val SOFT = 0.14f // terminator softness (fraction of radius)

        fun renderFrame(size: Int, phase: Double): IntArray {
            val n = if (size >= 25) 25 else 13
            val map = if (n == 25) MOON_25 else MOON_13
            val out = IntArray(size * size)
            val center = (n - 1) / 2f
            val r = n / 2f
            val terminator = cos(2.0 * Math.PI * phase).toFloat()
            val waxing = phase <= 0.5

            for (y in 0 until n) {
                for (x in 0 until n) {
                    val ref = map[y * n + x]
                    if (ref == 0) continue // outside the lunar disc
                    val base = ref * 4095 / REF_MAX

                    val dx = x - center
                    val dy = y - center
                    val chord = sqrt((r * r - dy * dy).coerceAtLeast(0.0001f))
                    val xn = (dx / chord).coerceIn(-1f, 1f)
                    // Signed distance past the terminator (>0 = lit side).
                    val signed = if (waxing) xn - terminator else -xn - terminator
                    val litFactor = (0.5f + signed / (2f * SOFT)).coerceIn(0f, 1f)

                    val bright = base * (EARTHSHINE + (1f - EARTHSHINE) * litFactor)
                    out[y * size + x] = bright.toInt().coerceIn(0, 4095)
                }
            }
            return out
        }

        // Coarse brightness maps of the real Moon's near-side (0..147),
        // north up, row-major — one per matrix size.
        private val MOON_13 = intArrayOf(
            0, 0, 0, 15, 20, 25, 37, 36, 24, 13, 0, 0, 0,
            0, 0, 11, 22, 20, 25, 46, 57, 44, 43, 26, 0, 0,
            0, 12, 23, 22, 25, 43, 53, 30, 27, 42, 62, 29, 0,
            3, 21, 37, 48, 40, 51, 51, 28, 26, 39, 77, 56, 14,
            10, 35, 59, 73, 43, 44, 33, 49, 31, 22, 46, 57, 18,
            14, 30, 51, 50, 36, 61, 90, 79, 24, 17, 23, 56, 34,
            18, 28, 41, 56, 82, 87, 110, 109, 65, 43, 47, 30, 41,
            41, 37, 43, 48, 71, 97, 104, 121, 106, 76, 69, 24, 37,
            46, 37, 51, 31, 69, 100, 101, 107, 121, 64, 68, 48, 39,
            22, 55, 57, 61, 96, 121, 109, 104, 111, 82, 74, 60, 15,
            0, 36, 81, 116, 147, 136, 113, 107, 97, 94, 106, 42, 0,
            0, 0, 38, 93, 128, 124, 102, 94, 93, 79, 38, 0, 0,
            0, 0, 0, 27, 76, 89, 85, 77, 60, 17, 0, 0, 0,
        )

        private val MOON_25 = intArrayOf(
            0, 0, 0, 0, 0, 0, 4, 11, 13, 21, 23, 28, 32, 30, 27, 21, 15, 7, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 2, 27, 28, 24, 24, 28, 29, 46, 47, 36, 37, 30, 26, 25, 7, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 1, 18, 24, 15, 15, 20, 25, 29, 43, 58, 61, 59, 43, 39, 47, 38, 11, 0, 0, 0, 0,
            0, 0, 0, 3, 14, 21, 19, 19, 21, 24, 24, 40, 46, 55, 52, 43, 44, 41, 41, 47, 40, 10, 0, 0, 0,
            0, 0, 2, 18, 21, 22, 23, 22, 27, 32, 39, 45, 52, 43, 26, 24, 30, 35, 42, 47, 53, 38, 10, 0, 0,
            0, 0, 18, 28, 21, 28, 28, 28, 29, 32, 50, 54, 58, 37, 25, 28, 28, 25, 51, 68, 69, 60, 37, 3, 0,
            0, 5, 19, 25, 26, 38, 35, 37, 37, 34, 47, 76, 59, 25, 22, 29, 29, 20, 39, 83, 74, 68, 53, 21, 0,
            0, 9, 21, 29, 41, 63, 63, 59, 52, 45, 56, 44, 38, 42, 36, 37, 28, 23, 42, 57, 66, 58, 50, 39, 9,
            5, 12, 36, 44, 43, 78, 92, 63, 54, 31, 30, 36, 23, 42, 47, 53, 27, 22, 28, 30, 50, 85, 36, 25, 19,
            9, 16, 40, 46, 48, 64, 53, 45, 34, 34, 71, 67, 40, 41, 70, 36, 15, 24, 22, 16, 27, 80, 47, 11, 24,
            11, 16, 27, 34, 45, 64, 51, 36, 32, 36, 61, 83, 89, 97, 84, 40, 25, 23, 21, 19, 22, 49, 64, 26, 33,
            16, 18, 22, 32, 39, 43, 59, 49, 60, 67, 48, 85, 109, 122, 94, 56, 22, 24, 26, 24, 28, 42, 56, 57, 37,
            18, 18, 22, 44, 43, 44, 63, 60, 98, 97, 89, 97, 108, 101, 105, 96, 68, 29, 51, 63, 38, 32, 23, 44, 37,
            32, 26, 26, 40, 35, 34, 64, 41, 77, 95, 93, 102, 121, 107, 108, 137, 93, 44, 74, 80, 51, 25, 20, 27, 37,
            38, 46, 48, 24, 44, 42, 54, 47, 66, 89, 85, 94, 107, 106, 120, 126, 90, 63, 80, 93, 52, 25, 26, 39, 34,
            47, 53, 35, 41, 60, 47, 41, 38, 57, 94, 97, 98, 104, 109, 113, 122, 132, 93, 59, 81, 77, 30, 41, 63, 30,
            35, 56, 18, 40, 53, 51, 38, 35, 78, 107, 96, 105, 102, 103, 100, 115, 136, 80, 39, 74, 82, 55, 42, 51, 20,
            9, 61, 44, 36, 53, 46, 32, 31, 65, 102, 103, 95, 102, 111, 100, 100, 118, 87, 55, 69, 63, 50, 51, 39, 9,
            0, 35, 65, 58, 50, 73, 75, 64, 100, 121, 129, 117, 123, 104, 97, 113, 120, 97, 87, 81, 73, 67, 61, 26, 0,
            0, 7, 45, 63, 66, 84, 114, 119, 147, 147, 133, 126, 106, 104, 117, 109, 101, 99, 87, 96, 105, 83, 44, 3, 0,
            0, 0, 13, 54, 79, 85, 107, 133, 144, 147, 132, 121, 113, 111, 100, 99, 89, 89, 89, 104, 109, 64, 15, 0, 1,
            0, 0, 0, 19, 55, 77, 93, 122, 131, 134, 130, 121, 107, 107, 101, 91, 93, 92, 85, 79, 56, 17, 0, 0, 0,
            0, 0, 0, 0, 14, 50, 80, 104, 121, 123, 123, 111, 94, 90, 91, 92, 96, 92, 67, 45, 13, 0, 0, 0, 0,
            0, 0, 0, 1, 0, 15, 47, 72, 103, 107, 101, 96, 91, 88, 85, 84, 73, 54, 25, 6, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 1, 0, 0, 12, 49, 73, 70, 84, 83, 76, 65, 61, 40, 12, 0, 0, 0, 0, 0, 0, 0,
        )
    }
}
