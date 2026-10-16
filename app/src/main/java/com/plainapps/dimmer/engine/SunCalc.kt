package com.plainapps.dimmer.engine

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.tan

/**
 * Sunrise and sunset from latitude/longitude and a date, with the NOAA Solar Calculator equations
 * (https://gml.noaa.gov/grad/solcalc/calcdetails.html, based on Meeus): Julian century, equation
 * of time, declination, zenith 90.833° (refraction + solar disc). Each event is refined once at
 * its own time, which keeps it within about a minute of the published tables. Pure, JVM-tested.
 */
object SunCalc {

    /** What happened on one local date. [sunrise]/[sunset] are null on polar day or polar night. */
    data class SunTimes(val sunrise: Instant?, val sunset: Instant?, val polar: Polar?)

    enum class Polar { DAY, NIGHT }

    /**
     * Sun events of the local calendar date [date] in [zone] at ([latitude], [longitude]) degrees
     * (north and east positive). The solar day used is the one whose solar noon falls on [date]
     * locally, so zones far from their longitude (e.g. UTC+14) still get the right day.
     */
    fun sunTimes(date: LocalDate, latitude: Double, longitude: Double, zone: ZoneId): SunTimes {
        // Pick the UTC day whose solar noon lands on the requested local date.
        var utcDay = date
        for (shift in longArrayOf(0, -1, 1)) {
            val candidate = date.plusDays(shift)
            val noon = eventUtcMinutes(candidate, latitude, longitude, Event.NOON) ?: continue
            if (toInstant(candidate, noon).atZone(zone).toLocalDate() == date) {
                utcDay = candidate
                break
            }
        }
        val rise = eventUtcMinutes(utcDay, latitude, longitude, Event.RISE)
        val set = eventUtcMinutes(utcDay, latitude, longitude, Event.SET)
        val polar = if (rise == null || set == null) polarKind(utcDay, latitude, longitude) else null
        return SunTimes(
            sunrise = rise?.let { toInstant(utcDay, it) },
            sunset = set?.let { toInstant(utcDay, it) },
            polar = polar,
        )
    }

    private enum class Event { RISE, NOON, SET }

    private fun toInstant(utcDay: LocalDate, minutes: Double): Instant =
        utcDay.atStartOfDay(ZoneOffset.UTC).toInstant().plusSeconds((minutes * 60).roundToLong())

    /** Minutes after 00:00 UTC of [utcDay] (may be negative or > 1440), or null on polar day/night. */
    private fun eventUtcMinutes(utcDay: LocalDate, lat: Double, lon: Double, event: Event): Double? {
        val jd0 = julianDay(utcDay)
        // First pass at local solar noon, then one refinement at the event's own time.
        var minutes = 720.0 - 4.0 * lon
        repeat(2) {
            val t = julianCentury(jd0 + minutes / 1440.0)
            val eqTime = equationOfTime(t)
            val noon = 720.0 - 4.0 * lon - eqTime
            if (event == Event.NOON) {
                minutes = noon
                return@repeat
            }
            val ha = hourAngleSunrise(lat, declination(t)) ?: return null
            minutes = if (event == Event.RISE) noon - 4.0 * ha else noon + 4.0 * ha
        }
        return minutes
    }

    private fun polarKind(utcDay: LocalDate, lat: Double, lon: Double): Polar {
        val t = julianCentury(julianDay(utcDay) + (720.0 - 4.0 * lon) / 1440.0)
        val cosHa = cosHourAngle(lat, declination(t))
        return if (cosHa > 1.0) Polar.NIGHT else Polar.DAY
    }

    /** Julian day number at 00:00 UTC of [date]. */
    internal fun julianDay(date: LocalDate): Double {
        var y = date.year
        var m = date.monthValue
        if (m <= 2) {
            y -= 1
            m += 12
        }
        val a = floor(y / 100.0)
        val b = 2 - a + floor(a / 4.0)
        return floor(365.25 * (y + 4716)) + floor(30.6001 * (m + 1)) + date.dayOfMonth + b - 1524.5
    }

    private fun julianCentury(jd: Double) = (jd - 2451545.0) / 36525.0

    private fun geomMeanLongSun(t: Double): Double {
        val l = 280.46646 + t * (36000.76983 + t * 0.0003032)
        return ((l % 360.0) + 360.0) % 360.0
    }

    private fun geomMeanAnomalySun(t: Double) = 357.52911 + t * (35999.05029 - 0.0001537 * t)

    private fun eccentricityEarthOrbit(t: Double) = 0.016708634 - t * (0.000042037 + 0.0000001267 * t)

    private fun sunEquationOfCenter(t: Double): Double {
        val m = rad(geomMeanAnomalySun(t))
        return sin(m) * (1.914602 - t * (0.004817 + 0.000014 * t)) +
            sin(2 * m) * (0.019993 - 0.000101 * t) +
            sin(3 * m) * 0.000289
    }

    private fun sunApparentLong(t: Double): Double {
        val trueLong = geomMeanLongSun(t) + sunEquationOfCenter(t)
        val omega = 125.04 - 1934.136 * t
        return trueLong - 0.00569 - 0.00478 * sin(rad(omega))
    }

    private fun obliquityCorrection(t: Double): Double {
        val seconds = 21.448 - t * (46.8150 + t * (0.00059 - t * 0.001813))
        val e0 = 23.0 + (26.0 + seconds / 60.0) / 60.0
        val omega = 125.04 - 1934.136 * t
        return e0 + 0.00256 * cos(rad(omega))
    }

    private fun declination(t: Double): Double =
        deg(asin(sin(rad(obliquityCorrection(t))) * sin(rad(sunApparentLong(t)))))

    /** Equation of time in minutes. */
    private fun equationOfTime(t: Double): Double {
        val epsilon = obliquityCorrection(t)
        val l0 = rad(geomMeanLongSun(t))
        val e = eccentricityEarthOrbit(t)
        val m = rad(geomMeanAnomalySun(t))
        var y = tan(rad(epsilon) / 2.0)
        y *= y
        val eq = y * sin(2 * l0) - 2 * e * sin(m) + 4 * e * y * sin(m) * cos(2 * l0) -
            0.5 * y * y * sin(4 * l0) - 1.25 * e * e * sin(2 * m)
        return 4.0 * deg(eq)
    }

    private fun cosHourAngle(lat: Double, decl: Double): Double {
        val latR = rad(lat)
        val declR = rad(decl)
        return cos(rad(ZENITH)) / (cos(latR) * cos(declR)) - tan(latR) * tan(declR)
    }

    /** Hour angle of sunrise in degrees, or null when the sun never crosses the horizon. */
    private fun hourAngleSunrise(lat: Double, decl: Double): Double? {
        val c = cosHourAngle(lat, decl)
        if (abs(c) > 1.0) return null
        return deg(acos(c))
    }

    private const val ZENITH = 90.833
    private fun rad(d: Double) = d * PI / 180.0
    private fun deg(r: Double) = r * 180.0 / PI
}
