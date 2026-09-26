package com.mototriptracker.app.tracking.persistence

import com.mototriptracker.app.core.database.dao.RawTrackPointDao
import com.mototriptracker.app.core.database.entity.RawTrackPointEntity

/**
 * REC-006: a seam for making raw-point writes fail on purpose, so the failure path (the buffer, the
 * retry, the warnings, the loss records) can be exercised on a real device without touching the
 * owner's data or the phone's storage - the one device available holds real trips.
 *
 * **No implementation ships in a release build.** The only one lives in the `debug` source set and is
 * bound through an optional binding ([com.mototriptracker.app.core.di.FaultInjectionModule]); with
 * nothing bound, `DatabaseModule` hands out the plain DAO and the buffer keeps its default size, so
 * release behaviour is exactly what it was.
 */
interface RawWriteFaultInjector {
    /** Throws to make the next raw insert fail; returns normally when there is no fault to inject. */
    fun beforeRawInsert()

    /** Overrides the writer's buffer capacity (so an overflow needs seconds, not twenty minutes); `null` keeps the default. */
    fun bufferCapacity(): Int?
}

/** Consults [faults] before every raw insert and otherwise behaves as the real DAO (every other query is delegated untouched). */
class FaultInjectingRawTrackPointDao(
    private val real: RawTrackPointDao,
    private val faults: RawWriteFaultInjector
) : RawTrackPointDao by real {
    override suspend fun insert(point: RawTrackPointEntity) {
        faults.beforeRawInsert()
        real.insert(point)
    }
}
