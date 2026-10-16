package com.plainapps.dimmer.engine

/**
 * Timed pause rules (v3 P4, SPEC 4). A pause hides the overlay and releases the backlight but
 * keeps dimming "on"; it is memory-only (a restart ends it together with `enabled`).
 * [startMs] is kept so that a clock moved backwards can never stretch a pause.
 */
data class PauseWindow(val startMs: Long, val untilMs: Long) {

    /**
     * True while now is inside [startMs]..[untilMs). The clock moved back to before the start
     * (beyond [Pause.CLOCK_SLACK_MS], so an NTP correction is harmless) ends the pause; the
     * clock moved forward past the deadline ends it too (plan check "move the clock +16 min").
     */
    fun activeAt(nowMs: Long): Boolean = nowMs < untilMs && nowMs >= startMs - Pause.CLOCK_SLACK_MS
}

object Pause {
    /** Choices offered by the app; the notification offers [NOTIFICATION_MINUTES]. */
    val CHOICES_MINUTES = listOf(15, 30, 60)
    const val NOTIFICATION_MINUTES = 15

    /** Longest pause automation may ask for (12 h). */
    const val MAX_MINUTES = 720
    const val CLOCK_SLACK_MS = 120_000L

    fun clampMinutes(minutes: Int): Int = minutes.coerceIn(1, MAX_MINUTES)

    fun start(nowMs: Long, minutes: Int): PauseWindow = PauseWindow(nowMs, nowMs + clampMinutes(minutes) * 60_000L)

    fun active(pause: PauseWindow?, nowMs: Long): Boolean = pause?.activeAt(nowMs) == true
}
