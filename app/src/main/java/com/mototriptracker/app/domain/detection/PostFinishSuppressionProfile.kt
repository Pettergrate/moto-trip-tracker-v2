package com.mototriptracker.app.domain.detection

/**
 * ADR-018: field-gated placeholder, not validated against real rides — F0.3
 * §9 itself says "exact timeout/logic remains a research question."
 *
 * F0.3 §7 requirement 7 / §9: after a Finish (manual or automatic), the
 * detector SHOULD NOT instantly treat still-ongoing motion as a brand new
 * Trip. Two minutes is a deliberately simple, time-only placeholder for a
 * question F0.3 itself leaves open ("should end after evidence shows
 * movement ended, or after another explicit Manual Start") — lifting the
 * window early on non-motorized evidence needs real field data about what
 * that evidence reliably looks like, which doesn't exist yet. A Manual
 * Start already bypasses this window structurally: it never goes through
 * the automatic-detection path this profile gates.
 */
data class PostFinishSuppressionProfile(
    val suppressionDurationMs: Long = 120_000L
)
