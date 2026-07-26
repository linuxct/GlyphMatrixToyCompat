package space.linuxct.glyphmatrixtoycompat.key

import org.junit.Assert.assertEquals
import org.junit.Test

class ClickCounterTest {

    @Test
    fun `presses inside the window accumulate`() {
        val c = ClickCounter(400)
        assertEquals(1, c.onPress(1000))
        assertEquals(2, c.onPress(1300))
        assertEquals(3, c.onPress(1650))
        assertEquals(3, c.finish())
    }

    @Test
    fun `a gap larger than the window starts a new burst`() {
        val c = ClickCounter(400)
        c.onPress(1000)
        assertEquals(1, c.onPress(1500)) // 500 ms gap: new burst
    }

    @Test
    fun `finish resets the burst`() {
        val c = ClickCounter(400)
        c.onPress(1000)
        c.onPress(1200)
        assertEquals(2, c.finish())
        assertEquals(0, c.finish())
        assertEquals(1, c.onPress(1300))
    }

    @Test
    fun `four or more clicks are reported as counted`() {
        val c = ClickCounter(400)
        repeat(5) { c.onPress(1000L + it * 100) }
        assertEquals(5, c.finish()) // router ignores counts above 3
    }
}
