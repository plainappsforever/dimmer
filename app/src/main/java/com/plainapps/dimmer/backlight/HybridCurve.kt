package com.plainapps.dimmer.backlight

import kotlin.math.roundToInt

/**
 * Pure maths for "hybrid" dimming: one darkness scale drives both the real backlight and a
 * dark overlay. No Android dependencies, JVM-tested in HybridCurveTest.
 *
 * With hybrid on:
 * - darkness 0            -> no backlight override at all, overlay 0 (nothing on screen changes)
 * - darkness 1..knee      -> backlight moves linearly from [startBacklight] down to [FLOOR],
 *                            overlay stays fully transparent (backlight dimming costs no contrast)
 * - darkness knee..100    -> backlight held at [FLOOR], overlay rises 0..100
 * With hybrid off: no backlight override, overlay = darkness (the v2 behaviour).
 *
 * Why a knee at 20: see docs/DECISIONS.md D-V3-02 (an assumption to tune on a real phone).
 */
object HybridCurve {

    const val DEFAULT_KNEE = 20
    const val MAX_DARKNESS = 100

    /**
     * Backlight floor as a 0..1 fraction: 1/255, the lowest non-zero step of the 0..255
     * Settings scale. Never 0, because 0 may mean "backlight off" rather than "minimum" in
     * AOSP's int<->float brightness conversion (RESEARCH §2.1). Both methods use it.
     */
    const val FLOOR = 1f / 255f

    data class Output(
        /** 0..1 backlight to request, or null = "do not override the backlight". */
        val backlight: Float?,
        /** 0..100 overlay darkness to feed into the overlay colour maths. */
        val overlayLevel: Int,
    )

    fun compute(
        darkness: Int,
        hybridOn: Boolean,
        startBacklight: Float,
        knee: Int = DEFAULT_KNEE,
    ): Output {
        val d = darkness.coerceIn(0, MAX_DARKNESS)
        if (!hybridOn) return Output(backlight = null, overlayLevel = d)
        if (d == 0) return Output(backlight = null, overlayLevel = 0)

        val k = knee.coerceIn(1, MAX_DARKNESS - 1)
        if (d <= k) {
            // Unknown start (v3.1, REVIEW_V3 finding 2): never guess. A guess of "full" (the old
            // rule) or the stored manual value can be brighter than the panel is right now, e.g.
            // with adaptive brightness; no override at all can never be brighter.
            if (startBacklight.isNaN()) return Output(backlight = null, overlayLevel = 0)
            val start = sanitize(startBacklight)
            val t = d.toFloat() / k
            val backlight = start + (FLOOR - start) * t
            return Output(backlight = backlight.coerceIn(FLOOR, 1f), overlayLevel = 0)
        }
        val overlay = ((d - k) * MAX_DARKNESS.toFloat() / (MAX_DARKNESS - k)).roundToInt()
        return Output(backlight = FLOOR, overlayLevel = overlay.coerceIn(0, MAX_DARKNESS))
    }

    /** Out-of-range start values become a 0..1 value >= FLOOR (NaN = unknown is handled by the caller). */
    private fun sanitize(value: Float): Float = value.coerceIn(FLOOR, 1f)

    /** Settings.System.SCREEN_BRIGHTNESS int (0..255) for a 0..1 target, never below 1. */
    fun toSettingsInt(target: Float): Int {
        val safe = if (target.isNaN()) 1f else target.coerceIn(0f, 1f)
        return (safe * 255f).roundToInt().coerceIn(1, 255)
    }

    /**
     * 0..1 brightness float for a Settings.System.SCREEN_BRIGHTNESS int; negative (missing) = NaN,
     * unknown. v3.1: AOSP's own mapping (BrightnessSynchronizer: int 1..255 -> float 0..1, i.e.
     * (v - 1) / 254), the same float scale the window override uses, so a start value read from
     * the setting is never above the panel's real level (v / 255 was, by up to 1/255 near the
     * bottom of the range, where it matters most).
     */
    fun fromSettingsInt(value: Int): Float = when {
        value < 0 -> Float.NaN
        else -> (value.coerceIn(1, 255) - 1) / 254f
    }
}
