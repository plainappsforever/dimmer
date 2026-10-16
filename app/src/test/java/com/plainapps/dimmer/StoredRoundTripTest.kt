package com.plainapps.dimmer

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.plainapps.dimmer.backlight.BacklightMethod
import com.plainapps.dimmer.engine.EndKind
import com.plainapps.dimmer.engine.ScheduleConfig
import com.plainapps.dimmer.engine.ShakeSensitivity
import com.plainapps.dimmer.engine.StartKind
import com.plainapps.dimmer.engine.Tint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private typealias Stored = DimmerRepository.Companion.Stored

/**
 * v3.1 (REVIEW_V3 test gap 3): every persisted setting survives write -> read. A key that is
 * written but never read back (or read from the wrong key) fails here.
 */
class StoredRoundTripTest {

    /** A state where every persisted value differs from the default. */
    private val custom = DimmerState(
        darkness = 77,
        tint = Tint.RED,
        tintStrength = 55,
        activePresetId = "deep_red",
        tileCycle = listOf("reading", "ultra_dim"),
        hybridEnabled = false,
        backlightMethod = BacklightMethod.SYSTEM,
        keepAwake = true,
        schedule = ScheduleConfig(
            enabled = true,
            startKind = StartKind.SUNSET,
            startMinute = 20 * 60 + 15,
            endKind = EndKind.SUNRISE,
            endMinute = 6 * 60 + 5,
            sunOffsetMin = 15,
            fadeMinutes = 60,
            presetId = "reading",
            resumeAfterRestart = true,
        ),
        suppressedUntilMs = 1_790_000_000_000L,
        shakeEnabled = false,
        shakeSensitivity = ShakeSensitivity.HIGH,
        automationEnabled = true,
        ambientEnabled = true,
        darkRoomPresetId = "evening",
    ).let { it.copy(presets = it.presets.map { p -> if (p.id == "evening") p.copy(name = "Late evening", darkness = 33) else p }) }

    private fun roundTrip(s: DimmerState): DimmerState {
        val prefs = mutablePreferencesOf()
        Stored.of(s).writeTo(prefs)
        return DimmerRepository.applyStored(DimmerState(), prefs, 100)
    }

    @Test
    fun everySettingSurvivesWriteThenRead() {
        val back = roundTrip(custom)
        assertEquals(Stored.of(custom), Stored.of(back))
        assertTrue(back.loaded)
    }

    @Test
    fun theTestStateReallyDiffersInEveryStoredField() {
        val a = Stored.of(custom)
        val b = Stored.of(DimmerState())
        for (f in Stored::class.java.declaredFields.filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }) {
            f.isAccessible = true
            assertNotEquals("field ${f.name} must differ from the default", f.get(b), f.get(a))
        }
    }

    @Test
    fun everyCurrentKeyIsWritten() {
        val prefs = mutablePreferencesOf()
        Stored.of(custom).writeTo(prefs)
        val written = prefs.asMap().keys.map { it.name }.toSet()
        val declared = PrefKeys::class.java.declaredFields
            .filter { Preferences.Key::class.java.isAssignableFrom(it.type) }
            .map { f -> f.isAccessible = true; (f.get(PrefKeys) as Preferences.Key<*>).name }
            .filter { it != "level" && it != "warmth" } // v2 names, migrated away
        assertEquals(declared.toSet(), written)
    }

    @Test
    fun memoryOnlyStateIsNeverPersisted() {
        // v1 safety rule: dimming is always off in a new process (unless the schedule resumes it).
        val names = Stored::class.java.declaredFields.map { it.name }.toSet()
        for (memoryOnly in listOf("enabled", "pause", "source", "fadeStartMs", "seenWindowStartMs", "ambientState")) {
            assertTrue("$memoryOnly must not be persisted", memoryOnly !in names)
        }
    }

    @Test
    fun outOfRangeStoredValuesAreClamped() {
        val prefs = mutablePreferencesOf()
        Stored.of(custom).writeTo(prefs)
        prefs[PrefKeys.DARKNESS] = 250
        prefs[PrefKeys.TINT_STRENGTH] = -5
        prefs[PrefKeys.ACTIVE_PRESET] = "no_such_preset"
        val back = DimmerRepository.applyStored(DimmerState(), prefs, 100)
        assertEquals(100, back.darkness)
        assertEquals(DimmerMath.clampTintStrength(-5), back.tintStrength)
        assertEquals(null, back.activePresetId)
    }
}
