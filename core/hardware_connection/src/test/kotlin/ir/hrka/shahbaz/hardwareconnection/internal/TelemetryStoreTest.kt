package ir.hrka.shahbaz.hardwareconnection.internal

import ir.hrka.shahbaz.hardwareconnection.Ms5611Telemetry
import ir.hrka.shahbaz.hardwareconnection.RawSensorField
import ir.hrka.shahbaz.hardwareconnection.RawSensorFieldType
import ir.hrka.shahbaz.hardwareconnection.SensorErrorCode
import ir.hrka.shahbaz.hardwareconnection.SensorKey
import ir.hrka.shahbaz.hardwareconnection.SensorState
import ir.hrka.shahbaz.hardwareconnection.internal.protocol.DeviceStatusPayload
import ir.hrka.shahbaz.hardwareconnection.internal.protocol.RawWireSensorSample
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryStoreTest {
    @Test
    fun validSensorsBecomeAvailableWithExactUnits() {
        val store = TelemetryStore(1013.25)
        store.awaitingTelemetry(startedAtMillis = 0L)
        store.accept(shtSample(sequence = 1u), 100L)
        store.accept(msSample(sequence = 2u), 110L)

        val sht = (store.snapshot.sht30 as SensorState.Available).sample.value
        assertEquals(23.456, sht.temperatureCelsius, 0.0001)
        assertEquals(50.123, sht.relativeHumidityPercent, 0.0001)
        val ms = (store.snapshot.ms5611 as SensorState.Available).sample.value
        assertEquals(101_325, ms.pressurePascal)
        assertEquals(22.34, ms.temperatureCelsius, 0.0001)
        assertTrue(abs(ms.altitudeAboveMeanSeaLevelMeters) < 0.001)
    }

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

    @Test
    fun duplicateSampleSequenceIsRejectedAndDoesNotReplaceLastGoodValue() {
        val store = TelemetryStore(1013.25)
        store.accept(shtSample(sequence = 5u), 10L)
        store.accept(shtSample(sequence = 5u), 20L)
        val failed = store.snapshot.sht30 as SensorState.Failed
        assertEquals(SensorErrorCode.INVALID_PAYLOAD, failed.error.code)
        assertEquals(5L, failed.lastSample?.sequence)
    }

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
