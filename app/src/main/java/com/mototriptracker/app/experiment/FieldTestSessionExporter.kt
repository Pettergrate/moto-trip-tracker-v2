package com.mototriptracker.app.experiment

import javax.inject.Inject

/**
 * EXP-001 acceptance: "session metadata, ground-truth markers and
 * diagnostic export can be captured without becoming user-facing Core UX."
 * This is the export step — `session.json` + `annotations.json` under
 * `field-tests/sessions/<sessionId>/`, per F0.6 §20. Never modifies raw
 * data (F0.6 §5: "nunca modificar silenciosamente los datos raw") — it only
 * writes new files from what it's given.
 */
class FieldTestSessionExporter @Inject constructor(
    private val writer: FieldTestDatasetWriter
) {
    fun export(metadata: FieldTestSessionMetadata, markers: List<GroundTruthMarker>) {
        writer.writeSessionFile(metadata.sessionId, "session.json", FieldTestSessionJson.toJson(metadata))
        writer.writeSessionFile(metadata.sessionId, "annotations.json", FieldTestSessionJson.toJson(markers))
    }
}
