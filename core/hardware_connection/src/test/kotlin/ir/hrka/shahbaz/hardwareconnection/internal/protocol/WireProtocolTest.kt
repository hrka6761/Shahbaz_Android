package ir.hrka.shahbaz.hardwareconnection.internal.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WireProtocolTest {
    @Test
    fun crcAndCobsMatchCanonicalVectors() {
        assertEquals(0xE3069283u, Crc32c.calculate("123456789".encodeToByteArray()).toUInt())
        val cases = listOf(
            byteArrayOf(),
            byteArrayOf(0),
            byteArrayOf(0x11, 0, 0x22, 0, 0, 0x33),
            ByteArray(256) { it.toByte() },
        )
        cases.forEach { assertArrayEquals(it, Cobs.decode(Cobs.encode(it))) }
    }

    @Test
    fun sharedGoldenPingFrameDecodesExactly() {
        val expected = hex(
            "04 02 16 42 01 01 02 01 0e 04 03 02 01 08 07 06 " +
                "05 04 03 02 01 08 02 aa 0b 55 01 02 03 04 05 6b 13 ee 0b 00",
        )
        val decoded = FrameCodec.decodeBody(expected.copyOf(expected.size - 1))
        assertEquals(MessageType.PING, decoded.header.messageType)
        assertEquals(0x01020304u, decoded.header.sequence)
        assertEquals(0x0102030405060708uL, decoded.header.senderMonotonicUs)
        assertArrayEquals(hex("aa 00 55 01 02 03 04 05"), decoded.payload)
    }

    @Test
    fun guardedPolicyEncodesActuatorCommandsWithSessionToken() {
        val token = 0x0102030405060708uL
        val motor = FrameCodec.decodeBody(
            FrameCodec.encode(
                SafeRequests.motorCommand(channel = 3, pulseMicros = 1_500),
                sequence = 7u,
                senderMonotonicUs = 1_000uL,
                sessionToken = token,
            ).dropLast(1).toByteArray(),
        )

        assertEquals(MessageType.MOTOR_COMMAND, motor.header.messageType)
        assertEquals(11, motor.payload.size)
        assertEquals(token, readU64(motor.payload, 0))
        assertEquals(3, readU8(motor.payload, 8))
        assertEquals(1_500, readU16(motor.payload, 9))

        val arm = FrameCodec.decodeBody(
            FrameCodec.encode(
                SafeRequests.armRequest(),
                sequence = 8u,
                senderMonotonicUs = 1_100uL,
                sessionToken = token,
            ).dropLast(1).toByteArray(),
        )
        assertEquals(MessageType.ARM_REQUEST, arm.header.messageType)
        assertEquals(8, arm.payload.size)
        assertEquals(token, readU64(arm.payload, 0))
    }

    @Test
    fun guardedPolicyRejectsOutboundOnlyFramesThatTheHostMustNotSend() {
        val error = assertThrows(ProtocolException::class.java) {
            FrameCodec.encode(
                OutboundRequest(MessageType.PROTOCOL_ERROR, MessagePriority.HIGH, byteArrayOf(), false),
                sequence = 1u,
                senderMonotonicUs = 1uL,
                sessionToken = null,
            )
        }
        assertEquals(ProtocolErrorKind.POLICY_REJECTED, error.kind)
    }

    @Test
    fun motorCommandPreservesFlightControllerGenerationTimestamp() {
        var now = 1_000uL
        val session = BoardProtocolSession { now }
        session.attach()
        val timeSync = session.buildTimeSync()
        val requestHostUs = now
        now = 1_100uL
        val response = deviceFrame(
            MessageType.TIME_SYNC_RESPONSE,
            sequence = 1u,
            senderUs = 2_000uL,
            payload = u64(requestHostUs) + u64(1_001uL) + u64(1_002uL) + u64(0x1122uL),
        )
        val frame = (session.feed(response).single() as BoardProtocolSession.Event.FrameReceived).frame
        session.requireFreshInboundSequence(frame.header.sequence, frame.header.priority)
        session.acceptTimeSync(frame, now)
        session.commitInboundSequence(frame.header.sequence, frame.header.priority)

        now = 5_000uL
        val command = session.buildMotorCommand(
            channel = 0,
            pulseMicros = 1_500,
            generatedAtHostMicros = 1_250uL,
        )
        val decoded = FrameCodec.decodeBody(command.bytes.dropLast(1).toByteArray())

        assertEquals(MessageType.TIME_SYNC_REQUEST, timeSync.type)
        assertEquals(1_250uL, decoded.header.senderMonotonicUs)
    }

    @Test
    fun reconnectClearsTokenAndBufferedBytes() {
        var now = 1_000uL
        val session = BoardProtocolSession { now }
        session.attach()
        val request = session.buildTimeSync()
        assertEquals(MessageType.TIME_SYNC_REQUEST, request.type)
        session.feed(byteArrayOf(0x02)) // Incomplete prior-session COBS body.

        session.detach()
        session.attach()
        assertEquals(null, session.sessionToken)
        val newRequest = session.buildTimeSync()
        assertEquals(1u, newRequest.sequence)
        val requestHostUs = now
        now = 1_100uL
        val response = deviceFrame(
            MessageType.TIME_SYNC_RESPONSE,
            sequence = 7u,
            senderUs = 2_000uL,
            payload = u64(requestHostUs) + u64(1_001uL) + u64(1_002uL) + u64(0x1122uL),
        )
        val events = session.feed(response)
        val frame = (events.single() as BoardProtocolSession.Event.FrameReceived).frame
        session.requireFreshInboundSequence(frame.header.sequence, frame.header.priority)
        assertEquals(0x1122uL, session.acceptTimeSync(frame, now))
        session.commitInboundSequence(frame.header.sequence, frame.header.priority)
        assertEquals(0x1122uL, session.sessionToken)
    }

    @Test
    fun timeSyncRejectsWrongEchoAndZeroToken() {
        var now = 5_000uL
        val session = BoardProtocolSession { now }
        session.attach()
        session.buildTimeSync()
        val wrongEcho = deviceFrame(
            MessageType.TIME_SYNC_RESPONSE,
            1u,
            10uL,
            u64(now + 1uL) + u64(1uL) + u64(2uL) + u64(0uL),
        )
        val frame = (
            session.feed(wrongEcho).single() as BoardProtocolSession.Event.FrameReceived
        ).frame
        assertThrows(ProtocolException::class.java) { session.acceptTimeSync(frame, now) }
        assertEquals(null, session.sessionToken)
    }

    @Test
    fun delayedResponseToAnEarlierBoundedRetryCanEstablishTheSession() {
        var now = 1_000uL
        val session = BoardProtocolSession { now }
        session.attach()
        session.buildTimeSync()
        now = 1_500uL
        session.buildTimeSync()

        now = 1_600uL
        val delayedFirstResponse = deviceFrame(
            MessageType.TIME_SYNC_RESPONSE,
            sequence = 1u,
            senderUs = 2_020uL,
            payload = u64(1_000uL) + u64(2_000uL) + u64(2_020uL) + u64(0x55uL),
        )
        val frame = (
            session.feed(delayedFirstResponse).single() as BoardProtocolSession.Event.FrameReceived
        ).frame

        assertEquals(0x55uL, session.acceptTimeSync(frame, now))
        assertEquals(0x55uL, session.sessionToken)
        session.requireFreshDeviceFrameTimestamp(
            frameSenderUs = 2_600uL,
            receivedHostUs = 1_610uL,
            maximumAgeUs = 100uL,
            maximumFutureSkewUs = 100uL,
        )
    }

    @Test
    fun accumulatorResynchronizesAfterOversizeInput() {
        val accumulator = FrameAccumulator()
        accumulator.feed(ByteArray(WireContract.MAX_COBS_BODY_LENGTH + 1) { 0x11 })
        val valid = deviceFrame(MessageType.HEARTBEAT_ACK, 1u, 1uL, byteArrayOf())
        val events = accumulator.feed(byteArrayOf(0) + valid)
        assertTrue(events.first() is StreamEvent.Rejected)
        assertTrue(events.last() is StreamEvent.Frame)
    }

    @Test
    fun crcValidPayloadFailuresAreReturnedAsRejections() {
        val protocolFailure = handleCrcValidFrameSafely {
            throw ProtocolException(ProtocolErrorKind.PAYLOAD_INVALID, "bad payload")
        }
        assertTrue(protocolFailure is FrameHandlingResult.Rejected)
        assertEquals(
            ProtocolErrorKind.PAYLOAD_INVALID,
            (protocolFailure as FrameHandlingResult.Rejected).exception.kind,
        )

        val boundsFailure = handleCrcValidFrameSafely {
            byteArrayOf()[0]
        }
        assertTrue(boundsFailure is FrameHandlingResult.Rejected)
        assertEquals(
            ProtocolErrorKind.PAYLOAD_INVALID,
            (boundsFailure as FrameHandlingResult.Rejected).exception.kind,
        )

        val malformedSensor = DecodedFrame(
            FrameHeader(MessageType.SENSOR_SAMPLE, MessagePriority.NORMAL, 1u, 1uL, 0),
            byteArrayOf(),
        )
        val malformedResult = handleCrcValidFrameSafely {
            malformedSensor.decodeSensorSample()
        }
        assertTrue(malformedResult is FrameHandlingResult.Rejected)
    }

    @Test
    fun heartbeatAckAndStartTelemetryAckAreStrictlyDecoded() {
        DecodedFrame(
            FrameHeader(MessageType.HEARTBEAT_ACK, MessagePriority.CRITICAL, 1u, 1uL, 0),
            byteArrayOf(),
        ).requireHeartbeatAckPayload()

        val heartbeatWithPayload = DecodedFrame(
            FrameHeader(MessageType.HEARTBEAT_ACK, MessagePriority.CRITICAL, 1u, 1uL, 1),
            byteArrayOf(1),
        )
        assertThrows(ProtocolException::class.java) {
            heartbeatWithPayload.requireHeartbeatAckPayload()
        }

        val validAck = DecodedFrame(
            FrameHeader(MessageType.COMMAND_ACK, MessagePriority.HIGH, 2u, 2uL, 5),
            u32(77u) + byteArrayOf(ApplicationAction.START_TELEMETRY.wireValue.toByte()),
        ).decodeCommandAck()
        assertEquals(77u, validAck?.requestSequence)
        assertEquals(ApplicationAction.START_TELEMETRY, validAck?.applicationAction)
        validAck?.requireAcknowledges(77u, ApplicationAction.START_TELEMETRY)
        assertThrows(ProtocolException::class.java) {
            validAck?.requireAcknowledges(78u, ApplicationAction.START_TELEMETRY)
        }
        assertThrows(ProtocolException::class.java) {
            validAck?.requireAcknowledges(77u, ApplicationAction.STOP_TELEMETRY)
        }

        val unknownAction = DecodedFrame(
            FrameHeader(MessageType.COMMAND_ACK, MessagePriority.HIGH, 2u, 2uL, 5),
            u32(77u) + byteArrayOf(0x7F),
        )
        assertThrows(ProtocolException::class.java) { unknownAction.decodeCommandAck() }
    }

    @Test
    fun inboundMessagePolicyRejectsPreSessionAndOutboundOnlyFrames() {
        assertThrows(ProtocolException::class.java) {
            MessageType.DEVICE_INFO_RESPONSE.requireAllowedInboundAt(
                InboundSessionStage.NOT_SYNCHRONIZED,
            )
        }
        assertThrows(ProtocolException::class.java) {
            MessageType.DEVICE_STATUS_RESPONSE.requireAllowedInboundAt(
                InboundSessionStage.VALIDATING_DEVICE,
            )
        }
        listOf(
            MessageType.DEVICE_INFO_REQUEST,
            MessageType.START_TELEMETRY,
            MessageType.ARM_REQUEST,
            MessageType.ACTUATOR_COMMAND,
            MessageType.PROTOCOL_ERROR,
        ).forEach { messageType ->
            assertThrows(ProtocolException::class.java) {
                messageType.requireAllowedInboundAt(InboundSessionStage.READY)
            }
        }

        MessageType.DEVICE_INFO_RESPONSE.requireAllowedInboundAt(
            InboundSessionStage.VALIDATING_DEVICE,
        )
        MessageType.HEARTBEAT_ACK.requireAllowedInboundAt(
            InboundSessionStage.AWAITING_HEARTBEAT,
        )
        MessageType.COMMAND_ACK.requireAllowedInboundAt(
            InboundSessionStage.STARTING_TELEMETRY,
        )
        MessageType.COMMAND_ACK.requireAllowedInboundAt(InboundSessionStage.READY)
        MessageType.DEVICE_STATUS_RESPONSE.requireAllowedInboundAt(
            InboundSessionStage.STARTING_TELEMETRY,
        )
        MessageType.SENSOR_SAMPLE.requireAllowedInboundAt(InboundSessionStage.READY)

        assertThrows(ProtocolException::class.java) {
            MessageType.COMMAND_ACK.requireAllowedInboundAt(
                InboundSessionStage.AWAITING_HEARTBEAT,
            )
        }
    }

    @Test
    fun inboundPriorityMustMatchProductionSenderPolicy() {
        DecodedFrame(
            FrameHeader(MessageType.HEARTBEAT_ACK, MessagePriority.CRITICAL, 1u, 1uL, 0),
            byteArrayOf(),
        ).requireExpectedInboundPriority()
        DecodedFrame(
            FrameHeader(MessageType.SENSOR_SAMPLE, MessagePriority.NORMAL, 2u, 2uL, 0),
            byteArrayOf(),
        ).requireExpectedInboundPriority()
        assertThrows(ProtocolException::class.java) {
            DecodedFrame(
                FrameHeader(MessageType.HEARTBEAT_ACK, MessagePriority.LOW, 3u, 3uL, 0),
                byteArrayOf(),
            ).requireExpectedInboundPriority()
        }
        assertThrows(ProtocolException::class.java) {
            DecodedFrame(
                FrameHeader(MessageType.ARM_REQUEST, MessagePriority.CRITICAL, 4u, 4uL, 0),
                byteArrayOf(),
            ).requireExpectedInboundPriority()
        }
    }

    @Test
    fun telemetryTimestampsRequireCurrentTimeSyncAndFreshWindow() {
        var now = 10_000uL
        val session = BoardProtocolSession { now }
        session.attach()

        assertThrows(ProtocolException::class.java) {
            session.requireFreshTelemetryTimestamp(
                frameSenderUs = 1_100uL,
                sampleDeviceUs = 1_090uL,
                receivedHostUs = now,
                maximumAgeUs = 1_000uL,
                maximumFutureSkewUs = 100uL,
            )
        }

        session.buildTimeSync()
        now = 10_100uL
        val timeSyncFrame = (session.feed(
            deviceFrame(
                MessageType.TIME_SYNC_RESPONSE,
                sequence = 1u,
                senderUs = 1_020uL,
                payload = u64(10_000uL) + u64(1_000uL) + u64(1_020uL) + u64(99uL),
            ),
        ).single() as BoardProtocolSession.Event.FrameReceived).frame
        session.acceptTimeSync(timeSyncFrame, now)
        session.commitInboundSequence(timeSyncFrame.header.sequence, timeSyncFrame.header.priority)

        session.requireFreshTelemetryTimestamp(
            frameSenderUs = 1_120uL,
            sampleDeviceUs = 1_100uL,
            receivedHostUs = 10_250uL,
            maximumAgeUs = 1_000uL,
            maximumFutureSkewUs = 100uL,
        )

        assertThrows(ProtocolException::class.java) {
            session.requireFreshTelemetryTimestamp(
                frameSenderUs = 1_100uL,
                sampleDeviceUs = 999uL,
                receivedHostUs = 10_250uL,
                maximumAgeUs = 1_000uL,
                maximumFutureSkewUs = 100uL,
            )
        }
        assertThrows(ProtocolException::class.java) {
            session.requireFreshTelemetryTimestamp(
                frameSenderUs = 1_120uL,
                sampleDeviceUs = 1_100uL,
                receivedHostUs = 12_000uL,
                maximumAgeUs = 1_000uL,
                maximumFutureSkewUs = 100uL,
            )
        }
        assertThrows(ProtocolException::class.java) {
            session.requireFreshTelemetryTimestamp(
                frameSenderUs = 2_500uL,
                sampleDeviceUs = 2_400uL,
                receivedHostUs = 10_200uL,
                maximumAgeUs = 1_000uL,
                maximumFutureSkewUs = 100uL,
            )
        }

        session.detach()
        session.attach()
        assertThrows(ProtocolException::class.java) {
            session.requireFreshTelemetryTimestamp(
                frameSenderUs = 1_120uL,
                sampleDeviceUs = 1_100uL,
                receivedHostUs = 10_250uL,
                maximumAgeUs = 1_000uL,
                maximumFutureSkewUs = 100uL,
            )
        }
    }

    @Test
    fun inboundFrameSequenceRejectsReplayAndOutOfOrderWithoutCommittingRejections() {
        var now = 10_000uL
        val session = BoardProtocolSession { now }
        session.attach()
        session.buildTimeSync()
        now = 10_100uL
        val syncFrame = (session.feed(
            deviceFrame(
                MessageType.TIME_SYNC_RESPONSE,
                sequence = 10u,
                senderUs = 1_020uL,
                payload = u64(10_000uL) + u64(1_000uL) + u64(1_020uL) + u64(99uL),
            ),
        ).single() as BoardProtocolSession.Event.FrameReceived).frame
        session.requireFreshInboundSequence(syncFrame.header.sequence, syncFrame.header.priority)
        session.acceptTimeSync(syncFrame, now)
        session.commitInboundSequence(syncFrame.header.sequence, syncFrame.header.priority)

        session.requireFreshInboundSequence(11u, MessagePriority.HIGH)
        session.commitInboundSequence(11u, MessagePriority.HIGH)
        assertThrows(ProtocolException::class.java) {
            session.requireFreshInboundSequence(11u, MessagePriority.HIGH)
        }
        assertThrows(ProtocolException::class.java) {
            session.requireFreshInboundSequence(10u, MessagePriority.HIGH)
        }

        // Validation alone deliberately does not advance state, so a failed payload cannot
        // consume a sequence and hide a corrected frame with the same sequence.
        session.requireFreshInboundSequence(20u, MessagePriority.HIGH)
        session.requireFreshInboundSequence(20u, MessagePriority.HIGH)
        session.commitInboundSequence(20u, MessagePriority.HIGH)
        session.requireFreshInboundSequence(21u, MessagePriority.HIGH)
    }

    @Test
    fun inboundFrameSequenceSupportsU32WrapAndInvalidTimeSyncDoesNotCommit() {
        var now = 20_000uL
        val session = BoardProtocolSession { now }
        session.attach()
        session.buildTimeSync()

        val invalid = (session.feed(
            deviceFrame(
                MessageType.TIME_SYNC_RESPONSE,
                sequence = UInt.MAX_VALUE - 1u,
                senderUs = 2_020uL,
                payload = u64(20_000uL) + u64(2_000uL) + u64(2_020uL) + u64(0uL),
            ),
        ).single() as BoardProtocolSession.Event.FrameReceived).frame
        session.requireFreshInboundSequence(invalid.header.sequence, invalid.header.priority)
        assertThrows(ProtocolException::class.java) {
            session.acceptTimeSync(invalid, now)
        }

        now = 20_100uL
        val corrected = (session.feed(
            deviceFrame(
                MessageType.TIME_SYNC_RESPONSE,
                sequence = UInt.MAX_VALUE - 1u,
                senderUs = 2_020uL,
                payload = u64(20_000uL) + u64(2_000uL) + u64(2_020uL) + u64(77uL),
            ),
        ).single() as BoardProtocolSession.Event.FrameReceived).frame
        session.requireFreshInboundSequence(corrected.header.sequence, corrected.header.priority)
        session.acceptTimeSync(corrected, now)
        session.commitInboundSequence(corrected.header.sequence, corrected.header.priority)
        session.commitInboundSequence(UInt.MAX_VALUE, MessagePriority.HIGH)
        session.commitInboundSequence(0u, MessagePriority.HIGH)
        session.requireFreshInboundSequence(1u, MessagePriority.HIGH)
    }

    @Test
    fun batchedNormalFrameIsProcessedBeforeFollowingTimeSyncChangesMapping() {
        var now = 10_000uL
        val session = BoardProtocolSession { now }
        session.attach()
        session.buildTimeSync()
        now = 10_100uL
        val initialSync = (session.feed(
            deviceFrame(
                MessageType.TIME_SYNC_RESPONSE,
                sequence = 1u,
                senderUs = 1_020uL,
                payload = u64(10_000uL) + u64(1_000uL) + u64(1_020uL) + u64(99uL),
            ),
        ).single() as BoardProtocolSession.Event.FrameReceived).frame
        session.acceptTimeSync(initialSync, now)
        session.commitInboundSequence(initialSync.header.sequence, initialSync.header.priority)

        now = 10_200uL
        session.buildTimeSync()
        val events = session.feed(
            deviceFrame(
                MessageType.HEARTBEAT_ACK,
                2u,
                1_120uL,
                byteArrayOf(),
                priority = MessagePriority.CRITICAL,
            ) +
                deviceFrame(
                    MessageType.TIME_SYNC_RESPONSE,
                    sequence = 3u,
                    senderUs = 1_220uL,
                    payload = u64(10_200uL) + u64(1_200uL) + u64(1_220uL) + u64(99uL),
                ),
        )
        assertEquals(2, events.size)

        val heartbeat = (events[0] as BoardProtocolSession.Event.FrameReceived).frame
        session.requireFreshInboundSequence(heartbeat.header.sequence, heartbeat.header.priority)
        session.requireFreshDeviceFrameTimestamp(
            frameSenderUs = heartbeat.header.senderMonotonicUs,
            receivedHostUs = 10_200uL,
            maximumAgeUs = 100uL,
            maximumFutureSkewUs = 100uL,
        )
        session.commitInboundSequence(heartbeat.header.sequence, heartbeat.header.priority)

        val refresh = (events[1] as BoardProtocolSession.Event.FrameReceived).frame
        session.requireFreshInboundSequence(refresh.header.sequence, refresh.header.priority)
        session.acceptTimeSync(refresh, receivedHostUs = 10_300uL)
        session.commitInboundSequence(refresh.header.sequence, refresh.header.priority)

        // The same timestamp is older relative to the later mapping, proving feed() did not apply
        // that refresh before the preceding batched frame was processed in wire order.
        assertThrows(ProtocolException::class.java) {
            session.requireFreshDeviceFrameTimestamp(
                frameSenderUs = heartbeat.header.senderMonotonicUs,
                receivedHostUs = 10_310uL,
                maximumAgeUs = 100uL,
                maximumFutureSkewUs = 100uL,
            )
        }
    }

    @Test
    fun lateTelemetryOvertakenByPeriodicTimeSyncUsesBackwardFreshnessMapping() {
        var now = 10_000uL
        val session = BoardProtocolSession { now }
        session.attach()
        session.buildTimeSync()
        now = 10_100uL
        val initialSync = (session.feed(
            deviceFrame(
                MessageType.TIME_SYNC_RESPONSE,
                sequence = 1u,
                senderUs = 1_020uL,
                payload = u64(10_000uL) + u64(1_000uL) + u64(1_020uL) + u64(99uL),
            ),
        ).single() as BoardProtocolSession.Event.FrameReceived).frame
        session.acceptTimeSync(initialSync, now)
        session.commitInboundSequence(initialSync.header.sequence, initialSync.header.priority)

        now = 10_200uL
        session.buildTimeSync()
        val periodicSync = (session.feed(
            deviceFrame(
                MessageType.TIME_SYNC_RESPONSE,
                sequence = 3u,
                senderUs = 1_220uL,
                payload = u64(10_200uL) + u64(1_200uL) + u64(1_220uL) + u64(99uL),
            ),
        ).single() as BoardProtocolSession.Event.FrameReceived).frame
        session.requireFreshInboundSequence(periodicSync.header.sequence, periodicSync.header.priority)
        session.acceptTimeSync(periodicSync, receivedHostUs = 10_300uL)
        session.commitInboundSequence(periodicSync.header.sequence, periodicSync.header.priority)

        // NORMAL telemetry sequence 2 was queued first, then overtaken by HIGH TimeSync sequence 3.
        session.requireFreshInboundSequence(2u, MessagePriority.NORMAL)
        session.requireFreshTelemetryTimestamp(
            frameSenderUs = 1_120uL,
            sampleDeviceUs = 1_100uL,
            receivedHostUs = 10_320uL,
            maximumAgeUs = 1_000uL,
            maximumFutureSkewUs = 100uL,
        )
        session.commitInboundSequence(2u, MessagePriority.NORMAL)
    }

    @Test
    fun priorityQueueAllowsCommandAckSequenceTwoAfterCriticalHeartbeatSequenceThree() {
        val session = BoardProtocolSession { 1uL }
        session.attach()
        session.commitInboundSequence(1u, MessagePriority.HIGH)
        val commandAck = DecodedFrame(
            FrameHeader(MessageType.COMMAND_ACK, MessagePriority.HIGH, 2u, 2uL, 5),
            u32(10u) + byteArrayOf(ApplicationAction.START_TELEMETRY.wireValue.toByte()),
        )
        val heartbeat = DecodedFrame(
            FrameHeader(MessageType.HEARTBEAT_ACK, MessagePriority.CRITICAL, 3u, 3uL, 0),
            byteArrayOf(),
        )

        session.requireFreshInboundSequence(heartbeat.header.sequence, heartbeat.header.priority)
        session.commitInboundSequence(heartbeat.header.sequence, heartbeat.header.priority)
        session.requireFreshInboundSequence(commandAck.header.sequence, commandAck.header.priority)
        session.commitInboundSequence(commandAck.header.sequence, commandAck.header.priority)

        assertThrows(ProtocolException::class.java) {
            session.requireFreshInboundSequence(commandAck.header.sequence, commandAck.header.priority)
        }
    }

    @Test
    fun boundedPriorityReorderHandlesWrapButRejectsSamePriorityAndTooOldFrames() {
        val wrapSession = BoardProtocolSession { 1uL }
        wrapSession.attach()
        wrapSession.commitInboundSequence(UInt.MAX_VALUE - 1u, MessagePriority.HIGH)
        wrapSession.commitInboundSequence(0u, MessagePriority.CRITICAL)
        wrapSession.commitInboundSequence(UInt.MAX_VALUE, MessagePriority.HIGH)
        assertThrows(ProtocolException::class.java) {
            wrapSession.requireFreshInboundSequence(UInt.MAX_VALUE, MessagePriority.HIGH)
        }

        val samePrioritySession = BoardProtocolSession { 1uL }
        samePrioritySession.attach()
        samePrioritySession.commitInboundSequence(1u, MessagePriority.HIGH)
        samePrioritySession.commitInboundSequence(3u, MessagePriority.HIGH)
        assertThrows(ProtocolException::class.java) {
            samePrioritySession.requireFreshInboundSequence(2u, MessagePriority.HIGH)
        }

        val tooOldSession = BoardProtocolSession { 1uL }
        tooOldSession.attach()
        tooOldSession.commitInboundSequence(100u, MessagePriority.HIGH)
        tooOldSession.commitInboundSequence(
            100u + BoardProtocolSession.INBOUND_REORDER_WINDOW.toUInt(),
            MessagePriority.CRITICAL,
        )
        assertThrows(ProtocolException::class.java) {
            tooOldSession.requireFreshInboundSequence(100u, MessagePriority.HIGH)
        }
    }

    private fun deviceFrame(
        type: MessageType,
        sequence: UInt,
        senderUs: ULong,
        payload: ByteArray,
        priority: MessagePriority = MessagePriority.HIGH,
    ): ByteArray {
        val body = ByteArray(WireContract.HEADER_LENGTH + payload.size)
        body[0] = WireContract.VERSION.toByte()
        body[1] = WireContract.HEADER_LENGTH.toByte()
        writeU16(body, 2, type.wireValue)
        writeU16(body, 4, 0)
        body[6] = priority.wireValue.toByte()
        body[7] = 0
        writeU32(body, 8, sequence)
        writeU64(body, 12, senderUs)
        writeU16(body, 20, payload.size)
        payload.copyInto(body, WireContract.HEADER_LENGTH)
        val crc = ByteArray(4).also { writeU32(it, 0, Crc32c.calculate(body).toUInt()) }
        return Cobs.encode(body + crc) + byteArrayOf(0)
    }

    private fun u64(value: ULong): ByteArray =
        ByteArray(8).also { writeU64(it, 0, value) }

    private fun u32(value: UInt): ByteArray =
        ByteArray(4).also { writeU32(it, 0, value) }

    private fun hex(value: String): ByteArray = value.trim()
        .split(Regex("\\s+"))
        .filter(String::isNotEmpty)
        .map { it.toInt(16).toByte() }
        .toByteArray()
}
