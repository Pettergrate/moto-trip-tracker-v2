package com.mototriptracker.app.experiment

/**
 * Writes one named file into a session's directory, per F0.6 §20's proposed
 * layout (`field-tests/sessions/<sessionId>/<fileName>`). Behind a seam
 * (ADR-013) so [FieldTestSessionExporter] is testable without real file I/O.
 *
 * F0.6 §20 lists more files (raw-track.csv, activity-events.jsonl,
 * detector-events.jsonl, system-events.jsonl, processed-track.geojson,
 * battery/) than EXP-001 writes — those need data sources that don't exist
 * yet (TRK-002 raw location ingestion, DET-001 activity recognition, a real
 * detector/system-lifecycle observer). This is the shell EXP-001 is named
 * for: session.json and annotations.json only, for now.
 */
interface FieldTestDatasetWriter {
    fun writeSessionFile(sessionId: String, fileName: String, content: String)
}
