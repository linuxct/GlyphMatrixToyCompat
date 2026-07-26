package space.linuxct.glyphmatrixtoycompat.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import space.linuxct.glyphmatrixtoycompat.core.LocationPort

/**
 * Passive last-known location (for the solar-path screen). Never requests an
 * active fix; returns null without a location permission. The lookup result
 * is cached for ~10 minutes to keep per-tick polling free.
 */
class AndroidLocationPort(private val app: Context) : LocationPort {

    @Volatile private var cached: Pair<Double, Double>? = null
    @Volatile private var cachedAt = 0L

    override fun latLon(): Pair<Double, Double>? {
        val now = System.currentTimeMillis()
        if (now - cachedAt < CACHE_MS) return cached
        cachedAt = now

        val fine = app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = app.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) {
            cached = null
            return null
        }
        cached = try {
            val lm = app.getSystemService(LocationManager::class.java)
            lm?.allProviders?.firstNotNullOfOrNull { provider ->
                try {
                    lm.getLastKnownLocation(provider)
                } catch (_: SecurityException) {
                    null
                }
            }?.let { it.latitude to it.longitude }
        } catch (_: Exception) {
            null
        }
        return cached
    }

    private companion object {
        const val CACHE_MS = 10 * 60_000L
    }
}
