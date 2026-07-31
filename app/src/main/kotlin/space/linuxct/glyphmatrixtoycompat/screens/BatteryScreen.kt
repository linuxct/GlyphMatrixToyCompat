package space.linuxct.glyphmatrixtoycompat.screens

import space.linuxct.glyphmatrixtoycompat.core.GlyphScreen
import space.linuxct.glyphmatrixtoycompat.core.PrefKeys
import space.linuxct.glyphmatrixtoycompat.core.ScreenContext
import space.linuxct.glyphmatrixtoycompat.matrix.Font3x5
import space.linuxct.glyphmatrixtoycompat.matrix.MatrixCanvas
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Battery gauge: the matrix fills bottom-up to the battery level, with a
 * brighter edge row marking the level. While charging, a rising wave sweeps
 * through the fill and a pulsing bolt overlays the centre — level and
 * charging status at a glance.
 *
 * With [PrefKeys.BATTERY_SHOW_WATTS] on, a charging device shows its charge
 * power instead ("45W"). Anything else — pref off, not charging, or an
 * untrustworthy power reading — falls back to the gauge above, unchanged.
 */
class BatteryScreen : GlyphScreen {
    override val id = "battery"
    override val interactive = false

    private var ctx: ScreenContext? = null

    override fun onActivate(ctx: ScreenContext) {
        this.ctx = ctx
        ctx.scheduler.setTicker(1000) { tick() }
    }

    override fun onDeactivate() {
        ctx = null
    }

    private fun tick() {
        val c = ctx ?: return
        val charging = c.ports.battery.isCharging()
        val showWatts = c.prefs.getBoolean(PrefKeys.BATTERY_SHOW_WATTS, PrefKeys.BATTERY_SHOW_WATTS_DEF)
        c.pushFrame(
            renderFrame(
                c.size,
                c.ports.battery.levelPercent(),
                charging,
                c.ports.clock.nowMillis(),
                if (charging && showWatts) c.ports.battery.chargeWatts() else null,
            ),
        )
    }

    companion object {
        /** Brightness of the "W" unit glyph: 63 % of the digits' 4095. */
        private const val UNIT = 2600

        /**
         * Gauge levels, all expressed against the edge marker, which owns 4095.
         *
         *   [EDGE] 4095 = 100 %  the row marking the level — the thing you read
         *   [WAVE] 3300 =  81 %  the charge sweep rising through the fill
         *   [FILL] 2000 =  49 %  the body of the gauge
         *   bolt   800..4000 = 20..98 %  the pulse, see [renderFrame]
         *
         * The marker owning the peak is what fixes the pulsating fill: brightness
         * is applied by multiplying the frame, so while the frame's brightest cell
         * was the *bolt* (2900 edge / 3600 wave / a pulse swinging to 4000, back
         * when the frame was max-normalised instead) the gauge's apparent
         * brightness breathed in time with the bolt. With the marker pinned at
         * full scale the peak never moves and only the bolt pulses.
         *
         * [FILL] keeps the ratio it always had against the marker (49 %, was
         * 1400/2900). [WAVE] cannot: it used to be 1.24x the marker, which a
         * marker at full scale forbids, so it now sits just below it — still the
         * brightest thing moving inside the fill, which is its whole job.
         */
        private const val EDGE = 4095
        private const val WAVE = 3300
        private const val FILL = 2000

        private val BOLT = listOf(
            "..#",
            ".#.",
            "###",
            ".#.",
            "#..",
        )

        /** "45W". Rounded to whole watts and clamped to what the font can place. */
        fun formatWatts(watts: Float): String = "${watts.roundToInt().coerceIn(1, 999)}W"

        /**
         * Charge power: **just the figure**, vertically centred. "5W"/"45W" fit
         * one line even on 13 columns; "120W" is 15 cells wide, so on 13 the
         * digits stack above the unit. The unit glyph is dimmer than the digits
         * either way, so the number reads first.
         *
         * There is deliberately NO bolt marker here. It used to sit above the
         * figure (the Download Speed toy's marker-over-value layout), but on 13
         * columns the digits, the "W" and the bolt together left the text
         * squeezed off-centre and unreadable — and the wattage readout only ever
         * appears while charging, so a bolt says nothing the context does not.
         */
        fun renderWattage(size: Int, watts: Float): IntArray {
            val canvas = MatrixCanvas(size)
            val text = formatWatts(watts)
            val digits = text.dropLast(1)
            if (Font3x5.stringWidth(text) <= size) {
                // One line of 5-row glyphs, centred in the matrix.
                val y = (size - 5) / 2
                var x = (size - Font3x5.stringWidth(text)) / 2
                digits.forEach { x += Font3x5.draw(canvas, it, x, y, 4095) }
                Font3x5.draw(canvas, 'W', x, y, UNIT)
            } else {
                // Two lines: 5 + 1 blank + 5 = 11 rows, which `size / 2 - 5`
                // already centres at both 13 (rows 1..11) and 25 (rows 7..17).
                Font3x5.drawStringCentered(canvas, digits, size / 2 - 5, 4095)
                Font3x5.drawStringCentered(canvas, "W", size / 2 + 1, UNIT)
            }
            return canvas.copyOut()
        }

        /**
         * [chargeWatts] non-null (and [charging]) switches to the wattage
         * readout; it defaults to null so ambient background 7 keeps calling
         * the four-argument gauge and renders byte-identically.
         */
        fun renderFrame(
            size: Int,
            levelPercent: Int,
            charging: Boolean,
            nowMs: Long,
            chargeWatts: Float? = null,
        ): IntArray {
            if (charging && chargeWatts != null) return renderWattage(size, chargeWatts)
            val canvas = MatrixCanvas(size)
            val level = levelPercent.coerceIn(0, 100)
            val fillRows = (size * level / 100).coerceIn(0, size)

            for (y in size - fillRows until size) {
                val rowFromBottom = size - 1 - y
                val wave = charging && rowFromBottom == (nowMs / 150 % size).toInt()
                val v = if (wave) WAVE else FILL
                for (x in 0 until size) canvas.light(x, y, v)
            }
            if (fillRows in 1..size) {
                val y = (size - fillRows).coerceAtLeast(0)
                for (x in 0 until size) canvas.light(x, y, EDGE)
            }
            if (charging) {
                val pulse = (2400 + 1600 * sin(nowMs / 200.0)).roundToInt().coerceIn(800, 4095)
                val boltY = if (size >= 25) size / 2 - 3 else size / 2 - 2
                // Overwrite (not max-blend) so the bolt stays visible inside the
                // fill: brighter than it at pulse-high, carved dark at pulse-low.
                BOLT.forEachIndexed { by, row ->
                    row.forEachIndexed { bx, ch ->
                        if (ch == '#') canvas.set(size / 2 - 1 + bx, boltY + by, pulse)
                    }
                }
            }
            return canvas.copyOut()
        }
    }
}
