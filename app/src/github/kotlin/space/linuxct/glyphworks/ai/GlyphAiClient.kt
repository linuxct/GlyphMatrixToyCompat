package space.linuxct.glyphworks.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import space.linuxct.glyphworks.core.DebugLog
import space.linuxct.glyphworks.core.ai.ChatRequest
import space.linuxct.glyphworks.core.ai.ChatStreamResult
import space.linuxct.glyphworks.core.ai.ChatWire
import space.linuxct.glyphworks.core.ai.GlyphChatClient
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

// Carried over verbatim from pulseloop-android's `OpenAIClient` companion object,
// in the same XOR-0x5E form and decoded with the same helper (`d`, now internal in
// OpenAIOAuth.kt) rather than a second copy of it. The two apps talk to the same
// backend with the same headers, and keeping the encoded arrays byte-identical is
// what lets the files be diffed against each other when the backend moves.
private val OAUTH_URL = d(
    intArrayOf(
        54, 42, 42, 46, 45, 100, 113, 113, 61, 54, 63, 42, 57, 46, 42, 112, 61, 49, 51, 113, 60,
        63, 61, 53, 59, 48, 58, 115, 63, 46, 55, 113, 61, 49, 58, 59, 38, 113, 44, 59, 45, 46,
        49, 48, 45, 59, 45,
    ),
)

private val OAUTH_HEADERS = mapOf(
    d(intArrayOf(17, 46, 59, 48, 31, 23, 115, 28, 59, 42, 63)) to
        d(intArrayOf(44, 59, 45, 46, 49, 48, 45, 59, 45, 99, 59, 38, 46, 59, 44, 55, 51, 59, 48, 42, 63, 50)),
    d(intArrayOf(49, 44, 55, 57, 55, 48, 63, 42, 49, 44)) to
        d(intArrayOf(61, 49, 58, 59, 38, 1, 61, 50, 55, 1, 44, 45)),
)

/**
 * Talks to the Responses API over the Codex OAuth backend, streaming.
 *
 * ## What is actually here
 *
 * Almost nothing, and that is the design. Opening a connection and reading lines
 * off a socket is the part of this feature that no unit test can execute, so it
 * has been reduced to exactly that: everything with a decision in it —
 * the request body, the SSE grammar, the accumulate-by-item-id logic, the
 * fallback when a stream is cut mid-item — lives in `core/ai/ChatWire.kt` and is
 * covered by plain JUnit tests. Compare `pulseloop/data/network/OpenAIClient.kt`,
 * where those two concerns are one 90-line method that can only be exercised
 * against a live account.
 *
 * The stream is read **lazily**: `lineSequence()` goes straight into
 * [ChatWire.parseSse], so a text delta reaches [GlyphChatClient.respond]'s
 * callback as it arrives off the wire rather than after the response completes.
 * That is the whole reason the reply appears a word at a time.
 *
 * ## `HttpURLConnection`, following `update/UpdateChecker`
 *
 * Explicit timeouts, a `responseCode` switch, the stream read inside `use`,
 * `disconnect()` in a `finally`. This app carries no HTTP client dependency, and
 * SSE over `HttpURLConnection` is a `BufferedReader` and a loop — OkHttp would
 * buy nothing here that `bufferedReader()` does not already do. The read timeout
 * is generous because it applies **between chunks**, and a reasoning model can
 * think for a long while before its first token.
 *
 * ## 401
 *
 * An OAuth access token lasts hours, and a conversation resumed the next morning
 * will find it stale. A 401 arrives *before* any of the body streams, so no
 * partial text has reached the UI and a silent retry is honest: refresh through
 * [refreshOAuthToken], persist through [TokenStore] — persisting matters, or the
 * next call pays the same round trip — and send once more. A second 401 is a real
 * authentication failure and is surfaced, because at that point the user has to
 * sign in again and nothing this class can do will help.
 */
class GlyphAiClient(
    private val tokens: TokenStore,
    private val url: String = OAUTH_URL,
    private val headers: Map<String, String> = OAUTH_HEADERS,
) : GlyphChatClient {

    override suspend fun respond(
        request: ChatRequest,
        onTextDelta: ((String) -> Unit)?,
    ): ChatStreamResult = withContext(Dispatchers.IO) {
        val body = ChatWire.encodeRequest(request)
        val access = accessToken()
        try {
            execute(body, access, onTextDelta)
        } catch (e: UnauthorizedException) {
            val refreshed = refreshAccessToken()
                ?: throw IOException("Signed out: please sign in again.", e)
            execute(body, refreshed, onTextDelta)
        }
    }

    /**
     * A usable access token, refreshing first if the stored one is spent.
     *
     * Refreshing *before* the call rather than only reacting to a 401 saves the
     * user a wasted round trip on the very common path of opening the editor a
     * day later, and costs nothing when the token is fresh.
     */
    private suspend fun accessToken(): String {
        val stored = tokens.accessToken
        if (!stored.isNullOrBlank() && tokens.hasFreshAccessToken()) return stored
        return refreshAccessToken()
            ?: stored?.takeIf { it.isNotBlank() }
            // A stale token is still worth trying — the expiry is the server's to
            // decide and the clock here may simply be wrong — but with neither a
            // token nor a refresh there is nothing to send.
            ?: throw IOException("Not signed in.")
    }

    /** Refreshes and persists, or null if there is no sign-in to refresh from. */
    private suspend fun refreshAccessToken(): String? {
        val refresh = tokens.refreshToken?.takeIf { it.isNotBlank() } ?: return null
        return try {
            val issued = refreshOAuthToken(refresh)
            tokens.save(issued)
            issued.accessToken
        } catch (e: Exception) {
            DebugLog.w(TAG, "token refresh failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    private fun execute(
        body: String,
        accessToken: String,
        onTextDelta: ((String) -> Unit)?,
    ): ChatStreamResult {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Authorization", "Bearer $accessToken")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "text/event-stream")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                // The reason for a 4xx is in the ERROR stream; reading inputStream
                // would throw and lose the only explanation there is.
                val text = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                DebugLog.w(TAG, "HTTP $code from the assistant backend: ${text.take(ERROR_SNIPPET)}")
                if (code == HttpURLConnection.HTTP_UNAUTHORIZED) throw UnauthorizedException(text)
                throw IOException("The assistant service returned $code. ${text.take(ERROR_SNIPPET)}".trim())
            }

            return conn.inputStream.bufferedReader().use { reader ->
                ChatWire.assemble(ChatWire.parseSse(reader.lineSequence()), onTextDelta)
            }
        } finally {
            conn.disconnect()
        }
    }

    /** A 401, distinguished only so [respond] can refresh once and retry. */
    private class UnauthorizedException(body: String) :
        IOException("The assistant service rejected the sign-in. ${body.take(ERROR_SNIPPET)}".trim())

    companion object {
        private const val TAG = "GlyphAiClient"

        /** Long enough for a reasoning model's first token; it applies per read. */
        private const val READ_TIMEOUT_MS = 180_000
        private const val CONNECT_TIMEOUT_MS = 30_000

        /** How much of an error body goes in a message a user might see. */
        private const val ERROR_SNIPPET = 300
    }
}
