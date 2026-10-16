package com.plainapps.dimmer

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.plainapps.dimmer.backlight.BacklightMethod
import com.plainapps.dimmer.engine.DiagFix
import com.plainapps.dimmer.engine.DiagItem
import com.plainapps.dimmer.engine.DiagRow

/**
 * The Play "prominent disclosure" (v3 P6, SPEC 7, D-V3-10): shown in normal app use, before
 * Android's Accessibility settings, separate from any privacy policy, and it needs a tap on
 * "Agree and open settings". "Not now" changes nothing and never opens Settings.
 * [showRestrictedHint]: the "Allow restricted settings" sentence, only for sideloaded installs (v3.1.1).
 */
@Composable
fun AccessDisclosureScreen(showRestrictedHint: Boolean, onAgree: () -> Unit, onNotNow: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // v3.1: targetSdk 36 draws edge to edge; keep the scrolling list out from
                // under the status bar and the gesture bar (it used to scroll under the clock).
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.disclosure_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(stringResource(R.string.disclosure_purpose), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.disclosure_does_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.disclosure_does), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.disclosure_never_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.disclosure_never), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.disclosure_android_note), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.disclosure_steps), style = MaterialTheme.typography.bodySmall)
            // v3.1.1 (Play audit C3): only for installs outside Google Play on Android 13+ (InstallSource).
            if (showRestrictedHint) {
                Text(
                    stringResource(R.string.disclosure_restricted),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("disclosure_restricted"),
                )
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = onAgree, modifier = Modifier.fillMaxWidth().testTag("disclosure_agree")) {
                Text(stringResource(R.string.disclosure_agree))
            }
            OutlinedButton(onClick = onNotNow, modifier = Modifier.fillMaxWidth().testTag("disclosure_not_now")) {
                Text(stringResource(R.string.disclosure_not_now))
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** Settings > Room light (v3 P6, SPEC 8): the switch and the dark-room preset. */
@Composable
fun RoomLightSection(state: DimmerState, commands: DimmerCommands, hasLightSensor: Boolean) {
    Text(text = stringResource(R.string.label_room_section), style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = stringResource(R.string.label_ambient),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f).padding(end = 12.dp),
        )
        Switch(
            checked = state.ambientEnabled,
            onCheckedChange = commands::setAmbientEnabled,
            modifier = Modifier.testTag("ambient_switch"),
        )
    }
    Text(
        text = stringResource(R.string.ambient_explainer),
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.fillMaxWidth(),
    )
    if (!hasLightSensor) {
        Text(
            text = stringResource(R.string.ambient_no_sensor),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
    }
    if (state.ambientEnabled) {
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.label_dark_room_preset), style = MaterialTheme.typography.titleSmall)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (p in state.presets) {
                FilterChip(
                    selected = state.darkRoomPresetId == p.id,
                    onClick = { commands.setDarkRoomPreset(p.id) },
                    label = { Text(presetLabel(p)) },
                )
            }
        }
        if (state.presets.none { it.id == state.darkRoomPresetId }) {
            Text(stringResource(R.string.ambient_dark_room_missing), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Settings > Status (v3 P6, SPEC 7): what works, what does not, one button per problem. */
@Composable
fun StatusSection(rows: List<DiagRow>, scheduleLine: String?, onFix: (DiagFix) -> Unit) {
    Text(text = stringResource(R.string.label_status_section), style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(8.dp))
    Card(modifier = Modifier.fillMaxWidth().testTag("status_card")) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (row in rows) {
                val text = when (row.item) {
                    DiagItem.ACCESSIBILITY -> stringResource(if (row.ok) R.string.diag_accessibility_on else R.string.diag_accessibility_off)
                    DiagItem.BACKLIGHT -> stringResource(
                        when (row.method) {
                            BacklightMethod.WINDOW -> R.string.diag_backlight_window
                            BacklightMethod.SYSTEM -> R.string.diag_backlight_system
                            else -> if (row.ok) R.string.diag_backlight_none_hybrid_off else R.string.diag_backlight_none
                        },
                    )
                    DiagItem.WRITE_SETTINGS -> stringResource(
                        when {
                            row.granted -> R.string.diag_write_ok
                            row.fix != null -> R.string.diag_write_off
                            else -> R.string.diag_write_not_needed
                        },
                    )
                    DiagItem.NOTIFICATIONS -> stringResource(if (row.ok) R.string.diag_notifications_ok else R.string.diag_notifications_off)
                    DiagItem.SCHEDULE ->
                        if (scheduleLine == null) stringResource(R.string.diag_schedule_off)
                        else stringResource(R.string.diag_schedule_next, scheduleLine)
                }
                Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("diag_${row.item.name.lowercase()}"))
                val fix = row.fix
                if (fix != null) {
                    TextButton(onClick = { onFix(fix) }) {
                        Text(
                            stringResource(
                                when (fix) {
                                    DiagFix.SET_UP_ACCESSIBILITY -> R.string.diag_fix_access
                                    DiagFix.GRANT_WRITE_SETTINGS -> R.string.diag_fix_write
                                    DiagFix.NOTIFICATION_SETTINGS -> R.string.diag_fix_notifications
                                },
                            ),
                        )
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    Text(stringResource(R.string.diag_recovery_title), style = MaterialTheme.typography.titleSmall)
    Text(stringResource(R.string.diag_recovery), style = MaterialTheme.typography.bodySmall)
}
