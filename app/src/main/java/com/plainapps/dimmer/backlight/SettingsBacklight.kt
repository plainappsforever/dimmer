package com.plainapps.dimmer.backlight

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log

/**
 * Method 2: writes the system brightness setting (what Twilight and Night screen do).
 * Needs WRITE_SETTINGS, granted by the user on the "Modify system settings" screen
 * (`Settings.ACTION_MANAGE_WRITE_SETTINGS` with a `package:` URI); check [isAvailable].
 *
 * Lifecycle of one dimming session:
 * 1. first non-null [apply]: journal the user's {brightness, mode} with a synchronous commit,
 *    THEN switch the mode to manual (automatic mode ignores writes on many devices) and write
 *    `max(1, round(target * 255))`;
 * 2. later [apply]s: journal the new value first, then write it (identical ints are skipped);
 * 3. a ContentObserver watches brightness + mode: any value Dimmer did not write means the user
 *    took the backlight back -> Dimmer stops writing for the rest of the session (recorded in
 *    the journal);
 * 4. [release] (or `apply(null)`): restore per [RestoreRules] - both values normally; only the
 *    mode if the user moved the slider; nothing if the user changed the mode - then clear the
 *    journal.
 * A crash between 1 and 4 is repaired by [BacklightJournal.restoreIfPending] on the next
 * process start / boot / app update.
 *
 * Call from the main thread only (the observer is delivered there too).
 */
class SettingsBacklight(
    context: Context,
    private val journal: BacklightJournal = BacklightJournal(context),
) : Backlight {

    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private var observer: ContentObserver? = null

    override val method = BacklightMethod.SYSTEM

    override fun isAvailable(): Boolean = Settings.System.canWrite(appContext)

    override fun apply(target: Float?) {
        if (target == null) {
            release()
            return
        }
        if (!isAvailable()) return
        val value = HybridCurve.toSettingsInt(target)
        var entry = journal.load()
        if (entry != null && observer == null) {
            // A journal left by an earlier session (crash, or a restore refused while the
            // permission was revoked): put the user's values back first, so the new journal
            // never records our own floor as "the user's brightness".
            journal.restoreIfPending(appContext)
            entry = journal.load()
            if (entry != null) return // could not restore; do not stack a new session on it
        }
        if (entry == null) {
            val current = SystemBrightness.read(appContext)
            entry = JournalEntry(current.brightness, current.mode, System.currentTimeMillis())
            if (!journal.save(entry)) {
                Log.w(Backlight.LOG_TAG, "backlight journal commit failed; not touching Settings")
                return
            }
            startObserving()
            Log.i(Backlight.LOG_TAG, "system backlight: journaled ${current.brightness}/mode ${current.mode}")
        }
        if (entry.takeover != Takeover.NONE) return
        val current = SystemBrightness.read(appContext)
        if (RestoreRules.detectTakeover(entry, current.brightness, current.mode) != Takeover.NONE) {
            markTakeover(entry, current)
            return
        }
        if (value == entry.written && current.mode == RestoreRules.MODE_MANUAL) return
        val next = entry.copy(written = value, previousWritten = entry.written)
        if (!journal.save(next)) return
        try {
            if (current.mode != RestoreRules.MODE_MANUAL) {
                Settings.System.putInt(
                    resolver,
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
                )
            }
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, value)
        } catch (e: SecurityException) {
            Log.w(Backlight.LOG_TAG, "system backlight write refused: ${e.message}")
        }
    }

    override fun release() {
        stopObserving()
        val entry = journal.load() ?: return
        val current = SystemBrightness.read(appContext)
        val plan = RestoreRules.plan(entry, current.brightness, current.mode)
        if (SystemBrightness.write(resolver, plan)) {
            journal.clear()
        }
        Log.i(Backlight.LOG_TAG, "system backlight released: $plan")
    }

    private fun markTakeover(entry: JournalEntry, current: SystemBrightness.Current) {
        if (entry.takeover != Takeover.NONE) return
        val takeover = RestoreRules.detectTakeover(entry, current.brightness, current.mode)
        if (takeover == Takeover.NONE) return
        journal.save(entry.copy(takeover = takeover))
        Log.i(Backlight.LOG_TAG, "system backlight: user took over ($takeover), no more writes this session")
    }

    private fun startObserving() {
        if (observer != null) return
        val o = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                val entry = journal.load() ?: return
                markTakeover(entry, SystemBrightness.read(appContext))
            }
        }
        resolver.registerContentObserver(Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS), false, o)
        resolver.registerContentObserver(Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS_MODE), false, o)
        observer = o
        activeSessions.incrementAndGet()
    }

    private fun stopObserving() {
        val o = observer ?: return
        resolver.unregisterContentObserver(o)
        observer = null
        activeSessions.decrementAndGet()
    }

    companion object {
        private val activeSessions = java.util.concurrent.atomic.AtomicInteger(0)

        /**
         * v3.1 (REVIEW_V3 finding 7): true while a session in this process owns the journal (it
         * has journaled the user's values and is writing). [BacklightRestoreReceiver] must not
         * replay the journal then (HYBRID_ENGINE.md: never restoreIfPending during a session).
         */
        val isSessionActive: Boolean get() = activeSessions.get() > 0
    }
}
