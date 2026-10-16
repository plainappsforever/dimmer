package com.plainapps.dimmer

import android.Manifest
import android.app.StatusBarManager
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.plainapps.dimmer.backlight.BacklightMethod
import com.plainapps.dimmer.backlight.Backlight
import com.plainapps.dimmer.backlight.BacklightMethods
import com.plainapps.dimmer.engine.AmbientLook
import com.plainapps.dimmer.engine.AmbientState
import com.plainapps.dimmer.engine.DiagFix
import com.plainapps.dimmer.engine.Diagnostics
import com.plainapps.dimmer.engine.InstallSource
import com.plainapps.dimmer.engine.Preset
import com.plainapps.dimmer.engine.PrivacyLink
import com.plainapps.dimmer.ui.theme.DimmerTheme
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private lateinit var repository: DimmerRepository
    private lateinit var commands: DimmerCommands

    // Must NOT call DimmerAccessibilityService.isEnabled(this)/checkSelfPermission(this)
    // here: field initializers run in the constructor, before attachBaseContext(), so
    // `this` is not yet a usable Context (crashes with NPE - see docs/DECISIONS.md).
    // onResume's first call fixes these before the user can interact; a starting value
    // of false/true is only ever visible for a single composition frame.
    private var accessibilityEnabled by mutableStateOf(false)
    private var notificationPermissionGranted by mutableStateOf(true)
    private var writeSettingsGranted by mutableStateOf(true)

    /** Bumped when a "Dimmer stopped" notification tap (or a new intent) asks for the fix screen (P6). */
    private var fixRequest by mutableIntStateOf(0)

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            notificationPermissionGranted = granted
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = (application as DimmerApplication).repository
        commands = (application as DimmerApplication).commands
        if (savedInstanceState == null && intent?.getBooleanExtra(EXTRA_FIX_ACCESS, false) == true) fixRequest = 1
        val hasLightSensor = getSystemService(SensorManager::class.java)?.getDefaultSensor(Sensor.TYPE_LIGHT) != null
        val showRestrictedHint = InstallSource.showRestrictedSettingsHint(Build.VERSION.SDK_INT, installerPackage())

        setContent {
            DimmerTheme {
                val state by repository.state.collectAsStateWithLifecycle()
                var settingsOpen by rememberSaveable { mutableStateOf(false) }
                // The prominent disclosure (P6) stands between every "set up access" button and Android's page.
                var disclosureOpen by rememberSaveable { mutableStateOf(false) }
                LaunchedEffect(fixRequest) { if (fixRequest > 0) disclosureOpen = true }
                LaunchedEffect(accessibilityEnabled) { if (accessibilityEnabled) disclosureOpen = false }
                val showDisclosure = disclosureOpen && !accessibilityEnabled
                BackHandler(enabled = settingsOpen) { settingsOpen = false }
                BackHandler(enabled = showDisclosure) { disclosureOpen = false }
                if (showDisclosure) {
                    AccessDisclosureScreen(
                        showRestrictedHint = showRestrictedHint,
                        onAgree = {
                            disclosureOpen = false
                            openAccessibilitySettings()
                        },
                        onNotNow = { disclosureOpen = false },
                    )
                } else if (settingsOpen) {
                    val status = remember(state) { commands.scheduleStatus(state) }
                    SettingsScreen(
                        state = state,
                        commands = commands,
                        showWriteSettingsHint = state.hybridEnabled &&
                            !writeSettingsGranted && needsWriteSettings(state.backlightMethod),
                        onBack = { settingsOpen = false },
                        onHybridChange = ::onHybridChange,
                        onBacklightMethodChange = ::onBacklightMethodChange,
                        onRequestWriteSettings = ::requestWriteSettings,
                        onAddWidget = if (canPinWidget()) ::requestPinWidget else null,
                        onAddPresetTile = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            { requestAddTile(DimmerPresetTileService::class.java, R.string.tile_presets_label, R.drawable.ic_tile_presets) }
                        } else {
                            null
                        },
                        hasLightSensor = hasLightSensor,
                        diagnostics = Diagnostics.rows(
                            accessibilityOn = accessibilityEnabled,
                            hybridEnabled = state.hybridEnabled,
                            chosenMethod = state.backlightMethod,
                            canWriteSettings = writeSettingsGranted,
                            notificationsAllowed = notificationPermissionGranted,
                        ),
                        scheduleLine = if (state.schedule.enabled) statusText(this@MainActivity, status) else null,
                        onOpenPrivacyPolicy = ::openPrivacyPolicy,
                        onFix = { fix ->
                            when (fix) {
                                DiagFix.SET_UP_ACCESSIBILITY -> disclosureOpen = true
                                DiagFix.GRANT_WRITE_SETTINGS -> requestWriteSettings()
                                DiagFix.NOTIFICATION_SETTINGS -> openNotificationSettings()
                            }
                        },
                    )
                } else {
                    // The status line depends on the clock too: refresh it every 15 s.
                    var clock by remember { mutableLongStateOf(System.currentTimeMillis()) }
                    LaunchedEffect(Unit) {
                        while (true) {
                            delay(STATUS_REFRESH_MS)
                            clock = System.currentTimeMillis()
                        }
                    }
                    val status = remember(state, clock) { commands.scheduleStatus(state) }
                    DimmerScreen(
                        state = state,
                        commands = commands,
                        maxLevel = repository.maxLevel,
                        accessibilityEnabled = accessibilityEnabled,
                        statusLine = scheduleStatusText(status),
                        roomLine = roomLine(state),
                        paused = status is com.plainapps.dimmer.engine.Resolver.Status.Paused,
                        showNotificationHiddenHint = state.enabled && !notificationPermissionGranted,
                        showAddTileButton = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
                        onToggle = ::onToggle,
                        onApplyPreset = ::onApplyPreset,
                        onOpenSettings = { settingsOpen = true },
                        onRequestAccessibilityAccess = { disclosureOpen = true },
                        onAddTile = { requestAddTile(DimmerTileService::class.java, R.string.app_name, R.drawable.ic_tile_dimmer) },
                        onPinPreset = if (Shortcuts.isPinSupported(this@MainActivity)) ::requestPinPreset else null,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_FIX_ACCESS, false)) fixRequest++
    }

    override fun onResume() {
        super.onResume()
        accessibilityEnabled = DimmerAccessibilityService.isEnabled(this)
        // The hint also covers notifications blocked in system settings (L13 review P4 #7).
        notificationPermissionGranted = !needsNotificationPermission() &&
            androidx.core.app.NotificationManagerCompat.from(this).areNotificationsEnabled()
        writeSettingsGranted = Settings.System.canWrite(this)
        // Let the service re-resolve the backlight method (e.g. just back from the grant screen).
        commands.refreshPermissions()
    }

    /**
     * True when [method] can only lower the backlight with WRITE_SETTINGS - SYSTEM, or AUTO
     * when the window override is not the automatic choice (D-V3-16).
     */
    private fun needsWriteSettings(method: BacklightMethod): Boolean =
        method != BacklightMethod.NONE &&
            BacklightMethods.resolve(method, windowPossible = true, canWriteSettings = false) ==
            BacklightMethod.NONE

    // The grant screen opens only when the user switches on a feature that needs it (L10),
    // never from the dimming toggle.
    private fun onHybridChange(on: Boolean) {
        commands.setHybridEnabled(on)
        if (on && !Settings.System.canWrite(this) && needsWriteSettings(repository.state.value.backlightMethod)) {
            requestWriteSettings()
        }
    }

    private fun onBacklightMethodChange(method: BacklightMethod) {
        commands.setBacklightMethod(method)
        if (repository.state.value.hybridEnabled && !Settings.System.canWrite(this) && needsWriteSettings(method)) {
            requestWriteSettings()
        }
    }

    private fun requestWriteSettings() {
        startActivity(
            Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, "package:$packageName".toUri()),
        )
    }

    private fun onToggle(enabled: Boolean) {
        if (!enabled) {
            commands.turnOff()
            return
        }
        turnOnWithNotificationPermission()
    }

    /** Applying a preset turns dimming on if off (SPEC 2); never blocked by the notification permission. */
    private fun onApplyPreset(id: String) {
        commands.applyPreset(id, turnOn = false)
        turnOnWithNotificationPermission()
    }

    private fun turnOnWithNotificationPermission() {
        if (!DimmerAccessibilityService.isEnabled(this)) return
        if (needsNotificationPermission()) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        commands.turnOn()
    }

    private fun needsNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED

    /**
     * Only ever called after the user tapped "Agree and open settings" (P6, SPEC 7). Tries the
     * details page of our own service first (D-V3-40); any refusal falls back to the general
     * Accessibility list, which is what v2 always did.
     */
    private fun openAccessibilitySettings() {
        val component = ComponentName(this, DimmerAccessibilityService::class.java)
        val details = Intent(ACTION_ACCESSIBILITY_DETAILS_SETTINGS)
            .putExtra(Intent.EXTRA_COMPONENT_NAME, component.flattenToString())
        val route = try {
            startActivity(details)
            "details"
        } catch (e: RuntimeException) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            "list (${e.javaClass.simpleName})"
        }
        Log.i(Backlight.LOG_TAG, "opened accessibility settings: $route")
    }

    /**
     * v3.1.1 (Play audit C3): the package that installed Dimmer ("com.android.vending" = Google Play), or null
     * (adb, unknown, or Android 10 and older, where the restricted-settings sentence is never shown anyway).
     */
    private fun installerPackage(): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                packageManager.getInstallSourceInfo(packageName).installingPackageName
            } catch (e: PackageManager.NameNotFoundException) {
                null
            }
        } else {
            null
        }

    /** v3.1.1 (Play audit C2): the policy in the user's browser; false = no app can open it (Settings shows the URL). */
    private fun openPrivacyPolicy(): Boolean = PrivacyLink.open { url ->
        startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE))
    }

    private fun openNotificationSettings() {
        startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
        )
    }

    /** Home status line for the room-light feature (SPEC 8), or null when it has nothing to say. */
    private fun roomLine(state: DimmerState): String? = when {
        state.ambientSuspended -> getString(R.string.status_room_bright)
        state.ambientEnabled && state.visible && state.ambientState == AmbientState.DARK_ROOM ->
            AmbientLook.override(true, state.ambientState, state.presets, state.darkRoomPresetId)
                ?.let { getString(R.string.status_room_dark, presetLabel(it)) }
        else -> null
    }

    companion object {
        /** Set by the "Dimmer stopped" notification: open the disclosure straight away. */
        const val EXTRA_FIX_ACCESS = "com.plainapps.dimmer.extra.FIX_ACCESS"

        /** `Settings.ACTION_ACCESSIBILITY_DETAILS_SETTINGS` is hidden API in compileSdk 36. */
        private const val ACTION_ACCESSIBILITY_DETAILS_SETTINGS = "android.settings.ACCESSIBILITY_DETAILS_SETTINGS"
        private const val STATUS_REFRESH_MS = 15_000L
    }

    private fun requestAddTile(service: Class<*>, label: Int, icon: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val statusBarManager = getSystemService(StatusBarManager::class.java) ?: return
        statusBarManager.requestAddTileService(
            ComponentName(this, service),
            getString(label),
            Icon.createWithResource(this, icon),
            mainExecutor,
        ) { /* result code ignored: nothing actionable to do differently either way */ }
    }

    /** "Add to home screen" in the preset menu (SPEC 2): the launcher shows its own confirmation. */
    private fun requestPinPreset(preset: Preset) {
        Shortcuts.requestPin(this, preset.copy(name = presetLabel(preset)))
    }

    private fun canPinWidget(): Boolean =
        getSystemService(AppWidgetManager::class.java)?.isRequestPinAppWidgetSupported == true

    /** Settings > Controls > "Add widget": the launcher asks where to put it (the system dialog). */
    private fun requestPinWidget() {
        getSystemService(AppWidgetManager::class.java)
            ?.requestPinAppWidget(DimmerWidgetProvider.componentName(this), null, null)
    }
}
