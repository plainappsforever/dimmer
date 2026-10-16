package com.plainapps.dimmer.backlight

import android.view.WindowManager

/**
 * Method 1: the window brightness override. Lowers the backlight through the
 * `screenBrightness` field of a window the caller owns, via [setWindowBrightness] (the
 * caller updates its LayoutParams and calls `updateViewLayout`).
 *
 * Why it works (AOSP, see RESEARCH §2.1): the window manager takes the brightness override
 * from the top-most visible window that is not a `syswin` system dialog, and the display
 * power controller ranks that override above the user's slider and auto-brightness. Nothing
 * is written to Settings, so the user's slider and auto mode are never touched, and the
 * override disappears the moment the window is removed - including on crash or force-stop.
 *
 * Requirement: the window must be visible and on top of what the user sees. An accessibility
 * overlay (TYPE_ACCESSIBILITY_OVERLAY) is; an ordinary activity window only while it is in
 * front. Verified on the emulator only - see docs/DECISIONS.md D-V3-16.
 */
class WindowBacklight(
    private val setWindowBrightness: (Float) -> Unit,
) : Backlight {

    override val method = BacklightMethod.WINDOW

    override fun isAvailable() = true

    override fun apply(target: Float?) {
        setWindowBrightness(toWindowValue(target))
    }

    override fun release() {
        setWindowBrightness(NO_OVERRIDE)
    }

    companion object {
        /** -1: "use the user's preferred brightness". */
        const val NO_OVERRIDE = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE

        /**
         * The floor-of-1 rule, window edition. [HybridCurve.FLOOR] (1/255) means "panel
         * minimum". For Settings that is the int 1 (0 is avoided). For a window override the
         * panel minimum is 0.0f: since Android 11 brightness is a float where 0.0 =
         * PowerManager.BRIGHTNESS_MIN and "off" is -1, and older releases clamp 0 up to the
         * configured minimum. Sending 1/255 instead would sit measurably above the minimum on
         * a linear float scale. Pure; JVM-tested.
         */
        fun toWindowValue(target: Float?): Float = when {
            target == null || target.isNaN() -> NO_OVERRIDE
            target <= HybridCurve.FLOOR -> 0f
            else -> target.coerceAtMost(1f)
        }
    }
}
