package com.mototriptracker.app.core.theme

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import com.mototriptracker.app.R

/** SET-002: the two bases a theme is built on. */
enum class ThemeBase { DARK, LIGHT }

/**
 * SET-002: the basic palette the owner asked for ("un sistema de dos colores": a base and an accent). Every accent is a
 * mid-tone on purpose, so it can be adjusted to read on either base (`AppearanceScheme.kt`); the palette is fixed rather
 * than a free colour picker because a free choice can produce text nobody can read.
 *
 * [ORANGE] is the accent of the theme the owner chose on 2026-09-19 (`BlackOrangePrimary`) and stays the default.
 */
enum class AccentColor(val color: Color, @StringRes val labelRes: Int) {
    ORANGE(Color(0xFFF2540C), R.string.accent_orange),
    RED(Color(0xFFE5383B), R.string.accent_red),
    YELLOW(Color(0xFFF2B705), R.string.accent_yellow),
    GREEN(Color(0xFF2FA35A), R.string.accent_green),
    TEAL(Color(0xFF12A6A0), R.string.accent_teal),
    BLUE(Color(0xFF2F80ED), R.string.accent_blue),
    VIOLET(Color(0xFF7E57C2), R.string.accent_violet),
    PINK(Color(0xFFD6336C), R.string.accent_pink)
}

/** What the person picked. The default is the black and orange theme, unchanged. */
data class Appearance(val base: ThemeBase = ThemeBase.DARK, val accent: AccentColor = AccentColor.ORANGE) {
    companion object {
        val Default = Appearance()
    }
}
