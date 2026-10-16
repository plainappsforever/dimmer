package com.plainapps.dimmer

import com.plainapps.dimmer.engine.Preset
import com.plainapps.dimmer.engine.PresetCodec
import com.plainapps.dimmer.engine.Presets
import com.plainapps.dimmer.engine.Tint
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class PresetCodecTest {

    @Test
    fun `defaults round trip exactly`() {
        assertEquals(Presets.DEFAULTS, PresetCodec.decode(PresetCodec.encode(Presets.DEFAULTS)))
    }

    @Test
    fun `encoding is versioned plain text`() {
        val text = PresetCodec.encode(listOf(Preset("evening", "Evening", 35, Tint.AMBER, 40)))
        assertEquals("v1\nevening|Evening|35|AMBER|40", text)
    }

    @Test
    fun `an empty list is a valid deliberate state`() {
        assertEquals("v1", PresetCodec.encode(emptyList()))
        assertEquals(emptyList(), PresetCodec.decode("v1"))
    }

    @Test
    fun `nasty names survive the round trip`() {
        // Names go through cleanName on the way in, so control characters become spaces;
        // pipes, backslashes, escape look-alikes, quotes and unicode must survive verbatim.
        val names = listOf("a|b", "back\\slash", "\\p literal", "\\n literal", "é ü 日本", "say \"hi\"", "|", "\\")
        val presets = names.mapIndexed { i, n -> Preset("p${i + 1}", n, 10 + i, Tint.entries[i % 4], 5 * i) }
        val decoded = assertNotNull(PresetCodec.decode(PresetCodec.encode(presets)))
        assertEquals(presets.map { it.name }, decoded.map { it.name })
        assertEquals(presets, decoded)
    }

    @Test
    fun `escape and unescape are inverse for arbitrary strings including newlines`() {
        val samples = listOf("", "plain", "a|b|c", "\\\\", "line1\nline2\r\n", "\\|\\n", "trailing\\")
        for (s in samples) assertEquals(s, PresetCodec.unescape(PresetCodec.escape(s)), "sample=$s")
        assertTrue(PresetCodec.escape("a|b\nc").none { it == '|' || it == '\n' })
    }

    @Test
    fun `unknown or missing version gives null so the caller uses defaults`() {
        assertNull(PresetCodec.decode(null))
        assertNull(PresetCodec.decode(""))
        assertNull(PresetCodec.decode("v2\nevening|Evening|35|AMBER|40"))
        assertNull(PresetCodec.decode("evening|Evening|35|AMBER|40"))
    }

    @Test
    fun `malformed lines are skipped, good ones kept`() {
        val text = listOf(
            "v1",
            "ok|Fine|30|RED|50",
            "too|few|fields",
            "bad id|Name|30|RED|50",
            "num|Name|abc|RED|50",
            "tint|Name|30|PURPLE|50",
            "esc|Bad\\qescape|30|RED|50",
            "blank|   |30|RED|50",
            "",
            "also_ok|Second|60|CANDLE|70",
        ).joinToString("\n")
        val decoded = assertNotNull(PresetCodec.decode(text))
        assertEquals(listOf("ok", "also_ok"), decoded.map { it.id })
    }

    @Test
    fun `values are clamped and duplicate ids dropped`() {
        val decoded = assertNotNull(
            PresetCodec.decode("v1\na|One|500|RED|-20\na|Dupe|1|RED|1\nb|Two|-5|AMBER|900"),
        )
        assertEquals(listOf(Preset("a", "One", 100, Tint.RED, 0), Preset("b", "Two", 0, Tint.AMBER, 100)), decoded)
    }

    @Test
    fun `at most eight presets are kept`() {
        val text = "v1\n" + (1..12).joinToString("\n") { "p$it|N$it|10|RED|10" }
        assertEquals(Presets.MAX, assertNotNull(PresetCodec.decode(text)).size)
    }

    @Test
    fun `garbage never throws`() {
        val junk = listOf("v1\n|||||", "v1\n\u0000\u0001", "v1\n" + "|".repeat(1000), "￿", "v1\r\np|x|1|RED|1")
        for (j in junk) PresetCodec.decode(j) // must not throw
        // CRLF line ends (a file edited on Windows) are tolerated on the version line
        assertEquals(1, PresetCodec.decode("v1\r\np|x|1|RED|1")?.size)
    }
}
