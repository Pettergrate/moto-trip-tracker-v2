package com.mototriptracker.app.core.model

/**
 * F0.6 §10's tracking-request parameters as a plain, swappable value. [id]
 * is a label only - matching F0.6's own profile IDs so a Raw Track point's
 * [LocationSample.requestProfileId] can be tied back to what the GPS request
 * actually was when it was captured, which is the whole point of EXP-003's
 * comparison (F0.5/F0.7 already required this field to be honest per-point
 * provenance, not a fixed constant).
 */
data class LocationProfile(
    val id: String,
    val intervalMillis: Long,
    val minUpdateDistanceMeters: Float,
    val maxUpdateDelayMillis: Long
)
