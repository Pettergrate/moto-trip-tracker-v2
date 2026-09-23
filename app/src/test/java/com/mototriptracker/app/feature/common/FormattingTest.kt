package com.mototriptracker.app.feature.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FormattingTest {

    @Test
    fun buildDataQualityNoteIsNullForACleanTrip() {
        assertNull(buildDataQualityNote(rejectedPointCount = 0, gapCount = 0))
    }

    @Test
    fun buildDataQualityNoteSingularWording() {
        assertEquals("1 GPS point excluded", buildDataQualityNote(rejectedPointCount = 1, gapCount = 0))
        assertEquals("1 signal gap", buildDataQualityNote(rejectedPointCount = 0, gapCount = 1))
    }

    @Test
    fun buildDataQualityNotePluralWording() {
        assertEquals("4 GPS points excluded", buildDataQualityNote(rejectedPointCount = 4, gapCount = 0))
        assertEquals("2 signal gaps", buildDataQualityNote(rejectedPointCount = 0, gapCount = 2))
    }

    @Test
    fun buildDataQualityNoteCombinesBothWhenBothAreNonZero() {
        assertEquals("3 GPS points excluded · 1 signal gap", buildDataQualityNote(rejectedPointCount = 3, gapCount = 1))
    }
}
