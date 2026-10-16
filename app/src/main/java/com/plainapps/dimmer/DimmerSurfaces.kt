package com.plainapps.dimmer

import android.appwidget.AppWidgetManager
import android.content.Context
import android.util.Log
import com.plainapps.dimmer.backlight.Backlight
import com.plainapps.dimmer.engine.WidgetContent
import com.plainapps.dimmer.engine.WidgetPlan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/**
 * v3 P5: pushes state to the surfaces the system draws for us - the home-screen widget and the
 * pinned shortcuts. Started once by [DimmerApplication]. The widget follows the repository within
 * [WIDGET_DEBOUNCE_MS] (SPEC 6: "within 1 s"); nothing is redrawn when the content is unchanged.
 */
class DimmerSurfaces(private val context: Context, private val repository: DimmerRepository) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val ping = MutableStateFlow(0)

    /** Accessibility bound/unbound: the widget's power button changes meaning (toggle vs open the app). */
    fun refresh() {
        ping.value++
    }

    @OptIn(FlowPreview::class)
    fun start() {
        scope.launch {
            combine(repository.state.filter { it.loaded }, ping) { state, _ -> widgetContent(state) }
                .distinctUntilChanged()
                .debounce(WIDGET_DEBOUNCE_MS)
                .collect { content ->
                    val manager = AppWidgetManager.getInstance(context)
                    val ids = manager.getAppWidgetIds(DimmerWidgetProvider.componentName(context))
                    if (ids.isNotEmpty()) {
                        Log.i(Backlight.LOG_TAG, "widget update ${ids.size} ${content.status} ${content.presetName ?: "Custom"}")
                        DimmerWidgetProvider.render(context, manager, ids, content)
                    }
                }
        }
        scope.launch {
            // ping too: a language change (DimmerApplication.onConfigurationChanged) relabels pinned shortcuts.
            combine(repository.state.filter { it.loaded }, ping) { state, _ -> context.presetLabels(state.presets) }
                .distinctUntilChanged()
                .collect { Shortcuts.reconcile(context, it) }
        }
    }

    /** Draws the given widgets now (system callbacks: add, resize, restore); a no-op until the state is loaded. */
    fun renderWidgets(ids: IntArray) {
        val state = repository.state.value
        if (!state.loaded) return
        DimmerWidgetProvider.render(context, AppWidgetManager.getInstance(context), ids, widgetContent(state))
    }

    private fun widgetContent(s: DimmerState): WidgetContent = WidgetPlan.content(
        enabled = s.enabled,
        paused = s.paused,
        presets = context.presetLabels(s.presets),
        cycle = s.tileCycle,
        activePresetId = s.activePresetId,
        darkness = s.darkness,
        accessOk = DimmerAccessibilityService.isEnabled(context),
    )

    private companion object {
        const val WIDGET_DEBOUNCE_MS = 300L
    }
}
