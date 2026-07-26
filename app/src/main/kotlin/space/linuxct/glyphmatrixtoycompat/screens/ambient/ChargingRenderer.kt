package space.linuxct.glyphmatrixtoycompat.screens.ambient

import space.linuxct.glyphmatrixtoycompat.matrix.Font3x5
import space.linuxct.glyphmatrixtoycompat.matrix.MatrixCanvas
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Charging layer, drawn every tick while status==CHARGING and level != 100
 * (a persistent animation, not a plug-in one-shot). Styles
 * (ambientChargingStyle): 0 fill grid with rising wave, 1 rising particles,
 * 2 battery glyph with pulsing bolt, 3 numeric with bolt. All animation
 * derives from the clock (deterministic in tests).
 */
object ChargingRenderer {

    fun render(size: Int, style: Int, levelPercent: Int, nowMs: Long): IntArray = when (style) {
        1 -> particles(size, nowMs)
        2 -> batteryGlyph(size, levelPercent, nowMs)
        3 -> numeric(size, levelPercent, nowMs)
        else -> fillGrid(size, levelPercent, nowMs)
    }

    /** 0: bottom-up fill to the battery level with a brighter wave sweeping upward. */
    private fun fillGrid(size: Int, level: Int, nowMs: Long): IntArray {
        val canvas = MatrixCanvas(size)
        val fillRows = (size * level / 100).coerceIn(0, size)
        val waveRow = (nowMs / 120 % size).toInt()
        for (y in size - fillRows until size) {
            val rowFromBottom = size - 1 - y
            val v = if (rowFromBottom == waveRow) 3600 else 1200
            for (x in 0 until size) canvas.light(x, y, v)
        }
        // Fill edge marker so the level reads even mid-wave.
        if (fillRows in 1 until size) {
            val y = size - fillRows
            for (x in 0 until size) canvas.light(x, y, 2200)
        }
        return canvas.copyOut()
    }

    /** 1: rising particle streams (index-hashed columns, clock-driven phase). */
    private fun particles(size: Int, nowMs: Long): IntArray {
        val canvas = MatrixCanvas(size)
        for (x in 0 until size) canvas.light(x, size - 1, 900) // baseline
        val streams = if (size >= 25) 10 else 6
        for (i in 0 until streams) {
            val x = (i * 7 + 3) % size
            val speed = 90 + (i * 37) % 70
            val y = size - 2 - ((nowMs / speed + i * 5) % (size - 1)).toInt()
            val v = 1500 + (i * 811) % 2200
            canvas.light(x, y, v)
            canvas.light(x, y + 1, v / 3)
        }
        return canvas.copyOut()
    }

    private val BOLT = listOf(
        "..#",
        ".#.",
        "###",
        ".#.",
        "#..",
    )

    /** 2: battery outline, level fill, pulsing bolt. */
    private fun batteryGlyph(size: Int, level: Int, nowMs: Long): IntArray {
        val canvas = MatrixCanvas(size)
        val pulse = (2200 + 1800 * sin(nowMs / 200.0)).roundToInt().coerceIn(600, 4095)
        if (size >= 25) {
            canvas.rect(3, 8, 17, 9, 1500)
            canvas.fillRect(20, 11, 2, 3, 1500) // cap
            val fill = 15 * level / 100
            canvas.fillRect(4, 9, fill, 7, 1000)
            canvas.blit(BOLT, 10, 10, pulse)
        } else {
            canvas.rect(1, 4, 10, 5, 1500)
            canvas.fillRect(11, 5, 1, 3, 1500) // cap
            val fill = 8 * level / 100
            canvas.fillRect(2, 5, fill, 3, 1000)
            canvas.blit(BOLT, 5, 4, pulse)
        }
        return canvas.copyOut()
    }

    /** 3: numeric percentage with a pulsing bolt beneath. */
    private fun numeric(size: Int, level: Int, nowMs: Long): IntArray {
        val canvas = MatrixCanvas(size)
        val text = if (level >= 100) "100" else "$level%"
        val textY = if (size >= 25) 7 else 2
        Font3x5.drawStringCentered(canvas, text, textY, 4095)
        val pulse = (2200 + 1800 * sin(nowMs / 200.0)).roundToInt().coerceIn(600, 4095)
        val boltY = if (size >= 25) 15 else 8
        canvas.blit(BOLT, size / 2 - 1, boltY, pulse)
        return canvas.copyOut()
    }
}
