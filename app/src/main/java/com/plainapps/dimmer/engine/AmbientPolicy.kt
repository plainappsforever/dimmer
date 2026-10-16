package com.plainapps.dimmer.engine

/** What the room light asks the dimmer to do (SPEC 8). NORMAL = show the chosen look. */
enum class AmbientState { NORMAL, DARK_ROOM, BRIGHT }

/**
 * Pure hysteresis for "Adjust to room light" (v3 P6, SPEC 8, D-V3-39). Fed lux readings and clock
 * ticks; every change of state must hold for [dwellMs] first, so a passing shadow or a lamp
 * switched on for a moment changes nothing.
 *
 * Enter DARK_ROOM below [DARK_LUX], leave it at [DARK_EXIT_LUX] or more (the band stops flicker
 * around 3 lux). Enter BRIGHT above [BRIGHT_LUX], leave it below [BRIGHT_EXIT_LUX]. Anything in
 * between is NORMAL. Light sensors report only when the value changes, so the caller must also
 * call [tick] (about once a second) for a dwell to complete without a new reading.
 */
class AmbientPolicy(private val dwellMs: Long = DWELL_MS) {

    var state: AmbientState = AmbientState.NORMAL
        private set

    private var lux: Float? = null
    private var candidate: AmbientState? = null
    private var candidateSinceMs = 0L

    /** v3.1: the next reading is the first since the sensor was (re)started (screen on). */
    private var firstReading = false

    fun reset() {
        state = AmbientState.NORMAL
        lux = null
        candidate = null
        firstReading = false
    }

    /** Forgets the last reading and any pending change, keeps the state (screen was off: the sensor was not read). */
    fun clearReading() {
        lux = null
        candidate = null
    }

    /**
     * The sensor is read again (screen on, or the session started): forget the old reading, keep
     * the state, and let the first reading leave BRIGHT at once if the room is no longer bright
     * (v3.1, REVIEW_V3 finding 6: waking the phone in a dark bedroom after a bright room must not
     * stay undimmed for the 10 s dwell). Only the darker direction skips the dwell.
     */
    fun restartReading() {
        clearReading()
        firstReading = true
    }

    /** Feeds one reading; returns the (possibly new) state. */
    fun onLux(nowMs: Long, value: Float): AmbientState {
        if (value.isNaN() || value < 0f) return state
        lux = value
        if (firstReading) {
            firstReading = false
            if (state == AmbientState.BRIGHT && value < BRIGHT_EXIT_LUX) {
                state = AmbientState.NORMAL
                candidate = null
            }
        }
        return evaluate(nowMs)
    }

    /** Lets a running dwell complete without a new reading. */
    fun tick(nowMs: Long): AmbientState = evaluate(nowMs)

    /** When a pending change would take effect (epoch ms), or null when nothing is pending. */
    fun pendingAtMs(): Long? = candidate?.let { candidateSinceMs + dwellMs }

    private fun target(l: Float): AmbientState = when (state) {
        AmbientState.NORMAL -> when {
            l > BRIGHT_LUX -> AmbientState.BRIGHT
            l < DARK_LUX -> AmbientState.DARK_ROOM
            else -> AmbientState.NORMAL
        }
        AmbientState.DARK_ROOM -> when {
            l > BRIGHT_LUX -> AmbientState.BRIGHT
            l >= DARK_EXIT_LUX -> AmbientState.NORMAL
            else -> AmbientState.DARK_ROOM
        }
        AmbientState.BRIGHT -> when {
            l < DARK_LUX -> AmbientState.DARK_ROOM
            l < BRIGHT_EXIT_LUX -> AmbientState.NORMAL
            else -> AmbientState.BRIGHT
        }
    }

    private fun evaluate(nowMs: Long): AmbientState {
        val l = lux ?: return state
        val want = target(l)
        if (want == state) {
            candidate = null
            return state
        }
        if (candidate != want) {
            candidate = want
            candidateSinceMs = nowMs
        }
        if (nowMs - candidateSinceMs >= dwellMs) {
            state = want
            candidate = null
        }
        return state
    }

    companion object {
        const val DWELL_MS = 10_000L
        const val DARK_LUX = 3f
        const val DARK_EXIT_LUX = 6f
        const val BRIGHT_LUX = 150f
        const val BRIGHT_EXIT_LUX = 80f
    }
}

/** What the service renders for a room-light state (v3 P6): pure so the rules are tested. */
object AmbientLook {
    /** The overlay is suspended (backlight back to normal) in a bright room. */
    fun suspended(enabled: Boolean, state: AmbientState): Boolean = enabled && state == AmbientState.BRIGHT

    /**
     * The preset to render instead of the chosen look: the dark-room preset in a dark room.
     * Null = render the chosen look (feature off, other states, or the preset was deleted).
     */
    fun override(enabled: Boolean, state: AmbientState, presets: List<Preset>, darkRoomPresetId: String): Preset? =
        if (enabled && state == AmbientState.DARK_ROOM) Presets.find(presets, darkRoomPresetId) else null

    /** Default dark-room preset (SPEC 8). */
    const val DEFAULT_DARK_ROOM_PRESET = "deep_red"
}
