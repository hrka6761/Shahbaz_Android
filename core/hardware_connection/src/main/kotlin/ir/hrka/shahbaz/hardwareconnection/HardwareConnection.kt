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
import android.util.Log
import com.shahbaz.flightblackbox.FbbEventRef
import com.shahbaz.flightblackbox.FbbEventType
import com.shahbaz.flightblackbox.FbbPersistence
import com.shahbaz.flightblackbox.FlightBlackBox
import ir.hrka.shahbaz.hardwareconnection.internal.AcceptedTimeSyncAction
import ir.hrka.shahbaz.hardwareconnection.internal.ActuatorAcknowledgementTracker
import ir.hrka.shahbaz.hardwareconnection.internal.validateQuadXMotorFrame
import ir.hrka.shahbaz.hardwareconnection.internal.BoundedActuatorSubmissionGate
import ir.hrka.shahbaz.hardwareconnection.internal.InitialTimeSyncAction
import ir.hrka.shahbaz.hardwareconnection.internal.ReenumerationGraceAction
import ir.hrka.shahbaz.hardwareconnection.internal.TelemetryStore
import ir.hrka.shahbaz.hardwareconnection.internal.UsbPermissionReconciliation
import ir.hrka.shahbaz.hardwareconnection.internal.ValidatedHandshakeAction
import ir.hrka.shahbaz.hardwareconnection.internal.allowsPostValidationMaintenance
import ir.hrka.shahbaz.hardwareconnection.internal.acceptedTimeSyncAction
import ir.hrka.shahbaz.hardwareconnection.internal.initialTimeSyncAction
import ir.hrka.shahbaz.hardwareconnection.internal.registerReceiversAtomically
import ir.hrka.shahbaz.hardwareconnection.internal.reenumerationGraceAction
import ir.hrka.shahbaz.hardwareconnection.internal.soleDeviceReplacesOpenedLink
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

/**
 * Exposes the USB_PERMISSION_DEVICE_ID_EXTRA value.
 */
private const val USB_PERMISSION_DEVICE_ID_EXTRA =
    "ir.hrka.shahbaz.hardwareconnection.extra.USB_PERMISSION_DEVICE_ID"
/**
 * Exposes the USB_LOG_TAG value.
 */
private const val USB_LOG_TAG = "ShahbazUsb"
/**
 * Exposes the USB_REENUMERATION_GRACE_MILLIS value.
 */
private const val USB_REENUMERATION_GRACE_MILLIS = 2_000L

/**
 * Owns one Android USB-host connection to `shahbaz_interface_board`.
 *
 * The facade defaults to telemetry-only operation. Arming and actuator output methods reject until
 * [HardwareConnectionConfig.allowActuatorCommands] is explicitly enabled and the board reports
 * an actuator-capable runtime profile. [stop] and [close] still transmit the tokenless Protocol v2
 * Disarm safety override before releasing USB.
 */
class HardwareConnection(
    context: Context,
    /**
     * Exposes the config value.
     */
    private val config: HardwareConnectionConfig = HardwareConnectionConfig(),
) : Closeable, AndroidUsbCdcTransport.Listener {
    /**
     * Exposes the applicationContext value.
     */
    private val applicationContext = context.applicationContext
    /**
     * Exposes the usbManager value.
     */
    private val usbManager = applicationContext.getSystemService(Context.USB_SERVICE) as UsbManager
    /**
     * Exposes the serialDispatcher value.
     */
    private val serialDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)
    /**
     * Exposes the scope value.
     */
    private val scope = CoroutineScope(SupervisorJob() + serialDispatcher)
    /**
     * Exposes the closed value.
     */
    private val closed = AtomicBoolean(false)
    /**
     * Exposes the session value.
     */
    private val session = BoardProtocolSession(::elapsedRealtimeMicros)
    /**
     * Exposes the telemetryStore value.
     */
    private val telemetryStore = TelemetryStore(
        config.initialQnhHectopascal,
        config.maximumUnknownSensors,
    )
    /**
     * Exposes the transport value.
     */
    private val transport = AndroidUsbCdcTransport(applicationContext, this)

    /**
     * Exposes the mutableConnectionState value.
     */
    private val mutableConnectionState = MutableStateFlow<BoardConnectionState>(
        BoardConnectionState.Stopped,
    )
    /**
     * Exposes the mutableTelemetry value.
     */
    private val mutableTelemetry = MutableStateFlow(telemetryStore.snapshot)

    /**
     * Exposes the connectionState value.
     */
    val connectionState: StateFlow<BoardConnectionState> = mutableConnectionState.asStateFlow()
    /**
     * Exposes the telemetry value.
     */
    val telemetry: StateFlow<BoardTelemetrySnapshot> = mutableTelemetry.asStateFlow()

    /**
     * Exposes the permissionAction value.
     */
    private val permissionAction = buildString {
        append(applicationContext.packageName)
        append(".SHAHBAZ_HARDWARE_CONNECTION_USB_PERMISSION.")
        append(UUID.randomUUID())
    }
    /**
     * Stores the mutable started value.
     */
    private var started = false
    /**
     * Stores the mutable receiversRegistered value.
     */
    private var receiversRegistered = false
    /**
     * Stores the mutable selectedDevice value.
     */
    private var selectedDevice: UsbDevice? = null
    /**
     * Stores the mutable selectedDescriptor value.
     */
    private var selectedDescriptor: BoardUsbDevice? = null
    /**
     * Stores the mutable generation value.
     */
    private var generation = 0L
    /**
     * Stores the mutable linkJob value.
     */
    private var linkJob: Job? = null
    /**
     * Stores the mutable reenumerationGraceJob value.
     */
    private var reenumerationGraceJob: Job? = null
    /**
     * Stores the mutable deviceInfo value.
     */
    private var deviceInfo: BoardDeviceInfo? = null
    /**
     * Stores the mutable activeToken value.
     */
    private var activeToken: ULong? = null
    /**
     * Stores the mutable telemetryStartSequence value.
     */
    private var telemetryStartSequence: UInt? = null
    /**
     * Stores the mutable telemetryStartAcknowledged value.
     */
    private var telemetryStartAcknowledged = false
    /**
     * Stores the mutable heartbeatAcknowledged value.
     */
    private var heartbeatAcknowledged = false
    /**
     * Stores the mutable connectedAtMillis value.
     */
    private var connectedAtMillis = 0L
    /**
     * Stores the mutable stageDeadlineMillis value.
     */
    private var stageDeadlineMillis = 0L
    /**
     * Stores the mutable lastHeartbeatSentMillis value.
     */
    private var lastHeartbeatSentMillis = 0L
    /**
     * Stores the mutable lastHeartbeatAckMillis value.
     */
    private var lastHeartbeatAckMillis = 0L
    /**
     * Stores the mutable timeSyncSentMillis value.
     */
    private var timeSyncSentMillis = 0L
    /**
     * Stores the mutable timeSyncPending value.
     */
    private var timeSyncPending = false
    /**
     * Stores the mutable initialTimeSyncAttemptsSent value.
     */
    private var initialTimeSyncAttemptsSent = 0
    /**
     * Stores the mutable lastDeviceStatusSentMillis value.
     */
    private var lastDeviceStatusSentMillis = 0L
    /**
     * Stores the mutable lastUsbEvent value.
     */
    private var lastUsbEvent: FbbEventRef? = null
    /**
     * Stores the mutable lastCommandSentType value.
     */
    private var lastCommandSentType: MessageType? = null
    /**
     * Stores the mutable lastCommandSentSequence value.
     */
    private var lastCommandSentSequence: UInt? = null
    /** Tracks sent actuator requests until the board explicitly accepts or rejects each one. */
    private val actuatorAcknowledgements = ActuatorAcknowledgementTracker(
        config.maximumPendingActuatorAcknowledgements,
    )
    /** Prevents the public control loop from accumulating an unbounded dispatcher backlog. */
    private val actuatorSubmissionGate = BoundedActuatorSubmissionGate(
        config.maximumQueuedActuatorSubmissions,
    )
    /** Disarm and E-stop each retain an independent reserved dispatcher slot. */
    private val disarmSubmissionGate = BoundedActuatorSubmissionGate(1)
    private val emergencyStopSubmissionGate = BoundedActuatorSubmissionGate(1)

    /**
     * Exposes the permissionReceiver value.
     */
    private val permissionReceiver = object : BroadcastReceiver() {
        /**
         * Runs the onReceive operation.
         */
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != permissionAction) return
            val requestedDeviceId = if (intent.hasExtra(USB_PERMISSION_DEVICE_ID_EXTRA)) {
                intent.getIntExtra(USB_PERMISSION_DEVICE_ID_EXTRA, 0)
            } else {
                null
            }
            val frameworkDeviceIdForLog = runCatching { intent.usbDevice()?.deviceId }
                .fold(
                    onSuccess = { it?.toString() ?: "null" },
                    onFailure = { "unavailable(${it.javaClass.simpleName})" },
                )
            val frameworkGrantedForLog = runCatching {
                intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            }.fold(
                onSuccess = { it.toString() },
                onFailure = { "unavailable(${it.javaClass.simpleName})" },
            )
            val frameworkGrantedExtraPresentForLog = runCatching {
                intent.hasExtra(UsbManager.EXTRA_PERMISSION_GRANTED)
            }.fold(
                onSuccess = { it.toString() },
                onFailure = { "unavailable(${it.javaClass.simpleName})" },
            )
            lastUsbEvent = FlightBlackBox.record(
                type = FbbEventType.SYSTEM,
                description = "USB permission broadcast received",
                cause = lastUsbEvent,
                metadata = mapOf(
                    "requestedDeviceId" to requestedDeviceId,
                    "frameworkDeviceId" to frameworkDeviceIdForLog,
                    "frameworkGrantedExtraPresent" to frameworkGrantedExtraPresentForLog,
                    "frameworkGranted" to frameworkGrantedForLog,
                ),
                persistence = FbbPersistence.IMPORTANT,
            )
            Log.i(
                USB_LOG_TAG,
                "permission callback appDeviceId=$requestedDeviceId " +
                    "frameworkDeviceId=$frameworkDeviceIdForLog " +
                    "frameworkGrantedExtraPresent=$frameworkGrantedExtraPresentForLog " +
                    "frameworkGranted=$frameworkGrantedForLog",
            )
            scope.launch {
                handlePermissionResult(requestedDeviceId)
            }
        }
    }

    /**
     * Exposes the usbLifecycleReceiver value.
     */
    private val usbLifecycleReceiver = object : BroadcastReceiver() {
        /**
         * Runs the onReceive operation.
         */
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            val announcedDevice = intent.usbDevice()
            scope.launch {
                when (action) {
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                        lastUsbEvent = FlightBlackBox.record(
                            type = FbbEventType.SYSTEM,
                            description = "USB device attached broadcast",
                            cause = lastUsbEvent,
                            metadata = mapOf(
                                "deviceId" to announcedDevice?.deviceId,
                                "matchingIds" to matchingDeviceIdsForLog(),
                            ),
                            persistence = FbbPersistence.IMPORTANT,
                        )
                        Log.i(
                            USB_LOG_TAG,
                            "ATTACHED broadcast deviceId=${announcedDevice?.deviceId} " +
                                "matchingIds=${matchingDeviceIdsForLog()}",
                        )
                        if (
                            transport.openedDeviceId() == null ||
                            announcedDevice?.isExactShahbazIdentity() == true
                        ) {
                            scanAndConnect()
                        }
                    }
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        val openedId = transport.openedDeviceId()
                        val announcedStillAttachedForLog = announcedDevice?.let {
                            attachmentStateForLog(it.deviceId)
                        } ?: "null"
                        lastUsbEvent = FlightBlackBox.record(
                            type = FbbEventType.SYSTEM,
                            description = "USB device detached broadcast",
                            cause = lastUsbEvent,
                            metadata = mapOf(
                                "deviceId" to announcedDevice?.deviceId,
                                "openedDeviceId" to openedId,
                                "announcedStillAttached" to announcedStillAttachedForLog,
                            ) + usbDiagnosticContextMetadata(),
                            persistence = FbbPersistence.IMPORTANT,
                        )
                        Log.w(
                            USB_LOG_TAG,
                            "DETACHED broadcast deviceId=${announcedDevice?.deviceId} " +
                                "openedDeviceId=$openedId " +
                                "announcedStillAttached=$announcedStillAttachedForLog " +
                                "matchingIds=${matchingDeviceIdsForLog()}",
                        )
                        if (
                            announcedDevice != null &&
                            openedId == announcedDevice.deviceId
                        ) {
                            handlePhysicalDetach("DETACHED broadcast")
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
        lastUsbEvent = FlightBlackBox.record(
            type = FbbEventType.CALL,
            description = "HardwareConnection.start()",
            cause = lastUsbEvent,
            persistence = FbbPersistence.IMPORTANT,
        )
        scope.launch { startInternal() }
    }

    /** Reconciles current attachment and permission state without reopening a healthy link. */
    fun refresh() {
        if (closed.get()) return
        lastUsbEvent = FlightBlackBox.record(
            type = FbbEventType.CALL,
            description = "HardwareConnection.refresh()",
            cause = lastUsbEvent,
        )
        scope.launch {
            if (!started) startInternal() else scanAndConnect()
        }
    }

    /** Safely closes the board link and unregisters dynamic USB receivers. */
    fun stop() {
        if (closed.get()) return
        lastUsbEvent = FlightBlackBox.record(
            type = FbbEventType.CALL,
            description = "HardwareConnection.stop()",
            cause = lastUsbEvent,
            persistence = FbbPersistence.IMPORTANT,
        )
        scope.launch { stopInternal(BoardDisconnectReason.APP_STOPPED, publishStopped = true) }
    }

    /** Discards a failed/partial link and performs exact VID/PID discovery again. */
    fun retry() {
        if (closed.get()) return
        lastUsbEvent = FlightBlackBox.record(
            type = FbbEventType.USER,
            description = "HardwareConnection.retry()",
            cause = lastUsbEvent,
            persistence = FbbPersistence.IMPORTANT,
        )
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
        lastUsbEvent = FlightBlackBox.record(
            type = FbbEventType.CALL,
            description = "HardwareConnection.requestPermission()",
            cause = lastUsbEvent,
            persistence = FbbPersistence.IMPORTANT,
        )
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

    /** Queues an opt-in arming request for the board actuator supervisor. */
    fun armActuators(): BoardActuatorCommandResult =
        submitActuatorRequest(
            commandName = "ArmRequest",
            commandCount = 1,
            expectedAction = ApplicationAction.ARM_APPLIED,
            validateReady = { validateReadyForActuatorOutput() },
        ) {
            listOf(session.buildArmRequest())
        }

    /**
     * Queues one motor PWM frame while preserving its original monotonic generation timestamp.
     *
     * Preserving [generatedAtElapsedRealtimeNanos] prevents a delayed host queue from serializing
     * old flight-controller output with a new timestamp that the board would incorrectly accept as
     * fresh. The frame must contain Quad-X channels 0 through 3 exactly once; it is transmitted as
     * one Protocol v2 request and consumes one acknowledgement slot.
     */
    fun sendMotorPulses(
        pulses: List<BoardMotorPulse>,
        generatedAtElapsedRealtimeNanos: Long = SystemClock.elapsedRealtimeNanos(),
    ): BoardActuatorCommandResult {
        // Snapshot before validation: callers may supply a mutable list, while encoding happens on
        // the asynchronous USB dispatcher. BoardMotorPulse itself is immutable.
        val frame = pulses.toList()
        val batchValidation = validateMotorBatch(frame)
        if (batchValidation != null) return batchValidation
        val timestampValidation = validateActuatorGenerationTime(generatedAtElapsedRealtimeNanos)
        if (timestampValidation != null) return timestampValidation
        val generatedAtHostMicros = (generatedAtElapsedRealtimeNanos / 1_000L).toULong()
        return submitActuatorRequest(
            commandName = "MotorFrameCommand",
            commandCount = 1,
            expectedAction = ApplicationAction.MOTOR_FRAME_COMMAND,
            validateReady = { validateReadyForActuatorOutput() },
        ) {
            listOf(session.buildMotorFrameCommand(frame, generatedAtHostMicros))
        }
    }

    /** Queues servo PWM pulses. Channels are zero-based board servo channels. */
    fun sendServoPulses(pulses: List<BoardServoPulse>): BoardActuatorCommandResult {
        val batchValidation = validateServoBatch(pulses)
        if (batchValidation != null) return batchValidation
        return submitActuatorRequest(
            commandName = "ServoCommand",
            commandCount = pulses.size,
            expectedAction = ApplicationAction.SERVO_COMMAND,
            validateReady = { validateReadyForActuatorOutput() },
        ) {
            pulses.map { pulse -> session.buildServoCommand(pulse.channel, pulse.pulseMicros) }
        }
    }

    /** Queues the tokenless Disarm safety override when a session is attached. */
    fun disarmActuators(): BoardActuatorCommandResult =
        submitSafetyOverride(
            commandName = "Disarm",
            expectedAction = ApplicationAction.DISARM_APPLIED,
        ) {
            session.buildDisarm()
        }

    /** Queues the tokenless EmergencyStop safety override when a session is attached. */
    fun emergencyStopActuators(): BoardActuatorCommandResult =
        submitSafetyOverride(
            commandName = "EmergencyStop",
            expectedAction = ApplicationAction.EMERGENCY_STOP_APPLIED,
        ) {
            session.buildEmergencyStop()
        }

    /**
     * Runs the close operation.
     */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        FlightBlackBox.record(
            type = FbbEventType.LIFECYCLE,
            description = "HardwareConnection.close()",
            cause = lastUsbEvent,
            persistence = FbbPersistence.IMPORTANT,
        )
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

    /**
     * Runs the onBytes operation.
     */
    override fun onBytes(generation: Long, bytes: ByteArray) {
        if (closed.get()) return
        val rx = FlightBlackBox.record(
            type = FbbEventType.USB_RX,
            description = "USB CDC bytes received",
            cause = lastUsbEvent,
            metadata = mapOf("generation" to generation, "size" to bytes.size),
        )
        scope.launch {
            if (
                closed.get() ||
                !started ||
                generation != this@HardwareConnection.generation
            ) return@launch
            try {
                lastUsbEvent = rx
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

    /**
     * Runs the onTransportError operation.
     */
    override fun onTransportError(generation: Long, message: String, cause: Throwable?) {
        if (closed.get()) return
        lastUsbEvent = FlightBlackBox.record(
            type = FbbEventType.ERROR,
            description = "USB transport error",
            cause = lastUsbEvent,
            metadata = mapOf(
                "generation" to generation,
                "message" to message,
                "cause" to cause?.javaClass?.simpleName,
            ),
            persistence = FbbPersistence.CRITICAL,
        )
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

    /**
     * Runs the startInternal operation.
     */
    private suspend fun startInternal() {
        if (started) {
            FlightBlackBox.record(
                type = FbbEventType.DECISION,
                description = "started=true -> skip duplicate HardwareConnection.startInternal()",
                cause = lastUsbEvent,
            )
            return
        }
        started = true
        FlightBlackBox.record(
            type = FbbEventType.STATE,
            description = "HardwareConnection.started: false -> true",
            cause = lastUsbEvent,
            persistence = FbbPersistence.IMPORTANT,
        )
        if (!applicationContext.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)) {
            lastUsbEvent = FlightBlackBox.record(
                type = FbbEventType.ERROR,
                description = "USB host feature unavailable",
                cause = lastUsbEvent,
                persistence = FbbPersistence.CRITICAL,
            )
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
            FlightBlackBox.record(
                type = FbbEventType.SYSTEM,
                description = "USB receivers registered",
                cause = lastUsbEvent,
            )
        } catch (error: RuntimeException) {
            started = false
            lastUsbEvent = FlightBlackBox.recordThrowable(
                type = FbbEventType.EXCEPTION,
                description = "USB receiver registration failed",
                error = error,
                cause = lastUsbEvent,
                persistence = FbbPersistence.CRITICAL,
            )
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

    /**
     * Runs the stopInternal operation.
     */
    private fun stopInternal(reason: BoardDisconnectReason, publishStopped: Boolean) {
        if (!started && !receiversRegistered) {
            if (publishStopped) mutableConnectionState.value = BoardConnectionState.Stopped
            FlightBlackBox.record(
                type = FbbEventType.DECISION,
                description = "started=false && receiversRegistered=false -> stop no-op",
                cause = lastUsbEvent,
                metadata = mapOf("reason" to reason, "publishStopped" to publishStopped),
            )
            return
        }
        started = false
        FlightBlackBox.record(
            type = FbbEventType.STATE,
            description = "HardwareConnection.started: true -> false",
            cause = lastUsbEvent,
            metadata = mapOf("reason" to reason, "publishStopped" to publishStopped),
            persistence = FbbPersistence.IMPORTANT,
        )
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

    /**
     * Runs the scanAndConnect operation.
     */
    private fun scanAndConnect() {
        if (!started) return
        val scan = FlightBlackBox.record(
            type = FbbEventType.CALL,
            description = "HardwareConnection.scanAndConnect()",
            cause = lastUsbEvent,
        )
        val matches = transport.matchingDevices()
        val discovery = FlightBlackBox.record(
            type = FbbEventType.VALUE,
            description = "USB discovery found ${matches.size} matching Shahbaz board(s)",
            cause = scan,
            metadata = mapOf("matchingDeviceIds" to matches.map { it.deviceId }),
            persistence = FbbPersistence.IMPORTANT,
        )
        if (matches.isNotEmpty()) cancelReenumerationGrace()
        when {
            matches.isEmpty() -> {
                lastUsbEvent = FlightBlackBox.record(
                    type = FbbEventType.DECISION,
                    description = "matchingDeviceCount=0 -> SEARCHING",
                    cause = discovery,
                    persistence = FbbPersistence.IMPORTANT,
                )
                if (transport.openedDeviceId() != null || session.attached) {
                    closeLink(sendSafetyShutdown = false)
                }
                selectedDevice = null
                selectedDescriptor = null
                mutableConnectionState.value = BoardConnectionState.Searching
            }
            matches.size > 1 -> {
                lastUsbEvent = FlightBlackBox.record(
                    type = FbbEventType.ERROR,
                    description = "multiple matching Shahbaz USB boards attached",
                    cause = discovery,
                    metadata = mapOf("matchingDeviceIds" to matches.map { it.deviceId }),
                    persistence = FbbPersistence.CRITICAL,
                )
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
                val openedDeviceId = transport.openedDeviceId()
                if (soleDeviceReplacesOpenedLink(openedDeviceId, device.deviceId)) {
                    Log.w(
                        USB_LOG_TAG,
                        "re-enumerated device replaces open link " +
                            "openedDeviceId=$openedDeviceId replacementDeviceId=${device.deviceId}",
                    )
                    closeLink(sendSafetyShutdown = false)
                }
                selectedDevice = device
                selectedDescriptor = descriptor
                if (
                    transport.openedDeviceId() == device.deviceId &&
                    session.attached
                ) {
                    FlightBlackBox.record(
                        type = FbbEventType.DECISION,
                        description = "matching board already open and attached -> keep link",
                        cause = discovery,
                        metadata = descriptor.fbbMetadata(),
                    )
                    return
                }
                if (!transport.hasPermission(device)) {
                    lastUsbEvent = FlightBlackBox.record(
                        type = FbbEventType.DECISION,
                        description = "USB permission missing -> PermissionRequired",
                        cause = discovery,
                        metadata = descriptor.fbbMetadata(),
                        persistence = FbbPersistence.IMPORTANT,
                    )
                    mutableConnectionState.value = BoardConnectionState.PermissionRequired(descriptor)
                } else {
                    lastUsbEvent = FlightBlackBox.record(
                        type = FbbEventType.DECISION,
                        description = "USB permission granted -> open device",
                        cause = discovery,
                        metadata = descriptor.fbbMetadata(),
                        persistence = FbbPersistence.IMPORTANT,
                    )
                    open(device, descriptor)
                }
            }
        }
    }

    /**
     * Runs the requestPermissionInternal operation.
     */
    private fun requestPermissionInternal() {
        if (!started) return
        val request = FlightBlackBox.record(
            type = FbbEventType.CALL,
            description = "HardwareConnection.requestPermissionInternal()",
            cause = lastUsbEvent,
            persistence = FbbPersistence.IMPORTANT,
        )
        val device = selectedDevice
        val descriptor = selectedDescriptor
        if (device == null || descriptor == null || !transport.isAttached(device.deviceId)) {
            lastUsbEvent = FlightBlackBox.record(
                type = FbbEventType.DECISION,
                description = "selected USB device missing or detached -> rescan",
                cause = request,
                persistence = FbbPersistence.IMPORTANT,
            )
            scanAndConnect()
            return
        }
        if (transport.hasPermission(device)) {
            lastUsbEvent = FlightBlackBox.record(
                type = FbbEventType.DECISION,
                description = "USB permission already granted -> open device",
                cause = request,
                metadata = descriptor.fbbMetadata(),
                persistence = FbbPersistence.IMPORTANT,
            )
            open(device, descriptor)
            return
        }
        mutableConnectionState.value = BoardConnectionState.RequestingPermission(descriptor)
        lastUsbEvent = FlightBlackBox.record(
            type = FbbEventType.SYSTEM,
            description = "Android USB permission request launched",
            cause = request,
            metadata = descriptor.fbbMetadata(),
            persistence = FbbPersistence.IMPORTANT,
        )
        Log.i(
            USB_LOG_TAG,
            "permission request deviceId=${device.deviceId} " +
                "attached=${attachmentStateForLog(device.deviceId)} " +
                "hasPermission=${permissionStateForLog(device)}",
        )
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
            Log.e(USB_LOG_TAG, "permission request failed deviceId=${device.deviceId}", error)
            lastUsbEvent = FlightBlackBox.recordThrowable(
                type = FbbEventType.EXCEPTION,
                description = "USB permission request failed",
                error = error,
                cause = lastUsbEvent,
                metadata = descriptor.fbbMetadata(),
                persistence = FbbPersistence.CRITICAL,
            )
            mutableConnectionState.value = BoardConnectionState.Failed(
                BoardLinkError(
                    BoardLinkErrorCode.PERMISSION_DENIED,
                    "USB permission request failed: ${error.message}",
                    recoverable = true,
                ),
            )
        }
    }

    /**
     * Runs the handlePermissionResult operation.
     */
    private fun handlePermissionResult(requestedDeviceId: Int?) {
        if (!started) return
        val resultEvent = FlightBlackBox.record(
            type = FbbEventType.SYSTEM,
            description = "USB permission result reconciliation started",
            cause = lastUsbEvent,
            metadata = mapOf("requestedDeviceId" to requestedDeviceId),
            persistence = FbbPersistence.IMPORTANT,
        )
        val device = selectedDevice
        val descriptor = selectedDescriptor
        val selectedIsAttached = device?.let { transport.isAttached(it.deviceId) } == true
        val selectedHasPermission = device?.let(transport::hasPermission) == true
        Log.i(
            USB_LOG_TAG,
            "permission reconcile appDeviceId=$requestedDeviceId " +
                "selectedDeviceId=${device?.deviceId} " +
                "attached=$selectedIsAttached hasPermission=$selectedHasPermission " +
                "matchingIds=${matchingDeviceIdsForLog()}",
        )
        val action = usbPermissionReconciliation(
            selectedDeviceId = device?.deviceId,
            requestedDeviceId = requestedDeviceId,
            selectedDeviceIsAttached = selectedIsAttached,
            selectedDeviceHasPermission = selectedHasPermission,
        )
        lastUsbEvent = FlightBlackBox.record(
            type = FbbEventType.DECISION,
            description = "USB permission reconciliation -> $action",
            cause = resultEvent,
            metadata = mapOf(
                "selectedDeviceId" to device?.deviceId,
                "attached" to selectedIsAttached,
                "hasPermission" to selectedHasPermission,
            ),
            persistence = FbbPersistence.IMPORTANT,
        )
        when (action) {
            UsbPermissionReconciliation.RESCAN -> scanAndConnect()
            UsbPermissionReconciliation.OPEN -> {
                if (device == null || descriptor == null) {
                    scanAndConnect()
                } else {
                    open(device, descriptor)
                }
            }
            UsbPermissionReconciliation.DENIED -> {
                FlightBlackBox.record(
                    type = FbbEventType.ERROR,
                    description = "USB permission denied",
                    cause = lastUsbEvent,
                    persistence = FbbPersistence.CRITICAL,
                )
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

    /**
     * Runs the open operation.
     */
    private fun open(device: UsbDevice, descriptor: BoardUsbDevice) {
        if (transport.openedDeviceId() == device.deviceId && session.attached) {
            FlightBlackBox.record(
                type = FbbEventType.DECISION,
                description = "open requested for already attached device -> no-op",
                cause = lastUsbEvent,
                metadata = descriptor.fbbMetadata(),
            )
            return
        }
        val open = FlightBlackBox.record(
            type = FbbEventType.CALL,
            description = "HardwareConnection.open()",
            cause = lastUsbEvent,
            metadata = descriptor.fbbMetadata(),
            persistence = FbbPersistence.IMPORTANT,
        )
        Log.i(
            USB_LOG_TAG,
            "open requested deviceId=${device.deviceId} generation=$generation " +
                "attached=${attachmentStateForLog(device.deviceId)} " +
                "hasPermission=${permissionStateForLog(device)}",
        )
        closeLink(sendSafetyShutdown = true)
        generation += 1
        val linkGeneration = generation
        mutableConnectionState.value = BoardConnectionState.Opening(descriptor)
        when (val result = transport.open(device, linkGeneration)) {
            AndroidUsbCdcTransport.OpenResult.Opened -> {
                lastUsbEvent = FlightBlackBox.record(
                    type = FbbEventType.STATE,
                    description = "USB CDC open succeeded -> Synchronizing",
                    cause = open,
                    metadata = descriptor.fbbMetadata() + mapOf("generation" to linkGeneration),
                    persistence = FbbPersistence.IMPORTANT,
                )
                Log.i(USB_LOG_TAG, "open succeeded deviceId=${device.deviceId} generation=$linkGeneration")
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
            AndroidUsbCdcTransport.OpenResult.PermissionMissing -> {
                lastUsbEvent = FlightBlackBox.record(
                    type = FbbEventType.WARNING,
                    description = "USB open lost permission -> PermissionRequired",
                    cause = open,
                    metadata = descriptor.fbbMetadata(),
                    persistence = FbbPersistence.IMPORTANT,
                )
                Log.w(USB_LOG_TAG, "open lost permission deviceId=${device.deviceId}")
                mutableConnectionState.value = BoardConnectionState.PermissionRequired(descriptor)
            }
            AndroidUsbCdcTransport.OpenResult.OpenFailed -> {
                FlightBlackBox.record(
                    type = FbbEventType.ERROR,
                    description = "UsbManager.openDevice returned null",
                    cause = open,
                    metadata = descriptor.fbbMetadata(),
                    persistence = FbbPersistence.CRITICAL,
                )
                failWithoutOpen(BoardLinkErrorCode.DEVICE_OPEN_FAILED, "UsbManager.openDevice returned null")
            }
            is AndroidUsbCdcTransport.OpenResult.Incompatible -> {
                FlightBlackBox.record(
                    type = FbbEventType.ERROR,
                    description = "USB CDC interface incompatible",
                    cause = open,
                    metadata = descriptor.fbbMetadata() + mapOf("message" to result.message),
                    persistence = FbbPersistence.CRITICAL,
                )
                failWithoutOpen(BoardLinkErrorCode.INCOMPATIBLE_USB_INTERFACE, result.message)
            }
            is AndroidUsbCdcTransport.OpenResult.Failed -> {
                FlightBlackBox.recordThrowable(
                    type = FbbEventType.EXCEPTION,
                    description = "USB initialization failed",
                    error = result.cause,
                    cause = open,
                    metadata = descriptor.fbbMetadata(),
                    persistence = FbbPersistence.CRITICAL,
                )
                failWithoutOpen(BoardLinkErrorCode.DEVICE_OPEN_FAILED, "USB initialization failed: ${result.cause.message}")
            }
        }
    }

    /**
     * Runs the handleProtocolBytes operation.
     */
    private fun handleProtocolBytes(bytes: ByteArray) {
        val processingGeneration = generation
        val events = session.feed(bytes)
        for (event in events) {
            if (generation != processingGeneration || !session.attached) break
            when (event) {
                is BoardProtocolSession.Event.Rejected -> {
                    FlightBlackBox.record(
                        type = FbbEventType.WARNING,
                        description = "Protocol frame rejected before decode",
                        cause = lastUsbEvent,
                        metadata = mapOf("kind" to event.exception.kind),
                        persistence = FbbPersistence.IMPORTANT,
                    )
                    recordRejectedFrame(event.exception)
                }
                is BoardProtocolSession.Event.FrameReceived -> {
                    val receivedAtUs = elapsedRealtimeMicros()
                    val frameEvent = FlightBlackBox.record(
                        type = FbbEventType.USB_RX,
                        description = "Protocol frame received",
                        cause = lastUsbEvent,
                        metadata = event.frame.fbbMetadata(receivedAtUs),
                    )
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
                            lastUsbEvent = FlightBlackBox.record(
                                type = FbbEventType.RETURN,
                                description = "Protocol frame accepted",
                                cause = frameEvent,
                                metadata = mapOf("messageType" to event.frame.header.messageType),
                            )
                            telemetryStore.onFrameAccepted()
                            publishTelemetry()
                        }
                        is FrameHandlingResult.Rejected -> {
                            lastUsbEvent = FlightBlackBox.record(
                                type = FbbEventType.WARNING,
                                description = "Protocol frame rejected",
                                cause = frameEvent,
                                metadata = mapOf(
                                    "messageType" to event.frame.header.messageType,
                                    "kind" to result.exception.kind,
                                    "message" to result.exception.message,
                                ),
                                persistence = FbbPersistence.IMPORTANT,
                            )
                            recordRejectedFrame(result.exception)
                            when (event.frame.header.messageType) {
                                MessageType.DEVICE_INFO_RESPONSE -> fail(
                                    BoardLinkErrorCode.DEVICE_INFO_INVALID,
                                    result.exception.message ?: "Invalid DeviceInfo payload",
                                    recoverable = false,
                                )
                                MessageType.COMMAND_ACK -> fail(
                                    BoardLinkErrorCode.PROTOCOL_ERROR,
                                    result.exception.message ?: "Invalid command acknowledgement",
                                    recoverable = true,
                                )
                                MessageType.COMMAND_NACK -> fail(
                                    BoardLinkErrorCode.ACTUATOR_COMMAND_REJECTED,
                                    result.exception.message ?: "Board rejected an actuator command",
                                    recoverable = true,
                                )
                                else -> Unit
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Runs the handleTimeSyncAccepted operation.
     */
    private fun handleTimeSyncAccepted(token: ULong) {
        val accepted = FlightBlackBox.record(
            type = FbbEventType.VALUE,
            description = "TimeSync accepted",
            cause = lastUsbEvent,
            metadata = mapOf("sessionMappingChanged" to (activeToken != null && activeToken != token)),
            persistence = FbbPersistence.IMPORTANT,
        )
        timeSyncPending = false
        val priorToken = activeToken
        if (priorToken != null && priorToken != token) {
            FlightBlackBox.record(
                type = FbbEventType.ERROR,
                description = "session token changed without physical USB reconnect",
                cause = accepted,
                persistence = FbbPersistence.CRITICAL,
            )
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
        FlightBlackBox.record(
            type = FbbEventType.STATE,
            description = "boardConnection -> ValidatingDevice",
            cause = accepted,
            metadata = descriptor.fbbMetadata(),
            persistence = FbbPersistence.IMPORTANT,
        )
        stageDeadlineMillis = SystemClock.elapsedRealtime() + config.handshakeTimeoutMillis
        if (!send(session.buildDeviceInfo())) return
    }

    /**
     * Runs the handleFrame operation.
     */
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
                    FlightBlackBox.record(
                        type = FbbEventType.VALUE,
                        description = "first HeartbeatAck received",
                        cause = lastUsbEvent,
                        persistence = FbbPersistence.IMPORTANT,
                    )
                    if (!send(session.buildDeviceStatus())) return
                    lastDeviceStatusSentMillis = SystemClock.elapsedRealtime()
                }
                advanceValidatedHandshake()
            }
            MessageType.COMMAND_ACK -> {
                val ack = frame.decodeCommandAck() ?: throw ProtocolException(
                    ProtocolErrorKind.PAYLOAD_INVALID,
                    "CommandAck decoder rejected its message type",
                )
                if (!telemetryStartAcknowledged) {
                    requireStartTelemetryResponseIsExpected()
                    val expectedSequence = telemetryStartSequence ?: throw ProtocolException(
                        ProtocolErrorKind.POLICY_REJECTED,
                        "Unsolicited CommandAck before StartTelemetry",
                    )
                    ack.requireAcknowledges(expectedSequence, ApplicationAction.START_TELEMETRY)
                    telemetryStartAcknowledged = true
                    FlightBlackBox.record(
                        type = FbbEventType.VALUE,
                        description = "StartTelemetry acknowledged",
                        cause = lastUsbEvent,
                        metadata = mapOf("requestSequence" to expectedSequence),
                        persistence = FbbPersistence.IMPORTANT,
                    )
                    telemetryStore.awaitingTelemetry(SystemClock.elapsedRealtime())
                    publishTelemetry()
                    advanceValidatedHandshake()
                } else {
                    val pending = actuatorAcknowledgements.remove(ack.requestSequence)
                        ?: throw ProtocolException(
                            ProtocolErrorKind.POLICY_REJECTED,
                            "Unexpected CommandAck for request ${ack.requestSequence}",
                        )
                    ack.requireAcknowledges(ack.requestSequence, pending.expectedAction)
                    FlightBlackBox.record(
                        type = FbbEventType.VALUE,
                        description = "Post-ready command acknowledged",
                        cause = lastUsbEvent,
                        metadata = mapOf(
                            "requestSequence" to ack.requestSequence,
                            "applicationAction" to ack.applicationAction,
                        ),
                        persistence = FbbPersistence.IMPORTANT,
                    )
                }
            }
            MessageType.COMMAND_NACK -> {
                val nack = frame.decodeCommandNack() ?: throw ProtocolException(
                    ProtocolErrorKind.PAYLOAD_INVALID,
                    "CommandNack decoder rejected its message type",
                )
                val pending = actuatorAcknowledgements.remove(nack.requestSequence)
                if (pending != null) {
                    FlightBlackBox.record(
                        type = FbbEventType.WARNING,
                        description = "Post-ready actuator command rejected by board",
                        cause = lastUsbEvent,
                        metadata = mapOf(
                            "requestSequence" to nack.requestSequence,
                            "expectedAction" to pending.expectedAction,
                            "reason" to "0x${nack.reason.toString(16)}",
                            "validationError" to nack.validationError,
                        ),
                        persistence = FbbPersistence.CRITICAL,
                    )
                    throw ProtocolException(
                        ProtocolErrorKind.POLICY_REJECTED,
                        "Board rejected actuator request ${nack.requestSequence} " +
                            "reason=0x${nack.reason.toString(16)} " +
                            "validation=${nack.validationError}",
                    )
                }
                if (
                    mutableConnectionState.value is BoardConnectionState.Ready &&
                    nack.reason != 0x0008
                ) {
                    throw ProtocolException(
                        ProtocolErrorKind.POLICY_REJECTED,
                        "Unexpected CommandNack for request ${nack.requestSequence}",
                    )
                }
                when (nack.reason) {
                    0x000A -> {
                        FlightBlackBox.record(
                            type = FbbEventType.ERROR,
                            description = "Board rejected session-bound request",
                            cause = lastUsbEvent,
                            metadata = mapOf(
                                "requestSequence" to nack.requestSequence,
                                "reason" to "0x${nack.reason.toString(16)}",
                            ),
                            persistence = FbbPersistence.CRITICAL,
                        )
                        throw ProtocolException(
                            ProtocolErrorKind.POLICY_REJECTED,
                            "Board rejected request ${nack.requestSequence} from this USB session",
                        )
                    }
                    0x0008 -> {
                        FlightBlackBox.record(
                            type = FbbEventType.DECISION,
                            description = "Board requested TimeSync refresh -> send TimeSync",
                            cause = lastUsbEvent,
                            metadata = mapOf("requestSequence" to nack.requestSequence),
                        )
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
                val observedHostUs = session.requireFreshTelemetryTimestamp(
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
                    (observedHostUs / 1_000uL).toLong(),
                )
                if (sensorError != null) {
                    FlightBlackBox.record(
                        type = FbbEventType.WARNING,
                        description = "SensorSample rejected by telemetry store",
                        cause = lastUsbEvent,
                        metadata = mapOf(
                            "sensorId" to sample.sensorId,
                            "instanceId" to sample.instanceId,
                            "message" to sensorError.message,
                        ),
                        persistence = FbbPersistence.IMPORTANT,
                    )
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
                if (status.actuatorArmed && !config.allowActuatorCommands) {
                    FlightBlackBox.record(
                        type = FbbEventType.ERROR,
                        description = "Board reported armed actuator state",
                        cause = lastUsbEvent,
                        persistence = FbbPersistence.CRITICAL,
                    )
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

    /**
     * Runs the currentInboundSessionStage operation.
     */
    private fun currentInboundSessionStage(): InboundSessionStage =
        when (mutableConnectionState.value) {
            is BoardConnectionState.ValidatingDevice -> InboundSessionStage.VALIDATING_DEVICE
            is BoardConnectionState.AwaitingHeartbeat -> InboundSessionStage.AWAITING_HEARTBEAT
            is BoardConnectionState.StartingTelemetry -> InboundSessionStage.STARTING_TELEMETRY
            is BoardConnectionState.Ready -> InboundSessionStage.READY
            else -> InboundSessionStage.NOT_SYNCHRONIZED
        }

    /**
     * Runs the handleDeviceInfo operation.
     */
    private fun handleDeviceInfo(frame: DecodedFrame) {
        val info = frame.decodeDeviceInfo() ?: throw ProtocolException(
            ProtocolErrorKind.PAYLOAD_INVALID,
            "DeviceInfo decoder rejected its message type",
        )
        val validationError = info.validationError(
            allowActuatorProfile = config.allowActuatorCommands,
        )
        if (validationError != null) {
            FlightBlackBox.record(
                type = FbbEventType.ERROR,
                description = "DeviceInfo validation failed",
                cause = lastUsbEvent,
                metadata = mapOf("validationError" to validationError),
                persistence = FbbPersistence.CRITICAL,
            )
            throw ProtocolException(ProtocolErrorKind.PAYLOAD_INVALID, validationError)
        }
        deviceInfo = info
        val descriptor = selectedDescriptor ?: return
        mutableConnectionState.value = BoardConnectionState.AwaitingHeartbeat(descriptor, info)
        FlightBlackBox.record(
            type = FbbEventType.VALUE,
            description = "DeviceInfo accepted",
            cause = lastUsbEvent,
            metadata = descriptor.fbbMetadata() + mapOf(
                "protocol" to info.protocolVersion,
                "target" to info.target,
                "flashBytes" to info.detectedFlashBytes,
                "psramBytes" to info.detectedPsramBytes,
            ),
            persistence = FbbPersistence.IMPORTANT,
        )
        stageDeadlineMillis = SystemClock.elapsedRealtime() + config.handshakeTimeoutMillis
        if (!sendHeartbeat()) return
    }

    /**
     * Runs the advanceValidatedHandshake operation.
     */
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
            ValidatedHandshakeAction.WAIT_FOR_TELEMETRY_ACK -> FlightBlackBox.record(
                type = FbbEventType.DECISION,
                description = "validatedHandshakeAction -> wait",
                cause = lastUsbEvent,
            )
            ValidatedHandshakeAction.START_TELEMETRY -> {
                val startTelemetry = FlightBlackBox.record(
                    type = FbbEventType.DECISION,
                    description = "validatedHandshakeAction -> START_TELEMETRY",
                    cause = lastUsbEvent,
                    persistence = FbbPersistence.IMPORTANT,
                )
                mutableConnectionState.value = BoardConnectionState.StartingTelemetry(
                    descriptor,
                    info,
                )
                stageDeadlineMillis = SystemClock.elapsedRealtime() + config.handshakeTimeoutMillis
                val start = session.buildStartTelemetry()
                telemetryStartSequence = start.sequence
                lastUsbEvent = startTelemetry
                if (!send(start)) return
            }
            ValidatedHandshakeAction.READY -> {
                if (activeToken == null) return
                mutableConnectionState.value = BoardConnectionState.Ready(
                    descriptor,
                    info,
                    connectedAtMillis,
                )
                lastUsbEvent = FlightBlackBox.record(
                    type = FbbEventType.STATE,
                    description = "boardConnection -> Ready",
                    cause = lastUsbEvent,
                    metadata = descriptor.fbbMetadata() + mapOf(
                        "connectedAtMs" to connectedAtMillis,
                    ),
                    persistence = FbbPersistence.IMPORTANT,
                )
            }
        }
    }

    /**
     * Runs the requireHeartbeatResponseIsExpected operation.
     */
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

    /**
     * Runs the requireStartTelemetryResponseIsExpected operation.
     */
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

    /**
     * Runs the requireFreshSessionFrame operation.
     */
    private fun requireFreshSessionFrame(frame: DecodedFrame, receivedAtUs: ULong) {
        session.requireFreshDeviceFrameTimestamp(
            frameSenderUs = frame.header.senderMonotonicUs,
            receivedHostUs = receivedAtUs,
            maximumAgeUs = config.sensorStaleAfterMillis.toULong() * 1_000uL,
            maximumFutureSkewUs =
                config.sensorTimestampFutureToleranceMillis.toULong() * 1_000uL,
        )
    }

    /**
     * Runs the recordRejectedFrame operation.
     */
    private fun recordRejectedFrame(error: ProtocolException) {
        FlightBlackBox.record(
            type = FbbEventType.WARNING,
            description = "Protocol v2 frame rejected",
            cause = lastUsbEvent,
            metadata = mapOf("kind" to error.kind, "message" to error.message),
            persistence = FbbPersistence.IMPORTANT,
        )
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

    /**
     * Runs the startLinkMaintenance operation.
     */
    private fun startLinkMaintenance(linkGeneration: Long) {
        linkJob?.cancel()
        linkJob = scope.launch {
            while (started && generation == linkGeneration && session.attached) {
                delay(100)
                maintenanceTick(linkGeneration)
            }
        }
    }

    /**
     * Runs the maintenanceTick operation.
     */
    private fun maintenanceTick(linkGeneration: Long) {
        if (linkGeneration != generation || !session.attached) return
        val now = SystemClock.elapsedRealtime()
        transport.openedDeviceId()?.let { openedDeviceId ->
            if (!transport.isAttached(openedDeviceId)) {
                Log.w(
                    USB_LOG_TAG,
                    "maintenance attachment missing openedDeviceId=$openedDeviceId " +
                        "matchingIds=${matchingDeviceIdsForLog()}",
                )
                handlePhysicalDetach("maintenance device-list check")
                return
            }
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
        actuatorAcknowledgements.firstTimedOut(
            nowElapsedRealtimeMillis = now,
            timeoutMillis = config.actuatorAcknowledgementTimeoutMillis,
        )?.let { pending ->
            fail(
                BoardLinkErrorCode.ACTUATOR_ACK_TIMEOUT,
                "Board did not acknowledge actuator request ${pending.sequence} " +
                    "(${pending.expectedAction}) within " +
                    "${config.actuatorAcknowledgementTimeoutMillis}ms",
                recoverable = true,
            )
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

    /**
     * Runs the submitActuatorRequest operation.
     */
    private fun submitActuatorRequest(
        commandName: String,
        commandCount: Int,
        expectedAction: ApplicationAction,
        validateReady: () -> BoardActuatorCommandResult.Rejected?,
        buildCommands: () -> List<BoardProtocolSession.EncodedCommand>,
    ): BoardActuatorCommandResult {
        if (closed.get()) {
            return actuatorRejected(BoardActuatorRejection.CLOSED, "$commandName rejected: link is closed")
        }
        validateReady()?.let { return it }
        if (!actuatorSubmissionGate.tryAcquire()) {
            return actuatorRejected(
                BoardActuatorRejection.QUEUE_SATURATED,
                "$commandName rejected: the bounded actuator submission queue is full",
            )
        }
        if (commandName != "MotorFrameCommand" && commandName != "ServoCommand") {
            FlightBlackBox.record(
                type = FbbEventType.CALL,
                description = "HardwareConnection actuator command queued",
                cause = lastUsbEvent,
                metadata = mapOf("command" to commandName, "count" to commandCount),
                persistence = FbbPersistence.IMPORTANT,
            )
        }
        val submission = scope.launch {
            val commands = runCatching { buildCommands() }.getOrElse {
                FlightBlackBox.record(
                    type = FbbEventType.ERROR,
                    description = "Actuator command build failed",
                    cause = lastUsbEvent,
                    metadata = mapOf(
                        "command" to commandName,
                        "error" to (it.message ?: it.javaClass.simpleName),
                    ),
                    persistence = FbbPersistence.CRITICAL,
                )
                fail(
                    BoardLinkErrorCode.INTERNAL_ERROR,
                    "$commandName could not be encoded for the board",
                    recoverable = true,
                )
                return@launch
            }
            if (commands.size != commandCount) {
                fail(
                    BoardLinkErrorCode.INTERNAL_ERROR,
                    "$commandName encoded ${commands.size} commands; expected $commandCount",
                    recoverable = true,
                )
                return@launch
            }
            if (!actuatorAcknowledgements.canTrack(commands.size)) {
                fail(
                    BoardLinkErrorCode.ACTUATOR_BACKPRESSURE,
                    "$commandName batch of ${commands.size} exceeds the remaining " +
                        "actuator acknowledgement window",
                    recoverable = true,
                )
                return@launch
            }
            for (command in commands) {
                if (!sendExpectingAck(command, expectedAction, bypassPendingLimit = false)) break
            }
        }
        submission.invokeOnCompletion { actuatorSubmissionGate.release() }
        return BoardActuatorCommandResult.Queued(commandCount)
    }

    /**
     * Runs the submitSafetyOverride operation.
     */
    private fun submitSafetyOverride(
        commandName: String,
        expectedAction: ApplicationAction,
        buildCommand: () -> BoardProtocolSession.EncodedCommand,
    ): BoardActuatorCommandResult {
        if (closed.get()) {
            return actuatorRejected(BoardActuatorRejection.CLOSED, "$commandName rejected: link is closed")
        }
        if (!session.attached) {
            return actuatorRejected(
                BoardActuatorRejection.NOT_READY,
                "$commandName rejected: no synchronized USB session is attached",
            )
        }
        val submissionGate = if (expectedAction == ApplicationAction.EMERGENCY_STOP_APPLIED) {
            emergencyStopSubmissionGate
        } else {
            disarmSubmissionGate
        }
        if (!submissionGate.tryAcquire()) {
            // An identical safety request already owns this action's reserved slot.
            return BoardActuatorCommandResult.Queued(1)
        }
        FlightBlackBox.record(
            type = FbbEventType.CALL,
            description = "HardwareConnection safety override queued",
            cause = lastUsbEvent,
            metadata = mapOf("command" to commandName),
            persistence = FbbPersistence.CRITICAL,
        )
        val submission = scope.launch {
            if (actuatorAcknowledgements.containsExpectedAction(expectedAction)) return@launch
            val command = runCatching { buildCommand() }.getOrElse {
                FlightBlackBox.record(
                    type = FbbEventType.ERROR,
                    description = "Safety override build failed",
                    cause = lastUsbEvent,
                    metadata = mapOf(
                        "command" to commandName,
                        "error" to (it.message ?: it.javaClass.simpleName),
                    ),
                    persistence = FbbPersistence.CRITICAL,
                )
                fail(
                    BoardLinkErrorCode.INTERNAL_ERROR,
                    "$commandName could not be encoded for the board",
                    recoverable = true,
                )
                return@launch
            }
            sendExpectingAck(command, expectedAction, bypassPendingLimit = true)
        }
        submission.invokeOnCompletion { submissionGate.release() }
        return BoardActuatorCommandResult.Queued(1)
    }

    /**
     * Runs the validateReadyForActuatorOutput operation.
     */
    private fun validateReadyForActuatorOutput(): BoardActuatorCommandResult.Rejected? {
        if (!config.allowActuatorCommands) {
            return actuatorRejected(
                BoardActuatorRejection.DISABLED_BY_CONFIG,
                "Actuator commands require HardwareConnectionConfig.allowActuatorCommands=true",
            )
        }
        val ready = mutableConnectionState.value as? BoardConnectionState.Ready
            ?: return actuatorRejected(
                BoardActuatorRejection.NOT_READY,
                "Actuator commands require a Ready board connection",
            )
        val info = ready.deviceInfo
        if (!info.actuatorsEnabledByConfiguration || !info.actuatorAvailable) {
            return actuatorRejected(
                BoardActuatorRejection.ACTUATOR_UNAVAILABLE,
                "The board runtime reports that physical actuators are unavailable",
            )
        }
        return null
    }

    /**
     * Runs the validateMotorBatch operation.
     */
    private fun validateMotorBatch(
        pulses: List<BoardMotorPulse>,
    ): BoardActuatorCommandResult.Rejected? {
        val activeChannels =
            (mutableConnectionState.value as? BoardConnectionState.Ready)?.deviceInfo?.activeMotorChannels
        val failure = validateQuadXMotorFrame(
            pulses = pulses,
            maximumBatchSize = config.maximumMotorCommandBatch,
            activeMotorChannels = activeChannels,
            pulseBounds = config.motorPulseBounds,
        ) ?: return null
        return actuatorRejected(failure.reason, failure.message)
    }

    /**
     * Runs the validateServoBatch operation.
     */
    private fun validateServoBatch(
        pulses: List<BoardServoPulse>,
    ): BoardActuatorCommandResult.Rejected? {
        if (pulses.isEmpty()) {
            return actuatorRejected(BoardActuatorRejection.EMPTY_BATCH, "Servo command batch is empty")
        }
        if (pulses.size > config.maximumServoCommandBatch) {
            return actuatorRejected(
                BoardActuatorRejection.BATCH_TOO_LARGE,
                "Servo command batch contains ${pulses.size} commands",
            )
        }
        val activeChannels =
            (mutableConnectionState.value as? BoardConnectionState.Ready)?.deviceInfo?.activeServoChannels
        val seenChannels = hashSetOf<Int>()
        pulses.forEach { pulse ->
            if (!seenChannels.add(pulse.channel)) {
                return actuatorRejected(
                    BoardActuatorRejection.INVALID_CHANNEL,
                    "Duplicate servo channel ${pulse.channel}",
                )
            }
            if (activeChannels != null && pulse.channel >= activeChannels) {
                return actuatorRejected(
                    BoardActuatorRejection.INVALID_CHANNEL,
                    "Servo channel ${pulse.channel} is outside active channel count $activeChannels",
                )
            }
            if (!config.servoPulseBounds.contains(pulse.pulseMicros)) {
                return actuatorRejected(
                    BoardActuatorRejection.INVALID_PULSE,
                    "Servo pulse ${pulse.pulseMicros}us is outside ${config.servoPulseBounds}",
                )
            }
        }
        return null
    }

    /** Rejects future or already stale actuator output before it enters the USB queue. */
    private fun validateActuatorGenerationTime(
        generatedAtElapsedRealtimeNanos: Long,
    ): BoardActuatorCommandResult.Rejected? {
        val nowNanos = SystemClock.elapsedRealtimeNanos()
        if (generatedAtElapsedRealtimeNanos < 0L || generatedAtElapsedRealtimeNanos > nowNanos) {
            return actuatorRejected(
                BoardActuatorRejection.FUTURE_COMMAND,
                "Motor command generation timestamp is invalid or in the future",
            )
        }
        val maximumAgeNanos = config.maximumActuatorCommandAgeMillis * 1_000_000L
        if (nowNanos - generatedAtElapsedRealtimeNanos > maximumAgeNanos) {
            return actuatorRejected(
                BoardActuatorRejection.STALE_COMMAND,
                "Motor command is older than ${config.maximumActuatorCommandAgeMillis}ms",
            )
        }
        return null
    }

    /**
     * Runs the actuatorRejected operation.
     */
    private fun actuatorRejected(
        reason: BoardActuatorRejection,
        message: String,
    ): BoardActuatorCommandResult.Rejected {
        FlightBlackBox.record(
            type = FbbEventType.WARNING,
            description = "HardwareConnection actuator command rejected",
            cause = lastUsbEvent,
            metadata = mapOf("reason" to reason, "message" to message),
            persistence = FbbPersistence.IMPORTANT,
        )
        return BoardActuatorCommandResult.Rejected(reason, message)
    }

    /**
     * Runs the sendTimeSync operation.
     */
    private fun sendTimeSync(): Boolean {
        val sent = send(session.buildTimeSync())
        if (sent) {
            timeSyncPending = true
            timeSyncSentMillis = SystemClock.elapsedRealtime()
        }
        return sent
    }

    /**
     * Runs the sendInitialTimeSync operation.
     */
    private fun sendInitialTimeSync(): Boolean {
        initialTimeSyncAttemptsSent += 1
        return sendTimeSync()
    }

    /**
     * Runs the sendHeartbeat operation.
     */
    private fun sendHeartbeat(): Boolean {
        val sent = send(session.buildHeartbeat())
        if (sent) lastHeartbeatSentMillis = SystemClock.elapsedRealtime()
        return sent
    }

    /**
     * Runs the sendExpectingAck operation.
     */
    private fun sendExpectingAck(
        command: BoardProtocolSession.EncodedCommand,
        expectedAction: ApplicationAction,
        bypassPendingLimit: Boolean,
    ): Boolean {
        val tracked = actuatorAcknowledgements.track(
            sequence = command.sequence,
            expectedAction = expectedAction,
            sentAtElapsedRealtimeMillis = SystemClock.elapsedRealtime(),
            bypassLimit = bypassPendingLimit,
        )
        if (!tracked) {
            fail(
                BoardLinkErrorCode.ACTUATOR_BACKPRESSURE,
                "Actuator acknowledgement window is full at " +
                    "${actuatorAcknowledgements.pendingCount} pending commands",
                recoverable = true,
            )
            return false
        }
        val sent = send(command)
        if (!sent) actuatorAcknowledgements.remove(command.sequence)
        return sent
    }

    /**
     * Runs the send operation.
     */
    private fun send(command: BoardProtocolSession.EncodedCommand): Boolean {
        val highFrequencyActuatorCommand =
            command.type == MessageType.MOTOR_FRAME_COMMAND ||
                command.type == MessageType.SERVO_COMMAND ||
                command.type == MessageType.ACTUATOR_COMMAND
        val tx = if (highFrequencyActuatorCommand) {
            lastUsbEvent
        } else {
            FlightBlackBox.record(
                type = FbbEventType.USB_TX,
                description = "Protocol command sent",
                cause = lastUsbEvent,
                metadata = mapOf(
                    "type" to command.type,
                    "sequence" to command.sequence,
                    "size" to command.bytes.size,
                    "generation" to generation,
                ),
            )
        }
        if (transport.write(generation, command.bytes)) {
            lastCommandSentType = command.type
            lastCommandSentSequence = command.sequence
            if (!highFrequencyActuatorCommand) lastUsbEvent = tx
            return true
        }
        lastUsbEvent = FlightBlackBox.record(
            type = FbbEventType.ERROR,
            description = "USB write failed",
            cause = tx,
            metadata = mapOf("type" to command.type, "sequence" to command.sequence),
            persistence = FbbPersistence.CRITICAL,
        )
        fail(
            BoardLinkErrorCode.USB_WRITE_FAILED,
            "Could not write ${command.type} to the board",
            recoverable = true,
        )
        return false
    }

    /**
     * Runs the handlePhysicalDetach operation.
     */
    private fun handlePhysicalDetach(source: String) {
        val detachedDeviceId = transport.openedDeviceId()
        lastUsbEvent = FlightBlackBox.record(
            type = FbbEventType.SYSTEM,
            description = "USB physical detach confirmed",
            cause = lastUsbEvent,
            metadata = mapOf(
                "source" to source,
                "openedDeviceId" to detachedDeviceId,
            ) + usbDiagnosticContextMetadata(),
            persistence = FbbPersistence.IMPORTANT,
        )
        Log.w(
            USB_LOG_TAG,
            "physical detach confirmed source=$source openedDeviceId=$detachedDeviceId " +
                "matchingIds=${matchingDeviceIdsForLog()}",
        )
        closeLink(sendSafetyShutdown = false)
        selectedDevice = null
        selectedDescriptor = null
        val currentMatchingDeviceIds = runCatching {
            transport.matchingDevices().map { it.deviceId }
        }.getOrElse { emptyList() }
        when (
            reenumerationGraceAction(
                graceExpired = false,
                matchingDeviceCount = currentMatchingDeviceIds.size,
            )
        ) {
            ReenumerationGraceAction.RESCAN_REPLACEMENT -> {
                Log.i(
                    USB_LOG_TAG,
                    "rescanning after detach deviceId=$detachedDeviceId " +
                        "replacementIds=$currentMatchingDeviceIds",
                )
                scanAndConnect()
            }
            ReenumerationGraceAction.WAIT_FOR_REPLACEMENT -> {
                mutableConnectionState.value = BoardConnectionState.Searching
                startReenumerationGrace(detachedDeviceId, generation)
            }
            ReenumerationGraceAction.PUBLISH_DETACHED -> Unit // Grace has not expired here.
        }
    }

    /**
     * Runs the startReenumerationGrace operation.
     */
    private fun startReenumerationGrace(detachedDeviceId: Int?, detachGeneration: Long) {
        cancelReenumerationGrace()
        Log.i(
            USB_LOG_TAG,
            "waiting ${USB_REENUMERATION_GRACE_MILLIS}ms for USB re-enumeration " +
                "detachedDeviceId=$detachedDeviceId generation=$detachGeneration",
        )
        reenumerationGraceJob = scope.launch {
            delay(USB_REENUMERATION_GRACE_MILLIS)
            if (!started || generation != detachGeneration) {
                reenumerationGraceJob = null
                return@launch
            }
            val matchingDeviceCount = runCatching { transport.matchingDevices().size }
                .getOrDefault(0)
            reenumerationGraceJob = null
            when (
                reenumerationGraceAction(
                    graceExpired = true,
                    matchingDeviceCount = matchingDeviceCount,
                )
            ) {
                ReenumerationGraceAction.RESCAN_REPLACEMENT -> scanAndConnect()
                ReenumerationGraceAction.PUBLISH_DETACHED -> {
                    Log.w(
                        USB_LOG_TAG,
                        "USB re-enumeration grace expired detachedDeviceId=$detachedDeviceId",
                    )
                    mutableConnectionState.value = BoardConnectionState.Disconnected(
                        BoardDisconnectReason.USB_DETACHED,
                    )
                }
                ReenumerationGraceAction.WAIT_FOR_REPLACEMENT -> Unit
            }
        }
    }

    /**
     * Runs the cancelReenumerationGrace operation.
     */
    private fun cancelReenumerationGrace() {
        reenumerationGraceJob?.cancel()
        reenumerationGraceJob = null
    }

    /**
     * Runs the fail operation.
     */
    private fun fail(code: BoardLinkErrorCode, message: String, recoverable: Boolean) {
        lastUsbEvent = FlightBlackBox.record(
            type = FbbEventType.ERROR,
            description = "HardwareConnection failed",
            cause = lastUsbEvent,
            metadata = mapOf(
                "code" to code,
                "message" to message,
                "recoverable" to recoverable,
            ),
            persistence = FbbPersistence.CRITICAL,
        )
        closeLink(sendSafetyShutdown = true)
        mutableConnectionState.value = BoardConnectionState.Failed(
            BoardLinkError(code, message, recoverable),
        )
    }

    /**
     * Runs the failWithoutOpen operation.
     */
    private fun failWithoutOpen(code: BoardLinkErrorCode, message: String) {
        lastUsbEvent = FlightBlackBox.record(
            type = FbbEventType.ERROR,
            description = "HardwareConnection failed before USB session opened",
            cause = lastUsbEvent,
            metadata = mapOf("code" to code, "message" to message),
            persistence = FbbPersistence.CRITICAL,
        )
        closeLink(sendSafetyShutdown = false)
        mutableConnectionState.value = BoardConnectionState.Failed(
            BoardLinkError(code, message, recoverable = true),
        )
    }

    /**
     * Runs the closeLink operation.
     */
    private fun closeLink(sendSafetyShutdown: Boolean) {
        cancelReenumerationGrace()
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

    /**
     * Runs the resetHandshakeState operation.
     */
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
        lastCommandSentType = null
        lastCommandSentSequence = null
        actuatorAcknowledgements.clear()
    }

    /**
     * Runs the publishTelemetry operation.
     */
    private fun publishTelemetry() {
        mutableTelemetry.value = telemetryStore.snapshot
    }

    /**
     * Runs the registerReceivers operation.
     */
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

    /**
     * Runs the unregisterReceivers operation.
     */
    private fun unregisterReceivers() {
        if (!receiversRegistered) return
        runCatching { applicationContext.unregisterReceiver(permissionReceiver) }
        runCatching { applicationContext.unregisterReceiver(usbLifecycleReceiver) }
        receiversRegistered = false
    }

    /**
     * Runs the BoardUsbDevice operation.
     */
    private fun BoardUsbDevice.fbbMetadata(): Map<String, Any?> = mapOf(
        "deviceId" to deviceId,
        "vid" to "0x${vendorId.toString(16)}",
        "pid" to "0x${productId.toString(16)}",
    )

    /**
     * Runs the usbDiagnosticContextMetadata operation.
     */
    private fun usbDiagnosticContextMetadata(): Map<String, Any?> = mapOf(
        "connection" to mutableConnectionState.value.fbbKind(),
        "stage" to currentInboundSessionStage(),
        "generation" to generation,
        "selectedDeviceId" to selectedDescriptor?.deviceId,
        "lastCommand" to lastCommandSentType,
        "lastCommandSequence" to lastCommandSentSequence,
    )

    /**
     * Runs the BoardConnectionState operation.
     */
    private fun BoardConnectionState.fbbKind(): String = when (this) {
        BoardConnectionState.Stopped -> "Stopped"
        BoardConnectionState.Searching -> "Searching"
        is BoardConnectionState.PermissionRequired -> "PermissionRequired"
        is BoardConnectionState.RequestingPermission -> "RequestingPermission"
        is BoardConnectionState.Opening -> "Opening"
        is BoardConnectionState.Synchronizing -> "Synchronizing"
        is BoardConnectionState.ValidatingDevice -> "ValidatingDevice"
        is BoardConnectionState.AwaitingHeartbeat -> "AwaitingHeartbeat"
        is BoardConnectionState.StartingTelemetry -> "StartingTelemetry"
        is BoardConnectionState.Ready -> "Ready"
        is BoardConnectionState.Disconnected -> "Disconnected"
        is BoardConnectionState.Failed -> "Failed"
    }

    /**
     * Runs the DecodedFrame operation.
     */
    private fun DecodedFrame.fbbMetadata(receivedAtUs: ULong): Map<String, Any?> = mapOf(
        "messageType" to header.messageType,
        "priority" to header.priority,
        "sequence" to header.sequence,
        "payloadLength" to header.payloadLength,
        "senderUs" to header.senderMonotonicUs,
        "receivedHostUs" to receivedAtUs,
    )

    /**
     * Runs the UsbDevice operation.
     */
    private fun UsbDevice.toPublicDescriptor() = BoardUsbDevice(
        deviceId = deviceId,
        deviceName = deviceName,
        vendorId = vendorId,
        productId = productId,
    )

    /**
     * Runs the UsbDevice operation.
     */
    private fun UsbDevice.isExactShahbazIdentity(): Boolean =
        hasExactShahbazBoardUsbIdentity(vendorId, productId)

    /**
     * Runs the matchingDeviceIdsForLog operation.
     */
    private fun matchingDeviceIdsForLog(): String = runCatching {
        transport.matchingDevices()
            .joinToString(prefix = "[", postfix = "]") { it.deviceId.toString() }
    }.getOrElse { "unavailable(${it.javaClass.simpleName})" }

    /**
     * Runs the attachmentStateForLog operation.
     */
    private fun attachmentStateForLog(deviceId: Int): String = runCatching {
        transport.isAttached(deviceId).toString()
    }.getOrElse { "unavailable(${it.javaClass.simpleName})" }

    /**
     * Runs the permissionStateForLog operation.
     */
    private fun permissionStateForLog(device: UsbDevice): String = runCatching {
        transport.hasPermission(device).toString()
    }.getOrElse { "unavailable(${it.javaClass.simpleName})" }

    /**
     * Runs the Intent operation.
     */
    private fun Intent.usbDevice(): UsbDevice? = if (Build.VERSION.SDK_INT >= 33) {
        getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(UsbManager.EXTRA_DEVICE) as? UsbDevice
    }

    /**
     * Runs the elapsedRealtimeMicros operation.
     */
    private fun elapsedRealtimeMicros(): ULong =
        SystemClock.elapsedRealtimeNanos().toULong() / 1_000uL
}
