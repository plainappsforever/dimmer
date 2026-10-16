package com.plainapps.dimmer.engine

enum class TileKind { TOGGLE, PRESETS }

/** The tile subtitle as data; the tile turns it into a string. */
sealed interface TileSubtitle {
    data object Paused : TileSubtitle
    data object Off : TileSubtitle

    /** [presetName] null = Custom; [darkness] shown only when [withDarkness]. */
    data class Look(val presetName: String?, val darkness: Int, val withDarkness: Boolean) : TileSubtitle
}

/** v3 P5 (SPEC 6): subtitles for the on/off tile (preset name) and the presets tile (name + darkness). */
object TilePlan {
    fun subtitle(kind: TileKind, enabled: Boolean, paused: Boolean, presetName: String?, darkness: Int): TileSubtitle? = when {
        enabled && paused -> TileSubtitle.Paused
        enabled -> TileSubtitle.Look(presetName, darkness, withDarkness = kind == TileKind.PRESETS)
        kind == TileKind.PRESETS -> TileSubtitle.Off
        else -> null
    }
}
