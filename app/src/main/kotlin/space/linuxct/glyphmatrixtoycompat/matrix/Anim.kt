package space.linuxct.glyphmatrixtoycompat.matrix

/** Small animation math helpers shared by the screens. */
object Anim {
    fun clamp01(t: Float): Float = t.coerceIn(0f, 1f)

    fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * clamp01(t)

    /** Smoothstep ease-in-out. */
    fun easeInOut(t: Float): Float {
        val c = clamp01(t)
        return c * c * (3f - 2f * c)
    }

    /**
     * Ping-pong index: for [steps] = 4 the sequence over increasing [step] is
     * 0 1 2 3 2 1 0 1 2 ... (both ends visited once per cycle).
     */
    fun pingPong(step: Int, steps: Int): Int {
        if (steps <= 1) return 0
        val period = 2 * (steps - 1)
        val m = ((step % period) + period) % period
        return if (m < steps) m else period - m
    }

    /** Triangle wave 0..1..0 over t in 0..1. */
    fun triangle(t: Float): Float {
        val c = clamp01(t)
        return if (c < 0.5f) c * 2f else (1f - c) * 2f
    }
}
