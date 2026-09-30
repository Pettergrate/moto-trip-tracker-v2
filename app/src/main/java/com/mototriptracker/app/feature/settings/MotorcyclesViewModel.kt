package com.mototriptracker.app.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mototriptracker.app.core.common.Clock
import com.mototriptracker.app.core.common.IdGenerator
import com.mototriptracker.app.core.database.dao.MotorcycleDao
import com.mototriptracker.app.core.database.entity.MotorcycleEntity
import com.mototriptracker.app.core.model.ProcessingVersion
import com.mototriptracker.app.core.model.VehicleType
import com.mototriptracker.app.tracking.processing.TripProcessingWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** MOTO-001/`FR-MOTO-001/002/003`/`ADR-024`: Motorcycle CRUD, archive (never a hard delete), and `FR-MOTO-003`'s live distance-per-motorcycle aggregate. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MotorcyclesViewModel @Inject constructor(
    private val motorcycleDao: MotorcycleDao,
    private val idGenerator: IdGenerator,
    private val clock: Clock
) : ViewModel() {

    private val version = TripProcessingWorker.CURRENT_PROCESSING_VERSION

    val motorcycles: StateFlow<List<MotorcycleEntity>> = motorcycleDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    /** `FR-MOTO-003`: live, recomputed as any motorcycle's own Trips gain/lose statistics - keyed by motorcycle id. */
    val distanceMetersById: StateFlow<Map<String, Double>> = motorcycles.flatMapLatest { list ->
        if (list.isEmpty()) {
            flowOf(emptyMap())
        } else {
            combine(list.map { motorcycle -> motorcycleDao.observeTotalDistanceMeters(motorcycle.id, version).map { motorcycle.id to it } }) { pairs ->
                pairs.toMap()
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyMap())

    fun addMotorcycle(name: String, make: String?, model: String?, year: Int?, vehicleType: VehicleType) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            val now = clock.wallClockMillis()
            motorcycleDao.insert(
                MotorcycleEntity(
                    id = idGenerator.newId(), name = trimmed, make = make?.trim()?.ifEmpty { null }, model = model?.trim()?.ifEmpty { null },
                    year = year, isArchived = false, createdAt = now, updatedAt = now, vehicleType = vehicleType
                )
            )
        }
    }

    fun updateMotorcycle(id: String, name: String, make: String?, model: String?, year: Int?, vehicleType: VehicleType) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            motorcycleDao.update(id, trimmed, make?.trim()?.ifEmpty { null }, model?.trim()?.ifEmpty { null }, year, vehicleType, clock.wallClockMillis())
        }
    }

    /** F0.7 §11: archive, never delete - a motorcycle's own Trip history stays legible either way. */
    fun setArchived(id: String, isArchived: Boolean) {
        viewModelScope.launch {
            motorcycleDao.setArchived(id, isArchived, clock.wallClockMillis())
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
