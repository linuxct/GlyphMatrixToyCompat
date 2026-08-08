package space.linuxct.glyphmatrixtoycompat.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import space.linuxct.glyphmatrixtoycompat.FakePrefs
import space.linuxct.glyphmatrixtoycompat.core.ai.ChatReasoning
import space.linuxct.glyphmatrixtoycompat.core.ai.ChatRequest
import space.linuxct.glyphmatrixtoycompat.core.ai.ChatWire
import space.linuxct.glyphmatrixtoycompat.core.ai.ReasoningEffort

/**
 * The reasoning-effort preference: the token that goes on the wire, and what
 * happens to a stored value nothing recognises.
 *
 * The interesting half is the second one. Unlike the round budget next door,
 * this preference is a **string**, and two of the six levels it can hold are
 * unverified guesses about somebody else's backend — so the value on disk may
 * well be one a later build no longer offers, or one this build never did. A
 * level that cannot be mapped back to something displayable would be a settings
 * row that cannot be used to repair itself, which is the one failure that would
 * make this setting worse than not having it.
 */
class AiReasoningEffortTest {
    @Test
    fun anUnsetEffortIsTheWireDefault() {
        // Not "medium" written out again: the point is that the preference and
        // the request body cannot disagree about what this app sends when
        // nobody has chosen.
        assertEquals(ChatWire.DEFAULT_REASONING_EFFORT, FakePrefs().aiReasoningEffort().wire)
    }

    @Test
    fun everyLevelSurvivesAStoreAndAReadBack() {
        val prefs = FakePrefs()
        for (level in ReasoningEffort.entries) {
            prefs.putString(PrefKeys.AI_REASONING_EFFORT, level.wire)
            assertEquals("$level should round trip", level, prefs.aiReasoningEffort())
        }
    }

    /**
     * A token nothing recognises reads as the default rather than crashing or
     * yielding null — which is what keeps the row drawable, and therefore what
     * keeps a bad value fixable from the UI.
     */
    @Test
    fun anUnknownStoredTokenDegradesToTheDefault() {
        val prefs = FakePrefs()
        for (stored in listOf("", "   ", "insane", "MEDIUM-ish", "0", "null", "extra-high")) {
            prefs.putString(PrefKeys.AI_REASONING_EFFORT, stored)
            assertEquals(
                "stored \"$stored\" should degrade to the default",
                ReasoningEffort.DEFAULT,
                prefs.aiReasoningEffort(),
            )
        }
    }

    /**
     * The whole point of the setting: the chosen token reaches the request body
     * as `"reasoning": {"effort": …}`, and an absent one is an absent KEY rather
     * than a null — which is the property [ChatWire.json] is configured for and
     * which some backends depend on.
     */
    @Test
    fun theChosenLevelIsWhatTheRequestCarries() {
        val body = ChatWire.encodeRequest(
            ChatRequest(reasoning = ChatReasoning(ReasoningEffort.ULTRA.wire)),
        )
        assertTrue(body, body.contains("\"reasoning\""))
        assertTrue(body, body.contains("\"effort\":\"ultra\""))

        val without = ChatWire.encodeRequest(ChatRequest(reasoning = null))
        assertFalse(without, without.contains("reasoning"))
    }
}
