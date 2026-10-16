package com.plainapps.dimmer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.plainapps.dimmer.backlight.BacklightMethod
import com.plainapps.dimmer.engine.DiagFix
import com.plainapps.dimmer.engine.DiagRow
import com.plainapps.dimmer.engine.PrivacyLink
import com.plainapps.dimmer.engine.ShakeSensitivity

/**
 * Settings screen (v3 P2). Holds P1's backlight controls; later phases added Schedule, Safety,
 * Controls, Room light and Status (P6) sections here.
 */
@Composable
fun SettingsScreen(
    state: DimmerState,
    commands: DimmerCommands,
    showWriteSettingsHint: Boolean,
    onBack: () -> Unit,
    onHybridChange: (Boolean) -> Unit,
    onBacklightMethodChange: (BacklightMethod) -> Unit,
    onRequestWriteSettings: () -> Unit,
    onAddWidget: (() -> Unit)?,
    onAddPresetTile: (() -> Unit)?,
    hasLightSensor: Boolean,
    diagnostics: List<DiagRow>,
    scheduleLine: String?,
    onOpenPrivacyPolicy: () -> Boolean,
    onFix: (DiagFix) -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // v3.1: targetSdk 36 draws edge to edge; keep the scrolling list out from
                // under the status bar and the gesture bar (it used to scroll under the clock).
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        ) {
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("‹ " + stringResource(R.string.label_back)) }
            }
            Text(
                text = stringResource(R.string.label_settings),
                style = MaterialTheme.typography.headlineMedium,
            )

            Spacer(Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.label_backlight_section),
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(8.dp))

            SwitchRow(
                label = stringResource(R.string.label_hybrid),
                checked = state.hybridEnabled,
                onCheckedChange = onHybridChange,
            )
            Text(
                text = stringResource(R.string.hybrid_explainer),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth(),
            )
            if (showWriteSettingsHint) {
                Spacer(Modifier.height(8.dp))
                WriteSettingsCard(onRequestWriteSettings)
            }
            Spacer(Modifier.height(8.dp))
            SwitchRow(
                label = stringResource(R.string.label_keep_awake),
                checked = state.keepAwake,
                onCheckedChange = commands::setKeepAwake,
            )

            var advancedOpen by rememberSaveable { mutableStateOf(false) }
            TextButton(onClick = { advancedOpen = !advancedOpen }) {
                Text(stringResource(R.string.label_advanced) + if (advancedOpen) "  ▲" else "  ▼")
            }
            if (advancedOpen) {
                Text(
                    text = stringResource(R.string.label_backlight_method),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.fillMaxWidth(),
                )
                for ((method, labelRes) in METHOD_LABELS) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onBacklightMethodChange(method) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = state.backlightMethod == method,
                            onClick = { onBacklightMethodChange(method) },
                        )
                        Text(stringResource(labelRes), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                // v3.1.1 (Play audit C4): say what the system-brightness method changes on the phone.
                Text(
                    text = stringResource(R.string.method_system_explainer),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(24.dp))
            ScheduleSection(state, commands)
            Spacer(Modifier.height(24.dp))
            SafetySection(state, commands)
            Spacer(Modifier.height(24.dp))
            ControlsSection(state, commands, onAddWidget, onAddPresetTile)
            Spacer(Modifier.height(24.dp))
            RoomLightSection(state, commands, hasLightSensor)
            Spacer(Modifier.height(24.dp))
            StatusSection(diagnostics, scheduleLine, onFix)
            Spacer(Modifier.height(24.dp))
            AboutSection(onOpenPrivacyPolicy)
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * v3.1.1 (Play audit C2): Settings > About > "Privacy policy" opens the policy in the browser (no INTERNET permission
 * needed). Without any browser the address is shown as selectable text instead.
 */
@Composable
private fun AboutSection(onOpenPrivacyPolicy: () -> Boolean) {
    Text(text = stringResource(R.string.label_about_section), style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(8.dp))
    Text(
        text = stringResource(R.string.privacy_summary),
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.fillMaxWidth(),
    )
    var noBrowser by rememberSaveable { mutableStateOf(false) }
    TextButton(
        onClick = { noBrowser = !onOpenPrivacyPolicy() },
        modifier = Modifier.testTag("privacy_policy"),
    ) { Text(stringResource(R.string.label_privacy_policy)) }
    if (noBrowser) {
        Text(stringResource(R.string.privacy_no_browser), style = MaterialTheme.typography.bodySmall)
        SelectionContainer { Text(PrivacyLink.URL, style = MaterialTheme.typography.bodyMedium) }
    }
}

/** v3 P4: shake to turn off (SPEC 4, D-V3-08). */
@Composable
private fun SafetySection(state: DimmerState, commands: DimmerCommands) {
    Text(text = stringResource(R.string.label_safety_section), style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(8.dp))
    SwitchRow(
        label = stringResource(R.string.label_shake),
        checked = state.shakeEnabled,
        onCheckedChange = commands::setShakeEnabled,
    )
    Text(
        text = stringResource(R.string.shake_explainer),
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.fillMaxWidth(),
    )
    if (state.shakeEnabled) {
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.label_shake_sensitivity), style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((s, label) in SHAKE_LABELS) {
                FilterChip(
                    selected = state.shakeSensitivity == s,
                    onClick = { commands.setShakeSensitivity(s) },
                    label = { Text(stringResource(label)) },
                )
            }
        }
    }
}

/** v3 P4/P5: widget and tile buttons, automation opt-in (SPEC 9, D-V3-13). */
@Composable
private fun ControlsSection(
    state: DimmerState,
    commands: DimmerCommands,
    onAddWidget: (() -> Unit)?,
    onAddPresetTile: (() -> Unit)?,
) {
    Text(text = stringResource(R.string.label_controls_section), style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(8.dp))
    Text(
        text = stringResource(R.string.controls_hint),
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.fillMaxWidth(),
    )
    if (onAddWidget != null) {
        TextButton(onClick = onAddWidget) { Text(stringResource(R.string.label_add_widget)) }
    }
    if (onAddPresetTile != null) {
        TextButton(onClick = onAddPresetTile) { Text(stringResource(R.string.label_add_preset_tile)) }
    }
    Spacer(Modifier.height(8.dp))
    SwitchRow(
        label = stringResource(R.string.label_automation),
        checked = state.automationEnabled,
        onCheckedChange = commands::setAutomationEnabled,
    )
    Text(
        text = stringResource(R.string.automation_explainer),
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.fillMaxWidth(),
    )
}

private val SHAKE_LABELS = listOf(
    ShakeSensitivity.LOW to R.string.shake_low,
    ShakeSensitivity.MEDIUM to R.string.shake_medium,
    ShakeSensitivity.HIGH to R.string.shake_high,
)

private val METHOD_LABELS = listOf(
    BacklightMethod.AUTO to R.string.method_auto,
    BacklightMethod.WINDOW to R.string.method_window,
    BacklightMethod.SYSTEM to R.string.method_system,
    BacklightMethod.NONE to R.string.method_none,
)

@Composable
private fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(end = 12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun WriteSettingsCard(onAllow: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.write_settings_needed),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = onAllow) { Text(stringResource(R.string.label_allow_write_settings)) }
        }
    }
}
