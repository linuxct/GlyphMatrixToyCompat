package android.net

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * **A JVM stand-in for `android.net.Uri`, for unit tests only.**
 *
 * ## Why this exists
 *
 * `ai/OpenAIOAuth.kt` builds the authorize URL with `Uri.parse(...).buildUpon()`,
 * and the thing worth testing about that URL — that it carries every required
 * query parameter, exactly once — is pure string assembly with no device in it.
 * Under plain JUnit, though, every method on the real `Uri` is an android.jar stub
 * that throws `RuntimeException("… not mocked")`, so the function cannot be called
 * at all. This class replaces it on the **test** classpath, and only there: the
 * app compiles and runs against the platform's own `Uri` exactly as before.
 *
 * That is the same reasoning the repo already applied to `org.json` — an
 * android.jar stub is not a dependency you can unit-test through — resolved the
 * other way round, because the token *parsing* could move to kotlinx.serialization
 * and a URL builder in code that must stay diffable against its source app could
 * not.
 *
 * ## What it is not
 *
 * Not a faithful `Uri`. It implements the four calls the OAuth code makes —
 * [parse], [buildUpon]/[Builder.appendQueryParameter]/[Builder.build],
 * [getQueryParameter] and [port] — over a plain string, with `application/x-www-
 * form-urlencoded` escaping corrected to `%20` for spaces, which is what the real
 * builder emits. It has no notion of opacity, authority parsing, or relative
 * resolution. Anything that needs those needs an instrumented test instead.
 *
 * If a test ever fails here with "not mocked", the real android.jar won the
 * classpath ordering and this file stopped taking effect — the failure is loud on
 * purpose rather than silently testing nothing.
 */
class Uri private constructor(private val raw: String) {

    /** The port in the authority, or -1 when the URL does not name one. */
    val port: Int
        get() {
            val afterScheme = raw.substringAfter("://", raw)
            val authority = afterScheme.substringBefore('/').substringBefore('?')
            val colon = authority.lastIndexOf(':')
            if (colon < 0) return -1
            return authority.substring(colon + 1).toIntOrNull() ?: -1
        }

    /** First value for [key], decoded, or null when the key is absent. */
    fun getQueryParameter(key: String): String? =
        queryPairs().firstOrNull { it.first == key }?.second

    fun buildUpon(): Builder = Builder(raw)

    override fun toString(): String = raw

    /** Every `name=value` pair in the query, in order, decoded. */
    fun queryPairs(): List<Pair<String, String>> {
        val query = raw.substringAfter('?', "")
        if (query.isEmpty()) return emptyList()
        return query.split('&').filter { it.isNotEmpty() }.map { pair ->
            val name = pair.substringBefore('=')
            val value = pair.substringAfter('=', "")
            decode(name) to decode(value)
        }
    }

    class Builder(private var raw: String) {
        fun appendQueryParameter(key: String, value: String): Builder {
            val separator = if (raw.contains('?')) "&" else "?"
            raw = "$raw$separator${encode(key)}=${encode(value)}"
            return this
        }

        fun build(): Uri = Uri(raw)
    }

    companion object {
        @JvmStatic
        fun parse(uriString: String): Uri = Uri(uriString)

        private fun encode(value: String): String =
            URLEncoder.encode(value, "UTF-8").replace("+", "%20")

        private fun decode(value: String): String =
            URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
    }
}
