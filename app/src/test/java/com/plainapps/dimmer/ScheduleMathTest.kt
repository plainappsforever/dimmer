package com.plainapps.dimmer

import com.plainapps.dimmer.engine.EndKind
import com.plainapps.dimmer.engine.ScheduleConfig
import com.plainapps.dimmer.engine.ScheduleMath
import com.plainapps.dimmer.engine.StartKind
import com.plainapps.dimmer.engine.SunCalc
import com.plainapps.dimmer.engine.SunSource
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScheduleMathTest {

    private val berlin = ZoneId.of("Europe/Berlin")
    private val newYork = ZoneId.of("America/New_York")
    private val nightCfg = ScheduleConfig(enabled = true, startMinute = 21 * 60, endMinute = 7 * 60, fadeMinutes = 30)

    private fun at(zone: ZoneId, y: Int, mo: Int, d: Int, h: Int, mi: Int): Instant =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant()

    private fun sunAt(lat: Double, lon: Double, zone: ZoneId) = SunSource { date -> SunCalc.sunTimes(date, lat, lon, zone) }
    private val berlinSun = sunAt(52.5, 13.3667, berlin)
    private val noSun = SunSource { error("sun not needed") }

    @Test
    fun windowCrossingMidnight() {
        val w = assertNotNull(ScheduleMath.windowAt(at(berlin, 2026, 10, 1, 23, 0), nightCfg, berlin, noSun))
        assertEquals(at(berlin, 2026, 10, 1, 21, 0), w.start)
        assertEquals(at(berlin, 2026, 10, 2, 7, 0), w.end)
        assertEquals(at(berlin, 2026, 10, 1, 21, 30), w.fadeEnd)
        // After midnight the window of the previous evening still holds.
        val early = assertNotNull(ScheduleMath.windowAt(at(berlin, 2026, 10, 2, 6, 59), nightCfg, berlin, noSun))
        assertEquals(w, early)
        // End is exclusive, start inclusive.
        assertNull(ScheduleMath.windowAt(at(berlin, 2026, 10, 2, 7, 0), nightCfg, berlin, noSun))
        assertNull(ScheduleMath.windowAt(at(berlin, 2026, 10, 1, 20, 59), nightCfg, berlin, noSun))
        assertNotNull(ScheduleMath.windowAt(at(berlin, 2026, 10, 1, 21, 0), nightCfg, berlin, noSun))
    }

    @Test
    fun nextStart() {
        assertEquals(at(berlin, 2026, 10, 2, 21, 0), ScheduleMath.nextStartAfter(at(berlin, 2026, 10, 2, 7, 0), nightCfg, berlin, noSun))
        // Inside a window the next start is tomorrow's.
        assertEquals(at(berlin, 2026, 10, 2, 21, 0), ScheduleMath.nextStartAfter(at(berlin, 2026, 10, 1, 21, 10), nightCfg, berlin, noSun))
        assertEquals(at(berlin, 2026, 10, 2, 21, 0), ScheduleMath.nextStartAfter(at(berlin, 2026, 10, 2, 1, 0), nightCfg, berlin, noSun))
    }

    @Test
    fun sameDayWindowWhenEndIsAfterStart() {
        val cfg = nightCfg.copy(startMinute = 9 * 60, endMinute = 17 * 60)
        val w = assertNotNull(ScheduleMath.windowAt(at(berlin, 2026, 10, 1, 12, 0), cfg, berlin, noSun))
        assertEquals(Duration.ofHours(8), Duration.between(w.start, w.end))
        assertNull(ScheduleMath.windowAt(at(berlin, 2026, 10, 1, 18, 0), cfg, berlin, noSun))
    }

    @Test
    fun equalStartAndEndIsAnEmptySchedule() {
        val cfg = nightCfg.copy(startMinute = 22 * 60, endMinute = 22 * 60)
        assertNull(ScheduleMath.windowAt(at(berlin, 2026, 10, 1, 22, 30), cfg, berlin, noSun))
        assertNull(ScheduleMath.nextStartAfter(at(berlin, 2026, 10, 1, 12, 0), cfg, berlin, noSun))
    }

    @Test
    fun dstSpringForwardBerlinAndNewYork() {
        // Europe/Berlin springs forward 2026-03-29 02:00 -> 03:00: 21:00 -> 07:00 is 9 real hours.
        val w = assertNotNull(ScheduleMath.windowAt(at(berlin, 2026, 3, 29, 1, 0), nightCfg, berlin, noSun))
        assertEquals(Duration.ofHours(9), Duration.between(w.start, w.end))
        assertEquals(at(berlin, 2026, 3, 29, 7, 0), w.end)
        // America/New_York springs forward 2026-03-08.
        val ny = assertNotNull(ScheduleMath.windowAt(at(newYork, 2026, 3, 8, 4, 0), nightCfg, newYork, noSun))
        assertEquals(Duration.ofHours(9), Duration.between(ny.start, ny.end))
    }

    @Test
    fun dstFallBackBerlinAndNewYork() {
        // Europe/Berlin falls back 2026-10-25 03:00 -> 02:00: the night is 11 real hours.
        val w = assertNotNull(ScheduleMath.windowAt(at(berlin, 2026, 10, 25, 5, 0), nightCfg, berlin, noSun))
        assertEquals(Duration.ofHours(11), Duration.between(w.start, w.end))
        // America/New_York falls back 2026-11-01.
        val ny = assertNotNull(ScheduleMath.windowAt(at(newYork, 2026, 11, 1, 5, 0), nightCfg, newYork, noSun))
        assertEquals(Duration.ofHours(11), Duration.between(ny.start, ny.end))
    }

    @Test
    fun startInsideTheSpringGapMovesForward() {
        // 02:30 does not exist in Berlin on 2026-03-29; java.time moves it to 03:30 CEST.
        val cfg = nightCfg.copy(startMinute = 2 * 60 + 30, endMinute = 6 * 60)
        val w = assertNotNull(ScheduleMath.windowStartingOn(java.time.LocalDate.of(2026, 3, 29), cfg, berlin, noSun))
        assertEquals(at(berlin, 2026, 3, 29, 3, 30), w.start)
        assertEquals(Duration.ofMinutes(150), Duration.between(w.start, w.end))
    }

    @Test
    fun ambiguousFallBackStartUsesTheEarlierOffset() {
        // 02:30 happens twice in Berlin on 2026-10-25; the first (CEST, UTC+2) is used.
        val cfg = nightCfg.copy(startMinute = 2 * 60 + 30, endMinute = 6 * 60)
        val w = assertNotNull(ScheduleMath.windowStartingOn(java.time.LocalDate.of(2026, 10, 25), cfg, berlin, noSun))
        assertEquals(Instant.parse("2026-10-25T00:30:00Z"), w.start)
        assertEquals(Duration.ofMinutes(270), Duration.between(w.start, w.end))
    }

    @Test
    fun fadeFractions() {
        val start = at(berlin, 2026, 10, 1, 21, 0)
        assertEquals(1f / 3f, ScheduleMath.fadeFraction(start.plusSeconds(600), start, 30), 1e-4f)
        assertEquals(0f, ScheduleMath.fadeFraction(start, start, 30), 0f)
        assertEquals(0f, ScheduleMath.fadeFraction(start.minusSeconds(60), start, 30), 0f)
        assertEquals(1f, ScheduleMath.fadeFraction(start.plusSeconds(3600), start, 30), 0f)
        assertEquals(1f, ScheduleMath.fadeFraction(start, start, 0), 0f)
        assertEquals(0.5f, ScheduleMath.fadeFraction(start.plusSeconds(3600), start, 120), 1e-4f)
    }

    @Test
    fun fadeAcrossTheDstJumpUsesRealTime() {
        // A 60-minute fade starting 01:30 CET on the spring-forward night: at 03:00 CEST (= 02:00
        // CET, 30 real minutes later) it is half done, although the wall clock moved 90 minutes.
        val start = at(berlin, 2026, 3, 29, 1, 30)
        val now = at(berlin, 2026, 3, 29, 3, 0)
        assertEquals(0.5f, ScheduleMath.fadeFraction(now, start, 60), 1e-4f)
    }

    @Test
    fun sunsetToClockEndWithOffset() {
        val cfg = ScheduleConfig(enabled = true, startKind = StartKind.SUNSET, endMinute = 7 * 60, sunOffsetMin = -30)
        val date = java.time.LocalDate.of(2026, 6, 21)
        val w = assertNotNull(ScheduleMath.windowStartingOn(date, cfg, berlin, berlinSun))
        val sunset = SunCalc.sunTimes(date, 52.5, 13.3667, berlin).sunset!!
        assertEquals(sunset.minusSeconds(1800), w.start)
        assertEquals(at(berlin, 2026, 6, 22, 7, 0), w.end)
        assertEquals(false, w.polarFallback)
    }

    @Test
    fun sunsetToSunriseCrossesMidnight() {
        val cfg = ScheduleConfig(enabled = true, startKind = StartKind.SUNSET, endKind = EndKind.SUNRISE)
        val w = assertNotNull(ScheduleMath.windowAt(at(berlin, 2026, 12, 21, 23, 0), cfg, berlin, berlinSun))
        val local = w.end.atZone(berlin)
        assertEquals(22, local.dayOfMonth)
        assertTrue(local.hour == 8, "Berlin sunrise on 22 Dec at ${local.toLocalTime()}")
    }

    @Test
    fun polarFallsBackTo21And7() {
        val zone = ZoneId.of("Arctic/Longyearbyen")
        val sun = sunAt(78.0, 16.0, zone)
        val cfg = ScheduleConfig(enabled = true, startKind = StartKind.SUNSET, endKind = EndKind.SUNRISE)
        val w = assertNotNull(ScheduleMath.windowStartingOn(java.time.LocalDate.of(2026, 6, 21), cfg, zone, sun))
        assertTrue(w.polarFallback)
        assertEquals(LocalDateTime.of(2026, 6, 21, 21, 0), w.start.atZone(zone).toLocalDateTime())
        assertEquals(LocalDateTime.of(2026, 6, 22, 7, 0), w.end.atZone(zone).toLocalDateTime())
    }

    @Test
    fun sanitizeClampsValues() {
        val s = ScheduleConfig(startMinute = -5, endMinute = 5000, sunOffsetMin = 200, fadeMinutes = 7).sanitized()
        assertEquals(0, s.startMinute)
        assertEquals(1439, s.endMinute)
        assertEquals(60, s.sunOffsetMin)
        assertEquals(30, s.fadeMinutes)
    }
}
