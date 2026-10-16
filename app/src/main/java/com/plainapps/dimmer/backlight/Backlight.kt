package com.plainapps.dimmer.backlight

/**
 * One way of lowering the real display backlight. Two implementations:
 * - [WindowBacklight]  - sets `LayoutParams.screenBrightness` on a window the caller owns.
 *                        No permission; the override vanishes with the window (crash-safe).
 * - [SettingsBacklight] - writes `Settings.System.SCREEN_BRIGHTNESS` after journaling the
 *                        user's value; needs WRITE_SETTINGS ("Modify system settings").
 * Plus [NoBacklight] for "overlay only".
 *
 * Contract:
 * - [apply] may be called any number of times (e.g. every slider step); `null` = no override
 *   (for SettingsBacklight that is the same as [release]).
 * - [release] must be called when dimming ends; it is idempotent.
 * - Targets are 0..1 fractions; callers should never go below [HybridCurve.FLOOR].
 *
 * This package imports nothing from the rest of the app so it can be copied as-is into
 * another app (see docs/HYBRID_ENGINE.md).
 */
interface Backlight {
    val method: BacklightMethod
    fun isAvailable(): Boolean
    fun apply(target: Float?)
    fun release()

    companion object {
        /** Logcat tag for one-line state transitions. Rename when copying into another app. */
        const val LOG_TAG = "DimmerV3"
    }
}

/** User-facing choice. [AUTO] is resolved with [BacklightMethods.resolve]. */
enum class BacklightMethod { AUTO, WINDOW, SYSTEM, NONE }

object BacklightMethods {
    /**
     * Result of the P1 emulator experiment (docs/DECISIONS.md D-V3-16): whether AUTO
     * prefers the window override. If false, AUTO means SYSTEM when granted, else NONE.
     */
    const val AUTO_PREFERS_WINDOW = true

    /**
     * Picks the backend that will actually run.
     * @param windowPossible true when the caller owns a window it can put the override on.
     * @param canWriteSettings `Settings.System.canWrite(context)`.
     */
    fun resolve(
        chosen: BacklightMethod,
        windowPossible: Boolean,
        canWriteSettings: Boolean,
    ): BacklightMethod = when (chosen) {
        BacklightMethod.NONE -> BacklightMethod.NONE
        BacklightMethod.WINDOW -> if (windowPossible) BacklightMethod.WINDOW else BacklightMethod.NONE
        BacklightMethod.SYSTEM -> if (canWriteSettings) BacklightMethod.SYSTEM else BacklightMethod.NONE
        BacklightMethod.AUTO -> when {
            AUTO_PREFERS_WINDOW && windowPossible -> BacklightMethod.WINDOW
            canWriteSettings -> BacklightMethod.SYSTEM
            else -> BacklightMethod.NONE
        }
    }
}

/** Overlay only: never touches the backlight. */
object NoBacklight : Backlight {
    override val method = BacklightMethod.NONE
    override fun isAvailable() = true
    override fun apply(target: Float?) = Unit
    override fun release() = Unit
}
