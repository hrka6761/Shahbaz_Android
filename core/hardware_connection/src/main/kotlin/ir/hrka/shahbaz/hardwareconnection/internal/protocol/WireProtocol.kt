/** Bounded Shahbaz Protocol v2 framing, guarded host requests, and response decoders. */
package ir.hrka.shahbaz.hardwareconnection.internal.protocol

import ir.hrka.shahbaz.hardwareconnection.BoardDeviceInfo
import ir.hrka.shahbaz.hardwareconnection.BoardMotorPulse
import ir.hrka.shahbaz.hardwareconnection.BoardTarget
import ir.hrka.shahbaz.hardwareconnection.RawSensorField
import ir.hrka.shahbaz.hardwareconnection.RawSensorFieldType
import ir.hrka.shahbaz.hardwareconnection.RangefinderLifecycle
import ir.hrka.shahbaz.hardwareconnection.RangefinderLifecycleStatus
import ir.hrka.shahbaz.hardwareconnection.internal.QUAD_X_MOTOR_CHANNEL_COUNT
import java.util.concurrent.CancellationException
import kotlin.math.pow

/**
 * Provides the singleton WireContract services for this module.
 */
internal object WireContract {
    /**
     * Exposes the VERSION value.
     */
    const val VERSION = 2
    /**
     * Exposes the HEADER_LENGTH value.
     */
    const val HEADER_LENGTH = 22
    /**
     * Exposes the CRC_LENGTH value.
     */
    const val CRC_LENGTH = 4
    /**
     * Exposes the MAX_PAYLOAD_LENGTH value.
     */
    const val MAX_PAYLOAD_LENGTH = 512
    /**
     * Exposes the MAX_DECODED_FRAME_LENGTH value.
     */
    const val MAX_DECODED_FRAME_LENGTH = HEADER_LENGTH + MAX_PAYLOAD_LENGTH + CRC_LENGTH
    /**
     * Exposes the MAX_COBS_BODY_LENGTH value.
     */
    const val MAX_COBS_BODY_LENGTH =
        MAX_DECODED_FRAME_LENGTH + (MAX_DECODED_FRAME_LENGTH / 254) + 1
    /**
     * Exposes the MAX_DELIMITED_FRAME_LENGTH value.
     */
    const val MAX_DELIMITED_FRAME_LENGTH = MAX_COBS_BODY_LENGTH + 1
    /**
     * Exposes the DELIMITER value.
     */
    const val DELIMITER: Byte = 0
}

/**
 * Documents the MessagePriority type and the role it plays in this module.
 */
internal enum class MessagePriority(val wireValue: Int) {
    CRITICAL(0), HIGH(1), NORMAL(2), LOW(3);

    companion object {
        /**
         * Runs the fromWire operation.
         */
        fun fromWire(value: Int): MessagePriority = entries.firstOrNull { it.wireValue == value }
            ?: throw ProtocolException(ProtocolErrorKind.INVALID_HEADER, "invalid priority $value")
    }
}

/**
 * Documents the MessageType type and the role it plays in this module.
 */
internal enum class MessageType(val wireValue: Int) {
    DEVICE_INFO_REQUEST(0x0001), DEVICE_INFO_RESPONSE(0x0002),
    START_TELEMETRY(0x0010), STOP_TELEMETRY(0x0011), SET_SENSOR_RATE(0x0012),
    SENSOR_SAMPLE(0x0020),
    DEVICE_STATUS_REQUEST(0x0030), DEVICE_STATUS_RESPONSE(0x0031),
    HEARTBEAT(0x0040), HEARTBEAT_ACK(0x0041), PING(0x0042), PONG(0x0043),
    TIME_SYNC_REQUEST(0x0050), TIME_SYNC_RESPONSE(0x0051),
    COMMAND_ACK(0x0060), COMMAND_NACK(0x0061), PROTOCOL_ERROR(0x0062), SAFETY_STATE(0x0063),
    EMERGENCY_STOP(0x0070), DISARM(0x0071),
    ARM_REQUEST(0x8000), ARM_CONFIRM(0x8001), ACTUATOR_COMMAND(0x8010),
    MOTOR_COMMAND(0x8011), SERVO_COMMAND(0x8012), SET_CONTROL_MODE(0x8013),
    MOTOR_FRAME_COMMAND(0x8014);

    companion object {
        /**
         * Runs the fromWire operation.
         */
        fun fromWire(value: Int): MessageType = entries.firstOrNull { it.wireValue == value }
            ?: throw ProtocolException(
                ProtocolErrorKind.UNKNOWN_MESSAGE,
                "unknown message type 0x${value.toString(16).padStart(4, '0')}",
            )
    }
}

/**
 * Documents the ProtocolErrorKind type and the role it plays in this module.
 */
internal enum class ProtocolErrorKind {
    MALFORMED_COBS,
    CRC_MISMATCH,
    INVALID_HEADER,
    UNKNOWN_MESSAGE,
    LENGTH_MISMATCH,
    PAYLOAD_INVALID,
    OVERSIZE,
    POLICY_REJECTED,
}

/** Handshake phase used to fail closed on unexpected board-to-host message types. */
internal enum class InboundSessionStage {
    NOT_SYNCHRONIZED,
    VALIDATING_DEVICE,
    AWAITING_HEARTBEAT,
    STARTING_TELEMETRY,
    READY,
}

/**
 * Runs the MessageType operation.
 */
internal fun MessageType.requireAllowedInboundAt(stage: InboundSessionStage) {
    val allowed = when (this) {
        MessageType.DEVICE_INFO_RESPONSE -> stage == InboundSessionStage.VALIDATING_DEVICE
        MessageType.HEARTBEAT_ACK ->
            stage == InboundSessionStage.AWAITING_HEARTBEAT ||
                stage == InboundSessionStage.STARTING_TELEMETRY ||
                stage == InboundSessionStage.READY
        MessageType.COMMAND_ACK ->
            stage == InboundSessionStage.STARTING_TELEMETRY ||
                stage == InboundSessionStage.READY
        MessageType.COMMAND_NACK -> stage != InboundSessionStage.NOT_SYNCHRONIZED
        MessageType.SENSOR_SAMPLE -> stage == InboundSessionStage.READY
        MessageType.DEVICE_STATUS_RESPONSE ->
            stage == InboundSessionStage.STARTING_TELEMETRY || stage == InboundSessionStage.READY
        else -> false
    }
    if (!allowed) {
        throw ProtocolException(
            ProtocolErrorKind.POLICY_REJECTED,
            "$this is not allowed while the board session is $stage",
        )
    }
}

/**
 * Documents the ProtocolException type and the role it plays in this module.
 */
internal class ProtocolException(
    /**
     * Exposes the kind value.
     */
    val kind: ProtocolErrorKind,
    message: String,
) : IllegalArgumentException(message)

/** Converts every CRC-valid frame handler failure into an observable protocol rejection. */
internal sealed interface FrameHandlingResult {
    /**
     * Provides the singleton Accepted services for this module.
     */
    data object Accepted : FrameHandlingResult
    /**
     * Documents the Rejected type and the role it plays in this module.
     */
    data class Rejected(val exception: ProtocolException) : FrameHandlingResult
}

/**
 * Runs the handleCrcValidFrameSafely operation.
 */
internal inline fun handleCrcValidFrameSafely(block: () -> Unit): FrameHandlingResult = try {
    block()
    FrameHandlingResult.Accepted
} catch (error: CancellationException) {
    throw error
} catch (error: ProtocolException) {
    FrameHandlingResult.Rejected(error)
} catch (error: RuntimeException) {
    FrameHandlingResult.Rejected(
        ProtocolException(
            ProtocolErrorKind.PAYLOAD_INVALID,
            "Protocol payload validation failed safely: ${error.message ?: error.javaClass.simpleName}",
        ),
    )
}

/**
 * Documents the FrameHeader type and the role it plays in this module.
 */
internal data class FrameHeader(
    /**
     * Exposes the messageType value.
     */
    val messageType: MessageType,
    /**
     * Exposes the priority value.
     */
    val priority: MessagePriority,
    /**
     * Exposes the sequence value.
     */
    val sequence: UInt,
    /**
     * Exposes the senderMonotonicUs value.
     */
    val senderMonotonicUs: ULong,
    /**
     * Exposes the payloadLength value.
     */
    val payloadLength: Int,
    /**
     * Exposes the flags value.
     */
    val flags: Int = 0,
)

/**
 * Documents the DecodedFrame type and the role it plays in this module.
 */
internal data class DecodedFrame(
    /**
     * Exposes the header value.
     */
    val header: FrameHeader,
    /**
     * Exposes the payload value.
     */
    val payload: ByteArray,
)

/** Validates the production sender priority before it can influence reorder admission. */
internal fun DecodedFrame.requireExpectedInboundPriority() {
    val expected = when (header.messageType) {
        MessageType.HEARTBEAT_ACK -> MessagePriority.CRITICAL
        MessageType.SENSOR_SAMPLE -> MessagePriority.NORMAL
        MessageType.TIME_SYNC_RESPONSE,
        MessageType.DEVICE_INFO_RESPONSE,
        MessageType.DEVICE_STATUS_RESPONSE,
        MessageType.COMMAND_ACK,
        MessageType.COMMAND_NACK -> MessagePriority.HIGH
        else -> throw ProtocolException(
            ProtocolErrorKind.POLICY_REJECTED,
            "${header.messageType} is not an accepted board-to-host message",
        )
    }
    if (header.priority != expected) {
        throw ProtocolException(
            ProtocolErrorKind.POLICY_REJECTED,
            "${header.messageType} priority ${header.priority} does not match $expected",
        )
    }
}

/**
 * Documents the OutboundRequest type and the role it plays in this module.
 */
internal data class OutboundRequest(
    /**
     * Exposes the messageType value.
     */
    val messageType: MessageType,
    /**
     * Exposes the priority value.
     */
    val priority: MessagePriority,
    /**
     * Exposes the payload value.
     */
    val payload: ByteArray,
    /**
     * Exposes the sessionBound value.
     */
    val sessionBound: Boolean,
)

/**
 * Provides the singleton Crc32c services for this module.
 */
internal object Crc32c {
    /**
     * Runs the calculate operation.
     */
    fun calculate(bytes: ByteArray, length: Int = bytes.size): Int {
        require(length in 0..bytes.size)
        var crc = -1
        repeat(length) { index ->
            crc = crc xor (bytes[index].toInt() and 0xFF)
            repeat(8) {
                val mask = -(crc and 1)
                crc = (crc ushr 1) xor (0x82F63B78.toInt() and mask)
            }
        }
        return crc xor -1
    }
}

/**
 * Provides the singleton Cobs services for this module.
 */
internal object Cobs {
    /**
     * Runs the encode operation.
     */
    fun encode(input: ByteArray): ByteArray {
        if (input.size > WireContract.MAX_DECODED_FRAME_LENGTH) {
            throw ProtocolException(ProtocolErrorKind.OVERSIZE, "decoded frame exceeds bound")
        }
        val output = ByteArray(input.size + (input.size / 254) + 1)
        var readIndex = 0
        var writeIndex = 1
        var codeIndex = 0
        var code = 1
        while (readIndex < input.size) {
            if (input[readIndex] == WireContract.DELIMITER) {
                output[codeIndex] = code.toByte()
                codeIndex = writeIndex++
                code = 1
                readIndex++
            } else {
                output[writeIndex++] = input[readIndex++]
                code++
                if (code == 0xFF) {
                    output[codeIndex] = code.toByte()
                    codeIndex = writeIndex++
                    code = 1
                }
            }
        }
        output[codeIndex] = code.toByte()
        return output.copyOf(writeIndex)
    }

    /**
     * Runs the decode operation.
     */
    fun decode(input: ByteArray): ByteArray {
        if (input.isEmpty()) {
            throw ProtocolException(ProtocolErrorKind.MALFORMED_COBS, "empty COBS frame")
        }
        if (input.size > WireContract.MAX_COBS_BODY_LENGTH) {
            throw ProtocolException(ProtocolErrorKind.OVERSIZE, "encoded frame exceeds bound")
        }
        if (input.any { it == WireContract.DELIMITER }) {
            throw ProtocolException(ProtocolErrorKind.MALFORMED_COBS, "COBS body contains zero")
        }
        val output = ByteArray(WireContract.MAX_DECODED_FRAME_LENGTH)
        var readIndex = 0
        var writeIndex = 0
        while (readIndex < input.size) {
            val code = readU8(input, readIndex++)
            val blockLength = code - 1
            if (blockLength > input.size - readIndex) {
                throw ProtocolException(ProtocolErrorKind.MALFORMED_COBS, "truncated COBS block")
            }
            if (blockLength > output.size - writeIndex) {
                throw ProtocolException(ProtocolErrorKind.OVERSIZE, "decoded frame exceeds bound")
            }
            repeat(blockLength) { output[writeIndex++] = input[readIndex++] }
            if (code != 0xFF && readIndex < input.size) {
                if (writeIndex >= output.size) {
                    throw ProtocolException(ProtocolErrorKind.OVERSIZE, "decoded frame exceeds bound")
                }
                output[writeIndex++] = 0
            }
        }
        return output.copyOf(writeIndex)
    }
}

/**
 * Provides the singleton FrameCodec services for this module.
 */
internal object FrameCodec {
    /**
     * Runs the encode operation.
     */
    fun encode(
        request: OutboundRequest,
        sequence: UInt,
        senderMonotonicUs: ULong,
        sessionToken: ULong?,
    ): ByteArray {
        if (request.messageType !in GUARDED_HOST_REQUEST_TYPES) {
            throw ProtocolException(
                ProtocolErrorKind.POLICY_REJECTED,
                "${request.messageType} is not exposed by the guarded host client",
            )
        }
        val payload = if (request.sessionBound) {
            val token = sessionToken?.takeIf { it != 0uL } ?: throw ProtocolException(
                ProtocolErrorKind.POLICY_REJECTED,
                "${request.messageType} requires a current session token",
            )
            ByteArray(8).also { writeU64(it, 0, token) } + request.payload
        } else {
            request.payload
        }
        if (payload.size > WireContract.MAX_PAYLOAD_LENGTH) {
            throw ProtocolException(ProtocolErrorKind.OVERSIZE, "payload exceeds 512 bytes")
        }
        val body = ByteArray(WireContract.HEADER_LENGTH + payload.size)
        body[0] = WireContract.VERSION.toByte()
        body[1] = WireContract.HEADER_LENGTH.toByte()
        writeU16(body, 2, request.messageType.wireValue)
        writeU16(body, 4, 0)
        body[6] = request.priority.wireValue.toByte()
        body[7] = 0
        writeU32(body, 8, sequence)
        writeU64(body, 12, senderMonotonicUs)
        writeU16(body, 20, payload.size)
        payload.copyInto(body, WireContract.HEADER_LENGTH)
        val decoded = body + ByteArray(4).also { writeU32(it, 0, Crc32c.calculate(body).toUInt()) }
        return Cobs.encode(decoded) + byteArrayOf(WireContract.DELIMITER)
    }

    /**
     * Runs the decodeBody operation.
     */
    fun decodeBody(encodedBody: ByteArray): DecodedFrame {
        val decoded = Cobs.decode(encodedBody)
        if (decoded.size < WireContract.HEADER_LENGTH + WireContract.CRC_LENGTH) {
            throw ProtocolException(ProtocolErrorKind.LENGTH_MISMATCH, "frame shorter than header and CRC")
        }
        val crcOffset = decoded.size - WireContract.CRC_LENGTH
        val expectedCrc = readU32(decoded, crcOffset)
        val actualCrc = Crc32c.calculate(decoded, crcOffset).toUInt()
        if (expectedCrc != actualCrc) {
            throw ProtocolException(ProtocolErrorKind.CRC_MISMATCH, "CRC-32C mismatch")
        }
        val version = readU8(decoded, 0)
        val headerLength = readU8(decoded, 1)
        if (version != WireContract.VERSION) {
            throw ProtocolException(ProtocolErrorKind.INVALID_HEADER, "unsupported protocol $version")
        }
        if (headerLength != WireContract.HEADER_LENGTH) {
            throw ProtocolException(ProtocolErrorKind.INVALID_HEADER, "invalid header length $headerLength")
        }
        val messageType = MessageType.fromWire(readU16(decoded, 2))
        val flags = readU16(decoded, 4)
        val priority = MessagePriority.fromWire(readU8(decoded, 6))
        if (readU8(decoded, 7) != 0) {
            throw ProtocolException(ProtocolErrorKind.INVALID_HEADER, "reserved byte is nonzero")
        }
        val payloadLength = readU16(decoded, 20)
        if (payloadLength > WireContract.MAX_PAYLOAD_LENGTH) {
            throw ProtocolException(ProtocolErrorKind.OVERSIZE, "declared payload exceeds bound")
        }
        val expectedLength = WireContract.HEADER_LENGTH + payloadLength + WireContract.CRC_LENGTH
        if (decoded.size != expectedLength) {
            throw ProtocolException(
                ProtocolErrorKind.LENGTH_MISMATCH,
                "decoded length ${decoded.size} != $expectedLength",
            )
        }
        return DecodedFrame(
            header = FrameHeader(
                messageType = messageType,
                priority = priority,
                sequence = readU32(decoded, 8),
                senderMonotonicUs = readU64(decoded, 12),
                payloadLength = payloadLength,
                flags = flags,
            ),
            payload = decoded.copyOfRange(WireContract.HEADER_LENGTH, crcOffset),
        )
    }
}

/**
 * Defines the StreamEvent contract used by this module.
 */
internal sealed interface StreamEvent {
    /**
     * Documents the Frame type and the role it plays in this module.
     */
    data class Frame(val value: DecodedFrame) : StreamEvent
    /**
     * Documents the Rejected type and the role it plays in this module.
     */
    data class Rejected(val exception: ProtocolException) : StreamEvent
}

/**
 * Documents the FrameAccumulator type and the role it plays in this module.
 */
internal class FrameAccumulator {
    /**
     * Exposes the encoded value.
     */
    private val encoded = ByteArray(WireContract.MAX_COBS_BODY_LENGTH)
    /**
     * Stores the mutable size value.
     */
    private var size = 0
    /**
     * Stores the mutable discarding value.
     */
    private var discarding = false

    /**
     * Runs the reset operation.
     */
    fun reset() {
        size = 0
        discarding = false
    }

    /**
     * Runs the feed operation.
     */
    fun feed(chunk: ByteArray): List<StreamEvent> {
        val output = mutableListOf<StreamEvent>()
        for (byte in chunk) {
            if (byte != WireContract.DELIMITER) {
                if (discarding) continue
                if (size == encoded.size) {
                    size = 0
                    discarding = true
                } else {
                    encoded[size++] = byte
                }
                continue
            }
            if (discarding) {
                reset()
                output += StreamEvent.Rejected(
                    ProtocolException(ProtocolErrorKind.OVERSIZE, "oversize frame discarded"),
                )
                continue
            }
            if (size == 0) continue
            val body = encoded.copyOf(size)
            size = 0
            output += try {
                StreamEvent.Frame(FrameCodec.decodeBody(body))
            } catch (error: ProtocolException) {
                StreamEvent.Rejected(error)
            } catch (error: RuntimeException) {
                StreamEvent.Rejected(
                    ProtocolException(
                        ProtocolErrorKind.PAYLOAD_INVALID,
                        "Frame decoding failed safely: ${error.message ?: error.javaClass.simpleName}",
                    ),
                )
            }
        }
        return output
    }
}

/**
 * Provides the singleton SafeRequests services for this module.
 */
internal object SafeRequests {
    /**
     * Runs the timeSync operation.
     */
    fun timeSync(hostUs: ULong) = request(
        type = MessageType.TIME_SYNC_REQUEST,
        payload = ByteArray(8).also { writeU64(it, 0, hostUs) },
    )

    /**
     * Runs the deviceInfo operation.
     */
    fun deviceInfo() = request(MessageType.DEVICE_INFO_REQUEST)
    /**
     * Runs the deviceStatus operation.
     */
    fun deviceStatus() = request(MessageType.DEVICE_STATUS_REQUEST)
    /**
     * Runs the startTelemetry operation.
     */
    fun startTelemetry() = request(MessageType.START_TELEMETRY, sessionBound = true)
    /**
     * Runs the stopTelemetry operation.
     */
    fun stopTelemetry() = request(MessageType.STOP_TELEMETRY, sessionBound = true)
    /**
     * Runs the heartbeat operation.
     */
    fun heartbeat() = request(MessageType.HEARTBEAT, MessagePriority.CRITICAL, sessionBound = true)

    /** Tokenless safety override used while closing or explicitly disarming. */
    fun disarm() = request(MessageType.DISARM, MessagePriority.CRITICAL)
    /**
     * Runs the emergencyStop operation.
     */
    fun emergencyStop() = request(MessageType.EMERGENCY_STOP, MessagePriority.CRITICAL)
    /**
     * Runs the armRequest operation.
     */
    fun armRequest() = request(MessageType.ARM_REQUEST, MessagePriority.CRITICAL, sessionBound = true)
    /**
     * Runs the armConfirm operation.
     */
    fun armConfirm() = request(MessageType.ARM_CONFIRM, MessagePriority.CRITICAL, sessionBound = true)

    /**
     * Builds one complete Quad-X motor generation.
     *
     * The application payload is `[count:u8][channel:u8, pulse_us:u16 LE] * 4`.
     * Entries are emitted in canonical channel order so a caller's list order cannot alter the
     * wire representation. The session token is prepended by [FrameCodec].
     */
    fun motorFrameCommand(pulses: List<BoardMotorPulse>): OutboundRequest {
        require(pulses.size == QUAD_X_MOTOR_CHANNEL_COUNT) {
            "Quad-X motor frame must contain exactly $QUAD_X_MOTOR_CHANNEL_COUNT channels"
        }
        val pulsesByChannel = arrayOfNulls<BoardMotorPulse>(QUAD_X_MOTOR_CHANNEL_COUNT)
        pulses.forEach { pulse ->
            require(pulse.channel in pulsesByChannel.indices) {
                "Quad-X motor channel must be in ${pulsesByChannel.indices}"
            }
            require(pulsesByChannel[pulse.channel] == null) {
                "Quad-X motor channel ${pulse.channel} is duplicated"
            }
            pulsesByChannel[pulse.channel] = pulse
        }

        val payload = ByteArray(1 + QUAD_X_MOTOR_CHANNEL_COUNT * 3)
        payload[0] = QUAD_X_MOTOR_CHANNEL_COUNT.toByte()
        pulsesByChannel.forEachIndexed { channel, pulse ->
            checkNotNull(pulse) { "Quad-X motor channel $channel is missing" }
            val offset = 1 + channel * 3
            payload[offset] = channel.toByte()
            writeU16(payload, offset + 1, pulse.pulseMicros)
        }
        return request(
            type = MessageType.MOTOR_FRAME_COMMAND,
            priority = MessagePriority.CRITICAL,
            payload = payload,
            sessionBound = true,
        )
    }

    /**
     * Runs the servoCommand operation.
     */
    fun servoCommand(channel: Int, pulseMicros: Int) = request(
        type = MessageType.SERVO_COMMAND,
        priority = MessagePriority.CRITICAL,
        payload = directPulsePayload(channel, pulseMicros),
        sessionBound = true,
    )

    /**
     * Runs the setControlMode operation.
     */
    fun setControlMode(mode: Int) = request(
        type = MessageType.SET_CONTROL_MODE,
        priority = MessagePriority.HIGH,
        payload = byteArrayOf(channelByte(mode)),
        sessionBound = true,
    )

    /**
     * Runs the request operation.
     */
    private fun request(
        type: MessageType,
        priority: MessagePriority = MessagePriority.HIGH,
        payload: ByteArray = byteArrayOf(),
        sessionBound: Boolean = false,
    ) = OutboundRequest(type, priority, payload, sessionBound)

    /**
     * Runs the directPulsePayload operation.
     */
    private fun directPulsePayload(channel: Int, pulseMicros: Int): ByteArray =
        ByteArray(3).also {
            it[0] = channelByte(channel)
            writeU16(it, 1, pulseMicros)
        }

    /**
     * Runs the channelByte operation.
     */
    private fun channelByte(value: Int): Byte {
        require(value in 0..255) { "value must fit in uint8" }
        return value.toByte()
    }
}

/**
 * Exposes the GUARDED_HOST_REQUEST_TYPES value.
 */
private val GUARDED_HOST_REQUEST_TYPES = setOf(
    MessageType.TIME_SYNC_REQUEST,
    MessageType.DEVICE_INFO_REQUEST,
    MessageType.DEVICE_STATUS_REQUEST,
    MessageType.START_TELEMETRY,
    MessageType.STOP_TELEMETRY,
    MessageType.HEARTBEAT,
    MessageType.EMERGENCY_STOP,
    MessageType.DISARM,
    MessageType.ARM_REQUEST,
    MessageType.ARM_CONFIRM,
    MessageType.MOTOR_FRAME_COMMAND,
    MessageType.SERVO_COMMAND,
    MessageType.SET_CONTROL_MODE,
)

/**
 * Documents the TimeSyncResponse type and the role it plays in this module.
 */
internal data class TimeSyncResponse(
    /**
     * Exposes the clientSendUs value.
     */
    val clientSendUs: ULong,
    /**
     * Exposes the deviceRxUs value.
     */
    val deviceRxUs: ULong,
    /**
     * Exposes the deviceTxUs value.
     */
    val deviceTxUs: ULong,
    /**
     * Exposes the sessionToken value.
     */
    val sessionToken: ULong,
)

/**
 * Runs the DecodedFrame operation.
 */
internal fun DecodedFrame.decodeTimeSync(): TimeSyncResponse? {
    if (header.messageType != MessageType.TIME_SYNC_RESPONSE) return null
    requirePayloadSize(32)
    return TimeSyncResponse(
        readU64(payload, 0), readU64(payload, 8), readU64(payload, 16), readU64(payload, 24),
    )
}

/**
 * Runs the DecodedFrame operation.
 */
internal fun DecodedFrame.decodeDeviceInfo(): BoardDeviceInfo? {
    if (header.messageType != MessageType.DEVICE_INFO_RESPONSE) return null
    requirePayloadSize(20)
    val protocol = readU8(payload, 0)
    val target = when (readU8(payload, 1)) {
        1 -> BoardTarget.ESP32_S3
        else -> throw ProtocolException(ProtocolErrorKind.PAYLOAD_INVALID, "target is not ESP32-S3")
    }
    return BoardDeviceInfo(
        protocolVersion = protocol,
        target = target,
        supportedMotorChannels = readU8(payload, 2),
        supportedServoChannels = readU8(payload, 3),
        detectedFlashBytes = readU32(payload, 4).toLong(),
        detectedPsramBytes = readU32(payload, 8).toLong(),
        boardValidationIssueMask = readU32(payload, 12).toLong(),
        activeMotorChannels = readU8(payload, 16),
        activeServoChannels = readU8(payload, 17),
        actuatorAvailable = readBooleanByte(payload, 18),
        actuatorsEnabledByConfiguration = readBooleanByte(payload, 19),
    )
}

/**
 * Documents the RawWireSensorSample type and the role it plays in this module.
 */
internal data class RawWireSensorSample(
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
    val sequence: UInt,
    /**
     * Exposes the deviceTimestampUs value.
     */
    val deviceTimestampUs: ULong,
    /**
     * Exposes the validityFlags value.
     */
    val validityFlags: UInt,
    /**
     * Exposes the qualityFlags value.
     */
    val qualityFlags: UInt,
    /**
     * Exposes the healthFlags value.
     */
    val healthFlags: UInt,
    /**
     * Exposes the fields value.
     */
    val fields: List<RawSensorField>,
)

/**
 * Runs the DecodedFrame operation.
 */
internal fun DecodedFrame.decodeSensorSample(): RawWireSensorSample? {
    if (header.messageType != MessageType.SENSOR_SAMPLE) return null
    if (payload.size < 27) {
        throw ProtocolException(ProtocolErrorKind.PAYLOAD_INVALID, "sensor prefix is truncated")
    }
    val count = readU8(payload, 26)
    if (payload.size != 27 + count * 6) {
        throw ProtocolException(ProtocolErrorKind.PAYLOAD_INVALID, "sensor field length mismatch")
    }
    val fields = ArrayList<RawSensorField>(count)
    val ids = hashSetOf<Int>()
    var offset = 27
    repeat(count) {
        val fieldId = readU8(payload, offset)
        if (!ids.add(fieldId)) {
            throw ProtocolException(ProtocolErrorKind.PAYLOAD_INVALID, "duplicate sensor field $fieldId")
        }
        val type = when (readU8(payload, offset + 1)) {
            1 -> RawSensorFieldType.SIGNED_32
            2 -> RawSensorFieldType.UNSIGNED_32
            else -> throw ProtocolException(
                ProtocolErrorKind.PAYLOAD_INVALID,
                "unknown field type ${readU8(payload, offset + 1)}",
            )
        }
        fields += RawSensorField(fieldId, type, readU32(payload, offset + 2))
        offset += 6
    }
    return RawWireSensorSample(
        sensorId = readU8(payload, 0),
        instanceId = readU8(payload, 1),
        sequence = readU32(payload, 2),
        deviceTimestampUs = readU64(payload, 6),
        validityFlags = readU32(payload, 14),
        qualityFlags = readU32(payload, 18),
        healthFlags = readU32(payload, 22),
        fields = fields,
    )
}

/**
 * Documents the CommandNack type and the role it plays in this module.
 */
internal data class CommandNack(
    /**
     * Exposes the requestSequence value.
     */
    val requestSequence: UInt,
    /**
     * Exposes the reason value.
     */
    val reason: Int,
    /**
     * Exposes the validationError value.
     */
    val validationError: Int,
)

/**
 * Runs the DecodedFrame operation.
 */
internal fun DecodedFrame.decodeCommandNack(): CommandNack? {
    if (header.messageType != MessageType.COMMAND_NACK) return null
    requirePayloadSize(8)
    return CommandNack(readU32(payload, 0), readU16(payload, 4), readU16(payload, 6))
}

/**
 * Documents the CommandAck type and the role it plays in this module.
 */
internal data class CommandAck(
    /**
     * Exposes the requestSequence value.
     */
    val requestSequence: UInt,
    /**
     * Exposes the applicationAction value.
     */
    val applicationAction: ApplicationAction,
)

/**
 * Documents the ApplicationAction type and the role it plays in this module.
 */
internal enum class ApplicationAction(val wireValue: Int) {
    NONE(0),
    REQUEST_DEVICE_INFO(1),
    START_TELEMETRY(2),
    STOP_TELEMETRY(3),
    SET_SENSOR_RATE(4),
    REQUEST_DEVICE_STATUS(5),
    HEARTBEAT_RECEIVED(6),
    HEARTBEAT_ACK_RECEIVED(7),
    PING_RECEIVED(8),
    PONG_RECEIVED(9),
    TIME_SYNC_REQUEST_RECEIVED(10),
    EMERGENCY_STOP_APPLIED(11),
    DISARM_APPLIED(12),
    ARM_APPLIED(13),
    MOTOR_COMMAND(14),
    SERVO_COMMAND(15),
    ACTUATOR_COMMAND(16),
    SET_CONTROL_MODE(17),
    MOTOR_FRAME_COMMAND(18);

    companion object {
        /**
         * Runs the fromWire operation.
         */
        fun fromWire(value: Int): ApplicationAction = entries.firstOrNull { it.wireValue == value }
            ?: throw ProtocolException(
                ProtocolErrorKind.PAYLOAD_INVALID,
                "unknown application action $value",
            )
    }
}

/**
 * Runs the DecodedFrame operation.
 */
internal fun DecodedFrame.decodeCommandAck(): CommandAck? {
    if (header.messageType != MessageType.COMMAND_ACK) return null
    requirePayloadSize(5)
    return CommandAck(readU32(payload, 0), ApplicationAction.fromWire(readU8(payload, 4)))
}

/**
 * Runs the CommandAck operation.
 */
internal fun CommandAck.requireAcknowledges(
    expectedRequestSequence: UInt,
    expectedAction: ApplicationAction,
) {
    if (requestSequence != expectedRequestSequence || applicationAction != expectedAction) {
        throw ProtocolException(
            ProtocolErrorKind.PAYLOAD_INVALID,
            "CommandAck does not match the expected request sequence and application action",
        )
    }
}

/**
 * Runs the DecodedFrame operation.
 */
internal fun DecodedFrame.requireHeartbeatAckPayload() {
    if (header.messageType != MessageType.HEARTBEAT_ACK) {
        throw ProtocolException(ProtocolErrorKind.PAYLOAD_INVALID, "frame is not HeartbeatAck")
    }
    requirePayloadSize(0)
}

/**
 * Documents the DeviceStatusPayload type and the role it plays in this module.
 */
internal data class DeviceStatusPayload(
    /**
     * Exposes the safetyState value.
     */
    val safetyState: Int,
    /**
     * Exposes the communicationState value.
     */
    val communicationState: Int,
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
    /** Null for the exact six-byte legacy response; populated by the ten-byte response. */
    val rangefinders: RangefinderLifecycleStatus? = null,
)

/**
 * Runs the DecodedFrame operation.
 */
internal fun DecodedFrame.decodeDeviceStatus(): DeviceStatusPayload? {
    if (header.messageType != MessageType.DEVICE_STATUS_RESPONSE) return null
    if (payload.size != LEGACY_DEVICE_STATUS_SIZE && payload.size != EXTENDED_DEVICE_STATUS_SIZE) {
        throw ProtocolException(
            ProtocolErrorKind.PAYLOAD_INVALID,
            "DeviceStatus payload ${payload.size} must be exactly " +
                "$LEGACY_DEVICE_STATUS_SIZE or $EXTENDED_DEVICE_STATUS_SIZE bytes",
        )
    }
    val safetyState = readU8(payload, 0)
    if (safetyState !in 0..6) {
        throw ProtocolException(
            ProtocolErrorKind.PAYLOAD_INVALID,
            "invalid safety state $safetyState",
        )
    }
    val communicationState = readU8(payload, 1)
    if (communicationState !in 0..4) {
        throw ProtocolException(
            ProtocolErrorKind.PAYLOAD_INVALID,
            "invalid communication state $communicationState",
        )
    }
    return DeviceStatusPayload(
        safetyState = safetyState,
        communicationState = communicationState,
        telemetryEnabled = readBooleanByte(payload, 2),
        actuatorArmed = readBooleanByte(payload, 3),
        sht30Online = readBooleanByte(payload, 4),
        ms5611Online = readBooleanByte(payload, 5),
        rangefinders = if (payload.size == EXTENDED_DEVICE_STATUS_SIZE) {
            RangefinderLifecycleStatus(
                ground = readRangefinderLifecycle(payload, 6),
                up = readRangefinderLifecycle(payload, 7),
                frontLeft = readRangefinderLifecycle(payload, 8),
                frontRight = readRangefinderLifecycle(payload, 9),
            )
        } else {
            null
        },
    )
}

private fun readRangefinderLifecycle(input: ByteArray, offset: Int): RangefinderLifecycle =
    when (val value = readU8(input, offset)) {
        0 -> RangefinderLifecycle.DISABLED_OR_ABSENT
        1 -> RangefinderLifecycle.INITIALIZING
        2 -> RangefinderLifecycle.LIVE
        3 -> RangefinderLifecycle.DEGRADED
        else -> throw ProtocolException(
            ProtocolErrorKind.PAYLOAD_INVALID,
            "invalid rangefinder lifecycle $value at role index ${offset - 6}",
        )
    }

private const val LEGACY_DEVICE_STATUS_SIZE = 6
private const val EXTENDED_DEVICE_STATUS_SIZE = 10

/**
 * Runs the altitudeMeters operation.
 */
internal fun altitudeMeters(pressurePa: Int, qnhHpa: Double): Double {
    if (pressurePa !in 1_000..120_000) {
        throw ProtocolException(ProtocolErrorKind.PAYLOAD_INVALID, "pressure is out of range")
    }
    if (!qnhHpa.isFinite() || qnhHpa !in 800.0..1100.0) {
        throw ProtocolException(ProtocolErrorKind.PAYLOAD_INVALID, "QNH must be 800..1100 hPa")
    }
    return 44_330.0 * (1.0 - (pressurePa / (qnhHpa * 100.0)).pow(0.19029495718363465))
}

/**
 * Runs the DecodedFrame operation.
 */
private fun DecodedFrame.requirePayloadSize(size: Int) {
    if (payload.size != size) {
        throw ProtocolException(
            ProtocolErrorKind.PAYLOAD_INVALID,
            "${header.messageType} payload ${payload.size} != $size",
        )
    }
}

/**
 * Runs the readBooleanByte operation.
 */
private fun readBooleanByte(input: ByteArray, offset: Int): Boolean = when (readU8(input, offset)) {
    0 -> false
    1 -> true
    else -> throw ProtocolException(ProtocolErrorKind.PAYLOAD_INVALID, "invalid boolean byte")
}

/**
 * Runs the readU8 operation.
 */
internal fun readU8(input: ByteArray, offset: Int): Int = input[offset].toInt() and 0xFF

/**
 * Runs the readU16 operation.
 */
internal fun readU16(input: ByteArray, offset: Int): Int =
    readU8(input, offset) or (readU8(input, offset + 1) shl 8)

/**
 * Runs the readU32 operation.
 */
internal fun readU32(input: ByteArray, offset: Int): UInt {
    var result = 0u
    repeat(4) { result = result or (readU8(input, offset + it).toUInt() shl (8 * it)) }
    return result
}

/**
 * Runs the readU64 operation.
 */
internal fun readU64(input: ByteArray, offset: Int): ULong {
    var result = 0uL
    repeat(8) { result = result or (readU8(input, offset + it).toULong() shl (8 * it)) }
    return result
}

/**
 * Runs the writeU16 operation.
 */
internal fun writeU16(output: ByteArray, offset: Int, value: Int) {
    require(value in 0..0xFFFF)
    output[offset] = value.toByte()
    output[offset + 1] = (value ushr 8).toByte()
}

/**
 * Runs the writeU32 operation.
 */
internal fun writeU32(output: ByteArray, offset: Int, value: UInt) {
    repeat(4) { output[offset + it] = (value shr (8 * it)).toByte() }
}

/**
 * Runs the writeU64 operation.
 */
internal fun writeU64(output: ByteArray, offset: Int, value: ULong) {
    repeat(8) { output[offset + it] = (value shr (8 * it)).toByte() }
}
