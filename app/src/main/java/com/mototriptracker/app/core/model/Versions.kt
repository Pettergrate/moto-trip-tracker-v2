package com.mototriptracker.app.core.model

/**
 * ADR-014: schema/detector/location/processing versions are independent
 * dimensions — a schema migration doesn't imply a new detector, and vice
 * versa. Distinct value classes (zero runtime overhead) stop these plain
 * Ints from being accidentally passed to the wrong parameter, which a bare
 * `Int` wouldn't catch at compile time. Room's `@Database(version = ...)`
 * already models schemaVersion on its own; it isn't wrapped here.
 */

@JvmInline
value class DetectorVersion(val value: Int) : Comparable<DetectorVersion> {
    override fun compareTo(other: DetectorVersion): Int = value.compareTo(other.value)
}

@JvmInline
value class LocationProfileVersion(val value: Int) : Comparable<LocationProfileVersion> {
    override fun compareTo(other: LocationProfileVersion): Int = value.compareTo(other.value)
}

@JvmInline
value class ProcessingVersion(val value: Int) : Comparable<ProcessingVersion> {
    override fun compareTo(other: ProcessingVersion): Int = value.compareTo(other.value)
}
