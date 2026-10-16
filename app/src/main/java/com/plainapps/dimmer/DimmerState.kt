package com.plainapps.dimmer

import com.plainapps.dimmer.backlight.BacklightMethod
import com.plainapps.dimmer.engine.AmbientLook
import com.plainapps.dimmer.engine.AmbientState
import com.plainapps.dimmer.engine.DimSource
import com.plainapps.dimmer.engine.PauseWindow
import com.plainapps.dimmer.engine.Preset
import com.plainapps.dimmer.engine.Presets
import com.plainapps.dimmer.engine.ScheduleConfig
import com.plainapps.dimmer.engine.ScheduleRuntime
import com.plainapps.dimmer.engine.ShakeSensitivity
import com.plainapps.dimmer.engine.Tint

/**
 * [enabled] is intentionally never persisted: after any process death or
 * reboot the dimmer comes back OFF, so the UI, the tile and the notification
 * can never disagree about whether an overlay is actually on screen.
 *
 * v3 P2 names: [darkness] is the one 0..100 scale (v2 "level"); with [hybridEnabled] it drives
 * the backlight first, then the overlay (backlight/HybridCurve). [tint] + [tintStrength] replace
 * v2's single "warmth" axis. [activePresetId] null means "Custom" (the values were moved by hand
 * or never came from a preset). [tileCycle] holds the preset ids the preset tile will cycle (P5).
 * [permissionEpoch] is memory-only: bumped when the app returns to the foreground so
 * the service re-resolves the backlight method after a WRITE_SETTINGS grant/revoke.
 *
 * v3 P3: [schedule] and [suppressedUntilMs] ("off until the next scheduled start", epoch ms, 0 =
 * none) are persisted. [source], [fadeStartMs], [seenWindowStartMs] and [scheduleEvaluated] are
 * memory-only schedule bookkeeping (see engine/Resolver); [loaded] turns true once DataStore
 * has been read, and the schedule is never evaluated before that.
 *
 * v3 P4: [pause] (the plan's `pausedUntil`, memory-only like `enabled`) hides the overlay and
 * releases the backlight while dimming stays on; the repository drops it whenever dimming turns
 * off. [shakeEnabled], [shakeSensitivity] and [automationEnabled] are persisted settings.
 *
 * v3 P6: [ambientEnabled] ("Adjust to room light") and [darkRoomPresetId] are persisted;
 * [ambientState] (what the room light asks for, engine/AmbientPolicy) is memory-only and is NORMAL
 * whenever the light sensor is not being read.
 */
data class DimmerState(
    val enabled: Boolean = false,
    val darkness: Int = 40,
    val tint: Tint = Tint.CANDLE,
    val tintStrength: Int = 0,
    val activePresetId: String? = null,
    val presets: List<Preset> = Presets.DEFAULTS,
    val tileCycle: List<String> = Presets.DEFAULT_TILE_CYCLE,
    val hybridEnabled: Boolean = true,
    val backlightMethod: BacklightMethod = BacklightMethod.AUTO,
    val keepAwake: Boolean = false,
    val permissionEpoch: Int = 0,
    val schedule: ScheduleConfig = ScheduleConfig(),
    val suppressedUntilMs: Long = 0L,
    val source: DimSource = DimSource.MANUAL,
    val fadeStartMs: Long? = null,
    val seenWindowStartMs: Long? = null,
    val scheduleEvaluated: Boolean = false,
    val loaded: Boolean = false,
    val pause: PauseWindow? = null,
    val shakeEnabled: Boolean = true,
    val shakeSensitivity: ShakeSensitivity = ShakeSensitivity.DEFAULT,
    val automationEnabled: Boolean = false,
    val ambientEnabled: Boolean = false,
    val darkRoomPresetId: String = AmbientLook.DEFAULT_DARK_ROOM_PRESET,
    val ambientState: AmbientState = AmbientState.NORMAL,
) {
    val activePreset: Preset? get() = Presets.find(presets, activePresetId)

    /**
     * Paused = a pause is stored. Expiry (and a clock moved back) is detected by
     * DimmerCommands.evaluateSchedule, which clears it; until then service, UI, tile and
     * notification all agree that it is paused (L13 review P4 #15).
     */
    val paused: Boolean get() = enabled && pause != null

    /** The overlay should be on screen (on and not paused). */
    val visible: Boolean get() = enabled && pause == null

    /** Dimming is on but a bright room has suspended the overlay (SPEC 8). */
    val ambientSuspended: Boolean get() = visible && AmbientLook.suspended(ambientEnabled, ambientState)

    val scheduleRuntime: ScheduleRuntime
        get() = ScheduleRuntime(
            enabled = enabled,
            source = source,
            fadeStart = fadeStartMs?.let(java.time.Instant::ofEpochMilli),
            seenWindowStart = seenWindowStartMs?.let(java.time.Instant::ofEpochMilli),
            evaluated = scheduleEvaluated,
        )

    fun withRuntime(rt: ScheduleRuntime): DimmerState = copy(
        enabled = rt.enabled,
        source = rt.source,
        fadeStartMs = rt.fadeStart?.toEpochMilli(),
        seenWindowStartMs = rt.seenWindowStart?.toEpochMilli(),
        scheduleEvaluated = rt.evaluated,
    )
}
