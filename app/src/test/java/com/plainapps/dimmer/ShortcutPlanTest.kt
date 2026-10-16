package com.plainapps.dimmer

import com.plainapps.dimmer.engine.Presets
import com.plainapps.dimmer.engine.ShortcutCommand
import com.plainapps.dimmer.engine.ShortcutPlan
import com.plainapps.dimmer.engine.ShortcutSpec
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class ShortcutPlanTest {

    @Test
    fun `parse off, preset and garbage`() {
        assertEquals(ShortcutCommand.Off, ShortcutCommand.parse(ShortcutCommand.ACTION_OFF, null))
        assertEquals(ShortcutCommand.Off, ShortcutCommand.parse(ShortcutCommand.ACTION_OFF, "candlelight"))
        assertEquals(
            ShortcutCommand.ApplyPreset("candlelight"),
            ShortcutCommand.parse(ShortcutCommand.ACTION_PRESET, "candlelight"),
        )
        assertEquals(ShortcutCommand.ApplyPreset("p3"), ShortcutCommand.parse(ShortcutCommand.ACTION_PRESET, "p3"))
        assertNull(ShortcutCommand.parse(ShortcutCommand.ACTION_PRESET, null))
        assertNull(ShortcutCommand.parse(ShortcutCommand.ACTION_PRESET, ""))
        assertNull(ShortcutCommand.parse(ShortcutCommand.ACTION_PRESET, "Has Space"))
        assertNull(ShortcutCommand.parse("com.plainapps.dimmer.action.OFF", null))
        assertNull(ShortcutCommand.parse(null, "p1"))
    }

    @Test
    fun `pinned id keeps the preset id recoverable`() {
        val id = ShortcutPlan.pinnedId("deep_red", 1_780_000_000_123)
        assertEquals("pin_deep_red_1780000000123", id)
        assertEquals("deep_red", ShortcutPlan.presetIdOf(id))
        assertEquals("p2", ShortcutPlan.presetIdOf(ShortcutPlan.pinnedId("p2", 5)))
        assertTrue(ShortcutPlan.pinnedId("p2", 1) != ShortcutPlan.pinnedId("p2", 2))
    }

    @Test
    fun `foreign shortcut ids are not ours`() {
        assertNull(ShortcutPlan.presetIdOf("static_off"))
        assertNull(ShortcutPlan.presetIdOf("pin_"))
        assertNull(ShortcutPlan.presetIdOf("pin_123"))
        assertNull(ShortcutPlan.presetIdOf("pin__5"))
        assertNull(ShortcutPlan.presetIdOf("pin_Bad Id_5"))
    }

    @Test
    fun `reconcile relabels existing presets and disables deleted ones`() {
        val presets = Presets.rename(Presets.DEFAULTS, "evening", "Sundown")
            .filterNot { it.id == "reading" }
        val pinned = listOf(
            ShortcutPlan.pinnedId("evening", 1),
            ShortcutPlan.pinnedId("reading", 2),
            "static_off",
            ShortcutPlan.pinnedId("evening", 3),
        )
        val r = ShortcutPlan.reconcilePinned(pinned, presets)
        assertEquals(
            listOf(
                ShortcutSpec("pin_evening_1", "evening", "Sundown"),
                ShortcutSpec("pin_evening_3", "evening", "Sundown"),
            ),
            r.update,
        )
        assertEquals(listOf("pin_reading_2"), r.disableIds)
    }

    @Test
    fun `reconcile with nothing pinned does nothing`() {
        val r = ShortcutPlan.reconcilePinned(emptyList(), Presets.DEFAULTS)
        assertTrue(r.update.isEmpty() && r.disableIds.isEmpty())
    }

    @Test
    fun `static shortcut targets are default presets`() {
        val ids = Presets.DEFAULTS.map { it.id }
        assertTrue("candlelight" in ids && "deep_red" in ids)
    }
}
