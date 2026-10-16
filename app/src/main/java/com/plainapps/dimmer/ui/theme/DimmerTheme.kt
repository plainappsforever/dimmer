package com.plainapps.dimmer.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Amber = Color(0xFFFF3C00)

private val DimmerColorScheme = darkColorScheme(
    primary = Amber,
    onPrimary = Color.Black,
    background = Color.Black,
    onBackground = Color.White,
    surface = Color.Black,
    onSurface = Color.White,
)

/** Always dark, never dynamic color - the screen itself is meant to look like the dimmer. */
@Composable
fun DimmerTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DimmerColorScheme, content = content)
}
