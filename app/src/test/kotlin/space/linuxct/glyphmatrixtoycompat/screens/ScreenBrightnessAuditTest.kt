package space.linuxct.glyphmatrixtoycompat.screens

import org.junit.Assert.fail
import org.junit.Test
import space.linuxct.glyphmatrixtoycompat.FakeClock
import space.linuxct.glyphmatrixtoycompat.TestHarness
import space.linuxct.glyphmatrixtoycompat.core.Events
import space.linuxct.glyphmatrixtoycompat.core.GlyphScreen
import space.linuxct.glyphmatrixtoycompat.core.PrefKeys
import space.linuxct.glyphmatrixtoycompat.core.design.DEFAULT_LEVELS
import space.linuxct.glyphmatrixtoycompat.core.design.Design
import space.linuxct.glyphmatrixtoycompat.core.design.DesignFrame
import space.linuxct.glyphmatrixtoycompat.core.design.DesignKind
import space.linuxct.glyphmatrixtoycompat.core.design.DesignVariant
import space.linuxct.glyphmatrixtoycompat.core.design.KeyMode
import space.linuxct.glyphmatrixtoycompat.core.design.PokemonCodename
import space.linuxct.glyphmatrixtoycompat.matrix.MAX_BRIGHTNESS
import space.linuxct.glyphmatrixtoycompat.screens.ambient.BackgroundRenderers
import space.linuxct.glyphmatrixtoycompat.screens.ambient.ChargingRenderer

/**
 * The brightness audit, and the reason it exists: `BrightnessScale` multiplies
 * a frame by the brightness setting instead of max-normalising it, so a frame
 * whose brightest cell is BELOW 4095 now renders dimmer than the user asked
 * for — and a frame whose peak *moves* (a pulsing bolt, a rising particle) used
 * to drag the apparent brightness of everything around it.
 *
 * The house rule that falls out of that is: **the brightest element of any
 * frame is 4095, and everything else is expressed as a ratio of it.** Art
 * written that way renders identically before and after the change, because for
 * a frame that already contains a 4095 cell max-normalisation and multiplication
 * are the same function.
 *
 * This drives every screen in [ScreenRegistry.create] through a real
 * [space.linuxct.glyphmatrixtoycompat.core.ScreenManager] — the ASCII goldens
 * go through [TestHarness.context], which applies no brightness at all, which is
 * exactly why they could never catch this — over a simulated-time sweep of each
 * screen's reachable states, and holds every emitted frame to that rule.
 *
 * Two frames are not held to it, on purpose:
 *  - **All-dark frames.** A blank matrix has no peak to check, and it is blank
 *    at every brightness. Blink-off subframes are the common case.
 *  - **Screens in [DIM_BY_DESIGN].** Those get the weaker check that *some*
 *    frame of the state peaks at 4095, which still catches art that is dim all
 *    the way through.
 */
class ScreenBrightnessAuditTest {

    /**
     * States that are dim from start to finish, keyed by `id [label]`.
     *
     * Distinct from [dimByDesign] because the audit runs one state at a time: a
     * screen listed there still has to prove *some* frame reaches 4095, which a
     * state that is dim all the way through can never do. Listing one here waives
     * the peak check entirely, so the justification is the only thing standing
     * between the state and unnoticed dim art. Keep it specific.
     */
    private val fullyDimStates = mapOf(
        // A rim at 600 and nothing else. The KDoc calls it "the dim outline of
        // the vessel" and the test calls it "an empty vessel" carrying "a tiny
        // fraction of the light" a finished timer does — so the dimness is the
        // point. Max-normalisation used to stretch that lone 600 to 4095, which
        // meant an idle timer sat at FULL brightness on the matrix, contradicting
        // both. It renders as authored now.
        "timer [idle]" to "an idle vessel is a dim outline and nothing else",
    )

    /**
     * Screens where *some* frames legitimately have no full-brightness element.
     * An entry means "this art is intentionally dim" — if that is not true of a
     * screen, fix the art instead of listing it here.
     */
    private val dimByDesign = mapOf(
        // A blink is all lids (RIM, 63 %): the pupil, the only 4095 element, is
        // covered. Raising the lids to 4095 would make a shut eye the brightest
        // thing on the matrix, which is backwards.
        "eyes" to "closed blink frames are all rim, by definition",
        // The resting disc is deliberately dimmer than the breathing disc at the
        // same radius: identical values would make the two frames byte-identical,
        // ScreenManager would drop the second as a duplicate, and stopping the
        // animation would produce no visible change at all.
        "breathing" to "the idle resting disc is dimmer than the breath it rests at",
        // A nearly-empty vessel: until the mound covers one whole row the sand
        // surface is an anti-aliased partial row (4095 x coverage) and the only
        // other thing lit is a falling grain. The fill growing in from dim IS
        // the animation.
        "timer" to "the first seconds of a run are a sub-row sand surface",
    )

    @Test
    fun `every screen peaks at full scale on 13 columns`() = auditAll(13)

    @Test
    fun `every screen peaks at full scale on 25 columns`() = auditAll(25)

    private fun auditAll(size: Int) {
        val failures = mutableListOf<String>()
        for (id in ScreenRegistry.create().map { it.id }) {
            for (state in statesOf(id)) {
                // A fresh instance per state: screens hold animation state, and
                // one state's leftovers must not decide the next one's verdict.
                val screen = ScreenRegistry.create().first { it.id == id }
                failures += audit(size, screen, state)
            }
        }
        if (failures.isNotEmpty()) {
            fail("brightness audit failed on ${size}x$size:\n" + failures.joinToString("\n") { "  - $it" })
        }
    }

    /** Runs one state of one screen and returns its complaints, if any. */
    private fun audit(size: Int, screen: GlyphScreen, state: State): List<String> {
        val where = "${screen.id} [${state.label}]"
        val h = harness(size)
        state.setUp(h)
        h.prefs.putString(PrefKeys.SCREEN_ORDER, screen.id)
        h.prefs.putString(PrefKeys.CURRENT_SCREEN, screen.id)

        val manager = h.manager(listOf(screen))
        manager.startSession()

        var elapsed = 0L
        var nextPress = PRESS_EVERY_MS
        var step = 0
        while (elapsed < SWEEP_MS) {
            state.onStep(h, step)
            val before = h.clock.now
            // Screens swap tickers as they change state (Timer, Dino, Bottle) and
            // some have none at all between events (Counter), so the cadence is
            // read fresh every step and idle time is advanced by hand.
            val interval = h.scheduler.tickerInterval
            if (interval != null && interval > 0) h.scheduler.tick() else h.scheduler.advanceTime(IDLE_STEP_MS)
            elapsed += h.clock.now - before
            if (state.press && elapsed >= nextPress) {
                nextPress += PRESS_EVERY_MS
                manager.dispatchGlyphEvent(Events.CHANGE)
            }
            step++
        }
        manager.stopSession()

        val lit = h.output.filter { frame -> frame.any { it > 0 } }
        // No lit frame at all means the rig failed to drive the screen, not that
        // the screen is fine — that would make the audit silently vacuous.
        if (lit.isEmpty()) return listOf("$where: produced no lit frame (${h.output.size} frames) — the audit rig is wrong, not the art")

        val peaks = lit.map { frame -> frame.max() }
        if (where in fullyDimStates) return emptyList()
        if (screen.id in dimByDesign) {
            if (peaks.none { it == MAX_BRIGHTNESS }) {
                return listOf(
                    "$where: listed as dim by design (${dimByDesign[screen.id]}) but NOT ONE of its " +
                        "${lit.size} frames peaks at $MAX_BRIGHTNESS (brightest was ${peaks.max()}) — " +
                        "the exemption is for dim states, not dim art",
                )
            }
            return emptyList()
        }
        val dim = peaks.filter { it != MAX_BRIGHTNESS }
        if (dim.isEmpty()) return emptyList()
        return listOf(
            "$where: ${dim.size} of ${lit.size} frames peak below $MAX_BRIGHTNESS " +
                "(peaks ${dim.min()}..${dim.max()}) — raise the brightest element to $MAX_BRIGHTNESS and " +
                "express the rest as ratios of it, or justify it in dimByDesign",
        )
    }

    /**
     * Every port fed something plausible, so screens render content instead of
     * their empty states, and brightness left at 1.0 so an emitted peak IS the
     * art's peak.
     */
    private fun harness(size: Int) = TestHarness(size, FakeClock(hour = 10, min = 8, sec = 20)).apply {
        prefs.putFloat(PrefKeys.BRIGHTNESS, 1.0f)
        battery.level = 55
        battery.charging = false
        battery.watts = 45f
        speed.total = 4_000_000L
        spectrum.values = FloatArray(32) // all zeroes: silence, below the visualizer's threshold
        azimuth.value = 200f
        shake.millisSince = 0L
        tilt.x = 0.3f
        tilt.y = -0.2f
        incline.pitch = 9f
        incline.roll = -14f
        light.lux = 300f
        location.value = 41.4 to 2.2
    }

    /** One reachable configuration of a screen: prefs, port values, and animation. */
    private class State(
        val label: String,
        /** Interactive screens need presses to leave their resting state. */
        val press: Boolean = true,
        /** Called before every step, for ports whose value has to move over time. */
        val onStep: (TestHarness, Int) -> Unit = { _, _ -> },
        val setUp: (TestHarness) -> Unit = {},
    )

    private fun statesOf(id: String): List<State> = when (id) {
        // Every layer of the compositor, since each is separate art: the ten
        // backgrounds, then the charging overlay's five styles, then audio (which
        // outranks both).
        "ambient" -> buildList {
            for (bg in 0 until BackgroundRenderers.COUNT) {
                add(
                    State("background $bg") {
                        it.prefs.putInt(PrefKeys.AMBIENT_BACKGROUND, bg)
                    },
                )
            }
            for (style in 0..ChargingRenderer.STYLE_WATTS) {
                add(
                    State("charging style $style") {
                        it.battery.charging = true
                        it.prefs.putInt(PrefKeys.AMBIENT_CHARGING_STYLE, style)
                    },
                )
            }
            add(State("audio override") { it.spectrum.values = loudBands() })
        }
        "clock" -> (0..2).map { theme ->
            State("theme $theme") { it.prefs.putInt(PrefKeys.CLOCK_THEME, theme) }
        }
        "battery" -> listOf(
            State("discharging"),
            State("charging") { it.battery.charging = true },
            State("charging, watts readout") {
                it.battery.charging = true
                it.prefs.putBoolean(PrefKeys.BATTERY_SHOW_WATTS, true)
            },
        )
        "speed" -> listOf(
            State("idle link"),
            // The screen renders the DELTA between ticks, so the counter has to
            // keep moving or it reads a permanently idle link.
            State("downloading", onStep = { h, step -> h.speed.total = 4_000_000L + step * 45_000L }),
        )
        "dice" -> listOf("D4", "D6", "D8", "D12", "D20").map { type ->
            State(type) { it.prefs.putString(PrefKeys.SELECTED_DICE, type) }
        }
        "coin" -> (0..1).map { design ->
            State("design $design") { it.prefs.putInt(PrefKeys.COIN_DESIGN, design) }
        }
        "breathing" -> listOf("2", "4", "8").map { pace ->
            State("pace $pace") { it.prefs.putString(PrefKeys.BREATHING_PACE, pace) }
        }
        // The four disjoint states of the timer, set up through the prefs that
        // define them (see TimerScreen); no presses, or the sweep would walk out
        // of the state under test.
        "timer" -> listOf(
            State("idle", press = false),
            State("running", press = false) {
                it.prefs.putLong(PrefKeys.TIMER_START, it.clock.now)
            },
            State("paused", press = false) {
                it.prefs.putLong(PrefKeys.TIMER_PAUSED_ELAPSED, 20_000L)
            },
            State("done", press = false) {
                it.prefs.putLong(PrefKeys.TIMER_START, it.clock.now - 120_000L)
            },
        )
        "compass" -> listOf(
            State("bearing"),
            State("no sensor") { it.azimuth.value = null },
        )
        "level" -> listOf(
            State("tilted"),
            State("flat") {
                it.incline.pitch = 0.5f
                it.incline.roll = -0.5f
            },
            State("no sensor") {
                it.incline.pitch = null
                it.incline.roll = null
            },
        )
        "visualizer" -> buildList {
            for (theme in 0..2) {
                add(
                    State("theme $theme") {
                        it.prefs.putInt(PrefKeys.VISUALIZER_THEME, theme)
                        it.spectrum.values = loudBands()
                    },
                )
            }
            add(State("silence"))
            add(State("no microphone") { it.spectrum.values = null })
        }
        // The one screen whose art this project does not own. A user is entitled
        // to draw an all-grey design, and after the brightness rework that design
        // finally renders as grey instead of being stretched to white — so a
        // user-supplied design is not something this audit can hold to the 4095
        // rule without contradicting the feature.
        //
        // What it CAN hold to the rule is everything the app itself contributes:
        // the placeholder, and the screen's own handling of art that does peak at
        // full scale. Both design states below therefore use a palette whose top
        // entry is 4095 and put at least one cell of it in every frame, so a
        // failure here means CustomScreen dimmed art that was authored bright,
        // which is a real bug.
        "custom" -> listOf(
            State("no design") { it.design.design = null },
            State("static design") { it.design.design = auditDesign(DesignKind.STATIC, frames = 1) },
            State("dynamic design") { it.design.design = auditDesign(DesignKind.DYNAMIC, frames = 6) },
        )
        else -> listOf(State("default"))
    }

    private companion object {
        /**
         * Simulated time per state. Long enough for the slowest animation the
         * audit cares about to come round: the Breathing cycle at the longest
         * pace, a full Bottle spin, the Dino's death-and-restart, and several
         * periods of every bolt pulse and particle sweep.
         */
        const val SWEEP_MS = 30_000L

        /** How far to jump when the screen has no ticker running (Counter between presses). */
        const val IDLE_STEP_MS = 50L

        /** Glyph Touch cadence: often enough to reach every state of a game. */
        const val PRESS_EVERY_MS = 2_500L

        /** A plausible non-silent spectrum: loud lows tapering to quiet highs. */
        fun loudBands() = FloatArray(32) { i -> (0.9f - i * 0.02f).coerceAtLeast(0.15f) }

        /**
         * A design carrying art for BOTH codenames (the audit runs at 13 and 25),
         * every frame a grey field with one cell at the palette's top entry.
         *
         * The grey is the point as much as the peak: it is exactly the content
         * max-normalisation used to stretch to white, so these states also stand
         * as a regression guard that a user's greys reach the panel as greys.
         * The moving 4095 cell makes consecutive frames differ, so
         * ScreenManager's identical-frame dedup cannot swallow the animation and
         * leave the audit inspecting one frame.
         */
        fun auditDesign(kind: DesignKind, frames: Int): Design = Design(
            id = "auditdesign",
            kind = kind,
            keyMode = KeyMode.PLAY_PAUSE,
            loop = true,
            levels = DEFAULT_LEVELS,
            variants = PokemonCodename.entries.associate { codename ->
                codename.codename to DesignVariant(
                    List(frames) { i ->
                        val cells = StringBuilder("1".repeat(codename.cellCount))
                        cells.setCharAt(i % codename.cellCount, '2')
                        DesignFrame(durationMs = 120, cells = cells.toString())
                    },
                )
            },
        )
    }
}
