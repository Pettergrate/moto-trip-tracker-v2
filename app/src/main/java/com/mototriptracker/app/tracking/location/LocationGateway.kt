package com.mototriptracker.app.tracking.location

import com.mototriptracker.app.core.model.LocationSample
import kotlinx.coroutines.flow.Flow

/**
 * F0.8 §6: "Abstrae Fused Location Provider y perfiles F0.5/F0.6." The
 * production implementation ([FusedLocationGateway]) is the only class that
 * touches `com.google.android.gms.location.*`/`android.location.Location` —
 * everything downstream (the coordinator, tests) only ever sees
 * [LocationSample]. Shaped identically to TST-001's `LocationReplaySource`
 * (`Flow<LocationSample>`, one sample at a time) so a test double can wrap it
 * directly with no adapter.
 */
interface LocationGateway {
    fun locationUpdates(): Flow<LocationSample>
}
