package com.plainapps.dimmer

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.plainapps.dimmer.engine.Preset
import com.plainapps.dimmer.engine.Presets

/**
 * v3.1.1: translated names of the five default presets (rule in [Presets.displayName]). Every place that SHOWS a
 * preset name goes through here; storage, automation and the codec keep the stored (English) name.
 */
private fun defaultNameRes(id: String): Int? = when (id) {
    "evening" -> R.string.preset_evening
    "reading" -> R.string.preset_reading
    "candlelight" -> R.string.preset_candlelight
    "deep_red" -> R.string.preset_deep_red
    "ultra_dim" -> R.string.preset_ultra_dim
    else -> null
}

fun Context.presetLabel(preset: Preset): String =
    Presets.displayName(preset) { id -> defaultNameRes(id)?.let(::getString) }

fun Context.presetLabels(list: List<Preset>): List<Preset> =
    Presets.displayNames(list) { id -> defaultNameRes(id)?.let(::getString) }

@Composable
fun presetLabel(preset: Preset): String = LocalContext.current.presetLabel(preset)
