package com.plainapps.dimmer

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Notification
import android.app.NotificationManager
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.plainapps.dimmer.backlight.Backlight
import com.plainapps.dimmer.backlight.BacklightJournal
import com.plainapps.dimmer.backlight.BacklightMethod
import com.plainapps.dimmer.backlight.BacklightMethods
import com.plainapps.dimmer.backlight.HybridCurve
import com.plainapps.dimmer.backlight.NoBacklight
import com.plainapps.dimmer.backlight.SettingsBacklight
import com.plainapps.dimmer.backlight.WindowBacklight
import com.plainapps.dimmer.engine.AmbientLook
import com.plainapps.dimmer.engine.AmbientPolicy
import com.plainapps.dimmer.engine.AmbientState
import com.plainapps.dimmer.engine.DimSource
import com.plainapps.dimmer.engine.NotificationAction
import com.plainapps.dimmer.engine.NotificationPlan
import com.plainapps.dimmer.engine.Resolver
import com.plainapps.dimmer.engine.ShakeDetector
import com.plainapps.dimmer.engine.ShakeSensitivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Bound accessibility service that mirrors [DimmerRepository] state onto a
 * trusted overlay window and a notification. It exists purely to host the
 * overlay - it never inspects screen content or acts on accessibility events
 * (canRetrieveWindowContent="false" in accessibility_service_config.xml).
 *
 * Unlike DimmerService (v1), this has no foreground-service lifecycle: the
 * process is kept alive by the system's accessibility bind, not by
 * startForeground/stopSelf. Enable/disable is driven entirely by
 * DimmerRepository.state; the service itself is "on" for as long as the user
 * has accessibility access granted, whether or not the dimmer is active.
 *
 * v3 P1: renders the hybrid engine too. Per state change: resolve the backlight method,
 * run backlight/HybridCurve over the darkness level, colour the overlay with the curve's
 * overlay part, and hand the backlight part to the active [Backlight] backend.
 *
 * v3 P4: a pause hides the overlay and releases the backlight like Off, but dimming stays on and
 * the notification stays (Resume / Off). The accelerometer (shake to turn off) is registered
 * only while the overlay is visible and the screen is on. The notification is derived from state
 * by [DimmerCommands.notificationPlan] and only re-posted when that plan changes.
 *
 * v3 P6: with "Adjust to room light" on, the light sensor is read while dimming is on, visible-or-
 * suspended and the screen is on ([AmbientPolicy] decides; a dark room renders the dark-room
 * preset, a bright room suspends the overlay and the backlight override). When the service is
 * unbound while dimming was on (the user switched access off), a "Dimmer stopped" notice is posted.
 */
@OptIn(FlowPreview::class)
class DimmerAccessibilityService : AccessibilityService() {

    private lateinit var overlayController: OverlayController
    private lateinit var repository: DimmerRepository
    private lateinit var commands: DimmerCommands
    private var serviceScope: CoroutineScope? = null

    /** What the notification currently shows (None = nothing posted). */
    private var postedPlan: NotificationPlan = NotificationPlan.None

    /** Active backlight backend; [NoBacklight] whenever dimming is off. */
    private var backlight: Backlight = NoBacklight

    private var lastLoggedColor = 0

    /**
     * Start value of the hybrid curve (0..1, NaN = unknown). v3.1 (REVIEW_V3 finding 2): re-read on
     * every render until the backlight part first engages in this session, so it is the panel's
     * value at that moment (with adaptive brightness on, AOSP keeps this setting at the current
     * automatic level - UNVERIFIED on a Pixel), not the value from when a schedule fade began.
     */
    private var sessionStartBacklight = Float.NaN
    private var backlightEngaged = false

    /**
     * "Is an unlocked user in front of the screen?" - gates keep-awake so it never holds the
     * lock screen on (e.g. after a pocket wake). SCREEN_OFF sets it false at once; every
     * screen event then re-checks KeyguardManager now and again [KEYGUARD_RECHECK_MS] later,
     * because at USER_PRESENT the keyguard can still report "locked" for a moment (measured
     * on the emulator). The broadcast action itself is never trusted to set "true", so a
     * spoofed USER_PRESENT (the receiver must be exported to get it at all) changes nothing.
     */
    private val userPresent = MutableStateFlow(true)
    private var screenReceiverRegistered = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val recheckKeyguard = Runnable { userPresent.value = !keyguardLocked() }

    /** Screen on/off (P4: the shake sensor and the pause ticker run only while it is on). */
    private val screenOn = MutableStateFlow(true)

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> screenOn.value = false
                Intent.ACTION_SCREEN_ON -> screenOn.value = true
            }
            if (intent.action != Intent.ACTION_SCREEN_OFF) evaluateAndTick(intent.action?.substringAfterLast('.') ?: "screen")
            userPresent.value = intent.action != Intent.ACTION_SCREEN_OFF && !keyguardLocked()
            mainHandler.removeCallbacks(recheckKeyguard)
            if (intent.action != Intent.ACTION_SCREEN_OFF) {
                mainHandler.postDelayed(recheckKeyguard, KEYGUARD_RECHECK_MS)
            }
            Log.i(Backlight.LOG_TAG, "screen event ${intent.action?.substringAfterLast('.')} -> userPresent=${userPresent.value}")
        }
    }

    /**
     * v3 P3 schedule clock (D-V3-03: no alarms). A separate receiver from [screenReceiver], so
     * the per-minute tick never touches the keyguard logic. These are protected system
     * broadcasts; TIME_TICK only reaches dynamically registered receivers.
     */
    private var timeReceiverRegistered = false
    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action?.substringAfterLast('.') ?: "?"
            if (intent.action != Intent.ACTION_TIME_TICK) Log.i(Backlight.LOG_TAG, "time event $action")
            evaluateAndTick(action)
        }
    }

    /** Re-render trigger for the fade (the fraction depends on the clock, not on state). */
    private val fadeTick = MutableStateFlow(0L)
    private val fadeTicker = Runnable { evaluateAndTick("fade ticker") }
    private var lastFadeLogMinute = -1L

    private fun evaluateAndTick(reason: String) {
        if (!::commands.isInitialized) return
        commands.evaluateSchedule(reason)
        fadeTick.value = fadeTick.value + 1
    }

    private fun isScreenOn(): Boolean = getSystemService(PowerManager::class.java)?.isInteractive ?: true

    // --- shake to turn off (P4, SPEC 4) ---
    private val shakeDetector = ShakeDetector()
    private var shakeRegistered = false
    private val shakeListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val v = event.values
            if (v.size < 3) return
            if (shakeDetector.add(event.timestamp / 1_000_000, v[0], v[1], v[2])) {
                Log.i(Backlight.LOG_TAG, "shake → off (sensitivity=${shakeDetector.sensitivity})")
                commands.turnOff("shake")
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    /** Registers the accelerometer only while it is needed (battery, SPEC 4). */
    private fun updateShake(wanted: Boolean, sensitivity: ShakeSensitivity) {
        shakeDetector.sensitivity = sensitivity
        if (wanted == shakeRegistered) return
        val sm = getSystemService(SensorManager::class.java)
        if (wanted) {
            val accel = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            if (sm == null || accel == null) return
            shakeDetector.reset()
            shakeDetector.maxRange = accel.maximumRange.takeIf { it > 0f } ?: Float.MAX_VALUE
            shakeRegistered = sm.registerListener(shakeListener, accel, SensorManager.SENSOR_DELAY_GAME, mainHandler)
            Log.i(
                Backlight.LOG_TAG,
                "shake listener on=$shakeRegistered (sensitivity=$sensitivity range=${accel.maximumRange} threshold=${"%.1f".format(shakeDetector.threshold)})",
            )
        } else {
            sm?.unregisterListener(shakeListener)
            shakeRegistered = false
            Log.i(Backlight.LOG_TAG, "shake listener off")
        }
    }

    // --- room light (P6, SPEC 8) ---
    private val ambientPolicy = AmbientPolicy()
    private var lightRegistered = false
    private var lastLux = Float.NaN
    private var lastLuxLogMs = 0L
    private val lightTicker = Runnable { applyAmbient(ambientPolicy.tick(SystemClock.elapsedRealtime()), "dwell") }
    private val lightListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.values.isEmpty()) return
            val now = SystemClock.elapsedRealtime()
            lastLux = event.values[0]
            if (now - lastLuxLogMs > LUX_LOG_MS) {
                lastLuxLogMs = now
                Log.i(Backlight.LOG_TAG, "light sensor lux=$lastLux state=${ambientPolicy.state}")
            }
            applyAmbient(ambientPolicy.onLux(now, lastLux), "lux=$lastLux")
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    /** Publishes the policy state (only a change does anything) and arms the dwell timer. */
    private fun applyAmbient(state: AmbientState, reason: String) {
        commands.setAmbientState(state, reason)
        mainHandler.removeCallbacks(lightTicker)
        val pending = ambientPolicy.pendingAtMs() ?: return
        mainHandler.postDelayed(lightTicker, (pending - SystemClock.elapsedRealtime() + 50).coerceAtLeast(250L))
    }

    /**
     * Reads the light sensor only while it is needed: the feature is on, dimming is on and not
     * paused, and the screen is on. Anything else drops the reading; ending the session (or
     * switching the feature off) also resets the state to NORMAL.
     */
    private fun updateAmbient(state: DimmerState, screenOn: Boolean) {
        val session = state.ambientEnabled && state.visible
        if (!session) {
            if (lightRegistered) setLightSensor(false)
            if (ambientPolicy.state != AmbientState.NORMAL || state.ambientState != AmbientState.NORMAL) {
                ambientPolicy.reset()
                commands.setAmbientState(AmbientState.NORMAL, "room light session ended")
            }
            return
        }
        setLightSensor(screenOn)
    }

    private fun setLightSensor(wanted: Boolean) {
        if (wanted == lightRegistered) return
        val sm = getSystemService(SensorManager::class.java)
        if (wanted) {
            val light = sm?.getDefaultSensor(Sensor.TYPE_LIGHT)
            if (sm == null || light == null) {
                Log.i(Backlight.LOG_TAG, "room light: this phone has no light sensor")
                return
            }
            ambientPolicy.restartReading()
            lightRegistered = sm.registerListener(lightListener, light, SensorManager.SENSOR_DELAY_NORMAL, mainHandler)
            Log.i(Backlight.LOG_TAG, "light listener on=$lightRegistered state=${ambientPolicy.state}")
        } else {
            sm?.unregisterListener(lightListener)
            lightRegistered = false
            mainHandler.removeCallbacks(lightTicker)
            ambientPolicy.clearReading()
            Log.i(Backlight.LOG_TAG, "light listener off")
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) resizeOverlay("display changed")
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        // The system can call this more than once per instance (unlike Service.onCreate,
        // which is one-shot) - only set up once.
        if (serviceScope != null) return

        // Access is back: the "Dimmer stopped" notice has done its job.
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(STOPPED_NOTIFICATION_ID)
        overlayController = OverlayController(this)
        repository = (application as DimmerApplication).repository
        commands = (application as DimmerApplication).commands
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        serviceScope = scope
        (application as DimmerApplication).surfaces.refresh() // widget: power button now toggles

        userPresent.value = !keyguardLocked()
        screenOn.value = isScreenOn()
        scope.launch {
            combine(repository.state, userPresent, screenOn, fadeTick) { state, present, on, _ -> Triple(state, present, on) }
                .collect { (state, present, on) -> render(state, present, on) }
        }
        // The notification follows state + clock (fade end, pause, window end); equal plans are
        // not re-posted, so a notification the user swiped away returns only on a real change.
        scope.launch {
            combine(repository.state.filter { it.loaded }, fadeTick) { state, _ -> commands.notificationPlan(state) }
                .distinctUntilChanged()
                // Removal is never delayed; a slider drag is coalesced.
                .debounce { if (it == NotificationPlan.None) 0L else NOTIFICATION_DEBOUNCE_MS }
                .collect { plan -> showNotification(plan) }
        }
        // Evaluate the schedule once settings are loaded (the first evaluation of a process is
        // the "restart" one, so it must see the real settings) and after every settings edit.
        scope.launch {
            repository.state
                .filter { it.loaded }
                .map { it.schedule }
                .distinctUntilChanged()
                .collect { evaluateAndTick("settings") }
        }

        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            },
            // Exported: USER_PRESENT was not delivered to a NOT_EXPORTED receiver on the API 36
            // emulator. Safe - the receiver only triggers a KeyguardManager re-check.
            ContextCompat.RECEIVER_EXPORTED,
        )
        screenReceiverRegistered = true
        ContextCompat.registerReceiver(
            this,
            timeReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_TIME_TICK)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        timeReceiverRegistered = true
        getSystemService(DisplayManager::class.java)
            ?.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
    }

    private fun render(state: DimmerState, userPresent: Boolean, screenOn: Boolean) {
        mainHandler.removeCallbacks(fadeTicker)
        updateAmbient(state, screenOn)
        val nowMs = commands.schedule.now().toEpochMilli()
        if (!state.visible) {
            updateShake(false, state.shakeSensitivity)
            val pause = state.pause
            if (state.paused && pause != null) {
                stopDimming("paused")
                // One timer for the deadline while the screen is on; TIME_TICK, SCREEN_ON and
                // TIME_CHANGED cover deep sleep and clock jumps (L13 review P4 #15).
                if (screenOn) mainHandler.postDelayed(fadeTicker, (pause.untilMs - nowMs + 50).coerceAtLeast(250L))
            } else {
                stopDimming("off")
            }
            return
        }
        if (state.ambientSuspended) {
            // Bright room (P6): overlay and backlight override off, dimming stays on; the sensor
            // keeps running (updateAmbient) and brings the overlay back below 80 lux.
            updateShake(false, state.shakeSensitivity)
            stopDimming("room light bright")
            return
        }
        // Schedule fade (P3): darkness and tint strength scaled by the fade fraction, at render
        // time only. While it runs and the screen is on, re-render every FADE_TICK_MS.
        val (fadedDarkness, fadedStrength, fade) = commands.renderedLook(state)
        // Dark room (P6): the dark-room preset replaces the chosen look while the room stays dark.
        val darkRoom = AmbientLook.override(state.ambientEnabled, state.ambientState, state.presets, state.darkRoomPresetId)
        val darkness = darkRoom?.darkness ?: fadedDarkness
        val tint = darkRoom?.tint ?: state.tint
        val tintStrength = darkRoom?.tintStrength ?: fadedStrength
        val fading = fade < 1f
        if (fading && isScreenOn()) mainHandler.postDelayed(fadeTicker, FADE_TICK_MS)
        val chosen = if (state.hybridEnabled) state.backlightMethod else BacklightMethod.NONE
        val method = BacklightMethods.resolve(
            chosen,
            windowPossible = true, // this service always owns the overlay window while dimming
            canWriteSettings = Settings.System.canWrite(this),
        )
        val starting = !overlayController.isShowing()
        if (starting) backlightEngaged = false
        if (!backlightEngaged) sessionStartBacklight = readSystemBacklight()
        val curve = HybridCurve.compute(darkness, method != BacklightMethod.NONE, sessionStartBacklight)
        // While the backlight part is active (hybrid), darkness 1..knee draws no overlay at all;
        // give a coloured tint its own small minimum alpha there so it stays visible (P2).
        val tintFloor = if (curve.backlight != null) {
            DimmerMath.tintOnlyAlpha(darkness, tint, tintStrength, HybridCurve.DEFAULT_KNEE)
        } else {
            0f
        }
        val color = DimmerMath.overlayColor(
            curve.overlayLevel, tint, tintStrength, repository.maxLevel, tintFloor,
        )
        // Keep-awake only for sessions the user started: a schedule starting at 21:00 must not
        // hold an unlocked phone on a desk awake all night (D-V3-28).
        val keepAwake = state.keepAwake && userPresent && state.source == DimSource.MANUAL

        if (starting) {
            val windowValue =
                if (method == BacklightMethod.WINDOW) WindowBacklight.toWindowValue(curve.backlight)
                else WindowBacklight.NO_OVERRIDE
            if (!overlayController.show(color, windowValue, keepAwake)) {
                // Accessibility access was revoked (or the token died) after we connected.
                if (commands.stopBySystem("overlay could not be shown")) postStoppedNotice()
                return
            }
            Log.i(
                Backlight.LOG_TAG,
                "dimming on: darkness=$darkness/${state.darkness} tint=$tint/$tintStrength room=${state.ambientState} source=${state.source} fade=${"%.3f".format(fade)} preset=${state.activePresetId} method=$method (chosen=$chosen) " +
                    "start=${"%.3f".format(sessionStartBacklight)} mode=${brightnessModeName()} backlight=${curve.backlight} " +
                    "overlay=${curve.overlayLevel} tintFloor=${"%.3f".format(tintFloor)} argb=${"%08x".format(color)} keepAwake=$keepAwake",
            )
        } else {
            overlayController.updateColor(color)
            val minute = System.currentTimeMillis() / 60_000
            if (fading) {
                // One line per minute during a fade instead of one per 5 s colour step.
                if (minute != lastFadeLogMinute) {
                    lastFadeLogMinute = minute
                    Log.i(Backlight.LOG_TAG, "fade ${"%.3f".format(fade)}: darkness=$darkness/${state.darkness} strength=$tintStrength/${state.tintStrength} argb=${"%08x".format(color)}")
                }
            } else if (color != lastLoggedColor) {
                Log.i(
                    Backlight.LOG_TAG,
                    "overlay colour: darkness=$darkness tint=$tint/$tintStrength room=${state.ambientState} " +
                        "preset=${state.activePresetId} overlayLevel=${curve.overlayLevel} " +
                        "tintFloor=${"%.3f".format(tintFloor)} argb=${"%08x".format(color)}",
                )
            }
            if (overlayController.setKeepAwake(keepAwake)) {
                Log.i(Backlight.LOG_TAG, "keep-awake flag -> $keepAwake (setting=${state.keepAwake} userPresent=$userPresent)")
            }
        }

        lastLoggedColor = color
        if (backlight.method != method) {
            backlight.release()
            backlight = createBacklight(method)
            if (!starting) Log.i(Backlight.LOG_TAG, "backlight method -> $method (chosen=$chosen)")
        }
        backlight.apply(curve.backlight)
        if (curve.backlight != null && !backlightEngaged) {
            backlightEngaged = true
            if (!starting) Log.i(Backlight.LOG_TAG, "backlight engaged: start=${"%.3f".format(sessionStartBacklight)} (mode=${brightnessModeName()}) backlight=${curve.backlight}")
        }
        replayStaleJournal()
        updateShake(state.shakeEnabled && screenOn, state.shakeSensitivity)
    }

    /**
     * A system-brightness journal left pending because WRITE_SETTINGS was revoked mid-session
     * is replayed as soon as it can be (e.g. the user re-grants while dimming is off), not
     * only at the next process start. Never while SettingsBacklight owns the journal.
     */
    private fun replayStaleJournal() {
        if (backlight !is SettingsBacklight) BacklightJournal.restoreIfPending(this)
    }

    private fun createBacklight(method: BacklightMethod): Backlight = when (method) {
        BacklightMethod.WINDOW -> WindowBacklight(overlayController::setBrightness)
        BacklightMethod.SYSTEM -> SettingsBacklight(this)
        BacklightMethod.AUTO, BacklightMethod.NONE -> NoBacklight
    }

    /** Ends a dimming session: overlay (and with it any window override) gone, Settings restored. */
    private fun stopDimming(reason: String) {
        val wasOn = overlayController.isShowing() || backlight !== NoBacklight
        overlayController.hide()
        backlight.release()
        backlight = NoBacklight
        backlightEngaged = false
        replayStaleJournal()
        if (wasOn) Log.i(Backlight.LOG_TAG, "dimming off ($reason)")
    }

    /**
     * Start value for the hybrid curve; reading needs no permission. NaN when the setting is
     * missing: the curve then skips the backlight part for darkness 1..20 instead of guessing
     * (the old default of 255 could start brighter than the panel). In automatic mode AOSP's
     * DisplayPowerController writes the current automatic level into this setting so the slider
     * shows it; an OEM that keeps the manual value here would still start from that value
     * (real-phone check, L11 row 2). Only darkness 1..20 is affected.
     */
    private fun readSystemBacklight(): Float =
        HybridCurve.fromSettingsInt(
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, -1),
        )

    private fun brightnessModeName(): String =
        when (Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, -1)) {
            Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC -> "auto"
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL -> "manual"
            else -> "unknown"
        }

    /** Keep-awake never holds the lock screen on (e.g. a pocket wake), only an unlocked phone. */
    private fun keyguardLocked(): Boolean =
        getSystemService(KeyguardManager::class.java)?.isKeyguardLocked ?: false

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::overlayController.isInitialized) resizeOverlay("configuration changed")
    }

    private fun resizeOverlay(reason: String) {
        if (!::overlayController.isInitialized) return
        val size = overlayController.updateBounds() ?: return
        Log.i(Backlight.LOG_TAG, "overlay resized to ${size.first}x${size.second} ($reason)")
    }

    private fun unregisterEventSources() {
        updateShake(false, shakeDetector.sensitivity)
        if (lightRegistered) setLightSensor(false)
        ambientPolicy.reset()
        mainHandler.removeCallbacks(recheckKeyguard)
        mainHandler.removeCallbacks(fadeTicker)
        if (timeReceiverRegistered) {
            unregisterReceiver(timeReceiver)
            timeReceiverRegistered = false
        }
        if (screenReceiverRegistered) {
            unregisterReceiver(screenReceiver)
            screenReceiverRegistered = false
        }
        getSystemService(DisplayManager::class.java)?.unregisterDisplayListener(displayListener)
    }

    /**
     * Fires when the user disables the service in Settings > Accessibility - the direct
     * analogue of v1's AppOpsManager.OnOpChangedListener for a revoked overlay
     * permission. (onInterrupt() is for interrupting spoken feedback, not a disconnect
     * signal - not the right hook here.)
     */
    override fun onUnbind(intent: Intent?): Boolean {
        val wasOn = ::commands.isInitialized && commands.stopBySystem("accessibility unbound")
        if (::overlayController.isInitialized) {
            stopDimming("accessibility unbound")
        }
        cancelNotificationNow()
        unregisterEventSources()
        // Dimming was on and the user did not turn it off: the screen just got bright, say why (SPEC 7).
        if (wasOn) postStoppedNotice()
        (application as DimmerApplication).surfaces.refresh() // widget: power button opens the app
        serviceScope?.cancel()
        serviceScope = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        serviceScope?.cancel()
        if (::overlayController.isInitialized) {
            stopDimming("service destroyed")
        }
        cancelNotificationNow()
        unregisterEventSources()
        super.onDestroy()
    }

    // Required abstract overrides. The config subscribes to no event types (v3.1, REVIEW_V3
    // finding 3), so nothing arrives here; we never interrupt spoken feedback either - this
    // service exists only to host the overlay window.
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    private fun actionIntent(action: NotificationAction): PendingIntent = PendingIntent.getBroadcast(
        this,
        action.ordinal + 1,
        Intent(this, DimmerActionReceiver::class.java).setAction(DimmerActionReceiver.intentAction(action)),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun actionLabel(action: NotificationAction): String = getString(
        when (action) {
            NotificationAction.LIGHTER -> R.string.notification_action_lighter
            NotificationAction.DARKER -> R.string.notification_action_darker
            NotificationAction.PAUSE -> R.string.notification_action_pause
            NotificationAction.RESUME -> R.string.notification_action_resume
            NotificationAction.OFF -> R.string.notification_action_off
            NotificationAction.TURN_ON -> R.string.notification_action_turn_on
        },
    )

    /** Synchronous, unconditional: the plan flow dies with the scope (L13 review P4 #1). */
    private fun cancelNotificationNow() {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFICATION_ID)
        postedPlan = NotificationPlan.None
    }

    private fun buildNotification(plan: NotificationPlan): Notification {
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val (title, text) = when (plan) {
            is NotificationPlan.On -> getString(
                R.string.notification_title_on,
                plan.presetName ?: getString(R.string.label_custom),
                plan.darkness,
            ) to statusText(this, plan.status)
            is NotificationPlan.Paused ->
                statusText(this, Resolver.Status.Paused(plan.until)) to getString(R.string.notification_paused_text)
            is NotificationPlan.ScheduleOff ->
                statusText(this, Resolver.Status.ResumesAt(plan.resumesAt)) to getString(R.string.notification_schedule_off_text)
            NotificationPlan.None -> getString(R.string.app_name) to null
        }
        // Schedule-off info has its own minimal channel, so it can be silenced without touching
        // the channel that carries Pause / Off (L13 review P4 #10).
        val channel = if (plan is NotificationPlan.ScheduleOff) SCHEDULE_CHANNEL_ID else CHANNEL_ID
        val builder = NotificationCompat.Builder(this, channel)
            .setSmallIcon(R.drawable.ic_tile_dimmer)
            // Nothing private in it, and the actions must work on the lock screen too.
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(plan.ongoing)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentPendingIntent)
        for (action in plan.actions) builder.addAction(0, actionLabel(action), actionIntent(action))
        if (plan is NotificationPlan.ScheduleOff) {
            // Gone by itself at the end of the window, even if this process is not alive then.
            val left = plan.windowEnd.toEpochMilli() - System.currentTimeMillis()
            if (left > 0) builder.setTimeoutAfter(left)
        }
        return builder.build()
    }

    /**
     * "Dimmer stopped" (SPEC 7): the screen went back to normal because the service was unbound
     * or the overlay could not be shown while dimming was on. Tap = the fix screen (the
     * disclosure, then Android's Accessibility page). Removed when access is back.
     */
    private fun postStoppedNotice() {
        val tap = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_FIX_ACCESS, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notice = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile_dimmer)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentTitle(getString(R.string.stopped_title))
            .setContentText(getString(R.string.stopped_text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(getString(R.string.stopped_text)))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(STOPPED_NOTIFICATION_ID, notice)
        Log.i(Backlight.LOG_TAG, "notification -> Dimmer stopped")
    }

    private fun showNotification(plan: NotificationPlan) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (plan == NotificationPlan.None) {
            if (postedPlan != NotificationPlan.None) manager.cancel(NOTIFICATION_ID)
        } else {
            manager.notify(NOTIFICATION_ID, buildNotification(plan))
        }
        if (plan::class != postedPlan::class) Log.i(Backlight.LOG_TAG, "notification -> ${plan::class.simpleName} ${plan.actions}")
        postedPlan = plan
    }

    companion object {
        /** v3 P4 channel: low importance, silent, no badge (SPEC 5). */
        const val CHANNEL_ID = "dimmer_status"

        /** v3 P4: "Off · schedule resumes at …" (minimal importance). */
        const val SCHEDULE_CHANNEL_ID = "dimmer_schedule"

        /** v1-v3 P3 channel, deleted at start (its badge setting cannot be changed). */
        const val OLD_CHANNEL_ID = "dimmer_channel"
        const val NOTIFICATION_ID = 1

        /** v3 P6: "Dimmer stopped" (default importance, no sound: the screen just went bright). */
        const val ALERT_CHANNEL_ID = "dimmer_alerts"
        const val STOPPED_NOTIFICATION_ID = 2
        private const val LUX_LOG_MS = 2_000L
        private const val NOTIFICATION_DEBOUNCE_MS = 300L
        private const val KEYGUARD_RECHECK_MS = 1_000L
        private const val FADE_TICK_MS = 5_000L

        /** Whether the user currently has this accessibility service turned on. */
        fun isEnabled(context: Context): Boolean {
            val accessibilityManager =
                context.getSystemService(AccessibilityManager::class.java) ?: return false
            val ourComponent = ComponentName(context, DimmerAccessibilityService::class.java)
            return accessibilityManager
                .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { info ->
                    val serviceInfo = info.resolveInfo?.serviceInfo ?: return@any false
                    serviceInfo.packageName == ourComponent.packageName &&
                        serviceInfo.name == ourComponent.className
                }
        }
    }
}
