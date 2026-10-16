package com.plainapps.dimmer

import com.plainapps.dimmer.engine.Preset
import com.plainapps.dimmer.engine.Presets
import com.plainapps.dimmer.engine.Tint
import com.plainapps.dimmer.engine.TileKind
import com.plainapps.dimmer.engine.TilePlan
import com.plainapps.dimmer.engine.TileSubtitle
import com.plainapps.dimmer.engine.WidgetAction
import com.plainapps.dimmer.engine.WidgetLayout
import com.plainapps.dimmer.engine.WidgetPlan
import com.plainapps.dimmer.engine.WidgetStatus
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class WidgetPlanTest {

    private fun content(
        enabled: Boolean = true,
        paused: Boolean = false,
        presets: List<Preset> = Presets.DEFAULTS,
        cycle: List<String> = Presets.DEFAULT_TILE_CYCLE,
        active: String? = "evening",
        darkness: Int = 35,
        access: Boolean = true,
    ) = WidgetPlan.content(enabled, paused, presets, cycle, active, darkness, access)

    @Test
    fun `layout follows the width`() {
        assertEquals(WidgetLayout.SMALL, WidgetPlan.layoutFor(0))
        assertEquals(WidgetLayout.SMALL, WidgetPlan.layoutFor(110))
        assertEquals(WidgetLayout.SMALL, WidgetPlan.layoutFor(249))
        assertEquals(WidgetLayout.WIDE, WidgetPlan.layoutFor(250))
        assertEquals(WidgetLayout.WIDE, WidgetPlan.layoutFor(320))
    }

    @Test
    fun `status is off on or paused`() {
        assertEquals(WidgetStatus.OFF, content(enabled = false).status)
        assertEquals(WidgetStatus.ON, content().status)
        assertEquals(WidgetStatus.PAUSED, content(paused = true).status)
        // A pause without dimming cannot exist (repository invariant), but off wins anyway.
        assertEquals(WidgetStatus.OFF, content(enabled = false, paused = true).status)
    }

    @Test
    fun `buttons are the cycle presets in preset order`() {
        val c = content()
        assertEquals(listOf("evening", "candlelight", "deep_red", "ultra_dim"), c.buttons.map { it.presetId })
        assertEquals(listOf("Evening", "Candlelight", "Deep red", "Ultra dim"), c.buttons.map { it.name })
    }

    @Test
    fun `only the active preset is selected and only while dimming is on`() {
        assertEquals(listOf(true, false, false, false), content().buttons.map { it.selected })
        assertEquals(listOf(false, false, false, false), content(enabled = false).buttons.map { it.selected })
        assertTrue(content(paused = true).buttons.first().selected)
        assertTrue(content(active = null).buttons.none { it.selected })
        // Active preset that is not in the cycle: nothing highlighted.
        assertTrue(content(active = "reading").buttons.none { it.selected })
    }

    @Test
    fun `at most four buttons, even with eight presets in the cycle`() {
        val many = (1..8).map { Preset("p$it", "P$it", 10 * it, Tint.NEUTRAL, 0) }
        val c = content(presets = many, cycle = many.map { it.id }, active = "p2")
        assertEquals(listOf("p1", "p2", "p3", "p4"), c.buttons.map { it.presetId })
    }

    @Test
    fun `an empty cycle falls back to the first presets`() {
        val c = content(cycle = emptyList())
        assertEquals(listOf("evening", "reading", "candlelight", "deep_red"), c.buttons.map { it.presetId })
    }

    @Test
    fun `no presets means no buttons`() {
        assertTrue(content(presets = emptyList(), cycle = emptyList(), active = null).buttons.isEmpty())
    }

    @Test
    fun `preset name is null for Custom and carries the darkness and access flag`() {
        val custom = content(active = null, darkness = 61, access = false)
        assertNull(custom.presetName)
        assertEquals(61, custom.darkness)
        assertEquals(false, custom.accessOk)
        assertEquals("Evening", content().presetName)
        // An id that no longer exists is Custom.
        assertNull(content(active = "gone").presetName)
    }

    @Test
    fun `equal state gives equal content so nothing is redrawn`() {
        assertEquals(content(), content())
        assertTrue(content() != content(darkness = 36))
    }

    @Test
    fun `widget actions round trip through their intent action`() {
        for (a in WidgetAction.entries) assertEquals(a, WidgetAction.fromIntentAction(a.intentAction))
        assertNull(WidgetAction.fromIntentAction("com.plainapps.dimmer.internal.LIGHTER"))
        assertNull(WidgetAction.fromIntentAction(null))
    }

    // --- tile subtitles ---

    @Test
    fun `on off tile shows the preset name only while on`() {
        assertEquals(TileSubtitle.Look("Evening", 35, false), TilePlan.subtitle(TileKind.TOGGLE, true, false, "Evening", 35))
        assertEquals(TileSubtitle.Look(null, 35, false), TilePlan.subtitle(TileKind.TOGGLE, true, false, null, 35))
        assertNull(TilePlan.subtitle(TileKind.TOGGLE, false, false, "Evening", 35))
        assertEquals(TileSubtitle.Paused, TilePlan.subtitle(TileKind.TOGGLE, true, true, "Evening", 35))
    }

    @Test
    fun `presets tile shows name and darkness, Off or Paused`() {
        assertEquals(TileSubtitle.Look("Deep red", 70, true), TilePlan.subtitle(TileKind.PRESETS, true, false, "Deep red", 70))
        assertEquals(TileSubtitle.Off, TilePlan.subtitle(TileKind.PRESETS, false, false, "Deep red", 70))
        assertEquals(TileSubtitle.Paused, TilePlan.subtitle(TileKind.PRESETS, true, true, null, 70))
    }
}
