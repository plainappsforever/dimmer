package com.plainapps.dimmer.backlight

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import android.util.Log

/**
 * Crash-safety for [SettingsBacklight]: the user's own brightness and mode are written to disk
 * (SharedPreferences `commit()`, synchronous) BEFORE Dimmer writes any Settings value, and
 * replayed by [restoreIfPending] on every process start and on BOOT_COMPLETED /
 * MY_PACKAGE_REPLACED ([BacklightRestoreReceiver]). So a crash, force-stop or reboot during
 * dimming can never strand the phone at minimum brightness for longer than "until Dimmer's
 * process next starts".
 *
 * The window method ([WindowBacklight]) needs none of this: its override dies with its window.
 */
class BacklightJournal(private val store: JournalStore) {

    constructor(context: Context) : this(SharedPrefsJournalStore(context))

    fun load(): JournalEntry? = store.load()

    /** Synchronous. Returns false if the entry could not be persisted (then do NOT write Settings). */
    fun save(entry: JournalEntry): Boolean = store.save(entry)

    fun clear() {
        store.save(null)
    }

    /**
     * If a previous session left Settings modified, put the user's values back (following
     * [RestoreRules]) and clear the journal. Safe to call any time no [SettingsBacklight] of
     * this process is active. Keeps the journal if WRITE_SETTINGS is currently not granted,
     * so the next start retries.
     */
    fun restoreIfPending(context: Context): Boolean {
        val entry = load() ?: return false
        if (!Settings.System.canWrite(context)) {
            if (!warnedNoPermission) { // once per process: callers may retry on every render
                Log.w(Backlight.LOG_TAG, "backlight journal pending but WRITE_SETTINGS not granted; keeping it")
                warnedNoPermission = true
            }
            return false
        }
        warnedNoPermission = false
        val resolver = context.contentResolver
        val current = SystemBrightness.read(context)
        val plan = RestoreRules.plan(entry, current.brightness, current.mode)
        val ok = SystemBrightness.write(resolver, plan)
        if (ok) clear()
        Log.i(Backlight.LOG_TAG, "backlight journal replayed: $plan ok=$ok")
        return ok
    }

    companion object {
        @Volatile
        private var warnedNoPermission = false

        /** Convenience for app start / receivers. */
        fun restoreIfPending(context: Context): Boolean =
            BacklightJournal(context.applicationContext).restoreIfPending(context.applicationContext)
    }
}

/**
 * Recorded when the live observer sees the user change brightness or mode. It only means
 * "stop writing for this session"; what is restored is decided by [RestoreRules.plan].
 */
enum class Takeover { NONE, BRIGHTNESS, MODE }

/**
 * @property brightness the user's SCREEN_BRIGHTNESS (0..255) before Dimmer's first write
 * @property mode the user's SCREEN_BRIGHTNESS_MODE (0 manual, 1 automatic) before that
 * @property written the value Dimmer last wrote (or is about to write); equals [brightness] until the first write
 * @property previousWritten the value written before [written] (a crash between journaling and writing leaves this in Settings)
 */
data class JournalEntry(
    val brightness: Int,
    val mode: Int,
    val timestampMs: Long,
    val written: Int = brightness,
    val previousWritten: Int = brightness,
    val takeover: Takeover = Takeover.NONE,
)

/** One-line text format, stored under a single key so every save is atomic. Pure, JVM-tested. */
object JournalCodec {
    private const val VERSION = "1"

    fun encode(e: JournalEntry): String =
        listOf(VERSION, e.brightness, e.mode, e.timestampMs, e.written, e.previousWritten, e.takeover.name)
            .joinToString("|")

    /** Returns null for anything unreadable (then there is nothing trustworthy to restore). */
    fun decode(text: String?): JournalEntry? {
        val parts = text?.split("|") ?: return null
        if (parts.size != 7 || parts[0] != VERSION) return null
        return try {
            JournalEntry(
                brightness = parts[1].toInt().coerceIn(0, 255),
                mode = parts[2].toInt(),
                timestampMs = parts[3].toLong(),
                written = parts[4].toInt().coerceIn(0, 255),
                previousWritten = parts[5].toInt().coerceIn(0, 255),
                takeover = Takeover.valueOf(parts[6]),
            )
        } catch (e: IllegalArgumentException) { // NumberFormatException is a subclass
            null
        }
    }
}

/** What to write back. null = leave that value alone. */
data class RestorePlan(val brightness: Int?, val mode: Int?)

/**
 * Pure restore/take-over rules, shared by the live observer and the crash replay. JVM-tested.
 *
 * Principle: what gets restored is decided from the CURRENT Settings values at restore time,
 * never from a remembered "the user took over" flag alone. Dimmer always leaves the mode at
 * manual and the brightness at [JournalEntry.written] (or [JournalEntry.previousWritten] while
 * a write is in flight), so:
 * - mode still manual AND brightness still ours -> restore both (the normal case);
 * - mode still manual, brightness NOT ours      -> the user moved the slider: keep their value,
 *                                                  restore only the mode (e.g. back to auto);
 * - mode no longer manual                       -> the user switched auto on: restore nothing
 *                                                  (auto mode ignores the stored brightness, so
 *                                                  nothing can be stranded at the floor).
 * [TOLERANCE] absorbs AOSP's int<->float brightness round-trip (BrightnessSynchronizer), so a
 * system re-write of our own value is not mistaken for the user. A false "not ours" could only
 * keep a value the user can see and change; a false "ours" could strand the floor, so the
 * tolerance errs towards "ours".
 */
object RestoreRules {
    const val MODE_MANUAL = 0 // Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
    const val MODE_AUTOMATIC = 1 // Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
    const val TOLERANCE = 2

    fun isOurs(entry: JournalEntry, currentBrightness: Int): Boolean =
        kotlin.math.abs(currentBrightness - entry.written) <= TOLERANCE ||
            kotlin.math.abs(currentBrightness - entry.previousWritten) <= TOLERANCE

    /** Live check: should Dimmer stop writing for the rest of this session? */
    fun detectTakeover(entry: JournalEntry, currentBrightness: Int, currentMode: Int): Takeover = when {
        // Before our first write the mode may legitimately still be the user's auto mode.
        currentMode != MODE_MANUAL && (entry.written != entry.brightness || entry.mode == MODE_MANUAL) ->
            Takeover.MODE
        !isOurs(entry, currentBrightness) -> Takeover.BRIGHTNESS
        else -> Takeover.NONE
    }

    fun plan(entry: JournalEntry, currentBrightness: Int, currentMode: Int): RestorePlan = when {
        // The user switched auto on - or we crashed after journaling but before touching the
        // mode, in which case nothing was changed. Either way: leave everything alone.
        currentMode != MODE_MANUAL -> RestorePlan(brightness = null, mode = null)
        isOurs(entry, currentBrightness) -> RestorePlan(brightness = entry.brightness, mode = entry.mode)
        else -> RestorePlan(brightness = null, mode = entry.mode)
    }
}

/** Where the journal lives. Tests use an in-memory store. */
interface JournalStore {
    fun load(): JournalEntry?

    /** Must be synchronous; null clears. */
    fun save(entry: JournalEntry?): Boolean
}

class SharedPrefsJournalStore(context: Context) : JournalStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun load(): JournalEntry? = JournalCodec.decode(prefs.getString(KEY, null))

    // commit(), not apply(): the entry must be on disk before Settings is touched.
    // (Plain SharedPreferences API on purpose - no androidx dependency, easy to copy.)
    @SuppressLint("ApplySharedPref", "UseKtx")
    override fun save(entry: JournalEntry?): Boolean {
        val editor = prefs.edit()
        if (entry == null) editor.remove(KEY) else editor.putString(KEY, JournalCodec.encode(entry))
        return editor.commit()
    }

    private companion object {
        const val PREFS = "backlight_journal"
        const val KEY = "entry"
    }
}

/** Thin, exception-safe wrapper around Settings.System brightness reads/writes. */
internal object SystemBrightness {
    data class Current(val brightness: Int, val mode: Int)

    /** Reading needs no permission. Falls back to "full, manual" if the values are missing. */
    fun read(context: Context): Current {
        val r = context.contentResolver
        return Current(
            brightness = Settings.System.getInt(r, Settings.System.SCREEN_BRIGHTNESS, 255),
            mode = Settings.System.getInt(
                r,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
            ),
        )
    }

    /** Brightness first (while still manual), then the mode. Needs WRITE_SETTINGS. */
    fun write(resolver: android.content.ContentResolver, plan: RestorePlan): Boolean = try {
        plan.brightness?.let { Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, it) }
        plan.mode?.let { Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, it) }
        true
    } catch (e: SecurityException) {
        Log.w(Backlight.LOG_TAG, "brightness write refused: ${e.message}")
        false
    }
}
