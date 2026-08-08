package space.linuxct.glyphworks.screens

import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Sunrise/sunset via the NOAA solar-position approximation (accurate to a
 * few minutes for civil use). Pure math, JVM-testable. Longitude is positive
 * EAST; results are LOCAL minutes-of-day (0..1440) via the UTC offset.
 */
object SolarMath {

    enum class Kind { NORMAL, POLAR_DAY, POLAR_NIGHT }

    data class SunTimes(val riseMin: Int, val setMin: Int, val kind: Kind)

    fun sunTimes(dayOfYear: Int, latDeg: Double, lonDeg: Double, utcOffsetMin: Int): SunTimes {
        val gamma = 2.0 * Math.PI / 365.0 * (dayOfYear - 1)
        val eqTime = 229.18 * (
            0.000075 +
                0.001868 * cos(gamma) - 0.032077 * sin(gamma) -
                0.014615 * cos(2 * gamma) - 0.040849 * sin(2 * gamma)
            )
        val decl = 0.006918 -
            0.399912 * cos(gamma) + 0.070257 * sin(gamma) -
            0.006758 * cos(2 * gamma) + 0.000907 * sin(2 * gamma) -
            0.002697 * cos(3 * gamma) + 0.00148 * sin(3 * gamma)

        val latRad = Math.toRadians(latDeg)
        val zenith = Math.toRadians(90.833) // official sunrise/sunset zenith
        val cosHa = cos(zenith) / (cos(latRad) * cos(decl)) - tan(latRad) * tan(decl)

        if (cosHa > 1.0) return SunTimes(0, 0, Kind.POLAR_NIGHT) // sun never rises
        if (cosHa < -1.0) return SunTimes(0, 1440, Kind.POLAR_DAY) // sun never sets

        val haDeg = Math.toDegrees(acos(cosHa))
        val riseUtc = 720.0 - 4.0 * (lonDeg + haDeg) - eqTime
        val setUtc = 720.0 - 4.0 * (lonDeg - haDeg) - eqTime
        return SunTimes(
            riseMin = normalize(riseUtc + utcOffsetMin),
            setMin = normalize(setUtc + utcOffsetMin),
            kind = Kind.NORMAL,
        )
    }

    private fun normalize(minutes: Double): Int {
        var m = minutes.toInt() % 1440
        if (m < 0) m += 1440
        return m
    }
}
