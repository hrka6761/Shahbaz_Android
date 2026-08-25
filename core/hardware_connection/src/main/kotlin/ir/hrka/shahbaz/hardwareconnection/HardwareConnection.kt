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

private const val USB_PERMISSION_DEVICE_ID_EXTRA =
    "ir.hrka.shahbaz.hardwareconnection.extra.USB_PERMISSION_DEVICE_ID"
private const val USB_LOG_TAG = "ShahbazUsb"
private const val USB_REENUMERATION_GRACE_MILLIS = 2_000L

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
    private var reenumerationGraceJob: Job? = null
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
    private var lastUsbEvent: FbbEventRef? = null

    private val permissionReceiver = object : BroadcastReceiver() {
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

    private val usbLifecycleReceiver = object : BroadcastReceiver() {
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
                            ),
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
            }
            MessageType.COMMAND_NACK -> {
                val nack = frame.decodeCommandNack() ?: throw ProtocolException(
                    ProtocolErrorKind.PAYLOAD_INVALID,
                    "CommandNack decoder rejected its message type",
                )
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
                        fail(
                            BoardLinkErrorCode.SESSION_REJECTED,
                            "Board rejected request ${nack.requestSequence} from this USB session",
                            recoverable = true,
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
                if (status.actuatorArmed) {
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
        val tx = FlightBlackBox.record(
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
        if (transport.write(generation, command.bytes)) {
            lastUsbEvent = tx
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

    private fun handlePhysicalDetach(source: String) {
        val detachedDeviceId = transport.openedDeviceId()
        lastUsbEvent = FlightBlackBox.record(
            type = FbbEventType.SYSTEM,
            description = "USB physical detach confirmed",
            cause = lastUsbEvent,
            metadata = mapOf("source" to source, "openedDeviceId" to detachedDeviceId),
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

    private fun cancelReenumerationGrace() {
        reenumerationGraceJob?.cancel()
        reenumerationGraceJob = null
    }

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

    private fun BoardUsbDevice.fbbMetadata(): Map<String, Any?> = mapOf(
        "deviceId" to deviceId,
        "vid" to "0x${vendorId.toString(16)}",
        "pid" to "0x${productId.toString(16)}",
    )

    private fun DecodedFrame.fbbMetadata(receivedAtUs: ULong): Map<String, Any?> = mapOf(
        "messageType" to header.messageType,
        "priority" to header.priority,
        "sequence" to header.sequence,
        "payloadLength" to header.payloadLength,
        "senderUs" to header.senderMonotonicUs,
        "receivedHostUs" to receivedAtUs,
    )

    private fun UsbDevice.toPublicDescriptor() = BoardUsbDevice(
        deviceId = deviceId,
        deviceName = deviceName,
        vendorId = vendorId,
        productId = productId,
    )

    private fun UsbDevice.isExactShahbazIdentity(): Boolean =
        hasExactShahbazBoardUsbIdentity(vendorId, productId)

    private fun matchingDeviceIdsForLog(): String = runCatching {
        transport.matchingDevices()
            .joinToString(prefix = "[", postfix = "]") { it.deviceId.toString() }
    }.getOrElse { "unavailable(${it.javaClass.simpleName})" }

    private fun attachmentStateForLog(deviceId: Int): String = runCatching {
        transport.isAttached(deviceId).toString()
    }.getOrElse { "unavailable(${it.javaClass.simpleName})" }

    private fun permissionStateForLog(device: UsbDevice): String = runCatching {
        transport.hasPermission(device).toString()
    }.getOrElse { "unavailable(${it.javaClass.simpleName})" }

    private fun Intent.usbDevice(): UsbDevice? = if (Build.VERSION.SDK_INT >= 33) {
        getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(UsbManager.EXTRA_DEVICE) as? UsbDevice
    }

    private fun elapsedRealtimeMicros(): ULong =
        SystemClock.elapsedRealtimeNanos().toULong() / 1_000uL
}
