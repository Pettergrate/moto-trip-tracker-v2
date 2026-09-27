package com.mototriptracker.app.testing

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** A preferences store that can neither be read nor written - what a full or corrupted disk looks like to the app. */
class FailingDataStore : DataStore<Preferences> {
    override val data: Flow<Preferences> = flow { throw IOException("cannot read") }

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        throw IOException("cannot write")
}
