/** Bounded Shahbaz Protocol v2 framing, safe requests, and response decoders. */
package ir.hrka.shahbaz.hardwareconnection.internal.protocol

import ir.hrka.shahbaz.hardwareconnection.BoardDeviceInfo
import ir.hrka.shahbaz.hardwareconnection.BoardTarget
import ir.hrka.shahbaz.hardwareconnection.RawSensorField
import ir.hrka.shahbaz.hardwareconnection.RawSensorFieldType
import java.util.concurrent.CancellationException
import kotlin.math.pow

internal object WireContract {
    const val VERSION = 2
    const val HEADER_LENGTH = 22
    const val CRC_LENGTH = 4
    const val MAX_PAYLOAD_LENGTH = 512
    const val MAX_DECODED_FRAME_LENGTH = HEADER_LENGTH + MAX_PAYLOAD_LENGTH + CRC_LENGTH
    const val MAX_COBS_BODY_LENGTH =
        MAX_DECODED_FRAME_LENGTH + (MAX_DECODED_FRAME_LENGTH / 254) + 1
    const val MAX_DELIMITED_FRAME_LENGTH = MAX_COBS_BODY_LENGTH + 1
    const val DELIMITER: Byte = 0
}

internal enum class MessagePriority(val wireValue: Int) {
    CRITICAL(0), HIGH(1), NORMAL(2), LOW(3);

    companion object {
        fun fromWire(value: Int): MessagePriority = entries.firstOrNull { it.wireValue == value }
            ?: throw ProtocolException(ProtocolErrorKind.INVALID_HEADER, "invalid priority $value")
    }
}

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
    MOTOR_COMMAND(0x8011), SERVO_COMMAND(0x8012), SET_CONTROL_MODE(0x8013);

    companion object {
        fun fromWire(value: Int): MessageType = entries.firstOrNull { it.wireValue == value }
            ?: throw ProtocolException(
                ProtocolErrorKind.UNKNOWN_MESSAGE,
                "unknown message type 0x${value.toString(16).padStart(4, '0')}",
            )
    }
}

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
    AWAITING_READY,
    READY,
}

internal fun MessageType.requireAllowedInboundAt(stage: InboundSessionStage) {
    val allowed = when (this) {
        MessageType.DEVICE_INFO_RESPONSE -> stage == InboundSessionStage.VALIDATING_DEVICE
        MessageType.HEARTBEAT_ACK ->
            stage == InboundSessionStage.AWAITING_READY || stage == InboundSessionStage.READY
        MessageType.COMMAND_ACK -> stage == InboundSessionStage.AWAITING_READY
        MessageType.COMMAND_NACK -> stage != InboundSessionStage.NOT_SYNCHRONIZED
        MessageType.SENSOR_SAMPLE -> stage == InboundSessionStage.READY
        MessageType.DEVICE_STATUS_RESPONSE ->
            stage == InboundSessionStage.AWAITING_READY || stage == InboundSessionStage.READY
        else -> false
    }
    if (!allowed) {
        throw ProtocolException(
            ProtocolErrorKind.POLICY_REJECTED,
            "$this is not allowed while the board session is $stage",
        )
    }
}

internal class ProtocolException(
    val kind: ProtocolErrorKind,
    message: String,
) : IllegalArgumentException(message)

/** Converts every CRC-valid frame handler failure into an observable protocol rejection. */
internal sealed interface FrameHandlingResult {
    data object Accepted : FrameHandlingResult
    data class Rejected(val exception: ProtocolException) : FrameHandlingResult
}

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

internal data class FrameHeader(
    val messageType: MessageType,
    val priority: MessagePriority,
    val sequence: UInt,
    val senderMonotonicUs: ULong,
    val payloadLength: Int,
    val flags: Int = 0,
)

internal data class DecodedFrame(
    val header: FrameHeader,
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

internal data class OutboundRequest(
    val messageType: MessageType,
    val priority: MessagePriority,
    val payload: ByteArray,
    val sessionBound: Boolean,
)

internal object Crc32c {
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

internal object Cobs {
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

internal object FrameCodec {
    fun encode(
        request: OutboundRequest,
        sequence: UInt,
        senderMonotonicUs: ULong,
        sessionToken: ULong?,
    ): ByteArray {
        if (request.messageType !in SAFE_HOST_REQUEST_TYPES) {
            throw ProtocolException(
                ProtocolErrorKind.POLICY_REJECTED,
                "${request.messageType} is not exposed by the sensor-only client",
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

internal sealed interface StreamEvent {
    data class Frame(val value: DecodedFrame) : StreamEvent
    data class Rejected(val exception: ProtocolException) : StreamEvent
}

internal class FrameAccumulator {
    private val encoded = ByteArray(WireContract.MAX_COBS_BODY_LENGTH)
    private var size = 0
    private var discarding = false

    fun reset() {
        size = 0
        discarding = false
    }

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

internal object SafeRequests {
    fun timeSync(hostUs: ULong) = request(
        type = MessageType.TIME_SYNC_REQUEST,
        payload = ByteArray(8).also { writeU64(it, 0, hostUs) },
    )

    fun deviceInfo() = request(MessageType.DEVICE_INFO_REQUEST)
    fun deviceStatus() = request(MessageType.DEVICE_STATUS_REQUEST)
    fun startTelemetry() = request(MessageType.START_TELEMETRY, sessionBound = true)
    fun stopTelemetry() = request(MessageType.STOP_TELEMETRY, sessionBound = true)
    fun heartbeat() = request(MessageType.HEARTBEAT, MessagePriority.CRITICAL, sessionBound = true)

    /** Tokenless safety override used only while closing; no arming/control builders exist. */
    fun disarm() = request(MessageType.DISARM, MessagePriority.CRITICAL)

    private fun request(
        type: MessageType,
        priority: MessagePriority = MessagePriority.HIGH,
        payload: ByteArray = byteArrayOf(),
        sessionBound: Boolean = false,
    ) = OutboundRequest(type, priority, payload, sessionBound)
}

private val SAFE_HOST_REQUEST_TYPES = setOf(
    MessageType.TIME_SYNC_REQUEST,
    MessageType.DEVICE_INFO_REQUEST,
    MessageType.DEVICE_STATUS_REQUEST,
    MessageType.START_TELEMETRY,
    MessageType.STOP_TELEMETRY,
    MessageType.HEARTBEAT,
    MessageType.DISARM,
)

internal data class TimeSyncResponse(
    val clientSendUs: ULong,
    val deviceRxUs: ULong,
    val deviceTxUs: ULong,
    val sessionToken: ULong,
)

internal fun DecodedFrame.decodeTimeSync(): TimeSyncResponse? {
    if (header.messageType != MessageType.TIME_SYNC_RESPONSE) return null
    requirePayloadSize(32)
    return TimeSyncResponse(
        readU64(payload, 0), readU64(payload, 8), readU64(payload, 16), readU64(payload, 24),
    )
}

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

internal data class RawWireSensorSample(
    val sensorId: Int,
    val instanceId: Int,
    val sequence: UInt,
    val deviceTimestampUs: ULong,
    val validityFlags: UInt,
    val qualityFlags: UInt,
    val healthFlags: UInt,
    val fields: List<RawSensorField>,
)

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

internal data class CommandNack(
    val requestSequence: UInt,
    val reason: Int,
    val validationError: Int,
)

internal fun DecodedFrame.decodeCommandNack(): CommandNack? {
    if (header.messageType != MessageType.COMMAND_NACK) return null
    requirePayloadSize(8)
    return CommandNack(readU32(payload, 0), readU16(payload, 4), readU16(payload, 6))
}

internal data class CommandAck(
    val requestSequence: UInt,
    val applicationAction: ApplicationAction,
)

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
    SET_CONTROL_MODE(17);

    companion object {
        fun fromWire(value: Int): ApplicationAction = entries.firstOrNull { it.wireValue == value }
            ?: throw ProtocolException(
                ProtocolErrorKind.PAYLOAD_INVALID,
                "unknown application action $value",
            )
    }
}

internal fun DecodedFrame.decodeCommandAck(): CommandAck? {
    if (header.messageType != MessageType.COMMAND_ACK) return null
    requirePayloadSize(5)
    return CommandAck(readU32(payload, 0), ApplicationAction.fromWire(readU8(payload, 4)))
}

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

internal fun DecodedFrame.requireHeartbeatAckPayload() {
    if (header.messageType != MessageType.HEARTBEAT_ACK) {
        throw ProtocolException(ProtocolErrorKind.PAYLOAD_INVALID, "frame is not HeartbeatAck")
    }
    requirePayloadSize(0)
}

internal data class DeviceStatusPayload(
    val safetyState: Int,
    val communicationState: Int,
    val telemetryEnabled: Boolean,
    val actuatorArmed: Boolean,
    val sht30Online: Boolean,
    val ms5611Online: Boolean,
)

internal fun DecodedFrame.decodeDeviceStatus(): DeviceStatusPayload? {
    if (header.messageType != MessageType.DEVICE_STATUS_RESPONSE) return null
    requirePayloadSize(6)
    return DeviceStatusPayload(
        readU8(payload, 0), readU8(payload, 1), readBooleanByte(payload, 2),
        readBooleanByte(payload, 3), readBooleanByte(payload, 4), readBooleanByte(payload, 5),
    )
}

internal fun altitudeMeters(pressurePa: Int, qnhHpa: Double): Double {
    if (pressurePa !in 1_000..120_000) {
        throw ProtocolException(ProtocolErrorKind.PAYLOAD_INVALID, "pressure is out of range")
    }
    if (!qnhHpa.isFinite() || qnhHpa !in 800.0..1100.0) {
        throw ProtocolException(ProtocolErrorKind.PAYLOAD_INVALID, "QNH must be 800..1100 hPa")
    }
    return 44_330.0 * (1.0 - (pressurePa / (qnhHpa * 100.0)).pow(0.19029495718363465))
}

private fun DecodedFrame.requirePayloadSize(size: Int) {
    if (payload.size != size) {
        throw ProtocolException(
            ProtocolErrorKind.PAYLOAD_INVALID,
            "${header.messageType} payload ${payload.size} != $size",
        )
    }
}

private fun readBooleanByte(input: ByteArray, offset: Int): Boolean = when (readU8(input, offset)) {
    0 -> false
    1 -> true
    else -> throw ProtocolException(ProtocolErrorKind.PAYLOAD_INVALID, "invalid boolean byte")
}

internal fun readU8(input: ByteArray, offset: Int): Int = input[offset].toInt() and 0xFF

internal fun readU16(input: ByteArray, offset: Int): Int =
    readU8(input, offset) or (readU8(input, offset + 1) shl 8)

internal fun readU32(input: ByteArray, offset: Int): UInt {
    var result = 0u
    repeat(4) { result = result or (readU8(input, offset + it).toUInt() shl (8 * it)) }
    return result
}

internal fun readU64(input: ByteArray, offset: Int): ULong {
    var result = 0uL
    repeat(8) { result = result or (readU8(input, offset + it).toULong() shl (8 * it)) }
    return result
}

internal fun writeU16(output: ByteArray, offset: Int, value: Int) {
    require(value in 0..0xFFFF)
    output[offset] = value.toByte()
    output[offset + 1] = (value ushr 8).toByte()
}

internal fun writeU32(output: ByteArray, offset: Int, value: UInt) {
    repeat(4) { output[offset + it] = (value shr (8 * it)).toByte() }
}

internal fun writeU64(output: ByteArray, offset: Int, value: ULong) {
    repeat(8) { output[offset + it] = (value shr (8 * it)).toByte() }
}
