package space.linuxct.glyphmatrixtoycompat.screens.ambient

import space.linuxct.glyphmatrixtoycompat.core.Events
import space.linuxct.glyphmatrixtoycompat.core.GlyphScreen
import space.linuxct.glyphmatrixtoycompat.core.PrefKeys
import space.linuxct.glyphmatrixtoycompat.core.ScreenContext
import space.linuxct.glyphmatrixtoycompat.screens.VisualizerScreen

/**
 * Ambient — the "background" home screen: a 50 ms compositor that keeps a
 * low-key display running whenever a session is live (lock screen, AOD, or
 * unlocked). Layers are evaluated low to high and each active layer REPLACES
 * the whole buffer (last-writer-wins):
 *
 *   Background (7 options; night + shake gating, prefs re-read every tick)
 *   -> Charging (while status==CHARGING and level != 100)
 *   -> Audio (FFT non-silent; reverts within one tick of silence)
 *
 * An empty buffer means the matrix is dark. Glyph events are ignored — this
 * screen is passive by design.
 */
class AmbientScreen : GlyphScreen {
    override val id = "ambient"
    override val interactive = false

    private var ctx: ScreenContext? = null
    private val backgrounds = HashMap<Int, AmbientBackground>()

    override fun onActivate(ctx: ScreenContext) {
        this.ctx = ctx
        ctx.scheduler.setTicker(50) { tick() }
    }

    override fun onDeactivate() {
        ctx = null
        backgrounds.clear()
    }

    override fun onEvent(event: String) {
        // Passive: CHANGE/AOD have no effect here.
        if (event == Events.CHANGE || event == Events.AOD) return
    }

    private fun tick() {
        val c = ctx ?: return
        c.pushFrame(composite(c))
    }

    /** One compositor pass; also used directly by tests. */
    fun composite(c: ScreenContext): IntArray {
        val nowMs = c.ports.clock.nowMillis()
        var frame: IntArray? = null

        if (c.prefs.getBoolean(PrefKeys.AMBIENT_USE_BACKGROUND, PrefKeys.AMBIENT_USE_BACKGROUND_DEF) &&
            backgroundVisible(c)
        ) {
            val idx = c.prefs.getInt(PrefKeys.AMBIENT_BACKGROUND, PrefKeys.AMBIENT_BACKGROUND_DEF)
                .coerceIn(0, BackgroundRenderers.COUNT - 1)
            val renderer = backgrounds.getOrPut(idx) { BackgroundRenderers.create(idx) }
            frame = renderer.render(c, nowMs)
        }

        if (c.prefs.getBoolean(PrefKeys.AMBIENT_USE_CHARGING, PrefKeys.AMBIENT_USE_CHARGING_DEF) &&
            c.ports.battery.isCharging() &&
            c.ports.battery.levelPercent() != 100
        ) {
            val style = c.prefs.getInt(
                PrefKeys.AMBIENT_CHARGING_STYLE,
                PrefKeys.AMBIENT_CHARGING_STYLE_DEF,
            )
            frame = ChargingRenderer.render(
                c.size,
                style,
                c.ports.battery.levelPercent(),
                nowMs,
                // Read the charge power ONLY for the style that draws it. The
                // port hits the platform battery service, and this composite runs
                // every tick — the other four styles would pay for a figure they
                // discard. The Battery toy's own "show watts" preference is
                // deliberately NOT consulted: charge power is a choice in this
                // selector now, so the two settings stay independent.
                if (style == ChargingRenderer.STYLE_WATTS) c.ports.battery.chargeWatts() else null,
            )
        }

        val bands = c.ports.spectrum.bands(c.size)
        if (bands != null && (bands.maxOrNull() ?: 0f) > VisualizerScreen.SILENCE_THRESHOLD) {
            frame = VisualizerScreen.renderFrame(
                c.size,
                bands,
                c.prefs.getInt(PrefKeys.VISUALIZER_THEME, PrefKeys.VISUALIZER_THEME_DEF),
            )
        }

        return frame ?: IntArray(c.size * c.size)
    }

    private fun backgroundVisible(c: ScreenContext): Boolean {
        if (NightWindow.isNight(c.ports.clock.hourOfDay()) &&
            !c.prefs.getBoolean(PrefKeys.AMBIENT_NIGHT_VISIBLE, PrefKeys.AMBIENT_NIGHT_VISIBLE_DEF)
        ) {
            return false
        }
        if (c.prefs.getBoolean(PrefKeys.AMBIENT_SHAKE_ACTIVATE, PrefKeys.AMBIENT_SHAKE_ACTIVATE_DEF) &&
            c.ports.shake.millisSinceLastShake() > SHAKE_WINDOW_MS
        ) {
            return false
        }
        return true
    }

    companion object {
        const val SHAKE_WINDOW_MS = 30_000L
    }
}
