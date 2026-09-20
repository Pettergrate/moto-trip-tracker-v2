package com.mototriptracker.app.experiment

/** Seam (ADR-013) over the Android-only device signals in [FieldTestDeviceSnapshot]. */
interface FieldTestDeviceInfoProvider {
    fun current(): FieldTestDeviceSnapshot
}
