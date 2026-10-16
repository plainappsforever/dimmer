package com.plainapps.dimmer.engine

import com.plainapps.dimmer.backlight.BacklightMethod
import com.plainapps.dimmer.backlight.BacklightMethods

/** The rows of Settings > Status (v3 P6, SPEC 7). */
enum class DiagItem { ACCESSIBILITY, BACKLIGHT, WRITE_SETTINGS, NOTIFICATIONS, SCHEDULE }

/** The one-tap fix a problem row offers. */
enum class DiagFix { SET_UP_ACCESSIBILITY, GRANT_WRITE_SETTINGS, NOTIFICATION_SETTINGS }

/**
 * [ok] false = a problem the user can act on ([fix] says how). [method] is set for BACKLIGHT only:
 * the backend that will really run (NONE = overlay only). [granted] is set for WRITE_SETTINGS only.
 */
data class DiagRow(
    val item: DiagItem,
    val ok: Boolean,
    val fix: DiagFix? = null,
    val method: BacklightMethod? = null,
    val granted: Boolean = false,
)

/** Pure: turns the current permissions and settings into the status rows (JVM-tested). */
object Diagnostics {
    fun rows(
        accessibilityOn: Boolean,
        hybridEnabled: Boolean,
        chosenMethod: BacklightMethod,
        canWriteSettings: Boolean,
        notificationsAllowed: Boolean,
    ): List<DiagRow> {
        val chosen = if (hybridEnabled) chosenMethod else BacklightMethod.NONE
        // The service always owns the overlay window while dimming, so the window override is possible.
        val running = BacklightMethods.resolve(chosen, windowPossible = true, canWriteSettings = canWriteSettings)
        // "Modify system settings" only matters when the chosen method needs it and cannot run without it.
        val needsWrite = chosen != BacklightMethod.NONE &&
            BacklightMethods.resolve(chosen, windowPossible = true, canWriteSettings = false) == BacklightMethod.NONE
        return listOf(
            DiagRow(DiagItem.ACCESSIBILITY, accessibilityOn, if (accessibilityOn) null else DiagFix.SET_UP_ACCESSIBILITY),
            DiagRow(DiagItem.BACKLIGHT, ok = !hybridEnabled || running != BacklightMethod.NONE, method = running),
            DiagRow(
                DiagItem.WRITE_SETTINGS,
                ok = canWriteSettings || !needsWrite,
                fix = if (canWriteSettings || !needsWrite) null else DiagFix.GRANT_WRITE_SETTINGS,
                granted = canWriteSettings,
            ),
            DiagRow(DiagItem.NOTIFICATIONS, notificationsAllowed, if (notificationsAllowed) null else DiagFix.NOTIFICATION_SETTINGS),
            DiagRow(DiagItem.SCHEDULE, ok = true),
        )
    }
}
