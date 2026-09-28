package com.mototriptracker.app.testing

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * A preferences store that lives in memory: for tests of what a view model *does* with a preference, where the disk is only
 * a source of timing noise (a temp directory that a test framework may delete while an asynchronous write is under way).
 * That preferences really reach a file is proven separately, by the `*PreferencesTest` classes that use the real thing.
 */
class InMemoryDataStore(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
    private val state = MutableStateFlow(initial)

    override val data: Flow<Preferences> = state

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        val updated = transform(state.value)
        state.value = updated
        return updated
    }
}
