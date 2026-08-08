package space.linuxct.glyphmatrixtoycompat.screens

import org.junit.Assert.assertTrue
import org.junit.Test
import space.linuxct.glyphmatrixtoycompat.GoldenAscii

class VisualizerScreenTest {
    private val ramp13 = FloatArray(13) { it / 12f }

    @Test
    fun `themes render`() {
        GoldenAscii.check("visualizer_13_bars", VisualizerScreen.renderFrame(13, ramp13, 0), 13)
        GoldenAscii.check("visualizer_13_mirror", VisualizerScreen.renderFrame(13, ramp13, 1), 13)
        GoldenAscii.check("visualizer_13_palette", VisualizerScreen.renderFrame(13, ramp13, 2), 13)
        val ramp25 = FloatArray(25) { it / 24f }
        GoldenAscii.check("visualizer_25_bars", VisualizerScreen.renderFrame(25, ramp25, 0), 25)
    }

    @Test
    fun `active audio shows the noise floor on every column`() {
        // One band above the silence threshold, everything else at zero:
        // every column must still show at least the faint 1-cell floor —
        // bars never start from nothing while audio is playing.
        val quiet = FloatArray(13) { if (it == 6) 0.3f else 0f }
        val frame = VisualizerScreen.renderFrame(13, quiet, 0)
        for (x in 0 until 13) {
            assertTrue("column $x missing floor", frame[12 * 13 + x] > 0)
        }
        GoldenAscii.check("visualizer_13_floor", frame, 13)
    }

    @Test
    fun `silence shows idle pattern`() {
        val silent = FloatArray(13) { 0.05f }
        assertTrue(
            VisualizerScreen.renderFrame(13, silent, 0)
                .contentEquals(VisualizerScreen.renderIdlePattern(13)),
        )
        GoldenAscii.check("visualizer_13_idle", VisualizerScreen.renderIdlePattern(13), 13)
    }
}
