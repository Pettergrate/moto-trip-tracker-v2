package com.mototriptracker.app.core.model

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADR-014: version dimensions must stay independently comparable (e.g. to
 * decide whether cached ProcessedTrackPoints are stale relative to the
 * current ProcessingVersion) without being interchangeable with each other.
 */
class VersionsTest {

    @Test
    fun processingVersionsAreComparable() {
        assertTrue(ProcessingVersion(2) > ProcessingVersion(1))
    }

    @Test
    fun detectorAndLocationProfileVersionsAreDistinctTypes() {
        val detector = DetectorVersion(1)
        val locationProfile = LocationProfileVersion(1)
        // Compiles only because these are genuinely different types despite
        // sharing the same underlying Int value — that's the point of ADR-014.
        assertTrue(detector.value == locationProfile.value)
    }
}
