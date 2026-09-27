package com.mototriptracker.app.feature.home

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mototriptracker.app.core.model.CapabilityMode
import com.mototriptracker.app.domain.capability.CapabilityIssue
import com.mototriptracker.app.feature.common.CapabilityIssueCopy
import com.mototriptracker.app.feature.common.rememberCapabilityFixer
import com.mototriptracker.app.feature.common.formatDistanceKm
import com.mototriptracker.app.feature.common.formatDurationClock
import com.mototriptracker.app.feature.common.formatDurationCompact
import com.mototriptracker.app.feature.common.LocationExplanationDialog
import com.mototriptracker.app.feature.common.LocationPermissionDeniedDialog
import com.mototriptracker.app.feature.common.hasLocationPermission
import com.mototriptracker.app.feature.common.rememberStartWithLocationPermission

/** How often Home re-reads permissions and services while it is visible. */
private const val CAPABILITY_RECHECK_MS = 3_000L

/** F0.9 §5: HOME-01. */
@Composable
fun HomeScreen(
    onViewActiveTrip: () -> Unit,
    onOpenTripDetail: (String) -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showPermissionDeniedDialog by remember { mutableStateOf(false) }
    var showLocationExplanation by remember { mutableStateOf(false) }
    var startAnywayIssue by remember { mutableStateOf<CapabilityIssue?>(null) }

    // F0.9 §17: re-check readiness every time Home comes back to the foreground - not only the first time it is
    // composed - so a permission or Location toggle changed in the phone's Settings (which is exactly where the
    // actions below send the person) is reflected on return, without a continuous poll.
    LifecycleResumeEffect(Unit) {
        viewModel.refreshCapabilityMode()
        onPauseOrDispose { }
    }
    // ...and while it stays in front: turning Location off from the quick-settings panel does not pause the activity, so
    // "on resume" alone would leave the card stale. A light re-read every few seconds, only while Home is visible.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                delay(CAPABILITY_RECHECK_MS)
                viewModel.refreshCapabilityMode()
            }
        }
    }

    val fixIssue = rememberCapabilityFixer(onAfterAttempt = viewModel::refreshCapabilityMode)

    // PERM-001 / privacy-permissions.md 19.2, in order: explain precise location -> the system asks -> (PERM-003) say
    // once if the trip would record no route -> offer notifications, once -> start. Each step only when needed, and a
    // "no" at any of them leaves the person where they were.
    val context = LocalContext.current
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Granted or not, the trip starts: denied notifications never stop a recording (the Active Trip card explains).
        viewModel.onStartTripClick()
    }
    val startAfterChecks = {
        viewModel.prepareNotificationAsk(
            onAsk = { notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
            onSkip = viewModel::onStartTripClick
        )
    }
    // PERM-003: judged from a fresh read at this point, not from what the card last showed - which also catches the
    // person who has just chosen "approximate" in the system dialog.
    val checkThenStart = {
        viewModel.checkBeforeStart(onWarn = { startAnywayIssue = it }, onProceed = startAfterChecks)
    }
    val requestLocation = rememberStartWithLocationPermission(
        onGranted = checkThenStart,
        onDenied = { showPermissionDeniedDialog = true }
    )
    val onStartTripClick = {
        if (hasLocationPermission(context)) requestLocation() else showLocationExplanation = true
    }

    HomeContent(
        uiState = uiState,
        onFixIssue = fixIssue,
        onStartTripClick = onStartTripClick,
        onPauseClick = viewModel::onPauseClick,
        onResumeClick = viewModel::onResumeClick,
        onViewActiveTrip = onViewActiveTrip,
        onOpenTripDetail = onOpenTripDetail
    )

    if (showPermissionDeniedDialog) {
        LocationPermissionDeniedDialog(onDismiss = { showPermissionDeniedDialog = false })
    }

    if (showLocationExplanation) {
        LocationExplanationDialog(
            onContinue = { showLocationExplanation = false; requestLocation() },
            onNotNow = { showLocationExplanation = false }
        )
    }

    startAnywayIssue?.let { issue ->
        val copy = CapabilityIssueCopy.of(issue)
        AlertDialog(
            onDismissRequest = { startAnywayIssue = null },
            title = { Text(copy.title) },
            text = { Text(copy.startAnywayMessage.orEmpty()) },
            confirmButton = { TextButton(onClick = { startAnywayIssue = null; fixIssue(issue) }) { Text(copy.actionLabel) } },
            dismissButton = { TextButton(onClick = { startAnywayIssue = null; startAfterChecks() }) { Text("Start anyway") } }
        )
    }
}

@Composable
private fun HomeContent(
    uiState: HomeUiState,
    onFixIssue: (CapabilityIssue) -> Unit,
    onStartTripClick: () -> Unit,
    onPauseClick: () -> Unit,
    onResumeClick: () -> Unit,
    onViewActiveTrip: () -> Unit,
    onOpenTripDetail: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            val activeTrip = uiState.activeTrip
            if (activeTrip != null) {
                ActiveTripCard(
                    isPaused = activeTrip.isPaused,
                    distanceMeters = activeTrip.distanceMeters,
                    elapsedMs = activeTrip.elapsedMs,
                    onViewActiveTrip = onViewActiveTrip,
                    onPauseClick = onPauseClick,
                    onResumeClick = onResumeClick
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (uiState.capabilityIssues.isNotEmpty()) {
                        CapabilityIssueCard(issues = uiState.capabilityIssues, onFix = onFixIssue)
                    }
                    ReadinessCard(capabilityMode = uiState.capabilityMode, onStartTripClick = onStartTripClick)
                }
            }
        }

        item {
            Text("Recent", style = MaterialTheme.typography.titleMedium)
        }

        if (uiState.recentTrips.isEmpty()) {
            item { Text("No trips yet", style = MaterialTheme.typography.bodyMedium) }
        } else {
            items(uiState.recentTrips, key = { it.tripId }) { trip ->
                RecentTripRow(trip, onClick = { onOpenTripDetail(trip.tripId) })
            }
        }
    }
}

@Composable
private fun ReadinessCard(capabilityMode: CapabilityMode?, onStartTripClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Auto Tracking", style = MaterialTheme.typography.titleMedium)
            Text(
                text = capabilityMode?.toReadinessText() ?: "Checking readiness…",
                style = MaterialTheme.typography.bodyMedium
            )
            Button(onClick = onStartTripClick, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
                Text("START TRIP")
            }
        }
    }
}

@Composable
private fun ActiveTripCard(
    isPaused: Boolean,
    distanceMeters: Double,
    elapsedMs: Long,
    onViewActiveTrip: () -> Unit,
    onPauseClick: () -> Unit,
    onResumeClick: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = if (isPaused) "● TRIP PAUSED" else "● TRIP IN PROGRESS",
                style = MaterialTheme.typography.titleMedium
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatDistanceKm(distanceMeters), style = MaterialTheme.typography.headlineSmall)
                Text(formatDurationClock(elapsedMs), style = MaterialTheme.typography.headlineSmall)
            }
            Button(onClick = onViewActiveTrip, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
                Text("VIEW ACTIVE TRIP")
            }
            OutlinedButton(
                onClick = if (isPaused) onResumeClick else onPauseClick,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isPaused) "Resume" else "Pause")
            }
        }
    }
}

@Composable
private fun RecentTripRow(trip: RecentTripUi, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(trip.displayName, style = MaterialTheme.typography.titleSmall)
            val distanceText = trip.distanceMeters?.let { formatDistanceKm(it) } ?: "—"
            val durationText = trip.durationMs?.let { formatDurationCompact(it) } ?: "—"
            Text("$distanceText · $durationText", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * PERM-003 / §19.4: the most important problem with one clear action; anything else is only mentioned, and comes up
 * once this one is fixed. Not a dialog, so it never interrupts and never repeats.
 */
@Composable
private fun CapabilityIssueCard(issues: List<CapabilityIssue>, onFix: (CapabilityIssue) -> Unit) {
    val top = issues.first()
    val copy = CapabilityIssueCopy.of(top)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(copy.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
            Text(copy.message, style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = { onFix(top) }) { Text(copy.actionLabel) }
            if (issues.size > 1) {
                Text(
                    "Also: " + issues.drop(1).joinToString(", ") { CapabilityIssueCopy.of(it).title.lowercase() },
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
