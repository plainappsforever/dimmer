package com.plainapps.dimmer

import com.plainapps.dimmer.engine.SunCalc
import com.plainapps.dimmer.engine.ZoneLocation
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.abs
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Reference times: US Naval Observatory "Rise/Set/Transit" API (aa.usno.navy.mil/api/rstt/oneday),
 * fetched 2026-09-30 for the exact coordinates below. USNO and NOAA use the same standard
 * (upper limb, 34' refraction) and agree to about a minute; SPEC §3 allows 5 minutes.
 */
class SunCalcTest {

    private data class Ref(
        val name: String,
        val zone: String,
        val lat: Double,
        val lon: Double,
        val date: LocalDate,
        val rise: String,
        val set: String,
    )

    private val refs = listOf(
        Ref("New York", "America/New_York", 40.7142, -74.0064, LocalDate.of(2026, 6, 21), "05:25", "20:31"),
        Ref("London", "Europe/London", 51.5083, -0.1253, LocalDate.of(2026, 12, 21), "08:04", "15:53"),
        Ref("Sydney", "Australia/Sydney", -33.8667, 151.2167, LocalDate.of(2026, 6, 21), "07:00", "16:54"),
        Ref("Tokyo", "Asia/Tokyo", 35.6544, 139.7447, LocalDate.of(2026, 3, 20), "05:45", "17:52"),
        Ref("Sao Paulo", "America/Sao_Paulo", -23.5333, -46.6167, LocalDate.of(2026, 9, 30), "05:48", "18:05"),
    )

    private fun minutesOff(actual: java.time.Instant, date: LocalDate, hhmm: String, zone: ZoneId): Long {
        val (h, m) = hhmm.split(':').map { it.toInt() }
        val expected = LocalDateTime.of(date.year, date.month, date.dayOfMonth, h, m).atZone(zone).toInstant()
        return abs(Duration.between(expected, actual).toMinutes())
    }

    @Test
    fun fiveCitiesWithinFiveMinutesOfReference() {
        for (r in refs) {
            val zone = ZoneId.of(r.zone)
            val t = SunCalc.sunTimes(r.date, r.lat, r.lon, zone)
            val rise = assertNotNull(t.sunrise, r.name)
            val set = assertNotNull(t.sunset, r.name)
            val riseOff = minutesOff(rise, r.date, r.rise, zone)
            val setOff = minutesOff(set, r.date, r.set, zone)
            assertTrue(riseOff <= 5, "${r.name} sunrise off by $riseOff min")
            assertTrue(setOff <= 5, "${r.name} sunset off by $setOff min")
            // In practice the equations land within 2 minutes; keep that as a tighter guard.
            assertTrue(riseOff <= 2 && setOff <= 2, "${r.name} drifted: $riseOff/$setOff min")
        }
    }

    @Test
    fun berlinZoneEstimateWithin20MinutesOfBerlinReference() {
        val zone = ZoneId.of("Europe/Berlin")
        val loc = ZoneLocation.of("Europe/Berlin", 3600)
        assertEquals(false, loc.estimated)
        val date = LocalDate.of(2026, 6, 21)
        val t = SunCalc.sunTimes(date, loc.latitude, loc.longitude, zone)
        // USNO for Berlin (52.5 N, 13.3667 E) on 2026-06-21, CEST: rise 04:43, set 21:33.
        assertTrue(minutesOff(t.sunrise!!, date, "04:43", zone) <= 20)
        assertTrue(minutesOff(t.sunset!!, date, "21:33", zone) <= 20)
    }

    @Test
    fun munichWithBerlinZoneIsWithinHalfAnHour() {
        // The "principal city" error for a user elsewhere in the zone: Munich is ~500 km from
        // Berlin. Munich 2026-06-21 computed at its own coordinates vs the Berlin-zone estimate.
        val zone = ZoneId.of("Europe/Berlin")
        val date = LocalDate.of(2026, 6, 21)
        val munich = SunCalc.sunTimes(date, 48.137, 11.575, zone)
        val loc = ZoneLocation.of("Europe/Berlin", 3600)
        val est = SunCalc.sunTimes(date, loc.latitude, loc.longitude, zone)
        assertTrue(abs(Duration.between(munich.sunset, est.sunset).toMinutes()) <= 30)
        assertTrue(abs(Duration.between(munich.sunrise, est.sunrise).toMinutes()) <= 30)
    }

    @Test
    fun polarDayAndNight() {
        val zone = ZoneId.of("Arctic/Longyearbyen")
        val summer = SunCalc.sunTimes(LocalDate.of(2026, 6, 21), 78.0, 16.0, zone)
        assertNull(summer.sunset)
        assertNull(summer.sunrise)
        assertEquals(SunCalc.Polar.DAY, summer.polar)
        val winter = SunCalc.sunTimes(LocalDate.of(2026, 12, 21), 78.0, 16.0, zone)
        assertNull(winter.sunset)
        assertEquals(SunCalc.Polar.NIGHT, winter.polar)
    }

    @Test
    fun farOffsetZoneGetsTheLocalDay() {
        // Kiritimati is UTC+14 at 157 W: the solar day of a local date starts on the previous UTC day.
        val zone = ZoneId.of("Pacific/Kiritimati")
        val date = LocalDate.of(2026, 9, 30)
        val t = SunCalc.sunTimes(date, 1.87, -157.33, zone)
        val set = t.sunset!!.atZone(zone)
        val rise = t.sunrise!!.atZone(zone)
        assertEquals(date, set.toLocalDate())
        assertEquals(date, rise.toLocalDate())
        assertTrue(set.hour in 17..20, "sunset at ${set.toLocalTime()}")
        assertTrue(rise.hour in 5..8, "sunrise at ${rise.toLocalTime()}")
    }

    @Test
    fun zoneLocationLookupAndEstimate() {
        assertTrue(ZoneLocation.tableSize >= 400)
        val ny = ZoneLocation.of("America/New_York", -18000)
        assertEquals(40.7142, ny.latitude, 1e-4)
        assertEquals(-74.0064, ny.longitude, 1e-4)
        // Legacy alias resolved through a canonical-ID function (ICU on Android).
        val canon = mapOf("Asia/Calcutta" to "Asia/Calcutta", "Asia/Kolkata" to "Asia/Calcutta")
        val kolkata = ZoneLocation.of("Asia/Calcutta", 19800) { canon[it] ?: it }
        assertEquals("Asia/Kolkata", kolkata.matchedId)
        assertEquals(false, kolkata.estimated)
        // Not in zone.tab: estimated from the standard offset.
        val est = ZoneLocation.of("Etc/GMT-1", 3600)
        assertTrue(est.estimated)
        assertEquals(15.0, est.longitude, 1e-9)
        assertEquals(ZoneLocation.FALLBACK_LATITUDE, est.latitude, 1e-9)
        val utc = ZoneLocation.of("UTC", 0)
        assertTrue(utc.estimated)
        assertEquals(0.0, utc.longitude, 1e-9)
    }
}
