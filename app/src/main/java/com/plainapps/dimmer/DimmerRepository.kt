package com.plainapps.dimmer

import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.TileService
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import com.plainapps.dimmer.backlight.Backlight
import com.plainapps.dimmer.backlight.BacklightMethod
import com.plainapps.dimmer.engine.AmbientState
import com.plainapps.dimmer.engine.Presets
import com.plainapps.dimmer.engine.PresetCodec
import com.plainapps.dimmer.engine.EndKind
import com.plainapps.dimmer.engine.ScheduleConfig
import com.plainapps.dimmer.engine.ShakeSensitivity
import com.plainapps.dimmer.engine.StartKind
import com.plainapps.dimmer.engine.Tint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

private val Context.dimmerDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "dimmer_prefs",
    // v3.1 (REVIEW_V3 finding 8): a corrupt file is replaced by defaults instead of crashing
    // the app at every start.
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
    produceMigrations = { listOf(V2KeysMigration) },
)

/**
 * Process-wide single source of truth, held by [DimmerApplication]. Settings are read from
 * DataStore once at start; after that memory is authoritative and every settings change is
 * written back as one snapshot through a conflated collector (a rapid slider drag only ever
 * persists the latest values, never a queue of intermediate ones). `enabled` is memory-only by
 * design: see [DimmerState].
 *
 * Nothing outside [DimmerCommands] should call the mutators: it is the one way state changes.
 */
class DimmerRepository(
    private val context: Context,
    val maxLevel: Int,
) : DimmerStore {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(
        DimmerState(darkness = DimmerMath.clampLevel(40, maxLevel))
    )
    override val state: StateFlow<DimmerState> = _state.asStateFlow()

    init {
        scope.launch {
            // v3.1 (finding 8): a read or write error (storage full, I/O) must not crash the
            // process. A failed read runs on defaults and never writes them over the file.
            val prefs = try {
                context.dimmerDataStore.data.first()
            } catch (e: IOException) {
                Log.w(Backlight.LOG_TAG, "settings could not be read, using defaults this session", e)
                null
            }
            _state.update { applyStored(it, prefs ?: emptyPreferences(), maxLevel) }
            if (prefs == null) return@launch
            _state
                .map { Stored.of(it) }
                .distinctUntilChanged()
                .collectLatest { stored ->
                    try {
                        context.dimmerDataStore.edit { stored.writeTo(it) }
                    } catch (e: IOException) {
                        Log.w(Backlight.LOG_TAG, "settings could not be saved", e)
                    }
                }
        }
    }

    /**
     * Atomic state change; only [DimmerCommands] calls this. Values are re-clamped here. Returns
     * the (old, new) pair of the update that won (the transform may run more than once under
     * contention). An on/off, pause or preset change refreshes both Quick Settings tiles. Invariant (P4): a pause
     * never outlives dimming - whenever `enabled` is false the pause is dropped, and so is the room-light state (P6).
     */
    override fun update(transform: (DimmerState) -> DimmerState): Pair<DimmerState, DimmerState> {
        var result: Pair<DimmerState, DimmerState>
        while (true) {
            val old = _state.value
            val n = transform(old).normalized(maxLevel)
            if (_state.compareAndSet(old, n)) {
                result = old to n
                break
            }
        }
        val (a, b) = result
        if (a.enabled != b.enabled || a.paused != b.paused || a.activePresetId != b.activePresetId) {
            TileService.requestListeningState(context, ComponentName(context, DimmerTileService::class.java))
            TileService.requestListeningState(context, ComponentName(context, DimmerPresetTileService::class.java))
        }
        return result
    }

    /** v3.1: pure (de)serialisation, internal so the round trip is JVM-tested (StoredRoundTripTest). */
    companion object {
        internal fun applyStored(current: DimmerState, prefs: Preferences, maxLevel: Int): DimmerState {
            val presets = PresetCodec.decode(prefs[PrefKeys.PRESETS]) ?: Presets.DEFAULTS
            val cycle = prefs[PrefKeys.TILE_CYCLE]?.let { Presets.decodeCycle(it) } ?: Presets.DEFAULT_TILE_CYCLE
            val activeId = prefs[PrefKeys.ACTIVE_PRESET]?.takeIf { id -> presets.any { it.id == id } }
            return current.copy(
                darkness = DimmerMath.clampLevel(prefs[PrefKeys.DARKNESS] ?: current.darkness, maxLevel),
                tint = Tint.fromName(prefs[PrefKeys.TINT]) ?: current.tint,
                tintStrength = DimmerMath.clampTintStrength(prefs[PrefKeys.TINT_STRENGTH] ?: current.tintStrength),
                activePresetId = activeId,
                presets = presets,
                tileCycle = Presets.reconcileCycle(cycle, presets),
                hybridEnabled = prefs[PrefKeys.HYBRID] ?: current.hybridEnabled,
                backlightMethod = prefs[PrefKeys.BACKLIGHT_METHOD]
                    ?.let { name -> BacklightMethod.entries.firstOrNull { it.name == name } }
                    ?: current.backlightMethod,
                keepAwake = prefs[PrefKeys.KEEP_AWAKE] ?: current.keepAwake,
                schedule = readSchedule(prefs, current.schedule),
                suppressedUntilMs = prefs[PrefKeys.SUPPRESSED_UNTIL] ?: 0L,
                shakeEnabled = prefs[PrefKeys.SHAKE_ENABLED] ?: current.shakeEnabled,
                shakeSensitivity = ShakeSensitivity.fromName(prefs[PrefKeys.SHAKE_SENSITIVITY]) ?: current.shakeSensitivity,
                automationEnabled = prefs[PrefKeys.AUTOMATION_ENABLED] ?: current.automationEnabled,
                ambientEnabled = prefs[PrefKeys.AMBIENT_ENABLED] ?: current.ambientEnabled,
                darkRoomPresetId = prefs[PrefKeys.DARK_ROOM_PRESET] ?: current.darkRoomPresetId,
                loaded = true,
            )
        }

        private fun readSchedule(prefs: Preferences, d: ScheduleConfig) = ScheduleConfig(
            enabled = prefs[PrefKeys.SCHEDULE_ENABLED] ?: d.enabled,
            startKind = prefs[PrefKeys.SCHEDULE_START_KIND]
                ?.let { n -> StartKind.entries.firstOrNull { it.name == n } } ?: d.startKind,
            startMinute = prefs[PrefKeys.SCHEDULE_START_MINUTE] ?: d.startMinute,
            endKind = prefs[PrefKeys.SCHEDULE_END_KIND]
                ?.let { n -> EndKind.entries.firstOrNull { it.name == n } } ?: d.endKind,
            endMinute = prefs[PrefKeys.SCHEDULE_END_MINUTE] ?: d.endMinute,
            sunOffsetMin = prefs[PrefKeys.SCHEDULE_SUN_OFFSET] ?: d.sunOffsetMin,
            fadeMinutes = prefs[PrefKeys.SCHEDULE_FADE] ?: d.fadeMinutes,
            presetId = prefs[PrefKeys.SCHEDULE_PRESET] ?: d.presetId,
            resumeAfterRestart = prefs[PrefKeys.SCHEDULE_RESUME] ?: d.resumeAfterRestart,
        ).sanitized()

        /** The settings part of [DimmerState] (everything that is persisted). */
        internal data class Stored(
            val darkness: Int,
            val tint: Tint,
            val tintStrength: Int,
            val activePresetId: String,
            val presets: String,
            val tileCycle: String,
            val hybrid: Boolean,
            val method: BacklightMethod,
            val keepAwake: Boolean,
            val schedule: ScheduleConfig,
            val suppressedUntilMs: Long,
            val shakeEnabled: Boolean,
            val shakeSensitivity: ShakeSensitivity,
            val automationEnabled: Boolean,
            val ambientEnabled: Boolean,
            val darkRoomPresetId: String,
        ) {
            fun writeTo(p: androidx.datastore.preferences.core.MutablePreferences) {
                p[PrefKeys.DARKNESS] = darkness
                p[PrefKeys.TINT] = tint.name
                p[PrefKeys.TINT_STRENGTH] = tintStrength
                p[PrefKeys.ACTIVE_PRESET] = activePresetId
                p[PrefKeys.PRESETS] = presets
                p[PrefKeys.TILE_CYCLE] = tileCycle
                p[PrefKeys.HYBRID] = hybrid
                p[PrefKeys.BACKLIGHT_METHOD] = method.name
                p[PrefKeys.KEEP_AWAKE] = keepAwake
                p[PrefKeys.SCHEDULE_ENABLED] = schedule.enabled
                p[PrefKeys.SCHEDULE_START_KIND] = schedule.startKind.name
                p[PrefKeys.SCHEDULE_START_MINUTE] = schedule.startMinute
                p[PrefKeys.SCHEDULE_END_KIND] = schedule.endKind.name
                p[PrefKeys.SCHEDULE_END_MINUTE] = schedule.endMinute
                p[PrefKeys.SCHEDULE_SUN_OFFSET] = schedule.sunOffsetMin
                p[PrefKeys.SCHEDULE_FADE] = schedule.fadeMinutes
                p[PrefKeys.SCHEDULE_PRESET] = schedule.presetId
                p[PrefKeys.SCHEDULE_RESUME] = schedule.resumeAfterRestart
                p[PrefKeys.SUPPRESSED_UNTIL] = suppressedUntilMs
                p[PrefKeys.SHAKE_ENABLED] = shakeEnabled
                p[PrefKeys.SHAKE_SENSITIVITY] = shakeSensitivity.name
                p[PrefKeys.AUTOMATION_ENABLED] = automationEnabled
                p[PrefKeys.AMBIENT_ENABLED] = ambientEnabled
                p[PrefKeys.DARK_ROOM_PRESET] = darkRoomPresetId
            }

            companion object {
                fun of(s: DimmerState) = Stored(
                    darkness = s.darkness,
                    tint = s.tint,
                    tintStrength = s.tintStrength,
                    activePresetId = s.activePresetId ?: "",
                    presets = PresetCodec.encode(s.presets),
                    tileCycle = Presets.encodeCycle(s.tileCycle),
                    hybrid = s.hybridEnabled,
                    method = s.backlightMethod,
                    keepAwake = s.keepAwake,
                    schedule = s.schedule,
                    suppressedUntilMs = s.suppressedUntilMs,
                    shakeEnabled = s.shakeEnabled,
                    shakeSensitivity = s.shakeSensitivity,
                    automationEnabled = s.automationEnabled,
                    ambientEnabled = s.ambientEnabled,
                    darkRoomPresetId = s.darkRoomPresetId,
                )
            }
        }
    }

}

/**
 * What [DimmerCommands] needs from the state holder (v3.1: a seam so the commands are JVM-tested
 * against an in-memory store, `DimmerCommandsTest`). [DimmerRepository] is the only real one.
 */
interface DimmerStore {
    val state: StateFlow<DimmerState>

    /** Atomic change; the result is always [DimmerState.normalized]. Returns (old, new). */
    fun update(transform: (DimmerState) -> DimmerState): Pair<DimmerState, DimmerState>
}

/**
 * The repository invariants, pure (v3.1, moved out of [DimmerRepository.update] so they are
 * tested): values clamped, schedule sanitized, and a pause or a room-light state never outlives
 * dimming (P4, P6).
 */
fun DimmerState.normalized(maxLevel: Int): DimmerState = copy(
    darkness = DimmerMath.clampLevel(darkness, maxLevel),
    tintStrength = DimmerMath.clampTintStrength(tintStrength),
    schedule = schedule.sanitized(),
    pause = if (enabled) pause else null,
    ambientState = if (enabled) ambientState else AmbientState.NORMAL,
)
