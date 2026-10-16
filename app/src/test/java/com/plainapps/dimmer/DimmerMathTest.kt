package com.plainapps.dimmer

import com.plainapps.dimmer.engine.Tint
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class DimmerMathTest {

    // --- clampLevel ---

    @Test
    fun `clampLevel keeps values inside range unchanged`() {
        assertEquals(40, DimmerMath.clampLevel(40, 100))
    }

    @Test
    fun `clampLevel clamps negative levels to zero`() {
        assertEquals(0, DimmerMath.clampLevel(-100, 100))
        assertEquals(0, DimmerMath.clampLevel(Int.MIN_VALUE, 100))
    }

    @Test
    fun `clampLevel clamps huge levels to maxLevel`() {
        assertEquals(100, DimmerMath.clampLevel(1_000_000, 100))
        assertEquals(100, DimmerMath.clampLevel(Int.MAX_VALUE, 100))
    }

    @Test
    fun `clampLevel never trusts a caller-supplied maxLevel above 100`() {
        // Even if a bug (or corrupted DataStore value) passes a bogus ceiling, the
        // hard cap wins.
        assertEquals(100, DimmerMath.clampLevel(1_000, 500))
        assertEquals(100, DimmerMath.clampLevel(1_000, Int.MAX_VALUE))
    }

    @Test
    fun `clampLevel treats a negative maxLevel as zero`() {
        assertEquals(0, DimmerMath.clampLevel(50, -10))
    }

    // --- clampTintStrength ---

    @Test
    fun `clampTintStrength clamps to 0 through 100`() {
        assertEquals(0, DimmerMath.clampTintStrength(-1))
        assertEquals(0, DimmerMath.clampTintStrength(Int.MIN_VALUE))
        assertEquals(100, DimmerMath.clampTintStrength(101))
        assertEquals(100, DimmerMath.clampTintStrength(Int.MAX_VALUE))
        assertEquals(50, DimmerMath.clampTintStrength(50))
    }

    // --- levelToAlphaFraction ---

    @Test
    fun `levelToAlphaFraction never exceeds 1_0 for any input`() {
        val inputs = listOf(-1_000, -1, 0, 1, 50, 100, 101, 1_000, Int.MAX_VALUE, Int.MIN_VALUE)
        for (level in inputs) {
            val fraction = DimmerMath.levelToAlphaFraction(level, 100)
            assertTrue(fraction <= 1.0f, "level=$level produced fraction=$fraction")
            assertTrue(fraction >= 0f, "level=$level produced negative fraction=$fraction")
        }
    }

    @Test
    fun `levelToAlphaFraction respects a stricter maxLevel`() {
        val fraction = DimmerMath.levelToAlphaFraction(100, maxLevel = 60)
        assertEquals(0.60f, fraction, 0.001f)
    }

    // --- tintToRgb ---

    @Test
    fun `tintToRgb (Candle) at zero is black`() {
        val rgb = DimmerMath.tintToRgb(Tint.CANDLE, 0)
        assertEquals(intArrayOf(0, 0, 0).toList(), rgb.toList())
    }

    @Test
    fun `tintToRgb (Candle) at max is the dark warm endpoint, not a bright color`() {
        val rgb = DimmerMath.tintToRgb(Tint.CANDLE, 100)
        assertEquals(intArrayOf(70, 15, 0).toList(), rgb.toList())
        // The whole point of the redesign: the endpoint itself must be dark.
        assertTrue(DimmerMath.luma(rgb[0], rgb[1], rgb[2]) < 60f)
    }

    @Test
    fun `tintToRgb (Candle) channels always stay in 0 through 255 for out-of-range warmth`() {
        for (warmth in listOf(-1_000, -1, 101, 1_000, Int.MAX_VALUE, Int.MIN_VALUE)) {
            val rgb = DimmerMath.tintToRgb(Tint.CANDLE, warmth)
            for (channel in rgb) {
                assertTrue(channel in 0..255, "warmth=$warmth produced channel=$channel")
            }
        }
    }

    // --- composeArgb ---

    @Test
    fun `composeArgb packs channels into the expected bit layout`() {
        val argb = DimmerMath.composeArgb(alpha = 0x12, red = 0x34, green = 0x56, blue = 0x78)
        assertEquals(0x12345678, argb)
    }

    @Test
    fun `composeArgb clamps every channel independently`() {
        val argb = DimmerMath.composeArgb(alpha = -10, red = 300, green = -1, blue = 999)
        assertEquals(DimmerMath.composeArgb(0, 255, 0, 255), argb)
    }

    // --- effectiveAlphaFraction / overlayColor: the ceiling can never be bypassed ---

    @Test
    fun `effectiveAlphaFraction never exceeds maxLevel regardless of warmth`() {
        val inputs = listOf(-1_000, 0, 40, 79, 80, 100, 101, 1_000, Int.MAX_VALUE, Int.MIN_VALUE)
        for (maxLevel in listOf(59, 79, 100)) {
            val ceiling = maxLevel / 100f
            for (level in inputs) {
                for (warmth in listOf(0, 25, 50, 75, 100, -5, 500)) {
                    val alpha = DimmerMath.effectiveAlphaFraction(level, warmth, maxLevel)
                    assertTrue(
                        alpha <= ceiling + 0.0001f,
                        "level=$level warmth=$warmth maxLevel=$maxLevel produced alpha=$alpha > ceiling=$ceiling",
                    )
                    assertTrue(alpha >= 0f, "level=$level warmth=$warmth produced negative alpha=$alpha")
                }
            }
        }
    }

    @Test
    fun `overlayColor alpha byte can never exceed the ceiling`() {
        val inputs = listOf(-1_000, 0, 40, 79, 100, 101, 1_000, Int.MAX_VALUE, Int.MIN_VALUE)
        for (maxLevel in listOf(59, 79, 100)) {
            val ceilingAlphaByte = Math.round(maxLevel / 100f * 255f)
            for (level in inputs) {
                for (warmth in listOf(0, 50, 100, -5, 500)) {
                    val color = DimmerMath.overlayColor(level, Tint.CANDLE, warmth, maxLevel)
                    val alphaByte = (color ushr 24) and 0xFF
                    assertTrue(
                        alphaByte <= ceilingAlphaByte,
                        "level=$level warmth=$warmth maxLevel=$maxLevel produced alphaByte=$alphaByte > ceiling=$ceilingAlphaByte",
                    )
                }
            }
        }
    }

    @Test
    fun `overlayColor at zero level and zero warmth is fully transparent`() {
        val color = DimmerMath.overlayColor(0, Tint.CANDLE, 0, maxLevel = 100)
        assertEquals(0, (color ushr 24) and 0xFF)
    }

    @Test
    fun `overlayColor at max level and zero warmth is fully opaque black`() {
        val color = DimmerMath.overlayColor(100, Tint.CANDLE, 0, maxLevel = 100)
        assertEquals(255, (color ushr 24) and 0xFF)
        assertEquals(0, (color ushr 16) and 0xFF)
        assertEquals(0, (color ushr 8) and 0xFF)
        assertEquals(0, color and 0xFF)
    }

    // --- The actual bug this redesign fixes: warmth must never brighten realistic content ---

    private val sampleDestinations = listOf(
        "white" to intArrayOf(255, 255, 255),
        "light gray" to intArrayOf(200, 200, 200),
        "mid gray" to intArrayOf(128, 128, 128),
        "colored (light blue)" to intArrayOf(100, 150, 200),
        "colored (pale yellow)" to intArrayOf(230, 220, 150),
    )

    @Test
    fun `increasing warmth never increases composited luminance on realistic content`() {
        // Tolerance covers legitimate 8-bit rounding noise (alpha and RGB are each
        // independently rounded to a byte) - not a loophole for real brightening, which
        // the old bug produced in tens of luma units, not fractions of one.
        val roundingTolerance = 1.5f
        val warmthSteps = listOf(0, 25, 50, 75, 100)
        for (level in listOf(25, 50, 75)) {
            for ((name, dest) in sampleDestinations) {
                var previousLuma = Float.MAX_VALUE
                for (warmth in warmthSteps) {
                    val overlay = DimmerMath.overlayColor(level, Tint.CANDLE, warmth, maxLevel = 100)
                    val (r, g, b) = DimmerMath.compositeOver(overlay, dest[0], dest[1], dest[2])
                    val resultLuma = DimmerMath.luma(r, g, b)
                    assertTrue(
                        resultLuma <= previousLuma + roundingTolerance,
                        "level=$level dest=$name warmth=$warmth: luma rose from $previousLuma to $resultLuma",
                    )
                    previousLuma = resultLuma
                }
            }
        }
    }

    @Test
    fun `warmth has zero effect at level 0 - nothing to tint yet`() {
        // Both are fully transparent (alpha=0); the packed RGB channels may still
        // differ, but that's invisible once composited - which is what actually matters.
        val off = DimmerMath.overlayColor(0, Tint.CANDLE, 0, maxLevel = 100)
        val warm = DimmerMath.overlayColor(0, Tint.CANDLE, 100, maxLevel = 100)
        assertEquals(0, (off ushr 24) and 0xFF)
        assertEquals(0, (warm ushr 24) and 0xFF)
        for ((_, dest) in sampleDestinations) {
            val resultOff = DimmerMath.compositeOver(off, dest[0], dest[1], dest[2])
            val resultWarm = DimmerMath.compositeOver(warm, dest[0], dest[1], dest[2])
            assertEquals(resultOff.toList(), resultWarm.toList())
        }
    }

    @Test
    fun `the unavoidable residual on near-black content stays small at every level`() {
        // Alpha-blending any non-black tint over a pure-black destination necessarily
        // produces something other than pure black - that's unavoidable with a simple
        // overlay window (see docs/DECISIONS.md), not a bug. Bound how bad it can get.
        for (level in listOf(0, 25, 50, 75, 100)) {
            val worstCase = DimmerMath.compositeOver(DimmerMath.overlayColor(level, Tint.CANDLE, 100, 100), 0, 0, 0)
            val worstCaseLuma = DimmerMath.luma(worstCase[0], worstCase[1], worstCase[2])
            assertTrue(worstCaseLuma < 40f, "level=$level worst-case residual too large: $worstCaseLuma / 255")
        }
    }

    @Test
    fun `warmth compensation respects a stricter runtime maxLevel too`() {
        val alpha = DimmerMath.effectiveAlphaFraction(level = 100, tintStrength = 100, maxLevel = 60)
        assertTrue(alpha <= 0.60f + 0.0001f)
    }
}
