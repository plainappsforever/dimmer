package com.plainapps.dimmer

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.plainapps.dimmer.engine.Pause
import com.plainapps.dimmer.engine.Preset
import com.plainapps.dimmer.engine.Presets
import com.plainapps.dimmer.engine.Tint
import kotlin.math.roundToInt

/** Home screen: on/off, darkness, preset chips, tint. Everything else is in [SettingsScreen]. */
@Composable
fun DimmerScreen(
    state: DimmerState,
    commands: DimmerCommands,
    maxLevel: Int,
    accessibilityEnabled: Boolean,
    statusLine: String?,
    roomLine: String?,
    paused: Boolean,
    showNotificationHiddenHint: Boolean,
    showAddTileButton: Boolean,
    onToggle: (Boolean) -> Unit,
    onApplyPreset: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onRequestAccessibilityAccess: () -> Unit,
    onAddTile: () -> Unit,
    onPinPreset: ((Preset) -> Unit)?,
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
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.label_dimmer),
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.label_settings)) }
                Switch(
                    checked = state.enabled,
                    onCheckedChange = onToggle,
                    enabled = accessibilityEnabled,
                )
            }

            if (statusLine != null) {
                Text(
                    text = statusLine,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (roomLine != null) {
                Text(
                    text = roomLine,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (state.enabled) {
                Spacer(Modifier.height(8.dp))
                PauseRow(paused, onPause = { commands.pause(it) }, onResume = { commands.resume() })
            }

            if (!accessibilityEnabled) {
                Spacer(Modifier.height(16.dp))
                AccessibilityPermissionCard(onRequestAccessibilityAccess)
            }

            Spacer(Modifier.height(24.dp))

            Text(text = "${state.darkness}%", style = MaterialTheme.typography.displayMedium)
            Text(
                text = state.activePreset?.let { presetLabel(it) } ?: stringResource(R.string.label_custom),
                style = MaterialTheme.typography.titleMedium,
            )

            Slider(
                value = state.darkness.toFloat(),
                onValueChange = { commands.setDarkness(it.roundToInt()) },
                valueRange = 0f..maxLevel.toFloat(),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(16.dp))
            PresetSection(state, commands, onApplyPreset, onPinPreset)

            Spacer(Modifier.height(24.dp))

            Text(
                text = stringResource(R.string.label_tint),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (tint in Tint.entries) {
                    Chip(
                        label = stringResource(tintLabel(tint)),
                        selected = state.tint == tint,
                        swatch = swatchColor(tint),
                        onClick = { commands.setTint(tint) },
                    )
                }
            }
            Text(
                text = stringResource(R.string.label_tint_strength) +
                    if (state.tint.isColored) "  ${state.tintStrength}%" else "",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            )
            Slider(
                value = state.tintStrength.toFloat(),
                onValueChange = { commands.setTintStrength(it.roundToInt()) },
                valueRange = 0f..100f,
                enabled = state.tint.isColored,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(16.dp))

            if (showAddTileButton) {
                TextButton(onClick = onAddTile) {
                    Text(stringResource(R.string.label_add_tile))
                }
            }

            if (showNotificationHiddenHint) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.notification_hidden_hint),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** v3 P4: "Pause 15 / 30 / 60 min" while dimming is on; "Resume now" while paused. */
@Composable
private fun PauseRow(paused: Boolean, onPause: (Int) -> Unit, onResume: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (paused) {
            Button(onClick = onResume) { Text(stringResource(R.string.label_resume)) }
        } else {
            Text(stringResource(R.string.label_pause), style = MaterialTheme.typography.bodyLarge)
            for (m in Pause.CHOICES_MINUTES) {
                OutlinedButton(onClick = { onPause(m) }) { Text(stringResource(R.string.pause_minutes, m)) }
            }
        }
    }
}

private sealed interface PresetDialog {
    data object Save : PresetDialog
    data class Rename(val preset: Preset) : PresetDialog
    data class Delete(val preset: Preset) : PresetDialog
}

@Composable
private fun PresetSection(
    state: DimmerState,
    commands: DimmerCommands,
    onApplyPreset: (String) -> Unit,
    onPinPreset: ((Preset) -> Unit)?,
) {
    var dialog by remember { mutableStateOf<PresetDialog?>(null) }

    Text(
        text = stringResource(R.string.label_presets),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (preset in state.presets) {
            PresetChip(
                preset = preset,
                selected = state.activePresetId == preset.id,
                inCycle = preset.id in state.tileCycle,
                onApply = { onApplyPreset(preset.id) },
                onRename = { dialog = PresetDialog.Rename(preset) },
                onUpdate = { commands.updatePresetToCurrent(preset.id) },
                onToggleCycle = { commands.setPresetInTileCycle(preset.id, preset.id !in state.tileCycle) },
                onAddToHome = onPinPreset?.let { pin -> { pin(preset) } },
                onDelete = { dialog = PresetDialog.Delete(preset) },
            )
        }
    }

    if (state.activePresetId == null) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
        ) {
            if (state.presets.size < Presets.MAX) {
                TextButton(onClick = { dialog = PresetDialog.Save }) {
                    Text(stringResource(R.string.label_save_preset))
                }
            }
        }
        if (state.presets.size >= Presets.MAX) {
            Text(stringResource(R.string.presets_full), style = MaterialTheme.typography.bodySmall)
        }
    } else {
        Text(
            text = stringResource(R.string.presets_hint),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
        )
    }

    when (val d = dialog) {
        null -> Unit
        PresetDialog.Save -> NameDialog(
            title = stringResource(R.string.dialog_save_title),
            initial = "",
            onConfirm = { name ->
                commands.saveCurrentAsPreset(name)
                dialog = null
            },
            onDismiss = { dialog = null },
        )
        is PresetDialog.Rename -> NameDialog(
            title = stringResource(R.string.dialog_rename_title),
            initial = presetLabel(d.preset),
            onConfirm = { name ->
                commands.renamePreset(d.preset.id, name)
                dialog = null
            },
            onDismiss = { dialog = null },
        )
        is PresetDialog.Delete -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(stringResource(R.string.dialog_delete_title)) },
            text = { Text(presetLabel(d.preset)) },
            confirmButton = {
                TextButton(onClick = {
                    commands.deletePreset(d.preset.id)
                    dialog = null
                }) { Text(stringResource(R.string.menu_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.dialog_cancel)) }
            },
        )
    }
}

@Composable
private fun NameDialog(title: String, initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(Presets.MAX_NAME_LENGTH) },
                label = { Text(stringResource(R.string.dialog_name_label)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = Presets.cleanName(text).isNotEmpty()) {
                Text(stringResource(R.string.dialog_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
    )
}

@Composable
private fun PresetChip(
    preset: Preset,
    selected: Boolean,
    inCycle: Boolean,
    onApply: () -> Unit,
    onRename: () -> Unit,
    onUpdate: () -> Unit,
    onToggleCycle: () -> Unit,
    onAddToHome: (() -> Unit)?,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        Chip(
            label = presetLabel(preset),
            selected = selected,
            swatch = swatchColor(preset.tint),
            onClick = onApply,
            onLongClick = { menuOpen = true },
            longClickLabel = stringResource(R.string.preset_long_press_label),
        )
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.menu_rename)) },
                onClick = { menuOpen = false; onRename() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.menu_update_to_current)) },
                onClick = { menuOpen = false; onUpdate() },
            )
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(
                            if (inCycle) R.string.menu_remove_from_cycle else R.string.menu_add_to_cycle,
                        ),
                    )
                },
                onClick = { menuOpen = false; onToggleCycle() },
            )
            if (onAddToHome != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.menu_add_to_home)) },
                    onClick = { menuOpen = false; onAddToHome() },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.menu_delete)) },
                onClick = { menuOpen = false; onDelete() },
            )
        }
    }
}

/** A pill-shaped choice with a colour dot; optional long-press. At least 48dp high at normal font size. */
@Composable
private fun Chip(
    label: String,
    selected: Boolean,
    swatch: Color,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    longClickLabel: String? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) scheme.primaryContainer else scheme.surfaceVariant)
            .combinedClickable(
                role = Role.Button,
                onLongClickLabel = longClickLabel,
                onLongClick = onLongClick,
                onClick = onClick,
            )
            .semantics { this.selected = selected }
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(swatch),
        )
        Spacer(Modifier.size(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

private fun tintLabel(tint: Tint): Int = when (tint) {
    Tint.NEUTRAL -> R.string.tint_neutral
    Tint.AMBER -> R.string.tint_amber
    Tint.CANDLE -> R.string.tint_candle
    Tint.RED -> R.string.tint_red
}

/** Bright swatch colours for the chips (the real tints are deliberately dark). */
private fun swatchColor(tint: Tint): Color = when (tint) {
    Tint.NEUTRAL -> Color(0xFF9E9E9E)
    Tint.AMBER -> Color(0xFFFFB74D)
    Tint.CANDLE -> Color(0xFFFF8A50)
    Tint.RED -> Color(0xFFD32F2F)
}

@Composable
private fun AccessibilityPermissionCard(onEnable: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.overlay_permission_explainer),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onEnable) {
                Text(stringResource(R.string.label_allow))
            }
        }
    }
}
