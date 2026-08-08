package space.linuxct.glyphworks.update

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Queries the GitHub Releases API for the newest published release and
 * compares it against the installed versionName. Pure HttpURLConnection +
 * org.json — no networking dependency for a once-a-day GET.
 */
object UpdateChecker {

    const val RELEASES_PAGE = "https://github.com/linuxct/glyphworks/releases/latest"
    private const val LATEST_API = "https://api.github.com/repos/linuxct/glyphworks/releases/latest"

    sealed interface Result {
        data class UpdateAvailable(val version: String, val url: String) : Result
        data object UpToDate : Result
        data class Failed(val reason: String) : Result
    }

    /** Blocking network call — invoke off the main thread. */
    fun check(installedVersion: String): Result {
        val conn: HttpURLConnection
        try {
            conn = URL(LATEST_API).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            return Result.Failed(e.message ?: e.javaClass.simpleName)
        }
        return try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            // GitHub rejects requests without a User-Agent.
            conn.setRequestProperty("User-Agent", "glyphworks")
            when (val code = conn.responseCode) {
                HttpURLConnection.HTTP_OK -> {
                    val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                    val tag = json.optString("tag_name")
                    if (tag.isNotEmpty() && isNewer(tag, installedVersion)) {
                        Result.UpdateAvailable(
                            version = tag.removePrefix("v"),
                            url = json.optString("html_url").ifEmpty { RELEASES_PAGE },
                        )
                    } else {
                        Result.UpToDate
                    }
                }
                // No release published yet.
                HttpURLConnection.HTTP_NOT_FOUND -> Result.UpToDate
                else -> Result.Failed("HTTP $code")
            }
        } catch (e: Exception) {
            Result.Failed(e.message ?: e.javaClass.simpleName)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Numeric dotted-version comparison; a leading "v" and any non-numeric
     * suffix are ignored, so "v0.2.0-beta" compares as 0.2.0 and missing
     * components count as 0 ("1.2" == "1.2.0").
     */
    fun isNewer(remote: String, installed: String): Boolean {
        val r = parse(remote)
        val l = parse(installed)
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    private fun parse(version: String): List<Int> =
        version.trim().removePrefix("v").removePrefix("V")
            .takeWhile { it.isDigit() || it == '.' }
            .split('.')
            .mapNotNull { it.toIntOrNull() }
}
