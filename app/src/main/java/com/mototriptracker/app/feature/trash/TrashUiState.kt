package com.mototriptracker.app.feature.trash

/** TRS-001/F0.9 §14: the Trash list state. */
data class TrashUiState(val trips: List<TrashTripUi> = emptyList())

data class TrashTripUi(
    val tripId: String,
    val displayName: String,
    val dateTimeLabel: String,
    val distanceMeters: Double?,
    val durationMs: Long?,
    val purgeDateLabel: String
)
