package com.mototriptracker.app.experiment

/** Deterministic [FieldTestDeviceInfoProvider] for tests. */
class FakeFieldTestDeviceInfoProvider(
    private var snapshot: FieldTestDeviceSnapshot = FieldTestDeviceSnapshot(
        appVersion = "test-version",
        phoneManufacturer = "TestManufacturer",
        phoneModel = "TestModel",
        androidVersion = "16",
        playServicesVersion = null,
        batterySaverState = "disabled",
        locationSettingsState = "enabled",
        notificationPermissionState = "enabled",
        screenStateAtStart = "on"
    )
) : FieldTestDeviceInfoProvider {
    override fun current(): FieldTestDeviceSnapshot = snapshot

    fun set(snapshot: FieldTestDeviceSnapshot) {
        this.snapshot = snapshot
    }
}
