package com.plainapps.dimmer.engine

/**
 * Versioned plain-text encoding of the preset list, stored as one DataStore string.
 *
 * ```
 * v1
 * evening|Evening|35|AMBER|40
 * p1|Name with \p pipe|50|RED|100
 * ```
 * Names are escaped (`\` -> `\\`, `|` -> `\p`, newline -> `\n`, carriage return -> `\r`).
 * Decoding never throws: an unknown version or an empty string gives `null` (caller falls back
 * to defaults), malformed lines are skipped, values are clamped, duplicate ids dropped, and at
 * most [Presets.MAX] presets are kept. An empty list is a valid, deliberate state ("v1" only).
 */
object PresetCodec {

    private const val VERSION_LINE = "v1"

    fun encode(presets: List<Preset>): String =
        (listOf(VERSION_LINE) + presets.map { p ->
            listOf(p.id, escape(p.name), p.darkness.toString(), p.tint.name, p.tintStrength.toString())
                .joinToString("|")
        }).joinToString("\n")

    fun decode(text: String?): List<Preset>? {
        if (text == null) return null
        val lines = text.split('\n')
        if (lines.firstOrNull()?.trim() != VERSION_LINE) return null
        val out = mutableListOf<Preset>()
        for (line in lines.drop(1)) {
            if (line.isBlank()) continue
            val preset = decodeLine(line) ?: continue
            if (out.any { it.id == preset.id }) continue
            out += preset
            if (out.size == Presets.MAX) break
        }
        return out
    }

    private fun decodeLine(line: String): Preset? {
        val parts = line.split('|')
        if (parts.size != 5) return null
        val id = parts[0]
        if (!id.matches(Presets.ID_REGEX)) return null
        val name = Presets.cleanName(unescape(parts[1]) ?: return null)
        if (name.isEmpty()) return null
        val darkness = parts[2].toIntOrNull() ?: return null
        val tint = Tint.fromName(parts[3]) ?: return null
        val strength = parts[4].toIntOrNull() ?: return null
        return Presets.sanitize(Preset(id, name, darkness, tint, strength))
    }

    internal fun escape(s: String): String = buildString {
        for (c in s) when (c) {
            '\\' -> append("\\\\")
            '|' -> append("\\p")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            else -> append(c)
        }
    }

    /** Null on a dangling or unknown escape (corrupt line). */
    internal fun unescape(s: String): String? = buildString {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '\\') {
                append(c)
                i++
                continue
            }
            if (i + 1 >= s.length) return null
            when (s[i + 1]) {
                '\\' -> append('\\')
                'p' -> append('|')
                'n' -> append('\n')
                'r' -> append('\r')
                else -> return null
            }
            i += 2
        }
    }
}
