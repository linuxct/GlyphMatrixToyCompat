package space.linuxct.glyphmatrixtoycompat.matrix

import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.cos
import kotlin.math.sqrt
import kotlin.math.atan2
import kotlin.math.abs

/** Maximum per-cell brightness of the Glyph Matrix (12-bit, white only). */
const val MAX_BRIGHTNESS = 4095

/**
 * A square monochrome drawing surface for the Glyph Matrix. Cells hold a
 * brightness 0..4095 at index y * size + x. All drawing primitives are
 * additive-max ("light"): overlapping shapes never darken each other.
 *
 * Angle convention throughout: 0 deg = up (12 o'clock), increasing clockwise.
 */
class MatrixCanvas(val size: Int) {
    val buf = IntArray(size * size)

    fun clear() {
        buf.fill(0)
    }

    private fun inBounds(x: Int, y: Int) = x in 0 until size && y in 0 until size

    fun get(x: Int, y: Int): Int = if (inBounds(x, y)) buf[y * size + x] else 0

    /** Overwrites a cell (bounds-checked, clamped). */
    fun set(x: Int, y: Int, v: Int) {
        if (inBounds(x, y)) buf[y * size + x] = v.coerceIn(0, MAX_BRIGHTNESS)
    }

    /** Max-blends a cell: keeps the brighter of current and new value. */
    fun light(x: Int, y: Int, v: Int) {
        if (!inBounds(x, y)) return
        val i = y * size + x
        val c = v.coerceIn(0, MAX_BRIGHTNESS)
        if (c > buf[i]) buf[i] = c
    }

    fun fill(v: Int) {
        buf.fill(v.coerceIn(0, MAX_BRIGHTNESS))
    }

    fun fillRect(x: Int, y: Int, w: Int, h: Int, v: Int) {
        for (yy in y until y + h) for (xx in x until x + w) light(xx, yy, v)
    }

    fun rect(x: Int, y: Int, w: Int, h: Int, v: Int) {
        for (xx in x until x + w) {
            light(xx, y, v); light(xx, y + h - 1, v)
        }
        for (yy in y until y + h) {
            light(x, yy, v); light(x + w - 1, yy, v)
        }
    }

    /** Bresenham line, endpoints inclusive. */
    fun line(x0: Int, y0: Int, x1: Int, y1: Int, v: Int) {
        var x = x0
        var y = y0
        val dx = abs(x1 - x0)
        val dy = -abs(y1 - y0)
        val sx = if (x0 < x1) 1 else -1
        val sy = if (y0 < y1) 1 else -1
        var err = dx + dy
        while (true) {
            light(x, y, v)
            if (x == x1 && y == y1) break
            val e2 = 2 * err
            if (e2 >= dy) {
                err += dy; x += sx
            }
            if (e2 <= dx) {
                err += dx; y += sy
            }
        }
    }

    /** Line from (cx,cy) outward at [angleDeg] (0 = up, clockwise) of [length] cells. */
    fun ray(cx: Int, cy: Int, angleDeg: Float, length: Float, v: Int) {
        val rad = Math.toRadians(angleDeg.toDouble())
        val ex = cx + sin(rad) * length
        val ey = cy - cos(rad) * length
        line(cx, cy, ex.roundToInt(), ey.roundToInt(), v)
    }

    /** Single cell at polar position from (cx,cy). */
    fun polar(cx: Int, cy: Int, angleDeg: Float, radius: Float, v: Int) {
        val rad = Math.toRadians(angleDeg.toDouble())
        light((cx + sin(rad) * radius).roundToInt(), (cy - cos(rad) * radius).roundToInt(), v)
    }

    /** Midpoint circle outline. */
    fun circle(cx: Int, cy: Int, r: Int, v: Int) {
        var x = r
        var y = 0
        var err = 1 - r
        while (x >= y) {
            light(cx + x, cy + y, v); light(cx - x, cy + y, v)
            light(cx + x, cy - y, v); light(cx - x, cy - y, v)
            light(cx + y, cy + x, v); light(cx - y, cy + x, v)
            light(cx + y, cy - x, v); light(cx - y, cy - x, v)
            y++
            if (err < 0) {
                err += 2 * y + 1
            } else {
                x--; err += 2 * (y - x) + 1
            }
        }
    }

    fun fillCircle(cx: Int, cy: Int, r: Int, v: Int) {
        for (yy in -r..r) for (xx in -r..r) {
            if (xx * xx + yy * yy <= r * r) light(cx + xx, cy + yy, v)
        }
    }

    /**
     * Anti-aliased filled disc with float center/radius: cells within r-0.5 get
     * full brightness, the 1-cell rim fades linearly to zero. Gives smooth
     * growth for breathing-style animations.
     */
    fun discSoft(cx: Float, cy: Float, r: Float, v: Int) {
        val x0 = (cx - r - 1).toInt().coerceAtLeast(0)
        val x1 = (cx + r + 1).roundToInt().coerceAtMost(size - 1)
        val y0 = (cy - r - 1).toInt().coerceAtLeast(0)
        val y1 = (cy + r + 1).roundToInt().coerceAtMost(size - 1)
        for (y in y0..y1) for (x in x0..x1) {
            val d = sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy))
            val f = when {
                d <= r - 0.5f -> 1f
                d <= r + 0.5f -> (r + 0.5f - d)
                else -> 0f
            }
            if (f > 0f) light(x, y, (v * f).roundToInt())
        }
    }

    /** Solid ring: cells whose distance from center is within [rInner]..[rOuter]. */
    fun ring(cx: Float, cy: Float, rInner: Float, rOuter: Float, v: Int) {
        arcRing(cx, cy, rInner, rOuter, 0f, 360f, v)
    }

    /**
     * Ring segment from [fromDeg] sweeping [sweepDeg] clockwise (0 deg = up).
     * Used for circular progress (charging, battery themes).
     */
    fun arcRing(cx: Float, cy: Float, rInner: Float, rOuter: Float, fromDeg: Float, sweepDeg: Float, v: Int) {
        if (sweepDeg <= 0f) return
        val x0 = (cx - rOuter - 1).toInt().coerceAtLeast(0)
        val x1 = (cx + rOuter + 1).roundToInt().coerceAtMost(size - 1)
        val y0 = (cy - rOuter - 1).toInt().coerceAtLeast(0)
        val y1 = (cy + rOuter + 1).roundToInt().coerceAtMost(size - 1)
        for (y in y0..y1) for (x in x0..x1) {
            val dx = x - cx
            val dy = y - cy
            val d = sqrt(dx * dx + dy * dy)
            if (d < rInner - 0.001f || d > rOuter + 0.001f) continue
            var ang = Math.toDegrees(atan2(dx.toDouble(), (-dy).toDouble())).toFloat()
            if (ang < 0f) ang += 360f
            var rel = ang - fromDeg
            if (rel < 0f) rel += 360f
            if (rel <= sweepDeg) light(x, y, v)
        }
    }

    /**
     * Blits a row-string sprite: '#' lights at [v], any other char is skipped.
     * Rows may have different lengths.
     */
    fun blit(rows: List<String>, dx: Int, dy: Int, v: Int) {
        rows.forEachIndexed { y, row ->
            row.forEachIndexed { x, c ->
                if (c == '#') light(dx + x, dy + y, v)
            }
        }
    }

    fun copyOut(): IntArray = buf.copyOf()
}
