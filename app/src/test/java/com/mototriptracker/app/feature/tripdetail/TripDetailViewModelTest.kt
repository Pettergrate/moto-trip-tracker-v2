package com.mototriptracker.app.feature.tripdetail

import com.mototriptracker.app.core.common.FakeClock
import com.mototriptracker.app.core.common.FakeIdGenerator
import com.mototriptracker.app.core.database.MotoTripDatabase
import com.mototriptracker.app.core.database.entity.DiagnosticEventEntity
import com.mototriptracker.app.core.database.entity.ProcessedTrackPointEntity
import com.mototriptracker.app.core.database.entity.TripCaptureEntity
import com.mototriptracker.app.core.database.entity.TripEntity
import com.mototriptracker.app.core.database.entity.TripPartEntity
import com.mototriptracker.app.core.database.entity.TripStatisticsEntity
import com.mototriptracker.app.core.model.CaptureStatus
import com.mototriptracker.app.core.model.DetectorVersion
import com.mototriptracker.app.core.model.DiagnosticCategory
import com.mototriptracker.app.core.model.DiagnosticSeverity
import com.mototriptracker.app.core.model.LocationProfileVersion
import com.mototriptracker.app.core.model.ProcessingVersion
import com.mototriptracker.app.core.model.StartSource
import com.mototriptracker.app.core.model.TripStatus
import com.mototriptracker.app.feature.common.fallbackTripName
import com.mototriptracker.app.feature.common.formatDateTime
import com.mototriptracker.app.testing.FakeProcessingScheduler
import com.mototriptracker.app.testing.TestDatabaseFactory
import com.mototriptracker.app.tracking.persistence.RawPointWriter
import com.mototriptracker.app.tracking.processing.TripProcessingWorker
import com.mototriptracker.app.worker.TripMerger
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class TripDetailViewModelTest {

    private lateinit var db: MotoTripDatabase
    private lateinit var viewModel: TripDetailViewModel
    private lateinit var processingScheduler: FakeProcessingScheduler
    private val clock = FakeClock(wallMillis = 10_000L, elapsedNanos = 10_000L)

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        db = TestDatabaseFactory.createInMemory()
        processingScheduler = FakeProcessingScheduler()
        val tripMerger = TripMerger(
            database = db,
            tripDao = db.tripDao(),
            tripPartDao = db.tripPartDao(),
            tripCaptureDao = db.tripCaptureDao(),
            tripEditOperationDao = db.tripEditOperationDao(),
            tripLineageLinkDao = db.tripLineageLinkDao(),
            processingScheduler = processingScheduler,
            clock = clock,
            idGenerator = FakeIdGenerator("merge")
        )
        viewModel = TripDetailViewModel(
            tripDao = db.tripDao(),
            tripStatisticsDao = db.tripStatisticsDao(),
            processedTrackPointDao = db.processedTrackPointDao(),
            tripPartDao = db.tripPartDao(),
            tripCaptureDao = db.tripCaptureDao(),
            diagnosticEventDao = db.diagnosticEventDao(),
            tripMerger = tripMerger,
            clock = clock
        )
    }

    @After
    fun tearDown() {
        // A load() launched by the test can still be running on Dispatchers.Main when it ends; resetting Main
        // underneath it raised "Dispatchers.Main is used concurrently with setting it" once in a full-suite run.
        viewModel.viewModelScope.coroutineContext[Job]?.cancel()
        db.close()
        Dispatchers.resetMain()
    }

    private fun trip(id: String, createdAt: Long, name: String? = null) = TripEntity(
        id = id,
        status = TripStatus.COMPLETED,
        name = name,
        isFavorite = true,
        motorcycleId = null,
        routeId = null,
        notes = null,
        createdAt = createdAt,
        updatedAt = createdAt,
        deletedAt = null
    )

    private fun statistics(
        tripId: String,
        computedAt: Long = 1_000L,
        startElevationM: Double? = null,
        endElevationM: Double? = null,
        rejectedPointCount: Int = 0,
        gapCount: Int = 0
    ) = TripStatisticsEntity(
        tripId = tripId,
        processingVersion = TripProcessingWorker.CURRENT_PROCESSING_VERSION,
        computedAt = computedAt,
        distanceM = 5_000.0,
        totalDurationMs = 1_800_000L,
        movingDurationMs = 1_500_000L,
        stoppedDurationMs = 300_000L,
        manualPauseDurationMs = 60_000L,
        maxSpeedMps = 27.0,
        averageSpeedMps = 8.0,
        averageMovingSpeedMps = 9.5,
        minElevationM = null,
        maxElevationM = null,
        startElevationM = startElevationM,
        endElevationM = endElevationM,
        ascentM = null,
        descentM = null,
        validPointCount = 100,
        suspectPointCount = 0,
        rejectedPointCount = rejectedPointCount,
        gapCount = gapCount
    )

    @Test
    fun loadedStateReflectsTheTripAndItsStatistics() = runBlocking {
        db.tripDao().insert(trip("trip-1", createdAt = 5_000L))
        db.tripStatisticsDao().upsert(statistics("trip-1"))

        viewModel.load("trip-1")

        val state = withTimeout(5_000) {
            viewModel.uiState.first { it is TripDetailUiState.Loaded && it.distanceMeters != null }
        } as TripDetailUiState.Loaded

        assertEquals("trip-1", state.tripId)
        assertFalse("no custom name was set - must fall back", state.isUserNamed)
        assertTrue(state.isFavorite)
        assertEquals(5_000.0, state.distanceMeters!!, 0.0001)
        assertEquals(1_800_000L, state.totalDurationMs)
        assertEquals(60_000L, state.manualPauseDurationMs)
    }

    @Test
    fun loadedStateHasNoCalculatedAtLabelOrQualityNoteBeforeStatisticsExist() = runBlocking {
        db.tripDao().insert(trip("trip-1", createdAt = 5_000L))

        viewModel.load("trip-1")

        val state = withTimeout(5_000) {
            viewModel.uiState.first { it is TripDetailUiState.Loaded }
        } as TripDetailUiState.Loaded
        assertNull("nothing to date yet - the footer must not appear", state.calculatedAtLabel)
        assertNull(state.qualityNote)
    }

    @Test
    fun aTripWithNoStatisticsIsFlaggedAsStillCalculatingAndClearsOnceTheyExist() = runBlocking {
        db.tripDao().insert(trip("trip-1", createdAt = 5_000L))
        viewModel.load("trip-1")

        val before = withTimeout(5_000) { viewModel.uiState.first { it is TripDetailUiState.Loaded } } as TripDetailUiState.Loaded
        assertTrue("EDT-004: unknown numbers are on their way, not zero", before.isCalculating)

        db.tripStatisticsDao().upsert(statistics("trip-1"))

        val after = withTimeout(5_000) { viewModel.uiState.first { it is TripDetailUiState.Loaded && !it.isCalculating } } as TripDetailUiState.Loaded
        assertFalse(after.isCalculating)
    }

    @Test
    fun loadedStateExposesCalculatedAtLabelOnceStatisticsExist() = runBlocking {
        db.tripDao().insert(trip("trip-1", createdAt = 5_000L))
        db.tripStatisticsDao().upsert(statistics("trip-1", computedAt = 42_000L))

        viewModel.load("trip-1")

        val state = withTimeout(5_000) {
            viewModel.uiState.first { it is TripDetailUiState.Loaded && it.calculatedAtLabel != null }
        } as TripDetailUiState.Loaded
        assertEquals(formatDateTime(42_000L), state.calculatedAtLabel)
    }

    @Test
    fun loadedStateHasNoQualityNoteForACleanTrip() = runBlocking {
        db.tripDao().insert(trip("trip-1", createdAt = 5_000L))
        db.tripStatisticsDao().upsert(statistics("trip-1", rejectedPointCount = 0, gapCount = 0))

        viewModel.load("trip-1")

        val state = withTimeout(5_000) {
            viewModel.uiState.first { it is TripDetailUiState.Loaded && it.calculatedAtLabel != null }
        } as TripDetailUiState.Loaded
        assertNull("a clean trip gets no decorative quality badge", state.qualityNote)
    }

    @Test
    fun loadedStateShowsAQualityNoteWhenPointsWereExcludedOrGapsExist() = runBlocking {
        db.tripDao().insert(trip("trip-1", createdAt = 5_000L))
        db.tripStatisticsDao().upsert(statistics("trip-1", rejectedPointCount = 3, gapCount = 1))

        viewModel.load("trip-1")

        val state = withTimeout(5_000) {
            viewModel.uiState.first { it is TripDetailUiState.Loaded && it.qualityNote != null }
        } as TripDetailUiState.Loaded
        assertEquals("3 GPS points excluded · 1 signal gap", state.qualityNote)
    }

    @Test
    fun loadedStateExposesStartAndEndElevation() = runBlocking {
        db.tripDao().insert(trip("trip-1", createdAt = 5_000L))
        db.tripStatisticsDao().upsert(statistics("trip-1", startElevationM = 100.0, endElevationM = 140.0))

        viewModel.load("trip-1")

        val state = withTimeout(5_000) {
            viewModel.uiState.first { it is TripDetailUiState.Loaded && it.startElevationM != null }
        } as TripDetailUiState.Loaded
        assertEquals(100.0, state.startElevationM!!, 0.0001)
        assertEquals(140.0, state.endElevationM!!, 0.0001)
    }

    @Test
    fun loadedStateExposesTheSimplifiedRouteFromProcessedTrackPoints() = runBlocking {
        db.tripDao().insert(trip("trip-1", createdAt = 5_000L))
        // A straight line of collinear points - MAP-001's simplifyRoute
        // should collapse it to just the two endpoints.
        val points = (0..9).map { i ->
            ProcessedTrackPointEntity(
                tripId = "trip-1",
                processingVersion = TripProcessingWorker.CURRENT_PROCESSING_VERSION,
                orderIndex = i,
                latitude = 10.0,
                longitude = -20.0 + i * 0.0001,
                sourceCaptureId = "capture-1",
                sourceSequenceNumber = i.toLong(),
                pointRole = null
            )
        }
        db.processedTrackPointDao().insertAll(points)

        viewModel.load("trip-1")

        val state = withTimeout(5_000) {
            viewModel.uiState.first { it is TripDetailUiState.Loaded && it.routePoints.isNotEmpty() }
        } as TripDetailUiState.Loaded

        assertEquals(2, state.routePoints.size)
        assertEquals(10.0, state.routePoints.first().latitude, 0.0001)
        assertEquals(10.0, state.routePoints.last().latitude, 0.0001)
    }

    @Test
    fun stateBecomesNotFoundForAnUnknownTripId() = runBlocking {
        viewModel.load("does-not-exist")

        val state = withTimeout(5_000) {
            viewModel.uiState.first { it !is TripDetailUiState.Loading }
        }

        assertEquals(TripDetailUiState.NotFound, state)
    }

    @Test
    fun onRenamePersistsTheNewNameAndReflectsBackReactively() = runBlocking {
        db.tripDao().insert(trip("trip-1", createdAt = 5_000L))
        viewModel.load("trip-1")
        withTimeout(5_000) { viewModel.uiState.first { it is TripDetailUiState.Loaded } }

        viewModel.onRename("Coastal loop")

        val renamed = withTimeout(5_000) {
            viewModel.uiState.first { it is TripDetailUiState.Loaded && it.isUserNamed }
        } as TripDetailUiState.Loaded
        assertEquals("Coastal loop", renamed.displayName)
        assertEquals(clock.wallClockMillis(), db.tripDao().findById("trip-1")?.updatedAt)
    }

    @Test
    fun onRenameWithBlankNameRevertsToTheFallbackName() = runBlocking {
        db.tripDao().insert(trip("trip-1", createdAt = 5_000L, name = "Coastal loop"))
        viewModel.load("trip-1")
        withTimeout(5_000) { viewModel.uiState.first { it is TripDetailUiState.Loaded && it.isUserNamed } }

        viewModel.onRename("   ")

        val reverted = withTimeout(5_000) {
            viewModel.uiState.first { it is TripDetailUiState.Loaded && !it.isUserNamed }
        } as TripDetailUiState.Loaded
        assertNull(db.tripDao().findById("trip-1")?.name)
        assertFalse(reverted.isUserNamed)
    }

    @Test
    fun onToggleFavoritePersistsAndReflectsBackReactively() = runBlocking {
        db.tripDao().insert(trip("trip-1", createdAt = 5_000L).copy(isFavorite = false))
        viewModel.load("trip-1")
        withTimeout(5_000) { viewModel.uiState.first { it is TripDetailUiState.Loaded && !it.isFavorite } }

        viewModel.onToggleFavorite()

        val favorited = withTimeout(5_000) {
            viewModel.uiState.first { it is TripDetailUiState.Loaded && it.isFavorite }
        } as TripDetailUiState.Loaded
        assertTrue(favorited.isFavorite)
        assertEquals(true, db.tripDao().findById("trip-1")?.isFavorite)
        assertEquals(clock.wallClockMillis(), db.tripDao().findById("trip-1")?.updatedAt)

        viewModel.onToggleFavorite()

        withTimeout(5_000) { viewModel.uiState.first { it is TripDetailUiState.Loaded && !it.isFavorite } }
        assertEquals(false, db.tripDao().findById("trip-1")?.isFavorite)
    }

    private fun capture(id: String, startedAt: Long) = TripCaptureEntity(
        id = id, status = CaptureStatus.COMPLETED, startedAt = startedAt, endedAt = startedAt + 1_000L,
        startElapsedRealtimeNanos = 0L, endElapsedRealtimeNanos = 1_000_000_000L, localTimeZoneId = "UTC",
        startSource = StartSource.MANUAL, endSource = null,
        detectorVersion = DetectorVersion(0), locationProfileVersion = LocationProfileVersion(0),
        createdAt = startedAt, updatedAt = startedAt
    )

    private fun part(id: String, tripId: String, captureId: String) = TripPartEntity(
        id = id, tripId = tripId, captureId = captureId, orderIndex = 0,
        startElapsedRealtimeNanos = 0L, endElapsedRealtimeNanos = 1_000_000_000L,
        startSequenceNumber = null, endSequenceNumber = null
    )

    @Test
    fun loadedStateExposesAdjacentTripsAsMergeCandidates() = runBlocking {
        db.tripDao().insert(trip("previous", createdAt = 1_000L, name = "Coastal loop"))
        db.tripDao().insert(trip("current", createdAt = 2_000L))
        db.tripDao().insert(trip("next", createdAt = 3_000L))

        viewModel.load("current")

        val state = withTimeout(5_000) {
            viewModel.uiState.first { it is TripDetailUiState.Loaded && it.previousTripCandidate != null && it.nextTripCandidate != null }
        } as TripDetailUiState.Loaded
        assertEquals("previous", state.previousTripCandidate?.tripId)
        assertEquals("Coastal loop", state.previousTripCandidate?.label)
        assertEquals("next", state.nextTripCandidate?.tripId)
        assertEquals(fallbackTripName(3_000L), state.nextTripCandidate?.label)
    }

    @Test
    fun loadedStateHasNoMergeCandidatesWhenNoAdjacentTripExists() = runBlocking {
        db.tripDao().insert(trip("only-trip", createdAt = 5_000L))

        viewModel.load("only-trip")

        val state = withTimeout(5_000) {
            viewModel.uiState.first { it is TripDetailUiState.Loaded }
        } as TripDetailUiState.Loaded
        assertNull(state.previousTripCandidate)
        assertNull(state.nextTripCandidate)
    }

    @Test
    fun mergeWithPreviousCreatesANewTripAndSupersedesBoth() = runBlocking {
        db.tripDao().insert(trip("earlier", createdAt = 1_000L))
        db.tripDao().insert(trip("current", createdAt = 2_000L))
        db.tripCaptureDao().insert(capture("cap-earlier", startedAt = 100L))
        db.tripCaptureDao().insert(capture("cap-current", startedAt = 200L))
        db.tripPartDao().insert(part("part-earlier", "earlier", "cap-earlier"))
        db.tripPartDao().insert(part("part-current", "current", "cap-current"))
        viewModel.load("current")
        withTimeout(5_000) { viewModel.uiState.first { it is TripDetailUiState.Loaded && it.previousTripCandidate != null } }

        val success = viewModel.mergeWithPrevious()

        assertTrue(success)
        assertEquals(TripStatus.SUPERSEDED, db.tripDao().findById("earlier")?.status)
        assertEquals(TripStatus.SUPERSEDED, db.tripDao().findById("current")?.status)
        assertEquals(1, processingScheduler.enqueuedRequests.size)
    }

    @Test
    fun mergeWithPreviousReturnsFalseWhenThereIsNoPreviousCandidate() = runBlocking {
        db.tripDao().insert(trip("only-trip", createdAt = 5_000L))
        viewModel.load("only-trip")
        withTimeout(5_000) { viewModel.uiState.first { it is TripDetailUiState.Loaded } }

        val success = viewModel.mergeWithPrevious()

        assertFalse(success)
        assertEquals(TripStatus.COMPLETED, db.tripDao().findById("only-trip")?.status)
    }

    @Test
    fun aTripWhoseCaptureWasSealedAsInterruptedIsFlaggedAsPossiblyIncomplete() = runBlocking {
        db.tripDao().insert(trip("interrupted", createdAt = 5_000L))
        db.tripCaptureDao().insert(capture("cap-x", startedAt = 100L).copy(status = CaptureStatus.ABORTED))
        db.tripPartDao().insert(part("part-x", "interrupted", "cap-x"))
        db.tripDao().insert(trip("normal", createdAt = 6_000L))
        db.tripCaptureDao().insert(capture("cap-y", startedAt = 200L))
        db.tripPartDao().insert(part("part-y", "normal", "cap-y"))

        viewModel.load("interrupted")
        val flagged = withTimeout(5_000) { viewModel.uiState.first { it is TripDetailUiState.Loaded && it.wasInterrupted } } as TripDetailUiState.Loaded
        assertTrue(flagged.wasInterrupted)

        viewModel.load("normal")
        val clean = withTimeout(5_000) { viewModel.uiState.first { it is TripDetailUiState.Loaded && it.tripId == "normal" } } as TripDetailUiState.Loaded
        assertFalse("a complete recording must not carry the warning", clean.wasInterrupted)
    }

    /** REC-006: points that could not be saved are a fact about the trip, not just a diagnostic. */
    @Test
    fun aTripWhoseRecordingLostPointsIsFlaggedAndACleanOneIsNot() = runBlocking {
        db.tripDao().insert(trip("lossy", createdAt = 5_000L))
        db.tripCaptureDao().insert(capture("cap-l", startedAt = 100L))
        db.tripPartDao().insert(part("part-l", "lossy", "cap-l"))
        db.diagnosticEventDao().insert(
            DiagnosticEventEntity(
                eventId = "loss-1", occurredAt = 1L, elapsedRealtimeNanos = 1L, category = DiagnosticCategory.PERSISTENCE,
                eventType = RawPointWriter.EVENT_DATA_LOSS, severity = DiagnosticSeverity.ERROR, source = "test",
                captureId = "cap-l", tripId = null, correlationId = null, stateBefore = null, stateAfter = null,
                reasonCode = "POINTS_NOT_SAVED", metadata = emptyMap(), appVersion = "test", schemaVersion = 1,
                detectorVersion = DetectorVersion(0), locationProfileVersion = LocationProfileVersion(0),
                processingVersion = ProcessingVersion(0)
            )
        )
        db.tripDao().insert(trip("clean", createdAt = 6_000L))
        db.tripCaptureDao().insert(capture("cap-c", startedAt = 200L))
        db.tripPartDao().insert(part("part-c", "clean", "cap-c"))

        viewModel.load("lossy")
        val flagged = withTimeout(5_000) { viewModel.uiState.first { it is TripDetailUiState.Loaded && it.hadDataLoss } } as TripDetailUiState.Loaded
        assertTrue(flagged.hadDataLoss)

        viewModel.load("clean")
        val clean = withTimeout(5_000) { viewModel.uiState.first { it is TripDetailUiState.Loaded && it.tripId == "clean" } } as TripDetailUiState.Loaded
        assertFalse(clean.hadDataLoss)
    }

    @Test
    fun onTrashSoftDeletesTheTrip() = runBlocking {
        db.tripDao().insert(trip("trip-1", createdAt = 5_000L))
        viewModel.load("trip-1")
        withTimeout(5_000) { viewModel.uiState.first { it is TripDetailUiState.Loaded } }

        viewModel.onTrash()

        val trashed = db.tripDao().findById("trip-1")
        assertEquals(TripStatus.TRASHED, trashed?.status)
        assertEquals(clock.wallClockMillis(), trashed?.deletedAt)
        assertEquals(clock.wallClockMillis(), trashed?.updatedAt)
    }
}
