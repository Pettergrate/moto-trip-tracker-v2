package com.mototriptracker.app.domain.capability

import com.mototriptracker.app.core.model.CapabilityInputs

/**
 * PERM-002 / SET-02: when the app should be listening for activity transitions at all.
 *
 * Only while Auto Tracking is on **and** the activity permission is granted. Off means *not listening*, not merely "not
 * starting anything": detection that stays registered while the person has switched it off keeps collecting movement
 * data they chose not to have collected (`privacy-permissions.md` §8.1, §8.2, `NFR-PRV-002`). Location and notifications
 * are deliberately not part of this - transitions need neither, and having the detector ready when they come back
 * is the point; whether a trip may then *start* is the capability resolver's separate decision.
 */
object DetectionListening {
    fun shouldListen(inputs: CapabilityInputs): Boolean = inputs.autoTrackingEnabledByUser && inputs.activityRecognitionGranted
}
