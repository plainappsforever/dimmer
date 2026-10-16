package com.plainapps.dimmer

import com.plainapps.dimmer.engine.DimSource
import com.plainapps.dimmer.engine.Resolver
import com.plainapps.dimmer.engine.Resolver.Action
import com.plainapps.dimmer.engine.ScheduleConfig
import com.plainapps.dimmer.engine.ScheduleMath
import com.plainapps.dimmer.engine.ScheduleRuntime
import com.plainapps.dimmer.engine.SunSource
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class ResolverTest {

    private val zone = ZoneId.of("Europe/Berlin")
    private val noSun = SunSource { error("sun not needed") }
    private val cfg = ScheduleConfig(enabled = true, startMinute = 21 * 60, endMinute = 7 * 60, fadeMinutes = 30)

    private fun at(d: Int, h: Int, m: Int): Instant = LocalDateTime.of(2026, 10, d, h, m).atZone(zone).toInstant()

    /** Runs one step the way DimmerCommands does. */
    private fun step(now: Instant, rt: ScheduleRuntime, suppressed: Instant? = null, c: ScheduleConfig = cfg): Resolver.Step {
        val w = if (c.enabled) ScheduleMath.windowAt(now, c, zone, noSun) else null
        val next = if (c.enabled) ScheduleMath.nextStartAfter(now, c, zone, noSun) else null
        return Resolver.step(now, c, w, next, suppressed, rt)
    }

    private val running = ScheduleRuntime(evaluated = true)

    @Test
    fun windowStartTurnsOnWithFadeFromStart() {
        val s = step(at(1, 21, 0), running)
        assertEquals(Action.TURN_ON, s.action)
        assertEquals(true, s.runtime.enabled)
        assertEquals(DimSource.SCHEDULE, s.runtime.source)
        assertEquals(at(1, 21, 0), s.runtime.fadeStart)
        // The next tick in the same window changes nothing.
        assertEquals(Action.NONE, step(at(1, 21, 1), s.runtime).action)
    }

    @Test
    fun enteringLateStillFadesFromTheWindowStart() {
        // Screen switched on 10 min into the window: fade fraction 10/30.
        val s = step(at(1, 21, 10), running)
        assertEquals(Action.TURN_ON, s.action)
        assertEquals(1f / 3f, ScheduleMath.fadeFraction(at(1, 21, 10), s.runtime.fadeStart!!, 30), 1e-4f)
    }

    @Test
    fun windowEndTurnsOffWhoeverTurnedItOn() {
        val on = step(at(1, 21, 0), running).runtime
        val end = step(at(2, 7, 0), on)
        assertEquals(Action.TURN_OFF_END, end.action)
        assertEquals(false, end.runtime.enabled)
        assertNull(end.runtime.seenWindowStart)
        // A manual "on" before the window is adopted and also ends at 07:00.
        val manual = running.copy(enabled = true)
        assertEquals(Action.NONE, step(at(1, 20, 0), manual).action)
        val adopted = step(at(1, 21, 0), manual)
        assertEquals(Action.ADOPT, adopted.action)
        assertEquals(DimSource.MANUAL, adopted.runtime.source)
        assertEquals(Action.TURN_OFF_END, step(at(2, 7, 0), adopted.runtime).action)
    }

    @Test
    fun manualOnOutsideAWindowStaysOn() {
        val manual = running.copy(enabled = true)
        val s = step(at(1, 12, 0), manual)
        assertEquals(Action.NONE, s.action)
        assertEquals(true, s.runtime.enabled)
    }

    @Test
    fun offDuringWindowStaysOffUntilTheNextStart() {
        // User turned off at 21:10 -> DimmerCommands stores suppressedUntil = next start.
        val on = step(at(1, 21, 0), running).runtime
        val off = on.copy(enabled = false, source = DimSource.MANUAL, fadeStart = null)
        val until = ScheduleMath.nextStartAfter(at(1, 21, 10), cfg, zone, noSun)
        assertEquals(at(2, 21, 0), until)
        val tick = step(at(1, 21, 11), off, until)
        assertEquals(Action.NONE, tick.action)
        assertEquals(false, tick.runtime.enabled)
        assertEquals(until, tick.suppressedUntil)
        // Process restart in the same window with resume ON: still suppressed.
        val restarted = step(at(1, 23, 0), ScheduleRuntime(), until, cfg.copy(resumeAfterRestart = true))
        assertEquals(Action.STAY_SUPPRESSED, restarted.action)
        assertEquals(false, restarted.runtime.enabled)
        // Next evening it turns on again and the suppression is gone.
        val next = step(at(2, 21, 0), step(at(2, 8, 0), tick.runtime, until).runtime, until)
        assertEquals(Action.TURN_ON, next.action)
        assertNull(next.suppressedUntil)
    }

    @Test
    fun restartInsideWindowHonoursResumeSetting() {
        val fresh = ScheduleRuntime() // evaluated = false: first evaluation of a new process
        val off = step(at(1, 22, 0), fresh)
        assertEquals(Action.SUPPRESS_RESTART, off.action)
        assertEquals(false, off.runtime.enabled)
        assertEquals(at(2, 21, 0), off.suppressedUntil)
        val on = step(at(1, 21, 10), fresh, c = cfg.copy(resumeAfterRestart = true))
        assertEquals(Action.TURN_ON, on.action)
        assertEquals(at(1, 21, 0), on.runtime.fadeStart) // at the current fade point
    }

    @Test
    fun restartOutsideWindowDoesNotSuppressTheNextOne() {
        val first = step(at(1, 12, 0), ScheduleRuntime())
        assertEquals(Action.NONE, first.action)
        assertEquals(true, first.runtime.evaluated)
        assertEquals(Action.TURN_ON, step(at(1, 21, 0), first.runtime).action)
    }

    @Test
    fun alreadyOnBeforeFirstEvaluationIsAdoptedNotSuppressed() {
        val s = step(at(1, 22, 0), ScheduleRuntime(enabled = true))
        assertEquals(Action.ADOPT, s.action)
        assertEquals(true, s.runtime.enabled)
        assertNull(s.suppressedUntil)
    }

    @Test
    fun disablingTheScheduleKeepsDimmingAsManual() {
        val on = step(at(1, 21, 0), running).runtime
        val s = step(at(1, 21, 5), on, at(2, 21, 0), cfg.copy(enabled = false))
        assertEquals(Action.SCHEDULE_DISABLED, s.action)
        assertEquals(true, s.runtime.enabled)
        assertEquals(DimSource.MANUAL, s.runtime.source)
        assertNull(s.suppressedUntil)
    }

    @Test
    fun suppressionIsDroppedWhenTheClockMovesBack() {
        // Off at 21:10 on the 1st -> until the 2nd 21:00. Clock set back to the 1st 12:00: the next
        // start is now the 1st 21:00, so the stored value would cover two windows -> dropped.
        val until = at(2, 21, 0)
        assertNull(Resolver.validSuppression(until, at(1, 12, 0), at(1, 21, 0)))
        assertEquals(until, Resolver.validSuppression(until, at(1, 23, 0), at(2, 21, 0)))
        assertNull(Resolver.validSuppression(until, at(2, 21, 0), at(3, 21, 0))) // expired
        // The TIME_CHANGED evaluation at 12:00 drops the stored value; 21:00 then turns on.
        val back = step(at(1, 12, 0), running, until)
        assertNull(back.suppressedUntil)
        val s = step(at(1, 21, 0), back.runtime, back.suppressedUntil)
        assertEquals(Action.TURN_ON, s.action)
    }

    @Test
    fun remapCarriesSeenAndSuppressionAcrossAnEdit() {
        // Suppressed at 21:40 in a 21:30 window; the user moves the start to 21:00.
        val old = cfg.copy(startMinute = 21 * 60 + 30)
        val now = at(1, 21, 40)
        val oldW = ScheduleMath.windowAt(now, old, zone, noSun)!!
        val rt = running.copy(seenWindowStart = oldW.start)
        val oldSup = ScheduleMath.nextStartAfter(now, old, zone, noSun)
        val newW = ScheduleMath.windowAt(now, cfg, zone, noSun)
        val newNext = ScheduleMath.nextStartAfter(now, cfg, zone, noSun)
        val (rt2, sup2) = Resolver.remap(now, oldW, oldSup, newW, newNext, rt)
        assertEquals(newW!!.start, rt2.seenWindowStart)
        assertEquals(at(2, 21, 0), sup2)
        // The next step keeps it off.
        val s = step(at(1, 21, 41), rt2, sup2)
        assertEquals(Action.NONE, s.action)
        assertEquals(false, s.runtime.enabled)
    }

    @Test
    fun fadedValues() {
        assertEquals(20 to 27, Resolver.faded(60, 80, 1f / 3f))
        assertEquals(60 to 80, Resolver.faded(60, 80, 1f))
        assertEquals(1 to 0, Resolver.faded(60, 80, 0f)) // never 0 once visible
        assertEquals(0 to 0, Resolver.faded(0, 0, 0.5f))
    }

    @Test
    fun statusLines() {
        val w = ScheduleMath.windowAt(at(1, 21, 10), cfg, zone, noSun)
        val next = ScheduleMath.nextStartAfter(at(1, 21, 10), cfg, zone, noSun)
        val fading = Resolver.status(at(1, 21, 10), cfg, w, next, null, true, DimSource.SCHEDULE, at(1, 21, 0))
        assertEquals(Resolver.Status.FadingIn(at(1, 21, 30)), fading)
        val until = Resolver.status(at(1, 21, 40), cfg, w, next, null, true, DimSource.SCHEDULE, at(1, 21, 0))
        assertEquals(Resolver.Status.Until(at(2, 7, 0)), until)
        val resumes = Resolver.status(at(1, 21, 40), cfg, w, next, next, false, DimSource.MANUAL, null)
        assertEquals(Resolver.Status.ResumesAt(at(2, 21, 0)), resumes)
        val starts = Resolver.status(at(1, 12, 0), cfg, null, at(1, 21, 0), null, false, DimSource.MANUAL, null)
        assertIs<Resolver.Status.StartsAt>(starts)
        assertEquals(Resolver.Status.None, Resolver.status(at(1, 12, 0), cfg.copy(enabled = false), null, null, null, false, DimSource.MANUAL, null))
    }
}
