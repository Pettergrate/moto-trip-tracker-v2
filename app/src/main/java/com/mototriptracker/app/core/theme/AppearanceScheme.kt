package com.mototriptracker.app.core.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance

/** WCAG's target for text: what every text pair of every theme must reach (`AppearanceSchemeTest` checks all of them). */
internal const val MIN_TEXT_CONTRAST = 4.5f

/** WCAG contrast ratio between two opaque colours, 1 (identical) to 21 (black on white). */
internal fun contrastRatio(a: Color, b: Color): Float {
    val la = a.luminance()
    val lb = b.luminance()
    return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
}

private fun mix(from: Color, to: Color, fraction: Float): Color = to.copy(alpha = fraction).compositeOver(from)

/** Moves [color] toward [toward] in small steps until it reads on [against] (or runs out of room, which the tests would catch). */
private fun readableOn(color: Color, against: Color, toward: Color): Color {
    var result = color
    var step = 0
    while (contrastRatio(result, against) < MIN_TEXT_CONTRAST && step < 20) {
        result = mix(result, toward, 0.08f)
        step++
    }
    return result
}

/** The text colour, near-black or white, that reads better on [background]. */
private fun onColorFor(background: Color): Color {
    val dark = Color(0xFF111111)
    return if (contrastRatio(dark, background) >= contrastRatio(Color.White, background)) dark else Color.White
}

/** Moves [color] toward [toward] until the label that sits on it (see [onColorFor]) reaches the target - mid-tones like red fall between black and white text. */
private fun withReadableLabel(color: Color, toward: Color): Color {
    var result = color
    var step = 0
    while (contrastRatio(onColorFor(result), result) < MIN_TEXT_CONTRAST && step < 20) {
        result = mix(result, toward, 0.06f)
        step++
    }
    return result
}

/**
 * SET-002: the colour scheme for a base and an accent.
 *
 * The default (dark, orange) is the theme the owner chose on 2026-09-19 and is returned **exactly**, hand-tuned values
 * and all - nothing about it changes because Appearance exists. Every other combination is derived from the accent:
 * the accent is nudged until it reads as text on the base's background (orange gets darker on white, violet lighter on
 * black), the text colours that sit on it are picked for contrast, and the containers are tints of it. All of it is
 * held to `MIN_TEXT_CONTRAST` by a test over every combination.
 */
fun appearanceColorScheme(appearance: Appearance): ColorScheme = when {
    appearance == Appearance.Default -> BlackOrangeColorScheme
    appearance.base == ThemeBase.DARK -> darkScheme(appearance.accent.color)
    else -> lightScheme(appearance.accent.color)
}

private fun darkScheme(accent: Color): ColorScheme {
    val primary = withReadableLabel(readableOn(accent, BlackOrangeSurfaceVariant, toward = Color.White), toward = Color.White)
    val secondary = withReadableLabel(mix(primary, Color.Black, 0.2f), toward = Color.Black)
    val primaryContainer = mix(accent, Color.Black, 0.72f)
    val secondaryContainer = mix(accent, Color.Black, 0.78f)
    return darkColorScheme(
        primary = primary,
        onPrimary = onColorFor(primary),
        primaryContainer = primaryContainer,
        onPrimaryContainer = readableOn(mix(accent, Color.White, 0.75f), primaryContainer, toward = Color.White),
        secondary = secondary,
        onSecondary = onColorFor(secondary),
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = readableOn(mix(accent, Color.White, 0.8f), secondaryContainer, toward = Color.White),
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
}

private val LightBackground = Color(0xFFFAFAFA)
private val LightSurface = Color(0xFFFFFFFF)

/** The darkest surface the accent is used on in the light base; what it must read against is checked here, not on white alone. */
private val LightSurfaceVariant = Color(0xFFEDEDED)

private fun lightScheme(accent: Color): ColorScheme {
    val primary = withReadableLabel(readableOn(accent, LightSurfaceVariant, toward = Color.Black), toward = Color.Black)
    val secondary = withReadableLabel(readableOn(mix(primary, Color.Black, 0.2f), LightSurfaceVariant, toward = Color.Black), toward = Color.Black)
    val primaryContainer = mix(accent, Color.White, 0.82f)
    val secondaryContainer = mix(accent, Color.White, 0.85f)
    return lightColorScheme(
        primary = primary,
        onPrimary = onColorFor(primary),
        primaryContainer = primaryContainer,
        onPrimaryContainer = readableOn(mix(accent, Color.Black, 0.65f), primaryContainer, toward = Color.Black),
        secondary = secondary,
        onSecondary = onColorFor(secondary),
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = readableOn(mix(accent, Color.Black, 0.7f), secondaryContainer, toward = Color.Black),
        background = LightBackground,
        onBackground = Color(0xFF1A1A1A),
        surface = LightSurface,
        onSurface = Color(0xFF1A1A1A),
        surfaceVariant = LightSurfaceVariant,
        onSurfaceVariant = Color(0xFF494949),
        outline = Color(0xFF767676),
        outlineVariant = Color(0xFFCFCFCF),
        error = Color(0xFFB3261E),
        onError = Color.White,
        // Neutral greys: Material's baseline light slots carry a lilac tint that would clash with any accent.
        surfaceTint = primary,
        surfaceDim = Color(0xFFDDDDDD),
        surfaceBright = LightSurface,
        surfaceContainerLowest = LightSurface,
        surfaceContainerLow = Color(0xFFF7F7F7),
        surfaceContainer = Color(0xFFF2F2F2),
        surfaceContainerHigh = Color(0xFFECECEC),
        surfaceContainerHighest = Color(0xFFE6E6E6),
        inverseSurface = Color(0xFF2B2B2B),
        inverseOnSurface = Color(0xFFF5F5F5)
    )
}
