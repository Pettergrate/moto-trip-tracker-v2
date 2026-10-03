package com.mototriptracker.app.domain.processing

import com.mototriptracker.app.core.common.FakeIdGenerator
import com.mototriptracker.app.core.database.entity.RawTrackPointEntity
import com.mototriptracker.app.core.database.entity.TripPartEntity
import com.mototriptracker.app.core.model.ProcessingVersion
import com.mototriptracker.app.core.model.TrackPointDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ProcessingEngineTest {

    private lateinit var engine: ProcessingEngine
    private val version = ProcessingVersion(0)

    @Before
    fun setUp() {
        engine = ProcessingEngine(FakeIdGenerator(prefix = "gap"))
    }

    private fun point(
        captureId: String = "capture-1",
        sequenceNumber: Long,
        elapsedNanos: Long,
        capturedAt: Long = elapsedNanos / 1_000_000,
        latitude: Double = 10.0,
        isApproximateLocation: Boolean? = null,
        accuracyM: Float? = null
    ) = RawTrackPointEntity(
        captureId = captureId,
        sequenceNumber = sequenceNumber,
        capturedAt = capturedAt,
        elapsedRealtimeNanos = elapsedNanos,
        receivedAtElapsedRealtimeNanos = elapsedNanos,
        latitude = latitude,
        longitude = -20.0,
        horizontalAccuracyM = accuracyM ?: if (isApproximateLocation == true) 2000.0f else 5.0f,
        altitudeEllipsoidM = null,
        altitudeMslM = null,
        verticalAccuracyM = null,
        speedMps = null,
        speedAccuracyMps = null,
        bearingDeg = null,
        bearingAccuracyDeg = null,
        provider = "fused",
        isMock = false,
        requestProfileId = "test-profile",
        callbackBatchId = null,
        detectorStateSnapshot = "TRACKING",
        isApproximateLocation = isApproximateLocation
    )

    private fun part(
        captureId: String = "capture-1",
        orderIndex: Int = 0,
        startSequenceNumber: Long? = null,
        endSequenceNumber: Long? = null
    ) = TripPartEntity(
        id = "part-$captureId-$orderIndex",
        tripId = "trip-1",
        captureId = captureId,
        orderIndex = orderIndex,
        startElapsedRealtimeNanos = 0L,
        endElapsedRealtimeNanos = null,
        startSequenceNumber = startSequenceNumber,
        endSequenceNumber = endSequenceNumber
    )

    /** REC-005 follow-up: found on the phone - one 2000 m fix taken with only approximate location allowed sat ~920 m from the real position. */
    @Test
    fun aFixTakenWithOnlyApproximateLocationIsRejectedAndNeverEntersTheRoute() {
        val points = listOf(
            point(sequenceNumber = 0, elapsedNanos = 1_000_000_000L),
            point(sequenceNumber = 1, elapsedNanos = 3_000_000_000L, latitude = 10.008, isApproximateLocation = true),
            point(sequenceNumber = 2, elapsedNanos = 5_000_000_000L)
        )

        val result = engine.process("trip-1", version, listOf(part()), mapOf("capture-1" to points))

        val approximate = result.assessments.single { it.sequenceNumber == 1L }
        assertEquals(TrackPointDecision.REJECTED, approximate.decision)
        assertEquals(ProcessingEngine.REASON_APPROXIMATE_LOCATION, approximate.reasonCodes)
        assertEquals("the kilometre-away block is not part of the route", listOf(0L, 2L), result.processedPoints.map { it.sourceSequenceNumber })
    }

    /** An approximate fix is not evidence of a live signal: it must not paper over a real gap. */
    @Test
    fun anApproximateFixInTheMiddleOfASilenceDoesNotHideTheGap() {
        val points = listOf(
            point(sequenceNumber = 0, elapsedNanos = 1_000_000_000L),
            point(sequenceNumber = 1, elapsedNanos = 21_000_000_000L, isApproximateLocation = true),
            point(sequenceNumber = 2, elapsedNanos = 41_000_000_000L)
        )

        val result = engine.process("trip-1", version, listOf(part()), mapOf("capture-1" to points))

        assertEquals("one 40 s gap between the two usable fixes, not two 20 s stretches", 1, result.gaps.size)
        assertEquals(40_000L, result.gaps.single().durationMs)
    }

    /** Every point recorded before schema v3 has an unknown marker (null) and must behave exactly as before. */
    @Test
    fun aPointWithAnUnknownMarkerIsTreatedLikeAnyOtherPoint() {
        val points = listOf(
            point(sequenceNumber = 0, elapsedNanos = 1_000_000_000L, isApproximateLocation = null),
            point(sequenceNumber = 1, elapsedNanos = 3_000_000_000L, isApproximateLocation = false)
        )

        val result = engine.process("trip-1", version, listOf(part()), mapOf("capture-1" to points))

        assertEquals(listOf(TrackPointDecision.ACCEPTED, TrackPointDecision.ACCEPTED), result.assessments.map { it.decision })
    }

    @Test
    fun firstPointIsAlwaysAcceptedWithNoPreviousEvidenceToCompareAgainst() {
        val points = listOf(point(sequenceNumber = 0, elapsedNanos = 1_000_000_000L))

        val result = engine.process("trip-1", version, listOf(part()), mapOf("capture-1" to points))

        assertEquals(1, result.assessments.size)
        assertEquals(TrackPointDecision.ACCEPTED, result.assessments.single().decision)
        assertEquals(1, result.processedPoints.size)
        assertNull(result.processedPoints.single().pointRole)
        assertTrue(result.gaps.isEmpty())
    }

    @Test
    fun pointsWithIncreasingTimestampsAtNormalIntervalsAreAllAcceptedWithContinuousOrderIndex() {
        val points = (0..2).map { i -> point(sequenceNumber = i.toLong(), elapsedNanos = i * 2_000_000_000L) }

        val result = engine.process("trip-1", version, listOf(part()), mapOf("capture-1" to points))

        assertTrue(result.assessments.all { it.decision == TrackPointDecision.ACCEPTED })
        assertEquals(listOf(0, 1, 2), result.processedPoints.map { it.orderIndex })
        assertTrue(result.gaps.isEmpty())
    }

    @Test
    fun outOfOrderPointIsRejectedAndDoesNotBecomeTheNewBaselineForTheNextComparison() {
        val points = listOf(
            point(sequenceNumber = 0, elapsedNanos = 10_000_000_000L),
            point(sequenceNumber = 1, elapsedNanos = 5_000_000_000L), // goes backward in time
            point(sequenceNumber = 2, elapsedNanos = 12_000_000_000L) // must compare against seq 0, not seq 1
        )

        val result = engine.process("trip-1", version, listOf(part()), mapOf("capture-1" to points))

        val bySequence = result.assessments.associateBy { it.sequenceNumber }
        assertEquals(TrackPointDecision.ACCEPTED, bySequence.getValue(0).decision)
        assertEquals(TrackPointDecision.REJECTED, bySequence.getValue(1).decision)
        assertEquals("REJECTED_OUT_OF_ORDER", bySequence.getValue(1).reasonCodes)
        assertEquals(TrackPointDecision.ACCEPTED, bySequence.getValue(2).decision)
        assertEquals(2, result.processedPoints.size)
    }

    @Test
    fun pointWithIdenticalTimestampToThePreviousAcceptedPointIsRejectedAsDuplicate() {
        val points = listOf(
            point(sequenceNumber = 0, elapsedNanos = 5_000_000_000L),
            point(sequenceNumber = 1, elapsedNanos = 5_000_000_000L)
        )

        val result = engine.process("trip-1", version, listOf(part()), mapOf("capture-1" to points))

        val second = result.assessments.single { it.sequenceNumber == 1L }
        assertEquals(TrackPointDecision.REJECTED, second.decision)
        assertEquals("REJECTED_DUPLICATE", second.reasonCodes)
    }

    @Test
    fun gapExceedingThresholdIsRecordedAndTheNextPointIsMarkedAsAGapBoundary() {
        val points = listOf(
            point(sequenceNumber = 0, elapsedNanos = 0L, capturedAt = 1_000L),
            point(sequenceNumber = 1, elapsedNanos = 40_000_000_000L, capturedAt = 41_000L) // 40s later
        )

        val result = engine.process("trip-1", version, listOf(part()), mapOf("capture-1" to points))

        assertEquals(1, result.gaps.size)
        val gap = result.gaps.single()
        assertEquals("trip-1", gap.tripId)
        assertEquals(1_000L, gap.startedAt)
        assertEquals(41_000L, gap.endedAt)
        assertEquals(40_000L, gap.durationMs)
        assertEquals("capture-1:0", gap.startSourceRef)
        assertEquals("capture-1:1", gap.endSourceRef)
        assertEquals(ProcessingEngine.POINT_ROLE_GAP_BOUNDARY, result.processedPoints.last().pointRole)
    }

    @Test
    fun gapBelowThresholdDoesNotCreateAGapOrMarkAnyPointAsABoundary() {
        val points = listOf(
            point(sequenceNumber = 0, elapsedNanos = 0L),
            point(sequenceNumber = 1, elapsedNanos = 10_000_000_000L) // 10s, under the 30s threshold
        )

        val result = engine.process("trip-1", version, listOf(part()), mapOf("capture-1" to points))

        assertTrue(result.gaps.isEmpty())
        assertTrue(result.processedPoints.all { it.pointRole == null })
    }

    @Test
    fun rejectedPointsDoNotCountAsEvidenceForGapTiming() {
        val points = listOf(
            point(sequenceNumber = 0, elapsedNanos = 0L, capturedAt = 0L),
            point(sequenceNumber = 1, elapsedNanos = 0L, capturedAt = 0L), // duplicate, rejected
            point(sequenceNumber = 2, elapsedNanos = 10_000_000_000L, capturedAt = 10_000L) // 10s after seq 0
        )

        val result = engine.process("trip-1", version, listOf(part()), mapOf("capture-1" to points))

        // Gap must be measured from seq 0 (last accepted), not seq 1 (rejected) - still under threshold either way here.
        assertTrue(result.gaps.isEmpty())
    }

    @Test
    fun everyRawPointGetsAnAssessmentRegardlessOfDecisionButOnlyAcceptedOnesBecomeProcessedPoints() {
        val points = listOf(
            point(sequenceNumber = 0, elapsedNanos = 5_000_000_000L),
            point(sequenceNumber = 1, elapsedNanos = 5_000_000_000L) // duplicate -> rejected
        )

        val result = engine.process("trip-1", version, listOf(part()), mapOf("capture-1" to points))

        assertEquals(2, result.assessments.size)
        assertEquals(1, result.processedPoints.size)
    }

    @Test
    fun multiplePartsAreProcessedInOrderIndexOrderWithOneContinuousOrderIndexSequence() {
        val partA = part(captureId = "capture-a", orderIndex = 0)
        val partB = part(captureId = "capture-b", orderIndex = 1)
        val pointsA = listOf(point(captureId = "capture-a", sequenceNumber = 0, elapsedNanos = 0L))
        val pointsB = listOf(point(captureId = "capture-b", sequenceNumber = 0, elapsedNanos = 1_000_000_000L))

        val result = engine.process(
            "trip-1",
            version,
            listOf(partA, partB),
            mapOf("capture-a" to pointsA, "capture-b" to pointsB)
        )

        assertEquals(listOf("capture-a", "capture-b"), result.processedPoints.map { it.sourceCaptureId })
        assertEquals(listOf(0, 1), result.processedPoints.map { it.orderIndex })
    }

    @Test
    fun tripPartSequenceRangeExcludesRawPointsOutsideItsDeclaredBounds() {
        val allPoints = (0..4).map { i -> point(sequenceNumber = i.toLong(), elapsedNanos = i * 2_000_000_000L) }
        val narrowedPart = part(startSequenceNumber = 1, endSequenceNumber = 3)

        val result = engine.process("trip-1", version, listOf(narrowedPart), mapOf("capture-1" to allPoints))

        assertEquals(listOf(1L, 2L, 3L), result.assessments.map { it.sequenceNumber })
    }

    @Test
    fun aRebootBetweenTwoMergedCapturesDoesNotRejectTheSecondCaptureAsOutOfOrder() {
        // EDT-001: capture-b's elapsedRealtimeNanos is SMALLER than capture-a's
        // last accepted point - exactly what happens if the device rebooted
        // between the two rides being merged (elapsedRealtime resets at
        // boot). Comparing these two clocks directly would reject all of
        // capture-b as "out of order"; crossing a capture boundary must skip
        // that comparison instead.
        val partA = part(captureId = "capture-a", orderIndex = 0)
        val partB = part(captureId = "capture-b", orderIndex = 1)
        val pointsA = listOf(point(captureId = "capture-a", sequenceNumber = 0, elapsedNanos = 50_000_000_000L, capturedAt = 50_000L))
        val pointsB = listOf(point(captureId = "capture-b", sequenceNumber = 0, elapsedNanos = 1_000_000_000L, capturedAt = 500_000L))

        val result = engine.process(
            "trip-1",
            version,
            listOf(partA, partB),
            mapOf("capture-a" to pointsA, "capture-b" to pointsB)
        )

        assertTrue("capture-b's point must not be rejected despite its smaller elapsedRealtimeNanos", result.assessments.all { it.decision == TrackPointDecision.ACCEPTED })
        assertEquals(2, result.processedPoints.size)
    }

    @Test
    fun crossingACaptureBoundaryAlwaysRecordsAGapUsingWallClockDuration() {
        val partA = part(captureId = "capture-a", orderIndex = 0)
        val partB = part(captureId = "capture-b", orderIndex = 1)
        // Only 5s apart by wall clock - well under GAP_THRESHOLD_MS - but a
        // capture boundary always counts as a real discontinuity regardless.
        val pointsA = listOf(point(captureId = "capture-a", sequenceNumber = 0, elapsedNanos = 0L, capturedAt = 1_000L))
        val pointsB = listOf(point(captureId = "capture-b", sequenceNumber = 0, elapsedNanos = 0L, capturedAt = 6_000L))

        val result = engine.process(
            "trip-1",
            version,
            listOf(partA, partB),
            mapOf("capture-a" to pointsA, "capture-b" to pointsB)
        )

        assertEquals(1, result.gaps.size)
        val gap = result.gaps.single()
        assertEquals("CAPTURE_BOUNDARY", gap.reasonCode)
        assertEquals(5_000L, gap.durationMs)
        assertEquals("capture-a:0", gap.startSourceRef)
        assertEquals("capture-b:0", gap.endSourceRef)
    }

    // --- PRC-004 (processing v1, ADR-029): a fix whose own accuracy is poor is not a position ---

    @Test
    fun aFixWhoseOwnAccuracyIsPoorIsRejectedAndNeverEntersTheRoute() {
        val points = listOf(
            point(sequenceNumber = 0, elapsedNanos = 1_000_000_000L),
            point(sequenceNumber = 1, elapsedNanos = 3_000_000_000L, latitude = 10.003, accuracyM = 300f),
            point(sequenceNumber = 2, elapsedNanos = 5_000_000_000L)
        )

        val result = engine.process("trip-1", version, listOf(part()), mapOf("capture-1" to points))

        val poor = result.assessments.single { it.sequenceNumber == 1L }
        assertEquals(TrackPointDecision.REJECTED, poor.decision)
        assertEquals(ProcessingEngine.REASON_POOR_ACCURACY, poor.reasonCodes)
        assertEquals(listOf(0L, 2L), result.processedPoints.map { it.sourceSequenceNumber })
    }

    @Test
    fun theLimitItselfIsStillUsableAndJustPastItIsNot() {
        val points = listOf(
            point(sequenceNumber = 0, elapsedNanos = 1_000_000_000L, accuracyM = FixQuality.MAX_USABLE_HORIZONTAL_ACCURACY_M),
            point(sequenceNumber = 1, elapsedNanos = 3_000_000_000L, accuracyM = FixQuality.MAX_USABLE_HORIZONTAL_ACCURACY_M + 0.1f)
        )

        val result = engine.process("trip-1", version, listOf(part()), mapOf("capture-1" to points))

        assertEquals(listOf(TrackPointDecision.ACCEPTED, TrackPointDecision.REJECTED), result.assessments.map { it.decision })
    }

    /**
     * The real case (2026-10-02, the 13.8 km ride): entering a garage the GPS was lost and for about two and a half
     * minutes the phone delivered network positions of 78-400 m accuracy that jump hundreds of metres. They added 3.1 km
     * to the ride and scribbled its end. Now the route ends where the good fixes end, the distance does not include the
     * jumps, and the stretch is an explicit gap.
     */
    @Test
    fun theStretchOfNetworkFixesAfterALostGpsIsNotRouteAndBecomesAnExplicitGap() {
        val points = mutableListOf<RawTrackPointEntity>()
        var seq = 0L
        // Ten good fixes, 2 s apart, riding north.
        repeat(10) { i -> points += point(sequenceNumber = seq++, elapsedNanos = (1L + 2 * i) * 1_000_000_000L, latitude = 10.0 + i * 0.0002) }
        // ~2.5 minutes of network fixes: 78-400 m, jumping ~300 m around.
        val networkAccuracies = listOf(400f, 100f, 100f, 100f, 78f, 287f, 100f, 400f, 83f, 283f, 300f, 83f, 381f, 300f)
        networkAccuracies.forEachIndexed { i, accuracy ->
            points += point(
                sequenceNumber = seq++, elapsedNanos = (30L + 10 * i) * 1_000_000_000L,
                latitude = 10.0 + 0.0018 + (if (i % 2 == 0) 0.003 else -0.003), accuracyM = accuracy
            )
        }
        // The GPS comes back, standing at the destination.
        repeat(3) { i -> points += point(sequenceNumber = seq++, elapsedNanos = (180L + 2 * i) * 1_000_000_000L, latitude = 10.0018, accuracyM = 10f) }

        val result = engine.process("trip-1", version, listOf(part()), mapOf("capture-1" to points))

        assertEquals("only the 13 good fixes are in the route", 13, result.processedPoints.size)
        assertTrue(result.processedPoints.none { it.sourceSequenceNumber in 10L..23L })
        assertEquals(14, result.assessments.count { it.reasonCodes == ProcessingEngine.REASON_POOR_ACCURACY })
        val gap = result.gaps.single()
        // From the last good fix (19 s) to the first one back (180 s): disclosed, not bridged.
        assertEquals(19_000L, gap.startedAt)
        assertEquals(180_000L, gap.endedAt)
        assertEquals(161_000L, gap.durationMs)
        assertEquals(ProcessingEngine.REASON_GAP_NO_FIX, gap.reasonCode)
    }

    @Test
    fun aPoorFixAsTheFirstPointOfACaptureIsRejectedAndTheNextGoodOneStartsTheRoute() {
        // The first fix after a cold start is often poor (71 and 88 m on the phone).
        val points = listOf(
            point(sequenceNumber = 0, elapsedNanos = 1_000_000_000L, accuracyM = 88f),
            point(sequenceNumber = 1, elapsedNanos = 5_000_000_000L, accuracyM = 4f)
        )

        val result = engine.process("trip-1", version, listOf(part()), mapOf("capture-1" to points))

        assertEquals(listOf(1L), result.processedPoints.map { it.sourceSequenceNumber })
        assertEquals(ProcessingEngine.REASON_POOR_ACCURACY, result.assessments.single { it.sequenceNumber == 0L }.reasonCodes)
    }

    @Test
    fun aPoorFixIsRejectedEvenWhenItIsTheFirstOfASecondCapture() {
        val partA = part(captureId = "capture-a")
        val partB = part(captureId = "capture-b", orderIndex = 1)
        val pointsA = listOf(point(captureId = "capture-a", sequenceNumber = 0, elapsedNanos = 1_000_000_000L, capturedAt = 1_000L))
        val pointsB = listOf(
            point(captureId = "capture-b", sequenceNumber = 0, elapsedNanos = 1_000_000_000L, capturedAt = 600_000L, accuracyM = 250f),
            point(captureId = "capture-b", sequenceNumber = 1, elapsedNanos = 3_000_000_000L, capturedAt = 602_000L)
        )

        val result = engine.process("trip-1", version, listOf(partA, partB), mapOf("capture-a" to pointsA, "capture-b" to pointsB))

        assertEquals(listOf("capture-a:0", "capture-b:1"), result.processedPoints.map { "${it.sourceCaptureId}:${it.sourceSequenceNumber}" })
    }

    @Test
    fun aCaptureWithNoRawPointsProducesNoOutputWithoutCrashing() {
        val result = engine.process("trip-1", version, listOf(part()), mapOf("capture-1" to emptyList()))

        assertTrue(result.assessments.isEmpty())
        assertTrue(result.processedPoints.isEmpty())
        assertTrue(result.gaps.isEmpty())
    }
}
