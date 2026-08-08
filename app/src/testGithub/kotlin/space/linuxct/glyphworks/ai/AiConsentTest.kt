package space.linuxct.glyphworks.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The order of the assistant's three doors, and the fact that acceptance sticks.
 *
 * This is the whole of the disclosure guarantee that can be proven off-device.
 * `AiConsentStore` itself is `SharedPreferences` and needs a `Context`, so the
 * storage is behind [AiConsentStorage] and what is tested here is the rule that
 * uses it — which is the part with an argument behind it rather than an API call.
 *
 * The rule that matters: **the disclosure comes before the sign-in.** Sending
 * somebody to OpenAI's login page is itself an act that tells OpenAI something
 * about them, so it may not happen until they have read what this app is going to
 * send and agreed to it.
 */
class AiConsentTest {
    /** [AiConsentStorage] with the `SharedPreferences` taken out. */
    private class FakeConsent(private var value: Boolean = false) : AiConsentStorage {
        var writes = 0
            private set

        override val accepted: Boolean get() = value

        override fun accept() {
            value = true
            writes++
        }
    }

    @Test
    fun `nothing is disclosed by default, so the first door is the disclosure`() {
        val consent = FakeConsent()
        assertFalse(consent.accepted)
        assertEquals(AiGate.CONSENT, aiGate(consented = consent.accepted, signedIn = false))
    }

    /**
     * The case an existing install is actually in: this build shipped sign-in
     * before it shipped the disclosure, so there are tokens out there with no
     * acceptance behind them. They must still be shown the disclosure.
     */
    @Test
    fun `a token from an older build does not skip the disclosure`() {
        assertEquals(AiGate.CONSENT, aiGate(consented = false, signedIn = true))
    }

    @Test
    fun `accepting moves on to the sign-in, and signing in to the chat`() {
        val consent = FakeConsent()
        consent.accept()
        assertEquals(AiGate.SIGN_IN, aiGate(consented = consent.accepted, signedIn = false))
        assertEquals(AiGate.CHAT, aiGate(consented = consent.accepted, signedIn = true))
    }

    @Test
    fun `acceptance persists across readers`() {
        val consent = FakeConsent()
        consent.accept()
        // The store is read afresh every time the button is tapped; nothing is
        // cached in the gate, so a second read must answer the same way.
        repeat(3) { assertTrue(consent.accepted) }
        assertEquals(AiGate.SIGN_IN, aiGate(consent.accepted, signedIn = false))
    }

    /**
     * Declining writes nothing at all — there is no stored "no". The feature is
     * inert because the gate keeps returning [AiGate.CONSENT], not because a
     * second flag says so; see `AiConsentStore` for why a stored refusal would be
     * a state to migrate and a state to disagree with "never asked".
     */
    @Test
    fun `declining leaves the feature inert and stores nothing`() {
        val consent = FakeConsent()
        // Declining is dismissing the dialog: no call reaches the storage.
        assertEquals(0, consent.writes)
        assertFalse(consent.accepted)
        assertEquals(AiGate.CONSENT, aiGate(consent.accepted, signedIn = true))
        assertEquals(AiGate.CONSENT, aiGate(consent.accepted, signedIn = false))
    }
}
