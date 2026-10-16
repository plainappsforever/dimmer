package com.plainapps.dimmer

import com.plainapps.dimmer.backlight.BacklightMethod
import com.plainapps.dimmer.engine.AmbientLook
import com.plainapps.dimmer.engine.AmbientPolicy
import com.plainapps.dimmer.engine.AmbientState
import com.plainapps.dimmer.engine.DiagFix
import com.plainapps.dimmer.engine.DiagItem
import com.plainapps.dimmer.engine.Diagnostics
import com.plainapps.dimmer.engine.Presets
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class AmbientPolicyTest {

    private val dwell = AmbientPolicy.DWELL_MS

    @Test fun startsNormalAndIgnoresGarbage() {
        val p = AmbientPolicy()
        assertEquals(AmbientState.NORMAL, p.state)
        assertEquals(AmbientState.NORMAL, p.onLux(0, Float.NaN))
        assertEquals(AmbientState.NORMAL, p.onLux(0, -1f))
        assertEquals(AmbientState.NORMAL, p.tick(99_999)) // no reading yet
        assertNull(p.pendingAtMs())
    }

    @Test fun darkRoomNeedsTenSecondsBelowThreeLux() {
        val p = AmbientPolicy()
        assertEquals(AmbientState.NORMAL, p.onLux(0, 1f))
        assertEquals(0L + dwell, p.pendingAtMs())
        assertEquals(AmbientState.NORMAL, p.tick(dwell - 1))
        assertEquals(AmbientState.DARK_ROOM, p.tick(dwell))
        assertNull(p.pendingAtMs())
    }

    @Test fun exactlyThreeLuxIsNotDark() {
        val p = AmbientPolicy()
        p.onLux(0, 3f)
        assertEquals(AmbientState.NORMAL, p.tick(dwell * 3))
    }

    @Test fun brightNeedsTenSecondsAboveOneFiftyLux() {
        val p = AmbientPolicy()
        p.onLux(0, 400f)
        assertEquals(AmbientState.NORMAL, p.tick(dwell - 1))
        assertEquals(AmbientState.BRIGHT, p.tick(dwell))
    }

    @Test fun exactlyOneFiftyIsNotBright() {
        val p = AmbientPolicy()
        p.onLux(0, 150f)
        assertEquals(AmbientState.NORMAL, p.tick(dwell * 3))
    }

    @Test fun aShortBlipChangesNothing() {
        val p = AmbientPolicy()
        p.onLux(0, 400f)
        p.onLux(5_000, 60f) // back to normal before the dwell ends
        assertNull(p.pendingAtMs())
        assertEquals(AmbientState.NORMAL, p.tick(20_000))
        // a new blip starts a fresh dwell
        p.onLux(30_000, 400f)
        assertEquals(30_000L + dwell, p.pendingAtMs())
        assertEquals(AmbientState.NORMAL, p.tick(39_999))
    }

    @Test fun dwellRestartsWhenTheCandidateChanges() {
        val p = AmbientPolicy()
        p.onLux(0, 1f) // wants DARK_ROOM
        p.onLux(6_000, 400f) // now wants BRIGHT: clock restarts at 6 s
        assertEquals(AmbientState.NORMAL, p.tick(dwell))
        assertEquals(AmbientState.BRIGHT, p.tick(6_000 + dwell))
    }

    @Test fun brightStaysUntilUnderEightyLux() {
        val p = AmbientPolicy()
        p.onLux(0, 400f)
        assertEquals(AmbientState.BRIGHT, p.tick(dwell))
        // 100 lux is between the two limits: still bright (hysteresis), nothing pending
        p.onLux(20_000, 100f)
        assertNull(p.pendingAtMs())
        assertEquals(AmbientState.BRIGHT, p.tick(60_000))
        // 79 lux for ten seconds ends it
        p.onLux(70_000, 79f)
        assertEquals(AmbientState.BRIGHT, p.tick(70_000 + dwell - 1))
        assertEquals(AmbientState.NORMAL, p.tick(70_000 + dwell))
    }

    // v3.1 (REVIEW_V3 finding 6): wake in a dark bedroom after a bright room
    @Test fun firstReadingAfterScreenOnLeavesBrightAtOnce() {
        val p = AmbientPolicy()
        p.onLux(0, 400f)
        assertEquals(AmbientState.BRIGHT, p.tick(dwell))
        p.restartReading() // screen off, then on in the bedroom
        assertEquals(AmbientState.NORMAL, p.onLux(100_000, 2f))
        // a dark room still needs its dwell to switch to the dark-room preset
        assertEquals(AmbientState.NORMAL, p.tick(100_000 + dwell - 1))
        assertEquals(AmbientState.DARK_ROOM, p.tick(100_000 + dwell))
    }

    @Test fun firstReadingStillBrightStaysBright_andOnlyTheFirstReadingSkipsTheDwell() {
        val p = AmbientPolicy()
        p.onLux(0, 400f)
        p.tick(dwell)
        p.restartReading()
        assertEquals(AmbientState.BRIGHT, p.onLux(100_000, 120f)) // between the limits: still bright
        assertEquals(AmbientState.BRIGHT, p.onLux(101_000, 20f)) // second reading: normal dwell
        assertEquals(AmbientState.NORMAL, p.tick(101_000 + dwell))
    }

    @Test fun restartReadingNeverMakesItBrighterAtOnce() {
        val p = AmbientPolicy()
        p.restartReading()
        assertEquals(AmbientState.NORMAL, p.onLux(0, 1000f)) // entering BRIGHT keeps its dwell
        assertEquals(AmbientState.BRIGHT, p.tick(dwell))
    }

    @Test fun exactlyEightyLuxDoesNotEndBright() {
        val p = AmbientPolicy()
        p.onLux(0, 400f)
        p.tick(dwell)
        p.onLux(20_000, 80f)
        assertEquals(AmbientState.BRIGHT, p.tick(100_000))
    }

    @Test fun darkRoomHasAnExitBand() {
        val p = AmbientPolicy()
        p.onLux(0, 1f)
        assertEquals(AmbientState.DARK_ROOM, p.tick(dwell))
        // 4 lux: above the entry limit but under the exit limit (6): still dark
        p.onLux(20_000, 4f)
        assertNull(p.pendingAtMs())
        assertEquals(AmbientState.DARK_ROOM, p.tick(90_000))
        // 6 lux for ten seconds: back to normal
        p.onLux(100_000, 6f)
        assertEquals(AmbientState.NORMAL, p.tick(100_000 + dwell))
    }

    @Test fun darkToBrightGoesStraightThrough() {
        val p = AmbientPolicy()
        p.onLux(0, 1f)
        p.tick(dwell)
        p.onLux(20_000, 500f) // lights on
        assertEquals(AmbientState.BRIGHT, p.tick(20_000 + dwell))
    }

    @Test fun brightToDarkGoesStraightThrough() {
        val p = AmbientPolicy()
        p.onLux(0, 500f)
        p.tick(dwell)
        p.onLux(20_000, 0f) // lights off
        assertEquals(AmbientState.DARK_ROOM, p.tick(20_000 + dwell))
    }

    @Test fun anotherReadingInTheSameBandKeepsTheRunningDwell() {
        val p = AmbientPolicy()
        p.onLux(0, 1f)
        p.onLux(4_000, 0.5f) // still wants DARK_ROOM: the clock keeps running from 0
        assertEquals(dwell, p.pendingAtMs())
        assertEquals(AmbientState.DARK_ROOM, p.onLux(dwell, 1f))
    }

    @Test fun clearReadingKeepsStateButDropsPendingChange() {
        val p = AmbientPolicy()
        p.onLux(0, 500f)
        p.tick(dwell)
        p.onLux(20_000, 10f) // pending: back to normal
        p.clearReading()
        assertEquals(AmbientState.BRIGHT, p.state)
        assertNull(p.pendingAtMs())
        assertEquals(AmbientState.BRIGHT, p.tick(999_999)) // no stale reading to act on
    }

    @Test fun resetGoesBackToNormal() {
        val p = AmbientPolicy()
        p.onLux(0, 500f)
        p.tick(dwell)
        p.reset()
        assertEquals(AmbientState.NORMAL, p.state)
        assertNull(p.pendingAtMs())
    }

    // --- what the service renders ---

    private val presets = Presets.DEFAULTS

    @Test fun lookOverrideOnlyInADarkRoomWithTheFeatureOn() {
        val deepRed = AmbientLook.override(true, AmbientState.DARK_ROOM, presets, AmbientLook.DEFAULT_DARK_ROOM_PRESET)
        assertNotNull(deepRed)
        assertEquals("deep_red", deepRed.id)
        assertNull(AmbientLook.override(false, AmbientState.DARK_ROOM, presets, "deep_red"))
        assertNull(AmbientLook.override(true, AmbientState.NORMAL, presets, "deep_red"))
        assertNull(AmbientLook.override(true, AmbientState.BRIGHT, presets, "deep_red"))
    }

    @Test fun deletedDarkRoomPresetFallsBackToTheChosenLook() {
        assertNull(AmbientLook.override(true, AmbientState.DARK_ROOM, presets, "gone"))
    }

    @Test fun suspendOnlyWhenBrightAndEnabled() {
        assertTrue(AmbientLook.suspended(true, AmbientState.BRIGHT))
        assertEquals(false, AmbientLook.suspended(false, AmbientState.BRIGHT))
        assertEquals(false, AmbientLook.suspended(true, AmbientState.DARK_ROOM))
        assertEquals(false, AmbientLook.suspended(true, AmbientState.NORMAL))
    }

    // --- diagnostics rows ---

    private fun rows(
        access: Boolean = true,
        hybrid: Boolean = true,
        method: BacklightMethod = BacklightMethod.AUTO,
        write: Boolean = false,
        notif: Boolean = true,
    ) = Diagnostics.rows(access, hybrid, method, write, notif).associateBy { it.item }

    @Test fun healthySetupHasNoFixes() {
        val r = rows()
        assertEquals(DiagItem.entries.toSet(), r.keys)
        assertTrue(r.values.all { it.ok && it.fix == null })
        assertEquals(BacklightMethod.WINDOW, r.getValue(DiagItem.BACKLIGHT).method)
    }

    @Test fun missingAccessibilityOffersSetup() {
        val r = rows(access = false)
        assertEquals(false, r.getValue(DiagItem.ACCESSIBILITY).ok)
        assertEquals(DiagFix.SET_UP_ACCESSIBILITY, r.getValue(DiagItem.ACCESSIBILITY).fix)
        // the backlight method does not depend on the grant: the service owns the window when it runs
        assertEquals(BacklightMethod.WINDOW, r.getValue(DiagItem.BACKLIGHT).method)
    }

    @Test fun systemMethodWithoutWriteSettingsIsAProblemWithAFix() {
        val r = rows(method = BacklightMethod.SYSTEM, write = false)
        val backlight = r.getValue(DiagItem.BACKLIGHT)
        assertEquals(BacklightMethod.NONE, backlight.method)
        assertEquals(false, backlight.ok)
        assertEquals(DiagFix.GRANT_WRITE_SETTINGS, r.getValue(DiagItem.WRITE_SETTINGS).fix)
        assertEquals(false, r.getValue(DiagItem.WRITE_SETTINGS).granted)
    }

    @Test fun systemMethodWithWriteSettingsRunsAndIsGranted() {
        val r = rows(method = BacklightMethod.SYSTEM, write = true)
        assertEquals(BacklightMethod.SYSTEM, r.getValue(DiagItem.BACKLIGHT).method)
        assertTrue(r.getValue(DiagItem.WRITE_SETTINGS).granted)
        assertNull(r.getValue(DiagItem.WRITE_SETTINGS).fix)
    }

    @Test fun writeSettingsNotNeededByTheWindowMethod() {
        val r = rows(method = BacklightMethod.WINDOW, write = false)
        assertTrue(r.getValue(DiagItem.WRITE_SETTINGS).ok)
        assertNull(r.getValue(DiagItem.WRITE_SETTINGS).fix)
    }

    @Test fun hybridOffIsNotAProblem() {
        val r = rows(hybrid = false, method = BacklightMethod.SYSTEM, write = false)
        assertTrue(r.getValue(DiagItem.BACKLIGHT).ok)
        assertEquals(BacklightMethod.NONE, r.getValue(DiagItem.BACKLIGHT).method)
        assertNull(r.getValue(DiagItem.WRITE_SETTINGS).fix)
    }

    @Test fun blockedNotificationsOfferTheNotificationSettings() {
        val r = rows(notif = false)
        assertEquals(false, r.getValue(DiagItem.NOTIFICATIONS).ok)
        assertEquals(DiagFix.NOTIFICATION_SETTINGS, r.getValue(DiagItem.NOTIFICATIONS).fix)
    }
}
