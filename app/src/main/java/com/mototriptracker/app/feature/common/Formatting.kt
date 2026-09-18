package com.mototriptracker.app.feature.common

import java.util.Locale

/** "38.4 km" - Core distance display. Units policy (km/mi, F0.9 §14) is SET-01's job; km is the only unit for now. */
fun formatDistanceKm(meters: Double): String = String.format(Locale.getDefault(), "%.1f km", meters / 1_000.0)

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
