package com.mototriptracker.app.feature.debug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mototriptracker.app.core.model.DiagnosticCategory
import com.mototriptracker.app.core.model.DiagnosticSeverity
import com.mototriptracker.app.diagnostics.DiagnosticSnapshot
import com.mototriptracker.app.diagnostics.EventBrief
import com.mototriptracker.app.feature.common.formatDateTime

/**
 * DIA-002 / F0.13 §10: the internal debug screen, reached from Settings. Read-only and free of coordinates (the
 * snapshot describes a fix by its age and accuracy, never where it was). Sections follow F0.13 §10.1 in order;
 * where the app has no source for a fact the screen says so instead of showing a plausible default.
 */
@Composable
fun DebugScreen(onBack: () -> Unit, viewModel: DebugViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val exportState by viewModel.exportState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // DIA-003: the package is ready - hand it to the system share sheet, once, and go back to idle.
    LaunchedEffect(exportState) {
        when (val current = exportState) {
            is ExportState.Ready -> {
                runCatching { context.startActivity(DiagnosticSharing.shareIntent(context, current.export)) }
                viewModel.onExportHandled()
            }
            ExportState.Failed -> viewModel.onExportHandled()
            else -> Unit
        }
    }
    DebugContent(
        state = state,
        exporting = exportState is ExportState.Working,
        onExport = viewModel::onExport,
        onBack = onBack,
        onCategorySelected = viewModel::onCategorySelected,
        onMinSeveritySelected = viewModel::onMinSeveritySelected,
        onQueryChanged = viewModel::onQueryChanged
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DebugContent(
    state: DebugUiState,
    exporting: Boolean,
    onExport: (Boolean) -> Unit,
    onBack: () -> Unit,
    onCategorySelected: (DiagnosticCategory?) -> Unit,
    onMinSeveritySelected: (DiagnosticSeverity?) -> Unit,
    onQueryChanged: (String) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Diagnostics (internal)") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val snapshot = state.snapshot
            if (snapshot == null) {
                item { Text("Reading state…", style = MaterialTheme.typography.bodyMedium) }
            } else {
                item { SnapshotSections(snapshot) }
            }
            item { ExportSection(exporting = exporting, onExport = onExport) }
            item { Text("Events", style = MaterialTheme.typography.titleMedium) }
            item { EventFilters(state.filter, onCategorySelected, onMinSeveritySelected, onQueryChanged) }
            item {
                Text(
                    "${state.matchingCount} matching" + if (state.matchingCount > state.events.size) " (showing the newest ${state.events.size})" else "",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            items(state.events, key = { it.eventId }) { row -> EventRowView(row) }
        }
    }
}

@Composable
private fun SnapshotSections(snapshot: DiagnosticSnapshot) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Section("Health") {
            Line("State", snapshot.tracking.health.name)
        }
        Section("App / build") {
            val a = snapshot.app
            Line("Version", "${a.versionName} (${a.versionCode})")
            Line("Android API", a.androidApi.toString())
            Line("Device", a.device)
            Line("Database schema", a.databaseSchemaVersion.toString())
            Line("Detector / location profile / processing", "${a.detectorVersion} / ${a.locationProfileVersion} / ${a.processingVersion}")
        }
        Section("Capabilities") {
            val c = snapshot.capabilities
            Line("Capability mode", c.mode.name)
            Line("Precise location", c.preciseLocation.yesNo())
            Line("Approximate location", c.approximateLocation.yesNo())
            Line("Background location", c.backgroundLocation.yesNo())
            Line("Activity recognition", c.activityRecognition.yesNo())
            Line("Notifications", c.notifications.yesNo())
            Line("Location services", c.locationServices.yesNo())
            Line("Battery saver", c.batterySaver?.yesNo() ?: "unknown")
            Line("Auto Tracking toggle", c.autoTrackingToggle.yesNo())
        }
        Section("Detector") {
            val d = snapshot.detector
            Text("Current state is not persisted - showing what was recorded.", style = MaterialTheme.typography.bodySmall)
            Line("Last activity transition", d.lastActivityTransition?.oneLine() ?: "none recorded")
            if (d.recent.isEmpty()) Line("Recent detector events", "none recorded") else d.recent.forEach { Line("Detector", it.oneLine()) }
        }
        Section("Location") {
            val l = snapshot.location
            Line("Active capture", l.hasActiveCapture.yesNo())
            Line("Stored points", l.pointCount?.toString() ?: "n/a")
            Line("Last fix", l.lastFixAgeMs?.let { "${formatAge(it)} ago" } ?: "n/a")
            Line("Last accuracy", l.lastAccuracyM?.let { "%.0f m".format(it) } ?: "n/a")
            Line("Last fix had speed", l.lastFixHadSpeed?.yesNo() ?: "n/a")
            Line("Effective interval", l.effectiveIntervalMs?.let { "${it} ms" } ?: "n/a")
            Line("Gap active", if (l.gapActive) "yes (${l.gapReason})" else "no")
            Line("Approximate location only", l.approximateOnly.yesNo())
            Line("Last finished trip", "${l.lastTripRejectedPoints ?: "n/a"} rejected points, ${l.lastTripGapCount ?: "n/a"} gaps")
        }
        Section("Tracking") {
            val t = snapshot.tracking
            Line("Capture", if (t.activeCapture) "ACTIVE (${t.shortCaptureId})" else "none")
            Line("Foreground notification", t.foregroundNotificationShown?.yesNo() ?: "unknown")
            Line("Last successful save", t.lastPersistenceAgeMs?.let { "${formatAge(it)} ago" } ?: "n/a")
            Line("Persistence", buildString {
                append(t.persistence.level.name)
                if (t.persistence.storageFull) append(", storage full")
                if (t.persistence.pointsLost) append(", points lost")
            })
        }
        Section("Processing") {
            val p = snapshot.processing
            Line("Pending / running", "${p.pending} / ${p.running}")
            Line("Succeeded / failed", "${p.succeeded} / ${p.failed}")
            Line("Highest attempt count", p.maxAttemptCount.toString())
        }
        Section("Recovery") {
            val r = snapshot.recovery
            Line("Last process exit", r.lastProcessExit?.oneLine() ?: "none recorded")
            Line("Last recovery action", r.lastRecoveryAction?.oneLine() ?: "none recorded")
            if (r.openInconsistencies.isEmpty()) Line("Open inconsistencies", "none") else r.openInconsistencies.forEach { Line("Inconsistency", it) }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            content()
        }
    }
}

@Composable
private fun Line(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.45f))
        Text(value, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.55f))
    }
}

@Composable
private fun EventFilters(
    filter: EventFilter,
    onCategorySelected: (DiagnosticCategory?) -> Unit,
    onMinSeveritySelected: (DiagnosticSeverity?) -> Unit,
    onQueryChanged: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = filter.query,
            onValueChange = onQueryChanged,
            label = { Text("Search reason code or event type") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { FilterChip(selected = filter.category == null, onClick = { onCategorySelected(null) }, label = { Text("All categories") }) }
            items(DiagnosticCategory.entries) { category ->
                FilterChip(
                    selected = filter.category == category,
                    onClick = { onCategorySelected(if (filter.category == category) null else category) },
                    label = { Text(category.name) }
                )
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { FilterChip(selected = filter.minSeverity == null, onClick = { onMinSeveritySelected(null) }, label = { Text("Any severity") }) }
            items(DiagnosticSeverity.entries) { severity ->
                FilterChip(
                    selected = filter.minSeverity == severity,
                    onClick = { onMinSeveritySelected(if (filter.minSeverity == severity) null else severity) },
                    label = { Text("${severity.name}+") }
                )
            }
        }
    }
}

@Composable
private fun EventRowView(row: EventRow) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("${formatDateTime(row.occurredAt)}  ${row.severity.name}", style = MaterialTheme.typography.labelMedium)
        Text("${row.category.name} · ${row.eventType}", style = MaterialTheme.typography.bodyMedium)
        val detail = listOfNotNull(row.reasonCode, row.shortCaptureId?.let { "capture $it" }).joinToString(" · ")
        if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodySmall)
        row.metadata?.let { Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
    }
}

private fun Boolean.yesNo() = if (this) "yes" else "no"

private fun EventBrief.oneLine(): String =
    listOfNotNull(formatDateTime(occurredAt), eventType, reasonCode, detail).joinToString(" · ")

private fun formatAge(ms: Long): String = when {
    ms < 1_000 -> "$ms ms"
    ms < 60_000 -> "${ms / 1_000} s"
    ms < 3_600_000 -> "${ms / 60_000} min"
    else -> "${ms / 3_600_000} h"
}

/**
 * DIA-003 / F0.13 §11: "Export diagnostic". The standard package is sanitized; route data is a separate, explicit
 * choice that says what it will contain (§11.3). Nothing is uploaded - the system share sheet opens and the person
 * chooses where it goes, or dismisses it.
 */
@Composable
private fun ExportSection(exporting: Boolean, onExport: (Boolean) -> Unit) {
    var dialogOpen by remember { mutableStateOf(false) }
    var includeRoute by remember { mutableStateOf(false) }

    OutlinedButton(onClick = { includeRoute = false; dialogOpen = true }, enabled = !exporting, modifier = Modifier.fillMaxWidth()) {
        Text(if (exporting) "Preparing…" else "Export diagnostic")
    }

    if (dialogOpen) {
        AlertDialog(
            onDismissRequest = { dialogOpen = false },
            title = { Text("Export diagnostic package") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Creates a file with the app's state and event history so a problem can be investigated. " +
                            "It contains no locations, no trip names or notes, and is not uploaded: you choose where to share it.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = includeRoute, onCheckedChange = { includeRoute = it })
                        Text("Include route data in this diagnostic", style = MaterialTheme.typography.bodyMedium)
                    }
                    if (includeRoute) {
                        Text(
                            "The file will contain precise locations (the raw track of your latest recording). Only share it with someone you trust with where you were.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { dialogOpen = false; onExport(includeRoute) }) { Text("Export") }
            },
            dismissButton = {
                TextButton(onClick = { dialogOpen = false }) { Text("Cancel") }
            }
        )
    }
}
