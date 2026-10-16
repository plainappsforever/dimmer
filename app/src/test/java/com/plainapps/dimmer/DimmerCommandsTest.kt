package com.plainapps.dimmer

import com.plainapps.dimmer.engine.AutomationCommand
import com.plainapps.dimmer.engine.DimSource
import com.plainapps.dimmer.engine.ScheduleConfig
import com.plainapps.dimmer.engine.Tint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.TimeZone

/** In-memory [DimmerStore] with the repository's real invariants ([normalized]). */
class FakeStore(initial: DimmerState) : DimmerStore {
    private val flow = MutableStateFlow(initial.normalized(100))
    override val state: StateFlow<DimmerState> = flow
    override fun update(transform: (DimmerState) -> DimmerState): Pair<DimmerState, DimmerState> {
        val old = flow.value
        val new = transform(old).normalized(100)
        flow.value = new
        return old to new
    }
}

/**
 * v3.1: the commands - the one place state changes - against an in-memory store and fake clocks
 * (REVIEW_V3 "test gaps" 1, 2, 4, 8). Zone UTC; the clock schedule runs 21:00-07:00.
 */
class DimmerCommandsTest {
    private var wall = Instant.parse("2026-10-01T12:00:00Z")
    private var mono = 1_000_000L
    private var access = true
    private lateinit var store: FakeStore
    private lateinit var cmd: DimmerCommands
    private val savedZone: TimeZone = TimeZone.getDefault()

    @Before
    fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        store = FakeStore(DimmerState(loaded = true, darkness = 60))
        cmd = DimmerCommands(store, { access }, ScheduleEnvironment { wall }, { mono })
    }

    @After
    fun tearDown() = TimeZone.setDefault(savedZone)

    private val s get() = store.state.value

    // --- v3.1 F3: automation lockout after a user Off (finding 5) ---

    @Test
    fun automationOnIsIgnoredForAMinuteAfterAUserOff() {
        cmd.turnOn("tile")
        cmd.turnOff("tile")
        mono += 30_000
        assertFalse(cmd.execute(AutomationCommand.On))
        assertFalse(s.enabled)
        assertFalse(cmd.execute(AutomationCommand.ApplyPreset("Ultra dim")))
        assertFalse(cmd.execute(AutomationCommand.NextPreset))
        assertFalse(cmd.execute(AutomationCommand.Toggle))
        assertFalse(cmd.execute(AutomationCommand.Set(100, null, null)))
        assertFalse(cmd.execute(AutomationCommand.Darker(10)))
        assertFalse(s.enabled)
        assertEquals(60, s.darkness)
        mono += 30_001
        assertEquals(0L, cmd.automationLockoutLeftMs())
        assertTrue(cmd.execute(AutomationCommand.On))
        assertTrue(s.enabled)
    }

    @Test
    fun everyManualExitStartsTheLockout() {
        for (reason in listOf("tile", "notification", "shake", "app", "widget", "shortcut")) {
            cmd.turnOn("app")
            cmd.turnOff(reason)
            assertTrue(reason, cmd.automationLockoutLeftMs() > 0)
            assertFalse(reason, cmd.execute(AutomationCommand.On))
            mono += 61_000
        }
    }

    @Test
    fun offLighterAndPauseStayAllowedDuringTheLockout() {
        cmd.turnOn("app")
        cmd.turnOff("shake")
        assertTrue(cmd.execute(AutomationCommand.Off))
        assertTrue(cmd.execute(AutomationCommand.Lighter(10)))
        assertEquals(50, s.darkness)
    }

    @Test
    fun automationOffDoesNotLockOutAutomation() {
        cmd.turnOn("app")
        cmd.execute(AutomationCommand.Off)
        assertEquals(0L, cmd.automationLockoutLeftMs())
        assertTrue(cmd.execute(AutomationCommand.On))
        assertTrue(s.enabled)
    }

    @Test
    fun turningOnByHandEndsTheLockout() {
        cmd.turnOn("app")
        cmd.turnOff("tile")
        cmd.turnOn("tile")
        assertEquals(0L, cmd.automationLockoutLeftMs())
        assertTrue(cmd.execute(AutomationCommand.Lighter(5)))
        assertTrue(cmd.execute(AutomationCommand.Toggle))
        assertFalse(s.enabled)
    }

    @Test
    fun lockoutUsesTheMonotonicClockNotTheWallClock() {
        cmd.turnOn("app")
        cmd.turnOff("tile")
        wall = wall.plusSeconds(3600) // the user's clock jumps; the lockout does not end early
        assertTrue(cmd.automationLockoutLeftMs() > 0)
    }

    // --- v3.1 F3: automation never darker than the user's own darkness without a value ---

    @Test
    fun automationDarkerStopsAtTheUsersDarkness() {
        cmd.setDarkness(40)
        cmd.turnOn("app")
        cmd.execute(AutomationCommand.Lighter(20))
        assertEquals(20, s.darkness)
        cmd.execute(AutomationCommand.Darker(50))
        assertEquals(40, s.darkness)
        cmd.execute(AutomationCommand.Darker(10))
        assertEquals(40, s.darkness)
    }

    @Test
    fun explicitAutomationValuesMayGoDarker() {
        cmd.setDarkness(40)
        cmd.turnOn("app")
        cmd.execute(AutomationCommand.Set(90, null, null))
        assertEquals(90, s.darkness)
        // DARKER never lowers an explicit value, and never raises it either.
        cmd.execute(AutomationCommand.Darker(10))
        assertEquals(90, s.darkness)
        // A preset named by the sender is an explicit value too.
        assertTrue(cmd.execute(AutomationCommand.ApplyPreset("Ultra dim")))
        assertEquals("ultra_dim", s.activePresetId)
    }

    @Test
    fun theUsersOwnDarkerHasNoCeiling() {
        cmd.setDarkness(40)
        cmd.turnOn("app")
        cmd.adjustDarkness(30, "notification")
        assertEquals(70, s.darkness)
        // and it raises the automation ceiling with it
        cmd.execute(AutomationCommand.Lighter(20))
        cmd.execute(AutomationCommand.Darker(50))
        assertEquals(70, s.darkness)
    }

    @Test
    fun automationOnUsesTheStoredLookUnchanged() {
        cmd.setDarkness(35)
        assertTrue(cmd.execute(AutomationCommand.On))
        assertTrue(s.enabled)
        assertEquals(35, s.darkness)
    }

    @Test
    fun withoutAccessNothingTurnsOn() {
        access = false
        assertFalse(cmd.turnOn("tile"))
        assertFalse(cmd.execute(AutomationCommand.On))
        assertFalse(s.enabled)
    }

    // --- REVIEW_V3 test gap 1: the command rules ---

    private val evening = ScheduleConfig(enabled = true, startMinute = 21 * 60, endMinute = 7 * 60, fadeMinutes = 60)

    /** Schedule on, and one evaluation at noon: this process is not "just restarted" any more. */
    private fun withSchedule(cfg: ScheduleConfig = evening) {
        val keep = wall
        wall = Instant.parse("2026-10-01T12:00:00Z")
        cmd.updateSchedule { cfg }
        cmd.evaluateSchedule("settings")
        wall = keep
    }

    @Test
    fun turnOffInsideAWindowMeansOffUntilTheNextStart() {
        wall = Instant.parse("2026-10-01T22:00:00Z")
        withSchedule()
        cmd.evaluateSchedule("test")
        assertTrue(s.enabled)
        cmd.turnOff("tile")
        assertFalse(s.enabled)
        assertEquals(Instant.parse("2026-10-02T21:00:00Z").toEpochMilli(), s.suppressedUntilMs)
        wall = wall.plusSeconds(600)
        cmd.evaluateSchedule("tick")
        assertFalse("stays off for the rest of the window", s.enabled)
    }

    @Test
    fun turnOffOutsideAWindowSuppressesNothing() {
        withSchedule()
        cmd.turnOn("app")
        cmd.turnOff("app")
        assertEquals(0L, s.suppressedUntilMs)
    }

    @Test
    fun stopBySystemNeverSuppresses() {
        wall = Instant.parse("2026-10-01T22:00:00Z")
        withSchedule()
        cmd.evaluateSchedule("test")
        assertTrue(cmd.stopBySystem("accessibility unbound"))
        assertFalse(s.enabled)
        assertEquals(0L, s.suppressedUntilMs)
        assertEquals(0L, cmd.automationLockoutLeftMs())
        assertFalse("already off: nothing to report", cmd.stopBySystem("again"))
    }

    @Test
    fun toggleWhilePausedTurnsOff() {
        cmd.turnOn("app")
        assertTrue(cmd.pause(15, "notification"))
        assertTrue(s.paused)
        assertFalse(cmd.toggle("tile"))
        assertFalse(s.enabled)
        assertNull(s.pause)
    }

    @Test
    fun aLookChangeByHandResumesAPause() {
        cmd.turnOn("app")
        cmd.pause(15, "notification")
        cmd.setDarkness(50)
        assertNull(s.pause)
        assertTrue(s.visible)
        assertEquals(50, s.darkness)
        assertNull("moved by hand = Custom", s.activePresetId)
    }

    @Test
    fun pauseWhileOffDoesNothing() {
        assertFalse(cmd.pause(15, "automation"))
        assertNull(s.pause)
    }

    @Test
    fun takingOverAFadeKeepsTheLookOnScreen_noJump() {
        wall = Instant.parse("2026-10-01T21:00:00Z")
        withSchedule()
        cmd.evaluateSchedule("start")
        assertEquals(DimSource.SCHEDULE, s.source)
        wall = Instant.parse("2026-10-01T21:30:00Z")
        val (onScreen, strengthOnScreen, fade) = cmd.renderedLook(s)
        assertTrue(fade > 0f && fade < 1f)
        assertTrue(onScreen < 60)
        cmd.setTint(Tint.AMBER)
        assertEquals(onScreen, s.darkness)
        assertEquals(strengthOnScreen, s.tintStrength)
        assertEquals(DimSource.MANUAL, s.source)
        assertNull(s.fadeStartMs)
        assertEquals(1f, cmd.renderedLook(s).third)
    }

    @Test
    fun applyPresetWithoutAccessOnlyChangesTheValues() {
        access = false
        assertFalse(cmd.applyPreset("deep_red", turnOn = true))
        assertFalse(s.enabled)
        assertEquals("deep_red", s.activePresetId)
        assertEquals(70, s.darkness)
        assertEquals(Tint.RED, s.tint)
    }

    @Test
    fun unknownPresetChangesNothing() {
        assertFalse(cmd.applyPreset("nope"))
        assertEquals(60, s.darkness)
    }

    @Test
    fun nextPresetWithAnEmptyCycleActsLikeTheOnOffTile() {
        store.update { it.copy(tileCycle = emptyList()) }
        cmd.nextPreset("tile")
        assertTrue(s.enabled)
        cmd.nextPreset("tile")
        assertFalse(s.enabled)
    }

    @Test
    fun nextPresetWalksTheCycleThenOff() {
        store.update { it.copy(tileCycle = listOf("reading", "deep_red")) }
        cmd.nextPreset("tile")
        assertEquals("reading", s.activePresetId)
        assertTrue(s.enabled)
        cmd.nextPreset("tile")
        assertEquals("deep_red", s.activePresetId)
        cmd.nextPreset("tile")
        assertFalse(s.enabled)
    }

    // --- gap 4: schedule evaluation and pause expiry at the command level ---

    @Test
    fun pauseEndsByItselfAtTheNextEvaluation() {
        cmd.turnOn("app")
        cmd.pause(15, "notification")
        wall = wall.plusSeconds(14 * 60)
        cmd.evaluateSchedule("tick")
        assertTrue(s.paused)
        wall = wall.plusSeconds(2 * 60)
        cmd.evaluateSchedule("tick")
        assertNull(s.pause)
        assertTrue(s.visible)
    }

    @Test
    fun nothingIsEvaluatedBeforeTheSettingsAreLoaded() {
        store.update { it.copy(loaded = false, schedule = evening) }
        wall = Instant.parse("2026-10-01T22:00:00Z")
        cmd.evaluateSchedule("connect")
        assertFalse(s.enabled)
        assertFalse(s.scheduleEvaluated)
    }

    @Test
    fun restartInsideAWindowWithResumeOff_staysOffUntilTheNextStart() {
        store.update { it.copy(schedule = evening) }
        wall = Instant.parse("2026-10-01T23:00:00Z")
        cmd.evaluateSchedule("connect") // the first evaluation of a new process = the restart one
        assertFalse(s.enabled)
        assertEquals(Instant.parse("2026-10-02T21:00:00Z").toEpochMilli(), s.suppressedUntilMs)
    }

    /** The owner's exit for option C: "if Resume after restart is on, restart, then shake". */
    @Test
    fun restartWithResumeOn_dimsAgain_thenShakeTurnsItOffForTheNight() {
        store.update { it.copy(schedule = evening.copy(resumeAfterRestart = true)) }
        wall = Instant.parse("2026-10-01T23:00:00Z")
        cmd.evaluateSchedule("connect")
        assertTrue(s.enabled)
        assertEquals(DimSource.SCHEDULE, s.source)
        cmd.turnOff("shake")
        assertFalse(s.enabled)
        for (minute in 1..30) {
            wall = wall.plusSeconds(60)
            cmd.evaluateSchedule("tick")
            assertFalse("minute $minute", s.enabled)
        }
        assertFalse("automation cannot undo the shake at once", cmd.execute(AutomationCommand.On))
    }

    @Test
    fun shakeToTurnOffIsOnByDefault() {
        assertTrue(DimmerState().shakeEnabled)
    }

    // --- gap 2: repository invariants ---

    @Test
    fun normalized_dropsPauseAndRoomLightWhenOff_andClamps() {
        val raw = DimmerState(
            enabled = false,
            darkness = 150,
            tintStrength = -4,
            pause = com.plainapps.dimmer.engine.PauseWindow(0, 1),
            ambientState = com.plainapps.dimmer.engine.AmbientState.BRIGHT,
        )
        val n = raw.normalized(100)
        assertNull(n.pause)
        assertEquals(com.plainapps.dimmer.engine.AmbientState.NORMAL, n.ambientState)
        assertEquals(100, n.darkness)
        assertEquals(DimmerMath.clampTintStrength(-4), n.tintStrength)
        assertEquals(0, DimmerState(darkness = -3).normalized(100).darkness)
        val on = raw.copy(enabled = true).normalized(100)
        assertNotNull(on.pause)
        assertEquals(com.plainapps.dimmer.engine.AmbientState.BRIGHT, on.ambientState)
    }
}
