/** Public, UI-free state and telemetry models for the Shahbaz Interface Board link. */
package ir.hrka.shahbaz.hardwareconnection

/** Stable identity of the production native-USB interface. */
object ShahbazBoardUsbIdentity {
    /**
     * Exposes the VENDOR_ID value.
     */
    const val VENDOR_ID: Int = 0x303A
    /**
     * Exposes the PRODUCT_ID value.
     */
    const val PRODUCT_ID: Int = 0x4001
}

/**
 * Runs the hasExactShahbazBoardUsbIdentity operation.
 */
internal fun hasExactShahbazBoardUsbIdentity(vendorId: Int, productId: Int): Boolean =
    vendorId == ShahbazBoardUsbIdentity.VENDOR_ID &&
        productId == ShahbazBoardUsbIdentity.PRODUCT_ID

/** Timing policy for one board client. Defaults match the production firmware contract. */
data class HardwareConnectionConfig(
    /**
     * Exposes the initialQnhHectopascal value.
     */
    val initialQnhHectopascal: Double = 1013.25,
    /**
     * Exposes the sensorStaleAfterMillis value.
     */
    val sensorStaleAfterMillis: Long = 2_500,
    /**
     * Exposes the sensorTimestampFutureToleranceMillis value.
     */
    val sensorTimestampFutureToleranceMillis: Long = 500,
    /**
     * Exposes the firstSensorSampleTimeoutMillis value.
     */
    val firstSensorSampleTimeoutMillis: Long = 5_000,
    /**
     * Exposes the maximumUnknownSensors value.
     */
    val maximumUnknownSensors: Int = 32,
    /**
     * Exposes the initialTimeSyncMaximumAttempts value.
     */
    val initialTimeSyncMaximumAttempts: Int = 4,
    /**
     * Exposes the initialTimeSyncRetryIntervalMillis value.
     */
    val initialTimeSyncRetryIntervalMillis: Long = 500,
    /**
     * Exposes the handshakeTimeoutMillis value.
     */
    val handshakeTimeoutMillis: Long = 2_000,
    /**
     * Exposes the heartbeatIntervalMillis value.
     */
    val heartbeatIntervalMillis: Long = 350,
    /**
     * Exposes the heartbeatTimeoutMillis value.
     */
    val heartbeatTimeoutMillis: Long = 1_000,
    /**
     * Enables public actuator transmission methods. The default keeps the board link telemetry-only.
     */
    val allowActuatorCommands: Boolean = false,
    /**
     * Exposes the motorPulseBounds value.
     */
    val motorPulseBounds: BoardPulseBounds = BoardPulseBounds(900, 2_100),
    /**
     * Exposes the servoPulseBounds value.
     */
    val servoPulseBounds: BoardPulseBounds = BoardPulseBounds(500, 2_500),
    /**
     * Exposes the maximumMotorCommandBatch value.
     */
    val maximumMotorCommandBatch: Int = 4,
    /**
     * Exposes the maximumServoCommandBatch value.
     */
    val maximumServoCommandBatch: Int = 8,
    /** Rejects actuator frames that were generated too long before submission. */
    val maximumActuatorCommandAgeMillis: Long = 100,
    /** Maximum time a transmitted actuator command may remain without an explicit board ACK. */
    val actuatorAcknowledgementTimeoutMillis: Long = 250,
    /** Hard bound for commands already written to USB but not yet acknowledged by the board. */
    val maximumPendingActuatorAcknowledgements: Int = 32,
    /** Hard bound for ordinary actuator submissions waiting on the serialized USB dispatcher. */
    val maximumQueuedActuatorSubmissions: Int = 2,
) {
    init {
        require(initialQnhHectopascal.isFinite() && initialQnhHectopascal in 800.0..1100.0)
        require(sensorStaleAfterMillis in 1..Long.MAX_VALUE / 1_000)
        require(sensorTimestampFutureToleranceMillis in 0..Long.MAX_VALUE / 1_000)
        require(firstSensorSampleTimeoutMillis > 0)
        require(maximumUnknownSensors in 1..256)
        require(initialTimeSyncMaximumAttempts in 1..10)
        require(initialTimeSyncRetryIntervalMillis > 0)
        require(handshakeTimeoutMillis > 0)
        require(heartbeatIntervalMillis in 100..heartbeatTimeoutMillis)
        require(heartbeatTimeoutMillis > 0)
        require(maximumMotorCommandBatch in 1..256)
        require(maximumServoCommandBatch in 1..256)
        require(maximumActuatorCommandAgeMillis in 1..1_000)
        require(actuatorAcknowledgementTimeoutMillis in 20..2_000)
        require(maximumPendingActuatorAcknowledgements in 1..256)
        require(maximumQueuedActuatorSubmissions in 1..16)
    }
}

/** Inclusive PWM pulse bounds in microseconds for one actuator output family. */
data class BoardPulseBounds(
    /**
     * Exposes the minimumMicros value.
     */
    val minimumMicros: Int,
    /**
     * Exposes the maximumMicros value.
     */
    val maximumMicros: Int,
) {
    init {
        require(minimumMicros in 1..65_535)
        require(maximumMicros in minimumMicros..65_535)
    }

    /**
     * Runs the contains operation.
     */
    fun contains(pulseMicros: Int): Boolean = pulseMicros in minimumMicros..maximumMicros
}

/**
 * Documents the BoardMotorPulse type and the role it plays in this module.
 */
data class BoardMotorPulse(
    /**
     * Exposes the channel value.
     */
    val channel: Int,
    /**
     * Exposes the pulseMicros value.
     */
    val pulseMicros: Int,
) {
    init {
        require(channel in 0..255)
        require(pulseMicros in 1..65_535)
    }
}

/**
 * Documents the BoardServoPulse type and the role it plays in this module.
 */
data class BoardServoPulse(
    /**
     * Exposes the channel value.
     */
    val channel: Int,
    /**
     * Exposes the pulseMicros value.
     */
    val pulseMicros: Int,
) {
    init {
        require(channel in 0..255)
        require(pulseMicros in 1..65_535)
    }
}

/**
 * Defines the BoardActuatorCommandResult contract used by this module.
 */
sealed interface BoardActuatorCommandResult {
    /** Admitted wire-command count; this is not board application or ACK. A motor frame counts once. */
    data class Queued(val commandCount: Int) : BoardActuatorCommandResult {
        init {
            require(commandCount > 0)
        }
    }
    /**
     * Documents the Rejected type and the role it plays in this module.
     */
    data class Rejected(
        /**
         * Exposes the reason value.
         */
        val reason: BoardActuatorRejection,
        /**
         * Exposes the message value.
         */
        val message: String,
    ) : BoardActuatorCommandResult
}

/**
 * Documents the BoardActuatorRejection type and the role it plays in this module.
 */
enum class BoardActuatorRejection {
    CLOSED,
    DISABLED_BY_CONFIG,
    NOT_READY,
    ACTUATOR_UNAVAILABLE,
    EMPTY_BATCH,
    BATCH_TOO_LARGE,
    INCOMPLETE_MOTOR_FRAME,
    INVALID_CHANNEL,
    INVALID_PULSE,
    STALE_COMMAND,
    FUTURE_COMMAND,
    QUEUE_SATURATED,
    INTERNAL_ERROR,
}

/** Safe descriptor data that does not expose Android USB handles to consumers. */
data class BoardUsbDevice(
    /**
     * Exposes the deviceId value.
     */
    val deviceId: Int,
    /**
     * Exposes the deviceName value.
     */
    val deviceName: String,
    /**
     * Exposes the vendorId value.
     */
    val vendorId: Int,
    /**
     * Exposes the productId value.
     */
    val productId: Int,
)

/** Runtime facts returned by the board's Protocol v2 DeviceInfo response. */
data class BoardDeviceInfo(
    /**
     * Exposes the protocolVersion value.
     */
    val protocolVersion: Int,
    /**
     * Exposes the target value.
     */
    val target: BoardTarget,
    /**
     * Exposes the supportedMotorChannels value.
     */
    val supportedMotorChannels: Int,
    /**
     * Exposes the supportedServoChannels value.
     */
    val supportedServoChannels: Int,
    /**
     * Exposes the detectedFlashBytes value.
     */
    val detectedFlashBytes: Long,
    /**
     * Exposes the detectedPsramBytes value.
     */
    val detectedPsramBytes: Long,
    /**
     * Exposes the boardValidationIssueMask value.
     */
    val boardValidationIssueMask: Long,
    /**
     * Exposes the activeMotorChannels value.
     */
    val activeMotorChannels: Int,
    /**
     * Exposes the activeServoChannels value.
     */
    val activeServoChannels: Int,
    /**
     * Exposes the actuatorAvailable value.
     */
    val actuatorAvailable: Boolean,
    /**
     * Exposes the actuatorsEnabledByConfiguration value.
     */
    val actuatorsEnabledByConfiguration: Boolean,
)

/**
 * Documents the BoardTarget type and the role it plays in this module.
 */
enum class BoardTarget { ESP32_S3 }

/** Typed connection lifecycle. Only [Ready] means the dashboard may trust the board link. */
sealed interface BoardConnectionState {
    /**
     * Provides the singleton Stopped services for this module.
     */
    data object Stopped : BoardConnectionState
    /**
     * Provides the singleton Searching services for this module.
     */
    data object Searching : BoardConnectionState
    /**
     * Documents the PermissionRequired type and the role it plays in this module.
     */
    data class PermissionRequired(val device: BoardUsbDevice) : BoardConnectionState
    /**
     * Documents the RequestingPermission type and the role it plays in this module.
     */
    data class RequestingPermission(val device: BoardUsbDevice) : BoardConnectionState
    /**
     * Documents the Opening type and the role it plays in this module.
     */
    data class Opening(val device: BoardUsbDevice) : BoardConnectionState
    /**
     * Documents the Synchronizing type and the role it plays in this module.
     */
    data class Synchronizing(val device: BoardUsbDevice) : BoardConnectionState
    /**
     * Documents the ValidatingDevice type and the role it plays in this module.
     */
    data class ValidatingDevice(val device: BoardUsbDevice) : BoardConnectionState
    /**
     * Documents the AwaitingHeartbeat type and the role it plays in this module.
     */
    data class AwaitingHeartbeat(
        /**
         * Exposes the device value.
         */
        val device: BoardUsbDevice,
        /**
         * Exposes the deviceInfo value.
         */
        val deviceInfo: BoardDeviceInfo,
    ) : BoardConnectionState
    /**
     * Documents the StartingTelemetry type and the role it plays in this module.
     */
    data class StartingTelemetry(
        /**
         * Exposes the device value.
         */
        val device: BoardUsbDevice,
        /**
         * Exposes the deviceInfo value.
         */
        val deviceInfo: BoardDeviceInfo,
    ) : BoardConnectionState

    /** TimeSync, DeviceInfo, HeartbeatAck, and StartTelemetry acknowledgement all succeeded. */
    data class Ready(
        /**
         * Exposes the device value.
         */
        val device: BoardUsbDevice,
        /**
         * Exposes the deviceInfo value.
         */
        val deviceInfo: BoardDeviceInfo,
        /**
         * Exposes the connectedAtElapsedRealtimeMillis value.
         */
        val connectedAtElapsedRealtimeMillis: Long,
    ) : BoardConnectionState

    /**
     * Documents the Disconnected type and the role it plays in this module.
     */
    data class Disconnected(val reason: BoardDisconnectReason) : BoardConnectionState
    /**
     * Documents the Failed type and the role it plays in this module.
     */
    data class Failed(val error: BoardLinkError) : BoardConnectionState
}

/**
 * Documents the BoardDisconnectReason type and the role it plays in this module.
 */
enum class BoardDisconnectReason {
    USB_DETACHED,
    APP_STOPPED,
    TRANSPORT_CLOSED,
}

/**
 * Documents the BoardLinkError type and the role it plays in this module.
 */
data class BoardLinkError(
    /**
     * Exposes the code value.
     */
    val code: BoardLinkErrorCode,
    /**
     * Exposes the message value.
     */
    val message: String,
    /**
     * Exposes the recoverable value.
     */
    val recoverable: Boolean,
)

/**
 * Documents the BoardLinkErrorCode type and the role it plays in this module.
 */
enum class BoardLinkErrorCode {
    USB_HOST_UNAVAILABLE,
    MULTIPLE_MATCHING_BOARDS,
    PERMISSION_DENIED,
    DEVICE_OPEN_FAILED,
    INCOMPATIBLE_USB_INTERFACE,
    USB_READ_FAILED,
    USB_WRITE_FAILED,
    TIME_SYNC_TIMEOUT,
    TIME_SYNC_REJECTED,
    DEVICE_INFO_TIMEOUT,
    DEVICE_INFO_INVALID,
    HEARTBEAT_TIMEOUT,
    TELEMETRY_START_TIMEOUT,
    SESSION_REJECTED,
    ACTUATOR_COMMAND_REJECTED,
    ACTUATOR_ACK_TIMEOUT,
    ACTUATOR_BACKPRESSURE,
    PROTOCOL_ERROR,
    INTERNAL_ERROR,
    RECEIVER_REGISTRATION_FAILED,
}

/** One external sensor's independent availability and failure state. */
sealed interface SensorState<out T> {
    /**
     * Documents the Unavailable type and the role it plays in this module.
     */
    data class Unavailable(val reason: SensorUnavailableReason) : SensorState<Nothing>
    /**
     * Provides the singleton AwaitingFirstSample services for this module.
     */
    data object AwaitingFirstSample : SensorState<Nothing>
    /**
     * Documents the Available type and the role it plays in this module.
     */
    data class Available<T>(val sample: SensorSample<T>) : SensorState<T>
    /**
     * Documents the Stale type and the role it plays in this module.
     */
    data class Stale<T>(
        /**
         * Exposes the lastSample value.
         */
        val lastSample: SensorSample<T>?,
        /**
         * Exposes the staleSinceElapsedRealtimeMillis value.
         */
        val staleSinceElapsedRealtimeMillis: Long,
    ) : SensorState<T>

    /**
     * Documents the Failed type and the role it plays in this module.
     */
    data class Failed<T>(
        /**
         * Exposes the lastSample value.
         */
        val lastSample: SensorSample<T>?,
        /**
         * Exposes the error value.
         */
        val error: SensorError,
    ) : SensorState<T>
}

/**
 * Documents the SensorUnavailableReason type and the role it plays in this module.
 */
enum class SensorUnavailableReason {
    BOARD_DISCONNECTED,
    TELEMETRY_NOT_STARTED,
    SENSOR_REPORTED_OFFLINE,
    RANGEFINDER_DISABLED_OR_ABSENT,
    RANGEFINDER_INITIALIZING,
}

/**
 * Documents the SensorError type and the role it plays in this module.
 */
data class SensorError(
    /**
     * Exposes the code value.
     */
    val code: SensorErrorCode,
    /**
     * Exposes the message value.
     */
    val message: String,
    /**
     * Exposes the occurredAtElapsedRealtimeMillis value.
     */
    val occurredAtElapsedRealtimeMillis: Long,
)

/**
 * Documents the SensorErrorCode type and the role it plays in this module.
 */
enum class SensorErrorCode {
    NO_RESPONSE,
    INVALID_PAYLOAD,
    INVALID_VALIDITY,
    NOT_FRESH,
    HEALTH_FAULT,
    OUT_OF_RANGE,
    RANGE_SIGMA_FAILURE,
    RANGE_SIGNAL_FAILURE,
    RANGE_MINIMUM_FAILURE,
    RANGE_PHASE_FAILURE,
    RANGE_HARDWARE_FAILURE,
    RANGE_STATUS_UNKNOWN,
    RANGEFINDER_DEGRADED,
    SENSOR_OFFLINE,
}

/**
 * Documents the SensorSample type and the role it plays in this module.
 */
data class SensorSample<out T>(
    /**
     * Exposes the value value.
     */
    val value: T,
    /**
     * Exposes the sequence value.
     */
    val sequence: Long,
    /**
     * Exposes the deviceTimestampMicros value.
     */
    val deviceTimestampMicros: ULong,
    /**
     * Exposes the receivedAtElapsedRealtimeMillis value.
     */
    val receivedAtElapsedRealtimeMillis: Long,
    /**
     * Exposes the quality value.
     */
    val quality: SensorSampleQuality,
    /** TimeSync-mapped host time at which the board acquired this measurement. */
    val observedAtElapsedRealtimeMillis: Long = receivedAtElapsedRealtimeMillis,
)

/**
 * Documents the SensorSampleQuality type and the role it plays in this module.
 */
data class SensorSampleQuality(
    /**
     * Exposes the recoveredAfterError value.
     */
    val recoveredAfterError: Boolean,
    /**
     * Exposes the rateLimited value.
     */
    val rateLimited: Boolean,
    /**
     * Exposes the rawValidityFlags value.
     */
    val rawValidityFlags: Long,
    /**
     * Exposes the rawQualityFlags value.
     */
    val rawQualityFlags: Long,
    /**
     * Exposes the rawHealthFlags value.
     */
    val rawHealthFlags: Long,
)

/**
 * Documents the Sht30Telemetry type and the role it plays in this module.
 */
data class Sht30Telemetry(
    /**
     * Exposes the temperatureCelsius value.
     */
    val temperatureCelsius: Double,
    /**
     * Exposes the relativeHumidityPercent value.
     */
    val relativeHumidityPercent: Double,
)

/**
 * Documents the Ms5611Telemetry type and the role it plays in this module.
 */
data class Ms5611Telemetry(
    /**
     * Exposes the pressurePascal value.
     */
    val pressurePascal: Int,
    /**
     * Exposes the temperatureCelsius value.
     */
    val temperatureCelsius: Double,
    /**
     * Exposes the altitudeAboveMeanSeaLevelMeters value.
     */
    val altitudeAboveMeanSeaLevelMeters: Double,
    /**
     * Exposes the qnhHectopascal value.
     */
    val qnhHectopascal: Double,
)

/** Stable physical role allocated to each of the four VL53L0X instances. */
enum class RangefinderRole(val instanceId: Int) {
    GROUND(0),
    UP(1),
    FRONT_LEFT(2),
    FRONT_RIGHT(3),
    ;

    companion object {
        fun fromInstanceId(instanceId: Int): RangefinderRole? =
            entries.firstOrNull { it.instanceId == instanceId }
    }
}

/** Board-reported lifecycle for one fixed physical VL53L0X role. */
enum class RangefinderLifecycle {
    DISABLED_OR_ABSENT,
    INITIALIZING,
    LIVE,
    DEGRADED,
}

/** One coherent DeviceStatus lifecycle snapshot for all four fixed rangefinder roles. */
data class RangefinderLifecycleStatus(
    val ground: RangefinderLifecycle,
    val up: RangefinderLifecycle,
    val frontLeft: RangefinderLifecycle,
    val frontRight: RangefinderLifecycle,
) {
    operator fun get(role: RangefinderRole): RangefinderLifecycle = when (role) {
        RangefinderRole.GROUND -> ground
        RangefinderRole.UP -> up
        RangefinderRole.FRONT_LEFT -> frontLeft
        RangefinderRole.FRONT_RIGHT -> frontRight
    }
}

/** One schema-valid and optically valid VL53L0X observation. */
data class Vl53l0xTelemetry(
    val role: RangefinderRole,
    val distanceMillimeters: Int,
    val rawRangeStatus: Int,
    val signalQualityPercent: Int,
    val minimumControlDistanceMillimeters: Int = 30,
    val maximumControlDistanceMillimeters: Int = 2_000,
    val fieldOfViewDegrees: Int = 25,
) {
    init {
        require(distanceMillimeters in minimumControlDistanceMillimeters..maximumControlDistanceMillimeters)
        require(rawRangeStatus == 0 || rawRangeStatus == 11)
        require(signalQualityPercent in 0..100)
        require(minimumControlDistanceMillimeters > 0)
        require(maximumControlDistanceMillimeters >= minimumControlDistanceMillimeters)
        require(fieldOfViewDegrees in 1..180)
    }

    val distanceMeters: Double
        get() = distanceMillimeters / 1_000.0
}

/** Forward-compatible raw representation for sensor IDs not yet known by this library. */
data class RawSensorSample(
    /**
     * Exposes the sensorId value.
     */
    val sensorId: Int,
    /**
     * Exposes the instanceId value.
     */
    val instanceId: Int,
    /**
     * Exposes the sequence value.
     */
    val sequence: Long,
    /**
     * Exposes the deviceTimestampMicros value.
     */
    val deviceTimestampMicros: ULong,
    /**
     * Exposes the validityFlags value.
     */
    val validityFlags: Long,
    /**
     * Exposes the qualityFlags value.
     */
    val qualityFlags: Long,
    /**
     * Exposes the healthFlags value.
     */
    val healthFlags: Long,
    /**
     * Exposes the fields value.
     */
    val fields: List<RawSensorField>,
    /**
     * Exposes the receivedAtElapsedRealtimeMillis value.
     */
    val receivedAtElapsedRealtimeMillis: Long,
)

/**
 * Documents the SensorKey type and the role it plays in this module.
 */
data class SensorKey(
    /**
     * Exposes the sensorId value.
     */
    val sensorId: Int,
    /**
     * Exposes the instanceId value.
     */
    val instanceId: Int,
)

/**
 * Documents the RawSensorField type and the role it plays in this module.
 */
data class RawSensorField(
    /**
     * Exposes the fieldId value.
     */
    val fieldId: Int,
    /**
     * Exposes the type value.
     */
    val type: RawSensorFieldType,
    /** Exact unsigned wire bits; consumers choose signed or unsigned interpretation from [type]. */
    val rawBits: UInt,
)

/**
 * Documents the RawSensorFieldType type and the role it plays in this module.
 */
enum class RawSensorFieldType { SIGNED_32, UNSIGNED_32 }

/**
 * Documents the BoardDeviceStatus type and the role it plays in this module.
 */
data class BoardDeviceStatus(
    /**
     * Exposes the safetyStateCode value.
     */
    val safetyStateCode: Int,
    /**
     * Exposes the communicationStateCode value.
     */
    val communicationStateCode: Int,
    /**
     * Exposes the telemetryEnabled value.
     */
    val telemetryEnabled: Boolean,
    /**
     * Exposes the actuatorArmed value.
     */
    val actuatorArmed: Boolean,
    /**
     * Exposes the sht30Online value.
     */
    val sht30Online: Boolean,
    /**
     * Exposes the ms5611Online value.
     */
    val ms5611Online: Boolean,
    /**
     * Exposes the receivedAtElapsedRealtimeMillis value.
     */
    val receivedAtElapsedRealtimeMillis: Long,
    /** Null only when an older board emitted the exact six-byte legacy DeviceStatus payload. */
    val rangefinders: RangefinderLifecycleStatus? = null,
)

/**
 * Documents the BoardLinkDiagnostics type and the role it plays in this module.
 */
data class BoardLinkDiagnostics(
    /**
     * Exposes the acceptedFrames value.
     */
    val acceptedFrames: Long = 0,
    /**
     * Exposes the rejectedFrames value.
     */
    val rejectedFrames: Long = 0,
    /**
     * Exposes the crcOrFramingErrors value.
     */
    val crcOrFramingErrors: Long = 0,
    /**
     * Exposes the unknownSensorSamples value.
     */
    val unknownSensorSamples: Long = 0,
    /**
     * Exposes the lastProtocolWarning value.
     */
    val lastProtocolWarning: String? = null,
)

/** Atomic dashboard-facing view of all external board telemetry. */
data class BoardTelemetrySnapshot(
    /**
     * Exposes the sht30 value.
     */
    val sht30: SensorState<Sht30Telemetry> = SensorState.Unavailable(
        SensorUnavailableReason.BOARD_DISCONNECTED,
    ),
    /**
     * Exposes the ms5611 value.
     */
    val ms5611: SensorState<Ms5611Telemetry> = SensorState.Unavailable(
        SensorUnavailableReason.BOARD_DISCONNECTED,
    ),
    val groundRange: SensorState<Vl53l0xTelemetry> = SensorState.Unavailable(
        SensorUnavailableReason.BOARD_DISCONNECTED,
    ),
    val upRange: SensorState<Vl53l0xTelemetry> = SensorState.Unavailable(
        SensorUnavailableReason.BOARD_DISCONNECTED,
    ),
    val frontLeftRange: SensorState<Vl53l0xTelemetry> = SensorState.Unavailable(
        SensorUnavailableReason.BOARD_DISCONNECTED,
    ),
    val frontRightRange: SensorState<Vl53l0xTelemetry> = SensorState.Unavailable(
        SensorUnavailableReason.BOARD_DISCONNECTED,
    ),
    /**
     * Exposes the unknownSensors value.
     */
    val unknownSensors: Map<SensorKey, RawSensorSample> = emptyMap(),
    /**
     * Exposes the deviceStatus value.
     */
    val deviceStatus: BoardDeviceStatus? = null,
    /**
     * Exposes the diagnostics value.
     */
    val diagnostics: BoardLinkDiagnostics = BoardLinkDiagnostics(),
) {
    fun rangefinder(role: RangefinderRole): SensorState<Vl53l0xTelemetry> = when (role) {
        RangefinderRole.GROUND -> groundRange
        RangefinderRole.UP -> upRange
        RangefinderRole.FRONT_LEFT -> frontLeftRange
        RangefinderRole.FRONT_RIGHT -> frontRightRange
    }
}
