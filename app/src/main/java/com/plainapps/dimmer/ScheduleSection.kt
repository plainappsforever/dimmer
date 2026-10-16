package com.plainapps.dimmer

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.plainapps.dimmer.engine.EndKind
import com.plainapps.dimmer.engine.Resolver
import com.plainapps.dimmer.engine.ScheduleMath
import com.plainapps.dimmer.engine.StartKind
import com.plainapps.dimmer.engine.SunCalc
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/** Settings › Schedule (v3 P3, SPEC 3). All changes go through [DimmerCommands.updateSchedule]. */
@Composable
fun ScheduleSection(state: DimmerState, commands: DimmerCommands) {
    val cfg = state.schedule
    Text(text = stringResource(R.string.label_schedule_section), style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(8.dp))
    ScheduleSwitchRow(stringResource(R.string.label_schedule_enabled), cfg.enabled) { on ->
        commands.updateSchedule { it.copy(enabled = on) }
    }
    if (!cfg.enabled) return

    var picking by rememberSaveable { mutableStateOf<String?>(null) } // "start" | "end"

    Spacer(Modifier.height(8.dp))
    Text(stringResource(R.string.label_schedule_start), style = MaterialTheme.typography.titleSmall)
    ChipRow {
        FilterChip(
            selected = cfg.startKind == StartKind.CLOCK,
            onClick = { commands.updateSchedule { it.copy(startKind = StartKind.CLOCK) } },
            label = { Text(stringResource(R.string.schedule_kind_time)) },
        )
        FilterChip(
            selected = cfg.startKind == StartKind.SUNSET,
            onClick = { commands.updateSchedule { it.copy(startKind = StartKind.SUNSET) } },
            label = { Text(stringResource(R.string.schedule_kind_sunset)) },
        )
        if (cfg.startKind == StartKind.CLOCK) {
            TextButton(onClick = { picking = "start" }) { Text(formatMinute(LocalContext.current, cfg.startMinute)) }
        }
    }

    Text(stringResource(R.string.label_schedule_end), style = MaterialTheme.typography.titleSmall)
    ChipRow {
        FilterChip(
            selected = cfg.endKind == EndKind.CLOCK,
            onClick = { commands.updateSchedule { it.copy(endKind = EndKind.CLOCK) } },
            label = { Text(stringResource(R.string.schedule_kind_time)) },
        )
        FilterChip(
            selected = cfg.endKind == EndKind.SUNRISE,
            onClick = { commands.updateSchedule { it.copy(endKind = EndKind.SUNRISE) } },
            label = { Text(stringResource(R.string.schedule_kind_sunrise)) },
        )
        if (cfg.endKind == EndKind.CLOCK) {
            TextButton(onClick = { picking = "end" }) { Text(formatMinute(LocalContext.current, cfg.endMinute)) }
        }
    }

    if (cfg.usesSun) {
        Text(
            text = stringResource(R.string.label_sun_offset, signed(cfg.sunOffsetMin)),
            style = MaterialTheme.typography.bodyMedium,
        )
        Slider(
            value = cfg.sunOffsetMin.toFloat(),
            onValueChange = { v ->
                val snapped = (v / 5f).roundToInt() * 5
                commands.updateSchedule { it.copy(sunOffsetMin = snapped) }
            },
            valueRange = ScheduleMath.OFFSET_MIN.toFloat()..ScheduleMath.OFFSET_MAX.toFloat(),
            modifier = Modifier.fillMaxWidth(),
        )
    }

    Text(stringResource(R.string.label_fade_in), style = MaterialTheme.typography.titleSmall)
    ChipRow {
        for (m in ScheduleMath.FADE_CHOICES) {
            FilterChip(
                selected = cfg.fadeMinutes == m,
                onClick = { commands.updateSchedule { it.copy(fadeMinutes = m) } },
                label = { Text(if (m == 0) stringResource(R.string.fade_off) else stringResource(R.string.fade_minutes, m)) },
            )
        }
    }

    Text(stringResource(R.string.label_schedule_preset), style = MaterialTheme.typography.titleSmall)
    ChipRow {
        for (p in state.presets) {
            FilterChip(
                selected = cfg.presetId == p.id,
                onClick = { commands.updateSchedule { it.copy(presetId = p.id) } },
                label = { Text(presetLabel(p)) },
            )
        }
    }
    if (state.presets.none { it.id == cfg.presetId }) {
        Text(stringResource(R.string.schedule_preset_missing), style = MaterialTheme.typography.bodySmall)
    }

    Spacer(Modifier.height(8.dp))
    ScheduleSwitchRow(stringResource(R.string.label_resume_after_restart), cfg.resumeAfterRestart) { on ->
        commands.updateSchedule { it.copy(resumeAfterRestart = on) }
    }
    Text(stringResource(R.string.resume_explainer), style = MaterialTheme.typography.bodySmall)

    Spacer(Modifier.height(12.dp))
    ScheduleInfo(state, commands)

    when (picking) {
        "start" -> TimeDialog(cfg.startMinute, onDismiss = { picking = null }) { m ->
            commands.updateSchedule { it.copy(startMinute = m) }
            picking = null
        }
        "end" -> TimeDialog(cfg.endMinute, onDismiss = { picking = null }) { m ->
            commands.updateSchedule { it.copy(endMinute = m) }
            picking = null
        }
    }
}

/** Today's computed times, the zone used and any estimate / polar note (SPEC 3, D-V3-04). */
@Composable
private fun ScheduleInfo(state: DimmerState, commands: DimmerCommands) {
    val context = LocalContext.current
    val info = remember(state.schedule, state.permissionEpoch) { commands.schedule.info(state.schedule) }
    val w = info.window
    val todayLine = if (w == null) {
        stringResource(R.string.schedule_empty)
    } else {
        stringResource(
            R.string.schedule_today,
            formatInstant(context, w.start, info.zone),
            formatInstant(context, w.end, info.zone),
        )
    }
    Text(todayLine, style = MaterialTheme.typography.bodyMedium)
    if (state.schedule.usesSun) {
        val sunLine = when (info.polar) {
            SunCalc.Polar.DAY -> stringResource(R.string.schedule_polar_day)
            SunCalc.Polar.NIGHT -> stringResource(R.string.schedule_polar_night)
            null -> stringResource(
                R.string.schedule_sun_times,
                info.sunset?.let { formatInstant(context, it, info.zone) } ?: "-",
                info.sunrise?.let { formatInstant(context, it, info.zone) } ?: "-",
            )
        }
        Text(sunLine, style = MaterialTheme.typography.bodySmall)
        val loc = info.location
        Text(
            text = if (loc.estimated) {
                stringResource(R.string.schedule_zone_estimated, info.zone.id)
            } else {
                stringResource(R.string.schedule_zone_used, info.zone.id, "%.1f".format(loc.latitude), "%.1f".format(loc.longitude))
            },
            style = MaterialTheme.typography.bodySmall,
        )
        if (w?.polarFallback == true) {
            Text(stringResource(R.string.schedule_polar_fallback), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** One status line for the home screen, or null when there is nothing to say. */
@Composable
fun scheduleStatusText(status: Resolver.Status): String? = statusText(LocalContext.current, status)

/** Same text outside Compose (the notification, v3 P4). */
fun statusText(context: Context, status: Resolver.Status): String? {
    val zone = ZoneId.systemDefault()
    fun t(i: Instant) = formatTime(context, i, zone)
    return when (status) {
        Resolver.Status.None -> null
        is Resolver.Status.FadingIn -> context.getString(R.string.status_fading_in, t(status.until))
        is Resolver.Status.Until -> context.getString(R.string.status_until, t(status.end))
        is Resolver.Status.ResumesAt -> context.getString(R.string.status_resumes, t(status.next))
        is Resolver.Status.StartsAt -> context.getString(R.string.status_starts, t(status.next))
        is Resolver.Status.Paused -> context.getString(R.string.status_paused, t(status.until))
    }
}

/** "21:30", "21:30 tomorrow" or "21:30 Fri" in the user's 12/24 h format. */
fun formatTime(context: Context, i: Instant, zone: ZoneId = ZoneId.systemDefault()): String =
    formatInstant(context, i, zone) + daySuffix(context, i, zone)

private fun daySuffix(context: Context, i: Instant, zone: ZoneId): String {
    val today = Instant.now().atZone(zone).toLocalDate()
    val d = i.atZone(zone).toLocalDate()
    return when (d) {
        today -> ""
        today.plusDays(1) -> " " + context.getString(R.string.day_tomorrow)
        else -> " " + d.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault())
    }
}

private fun timeFormatter(context: Context): DateTimeFormatter =
    DateTimeFormatter.ofPattern(if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a")

private fun formatInstant(context: Context, i: Instant, zone: ZoneId): String =
    i.atZone(zone).toLocalTime().format(timeFormatter(context))

private fun formatMinute(context: Context, minute: Int): String =
    java.time.LocalTime.of(minute / 60, minute % 60).format(timeFormatter(context))

private fun signed(m: Int) = if (m > 0) "+$m" else "$m"

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

@Composable
private fun ScheduleSwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(end = 12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(minute: Int, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    val context = LocalContext.current
    val pickerState = rememberTimePickerState(
        initialHour = minute / 60,
        initialMinute = minute % 60,
        is24Hour = DateFormat.is24HourFormat(context),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(pickerState.hour * 60 + pickerState.minute) }) {
                Text(stringResource(R.string.dialog_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
        text = { Column { TimePicker(state = pickerState) } },
    )
}
