package com.mototriptracker.app.core.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** SET-002: whatever base and accent the person picks, the text on it must be readable - and the default must not move. */
class AppearanceSchemeTest {

    private val allCombinations = ThemeBase.entries.flatMap { base -> AccentColor.entries.map { Appearance(base, it) } }

    @Test
    fun theDefaultIsExactlyTheBlackAndOrangeThemeTheOwnerChose() {
        assertEquals(Appearance(ThemeBase.DARK, AccentColor.ORANGE), Appearance.Default)
        assertEquals(BlackOrangeColorScheme, appearanceColorScheme(Appearance.Default))
    }

    @Test
    fun theOrangeAccentIsTheOriginalPrimary() {
        assertEquals(BlackOrangePrimary, AccentColor.ORANGE.color)
    }

    @Test
    fun everyCombinationHasReadableTextOnEveryColourItSitsOn() {
        allCombinations.forEach { appearance ->
            val s = appearanceColorScheme(appearance)
            fun check(what: String, text: Color, background: Color, minimum: Float = MIN_TEXT_CONTRAST) {
                val ratio = contrastRatio(text, background)
                assertTrue("$appearance: $what has contrast $ratio, needs $minimum", ratio >= minimum)
            }
            check("onPrimary on primary", s.onPrimary, s.primary)
            check("onSecondary on secondary", s.onSecondary, s.secondary)
            check("onPrimaryContainer on primaryContainer", s.onPrimaryContainer, s.primaryContainer)
            check("onSecondaryContainer on secondaryContainer", s.onSecondaryContainer, s.secondaryContainer)
            check("onSurface on surface", s.onSurface, s.surface, minimum = 7f)
            check("onBackground on background", s.onBackground, s.background, minimum = 7f)
            check("onSurfaceVariant on surfaceVariant", s.onSurfaceVariant, s.surfaceVariant)
            check("onError on error", s.onError, s.error)
            // The accent is used as text too (section titles, links): it has to read on the backgrounds it sits on.
            check("primary on background", s.primary, s.background)
            check("primary on surface", s.primary, s.surface)
            check("primary on surfaceVariant", s.primary, s.surfaceVariant)
            check("onSurfaceVariant on surface", s.onSurfaceVariant, s.surface)
        }
    }

    @Test
    fun aLightBaseIsLightAndADarkBaseIsDark() {
        allCombinations.forEach { appearance ->
            val background = appearanceColorScheme(appearance).background.luminance()
            if (appearance.base == ThemeBase.LIGHT) assertTrue("$appearance", background > 0.8f) else assertTrue("$appearance", background < 0.05f)
        }
    }

    @Test
    fun eachAccentGivesItsOwnLookOnEachBase() {
        ThemeBase.entries.forEach { base ->
            val primaries = AccentColor.entries.map { appearanceColorScheme(Appearance(base, it)).primary }
            assertEquals("two accents ended up identical on $base", primaries.size, primaries.toSet().size)
        }
    }

    @Test
    fun theAccentAdaptsToTheBaseInsteadOfBeingUsedRaw() {
        // Orange reads on black as it is, but is too light for text on white: it has to get darker there.
        val onWhite = appearanceColorScheme(Appearance(ThemeBase.LIGHT, AccentColor.ORANGE)).primary
        assertTrue(onWhite.luminance() < AccentColor.ORANGE.color.luminance())
        // Violet is too dark for text on black: it has to get lighter there.
        val onBlack = appearanceColorScheme(Appearance(ThemeBase.DARK, AccentColor.VIOLET)).primary
        assertTrue(onBlack.luminance() > AccentColor.VIOLET.color.luminance())
    }

    @Test
    fun theLightBaseCarriesNoLilacTintFromMaterialBaseline() {
        val scheme = appearanceColorScheme(Appearance(ThemeBase.LIGHT, AccentColor.ORANGE))
        listOf(scheme.surfaceContainerLowest, scheme.surfaceContainerLow, scheme.surfaceContainer, scheme.surfaceContainerHigh, scheme.surfaceContainerHighest)
            .forEach { c -> assertTrue("$c is not a neutral grey", c.red == c.green && c.green == c.blue) }
    }

    @Test
    fun contrastRatioIsTheStandardOne() {
        assertEquals(21f, contrastRatio(Color.Black, Color.White), 0.01f)
        assertEquals(1f, contrastRatio(Color.Red, Color.Red), 0.001f)
        assertNotEquals(contrastRatio(Color.Black, Color.White), contrastRatio(Color.Gray, Color.White))
    }
}
