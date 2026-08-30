/** Pure reducer for external-sensor samples, health, staleness, and QNH updates. */
package ir.hrka.shahbaz.hardwareconnection.internal

import ir.hrka.shahbaz.hardwareconnection.BoardDeviceStatus
import ir.hrka.shahbaz.hardwareconnection.BoardLinkDiagnostics
import ir.hrka.shahbaz.hardwareconnection.BoardTelemetrySnapshot
import ir.hrka.shahbaz.hardwareconnection.Ms5611Telemetry
import ir.hrka.shahbaz.hardwareconnection.RawSensorFieldType
import ir.hrka.shahbaz.hardwareconnection.RawSensorSample
import ir.hrka.shahbaz.hardwareconnection.SensorError
import ir.hrka.shahbaz.hardwareconnection.SensorErrorCode
import ir.hrka.shahbaz.hardwareconnection.SensorKey
import ir.hrka.shahbaz.hardwareconnection.SensorSample
import ir.hrka.shahbaz.hardwareconnection.SensorSampleQuality
import ir.hrka.shahbaz.hardwareconnection.SensorState
import ir.hrka.shahbaz.hardwareconnection.SensorUnavailableReason
import ir.hrka.shahbaz.hardwareconnection.Sht30Telemetry
import ir.hrka.shahbaz.hardwareconnection.internal.protocol.DeviceStatusPayload
import ir.hrka.shahbaz.hardwareconnection.internal.protocol.ProtocolErrorKind
import ir.hrka.shahbaz.hardwareconnection.internal.protocol.ProtocolException
import ir.hrka.shahbaz.hardwareconnection.internal.protocol.RawWireSensorSample
import ir.hrka.shahbaz.hardwareconnection.internal.protocol.altitudeMeters

/**
 * Documents the TelemetryStore type and the role it plays in this module.
 */
internal class TelemetryStore(
    initialQnhHectopascal: Double,
    /**
     * Exposes the maximumUnknownSensors value.
     */
    private val maximumUnknownSensors: Int = 32,
) {
    init {
        require(maximumUnknownSensors > 0) { "Unknown sensor retention must be positive" }
    }

    private var qnhHectopascal = validateQnh(initialQnhHectopascal)
    private var lastShtSequence: UInt? = null
    private var lastMsSequence: UInt? = null
    private var awaitingFirstSampleSinceMillis: Long? = null

    var snapshot = BoardTelemetrySnapshot()
        private set

    /**
     * Runs the awaitingTelemetry operation.
     */
    fun awaitingTelemetry(startedAtMillis: Long) {
        require(startedAtMillis >= 0) { "Telemetry start time must be non-negative" }
        snapshot = BoardTelemetrySnapshot(
            sht30 = SensorState.AwaitingFirstSample,
            ms5611 = SensorState.AwaitingFirstSample,
            diagnostics = snapshot.diagnostics,
        )
        lastShtSequence = null
        lastMsSequence = null
        awaitingFirstSampleSinceMillis = startedAtMillis
    }

    /**
     * Runs the disconnected operation.
     */
    fun disconnected() {
        snapshot = BoardTelemetrySnapshot(
            diagnostics = snapshot.diagnostics,
        )
        lastShtSequence = null
        lastMsSequence = null
        awaitingFirstSampleSinceMillis = null
    }

    /**
     * Runs the setQnh operation.
     */
    fun setQnh(value: Double) {
        qnhHectopascal = validateQnh(value)
        snapshot = snapshot.copy(ms5611 = snapshot.ms5611.mapLatest(::withCurrentQnh))
    }

    /**
     * Runs the onFrameAccepted operation.
     */
    fun onFrameAccepted() {
        snapshot = snapshot.copy(
            diagnostics = snapshot.diagnostics.copy(
                acceptedFrames = snapshot.diagnostics.acceptedFrames + 1,
            ),
        )
    }

    /**
     * Runs the onFrameRejected operation.
     */
    fun onFrameRejected(message: String, crcOrFraming: Boolean = true) {
        val diagnostics = snapshot.diagnostics
        snapshot = snapshot.copy(
            diagnostics = diagnostics.copy(
                rejectedFrames = diagnostics.rejectedFrames + 1,
                crcOrFramingErrors = diagnostics.crcOrFramingErrors + if (crcOrFraming) 1 else 0,
                lastProtocolWarning = message,
            ),
        )
    }

    /** Returns a typed error when a known sensor payload was rejected. */
    fun accept(sample: RawWireSensorSample, receivedAtMillis: Long): SensorError? {
        if (sample.instanceId != 0 || sample.sensorId !in 1..2) {
            acceptUnknown(sample, receivedAtMillis)
            return null
        }
        return when (sample.sensorId) {
            1 -> acceptSht30(sample, receivedAtMillis)
            2 -> acceptMs5611(sample, receivedAtMillis)
            else -> null
        }
    }

    /**
     * Runs the acceptStatus operation.
     */
    fun acceptStatus(status: DeviceStatusPayload, receivedAtMillis: Long) {
        var sht = snapshot.sht30
        var ms = snapshot.ms5611
        if (!status.sht30Online) {
            sht = failed(
                sht.latest(),
                SensorErrorCode.SENSOR_OFFLINE,
                "The board reports SHT30 offline",
                receivedAtMillis,
            )
        }
        if (!status.ms5611Online) {
            ms = failed(
                ms.latest(),
                SensorErrorCode.SENSOR_OFFLINE,
                "The board reports MS5611 offline",
                receivedAtMillis,
            )
        }
        snapshot = snapshot.copy(
            sht30 = sht,
            ms5611 = ms,
            deviceStatus = BoardDeviceStatus(
                safetyStateCode = status.safetyState,
                communicationStateCode = status.communicationState,
                telemetryEnabled = status.telemetryEnabled,
                actuatorArmed = status.actuatorArmed,
                sht30Online = status.sht30Online,
                ms5611Online = status.ms5611Online,
                receivedAtElapsedRealtimeMillis = receivedAtMillis,
            ),
        )
    }

    /**
     * Runs the updateSensorHealth operation.
     */
    fun updateSensorHealth(
        nowMillis: Long,
        staleAfterMillis: Long,
        firstSampleTimeoutMillis: Long,
    ) {
        val awaitingSince = awaitingFirstSampleSinceMillis
        snapshot = snapshot.copy(
            sht30 = snapshot.sht30
                .toNoResponseIfNeeded(
                    sensorName = "SHT30",
                    now = nowMillis,
                    awaitingSince = awaitingSince,
                    threshold = firstSampleTimeoutMillis,
                )
                .toStaleIfNeeded(nowMillis, staleAfterMillis),
            ms5611 = snapshot.ms5611
                .toNoResponseIfNeeded(
                    sensorName = "MS5611",
                    now = nowMillis,
                    awaitingSince = awaitingSince,
                    threshold = firstSampleTimeoutMillis,
                )
                .toStaleIfNeeded(nowMillis, staleAfterMillis),
        )
    }

    /**
     * Runs the acceptSht30 operation.
     */
    private fun acceptSht30(sample: RawWireSensorSample, now: Long): SensorError? =
        try {
            requireForwardSequence(lastShtSequence, sample.sequence, "SHT30")
            requireFlags(sample, requiredValidity = 0x1Bu)
            val fields = sample.fields.associateBy { it.fieldId }
            if (fields.keys != setOf(1, 2)) invalid("SHT30 fields must be {1,2}")
            val temperature = fields.getValue(1)
            val humidity = fields.getValue(2)
            if (temperature.type != RawSensorFieldType.SIGNED_32) invalid("SHT30 temperature type")
            if (humidity.type != RawSensorFieldType.UNSIGNED_32) invalid("SHT30 humidity type")
            val temperatureMilliCelsius = temperature.rawBits.toInt()
            val humidityMilliPercent = humidity.rawBits
            if (temperatureMilliCelsius !in -40_000..125_000) range("SHT30 temperature")
            if (humidityMilliPercent > 100_000u) range("SHT30 humidity")
            lastShtSequence = sample.sequence
            snapshot = snapshot.copy(
                sht30 = SensorState.Available(
                    SensorSample(
                        value = Sht30Telemetry(
                            temperatureCelsius = temperatureMilliCelsius / 1_000.0,
                            relativeHumidityPercent = humidityMilliPercent.toDouble() / 1_000.0,
                        ),
                        sequence = sample.sequence.toLong(),
                        deviceTimestampMicros = sample.deviceTimestampUs,
                        receivedAtElapsedRealtimeMillis = now,
                        quality = sample.quality(),
                    ),
                ),
            )
            null
        } catch (error: SensorSampleException) {
            val sensorError = SensorError(error.code, error.message, now)
            snapshot = snapshot.copy(
                sht30 = SensorState.Failed(snapshot.sht30.latest(), sensorError),
            )
            sensorError
        }

    /**
     * Runs the acceptMs5611 operation.
     */
    private fun acceptMs5611(sample: RawWireSensorSample, now: Long): SensorError? =
        try {
            requireForwardSequence(lastMsSequence, sample.sequence, "MS5611")
            requireFlags(sample, requiredValidity = 0x1Fu)
            val fields = sample.fields.associateBy { it.fieldId }
            if (fields.keys != setOf(3, 4)) invalid("MS5611 fields must be {3,4}")
            val pressure = fields.getValue(3)
            val temperature = fields.getValue(4)
            if (pressure.type != RawSensorFieldType.SIGNED_32) invalid("MS5611 pressure type")
            if (temperature.type != RawSensorFieldType.SIGNED_32) invalid("MS5611 temperature type")
            val pressurePascal = pressure.rawBits.toInt()
            val temperatureMilliCelsius = temperature.rawBits.toInt()
            if (pressurePascal !in 1_000..120_000) range("MS5611 pressure")
            if (temperatureMilliCelsius !in -40_000..85_000) range("MS5611 temperature")
            lastMsSequence = sample.sequence
            snapshot = snapshot.copy(
                ms5611 = SensorState.Available(
                    SensorSample(
                        value = Ms5611Telemetry(
                            pressurePascal = pressurePascal,
                            temperatureCelsius = temperatureMilliCelsius / 1_000.0,
                            altitudeAboveMeanSeaLevelMeters = altitudeMeters(
                                pressurePascal,
                                qnhHectopascal,
                            ),
                            qnhHectopascal = qnhHectopascal,
                        ),
                        sequence = sample.sequence.toLong(),
                        deviceTimestampMicros = sample.deviceTimestampUs,
                        receivedAtElapsedRealtimeMillis = now,
                        quality = sample.quality(),
                    ),
                ),
            )
            null
        } catch (error: SensorSampleException) {
            val sensorError = SensorError(error.code, error.message, now)
            snapshot = snapshot.copy(
                ms5611 = SensorState.Failed(snapshot.ms5611.latest(), sensorError),
            )
            sensorError
        }

    /**
     * Runs the acceptUnknown operation.
     */
    private fun acceptUnknown(sample: RawWireSensorSample, now: Long) {
        val raw = RawSensorSample(
            sensorId = sample.sensorId,
            instanceId = sample.instanceId,
            sequence = sample.sequence.toLong(),
            deviceTimestampMicros = sample.deviceTimestampUs,
            validityFlags = sample.validityFlags.toLong(),
            qualityFlags = sample.qualityFlags.toLong(),
            healthFlags = sample.healthFlags.toLong(),
            fields = sample.fields,
            receivedAtElapsedRealtimeMillis = now,
        )
        val diagnostics = snapshot.diagnostics
        val retained = snapshot.unknownSensors.toMutableMap()
        val key = SensorKey(sample.sensorId, sample.instanceId)
        if (key !in retained && retained.size >= maximumUnknownSensors) {
            val oldest = retained.entries.minWithOrNull(
                compareBy<Map.Entry<SensorKey, RawSensorSample>> {
                    it.value.receivedAtElapsedRealtimeMillis
                }.thenBy { it.key.sensorId }.thenBy { it.key.instanceId },
            )
            if (oldest != null) retained.remove(oldest.key)
        }
        retained[key] = raw
        snapshot = snapshot.copy(
            unknownSensors = retained,
            diagnostics = diagnostics.copy(unknownSensorSamples = diagnostics.unknownSensorSamples + 1),
        )
    }

    /**
     * Runs the requireFlags operation.
     */
    private fun requireFlags(sample: RawWireSensorSample, requiredValidity: UInt) {
        if ((sample.validityFlags and requiredValidity) != requiredValidity) {
            throw SensorSampleException(
                SensorErrorCode.INVALID_VALIDITY,
                "validity flags 0x${sample.validityFlags.toString(16)} lack required evidence",
            )
        }
        if ((sample.qualityFlags and 0x01u) == 0u) {
            throw SensorSampleException(SensorErrorCode.NOT_FRESH, "sample is not marked fresh")
        }
        if (sample.healthFlags != 0u) {
            throw SensorSampleException(
                SensorErrorCode.HEALTH_FAULT,
                "sensor health flags are 0x${sample.healthFlags.toString(16)}",
            )
        }
    }

    /**
     * Runs the requireForwardSequence operation.
     */
    private fun requireForwardSequence(previous: UInt?, current: UInt, sensor: String) {
        if (previous == null) return
        val distance = current - previous
        if (distance == 0u || distance >= 0x8000_0000u) {
            throw SensorSampleException(
                SensorErrorCode.INVALID_PAYLOAD,
                "$sensor sample sequence did not advance",
            )
        }
    }

    /**
     * Runs the RawWireSensorSample operation.
     */
    private fun RawWireSensorSample.quality() = SensorSampleQuality(
        recoveredAfterError = (qualityFlags and 0x02u) != 0u,
        rateLimited = (qualityFlags and 0x04u) != 0u,
        rawValidityFlags = validityFlags.toLong(),
        rawQualityFlags = qualityFlags.toLong(),
        rawHealthFlags = healthFlags.toLong(),
    )

    /**
     * Runs the withCurrentQnh operation.
     */
    private fun withCurrentQnh(value: Ms5611Telemetry): Ms5611Telemetry = value.copy(
        altitudeAboveMeanSeaLevelMeters = altitudeMeters(value.pressurePascal, qnhHectopascal),
        qnhHectopascal = qnhHectopascal,
    )

    /**
     * Runs the invalid operation.
     */
    private fun invalid(message: String): Nothing =
        throw SensorSampleException(SensorErrorCode.INVALID_PAYLOAD, message)

    /**
     * Runs the range operation.
     */
    private fun range(message: String): Nothing =
        throw SensorSampleException(SensorErrorCode.OUT_OF_RANGE, "$message is outside physical range")

    /**
     * Documents the SensorSampleException type and the role it plays in this module.
     */
    private class SensorSampleException(
        val code: SensorErrorCode,
        override val message: String,
    ) : IllegalArgumentException(message)

    /**
     * Runs the validateQnh operation.
     */
    private fun validateQnh(value: Double): Double {
        if (!value.isFinite() || value !in 800.0..1100.0) {
            throw IllegalArgumentException("QNH must be a finite value in 800..1100 hPa")
        }
        return value
    }
}

/**
 * Runs the fun operation.
 */
private fun <T> failed(
    last: SensorSample<T>?,
    code: SensorErrorCode,
    message: String,
    now: Long,
): SensorState<T> = SensorState.Failed(last, SensorError(code, message, now))

/**
 * Runs the fun operation.
 */
private fun <T> SensorState<T>.latest(): SensorSample<T>? = when (this) {
    is SensorState.Available -> sample
    is SensorState.Stale -> lastSample
    is SensorState.Failed -> lastSample
    is SensorState.AwaitingFirstSample,
    is SensorState.Unavailable -> null
}

/**
 * Runs the fun operation.
 */
private fun <T> SensorState<T>.toStaleIfNeeded(now: Long, threshold: Long): SensorState<T> {
    val current = this as? SensorState.Available ?: return this
    if (now - current.sample.receivedAtElapsedRealtimeMillis <= threshold) return this
    return SensorState.Stale(current.sample, current.sample.receivedAtElapsedRealtimeMillis + threshold)
}

/**
 * Runs the fun operation.
 */
private fun <T> SensorState<T>.toNoResponseIfNeeded(
    sensorName: String,
    now: Long,
    awaitingSince: Long?,
    threshold: Long,
): SensorState<T> {
    if (this !is SensorState.AwaitingFirstSample || awaitingSince == null) return this
    if (now - awaitingSince <= threshold) return this
    return failed(
        last = null,
        code = SensorErrorCode.NO_RESPONSE,
        message = "No $sensorName sample received within $threshold ms",
        now = now,
    )
}

/**
 * Runs the fun operation.
 */
private fun <T> SensorState<T>.mapLatest(transform: (T) -> T): SensorState<T> = when (this) {
    is SensorState.Available -> copy(sample = sample.copy(value = transform(sample.value)))
    is SensorState.Stale -> copy(lastSample = lastSample?.let { it.copy(value = transform(it.value)) })
    is SensorState.Failed -> copy(lastSample = lastSample?.let { it.copy(value = transform(it.value)) })
    is SensorState.AwaitingFirstSample,
    is SensorState.Unavailable -> this
}
