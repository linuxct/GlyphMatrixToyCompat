package space.linuxct.glyphmatrixtoycompat.screens.ambient

import space.linuxct.glyphmatrixtoycompat.screens.BatteryScreen
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

    /**
     * Style index of the charge-power readout, and the last entry in the
     * "Charging style" selector. Named because two files agree on it: the
     * caller uses it to decide whether reading [chargeWatts] is worth doing at
     * all, since every other style ignores the value.
     */
    const val STYLE_WATTS = 4

    /**
     * [chargeWatts] is consulted only by [STYLE_WATTS] and defaults to null, so
     * every other style renders byte-identically whether or not it is supplied.
     *
     * The readout is the same one the Battery toy draws
     * ([BatteryScreen.renderWattage]) rather than a second spelling of it, so the
     * two cannot drift apart. A null reading — the platform reporting an
     * implausible figure, which the port filters out — falls back to [numeric]:
     * having asked for a number, a percentage is a better answer than a blank
     * matrix or a sudden switch to an abstract animation.
     */
    fun render(
        size: Int,
        style: Int,
        levelPercent: Int,
        nowMs: Long,
        chargeWatts: Float? = null,
    ): IntArray = when (style) {
        1 -> particles(size, nowMs)
        2 -> batteryGlyph(size, levelPercent, nowMs)
        3 -> numeric(size, levelPercent, nowMs)
        STYLE_WATTS -> chargeWatts
            ?.let { BatteryScreen.renderWattage(size, it) }
            ?: numeric(size, levelPercent, nowMs)
        else -> fillGrid(size, levelPercent, nowMs)
    }

    /**
     * Brightness levels for the styles below, each expressed against the one
     * element of its frame that owns 4095. Brightness reaches the panel as a
     * multiplication of the finished frame, so an element that pulses or moves
     * must never be the brightest one: the frame's peak would travel with it and
     * drag everything else's apparent brightness along (which is precisely what
     * these three styles used to do).
     *
     *   [FILL_EDGE]   4095 = 100 %  fill style: the row marking the level
     *   [FILL_WAVE]   3300 =  81 %  fill style: the sweep rising through the fill
     *   [FILL_BODY]   2234 =  55 %  fill style: the fill itself (was 1200/2200)
     *   [PARTICLE_HI] 4095 = 100 %  particles: the brightest stream
     *   [PARTICLE_LO] 1500 =  37 %  particles: the dimmest stream
     *   [BASELINE]     900 =  22 %  particles: the ground line they rise from
     *   [GLYPH]       4095 = 100 %  battery glyph: its outline and cap
     *   [GLYPH_FILL]  2730 =  67 %  battery glyph: the level fill (was 1000/1500)
     */
    private const val FILL_EDGE = 4095
    private const val FILL_WAVE = 3300
    private const val FILL_BODY = 2234
    private const val PARTICLE_HI = 4095
    private const val PARTICLE_LO = 1500
    private const val BASELINE = 900
    private const val GLYPH = 4095
    private const val GLYPH_FILL = 2730

    /** 0: bottom-up fill to the battery level with a brighter wave sweeping upward. */
    private fun fillGrid(size: Int, level: Int, nowMs: Long): IntArray {
        val canvas = MatrixCanvas(size)
        val fillRows = (size * level / 100).coerceIn(0, size)
        val waveRow = (nowMs / 120 % size).toInt()
        for (y in size - fillRows until size) {
            val rowFromBottom = size - 1 - y
            val v = if (rowFromBottom == waveRow) FILL_WAVE else FILL_BODY
            for (x in 0 until size) canvas.light(x, y, v)
        }
        // Fill edge marker so the level reads even mid-wave.
        if (fillRows in 1 until size) {
            val y = size - fillRows
            for (x in 0 until size) canvas.light(x, y, FILL_EDGE)
        }
        return canvas.copyOut()
    }

    /**
     * 1: rising particle streams (index-hashed columns, clock-driven phase).
     *
     * Stream brightness is a ramp from [PARTICLE_LO] to [PARTICLE_HI] across the
     * streams rather than the hash it used to be: the hash's highest value
     * depended on how many streams the matrix had (3355 on 13, 3588 on 25), so
     * the whole animation was capped a little below full brightness, and by a
     * different amount per device. The columns are still hash-scattered, so the
     * ramp does not read as an ordered gradient.
     */
    private fun particles(size: Int, nowMs: Long): IntArray {
        val canvas = MatrixCanvas(size)
        for (x in 0 until size) canvas.light(x, size - 1, BASELINE)
        val streams = if (size >= 25) 10 else 6
        for (i in 0 until streams) {
            val x = (i * 7 + 3) % size
            val speed = 90 + (i * 37) % 70
            val y = size - 2 - ((nowMs / speed + i * 5) % (size - 1)).toInt()
            val v = PARTICLE_LO + (PARTICLE_HI - PARTICLE_LO) * i / (streams - 1)
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

    /**
     * 2: battery outline, level fill, pulsing bolt.
     *
     * The outline owns the frame's peak, not the bolt. The bolt's pulse used to
     * be the brightest thing here and swung from 600 to 4000, so the outline and
     * the fill breathed with it — at the bottom of the swing the frame was
     * normalised by 600 and the outline came out fully saturated. The pulse now
     * plays out *underneath* a fixed outline, which is what it always looked like
     * it was meant to do.
     */
    private fun batteryGlyph(size: Int, level: Int, nowMs: Long): IntArray {
        val canvas = MatrixCanvas(size)
        val pulse = (2200 + 1800 * sin(nowMs / 200.0)).roundToInt().coerceIn(600, 4095)
        if (size >= 25) {
            canvas.rect(3, 8, 17, 9, GLYPH)
            canvas.fillRect(20, 11, 2, 3, GLYPH) // cap
            val fill = 15 * level / 100
            canvas.fillRect(4, 9, fill, 7, GLYPH_FILL)
            canvas.blit(BOLT, 10, 10, pulse)
        } else {
            canvas.rect(1, 4, 10, 5, GLYPH)
            canvas.fillRect(11, 5, 1, 3, GLYPH) // cap
            val fill = 8 * level / 100
            canvas.fillRect(2, 5, fill, 3, GLYPH_FILL)
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
