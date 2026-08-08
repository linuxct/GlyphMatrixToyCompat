package space.linuxct.glyphworks.core

import space.linuxct.glyphworks.core.ai.AiPrefKeys
import space.linuxct.glyphworks.core.ai.aiMaxRounds
import space.linuxct.glyphworks.core.ai.aiReasoningEffort
import org.junit.Assert.assertEquals
import org.junit.Test
import space.linuxct.glyphworks.FakePrefs
import space.linuxct.glyphworks.core.ai.GlyphAiOrchestrator

/**
 * The assistant's tool-round budget, and the clamp that makes it safe to expose.
 *
 * The setting exists because eight rounds is the wrong number for a big
 * animation — but the value it controls is the only bound on a loop that issues
 * one network request per iteration, so [aiMaxRounds] is the last line of
 * defence and is worth pinning down. Everything here is about what happens when
 * the stored integer is *not* one the UI would have written.
 */
class AiMaxRoundsTest {
    @Test
    fun anUnsetBudgetIsTheOrchestratorsOwnDefault() {
        // Not "8" written out again: the point is that the pref and the
        // orchestrator cannot disagree about what the built-in budget is.
        assertEquals(
            GlyphAiOrchestrator.DEFAULT_MAX_ROUNDS,
            FakePrefs().aiMaxRounds(),
        )
    }

    @Test
    fun aStoredBudgetIsUsedAsWritten() {
        val prefs = FakePrefs()
        prefs.putInt(AiPrefKeys.MAX_ROUNDS, 24)
        assertEquals(24, prefs.aiMaxRounds())
    }

    /**
     * Zero and negatives are the dangerous direction, not the harmless one: a
     * turn with no rounds cannot call a tool, so it would fail on its first
     * iteration having done nothing, and the assistant would look broken rather
     * than misconfigured.
     */
    @Test
    fun aBudgetBelowTheFloorIsRaisedToIt() {
        val prefs = FakePrefs()
        for (stored in listOf(Int.MIN_VALUE, -1, 0, 1, AiPrefKeys.MAX_ROUNDS_MIN - 1)) {
            prefs.putInt(AiPrefKeys.MAX_ROUNDS, stored)
            assertEquals(
                "stored $stored should clamp up to the floor",
                AiPrefKeys.MAX_ROUNDS_MIN,
                prefs.aiMaxRounds(),
            )
        }
    }
}
