/** Pure reducer for external-sensor samples, health, staleness, and QNH updates. */
package ir.hrka.shahbaz.hardwareconnection.internal

import ir.hrka.shahbaz.hardwareconnection.BoardDeviceStatus
import ir.hrka.shahbaz.hardwareconnection.BoardLinkDiagnostics
import ir.hrka.shahbaz.hardwareconnection.BoardTelemetrySnapshot
import ir.hrka.shahbaz.hardwareconnection.Ms5611Telemetry
import ir.hrka.shahbaz.hardwareconnection.RawSensorFieldType
import ir.hrka.shahbaz.hardwareconnection.RawSensorSample
import ir.hrka.shahbaz.hardwareconnection.RangefinderLifecycle
import ir.hrka.shahbaz.hardwareconnection.RangefinderRole
import ir.hrka.shahbaz.hardwareconnection.SensorError
import ir.hrka.shahbaz.hardwareconnection.SensorErrorCode
import ir.hrka.shahbaz.hardwareconnection.SensorKey
import ir.hrka.shahbaz.hardwareconnection.SensorSample
import ir.hrka.shahbaz.hardwareconnection.SensorSampleQuality
import ir.hrka.shahbaz.hardwareconnection.SensorState
import ir.hrka.shahbaz.hardwareconnection.SensorUnavailableReason
import ir.hrka.shahbaz.hardwareconnection.Sht30Telemetry
import ir.hrka.shahbaz.hardwareconnection.Vl53l0xTelemetry
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
    private val lastRangeSequences = arrayOfNulls<UInt>(RANGEFINDER_COUNT)
    private val rangeAwaitingFirstSampleSinceMillis = arrayOfNulls<Long>(RANGEFINDER_COUNT)
    private var awaitingFirstSampleSinceMillis: Long? = null

    var snapshot = BoardTelemetrySnapshot()
        private set

    /**
     * Runs the awaitingTelemetry operation.
     */
    fun awaitingTelemetry(startedAtMillis: Long) {
        require(startedAtMillis >= 0) { "Telemetry start time must be non-negative" }
        rangeAwaitingFirstSampleSinceMillis.fill(startedAtMillis)
        val currentStatus = snapshot.deviceStatus
        var awaitingSnapshot = BoardTelemetrySnapshot(
            sht30 = SensorState.AwaitingFirstSample,
            ms5611 = SensorState.AwaitingFirstSample,
            groundRange = SensorState.AwaitingFirstSample,
            upRange = SensorState.AwaitingFirstSample,
            frontLeftRange = SensorState.AwaitingFirstSample,
            frontRightRange = SensorState.AwaitingFirstSample,
            deviceStatus = currentStatus,
            diagnostics = snapshot.diagnostics,
        )
        if (currentStatus?.sht30Online == false) {
            awaitingSnapshot = awaitingSnapshot.copy(
                sht30 = failed(
                    last = null,
                    code = SensorErrorCode.SENSOR_OFFLINE,
                    message = "The board reports SHT30 offline",
                    now = currentStatus.receivedAtElapsedRealtimeMillis,
                ),
            )
        }
        if (currentStatus?.ms5611Online == false) {
            awaitingSnapshot = awaitingSnapshot.copy(
                ms5611 = failed(
                    last = null,
                    code = SensorErrorCode.SENSOR_OFFLINE,
                    message = "The board reports MS5611 offline",
                    now = currentStatus.receivedAtElapsedRealtimeMillis,
                ),
            )
        }
        currentStatus?.rangefinders?.let { rangefinders ->
            RangefinderRole.entries.forEach { role ->
                val previous = awaitingSnapshot.rangefinder(role)
                val lifecycle = rangefinders[role]
                val next = previous.applyLifecycle(
                    role = role,
                    lifecycle = lifecycle,
                    receivedAtMillis = currentStatus.receivedAtElapsedRealtimeMillis,
                )
                updateRangeFirstSampleEpoch(
                    role = role,
                    previous = previous,
                    next = next,
                    lifecycle = lifecycle,
                    receivedAtMillis = currentStatus.receivedAtElapsedRealtimeMillis,
                )
                awaitingSnapshot = awaitingSnapshot.withRangefinder(
                    role,
                    next,
                )
            }
        }
        snapshot = awaitingSnapshot
        lastShtSequence = null
        lastMsSequence = null
        lastRangeSequences.fill(null)
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
        lastRangeSequences.fill(null)
        rangeAwaitingFirstSampleSinceMillis.fill(null)
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
    fun accept(
        sample: RawWireSensorSample,
        receivedAtMillis: Long,
        observedAtMillis: Long = receivedAtMillis,
    ): SensorError? {
        if (sample.sensorId !in 1..3) {
            acceptUnknown(sample, receivedAtMillis)
            return null
        }
        return when (sample.sensorId) {
            1 -> if (sample.instanceId == 0) {
                acceptSht30(sample, receivedAtMillis, observedAtMillis)
            } else {
                invalidKnownInstance("SHT30", sample.instanceId, receivedAtMillis)
            }
            2 -> if (sample.instanceId == 0) {
                acceptMs5611(sample, receivedAtMillis, observedAtMillis)
            } else {
                invalidKnownInstance("MS5611", sample.instanceId, receivedAtMillis)
            }
            3 -> if (sample.instanceId in 0 until RANGEFINDER_COUNT) {
                acceptVl53l0x(sample, receivedAtMillis, observedAtMillis)
            } else {
                invalidKnownInstance("VL53L0X", sample.instanceId, receivedAtMillis)
            }
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
        var updatedSnapshot = snapshot.copy(
            sht30 = sht,
            ms5611 = ms,
        )
        status.rangefinders?.let { rangefinders ->
            RangefinderRole.entries.forEach { role ->
                val previous = updatedSnapshot.rangefinder(role)
                val lifecycle = rangefinders[role]
                val next = previous.applyLifecycle(
                    role = role,
                    lifecycle = lifecycle,
                    receivedAtMillis = receivedAtMillis,
                )
                updateRangeFirstSampleEpoch(
                    role = role,
                    previous = previous,
                    next = next,
                    lifecycle = lifecycle,
                    receivedAtMillis = receivedAtMillis,
                )
                updatedSnapshot = updatedSnapshot.withRangefinder(
                    role,
                    next,
                )
            }
        }
        snapshot = updatedSnapshot.copy(
            deviceStatus = BoardDeviceStatus(
                safetyStateCode = status.safetyState,
                communicationStateCode = status.communicationState,
                telemetryEnabled = status.telemetryEnabled,
                actuatorArmed = status.actuatorArmed,
                sht30Online = status.sht30Online,
                ms5611Online = status.ms5611Online,
                receivedAtElapsedRealtimeMillis = receivedAtMillis,
                rangefinders = status.rangefinders,
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
            groundRange = snapshot.groundRange
                .toNoResponseIfNeeded(
                    sensorName = "VL53L0X Ground",
                    now = nowMillis,
                    awaitingSince = rangeAwaitingFirstSampleSinceMillis[
                        RangefinderRole.GROUND.instanceId
                    ],
                    threshold = firstSampleTimeoutMillis,
                )
                .toStaleIfNeeded(nowMillis, staleAfterMillis),
            upRange = snapshot.upRange
                .toNoResponseIfNeeded(
                    sensorName = "VL53L0X Up",
                    now = nowMillis,
                    awaitingSince = rangeAwaitingFirstSampleSinceMillis[
                        RangefinderRole.UP.instanceId
                    ],
                    threshold = firstSampleTimeoutMillis,
                )
                .toStaleIfNeeded(nowMillis, staleAfterMillis),
            frontLeftRange = snapshot.frontLeftRange
                .toNoResponseIfNeeded(
                    sensorName = "VL53L0X Front Left",
                    now = nowMillis,
                    awaitingSince = rangeAwaitingFirstSampleSinceMillis[
                        RangefinderRole.FRONT_LEFT.instanceId
                    ],
                    threshold = firstSampleTimeoutMillis,
                )
                .toStaleIfNeeded(nowMillis, staleAfterMillis),
            frontRightRange = snapshot.frontRightRange
                .toNoResponseIfNeeded(
                    sensorName = "VL53L0X Front Right",
                    now = nowMillis,
                    awaitingSince = rangeAwaitingFirstSampleSinceMillis[
                        RangefinderRole.FRONT_RIGHT.instanceId
                    ],
                    threshold = firstSampleTimeoutMillis,
                )
                .toStaleIfNeeded(nowMillis, staleAfterMillis),
        )
    }

    /** Restarts a role's first-sample deadline once, only when lifecycle evidence becomes live. */
    private fun updateRangeFirstSampleEpoch(
        role: RangefinderRole,
        previous: SensorState<Vl53l0xTelemetry>,
        next: SensorState<Vl53l0xTelemetry>,
        lifecycle: RangefinderLifecycle,
        receivedAtMillis: Long,
    ) {
        val index = role.instanceId
        when (lifecycle) {
            RangefinderLifecycle.DISABLED_OR_ABSENT,
            RangefinderLifecycle.INITIALIZING,
            RangefinderLifecycle.DEGRADED -> rangeAwaitingFirstSampleSinceMillis[index] = null
            RangefinderLifecycle.LIVE -> when {
                next !is SensorState.AwaitingFirstSample ->
                    rangeAwaitingFirstSampleSinceMillis[index] = null
                previous !is SensorState.AwaitingFirstSample ||
                    rangeAwaitingFirstSampleSinceMillis[index] == null ->
                    rangeAwaitingFirstSampleSinceMillis[index] = receivedAtMillis
                else -> Unit
            }
        }
    }

    /**
     * Runs the acceptSht30 operation.
     */
    private fun acceptSht30(
        sample: RawWireSensorSample,
        now: Long,
        observedAtMillis: Long,
    ): SensorError? =
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
                        observedAtElapsedRealtimeMillis = observedAtMillis,
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
    private fun acceptMs5611(
        sample: RawWireSensorSample,
        now: Long,
        observedAtMillis: Long,
    ): SensorError? =
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
                        observedAtElapsedRealtimeMillis = observedAtMillis,
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
     * Accepts the generic v2 payload for one fixed-role VL53L0X instance.
     * Optical invalidity is a sensor state, not a malformed protocol frame.
     */
    private fun acceptVl53l0x(
        sample: RawWireSensorSample,
        now: Long,
        observedAtMillis: Long,
    ): SensorError? =
        try {
            val role = requireNotNull(RangefinderRole.fromInstanceId(sample.instanceId))
            requireForwardSequence(
                lastRangeSequences[sample.instanceId],
                sample.sequence,
                "VL53L0X ${role.name}",
            )
            requireRangeBaseFlags(sample)
            val fields = sample.fields.associateBy { it.fieldId }
            if (fields.keys != setOf(5, 6, 7)) {
                invalid("VL53L0X fields must be {5,6,7}")
            }
            val distance = fields.getValue(5)
            val status = fields.getValue(6)
            val quality = fields.getValue(7)
            if (distance.type != RawSensorFieldType.UNSIGNED_32) {
                invalid("VL53L0X distance type")
            }
            if (status.type != RawSensorFieldType.UNSIGNED_32) {
                invalid("VL53L0X range status type")
            }
            if (quality.type != RawSensorFieldType.UNSIGNED_32) {
                invalid("VL53L0X signal quality type")
            }
            if (distance.rawBits > UShort.MAX_VALUE.toUInt()) invalid("VL53L0X distance width")
            if (status.rawBits > 15u) invalid("VL53L0X range status width")
            if (quality.rawBits > 100u) range("VL53L0X signal quality")

            val distanceMm = distance.rawBits.toInt()
            val rawStatus = status.rawBits.toInt()
            val qualityPercent = quality.rawBits.toInt()
            val statusValid = rawStatus == 0 || rawStatus == 11
            val distanceValid = distanceMm in RANGE_MINIMUM_MM..RANGE_MAXIMUM_MM
            val expectedHealth =
                (if (statusValid) 0u else RANGE_HEALTH_INVALID_STATUS) or
                    (if (distanceValid) 0u else RANGE_HEALTH_DISTANCE_INVALID)
            val plausibilityMarked = (sample.validityFlags and VALIDITY_PLAUSIBILITY) != 0u
            val eligible = statusValid && distanceValid
            if (sample.healthFlags != expectedHealth) {
                invalid(
                    "VL53L0X health flags 0x${sample.healthFlags.toString(16)} " +
                        "do not match status/distance evidence 0x${expectedHealth.toString(16)}",
                )
            }
            if (plausibilityMarked != eligible) {
                invalid("VL53L0X plausibility flag disagrees with optical validity")
            }
            val expectedQuality = if (eligible) 100 else 0
            if (qualityPercent != expectedQuality) {
                invalid("VL53L0X quality must be $expectedQuality for the supplied evidence")
            }

            lastRangeSequences[sample.instanceId] = sample.sequence
            val reportedLifecycle = snapshot.deviceStatus?.rangefinders?.get(role)
            if (reportedLifecycle != null && reportedLifecycle != RangefinderLifecycle.LIVE) {
                // Extended DeviceStatus is authoritative for control eligibility. A sensor frame
                // already in flight must not make a disabled, initializing, or degraded role live.
                return null
            }
            rangeAwaitingFirstSampleSinceMillis[role.instanceId] = null
            if (eligible) {
                val value = SensorState.Available(
                    SensorSample(
                        value = Vl53l0xTelemetry(
                            role = role,
                            distanceMillimeters = distanceMm,
                            rawRangeStatus = rawStatus,
                            signalQualityPercent = qualityPercent,
                        ),
                        sequence = sample.sequence.toLong(),
                        deviceTimestampMicros = sample.deviceTimestampUs,
                        receivedAtElapsedRealtimeMillis = now,
                        quality = sample.quality(),
                        observedAtElapsedRealtimeMillis = observedAtMillis,
                    ),
                )
                snapshot = snapshot.withRangefinder(role, value)
            } else {
                val previous = snapshot.rangefinder(role).latest()
                val code = rangeErrorCode(rawStatus, distanceValid)
                val error = SensorError(
                    code = code,
                    message = rangeErrorMessage(role, rawStatus, distanceMm, code),
                    occurredAtElapsedRealtimeMillis = now,
                )
                snapshot = snapshot.withRangefinder(role, SensorState.Failed(previous, error))
            }
            // Schema-valid optical failures are intentionally accepted so one
            // difficult target/sunlight event cannot poison the USB session.
            null
        } catch (error: SensorSampleException) {
            val sensorError = SensorError(error.code, error.message, now)
            val role = RangefinderRole.fromInstanceId(sample.instanceId)
            val reportedLifecycle = role?.let {
                snapshot.deviceStatus?.rangefinders?.get(it)
            }
            if (
                role != null &&
                (reportedLifecycle == null || reportedLifecycle == RangefinderLifecycle.LIVE) &&
                snapshot.rangefinder(role) !is SensorState.AwaitingFirstSample
            ) {
                snapshot = snapshot.withRangefinder(
                    role,
                    SensorState.Failed(snapshot.rangefinder(role).latest(), sensorError),
                )
            }
            sensorError
        }

    private fun requireRangeBaseFlags(sample: RawWireSensorSample) {
        val required = VALIDITY_TRANSPORT or VALIDITY_CALIBRATION or VALIDITY_TIMING
        if ((sample.validityFlags and required) != required) {
            throw SensorSampleException(
                SensorErrorCode.INVALID_VALIDITY,
                "VL53L0X validity flags 0x${sample.validityFlags.toString(16)} lack base evidence",
            )
        }
        if ((sample.validityFlags and VALIDITY_CRC) != 0u) {
            invalid("VL53L0X must not claim an unavailable wire CRC")
        }
        if ((sample.validityFlags and VALIDITY_KNOWN_MASK.inv()) != 0u) {
            invalid("VL53L0X contains unknown validity flags")
        }
        if ((sample.qualityFlags and QUALITY_FRESH) == 0u) {
            throw SensorSampleException(SensorErrorCode.NOT_FRESH, "VL53L0X sample is not fresh")
        }
        if ((sample.qualityFlags and QUALITY_KNOWN_MASK.inv()) != 0u) {
            invalid("VL53L0X contains unknown quality flags")
        }
    }

    private fun invalidKnownInstance(name: String, instance: Int, now: Long): SensorError =
        SensorError(
            SensorErrorCode.INVALID_PAYLOAD,
            "$name instance $instance is outside its stable allocation",
            now,
        )

    private fun rangeErrorCode(rawStatus: Int, distanceValid: Boolean): SensorErrorCode = when {
        !distanceValid && (rawStatus == 0 || rawStatus == 11) -> SensorErrorCode.OUT_OF_RANGE
        rawStatus == 1 -> SensorErrorCode.RANGE_SIGMA_FAILURE
        rawStatus == 2 -> SensorErrorCode.RANGE_SIGNAL_FAILURE
        rawStatus == 3 -> SensorErrorCode.RANGE_MINIMUM_FAILURE
        rawStatus == 4 -> SensorErrorCode.RANGE_PHASE_FAILURE
        rawStatus == 5 -> SensorErrorCode.RANGE_HARDWARE_FAILURE
        else -> SensorErrorCode.RANGE_STATUS_UNKNOWN
    }

    private fun rangeErrorMessage(
        role: RangefinderRole,
        rawStatus: Int,
        distanceMm: Int,
        code: SensorErrorCode,
    ): String = "VL53L0X ${role.name} rejected optical result: " +
        "status=$rawStatus distance=${distanceMm}mm code=$code"

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
    if (now - current.sample.observedAtElapsedRealtimeMillis <= threshold) return this
    return SensorState.Stale(
        current.sample,
        current.sample.observedAtElapsedRealtimeMillis + threshold,
    )
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

private fun BoardTelemetrySnapshot.withRangefinder(
    role: RangefinderRole,
    state: SensorState<Vl53l0xTelemetry>,
): BoardTelemetrySnapshot = when (role) {
    RangefinderRole.GROUND -> copy(groundRange = state)
    RangefinderRole.UP -> copy(upRange = state)
    RangefinderRole.FRONT_LEFT -> copy(frontLeftRange = state)
    RangefinderRole.FRONT_RIGHT -> copy(frontRightRange = state)
}

/** Applies only explicit extended-DeviceStatus lifecycle evidence for one physical role. */
private fun SensorState<Vl53l0xTelemetry>.applyLifecycle(
    role: RangefinderRole,
    lifecycle: RangefinderLifecycle,
    receivedAtMillis: Long,
): SensorState<Vl53l0xTelemetry> = when (lifecycle) {
    RangefinderLifecycle.DISABLED_OR_ABSENT -> SensorState.Unavailable(
        SensorUnavailableReason.RANGEFINDER_DISABLED_OR_ABSENT,
    )
    RangefinderLifecycle.INITIALIZING -> SensorState.Unavailable(
        SensorUnavailableReason.RANGEFINDER_INITIALIZING,
    )
    RangefinderLifecycle.LIVE -> when (this) {
        is SensorState.Unavailable -> when (reason) {
            SensorUnavailableReason.RANGEFINDER_DISABLED_OR_ABSENT,
            SensorUnavailableReason.RANGEFINDER_INITIALIZING,
            SensorUnavailableReason.SENSOR_REPORTED_OFFLINE -> SensorState.AwaitingFirstSample
            SensorUnavailableReason.BOARD_DISCONNECTED,
            SensorUnavailableReason.TELEMETRY_NOT_STARTED -> this
        }
        is SensorState.Failed -> if (error.code == SensorErrorCode.RANGEFINDER_DEGRADED) {
            lastSample?.let { SensorState.Stale(it, receivedAtMillis) }
                ?: SensorState.AwaitingFirstSample
        } else {
            this
        }
        else -> this
    }
    RangefinderLifecycle.DEGRADED -> failed(
        last = latest(),
        code = SensorErrorCode.RANGEFINDER_DEGRADED,
        message = "The board reports VL53L0X ${role.name} degraded",
        now = receivedAtMillis,
    )
}

private const val RANGEFINDER_COUNT = 4
private const val RANGE_MINIMUM_MM = 30
private const val RANGE_MAXIMUM_MM = 2_000
private const val RANGE_HEALTH_INVALID_STATUS: UInt = 1u
private const val RANGE_HEALTH_DISTANCE_INVALID: UInt = 2u
private const val VALIDITY_TRANSPORT: UInt = 1u
private const val VALIDITY_CRC: UInt = 2u
private const val VALIDITY_CALIBRATION: UInt = 4u
private const val VALIDITY_TIMING: UInt = 8u
private const val VALIDITY_PLAUSIBILITY: UInt = 16u
private const val VALIDITY_KNOWN_MASK: UInt = 31u
private const val QUALITY_FRESH: UInt = 1u
private const val QUALITY_KNOWN_MASK: UInt = 7u
