package com.plainapps.dimmer

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.plainapps.dimmer.engine.Tint

/** DataStore keys (settings only; `enabled` is never persisted, see [DimmerState]). */
internal object PrefKeys {
    val DARKNESS = intPreferencesKey("darkness")
    val TINT = stringPreferencesKey("tint")
    val TINT_STRENGTH = intPreferencesKey("tint_strength")

    /** Empty string = Custom (no active preset). */
    val ACTIVE_PRESET = stringPreferencesKey("active_preset")
    val PRESETS = stringPreferencesKey("presets")
    val TILE_CYCLE = stringPreferencesKey("tile_cycle")
    val HYBRID = booleanPreferencesKey("hybrid_enabled")
    val BACKLIGHT_METHOD = stringPreferencesKey("backlight_method")
    val KEEP_AWAKE = booleanPreferencesKey("keep_awake")

    // v3 P3 schedule.
    val SCHEDULE_ENABLED = booleanPreferencesKey("schedule_enabled")
    val SCHEDULE_START_KIND = stringPreferencesKey("schedule_start_kind")
    val SCHEDULE_START_MINUTE = intPreferencesKey("schedule_start_minute")
    val SCHEDULE_END_KIND = stringPreferencesKey("schedule_end_kind")
    val SCHEDULE_END_MINUTE = intPreferencesKey("schedule_end_minute")
    val SCHEDULE_SUN_OFFSET = intPreferencesKey("schedule_sun_offset")
    val SCHEDULE_FADE = intPreferencesKey("schedule_fade")
    val SCHEDULE_PRESET = stringPreferencesKey("schedule_preset")
    val SCHEDULE_RESUME = booleanPreferencesKey("schedule_resume")

    /** "Off until the next scheduled start", epoch ms (0 = none). Survives restarts on purpose. */
    val SUPPRESSED_UNTIL = longPreferencesKey("suppressed_until")

    // v3 P4 safety exits and automation.
    val SHAKE_ENABLED = booleanPreferencesKey("shake_enabled")
    val SHAKE_SENSITIVITY = stringPreferencesKey("shake_sensitivity")
    val AUTOMATION_ENABLED = booleanPreferencesKey("automation_enabled")

    // v3 P6 room light.
    val AMBIENT_ENABLED = booleanPreferencesKey("ambient_enabled")
    val DARK_ROOM_PRESET = stringPreferencesKey("dark_room_preset")

    // v2 names, migrated once by [migrateV2Keys].
    val V2_LEVEL = intPreferencesKey("level")
    val V2_WARMTH = intPreferencesKey("warmth")
}

/**
 * v3 P2 rename, done once: v2 `level` -> `darkness`, v2 `warmth` -> `tint_strength` with the
 * Candle tint (Candle is the v2 warm endpoint, so a v2 user's look is unchanged). No preset is
 * active afterwards, so the values show as Custom. New keys already present are never
 * overwritten; the old keys are always removed.
 */
internal fun migrateV2Keys(current: Preferences): Preferences {
    val out = current.toMutablePreferences()
    val level = current[PrefKeys.V2_LEVEL]
    val warmth = current[PrefKeys.V2_WARMTH]
    if (level != null) {
        if (PrefKeys.DARKNESS !in current) out[PrefKeys.DARKNESS] = level
        out.remove(PrefKeys.V2_LEVEL)
    }
    if (warmth != null) {
        if (PrefKeys.TINT_STRENGTH !in current) {
            out[PrefKeys.TINT_STRENGTH] = warmth
            if (PrefKeys.TINT !in current) out[PrefKeys.TINT] = Tint.CANDLE.name
        }
        out.remove(PrefKeys.V2_WARMTH)
    }
    return out
}

internal object V2KeysMigration : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean =
        PrefKeys.V2_LEVEL in currentData || PrefKeys.V2_WARMTH in currentData

    override suspend fun migrate(currentData: Preferences): Preferences = migrateV2Keys(currentData)

    override suspend fun cleanUp() = Unit
}
