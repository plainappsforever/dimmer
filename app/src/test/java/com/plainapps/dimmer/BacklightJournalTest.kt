package com.plainapps.dimmer

import com.plainapps.dimmer.backlight.BacklightJournal
import com.plainapps.dimmer.backlight.JournalCodec
import com.plainapps.dimmer.backlight.JournalEntry
import com.plainapps.dimmer.backlight.JournalStore
import com.plainapps.dimmer.backlight.RestorePlan
import com.plainapps.dimmer.backlight.RestoreRules
import com.plainapps.dimmer.backlight.RestoreRules.MODE_AUTOMATIC
import com.plainapps.dimmer.backlight.RestoreRules.MODE_MANUAL
import com.plainapps.dimmer.backlight.Takeover
import com.plainapps.dimmer.backlight.WindowBacklight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BacklightJournalTest {

    private class MemoryStore : JournalStore {
        var text: String? = null
        override fun load() = JournalCodec.decode(text)
        override fun save(entry: JournalEntry?): Boolean {
            text = entry?.let(JournalCodec::encode)
            return true
        }
    }

    @Test
    fun roundTrip_throughStore() {
        val store = MemoryStore()
        val journal = BacklightJournal(store)
        assertNull(journal.load())
        val entry = JournalEntry(200, MODE_AUTOMATIC, 1_727_000_000_000L, written = 1, previousWritten = 12, takeover = Takeover.BRIGHTNESS)
        journal.save(entry)
        assertEquals(entry, journal.load())
        journal.clear()
        assertNull(journal.load())
        assertNull(store.text)
    }

    @Test
    fun roundTrip_codecAllTakeovers() {
        for (t in Takeover.entries) {
            val e = JournalEntry(0, MODE_MANUAL, 0L, 255, 3, t)
            assertEquals(e, JournalCodec.decode(JournalCodec.encode(e)))
        }
    }

    @Test
    fun newEntryDefaultsWrittenToOriginal() {
        val e = JournalEntry(150, MODE_MANUAL, 5L)
        assertEquals(150, e.written)
        assertEquals(150, e.previousWritten)
        assertEquals(Takeover.NONE, e.takeover)
    }

    @Test
    fun decode_rejectsGarbage() {
        assertNull(JournalCodec.decode(null))
        assertNull(JournalCodec.decode(""))
        assertNull(JournalCodec.decode("2|200|0|0|1|1|NONE"))
        assertNull(JournalCodec.decode("1|200|0|0|1|1|WHATEVER"))
        assertNull(JournalCodec.decode("1|abc|0|0|1|1|NONE"))
        assertNull(JournalCodec.decode("1|200|0|0|1|NONE"))
    }

    @Test
    fun decode_clampsBrightness() {
        assertEquals(255, JournalCodec.decode("1|999|0|0|1|1|NONE")!!.brightness)
    }

    // --- restore rules -------------------------------------------------------------------

    private val manual200AtFloor = JournalEntry(200, MODE_MANUAL, 0L, written = 1, previousWritten = 60)
    private val auto120AtFloor = JournalEntry(120, MODE_AUTOMATIC, 0L, written = 1, previousWritten = 1)

    @Test
    fun normalRelease_restoresBoth() {
        assertEquals(RestorePlan(200, MODE_MANUAL), RestoreRules.plan(manual200AtFloor, 1, MODE_MANUAL))
        assertEquals(RestorePlan(120, MODE_AUTOMATIC), RestoreRules.plan(auto120AtFloor, 1, MODE_MANUAL))
    }

    @Test
    fun writeInFlight_previousValueStillCountsAsOurs() {
        assertEquals(RestorePlan(200, MODE_MANUAL), RestoreRules.plan(manual200AtFloor, 60, MODE_MANUAL))
        assertEquals(Takeover.NONE, RestoreRules.detectTakeover(manual200AtFloor, 60, MODE_MANUAL))
    }

    @Test
    fun systemRoundingOfOurValue_isNotATakeover() {
        assertEquals(Takeover.NONE, RestoreRules.detectTakeover(manual200AtFloor, 3, MODE_MANUAL))
        assertEquals(RestorePlan(200, MODE_MANUAL), RestoreRules.plan(manual200AtFloor, 2, MODE_MANUAL))
    }

    @Test
    fun userMovedSlider_keepsTheirValue_restoresModeOnly() {
        assertEquals(Takeover.BRIGHTNESS, RestoreRules.detectTakeover(auto120AtFloor, 90, MODE_MANUAL))
        assertEquals(RestorePlan(null, MODE_AUTOMATIC), RestoreRules.plan(auto120AtFloor, 90, MODE_MANUAL))
    }

    @Test
    fun userSwitchedAutoOn_restoresNothing() {
        assertEquals(Takeover.MODE, RestoreRules.detectTakeover(manual200AtFloor, 1, MODE_AUTOMATIC))
        assertEquals(RestorePlan(null, null), RestoreRules.plan(manual200AtFloor, 1, MODE_AUTOMATIC))
        assertEquals(RestorePlan(null, null), RestoreRules.plan(auto120AtFloor, 1, MODE_AUTOMATIC))
    }

    @Test
    fun crashBetweenJournalAndFirstWrite_isHarmless() {
        val fresh = JournalEntry(120, MODE_AUTOMATIC, 0L) // written == brightness, mode untouched
        assertEquals(Takeover.NONE, RestoreRules.detectTakeover(fresh, 120, MODE_AUTOMATIC))
        assertEquals(RestorePlan(null, null), RestoreRules.plan(fresh, 120, MODE_AUTOMATIC))
    }

    @Test
    fun storedTakeoverFlagDoesNotStrandTheFloor() {
        // A (possibly false) takeover was recorded, but Settings still hold our floor: restore.
        val flagged = manual200AtFloor.copy(takeover = Takeover.BRIGHTNESS)
        assertEquals(RestorePlan(200, MODE_MANUAL), RestoreRules.plan(flagged, 1, MODE_MANUAL))
    }

    // --- window floor ------------------------------------------------------------------

    @Test
    fun windowValue_floorIsZero_nullIsNoOverride() {
        assertEquals(WindowBacklight.NO_OVERRIDE, WindowBacklight.toWindowValue(null), 0f)
        assertEquals(WindowBacklight.NO_OVERRIDE, WindowBacklight.toWindowValue(Float.NaN), 0f)
        assertEquals(0f, WindowBacklight.toWindowValue(1f / 255f), 0f)
        assertEquals(0f, WindowBacklight.toWindowValue(-0.5f), 0f)
        assertEquals(0.5f, WindowBacklight.toWindowValue(0.5f), 0f)
        assertEquals(1f, WindowBacklight.toWindowValue(4f), 0f)
    }
}
