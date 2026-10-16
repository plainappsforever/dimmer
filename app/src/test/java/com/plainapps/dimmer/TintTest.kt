package com.plainapps.dimmer

import com.plainapps.dimmer.backlight.HybridCurve
import com.plainapps.dimmer.engine.Tint
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

/** Per-tint checks (D-V3-12) and the tint-only layer that keeps a tint visible at darkness 1..20. */
class TintTest {

    private val realistic = listOf(
        intArrayOf(255, 255, 255),
        intArrayOf(200, 200, 200),
        intArrayOf(128, 128, 128),
        intArrayOf(100, 150, 200),
        intArrayOf(230, 220, 150),
    )

    private fun argbAlpha(argb: Int) = (argb ushr 24) and 0xFF

    @Test
    fun `palette endpoints`() {
        assertEquals(listOf(0, 0, 0), DimmerMath.tintToRgb(Tint.NEUTRAL, 100).toList())
        assertEquals(listOf(70, 30, 0), DimmerMath.tintToRgb(Tint.AMBER, 100).toList())
        assertEquals(listOf(70, 15, 0), DimmerMath.tintToRgb(Tint.CANDLE, 100).toList()) // = v2 warm endpoint
        assertEquals(listOf(60, 0, 0), DimmerMath.tintToRgb(Tint.RED, 100).toList())
    }

    @Test
    fun `strength zero is black for every tint and half strength is half the endpoint`() {
        for (t in Tint.entries) {
            assertEquals(listOf(0, 0, 0), DimmerMath.tintToRgb(t, 0).toList(), "$t")
            val half = DimmerMath.tintToRgb(t, 50)
            assertEquals(Math.round(t.r / 2f), half[0], "$t r")
        }
    }

    @Test
    fun `every tint endpoint is dark`() {
        for (t in Tint.entries) {
            assertTrue(DimmerMath.luma(t.r, t.g, t.b) < 40f, "$t endpoint luma too high")
        }
    }

    @Test
    fun `residual light on black stays under 40 of 255 for every tint at every darkness`() {
        for (t in Tint.entries) {
            for (darkness in 0..100 step 5) {
                for (strength in listOf(0, 50, 100)) {
                    val argb = DimmerMath.overlayColor(darkness, t, strength, 100)
                    val out = DimmerMath.compositeOver(argb, 0, 0, 0)
                    val luma = DimmerMath.luma(out[0], out[1], out[2])
                    assertTrue(luma < 40f, "$t darkness=$darkness strength=$strength residual=$luma")
                }
            }
        }
    }

    @Test
    fun `raising strength never brightens realistic content for any tint`() {
        for (t in Tint.entries) {
            // Candle and Red: 8-bit rounding only (v2's 1.5). Amber has a brighter endpoint
            // (luma 38.5 vs Candle 29.7), so in the extreme corner (darkness 75, mid-grey content,
            // strength 75 -> 100) the tint's own light exceeds the extra dimming by up to ~3.6/255
            // luma. That is bounded, physical (D-V3-22) and nowhere near v2's old tens-of-units bug.
            val tolerance = if (t == Tint.AMBER) 4f else 1.5f
            for (darkness in listOf(25, 50, 75)) {
                for (dest in realistic) {
                    var previous = Float.MAX_VALUE
                    for (strength in listOf(0, 25, 50, 75, 100)) {
                        val argb = DimmerMath.overlayColor(darkness, t, strength, 100)
                        val o = DimmerMath.compositeOver(argb, dest[0], dest[1], dest[2])
                        val luma = DimmerMath.luma(o[0], o[1], o[2])
                        assertTrue(luma <= previous + tolerance, "$t d=$darkness dest=${dest.toList()} s=$strength")
                        previous = luma
                    }
                }
            }
        }
    }

    @Test
    fun `neutral ignores strength completely`() {
        for (darkness in listOf(0, 30, 70, 100)) {
            val base = DimmerMath.overlayColor(darkness, Tint.NEUTRAL, 0, 100)
            for (strength in listOf(25, 100)) {
                assertEquals(base, DimmerMath.overlayColor(darkness, Tint.NEUTRAL, strength, 100))
            }
        }
    }

    @Test
    fun `tintOnlyAlpha is zero for neutral, zero strength and darkness zero`() {
        assertEquals(0f, DimmerMath.tintOnlyAlpha(10, Tint.NEUTRAL, 100))
        assertEquals(0f, DimmerMath.tintOnlyAlpha(10, Tint.AMBER, 0))
        assertEquals(0f, DimmerMath.tintOnlyAlpha(0, Tint.AMBER, 100))
    }

    @Test
    fun `tintOnlyAlpha ramps over the knee then holds, and never exceeds its cap`() {
        var previous = 0f
        for (d in 0..100) {
            val a = DimmerMath.tintOnlyAlpha(d, Tint.CANDLE, 100)
            assertTrue(a >= previous - 1e-6f, "not monotonic at $d")
            assertTrue(a <= DimmerMath.TINT_ONLY_MAX_ALPHA + 1e-6f)
            previous = a
        }
        assertEquals(DimmerMath.TINT_ONLY_MAX_ALPHA, DimmerMath.tintOnlyAlpha(20, Tint.CANDLE, 100), 1e-6f)
        assertEquals(DimmerMath.TINT_ONLY_MAX_ALPHA / 2, DimmerMath.tintOnlyAlpha(10, Tint.CANDLE, 100), 1e-6f)
        assertEquals(DimmerMath.TINT_ONLY_MAX_ALPHA / 2, DimmerMath.tintOnlyAlpha(100, Tint.CANDLE, 50), 1e-6f)
    }

    /** The service's rule: floor only while the hybrid curve has a backlight part. */
    private fun hybridColor(darkness: Int, tint: Tint, strength: Int, hybridOn: Boolean = true): Int {
        val curve = HybridCurve.compute(darkness, hybridOn, 0.8f)
        val floor = if (curve.backlight != null) DimmerMath.tintOnlyAlpha(darkness, tint, strength) else 0f
        return DimmerMath.overlayColor(curve.overlayLevel, tint, strength, 100, floor)
    }

    @Test
    fun `hybrid on - a coloured tint is visible at darkness 1 to 20 (P1 L11 row)`() {
        for (t in listOf(Tint.AMBER, Tint.CANDLE, Tint.RED)) {
            for (d in 1..20) {
                val argb = hybridColor(d, t, 60)
                assertTrue(argbAlpha(argb) > 0, "$t darkness=$d: no tint layer")
                val out = DimmerMath.compositeOver(argb, 255, 255, 255)
                assertTrue(out[2] < 255, "$t darkness=$d: white content not tinted")
            }
        }
    }

    @Test
    fun `hybrid on - nothing is drawn at darkness 0, for neutral, or at strength 0`() {
        assertEquals(0, argbAlpha(hybridColor(0, Tint.RED, 100)))
        for (d in 1..20) {
            assertEquals(0, argbAlpha(hybridColor(d, Tint.NEUTRAL, 100)), "neutral d=$d")
            assertEquals(0, argbAlpha(hybridColor(d, Tint.AMBER, 0)), "strength 0 d=$d")
        }
    }

    @Test
    fun `hybrid on - overlay alpha never decreases as darkness rises, no jump at the knee`() {
        for (t in Tint.entries) {
            for (strength in listOf(0, 40, 100)) {
                var previous = 0
                for (d in 0..100) {
                    val a = argbAlpha(hybridColor(d, t, strength))
                    assertTrue(a >= previous, "$t s=$strength: alpha fell from $previous to $a at d=$d")
                    assertTrue(a - previous <= 40, "$t s=$strength: jump ${a - previous} at d=$d")
                    previous = a
                }
                assertEquals(255, previous, "$t s=$strength: darkness 100 must be fully opaque")
            }
        }
    }

    @Test
    fun `hybrid off - tint floor is not used, plain v2 overlay`() {
        for (d in 1..20) {
            assertEquals(DimmerMath.overlayColor(d, Tint.AMBER, 100, 100), hybridColor(d, Tint.AMBER, 100, hybridOn = false))
        }
    }

    @Test
    fun `the tint floor respects a stricter ceiling`() {
        val argb = DimmerMath.overlayColor(0, Tint.CANDLE, 100, maxLevel = 10, minTintAlpha = 0.2f)
        assertTrue(argbAlpha(argb) <= Math.round(0.10f * 255f))
    }

    @Test
    fun `the tint floor stays under the residual bound on black`() {
        for (t in Tint.entries) {
            val argb = hybridColor(20, t, 100)
            val out = DimmerMath.compositeOver(argb, 0, 0, 0)
            assertTrue(DimmerMath.luma(out[0], out[1], out[2]) < 10f, "$t")
        }
    }
}
