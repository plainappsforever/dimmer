package com.plainapps.dimmer

import com.plainapps.dimmer.engine.AutomationCommand
import com.plainapps.dimmer.engine.AutomationCommand.Companion.PREFIX
import com.plainapps.dimmer.engine.Presets
import com.plainapps.dimmer.engine.Tint
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

class AutomationCommandTest {

    private fun parse(action: String, vararg extras: Pair<String, Any?>): AutomationCommand? {
        val map = extras.toMap()
        return AutomationCommand.parse(PREFIX + action) { map[it] }
    }

    @Test
    fun `simple actions`() {
        assertEquals(AutomationCommand.On, parse("ON"))
        assertEquals(AutomationCommand.Off, parse("OFF"))
        assertEquals(AutomationCommand.Toggle, parse("TOGGLE"))
        assertEquals(AutomationCommand.Resume, parse("RESUME"))
        assertEquals(AutomationCommand.NextPreset, parse("NEXT_PRESET"))
        assertEquals(AutomationCommand.ACTIONS.size, AutomationCommand.ACTIONS.count { parse(it, "name" to "x", "darkness" to 1) != null })
    }

    @Test
    fun `unknown or foreign actions are ignored`() {
        assertNull(parse("EXPLODE"))
        assertNull(parse("on"))
        assertNull(AutomationCommand.parse("android.intent.action.BOOT_COMPLETED") { null })
        assertNull(AutomationCommand.parse(null) { null })
    }

    @Test
    fun `pause minutes default and clamp, ints or strings`() {
        assertEquals(AutomationCommand.PauseFor(15), parse("PAUSE"))
        assertEquals(AutomationCommand.PauseFor(45), parse("PAUSE", "minutes" to 45))
        assertEquals(AutomationCommand.PauseFor(45), parse("PAUSE", "minutes" to " 45 "))
        assertEquals(AutomationCommand.PauseFor(720), parse("PAUSE", "minutes" to 99_999L))
        assertEquals(AutomationCommand.PauseFor(1), parse("PAUSE", "minutes" to -3))
        assertEquals(AutomationCommand.PauseFor(15), parse("PAUSE", "minutes" to "soon"))
    }

    @Test
    fun `darker and lighter step default 10, clamped 1 to 50`() {
        assertEquals(AutomationCommand.Darker(10), parse("DARKER"))
        assertEquals(AutomationCommand.Lighter(10), parse("LIGHTER"))
        assertEquals(AutomationCommand.Darker(50), parse("DARKER", "step" to 500))
        assertEquals(AutomationCommand.Lighter(1), parse("LIGHTER", "step" to "0"))
        assertEquals(AutomationCommand.Lighter(25), parse("LIGHTER", "step" to 25.7))
    }

    @Test
    fun `set clamps, parses tint names, needs at least one value`() {
        assertEquals(AutomationCommand.Set(100, Tint.RED, 0), parse("SET", "darkness" to 250, "tint" to "red", "strength" to -1))
        assertEquals(AutomationCommand.Set(60, null, null), parse("SET", "darkness" to "60"))
        assertEquals(AutomationCommand.Set(null, Tint.CANDLE, null), parse("SET", "tint" to " Candle "))
        assertNull(parse("SET"))
        assertNull(parse("SET", "tint" to "purple"))
    }

    @Test
    fun `preset needs a name and finds by id or name`() {
        assertEquals(AutomationCommand.ApplyPreset("Deep red"), parse("PRESET", "name" to " Deep red "))
        assertNull(parse("PRESET"))
        assertNull(parse("PRESET", "name" to "  "))
        assertNull(parse("PRESET", "name" to 5))
        val list = Presets.DEFAULTS
        assertEquals("deep_red", AutomationCommand.findPreset(list, "deep red")?.id)
        assertEquals("deep_red", AutomationCommand.findPreset(list, "deep_red")?.id)
        assertNull(AutomationCommand.findPreset(list, "Disco"))
    }
}
