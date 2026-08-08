package space.linuxct.glyphworks.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Once, ever" — the whole behaviour of the Create tab's tutorial offer.
 *
 * It is a predicate over three facts, and each of them is a way the offer could
 * be wrong in a manner nobody would notice until it had already annoyed
 * somebody:
 *
 * 1. **Composed is not opened.** The pager keeps a page composed either side of
 *    the viewport, so the offer must wait for an actual arrival on the tab
 *    (`visited`) rather than for its own first composition.
 * 2. **Asked is asked.** The preference is written when the dialog goes UP, so a
 *    "no", a swipe away and a process death mid-dialog are the same thing here —
 *    all three leave `prompted` true and none of them may bring it back.
 * 3. **Never inside the tour.** The guided demo drives the real Create tab and
 *    swallows touches; an offer put up in there would be both absurd and
 *    undismissable.
 */
class CreateTourOfferTest {

    @Test
    fun `offered on the first arrival`() {
        assertTrue(shouldOfferCreateTour(visited = true, prompted = false, inDemo = false))
    }

    @Test
    fun `not offered before the tab is opened`() {
        assertFalse(shouldOfferCreateTour(visited = false, prompted = false, inDemo = false))
    }

    @Test
    fun `never offered twice`() {
        assertFalse(shouldOfferCreateTour(visited = true, prompted = true, inDemo = false))
    }

    @Test
    fun `never offered inside the guided demo`() {
        assertFalse(shouldOfferCreateTour(visited = true, prompted = false, inDemo = true))
        // Belt and braces: being in the demo does not become an exemption just
        // because the user has never been asked.
        assertFalse(shouldOfferCreateTour(visited = true, prompted = true, inDemo = true))
    }
}
