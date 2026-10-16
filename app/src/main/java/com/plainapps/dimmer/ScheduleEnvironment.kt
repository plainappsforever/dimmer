package com.plainapps.dimmer

import com.plainapps.dimmer.engine.ScheduleConfig
import com.plainapps.dimmer.engine.ScheduleMath
import com.plainapps.dimmer.engine.ScheduleWindow
import com.plainapps.dimmer.engine.SunCalc
import com.plainapps.dimmer.engine.SunSource
import com.plainapps.dimmer.engine.ZoneLocation
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The Android side of the schedule: the clock, the device time zone, the zone's sun location
 * (ICU canonical IDs, D-V3-04) and cached sun times. Everything it computes comes from the pure
 * engine (ScheduleMath, SunCalc, ZoneLocation). Thread-safe: small synchronized caches.
 */
class ScheduleEnvironment(private val clock: () -> Instant = Instant::now) {

    fun now(): Instant = clock()

    /** Android resets the process default zone on ACTION_TIMEZONE_CHANGED, so this follows it. */
    fun zone(): ZoneId = ZoneId.systemDefault()

    private val locations = HashMap<String, ZoneLocation>()
    private val sunCache = LinkedHashMap<Pair<String, LocalDate>, SunCalc.SunTimes>()

    fun location(zone: ZoneId): ZoneLocation = synchronized(locations) {
        locations.getOrPut(zone.id) {
            val std = zone.rules.getStandardOffset(now()).totalSeconds
            ZoneLocation.of(zone.id, std) { id ->
                runCatching { android.icu.util.TimeZone.getCanonicalID(id) }.getOrNull()
            }
        }
    }

    fun sun(zone: ZoneId): SunSource {
        val loc = location(zone)
        return SunSource { date ->
            synchronized(sunCache) {
                sunCache.getOrPut(zone.id to date) {
                    if (sunCache.size > 32) sunCache.remove(sunCache.keys.first())
                    SunCalc.sunTimes(date, loc.latitude, loc.longitude, zone)
                }
            }
        }
    }

    /** Current window and next start under [cfg] (both null when the schedule is off). */
    fun evaluate(now: Instant, cfg: ScheduleConfig): Pair<ScheduleWindow?, Instant?> {
        if (!cfg.enabled) return null to null
        val zone = zone()
        val sun = sun(zone)
        return ScheduleMath.windowAt(now, cfg, zone, sun) to ScheduleMath.nextStartAfter(now, cfg, zone, sun)
    }

    /** For the settings screen: today's window (start today, or the one running now) and today's sun. */
    data class Info(
        val window: ScheduleWindow?,
        val sunrise: Instant?,
        val sunset: Instant?,
        val polar: SunCalc.Polar?,
        val location: ZoneLocation,
        val zone: ZoneId,
    )

    fun info(cfg: ScheduleConfig): Info {
        val zone = zone()
        val now = now()
        val sun = sun(zone)
        val today = now.atZone(zone).toLocalDate()
        val window = ScheduleMath.windowAt(now, cfg, zone, sun)
            ?: ScheduleMath.windowStartingOn(today, cfg, zone, sun)
        val s = sun.on(today)
        return Info(window, s.sunrise, s.sunset, s.polar, location(zone), zone)
    }
}
