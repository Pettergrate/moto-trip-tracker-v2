package com.mototriptracker.app.feature.fieldtest

import com.mototriptracker.app.core.model.CapabilityMode
import com.mototriptracker.app.core.model.StartSource
import com.mototriptracker.app.experiment.GroundTruthMarkerType

/** EXP-002/F0.6 §5: the harness's own two modes — configuring a not-yet-started session, or one actively running. */
sealed interface FieldTestHarnessUiState {
    data class Configuring(
        val experimentProfileId: String = "",
        val phonePlacement: String = "",
        val routeType: String = "",
        val weatherNotes: String = "",
        val notes: String = "",
        val lastExport: ExportSummary? = null
    ) : FieldTestHarnessUiState

    data class Active(
        val sessionId: String,
        val experimentProfileId: String,
        val elapsedMs: Long,
        val capabilityMode: CapabilityMode,
        val activeCapture: ActiveCaptureInfo?,
        val markerCounts: Map<GroundTruthMarkerType, Int>
    ) : FieldTestHarnessUiState
}

data class ActiveCaptureInfo(
    val startSource: StartSource,
    val isPaused: Boolean,
    val distanceMeters: Double,
    val elapsedMs: Long
)

data class ExportSummary(val sessionId: String, val markerCount: Int)

/**
 * F0.6 §7 / `GroundTruthMarker`'s own KDoc: GT_START/GT_END are reviewed
 * post-hoc against the Raw Track, never tapped live by the tester - so the
 * harness only offers buttons for the remaining, genuinely live-taggable
 * vocabulary.
 */
val LIVE_GROUND_TRUTH_MARKER_TYPES: List<GroundTruthMarkerType> = GroundTruthMarkerType.entries.filterNot {
    it == GroundTruthMarkerType.GT_START || it == GroundTruthMarkerType.GT_END
}
