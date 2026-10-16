package com.plainapps.dimmer.engine

/** Home-screen widget layouts (v3 P5, SPEC 6): 2x1 small, 4x1 wide. */
enum class WidgetLayout { SMALL, WIDE }

enum class WidgetStatus { OFF, ON, PAUSED }

/** What a widget tap does; carried as `com.plainapps.dimmer.internal.WIDGET_<name>` to DimmerActionReceiver. */
enum class WidgetAction {
    TOGGLE,
    CYCLE,
    PRESET,
    ;

    val intentAction: String get() = "com.plainapps.dimmer.internal.WIDGET_$name"

    companion object {
        fun fromIntentAction(action: String?): WidgetAction? = entries.firstOrNull { it.intentAction == action }
    }
}

data class WidgetButton(val presetId: String, val name: String, val selected: Boolean)

/**
 * Everything the widget shows, as plain data (so it is JVM-tested and equal content means no
 * redraw). [presetName] null = Custom. [accessOk] false = the power button opens the app instead
 * of switching (Accessibility access is missing).
 */
data class WidgetContent(
    val status: WidgetStatus,
    val accessOk: Boolean,
    val presetName: String?,
    val darkness: Int,
    val buttons: List<WidgetButton>,
)

object WidgetPlan {
    /** Widget width (dp, the launcher's minimum width) from which the wide layout is used. */
    const val WIDE_MIN_DP = 250
    const val MAX_BUTTONS = 4

    fun layoutFor(minWidthDp: Int): WidgetLayout =
        if (minWidthDp >= WIDE_MIN_DP) WidgetLayout.WIDE else WidgetLayout.SMALL

    /**
     * Wide-layout buttons are the tile-cycle presets, up to [MAX_BUTTONS], in preset order. With
     * an empty cycle the first presets are used instead, so the widget is never useless.
     */
    fun content(
        enabled: Boolean,
        paused: Boolean,
        presets: List<Preset>,
        cycle: List<String>,
        activePresetId: String?,
        darkness: Int,
        accessOk: Boolean,
    ): WidgetContent {
        val status = when {
            !enabled -> WidgetStatus.OFF
            paused -> WidgetStatus.PAUSED
            else -> WidgetStatus.ON
        }
        val cyclePresets = presets.filter { it.id in cycle }.ifEmpty { presets }
        val buttons = cyclePresets.take(MAX_BUTTONS).map {
            WidgetButton(it.id, it.name, selected = enabled && it.id == activePresetId)
        }
        return WidgetContent(
            status = status,
            accessOk = accessOk,
            presetName = presets.firstOrNull { it.id == activePresetId }?.name,
            darkness = darkness,
            buttons = buttons,
        )
    }
}
