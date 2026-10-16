package com.plainapps.dimmer

import com.plainapps.dimmer.engine.Tint
import kotlin.math.roundToInt

/**
 * Pure color/clamping math for the overlay. No Android dependencies, so this
 * is exercised directly by JVM unit tests.
 *
 * v2: the overlay is now a trusted `TYPE_ACCESSIBILITY_OVERLAY` window (added by
 * `DimmerAccessibilityService`), which is exempt from the touch-blocking opacity
 * ceiling that constrained the old `TYPE_APPLICATION_OVERLAY` to 79%. There is no
 * device-dependent ceiling to read anymore, so DEFAULT_MAX_LEVEL is a plain 100.
 * See docs/DECISIONS.md for the touch-safety reasoning this replaced.
 */
object DimmerMath {

    const val DEFAULT_MAX_LEVEL = 100
    const val MIN_LEVEL = 0
    const val MIN_TINT_STRENGTH = 0
    const val MAX_TINT_STRENGTH = 100

    // The tint endpoints live in engine/Tint.kt (v3 P2: Neutral, Amber, Candle, Red). Candle is the
    // v2 warm endpoint (70, 15, 0). Deliberately dark, not a bright orange: alpha-blending ANY
    // non-black color over content necessarily adds some light back in - there is no way for a
    // simple overlay window to avoid this entirely (it can't "multiply" against content it doesn't
    // control the pixels of). Picked dark enough that even the worst case - darkness=100 AND
    // strength=100 simultaneously, where no compensation headroom is left below the ceiling -
    // stays a small, bounded residual (luma < 40/255 for every tint, tested per tint) rather than
    // the dramatic brightening the old (255, 60, 0) endpoint caused at every level. See
    // effectiveAlphaFraction and docs/DECISIONS.md.

    // How much extra opacity warmth adds, as a fraction of the CURRENT dimming alpha
    // (not of unused ceiling headroom) - so tint has zero effect at level=0 (there is
    // nothing yet to tint) and scales with however much dimming is already active,
    // rather than being able to conjure opacity out of nowhere just because the level
    // slider happens to be far from its ceiling. Counteracts the light the tint's color
    // adds on realistic (non-near-black) content; see effectiveAlphaFraction.
    private const val TINT_COMPENSATION_FACTOR = 0.3f

    /** Clamps [level] into [0, maxLevel], where maxLevel itself can never exceed DEFAULT_MAX_LEVEL. */
    fun clampLevel(level: Int, maxLevel: Int = DEFAULT_MAX_LEVEL): Int {
        val safeMax = maxLevel.coerceIn(MIN_LEVEL, DEFAULT_MAX_LEVEL)
        return level.coerceIn(MIN_LEVEL, safeMax)
    }

    fun clampTintStrength(strength: Int): Int = strength.coerceIn(MIN_TINT_STRENGTH, MAX_TINT_STRENGTH)

    /** Darkness (0..maxLevel) as an alpha fraction, ignoring tint compensation. */
    fun levelToAlphaFraction(level: Int, maxLevel: Int = DEFAULT_MAX_LEVEL): Float =
        clampLevel(level, maxLevel) / 100f

    /** Tint strength (0..100) as an (r, g, b) triple lerped from black to the tint's dark endpoint. */
    fun tintToRgb(tint: Tint, strength: Int): IntArray {
        val fraction = clampTintStrength(strength) / 100f
        return intArrayOf(
            lerpChannel(0, tint.r, fraction),
            lerpChannel(0, tint.g, fraction),
            lerpChannel(0, tint.b, fraction),
        )
    }

    private fun lerpChannel(from: Int, to: Int, fraction: Float): Int =
        (from + (to - from) * fraction).roundToInt().coerceIn(0, 255)

    /** Packs alpha/red/green/blue (each clamped to 0..255) into a single ARGB int. */
    fun composeArgb(alpha: Int, red: Int, green: Int, blue: Int): Int {
        val a = alpha.coerceIn(0, 255)
        val r = red.coerceIn(0, 255)
        val g = green.coerceIn(0, 255)
        val b = blue.coerceIn(0, 255)
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** Standard perceptual luma (0..255) of an (r, g, b) triple (Rec. 601 weights). */
    fun luma(red: Int, green: Int, blue: Int): Float =
        0.299f * red.coerceIn(0, 255) + 0.587f * green.coerceIn(0, 255) + 0.114f * blue.coerceIn(0, 255)

    /**
     * The alpha fraction actually used for the overlay: [level]'s own alpha, plus a
     * little extra opacity as [tintStrength] increases - proportional to that same alpha, so
     * a tint has no effect at level=0 and never exceeds [maxLevel] - to counteract the
     * light the tint's color adds. A small residual is physically unavoidable on
     * near-black content even with compensation (any non-black tint blended over pure
     * black is, by definition, no longer pure black); see the class doc and
     * docs/DECISIONS.md for why that's a documented trade-off, not a bug.
     */
    fun effectiveAlphaFraction(level: Int, tintStrength: Int, maxLevel: Int = DEFAULT_MAX_LEVEL): Float {
        val baseAlpha = levelToAlphaFraction(level, maxLevel)
        val ceilingFraction = maxLevel.coerceIn(MIN_LEVEL, DEFAULT_MAX_LEVEL) / 100f
        val strengthFraction = clampTintStrength(tintStrength) / 100f
        val compensation = (baseAlpha * strengthFraction * TINT_COMPENSATION_FACTOR).coerceAtLeast(0f)
        return (baseAlpha + compensation).coerceIn(0f, ceilingFraction)
    }

    /**
     * Minimum overlay alpha that keeps a tint visible while hybrid mode is in its backlight-only
     * range (darkness 1..knee), where the curve's overlay level is 0 (v3 P2, resolves the P1 L11
     * row). Ramps 0 -> [TINT_ONLY_MAX_ALPHA] * strength over darkness 0..[knee], then stays put
     * (the overlay's own alpha takes over once it exceeds it). 0 for Neutral, strength 0, darkness 0.
     * Only the hybrid path passes this to [overlayColor]; plain overlay dimming does not need it.
     */
    fun tintOnlyAlpha(darkness: Int, tint: Tint, tintStrength: Int, knee: Int = 20): Float {
        if (!tint.isColored) return 0f
        val d = darkness.coerceIn(0, 100)
        val k = knee.coerceIn(1, 99)
        val ramp = minOf(1f, d.toFloat() / k)
        return TINT_ONLY_MAX_ALPHA * (clampTintStrength(tintStrength) / 100f) * ramp
    }

    /** Alpha ceiling of the tint-only layer (a fraction of full opacity). */
    const val TINT_ONLY_MAX_ALPHA = 0.2f

    /**
     * The single entry point every overlay-color code path must use. [level] is the overlay part
     * of the darkness (from HybridCurve); [minTintAlpha] is [tintOnlyAlpha] when hybrid is active,
     * else 0. The result never exceeds the ceiling. Neutral ignores [tintStrength] entirely.
     */
    fun overlayColor(
        level: Int,
        tint: Tint,
        tintStrength: Int,
        maxLevel: Int = DEFAULT_MAX_LEVEL,
        minTintAlpha: Float = 0f,
    ): Int {
        val strength = if (tint.isColored) tintStrength else 0
        val ceilingFraction = maxLevel.coerceIn(MIN_LEVEL, DEFAULT_MAX_LEVEL) / 100f
        val alphaFraction = maxOf(effectiveAlphaFraction(level, strength, maxLevel), minTintAlpha)
            .coerceIn(0f, ceilingFraction)
        val alpha = (alphaFraction * 255f).roundToInt()
        val (r, g, b) = tintToRgb(tint, strength).let { Triple(it[0], it[1], it[2]) }
        return composeArgb(alpha, r, g, b)
    }

    /**
     * SRC_OVER-composites [overlayArgb] (from [overlayColor]) on top of an opaque
     * destination pixel, returning the resulting (r, g, b). Pure math; used by tests
     * to confirm a tint never meaningfully brightens realistic content.
     */
    fun compositeOver(overlayArgb: Int, destinationRed: Int, destinationGreen: Int, destinationBlue: Int): IntArray {
        val a = ((overlayArgb ushr 24) and 0xFF) / 255f
        val r = (overlayArgb ushr 16) and 0xFF
        val g = (overlayArgb ushr 8) and 0xFF
        val b = overlayArgb and 0xFF
        return intArrayOf(
            (r * a + destinationRed * (1 - a)).roundToInt().coerceIn(0, 255),
            (g * a + destinationGreen * (1 - a)).roundToInt().coerceIn(0, 255),
            (b * a + destinationBlue * (1 - a)).roundToInt().coerceIn(0, 255),
        )
    }
}
