package com.plainapps.dimmer

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.plainapps.dimmer.engine.TileKind
import com.plainapps.dimmer.engine.TilePlan
import com.plainapps.dimmer.engine.TileSubtitle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Shared behaviour of both Quick Settings tiles (v3 P5, D-V3-06): follows the state while the
 * shade is open, opens the app when Accessibility access is missing, and leaves the click to
 * the subclass. Long-press opens the app (QS_TILE_PREFERENCES on [MainActivity]).
 */
abstract class DimmerTileBase : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var collectJob: Job? = null

    protected abstract val kind: TileKind
    protected abstract fun onAction(commands: DimmerCommands)

    override fun onStartListening() {
        super.onStartListening()
        val repository = (application as DimmerApplication).repository
        updateTile(repository.state.value)
        collectJob = scope.launch {
            repository.state.collect { state -> updateTile(state) }
        }
    }

    override fun onStopListening() {
        collectJob?.cancel()
        collectJob = null
        super.onStopListening()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onClick() {
        super.onClick()
        if (!DimmerAccessibilityService.isEnabled(this)) {
            openApp()
            return
        }
        onAction((application as DimmerApplication).commands)
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }

    /** A pause is shown as "Paused" (SPEC 4: distinct from Off); a tap still turns off / moves on. */
    private fun updateTile(state: DimmerState) {
        qsTile?.let { tile ->
            tile.state = if (state.enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = subtitleText(TilePlan.subtitle(kind, state.enabled, state.paused, state.activePreset?.let { presetLabel(it) }, state.darkness))
            }
            tile.updateTile()
        }
    }

    private fun subtitleText(s: TileSubtitle?): String? = when (s) {
        null -> null
        TileSubtitle.Paused -> getString(R.string.tile_paused)
        TileSubtitle.Off -> getString(R.string.widget_off)
        is TileSubtitle.Look -> {
            val name = s.presetName ?: getString(R.string.label_custom)
            if (s.withDarkness) getString(R.string.tile_look_with_darkness, name, s.darkness) else name
        }
    }
}

/** The v2 tile: on/off, unchanged behaviour (a tap while paused turns off). Subtitle = preset. */
class DimmerTileService : DimmerTileBase() {
    override val kind = TileKind.TOGGLE
    override fun onAction(commands: DimmerCommands) {
        commands.toggle("tile")
    }
}

/** v3 P5: Off -> first cycle preset -> ... -> last -> Off (the same order as NEXT_PRESET and the widget). */
class DimmerPresetTileService : DimmerTileBase() {
    override val kind = TileKind.PRESETS
    override fun onAction(commands: DimmerCommands) {
        commands.nextPreset("tile")
    }
}
