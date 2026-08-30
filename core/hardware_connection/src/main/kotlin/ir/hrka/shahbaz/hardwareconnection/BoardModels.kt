/** Public, UI-free state and telemetry models for the Shahbaz Interface Board link. */
package ir.hrka.shahbaz.hardwareconnection

/** Stable identity of the production native-USB interface. */
object ShahbazBoardUsbIdentity {
    const val VENDOR_ID: Int = 0x303A
    const val PRODUCT_ID: Int = 0x4001
}

internal fun hasExactShahbazBoardUsbIdentity(vendorId: Int, productId: Int): Boolean =
    vendorId == ShahbazBoardUsbIdentity.VENDOR_ID &&
        productId == ShahbazBoardUsbIdentity.PRODUCT_ID

/** Timing policy for one board client. Defaults match the production firmware contract. */
data class HardwareConnectionConfig(
    val initialQnhHectopascal: Double = 1013.25,
    val sensorStaleAfterMillis: Long = 2_500,
    val sensorTimestampFutureToleranceMillis: Long = 500,
    val firstSensorSampleTimeoutMillis: Long = 5_000,
    val maximumUnknownSensors: Int = 32,
    val initialTimeSyncMaximumAttempts: Int = 4,
    val initialTimeSyncRetryIntervalMillis: Long = 500,
    val handshakeTimeoutMillis: Long = 2_000,
    val heartbeatIntervalMillis: Long = 350,
    val heartbeatTimeoutMillis: Long = 1_000,
    /**
     * Enables public actuator transmission methods. The default keeps the board link telemetry-only.
     */
    val allowActuatorCommands: Boolean = false,
    val motorPulseBounds: BoardPulseBounds = BoardPulseBounds(900, 2_100),
    val servoPulseBounds: BoardPulseBounds = BoardPulseBounds(500, 2_500),
    val maximumMotorCommandBatch: Int = 4,
    val maximumServoCommandBatch: Int = 8,
    /** Rejects actuator frames that were generated too long before submission. */
    val maximumActuatorCommandAgeMillis: Long = 100,
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
    }
}

/** Inclusive PWM pulse bounds in microseconds for one actuator output family. */
data class BoardPulseBounds(
    val minimumMicros: Int,
    val maximumMicros: Int,
) {
    init {
        require(minimumMicros in 1..65_535)
        require(maximumMicros in minimumMicros..65_535)
    }

    fun contains(pulseMicros: Int): Boolean = pulseMicros in minimumMicros..maximumMicros
}

data class BoardMotorPulse(
    val channel: Int,
    val pulseMicros: Int,
) {
    init {
        require(channel in 0..255)
        require(pulseMicros in 1..65_535)
    }
}

data class BoardServoPulse(
    val channel: Int,
    val pulseMicros: Int,
) {
    init {
        require(channel in 0..255)
        require(pulseMicros in 1..65_535)
    }
}

sealed interface BoardActuatorCommandResult {
    data class Queued(val commandCount: Int) : BoardActuatorCommandResult
    data class Rejected(
        val reason: BoardActuatorRejection,
        val message: String,
    ) : BoardActuatorCommandResult
}

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
    INTERNAL_ERROR,
}

/** Safe descriptor data that does not expose Android USB handles to consumers. */
data class BoardUsbDevice(
    val deviceId: Int,
    val deviceName: String,
    val vendorId: Int,
    val productId: Int,
)

/** Runtime facts returned by the board's Protocol v2 DeviceInfo response. */
data class BoardDeviceInfo(
    val protocolVersion: Int,
    val target: BoardTarget,
    val supportedMotorChannels: Int,
    val supportedServoChannels: Int,
    val detectedFlashBytes: Long,
    val detectedPsramBytes: Long,
    val boardValidationIssueMask: Long,
    val activeMotorChannels: Int,
    val activeServoChannels: Int,
    val actuatorAvailable: Boolean,
    val actuatorsEnabledByConfiguration: Boolean,
)

enum class BoardTarget { ESP32_S3 }

/** Typed connection lifecycle. Only [Ready] means the dashboard may trust the board link. */
sealed interface BoardConnectionState {
    data object Stopped : BoardConnectionState
    data object Searching : BoardConnectionState
    data class PermissionRequired(val device: BoardUsbDevice) : BoardConnectionState
    data class RequestingPermission(val device: BoardUsbDevice) : BoardConnectionState
    data class Opening(val device: BoardUsbDevice) : BoardConnectionState
    data class Synchronizing(val device: BoardUsbDevice) : BoardConnectionState
    data class ValidatingDevice(val device: BoardUsbDevice) : BoardConnectionState
    data class AwaitingHeartbeat(
        val device: BoardUsbDevice,
        val deviceInfo: BoardDeviceInfo,
    ) : BoardConnectionState
    data class StartingTelemetry(
        val device: BoardUsbDevice,
        val deviceInfo: BoardDeviceInfo,
    ) : BoardConnectionState

    /** TimeSync, DeviceInfo, HeartbeatAck, and StartTelemetry acknowledgement all succeeded. */
    data class Ready(
        val device: BoardUsbDevice,
        val deviceInfo: BoardDeviceInfo,
        val connectedAtElapsedRealtimeMillis: Long,
    ) : BoardConnectionState

    data class Disconnected(val reason: BoardDisconnectReason) : BoardConnectionState
    data class Failed(val error: BoardLinkError) : BoardConnectionState
}

enum class BoardDisconnectReason {
    USB_DETACHED,
    APP_STOPPED,
    TRANSPORT_CLOSED,
}

data class BoardLinkError(
    val code: BoardLinkErrorCode,
    val message: String,
    val recoverable: Boolean,
)

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
    PROTOCOL_ERROR,
    INTERNAL_ERROR,
    RECEIVER_REGISTRATION_FAILED,
}

/** One external sensor's independent availability and failure state. */
sealed interface SensorState<out T> {
    data class Unavailable(val reason: SensorUnavailableReason) : SensorState<Nothing>
    data object AwaitingFirstSample : SensorState<Nothing>
    data class Available<T>(val sample: SensorSample<T>) : SensorState<T>
    data class Stale<T>(
        val lastSample: SensorSample<T>?,
        val staleSinceElapsedRealtimeMillis: Long,
    ) : SensorState<T>

    data class Failed<T>(
        val lastSample: SensorSample<T>?,
        val error: SensorError,
    ) : SensorState<T>
}

enum class SensorUnavailableReason {
    BOARD_DISCONNECTED,
    TELEMETRY_NOT_STARTED,
    SENSOR_REPORTED_OFFLINE,
}

data class SensorError(
    val code: SensorErrorCode,
    val message: String,
    val occurredAtElapsedRealtimeMillis: Long,
)

enum class SensorErrorCode {
    NO_RESPONSE,
    INVALID_PAYLOAD,
    INVALID_VALIDITY,
    NOT_FRESH,
    HEALTH_FAULT,
    OUT_OF_RANGE,
    SENSOR_OFFLINE,
}

data class SensorSample<out T>(
    val value: T,
    val sequence: Long,
    val deviceTimestampMicros: ULong,
    val receivedAtElapsedRealtimeMillis: Long,
    val quality: SensorSampleQuality,
)

data class SensorSampleQuality(
    val recoveredAfterError: Boolean,
    val rateLimited: Boolean,
    val rawValidityFlags: Long,
    val rawQualityFlags: Long,
    val rawHealthFlags: Long,
)

data class Sht30Telemetry(
    val temperatureCelsius: Double,
    val relativeHumidityPercent: Double,
)

data class Ms5611Telemetry(
    val pressurePascal: Int,
    val temperatureCelsius: Double,
    val altitudeAboveMeanSeaLevelMeters: Double,
    val qnhHectopascal: Double,
)

/** Forward-compatible raw representation for sensor IDs not yet known by this library. */
data class RawSensorSample(
    val sensorId: Int,
    val instanceId: Int,
    val sequence: Long,
    val deviceTimestampMicros: ULong,
    val validityFlags: Long,
    val qualityFlags: Long,
    val healthFlags: Long,
    val fields: List<RawSensorField>,
    val receivedAtElapsedRealtimeMillis: Long,
)

data class SensorKey(
    val sensorId: Int,
    val instanceId: Int,
)

data class RawSensorField(
    val fieldId: Int,
    val type: RawSensorFieldType,
    /** Exact unsigned wire bits; consumers choose signed or unsigned interpretation from [type]. */
    val rawBits: UInt,
)

enum class RawSensorFieldType { SIGNED_32, UNSIGNED_32 }

data class BoardDeviceStatus(
    val safetyStateCode: Int,
    val communicationStateCode: Int,
    val telemetryEnabled: Boolean,
    val actuatorArmed: Boolean,
    val sht30Online: Boolean,
    val ms5611Online: Boolean,
    val receivedAtElapsedRealtimeMillis: Long,
)

data class BoardLinkDiagnostics(
    val acceptedFrames: Long = 0,
    val rejectedFrames: Long = 0,
    val crcOrFramingErrors: Long = 0,
    val unknownSensorSamples: Long = 0,
    val lastProtocolWarning: String? = null,
)

/** Atomic dashboard-facing view of all external board telemetry. */
data class BoardTelemetrySnapshot(
    val sht30: SensorState<Sht30Telemetry> = SensorState.Unavailable(
        SensorUnavailableReason.BOARD_DISCONNECTED,
    ),
    val ms5611: SensorState<Ms5611Telemetry> = SensorState.Unavailable(
        SensorUnavailableReason.BOARD_DISCONNECTED,
    ),
    val unknownSensors: Map<SensorKey, RawSensorSample> = emptyMap(),
    val deviceStatus: BoardDeviceStatus? = null,
    val diagnostics: BoardLinkDiagnostics = BoardLinkDiagnostics(),
)
