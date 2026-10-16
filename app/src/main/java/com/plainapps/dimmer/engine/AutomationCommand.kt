package com.plainapps.dimmer.engine

/**
 * Broadcast commands for Tasker and similar apps (v3 P4, SPEC 9). Only reachable while the user
 * has switched on "Allow other apps to control Dimmer" (the receiver component is disabled
 * otherwise, D-V3-13). Parsing is pure and forgiving about extra types (Tasker sends numbers as
 * strings), strict about ranges: every value is clamped, unknown actions are ignored.
 */
sealed interface AutomationCommand {
    data object On : AutomationCommand
    data object Off : AutomationCommand
    data object Toggle : AutomationCommand
    data class PauseFor(val minutes: Int) : AutomationCommand
    data object Resume : AutomationCommand
    data class Darker(val step: Int) : AutomationCommand
    data class Lighter(val step: Int) : AutomationCommand

    /** Any of the three may be missing; missing = unchanged. */
    data class Set(val darkness: Int?, val tint: Tint?, val strength: Int?) : AutomationCommand
    data class ApplyPreset(val nameOrId: String) : AutomationCommand
    data object NextPreset : AutomationCommand

    companion object {
        const val PREFIX = "com.plainapps.dimmer.action."
        const val EXTRA_MINUTES = "minutes"
        const val EXTRA_STEP = "step"
        const val EXTRA_DARKNESS = "darkness"
        const val EXTRA_TINT = "tint"
        const val EXTRA_STRENGTH = "strength"
        const val EXTRA_NAME = "name"

        const val DEFAULT_STEP = 10
        const val MAX_STEP = 50

        val ACTIONS = listOf("ON", "OFF", "TOGGLE", "PAUSE", "RESUME", "DARKER", "LIGHTER", "SET", "PRESET", "NEXT_PRESET")

        /** [extra] looks an extra up by key (Int, Long, String … or null). Null = not a command. */
        fun parse(action: String?, extra: (String) -> Any?): AutomationCommand? {
            if (action == null || !action.startsWith(PREFIX)) return null
            return when (action.removePrefix(PREFIX)) {
                "ON" -> On
                "OFF" -> Off
                "TOGGLE" -> Toggle
                "PAUSE" -> PauseFor(Pause.clampMinutes(int(extra(EXTRA_MINUTES)) ?: Pause.NOTIFICATION_MINUTES))
                "RESUME" -> Resume
                "DARKER" -> Darker(step(extra(EXTRA_STEP)))
                "LIGHTER" -> Lighter(step(extra(EXTRA_STEP)))
                "SET" -> {
                    val d = int(extra(EXTRA_DARKNESS))?.coerceIn(0, 100)
                    val t = tint(extra(EXTRA_TINT))
                    val s = int(extra(EXTRA_STRENGTH))?.coerceIn(0, 100)
                    if (d == null && t == null && s == null) null else Set(d, t, s)
                }
                "PRESET" -> (extra(EXTRA_NAME) as? String)?.trim()?.takeIf { it.isNotEmpty() }?.let { ApplyPreset(it.take(64)) }
                "NEXT_PRESET" -> NextPreset
                else -> null
            }
        }

        private fun step(v: Any?): Int = (int(v) ?: DEFAULT_STEP).coerceIn(1, MAX_STEP)

        internal fun int(v: Any?): Int? = when (v) {
            is Int -> v
            is Long -> v.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
            is Short -> v.toInt()
            is Float -> if (v.isNaN()) null else v.toInt()
            is Double -> if (v.isNaN()) null else v.toInt()
            is String -> v.trim().toIntOrNull()
            else -> null
        }

        private fun tint(v: Any?): Tint? = (v as? String)?.trim()?.uppercase()?.let { Tint.fromName(it) }

        /** Preset by id, else by name (case-insensitive). */
        fun findPreset(presets: List<Preset>, nameOrId: String): Preset? =
            presets.firstOrNull { it.id == nameOrId } ?: presets.firstOrNull { it.name.equals(nameOrId, ignoreCase = true) }
    }
}
