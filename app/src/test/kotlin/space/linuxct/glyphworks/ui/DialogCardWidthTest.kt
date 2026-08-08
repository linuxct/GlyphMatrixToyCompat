package space.linuxct.glyphworks.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The one number that decides how wide a dialog card is, in both of the places
 * this app draws one.
 *
 * The defect this pins down is not arithmetic, it is *divergence*: Design
 * settings is shown as a real dialog window in the app and as a plain card inside
 * the guided tour, and a wrap-content card measures completely differently in
 * those two contexts — 320 dp against 363 dp on the window this app runs on. Both
 * now ask [dialogCardWidth], so what is worth testing is that the clamp behaves
 * itself at every end: it cannot be wider than the window, it cannot be wider
 * than MD3 allows, and on a normal phone it must be a no-op against the
 * platform's own preferred width, or the windowed case would visibly change.
 *
 * Plain JVM: the function takes two [Dp]s and returns one, and touches nothing
 * else. The composable that feeds it (the platform resource, the window size) is
 * exactly the part a unit test could not run, which is why it is a separate,
 * three-line function.
 */
class DialogCardWidthTest {
    /** The 411 x 919 dp window of the phone this app is built for. */
    private val phoneWindow = 411.dp

    @Test
    fun `platform width passes through on a phone`() {
        // The whole point of the windowed case being unchanged: the card asks for
        // precisely the width `config_prefDialogWidth` was going to give it.
        assertEquals(320.dp, dialogCardWidth(preferred = 320.dp, available = phoneWindow))
    }

    @Test
    fun `a large-screen platform width is held to MD3's maximum`() {
        // sw600dp reports 580 dp, which is wider than a dialog is ever meant to
        // be; 560 is material3's own DialogMaxWidth.
        assertEquals(DIALOG_MAX_WIDTH, dialogCardWidth(preferred = 580.dp, available = 800.dp))
    }

    @Test
    fun `a mean platform width is lifted to MD3's minimum`() {
        assertEquals(DIALOG_MIN_WIDTH, dialogCardWidth(preferred = 200.dp, available = phoneWindow))
    }

    @Test
    fun `the window wins over the minimum`() {
        // A 320 dp window (small phone, split screen) cannot hold a 280 dp card
        // plus its margins. Physics beats the spec: 320 - 2 x 24.
        assertEquals(272.dp, dialogCardWidth(preferred = 320.dp, available = 320.dp))
    }
}
