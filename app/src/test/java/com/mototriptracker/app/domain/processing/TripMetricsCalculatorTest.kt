package com.mototriptracker.app.domain.processing

import com.mototriptracker.app.core.database.entity.LocationGapEntity
import com.mototriptracker.app.core.database.entity.ManualPauseIntervalEntity
import com.mototriptracker.app.core.database.entity.PointAssessmentEntity
import com.mototriptracker.app.core.database.entity.ProcessedTrackPointEntity
import com.mototriptracker.app.core.database.entity.RawTrackPointEntity
import com.mototriptracker.app.core.database.entity.TripPartEntity
import com.mototriptracker.app.core.model.ProcessingVersion
import com.mototriptracker.app.core.model.TrackPointDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class TripMetricsCalculatorTest {

    private lateinit var calculator: TripMetricsCalculator
    private val version = ProcessingVersion(0)
    private val tripId = "trip-1"
    private val captureId = "capture-1"

    @Before
    fun setUp() {
        calculator = TripMetricsCalculator()
    }

    private fun rawPoint(
        sequenceNumber: Long,
        elapsedNanos: Long,
        speedMps: Float? = null,
        altitudeEllipsoidM: Double? = null,
        altitudeMslM: Double? = null,
        verticalAccuracyM: Float? = null
    ) = RawTrackPointEntity(
        captureId = captureId, sequenceNumber = sequenceNumber, capturedAt = elapsedNanos / 1_000_000,
        elapsedRealtimeNanos = elapsedNanos, receivedAtElapsedRealtimeNanos = elapsedNanos,
        latitude = 10.0, longitude = -20.0, horizontalAccuracyM = 5.0f,
        altitudeEllipsoidM = altitudeEllipsoidM, altitudeMslM = altitudeMslM, verticalAccuracyM = verticalAccuracyM,
        speedMps = speedMps, speedAccuracyMps = null, bearingDeg = null, bearingAccuracyDeg = null,
        provider = "fused", isMock = false, requestProfileId = "test-profile",
        callbackBatchId = null, detectorStateSnapshot = "TRACKING"
    )

    private fun processedPoint(
        orderIndex: Int,
        sequenceNumber: Long,
        latitude: Double,
        longitude: Double,
        gapBoundary: Boolean = false
    ) = ProcessedTrackPointEntity(
        tripId = tripId, processingVersion = version, orderIndex = orderIndex,
        latitude = latitude, longitude = longitude, sourceCaptureId = captureId,
        sourceSequenceNumber = sequenceNumber,
        pointRole = if (gapBoundary) ProcessingEngine.POINT_ROLE_GAP_BOUNDARY else null
    )

    private fun part(startNanos: Long, endNanos: Long?) = TripPartEntity(
        id = "part-1", tripId = tripId, captureId = captureId, orderIndex = 0,
        startElapsedRealtimeNanos = startNanos, endElapsedRealtimeNanos = endNanos,
        startSequenceNumber = null, endSequenceNumber = null
    )

    private fun assessment(sequenceNumber: Long, decision: TrackPointDecision) = PointAssessmentEntity(
        captureId = captureId, sequenceNumber = sequenceNumber, processingVersion = version,
        decision = decision, reasonCodes = decision.name
    )

    private fun pause(id: String, startNanos: Long, endNanos: Long?) = ManualPauseIntervalEntity(
        id = id, captureId = captureId, startedAt = startNanos / 1_000_000, endedAt = endNanos?.let { it / 1_000_000 },
        startElapsedRealtimeNanos = startNanos, endElapsedRealtimeNanos = endNanos,
        startReason = "USER_COMMAND", endReason = endNanos?.let { "USER_COMMAND" }
    )

    private fun expectedHaversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    @Test
    fun distanceIsTheSumOfHaversineDistancesBetweenConsecutiveProcessedPoints() {
        val points = listOf(
            processedPoint(0, 0, 10.0, -20.0),
            processedPoint(1, 1, 10.001, -20.001)
        )
        val result = ProcessingEngine.Result(
            assessments = listOf(assessment(0, TrackPointDecision.ACCEPTED), assessment(1, TrackPointDecision.ACCEPTED)),
            processedPoints = points,
            gaps = emptyList()
        )

        val stats = calculator.calculate(tripId, version, 0L, listOf(part(0L, 1_000_000_000L)), result, mapOf(captureId to listOf(rawPoint(0, 0L), rawPoint(1, 1_000_000_000L))))

        val expected = expectedHaversineMeters(10.0, -20.0, 10.001, -20.001)
        assertEquals(expected, stats.distanceM, 0.01)
    }

    @Test
    fun distanceExcludesTheEdgeLandingOnAGapBoundaryPoint() {
        val points = listOf(
            processedPoint(0, 0, 10.0, -20.0),
            processedPoint(1, 1, 11.0, -21.0, gapBoundary = true) // real distance away, but across a gap
        )
        val result = ProcessingEngine.Result(
            assessments = listOf(assessment(0, TrackPointDecision.ACCEPTED), assessment(1, TrackPointDecision.ACCEPTED)),
            processedPoints = points,
            gaps = listOf(
                LocationGapEntity(
                    id = "gap-1", tripId = tripId, processingVersion = version,
                    startedAt = 0L, endedAt = 40_000L, startSourceRef = "$captureId:0", endSourceRef = "$captureId:1",
                    durationMs = 40_000L, reasonCode = "GAP_NO_FIX"
                )
            )
        )

        val stats = calculator.calculate(tripId, version, 0L, listOf(part(0L, 40_000_000_000L)), result, mapOf(captureId to listOf(rawPoint(0, 0L), rawPoint(1, 40_000_000_000L))))

        assertEquals(0.0, stats.distanceM, 0.0)
    }

    @Test
    fun totalDurationIsSummedFromTripPartElapsedBoundsNotFromProcessedPoints() {
        val result = ProcessingEngine.Result(assessments = emptyList(), processedPoints = emptyList(), gaps = emptyList())

        val stats = calculator.calculate(tripId, version, 0L, listOf(part(1_000_000_000L, 6_000_000_000L)), result, emptyMap())

        assertEquals(5_000L, stats.totalDurationMs)
    }

    @Test
    fun totalDurationSumsMultiplePartsIndependently() {
        val result = ProcessingEngine.Result(assessments = emptyList(), processedPoints = emptyList(), gaps = emptyList())
        val partA = part(0L, 2_000_000_000L) // 2s
        val partB = TripPartEntity(
            id = "part-2", tripId = tripId, captureId = "capture-2", orderIndex = 1,
            startElapsedRealtimeNanos = 100_000_000_000L, endElapsedRealtimeNanos = 103_000_000_000L, // 3s, unrelated to partA's clock range
            startSequenceNumber = null, endSequenceNumber = null
        )

        val stats = calculator.calculate(tripId, version, 0L, listOf(partA, partB), result, emptyMap())

        assertEquals(5_000L, stats.totalDurationMs)
    }

    @Test
    fun manualPauseDurationIsZeroWithNoPausesAndMovingStoppedStayNull() {
        val result = ProcessingEngine.Result(assessments = emptyList(), processedPoints = emptyList(), gaps = emptyList())

        val stats = calculator.calculate(tripId, version, 0L, listOf(part(0L, 1_000_000_000L)), result, emptyMap())

        assertEquals(0L, stats.manualPauseDurationMs)
        assertNull(stats.movingDurationMs)
        assertNull(stats.stoppedDurationMs)
        assertNull(stats.averageMovingSpeedMps)
    }

    @Test
    fun manualPauseDurationSumsEveryClosedPauseAcrossCapturesWithoutChangingTotalDuration() {
        // TRK-003: totalDurationMs stays the full wall-clock span - a caller
        // wanting "riding time" computes totalDurationMs - manualPauseDurationMs
        // itself, this class doesn't redefine "total" to exclude pauses.
        val result = ProcessingEngine.Result(assessments = emptyList(), processedPoints = emptyList(), gaps = emptyList())
        val pausesByCapture = mapOf(
            captureId to listOf(
                pause(id = "pause-1", startNanos = 1_000_000_000L, endNanos = 4_000_000_000L), // 3s
                pause(id = "pause-2", startNanos = 6_000_000_000L, endNanos = 7_500_000_000L) // 1.5s
            )
        )

        val stats = calculator.calculate(
            tripId, version, 0L, listOf(part(0L, 10_000_000_000L)), result, emptyMap(), pausesByCapture
        )

        assertEquals(4_500L, stats.manualPauseDurationMs)
        assertEquals(10_000L, stats.totalDurationMs)
    }

    @Test(expected = IllegalStateException::class)
    fun manualPauseDurationRejectsAnOpenPauseAsAnInconsistentState() {
        // finishCapture always closes an open pause before a Trip can exist,
        // so an open one reaching this class would mean that invariant broke
        // somewhere - fail loudly rather than silently under-counting.
        val result = ProcessingEngine.Result(assessments = emptyList(), processedPoints = emptyList(), gaps = emptyList())
        val pausesByCapture = mapOf(captureId to listOf(pause(id = "pause-1", startNanos = 1_000_000_000L, endNanos = null)))

        calculator.calculate(tripId, version, 0L, listOf(part(0L, 10_000_000_000L)), result, emptyMap(), pausesByCapture)
    }

    @Test
    fun maxSpeedIsTheMaximumReportedSpeedAmongNonGapBoundaryPoints() {
        val points = listOf(
            processedPoint(0, 0, 10.0, -20.0),
            processedPoint(1, 1, 10.001, -20.001),
            processedPoint(2, 2, 10.002, -20.002)
        )
        val result = ProcessingEngine.Result(
            assessments = points.map { assessment(it.sourceSequenceNumber!!, TrackPointDecision.ACCEPTED) },
            processedPoints = points,
            gaps = emptyList()
        )
        val rawPoints = listOf(
            rawPoint(0, 0L, speedMps = 5.0f),
            rawPoint(1, 1_000_000_000L, speedMps = 30.0f),
            rawPoint(2, 2_000_000_000L, speedMps = 8.0f)
        )

        val stats = calculator.calculate(tripId, version, 0L, listOf(part(0L, 2_000_000_000L)), result, mapOf(captureId to rawPoints))

        assertEquals(30.0, stats.maxSpeedMps!!, 0.001)
    }

    @Test
    fun maxSpeedExcludesAGapBoundaryPointsReportedSpeedEvenIfItsTheHighest() {
        val points = listOf(
            processedPoint(0, 0, 10.0, -20.0),
            processedPoint(1, 1, 10.001, -20.001, gapBoundary = true)
        )
        val result = ProcessingEngine.Result(
            assessments = points.map { assessment(it.sourceSequenceNumber!!, TrackPointDecision.ACCEPTED) },
            processedPoints = points,
            gaps = listOf(
                LocationGapEntity(
                    id = "gap-1", tripId = tripId, processingVersion = version,
                    startedAt = 0L, endedAt = 40_000L, startSourceRef = "$captureId:0", endSourceRef = "$captureId:1",
                    durationMs = 40_000L, reasonCode = "GAP_NO_FIX"
                )
            )
        )
        val rawPoints = listOf(
            rawPoint(0, 0L, speedMps = 5.0f),
            rawPoint(1, 40_000_000_000L, speedMps = 200.0f) // implausible post-gap spike
        )

        val stats = calculator.calculate(tripId, version, 0L, listOf(part(0L, 40_000_000_000L)), result, mapOf(captureId to rawPoints))

        assertEquals(5.0, stats.maxSpeedMps!!, 0.001)
    }

    @Test
    fun maxSpeedIsNullWhenNoPointReportedARealSpeed() {
        val points = listOf(processedPoint(0, 0, 10.0, -20.0))
        val result = ProcessingEngine.Result(
            assessments = listOf(assessment(0, TrackPointDecision.ACCEPTED)),
            processedPoints = points,
            gaps = emptyList()
        )

        val stats = calculator.calculate(tripId, version, 0L, listOf(part(0L, 1_000_000_000L)), result, mapOf(captureId to listOf(rawPoint(0, 0L, speedMps = null))))

        assertNull(stats.maxSpeedMps)
    }

    @Test
    fun averageSpeedIsDistanceOverTotalDuration() {
        val points = listOf(
            processedPoint(0, 0, 0.0, 0.0),
            processedPoint(1, 1, 0.0, 0.001) // ~111.2m at the equator
        )
        val result = ProcessingEngine.Result(
            assessments = points.map { assessment(it.sourceSequenceNumber!!, TrackPointDecision.ACCEPTED) },
            processedPoints = points,
            gaps = emptyList()
        )

        val stats = calculator.calculate(tripId, version, 0L, listOf(part(0L, 10_000_000_000L)), result, mapOf(captureId to listOf(rawPoint(0, 0L), rawPoint(1, 10_000_000_000L))))

        assertEquals(stats.distanceM / 10.0, stats.averageSpeedMps!!, 0.0001)
    }

    @Test
    fun averageSpeedIsNullWhenTotalDurationIsZero() {
        val result = ProcessingEngine.Result(assessments = emptyList(), processedPoints = emptyList(), gaps = emptyList())

        val stats = calculator.calculate(tripId, version, 0L, listOf(part(0L, 0L)), result, emptyMap())

        assertNull(stats.averageSpeedMps)
    }

    @Test
    fun pointCountsMatchTheProcessingResultDirectly() {
        val assessments = listOf(
            assessment(0, TrackPointDecision.ACCEPTED),
            assessment(1, TrackPointDecision.REJECTED),
            assessment(2, TrackPointDecision.ACCEPTED)
        )
        val processed = listOf(processedPoint(0, 0, 10.0, -20.0), processedPoint(1, 2, 10.001, -20.001))
        val gaps = listOf(
            LocationGapEntity("gap-1", tripId, version, 0L, 1L, null, null, 1L, null)
        )
        val result = ProcessingEngine.Result(assessments, processed, gaps)

        val stats = calculator.calculate(tripId, version, 0L, listOf(part(0L, 1_000_000_000L)), result, mapOf(captureId to listOf(rawPoint(0, 0L), rawPoint(2, 1_000_000_000L))))

        assertEquals(2, stats.validPointCount)
        assertEquals(0, stats.suspectPointCount)
        assertEquals(1, stats.rejectedPointCount)
        assertEquals(1, stats.gapCount)
    }

    @Test(expected = IllegalStateException::class)
    fun throwsIfATripPartHasNoEndElapsedRealtimeNanos() {
        val result = ProcessingEngine.Result(assessments = emptyList(), processedPoints = emptyList(), gaps = emptyList())

        calculator.calculate(tripId, version, 0L, listOf(part(0L, null)), result, emptyMap())
    }

    // --- PRC-003: elevation -----------------------------------------------

    @Test
    fun elevationRangeAndAscentComeFromMslAltitudeWhenAvailable() {
        val points = listOf(processedPoint(0, 0, 10.0, -20.0), processedPoint(1, 1, 10.001, -20.001), processedPoint(2, 2, 10.002, -20.002))
        val result = ProcessingEngine.Result(
            assessments = points.map { assessment(it.sourceSequenceNumber!!, TrackPointDecision.ACCEPTED) },
            processedPoints = points,
            gaps = emptyList()
        )
        val rawPoints = listOf(
            rawPoint(0, 0L, altitudeMslM = 100.0),
            rawPoint(1, 1_000_000_000L, altitudeMslM = 110.0),
            rawPoint(2, 2_000_000_000L, altitudeMslM = 120.0)
        )

        val stats = calculator.calculate(tripId, version, 0L, listOf(part(0L, 2_000_000_000L)), result, mapOf(captureId to rawPoints))

        assertEquals(100.0, stats.minElevationM!!, 0.0001)
        assertEquals(120.0, stats.maxElevationM!!, 0.0001)
        assertEquals(20.0, stats.ascentM!!, 0.0001)
        assertEquals(0.0, stats.descentM!!, 0.0001)
    }

    @Test
    fun elevationFallsBackToEllipsoidAltitudeWhenMslIsMissing() {
        val points = listOf(processedPoint(0, 0, 10.0, -20.0))
        val result = ProcessingEngine.Result(
            assessments = listOf(assessment(0, TrackPointDecision.ACCEPTED)),
            processedPoints = points,
            gaps = emptyList()
        )
        val rawPoints = listOf(rawPoint(0, 0L, altitudeEllipsoidM = 250.0, altitudeMslM = null))

        val stats = calculator.calculate(tripId, version, 0L, listOf(part(0L, 1_000_000_000L)), result, mapOf(captureId to rawPoints))

        assertEquals(250.0, stats.minElevationM!!, 0.0001)
        assertEquals(250.0, stats.maxElevationM!!, 0.0001)
    }

    @Test
    fun pointWithPoorVerticalAccuracyIsExcludedFromTheElevationRange() {
        val points = listOf(processedPoint(0, 0, 10.0, -20.0), processedPoint(1, 1, 10.001, -20.001))
        val result = ProcessingEngine.Result(
            assessments = points.map { assessment(it.sourceSequenceNumber!!, TrackPointDecision.ACCEPTED) },
            processedPoints = points,
            gaps = emptyList()
        )
        val rawPoints = listOf(
            rawPoint(0, 0L, altitudeMslM = 100.0, verticalAccuracyM = 5.0f),
            // Default ElevationProfile.maxVerticalAccuracyM is 20.0 - this point's own reported
            // accuracy is known and worse than that, so it must not distort the range.
            rawPoint(1, 1_000_000_000L, altitudeMslM = 9_999.0, verticalAccuracyM = 50.0f)
        )

        val stats = calculator.calculate(tripId, version, 0L, listOf(part(0L, 1_000_000_000L)), result, mapOf(captureId to rawPoints))

        assertEquals(100.0, stats.minElevationM!!, 0.0001)
        assertEquals(100.0, stats.maxElevationM!!, 0.0001)
    }
}
