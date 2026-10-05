package com.mototriptracker.app.domain.detection

/**
 * DET-012 (`ADR-031`): the numbers of the start probe - a short GPS look, opened when Activity Recognition says the phone
 * stopped being still, to see whether a ride is beginning before Android is willing to call the rider a vehicle.
 * ADR-018: every one is a placeholder, here chosen to be measured.
 *
 * - [windowMs]: 6 minutes. In nine rides the `STILL`→`WALKING` transition came 1.6, 2.0, 4.1, 6.2 and 10.1 minutes before
 *   Activity Recognition's own trigger (and not at all in four); six minutes covers four of those five. Long enough
 *   to matter, short enough that a walk to the shop costs a few minutes of GPS (≈ 0.19 mAh a minute on the owner's
 *   phone, measured from the foreground-service share of the app's battery statistics).
 * - Speed only, never displacement ([toCandidateProfile] switches that path off): a person walking 40 m is not a trip,
 *   and displacement from an anchor is exactly what confirms one. [vehicleSpeedMps] on [vehicleSpeedFixesRequired]
 *   consecutive fixes are `CandidateStartProfile`'s own, 2.5 m/s (above brisk walking) three times in a row.
 * - [minConfirmationDurationMs]: 5 s - speed starts at the first fixes of a ride that begins from standstill, so the 15 s
 *   the detector waits is not needed to rule out a jump.
 * - [quietAfterRideMs]: 3 minutes. Right after a ride ends the rider walks away from the bike (a `STILL`→`WALKING` that is
 *   not the start of anything); no probe is opened for it.
 */
data class StartProbeProfile(
    val windowMs: Long = 360_000L,
    val minConfirmationDurationMs: Long = 5_000L,
    val vehicleSpeedMps: Float = 2.5f,
    val vehicleSpeedFixesRequired: Int = 3,
    val quietAfterRideMs: Long = 180_000L
) {
    /** The detector's own start engine, driven by speed alone (an unreachable displacement bar). */
    fun toCandidateProfile() = CandidateStartProfile(
        minConfirmationDurationMs = minConfirmationDurationMs,
        minDisplacementMeters = Double.MAX_VALUE,
        maxCandidateWindowMs = windowMs,
        vehicleSpeedMps = vehicleSpeedMps,
        vehicleSpeedFixesRequired = vehicleSpeedFixesRequired
    )
}
