package com.mototriptracker.app.testing

import com.mototriptracker.app.core.database.dao.DiagnosticEventDao
import com.mototriptracker.app.core.database.dao.RawTrackPointDao
import com.mototriptracker.app.core.database.entity.DiagnosticEventEntity
import com.mototriptracker.app.core.database.entity.RawTrackPointEntity

/** REC-006: a real DAO whose inserts can be made to fail on demand, to exercise the persistence-failure paths. */
class FlakyRawTrackPointDao(private val real: RawTrackPointDao) : RawTrackPointDao by real {
    @Volatile var failure: Exception? = null
    @Volatile var insertAttempts = 0

    override suspend fun insert(point: RawTrackPointEntity) {
        insertAttempts++
        failure?.let { throw it }
        real.insert(point)
    }
}

/** REC-006: when the database is the thing that is down, the diagnostics about it fail too. */
class FlakyDiagnosticEventDao(private val real: DiagnosticEventDao) : DiagnosticEventDao by real {
    @Volatile var failing = false

    override suspend fun insert(event: DiagnosticEventEntity) {
        if (failing) throw IllegalStateException("diagnostics unavailable")
        real.insert(event)
    }
}
