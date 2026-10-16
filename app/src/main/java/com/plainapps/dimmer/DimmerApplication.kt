package com.plainapps.dimmer

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.pm.PackageManager
import android.util.Log
import com.plainapps.dimmer.backlight.Backlight
import com.plainapps.dimmer.backlight.BacklightJournal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class DimmerApplication : Application() {

    lateinit var repository: DimmerRepository
        private set

    /** The only way to change dimmer state (UI, tile, notification, later widget/automation). */
    lateinit var commands: DimmerCommands
        private set

    /** v3 P5: widget + pinned shortcuts follow the state. */
    lateinit var surfaces: DimmerSurfaces
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** v3.1.1: after a language change, redraw the widget and relabel pinned shortcuts (translated preset names). */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::surfaces.isInitialized) surfaces.refresh()
    }

    override fun onCreate() {
        super.onCreate()
        // Every process start (incl. the first launch after a force-stop, when no broadcast
        // can reach us): put back a brightness the system-settings method left behind.
        // No session can be active yet in a fresh process, so this never races one.
        BacklightJournal.restoreIfPending(this)
        repository = DimmerRepository(applicationContext, DimmerMath.DEFAULT_MAX_LEVEL)
        commands = DimmerCommands(
            repository,
            { DimmerAccessibilityService.isEnabled(applicationContext) },
            presetLabel = { applicationContext.presetLabel(it) },
        )
        surfaces = DimmerSurfaces(applicationContext, repository).also { it.start() }
        createNotificationChannel()
        mirrorAutomationSwitch()
    }

    /**
     * For broadcast receivers (notification buttons, automation): a broadcast can cold-start the
     * process, and a command applied before DataStore is loaded would be overwritten by the
     * stored settings. Waits up to [LOAD_WAIT_MS] for the load, then runs [block] on the main
     * thread; never throws (L13 review P4 #16, #17).
     */
    fun runWhenLoaded(receiver: BroadcastReceiver, block: () -> Unit) {
        val pending = receiver.goAsync()
        appScope.launch(Dispatchers.Main) {
            try {
                withTimeoutOrNull(LOAD_WAIT_MS) { repository.state.first { it.loaded } }
                block()
            } catch (e: RuntimeException) {
                Log.w(Backlight.LOG_TAG, "receiver command failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val LOAD_WAIT_MS = 2_000L
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            DimmerAccessibilityService.CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
        manager.createNotificationChannel(
            NotificationChannel(
                DimmerAccessibilityService.SCHEDULE_CHANNEL_ID,
                getString(R.string.notification_schedule_channel_name),
                NotificationManager.IMPORTANCE_MIN,
            ).apply { setShowBadge(false) },
        )
        // v3 P6: "Dimmer stopped" needs to be seen, but the screen just went bright: no sound, no badge.
        manager.createNotificationChannel(
            NotificationChannel(
                DimmerAccessibilityService.ALERT_CHANNEL_ID,
                getString(R.string.notification_alert_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            },
        )
        // `enabled` is memory-only, so any Dimmer notification that exists when a process starts
        // is stale (crash, update): remove it; the service re-posts the right one.
        manager.cancel(DimmerAccessibilityService.NOTIFICATION_ID)
        // The old channel had a badge, which cannot be changed after creation (SPEC 5: no badge).
        manager.deleteNotificationChannel(DimmerAccessibilityService.OLD_CHANNEL_ID)
    }

    /**
     * D-V3-13: the exported AutomationReceiver is reachable only while the user has "Allow other
     * apps to control Dimmer" on. The saved setting is mirrored onto the component state (after
     * DataStore is loaded, so a fresh process never briefly disables a component the user enabled).
     */
    private fun mirrorAutomationSwitch() {
        val component = ComponentName(this, AutomationReceiver::class.java)
        appScope.launch {
            repository.state
                .filter { it.loaded }
                .map { it.automationEnabled }
                .distinctUntilChanged()
                .collect { on ->
                    val wanted = if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    if (packageManager.getComponentEnabledSetting(component) != wanted) {
                        packageManager.setComponentEnabledSetting(component, wanted, PackageManager.DONT_KILL_APP)
                        Log.i(Backlight.LOG_TAG, "automation receiver enabled=$on")
                    }
                }
        }
    }
}
