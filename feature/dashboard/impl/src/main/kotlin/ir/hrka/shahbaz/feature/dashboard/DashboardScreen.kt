/** Cockpit-style, failure-explicit presentation for a confirmed Shahbaz flight plan. */
package ir.hrka.shahbaz.feature.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded._360
import androidx.compose.material.icons.rounded.Compress
import androidx.compose.material.icons.rounded.DeviceThermostat
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.FlightTakeoff
import androidx.compose.material.icons.rounded.Height
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.Terrain
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shahbaz.flightblackbox.FbbEventType
import com.shahbaz.flightblackbox.FbbPersistence
import com.shahbaz.flightblackbox.FlightBlackBox
import ir.hrka.compass.CompassAccuracyLevel
import ir.hrka.compass.CompassReading
import ir.hrka.shahbaz.autopilot.AutopilotPhase
import ir.hrka.shahbaz.autopilot.AutopilotSnapshot
import ir.hrka.shahbaz.core.designsystem.attitude.AttitudeIndicatorView
import ir.hrka.shahbaz.core.designsystem.compass.CompassView
import ir.hrka.shahbaz.core.domain.formatDistance
import ir.hrka.shahbaz.core.domain.sphericalMidpoint
import ir.hrka.shahbaz.core.domain.wgs84GeodesicDistanceMeters
import ir.hrka.shahbaz.core.model.GeoCoordinate
import ir.hrka.shahbaz.feature.dashboard.impl.R
import ir.hrka.shahbaz.hardwareconnection.BoardConnectionState
import ir.hrka.shahbaz.hardwareconnection.BoardDisconnectReason
import ir.hrka.shahbaz.hardwareconnection.RangefinderRole
import ir.hrka.shahbaz.hardwareconnection.SensorErrorCode
import ir.hrka.shahbaz.hardwareconnection.SensorState
import ir.hrka.shahbaz.hardwareconnection.SensorUnavailableReason
import ir.hrka.shahbaz.hardwareconnection.Vl53l0xTelemetry
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.rememberCameraState
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.format
import org.maplibre.compose.expressions.dsl.span
import org.maplibre.compose.expressions.value.LineCap
import org.maplibre.compose.expressions.value.LineJoin
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.map.GestureOptions
import org.maplibre.compose.map.MapOptions
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.OrnamentOptions
import org.maplibre.compose.map.RenderOptions
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonOptions
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.rememberStyleState
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.LineString
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position

/** Exact adaptive pane decision used by the 70/30 dashboard layout. */
internal enum class DashboardPaneLayout { PORTRAIT, LANDSCAPE }

/**
 * Runs the dashboardPaneLayout operation.
 */
internal fun dashboardPaneLayout(width: Float, height: Float): DashboardPaneLayout {
    require(width >= 0f && height >= 0f)
    return if (width > height) DashboardPaneLayout.LANDSCAPE else DashboardPaneLayout.PORTRAIT
}

/** Primary operator intent exposed by the dashboard for the current mission phase. */
internal enum class AutopilotPrimaryControl { START, ABORT, STATUS }

/** Platform-neutral decision consumed by the Compose mission controls. */
internal data class AutopilotControlPresentation(
    val primaryControl: AutopilotPrimaryControl,
    val phase: AutopilotPhase?,
    val showEmergencyStop: Boolean,
    val firstIssueMessage: String?,
    val additionalIssueCount: Int,
)

/**
 * Maps an immutable autopilot snapshot to operator controls without performing any flight action.
 * The UI emits intent only; the autopilot and flight controller retain all physical safety gates.
 */
internal fun autopilotControlPresentation(
    snapshot: AutopilotSnapshot?,
): AutopilotControlPresentation {
    val phase = snapshot?.phase
    val primaryControl = when (phase) {
        AutopilotPhase.STANDBY -> AutopilotPrimaryControl.START
        AutopilotPhase.PREFLIGHT,
        AutopilotPhase.ARMING,
        AutopilotPhase.TAKEOFF,
        AutopilotPhase.CRUISE,
        AutopilotPhase.LANDING,
        AutopilotPhase.RETURN_CLIMB,
        AutopilotPhase.RETURNING,
        AutopilotPhase.DISARMING,
        -> AutopilotPrimaryControl.ABORT
        AutopilotPhase.COMPLETED,
        AutopilotPhase.ABORTED,
        AutopilotPhase.FAILED,
        AutopilotPhase.EMERGENCY_STOPPED,
        null,
        -> AutopilotPrimaryControl.STATUS
    }
    val issues = snapshot?.issues.orEmpty()
    return AutopilotControlPresentation(
        primaryControl = primaryControl,
        phase = phase,
        showEmergencyStop = phase != null && primaryControl != AutopilotPrimaryControl.STATUS,
        firstIssueMessage = issues.firstOrNull()?.message,
        additionalIssueCount = (issues.size - 1).coerceAtLeast(0),
    )
}

/** Recovery action exposed by the blocking board-connection gate. */
internal enum class ConnectionGateAction { NONE, REQUEST_PERMISSION, RETRY }

/**
 * Runs the connectionGateAction operation.
 */
internal fun connectionGateAction(state: BoardConnectionState): ConnectionGateAction = when (state) {
    is BoardConnectionState.PermissionRequired -> ConnectionGateAction.REQUEST_PERMISSION
    is BoardConnectionState.Disconnected,
    BoardConnectionState.Stopped -> ConnectionGateAction.RETRY
    is BoardConnectionState.Failed -> if (state.error.recoverable) {
        ConnectionGateAction.RETRY
    } else {
        ConnectionGateAction.NONE
    }
    is BoardConnectionState.Ready,
    BoardConnectionState.Searching,
    is BoardConnectionState.RequestingPermission,
    is BoardConnectionState.Opening,
    is BoardConnectionState.Synchronizing,
    is BoardConnectionState.ValidatingDevice,
    is BoardConnectionState.AwaitingHeartbeat,
    is BoardConnectionState.StartingTelemetry -> ConnectionGateAction.NONE
}

/**
 * Runs the shouldBlockDashboard operation.
 */
internal fun shouldBlockDashboard(state: BoardConnectionState): Boolean =
    state !is BoardConnectionState.Ready

/**
 * Runs the dashboardBlockReason operation.
 */
internal fun dashboardBlockReason(state: BoardConnectionState): String = when (state) {
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

/** Keeps firmware evidence warnings visible without treating them as live-link failures. */
internal fun boardReadyStatusKind(state: BoardConnectionState): InstrumentStatusKind {
    val ready = state as? BoardConnectionState.Ready ?: return InstrumentStatusKind.NOT_CONNECTED
    return if (ready.deviceInfo.boardValidationIssueMask == 0L) {
        InstrumentStatusKind.LIVE
    } else {
        InstrumentStatusKind.DEGRADED
    }
}

/** Shared, pure status classification for visual and textual instrument state. */
internal enum class InstrumentStatusKind {
    LIVE,
    DEGRADED,
    STALE,
    LOADING,
    NOT_CONNECTED,
    NOT_PRESENT,
    UNAVAILABLE,
    NO_RESPONSE,
    OUT_OF_RANGE,
    SIGNAL_FAILURE,
    INVALID,
    ERROR,
    INACTIVE,
}

/**
 * Runs the sensorStatusKind operation.
 */
internal fun sensorStatusKind(state: SensorState<*>): InstrumentStatusKind = when (state) {
    is SensorState.Available -> InstrumentStatusKind.LIVE
    is SensorState.Stale -> InstrumentStatusKind.STALE
    SensorState.AwaitingFirstSample -> InstrumentStatusKind.LOADING
    is SensorState.Unavailable -> when (state.reason) {
        SensorUnavailableReason.BOARD_DISCONNECTED -> InstrumentStatusKind.NOT_CONNECTED
        SensorUnavailableReason.TELEMETRY_NOT_STARTED -> InstrumentStatusKind.LOADING
        SensorUnavailableReason.SENSOR_REPORTED_OFFLINE -> InstrumentStatusKind.NOT_PRESENT
        SensorUnavailableReason.RANGEFINDER_DISABLED_OR_ABSENT -> InstrumentStatusKind.NOT_PRESENT
        SensorUnavailableReason.RANGEFINDER_INITIALIZING -> InstrumentStatusKind.LOADING
    }
    is SensorState.Failed -> when (state.error.code) {
        SensorErrorCode.INVALID_PAYLOAD,
        SensorErrorCode.INVALID_VALIDITY,
        SensorErrorCode.RANGE_STATUS_UNKNOWN -> InstrumentStatusKind.INVALID
        SensorErrorCode.OUT_OF_RANGE,
        SensorErrorCode.RANGE_MINIMUM_FAILURE -> InstrumentStatusKind.OUT_OF_RANGE
        SensorErrorCode.RANGE_SIGMA_FAILURE,
        SensorErrorCode.RANGE_SIGNAL_FAILURE,
        SensorErrorCode.RANGE_PHASE_FAILURE -> InstrumentStatusKind.SIGNAL_FAILURE
        SensorErrorCode.NO_RESPONSE -> InstrumentStatusKind.NO_RESPONSE
        SensorErrorCode.NOT_FRESH -> InstrumentStatusKind.STALE
        SensorErrorCode.SENSOR_OFFLINE -> InstrumentStatusKind.NOT_PRESENT
        SensorErrorCode.RANGEFINDER_DEGRADED -> InstrumentStatusKind.DEGRADED
        SensorErrorCode.HEALTH_FAULT,
        SensorErrorCode.RANGE_HARDWARE_FAILURE -> InstrumentStatusKind.ERROR
    }
}

/**
 * Runs the phoneStatusKind operation.
 */
internal fun phoneStatusKind(state: PhoneReading<*>): InstrumentStatusKind = when (state) {
    is PhoneReading.Available -> InstrumentStatusKind.LIVE
    is PhoneReading.Stale -> InstrumentStatusKind.STALE
    is PhoneReading.NoResponse -> InstrumentStatusKind.NO_RESPONSE
    is PhoneReading.Invalid -> InstrumentStatusKind.INVALID
    PhoneReading.AwaitingFirstSample -> InstrumentStatusKind.LOADING
    is PhoneReading.NotPresent -> InstrumentStatusKind.NOT_PRESENT
    is PhoneReading.Unavailable -> InstrumentStatusKind.UNAVAILABLE
    is PhoneReading.Failed -> InstrumentStatusKind.ERROR
    PhoneReading.Inactive -> InstrumentStatusKind.INACTIVE
}

/** A present orientation sample is not healthy unless Android reports usable accuracy. */
internal fun orientationStatusKind(
    state: PhoneReading<CompassReading>,
): InstrumentStatusKind = when (state) {
    is PhoneReading.Available -> when (state.value.accuracy.level) {
        CompassAccuracyLevel.HIGH,
        CompassAccuracyLevel.MEDIUM -> InstrumentStatusKind.LIVE
        CompassAccuracyLevel.LOW,
        CompassAccuracyLevel.UNKNOWN -> InstrumentStatusKind.DEGRADED
        CompassAccuracyLevel.UNRELIABLE -> InstrumentStatusKind.INVALID
    }
    else -> phoneStatusKind(state)
}

/**
 * Displays the flight dashboard only after the USB board has completed its full Ready handshake.
 * The map and instruments occupy exact 30/70 weights in either orientation. Mission controls emit
 * operator intent through callbacks and never arm or command the aircraft directly.
 */
@Composable
fun DashboardScreen(
    state: DashboardUiState,
    onRequestUsbPermission: () -> Unit,
    onRetryBoardConnection: () -> Unit,
    onStartFlight: () -> Unit,
    onAbortFlight: () -> Unit,
    onEmergencyStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val blocked = shouldBlockDashboard(state.boardConnection)
    val blockReason = dashboardBlockReason(state.boardConnection)
    LaunchedEffect(Unit) {
        FlightBlackBox.record(
            type = FbbEventType.UI,
            description = "DashboardScreen visible",
            metadata = mapOf(
                "boardConnection" to blockReason,
                "blockedUntilBoardReady" to if (blocked) blockReason else false,
                "hasFlightPlan" to (state.flightPlan != null),
            ),
            persistence = FbbPersistence.IMPORTANT,
        )
    }
    LaunchedEffect(state.boardConnection) {
        FlightBlackBox.record(
            type = if (blocked) FbbEventType.DECISION else FbbEventType.UI,
            description = if (blocked) {
                "DashboardScreen blocked until board ready"
            } else {
                "DashboardScreen ready content visible"
            },
            metadata = mapOf(
                "boardConnection" to blockReason,
                "blockedUntilBoardReady" to if (blocked) blockReason else false,
                "connectionGateAction" to connectionGateAction(state.boardConnection),
                "hasFlightPlan" to (state.flightPlan != null),
            ),
            persistence = FbbPersistence.IMPORTANT,
        )
    }
    Surface(
        modifier = modifier.fillMaxSize(),
        color = CockpitBackground,
        contentColor = CockpitOnSurface,
    ) {
        if (blocked) {
            BoardConnectionGate(
                state = state.boardConnection,
                onRequestPermission = onRequestUsbPermission,
                onRetry = onRetryBoardConnection,
            )
            return@Surface
        }

        BoxWithConstraints(Modifier.fillMaxSize()) {
            val paneLayout = dashboardPaneLayout(maxWidth.value, maxHeight.value)
            Box(Modifier.fillMaxSize()) {
                if (paneLayout == DashboardPaneLayout.LANDSCAPE) {
                    Row(Modifier.fillMaxSize()) {
                        InstrumentPane(
                            state = state,
                            modifier = Modifier
                                .weight(INSTRUMENT_WEIGHT)
                                .fillMaxHeight(),
                        )
                        DashboardMapPane(
                            state = state,
                            modifier = Modifier
                                .weight(MAP_WEIGHT)
                                .fillMaxHeight(),
                        )
                    }
                } else {
                    Column(Modifier.fillMaxSize()) {
                        InstrumentPane(
                            state = state,
                            modifier = Modifier
                                .weight(INSTRUMENT_WEIGHT)
                                .fillMaxWidth(),
                        )
                        DashboardMapPane(
                            state = state,
                            modifier = Modifier
                                .weight(MAP_WEIGHT)
                                .fillMaxWidth(),
                        )
                    }
                }

                AutopilotControls(
                    snapshot = state.mission,
                    onStartFlight = onStartFlight,
                    onAbortFlight = onAbortFlight,
                    onEmergencyStop = onEmergencyStop,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(end = 16.dp, bottom = 16.dp),
                )
            }
        }
    }
}

/**
 * Runs the BoardConnectionGate operation.
 */
@Composable
private fun BoardConnectionGate(
    state: BoardConnectionState,
    onRequestPermission: () -> Unit,
    onRetry: () -> Unit,
) {
    val title = connectionTitle(state)
    val message = connectionMessage(state)
    val busy = state == BoardConnectionState.Searching ||
        state is BoardConnectionState.RequestingPermission ||
        state is BoardConnectionState.Opening ||
        state is BoardConnectionState.Synchronizing ||
        state is BoardConnectionState.ValidatingDevice ||
        state is BoardConnectionState.AwaitingHeartbeat ||
        state is BoardConnectionState.StartingTelemetry

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = 480.dp)
                .semantics {
                    liveRegion = LiveRegionMode.Polite
                    contentDescription = "$title. $message"
                },
            color = CockpitSurface,
            shape = DashboardCardShape,
            shadowElevation = 10.dp,
        ) {
            Column(
                modifier = Modifier.padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (busy) CircularProgressIndicator(color = MaterialTheme.colorScheme.tertiary)
                Text(
                    text = stringResource(R.string.board_gate_eyebrow),
                    color = CockpitMuted,
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = message,
                    color = CockpitMuted,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = stringResource(R.string.board_gate_blocking_note),
                    color = WarningAmber,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                )
                when (connectionGateAction(state)) {
                    ConnectionGateAction.REQUEST_PERMISSION -> Button(onClick = onRequestPermission) {
                        Text(stringResource(R.string.grant_usb_permission))
                    }
                    ConnectionGateAction.RETRY -> OutlinedButton(onClick = onRetry) {
                        Text(stringResource(R.string.retry_board_connection))
                    }
                    ConnectionGateAction.NONE -> Unit
                }
            }
        }
    }
}

/**
 * Runs the connectionTitle operation.
 */
@Composable
private fun connectionTitle(state: BoardConnectionState): String = stringResource(
    when (state) {
        BoardConnectionState.Stopped -> R.string.board_stopped_title
        BoardConnectionState.Searching -> R.string.board_searching_title
        is BoardConnectionState.PermissionRequired -> R.string.board_permission_title
        is BoardConnectionState.RequestingPermission -> R.string.board_permission_wait_title
        is BoardConnectionState.Opening -> R.string.board_opening_title
        is BoardConnectionState.Synchronizing -> R.string.board_sync_title
        is BoardConnectionState.ValidatingDevice -> R.string.board_validating_title
        is BoardConnectionState.AwaitingHeartbeat -> R.string.board_heartbeat_title
        is BoardConnectionState.StartingTelemetry -> R.string.board_telemetry_start_title
        is BoardConnectionState.Disconnected -> R.string.board_disconnected_title
        is BoardConnectionState.Failed -> R.string.board_failed_title
        is BoardConnectionState.Ready -> R.string.board_ready_title
    }
)

/**
 * Runs the connectionMessage operation.
 */
@Composable
private fun connectionMessage(state: BoardConnectionState): String = when (state) {
    BoardConnectionState.Stopped -> stringResource(R.string.board_stopped_message)
    BoardConnectionState.Searching -> stringResource(R.string.board_searching_message)
    is BoardConnectionState.PermissionRequired -> stringResource(
        R.string.board_permission_message,
        state.device.deviceName,
    )
    is BoardConnectionState.RequestingPermission -> stringResource(R.string.board_permission_wait_message)
    is BoardConnectionState.Opening -> stringResource(R.string.board_opening_message)
    is BoardConnectionState.Synchronizing -> stringResource(R.string.board_sync_message)
    is BoardConnectionState.ValidatingDevice -> stringResource(R.string.board_validating_message)
    is BoardConnectionState.AwaitingHeartbeat -> stringResource(R.string.board_heartbeat_message)
    is BoardConnectionState.StartingTelemetry ->
        stringResource(R.string.board_telemetry_start_message)
    is BoardConnectionState.Disconnected -> stringResource(
        R.string.board_disconnected_message,
        disconnectReasonText(state.reason),
    )
    is BoardConnectionState.Failed -> state.error.message.ifBlank {
        stringResource(R.string.board_failed_message)
    }
    is BoardConnectionState.Ready -> stringResource(R.string.board_ready_message)
}

/**
 * Runs the disconnectReasonText operation.
 */
@Composable
private fun disconnectReasonText(reason: BoardDisconnectReason): String = stringResource(
    when (reason) {
        BoardDisconnectReason.USB_DETACHED -> R.string.disconnect_usb_detached
        BoardDisconnectReason.APP_STOPPED -> R.string.disconnect_app_stopped
        BoardDisconnectReason.TRANSPORT_CLOSED -> R.string.disconnect_transport_closed
    }
)

/**
 * Runs the InstrumentPane operation.
 */
@Composable
private fun InstrumentPane(state: DashboardUiState, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(CockpitBackground)
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(start = 8.dp, top = 8.dp, end = 8.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AttitudePanel(state.phoneSensors.orientation)
        InstrumentRow(environmentInstruments(state))
        InstrumentRow(altitudeInstruments(state))
        rangefinderInstruments(state).chunked(2).forEach { row -> InstrumentRow(row) }
    }
}

/**
 * Runs the instrumentColumnCount operation.
 */
internal fun instrumentColumnCount(availableWidthDp: Float): Int = when {
    availableWidthDp >= 900f -> 3
    availableWidthDp >= 520f -> 2
    else -> 1
}

/**
 * Runs the AttitudePanel operation.
 */
@Composable
private fun AttitudePanel(orientation: PhoneReading<CompassReading>) {
    val reading = when (orientation) {
        is PhoneReading.Available -> orientation.value
        is PhoneReading.Stale -> orientation.lastValue
        is PhoneReading.NoResponse -> orientation.lastValue
        is PhoneReading.Invalid -> orientation.lastValue
        else -> null
    }
    val status = orientationStatusKind(orientation)
    val heading = reading?.magneticAzimuthDegrees
    val compassDescription = if (heading == null) {
        stringResource(R.string.compass_unavailable_accessibility, statusLabel(status))
    } else {
        stringResource(R.string.compass_accessibility, heading, statusLabel(status))
    }
    val attitudeDescription = if (reading == null) {
        stringResource(R.string.attitude_unavailable_description, statusLabel(status))
    } else {
        stringResource(
            R.string.attitude_indicator_accessibility,
            reading.pitchDegrees,
            reading.rollDegrees,
            statusLabel(status),
        )
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .aspectRatio(1f),
                color = Color.Black,
                shape = DashboardCardShape,
                border = BorderStroke(1.dp, statusColor(status)),
                shadowElevation = 3.dp,
            ) {
                CompassView(
                    heading = heading,
                    contentDescription = compassDescription,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .aspectRatio(1f),
                color = Color.Black,
                shape = DashboardCardShape,
                border = BorderStroke(1.dp, statusColor(status)),
                shadowElevation = 3.dp,
            ) {
                AttitudeIndicatorView(
                    pitchDegrees = reading?.pitchDegrees,
                    rollDegrees = reading?.rollDegrees,
                    contentDescription = attitudeDescription,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        OrientationReadoutRow(reading = reading, status = status)
    }
}

/**
 * Documents the OrientationReadout type and the role it plays in this module.
 */
private data class OrientationReadout(
    /**
     * Exposes the label value.
     */
    val label: String,
    /**
     * Exposes the value value.
     */
    val value: String,
    /**
     * Exposes the icon value.
     */
    val icon: ImageVector,
)

/** Android magnetic azimuth is also the display-corrected yaw around the screen-normal axis. */
@Composable
private fun OrientationReadoutRow(
    reading: CompassReading?,
    status: InstrumentStatusKind,
) {
    val unavailableValue = stringResource(R.string.no_value)
    val azimuth = reading?.magneticAzimuthDegrees
    val readouts = listOf(
        OrientationReadout(
            label = stringResource(R.string.orientation_compass_heading),
            value = azimuth?.let { stringResource(R.string.value_orientation_degrees, it) }
                ?: unavailableValue,
            icon = Icons.Rounded.Explore,
        ),
        OrientationReadout(
            label = stringResource(R.string.orientation_pitch),
            value = reading?.let {
                stringResource(R.string.value_orientation_signed_degrees, it.pitchDegrees)
            } ?: unavailableValue,
            icon = Icons.Rounded.SwapVert,
        ),
        OrientationReadout(
            label = stringResource(R.string.orientation_roll),
            value = reading?.let {
                stringResource(R.string.value_orientation_signed_degrees, it.rollDegrees)
            } ?: unavailableValue,
            icon = Icons.Rounded.ScreenRotation,
        ),
        OrientationReadout(
            label = stringResource(R.string.orientation_yaw),
            value = azimuth?.let { stringResource(R.string.value_orientation_degrees, it) }
                ?: unavailableValue,
            icon = Icons.AutoMirrored.Rounded._360,
        ),
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        readouts.forEach { readout ->
            OrientationReadoutCard(
                readout = readout,
                status = status,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * Runs the OrientationReadoutCard operation.
 */
@Composable
private fun OrientationReadoutCard(
    readout: OrientationReadout,
    status: InstrumentStatusKind,
    modifier: Modifier = Modifier,
) {
    val description = stringResource(
        R.string.orientation_readout_accessibility,
        readout.label,
        readout.value,
        statusLabel(status),
    )
    Surface(
        modifier = modifier
            .height(76.dp)
            .semantics(mergeDescendants = true) { contentDescription = description },
        color = CockpitSurface,
        shape = DashboardCardShape,
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(32.dp),
                horizontalArrangement = Arrangement.spacedBy(
                    space = 3.dp,
                    alignment = Alignment.CenterHorizontally,
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = readout.icon,
                    contentDescription = null,
                    tint = statusColor(status),
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = readout.label,
                    color = CockpitMuted,
                    fontSize = 10.sp,
                    lineHeight = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = readout.value,
                color = statusColor(status),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Documents the InstrumentReadout type and the role it plays in this module.
 */
private data class InstrumentReadout(
    /**
     * Exposes the id value.
     */
    val id: String,
    /**
     * Exposes the title value.
     */
    val title: String,
    /**
     * Exposes the icon value.
     */
    val icon: ImageVector,
    /**
     * Exposes the primaryValue value.
     */
    val primaryValue: String,
    /**
     * Exposes the status value.
     */
    val status: InstrumentStatusKind,
)

/**
 * Runs the environmentInstruments operation.
 */
@Composable
private fun environmentInstruments(state: DashboardUiState): List<InstrumentReadout> {
    val shtStatus = sensorStatusKind(state.boardTelemetry.sht30)
    val msStatus = sensorStatusKind(state.boardTelemetry.ms5611)
    val shtValue = state.boardTelemetry.sht30.lastShtValue()
    val msValue = state.boardTelemetry.ms5611.lastMsValue()

    return listOf(
        InstrumentReadout(
            id = "sht30-temperature",
            title = stringResource(R.string.instrument_temperature),
            icon = Icons.Rounded.DeviceThermostat,
            primaryValue = retainedInstrumentValue(shtValue?.let {
                stringResource(R.string.value_celsius, it.temperatureCelsius)
            }, shtStatus),
            status = shtStatus,
        ),
        InstrumentReadout(
            id = "sht30-humidity",
            title = stringResource(R.string.instrument_humidity),
            icon = Icons.Rounded.WaterDrop,
            primaryValue = retainedInstrumentValue(shtValue?.let {
                stringResource(R.string.value_percent, it.relativeHumidityPercent)
            }, shtStatus),
            status = shtStatus,
        ),
        InstrumentReadout(
            id = "ms5611-pressure",
            title = stringResource(R.string.instrument_pressure),
            icon = Icons.Rounded.Compress,
            primaryValue = retainedInstrumentValue(msValue?.let {
                stringResource(R.string.value_hectopascal, it.pressurePascal / 100.0)
            }, msStatus),
            status = msStatus,
        ),
    )
}

/**
 * Runs the altitudeInstruments operation.
 */
@Composable
private fun altitudeInstruments(state: DashboardUiState): List<InstrumentReadout> {
    val msStatus = sensorStatusKind(state.boardTelemetry.ms5611)
    val msValue = state.boardTelemetry.ms5611.lastMsValue()
    val takeoffStatus = when {
        msStatus == InstrumentStatusKind.ERROR -> InstrumentStatusKind.ERROR
        msStatus == InstrumentStatusKind.INVALID -> InstrumentStatusKind.INVALID
        msStatus == InstrumentStatusKind.NO_RESPONSE -> InstrumentStatusKind.NO_RESPONSE
        msStatus == InstrumentStatusKind.NOT_CONNECTED -> InstrumentStatusKind.NOT_CONNECTED
        msStatus == InstrumentStatusKind.NOT_PRESENT -> InstrumentStatusKind.NOT_PRESENT
        state.altitudeAboveTakeoffMeters == null -> InstrumentStatusKind.LOADING
        else -> msStatus
    }
    val targetAltitude = state.flightPlan?.targetAltitudeAboveOriginMeters

    return listOf(
        InstrumentReadout(
            id = "ms5611-altitude-msl",
            title = stringResource(R.string.instrument_altitude_msl),
            icon = Icons.Rounded.Terrain,
            primaryValue = retainedInstrumentValue(msValue?.let {
                stringResource(R.string.value_meters, it.altitudeAboveMeanSeaLevelMeters)
            }, msStatus),
            status = msStatus,
        ),
        InstrumentReadout(
            id = "derived-altitude-takeoff",
            title = stringResource(R.string.instrument_altitude_takeoff),
            icon = Icons.Rounded.Height,
            primaryValue = retainedInstrumentValue(state.altitudeAboveTakeoffMeters?.let {
                stringResource(R.string.value_meters_signed, it)
            }, takeoffStatus),
            status = takeoffStatus,
        ),
        InstrumentReadout(
            id = "flight-target-altitude",
            title = stringResource(R.string.instrument_target_altitude),
            icon = Icons.Rounded.FlightTakeoff,
            primaryValue = targetAltitude?.let {
                stringResource(R.string.value_meters, it)
            } ?: stringResource(R.string.no_value),
            status = if (targetAltitude == null) {
                InstrumentStatusKind.UNAVAILABLE
            } else {
                InstrumentStatusKind.LIVE
            },
        ),
    )
}

/** Four independently labelled physical rangefinder channels; one failure never masks another. */
@Composable
private fun rangefinderInstruments(state: DashboardUiState): List<InstrumentReadout> =
    RangefinderRole.entries.map { role ->
        val sensor = state.boardTelemetry.rangefinder(role)
        val status = sensorStatusKind(sensor)
        InstrumentReadout(
            id = "vl53l0x-${role.name.lowercase()}",
            title = stringResource(
                when (role) {
                    RangefinderRole.GROUND -> R.string.instrument_range_ground
                    RangefinderRole.UP -> R.string.instrument_range_up
                    RangefinderRole.FRONT_LEFT -> R.string.instrument_range_front_left
                    RangefinderRole.FRONT_RIGHT -> R.string.instrument_range_front_right
                },
            ),
            icon = when (role) {
                RangefinderRole.GROUND,
                RangefinderRole.UP -> Icons.Rounded.SwapVert
                RangefinderRole.FRONT_LEFT,
                RangefinderRole.FRONT_RIGHT -> Icons.Rounded.Explore
            },
            primaryValue = retainedInstrumentValue(
                sensor.lastVl53l0xValue()?.distanceMillimeters?.let {
                    stringResource(R.string.value_millimeters, it)
                },
                status,
            ),
            status = status,
        )
    }

/**
 * Runs the retainedInstrumentValue operation.
 */
@Composable
private fun retainedInstrumentValue(
    formattedValue: String?,
    status: InstrumentStatusKind,
): String = when {
    formattedValue == null -> stringResource(R.string.no_value)
    status == InstrumentStatusKind.LIVE -> formattedValue
    else -> stringResource(R.string.last_valid_value, formattedValue)
}

/**
 * Runs the InstrumentRow operation.
 */
@Composable
private fun InstrumentRow(instruments: List<InstrumentReadout>) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        instruments.forEach { instrument ->
            key(instrument.id) {
                InstrumentCard(instrument, Modifier.weight(1f))
            }
        }
    }
}

/**
 * Runs the InstrumentCard operation.
 */
@Composable
private fun InstrumentCard(readout: InstrumentReadout, modifier: Modifier = Modifier) {
    val description = stringResource(
        R.string.compact_instrument_accessibility,
        readout.title,
        readout.primaryValue,
        statusLabel(readout.status),
    )
    Surface(
        modifier = modifier
            .heightIn(min = 96.dp)
            .semantics(mergeDescendants = true) { contentDescription = description },
        color = CockpitSurface,
        shape = DashboardCardShape,
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = readout.icon,
                    contentDescription = null,
                    tint = statusColor(readout.status),
                    modifier = Modifier.size(22.dp),
                )
                StatusDot(readout.status)
            }
            Text(
                text = readout.title,
                style = MaterialTheme.typography.labelMedium,
                color = CockpitMuted,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = readout.primaryValue,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Black,
                color = statusColor(readout.status),
                textAlign = TextAlign.Center,
                lineHeight = 20.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Runs the StatusPill operation.
 */
@Composable
private fun StatusPill(text: String, kind: InstrumentStatusKind) {
    Surface(
        color = statusColor(kind).copy(alpha = .18f),
        contentColor = statusColor(kind),
        shape = CircleShape,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(kind)
            Text(text, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * Runs the StatusDot operation.
 */
@Composable
private fun StatusDot(kind: InstrumentStatusKind) {
    Box(
        Modifier
            .size(8.dp)
            .background(statusColor(kind), CircleShape)
    )
}

/**
 * Runs the statusColor operation.
 */
@Composable
private fun statusColor(kind: InstrumentStatusKind): Color = when (kind) {
    InstrumentStatusKind.LIVE -> ClearGreen
    InstrumentStatusKind.DEGRADED,
    InstrumentStatusKind.STALE,
    InstrumentStatusKind.LOADING,
    InstrumentStatusKind.NO_RESPONSE,
    InstrumentStatusKind.OUT_OF_RANGE,
    InstrumentStatusKind.SIGNAL_FAILURE -> WarningAmber
    InstrumentStatusKind.INVALID,
    InstrumentStatusKind.ERROR -> CriticalRed
    InstrumentStatusKind.NOT_CONNECTED,
    InstrumentStatusKind.NOT_PRESENT,
    InstrumentStatusKind.UNAVAILABLE,
    InstrumentStatusKind.INACTIVE -> UnknownGrey
}

/**
 * Runs the statusLabel operation.
 */
@Composable
private fun statusLabel(kind: InstrumentStatusKind): String = stringResource(
    when (kind) {
        InstrumentStatusKind.LIVE -> R.string.status_live
        InstrumentStatusKind.DEGRADED -> R.string.status_accuracy_warning
        InstrumentStatusKind.STALE -> R.string.status_stale
        InstrumentStatusKind.LOADING -> R.string.status_loading
        InstrumentStatusKind.NOT_CONNECTED -> R.string.status_not_connected
        InstrumentStatusKind.NOT_PRESENT -> R.string.status_not_present
        InstrumentStatusKind.UNAVAILABLE -> R.string.status_unavailable
        InstrumentStatusKind.NO_RESPONSE -> R.string.status_no_response
        InstrumentStatusKind.OUT_OF_RANGE -> R.string.status_out_of_range
        InstrumentStatusKind.SIGNAL_FAILURE -> R.string.status_signal_failure
        InstrumentStatusKind.INVALID -> R.string.status_invalid
        InstrumentStatusKind.ERROR -> R.string.status_error
        InstrumentStatusKind.INACTIVE -> R.string.status_inactive
    }
)

/**
 * Runs the SensorState operation.
 */
private fun SensorState<ir.hrka.shahbaz.hardwareconnection.Sht30Telemetry>.lastShtValue() = when (this) {
    is SensorState.Available -> sample.value
    is SensorState.Stale -> lastSample?.value
    is SensorState.Failed -> lastSample?.value
    else -> null
}

/**
 * Runs the SensorState operation.
 */
private fun SensorState<ir.hrka.shahbaz.hardwareconnection.Ms5611Telemetry>.lastMsValue() = when (this) {
    is SensorState.Available -> sample.value
    is SensorState.Stale -> lastSample?.value
    is SensorState.Failed -> lastSample?.value
    else -> null
}

/** Retains only the last schema-valid and optically valid range sample for warning states. */
private fun SensorState<Vl53l0xTelemetry>.lastVl53l0xValue() = when (this) {
    is SensorState.Available -> sample.value
    is SensorState.Stale -> lastSample?.value
    is SensorState.Failed -> lastSample?.value
    else -> null
}

/**
 * Runs the DashboardMapPane operation.
 */
@Composable
private fun DashboardMapPane(state: DashboardUiState, modifier: Modifier = Modifier) {
    val plan = state.flightPlan
    Surface(
        modifier = modifier
            .padding(top = 8.dp)
            .fillMaxSize(),
        color = Color(0xFFE6ECE8),
        shape = DashboardCardShape,
        border = BorderStroke(1.dp, CockpitMuted.copy(alpha = .35f)),
    ) {
        Box(Modifier.fillMaxSize()) {
            if (plan == null) {
                MapLocalError(
                    text = stringResource(R.string.flight_plan_missing_map),
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                DashboardRouteMap(
                    origin = plan.origin,
                    destination = plan.destination,
                    isOnline = state.isOnline,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/**
 * Documents the DashboardMapLoadState type and the role it plays in this module.
 */
internal enum class DashboardMapLoadState {
    LOADING,
    READY,
    OFFLINE,
    ERROR,
}

/**
 * Runs the dashboardMapLoadState operation.
 */
internal fun dashboardMapLoadState(
    isOnline: Boolean,
    styleAttached: Boolean,
    mapLoaded: Boolean,
    mapLoadTimedOut: Boolean,
    mapLoadFailed: Boolean,
): DashboardMapLoadState = when {
    !isOnline -> DashboardMapLoadState.OFFLINE
    styleAttached || mapLoaded -> DashboardMapLoadState.READY
    mapLoadTimedOut || mapLoadFailed -> DashboardMapLoadState.ERROR
    else -> DashboardMapLoadState.LOADING
}

/**
 * Runs the DashboardRouteMap operation.
 */
@Composable
private fun DashboardRouteMap(
    origin: GeoCoordinate,
    destination: GeoCoordinate,
    isOnline: Boolean,
    modifier: Modifier = Modifier,
) {
    var mapInstanceKey by rememberSaveable { mutableIntStateOf(0) }

    key(mapInstanceKey) {
        DashboardRouteMapInstance(
            origin = origin,
            destination = destination,
            isOnline = isOnline,
            onRetry = { mapInstanceKey += 1 },
            modifier = modifier,
        )
    }
}

/**
 * Runs the DashboardRouteMapInstance operation.
 */
@Composable
private fun DashboardRouteMapInstance(
    origin: GeoCoordinate,
    destination: GeoCoordinate,
    isOnline: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cameraState = rememberCameraState()
    val styleState = rememberStyleState()
    var mapLoaded by remember { mutableStateOf(false) }
    var mapLoadTimedOut by remember { mutableStateOf(false) }
    var mapLoadFailed by remember { mutableStateOf(false) }
    var mapSize by remember { mutableStateOf(IntSize.Zero) }
    val styleAttached = styleState.sources.isNotEmpty()
    val mapReady = styleAttached || mapLoaded
    val mapContentPadding = PaddingValues(
        start = DASHBOARD_MAP_CAMERA_PADDING,
        top = DASHBOARD_MAP_CAMERA_PADDING,
        end = DASHBOARD_MAP_CAMERA_PADDING,
        bottom = DASHBOARD_MAP_BOTTOM_CONTENT_PADDING,
    )
    val loadState = dashboardMapLoadState(
        isOnline = isOnline,
        styleAttached = styleAttached,
        mapLoaded = mapLoaded,
        mapLoadTimedOut = mapLoadTimedOut,
        mapLoadFailed = mapLoadFailed,
    )
    val mapDescription = stringResource(R.string.dashboard_map_description)

    LaunchedEffect(mapReady, isOnline, mapLoadFailed) {
        mapLoadTimedOut = false
        if (!mapReady && isOnline && !mapLoadFailed) {
            delay(DASHBOARD_MAP_LOAD_TIMEOUT_MILLIS)
            if (!mapReady && !mapLoadFailed) {
                mapLoadTimedOut = true
                FlightBlackBox.record(
                    type = FbbEventType.WARNING,
                    description = "Dashboard map style attachment timed out",
                    metadata = mapOf(
                        "isOnline" to isOnline,
                        "timeoutMs" to DASHBOARD_MAP_LOAD_TIMEOUT_MILLIS,
                    ),
                )
            }
        }
    }

    LaunchedEffect(styleAttached) {
        if (styleAttached) {
            FlightBlackBox.record(
                type = FbbEventType.UI,
                description = "Dashboard map base style attached",
                metadata = mapOf("sourceCount" to styleState.sources.size),
            )
        }
    }

    LaunchedEffect(origin, destination, mapSize) {
        if (mapSize.width <= 0 || mapSize.height <= 0) return@LaunchedEffect

        val distance = wgs84GeodesicDistanceMeters(origin, destination)
        if (distance < DASHBOARD_MAP_MIN_BOUNDS_DISTANCE_METERS) {
            cameraState.position = CameraPosition(
                target = destination.toPosition(),
                zoom = DASHBOARD_MAP_CLOSE_ROUTE_ZOOM,
                padding = mapContentPadding,
            )
        } else {
            cameraState.jumpTo(
                boundingBox = BoundingBox(
                    west = minOf(origin.longitude, destination.longitude),
                    south = minOf(origin.latitude, destination.latitude),
                    east = maxOf(origin.longitude, destination.longitude),
                    north = maxOf(origin.latitude, destination.latitude),
                ),
                padding = mapContentPadding,
            )
        }
    }

    Box(
        modifier = modifier
            .background(Color(0xFFE6ECE8))
            .onSizeChanged { mapSize = it }
            .semantics {
                contentDescription = mapDescription
            },
    ) {
        MaplibreMap(
            modifier = Modifier.fillMaxSize(),
            baseStyle = BaseStyle.Uri(DASHBOARD_MAP_STYLE_URL),
            cameraState = cameraState,
            styleState = styleState,
            options = MapOptions(
                renderOptions = RenderOptions(
                    renderMode = RenderOptions.RenderMode.TextureView,
                    foregroundLoadColor = Color(0xFFE6ECE8),
                ),
                gestureOptions = GestureOptions(
                    isRotateEnabled = false,
                    isScrollEnabled = true,
                    isTiltEnabled = false,
                    isZoomEnabled = true,
                ),
                ornamentOptions = OrnamentOptions(
                    padding = PaddingValues(
                        start = 8.dp,
                        top = 8.dp,
                        end = 8.dp,
                        bottom = DASHBOARD_MAP_ORNAMENT_BOTTOM_PADDING,
                    ),
                    isLogoEnabled = true,
                    logoAlignment = Alignment.BottomStart,
                    isAttributionEnabled = true,
                    attributionAlignment = Alignment.BottomEnd,
                    isCompassEnabled = false,
                    isScaleBarEnabled = false,
                ),
            ),
            onMapLoadFinished = {
                mapLoaded = true
                mapLoadTimedOut = false
                mapLoadFailed = false
                FlightBlackBox.record(
                    type = FbbEventType.UI,
                    description = "Dashboard map finished loading",
                )
            },
            onMapLoadFailed = { reason ->
                mapLoaded = false
                mapLoadFailed = true
                FlightBlackBox.record(
                    type = FbbEventType.WARNING,
                    description = "Dashboard map reported a load failure",
                    metadata = mapOf("reason" to (reason ?: "unknown")),
                )
            },
        ) {
            DashboardMapOverlays(
                origin = origin,
                destination = destination,
            )
        }

        DashboardMapStatusPresentation(
            state = loadState,
            mapAlreadyLoaded = mapReady,
            onRetry = onRetry,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}

/**
 * Runs the DashboardMapOverlays operation.
 */
@Composable
private fun DashboardMapOverlays(
    origin: GeoCoordinate,
    destination: GeoCoordinate,
) {
    val originPosition = origin.toPosition()
    val destinationPosition = destination.toPosition()
    val midpoint = runCatching {
        sphericalMidpoint(origin, destination)
    }.getOrElse { origin }.toPosition()
    val distanceLabel = formatDistance(wgs84GeodesicDistanceMeters(origin, destination))
    val routeSource = rememberGeoJsonSource(
        data = lineData(originPosition, destinationPosition),
        options = DashboardDynamicGeoJsonOptions,
    )
    val originSource = rememberGeoJsonSource(
        data = pointData(originPosition),
        options = DashboardDynamicGeoJsonOptions,
    )
    val destinationSource = rememberGeoJsonSource(
        data = pointData(destinationPosition),
        options = DashboardDynamicGeoJsonOptions,
    )
    val distanceSource = rememberGeoJsonSource(
        data = pointData(midpoint),
        options = DashboardDynamicGeoJsonOptions,
    )

    LineLayer(
        id = "shahbaz-dashboard-route",
        source = routeSource,
        color = const(DashboardRouteColor),
        width = const(DashboardRouteLineWidth),
        cap = const(LineCap.Round),
        join = const(LineJoin.Round),
    )
    CircleLayer(
        id = "shahbaz-dashboard-origin",
        source = originSource,
        color = const(Color(0xFF1976D2)),
        radius = const(9.dp),
        strokeColor = const(Color.White),
        strokeWidth = const(4.dp),
    )
    CircleLayer(
        id = "shahbaz-dashboard-destination",
        source = destinationSource,
        color = const(Color(0xFFD32F2F)),
        radius = const(10.dp),
        strokeColor = const(Color.White),
        strokeWidth = const(3.dp),
    )
    SymbolLayer(
        id = "shahbaz-dashboard-distance",
        source = distanceSource,
        textField = format(span(distanceLabel)),
        textSize = const(14.sp),
        textColor = const(DashboardRouteColor),
        textHaloColor = const(Color.White),
        textHaloWidth = const(2.dp),
        textAllowOverlap = const(true),
        textIgnorePlacement = const(true),
    )
}

/**
 * Runs the BoxScope operation.
 */
@Composable
private fun BoxScope.DashboardMapStatusPresentation(
    state: DashboardMapLoadState,
    mapAlreadyLoaded: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state == DashboardMapLoadState.READY) return

    val compactOfflineNotice = state == DashboardMapLoadState.OFFLINE && mapAlreadyLoaded
    val title = when (state) {
        DashboardMapLoadState.LOADING -> stringResource(R.string.dashboard_map_loading)
        DashboardMapLoadState.OFFLINE -> stringResource(R.string.dashboard_map_offline_title)
        DashboardMapLoadState.ERROR -> stringResource(R.string.dashboard_map_error_title)
        DashboardMapLoadState.READY -> return
    }
    val message = when (state) {
        DashboardMapLoadState.OFFLINE -> stringResource(R.string.dashboard_map_offline_message)
        DashboardMapLoadState.ERROR -> stringResource(R.string.dashboard_map_error_message)
        DashboardMapLoadState.LOADING,
        DashboardMapLoadState.READY -> null
    }

    if (!compactOfflineNotice) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.24f)),
            contentAlignment = Alignment.Center,
        ) {
            DashboardMapStatusCard(
                state = state,
                title = title,
                message = message,
                onRetry = onRetry,
                modifier = modifier,
            )
        }
    } else {
        DashboardMapStatusCard(
            state = state,
            title = title,
            message = null,
            onRetry = onRetry,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(8.dp),
        )
    }
}

/**
 * Runs the DashboardMapStatusCard operation.
 */
@Composable
private fun DashboardMapStatusCard(
    state: DashboardMapLoadState,
    title: String,
    message: String?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .padding(12.dp)
            .widthIn(max = 320.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        shape = DashboardCardShape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 4.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state == DashboardMapLoadState.LOADING) {
                CircularProgressIndicator()
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            if (message != null) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                )
            }
            if (state == DashboardMapLoadState.OFFLINE || state == DashboardMapLoadState.ERROR) {
                Button(onClick = onRetry) {
                    Text(stringResource(R.string.dashboard_map_retry))
                }
            }
        }
    }
}

/**
 * Runs the pointData operation.
 */
private fun pointData(position: Position?): GeoJsonData {
    val features: List<Feature<Point, JsonObject?>> = position?.let {
        listOf(Feature(geometry = Point(it), properties = null))
    }.orEmpty()
    return GeoJsonData.Features(FeatureCollection(features))
}

/**
 * Runs the lineData operation.
 */
private fun lineData(start: Position?, end: Position?): GeoJsonData {
    val features: List<Feature<LineString, JsonObject?>> = if (start != null && end != null) {
        listOf(Feature(geometry = LineString(start, end), properties = null))
    } else {
        emptyList()
    }
    return GeoJsonData.Features(FeatureCollection(features))
}

/**
 * Runs the GeoCoordinate operation.
 */
private fun GeoCoordinate.toPosition(): Position = Position(
    longitude = longitude,
    latitude = latitude,
)

/**
 * Runs the MapLocalError operation.
 */
@Composable
private fun MapLocalError(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.padding(16.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = DashboardCardShape,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Presents mission state and dispatches operator intent without bypassing autopilot safety gates.
 */
@Composable
private fun AutopilotControls(
    snapshot: AutopilotSnapshot?,
    onStartFlight: () -> Unit,
    onAbortFlight: () -> Unit,
    onEmergencyStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val presentation = autopilotControlPresentation(snapshot)
    val phaseText = autopilotPhaseText(presentation.phase)
    val emergencyStopDescription = stringResource(R.string.emergency_stop_flight)
    val issueText = when {
        snapshot == null -> stringResource(R.string.autopilot_status_missing_detail)
        presentation.firstIssueMessage == null -> null
        presentation.additionalIssueCount == 0 -> presentation.firstIssueMessage
        else -> stringResource(
            R.string.autopilot_issue_with_more,
            presentation.firstIssueMessage,
            presentation.additionalIssueCount,
        )
    }

    Column(
        modifier = modifier.widthIn(max = 360.dp),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        issueText?.let { text ->
            Surface(
                modifier = Modifier.semantics {
                    liveRegion = LiveRegionMode.Polite
                    contentDescription = text
                },
                color = CockpitSurface.copy(alpha = .96f),
                contentColor = WarningAmber,
                shape = DashboardCardShape,
                shadowElevation = 4.dp,
            ) {
                Text(
                    text = text,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (presentation.showEmergencyStop) {
                Button(
                    onClick = onEmergencyStop,
                    modifier = Modifier.semantics {
                        contentDescription = "$emergencyStopDescription. $phaseText"
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CriticalRed,
                        contentColor = Color.White,
                    ),
                ) {
                    Text(
                        text = stringResource(R.string.emergency_stop),
                        fontWeight = FontWeight.Black,
                    )
                }
            }

            when (presentation.primaryControl) {
                AutopilotPrimaryControl.START -> OutlinedButton(
                    onClick = onStartFlight,
                    modifier = Modifier.semantics { stateDescription = phaseText },
                    border = BorderStroke(1.dp, WarningAmber),
                ) {
                    Text(
                        text = stringResource(R.string.start_flight),
                        color = CockpitOnSurface,
                        fontWeight = FontWeight.Black,
                    )
                }

                AutopilotPrimaryControl.ABORT -> OutlinedButton(
                    onClick = onAbortFlight,
                    modifier = Modifier.semantics { stateDescription = phaseText },
                    border = BorderStroke(1.dp, WarningAmber),
                ) {
                    Text(
                        text = stringResource(R.string.abort_flight),
                        color = CockpitOnSurface,
                        fontWeight = FontWeight.Black,
                    )
                }

                AutopilotPrimaryControl.STATUS -> Surface(
                    modifier = Modifier.semantics {
                        disabled()
                        stateDescription = phaseText
                        contentDescription = phaseText
                    },
                    color = CockpitSurface.copy(alpha = .96f),
                    contentColor = CockpitOnSurface,
                    shape = DashboardCardShape,
                    border = BorderStroke(1.dp, CockpitMuted.copy(alpha = .6f)),
                    shadowElevation = 4.dp,
                ) {
                    Text(
                        text = phaseText.uppercase(),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        textAlign = TextAlign.Center,
                        fontSize = 14.sp,
                        lineHeight = 16.sp,
                        fontWeight = FontWeight.Black,
                    )
                }
            }
        }
    }
}

/** Human-readable status for each autonomous mission phase. */
@Composable
private fun autopilotPhaseText(phase: AutopilotPhase?): String = stringResource(
    when (phase) {
        AutopilotPhase.STANDBY -> R.string.autopilot_phase_standby
        AutopilotPhase.PREFLIGHT -> R.string.autopilot_phase_preflight
        AutopilotPhase.ARMING -> R.string.autopilot_phase_arming
        AutopilotPhase.TAKEOFF -> R.string.autopilot_phase_takeoff
        AutopilotPhase.CRUISE -> R.string.autopilot_phase_cruise
        AutopilotPhase.LANDING -> R.string.autopilot_phase_landing
        AutopilotPhase.RETURN_CLIMB -> R.string.autopilot_phase_return_climb
        AutopilotPhase.RETURNING -> R.string.autopilot_phase_returning
        AutopilotPhase.DISARMING -> R.string.autopilot_phase_disarming
        AutopilotPhase.COMPLETED -> R.string.autopilot_phase_completed
        AutopilotPhase.ABORTED -> R.string.autopilot_phase_aborted
        AutopilotPhase.FAILED -> R.string.autopilot_phase_failed
        AutopilotPhase.EMERGENCY_STOPPED -> R.string.autopilot_phase_emergency_stopped
        null -> R.string.autopilot_phase_unavailable
    },
)

/**
 * Exposes the INSTRUMENT_WEIGHT value.
 */
private const val INSTRUMENT_WEIGHT = 0.70f
/**
 * Exposes the MAP_WEIGHT value.
 */
private const val MAP_WEIGHT = 0.30f

/**
 * Exposes the DashboardCardShape value.
 */
private val DashboardCardShape = RoundedCornerShape(8.dp)
/**
 * Exposes the DashboardDynamicGeoJsonOptions value.
 */
private val DashboardDynamicGeoJsonOptions = GeoJsonOptions(synchronousUpdate = true)
/**
 * Exposes the DashboardRouteColor value.
 */
private val DashboardRouteColor = Color.Black
/**
 * Exposes the DashboardRouteLineWidth value.
 */
private val DashboardRouteLineWidth = 2.8.dp
/**
 * Exposes the DASHBOARD_MAP_CAMERA_PADDING value.
 */
private val DASHBOARD_MAP_CAMERA_PADDING = 32.dp
/**
 * Exposes the DASHBOARD_MAP_BOTTOM_CONTENT_PADDING value.
 */
private val DASHBOARD_MAP_BOTTOM_CONTENT_PADDING = 96.dp
/**
 * Exposes the DASHBOARD_MAP_ORNAMENT_BOTTOM_PADDING value.
 */
private val DASHBOARD_MAP_ORNAMENT_BOTTOM_PADDING = 96.dp

/**
 * Exposes the DASHBOARD_MAP_STYLE_URL value.
 */
private const val DASHBOARD_MAP_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
/**
 * Exposes the DASHBOARD_MAP_LOAD_TIMEOUT_MILLIS value.
 */
private const val DASHBOARD_MAP_LOAD_TIMEOUT_MILLIS = 15_000L
/**
 * Exposes the DASHBOARD_MAP_CLOSE_ROUTE_ZOOM value.
 */
private const val DASHBOARD_MAP_CLOSE_ROUTE_ZOOM = 18.0
/**
 * Exposes the DASHBOARD_MAP_MIN_BOUNDS_DISTANCE_METERS value.
 */
private const val DASHBOARD_MAP_MIN_BOUNDS_DISTANCE_METERS = 2.0

/**
 * Exposes the CockpitBackground value.
 */
private val CockpitBackground = Color(0xFF0D151C)
/**
 * Exposes the CockpitSurface value.
 */
private val CockpitSurface = Color(0xFF17232D)
/**
 * Exposes the CockpitOnSurface value.
 */
private val CockpitOnSurface = Color(0xFFF1F6F8)
/**
 * Exposes the CockpitMuted value.
 */
private val CockpitMuted = Color(0xFF9FB0BC)
/**
 * Exposes the SkyBlue value.
 */
private val SkyBlue = Color(0xFF3277A8)
/**
 * Exposes the GroundBrown value.
 */
private val GroundBrown = Color(0xFF8A5B36)
/**
 * Exposes the ClearGreen value.
 */
private val ClearGreen = Color(0xFF35C978)
/**
 * Exposes the WarningAmber value.
 */
private val WarningAmber = Color(0xFFF4C247)
/**
 * Exposes the CriticalRed value.
 */
private val CriticalRed = Color(0xFFD92D3E)
/**
 * Exposes the UnknownGrey value.
 */
private val UnknownGrey = Color(0xFF7B8790)
/**
 * Exposes the UnknownGreyDark value.
 */
private val UnknownGreyDark = Color(0xFF4A555D)
