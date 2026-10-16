package com.plainapps.dimmer

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * Used only by [DimmerAccessibilityService]. Adds/removes a single plain,
 * non-touchable overlay View and recolors it in place — level/warmth changes
 * only ever call setBackgroundColor, never updateViewLayout.
 *
 * TYPE_ACCESSIBILITY_OVERLAY (not TYPE_APPLICATION_OVERLAY): windows added by a
 * bound AccessibilityService are trusted, so unlike an ordinary app overlay they
 * are exempt from Android 12+'s touch-obscuring-opacity ceiling and can render
 * above system UI (status bar, notification shade, even Settings). See
 * docs/DECISIONS.md.
 *
 * v3 P1: the window also carries the hybrid engine's backlight override
 * (`screenBrightness`, set only through [setBrightness], which backlight/WindowBacklight
 * calls) and, when asked, FLAG_KEEP_SCREEN_ON. Both live on this window, so neither can
 * outlive the overlay. [updateBounds] re-sizes the window after rotation / fold.
 */
class OverlayController(private val context: Context) {

    private var overlayView: View? = null
    private var params: WindowManager.LayoutParams? = null

    private val windowManager: WindowManager
        get() = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    fun isShowing(): Boolean = overlayView != null

    /** Returns false if the overlay could not be added (permission revoked, no valid token, ...). */
    fun show(initialColor: Int, brightness: Float, keepAwake: Boolean): Boolean {
        if (overlayView != null) return true
        val view = View(context).apply { setBackgroundColor(initialColor) }
        val (width, height) = realDisplaySize()
        val params = WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        // MATCH_PARENT resolves to the "stable" area (status bar excluded) for this
        // window type - explicit real-display pixel dimensions, anchored top-left, are
        // what actually extends the frame up into the status bar. See docs/DECISIONS.md.
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 0
        params.y = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            params.fitInsetsTypes = 0
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS itself needs API 30, unlike the
            // other _DEFAULT/_SHORT_EDGES/_NEVER constants (API 28) - easy to
            // misremember, lint catches the mismatch if this guard ever regresses.
            params.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        // Set before addView: the backlight change and the overlay appear in the same frame.
        params.screenBrightness = brightness
        if (keepAwake) params.flags = params.flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        return try {
            windowManager.addView(view, params)
            overlayView = view
            this.params = params
            true
        } catch (e: SecurityException) {
            false
        } catch (e: WindowManager.BadTokenException) {
            false
        } catch (e: IllegalArgumentException) {
            false
        }
    }

    /** The full physical display size in pixels, status bar and all. */
    private fun realDisplaySize(): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            return bounds.width() to bounds.height()
        }
        val point = Point()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealSize(point)
        return point.x to point.y
    }

    fun updateColor(color: Int) {
        overlayView?.setBackgroundColor(color)
    }

    /** Window brightness override: 0..1, or BRIGHTNESS_OVERRIDE_NONE (-1). No-op when hidden/unchanged. */
    fun setBrightness(value: Float) {
        val p = params ?: return
        if (p.screenBrightness == value) return
        p.screenBrightness = value
        relayout()
    }

    /** Returns true if the flag actually changed. */
    fun setKeepAwake(on: Boolean): Boolean {
        val p = params ?: return false
        val flag = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        val newFlags = if (on) p.flags or flag else p.flags and flag.inv()
        if (newFlags == p.flags) return false
        p.flags = newFlags
        relayout()
        return true
    }

    /**
     * Re-reads the real display size (after rotation / fold) and re-sizes the window.
     * Returns the new size, or null when hidden/unchanged (for logging).
     */
    fun updateBounds(): Pair<Int, Int>? {
        val p = params ?: return null
        val (width, height) = realDisplaySize()
        if (p.width == width && p.height == height) return null
        p.width = width
        p.height = height
        relayout()
        return width to height
    }

    private fun relayout() {
        val view = overlayView ?: return
        val p = params ?: return
        try {
            windowManager.updateViewLayout(view, p)
        } catch (e: IllegalArgumentException) {
            // View already detached (token died) - hide() will clean up.
        }
    }

    fun hide() {
        val view = overlayView ?: return
        overlayView = null
        params = null
        try {
            windowManager.removeView(view)
        } catch (e: IllegalArgumentException) {
            // Already detached (e.g. window token died) - nothing left to clean up.
        }
    }
}
