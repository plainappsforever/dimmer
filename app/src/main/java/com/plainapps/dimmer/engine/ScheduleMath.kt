package com.plainapps.dimmer.engine

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

enum class StartKind { CLOCK, SUNSET }

enum class EndKind { CLOCK, SUNRISE }

/** The one daily schedule (D-V3-15). Minutes are minutes after local midnight (0..1439). */
data class ScheduleConfig(
    val enabled: Boolean = false,
    val startKind: StartKind = StartKind.CLOCK,
    val startMinute: Int = 21 * 60,
    val endKind: EndKind = EndKind.CLOCK,
    val endMinute: Int = 7 * 60,
    /** Shifts sunset AND sunrise (it corrects the time-zone location error, which moves both). */
    val sunOffsetMin: Int = 0,
    val fadeMinutes: Int = 30,
    val presetId: String = "candlelight",
    val resumeAfterRestart: Boolean = false,
) {
    fun sanitized() = copy(
        startMinute = startMinute.coerceIn(0, MINUTES_PER_DAY - 1),
        endMinute = endMinute.coerceIn(0, MINUTES_PER_DAY - 1),
        sunOffsetMin = sunOffsetMin.coerceIn(ScheduleMath.OFFSET_MIN, ScheduleMath.OFFSET_MAX),
        fadeMinutes = if (fadeMinutes in ScheduleMath.FADE_CHOICES) fadeMinutes else 30,
    )

    val usesSun: Boolean get() = startKind == StartKind.SUNSET || endKind == EndKind.SUNRISE

    companion object {
        const val MINUTES_PER_DAY = 24 * 60
    }
}

/** One scheduled window [start, end). [polarFallback]: a sun time did not exist, 21:00/07:00 used. */
data class ScheduleWindow(
    val start: Instant,
    val end: Instant,
    val fadeEnd: Instant,
    val polarFallback: Boolean,
)

/** Sun times of a local date (production: [SunCalc] at the zone's [ZoneLocation], cached). */
fun interface SunSource {
    fun on(date: LocalDate): SunCalc.SunTimes
}

/**
 * Window maths for the daily schedule. java.time does the calendar work, so DST days are right by
 * construction: 21:00 → 07:00 across a spring-forward night is 9 real hours. A clock time that
 * does not exist on a DST day (02:30) moves forward by the gap, like an alarm clock.
 */
object ScheduleMath {

    val FADE_CHOICES = listOf(0, 15, 30, 60, 120, 180)
    const val OFFSET_MIN = -60
    const val OFFSET_MAX = 60
    const val POLAR_START_MINUTE = 21 * 60
    const val POLAR_END_MINUTE = 7 * 60

    private fun atMinute(date: LocalDate, minute: Int, zone: ZoneId): Instant =
        ZonedDateTime.of(date, LocalTime.of(minute / 60, minute % 60), zone).toInstant()

    /** Start instant for local date [date], and whether the polar fallback was used. */
    fun startOn(date: LocalDate, cfg: ScheduleConfig, zone: ZoneId, sun: SunSource): Pair<Instant, Boolean> =
        when (cfg.startKind) {
            StartKind.CLOCK -> atMinute(date, cfg.startMinute, zone) to false
            StartKind.SUNSET -> {
                val set = sun.on(date).sunset
                if (set == null) atMinute(date, POLAR_START_MINUTE, zone) to true
                else set.plusSeconds(cfg.sunOffsetMin * 60L) to false
            }
        }

    /** End candidate on local date [date], and whether the polar fallback was used. */
    fun endOn(date: LocalDate, cfg: ScheduleConfig, zone: ZoneId, sun: SunSource): Pair<Instant, Boolean> =
        when (cfg.endKind) {
            EndKind.CLOCK -> atMinute(date, cfg.endMinute, zone) to false
            EndKind.SUNRISE -> {
                val rise = sun.on(date).sunrise
                if (rise == null) atMinute(date, POLAR_END_MINUTE, zone) to true
                else rise.plusSeconds(cfg.sunOffsetMin * 60L) to false
            }
        }

    /**
     * The window that starts on local date [date]: it ends at the first end candidate after its
     * start (same day, or the next day when the window crosses midnight). Null when start and end
     * are the same clock time (an empty schedule, shown as such in the UI).
     */
    fun windowStartingOn(date: LocalDate, cfg: ScheduleConfig, zone: ZoneId, sun: SunSource): ScheduleWindow? {
        if (cfg.startKind == StartKind.CLOCK && cfg.endKind == EndKind.CLOCK && cfg.startMinute == cfg.endMinute) {
            return null
        }
        val (start, polarStart) = startOn(date, cfg, zone, sun)
        var (end, polarEnd) = endOn(date, cfg, zone, sun)
        if (!end.isAfter(start)) {
            val next = endOn(date.plusDays(1), cfg, zone, sun)
            end = next.first
            polarEnd = next.second
        }
        if (!end.isAfter(start)) return null
        return ScheduleWindow(
            start = start,
            end = end,
            fadeEnd = start.plus(Duration.ofMinutes(cfg.fadeMinutes.toLong())),
            polarFallback = polarStart || polarEnd,
        )
    }

    /** The window containing [now] (start inclusive, end exclusive), or null. Ignores [ScheduleConfig.enabled]. */
    fun windowAt(now: Instant, cfg: ScheduleConfig, zone: ZoneId, sun: SunSource): ScheduleWindow? {
        val today = now.atZone(zone).toLocalDate()
        var best: ScheduleWindow? = null
        for (d in -2L..1L) {
            val w = windowStartingOn(today.plusDays(d), cfg, zone, sun) ?: continue
            if (!now.isBefore(w.start) && now.isBefore(w.end)) {
                if (best == null || w.start.isAfter(best.start)) best = w
            }
        }
        return best
    }

    /** The first window start strictly after [now], or null (empty schedule). */
    fun nextStartAfter(now: Instant, cfg: ScheduleConfig, zone: ZoneId, sun: SunSource): Instant? {
        val today = now.atZone(zone).toLocalDate()
        var best: Instant? = null
        for (d in -1L..3L) {
            val w = windowStartingOn(today.plusDays(d), cfg, zone, sun) ?: continue
            if (w.start.isAfter(now) && (best == null || w.start.isBefore(best))) best = w.start
        }
        return best
    }

    /**
     * Fade progress 0..1 at [now] for a fade that began at [fadeStart] and lasts [fadeMinutes]
     * (0 = no fade, always 1).
     */
    fun fadeFraction(now: Instant, fadeStart: Instant, fadeMinutes: Int): Float {
        if (fadeMinutes <= 0) return 1f
        val elapsed = Duration.between(fadeStart, now).toMillis().toDouble()
        val total = fadeMinutes * 60_000.0
        return (elapsed / total).coerceIn(0.0, 1.0).toFloat()
    }
}
