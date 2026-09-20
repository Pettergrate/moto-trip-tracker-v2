package com.mototriptracker.app.experiment

import com.mototriptracker.app.core.model.LocationProfile

/**
 * F0.6 §10's tracking-request campaigns as real, swappable [LocationProfile]
 * values - EXP-003's actual subject ("compare interval/min-distance/batching
 * experiment profiles"). Before this, `experimentProfileId` in the harness
 * was just a free-text label with no real switch behind it (see EXP-002's
 * backlog note) - `FusedLocationGateway`'s GPS request was a single
 * hardcoded configuration regardless of what a tester typed.
 *
 * Only Campaign S1 (interval) is defined here. F0.6 §10 makes S2 (min
 * distance) depend on "el mejor intervalo preliminar de S1" and S3
 * (batching) on "el perfil ganador provisional de S1/S2" - neither has a
 * real answer yet, since no S1 campaign has actually been run on real rides.
 * Hardcoding S2/S3 profiles now would mean inventing a result F0.6 itself
 * says must come from real data. Add them once a real S1 pilot/validation
 * campaign actually picks a winner.
 */
object ExperimentLocationProfiles {

    /** The single fixed configuration production tracking used before EXP-003 - the safe fallback whenever no experiment profile is selected. */
    val DEFAULT = LocationProfile(
        id = "tracking-manual-v0",
        intervalMillis = 2_000L,
        minUpdateDistanceMeters = 0f,
        maxUpdateDelayMillis = 0L
    )

    /** F0.6 §10 Campaign S1: "Mantener minUpdateDistance = 0 y batching desactivado." */
    val S1_A = LocationProfile("S1-A", intervalMillis = 1_000L, minUpdateDistanceMeters = 0f, maxUpdateDelayMillis = 0L)
    val S1_B = LocationProfile("S1-B", intervalMillis = 2_000L, minUpdateDistanceMeters = 0f, maxUpdateDelayMillis = 0L)
    val S1_C = LocationProfile("S1-C", intervalMillis = 5_000L, minUpdateDistanceMeters = 0f, maxUpdateDelayMillis = 0L)

    private val known = listOf(DEFAULT, S1_A, S1_B, S1_C)

    /** `null` when [profileId] doesn't match a known real profile - the harness still accepts arbitrary text labels for record-keeping, but only these actually change GPS behavior. */
    fun findById(profileId: String): LocationProfile? = known.firstOrNull { it.id == profileId }
}
