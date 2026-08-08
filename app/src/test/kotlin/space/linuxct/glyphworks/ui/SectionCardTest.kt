package space.linuxct.glyphworks.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `SectionCard`'s corner-radius selection.
 *
 * A group of settings rows is drawn as one card PER ROW, with the outer 16 dp
 * corner only on the corners that face the page and a near-square 3 dp corner
 * on every corner that faces a sibling (measured off Nothing OS; see
 * `SectionCard`). Which of those a given card gets depends entirely on
 * [sectionItemPosition], so that is the part worth testing: nothing here checks
 * a radius value, only that the right card is told it is an end.
 *
 * The failure this guards against is an off-by-one — a two-row group whose
 * second row comes back MIDDLE would round three of its corners and leave the
 * bottom of the group square, which is invisible in code review and obvious on
 * a phone.
 */
class SectionCardTest {

    @Test
    fun `a group of one is all outer corners`() {
        assertEquals(SectionItemPosition.ONLY, sectionItemPosition(index = 0, count = 1))
    }

    @Test
    fun `a group of two is a first and a last, with no middle`() {
        assertEquals(SectionItemPosition.FIRST, sectionItemPosition(index = 0, count = 2))
        assertEquals(SectionItemPosition.LAST, sectionItemPosition(index = 1, count = 2))
    }

    @Test
    fun `only the ends of a long group are rounded`() {
        val count = 6
        val positions = (0 until count).map { sectionItemPosition(it, count) }
        assertEquals(
            listOf(
                SectionItemPosition.FIRST,
                SectionItemPosition.MIDDLE,
                SectionItemPosition.MIDDLE,
                SectionItemPosition.MIDDLE,
                SectionItemPosition.MIDDLE,
                SectionItemPosition.LAST,
            ),
            positions,
        )
    }

    /**
     * An empty group draws nothing, so this only pins down that asking is not a
     * crash — the loop in `SectionCard` never runs.
     */
    @Test
    fun `an empty group has no ends to get wrong`() {
        assertEquals(SectionItemPosition.ONLY, sectionItemPosition(index = 0, count = 0))
    }
}
