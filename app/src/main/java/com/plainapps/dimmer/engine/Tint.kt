package com.plainapps.dimmer.engine

/**
 * The fixed tint palette (D-V3-12): four tints, no colour picker, no Kelvin. An alpha overlay
 * can only add colour, never remove blue, so a handful of dark, honest tints beat a precise
 * "temperature" that the maths cannot deliver.
 *
 * [r]/[g]/[b] is the endpoint at tint strength 100. Every endpoint is dark enough that the
 * worst case (darkness 100 and strength 100, overlay fully opaque) leaves a residual light
 * below 40/255 luma, the v2 bound; DimmerMathTest checks it per tint.
 */
enum class Tint(val r: Int, val g: Int, val b: Int) {
    /** Plain black: darkness only, tint strength has no effect. */
    NEUTRAL(0, 0, 0),
    AMBER(70, 30, 0),

    /** The v2 warm endpoint: v2 "warmth" maps to Candle strength. */
    CANDLE(70, 15, 0),

    /** Deep red for the darkest rooms. */
    RED(60, 0, 0),
    ;

    val isColored: Boolean get() = this != NEUTRAL

    companion object {
        fun fromName(name: String?): Tint? = entries.firstOrNull { it.name == name }
    }
}
