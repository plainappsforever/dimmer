package com.plainapps.dimmer

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.plainapps.dimmer.backlight.Backlight
import com.plainapps.dimmer.engine.Preset
import com.plainapps.dimmer.engine.ShortcutCommand
import com.plainapps.dimmer.engine.ShortcutPlan
import com.plainapps.dimmer.engine.ShortcutSpec

/**
 * Pinned per-preset shortcuts (v3 P5, SPEC 2 + 6) through `ShortcutManagerCompat` (androidx.core,
 * no new dependency). Static shortcuts live in res/xml/shortcuts.xml. What a pinned shortcut
 * means is decided by the pure [ShortcutPlan]; this file only talks to the framework.
 */
object Shortcuts {

    fun isPinSupported(context: Context): Boolean =
        ShortcutManagerCompat.isRequestPinShortcutSupported(context)

    /** "Add to home screen" in the preset menu: asks the launcher to pin a shortcut that applies [preset]. */
    fun requestPin(context: Context, preset: Preset): Boolean {
        val spec = ShortcutPlan.spec(ShortcutPlan.pinnedId(preset.id, System.currentTimeMillis()), preset)
        return try {
            ShortcutManagerCompat.requestPinShortcut(context, info(context, spec), null)
        } catch (e: RuntimeException) {
            Log.w(Backlight.LOG_TAG, "requestPin failed: ${e.message}")
            false
        }
    }

    /** Pinned shortcuts follow the preset list: relabelled after a rename, disabled after a delete. */
    fun reconcile(context: Context, presets: List<Preset>) {
        try {
            val pinnedIds = ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_PINNED).map { it.id }
            val plan = ShortcutPlan.reconcilePinned(pinnedIds, presets)
            if (plan.update.isNotEmpty()) {
                ShortcutManagerCompat.updateShortcuts(context, plan.update.map { info(context, it) })
            }
            if (plan.disableIds.isNotEmpty()) {
                ShortcutManagerCompat.disableShortcuts(context, plan.disableIds, context.getString(R.string.shortcut_preset_deleted))
                Log.i(Backlight.LOG_TAG, "pinned shortcuts disabled: ${plan.disableIds}")
            }
        } catch (e: RuntimeException) {
            // Rate limiting or a launcher without shortcut support: not worth a crash.
            Log.w(Backlight.LOG_TAG, "shortcut reconcile failed: ${e.message}")
        }
    }

    private fun info(context: Context, spec: ShortcutSpec): ShortcutInfoCompat =
        ShortcutInfoCompat.Builder(context, spec.id)
            .setShortLabel(spec.shortLabel)
            .setLongLabel(spec.shortLabel)
            .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
            .setIntent(
                Intent(context, ShortcutActivity::class.java)
                    .setAction(ShortcutCommand.ACTION_PRESET)
                    .putExtra(ShortcutCommand.EXTRA_PRESET_ID, spec.presetId),
            )
            .build()
}
