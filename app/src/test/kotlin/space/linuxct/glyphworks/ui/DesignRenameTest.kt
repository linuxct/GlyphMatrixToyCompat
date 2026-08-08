package space.linuxct.glyphworks.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import space.linuxct.glyphworks.core.design.DesignCodec

/**
 * Everything the rename dialog is allowed to save.
 *
 * [renamedName] answers "is this legal" and "what gets written" in one call on
 * purpose: the confirm button's `enabled` state and the value handed to
 * `DesignStore` come from the same expression, so the pair that is classically
 * wrong — a button that lets you press it and a save that then writes something
 * else, or nothing — cannot be wrong here without this test failing.
 *
 * The null cases are all the same sentence to a user ("there is nothing to
 * save"), which is why they are one return value rather than three error types:
 * the dialog does not explain itself, it simply stays closed.
 */
class DesignRenameTest {
    @Test
    fun `a new name is trimmed and returned`() {
        assertEquals("Quiet Comet", renamedName(current = "Slow Ember", typed = "  Quiet Comet  "))
    }

    @Test
    fun `an empty field saves nothing`() {
        assertNull(renamedName(current = "Slow Ember", typed = ""))
    }

    @Test
    fun `an over-length name is capped at the format's limit`() {
        // The field caps as the user types, but a paste arrives whole and the
        // codec would refuse to save a 65th character. Capped here as well, so
        // the rule lives with the validation rather than only in the widget.
        val typed = "x".repeat(DesignCodec.MAX_NAME_LENGTH * 2)
        val renamed = renamedName(current = "Slow Ember", typed = typed)
        assertEquals(DesignCodec.MAX_NAME_LENGTH, renamed?.length)
        assertEquals("x".repeat(DesignCodec.MAX_NAME_LENGTH), renamed)
    }

    @Test
    fun `newlines become spaces rather than breaking the name`() {
        // A paste can carry one even though the field is singleLine.
        assertEquals("Slow Ember", renamedName(current = "Quiet Comet", typed = "Slow\nEmber"))
    }
}
