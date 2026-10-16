package com.plainapps.dimmer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.plainapps.dimmer.backlight.Backlight
import com.plainapps.dimmer.engine.NotificationAction
import com.plainapps.dimmer.engine.Pause
import com.plainapps.dimmer.engine.WidgetAction

/**
 * Backs the notification buttons (v3 P4; replaces v2's DimmerStopReceiver; P5's widget will use
 * it too, P5). Not exported: only our own PendingIntents reach it. Everything goes through
 * [DimmerCommands], like every other control.
 */
class DimmerActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as DimmerApplication
        val widgetAction = WidgetAction.fromIntentAction(intent.action)
        if (widgetAction != null) {
            onWidgetAction(app, widgetAction, intent.getStringExtra(DimmerWidgetProvider.EXTRA_PRESET_ID))
            return
        }
        val action = NotificationAction.entries.firstOrNull { intentAction(it) == intent.action } ?: return
        Log.i(Backlight.LOG_TAG, "notification action $action")
        val reason = "notification"
        app.runWhenLoaded(this) {
            val commands = app.commands
            when (action) {
                NotificationAction.LIGHTER -> commands.adjustDarkness(-STEP, reason)
                NotificationAction.DARKER -> commands.adjustDarkness(STEP, reason)
                NotificationAction.PAUSE -> commands.pause(Pause.NOTIFICATION_MINUTES, reason)
                NotificationAction.RESUME -> commands.resume(reason)
                NotificationAction.OFF -> commands.turnOff(reason)
                NotificationAction.TURN_ON -> commands.turnOn(reason)
            }
        }
    }

    /** v3 P5: widget taps. Same rules as every other control: through [DimmerCommands]. */
    private fun onWidgetAction(app: DimmerApplication, action: WidgetAction, presetId: String?) {
        Log.i(Backlight.LOG_TAG, "widget action $action ${presetId ?: ""}".trim())
        val reason = "widget"
        app.runWhenLoaded(this) {
            val commands = app.commands
            when (action) {
                WidgetAction.TOGGLE -> commands.toggle(reason)
                WidgetAction.CYCLE -> commands.nextPreset(reason)
                WidgetAction.PRESET -> if (presetId != null) commands.applyPreset(presetId, turnOn = true)
            }
        }
    }

    companion object {
        /** Lighter / Darker step (SPEC 5). */
        const val STEP = 10

        fun intentAction(action: NotificationAction) = "com.plainapps.dimmer.internal.${action.name}"
    }
}
