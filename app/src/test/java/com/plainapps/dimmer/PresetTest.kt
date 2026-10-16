package com.plainapps.dimmer

import com.plainapps.dimmer.engine.Preset
import com.plainapps.dimmer.engine.Presets
import com.plainapps.dimmer.engine.Tint
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class PresetTest {

    @Test
    fun `defaults match the spec`() {
        val d = Presets.DEFAULTS
        assertEquals(listOf("Evening", "Reading", "Candlelight", "Deep red", "Ultra dim"), d.map { it.name })
        assertEquals(Preset("evening", "Evening", 35, Tint.AMBER, 40), d[0])
        assertEquals(Preset("reading", "Reading", 45, Tint.CANDLE, 30), d[1])
        assertEquals(Preset("candlelight", "Candlelight", 60, Tint.CANDLE, 80), d[2])
        assertEquals(Preset("deep_red", "Deep red", 70, Tint.RED, 100), d[3])
        assertEquals(Preset("ultra_dim", "Ultra dim", 90, Tint.NEUTRAL, 0), d[4])
        assertEquals(d.size, d.map { it.id }.toSet().size)
        assertTrue(d.size <= Presets.MAX)
    }

    @Test
    fun `default tile cycle is Evening Candlelight Deep red Ultra dim and all ids exist`() {
        assertEquals(listOf("evening", "candlelight", "deep_red", "ultra_dim"), Presets.DEFAULT_TILE_CYCLE)
        assertEquals(Presets.DEFAULT_TILE_CYCLE, Presets.reconcileCycle(Presets.DEFAULT_TILE_CYCLE, Presets.DEFAULTS))
    }

    @Test
    fun `cleanName trims, collapses, strips control characters and cuts to the limit`() {
        assertEquals("a b", Presets.cleanName("  a \n\t  b  "))
        assertEquals("", Presets.cleanName("   \n"))
        assertEquals(Presets.MAX_NAME_LENGTH, Presets.cleanName("x".repeat(100)).length)
    }

    @Test
    fun `add appends with a fresh id and clamps values`() {
        val p = assertNotNull(Presets.add(Presets.DEFAULTS, " Night ", 500, Tint.RED, -9))
        assertEquals("p1", p.id)
        assertEquals("Night", p.name)
        assertEquals(100, p.darkness)
        assertEquals(0, p.tintStrength)
    }

    @Test
    fun `add refuses blank names and a ninth preset`() {
        assertNull(Presets.add(Presets.DEFAULTS, "   ", 10, Tint.RED, 10))
        var list = Presets.DEFAULTS
        while (list.size < Presets.MAX) list = list + Presets.add(list, "n${list.size}", 1, Tint.NEUTRAL, 0)!!
        assertEquals(Presets.MAX, list.size)
        assertNull(Presets.add(list, "one too many", 1, Tint.NEUTRAL, 0))
    }

    @Test
    fun `new ids never collide, even after deletes`() {
        var list = Presets.DEFAULTS
        val a = Presets.add(list, "a", 1, Tint.RED, 1)!!
        list = list + a
        val b = Presets.add(list, "b", 1, Tint.RED, 1)!!
        list = list + b
        assertEquals(listOf("p1", "p2"), listOf(a.id, b.id))
        list = Presets.delete(list, "p1")
        assertEquals("p1", Presets.newId(list)) // freed id reused, still unique in the list
        assertEquals(list.size, list.map { it.id }.toSet().size)
    }

    @Test
    fun `rename keeps values, ignores blank and unknown ids`() {
        val renamed = Presets.rename(Presets.DEFAULTS, "evening", "  Dusk ")
        assertEquals("Dusk", renamed[0].name)
        assertEquals(Presets.DEFAULTS[0].copy(name = "Dusk"), renamed[0])
        assertEquals(Presets.DEFAULTS, Presets.rename(Presets.DEFAULTS, "evening", "  "))
        assertEquals(Presets.DEFAULTS, Presets.rename(Presets.DEFAULTS, "nope", "x"))
    }

    @Test
    fun `updateValues changes only that preset`() {
        val u = Presets.updateValues(Presets.DEFAULTS, "reading", 12, Tint.RED, 99)
        assertEquals(Preset("reading", "Reading", 12, Tint.RED, 99), u[1])
        assertEquals(Presets.DEFAULTS[0], u[0])
        assertEquals(Presets.DEFAULTS.size, u.size)
    }

    @Test
    fun `delete removes it, and the tile cycle follows`() {
        val presets = Presets.delete(Presets.DEFAULTS, "candlelight")
        assertEquals(4, presets.size)
        assertEquals(
            listOf("evening", "deep_red", "ultra_dim"),
            Presets.reconcileCycle(Presets.DEFAULT_TILE_CYCLE, presets),
        )
    }

    @Test
    fun `tile cycle toggling keeps preset order and ignores unknown ids`() {
        var cycle = Presets.DEFAULT_TILE_CYCLE
        cycle = Presets.setInCycle(cycle, Presets.DEFAULTS, "reading", true)
        assertEquals(listOf("evening", "reading", "candlelight", "deep_red", "ultra_dim"), cycle)
        cycle = Presets.setInCycle(cycle, Presets.DEFAULTS, "evening", false)
        assertEquals(listOf("reading", "candlelight", "deep_red", "ultra_dim"), cycle)
        assertEquals(cycle, Presets.setInCycle(cycle, Presets.DEFAULTS, "ghost", true))
        cycle = Presets.setInCycle(cycle, Presets.DEFAULTS, "reading", true) // already in: no duplicate
        assertEquals(1, cycle.count { it == "reading" })
    }

    @Test
    fun `cycle encode and decode round trip, bad ids dropped, empty is empty`() {
        assertEquals(Presets.DEFAULT_TILE_CYCLE, Presets.decodeCycle(Presets.encodeCycle(Presets.DEFAULT_TILE_CYCLE)))
        assertEquals(emptyList(), Presets.decodeCycle(Presets.encodeCycle(emptyList())))
        assertEquals(listOf("a1"), Presets.decodeCycle("a1,BAD ID,,a1"))
    }

    @Test
    fun `next in cycle goes off, first, next, last, off`() {
        val c = listOf("evening", "candlelight", "deep_red")
        assertEquals("evening", Presets.nextInCycle(c, enabled = false, activeId = "candlelight"))
        assertEquals("candlelight", Presets.nextInCycle(c, enabled = true, activeId = "evening"))
        assertEquals("deep_red", Presets.nextInCycle(c, enabled = true, activeId = "candlelight"))
        assertNull(Presets.nextInCycle(c, enabled = true, activeId = "deep_red"))
        // A look outside the cycle (Custom or another preset) starts the cycle.
        assertEquals("evening", Presets.nextInCycle(c, enabled = true, activeId = null))
        assertEquals("evening", Presets.nextInCycle(c, enabled = true, activeId = "reading"))
        assertNull(Presets.nextInCycle(emptyList(), enabled = false, activeId = null))
    }

    // v3.1.1: translated display names of the default presets

    private val german = mapOf("evening" to "Abend", "reading" to "Lesen", "candlelight" to "Kerzenlicht",
        "deep_red" to "Tiefrot", "ultra_dim" to "Ultra dunkel")

    @Test
    fun `unrenamed default presets show the translation`() {
        val shown = Presets.displayNames(Presets.DEFAULTS) { german[it] }.map { it.name }
        assertEquals(listOf("Abend", "Lesen", "Kerzenlicht", "Tiefrot", "Ultra dunkel"), shown)
        // values and ids are untouched
        assertEquals(Presets.DEFAULTS.map { it.id }, Presets.displayNames(Presets.DEFAULTS) { german[it] }.map { it.id })
    }

    @Test
    fun `renamed defaults and user presets keep their stored name`() {
        val renamed = Presets.rename(Presets.DEFAULTS, "reading", "Bed")
        assertEquals("Bed", Presets.displayName(Presets.find(renamed, "reading")!!) { german[it] })
        val own = Presets.add(Presets.DEFAULTS, "Evening", 10, Tint.AMBER, 10)!!   // own preset named like a default
        assertEquals("Evening", Presets.displayName(own) { german[it] })
    }

    @Test
    fun `missing translation falls back to the stored name`() {
        assertEquals("Reading", Presets.displayName(Presets.DEFAULTS[1]) { null })
    }
}
