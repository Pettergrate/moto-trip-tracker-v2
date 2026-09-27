package com.mototriptracker.app.feature.map

import androidx.compose.ui.graphics.Color
import com.mototriptracker.app.core.theme.AccentColor
import org.junit.Assert.assertEquals
import org.junit.Test

/** SET-002: the map marker takes the accent, and with the default accent it is the orange it always was. */
class MapColorsTest {

    @Test
    fun theDefaultAccentGivesTheOrangeTheMarkerAlwaysHad() {
        assertEquals("#F2540C", AccentColor.ORANGE.color.toMapHex())
    }

    @Test
    fun theAlphaIsDroppedAndTheDigitsAreZeroPadded() {
        assertEquals("#000005", Color(0xFF000005).toMapHex())
        assertEquals("#FFFFFF", Color.White.toMapHex())
    }
}
