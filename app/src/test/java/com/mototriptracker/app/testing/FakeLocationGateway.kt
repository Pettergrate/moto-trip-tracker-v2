package com.mototriptracker.app.testing

import com.mototriptracker.app.core.model.LocationSample
import com.mototriptracker.app.tracking.location.LocationGateway
import kotlinx.coroutines.flow.Flow

/** Wraps [LocationReplaySource] behind the real [LocationGateway] seam. */
class FakeLocationGateway(samples: List<LocationSample>) : LocationGateway {
    private val replaySource = LocationReplaySource(samples)
    override fun locationUpdates(): Flow<LocationSample> = replaySource.replay()
}
