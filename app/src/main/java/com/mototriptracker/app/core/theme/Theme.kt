package com.mototriptracker.app.core.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

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
 * Sharp, angular corners rather than Material3's default soft/pill shapes -
 * part of the same "aggressive, motorcyclist-facing" direction as the color
 * revision above: rounded pill buttons read as friendly/soft, not racing/KTM.
 */
val AggressiveShapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp),
    small = RoundedCornerShape(4.dp),
    medium = RoundedCornerShape(4.dp),
    large = RoundedCornerShape(6.dp),
    extraLarge = RoundedCornerShape(8.dp)
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
    MaterialTheme(colorScheme = BlackOrangeColorScheme, shapes = AggressiveShapes, content = content)
}
