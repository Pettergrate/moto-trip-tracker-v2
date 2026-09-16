package com.mototriptracker.app.core.database.entity

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Operationalizes F0.13 §4.1's metadata rules at the type level, the same
 * way DomainBoundaryTest operationalizes ADR-013: instead of just trusting
 * that nobody adds a forbidden field later, fail the build if one appears.
 *
 * Checks full words ("latitude", "longitude"), not short substrings like
 * "lat"/"lon" — those false-positive against legitimate fields such as
 * `correlationId` (contains "lat").
 */
class DiagnosticEventPrivacyTest {

    @Test
    fun entityHasNoCoordinateOrFreeTextFields() {
        val forbidden = listOf("latitude", "longitude", "notes", "name")
        val fieldNames = DiagnosticEventEntity::class.java.declaredFields.map { it.name.lowercase() }

        val offending = fieldNames.filter { field -> forbidden.any { field.contains(it) } }

        assertTrue(
            "DiagnosticEventEntity must not carry coordinate/free-text fields " +
                "(F0.13 §4.1). Offending fields: $offending",
            offending.isEmpty()
        )
    }
}
