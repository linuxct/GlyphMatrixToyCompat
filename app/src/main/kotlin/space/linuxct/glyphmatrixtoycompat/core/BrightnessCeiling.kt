package space.linuxct.glyphmatrixtoycompat.core

import space.linuxct.glyphmatrixtoycompat.matrix.MAX_BRIGHTNESS
import kotlin.math.roundToInt

/**
 * Global brightness: rescales the whole frame so its brightest cell equals
 * brightness01 * 4095 (max-normalization — dim source art is brightened,
 * bright art is dimmed). Applied once per frame, after compositing, before
 * the push.
 */
object BrightnessCeiling {
    fun apply(frame: IntArray, brightness01: Float): IntArray {
        var max = 0
        for (v in frame) if (v > max) max = v
        if (max <= 0) return frame
        val target = (brightness01.coerceIn(0f, 1f) * MAX_BRIGHTNESS).roundToInt()
        if (target == max) return frame
        val out = IntArray(frame.size)
        for (i in frame.indices) {
            out[i] = (frame[i].toLong() * target / max).toInt()
        }
        return out
    }
}
