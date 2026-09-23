package com.mototriptracker.app.feature.common

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** "38.4 km" - Core distance display. Units policy (km/mi, F0.9 §14) is SET-01's job; km is the only unit for now. */
fun formatDistanceKm(meters: Double): String = String.format(Locale.getDefault(), "%.1f km", meters / 1_000.0)

/** "97 km/h" - HIS-001's Trip Detail speed fields. Same km-only posture as [formatDistanceKm]. */
fun formatSpeedKmh(metersPerSecond: Double): String = String.format(Locale.getDefault(), "%.0f km/h", metersPerSecond * 3.6)

/** "142 m" - HIS-001's Trip Detail elevation fields (PRC-003 not built yet, so callers only ever see this once real values exist). */
fun formatElevationM(meters: Double): String = String.format(Locale.getDefault(), "%.0f m", meters)

/** "1h 47m" for durations at/over an hour, "39m" under an hour - matches F0.9's own wireframes. */
fun formatDurationCompact(millis: Long): String {
    val totalMinutes = millis / 60_000L
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}

/** "00:47:12" - Active Trip's own live timer format (F0.9 TRP-01 wireframe), distinct from the compact form above. */
fun formatDurationClock(millis: Long): String {
    val totalSeconds = millis / 1_000L
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
}

/**
 * F0.9 §8.2's fallback name: "Trip · Sep 15 · 08:42" - used by both Home
 * (UI-001) and History/Trip Detail (HIS-001) whenever a Trip has no
 * user-given name, so it lives here rather than duplicated per screen.
 */
fun fallbackTripName(createdAtEpochMs: Long): String {
    val formatter = SimpleDateFormat("MMM d · HH:mm", Locale.getDefault())
    return "Trip · ${formatter.format(Date(createdAtEpochMs))}"
}

/** "Sep 18, 2026 · 10:41" - Trip Detail's header (F0.9 §9.1: name/date/favorite), a fuller form than [fallbackTripName]'s own compact date. */
fun formatDateTime(epochMs: Long): String {
    val formatter = SimpleDateFormat("MMM d, yyyy · HH:mm", Locale.getDefault())
    return formatter.format(Date(epochMs))
}

/**
 * MET-001: an honest note only when there's something real to report - no
 * FR-MET requirement or UX wireframe specifies what "calidad del registro"
 * should look like (ux-navigation.md §9.2's only trace of the concept), so
 * this stays derived from real counters rather than a decorative badge on
 * every clean trip. `suspectPointCount` is deliberately not one of the
 * inputs - nothing in this codebase ever sets it to anything but 0 yet
 * (`TripMetricsCalculator`), so it isn't real signal.
 */
fun buildDataQualityNote(rejectedPointCount: Int, gapCount: Int): String? {
    val parts = mutableListOf<String>()
    if (rejectedPointCount > 0) {
        parts += if (rejectedPointCount == 1) "1 GPS point excluded" else "$rejectedPointCount GPS points excluded"
    }
    if (gapCount > 0) {
        parts += if (gapCount == 1) "1 signal gap" else "$gapCount signal gaps"
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}
