/** One physical-attachment Protocol v2 session with bounded parser and fresh token state. */
package ir.hrka.shahbaz.hardwareconnection.internal.protocol

/**
 * Documents the BoardProtocolSession type and the role it plays in this module.
 */
internal class BoardProtocolSession(
    /**
     * Exposes the monotonicMicros value.
     */
    private val monotonicMicros: () -> ULong,
) {
    companion object {
        const val TIME_SYNC_REFRESH_MICROS: ULong = 30_000_000uL

        /** Mirrors production EspIdfUsbCdcTransport::kTxQueueDepth. */
        const val INBOUND_REORDER_WINDOW: Int = 12
    }

    /**
     * Defines the Event contract used by this module.
     */
    sealed interface Event {
        /**
         * Documents the FrameReceived type and the role it plays in this module.
         */
        data class FrameReceived(val frame: DecodedFrame) : Event
        /**
         * Documents the Rejected type and the role it plays in this module.
         */
        data class Rejected(val exception: ProtocolException) : Event
    }

    /**
     * Documents the EncodedCommand type and the role it plays in this module.
     */
    data class EncodedCommand(
        val sequence: UInt,
        val type: MessageType,
        val bytes: ByteArray,
    )

    private val accumulator = FrameAccumulator()
    private var nextSequence = 1u
    private val pendingTimeSyncHostUs = linkedSetOf<ULong>()
    private var lastSuccessfulTimeSyncHostUs: ULong? = null
    private var timeMapping: TimeMapping? = null
    private var attachmentDeviceFloorUs: ULong? = null
    private var highestAcceptedInboundSequence: UInt? = null
    private val acceptedInboundPriorities = arrayOfNulls<MessagePriority>(INBOUND_REORDER_WINDOW)

    /**
     * Documents the TimeMapping type and the role it plays in this module.
     */
    private data class TimeMapping(
        val deviceReferenceUs: ULong,
        val hostReferenceUs: ULong,
    )

    var attached: Boolean = false
        private set
    var sessionToken: ULong? = null
        private set

    /**
     * Runs the attach operation.
     */
    fun attach() {
        reset()
        attached = true
    }

    /**
     * Runs the detach operation.
     */
    fun detach() {
        reset()
        attached = false
    }

    /**
     * Runs the buildTimeSync operation.
     */
    fun buildTimeSync(): EncodedCommand {
        requireAttached()
        val now = monotonicMicros()
        pendingTimeSyncHostUs += now
        return encode(SafeRequests.timeSync(now))
    }

    /**
     * Runs the buildDeviceInfo operation.
     */
    fun buildDeviceInfo() = encodeAttached(SafeRequests.deviceInfo())
    /**
     * Runs the buildDeviceStatus operation.
     */
    fun buildDeviceStatus() = encodeAttached(SafeRequests.deviceStatus())
    /**
     * Runs the buildStartTelemetry operation.
     */
    fun buildStartTelemetry() = encodeAttached(SafeRequests.startTelemetry())
    /**
     * Runs the buildStopTelemetry operation.
     */
    fun buildStopTelemetry() = encodeAttached(SafeRequests.stopTelemetry())
    /**
     * Runs the buildHeartbeat operation.
     */
    fun buildHeartbeat() = encodeAttached(SafeRequests.heartbeat())
    /**
     * Runs the buildDisarm operation.
     */
    fun buildDisarm() = encodeAttached(SafeRequests.disarm())
    /**
     * Runs the buildEmergencyStop operation.
     */
    fun buildEmergencyStop() = encodeAttached(SafeRequests.emergencyStop())
    /**
     * Runs the buildArmRequest operation.
     */
    fun buildArmRequest() = encodeAttached(SafeRequests.armRequest())
    /**
     * Runs the buildArmConfirm operation.
     */
    fun buildArmConfirm() = encodeAttached(SafeRequests.armConfirm())
    /**
     * Runs the buildMotorCommand operation.
     */
    fun buildMotorCommand(
        channel: Int,
        pulseMicros: Int,
        generatedAtHostMicros: ULong = monotonicMicros(),
    ) = encodeAttached(SafeRequests.motorCommand(channel, pulseMicros), generatedAtHostMicros)

    /**
     * Runs the buildServoCommand operation.
     */
    fun buildServoCommand(channel: Int, pulseMicros: Int) =
        encodeAttached(SafeRequests.servoCommand(channel, pulseMicros))

    /**
     * Runs the buildActuatorCommand operation.
     */
    fun buildActuatorCommand(kind: ActuatorKind, channel: Int, pulseMicros: Int) =
        encodeAttached(SafeRequests.actuatorCommand(kind, channel, pulseMicros))

    /**
     * Runs the buildSetControlMode operation.
     */
    fun buildSetControlMode(mode: Int) = encodeAttached(SafeRequests.setControlMode(mode))

    /**
     * Runs the timeSyncRefreshDue operation.
     */
    fun timeSyncRefreshDue(nowUs: ULong = monotonicMicros()): Boolean {
        val last = lastSuccessfulTimeSyncHostUs ?: return true
        return nowUs >= last && nowUs - last >= TIME_SYNC_REFRESH_MICROS
    }

    /**
     * Rejects pre-session, replayed, expired, or unreasonably future board telemetry timestamps.
     * The mapping is refreshed only by a valid TimeSync response for this physical attachment.
     */
    fun requireFreshTelemetryTimestamp(
        frameSenderUs: ULong,
        sampleDeviceUs: ULong,
        receivedHostUs: ULong,
        maximumAgeUs: ULong,
        maximumFutureSkewUs: ULong,
    ) {
        requireAttached()
        val mapping = requireCurrentTimeMapping("SensorSample")
        if (frameSenderUs < sampleDeviceUs) {
            throw ProtocolException(
                ProtocolErrorKind.PAYLOAD_INVALID,
                "SensorSample frame timestamp precedes its measurement timestamp",
            )
        }
        requireFreshDeviceTimestamp(
            label = "measurement",
            deviceTimestampUs = sampleDeviceUs,
            receivedHostUs = receivedHostUs,
            mapping = mapping,
            maximumAgeUs = maximumAgeUs,
            maximumFutureSkewUs = maximumFutureSkewUs,
        )
        requireFreshDeviceTimestamp(
            label = "frame",
            deviceTimestampUs = frameSenderUs,
            receivedHostUs = receivedHostUs,
            mapping = mapping,
            maximumAgeUs = maximumAgeUs,
            maximumFutureSkewUs = maximumFutureSkewUs,
        )
    }

    /**
     * Runs the requireFreshDeviceFrameTimestamp operation.
     */
    fun requireFreshDeviceFrameTimestamp(
        frameSenderUs: ULong,
        receivedHostUs: ULong,
        maximumAgeUs: ULong,
        maximumFutureSkewUs: ULong,
    ) {
        requireAttached()
        requireFreshDeviceTimestamp(
            label = "frame",
            deviceTimestampUs = frameSenderUs,
            receivedHostUs = receivedHostUs,
            mapping = requireCurrentTimeMapping("session response"),
            maximumAgeUs = maximumAgeUs,
            maximumFutureSkewUs = maximumFutureSkewUs,
        )
    }

    /**
     * Validates the board's global modulo-u32 frame sequence without advancing it.
     * Call [commitInboundSequence] only after the complete frame payload has been accepted.
     */
    fun requireFreshInboundSequence(sequence: UInt, priority: MessagePriority) {
        requireAttached()
        val highest = highestAcceptedInboundSequence ?: return
        val forwardDistance = sequence - highest
        if (forwardDistance == 0u) {
            throw ProtocolException(
                ProtocolErrorKind.POLICY_REJECTED,
                "Board frame sequence is a duplicate",
            )
        }
        if (forwardDistance < 0x8000_0000u) return

        val backwardDistance = highest - sequence
        if (backwardDistance >= INBOUND_REORDER_WINDOW.toUInt()) {
            throw ProtocolException(
                ProtocolErrorKind.POLICY_REJECTED,
                "Board frame sequence is outside the bounded reorder window",
            )
        }
        val offset = backwardDistance.toInt()
        if (acceptedInboundPriorities[offset] != null) {
            throw ProtocolException(
                ProtocolErrorKind.POLICY_REJECTED,
                "Board frame sequence is a duplicate",
            )
        }
        val wasOvertakenByHigherPriorityFrame = (0 until offset).any { laterOffset ->
            val laterPriority = acceptedInboundPriorities[laterOffset]
            laterPriority != null && laterPriority.wireValue < priority.wireValue
        }
        if (!wasOvertakenByHigherPriorityFrame) {
            throw ProtocolException(
                ProtocolErrorKind.POLICY_REJECTED,
                "Board frame reordering is inconsistent with the priority queue",
            )
        }
    }

    /**
     * Runs the commitInboundSequence operation.
     */
    fun commitInboundSequence(sequence: UInt, priority: MessagePriority) {
        requireFreshInboundSequence(sequence, priority)
        val highest = highestAcceptedInboundSequence
        if (highest == null) {
            highestAcceptedInboundSequence = sequence
            acceptedInboundPriorities.fill(null)
            acceptedInboundPriorities[0] = priority
            return
        }
        val forwardDistance = sequence - highest
        if (forwardDistance != 0u && forwardDistance < 0x8000_0000u) {
            val shift = forwardDistance.toInt()
            if (shift >= INBOUND_REORDER_WINDOW) {
                acceptedInboundPriorities.fill(null)
            } else {
                for (offset in INBOUND_REORDER_WINDOW - 1 downTo shift) {
                    acceptedInboundPriorities[offset] = acceptedInboundPriorities[offset - shift]
                }
                for (offset in 0 until shift) acceptedInboundPriorities[offset] = null
            }
            highestAcceptedInboundSequence = sequence
            acceptedInboundPriorities[0] = priority
            return
        }
        acceptedInboundPriorities[(highest - sequence).toInt()] = priority
    }

    /** Applies a TimeSync response only while its matching request is pending. */
    fun acceptTimeSync(frame: DecodedFrame, receivedHostUs: ULong): ULong {
        requireAttached()
        val timeSync = frame.decodeTimeSync() ?: throw ProtocolException(
            ProtocolErrorKind.PAYLOAD_INVALID,
            "frame is not TimeSyncResponse",
        )
        val pending = pendingTimeSyncHostUs.firstOrNull { it == timeSync.clientSendUs }
        if (
            pending == null ||
            timeSync.sessionToken == 0uL ||
            timeSync.deviceTxUs < timeSync.deviceRxUs ||
            frame.header.senderMonotonicUs < timeSync.deviceTxUs
        ) {
            throw ProtocolException(
                ProtocolErrorKind.PAYLOAD_INVALID,
                "invalid or unsolicited TimeSyncResponse",
            )
        }
        if (receivedHostUs < pending) {
            throw ProtocolException(
                ProtocolErrorKind.PAYLOAD_INVALID,
                "TimeSync host clock regressed",
            )
        }
        val deviceProcessingUs = timeSync.deviceTxUs - timeSync.deviceRxUs
        val hostRoundTripUs = receivedHostUs - pending
        if (
            deviceProcessingUs > hostRoundTripUs ||
            pending > ULong.MAX_VALUE - deviceProcessingUs
        ) {
            throw ProtocolException(
                ProtocolErrorKind.PAYLOAD_INVALID,
                "TimeSync processing interval exceeds the host round trip",
            )
        }
        sessionToken = timeSync.sessionToken
        pendingTimeSyncHostUs.clear()
        lastSuccessfulTimeSyncHostUs = receivedHostUs
        if (attachmentDeviceFloorUs == null) attachmentDeviceFloorUs = timeSync.deviceRxUs
        timeMapping = TimeMapping(
            deviceReferenceUs = timeSync.deviceTxUs,
            // Use the conservative lower bound for when the device transmitted. This prevents
            // a response delayed in Android scheduling from projecting immediately-following
            // DeviceInfo/telemetry timestamps into the future.
            hostReferenceUs = pending + deviceProcessingUs,
        )
        return timeSync.sessionToken
    }

    /**
     * Runs the feed operation.
     */
    fun feed(bytes: ByteArray): List<Event> {
        if (!attached) return emptyList()
        val output = mutableListOf<Event>()
        for (streamEvent in accumulator.feed(bytes)) {
            when (streamEvent) {
                is StreamEvent.Rejected -> output += Event.Rejected(streamEvent.exception)
                is StreamEvent.Frame -> output += Event.FrameReceived(streamEvent.value)
            }
        }
        return output
    }

    /**
     * Runs the encodeAttached operation.
     */
    private fun encodeAttached(
        request: OutboundRequest,
        senderMonotonicUs: ULong = monotonicMicros(),
    ): EncodedCommand {
        requireAttached()
        return encode(request, senderMonotonicUs)
    }

    /**
     * Runs the encode operation.
     */
    private fun encode(
        request: OutboundRequest,
        senderMonotonicUs: ULong = monotonicMicros(),
    ): EncodedCommand {
        val sequence = nextSequence
        nextSequence += 1u
        return EncodedCommand(
            sequence = sequence,
            type = request.messageType,
            bytes = FrameCodec.encode(
                request = request,
                sequence = sequence,
                senderMonotonicUs = senderMonotonicUs,
                sessionToken = if (request.sessionBound) sessionToken else null,
            ),
        )
    }

    /**
     * Runs the requireAttached operation.
     */
    private fun requireAttached() {
        if (!attached) {
            throw ProtocolException(ProtocolErrorKind.POLICY_REJECTED, "USB is not attached")
        }
    }

    /**
     * Runs the requireCurrentTimeMapping operation.
     */
    private fun requireCurrentTimeMapping(frameName: String): TimeMapping {
        if (sessionToken == null) {
            throw ProtocolException(
                ProtocolErrorKind.POLICY_REJECTED,
                "$frameName arrived before the current USB session was synchronized",
            )
        }
        return timeMapping ?: throw ProtocolException(
            ProtocolErrorKind.POLICY_REJECTED,
            "$frameName has no current TimeSync mapping",
        )
    }

    /**
     * Runs the requireFreshDeviceTimestamp operation.
     */
    private fun requireFreshDeviceTimestamp(
        label: String,
        deviceTimestampUs: ULong,
        receivedHostUs: ULong,
        mapping: TimeMapping,
        maximumAgeUs: ULong,
        maximumFutureSkewUs: ULong,
    ) {
        val deviceFloorUs = attachmentDeviceFloorUs ?: throw ProtocolException(
            ProtocolErrorKind.POLICY_REJECTED,
            "$label timestamp has no attachment TimeSync floor",
        )
        if (deviceTimestampUs < deviceFloorUs) {
            throw ProtocolException(
                ProtocolErrorKind.PAYLOAD_INVALID,
                "$label timestamp predates the current USB attachment",
            )
        }
        val expectedHostUs = if (deviceTimestampUs >= mapping.deviceReferenceUs) {
            val deviceDeltaUs = deviceTimestampUs - mapping.deviceReferenceUs
            if (deviceDeltaUs > ULong.MAX_VALUE - mapping.hostReferenceUs) {
                throw ProtocolException(
                    ProtocolErrorKind.PAYLOAD_INVALID,
                    "$label timestamp overflows the TimeSync mapping",
                )
            }
            mapping.hostReferenceUs + deviceDeltaUs
        } else {
            val backwardDeviceDeltaUs = mapping.deviceReferenceUs - deviceTimestampUs
            if (backwardDeviceDeltaUs > mapping.hostReferenceUs) {
                throw ProtocolException(
                    ProtocolErrorKind.PAYLOAD_INVALID,
                    "$label timestamp underflows the TimeSync mapping",
                )
            }
            mapping.hostReferenceUs - backwardDeviceDeltaUs
        }
        if (expectedHostUs > receivedHostUs) {
            if (expectedHostUs - receivedHostUs > maximumFutureSkewUs) {
                throw ProtocolException(
                    ProtocolErrorKind.PAYLOAD_INVALID,
                    "$label timestamp is unreasonably far in the future",
                )
            }
        } else if (receivedHostUs - expectedHostUs > maximumAgeUs) {
            throw ProtocolException(
                ProtocolErrorKind.PAYLOAD_INVALID,
                "$label timestamp is stale or replayed",
            )
        }
    }

    /**
     * Runs the reset operation.
     */
    private fun reset() {
        accumulator.reset()
        nextSequence = 1u
        pendingTimeSyncHostUs.clear()
        lastSuccessfulTimeSyncHostUs = null
        timeMapping = null
        attachmentDeviceFloorUs = null
        highestAcceptedInboundSequence = null
        acceptedInboundPriorities.fill(null)
        sessionToken = null
    }
}
