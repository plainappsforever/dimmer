package com.plainapps.dimmer

import com.plainapps.dimmer.backlight.BacklightMethod
import com.plainapps.dimmer.backlight.BacklightMethods
import com.plainapps.dimmer.backlight.HybridCurve
import com.plainapps.dimmer.backlight.HybridCurve.FLOOR
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HybridCurveTest {

    private val eps = 1e-4f

    private fun on(d: Int, start: Float) = HybridCurve.compute(d, hybridOn = true, startBacklight = start)

    @Test
    fun hybridOff_isV2Behaviour_noBacklight_overlayEqualsDarkness() {
        for (d in listOf(0, 10, 20, 21, 60, 100)) {
            for (start in listOf(0f, 0.5f, 1f)) {
                val out = HybridCurve.compute(d, hybridOn = false, startBacklight = start)
                assertNull(out.backlight)
                assertEquals(d, out.overlayLevel)
            }
        }
    }

    @Test
    fun darknessZero_touchesNothing() {
        for (start in listOf(0f, 0.5f, 1f)) {
            val out = on(0, start)
            assertNull(out.backlight)
            assertEquals(0, out.overlayLevel)
        }
    }

    @Test
    fun darkness10_isHalfwayBetweenStartAndFloor_overlayTransparent() {
        assertEquals((1f + FLOOR) / 2, on(10, 1f).backlight!!, eps)
        assertEquals((0.5f + FLOOR) / 2, on(10, 0.5f).backlight!!, eps)
        // start 0.0 is raised to the floor: nothing to lower, stays at the floor
        assertEquals(FLOOR, on(10, 0f).backlight!!, eps)
        for (start in listOf(0f, 0.5f, 1f)) assertEquals(0, on(10, start).overlayLevel)
    }

    @Test
    fun darkness20_isTheKnee_floorAndNoOverlay() {
        for (start in listOf(0f, 0.5f, 1f)) {
            val out = on(20, start)
            assertEquals(FLOOR, out.backlight!!, eps)
            assertEquals(0, out.overlayLevel)
        }
    }

    @Test
    fun darkness21_floorAndFirstOverlayStep() {
        for (start in listOf(0f, 0.5f, 1f)) {
            val out = on(21, start)
            assertEquals(FLOOR, out.backlight!!, eps)
            assertEquals(1, out.overlayLevel) // 1 * 100 / 80 = 1.25 -> 1
        }
    }

    @Test
    fun darkness60_floorAndHalfOverlay() {
        for (start in listOf(0f, 0.5f, 1f)) {
            val out = on(60, start)
            assertEquals(FLOOR, out.backlight!!, eps)
            assertEquals(50, out.overlayLevel)
        }
    }

    @Test
    fun darkness100_floorAndFullOverlay() {
        for (start in listOf(0f, 0.5f, 1f)) {
            val out = on(100, start)
            assertEquals(FLOOR, out.backlight!!, eps)
            assertEquals(100, out.overlayLevel)
        }
    }

    @Test
    fun backlightNeverBelowFloor_andMonotonic() {
        for (start in listOf(0f, 0.2f, 0.5f, 1f, 7f, -3f)) {
            var previousBacklight = Float.MAX_VALUE
            var previousOverlay = -1
            for (d in 1..100) {
                val out = on(d, start)
                val b = out.backlight!!
                assertTrue("d=$d start=$start b=$b", b >= FLOOR - eps && b <= 1f + eps)
                assertTrue("backlight must not rise with darkness", b <= previousBacklight + eps)
                assertTrue("overlay must not fall with darkness", out.overlayLevel >= previousOverlay)
                previousBacklight = b
                previousOverlay = out.overlayLevel
            }
        }
    }

    @Test
    fun outOfRangeDarknessIsClamped() {
        assertEquals(on(100, 1f), on(250, 1f))
        assertEquals(on(0, 1f), on(-5, 1f))
    }

    @Test
    fun customKnee() {
        val out = HybridCurve.compute(50, hybridOn = true, startBacklight = 1f, knee = 50)
        assertEquals(FLOOR, out.backlight!!, eps)
        assertEquals(0, out.overlayLevel)
        assertEquals(100, HybridCurve.compute(100, true, 1f, knee = 50).overlayLevel)
    }

    @Test
    fun settingsIntConversion_floorOfOne() {
        assertEquals(1, HybridCurve.toSettingsInt(0f))
        assertEquals(1, HybridCurve.toSettingsInt(FLOOR))
        assertEquals(255, HybridCurve.toSettingsInt(1f))
        assertEquals(255, HybridCurve.toSettingsInt(3f))
        assertEquals(1, HybridCurve.toSettingsInt(-1f))
        assertEquals(128, HybridCurve.toSettingsInt(0.5f))
        assertEquals(200, HybridCurve.toSettingsInt(HybridCurve.fromSettingsInt(200)))
    }

    @Test
    fun methodResolution() {
        fun r(m: BacklightMethod, window: Boolean, write: Boolean) = BacklightMethods.resolve(m, window, write)
        assertEquals(BacklightMethod.WINDOW, r(BacklightMethod.AUTO, window = true, write = false))
        assertEquals(BacklightMethod.SYSTEM, r(BacklightMethod.AUTO, window = false, write = true))
        assertEquals(BacklightMethod.NONE, r(BacklightMethod.AUTO, window = false, write = false))
        assertEquals(BacklightMethod.SYSTEM, r(BacklightMethod.SYSTEM, window = true, write = true))
        assertEquals(BacklightMethod.NONE, r(BacklightMethod.SYSTEM, window = true, write = false))
        assertEquals(BacklightMethod.WINDOW, r(BacklightMethod.WINDOW, window = true, write = true))
        assertEquals(BacklightMethod.NONE, r(BacklightMethod.WINDOW, window = false, write = true))
        assertEquals(BacklightMethod.NONE, r(BacklightMethod.NONE, window = true, write = true))
    }

    // --- v3.1 (REVIEW_V3 finding 2): never brighter than the panel ---

    @Test
    fun unknownStart_skipsTheBacklightPartInTheKnee_thenFloor() {
        for (d in 1..HybridCurve.DEFAULT_KNEE) {
            val out = on(d, Float.NaN)
            assertNull("d=$d: no guess, no override", out.backlight)
            assertEquals(0, out.overlayLevel)
        }
        val above = on(HybridCurve.DEFAULT_KNEE + 1, Float.NaN)
        assertEquals(FLOOR, above.backlight!!, eps)
        assertTrue(above.overlayLevel > 0)
    }

    @Test
    fun fadeFromTheCurrentValue_neverStartsBrighterThanThatValue() {
        // A schedule fade walks darkness 1, 2, ... from the panel's current value: every step is
        // at or below it, whatever that value is (an auto level of 3 % included).
        for (start in listOf(0.03f, 0.1f, 0.4f, 0.95f)) {
            for (d in 1..100) {
                val b = on(d, start).backlight!!
                assertTrue("start=$start d=$d b=$b", b <= start + eps || b <= FLOOR + eps)
            }
        }
    }

    @Test
    fun missingSetting_isUnknown() {
        assertTrue(HybridCurve.fromSettingsInt(-1).isNaN())
        assertEquals(101 / 254f, HybridCurve.fromSettingsInt(102), eps)
        assertEquals(0f, HybridCurve.fromSettingsInt(1), eps)
        // Never above the panel's float for the same int, at any level (start of a fade).
        for (v in 1..255) assertTrue("v=$v", HybridCurve.fromSettingsInt(v) <= (v - 1) / 254f + 1e-6f)
        assertEquals(1f, HybridCurve.fromSettingsInt(300), eps)
    }
}
