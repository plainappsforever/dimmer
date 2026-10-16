package com.plainapps.dimmer

import android.util.Log
import com.plainapps.dimmer.backlight.Backlight
import com.plainapps.dimmer.backlight.BacklightMethod
import com.plainapps.dimmer.engine.AmbientState
import com.plainapps.dimmer.engine.AutomationCommand
import com.plainapps.dimmer.engine.DimSource
import com.plainapps.dimmer.engine.NotificationPlan
import com.plainapps.dimmer.engine.Pause
import com.plainapps.dimmer.engine.Preset
import com.plainapps.dimmer.engine.Presets
import com.plainapps.dimmer.engine.Resolver
import com.plainapps.dimmer.engine.ScheduleConfig
import com.plainapps.dimmer.engine.ScheduleMath
import com.plainapps.dimmer.engine.ShakeSensitivity
import com.plainapps.dimmer.engine.Tint
import java.time.Instant

/**
 * The ONLY way anything changes dimmer state (v3 P2): UI, Quick Settings tile, notification
 * action and, in later phases, widget, shortcuts, automation and shake all call these. It keeps
 * the rules in one place: moving a value by hand makes the look "Custom", applying a preset
 * turns dimming on, and a tint change never silently keeps a preset name that no longer matches.
 */
class DimmerCommands(
    private val repository: DimmerStore,
    /** Is the accessibility service enabled? (Android: DimmerAccessibilityService.isEnabled.) */
    private val accessGranted: () -> Boolean,
    val schedule: ScheduleEnvironment = ScheduleEnvironment(),
    /** Monotonic clock for the automation lockout (a moved wall clock must not end or stretch it). */
    private val monotonicMs: () -> Long = { System.nanoTime() / 1_000_000 },
    /** v3.1.1: the name the notification shows for a preset (translated defaults; Android: Context.presetLabel). */
    private val presetLabel: (Preset) -> String = { it.name },
) {
    val state get() = repository.state

    /**
     * v3.1 (REVIEW_V3 finding 5): when the user last turned dimming off by hand (tile, notification,
     * shake, app, widget, shortcut), monotonic ms; null = no lockout. Memory-only.
     */
    @Volatile private var userOffAtMs: Long? = null

    /**
     * v3.1: the darkness the user last chose by hand (slider, notification Lighter/Darker, a preset
     * picked by hand, the schedule's preset). Automation without an explicit value (DARKER, ON,
     * TOGGLE) never takes darkness above it. Memory-only; first set from the stored value.
     */
    @Volatile private var userDarkness: Int? = null

    private fun noteUserDarkness(reason: String) {
        if (reason != AUTOMATION) userDarkness = repository.state.value.darkness
    }

    /** Milliseconds left of the automation lockout after a user Off (0 = none). */
    fun automationLockoutLeftMs(): Long {
        val at = userOffAtMs ?: return 0L
        val left = AUTOMATION_LOCKOUT_MS - (monotonicMs() - at)
        return if (left in 1..AUTOMATION_LOCKOUT_MS) left else 0L
    }

    // --- on / off ---

    /**
     * Turns dimming on (a user action). False (and no change) when Accessibility access is not
     * granted. Clears "off until the next scheduled start" (SPEC 3). While paused it resumes (P4).
     */
    fun turnOn(reason: String = "manual"): Boolean {
        if (!accessGranted()) return false
        // The user turning it on again by hand ends the automation lockout at once.
        if (reason != AUTOMATION) userOffAtMs = null
        val (old, new) = repository.update {
            if (it.enabled) {
                it.copy(pause = null)
            } else {
                it.copy(enabled = true, source = DimSource.MANUAL, fadeStartMs = null, suppressedUntilMs = 0L, pause = null)
            }
        }
        if (!old.enabled && new.enabled) Log.i(Backlight.LOG_TAG, "turn on ($reason)")
        if (old.pause != null && new.pause == null) Log.i(Backlight.LOG_TAG, "resume ($reason)")
        return true
    }

    /**
     * Turns dimming off (a user action: app, tile, notification, shake, automation). Inside a
     * scheduled window this means "off until the next scheduled start" (SPEC 3, D-V3-15).
     * Ends a pause too (repository invariant).
     */
    fun turnOff(reason: String = "manual") {
        val now = schedule.now()
        if (reason != AUTOMATION) userOffAtMs = monotonicMs()
        val (old, new) = repository.update { s ->
            val (window, next) = schedule.evaluate(now, s.schedule)
            val base = s.copy(enabled = false, source = DimSource.MANUAL, fadeStartMs = null)
            if (window != null && next != null) {
                base.copy(suppressedUntilMs = next.toEpochMilli(), seenWindowStartMs = window.start.toEpochMilli())
            } else {
                base
            }
        }
        if (old.enabled) {
            Log.i(Backlight.LOG_TAG, "turn off ($reason) suppressedUntil=${fmt(new.suppressedUntilMs)}")
        }
    }

    /**
     * Dimming stopped by the system (accessibility unbound, overlay could not be shown). Never
     * suppresses the schedule: a reboot's unbind must not block "resume after restart", and a
     * re-bind inside a window lets the schedule bring dimming back. Returns true when dimming was
     * on (the service then posts the "Dimmer stopped" notice, SPEC 7).
     */
    fun stopBySystem(reason: String): Boolean {
        val (old, _) = repository.update {
            it.copy(enabled = false, source = DimSource.MANUAL, fadeStartMs = null, seenWindowStartMs = null, ambientState = AmbientState.NORMAL)
        }
        if (old.enabled) Log.i(Backlight.LOG_TAG, "stopped by system ($reason)")
        return old.enabled
    }

    /** Returns the new "on" state. A paused dimmer counts as on: toggle turns it off. */
    fun toggle(reason: String = "manual"): Boolean {
        if (repository.state.value.enabled) {
            turnOff(reason)
            return false
        }
        return turnOn(reason)
    }

    // --- pause (v3 P4) ---

    /**
     * Pauses dimming for [minutes] (clamped 1..720): overlay hidden, backlight restored, dimming
     * stays on and comes back by itself (the service calls [checkPause]). No-op while off.
     * A running schedule fade keeps running in real time, so it resumes at the current fade point.
     */
    fun pause(minutes: Int, reason: String = "manual"): Boolean {
        val now = schedule.now().toEpochMilli()
        val (_, new) = repository.update { if (it.enabled) it.copy(pause = Pause.start(now, minutes)) else it }
        val p = new.pause ?: return false
        Log.i(Backlight.LOG_TAG, "pause ${Pause.clampMinutes(minutes)} min ($reason) until=${fmt(p.untilMs)}")
        return true
    }

    fun resume(reason: String = "manual") {
        val (old, new) = repository.update { it.copy(pause = null) }
        if (old.pause != null && new.pause == null) Log.i(Backlight.LOG_TAG, "resume ($reason)")
    }

    /** Pause expiry, applied inside [evaluateSchedule]'s atomic update (L13 review P4 #3). */
    private fun expirePause(s: DimmerState, nowMs: Long): DimmerState =
        if (s.pause != null && !Pause.active(s.pause, nowMs)) s.copy(pause = null) else s

    /** A look change by hand (app, notification, automation) resumes a pause: you want to see it. */
    private fun lookChange(reason: String, transform: (DimmerState) -> DimmerState): Pair<DimmerState, DimmerState> {
        val (old, new) = repository.update { transform(bakeFade(it)).copy(pause = null) }
        if (old.pause != null) Log.i(Backlight.LOG_TAG, "resume ($reason: look changed)")
        return old to new
    }

    // --- the look: any manual change makes it Custom ---

    fun setDarkness(darkness: Int) {
        lookChange("manual") { it.copy(darkness = darkness, activePresetId = null) }
        noteUserDarkness("manual")
    }

    /**
     * Notification / automation Lighter (negative) and Darker (positive): from the look on screen.
     * Automation Darker stops at the user's own last darkness (v3.1, finding 5) - it never makes
     * the screen darker than the user chose without an explicit value (SET / PRESET).
     */
    fun adjustDarkness(delta: Int, reason: String = "manual") {
        val ceiling = if (reason == AUTOMATION && delta > 0) userDarkness else null
        val (old, new) = lookChange(reason) {
            val wanted = it.darkness + delta
            val d = if (ceiling != null) minOf(wanted, maxOf(it.darkness, ceiling)) else wanted
            it.copy(darkness = d, activePresetId = null)
        }
        noteUserDarkness(reason)
        val capped = if (ceiling != null && new.darkness < old.darkness + delta && new.darkness < 100) " (capped at the user's darkness $ceiling)" else ""
        Log.i(Backlight.LOG_TAG, "darkness ${if (delta >= 0) "+" else ""}$delta ($reason): ${old.darkness} -> ${new.darkness}$capped")
    }

    fun setTint(tint: Tint) {
        lookChange("manual") { it.copy(tint = tint, activePresetId = null) }
    }

    fun setTintStrength(strength: Int) {
        lookChange("manual") { it.copy(tintStrength = strength, activePresetId = null) }
    }

    /**
     * A running schedule fade ends when the user takes over: the look on screen right now becomes
     * the stored look (no jump), and the session becomes a manual one.
     */
    private fun bakeFade(s: DimmerState): DimmerState {
        if (!s.enabled || s.source != DimSource.SCHEDULE) return s
        val start = s.fadeStartMs ?: return s.copy(source = DimSource.MANUAL)
        val f = ScheduleMath.fadeFraction(schedule.now(), Instant.ofEpochMilli(start), s.schedule.fadeMinutes)
        val (d, t) = Resolver.faded(s.darkness, s.tintStrength, f)
        return s.copy(darkness = d, tintStrength = t, source = DimSource.MANUAL, fadeStartMs = null)
    }

    /** Effective (darkness, tint strength) to render now: the fade applied to a schedule session. */
    fun renderedLook(s: DimmerState, now: Instant = schedule.now()): Triple<Int, Int, Float> {
        val start = s.fadeStartMs
        if (!s.enabled || s.source != DimSource.SCHEDULE || start == null) return Triple(s.darkness, s.tintStrength, 1f)
        val f = ScheduleMath.fadeFraction(now, Instant.ofEpochMilli(start), s.schedule.fadeMinutes)
        val (d, t) = Resolver.faded(s.darkness, s.tintStrength, f)
        return Triple(d, t, f)
    }

    // --- presets ---

    /** Applies a preset's values; with [turnOn] also switches dimming on. False if the id is unknown. */
    fun applyPreset(id: String, turnOn: Boolean = true, reason: String = "manual"): Boolean {
        val preset = Presets.find(repository.state.value.presets, id) ?: return false
        repository.update {
            it.copy(
                darkness = preset.darkness,
                tint = preset.tint,
                tintStrength = preset.tintStrength,
                activePresetId = preset.id,
                // A preset picked by hand ends a schedule fade.
                source = DimSource.MANUAL,
                fadeStartMs = null,
            )
        }
        noteUserDarkness(reason)
        if (turnOn) return turnOn(reason)
        return true
    }

    /** Saves the current look as a new preset and makes it the active one. False if full or the name is blank. */
    fun saveCurrentAsPreset(name: String): Boolean {
        var saved = false
        repository.update { s ->
            saved = false
            val preset = Presets.add(s.presets, name, s.darkness, s.tint, s.tintStrength) ?: return@update s
            saved = true
            s.copy(presets = s.presets + preset, activePresetId = preset.id)
        }
        return saved
    }

    fun renamePreset(id: String, name: String) =
        repository.update { it.copy(presets = Presets.rename(it.presets, id, name)) }

    /** Overwrites a preset with the current look and marks it active. */
    fun updatePresetToCurrent(id: String) = repository.update { s ->
        if (s.presets.none { it.id == id }) return@update s
        s.copy(
            presets = Presets.updateValues(s.presets, id, s.darkness, s.tint, s.tintStrength),
            activePresetId = id,
        )
    }

    /** Deleting the active preset keeps the values on screen as Custom. */
    fun deletePreset(id: String) = repository.update { s ->
        val presets = Presets.delete(s.presets, id)
        s.copy(
            presets = presets,
            tileCycle = Presets.reconcileCycle(s.tileCycle, presets),
            activePresetId = s.activePresetId.takeIf { it != id },
        )
    }

    fun setPresetInTileCycle(id: String, included: Boolean) = repository.update {
        it.copy(tileCycle = Presets.setInCycle(it.tileCycle, it.presets, id, included))
    }

    // --- backlight settings ---

    fun setHybridEnabled(on: Boolean) = repository.update { it.copy(hybridEnabled = on) }

    fun setBacklightMethod(method: BacklightMethod) = repository.update { it.copy(backlightMethod = method) }

    fun setKeepAwake(on: Boolean) = repository.update { it.copy(keepAwake = on) }

    // --- safety and controls settings (v3 P4) ---

    fun setShakeEnabled(on: Boolean) = repository.update { it.copy(shakeEnabled = on) }

    fun setShakeSensitivity(s: ShakeSensitivity) = repository.update { it.copy(shakeSensitivity = s) }

    // --- room light (v3 P6, SPEC 8) ---

    fun setAmbientEnabled(on: Boolean) = repository.update {
        it.copy(ambientEnabled = on, ambientState = if (on) it.ambientState else AmbientState.NORMAL)
    }

    fun setDarkRoomPreset(id: String) = repository.update { it.copy(darkRoomPresetId = id) }

    /** Called by the service when the room-light policy changes state (memory only). */
    fun setAmbientState(state: AmbientState, reason: String) {
        val (old, new) = repository.update { it.copy(ambientState = state) }
        if (old.ambientState != new.ambientState) Log.i(Backlight.LOG_TAG, "room light ${old.ambientState} -> ${new.ambientState} ($reason)")
    }

    /** DimmerApplication mirrors this onto the AutomationReceiver component (D-V3-13). */
    fun setAutomationEnabled(on: Boolean) = repository.update { it.copy(automationEnabled = on) }

    /**
     * NEXT_PRESET: Off -> first cycle preset -> ... -> last -> Off (the preset tile, the widget and NEXT_PRESET).
     */
    fun nextPreset(reason: String) {
        val s = repository.state.value
        if (s.tileCycle.isEmpty()) {
            // Nothing to cycle (the user removed every preset from the cycle): behave like the on/off tile.
            toggle(reason)
            return
        }
        val id = Presets.nextInCycle(s.tileCycle, s.enabled, s.activePresetId)
        if (id == null) turnOff(reason) else applyPreset(id, turnOn = true, reason = reason)
        Log.i(Backlight.LOG_TAG, "next preset ($reason) -> ${id ?: "off"}")
    }

    /**
     * Runs one automation command (AutomationReceiver). Returns false when it could not act
     * (e.g. ON without Accessibility access, unknown preset).
     */
    fun execute(cmd: AutomationCommand): Boolean {
        val reason = AUTOMATION
        if (userDarkness == null) userDarkness = repository.state.value.darkness
        // v3.1 (finding 5): for a minute after the user turned dimming off by hand, other apps may
        // only make it lighter or keep it off - never turn it on or change the look.
        val lockout = automationLockoutLeftMs()
        if (lockout > 0 && !allowedDuringLockout(cmd)) {
            Log.i(Backlight.LOG_TAG, "automation $cmd ignored (user turned dimming off; lockout ${(lockout + 999) / 1000} s left)")
            return false
        }
        return when (cmd) {
            AutomationCommand.On -> turnOn(reason)
            AutomationCommand.Off -> { turnOff(reason); true }
            AutomationCommand.Toggle -> { toggle(reason); true }
            is AutomationCommand.PauseFor -> pause(cmd.minutes, reason)
            AutomationCommand.Resume -> { resume(reason); true }
            is AutomationCommand.Darker -> { adjustDarkness(cmd.step, reason); true }
            is AutomationCommand.Lighter -> { adjustDarkness(-cmd.step, reason); true }
            is AutomationCommand.Set -> {
                lookChange(reason) { b ->
                    b.copy(
                        darkness = cmd.darkness ?: b.darkness,
                        tint = cmd.tint ?: b.tint,
                        tintStrength = cmd.strength ?: b.tintStrength,
                        activePresetId = null,
                    )
                }
                true
            }
            is AutomationCommand.ApplyPreset -> {
                val p = AutomationCommand.findPreset(repository.state.value.presets, cmd.nameOrId) ?: return false
                applyPreset(p.id, turnOn = true, reason = reason)
            }
            AutomationCommand.NextPreset -> { nextPreset(reason); true }
        }
    }

    /** During the lockout: Off, Lighter and Pause only (Toggle only while it would turn dimming off). */
    private fun allowedDuringLockout(cmd: AutomationCommand): Boolean = when (cmd) {
        AutomationCommand.Off, is AutomationCommand.Lighter, is AutomationCommand.PauseFor -> true
        AutomationCommand.Toggle -> repository.state.value.enabled
        else -> false
    }

    /** Memory-only nudge so the service re-checks permissions (see DimmerState.permissionEpoch). */
    fun refreshPermissions() {
        repository.update { it.copy(permissionEpoch = it.permissionEpoch + 1) }
    }

    // --- schedule (v3 P3) ---

    /**
     * Edits the schedule settings. If we are inside a window before and after the edit, "seen"
     * and "off until next start" move to the new window (Resolver.remap), so an edit neither
     * switches dimming on mid-window nor loses the user's Off. The service evaluates afterwards.
     */
    fun updateSchedule(transform: (ScheduleConfig) -> ScheduleConfig) {
        val now = schedule.now()
        val (old, new) = repository.update { s ->
            val newCfg = transform(s.schedule).sanitized()
            if (newCfg == s.schedule) return@update s
            val (oldWindow, oldNext) = schedule.evaluate(now, s.schedule)
            val oldSuppressed = Resolver.validSuppression(s.suppressedUntilMs.toInstantOrNull(), now, oldNext)
            val (newWindow, newNext) = schedule.evaluate(now, newCfg)
            val (rt, sup) = Resolver.remap(now, oldWindow, oldSuppressed, newWindow, newNext, s.scheduleRuntime)
            s.withRuntime(rt).copy(schedule = newCfg, suppressedUntilMs = sup?.toEpochMilli() ?: 0L)
        }
        if (old.schedule != new.schedule) Log.i(Backlight.LOG_TAG, "schedule settings -> ${new.schedule}")
    }

    /**
     * The only schedule mutator besides user on/off (V3_PLAN Resolver): evaluates the clock
     * and applies the result atomically. Called by the service only (on connect, screen on,
     * unlock, TIME_TICK, time/zone change, settings change, fade ticker). No-op until DataStore
     * is loaded, so the first evaluation after a restart sees the real settings.
     */
    fun evaluateSchedule(reason: String) {
        val now = schedule.now()
        var step: Resolver.Step? = null
        var window: com.plainapps.dimmer.engine.ScheduleWindow? = null
        val (old, new) = repository.update { s0 ->
            if (!s0.loaded) {
                step = null
                return@update s0
            }
            // The schedule step and the pause expiry in one atomic update: a pause ending at the
            // same minute as the window must not flash the overlay back for one frame.
            val s = expirePause(s0, now.toEpochMilli())
            val (w, next) = schedule.evaluate(now, s.schedule)
            window = w
            val st = Resolver.step(now, s.schedule, w, next, s.suppressedUntilMs.toInstantOrNull(), s.scheduleRuntime)
            step = st
            var n = s.withRuntime(st.runtime).copy(suppressedUntilMs = st.suppressedUntil?.toEpochMilli() ?: 0L)
            when (st.action) {
                Resolver.Action.TURN_ON -> {
                    val preset = Presets.find(s.presets, s.schedule.presetId)
                    if (preset != null) {
                        n = n.copy(
                            darkness = preset.darkness,
                            tint = preset.tint,
                            tintStrength = preset.tintStrength,
                            activePresetId = preset.id,
                        )
                    }
                }
                Resolver.Action.SCHEDULE_DISABLED -> {
                    // Keep what is on screen; the fade ends where it is.
                    n = bakeFade(s).withRuntime(st.runtime).copy(suppressedUntilMs = 0L)
                }
                else -> Unit
            }
            n
        }
        if (step?.action == Resolver.Action.TURN_ON) noteUserDarkness("schedule")
        if (old.pause != null && new.pause == null) {
            Log.i(Backlight.LOG_TAG, "pause ended ($reason) now=${fmt(now.toEpochMilli())} until=${fmt(old.pause.untilMs)} enabled=${new.enabled}")
        }
        val st = step ?: return
        if (st.action != Resolver.Action.NONE || old.suppressedUntilMs != new.suppressedUntilMs) {
            val fade = new.fadeStartMs?.let {
                "%.3f".format(ScheduleMath.fadeFraction(now, Instant.ofEpochMilli(it), new.schedule.fadeMinutes))
            }
            Log.i(
                Backlight.LOG_TAG,
                "schedule ${st.action} ($reason) now=${fmt(now.toEpochMilli())} window=${window?.let { "${fmt(it.start.toEpochMilli())}..${fmt(it.end.toEpochMilli())}" }} " +
                    "enabled=${new.enabled} source=${new.source} fade=$fade preset=${new.activePresetId} " +
                    "suppressedUntil=${fmt(new.suppressedUntilMs)} resume=${new.schedule.resumeAfterRestart}",
            )
        }
    }

    /** What the status line should say right now (read-only); an active pause comes first. */
    fun scheduleStatus(s: DimmerState = repository.state.value): Resolver.Status {
        val now = schedule.now()
        val (w, next) = schedule.evaluate(now, s.schedule)
        return status(s, now, w, next)
    }

    private fun status(s: DimmerState, now: Instant, w: com.plainapps.dimmer.engine.ScheduleWindow?, next: Instant?) =
        Resolver.status(
            now, s.schedule, w, next, s.suppressedUntilMs.toInstantOrNull(), s.enabled, s.source,
            s.fadeStartMs?.let(Instant::ofEpochMilli),
            pausedUntil = s.pause?.takeIf { s.paused }?.let { Instant.ofEpochMilli(it.untilMs) },
        )

    /** What the one notification should show right now (v3 P4, SPEC 5 and SPEC 3). */
    fun notificationPlan(s: DimmerState = repository.state.value): NotificationPlan {
        val now = schedule.now()
        val (w, next) = schedule.evaluate(now, s.schedule)
        return NotificationPlan.decide(
            enabled = s.enabled,
            pause = s.pause,
            presetName = s.activePreset?.let(presetLabel),
            darkness = s.darkness,
            status = status(s, now, w, next),
            windowEnd = w?.end?.takeIf { s.schedule.enabled },
        )
    }

    companion object {
        /** The reason string automation commands carry (AutomationReceiver -> [execute]). */
        const val AUTOMATION = "automation"

        /** v3.1: how long automation may not turn dimming on again after a user Off. */
        const val AUTOMATION_LOCKOUT_MS = 60_000L
    }

    private fun Long.toInstantOrNull(): Instant? = if (this > 0L) Instant.ofEpochMilli(this) else null

    private fun fmt(ms: Long): String =
        if (ms <= 0L) "none" else Instant.ofEpochMilli(ms).atZone(schedule.zone()).toLocalDateTime().toString()
}
