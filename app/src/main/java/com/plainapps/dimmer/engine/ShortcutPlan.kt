package com.plainapps.dimmer.engine

/** What an app shortcut asks for (static, dynamic or pinned): applies without showing the UI. */
sealed interface ShortcutCommand {
    data object Off : ShortcutCommand
    data class ApplyPreset(val presetId: String) : ShortcutCommand

    companion object {
        const val ACTION_OFF = "com.plainapps.dimmer.action.SHORTCUT_OFF"
        const val ACTION_PRESET = "com.plainapps.dimmer.action.SHORTCUT_PRESET"
        const val EXTRA_PRESET_ID = "preset_id"

        /** Null for an unknown action or a preset command without a valid id. */
        fun parse(action: String?, presetId: String?): ShortcutCommand? = when (action) {
            ACTION_OFF -> Off
            ACTION_PRESET -> presetId?.takeIf { Presets.ID_REGEX.matches(it) }?.let(::ApplyPreset)
            else -> null
        }
    }
}

/** A pinned shortcut to publish or update. */
data class ShortcutSpec(val id: String, val presetId: String, val shortLabel: String)

/** What to do with the pinned shortcuts after the preset list changed. */
data class PinnedReconcile(val update: List<ShortcutSpec>, val disableIds: List<String>)

/** Pinned per-preset shortcuts (SPEC 2 "Add to home screen", SPEC 6). Pure and JVM-tested. */
object ShortcutPlan {
    private const val PIN_PREFIX = "pin_"

    /** Unique per pin, so the same preset can be pinned twice; the preset id stays recoverable. */
    fun pinnedId(presetId: String, millis: Long) = "$PIN_PREFIX${presetId}_$millis"

    /** The preset a pinned shortcut id refers to, or null for ids we did not create. */
    fun presetIdOf(shortcutId: String): String? {
        if (!shortcutId.startsWith(PIN_PREFIX)) return null
        val rest = shortcutId.removePrefix(PIN_PREFIX)
        val cut = rest.lastIndexOf('_')
        if (cut <= 0) return null
        return rest.substring(0, cut).takeIf { Presets.ID_REGEX.matches(it) }
    }

    fun spec(shortcutId: String, preset: Preset) = ShortcutSpec(shortcutId, preset.id, preset.name)

    /**
     * Existing pinned shortcuts follow the presets: relabelled after a rename, disabled once the
     * preset is gone (a launcher keeps the icon; disabling shows "Preset deleted" and it stays
     * disabled even if the id is reused later, see D-V3-36). Ids that are not ours are ignored.
     */
    fun reconcilePinned(pinnedIds: List<String>, presets: List<Preset>): PinnedReconcile {
        val update = mutableListOf<ShortcutSpec>()
        val disable = mutableListOf<String>()
        for (id in pinnedIds) {
            val presetId = presetIdOf(id) ?: continue
            val preset = presets.firstOrNull { it.id == presetId }
            if (preset == null) disable += id else update += spec(id, preset)
        }
        return PinnedReconcile(update, disable)
    }
}
