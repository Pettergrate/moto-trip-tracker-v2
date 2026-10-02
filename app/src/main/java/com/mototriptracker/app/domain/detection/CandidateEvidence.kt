package com.mototriptracker.app.domain.detection

/**
 * DP-007/DET-008: what a candidate engine had actually seen when it decided - counts, speeds and one distance, never a
 * coordinate (`ADR-009`'s "no coordinates in diagnostics" holds). Before this, a candidate that never confirmed left no
 * trace at all: four whole rides in one day were lost and nothing recorded why.
 *
 * [fixCount] 0 means no location fix arrived while the candidate was open (the engine could not even try);
 * [displacementMeters] is straight-line from the candidate's first fix to its latest, `null` until a second fix.
 */
data class CandidateEvidence(
    val elapsedMs: Long,
    val fixCount: Int,
    val firstFixDelayMs: Long?,
    val maxSpeedMps: Float?,
    val displacementMeters: Double?,
    /** Stop candidates only: Activity Recognition said WALKING/ON_FOOT at some point while the candidate was open. */
    val walkingSeen: Boolean = false
) {
    fun toMetadata(): Map<String, String> = buildMap {
        put("elapsedMs", elapsedMs.toString())
        put("fixCount", fixCount.toString())
        firstFixDelayMs?.let { put("firstFixDelayMs", it.toString()) }
        maxSpeedMps?.let { put("maxSpeedMps", "%.1f".format(java.util.Locale.ROOT, it)) }
        displacementMeters?.let { put("displacementM", "%.0f".format(java.util.Locale.ROOT, it)) }
        if (walkingSeen) put("walkingSeen", "true")
    }
}
