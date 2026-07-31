package space.linuxct.glyphmatrixtoycompat.screens

import space.linuxct.glyphmatrixtoycompat.core.Events
import space.linuxct.glyphmatrixtoycompat.core.GlyphScreen
import space.linuxct.glyphmatrixtoycompat.core.PrefKeys
import space.linuxct.glyphmatrixtoycompat.core.ScreenContext
import space.linuxct.glyphmatrixtoycompat.matrix.MAX_BRIGHTNESS
import space.linuxct.glyphmatrixtoycompat.matrix.MatrixCanvas
import kotlin.math.roundToInt

/**
 * Music Visualizer: 50 ms ticker over the shared FFT engine.
 * Themes (visualizerTheme): 0 bottom-up bars, 1 centre-mirrored bars,
 * 2 palette (energy-driven pulsing rings). Silence (max band <= 0.1) shows a
 * static idle pattern; a null spectrum (no mic permission / engine blocked)
 * shows a static "permission" pattern. EVENT_AOD only records the AOD hint
 * pref.
 */
class VisualizerScreen : GlyphScreen {
    override val id = "visualizer"
    override val interactive = false

    private var ctx: ScreenContext? = null

    override fun onActivate(ctx: ScreenContext) {
        this.ctx = ctx
        ctx.scheduler.setTicker(50) { tick() }
    }

    override fun onDeactivate() {
        ctx = null
    }

    override fun onEvent(event: String) {
        if (event == Events.AOD) {
            ctx?.prefs?.putBoolean(PrefKeys.VISUALIZER_AOD_HINT, true)
        }
    }

    private fun tick() {
        val c = ctx ?: return
        val bands = c.ports.spectrum.bands(c.size)
        val theme = c.prefs.getInt(PrefKeys.VISUALIZER_THEME, PrefKeys.VISUALIZER_THEME_DEF)
        c.pushFrame(renderFrame(c.size, bands, theme))
    }

    companion object {
        const val SILENCE_THRESHOLD = 0.1f

        fun renderFrame(size: Int, bands: FloatArray?, theme: Int): IntArray {
            if (bands == null) return renderPermissionPattern(size)
            if ((bands.maxOrNull() ?: 0f) <= SILENCE_THRESHOLD) return renderIdlePattern(size)
            val canvas = MatrixCanvas(size)
            when (theme) {
                1 -> renderMirrored(canvas, bands)
                2 -> renderPalette(canvas, bands)
                else -> renderBars(canvas, bands)
            }
            return canvas.copyOut()
        }

        /** Faint brightness of the always-on 1-cell noise floor while audio plays. */
        private const val FLOOR_BRIGHTNESS = 1300

        private fun renderBars(canvas: MatrixCanvas, bands: FloatArray) {
            val size = canvas.size
            for (x in 0 until size) {
                // Noise floor: while audio is active every column shows at least
                // one faint cell — bars grow out of the floor, never from nothing.
                val h = (bands[x % bands.size] * size).roundToInt().coerceIn(1, size)
                if (h == 1) {
                    canvas.light(x, size - 1, FLOOR_BRIGHTNESS)
                    continue
                }
                for (i in 0 until h) {
                    val y = size - 1 - i
                    val v = if (i == h - 1) 4095 else 1400
                    canvas.light(x, y, v)
                }
            }
        }

        private fun renderMirrored(canvas: MatrixCanvas, bands: FloatArray) {
            val size = canvas.size
            val mid = size / 2
            for (x in 0 until size) {
                val h = (bands[x % bands.size] * (size / 2f)).roundToInt().coerceIn(1, mid)
                if (h == 1) {
                    canvas.light(x, mid, FLOOR_BRIGHTNESS)
                    continue
                }
                for (i in 0 until h) {
                    val v = if (i == h - 1) 4095 else 1400
                    canvas.light(x, mid - i, v)
                    canvas.light(x, mid + i, v)
                }
                canvas.light(x, mid, 2200)
            }
        }

        /**
         * The disc is the frame's fixed full-brightness element and the ring's
         * brightness is the data (the energy in the top half of the spectrum).
         * The disc used to be 61 % of a full-scale ring, which meant that with
         * quiet highs the frame's peak was the ring — so the *disc* dimmed and
         * brightened with the treble too, on top of the ring doing it on purpose.
         */
        private fun renderPalette(canvas: MatrixCanvas, bands: FloatArray) {
            val size = canvas.size
            val center = (size - 1) / 2f
            val low = bands.take(bands.size / 4).average().toFloat()
            val high = bands.drop(bands.size / 2).average().toFloat()
            val rOuter = 1f + low * (size / 2f - 1f)
            canvas.discSoft(center, center, rOuter, MAX_BRIGHTNESS)
            canvas.ring(
                center, center, rOuter + 0.5f, rOuter + 1.2f,
                (MAX_BRIGHTNESS * high).roundToInt().coerceAtLeast(600),
            )
        }

        /**
         * Audio is active but silent. The three dots own full brightness and the
         * baseline is 58 % of them, the ratio the pair has always rendered at —
         * absolute levels matter now that brightness is a multiplication, and at
         * the old 1200/700 the whole idle pattern would have sat at 29 %.
         */
        fun renderIdlePattern(size: Int): IntArray {
            val canvas = MatrixCanvas(size)
            val mid = size / 2
            for (x in 1 until size - 1) canvas.light(x, size - 2, IDLE_BASELINE)
            canvas.set(mid - 3, size - 3, IDLE_DOT)
            canvas.set(mid, size - 4, IDLE_DOT)
            canvas.set(mid + 3, size - 3, IDLE_DOT)
            return canvas.copyOut()
        }

        /** As [renderIdlePattern]: the same art, lifted so the slash owns 4095. */
        fun renderPermissionPattern(size: Int): IntArray {
            val canvas = MatrixCanvas(size)
            val cx = size / 2
            val topY = size / 4
            // Mic glyph: capsule + stand, with a slash across.
            canvas.fillRect(cx - 1, topY, 3, 4, MIC_BODY)
            canvas.line(cx - 2, topY + 4, cx + 2, topY + 4, MIC_STAND)
            canvas.line(cx, topY + 5, cx, topY + 6, MIC_STAND)
            canvas.line(cx - 2, topY + 6, cx + 2, topY + 6, MIC_STAND)
            canvas.line(size - 3, 2, 2, size - 3, MIC_SLASH)
            return canvas.copyOut()
        }

        private const val IDLE_DOT = MAX_BRIGHTNESS
        private const val IDLE_BASELINE = 2389

        private const val MIC_SLASH = MAX_BRIGHTNESS
        private const val MIC_BODY = 2457
        private const val MIC_STAND = 1228
    }
}
