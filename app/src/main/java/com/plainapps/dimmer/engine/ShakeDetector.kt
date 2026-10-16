package com.plainapps.dimmer.engine

import kotlin.math.sqrt

/** How hard the phone must be shaken (SPEC 4). LOW = least sensitive = needs the hardest shake. */
enum class ShakeSensitivity(val thresholdG: Float) {
    LOW(2.6f),
    MEDIUM(2.2f),
    HIGH(1.8f),
    ;

    companion object {
        val DEFAULT = MEDIUM
        fun fromName(name: String?): ShakeSensitivity? = entries.firstOrNull { it.name == name }
    }
}

/**
 * Pure shake detection (v3 P4, SPEC 4 "two strong shakes within 1 s"). Fed raw accelerometer
 * samples (m/s², gravity included). A sample is *strong* when its magnitude exceeds the
 * sensitivity threshold (capped by the sensor range). A *stroke* starts on a strong sample that either follows a re-arm (the
 * magnitude fell below [REARM_FRACTION] × threshold and at least [MIN_SAME_DIRECTION_GAP_MS]
 * passed since the last stroke) or points against the current stroke (negative dot product =
 * the phone reversed direction). [STROKES_TO_TRIGGER] strokes (there, back, there = two shakes)
 * within [WINDOW_MS] trigger once, then the detector starts over.
 *
 * A single tilt or a single bump is at most one stroke, so it never triggers.
 */
class ShakeDetector(var sensitivity: ShakeSensitivity = ShakeSensitivity.DEFAULT) {

    /**
     * The sensor's maximum range (m/s²). A ±2 g part saturates near 2.2 g on a one-axis shake,
     * so the threshold is capped at [RANGE_FRACTION] of the range (L13 review P4 #4).
     */
    var maxRange: Float = Float.MAX_VALUE

    /** Effective threshold in m/s². */
    val threshold: Float get() = minOf(sensitivity.thresholdG * GRAVITY, maxRange * RANGE_FRACTION)

    private val strokeTimes = ArrayDeque<Long>()
    private var strokeX = 0f
    private var strokeY = 0f
    private var strokeZ = 0f
    private var inStroke = false
    private var armed = true
    private var lastStrokeMs = Long.MIN_VALUE / 2

    fun reset() {
        strokeTimes.clear()
        inStroke = false
        armed = true
        lastStrokeMs = Long.MIN_VALUE / 2
    }

    /** Adds one sample; true exactly when this sample completes a shake gesture. */
    fun add(timeMs: Long, x: Float, y: Float, z: Float): Boolean {
        val threshold = threshold
        val magnitude = sqrt(x * x + y * y + z * z)
        if (magnitude < threshold * REARM_FRACTION) {
            armed = true
            inStroke = false
            return false
        }
        if (magnitude <= threshold) return false

        // Any two strokes are at least MIN_STROKE_GAP_MS apart: a drop onto a table rings with
        // several sign flips within 20-60 ms, which must stay one stroke (L13 review P4 #5).
        if (timeMs - lastStrokeMs < MIN_STROKE_GAP_MS) return false
        val reversed = inStroke && (x * strokeX + y * strokeY + z * strokeZ) < 0f
        val rearmed = !inStroke && armed && timeMs - lastStrokeMs >= MIN_SAME_DIRECTION_GAP_MS
        if (!reversed && !rearmed) return false

        inStroke = true
        armed = false
        strokeX = x
        strokeY = y
        strokeZ = z
        lastStrokeMs = timeMs
        strokeTimes.addLast(timeMs)
        while (strokeTimes.isNotEmpty() && timeMs - strokeTimes.first() > WINDOW_MS) strokeTimes.removeFirst()
        if (strokeTimes.size >= STROKES_TO_TRIGGER) {
            // One gesture = one trigger: the rest of this shaking is ignored until the phone calms
            // down (below the re-arm level).
            strokeTimes.clear()
            inStroke = false
            armed = false
            return true
        }
        return false
    }

    companion object {
        const val GRAVITY = 9.80665f
        const val REARM_FRACTION = 0.75f
        const val MIN_SAME_DIRECTION_GAP_MS = 150L
        const val MIN_STROKE_GAP_MS = 80L
        const val RANGE_FRACTION = 0.85f
        const val STROKES_TO_TRIGGER = 3
        const val WINDOW_MS = 1_000L
    }
}
