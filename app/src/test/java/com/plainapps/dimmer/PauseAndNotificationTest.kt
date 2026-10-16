package com.plainapps.dimmer

import com.plainapps.dimmer.engine.NotificationAction
import com.plainapps.dimmer.engine.NotificationPlan
import com.plainapps.dimmer.engine.Pause
import com.plainapps.dimmer.engine.Resolver
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Test

class PauseAndNotificationTest {

    private val t0 = 1_790_000_000_000L
    private fun min(m: Int) = m * 60_000L

    @Test
    fun `pause lasts exactly its minutes`() {
        val p = Pause.start(t0, 15)
        assertTrue(Pause.active(p, t0))
        assertTrue(Pause.active(p, t0 + min(15) - 1))
        assertFalse(Pause.active(p, t0 + min(15)))
        assertFalse(Pause.active(p, t0 + min(16))) // plan check: clock moved +16 min
        assertFalse(Pause.active(null, t0))
    }

    @Test
    fun `pause minutes are clamped`() {
        assertEquals(t0 + min(1), Pause.start(t0, 0).untilMs)
        assertEquals(t0 + min(1), Pause.start(t0, -5).untilMs)
        assertEquals(t0 + min(Pause.MAX_MINUTES), Pause.start(t0, 100_000).untilMs)
    }

    @Test
    fun `clock moved backwards ends the pause instead of stretching it`() {
        val p = Pause.start(t0, 15)
        assertTrue(Pause.active(p, t0 - 60_000)) // small NTP correction: still paused
        assertFalse(Pause.active(p, t0 - min(10))) // moved back 10 min: would have been 25 min
    }

    private fun decide(
        enabled: Boolean = true,
        pauseMin: Int? = null,
        status: Resolver.Status = Resolver.Status.None,
        inWindow: Boolean = false,
        preset: String? = "Candlelight",
    ) = NotificationPlan.decide(
        enabled, pauseMin?.let { Pause.start(t0, it) }, preset, 60, status,
        if (inWindow) Instant.ofEpochMilli(t0 + min(300)) else null,
    )

    @Test
    fun `on shows preset, darkness and lighter darker pause`() {
        val p = decide()
        assertIs<NotificationPlan.On>(p)
        assertEquals("Candlelight", p.presetName)
        assertEquals(60, p.darkness)
        assertEquals(listOf(NotificationAction.LIGHTER, NotificationAction.DARKER, NotificationAction.PAUSE), p.actions)
        assertTrue(p.ongoing)
    }

    @Test
    fun `on keeps fade and until lines, drops the others`() {
        val fade = Resolver.Status.FadingIn(Instant.ofEpochMilli(t0 + min(20)))
        assertEquals(fade, (decide(status = fade) as NotificationPlan.On).status)
        val until = Resolver.Status.Until(Instant.ofEpochMilli(t0 + min(200)))
        assertEquals(until, (decide(status = until) as NotificationPlan.On).status)
        val starts = Resolver.Status.StartsAt(Instant.ofEpochMilli(t0 + min(200)))
        assertEquals(Resolver.Status.None, (decide(status = starts) as NotificationPlan.On).status)
    }

    @Test
    fun `paused shows resume and off`() {
        val p = decide(pauseMin = 15)
        assertIs<NotificationPlan.Paused>(p)
        assertEquals(Instant.ofEpochMilli(t0 + min(15)), p.until)
        assertEquals(listOf(NotificationAction.RESUME, NotificationAction.OFF), p.actions)
    }

    @Test
    fun `off inside a suppressed window says when the schedule resumes`() {
        val next = Instant.ofEpochMilli(t0 + min(1200))
        val p = decide(enabled = false, status = Resolver.Status.ResumesAt(next), inWindow = true)
        assertIs<NotificationPlan.ScheduleOff>(p)
        assertEquals(next, p.resumesAt)
        assertEquals(Instant.ofEpochMilli(t0 + min(300)), p.windowEnd)
        assertEquals(listOf(NotificationAction.TURN_ON), p.actions)
        assertFalse(p.ongoing)
    }

    @Test
    fun `off otherwise shows nothing`() {
        val next = Instant.ofEpochMilli(t0 + min(1200))
        // Suppressed but the window is over (daytime): no notification all day.
        assertEquals(NotificationPlan.None, decide(enabled = false, status = Resolver.Status.ResumesAt(next), inWindow = false))
        assertEquals(NotificationPlan.None, decide(enabled = false, status = Resolver.Status.StartsAt(next)))
        assertEquals(NotificationPlan.None, decide(enabled = false))
        // A pause left over while off never shows (the repository clears it anyway).
        assertEquals(NotificationPlan.None, decide(enabled = false, pauseMin = 15))
    }

    @Test
    fun `equal states give equal plans so a dismissed notification is not re-posted`() {
        assertEquals(decide(pauseMin = 30), decide(pauseMin = 30))
        assertEquals(decide(), decide())
    }

    @Test
    fun `resolver status shows an active pause first`() {
        val cfg = com.plainapps.dimmer.engine.ScheduleConfig(enabled = false)
        val until = Instant.ofEpochMilli(t0 + min(15))
        val s = Resolver.status(Instant.ofEpochMilli(t0), cfg, null, null, null, true, com.plainapps.dimmer.engine.DimSource.MANUAL, null, until)
        assertEquals(Resolver.Status.Paused(until), s)
        val off = Resolver.status(Instant.ofEpochMilli(t0), cfg, null, null, null, false, com.plainapps.dimmer.engine.DimSource.MANUAL, null, until)
        assertEquals(Resolver.Status.None, off)
    }
}
