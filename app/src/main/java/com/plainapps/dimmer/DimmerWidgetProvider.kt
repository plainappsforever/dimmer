package com.plainapps.dimmer

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import com.plainapps.dimmer.engine.WidgetAction
import com.plainapps.dimmer.engine.WidgetContent
import com.plainapps.dimmer.engine.WidgetLayout
import com.plainapps.dimmer.engine.WidgetPlan
import com.plainapps.dimmer.engine.WidgetStatus

/**
 * Home-screen widget (v3 P5, SPEC 6, D-V3-05): plain RemoteViews, `updatePeriodMillis = 0`.
 * Small (2x1) = power button + preset name, tap the name to cycle. Wide (4x1) = power + up to four
 * cycle presets. The layout is chosen from the widget's width every time it is drawn (add, resize
 * in [onAppWidgetOptionsChanged], state change). State changes reach it through [DimmerSurfaces],
 * debounced; taps go to [DimmerActionReceiver] and so through [DimmerCommands].
 */
class DimmerWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        // Before DataStore is read the state would be wrong (Off, default presets): skip; the
        // DimmerSurfaces collector draws every widget as soon as the state is loaded.
        (context.applicationContext as DimmerApplication).surfaces.renderWidgets(appWidgetIds)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle?,
    ) {
        (context.applicationContext as DimmerApplication).surfaces.renderWidgets(intArrayOf(appWidgetId))
    }

    companion object {
        fun componentName(context: Context) = ComponentName(context, DimmerWidgetProvider::class.java)

        fun render(context: Context, manager: AppWidgetManager, ids: IntArray, content: WidgetContent) {
            for (id in ids) {
                val minWidthDp = manager.getAppWidgetOptions(id)
                    .getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
                val layout = WidgetPlan.layoutFor(minWidthDp)
                val views = when (layout) {
                    WidgetLayout.SMALL -> smallViews(context, content)
                    WidgetLayout.WIDE -> wideViews(context, content)
                }
                manager.updateAppWidget(id, views)
            }
        }

        private fun statusText(context: Context, c: WidgetContent): String = when (c.status) {
            WidgetStatus.OFF -> context.getString(R.string.widget_off)
            WidgetStatus.PAUSED -> context.getString(R.string.tile_paused)
            WidgetStatus.ON -> context.getString(R.string.widget_on, c.darkness)
        }

        private fun powerViews(context: Context, views: RemoteViews, c: WidgetContent) {
            val on = c.status != WidgetStatus.OFF
            views.setInt(
                R.id.widget_power,
                "setColorFilter",
                context.getColor(if (on) R.color.widget_power_on else R.color.widget_power_off),
            )
            views.setInt(
                R.id.widget_power,
                "setBackgroundResource",
                if (on) R.drawable.widget_power_bg_on else R.drawable.widget_power_bg,
            )
            views.setContentDescription(R.id.widget_power, context.getString(R.string.widget_power_description))
            views.setOnClickPendingIntent(R.id.widget_power, powerIntent(context, c))
        }

        private fun smallViews(context: Context, c: WidgetContent): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_small)
            powerViews(context, views, c)
            views.setTextViewText(
                R.id.widget_title,
                if (c.status == WidgetStatus.OFF) context.getString(R.string.app_name)
                else c.presetName ?: context.getString(R.string.label_custom),
            )
            views.setTextViewText(R.id.widget_subtitle, statusText(context, c))
            views.setOnClickPendingIntent(R.id.widget_text, actionIntent(context, WidgetAction.CYCLE, 2, c))
            return views
        }

        private val buttonIds = intArrayOf(R.id.widget_btn1, R.id.widget_btn2, R.id.widget_btn3, R.id.widget_btn4)

        private fun wideViews(context: Context, c: WidgetContent): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_wide)
            powerViews(context, views, c)
            views.setTextViewText(R.id.widget_subtitle, statusText(context, c))
            buttonIds.forEachIndexed { index, viewId ->
                val button = c.buttons.getOrNull(index)
                if (button == null) {
                    views.setViewVisibility(viewId, View.GONE)
                } else {
                    views.setViewVisibility(viewId, View.VISIBLE)
                    views.setTextViewText(viewId, button.name)
                    views.setInt(
                        viewId,
                        "setBackgroundResource",
                        if (button.selected) R.drawable.widget_button_bg_active else R.drawable.widget_button_bg,
                    )
                    views.setTextColor(
                        viewId,
                        context.getColor(if (button.selected) R.color.widget_button_text_active else R.color.widget_button_text),
                    )
                    views.setOnClickPendingIntent(
                        viewId,
                        actionIntent(context, WidgetAction.PRESET, 10 + index, c, presetId = button.presetId),
                    )
                }
            }
            return views
        }

        /** Power: toggles; while Accessibility access is missing it opens the app, where the grant is. */
        private fun powerIntent(context: Context, c: WidgetContent): PendingIntent =
            if (c.accessOk) actionIntent(context, WidgetAction.TOGGLE, 1, c) else openAppIntent(context)

        private fun actionIntent(
            context: Context,
            action: WidgetAction,
            requestCode: Int,
            c: WidgetContent,
            presetId: String? = null,
        ): PendingIntent =
            if (!c.accessOk) {
                openAppIntent(context)
            } else {
                PendingIntent.getBroadcast(
                    context,
                    requestCode,
                    Intent(context, DimmerActionReceiver::class.java)
                        .setAction(action.intentAction)
                        .apply { presetId?.let { putExtra(EXTRA_PRESET_ID, it) } },
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            }

        private fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        const val EXTRA_PRESET_ID = "preset_id"
    }
}
