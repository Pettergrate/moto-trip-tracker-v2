package com.mototriptracker.app.feature.debug

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mototriptracker.app.core.database.dao.DiagnosticEventDao
import com.mototriptracker.app.core.model.DiagnosticCategory
import com.mototriptracker.app.core.model.DiagnosticSeverity
import com.mototriptracker.app.diagnostics.DiagnosticSnapshot
import com.mototriptracker.app.diagnostics.DiagnosticSnapshotProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import javax.inject.Inject

/**
 * DIA-002 / F0.13 §10: the internal debug screen. A *view* over evidence that already lives elsewhere (Room, the
 * platform, an in-memory bus) - it can be thrown away and rebuilt at any time and owns no state that matters
 * (F0.13 §18.1: "puede reconstruirse desde repositories sin convertirse en fuente de verdad").
 */
@HiltViewModel
class DebugViewModel @Inject constructor(
    private val snapshotProvider: DiagnosticSnapshotProvider,
    diagnosticEventDao: DiagnosticEventDao
) : ViewModel() {

    private val filter = MutableStateFlow(EventFilter())

    /** Re-read every couple of seconds while the screen is visible, so "age of the last fix" and the health state are live. */
    private val snapshot: Flow<DiagnosticSnapshot?> = flow {
        while (true) {
            emit(runCatching { snapshotProvider.snapshot() }.getOrNull())
            delay(REFRESH_MS)
        }
    }

    val uiState: StateFlow<DebugUiState> = combine(
        snapshot,
        diagnosticEventDao.observeRecent(LOAD_LIMIT),
        filter
    ) { snapshot, events, filter ->
        val matching = events.filter { DebugEventFiltering.matches(it, filter) }
        DebugUiState(
            snapshot = snapshot,
            events = matching.take(DebugEventFiltering.DISPLAY_CAP).map(DebugEventFiltering::toRow),
            matchingCount = matching.size,
            filter = filter
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), DebugUiState())

    fun onCategorySelected(category: DiagnosticCategory?) = filter.update { it.copy(category = category) }

    fun onMinSeveritySelected(severity: DiagnosticSeverity?) = filter.update { it.copy(minSeverity = severity) }

    fun onQueryChanged(query: String) = filter.update { it.copy(query = query) }

    private companion object {
        const val REFRESH_MS = 2_000L
        const val STOP_TIMEOUT_MS = 5_000L

        /** More than the display cap so a narrow filter still has something to find; still far below the table's own bound. */
        const val LOAD_LIMIT = 1_000
    }
}
