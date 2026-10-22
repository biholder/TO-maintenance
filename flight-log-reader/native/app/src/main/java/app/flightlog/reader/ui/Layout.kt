package app.flightlog.reader.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

/** Экран шире, чем выше (телефон в альбомной ориентации, планшет). */
val LocalLandscape = staticCompositionLocalOf { false }

val landscape: Boolean
    @Composable get() = LocalLandscape.current
