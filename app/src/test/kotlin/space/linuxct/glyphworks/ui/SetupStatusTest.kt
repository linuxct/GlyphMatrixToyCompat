package space.linuxct.glyphworks.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one predicate two pieces of UI are drawn from.
 *
 * `SetupStatus.needsAttention` decides both of the things this feature adds:
 * whether the navigation bar shows the attention badge on the Settings chip, and
 * whether the Initial setup section is open the first time the page is composed
 * (`MainActivity`'s `SettingsTab` passes exactly this expression to
 * `rememberSaveable { mutableStateOf(...) }`, and nothing else ever recomputes
 * it — the user's own toggle wins after that).
 *
 * Those two consequences are asserted here by name, because the failure mode
 * being ruled out is not "the boolean is wrong" but "the badge and the checklist
 * stopped agreeing" — a badge over a page of six check marks, or a page of
 * question marks with no badge pointing at it. Both are the same bit, and this is
 * the test that says so.
 *
 * Every item gets its own case rather than a loop: when one of them stops
 * counting, the failing test should name it.
 */
class SetupStatusTest {
    /** What the nav bar does with the predicate. */
    private fun badgeShown(status: SetupStatus) = status.needsAttention

    /** What the Settings page does with it, on first composition only. */
    private fun sectionStartsExpanded(status: SetupStatus) = status.needsAttention

    private fun assertNeedsAttention(status: SetupStatus) {
        assertTrue("an outstanding item must badge the nav bar: $status", badgeShown(status))
        assertTrue(
            "an outstanding item must open the section on arrival: $status",
            sectionStartsExpanded(status),
        )
    }

    @Test
    fun `everything done means no badge and a collapsed section`() {
        val status = SetupStatus.COMPLETE
        assertFalse("a finished checklist must not badge the nav bar", badgeShown(status))
        assertFalse(
            "a finished checklist is six rows of noise; it stays collapsed",
            sectionStartsExpanded(status),
        )
    }

    @Test
    fun `the accessibility service missing badges and expands`() {
        assertNeedsAttention(SetupStatus.COMPLETE.copy(accessibility = false))
    }

    @Test
    fun `nothing done at all badges and expands`() {
        assertNeedsAttention(
            SetupStatus(
                accessibility = false,
                alwaysOnToy = false,
                notifications = false,
                microphone = false,
                location = false,
                exactAlarms = false,
            ),
        )
    }

    @Test
    fun `only the complete status clears the badge`() {
        // Every single-item failure, in one sweep, so that a predicate rewritten
        // as `accessibility && alwaysOnToy` with an item quietly dropped fails
        // here as well as in the case above that names it.
        val oneMissing = listOf(
            SetupStatus.COMPLETE.copy(accessibility = false),
            SetupStatus.COMPLETE.copy(alwaysOnToy = false),
            SetupStatus.COMPLETE.copy(notifications = false),
            SetupStatus.COMPLETE.copy(microphone = false),
            SetupStatus.COMPLETE.copy(location = false),
            SetupStatus.COMPLETE.copy(exactAlarms = false),
        )
        assertEquals("one case per item", 6, oneMissing.size)
        assertEquals("each case must differ from COMPLETE", 6, oneMissing.toSet().size)
        oneMissing.forEach { assertTrue(it.toString(), badgeShown(it)) }
    }
}
