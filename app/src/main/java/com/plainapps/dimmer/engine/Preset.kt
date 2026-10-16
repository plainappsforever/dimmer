package com.plainapps.dimmer.engine

/** One saved look: darkness 0..100, a [Tint] and its strength 0..100. [id] is stable and [a-z0-9_]. */
data class Preset(
    val id: String,
    val name: String,
    val darkness: Int,
    val tint: Tint,
    val tintStrength: Int,
)

/** Pure list operations on presets (max [MAX]) and the tile-cycle id list. JVM-tested. */
object Presets {

    const val MAX = 8
    const val MAX_NAME_LENGTH = 24

    val DEFAULTS: List<Preset> = listOf(
        Preset("evening", "Evening", 35, Tint.AMBER, 40),
        Preset("reading", "Reading", 45, Tint.CANDLE, 30),
        Preset("candlelight", "Candlelight", 60, Tint.CANDLE, 80),
        Preset("deep_red", "Deep red", 70, Tint.RED, 100),
        Preset("ultra_dim", "Ultra dim", 90, Tint.NEUTRAL, 0),
    )

    /** D-V3-15: the tile cycle defaults to Evening, Candlelight, Deep red, Ultra dim. */
    val DEFAULT_TILE_CYCLE: List<String> = listOf("evening", "candlelight", "deep_red", "ultra_dim")

    /**
     * v3.1.1 (translations): the five default presets keep their English name in storage (automation PRESET by name,
     * backups and the codec stay language-neutral). While the user has not renamed one, the UI shows [translated] for
     * its id instead; a renamed preset (or one whose translation is missing) shows its stored name.
     */
    fun displayName(preset: Preset, translated: (id: String) -> String?): String {
        val default = DEFAULTS.firstOrNull { it.id == preset.id } ?: return preset.name
        return if (preset.name == default.name) translated(preset.id) ?: preset.name else preset.name
    }

    /** [displayName] for a whole list (widget buttons, shortcuts). */
    fun displayNames(list: List<Preset>, translated: (id: String) -> String?): List<Preset> =
        list.map { p -> displayName(p, translated).let { if (it == p.name) p else p.copy(name = it) } }

    /** Control characters become spaces, runs of spaces collapse, trimmed and cut to [MAX_NAME_LENGTH]. */
    fun cleanName(raw: String): String =
        raw.map { if (it.isISOControl()) ' ' else it }
            .joinToString("")
            .replace(Regex(" {2,}"), " ")
            .trim()
            .take(MAX_NAME_LENGTH)
            .trim()

    fun sanitize(preset: Preset): Preset = preset.copy(
        name = cleanName(preset.name),
        darkness = preset.darkness.coerceIn(0, 100),
        tintStrength = preset.tintStrength.coerceIn(0, 100),
    )

    /** Smallest unused `p1`, `p2`, ... id: deterministic, no clock needed. */
    fun newId(existing: List<Preset>): String {
        val used = existing.map { it.id }.toSet()
        var n = 1
        while ("p$n" in used) n++
        return "p$n"
    }

    /** Appends a new preset; null when the list is full or the name is blank. */
    fun add(list: List<Preset>, name: String, darkness: Int, tint: Tint, tintStrength: Int): Preset? {
        if (list.size >= MAX) return null
        val clean = cleanName(name)
        if (clean.isEmpty()) return null
        return sanitize(Preset(newId(list), clean, darkness, tint, tintStrength))
    }

    /** Unchanged list when the id is unknown or the new name is blank. */
    fun rename(list: List<Preset>, id: String, name: String): List<Preset> {
        val clean = cleanName(name)
        if (clean.isEmpty()) return list
        return list.map { if (it.id == id) it.copy(name = clean) else it }
    }

    fun updateValues(list: List<Preset>, id: String, darkness: Int, tint: Tint, tintStrength: Int): List<Preset> =
        list.map { if (it.id == id) sanitize(it.copy(darkness = darkness, tint = tint, tintStrength = tintStrength)) else it }

    fun delete(list: List<Preset>, id: String): List<Preset> = list.filterNot { it.id == id }

    fun find(list: List<Preset>, id: String?): Preset? = if (id == null) null else list.firstOrNull { it.id == id }

    // --- tile cycle (ids, in preset order) ---

    /** Keeps only ids that still exist, in the order of [presets]. */
    fun reconcileCycle(cycle: List<String>, presets: List<Preset>): List<String> {
        val wanted = cycle.toSet()
        return presets.map { it.id }.filter { it in wanted }
    }

    fun setInCycle(cycle: List<String>, presets: List<Preset>, id: String, included: Boolean): List<String> {
        val next = if (included) cycle + id else cycle - id
        return reconcileCycle(next, presets)
    }

    /**
     * Next step of the cycle Off → first → … → last → Off (v3 P4 automation NEXT_PRESET; the P5
     * preset tile uses the same order). Returns the preset id to apply, or null = turn off.
     * Off, or on with a look that is not in the cycle, goes to the first entry.
     */
    fun nextInCycle(cycle: List<String>, enabled: Boolean, activeId: String?): String? {
        if (cycle.isEmpty()) return null
        if (!enabled) return cycle.first()
        val i = cycle.indexOf(activeId)
        return when {
            i < 0 -> cycle.first()
            i == cycle.lastIndex -> null
            else -> cycle[i + 1]
        }
    }

    fun encodeCycle(cycle: List<String>): String = cycle.joinToString(",")

    fun decodeCycle(text: String): List<String> = text.split(',').filter { it.matches(ID_REGEX) }.distinct()

    internal val ID_REGEX = Regex("[a-z0-9_]{1,32}")
}
