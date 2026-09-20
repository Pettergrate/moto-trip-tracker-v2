package com.mototriptracker.app.core.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/**
 * The stock Material3 baseline the app shipped with through EXP-002 (plain
 * `MaterialTheme(content = content)`, i.e. `lightColorScheme()`'s defaults).
 * Kept, not deleted, by the project owner's 2026-09-19 decision:
 * [BlackOrangeColorScheme] becomes the active theme now, but this stays
 * available in code to become a SET-01 "Appearance" option later, rather
 * than being lost when the default changed.
 */
val LegacyMaterialColorScheme = lightColorScheme()

/** The black & orange theme chosen 2026-09-19 (project-owner decision) - see `Color.kt`. */
val BlackOrangeColorScheme = darkColorScheme(
    primary = BlackOrangePrimary,
    onPrimary = BlackOrangeOnPrimary,
    primaryContainer = BlackOrangePrimaryContainer,
    onPrimaryContainer = BlackOrangeOnPrimaryContainer,
    secondary = BlackOrangeSecondary,
    onSecondary = BlackOrangeOnSecondary,
    background = BlackOrangeBackground,
    onBackground = BlackOrangeOnBackground,
    surface = BlackOrangeSurface,
    onSurface = BlackOrangeOnSurface,
    surfaceVariant = BlackOrangeSurfaceVariant,
    onSurfaceVariant = BlackOrangeOnSurfaceVariant,
    outline = BlackOrangeOutline,
    error = BlackOrangeError,
    onError = BlackOrangeOnError
)

/**
 * App-wide theme entry point (`MainActivity`'s `setContent`). Not yet
 * user-selectable - SET-01 (Settings, not built yet) is where a real
 * "Appearance" toggle between [BlackOrangeColorScheme] and
 * [LegacyMaterialColorScheme] belongs. Until then this is the single
 * hardcoded scheme.
 */
@Composable
fun MotoTripTrackerTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = BlackOrangeColorScheme, content = content)
}
