package com.mototriptracker.app.tracking.persistence

import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteFullException
import android.util.Log
import com.mototriptracker.app.BuildConfig
import com.mototriptracker.app.core.common.Clock
import com.mototriptracker.app.core.common.IdGenerator
import com.mototriptracker.app.core.database.dao.DiagnosticEventDao
import com.mototriptracker.app.core.database.dao.RawTrackPointDao
import com.mototriptracker.app.core.database.entity.DiagnosticEventEntity
import com.mototriptracker.app.core.database.entity.RawTrackPointEntity
import com.mototriptracker.app.core.model.DetectorVersion
import com.mototriptracker.app.core.model.DiagnosticCategory
import com.mototriptracker.app.core.model.DiagnosticSeverity
import com.mototriptracker.app.core.model.LocationProfileVersion
import com.mototriptracker.app.core.model.ProcessingVersion
import com.mototriptracker.app.domain.persistence.BoundedWriteBuffer
import com.mototriptracker.app.domain.persistence.RetryBackoff
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * REC-006 / `reliability-recovery.md` §14: the one place a recording's raw points reach Room.
 *
 * Healthy, it is a plain insert per point. When an insert fails it stops trusting the database
 * *without lying about it*:
 *
 * 1. The point and the ones after it are held in a bounded, ordered buffer ([BoundedWriteBuffer])
 *    - never claimed as saved (§14.1 item 4). Each keeps the `sequenceNumber` it was given on
 *    receipt, so recovery writes them back in order, in the right place.
 * 2. Retries are gated by [RetryBackoff]: a database that stays down is tried a few dozen times an
 *    hour, not once per fix (§14.3, "evitar ciclos infinitos").
 * 3. While it lasts, [PersistenceState] says so (DEGRADED; CRITICAL once storage is full or a point
 *    had to be dropped) - handed to the caller in memory, because the failing database is the one
 *    place the warning cannot be stored.
 * 4. When the buffer overflows the *oldest* point is dropped and counted - never silently
 *    (§14.2). One outage is one **episode** with a handful of events (the first failure, the first
 *    overflow, the recovery summary, and `DATA_LOSS_DETECTED` if anything was lost), not one event per
 *    failed point; events that cannot be written yet wait and are retried.
 *
 * A [SQLiteConstraintException] is different in kind: that exact row can never be stored, so
 * retrying it would block every point behind it forever. It is dropped and counted as lost, and the
 * points behind it carry on.
 *
 * All entry points are serialized ([Mutex]): the location collector and the periodic retry tick
 * are sibling coroutines. Diagnostics carry counts and durations only - never a coordinate.
 */
class RawPointWriter(
    private val rawTrackPointDao: RawTrackPointDao,
    private val diagnosticEventDao: DiagnosticEventDao,
    private val clock: Clock,
    private val idGenerator: IdGenerator,
    private val captureId: String,
    capacity: Int = DEFAULT_CAPACITY,
    private val onStateChanged: suspend (PersistenceState) -> Unit = {}
) {
    private val turn = Mutex()
    private val buffer = BoundedWriteBuffer<RawTrackPointEntity>(capacity)
    private val backoff = RetryBackoff()
    private var episode: Episode? = null
    @Volatile
    private var publishedState = PersistenceState.HEALTHY

    /** Points lost for a reason other than an outage (constraint rejections) since this writer began. */
    private var rejectedTotal = 0
    private var rejectionLogged = false

    /** Events that could not be written when they happened; retried in order, bounded. */
    private val pendingEvents = ArrayDeque<DiagnosticEventEntity>()

    private class Episode(val startedAtElapsedMs: Long, val firstErrorClass: String) {
        var attempts = 0
        var flushed = 0
        var dropped = 0
        var rejected = 0
        var storageFull = false
        var overflowLogged = false
        var firstLostElapsedNanos: Long? = null
        var lastLostElapsedNanos: Long? = null

        fun noteLoss(point: RawTrackPointEntity) {
            if (firstLostElapsedNanos == null) firstLostElapsedNanos = point.elapsedRealtimeNanos
            lastLostElapsedNanos = point.elapsedRealtimeNanos
        }
    }

    /** A point the recording has just received, already carrying its `sequenceNumber`. */
    suspend fun write(point: RawTrackPointEntity) = turn.withLock {
        if (episode == null) {
            try {
                rawTrackPointDao.insert(point)
                return@withLock
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (error is SQLiteConstraintException) {
                    recordRejection(point, error)
                    return@withLock
                }
                beginEpisode(error)
            }
            hold(point)
            backoff.onFailure(nowMs())
            publish()
        } else {
            hold(point)
            flush(force = false)
        }
    }

    /** The periodic check: nothing new arrived, but a held buffer should still be retried (subject to the backoff). */
    suspend fun retryPending() = turn.withLock {
        if (episode != null) flush(force = false)
        drainPendingEvents()
    }

    /**
     * The recording is ending (Finish, service stopping): one last attempt that ignores the
     * backoff, and whatever still cannot be written is *recorded as lost*, never left as if saved.
     * Idempotent.
     */
    suspend fun finish() = turn.withLock {
        val current = episode
        if (current != null) {
            flush(force = true)
            if (episode != null && !buffer.isEmpty) {
                buffer.toList().forEach { current.noteLoss(it) }
                val discarded = buffer.discardAll()
                current.dropped += discarded
                Log.e(TAG, "recording ended with $discarded unsaved point(s) capture=$captureId")
                endEpisode(discardedAtStop = discarded)
            }
        }
        if (rejectedTotal > 0) {
            emit(dataLoss(droppedOnOverflow = 0, rejected = rejectedTotal, discardedAtStop = 0, firstLost = null, lastLost = null))
            rejectedTotal = 0
        }
        drainPendingEvents()
    }

    /** What the caller should show right now. */
    val state: PersistenceState get() = publishedState

    private fun hold(point: RawTrackPointEntity) {
        val displaced = buffer.offer(point) ?: return
        val current = episode ?: return
        current.dropped++
        current.noteLoss(displaced)
    }

    private suspend fun flush(force: Boolean) {
        val current = episode ?: return
        if (buffer.isEmpty) {
            endEpisode()
            return
        }
        val now = nowMs()
        if (!force && !backoff.canAttempt(now)) {
            // Overflow can happen between attempts; make it visible even if no attempt is due.
            noteOverflowIfAny(current)
            publish()
            return
        }
        current.attempts++
        while (true) {
            val next = buffer.peek() ?: break
            try {
                rawTrackPointDao.insert(next)
                buffer.removeFirst()
                current.flushed++
                backoff.onSuccess()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (error is SQLiteConstraintException) {
                    buffer.removeFirst()
                    current.rejected++
                    current.noteLoss(next)
                    continue
                }
                if (error is SQLiteFullException) current.storageFull = true
                backoff.onFailure(now)
                noteOverflowIfAny(current)
                publish()
                return
            }
        }
        endEpisode()
    }

    private suspend fun beginEpisode(error: Exception) {
        val current = Episode(startedAtElapsedMs = nowMs(), firstErrorClass = error::class.simpleName ?: "Exception")
        if (error is SQLiteFullException) current.storageFull = true
        episode = current
        Log.w(TAG, "raw point writes failing capture=$captureId error=${current.firstErrorClass}; buffering")
        emit(event(EVENT_INSERT_FAILED, DiagnosticSeverity.ERROR, current.firstErrorClass, metadata = mapOf("storageFull" to current.storageFull.toString())))
    }

    private suspend fun noteOverflowIfAny(current: Episode) {
        if (current.dropped > 0 && !current.overflowLogged) {
            current.overflowLogged = true
            Log.e(TAG, "raw point buffer overflow capture=$captureId; dropping the oldest held points")
            emit(
                event(
                    EVENT_BUFFER_OVERFLOW, DiagnosticSeverity.ERROR, "BUFFER_FULL",
                    metadata = mapOf("capacity" to buffer.capacity.toString()),
                    elapsedNanos = current.firstLostElapsedNanos
                )
            )
        }
    }

    private suspend fun endEpisode(discardedAtStop: Int = 0) {
        val current = episode ?: return
        noteOverflowIfAny(current)
        val lost = current.dropped + current.rejected
        Log.i(
            TAG,
            "raw point writes recovered capture=$captureId flushed=${current.flushed} lost=$lost " +
                "episodeMs=${nowMs() - current.startedAtElapsedMs} attempts=${current.attempts}"
        )
        emit(
            event(
                EVENT_RECOVERED,
                if (lost > 0) DiagnosticSeverity.WARN else DiagnosticSeverity.INFO,
                if (discardedAtStop > 0) "RECORDING_ENDED_UNSAVED" else "WRITES_RESUMED",
                metadata = mapOf(
                    "flushedPoints" to current.flushed.toString(),
                    "droppedPoints" to current.dropped.toString(),
                    "rejectedPoints" to current.rejected.toString(),
                    "highWaterMark" to buffer.highWaterMark.toString(),
                    "attempts" to current.attempts.toString(),
                    "episodeMs" to (nowMs() - current.startedAtElapsedMs).toString(),
                    "storageFull" to current.storageFull.toString()
                )
            )
        )
        if (lost > 0) {
            emit(
                dataLoss(
                    droppedOnOverflow = current.dropped - discardedAtStop,
                    rejected = current.rejected,
                    discardedAtStop = discardedAtStop,
                    firstLost = current.firstLostElapsedNanos,
                    lastLost = current.lastLostElapsedNanos
                )
            )
        }
        episode = null
        backoff.onSuccess()
        drainPendingEvents()
        publish()
    }

    /** A point that can never be stored: counted, the first one visible at once, the total summarized at the end. */
    private suspend fun recordRejection(point: RawTrackPointEntity, error: Exception) {
        rejectedTotal++
        if (!rejectionLogged) {
            rejectionLogged = true
            Log.w(TAG, "raw point rejected by the database capture=$captureId error=${error::class.simpleName}")
            emit(event(EVENT_INSERT_FAILED, DiagnosticSeverity.ERROR, error::class.simpleName ?: "Exception", metadata = mapOf("permanent" to "true"), elapsedNanos = point.elapsedRealtimeNanos))
        }
    }

    private fun dataLoss(droppedOnOverflow: Int, rejected: Int, discardedAtStop: Int, firstLost: Long?, lastLost: Long?) = event(
        EVENT_DATA_LOSS, DiagnosticSeverity.ERROR, "POINTS_NOT_SAVED",
        metadata = buildMap {
            put("droppedOnOverflow", droppedOnOverflow.toString())
            put("rejectedPoints", rejected.toString())
            put("discardedAtStop", discardedAtStop.toString())
            lastLost?.let { put("lastLostElapsedRealtimeNanos", it.toString()) }
        },
        elapsedNanos = firstLost
    )

    private suspend fun publish() {
        val current = episode
        val next = when {
            current == null -> PersistenceState.HEALTHY
            current.storageFull || current.dropped > 0 || current.rejected > 0 -> PersistenceState(PersistenceLevel.CRITICAL, current.storageFull)
            else -> PersistenceState(PersistenceLevel.DEGRADED)
        }
        if (next == publishedState) return
        publishedState = next
        try {
            onStateChanged(next)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "persistence state listener failed (${error::class.simpleName}); recording continues")
        }
    }

    /** Tries to write [event] now; if the database is what is failing, it waits (bounded) and is retried. */
    private suspend fun emit(event: DiagnosticEventEntity) {
        drainPendingEvents()
        if (pendingEvents.isNotEmpty() || !tryInsert(event)) {
            if (pendingEvents.size >= MAX_PENDING_EVENTS) pendingEvents.removeFirst()
            pendingEvents.addLast(event)
        }
    }

    private suspend fun drainPendingEvents() {
        while (pendingEvents.isNotEmpty()) {
            if (!tryInsert(pendingEvents.first())) return
            pendingEvents.removeFirst()
        }
    }

    private suspend fun tryInsert(event: DiagnosticEventEntity): Boolean = try {
        diagnosticEventDao.insert(event)
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        false
    }

    private fun nowMs(): Long = clock.elapsedRealtimeNanos() / 1_000_000

    private fun event(
        type: String,
        severity: DiagnosticSeverity,
        reasonCode: String,
        metadata: Map<String, String> = emptyMap(),
        elapsedNanos: Long? = null
    ) = DiagnosticEventEntity(
        eventId = idGenerator.newId(),
        occurredAt = clock.wallClockMillis(),
        elapsedRealtimeNanos = elapsedNanos ?: clock.elapsedRealtimeNanos(),
        category = DiagnosticCategory.PERSISTENCE,
        eventType = type,
        severity = severity,
        source = "tracking-service",
        captureId = captureId,
        tripId = null,
        correlationId = null,
        stateBefore = null,
        stateAfter = null,
        reasonCode = reasonCode,
        metadata = metadata,
        appVersion = BuildConfig.VERSION_NAME,
        schemaVersion = 1,
        detectorVersion = DetectorVersion(0),
        locationProfileVersion = LocationProfileVersion(0),
        processingVersion = ProcessingVersion(0)
    )

    companion object {
        /**
         * About 20 minutes of fixes at the default 2 s interval, roughly 150 KB. An unvalidated
         * placeholder: F0.8 leaves the number to Phase 1 measurement.
         */
        const val DEFAULT_CAPACITY = 600
        private const val MAX_PENDING_EVENTS = 32
        private const val TAG = "RawPointWriter"

        /** The first insert failure of an outage (kept from `TRK-002`; now once per outage, not once per point). */
        const val EVENT_INSERT_FAILED = "RAW_TRACK_POINT_INSERT_FAILED"
        const val EVENT_BUFFER_OVERFLOW = "PERSISTENCE_BUFFER_OVERFLOW"
        const val EVENT_RECOVERED = "PERSISTENCE_RECOVERED"

        /** F0.10 §14.2's `DATA_LOSS_DETECTED`. */
        const val EVENT_DATA_LOSS = "DATA_LOSS_DETECTED"
    }
}

/** How much the rider should worry, in the terms of F0.10 §21: DEGRADED = still saving, just late; CRITICAL = data is being or has been lost. */
enum class PersistenceLevel { HEALTHY, DEGRADED, CRITICAL }

/**
 * REC-006: deliberately no counters - they would change on every fix and either flood the
 * notification or go stale on screen; the numbers live in the `PERSISTENCE_RECOVERED` and
 * `DATA_LOSS_DETECTED` events. [storageFull] says the phone itself is out of space, the one cause the
 * rider can act on.
 */
data class PersistenceState(
    val level: PersistenceLevel,
    val storageFull: Boolean = false
) {
    companion object {
        val HEALTHY = PersistenceState(PersistenceLevel.HEALTHY)
    }
}
