package com.mototriptracker.app.testing

import com.mototriptracker.app.core.model.LocationSample
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow

/**
 * F0.12 §5.2. Replays a fixed, ordered [LocationSample] sequence with no
 * real delays (F0.12 §19 forbids sleeps as test synchronization) — each
 * sample already carries its own recorded timestamps, so detector/
 * processing logic under test reasons about elapsed time from that data
 * plus a [com.mototriptracker.app.core.common.Clock] fake, not from actual
 * wall-clock waiting here.
 *
 * Deliberately does no interpretation (no filtering, no detector logic):
 * that belongs to the DET and PRC tasks once they exist. This only replays
 * what it was given, in order, deterministically.
 */
class LocationReplaySource(private val samples: List<LocationSample>) {
    fun replay(): Flow<LocationSample> = samples.asFlow()
}
