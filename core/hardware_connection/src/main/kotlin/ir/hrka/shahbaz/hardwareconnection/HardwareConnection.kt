/** Public UI-free facade for discovery, Protocol v2 connection health, and board sensors. */
package ir.hrka.shahbaz.hardwareconnection

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import ir.hrka.shahbaz.hardwareconnection.internal.AcceptedTimeSyncAction
import ir.hrka.shahbaz.hardwareconnection.internal.InitialTimeSyncAction
import ir.hrka.shahbaz.hardwareconnection.internal.TelemetryStore
import ir.hrka.shahbaz.hardwareconnection.internal.UsbPermissionReconciliation
import ir.hrka.shahbaz.hardwareconnection.internal.ValidatedHandshakeAction
import ir.hrka.shahbaz.hardwareconnection.internal.allowsPostValidationMaintenance
import ir.hrka.shahbaz.hardwareconnection.internal.acceptedTimeSyncAction
import ir.hrka.shahbaz.hardwareconnection.internal.initialTimeSyncAction
import ir.hrka.shahbaz.hardwareconnection.internal.registerReceiversAtomically
import ir.hrka.shahbaz.hardwareconnection.internal.usbPermissionPendingIntentFlags
import ir.hrka.shahbaz.hardwareconnection.internal.usbPermissionReconciliation
import ir.hrka.shahbaz.hardwareconnection.internal.validatedHandshakeAction
import ir.hrka.shahbaz.hardwareconnection.internal.validationError
import ir.hrka.shahbaz.hardwareconnection.internal.protocol.ApplicationAction
import ir.hrka.shahbaz.hardwareconnection.internal.protocol.BoardProtocolSession
import ir.hrka.shahbaz.hardwareconnection.internal.protocol.DecodedFrame
import ir.hrka.shahbaz.hardwareconnection.internal.protocol.FrameHandlingResult
import ir.hrka.shahbaz.hardwareconnection.internal.protocol.InboundSessionStage
import ir.hrka.shahbaz.hardwareconnection.internal.protocol.MessageType
import ir.hrka.shahbaz.hardwareconnection.internal.protocol.ProtocolErrorKind
import ir.hrka.shahbaz.hardwareconnection.internal.protocol.ProtocolException
import ir.hrka.shahbaz.hardwareconnection.internal.protocol.decodeCommandAck
import ir.hrka.shahbaz.hardwareconnection.internal.protocol.decodeCommandNack
import ir.hrka.shahbaz.hardwareconnection.internal.protocol.decodeDeviceInfo
import ir.hrka.shahbaz.hardwareconnection.internal.protocol.decodeDeviceStatus
import ir.hrka.shahbaz.hardwareconnection.internal.protocol.decodeSensorSample
import ir.hrka.shahbaz.hardwareconnection.internal.protocol.handleCrcValidFrameSafely
import ir.hrka.shahbaz.hardwareconnection.internal.protocol.requireAcknowledges
import ir.hrka.shahbaz.hardwareconnection.internal.protocol.requireHeartbeatAckPayload
import ir.hrka.shahbaz.hardwareconnection.internal.protocol.requireAllowedInboundAt
import ir.hrka.shahbaz.hardwareconnection.internal.protocol.requireExpectedInboundPriority
import ir.hrka.shahbaz.hardwareconnection.internal.usb.AndroidUsbCdcTransport
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

private const val USB_PERMISSION_DEVICE_ID_EXTRA =
    "ir.hrka.shahbaz.hardwareconnection.extra.USB_PERMISSION_DEVICE_ID"

/**
 * Owns one sensor-only Android USB-host connection to `shahbaz_interface_board`.
 *
 * The facade deliberately exposes no arming, motor, servo, or actuator commands. [stop] and
 * [close] may transmit the tokenless Protocol v2 Disarm safety override before releasing USB.
 */
class HardwareConnection(
    context: Context,
    private val config: HardwareConnectionConfig = HardwareConnectionConfig(),
) : Closeable, AndroidUsbCdcTransport.Listener {
    private val applicationContext = context.applicationContext
    private val usbManager = applicationContext.getSystemService(Context.USB_SERVICE) as UsbManager
    private val serialDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + serialDispatcher)
    private val closed = AtomicBoolean(false)
    private val session = BoardProtocolSession(::elapsedRealtimeMicros)
    private val telemetryStore = TelemetryStore(
        config.initialQnhHectopascal,
        config.maximumUnknownSensors,
    )
    private val transport = AndroidUsbCdcTransport(applicationContext, this)

    private val mutableConnectionState = MutableStateFlow<BoardConnectionState>(
        BoardConnectionState.Stopped,
    )
    private val mutableTelemetry = MutableStateFlow(telemetryStore.snapshot)

    val connectionState: StateFlow<BoardConnectionState> = mutableConnectionState.asStateFlow()
    val telemetry: StateFlow<BoardTelemetrySnapshot> = mutableTelemetry.asStateFlow()

    private val permissionAction = buildString {
        append(applicationContext.packageName)
        append(".SHAHBAZ_HARDWARE_CONNECTION_USB_PERMISSION.")
        append(UUID.randomUUID())
    }
    private var started = false
    private var receiversRegistered = false
    private var selectedDevice: UsbDevice? = null
    private var selectedDescriptor: BoardUsbDevice? = null
    private var generation = 0L
    private var linkJob: Job? = null
    private var deviceInfo: BoardDeviceInfo? = null
    private var activeToken: ULong? = null
    private var telemetryStartSequence: UInt? = null
    private var telemetryStartAcknowledged = false
    private var heartbeatAcknowledged = false
    private var connectedAtMillis = 0L
    private var stageDeadlineMillis = 0L
    private var lastHeartbeatSentMillis = 0L
    private var lastHeartbeatAckMillis = 0L
    private var timeSyncSentMillis = 0L
    private var timeSyncPending = false
    private var initialTimeSyncAttemptsSent = 0
    private var lastDeviceStatusSentMillis = 0L

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != permissionAction) return
            val requestedDeviceId = if (intent.hasExtra(USB_PERMISSION_DEVICE_ID_EXTRA)) {
                intent.getIntExtra(USB_PERMISSION_DEVICE_ID_EXTRA, 0)
            } else {
                null
            }
            scope.launch {
                handlePermissionResult(requestedDeviceId)
            }
        }
    }

    private val usbLifecycleReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            val announcedDevice = intent.usbDevice()
            scope.launch {
                when (action) {
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                        if (
                            transport.openedDeviceId() == null ||
                            announcedDevice?.isExactShahbazIdentity() == true
                        ) {
                            scanAndConnect()
                        }
                    }
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        val openedId = transport.openedDeviceId()
                        if (
                            announcedDevice != null &&
                            openedId == announcedDevice.deviceId &&
                            !transport.isAttached(announcedDevice.deviceId)
                        ) {
                            handlePhysicalDetach()
                        } else if (openedId == null) {
                            scanAndConnect()
                        }
                    }
                }
            }
        }
    }

    /** Registers USB lifecycle observation and discovers an already attached production board. */
    fun start() {
        if (closed.get()) return
        scope.launch { startInternal() }
    }

    /** Reconciles current attachment and permission state without reopening a healthy link. */
    fun refresh() {
        if (closed.get()) return
        scope.launch {
            if (!started) startInternal() else scanAndConnect()
        }
    }

    /** Safely closes the board link and unregisters dynamic USB receivers. */
    fun stop() {
        if (closed.get()) return
        scope.launch { stopInternal(BoardDisconnectReason.APP_STOPPED, publishStopped = true) }
    }

    /** Discards a failed/partial link and performs exact VID/PID discovery again. */
    fun retry() {
        if (closed.get()) return
        scope.launch {
            if (!started) startInternal() else {
                closeLink(sendSafetyShutdown = true)
                scanAndConnect()
            }
        }
    }

    /** Requests Android's per-attachment USB permission for the one discovered board. */
    fun requestPermission() {
        if (closed.get()) return
        scope.launch { requestPermissionInternal() }
    }

    /** Updates app-owned QNH and immediately recalculates the last pressure altitude. */
    fun setQnh(qnhHectopascal: Double) {
        require(qnhHectopascal.isFinite() && qnhHectopascal in 800.0..1100.0) {
            "QNH must be a finite value in 800..1100 hPa"
        }
        if (closed.get()) return
        scope.launch {
            telemetryStore.setQnh(qnhHectopascal)
            publishTelemetry()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            // Preserve StopTelemetry + Disarm, but never block Android's main thread on USB I/O.
            scope.launch {
                try {
                    stopInternal(BoardDisconnectReason.APP_STOPPED, publishStopped = true)
                } finally {
                    scope.cancel()
                }
            }
            return
        }
        runBlocking {
            withContext(serialDispatcher) {
                stopInternal(BoardDisconnectReason.APP_STOPPED, publishStopped = true)
            }
        }
        scope.cancel()
    }

    override fun onBytes(generation: Long, bytes: ByteArray) {
        if (closed.get()) return
        scope.launch {
            if (
                closed.get() ||
                !started ||
                generation != this@HardwareConnection.generation
            ) return@launch
            try {
                handleProtocolBytes(bytes)
            } catch (error: CancellationException) {
                throw error
            } catch (error: RuntimeException) {
                fail(
                    BoardLinkErrorCode.INTERNAL_ERROR,
                    "Protocol processing failed safely: ${error.message ?: error.javaClass.simpleName}",
                    recoverable = true,
                )
            }
        }
    }

    override fun onTransportError(generation: Long, message: String, cause: Throwable?) {
        if (closed.get()) return
        scope.launch {
            if (
                closed.get() ||
                !started ||
                generation != this@HardwareConnection.generation
            ) return@launch
            fail(
                BoardLinkErrorCode.USB_READ_FAILED,
                cause?.let { "$message: ${it.message}" } ?: message,
                recoverable = true,
            )
        }
    }

    private suspend fun startInternal() {
        if (started) return
        started = true
        if (!applicationContext.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)) {
            mutableConnectionState.value = BoardConnectionState.Failed(
                BoardLinkError(
                    BoardLinkErrorCode.USB_HOST_UNAVAILABLE,
                    "This Android device does not expose USB host mode",
                    recoverable = false,
                ),
            )
            return
        }
        try {
            registerReceivers()
        } catch (error: RuntimeException) {
            started = false
            mutableConnectionState.value = BoardConnectionState.Failed(
                BoardLinkError(
                    BoardLinkErrorCode.RECEIVER_REGISTRATION_FAILED,
                    "USB receiver registration failed: ${error.message ?: error.javaClass.simpleName}",
                    recoverable = true,
                ),
            )
            return
        }
        scanAndConnect()
    }

    private fun stopInternal(reason: BoardDisconnectReason, publishStopped: Boolean) {
        if (!started && !receiversRegistered) {
            if (publishStopped) mutableConnectionState.value = BoardConnectionState.Stopped
            return
        }
        started = false
        closeLink(sendSafetyShutdown = true)
        unregisterReceivers()
        selectedDevice = null
        selectedDescriptor = null
        mutableConnectionState.value = if (publishStopped) {
            BoardConnectionState.Stopped
        } else {
            BoardConnectionState.Disconnected(reason)
        }
    }

    private fun scanAndConnect() {
        if (!started) return
        val matches = transport.matchingDevices()
        when {
            matches.isEmpty() -> {
                if (transport.openedDeviceId() != null || session.attached) {
                    closeLink(sendSafetyShutdown = false)
                }
                selectedDevice = null
                selectedDescriptor = null
                mutableConnectionState.value = BoardConnectionState.Searching
            }
            matches.size > 1 -> {
                closeLink(sendSafetyShutdown = true)
                selectedDevice = null
                selectedDescriptor = null
                mutableConnectionState.value = BoardConnectionState.Failed(
                    BoardLinkError(
                        BoardLinkErrorCode.MULTIPLE_MATCHING_BOARDS,
                        "More than one Shahbaz native USB device is attached",
                        recoverable = true,
                    ),
                )
            }
            else -> {
                val device = matches.single()
                val descriptor = device.toPublicDescriptor()
                selectedDevice = device
                selectedDescriptor = descriptor
                if (
                    transport.openedDeviceId() == device.deviceId &&
                    session.attached
                ) {
                    return
                }
                if (!transport.hasPermission(device)) {
                    mutableConnectionState.value = BoardConnectionState.PermissionRequired(descriptor)
                } else {
                    open(device, descriptor)
                }
            }
        }
    }

    private fun requestPermissionInternal() {
        if (!started) return
        val device = selectedDevice
        val descriptor = selectedDescriptor
        if (device == null || descriptor == null || !transport.isAttached(device.deviceId)) {
            scanAndConnect()
            return
        }
        if (transport.hasPermission(device)) {
            open(device, descriptor)
            return
        }
        mutableConnectionState.value = BoardConnectionState.RequestingPermission(descriptor)
        val resultIntent = Intent(permissionAction)
            .setPackage(applicationContext.packageName)
            .putExtra(USB_PERMISSION_DEVICE_ID_EXTRA, device.deviceId)
        val pendingIntent = PendingIntent.getBroadcast(
            applicationContext,
            device.deviceId,
            resultIntent,
            usbPermissionPendingIntentFlags(),
        )
        try {
            usbManager.requestPermission(device, pendingIntent)
        } catch (error: RuntimeException) {
            mutableConnectionState.value = BoardConnectionState.Failed(
                BoardLinkError(
                    BoardLinkErrorCode.PERMISSION_DENIED,
                    "USB permission request failed: ${error.message}",
                    recoverable = true,
                ),
            )
        }
    }

    private fun handlePermissionResult(requestedDeviceId: Int?) {
        if (!started) return
        val device = selectedDevice
        val descriptor = selectedDescriptor
        when (
            usbPermissionReconciliation(
                selectedDeviceId = device?.deviceId,
                requestedDeviceId = requestedDeviceId,
                selectedDeviceIsAttached = device?.let { transport.isAttached(it.deviceId) } == true,
                selectedDeviceHasPermission = device?.let(transport::hasPermission) == true,
            )
        ) {
            UsbPermissionReconciliation.RESCAN -> scanAndConnect()
            UsbPermissionReconciliation.OPEN -> {
                if (device == null || descriptor == null) {
                    scanAndConnect()
                } else {
                    open(device, descriptor)
                }
            }
            UsbPermissionReconciliation.DENIED -> {
                mutableConnectionState.value = BoardConnectionState.Failed(
                    BoardLinkError(
                        BoardLinkErrorCode.PERMISSION_DENIED,
                        "USB permission was denied",
                        recoverable = true,
                    ),
                )
            }
        }
    }

    private fun open(device: UsbDevice, descriptor: BoardUsbDevice) {
        if (transport.openedDeviceId() == device.deviceId && session.attached) return
        closeLink(sendSafetyShutdown = true)
        generation += 1
        val linkGeneration = generation
        mutableConnectionState.value = BoardConnectionState.Opening(descriptor)
        when (val result = transport.open(device, linkGeneration)) {
            AndroidUsbCdcTransport.OpenResult.Opened -> {
                selectedDevice = device
                selectedDescriptor = descriptor
                session.attach()
                connectedAtMillis = SystemClock.elapsedRealtime()
                resetHandshakeState()
                mutableConnectionState.value = BoardConnectionState.Synchronizing(descriptor)
                stageDeadlineMillis = connectedAtMillis + config.handshakeTimeoutMillis
                if (!sendInitialTimeSync()) return
                startLinkMaintenance(linkGeneration)
            }
            AndroidUsbCdcTransport.OpenResult.PermissionMissing ->
                mutableConnectionState.value = BoardConnectionState.PermissionRequired(descriptor)
            AndroidUsbCdcTransport.OpenResult.OpenFailed -> failWithoutOpen(
                BoardLinkErrorCode.DEVICE_OPEN_FAILED,
                "UsbManager.openDevice returned null",
            )
            is AndroidUsbCdcTransport.OpenResult.Incompatible -> failWithoutOpen(
                BoardLinkErrorCode.INCOMPATIBLE_USB_INTERFACE,
                result.message,
            )
            is AndroidUsbCdcTransport.OpenResult.Failed -> failWithoutOpen(
                BoardLinkErrorCode.DEVICE_OPEN_FAILED,
                "USB initialization failed: ${result.cause.message}",
            )
        }
    }

    private fun handleProtocolBytes(bytes: ByteArray) {
        val processingGeneration = generation
        val events = session.feed(bytes)
        for (event in events) {
            if (generation != processingGeneration || !session.attached) break
            when (event) {
                is BoardProtocolSession.Event.Rejected -> recordRejectedFrame(event.exception)
                is BoardProtocolSession.Event.FrameReceived -> {
                    val receivedAtUs = elapsedRealtimeMicros()
                    when (
                        val result = handleCrcValidFrameSafely {
                            event.frame.requireExpectedInboundPriority()
                            session.requireFreshInboundSequence(
                                event.frame.header.sequence,
                                event.frame.header.priority,
                            )
                            if (event.frame.header.messageType == MessageType.TIME_SYNC_RESPONSE) {
                                handleTimeSyncAccepted(
                                    session.acceptTimeSync(event.frame, receivedAtUs),
                                )
                            } else {
                                handleFrame(event.frame, receivedAtUs)
                            }
                            session.commitInboundSequence(
                                event.frame.header.sequence,
                                event.frame.header.priority,
                            )
                        }
                    ) {
                        FrameHandlingResult.Accepted -> {
                            telemetryStore.onFrameAccepted()
                            publishTelemetry()
                        }
                        is FrameHandlingResult.Rejected -> {
                            recordRejectedFrame(result.exception)
                            if (event.frame.header.messageType == MessageType.DEVICE_INFO_RESPONSE) {
                                fail(
                                    BoardLinkErrorCode.DEVICE_INFO_INVALID,
                                    result.exception.message ?: "Invalid DeviceInfo payload",
                                    recoverable = false,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun handleTimeSyncAccepted(token: ULong) {
        timeSyncPending = false
        val priorToken = activeToken
        if (priorToken != null && priorToken != token) {
            fail(
                BoardLinkErrorCode.SESSION_REJECTED,
                "The session token changed without a physical USB reconnect",
                recoverable = true,
            )
            return
        }
        activeToken = token
        if (
            acceptedTimeSyncAction(priorToken != null) ==
            AcceptedTimeSyncAction.REFRESH_MAPPING_ONLY
        ) return
        val descriptor = selectedDescriptor ?: return
        mutableConnectionState.value = BoardConnectionState.ValidatingDevice(descriptor)
        stageDeadlineMillis = SystemClock.elapsedRealtime() + config.handshakeTimeoutMillis
        if (!send(session.buildDeviceInfo())) return
    }

    private fun handleFrame(frame: DecodedFrame, receivedAtUs: ULong) {
        frame.header.messageType.requireAllowedInboundAt(currentInboundSessionStage())
        // Every session-bound board response must come from the device-time window established
        // by this attachment's TimeSync. TimeSyncResponse itself is validated separately.
        requireFreshSessionFrame(frame, receivedAtUs)
        when (frame.header.messageType) {
            MessageType.DEVICE_INFO_RESPONSE -> handleDeviceInfo(frame)
            MessageType.HEARTBEAT_ACK -> {
                frame.requireHeartbeatAckPayload()
                requireHeartbeatResponseIsExpected()
                val firstAcknowledgement = !heartbeatAcknowledged
                heartbeatAcknowledged = true
                lastHeartbeatAckMillis = (receivedAtUs / 1_000uL).toLong()
                if (firstAcknowledgement) {
                    if (!send(session.buildDeviceStatus())) return
                    lastDeviceStatusSentMillis = SystemClock.elapsedRealtime()
                }
                advanceValidatedHandshake()
            }
            MessageType.COMMAND_ACK -> {
                if (telemetryStartAcknowledged) {
                    throw ProtocolException(
                        ProtocolErrorKind.POLICY_REJECTED,
                        "StartTelemetry was already acknowledged for the current session",
                    )
                }
                val ack = frame.decodeCommandAck() ?: throw ProtocolException(
                    ProtocolErrorKind.PAYLOAD_INVALID,
                    "CommandAck decoder rejected its message type",
                )
                requireStartTelemetryResponseIsExpected()
                val expectedSequence = telemetryStartSequence ?: throw ProtocolException(
                    ProtocolErrorKind.POLICY_REJECTED,
                    "Unsolicited CommandAck before StartTelemetry",
                )
                ack.requireAcknowledges(expectedSequence, ApplicationAction.START_TELEMETRY)
                telemetryStartAcknowledged = true
                telemetryStore.awaitingTelemetry(SystemClock.elapsedRealtime())
                publishTelemetry()
                advanceValidatedHandshake()
            }
            MessageType.COMMAND_NACK -> {
                val nack = frame.decodeCommandNack() ?: throw ProtocolException(
                    ProtocolErrorKind.PAYLOAD_INVALID,
                    "CommandNack decoder rejected its message type",
                )
                when (nack.reason) {
                    0x000A -> fail(
                        BoardLinkErrorCode.SESSION_REJECTED,
                        "Board rejected request ${nack.requestSequence} from this USB session",
                        recoverable = true,
                    )
                    0x0008 -> {
                        if (!sendTimeSync()) return
                    }
                    else -> telemetryStore.onFrameRejected(
                        "Board NACK reason=0x${nack.reason.toString(16)} validation=${nack.validationError}",
                        crcOrFraming = false,
                    )
                }
            }
            MessageType.SENSOR_SAMPLE -> {
                if (mutableConnectionState.value !is BoardConnectionState.Ready) {
                    throw ProtocolException(
                        ProtocolErrorKind.POLICY_REJECTED,
                        "SensorSample arrived before the current session became Ready",
                    )
                }
                val sample = frame.decodeSensorSample() ?: throw ProtocolException(
                    ProtocolErrorKind.PAYLOAD_INVALID,
                    "SensorSample decoder rejected its message type",
                )
                session.requireFreshTelemetryTimestamp(
                    frameSenderUs = frame.header.senderMonotonicUs,
                    sampleDeviceUs = sample.deviceTimestampUs,
                    receivedHostUs = receivedAtUs,
                    maximumAgeUs = config.sensorStaleAfterMillis.toULong() * 1_000uL,
                    maximumFutureSkewUs =
                        config.sensorTimestampFutureToleranceMillis.toULong() * 1_000uL,
                )
                val sensorError = telemetryStore.accept(
                    sample,
                    (receivedAtUs / 1_000uL).toLong(),
                )
                if (sensorError != null) {
                    throw ProtocolException(
                        ProtocolErrorKind.PAYLOAD_INVALID,
                        "SensorSample rejected: ${sensorError.message}",
                    )
                }
            }
            MessageType.DEVICE_STATUS_RESPONSE -> {
                val status = frame.decodeDeviceStatus() ?: throw ProtocolException(
                    ProtocolErrorKind.PAYLOAD_INVALID,
                    "DeviceStatus decoder rejected its message type",
                )
                telemetryStore.acceptStatus(status, SystemClock.elapsedRealtime())
                if (status.actuatorArmed) {
                    fail(
                        BoardLinkErrorCode.DEVICE_INFO_INVALID,
                        "Board unexpectedly reports an armed actuator state",
                        recoverable = false,
                    )
                }
            }
            else -> throw ProtocolException(
                ProtocolErrorKind.POLICY_REJECTED,
                "Unexpected inbound message ${frame.header.messageType}",
            )
        }
    }

    private fun currentInboundSessionStage(): InboundSessionStage =
        when (mutableConnectionState.value) {
            is BoardConnectionState.ValidatingDevice -> InboundSessionStage.VALIDATING_DEVICE
            is BoardConnectionState.AwaitingHeartbeat -> InboundSessionStage.AWAITING_HEARTBEAT
            is BoardConnectionState.StartingTelemetry -> InboundSessionStage.STARTING_TELEMETRY
            is BoardConnectionState.Ready -> InboundSessionStage.READY
            else -> InboundSessionStage.NOT_SYNCHRONIZED
        }

    private fun handleDeviceInfo(frame: DecodedFrame) {
        val info = frame.decodeDeviceInfo() ?: throw ProtocolException(
            ProtocolErrorKind.PAYLOAD_INVALID,
            "DeviceInfo decoder rejected its message type",
        )
        val validationError = info.validationError()
        if (validationError != null) {
            throw ProtocolException(ProtocolErrorKind.PAYLOAD_INVALID, validationError)
        }
        deviceInfo = info
        val descriptor = selectedDescriptor ?: return
        mutableConnectionState.value = BoardConnectionState.AwaitingHeartbeat(descriptor, info)
        stageDeadlineMillis = SystemClock.elapsedRealtime() + config.handshakeTimeoutMillis
        if (!sendHeartbeat()) return
    }

    private fun advanceValidatedHandshake() {
        val info = deviceInfo ?: return
        val descriptor = selectedDescriptor ?: return
        when (
            validatedHandshakeAction(
                heartbeatAcknowledged = heartbeatAcknowledged,
                telemetryStartRequested = telemetryStartSequence != null,
                telemetryStartAcknowledged = telemetryStartAcknowledged,
            )
        ) {
            ValidatedHandshakeAction.WAIT_FOR_HEARTBEAT,
            ValidatedHandshakeAction.WAIT_FOR_TELEMETRY_ACK -> Unit
            ValidatedHandshakeAction.START_TELEMETRY -> {
                mutableConnectionState.value = BoardConnectionState.StartingTelemetry(
                    descriptor,
                    info,
                )
                stageDeadlineMillis = SystemClock.elapsedRealtime() + config.handshakeTimeoutMillis
                val start = session.buildStartTelemetry()
                telemetryStartSequence = start.sequence
                if (!send(start)) return
            }
            ValidatedHandshakeAction.READY -> {
                if (activeToken == null) return
                mutableConnectionState.value = BoardConnectionState.Ready(
                    descriptor,
                    info,
                    connectedAtMillis,
                )
            }
        }
    }

    private fun requireHeartbeatResponseIsExpected() {
        if (
            activeToken == null ||
            deviceInfo == null ||
            lastHeartbeatSentMillis == 0L
        ) {
            throw ProtocolException(
                ProtocolErrorKind.POLICY_REJECTED,
                "Unsolicited HeartbeatAck before the current handshake requested it",
            )
        }
    }

    private fun requireStartTelemetryResponseIsExpected() {
        if (
            activeToken == null ||
            deviceInfo == null ||
            !heartbeatAcknowledged ||
            telemetryStartSequence == null
        ) {
            throw ProtocolException(
                ProtocolErrorKind.POLICY_REJECTED,
                "Unsolicited CommandAck before heartbeat recovery and StartTelemetry",
            )
        }
    }

    private fun requireFreshSessionFrame(frame: DecodedFrame, receivedAtUs: ULong) {
        session.requireFreshDeviceFrameTimestamp(
            frameSenderUs = frame.header.senderMonotonicUs,
            receivedHostUs = receivedAtUs,
            maximumAgeUs = config.sensorStaleAfterMillis.toULong() * 1_000uL,
            maximumFutureSkewUs =
                config.sensorTimestampFutureToleranceMillis.toULong() * 1_000uL,
        )
    }

    private fun recordRejectedFrame(error: ProtocolException) {
        telemetryStore.onFrameRejected(
            error.message ?: "Protocol v2 frame rejected",
            crcOrFraming = error.kind in setOf(
                ProtocolErrorKind.CRC_MISMATCH,
                ProtocolErrorKind.MALFORMED_COBS,
                ProtocolErrorKind.LENGTH_MISMATCH,
                ProtocolErrorKind.OVERSIZE,
            ),
        )
        publishTelemetry()
    }

    private fun startLinkMaintenance(linkGeneration: Long) {
        linkJob?.cancel()
        linkJob = scope.launch {
            while (started && generation == linkGeneration && session.attached) {
                delay(100)
                maintenanceTick(linkGeneration)
            }
        }
    }

    private fun maintenanceTick(linkGeneration: Long) {
        if (linkGeneration != generation || !session.attached) return
        val now = SystemClock.elapsedRealtime()
        if (transport.openedDeviceId()?.let { !transport.isAttached(it) } == true) {
            handlePhysicalDetach()
            return
        }
        val connectionState = mutableConnectionState.value
        when (connectionState) {
            is BoardConnectionState.Synchronizing -> when (
                initialTimeSyncAction(
                    elapsedSinceLastAttemptMillis = now - timeSyncSentMillis,
                    attemptsSent = initialTimeSyncAttemptsSent,
                    retryIntervalMillis = config.initialTimeSyncRetryIntervalMillis,
                    maximumAttempts = config.initialTimeSyncMaximumAttempts,
                )
            ) {
                InitialTimeSyncAction.WAIT -> Unit
                InitialTimeSyncAction.RETRY -> if (!sendInitialTimeSync()) return
                InitialTimeSyncAction.FAIL -> {
                    fail(
                        BoardLinkErrorCode.TIME_SYNC_TIMEOUT,
                        "Initial TimeSync failed after $initialTimeSyncAttemptsSent attempts",
                        true,
                    )
                    return
                }
            }
            is BoardConnectionState.ValidatingDevice -> if (now > stageDeadlineMillis) {
                fail(BoardLinkErrorCode.DEVICE_INFO_TIMEOUT, "DeviceInfo response timed out", true)
                return
            }
            is BoardConnectionState.AwaitingHeartbeat -> if (now > stageDeadlineMillis) {
                fail(BoardLinkErrorCode.HEARTBEAT_TIMEOUT, "Heartbeat acknowledgement timed out", true)
                return
            }
            is BoardConnectionState.StartingTelemetry -> if (now > stageDeadlineMillis) {
                fail(
                    BoardLinkErrorCode.TELEMETRY_START_TIMEOUT,
                    "StartTelemetry acknowledgement timed out",
                    true,
                )
                return
            }
            else -> Unit
        }
        if (
            connectionState !is BoardConnectionState.Synchronizing &&
            timeSyncPending &&
            now - timeSyncSentMillis > config.handshakeTimeoutMillis
        ) {
            fail(BoardLinkErrorCode.TIME_SYNC_TIMEOUT, "Periodic TimeSync response timed out", true)
            return
        }
        if (activeToken != null) {
            if (!timeSyncPending && session.timeSyncRefreshDue() && !sendTimeSync()) return
            if (allowsPostValidationMaintenance(mutableConnectionState.value)) {
                if (
                    now - lastHeartbeatSentMillis >= config.heartbeatIntervalMillis &&
                    !sendHeartbeat()
                ) return
                if (
                    heartbeatAcknowledged &&
                    now - lastHeartbeatAckMillis > config.heartbeatTimeoutMillis
                ) {
                    fail(BoardLinkErrorCode.HEARTBEAT_TIMEOUT, "Board heartbeat became unhealthy", true)
                    return
                }
                if (
                    heartbeatAcknowledged &&
                    now - lastDeviceStatusSentMillis >= 1_000L
                ) {
                    lastDeviceStatusSentMillis = now
                    if (!send(session.buildDeviceStatus())) return
                }
            }
        }
        val before = telemetryStore.snapshot
        telemetryStore.updateSensorHealth(
            nowMillis = now,
            staleAfterMillis = config.sensorStaleAfterMillis,
            firstSampleTimeoutMillis = config.firstSensorSampleTimeoutMillis,
        )
        if (before != telemetryStore.snapshot) publishTelemetry()
    }

    private fun sendTimeSync(): Boolean {
        val sent = send(session.buildTimeSync())
        if (sent) {
            timeSyncPending = true
            timeSyncSentMillis = SystemClock.elapsedRealtime()
        }
        return sent
    }

    private fun sendInitialTimeSync(): Boolean {
        initialTimeSyncAttemptsSent += 1
        return sendTimeSync()
    }

    private fun sendHeartbeat(): Boolean {
        val sent = send(session.buildHeartbeat())
        if (sent) lastHeartbeatSentMillis = SystemClock.elapsedRealtime()
        return sent
    }

    private fun send(command: BoardProtocolSession.EncodedCommand): Boolean {
        if (transport.write(generation, command.bytes)) return true
        fail(
            BoardLinkErrorCode.USB_WRITE_FAILED,
            "Could not write ${command.type} to the board",
            recoverable = true,
        )
        return false
    }

    private fun handlePhysicalDetach() {
        closeLink(sendSafetyShutdown = false)
        selectedDevice = null
        selectedDescriptor = null
        mutableConnectionState.value = BoardConnectionState.Disconnected(
            BoardDisconnectReason.USB_DETACHED,
        )
    }

    private fun fail(code: BoardLinkErrorCode, message: String, recoverable: Boolean) {
        closeLink(sendSafetyShutdown = true)
        mutableConnectionState.value = BoardConnectionState.Failed(
            BoardLinkError(code, message, recoverable),
        )
    }

    private fun failWithoutOpen(code: BoardLinkErrorCode, message: String) {
        closeLink(sendSafetyShutdown = false)
        mutableConnectionState.value = BoardConnectionState.Failed(
            BoardLinkError(code, message, recoverable = true),
        )
    }

    private fun closeLink(sendSafetyShutdown: Boolean) {
        linkJob?.cancel()
        linkJob = null
        if (sendSafetyShutdown && session.attached) {
            if (session.sessionToken != null) {
                runCatching { transport.write(generation, session.buildStopTelemetry().bytes) }
            }
            runCatching { transport.write(generation, session.buildDisarm().bytes) }
        }
        transport.close()
        session.detach()
        generation += 1
        resetHandshakeState()
        telemetryStore.disconnected()
        publishTelemetry()
    }

    private fun resetHandshakeState() {
        deviceInfo = null
        activeToken = null
        telemetryStartSequence = null
        telemetryStartAcknowledged = false
        heartbeatAcknowledged = false
        stageDeadlineMillis = 0L
        lastHeartbeatSentMillis = 0L
        lastHeartbeatAckMillis = 0L
        timeSyncSentMillis = 0L
        timeSyncPending = false
        initialTimeSyncAttemptsSent = 0
        lastDeviceStatusSentMillis = 0L
    }

    private fun publishTelemetry() {
        mutableTelemetry.value = telemetryStore.snapshot
    }

    private fun registerReceivers() {
        if (receiversRegistered) return
        val usbFilter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        registerReceiversAtomically(
            registerPermissionReceiver = {
                registerReceiver(permissionReceiver, IntentFilter(permissionAction), exported = false)
            },
            registerUsbLifecycleReceiver = {
                registerReceiver(usbLifecycleReceiver, usbFilter, exported = true)
            },
            unregisterPermissionReceiver = {
                applicationContext.unregisterReceiver(permissionReceiver)
            },
            unregisterUsbLifecycleReceiver = {
                applicationContext.unregisterReceiver(usbLifecycleReceiver)
            },
        )
        receiversRegistered = true
    }

    /**
     * Registers directly through the platform so this portable module does not require AndroidX.
     * Android 13+ requires an explicit export policy; API 31-32 use the legacy overload.
     */
    private fun registerReceiver(
        receiver: BroadcastReceiver,
        filter: IntentFilter,
        exported: Boolean,
    ) {
        if (Build.VERSION.SDK_INT >= 33) {
            val flags = if (exported) Context.RECEIVER_EXPORTED else Context.RECEIVER_NOT_EXPORTED
            applicationContext.registerReceiver(receiver, filter, flags)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            applicationContext.registerReceiver(receiver, filter)
        }
    }

    private fun unregisterReceivers() {
        if (!receiversRegistered) return
        runCatching { applicationContext.unregisterReceiver(permissionReceiver) }
        runCatching { applicationContext.unregisterReceiver(usbLifecycleReceiver) }
        receiversRegistered = false
    }

    private fun UsbDevice.toPublicDescriptor() = BoardUsbDevice(
        deviceId = deviceId,
        deviceName = deviceName,
        vendorId = vendorId,
        productId = productId,
    )

    private fun UsbDevice.isExactShahbazIdentity(): Boolean =
        hasExactShahbazBoardUsbIdentity(vendorId, productId)

    private fun Intent.usbDevice(): UsbDevice? = if (Build.VERSION.SDK_INT >= 33) {
        getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(UsbManager.EXTRA_DEVICE) as? UsbDevice
    }

    private fun elapsedRealtimeMicros(): ULong =
        SystemClock.elapsedRealtimeNanos().toULong() / 1_000uL
}
