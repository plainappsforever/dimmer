package com.plainapps.dimmer

import com.plainapps.dimmer.engine.ShakeDetector
import com.plainapps.dimmer.engine.ShakeSensitivity
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class ShakeDetectorTest {

    private val rest = floatArrayOf(0f, 9.8f, 0f)

    /** Feeds [values] as constant readings, each held for [holdMs] at 20 ms sampling (SENSOR_DELAY_GAME). */
    private fun feed(d: ShakeDetector, values: List<FloatArray>, holdMs: Long = 100, startMs: Long = 0): Int {
        var t = startMs
        var triggers = 0
        for (v in values) {
            val end = t + holdMs
            while (t < end) {
                if (d.add(t, v[0], v[1], v[2])) triggers++
                t += 20
            }
        }
        return triggers
    }

    private fun v(x: Float, y: Float, z: Float) = floatArrayOf(x, y, z)

    @Test
    fun `plan emulator sequence triggers exactly once`() {
        // adb emu sensor set acceleration 30:0:9.8 / -30:0:9.8, three times, then 0:9.8:0.
        val seq = List(3) { listOf(v(30f, 0f, 9.8f), v(-30f, 0f, 9.8f)) }.flatten() + listOf(rest)
        assertEquals(1, feed(ShakeDetector(), seq))
    }

    @Test
    fun `plan sequence triggers on every sensitivity`() {
        for (s in ShakeSensitivity.entries) {
            val seq = listOf(v(30f, 0f, 9.8f), v(-30f, 0f, 9.8f), v(30f, 0f, 9.8f))
            assertEquals(1, feed(ShakeDetector(s), seq), "sensitivity $s")
        }
    }

    @Test
    fun `one mild step does not trigger`() {
        assertEquals(0, feed(ShakeDetector(ShakeSensitivity.HIGH), listOf(rest, v(3f, 0f, 9.8f), rest), holdMs = 2_000))
    }

    @Test
    fun `turning the phone over or laying it flat does not trigger`() {
        val tilts = listOf(rest, v(0f, 0f, 9.8f), v(9.8f, 0f, 0f), v(0f, -9.8f, 0f), v(-9.8f, 0f, 0f), rest)
        assertEquals(0, feed(ShakeDetector(ShakeSensitivity.HIGH), tilts, holdMs = 40))
    }

    @Test
    fun `a single bump and two strokes do not trigger`() {
        assertEquals(0, feed(ShakeDetector(), listOf(rest, v(35f, 0f, 9.8f), rest), holdMs = 60))
        assertEquals(0, feed(ShakeDetector(), listOf(v(30f, 0f, 9.8f), v(-30f, 0f, 9.8f), rest), holdMs = 100))
    }

    @Test
    fun `strokes spread over more than a second do not trigger`() {
        val seq = listOf(v(30f, 0f, 9.8f), rest, v(-30f, 0f, 9.8f), rest, v(30f, 0f, 9.8f), rest)
        assertEquals(0, feed(ShakeDetector(), seq, holdMs = 300))
    }

    @Test
    fun `vertical shake with weak down strokes triggers via re-arm`() {
        val seq = List(3) { listOf(v(0f, 36f, 0f), v(0f, -14f, 0f)) }.flatten()
        assertEquals(1, feed(ShakeDetector(), seq, holdMs = 160))
    }

    @Test
    fun `jitter around the threshold is one stroke`() {
        val d = ShakeDetector()
        var t = 0L
        var triggers = 0
        // 22.0 / 21.0 m/s² alternating: above and just below the MEDIUM threshold (21.6), never re-armed.
        repeat(50) {
            if (d.add(t, 22f, 0f, 0f)) triggers++
            if (d.add(t + 10, 21f, 0f, 0f)) triggers++
            t += 20
        }
        assertEquals(0, triggers)
    }

    @Test
    fun `sensitivity decides what counts as strong`() {
        val medium = listOf(v(19f, 0f, 0f), v(-19f, 0f, 0f), v(19f, 0f, 0f))
        assertEquals(1, feed(ShakeDetector(ShakeSensitivity.HIGH), medium))
        assertEquals(0, feed(ShakeDetector(ShakeSensitivity.MEDIUM), medium))
        assertEquals(0, feed(ShakeDetector(ShakeSensitivity.LOW), medium))
    }

    @Test
    fun `a ringing impact is one stroke`() {
        // A drop onto a table: sign flips every 20 ms for 60 ms, well above every threshold.
        val d = ShakeDetector(ShakeSensitivity.HIGH)
        val ring = listOf(40f, -38f, 35f, -30f)
        var triggers = 0
        ring.forEachIndexed { i, a -> if (d.add(1_000L + i * 20, a, 9.8f, 0f)) triggers++ }
        for (t in 1_080L..2_000L step 20) if (d.add(t, 0f, 9.8f, 0f)) triggers++
        assertEquals(0, triggers)
    }

    @Test
    fun `slow adb-like sequence still triggers within one second`() {
        // Console commands ~300 ms apart: strokes at 0, 300, 600 ms.
        assertEquals(1, feed(ShakeDetector(), listOf(v(30f, 0f, 9.8f), v(-30f, 0f, 9.8f), v(30f, 0f, 9.8f), rest), holdMs = 300))
    }

    @Test
    fun `threshold is capped by the sensor range`() {
        val d = ShakeDetector(ShakeSensitivity.LOW)
        d.maxRange = 19.6f // a ±2 g part
        assertEquals(19.6f * ShakeDetector.RANGE_FRACTION, d.threshold)
        // A saturated one-axis shake (±19.6 plus gravity) still triggers on LOW.
        assertEquals(1, feed(d, listOf(v(19.6f, 9.8f, 0f), v(-19.6f, 9.8f, 0f), v(19.6f, 9.8f, 0f))))
    }

    @Test
    fun `reset clears partial gestures`() {
        val d = ShakeDetector()
        assertFalse(d.add(0, 30f, 0f, 0f))
        assertFalse(d.add(100, -30f, 0f, 0f))
        d.reset()
        assertFalse(d.add(200, 30f, 0f, 0f))
        assertFalse(d.add(300, -30f, 0f, 0f))
        assertTrue(d.add(400, 30f, 0f, 0f))
    }
}
