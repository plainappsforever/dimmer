package com.plainapps.dimmer

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import com.plainapps.dimmer.backlight.Backlight
import com.plainapps.dimmer.engine.Presets
import com.plainapps.dimmer.engine.ShortcutCommand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The target of every app shortcut (static and pinned): a transparent, never-visible activity that
 * runs the command through [DimmerCommands] and finishes (SPEC 6: "a shortcut applies without
 * showing the app UI"). Without Accessibility access it opens the app instead, where the grant is.
 * Not exported: the launcher starts shortcuts on the app's behalf.
 */
class ShortcutActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as DimmerApplication
        val command = ShortcutCommand.parse(intent?.action, intent?.getStringExtra(ShortcutCommand.EXTRA_PRESET_ID))
        if (command == null) {
            finish()
            return
        }
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
            try {
                // A shortcut can cold-start the process: wait for DataStore before changing anything.
                withTimeoutOrNull(LOAD_WAIT_MS) { app.repository.state.first { it.loaded } }
                run(app, command)
            } finally {
                finish()
            }
        }
    }

    private fun run(app: DimmerApplication, command: ShortcutCommand) {
        val commands = app.commands
        when (command) {
            ShortcutCommand.Off -> {
                Log.i(Backlight.LOG_TAG, "shortcut off")
                commands.turnOff("shortcut")
            }
            is ShortcutCommand.ApplyPreset -> {
                if (Presets.find(app.repository.state.value.presets, command.presetId) == null) {
                    Log.i(Backlight.LOG_TAG, "shortcut preset ${command.presetId} not found")
                    Toast.makeText(this, R.string.shortcut_preset_deleted, Toast.LENGTH_SHORT).show()
                } else if (!DimmerAccessibilityService.isEnabled(this)) {
                    startActivity(Intent(this, MainActivity::class.java))
                } else {
                    Log.i(Backlight.LOG_TAG, "shortcut preset ${command.presetId}")
                    commands.applyPreset(command.presetId, turnOn = true)
                }
            }
        }
    }

    private companion object {
        const val LOAD_WAIT_MS = 2_000L
    }
}
