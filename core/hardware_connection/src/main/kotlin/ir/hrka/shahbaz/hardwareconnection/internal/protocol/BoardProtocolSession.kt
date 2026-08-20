/** One physical-attachment Protocol v2 session with bounded parser and fresh token state. */
package ir.hrka.shahbaz.hardwareconnection.internal.protocol

internal class BoardProtocolSession(
    private val monotonicMicros: () -> ULong,
) {
    companion object {
        const val TIME_SYNC_REFRESH_MICROS: ULong = 30_000_000uL

        /** Mirrors production EspIdfUsbCdcTransport::kTxQueueDepth. */
        const val INBOUND_REORDER_WINDOW: Int = 12
    }

    sealed interface Event {
        data class FrameReceived(val frame: DecodedFrame) : Event
        data class Rejected(val exception: ProtocolException) : Event
    }

    data class EncodedCommand(
        val sequence: UInt,
        val type: MessageType,
        val bytes: ByteArray,
    )

    private val accumulator = FrameAccumulator()
    private var nextSequence = 1u
    private var pendingTimeSyncHostUs: ULong? = null
    private var lastSuccessfulTimeSyncHostUs: ULong? = null
    private var timeMapping: TimeMapping? = null
    private var attachmentDeviceFloorUs: ULong? = null
    private var highestAcceptedInboundSequence: UInt? = null
    private val acceptedInboundPriorities = arrayOfNulls<MessagePriority>(INBOUND_REORDER_WINDOW)

    private data class TimeMapping(
        val deviceReferenceUs: ULong,
        val hostReferenceUs: ULong,
    )

    var attached: Boolean = false
        private set
    var sessionToken: ULong? = null
        private set

    fun attach() {
        reset()
        attached = true
    }

    fun detach() {
        reset()
        attached = false
    }

    fun buildTimeSync(): EncodedCommand {
        requireAttached()
        val now = monotonicMicros()
        pendingTimeSyncHostUs = now
        return encode(SafeRequests.timeSync(now))
    }

    fun buildDeviceInfo() = encodeAttached(SafeRequests.deviceInfo())
    fun buildDeviceStatus() = encodeAttached(SafeRequests.deviceStatus())
    fun buildStartTelemetry() = encodeAttached(SafeRequests.startTelemetry())
    fun buildStopTelemetry() = encodeAttached(SafeRequests.stopTelemetry())
    fun buildHeartbeat() = encodeAttached(SafeRequests.heartbeat())
    fun buildDisarm() = encodeAttached(SafeRequests.disarm())

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
        val pending = pendingTimeSyncHostUs
        if (
            pending == null ||
            timeSync.clientSendUs != pending ||
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
        sessionToken = timeSync.sessionToken
        pendingTimeSyncHostUs = null
        lastSuccessfulTimeSyncHostUs = receivedHostUs
        if (attachmentDeviceFloorUs == null) attachmentDeviceFloorUs = timeSync.deviceRxUs
        timeMapping = TimeMapping(
            deviceReferenceUs = timeSync.deviceTxUs,
            hostReferenceUs = receivedHostUs,
        )
        return timeSync.sessionToken
    }

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

    private fun encodeAttached(request: OutboundRequest): EncodedCommand {
        requireAttached()
        return encode(request)
    }

    private fun encode(request: OutboundRequest): EncodedCommand {
        val sequence = nextSequence
        nextSequence += 1u
        return EncodedCommand(
            sequence = sequence,
            type = request.messageType,
            bytes = FrameCodec.encode(
                request = request,
                sequence = sequence,
                senderMonotonicUs = monotonicMicros(),
                sessionToken = if (request.sessionBound) sessionToken else null,
            ),
        )
    }

    private fun requireAttached() {
        if (!attached) {
            throw ProtocolException(ProtocolErrorKind.POLICY_REJECTED, "USB is not attached")
        }
    }

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

    private fun reset() {
        accumulator.reset()
        nextSequence = 1u
        pendingTimeSyncHostUs = null
        lastSuccessfulTimeSyncHostUs = null
        timeMapping = null
        attachmentDeviceFloorUs = null
        highestAcceptedInboundSequence = null
        acceptedInboundPriorities.fill(null)
        sessionToken = null
    }
}
