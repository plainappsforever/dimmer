package com.plainapps.dimmer.backlight

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Replays the [BacklightJournal] after a reboot or an app update, so a crash while
 * [SettingsBacklight] held the brightness cannot leave the phone dim until the app is opened.
 * Manifest: not exported; receives the system-sent BOOT_COMPLETED and MY_PACKAGE_REPLACED.
 * (After a force-stop the app gets no broadcasts until it is opened again; the app's
 * `Application.onCreate` replays the journal then.)
 */
class BacklightRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                // v3.1 (finding 7): with "resume after restart" a new session can already be
                // running when BOOT_COMPLETED arrives; its journal is not stale - leave it alone.
                if (SettingsBacklight.isSessionActive) {
                    Log.i(Backlight.LOG_TAG, "restore receiver ${intent.action}: skipped (system-brightness session active)")
                    return
                }
                val restored = BacklightJournal.restoreIfPending(context)
                Log.i(Backlight.LOG_TAG, "restore receiver ${intent.action}: restored=$restored")
            }
        }
    }
}
