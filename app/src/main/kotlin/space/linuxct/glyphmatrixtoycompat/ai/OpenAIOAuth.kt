package space.linuxct.glyphmatrixtoycompat.ai

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * pulseloop-android's XOR-`0x5E` decoder, name and all, so the two apps' auth and
 * client code stay diffable against each other.
 *
 * `internal` rather than file-private only so `GlyphAiClient` can decode the
 * endpoint and headers it carries over from pulseloop's `OpenAIClient` companion
 * object with **this** helper instead of a second copy of it. A second copy is a
 * second thing to get subtly wrong, and the encoding is not a secret — it keeps
 * these strings out of a trivial `strings` sweep of the APK, nothing more.
 */
internal fun d(v: IntArray) = v.map { (it xor 0x5E).toChar() }.joinToString("")

private val CLIENT_ID     = d(intArrayOf(63, 46, 46, 1, 27, 19, 49, 63, 51, 27, 27, 4, 105, 109, 56, 110, 29, 53, 6, 63, 6, 46, 105, 54, 44, 63, 48, 48))
private val AUTHORIZE_URL = d(intArrayOf(54, 42, 42, 46, 45, 100, 113, 113, 63, 43, 42, 54, 112, 49, 46, 59, 48, 63, 55, 112, 61, 49, 51, 113, 49, 63, 43, 42, 54, 113, 63, 43, 42, 54, 49, 44, 55, 36, 59))
private val TOKEN_URL     = d(intArrayOf(54, 42, 42, 46, 45, 100, 113, 113, 63, 43, 42, 54, 112, 49, 46, 59, 48, 63, 55, 112, 61, 49, 51, 113, 49, 63, 43, 42, 54, 113, 42, 49, 53, 59, 48))
private val SCOPE         = d(intArrayOf(49, 46, 59, 48, 55, 58, 126, 46, 44, 49, 56, 55, 50, 59, 126, 59, 51, 63, 55, 50, 126, 49, 56, 56, 50, 55, 48, 59, 1, 63, 61, 61, 59, 45, 45))
val OAUTH_REDIRECT_URI    = d(intArrayOf(54, 42, 42, 46, 100, 113, 113, 50, 49, 61, 63, 50, 54, 49, 45, 42, 100, 111, 106, 107, 107, 113, 63, 43, 42, 54, 113, 61, 63, 50, 50, 60, 63, 61, 53))

data class OAuthFlow(
    val url: String,
    val state: String,
    val verifier: String
)

data class OAuthTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Int
)

fun createOAuthFlow(): OAuthFlow {
    val verifier  = newVerifier()
    val challenge = codeChallenge(verifier)
    val state     = newState()

    val url = Uri.parse(AUTHORIZE_URL).buildUpon()
        .appendQueryParameter("response_type",              "code")
        .appendQueryParameter("client_id",                  CLIENT_ID)
        .appendQueryParameter("redirect_uri",               OAUTH_REDIRECT_URI)
        .appendQueryParameter("scope",                      SCOPE)
        .appendQueryParameter("code_challenge",             challenge)
        .appendQueryParameter("code_challenge_method",      "S256")
        .appendQueryParameter("state",                      state)
        .appendQueryParameter(d(intArrayOf(55, 58, 1, 42, 49, 53, 59, 48, 1, 63, 58, 58, 1, 49, 44, 57, 63, 48, 55, 36, 63, 42, 55, 49, 48, 45)), "true")
        .appendQueryParameter(d(intArrayOf(61, 49, 58, 59, 38, 1, 61, 50, 55, 1, 45, 55, 51, 46, 50, 55, 56, 55, 59, 58, 1, 56, 50, 49, 41)), "true")
        .appendQueryParameter(d(intArrayOf(49, 44, 55, 57, 55, 48, 63, 42, 49, 44)), d(intArrayOf(61, 49, 58, 59, 38, 1, 61, 50, 55, 1, 44, 45)))
        .build().toString()

    return OAuthFlow(url = url, state = state, verifier = verifier)
}

suspend fun exchangeAuthorizationCode(code: String, verifier: String): OAuthTokens =
    requestToken(
        "grant_type"    to "authorization_code",
        "client_id"     to CLIENT_ID,
        "code"          to code,
        "code_verifier" to verifier,
        "redirect_uri"  to OAUTH_REDIRECT_URI
    )

suspend fun refreshOAuthToken(refreshToken: String): OAuthTokens =
    requestToken(
        "grant_type"    to "refresh_token",
        "client_id"     to CLIENT_ID,
        "refresh_token" to refreshToken
    )

/**
 * How long the token endpoint gets to answer. The same 20 s the OkHttp client
 * this replaced was configured with, applied to connect and read alike.
 */
private const val TOKEN_TIMEOUT_MS = 20_000

/**
 * The token endpoint's reply, as far as this app is concerned.
 *
 * Every field is optional *to the parser* and required by [parseTokenResponse]
 * instead. That split is deliberate: `ignoreUnknownKeys` already lets the server
 * add fields, and making the three we need nullable here means a reply that is
 * valid JSON but missing `access_token` is reported by name — "…did not contain
 * access_token" — rather than as a `MissingFieldException` from inside the
 * generated deserializer. An OAuth failure is something the user has to act on,
 * so the message it produces is part of the feature.
 */
@Serializable
internal data class TokenResponseJson(
    @SerialName("access_token") val accessToken: String? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_in") val expiresIn: Int? = null,
)

/**
 * Lenient about *extra* fields (the endpoint returns `id_token`, `token_type`
 * and more that this app has no use for), strict about the shape of the ones it
 * reads.
 */
private val tokenJson = Json { ignoreUnknownKeys = true }

/**
 * kotlinx.serialization rather than `org.json`, which is an empty android.jar
 * stub under plain JUnit — the whole of this parse is unit-tested, and this
 * repo has already rejected `org.json` once for that reason.
 */
internal fun parseTokenResponse(body: String): OAuthTokens {
    val parsed = try {
        tokenJson.decodeFromString(TokenResponseJson.serializer(), body)
    } catch (e: Exception) {
        error("Token response was not valid JSON: ${e.message ?: e.javaClass.simpleName}")
    }
    val access = parsed.accessToken?.takeIf { it.isNotBlank() }
        ?: error("Token response did not contain access_token")
    val refresh = parsed.refreshToken?.takeIf { it.isNotBlank() }
        ?: error("Token response did not contain refresh_token")
    val expires = parsed.expiresIn
        ?: error("Token response did not contain expires_in")
    return OAuthTokens(accessToken = access, refreshToken = refresh, expiresIn = expires)
}

/**
 * `HttpURLConnection` posting `application/x-www-form-urlencoded`, following
 * `update/UpdateChecker`'s idiom — explicit timeouts, a `responseCode` switch,
 * the stream read inside `use`, and `disconnect()` in a `finally`. This app
 * carries no HTTP client dependency and does not gain one for two POSTs.
 */
private suspend fun requestToken(vararg params: Pair<String, String>): OAuthTokens =
    withContext(Dispatchers.IO) {
        val body = params.joinToString("&") { (k, v) -> "${formEncode(k)}=${formEncode(v)}" }
        val conn = URL(TOKEN_URL).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = TOKEN_TIMEOUT_MS
            conn.readTimeout = TOKEN_TIMEOUT_MS
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.setRequestProperty("Accept", "application/json")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            // A 4xx from the token endpoint carries the reason (`invalid_grant`
            // and friends) in the ERROR stream, which is where the useful half
            // of a failed sign-in lives; reading only inputStream would throw
            // and lose it.
            val stream = if (code == HttpURLConnection.HTTP_OK) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code != HttpURLConnection.HTTP_OK) error("Token request failed $code: $text")
            if (text.isBlank()) error("Empty token response")
            parseTokenResponse(text)
        } finally {
            conn.disconnect()
        }
    }

/**
 * `application/x-www-form-urlencoded` escaping: `URLEncoder` with the space-as-`+`
 * rule that form encoding actually specifies, which is what `FormBody` did.
 */
private fun formEncode(value: String): String = URLEncoder.encode(value, "UTF-8")

private fun newVerifier(): String {
    val b = ByteArray(32)
    SecureRandom().nextBytes(b)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(b)
}

private fun codeChallenge(verifier: String): String {
    val hash = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray())
    return Base64.getUrlEncoder().withoutPadding().encodeToString(hash)
}

private fun newState(): String {
    val b = ByteArray(16)
    SecureRandom().nextBytes(b)
    return b.joinToString("") { "%02x".format(it) }
}