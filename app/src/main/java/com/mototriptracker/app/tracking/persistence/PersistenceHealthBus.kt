package com.mototriptracker.app.tracking.persistence

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * REC-006: how the live recording tells the UI that it cannot save. In memory on purpose - the
 * database is exactly what may be failing, so it is the one place this warning cannot be stored
 * (`reliability-recovery.md` §14: "señalar estado de persistencia degradado"). A `@Singleton` so the
 * service that records and the screen that shows it share the one state for the process lifetime,
 * like `ActivityTransitionBus`. A restarted process starts HEALTHY, which is honest: whatever was
 * held in RAM died with the old one and its loss is already an ordinary gap in the raw track.
 */
@Singleton
class PersistenceHealthBus @Inject constructor() {
    private val _state = MutableStateFlow(PersistenceState.HEALTHY)
    val state: StateFlow<PersistenceState> = _state.asStateFlow()

    fun publish(state: PersistenceState) {
        _state.value = state
    }

    fun reset() {
        _state.value = PersistenceState.HEALTHY
    }
}
