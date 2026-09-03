package ir.hrka.shahbaz.hardwareconnection.internal

import ir.hrka.shahbaz.hardwareconnection.Ms5611Telemetry
import ir.hrka.shahbaz.hardwareconnection.RawSensorField
import ir.hrka.shahbaz.hardwareconnection.RawSensorFieldType
import ir.hrka.shahbaz.hardwareconnection.RangefinderRole
import ir.hrka.shahbaz.hardwareconnection.RangefinderLifecycle
import ir.hrka.shahbaz.hardwareconnection.RangefinderLifecycleStatus
import ir.hrka.shahbaz.hardwareconnection.SensorErrorCode
import ir.hrka.shahbaz.hardwareconnection.SensorKey
import ir.hrka.shahbaz.hardwareconnection.SensorState
import ir.hrka.shahbaz.hardwareconnection.SensorUnavailableReason
import ir.hrka.shahbaz.hardwareconnection.Vl53l0xTelemetry
import ir.hrka.shahbaz.hardwareconnection.internal.protocol.DeviceStatusPayload
import ir.hrka.shahbaz.hardwareconnection.internal.protocol.RawWireSensorSample
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Documents the TelemetryStoreTest type and the role it plays in this module.
 */
class TelemetryStoreTest {
    /**
     * Runs the validSensorsBecomeAvailableWithExactUnits operation.
     */
    @Test
    fun validSensorsBecomeAvailableWithExactUnits() {
        val store = TelemetryStore(1013.25)
        store.awaitingTelemetry(startedAtMillis = 0L)
        store.accept(shtSample(sequence = 1u), 100L, observedAtMillis = 80L)
        store.accept(msSample(sequence = 2u), 110L, observedAtMillis = 90L)

        val sht = (store.snapshot.sht30 as SensorState.Available).sample.value
        assertEquals(23.456, sht.temperatureCelsius, 0.0001)
        assertEquals(50.123, sht.relativeHumidityPercent, 0.0001)
        assertEquals(
            80L,
            (store.snapshot.sht30 as SensorState.Available)
                .sample.observedAtElapsedRealtimeMillis,
        )
        val ms = (store.snapshot.ms5611 as SensorState.Available).sample.value
        assertEquals(101_325, ms.pressurePascal)
        assertEquals(22.34, ms.temperatureCelsius, 0.0001)
        assertTrue(abs(ms.altitudeAboveMeanSeaLevelMeters) < 0.001)
        assertEquals(
            90L,
            (store.snapshot.ms5611 as SensorState.Available)
                .sample.observedAtElapsedRealtimeMillis,
        )
    }

    /**
     * Runs the qnhRecalculatesAltitudeWithoutChangingRawPressure operation.
     */
    @Test
    fun qnhRecalculatesAltitudeWithoutChangingRawPressure() {
        val store = TelemetryStore(1013.25)
        store.accept(msSample(sequence = 1u, pressurePa = 89_875), 1L)
        val before = (store.snapshot.ms5611 as SensorState.Available).sample.value
        store.setQnh(1000.0)
        val after = (store.snapshot.ms5611 as SensorState.Available).sample.value
        assertEquals(before.pressurePascal, after.pressurePascal)
        assertNotEquals(before.altitudeAboveMeanSeaLevelMeters, after.altitudeAboveMeanSeaLevelMeters)
        assertEquals(1000.0, after.qnhHectopascal, 0.0)
    }

    /**
     * Runs the missingEvidenceAndHealthFaultsNeverBecomeAvailable operation.
     */
    @Test
    fun missingEvidenceAndHealthFaultsNeverBecomeAvailable() {
        val store = TelemetryStore(1013.25)
        val invalidResult = store.accept(shtSample(sequence = 1u, validity = 0x01u), 10L)
        val invalid = store.snapshot.sht30 as SensorState.Failed
        assertEquals(SensorErrorCode.INVALID_VALIDITY, invalidResult?.code)
        assertEquals(SensorErrorCode.INVALID_VALIDITY, invalid.error.code)

        val unhealthyResult = store.accept(msSample(sequence = 1u, health = 0x02u), 20L)
        val unhealthy = store.snapshot.ms5611 as SensorState.Failed
        assertEquals(SensorErrorCode.HEALTH_FAULT, unhealthyResult?.code)
        assertEquals(SensorErrorCode.HEALTH_FAULT, unhealthy.error.code)
    }

    /**
     * Runs the staleAndOfflineStatesRetainLastGoodReading operation.
     */
    @Test
    fun staleAndOfflineStatesRetainLastGoodReading() {
        val store = TelemetryStore(1013.25)
        store.accept(shtSample(sequence = 1u), 100L)
        store.updateSensorHealth(
            nowMillis = 2_601L,
            staleAfterMillis = 2_500L,
            firstSampleTimeoutMillis = 5_000L,
        )
        assertTrue(store.snapshot.sht30 is SensorState.Stale)

        store.acceptStatus(
            DeviceStatusPayload(1, 4, true, false, false, true),
            receivedAtMillis = 2_700L,
        )
        val offline = store.snapshot.sht30 as SensorState.Failed
        assertEquals(SensorErrorCode.SENSOR_OFFLINE, offline.error.code)
        assertTrue(offline.lastSample != null)
    }

    @Test
    fun `mapped acquisition time controls sample age instead of delayed USB receipt time`() {
        val store = TelemetryStore(1013.25)
        assertEquals(
            null,
            store.accept(
                sample = rangeSample(instance = 0, sequence = 1u, distanceMm = 500),
                receivedAtMillis = 1_000L,
                observedAtMillis = 100L,
            ),
        )

        val available = store.snapshot.groundRange as SensorState.Available
        assertEquals(1_000L, available.sample.receivedAtElapsedRealtimeMillis)
        assertEquals(100L, available.sample.observedAtElapsedRealtimeMillis)

        store.updateSensorHealth(
            nowMillis = 1_001L,
            staleAfterMillis = 500L,
            firstSampleTimeoutMillis = 5_000L,
        )
        val stale = store.snapshot.groundRange as SensorState.Stale
        assertEquals(600L, stale.staleSinceElapsedRealtimeMillis)
    }

    @Test
    fun extendedDeviceStatusAppliesEveryRangefinderLifecycleIndependently() {
        val store = TelemetryStore(1013.25)
        store.awaitingTelemetry(startedAtMillis = 0L)
        val rangefinders = RangefinderLifecycleStatus(
            ground = RangefinderLifecycle.DISABLED_OR_ABSENT,
            up = RangefinderLifecycle.INITIALIZING,
            frontLeft = RangefinderLifecycle.LIVE,
            frontRight = RangefinderLifecycle.DEGRADED,
        )

        store.acceptStatus(
            DeviceStatusPayload(1, 4, true, false, true, true, rangefinders),
            receivedAtMillis = 100L,
        )

        assertEquals(
            SensorUnavailableReason.RANGEFINDER_DISABLED_OR_ABSENT,
            (store.snapshot.groundRange as SensorState.Unavailable).reason,
        )
        assertEquals(
            SensorUnavailableReason.RANGEFINDER_INITIALIZING,
            (store.snapshot.upRange as SensorState.Unavailable).reason,
        )
        assertTrue(store.snapshot.frontLeftRange is SensorState.AwaitingFirstSample)
        assertEquals(
            SensorErrorCode.RANGEFINDER_DEGRADED,
            (store.snapshot.frontRightRange as SensorState.Failed).error.code,
        )
        assertEquals(rangefinders, store.snapshot.deviceStatus?.rangefinders)
    }

    @Test
    fun telemetryStartPreservesStatusThatArrivedBeforeItsAcknowledgement() {
        val store = TelemetryStore(1013.25)
        val rangefinders = RangefinderLifecycleStatus(
            ground = RangefinderLifecycle.DISABLED_OR_ABSENT,
            up = RangefinderLifecycle.INITIALIZING,
            frontLeft = RangefinderLifecycle.LIVE,
            frontRight = RangefinderLifecycle.DEGRADED,
        )
        store.acceptStatus(
            DeviceStatusPayload(1, 4, true, false, false, true, rangefinders),
            receivedAtMillis = 90L,
        )

        store.awaitingTelemetry(startedAtMillis = 100L)

        assertEquals(SensorErrorCode.SENSOR_OFFLINE, (store.snapshot.sht30 as SensorState.Failed).error.code)
        assertTrue(store.snapshot.ms5611 is SensorState.AwaitingFirstSample)
        assertEquals(
            SensorUnavailableReason.RANGEFINDER_DISABLED_OR_ABSENT,
            (store.snapshot.groundRange as SensorState.Unavailable).reason,
        )
        assertEquals(
            SensorUnavailableReason.RANGEFINDER_INITIALIZING,
            (store.snapshot.upRange as SensorState.Unavailable).reason,
        )
        assertTrue(store.snapshot.frontLeftRange is SensorState.AwaitingFirstSample)
        assertEquals(
            SensorErrorCode.RANGEFINDER_DEGRADED,
            (store.snapshot.frontRightRange as SensorState.Failed).error.code,
        )
        assertEquals(rangefinders, store.snapshot.deviceStatus?.rangefinders)
    }

    @Test
    fun blockedToLiveRangefindersReceiveIndependentNonExtendableFirstSampleDeadlines() {
        val store = TelemetryStore(1013.25)
        store.awaitingTelemetry(startedAtMillis = 0L)
        store.acceptStatus(
            DeviceStatusPayload(
                1,
                4,
                true,
                false,
                true,
                true,
                RangefinderLifecycleStatus(
                    ground = RangefinderLifecycle.DISABLED_OR_ABSENT,
                    up = RangefinderLifecycle.INITIALIZING,
                    frontLeft = RangefinderLifecycle.DEGRADED,
                    frontRight = RangefinderLifecycle.LIVE,
                ),
            ),
            receivedAtMillis = 100L,
        )
        store.updateSensorHealth(
            nowMillis = 10_000L,
            staleAfterMillis = 5_000L,
            firstSampleTimeoutMillis = 1_000L,
        )

        val allLive = RangefinderLifecycleStatus(
            ground = RangefinderLifecycle.LIVE,
            up = RangefinderLifecycle.LIVE,
            frontLeft = RangefinderLifecycle.LIVE,
            frontRight = RangefinderLifecycle.LIVE,
        )
        store.acceptStatus(
            DeviceStatusPayload(1, 4, true, false, true, true, allLive),
            receivedAtMillis = 10_000L,
        )
        assertTrue(store.snapshot.groundRange is SensorState.AwaitingFirstSample)
        assertTrue(store.snapshot.upRange is SensorState.AwaitingFirstSample)
        assertTrue(store.snapshot.frontLeftRange is SensorState.AwaitingFirstSample)

        store.acceptStatus(
            DeviceStatusPayload(1, 4, true, false, true, true, allLive),
            receivedAtMillis = 10_500L,
        )
        store.updateSensorHealth(
            nowMillis = 10_999L,
            staleAfterMillis = 5_000L,
            firstSampleTimeoutMillis = 1_000L,
        )
        assertTrue(store.snapshot.groundRange is SensorState.AwaitingFirstSample)
        assertTrue(store.snapshot.upRange is SensorState.AwaitingFirstSample)
        assertTrue(store.snapshot.frontLeftRange is SensorState.AwaitingFirstSample)

        store.updateSensorHealth(
            nowMillis = 11_001L,
            staleAfterMillis = 5_000L,
            firstSampleTimeoutMillis = 1_000L,
        )
        listOf(
            store.snapshot.groundRange,
            store.snapshot.upRange,
            store.snapshot.frontLeftRange,
        ).forEach { state ->
            assertEquals(SensorErrorCode.NO_RESPONSE, (state as SensorState.Failed).error.code)
        }
    }

    @Test
    fun legacyStatusDoesNotInventOrOverwriteRangefinderLifecycle() {
        val store = TelemetryStore(1013.25)
        store.awaitingTelemetry(startedAtMillis = 0L)
        store.accept(rangeSample(instance = 0, sequence = 1u, distanceMm = 500), 50L)
        val before = store.snapshot.groundRange

        store.acceptStatus(
            DeviceStatusPayload(1, 4, true, false, true, true),
            receivedAtMillis = 100L,
        )

        assertEquals(before, store.snapshot.groundRange)
        assertEquals(null, store.snapshot.deviceStatus?.rangefinders)
    }

    @Test
    fun degradedRangefinderRetainsLastGoodSampleAndRecoversToStaleUntilNextSample() {
        val store = TelemetryStore(1013.25)
        store.awaitingTelemetry(startedAtMillis = 0L)
        store.accept(rangeSample(instance = 3, sequence = 1u, distanceMm = 750), 50L)
        val degraded = RangefinderLifecycleStatus(
            ground = RangefinderLifecycle.LIVE,
            up = RangefinderLifecycle.LIVE,
            frontLeft = RangefinderLifecycle.LIVE,
            frontRight = RangefinderLifecycle.DEGRADED,
        )
        store.acceptStatus(
            DeviceStatusPayload(1, 4, true, false, true, true, degraded),
            receivedAtMillis = 100L,
        )
        val failed = store.snapshot.frontRightRange as SensorState.Failed
        assertEquals(750, failed.lastSample?.value?.distanceMillimeters)

        store.acceptStatus(
            DeviceStatusPayload(
                1,
                4,
                true,
                false,
                true,
                true,
                degraded.copy(frontRight = RangefinderLifecycle.LIVE),
            ),
            receivedAtMillis = 150L,
        )
        val recovering = store.snapshot.frontRightRange as SensorState.Stale
        assertEquals(750, recovering.lastSample?.value?.distanceMillimeters)

        store.accept(rangeSample(instance = 3, sequence = 2u, distanceMm = 700), 200L)
        assertEquals(
            700,
            (store.snapshot.frontRightRange as SensorState.Available)
                .sample.value.distanceMillimeters,
        )
    }

    @Test
    fun validLateSampleCannotPromoteDisabledRangefinderToLive() {
        assertValidLateSampleDoesNotOverrideLifecycle(
            lifecycle = RangefinderLifecycle.DISABLED_OR_ABSENT,
            expectedState = SensorState.Unavailable(
                SensorUnavailableReason.RANGEFINDER_DISABLED_OR_ABSENT,
            ),
        )
    }

    @Test
    fun validLateSampleCannotPromoteInitializingRangefinderToLive() {
        assertValidLateSampleDoesNotOverrideLifecycle(
            lifecycle = RangefinderLifecycle.INITIALIZING,
            expectedState = SensorState.Unavailable(
                SensorUnavailableReason.RANGEFINDER_INITIALIZING,
            ),
        )
    }

    @Test
    fun validLateSampleCannotPromoteDegradedRangefinderToLive() {
        val store = TelemetryStore(1013.25)
        store.awaitingTelemetry(startedAtMillis = 0L)
        store.accept(rangeSample(instance = 0, sequence = 1u, distanceMm = 750), 50L)
        store.acceptStatus(
            DeviceStatusPayload(
                1,
                4,
                true,
                false,
                true,
                true,
                allRangefinders(RangefinderLifecycle.DEGRADED),
            ),
            receivedAtMillis = 100L,
        )
        val beforeLateSample = store.snapshot.groundRange

        assertEquals(
            null,
            store.accept(rangeSample(instance = 0, sequence = 2u, distanceMm = 700), 150L),
        )

        assertEquals(beforeLateSample, store.snapshot.groundRange)
        val degraded = store.snapshot.groundRange as SensorState.Failed
        assertEquals(SensorErrorCode.RANGEFINDER_DEGRADED, degraded.error.code)
        assertEquals(750, degraded.lastSample?.value?.distanceMillimeters)
    }

    @Test
    fun malformedAndReplayedSamplesCannotSatisfyLiveFirstSampleDeadline() {
        val rejectedSamples = listOf(
            rangeSample(instance = 0, sequence = 2u, distanceMm = 700).copy(
                fields = rangeSample(instance = 0, sequence = 2u, distanceMm = 700)
                    .fields.dropLast(1),
            ),
            rangeSample(instance = 0, sequence = 1u, distanceMm = 700),
        )

        rejectedSamples.forEach { rejectedSample ->
            val store = TelemetryStore(1013.25)
            store.awaitingTelemetry(startedAtMillis = 0L)
            store.accept(rangeSample(instance = 0, sequence = 1u, distanceMm = 750), 50L)
            store.acceptStatus(
                DeviceStatusPayload(
                    1,
                    4,
                    true,
                    false,
                    true,
                    true,
                    allRangefinders(RangefinderLifecycle.DISABLED_OR_ABSENT),
                ),
                receivedAtMillis = 75L,
            )
            store.acceptStatus(
                DeviceStatusPayload(
                    1,
                    4,
                    true,
                    false,
                    true,
                    true,
                    allRangefinders(RangefinderLifecycle.LIVE),
                ),
                receivedAtMillis = 100L,
            )

            val error = store.accept(rejectedSample, 150L)

            assertEquals(SensorErrorCode.INVALID_PAYLOAD, error?.code)
            assertTrue(store.snapshot.groundRange is SensorState.AwaitingFirstSample)
            store.updateSensorHealth(
                nowMillis = 1_101L,
                staleAfterMillis = 5_000L,
                firstSampleTimeoutMillis = 1_000L,
            )
            assertEquals(
                SensorErrorCode.NO_RESPONSE,
                (store.snapshot.groundRange as SensorState.Failed).error.code,
            )
        }
    }

    /**
     * Runs the unknownSensorAndKnownFutureInstanceRemainRawAndExtensible operation.
     */
    @Test
    fun unknownSensorAndKnownFutureInstanceRemainRawAndExtensible() {
        val store = TelemetryStore(1013.25)
        val unknown = RawWireSensorSample(
            sensorId = 42,
            instanceId = 3,
            sequence = 9u,
            deviceTimestampUs = 123uL,
            validityFlags = 0u,
            qualityFlags = 0u,
            healthFlags = 7u,
            fields = listOf(RawSensorField(99, RawSensorFieldType.UNSIGNED_32, 123u)),
        )
        store.accept(unknown, 50L)
        assertEquals(unknown.sensorId, store.snapshot.unknownSensors.getValue(SensorKey(42, 3)).sensorId)
    }

    /**
     * Runs the duplicateSampleSequenceIsRejectedAndDoesNotReplaceLastGoodValue operation.
     */
    @Test
    fun duplicateSampleSequenceIsRejectedAndDoesNotReplaceLastGoodValue() {
        val store = TelemetryStore(1013.25)
        store.accept(shtSample(sequence = 5u), 10L)
        store.accept(shtSample(sequence = 5u), 20L)
        val failed = store.snapshot.sht30 as SensorState.Failed
        assertEquals(SensorErrorCode.INVALID_PAYLOAD, failed.error.code)
        assertEquals(5L, failed.lastSample?.sequence)
    }

    /**
     * Runs the firstSampleTimeoutFailsOnlyTheSensorThatDidNotRespond operation.
     */
    @Test
    fun firstSampleTimeoutFailsOnlyTheSensorThatDidNotRespond() {
        val store = TelemetryStore(1013.25)
        store.awaitingTelemetry(startedAtMillis = 100L)
        store.accept(shtSample(sequence = 1u), 200L)

        store.updateSensorHealth(
            nowMillis = 1_101L,
            staleAfterMillis = 5_000L,
            firstSampleTimeoutMillis = 1_000L,
        )

        assertTrue(store.snapshot.sht30 is SensorState.Available)
        val missingPressure = store.snapshot.ms5611 as SensorState.Failed
        assertEquals(SensorErrorCode.NO_RESPONSE, missingPressure.error.code)
    }

    /**
     * Runs the lateValidSampleRecoversAfterNoResponseFailure operation.
     */
    @Test
    fun lateValidSampleRecoversAfterNoResponseFailure() {
        val store = TelemetryStore(1013.25)
        store.awaitingTelemetry(startedAtMillis = 0L)
        store.updateSensorHealth(
            nowMillis = 1_001L,
            staleAfterMillis = 5_000L,
            firstSampleTimeoutMillis = 1_000L,
        )
        assertEquals(
            SensorErrorCode.NO_RESPONSE,
            (store.snapshot.sht30 as SensorState.Failed).error.code,
        )

        store.accept(shtSample(sequence = 1u), 1_100L)

        assertTrue(store.snapshot.sht30 is SensorState.Available)
        assertEquals(
            23.456,
            (store.snapshot.sht30 as SensorState.Available).sample.value.temperatureCelsius,
            0.0001,
        )
        assertEquals(
            SensorErrorCode.NO_RESPONSE,
            (store.snapshot.ms5611 as SensorState.Failed).error.code,
        )
    }

    /**
     * Runs the unknownSensorRetentionIsStrictlyBoundedAndEvictsOldest operation.
     */
    @Test
    fun unknownSensorRetentionIsStrictlyBoundedAndEvictsOldest() {
        val store = TelemetryStore(1013.25, maximumUnknownSensors = 2)
        store.accept(unknownSample(sensorId = 40), 100L)
        store.accept(unknownSample(sensorId = 41), 200L)
        store.accept(unknownSample(sensorId = 42), 300L)

        assertEquals(2, store.snapshot.unknownSensors.size)
        assertTrue(SensorKey(40, 0) !in store.snapshot.unknownSensors)
        assertTrue(SensorKey(41, 0) in store.snapshot.unknownSensors)
        assertTrue(SensorKey(42, 0) in store.snapshot.unknownSensors)
        assertEquals(3L, store.snapshot.diagnostics.unknownSensorSamples)
    }

    @Test
    fun fourRangefinderInstancesMapToStableRolesWithIndependentSequences() {
        val store = TelemetryStore(1013.25)
        store.awaitingTelemetry(0L)
        repeat(4) { instance ->
            assertEquals(null, store.accept(rangeSample(instance, 1u, 500 + instance), 100L + instance))
        }

        RangefinderRole.entries.forEach { role ->
            val available = store.snapshot.rangefinder(role) as SensorState.Available
            assertEquals(role, available.sample.value.role)
            assertEquals(500 + role.instanceId, available.sample.value.distanceMillimeters)
            assertEquals(100, available.sample.value.signalQualityPercent)
            assertEquals(1L, available.sample.sequence)
        }

        assertEquals(null, store.accept(rangeSample(0, 2u, 600), 200L))
        assertEquals(null, store.accept(rangeSample(1, 2u, 700), 201L))
        assertEquals(600, (store.snapshot.groundRange as SensorState.Available).sample.value.distanceMillimeters)
        assertEquals(700, (store.snapshot.upRange as SensorState.Available).sample.value.distanceMillimeters)
    }

    @Test
    fun everyRawRangeStatusHasAnExplicitSafeState() {
        val expected = mapOf(
            1 to SensorErrorCode.RANGE_SIGMA_FAILURE,
            2 to SensorErrorCode.RANGE_SIGNAL_FAILURE,
            3 to SensorErrorCode.RANGE_MINIMUM_FAILURE,
            4 to SensorErrorCode.RANGE_PHASE_FAILURE,
            5 to SensorErrorCode.RANGE_HARDWARE_FAILURE,
        )
        for (status in 0..15) {
            val store = TelemetryStore(1013.25)
            val accepted = store.accept(rangeSample(0, 1u, 500, status), 10L)
            assertEquals("schema-valid optical status $status is accepted", null, accepted)
            if (status == 0 || status == 11) {
                assertTrue("status $status is live", store.snapshot.groundRange is SensorState.Available)
            } else {
                val failed = store.snapshot.groundRange as SensorState.Failed
                assertEquals(
                    "status $status has stable error mapping",
                    expected[status] ?: SensorErrorCode.RANGE_STATUS_UNKNOWN,
                    failed.error.code,
                )
            }
        }
    }

    @Test
    fun rangeDistanceBoundariesAndOpticalFailuresRetainLastGoodValue() {
        val store = TelemetryStore(1013.25)
        store.accept(rangeSample(0, 1u, 30), 10L)
        assertTrue(store.snapshot.groundRange is SensorState.Available)
        store.accept(rangeSample(0, 2u, 2_000), 20L)
        assertTrue(store.snapshot.groundRange is SensorState.Available)

        store.accept(rangeSample(0, 3u, 2_001), 30L)
        val tooFar = store.snapshot.groundRange as SensorState.Failed
        assertEquals(SensorErrorCode.OUT_OF_RANGE, tooFar.error.code)
        assertEquals(2_000, tooFar.lastSample?.value?.distanceMillimeters)

        store.accept(rangeSample(0, 4u, 0, status = 2), 40L)
        val signal = store.snapshot.groundRange as SensorState.Failed
        assertEquals(SensorErrorCode.RANGE_SIGNAL_FAILURE, signal.error.code)
        assertEquals(2_000, signal.lastSample?.value?.distanceMillimeters)
    }

    @Test
    fun malformedRangeSchemasAreRejectedWithoutCrossInstanceContamination() {
        val store = TelemetryStore(1013.25)
        store.accept(rangeSample(0, 1u, 500), 10L)
        store.accept(rangeSample(1, 1u, 600), 11L)

        val malformed = listOf(
            rangeSample(0, 2u, 500).copy(validityFlags = 0x1Fu),
            rangeSample(0, 2u, 500).copy(validityFlags = 0x3Du),
            rangeSample(0, 2u, 500).copy(qualityFlags = 0x09u),
            rangeSample(0, 2u, 500).copy(healthFlags = 1u),
            rangeSample(0, 2u, 500).copy(
                fields = rangeSample(0, 2u, 500).fields.dropLast(1),
            ),
            rangeSample(0, 2u, 500).copy(
                fields = rangeSample(0, 2u, 500).fields.mapIndexed { index, field ->
                    if (index == 0) field.copy(type = RawSensorFieldType.SIGNED_32) else field
                },
            ),
        )
        malformed.forEachIndexed { index, sample ->
            val error = store.accept(sample, 20L + index)
            assertTrue("malformed range schema $index rejected", error != null)
            assertEquals(SensorErrorCode.INVALID_PAYLOAD, error?.code)
        }
        assertEquals(600, (store.snapshot.upRange as SensorState.Available).sample.value.distanceMillimeters)

        val invalidInstance = store.accept(rangeSample(4, 1u, 500), 50L)
        assertEquals(SensorErrorCode.INVALID_PAYLOAD, invalidInstance?.code)
        assertTrue(SensorKey(3, 4) !in store.snapshot.unknownSensors)
    }

    @Test
    fun rangefinderAwaitingTimeoutStalenessAndLateRecoveryAreIndependent() {
        val store = TelemetryStore(1013.25)
        store.awaitingTelemetry(100L)
        store.accept(rangeSample(0, 1u, 500), 200L)
        store.updateSensorHealth(1_101L, staleAfterMillis = 5_000L, firstSampleTimeoutMillis = 1_000L)
        assertTrue(store.snapshot.groundRange is SensorState.Available)
        assertEquals(
            SensorErrorCode.NO_RESPONSE,
            (store.snapshot.upRange as SensorState.Failed).error.code,
        )

        store.accept(rangeSample(1, 1u, 600), 1_200L)
        assertTrue(store.snapshot.upRange is SensorState.Available)
        store.updateSensorHealth(6_201L, staleAfterMillis = 5_000L, firstSampleTimeoutMillis = 1_000L)
        assertTrue(store.snapshot.groundRange is SensorState.Stale)
        assertTrue(store.snapshot.upRange is SensorState.Stale)
    }

    /**
     * Runs the shtSample operation.
     */
    private fun shtSample(
        sequence: UInt,
        validity: UInt = 0x1Bu,
        health: UInt = 0u,
    ) = RawWireSensorSample(
        sensorId = 1,
        instanceId = 0,
        sequence = sequence,
        deviceTimestampUs = 1_000uL,
        validityFlags = validity,
        qualityFlags = 0x01u,
        healthFlags = health,
        fields = listOf(
            RawSensorField(1, RawSensorFieldType.SIGNED_32, 23_456.toUInt()),
            RawSensorField(2, RawSensorFieldType.UNSIGNED_32, 50_123u),
        ),
    )

    /**
     * Runs the msSample operation.
     */
    private fun msSample(
        sequence: UInt,
        pressurePa: Int = 101_325,
        health: UInt = 0u,
    ) = RawWireSensorSample(
        sensorId = 2,
        instanceId = 0,
        sequence = sequence,
        deviceTimestampUs = 1_100uL,
        validityFlags = 0x1Fu,
        qualityFlags = 0x01u,
        healthFlags = health,
        fields = listOf(
            RawSensorField(3, RawSensorFieldType.SIGNED_32, pressurePa.toUInt()),
            RawSensorField(4, RawSensorFieldType.SIGNED_32, 22_340u),
        ),
    )

    private fun rangeSample(
        instance: Int,
        sequence: UInt,
        distanceMm: Int,
        status: Int = 0,
    ): RawWireSensorSample {
        val eligible = (status == 0 || status == 11) && distanceMm in 30..2_000
        val health = (if (status == 0 || status == 11) 0u else 1u) or
            (if (distanceMm in 30..2_000) 0u else 2u)
        return RawWireSensorSample(
            sensorId = 3,
            instanceId = instance,
            sequence = sequence,
            deviceTimestampUs = 1_200uL + instance.toULong(),
            validityFlags = if (eligible) 0x1Du else 0x0Du,
            qualityFlags = 0x01u,
            healthFlags = health,
            fields = listOf(
                RawSensorField(5, RawSensorFieldType.UNSIGNED_32, distanceMm.toUInt()),
                RawSensorField(6, RawSensorFieldType.UNSIGNED_32, status.toUInt()),
                RawSensorField(
                    7,
                    RawSensorFieldType.UNSIGNED_32,
                    if (eligible) 100u else 0u,
                ),
            ),
        )
    }

    private fun assertValidLateSampleDoesNotOverrideLifecycle(
        lifecycle: RangefinderLifecycle,
        expectedState: SensorState<Vl53l0xTelemetry>,
    ) {
        val store = TelemetryStore(1013.25)
        store.awaitingTelemetry(startedAtMillis = 0L)
        store.acceptStatus(
            DeviceStatusPayload(
                1,
                4,
                true,
                false,
                true,
                true,
                allRangefinders(lifecycle),
            ),
            receivedAtMillis = 100L,
        )

        assertEquals(
            null,
            store.accept(rangeSample(instance = 0, sequence = 1u, distanceMm = 700), 150L),
        )

        assertEquals(expectedState, store.snapshot.groundRange)
    }

    private fun allRangefinders(lifecycle: RangefinderLifecycle) = RangefinderLifecycleStatus(
        ground = lifecycle,
        up = lifecycle,
        frontLeft = lifecycle,
        frontRight = lifecycle,
    )

    /**
     * Runs the unknownSample operation.
     */
    private fun unknownSample(sensorId: Int) = RawWireSensorSample(
        sensorId = sensorId,
        instanceId = 0,
        sequence = 1u,
        deviceTimestampUs = 1_000uL,
        validityFlags = 0u,
        qualityFlags = 0u,
        healthFlags = 0u,
        fields = listOf(RawSensorField(1, RawSensorFieldType.UNSIGNED_32, 1u)),
    )
}
