/** Cockpit-style, failure-explicit presentation for a confirmed Shahbaz flight plan. */
package ir.hrka.shahbaz.feature.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shahbaz.flightblackbox.FbbEventType
import com.shahbaz.flightblackbox.FbbPersistence
import com.shahbaz.flightblackbox.FlightBlackBox
import ir.hrka.compass.CompassAccuracyLevel
import ir.hrka.compass.CompassDirection
import ir.hrka.compass.CompassReading
import ir.hrka.compass.NorthReference
import ir.hrka.shahbaz.core.map.FlightRouteMap
import ir.hrka.shahbaz.core.map.FlightRouteMapState
import ir.hrka.shahbaz.feature.dashboard.impl.R
import ir.hrka.shahbaz.hardwareconnection.BoardConnectionState
import ir.hrka.shahbaz.hardwareconnection.BoardDisconnectReason
import ir.hrka.shahbaz.hardwareconnection.SensorErrorCode
import ir.hrka.shahbaz.hardwareconnection.SensorState
import ir.hrka.shahbaz.hardwareconnection.SensorUnavailableReason
import java.util.Locale
import kotlin.math.min

/** Exact adaptive pane decision used by the 70/30 dashboard layout. */
internal enum class DashboardPaneLayout { PORTRAIT, LANDSCAPE }

internal fun dashboardPaneLayout(width: Float, height: Float): DashboardPaneLayout {
    require(width >= 0f && height >= 0f)
    return if (width > height) DashboardPaneLayout.LANDSCAPE else DashboardPaneLayout.PORTRAIT
}

/** Recovery action exposed by the blocking board-connection gate. */
internal enum class ConnectionGateAction { NONE, REQUEST_PERMISSION, RETRY }

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

internal fun shouldBlockDashboard(state: BoardConnectionState): Boolean =
    state !is BoardConnectionState.Ready

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
    INVALID,
    ERROR,
    INACTIVE,
}

internal fun sensorStatusKind(state: SensorState<*>): InstrumentStatusKind = when (state) {
    is SensorState.Available -> InstrumentStatusKind.LIVE
    is SensorState.Stale -> InstrumentStatusKind.STALE
    SensorState.AwaitingFirstSample -> InstrumentStatusKind.LOADING
    is SensorState.Unavailable -> when (state.reason) {
        SensorUnavailableReason.BOARD_DISCONNECTED -> InstrumentStatusKind.NOT_CONNECTED
        SensorUnavailableReason.TELEMETRY_NOT_STARTED -> InstrumentStatusKind.LOADING
        SensorUnavailableReason.SENSOR_REPORTED_OFFLINE -> InstrumentStatusKind.NOT_PRESENT
    }
    is SensorState.Failed -> when (state.error.code) {
        SensorErrorCode.INVALID_PAYLOAD,
        SensorErrorCode.INVALID_VALIDITY,
        SensorErrorCode.OUT_OF_RANGE -> InstrumentStatusKind.INVALID
        SensorErrorCode.NO_RESPONSE -> InstrumentStatusKind.NO_RESPONSE
        SensorErrorCode.NOT_FRESH -> InstrumentStatusKind.STALE
        SensorErrorCode.SENSOR_OFFLINE -> InstrumentStatusKind.NOT_PRESENT
        SensorErrorCode.HEALTH_FAULT -> InstrumentStatusKind.ERROR
    }
}

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
 * The map and instruments occupy exact 30/70 weights in either orientation. The start control is
 * intentionally disabled and has no click or long-click behavior in this implementation stage.
 */
@Composable
fun DashboardScreen(
    state: DashboardUiState,
    onRequestUsbPermission: () -> Unit,
    onRetryBoardConnection: () -> Unit,
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
        modifier = modifier
            .fillMaxSize()
            .systemBarsPadding(),
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

                StartFlightPlaceholder(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 18.dp),
                )
            }
        }
    }
}

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
            shape = RoundedCornerShape(28.dp),
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

@Composable
private fun disconnectReasonText(reason: BoardDisconnectReason): String = stringResource(
    when (reason) {
        BoardDisconnectReason.USB_DETACHED -> R.string.disconnect_usb_detached
        BoardDisconnectReason.APP_STOPPED -> R.string.disconnect_app_stopped
        BoardDisconnectReason.TRANSPORT_CLOSED -> R.string.disconnect_transport_closed
    }
)

@Composable
private fun InstrumentPane(state: DashboardUiState, modifier: Modifier = Modifier) {
    BoxWithConstraints(
        modifier = modifier.background(CockpitBackground),
    ) {
        val cardColumns = instrumentColumnCount(maxWidth.value)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 14.dp, top = 12.dp, end = 14.dp, bottom = 112.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DashboardHeader(state)
            AttitudePanel(state.phoneSensors.orientation)
            InstrumentGrid(
                instruments = dashboardInstruments(state),
                columns = cardColumns,
            )
        }
    }
}

internal fun instrumentColumnCount(availableWidthDp: Float): Int = when {
    availableWidthDp >= 900f -> 3
    availableWidthDp >= 520f -> 2
    else -> 1
}

@Composable
private fun DashboardHeader(state: DashboardUiState) {
    val plan = state.flightPlan
    val ready = state.boardConnection as BoardConnectionState.Ready
    val advisoryIssueMask = ready.deviceInfo.boardValidationIssueMask
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.dashboard_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Black,
            )
            Text(
                text = if (plan == null) {
                    stringResource(R.string.flight_plan_missing)
                } else {
                    stringResource(
                        R.string.flight_target_format,
                        plan.targetAltitudeAboveOriginMeters,
                    )
                },
                color = CockpitMuted,
                style = MaterialTheme.typography.bodySmall,
            )
            if (advisoryIssueMask != 0L) {
                Text(
                    text = stringResource(
                        R.string.board_advisory_issue_mask,
                        advisoryIssueMask,
                    ),
                    color = WarningAmber,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        StatusPill(
            text = stringResource(
                if (advisoryIssueMask == 0L) {
                    R.string.board_ready_status
                } else {
                    R.string.board_ready_with_advisories_status
                }
            ),
            kind = boardReadyStatusKind(state.boardConnection),
        )
    }
}

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
    val northReference = if (reading?.trueAzimuthDegrees != null) {
        NorthReference.TRUE
    } else {
        NorthReference.MAGNETIC
    }
    val heading = reading?.azimuth(northReference)
    val direction = reading?.nearestDirection(northReference)
    val cardinalDeviations = reading?.let { cardinalAngles(it, northReference) }.orEmpty()
    val cardinalDescriptions = mutableListOf<String>()
    for (angle in cardinalDeviations) {
        cardinalDescriptions += stringResource(
            R.string.cardinal_angle_accessibility,
            directionLabel(angle.direction),
            angle.signedDegrees,
            angle.absoluteDegrees,
        )
    }
    val description = if (reading == null) {
        stringResource(R.string.attitude_unavailable_description, statusLabel(status))
    } else {
        stringResource(
            R.string.attitude_description,
            heading ?: reading.magneticAzimuthDegrees,
            reading.pitchDegrees,
            reading.rollDegrees,
            directionLabel(direction),
            statusLabel(status),
        ) + " " + cardinalDescriptions.joinToString(separator = ". ")
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = description },
        color = CockpitSurface,
        shape = RoundedCornerShape(22.dp),
        tonalElevation = 3.dp,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.attitude_heading_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    SourceLabel(stringResource(R.string.source_phone_orientation), status)
                }
                StatusPill(statusLabel(status), status)
            }
            Text(
                text = orientationDetail(orientation),
                color = statusColor(status),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AttitudeIndicator(
                    pitchDegrees = reading?.pitchDegrees ?: 0f,
                    rollDegrees = reading?.rollDegrees ?: 0f,
                    hasReading = reading != null,
                    modifier = Modifier.weight(1f),
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.compass_heading),
                        color = CockpitMuted,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Text(
                        text = if (heading == null) {
                            stringResource(R.string.no_value)
                        } else {
                            stringResource(
                                R.string.compass_heading_value,
                                heading,
                                directionLabel(direction),
                            )
                        },
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Black,
                        color = statusColor(status),
                    )
                    AxisValue(
                        label = stringResource(R.string.axis_x_pitch),
                        value = reading?.let {
                            stringResource(R.string.value_signed_degrees, it.pitchDegrees)
                        } ?: stringResource(R.string.no_value),
                    )
                    AxisValue(
                        label = stringResource(R.string.axis_y_roll),
                        value = reading?.let {
                            stringResource(R.string.value_signed_degrees, it.rollDegrees)
                        } ?: stringResource(R.string.no_value),
                    )
                    AxisValue(
                        label = stringResource(R.string.axis_z_yaw),
                        value = heading?.let { stringResource(R.string.value_degrees, it) }
                            ?: stringResource(R.string.no_value),
                    )
                    Text(
                        text = stringResource(
                            R.string.orientation_sensor_detail,
                            reading?.sensorSource?.name?.humanize()
                                ?: stringResource(R.string.no_value),
                            reading?.accuracy?.level?.name?.humanize()
                                ?: stringResource(R.string.no_value),
                            if (northReference == NorthReference.TRUE) {
                                stringResource(R.string.true_north)
                            } else {
                                stringResource(R.string.magnetic_north)
                            },
                        ),
                        color = CockpitMuted,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            Text(
                text = stringResource(R.string.cardinal_angles_title),
                color = CockpitMuted,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CardinalDirections.forEach { cardinal ->
                    val angle = cardinalDeviations.firstOrNull { it.direction == cardinal }
                    CardinalAngleValue(
                        label = directionLabel(cardinal),
                        signedDegrees = angle?.signedDegrees,
                        absoluteDegrees = angle?.absoluteDegrees,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Text(
                text = stringResource(R.string.axis_convention),
                color = CockpitMuted,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun orientationDetail(state: PhoneReading<CompassReading>): String = when (state) {
    is PhoneReading.Available -> when (state.value.accuracy.level) {
        CompassAccuracyLevel.UNKNOWN -> stringResource(R.string.compass_accuracy_unknown)
        CompassAccuracyLevel.UNRELIABLE -> stringResource(R.string.compass_accuracy_unreliable)
        CompassAccuracyLevel.LOW -> stringResource(R.string.compass_accuracy_low)
        CompassAccuracyLevel.MEDIUM,
        CompassAccuracyLevel.HIGH -> stringResource(R.string.sample_current)
    }
    else -> phoneDetail(state)
}

@Composable
private fun CardinalAngleValue(
    label: String,
    signedDegrees: Float?,
    absoluteDegrees: Float?,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = CockpitBackground.copy(alpha = .72f),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(label, color = CockpitMuted, fontWeight = FontWeight.Bold)
            Text(
                text = if (signedDegrees == null || absoluteDegrees == null) {
                    stringResource(R.string.no_value)
                } else {
                    stringResource(
                        R.string.cardinal_angle_value,
                        signedDegrees,
                        absoluteDegrees,
                    )
                },
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun AttitudeIndicator(
    pitchDegrees: Float,
    rollDegrees: Float,
    hasReading: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .widthIn(max = 220.dp)
            .aspectRatio(1f),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .border(3.dp, CockpitMuted, CircleShape),
        ) {
            val radius = min(size.width, size.height) / 2f
            val horizonOffset = (pitchDegrees.coerceIn(-45f, 45f) / 45f) * radius * .7f
            val circle = Path().apply {
                addOval(androidx.compose.ui.geometry.Rect(center - Offset(radius, radius), center + Offset(radius, radius)))
            }
            clipPath(circle) {
                drawRect(color = if (hasReading) SkyBlue else UnknownGrey)
                rotate(degrees = -rollDegrees, pivot = center) {
                    drawRect(
                        color = if (hasReading) GroundBrown else UnknownGreyDark,
                        topLeft = Offset(-size.width, center.y + horizonOffset),
                        size = androidx.compose.ui.geometry.Size(size.width * 3f, size.height * 2f),
                    )
                    drawLine(
                        color = Color.White,
                        start = Offset(-size.width, center.y + horizonOffset),
                        end = Offset(size.width * 2f, center.y + horizonOffset),
                        strokeWidth = 3.dp.toPx(),
                    )
                }
                drawLine(
                    color = Color.White,
                    start = Offset(center.x - radius * .38f, center.y),
                    end = Offset(center.x - radius * .08f, center.y),
                    strokeWidth = 4.dp.toPx(),
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = Color.White,
                    start = Offset(center.x + radius * .08f, center.y),
                    end = Offset(center.x + radius * .38f, center.y),
                    strokeWidth = 4.dp.toPx(),
                    cap = StrokeCap.Round,
                )
                drawCircle(Color.White, radius = 3.dp.toPx())
            }
        }
    }
}

@Composable
private fun AxisValue(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = CockpitMuted, style = MaterialTheme.typography.labelMedium)
        Text(value, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
    }
}

private data class InstrumentReadout(
    val id: String,
    val title: String,
    val primaryValue: String,
    val detail: String,
    val source: String,
    val status: InstrumentStatusKind,
)

@Composable
private fun dashboardInstruments(state: DashboardUiState): List<InstrumentReadout> {
    val shtStatus = sensorStatusKind(state.boardTelemetry.sht30)
    val msStatus = sensorStatusKind(state.boardTelemetry.ms5611)
    val shtValue = state.boardTelemetry.sht30.lastShtValue()
    val msValue = state.boardTelemetry.ms5611.lastMsValue()
    val shtDetail = sensorDetail(state.boardTelemetry.sht30)
    val msDetail = sensorDetail(state.boardTelemetry.ms5611)
    val takeoffStatus = when {
        msStatus == InstrumentStatusKind.ERROR -> InstrumentStatusKind.ERROR
        msStatus == InstrumentStatusKind.INVALID -> InstrumentStatusKind.INVALID
        msStatus == InstrumentStatusKind.NO_RESPONSE -> InstrumentStatusKind.NO_RESPONSE
        msStatus == InstrumentStatusKind.NOT_CONNECTED -> InstrumentStatusKind.NOT_CONNECTED
        msStatus == InstrumentStatusKind.NOT_PRESENT -> InstrumentStatusKind.NOT_PRESENT
        state.altitudeAboveTakeoffMeters == null -> InstrumentStatusKind.LOADING
        else -> msStatus
    }
    val takeoffDetails = mutableListOf<String>()
    state.flightPlan?.let {
        takeoffDetails += stringResource(
            R.string.target_altitude_detail,
            it.targetAltitudeAboveOriginMeters,
        )
    }
    if (state.altitudeAboveTakeoffMeters == null) {
        takeoffDetails += stringResource(R.string.baseline_waiting)
    } else {
        takeoffDetails += msDetail
    }

    return listOf(
        InstrumentReadout(
            id = "sht30-temperature",
            title = stringResource(R.string.instrument_temperature),
            primaryValue = retainedInstrumentValue(shtValue?.let {
                stringResource(R.string.value_celsius, it.temperatureCelsius)
            }, shtStatus),
            detail = shtDetail,
            source = stringResource(R.string.source_sht30),
            status = shtStatus,
        ),
        InstrumentReadout(
            id = "sht30-humidity",
            title = stringResource(R.string.instrument_humidity),
            primaryValue = retainedInstrumentValue(shtValue?.let {
                stringResource(R.string.value_percent, it.relativeHumidityPercent)
            }, shtStatus),
            detail = shtDetail,
            source = stringResource(R.string.source_sht30),
            status = shtStatus,
        ),
        InstrumentReadout(
            id = "ms5611-pressure",
            title = stringResource(R.string.instrument_pressure),
            primaryValue = retainedInstrumentValue(msValue?.let {
                stringResource(R.string.value_hectopascal, it.pressurePascal / 100.0)
            }, msStatus),
            detail = msValue?.let {
                listOf(
                    stringResource(
                        R.string.pressure_detail,
                        it.pressurePascal,
                        it.temperatureCelsius,
                    ),
                    msDetail,
                ).joinToString(separator = " · ")
            } ?: msDetail,
            source = stringResource(R.string.source_ms5611),
            status = msStatus,
        ),
        InstrumentReadout(
            id = "ms5611-altitude-msl",
            title = stringResource(R.string.instrument_altitude_msl),
            primaryValue = retainedInstrumentValue(msValue?.let {
                stringResource(R.string.value_meters, it.altitudeAboveMeanSeaLevelMeters)
            }, msStatus),
            detail = msValue?.let {
                listOf(
                    stringResource(R.string.qnh_detail, it.qnhHectopascal),
                    msDetail,
                ).joinToString(separator = " · ")
            } ?: msDetail,
            source = stringResource(R.string.source_qnh_altitude),
            status = msStatus,
        ),
        InstrumentReadout(
            id = "derived-altitude-takeoff",
            title = stringResource(R.string.instrument_altitude_takeoff),
            primaryValue = retainedInstrumentValue(state.altitudeAboveTakeoffMeters?.let {
                stringResource(R.string.value_meters_signed, it)
            }, takeoffStatus),
            detail = takeoffDetails.joinToString(separator = " · "),
            source = stringResource(R.string.source_derived_pressure),
            status = takeoffStatus,
        ),
    )
}

@Composable
private fun retainedInstrumentValue(
    formattedValue: String?,
    status: InstrumentStatusKind,
): String = when {
    formattedValue == null -> stringResource(R.string.no_value)
    status == InstrumentStatusKind.LIVE -> formattedValue
    else -> stringResource(R.string.last_valid_value, formattedValue)
}

@Composable
private fun InstrumentGrid(instruments: List<InstrumentReadout>, columns: Int) {
    instruments.chunked(columns).forEach { rowItems ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            rowItems.forEach { instrument ->
                key(instrument.id) {
                    InstrumentCard(instrument, Modifier.weight(1f))
                }
            }
            repeat(columns - rowItems.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun InstrumentCard(readout: InstrumentReadout, modifier: Modifier = Modifier) {
    val description = stringResource(
        R.string.instrument_accessibility,
        readout.title,
        readout.primaryValue,
        readout.source,
        statusLabel(readout.status),
        readout.detail,
    )
    Surface(
        modifier = modifier
            .heightIn(min = 132.dp)
            .semantics(mergeDescendants = true) { contentDescription = description },
        color = CockpitSurface,
        shape = RoundedCornerShape(18.dp),
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = readout.title,
                    color = CockpitMuted,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                StatusDot(readout.status)
            }
            Text(
                text = readout.primaryValue,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Black,
                color = statusColor(readout.status),
            )
            Text(
                text = readout.detail,
                color = CockpitMuted,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
            )
            SourceLabel(readout.source, readout.status)
        }
    }
}

@Composable
private fun SourceLabel(source: String, status: InstrumentStatusKind) {
    Text(
        text = stringResource(R.string.source_status_format, source, statusLabel(status)),
        color = CockpitMuted,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Medium,
    )
}

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

@Composable
private fun StatusDot(kind: InstrumentStatusKind) {
    Box(
        Modifier
            .size(8.dp)
            .background(statusColor(kind), CircleShape)
    )
}

@Composable
private fun statusColor(kind: InstrumentStatusKind): Color = when (kind) {
    InstrumentStatusKind.LIVE -> ClearGreen
    InstrumentStatusKind.DEGRADED,
    InstrumentStatusKind.STALE,
    InstrumentStatusKind.LOADING,
    InstrumentStatusKind.NO_RESPONSE -> WarningAmber
    InstrumentStatusKind.INVALID,
    InstrumentStatusKind.ERROR -> CriticalRed
    InstrumentStatusKind.NOT_CONNECTED,
    InstrumentStatusKind.NOT_PRESENT,
    InstrumentStatusKind.UNAVAILABLE,
    InstrumentStatusKind.INACTIVE -> UnknownGrey
}

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
        InstrumentStatusKind.INVALID -> R.string.status_invalid
        InstrumentStatusKind.ERROR -> R.string.status_error
        InstrumentStatusKind.INACTIVE -> R.string.status_inactive
    }
)

@Composable
private fun sensorDetail(state: SensorState<*>): String = when (state) {
    is SensorState.Available -> stringResource(R.string.sample_current)
    is SensorState.Stale -> stringResource(R.string.sample_stale)
    SensorState.AwaitingFirstSample -> stringResource(R.string.awaiting_first_sample)
    is SensorState.Unavailable -> state.reason.name.humanize()
    is SensorState.Failed -> {
        val reason = state.error.message.ifBlank { state.error.code.name.humanize() }
        if (state.lastSample == null) reason else stringResource(R.string.failure_retains_last, reason)
    }
}

@Composable
private fun phoneDetail(state: PhoneReading<*>): String = when (state) {
    is PhoneReading.Available -> stringResource(R.string.sample_current)
    is PhoneReading.Stale -> stringResource(R.string.sample_stale)
    is PhoneReading.NoResponse -> if (state.lastValue == null) {
        state.reason
    } else {
        stringResource(R.string.failure_retains_last, state.reason)
    }
    is PhoneReading.Invalid -> if (state.lastValue == null) {
        state.reason
    } else {
        stringResource(R.string.failure_retains_last, state.reason)
    }
    is PhoneReading.NotPresent -> state.reason
    PhoneReading.AwaitingFirstSample -> stringResource(R.string.awaiting_first_sample)
    is PhoneReading.Unavailable -> state.reason
    is PhoneReading.Failed -> state.reason
    PhoneReading.Inactive -> stringResource(R.string.sensor_inactive)
}

private fun String.humanize(): String =
    lowercase(Locale.getDefault()).replace('_', ' ').replaceFirstChar { it.titlecase(Locale.getDefault()) }

private fun <T> PhoneReading<T>.lastPhoneValue(): T? = when (this) {
    is PhoneReading.Available -> value
    is PhoneReading.Stale -> lastValue
    is PhoneReading.NoResponse -> lastValue
    is PhoneReading.Invalid -> lastValue
    else -> null
}

private fun SensorState<ir.hrka.shahbaz.hardwareconnection.Sht30Telemetry>.lastShtValue() = when (this) {
    is SensorState.Available -> sample.value
    is SensorState.Stale -> lastSample?.value
    is SensorState.Failed -> lastSample?.value
    else -> null
}

private fun SensorState<ir.hrka.shahbaz.hardwareconnection.Ms5611Telemetry>.lastMsValue() = when (this) {
    is SensorState.Available -> sample.value
    is SensorState.Stale -> lastSample?.value
    is SensorState.Failed -> lastSample?.value
    else -> null
}

@Composable
private fun directionLabel(direction: CompassDirection?): String = when (direction) {
    CompassDirection.NORTH -> stringResource(R.string.compass_north)
    CompassDirection.NORTH_EAST -> stringResource(R.string.compass_north_east)
    CompassDirection.EAST -> stringResource(R.string.compass_east)
    CompassDirection.SOUTH_EAST -> stringResource(R.string.compass_south_east)
    CompassDirection.SOUTH -> stringResource(R.string.compass_south)
    CompassDirection.SOUTH_WEST -> stringResource(R.string.compass_south_west)
    CompassDirection.WEST -> stringResource(R.string.compass_west)
    CompassDirection.NORTH_WEST -> stringResource(R.string.compass_north_west)
    null -> stringResource(R.string.no_value)
}

@Composable
private fun DashboardMapPane(state: DashboardUiState, modifier: Modifier = Modifier) {
    val plan = state.flightPlan
    val phonePosition = state.phoneSensors.position.lastPhoneValue()
    val orientation = state.phoneSensors.orientation
    val phoneHeading = if (
        orientation is PhoneReading.Available &&
        orientationStatusKind(orientation) == InstrumentStatusKind.LIVE
    ) {
        orientation.value.trueAzimuthDegrees ?: orientation.value.magneticAzimuthDegrees
    } else {
        null
    }
    val positionStatus = phoneStatusKind(state.phoneSensors.position)
    val warningText = when (state.phoneSensors.position) {
        is PhoneReading.Available -> stringResource(R.string.phone_position_proxy_warning)
        is PhoneReading.Stale -> stringResource(R.string.phone_position_proxy_stale_warning)
        is PhoneReading.NoResponse -> stringResource(R.string.phone_position_no_response)
        is PhoneReading.Invalid -> stringResource(R.string.phone_position_invalid)
        is PhoneReading.NotPresent -> stringResource(R.string.phone_position_not_present)
        PhoneReading.AwaitingFirstSample -> stringResource(R.string.phone_position_waiting)
        is PhoneReading.Unavailable -> stringResource(R.string.phone_position_unavailable)
        is PhoneReading.Failed -> stringResource(R.string.phone_position_failed)
        PhoneReading.Inactive -> stringResource(R.string.phone_position_inactive)
    }

    Box(
        modifier = modifier
            .background(Color(0xFFE6ECE8))
            .border(1.dp, CockpitMuted.copy(alpha = .35f)),
    ) {
        if (plan == null) {
            MapLocalError(
                text = stringResource(R.string.flight_plan_missing_map),
                modifier = Modifier.align(Alignment.Center),
            )
        } else {
            FlightRouteMap(
                state = FlightRouteMapState(
                    origin = plan.origin,
                    destination = plan.destination,
                    currentPosition = phonePosition,
                    currentPositionHeadingDegrees = if (phonePosition != null) phoneHeading else null,
                ),
                isOnline = state.isOnline,
                modifier = Modifier.fillMaxSize(),
            )
        }

        Surface(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(8.dp)
                .widthIn(max = 380.dp)
                .semantics(mergeDescendants = true) {
                    contentDescription = warningText
                    liveRegion = LiveRegionMode.Polite
                },
            color = CockpitSurface.copy(alpha = .96f),
            contentColor = CockpitOnSurface,
            shape = RoundedCornerShape(12.dp),
            shadowElevation = 3.dp,
        ) {
            Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                Text(
                    text = warningText,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                SourceLabel(stringResource(R.string.source_phone_gps), positionStatus)
            }
        }
    }
}

@Composable
private fun MapLocalError(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.padding(16.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(16.dp),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun StartFlightPlaceholder(modifier: Modifier = Modifier) {
    val unavailable = stringResource(R.string.start_flight_unavailable)
    Button(
        onClick = {},
        enabled = false,
        modifier = modifier
            .size(88.dp)
            .semantics {
                disabled()
                stateDescription = unavailable
                contentDescription = unavailable
            },
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            disabledContainerColor = CriticalRed,
            disabledContentColor = Color.White,
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp),
    ) {
        Text(
            text = stringResource(R.string.start_flight),
            textAlign = TextAlign.Center,
            fontSize = 11.sp,
            lineHeight = 13.sp,
            fontWeight = FontWeight.Black,
        )
    }
}

private const val INSTRUMENT_WEIGHT = 0.70f
private const val MAP_WEIGHT = 0.30f

private val CockpitBackground = Color(0xFF0D151C)
private val CockpitSurface = Color(0xFF17232D)
private val CockpitOnSurface = Color(0xFFF1F6F8)
private val CockpitMuted = Color(0xFF9FB0BC)
private val SkyBlue = Color(0xFF3277A8)
private val GroundBrown = Color(0xFF8A5B36)
private val ClearGreen = Color(0xFF35C978)
private val WarningAmber = Color(0xFFF4C247)
private val CriticalRed = Color(0xFFD92D3E)
private val UnknownGrey = Color(0xFF7B8790)
private val UnknownGreyDark = Color(0xFF4A555D)
