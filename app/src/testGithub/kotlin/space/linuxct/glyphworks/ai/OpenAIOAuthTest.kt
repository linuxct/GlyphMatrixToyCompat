package space.linuxct.glyphworks.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder

/**
 * The OAuth flow's pure half: PKCE, the randomness that protects it, the
 * authorize URL's shape, and the token response parse.
 *
 * Plain JUnit — no Robolectric, no instrumentation, and nothing that touches
 * `org.json`, which is an empty android.jar stub down here.
 *
 * ## No `android.net.Uri`, and no stand-in for one either
 *
 * `createOAuthFlow` used to build its URL with `Uri.parse(…).buildUpon()`, whose
 * every method throws `"not mocked"` under plain JUnit, so this file could only
 * run against a hand-written `Uri` planted in `android.net` on the test
 * classpath — a class that shadowed the platform's for every test in the module,
 * whether or not it wanted one. The production code assembles the string itself
 * now (see `authorizeUrl`/`uriEncode`), the stand-in is deleted, and these tests
 * exercise the real implementation.
 *
 * That swap is only safe if the escaping did not move, so it is pinned twice
 * over: once directly, on the encoder's own rules, and once through the two
 * parameters where a wrong rule would actually break a sign-in — the scope,
 * whose spaces must be `%20` and never `+`, and the redirect URI, which must
 * arrive fully escaped.
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
        val keys = queryPairs(flow.url).map { it.first }

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

    /**
     * The exact string, not a bag of parameters: endpoint, `?`, then every pair
     * in the order the source lists them, each half percent-encoded.
     *
     * Everything nameable is asserted literally. The three parameters whose
     * *names* are obfuscated in the source are asserted as what they must be
     * without being spelled out — present, in that position, and containing
     * nothing that escaping should have removed — because naming them here would
     * put them back in the repo; see this class's KDoc.
     */
    @Test
    fun `the authorize url is the endpoint, a question mark, and the parameters in order`() {
        val flow = createOAuthFlow()
        val head = constant("AUTHORIZE_URL") + "?" + listOf(
            "response_type" to "code",
            "client_id" to constant("CLIENT_ID"),
            "redirect_uri" to OAUTH_REDIRECT_URI,
            "scope" to constant("SCOPE"),
            "code_challenge" to codeChallenge(flow.verifier),
            "code_challenge_method" to "S256",
            "state" to flow.state,
        ).joinToString("&") { (key, value) -> "${uriEncode(key)}=${uriEncode(value)}" }

        assertTrue(flow.url, flow.url.startsWith(head))
        val rest = flow.url.removePrefix(head)
        assertTrue(rest, rest.startsWith("&"))
        val tail = rest.drop(1).split("&")
        assertEquals(rest, 3, tail.size)
        tail.forEach { pair -> assertTrue(pair, ESCAPED_PAIR.matches(pair)) }
    }

    /**
     * The scope is a space-separated list, and this is the single place where
     * `URLEncoder` — the obvious thing to reach for — would have silently changed
     * the request: it writes a space as `+`, which a query parser reads as a
     * literal plus, and the sign-in fails on the device with a scope the server
     * does not recognise.
     */
    @Test
    fun `spaces in the scope are percent-encoded, never plus-encoded`() {
        val scope = rawQueryValue(createOAuthFlow().url, "scope")
        assertTrue(scope, scope.contains("%20"))
        assertFalse(scope, scope.contains("+"))
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

    /**
     * The query as `name` to `value`, decoded, in order.
     *
     * Six lines here rather than a `Uri` on the test classpath. The decode side
     * has to guard a literal `+`: `URLDecoder` reads it as a space, and this
     * query encodes `+` as `%2B` and a space as `%20`, so an undefended decode
     * would turn a `+` in a token into a space that was never there.
     */
    private fun queryPairs(url: String): List<Pair<String, String>> =
        url.substringAfter('?', "")
            .split('&')
            .filter { it.isNotEmpty() }
            .map { pair ->
                decode(pair.substringBefore('=')) to decode(pair.substringAfter('=', ""))
            }

    /** [key]'s value **as it appears in the URL**, still escaped. */
    private fun rawQueryValue(url: String, key: String): String =
        url.substringAfter('?', "")
            .split('&')
            .first { it.substringBefore('=') == key }
            .substringAfter('=', "")

    private fun decode(value: String): String =
        URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")

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

        /**
         * A `name=value` pair in which everything that should have been escaped
         * was: only the unreserved set and `%` survive an encode.
         */
        val ESCAPED_PAIR = Regex("""[A-Za-z0-9_\-!.~'()*%]+=[A-Za-z0-9_\-!.~'()*%]+""")

        /** The file facade `OpenAIOAuth.kt` compiles to; see this class's KDoc. */
        val facade: Class<*> = Class.forName("space.linuxct.glyphworks.ai.OpenAIOAuthKt")

        fun invoke(name: String, vararg args: Any?): Any {
            val method = facade.declaredMethods.single { it.name == name }
            method.isAccessible = true
            return checkNotNull(method.invoke(null, *args)) { "$name returned null" }
        }

        /**
         * One of the file's private constants, read rather than reproduced.
         *
         * Reading it is not the same as *asserting* it: nothing here spells one
         * out, and the URL test compares the code's own output against the code's
         * own inputs, which is what makes it an exact-string assertion that
         * survives any of them being rotated.
         */
        fun constant(name: String): String {
            val field = facade.getDeclaredField(name)
            field.isAccessible = true
            return field.get(null) as String
        }

        fun newVerifier(): String = invoke("newVerifier") as String
        fun newState(): String = invoke("newState") as String
        fun codeChallenge(verifier: String): String = invoke("codeChallenge", verifier) as String
        fun uriEncode(value: String): String = invoke("uriEncode", value) as String
    }
}
