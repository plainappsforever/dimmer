package com.plainapps.dimmer.engine

import java.time.Instant

/** Buttons the notification can show (at most three per state, D-V3-07). */
enum class NotificationAction { LIGHTER, DARKER, PAUSE, RESUME, OFF, TURN_ON }

/**
 * What the one Dimmer notification shows (v3 P4, SPEC 5 + SPEC 3). Pure, so every state is
 * JVM-tested; the service turns it into strings and PendingIntents. Equal plans = no re-post,
 * so a notification the user dismissed comes back only on the next real change.
 */
sealed interface NotificationPlan {
    val actions: List<NotificationAction>

    /** False = the user can swipe it away on every Android version (still true on 14+ for all). */
    val ongoing: Boolean

    data object None : NotificationPlan {
        override val actions = emptyList<NotificationAction>()
        override val ongoing = false
    }

    /** Dimming visible. [presetName] null = Custom. [status] = the schedule line (fade / until), or None. */
    data class On(val presetName: String?, val darkness: Int, val status: Resolver.Status) : NotificationPlan {
        override val actions = listOf(NotificationAction.LIGHTER, NotificationAction.DARKER, NotificationAction.PAUSE)
        override val ongoing = true
    }

    /** Dimming on but paused until [until]. */
    data class Paused(val until: Instant) : NotificationPlan {
        override val actions = listOf(NotificationAction.RESUME, NotificationAction.OFF)
        override val ongoing = true
    }

    /**
     * The user (or a restart) turned dimming off inside a scheduled window: "Off · schedule
     * resumes at 21:30 tomorrow" (SPEC 3). Only while that window lasts; silent and dismissible.
     */
    data class ScheduleOff(val resumesAt: Instant, val windowEnd: Instant) : NotificationPlan {
        override val actions = listOf(NotificationAction.TURN_ON)
        override val ongoing = false
    }

    companion object {
        /**
         * [status] = Resolver.status for now; [windowEnd] = end of the scheduled window that
         * contains now (null = outside). A stored pause wins while on (its expiry is cleared by
         * the schedule evaluation, so all surfaces agree); off shows something only in a
         * suppressed window. [darkness] is the stored (target) value, never the faded one, so a
         * fade does not re-post the notification every few seconds.
         */
        fun decide(
            enabled: Boolean,
            pause: PauseWindow?,
            presetName: String?,
            darkness: Int,
            status: Resolver.Status,
            windowEnd: Instant?,
        ): NotificationPlan = when {
            enabled && pause != null -> Paused(Instant.ofEpochMilli(pause.untilMs))
            enabled -> On(
                presetName,
                darkness,
                if (status is Resolver.Status.FadingIn || status is Resolver.Status.Until) status else Resolver.Status.None,
            )
            windowEnd != null && status is Resolver.Status.ResumesAt -> ScheduleOff(status.next, windowEnd)
            else -> None
        }
    }
}
