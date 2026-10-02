package com.mototriptracker.app.domain.detection

import com.mototriptracker.app.core.model.ActivityType

/**
 * DET-009 (`ADR-026`): the labels Android's Activity Recognition gives a motorcycle ride. Besides `IN_VEHICLE`, the
 * owner's phone called real rides `ON_BICYCLE` - for the whole of one 1 km ride, and for most of another - and changed
 * label mid-ride. Detection that only listened for `IN_VEHICLE` never saw those rides begin.
 *
 * Activity Recognition reports one activity at a time, so a change between these two arrives as an EXIT of the old
 * label and an ENTER of the new one at the same instant; the engines follow which label is current instead of
 * treating the EXIT as the end of the ride.
 */
fun ActivityType.isVehicleLike(): Boolean = this == ActivityType.IN_VEHICLE || this == ActivityType.ON_BICYCLE
