package space.linuxct.glyphworks.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    @Test
    fun `newer versions are detected`() {
        assertTrue(UpdateChecker.isNewer("0.2.0", "0.1.0"))
        assertTrue(UpdateChecker.isNewer("1.0.0", "0.9.9"))
        assertTrue(UpdateChecker.isNewer("0.1.1", "0.1.0"))
        assertTrue(UpdateChecker.isNewer("0.1.10", "0.1.9"))
    }

    @Test
    fun `equal and older versions are not updates`() {
        assertFalse(UpdateChecker.isNewer("0.1.0", "0.1.0"))
        assertFalse(UpdateChecker.isNewer("0.1.0", "0.2.0"))
        assertFalse(UpdateChecker.isNewer("0.9.9", "1.0.0"))
    }

    @Test
    fun `tag prefix and missing components are tolerated`() {
        assertTrue(UpdateChecker.isNewer("v0.2.0", "0.1.0"))
        assertTrue(UpdateChecker.isNewer("V2", "1.9.3"))
        assertFalse(UpdateChecker.isNewer("v0.1", "0.1.0"))
        assertFalse(UpdateChecker.isNewer("v1.2", "1.2.1"))
    }

    @Test
    fun `non-numeric suffixes are ignored`() {
        assertTrue(UpdateChecker.isNewer("0.2.0-beta1", "0.1.0"))
        assertFalse(UpdateChecker.isNewer("0.1.0-rc2", "0.1.0"))
        assertFalse(UpdateChecker.isNewer("garbage", "0.1.0"))
    }
}
