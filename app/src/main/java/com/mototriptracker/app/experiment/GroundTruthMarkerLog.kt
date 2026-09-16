package com.mototriptracker.app.experiment

/**
 * Accumulates markers for one session in the order they were recorded.
 * Pure, zero Android dependency — a future harness UI/service calls
 * [record] as the tester taps buttons; this only keeps the list.
 */
class GroundTruthMarkerLog {
    private val entries = mutableListOf<GroundTruthMarker>()

    val markers: List<GroundTruthMarker> get() = entries.toList()

    fun record(marker: GroundTruthMarker) {
        entries.add(marker)
    }

    fun clear() {
        entries.clear()
    }
}
