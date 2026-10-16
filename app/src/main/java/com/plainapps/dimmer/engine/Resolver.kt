package com.plainapps.dimmer.engine

import java.time.Duration
import java.time.Instant
import kotlin.math.roundToInt

/** Who turned dimming on (memory only). SCHEDULE = the fade applies. */
enum class DimSource { MANUAL, SCHEDULE }

/**
 * Memory-only schedule bookkeeping (v1 safety rule: nothing here survives a restart).
 * [seenWindowStart] = start of the window the last evaluation was inside (null = outside);
 * [evaluated] = at least one evaluation ran in this process (false = we just (re)started).
 */
data class ScheduleRuntime(
    val enabled: Boolean = false,
    val source: DimSource = DimSource.MANUAL,
    val fadeStart: Instant? = null,
    val seenWindowStart: Instant? = null,
    val evaluated: Boolean = false,
)

/**
 * Pure schedule decisions (V3_PLAN "Resolver", injected clock). The service asks it on every
 * event; [DimmerCommands][com.plainapps.dimmer.DimmerCommands] applies the result. JVM-tested.
 */
object Resolver {

    enum class Action {
        /** Nothing changed. */
        NONE,

        /** A new window started (or we entered one): dimming on, target preset, fade from window start. */
        TURN_ON,

        /** Entered a window while already on (manual): keep the look; it will end with the window. */
        ADOPT,

        /** The window we were in ended: off, whoever turned it on. */
        TURN_OFF_END,

        /** First evaluation after a restart, inside a window, resume OFF: off until the next start. */
        SUPPRESS_RESTART,

        /** Inside a new window but the user turned it off earlier ("off until next start"). */
        STAY_SUPPRESSED,

        /** The schedule is switched off: a running schedule session becomes a manual one. */
        SCHEDULE_DISABLED,
    }

    data class Step(
        val runtime: ScheduleRuntime,
        val suppressedUntil: Instant?,
        val action: Action,
    )

    /**
     * One evaluation at [now]. [window] = the window containing now (null if none), [nextStart] =
     * the next window start after now, [suppressedUntil] = the persisted "off until" (expired values
     * are dropped here).
     */
    fun step(
        now: Instant,
        cfg: ScheduleConfig,
        window: ScheduleWindow?,
        nextStart: Instant?,
        suppressedUntil: Instant?,
        runtime: ScheduleRuntime,
    ): Step {
        val suppressed = validSuppression(suppressedUntil, now, nextStart)
        if (!cfg.enabled) {
            val action = if (runtime.enabled && runtime.source == DimSource.SCHEDULE) Action.SCHEDULE_DISABLED else Action.NONE
            return Step(
                runtime.copy(source = DimSource.MANUAL, fadeStart = null, seenWindowStart = null, evaluated = true),
                null,
                action,
            )
        }
        if (window == null) {
            val ending = runtime.seenWindowStart != null && runtime.enabled
            val rt = runtime.copy(
                enabled = runtime.enabled && !ending,
                source = if (ending) DimSource.MANUAL else runtime.source,
                fadeStart = if (ending) null else runtime.fadeStart,
                seenWindowStart = null,
                evaluated = true,
            )
            return Step(rt, suppressed, if (ending) Action.TURN_OFF_END else Action.NONE)
        }
        if (runtime.seenWindowStart == window.start) {
            return Step(runtime.copy(evaluated = true), suppressed, Action.NONE)
        }
        // A window this process has not acted on yet: it just started, we just (re)started,
        // the schedule was just switched on, or the clock jumped into it.
        val base = runtime.copy(seenWindowStart = window.start, evaluated = true)
        return when {
            // Already on (manual, or the tile before our first evaluation): never suppress it.
            runtime.enabled -> Step(base, null, Action.ADOPT)
            suppressed != null -> Step(base, suppressed, Action.STAY_SUPPRESSED)
            !runtime.evaluated && !cfg.resumeAfterRestart ->
                Step(base, nextStart, Action.SUPPRESS_RESTART)
            else -> Step(
                base.copy(enabled = true, source = DimSource.SCHEDULE, fadeStart = window.start),
                null,
                Action.TURN_ON,
            )
        }
    }

    /** Tolerance when matching a stored "off until" against the computed next start. */
    const val SUPPRESSION_SLACK_SECONDS = 60L

    /**
     * The persisted "off until next start" is honoured only while it still IS the next start:
     * in the future and within [SUPPRESSION_SLACK_SECONDS] of [nextStart]. A clock or zone moved
     * backwards (it would otherwise cover several windows) or an expired value drops it.
     */
    fun validSuppression(suppressedUntil: Instant?, now: Instant, nextStart: Instant?): Instant? {
        if (suppressedUntil == null || !suppressedUntil.isAfter(now) || nextStart == null) return null
        val diff = Duration.between(suppressedUntil, nextStart).abs().seconds
        return if (diff <= SUPPRESSION_SLACK_SECONDS) suppressedUntil else null
    }

    /**
     * The user edited the schedule. If we were inside a window under the old settings and still
     * are under the new ones, it is the same evening: carry "seen" and "off until next start" over
     * to the new window, so an edit neither switches dimming on mid-window nor loses the user's Off.
     * Returns the runtime and suppression to store before the next [step].
     */
    fun remap(
        now: Instant,
        oldWindow: ScheduleWindow?,
        oldSuppressed: Instant?,
        newWindow: ScheduleWindow?,
        newNextStart: Instant?,
        runtime: ScheduleRuntime,
    ): Pair<ScheduleRuntime, Instant?> {
        if (oldWindow != null && newWindow != null && runtime.seenWindowStart == oldWindow.start) {
            val suppressed = if (oldSuppressed != null && newNextStart != null && newNextStart.isAfter(now)) newNextStart else null
            return runtime.copy(seenWindowStart = newWindow.start) to suppressed
        }
        // Outside the new window, a suppression of the old evening means nothing any more.
        return runtime to (if (newWindow == null) null else oldSuppressed)
    }

    /**
     * Darkness and tint strength scaled by the fade [fraction] (0..1); stored values stay untouched.
     * Once visible, darkness never rounds to 0 (that would release the backlight and restart it).
     */
    fun faded(darkness: Int, tintStrength: Int, fraction: Float): Pair<Int, Int> {
        val f = fraction.coerceIn(0f, 1f)
        val d = (darkness * f).roundToInt().let { if (darkness > 0) it.coerceAtLeast(1) else it }
        return d to (tintStrength * f).roundToInt()
    }

    /** What the status line says. */
    sealed interface Status {
        data object None : Status
        data class FadingIn(val until: Instant) : Status
        data class Until(val end: Instant) : Status
        data class ResumesAt(val next: Instant) : Status
        data class StartsAt(val next: Instant) : Status

        /** v3 P4: dimming on but paused ("Paused · resumes 23:15"). */
        data class Paused(val until: Instant) : Status
    }

    fun status(
        now: Instant,
        cfg: ScheduleConfig,
        window: ScheduleWindow?,
        nextStart: Instant?,
        suppressedUntil: Instant?,
        enabled: Boolean,
        source: DimSource,
        fadeStart: Instant?,
        pausedUntil: Instant? = null,
    ): Status {
        // A pause is shown whether or not a schedule exists (only an active pause is passed in).
        if (enabled && pausedUntil != null) return Status.Paused(pausedUntil)
        if (!cfg.enabled) return Status.None
        if (enabled) {
            if (source == DimSource.SCHEDULE && fadeStart != null && cfg.fadeMinutes > 0) {
                val fadeEnd = fadeStart.plus(Duration.ofMinutes(cfg.fadeMinutes.toLong()))
                if (fadeEnd.isAfter(now)) return Status.FadingIn(fadeEnd)
            }
            return if (window != null) Status.Until(window.end) else Status.None
        }
        val suppressed = validSuppression(suppressedUntil, now, nextStart)
        if (suppressed != null) return Status.ResumesAt(suppressed)
        return if (nextStart != null) Status.StartsAt(nextStart) else Status.None
    }
}
