package com.plainapps.dimmer

import androidx.datastore.preferences.core.mutablePreferencesOf
import com.plainapps.dimmer.engine.Tint
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test

class StorageMigrationTest {

    @Test
    fun `v2 level and warmth become darkness and Candle strength, old keys removed`() {
        val v2 = mutablePreferencesOf(PrefKeys.V2_LEVEL to 63, PrefKeys.V2_WARMTH to 45)
        val out = migrateV2Keys(v2)
        assertEquals(63, out[PrefKeys.DARKNESS])
        assertEquals(45, out[PrefKeys.TINT_STRENGTH])
        assertEquals(Tint.CANDLE.name, out[PrefKeys.TINT])
        assertNull(out[PrefKeys.V2_LEVEL])
        assertNull(out[PrefKeys.V2_WARMTH])
        assertNull(out[PrefKeys.ACTIVE_PRESET]) // no preset active: shows as Custom
    }

    @Test
    fun `v2 warmth zero still maps to Candle strength 0`() {
        val out = migrateV2Keys(mutablePreferencesOf(PrefKeys.V2_LEVEL to 10, PrefKeys.V2_WARMTH to 0))
        assertEquals(0, out[PrefKeys.TINT_STRENGTH])
        assertEquals(Tint.CANDLE.name, out[PrefKeys.TINT])
    }

    @Test
    fun `other v2 keys are untouched`() {
        val v2 = mutablePreferencesOf(PrefKeys.V2_LEVEL to 20, PrefKeys.HYBRID to false, PrefKeys.KEEP_AWAKE to true)
        val out = migrateV2Keys(v2)
        assertEquals(false, out[PrefKeys.HYBRID])
        assertEquals(true, out[PrefKeys.KEEP_AWAKE])
    }

    @Test
    fun `running it again changes nothing, and it never overwrites v3 keys`() {
        val once = migrateV2Keys(mutablePreferencesOf(PrefKeys.V2_LEVEL to 63, PrefKeys.V2_WARMTH to 45))
        assertEquals(once, migrateV2Keys(once))

        val mixed = mutablePreferencesOf(
            PrefKeys.V2_LEVEL to 11,
            PrefKeys.V2_WARMTH to 22,
            PrefKeys.DARKNESS to 77,
            PrefKeys.TINT_STRENGTH to 88,
            PrefKeys.TINT to Tint.RED.name,
        )
        val out = migrateV2Keys(mixed)
        assertEquals(77, out[PrefKeys.DARKNESS])
        assertEquals(88, out[PrefKeys.TINT_STRENGTH])
        assertEquals(Tint.RED.name, out[PrefKeys.TINT])
        assertNull(out[PrefKeys.V2_LEVEL])
    }

    @Test
    fun `shouldMigrate is true only while a v2 key exists`() = runBlocking {
        assertTrue(V2KeysMigration.shouldMigrate(mutablePreferencesOf(PrefKeys.V2_LEVEL to 1)))
        assertTrue(V2KeysMigration.shouldMigrate(mutablePreferencesOf(PrefKeys.V2_WARMTH to 1)))
        assertFalse(V2KeysMigration.shouldMigrate(mutablePreferencesOf(PrefKeys.DARKNESS to 1)))
        assertFalse(V2KeysMigration.shouldMigrate(V2KeysMigration.migrate(mutablePreferencesOf(PrefKeys.V2_LEVEL to 1))))
    }
}
