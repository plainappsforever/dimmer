package com.plainapps.dimmer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.plainapps.dimmer.backlight.Backlight
import com.plainapps.dimmer.engine.AutomationCommand

/**
 * Tasker / automation intents (v3 P4, SPEC 9): `com.plainapps.dimmer.action.ON | OFF | TOGGLE |
 * PAUSE (minutes) | RESUME | DARKER | LIGHTER (step) | SET (darkness, tint, strength) |
 * PRESET (name) | NEXT_PRESET`. Exported, but the component is disabled in the manifest and only
 * enabled while the user has "Allow other apps to control Dimmer" on (D-V3-13), so with the
 * switch off no broadcast can reach it at all.
 */
class AutomationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as DimmerApplication
        // Any app can send us anything once this is enabled: a malformed extras Bundle throws
        // when it is unparcelled, so reading it must never crash the process (L13 review P4 #16).
        val cmd = try {
            val extras = intent.extras
            @Suppress("DEPRECATION")
            AutomationCommand.parse(intent.action) { key -> extras?.get(key) }
        } catch (e: RuntimeException) {
            Log.w(Backlight.LOG_TAG, "automation ${intent.action} ignored (unreadable extras: ${e.javaClass.simpleName})")
            return
        }
        if (cmd == null) {
            Log.i(Backlight.LOG_TAG, "automation ${intent.action} ignored (unknown action or missing extras)")
            return
        }
        app.runWhenLoaded(this) {
            // Belt and braces: the component state is the gate, the setting is checked too.
            if (!app.repository.state.value.automationEnabled) {
                Log.i(Backlight.LOG_TAG, "automation $cmd ignored (not allowed)")
                return@runWhenLoaded
            }
            val ok = app.commands.execute(cmd)
            Log.i(Backlight.LOG_TAG, "automation $cmd -> ${if (ok) "done" else "not done"}")
        }
    }
}
