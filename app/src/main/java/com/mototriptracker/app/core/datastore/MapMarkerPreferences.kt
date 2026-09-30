package com.mototriptracker.app.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.mototriptracker.app.core.model.VehicleType
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/**
 * MAP-006/`ADR-024`: what the map's current-position marker draws when no `Trip`/Motorcycle is more specifically
 * known. [defaultVehicleType] is the fallback used everywhere (a completed Trip with no motorcycle assigned).
 * [selectedMotorcycleId] is purely cosmetic and read only by the live map (`ActiveTripViewModel`) - no `TripEntity`
 * exists yet for an in-progress capture, so there is nothing to look up there; this is never written to or read by
 * any tracking/persistence code (`ADR-024`'s own deliberate boundary). Same safe-default shape as
 * [AppearancePreferences]: an unreadable store or an unrecognized value falls back to the default, never an error.
 */
class MapMarkerPreferences @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    val defaultVehicleType: Flow<VehicleType> = dataStore.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { preferences -> VehicleType.entries.firstOrNull { it.name == preferences[KEY_DEFAULT_VEHICLE_TYPE] } ?: VehicleType.MOTORCYCLE }

    val selectedMotorcycleId: Flow<String?> = dataStore.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { preferences -> preferences[KEY_SELECTED_MOTORCYCLE_ID] }

    suspend fun setDefaultVehicleType(vehicleType: VehicleType) {
        dataStore.edit { it[KEY_DEFAULT_VEHICLE_TYPE] = vehicleType.name }
    }

    suspend fun setSelectedMotorcycle(motorcycleId: String?) {
        dataStore.edit {
            if (motorcycleId == null) it.remove(KEY_SELECTED_MOTORCYCLE_ID) else it[KEY_SELECTED_MOTORCYCLE_ID] = motorcycleId
        }
    }

    companion object {
        private val KEY_DEFAULT_VEHICLE_TYPE = stringPreferencesKey("map_default_vehicle_type")
        private val KEY_SELECTED_MOTORCYCLE_ID = stringPreferencesKey("map_selected_motorcycle_id")
    }
}
