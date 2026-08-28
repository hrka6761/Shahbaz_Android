/** Cockpit-style, failure-explicit presentation for a confirmed Shahbaz flight plan. */
package ir.hrka.shahbaz.feature.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.rounded.Compress
import androidx.compose.material.icons.rounded.DeviceThermostat
import androidx.compose.material.icons.rounded.FlightTakeoff
import androidx.compose.material.icons.rounded.Height
import androidx.compose.material.icons.rounded.Terrain
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
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
import ir.hrka.compass.CompassDirection
import ir.hrka.compass.CompassReading
import ir.hrka.compass.NorthReference
import ir.hrka.shahbaz.core.domain.formatDistance
import ir.hrka.shahbaz.core.domain.sphericalMidpoint
import ir.hrka.shahbaz.core.domain.wgs84GeodesicDistanceMeters
import ir.hrka.shahbaz.core.model.GeoCoordinate
import ir.hrka.shahbaz.feature.dashboard.impl.R
import ir.hrka.shahbaz.hardwareconnection.BoardConnectionState
import ir.hrka.shahbaz.hardwareconnection.BoardDisconnectReason
import ir.hrka.shahbaz.hardwareconnection.SensorErrorCode
import ir.hrka.shahbaz.hardwareconnection.SensorState
import ir.hrka.shahbaz.hardwareconnection.SensorUnavailableReason
import kotlin.math.min
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

                StartFlightPlaceholder(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(end = 16.dp, bottom = 16.dp),
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
    }
}

internal fun instrumentColumnCount(availableWidthDp: Float): Int = when {
    availableWidthDp >= 900f -> 3
    availableWidthDp >= 520f -> 2
    else -> 1
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
        shape = DashboardCardShape,
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
                }
                StatusPill(statusLabel(status), status)
            }
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
        }
    }
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
        shape = DashboardCardShape,
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
    val icon: ImageVector,
    val primaryValue: String,
    val status: InstrumentStatusKind,
)

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

internal enum class DashboardMapLoadState {
    LOADING,
    READY,
    OFFLINE,
    ERROR,
}

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

private fun pointData(position: Position?): GeoJsonData {
    val features: List<Feature<Point, JsonObject?>> = position?.let {
        listOf(Feature(geometry = Point(it), properties = null))
    }.orEmpty()
    return GeoJsonData.Features(FeatureCollection(features))
}

private fun lineData(start: Position?, end: Position?): GeoJsonData {
    val features: List<Feature<LineString, JsonObject?>> = if (start != null && end != null) {
        listOf(Feature(geometry = LineString(start, end), properties = null))
    } else {
        emptyList()
    }
    return GeoJsonData.Features(FeatureCollection(features))
}

private fun GeoCoordinate.toPosition(): Position = Position(
    longitude = longitude,
    latitude = latitude,
)

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

@Composable
private fun StartFlightPlaceholder(modifier: Modifier = Modifier) {
    val unavailable = stringResource(R.string.start_flight_unavailable)
    FloatingActionButton(
        onClick = {},
        modifier = modifier
            .semantics {
                disabled()
                stateDescription = unavailable
                contentDescription = unavailable
            },
        containerColor = CriticalRed,
        contentColor = Color.White,
    ) {
        Text(
            text = stringResource(R.string.start_flight),
            textAlign = TextAlign.Center,
            fontSize = 14.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.Black,
        )
    }
}

private const val INSTRUMENT_WEIGHT = 0.70f
private const val MAP_WEIGHT = 0.30f

private val DashboardCardShape = RoundedCornerShape(8.dp)
private val DashboardDynamicGeoJsonOptions = GeoJsonOptions(synchronousUpdate = true)
private val DashboardRouteColor = Color.Black
private val DashboardRouteLineWidth = 2.8.dp
private val DASHBOARD_MAP_CAMERA_PADDING = 32.dp
private val DASHBOARD_MAP_BOTTOM_CONTENT_PADDING = 96.dp
private val DASHBOARD_MAP_ORNAMENT_BOTTOM_PADDING = 96.dp

private const val DASHBOARD_MAP_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
private const val DASHBOARD_MAP_LOAD_TIMEOUT_MILLIS = 15_000L
private const val DASHBOARD_MAP_CLOSE_ROUTE_ZOOM = 18.0
private const val DASHBOARD_MAP_MIN_BOUNDS_DISTANCE_METERS = 2.0

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
