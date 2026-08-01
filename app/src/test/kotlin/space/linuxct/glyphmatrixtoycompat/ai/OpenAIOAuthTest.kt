package space.linuxct.glyphmatrixtoycompat.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The OAuth flow's pure half: PKCE, the randomness that protects it, the
 * authorize URL's shape, and the token response parse.
 *
 * Plain JUnit — no Robolectric, no instrumentation, and nothing that touches
 * `org.json`, which is an empty android.jar stub down here. `android.net.Uri` is
 * the same kind of stub and is replaced for the test classpath only; see
 * `app/src/test/kotlin/android/net/Uri.kt`.
 *
 * ## What is deliberately NOT tested
 *
 * The obfuscated constants are not decoded and their plaintext is not asserted
 * anywhere in this file. Writing `assertEquals("https://…", AUTHORIZE_URL)` would
 * put the very values the author chose to keep out of the source straight back
 * into the repo, in a file that is easier to grep than the one they came from,
 * and would pin them so that rotating one means editing a test. The assertions
 * below are all about *structure* — how many parameters, which of the
 * plain-language ones are present, that nothing is duplicated — which is what can
 * actually regress.
 *
 * ## Reflection
 *
 * `newVerifier`, `codeChallenge` and `newState` are private top-level functions,
 * and they stay private: they are the user's file and the task was the transport,
 * not its visibility. Kotlin compiles them to private statics on the file facade,
 * so the tests reach them through `setAccessible`. That is a deliberate trade —
 * a reflective test that leaves the production file alone, rather than a widened
 * API that exists only for tests.
 */
class OpenAIOAuthTest {

    // ---------- PKCE ----------

    /**
     * RFC 7636 appendix B's worked example, which is the canonical way to be sure
     * an S256 challenge is base64url of the SHA-256 **without padding** — the
     * three things this can get wrong are hex instead of base64, standard base64
     * instead of the URL alphabet, and a trailing `=`.
     */
    @Test
    fun `code challenge is the unpadded base64url sha256 of the verifier`() {
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", codeChallenge(verifier))
    }

    @Test
    fun `code challenge is deterministic`() {
        assertEquals(codeChallenge("some-verifier"), codeChallenge("some-verifier"))
    }

    @Test
    fun `code challenge never contains base64 padding or non-url characters`() {
        repeat(20) {
            val challenge = codeChallenge(newVerifier())
            assertEquals(43, challenge.length)
            assertTrue(challenge, challenge.all { it in BASE64URL })
        }
    }

    // ---------- the random material ----------

    /**
     * 32 bytes of entropy, base64url without padding — 43 characters. RFC 7636
     * requires 43..128 and at least 256 bits, so both the length and the alphabet
     * are part of the contract rather than cosmetic.
     */
    @Test
    fun `verifier is 43 url-safe characters`() {
        repeat(20) {
            val verifier = newVerifier()
            assertEquals(verifier, 43, verifier.length)
            assertTrue(verifier, verifier.all { it in BASE64URL })
        }
    }

    /** 16 random bytes as lower-case hex. */
    @Test
    fun `state is 32 lower-case hex characters`() {
        repeat(20) {
            val state = newState()
            assertEquals(state, 32, state.length)
            assertTrue(state, state.all { it in HEX })
        }
    }

    /**
     * The whole point of both values. A verifier that repeated would let a
     * captured code be replayed; a state that repeated would not distinguish this
     * app's callback from a forged one.
     */
    @Test
    fun `verifier and state differ on every call`() {
        val verifiers = List(50) { newVerifier() }
        val states = List(50) { newState() }
        assertEquals(50, verifiers.toSet().size)
        assertEquals(50, states.toSet().size)
    }

    // ---------- the authorize URL ----------

    /**
     * Every parameter the endpoint is handed appears once and once only. A
     * duplicate `state` or `code_challenge` is the classic way a hand-built
     * authorize URL breaks — the server takes the first, the app checks the
     * second — and it produces a failure that only shows up on a real device.
     */
    @Test
    fun `authorize url carries each query parameter exactly once`() {
        val flow = createOAuthFlow()
        val keys = android.net.Uri.parse(flow.url).queryPairs().map { it.first }

        // The parameters whose names are plain language in the source. The three
        // obfuscated ones are counted, not named — see this class's KDoc.
        val required = listOf(
            "response_type",
            "client_id",
            "redirect_uri",
            "scope",
            "code_challenge",
            "code_challenge_method",
            "state",
        )
        required.forEach { key ->
            assertEquals("parameter $key", 1, keys.count { it == key })
        }
        assertEquals("no parameter may be repeated", keys.size, keys.toSet().size)
        assertEquals("unexpected number of query parameters", 10, keys.size)
    }

    @Test
    fun `authorize url pins the pkce method and echoes the flow's own state and challenge`() {
        val flow = createOAuthFlow()
        val uri = android.net.Uri.parse(flow.url)
        assertEquals("code", uri.getQueryParameter("response_type"))
        assertEquals("S256", uri.getQueryParameter("code_challenge_method"))
        assertEquals(flow.state, uri.getQueryParameter("state"))
        // The URL must commit to the challenge for the verifier it hands back,
        // or the exchange fails after the user has already typed a password.
        assertEquals(codeChallenge(flow.verifier), uri.getQueryParameter("code_challenge"))
    }

    @Test
    fun `two flows share no state or verifier`() {
        val a = createOAuthFlow()
        val b = createOAuthFlow()
        assertNotEquals(a.state, b.state)
        assertNotEquals(a.verifier, b.verifier)
    }

    // ---------- the token response ----------

    @Test
    fun `a well-formed token response parses`() {
        val tokens = parseTokenResponse(
            """{"access_token":"at","refresh_token":"rt","expires_in":3600}""",
        )
        assertEquals("at", tokens.accessToken)
        assertEquals("rt", tokens.refreshToken)
        assertEquals(3600, tokens.expiresIn)
    }

    /**
     * The endpoint returns `id_token`, `token_type` and more besides. Unknown
     * keys must not be an error, or a field added on the server's side breaks
     * sign-in for every installed copy of the app at once.
     */
    @Test
    fun `unknown fields are ignored`() {
        val tokens = parseTokenResponse(
            """
            {"token_type":"Bearer","access_token":"at","id_token":"jwt",
             "refresh_token":"rt","expires_in":3600,"scope":"openid",
             "something_new":{"nested":[1,2,3]}}
            """.trimIndent(),
        )
        assertEquals("at", tokens.accessToken)
        assertEquals("rt", tokens.refreshToken)
        assertEquals(3600, tokens.expiresIn)
    }

    /**
     * "It didn't work" is not a bug report. Each of these has to say which field
     * was missing, because the only person who will ever read the message is
     * someone whose sign-in failed on a device nobody can attach a debugger to.
     */
    @Test
    fun `a missing field names itself`() {
        assertFailsWithMessage("access_token") {
            parseTokenResponse("""{"refresh_token":"rt","expires_in":3600}""")
        }
        assertFailsWithMessage("refresh_token") {
            parseTokenResponse("""{"access_token":"at","expires_in":3600}""")
        }
        assertFailsWithMessage("expires_in") {
            parseTokenResponse("""{"access_token":"at","refresh_token":"rt"}""")
        }
    }

    /** A field that is present but empty is a missing field with extra steps. */
    @Test
    fun `a blank field is treated as missing`() {
        assertFailsWithMessage("access_token") {
            parseTokenResponse("""{"access_token":"","refresh_token":"rt","expires_in":3600}""")
        }
    }

    /**
     * The realistic malformed responses: a proxy's HTML error page, a truncated
     * body, an empty one, and a field of the wrong type. None may escape as a raw
     * `SerializationException` — the dialog shows this text.
     */
    @Test
    fun `malformed json fails with a readable message`() {
        listOf(
            "<html><body>502 Bad Gateway</body></html>",
            """{"access_token":"at","refresh_token":""",
            "",
            """{"access_token":{"nested":true},"refresh_token":"rt","expires_in":3600}""",
            """{"access_token":"at","refresh_token":"rt","expires_in":"soon"}""",
        ).forEach { body ->
            assertFailsWithMessage("not valid JSON", body) { parseTokenResponse(body) }
        }
    }

    // ---------- helpers ----------

    private fun assertFailsWithMessage(needle: String, label: String = needle, block: () -> Unit) {
        val thrown = try {
            block()
            null
        } catch (e: IllegalStateException) {
            e
        }
        val message = thrown?.message
        assertTrue(
            "expected a message mentioning \"$needle\" for <$label>, got ${thrown?.javaClass?.simpleName}: $message",
            message != null && message.contains(needle),
        )
    }

    private companion object {
        val BASE64URL = ('A'..'Z') + ('a'..'z') + ('0'..'9') + listOf('-', '_')
        val HEX = ('0'..'9') + ('a'..'f')

        /** The file facade `OpenAIOAuth.kt` compiles to; see this class's KDoc. */
        val facade: Class<*> = Class.forName("space.linuxct.glyphmatrixtoycompat.ai.OpenAIOAuthKt")

        fun invoke(name: String, vararg args: Any?): Any {
            val method = facade.declaredMethods.single { it.name == name }
            method.isAccessible = true
            return checkNotNull(method.invoke(null, *args)) { "$name returned null" }
        }

        fun newVerifier(): String = invoke("newVerifier") as String
        fun newState(): String = invoke("newState") as String
        fun codeChallenge(verifier: String): String = invoke("codeChallenge", verifier) as String
    }
}
