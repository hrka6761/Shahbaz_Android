/**
 * Map screen overview
 * -------------------
 * This file owns the single-screen MapLibre UI for Shahbaz. It renders the OpenFreeMap base
 * style, waits for both a precise origin and a usable map, and then lets the user choose a
 * destination either by long-pressing the map or by entering latitude and longitude in a
 * dialog. Domain coordinates are always expressed as latitude then longitude; conversion to
 * GeoJSON/MapLibre's longitude-first Position representation is isolated in [toPosition].
 *
 * A two-vertex route is drawn directly between origin and destination. Its label and summary
 * use the WGS-84 geodesic distance, while the label is placed at the spherical midpoint for
 * map presentation. A guided second step collects the drone's positive flight-start altitude
 * above the takeoff surface. The remaining overlays provide actionable loading/error states,
 * camera recentering, and a compass whose dial is interpreted relative to the device heading.
 */
package ir.hrka.shahbaz.feature.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.GpsFixed
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import ir.hrka.compass.CompassDirection
import ir.hrka.compass.CompassReading
import ir.hrka.compass.NorthReference
import ir.hrka.shahbaz.core.domain.formatCoordinate
import ir.hrka.shahbaz.core.domain.formatDistance
import ir.hrka.shahbaz.core.domain.sphericalMidpoint
import ir.hrka.shahbaz.core.domain.wgs84GeodesicDistanceMeters
import ir.hrka.shahbaz.core.model.GeoCoordinate
import ir.hrka.shahbaz.feature.map.impl.R
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonOptions
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.util.ClickResult
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.LineString
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position

/** Color shared by the direct route stroke and its on-map distance label. */
private val RouteColor = Color.Black

/** Width of the MapLibre line layer that joins the origin and destination. */
private val RouteLineWidth = 2.8.dp

/** Neutral color used for the compass dial, center, heading, and unavailable state. */
private val CompassNeutralColor = Color(0xFF6B7280)

/** Red color used for the north-facing half of the compass needle and north deviation. */
private val CompassNorthColor = Color(0xFFD32F2F)

/** Blue color used for the south-facing half of the compass needle and south deviation. */
private val CompassSouthColor = Color(0xFF1565C0)

/**
 * Source options that apply origin, destination, route, and distance-label changes to MapLibre
 * synchronously so all related overlays represent the same UI state in a rendered frame.
 */
private val DynamicGeoJsonOptions = GeoJsonOptions(synchronousUpdate = true)

/**
 * Displays the complete Shahbaz map experience.
 *
 * The MapLibre map fills the available window beneath a measured, scrollable location panel.
 * The screen automatically requests initial location permission, blocks interaction while the
 * precise origin or map is unavailable, and provides recovery actions for permission, device
 * location, connectivity, and map-style failures. Once ready, a map long-press supplies a
 * destination in domain order (`latitude`, `longitude`); manual entry is handled by the top
 * panel's coordinate dialog. Selecting a destination adds the marker, direct line, WGS-84
 * distance label, and bounds-framing camera animation. The panel then advances to validated
 * takeoff-altitude entry while preserving a Previous path back to destination editing. Bottom
 * controls expose the device-heading compass and an animated return to the latest origin.
 *
 * @param state Immutable state containing location readiness, origin and destination points,
 * connectivity, and the complete compass reading. Compass presentation uses true north when the
 * reading contains it and otherwise falls back to magnetic north.
 * @param onDestinationSelected Called with a validated latitude/longitude domain coordinate
 * after either a map long-press or successful dialog submission.
 * @param onClearDestination Called when the user removes the selected destination.
 * @param onAdvanceToTakeoffAltitude Called when the destination-step Next action is activated.
 * @param onReturnToDestinationSelection Called when Previous returns to destination editing.
 * @param onTakeoffAltitudeChanged Called whenever the raw altitude text changes.
 * @param onConfirmTakeoffAltitude Called by the altitude-step Next action after validation.
 * @param onRequestPermission Called to launch the runtime location-permission request.
 * @param onOpenAppSettings Called when permission must be changed in system app settings.
 * @param onOpenLocationSettings Called when device location/GPS must be enabled.
 * @param onRetryLocation Called when the user retries acquisition after a location failure.
 */
@Composable
fun MapScreen(
    state: MapUiState,
    onDestinationSelected: (GeoCoordinate) -> Unit,
    onClearDestination: () -> Unit,
    onAdvanceToTakeoffAltitude: () -> Unit,
    onReturnToDestinationSelection: () -> Unit,
    onTakeoffAltitudeChanged: (String) -> Unit,
    onConfirmTakeoffAltitude: () -> Unit,
    onRequestPermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onOpenLocationSettings: () -> Unit,
    onRetryLocation: () -> Unit,
) {
    val cameraState = rememberCameraState()
    val coroutineScope = rememberCoroutineScope()
    val hapticFeedback = LocalHapticFeedback.current
    val currentLocationStatus by rememberUpdatedState(state.locationStatus)
    val currentFlightSetupStep by rememberUpdatedState(state.flightSetupStep)
    val currentOnDestinationSelected by rememberUpdatedState(onDestinationSelected)
    var mapLoaded by remember { mutableStateOf(false) }
    var mapLoadTimedOut by remember { mutableStateOf(false) }
    var mapLoadFailed by remember { mutableStateOf(false) }
    var mapInstanceKey by rememberSaveable { mutableIntStateOf(0) }
    var topPanelHeightPx by remember { mutableIntStateOf(0) }
    var initialCameraPositioned by rememberSaveable { mutableStateOf(false) }
    var lastFramedDestination by rememberSaveable { mutableStateOf<String?>(null) }
    var permissionWasAutoRequested by rememberSaveable { mutableStateOf(false) }

    val originCoordinate = state.origin?.coordinate
    val destinationCoordinate = state.destination?.coordinate
    val originPosition = originCoordinate?.toPosition()
    val destinationPosition = destinationCoordinate?.toPosition()
    val density = LocalDensity.current
    val topPanelHeight = with(density) { topPanelHeightPx.toDp() }
    val mapTopPadding = if (topPanelHeightPx == 0) 196.dp else topPanelHeight + 12.dp
    val mapContentPadding = PaddingValues(
        top = mapTopPadding,
        bottom = MAP_BOTTOM_CONTENT_PADDING,
    )
    val retryMapLoad = {
        mapLoaded = false
        mapLoadTimedOut = false
        mapLoadFailed = false
        mapInstanceKey += 1
    }

    LaunchedEffect(state.locationStatus) {
        if (
            state.locationStatus == LocationStatus.PERMISSION_REQUIRED &&
            !permissionWasAutoRequested
        ) {
            permissionWasAutoRequested = true
            onRequestPermission()
        }
    }

    LaunchedEffect(mapLoaded, state.isOnline, mapLoadFailed, mapInstanceKey) {
        mapLoadTimedOut = false
        if (!mapLoaded && state.isOnline && !mapLoadFailed) {
            delay(MAP_LOAD_TIMEOUT_MILLIS)
            if (!mapLoaded && !mapLoadFailed) mapLoadTimedOut = true
        }
    }

    LaunchedEffect(mapLoaded, originPosition, destinationPosition, initialCameraPositioned) {
        if (
            mapLoaded &&
            originPosition != null &&
            destinationPosition == null &&
            !initialCameraPositioned
        ) {
            cameraState.animateTo(
                finalPosition = CameraPosition(
                    target = originPosition,
                    zoom = DEFAULT_LOCATION_ZOOM,
                    padding = mapContentPadding,
                ),
                duration = CAMERA_ANIMATION_MILLIS.milliseconds,
            )
            initialCameraPositioned = true
        }
    }

    LaunchedEffect(mapLoaded, originPosition, destinationPosition) {
        val destinationKey = destinationCoordinate?.let(::formatCoordinate)
        if (
            mapLoaded &&
            originPosition != null &&
            destinationPosition != null &&
            destinationKey != lastFramedDestination
        ) {
            val distance = wgs84GeodesicDistanceMeters(
                originCoordinate,
                destinationCoordinate,
            )
            if (distance < MIN_BOUNDS_DISTANCE_METERS) {
                cameraState.animateTo(
                    finalPosition = CameraPosition(
                        target = destinationPosition,
                        zoom = CLOSE_LOCATION_ZOOM,
                        padding = mapContentPadding,
                    ),
                    duration = CAMERA_ANIMATION_MILLIS.milliseconds,
                )
            } else {
                cameraState.animateTo(
                    boundingBox = BoundingBox(
                        west = minOf(originCoordinate.longitude, destinationCoordinate.longitude),
                        south = minOf(originCoordinate.latitude, destinationCoordinate.latitude),
                        east = maxOf(originCoordinate.longitude, destinationCoordinate.longitude),
                        north = maxOf(originCoordinate.latitude, destinationCoordinate.latitude),
                    ),
                    padding = PaddingValues(
                        start = CAMERA_BOUNDS_PADDING_DP,
                        top = mapTopPadding + CAMERA_BOUNDS_PADDING_DP,
                        end = CAMERA_BOUNDS_PADDING_DP,
                        bottom = MAP_BOTTOM_CONTENT_PADDING + CAMERA_BOUNDS_PADDING_DP,
                    ),
                    duration = CAMERA_ANIMATION_MILLIS.milliseconds,
                )
            }
            lastFramedDestination = destinationKey
            initialCameraPositioned = true
        }
    }

    LaunchedEffect(destinationCoordinate) {
        if (destinationCoordinate == null) lastFramedDestination = null
    }

    LaunchedEffect(originCoordinate) {
        if (originCoordinate == null) {
            initialCameraPositioned = false
            lastFramedDestination = null
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFE6ECE8))
    ) {
        key(mapInstanceKey) {
            MaplibreMap(
                modifier = Modifier.fillMaxSize(),
                baseStyle = BaseStyle.Uri(OPEN_FREE_MAP_STYLE_URL),
                cameraState = cameraState,
                options = MapOptions(
                    gestureOptions = GestureOptions(
                        isRotateEnabled = false,
                        isScrollEnabled = true,
                        isTiltEnabled = false,
                        isZoomEnabled = true,
                    ),
                    ornamentOptions = OrnamentOptions(
                        padding = PaddingValues(
                            top = mapTopPadding,
                            bottom = MAP_ORNAMENT_BOTTOM_PADDING,
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
                },
                onMapLoadFailed = {
                    mapLoaded = false
                    mapLoadFailed = true
                },
                onMapLongClick = { position, _ ->
                    if (
                        currentLocationStatus == LocationStatus.READY &&
                        currentFlightSetupStep == FlightSetupStep.DESTINATION
                    ) {
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                        currentOnDestinationSelected(
                            GeoCoordinate(position.latitude, position.longitude)
                        )
                        ClickResult.Consume
                    } else {
                        ClickResult.Pass
                    }
                },
            ) {
                MapOverlays(
                    origin = originCoordinate,
                    destination = destinationCoordinate,
                )
            }
        }

        LocationGate(
            status = state.locationStatus,
            waitingForMap = state.locationStatus == LocationStatus.READY &&
                !mapLoaded &&
                state.isOnline &&
                !mapLoadTimedOut &&
                !mapLoadFailed,
            mapUnavailable = state.locationStatus == LocationStatus.READY &&
                !mapLoaded &&
                (!state.isOnline || mapLoadTimedOut || mapLoadFailed),
            topContentPadding = topPanelHeight,
            onRequestPermission = onRequestPermission,
            onOpenAppSettings = onOpenAppSettings,
            onOpenLocationSettings = onOpenLocationSettings,
            onRetryLocation = onRetryLocation,
            onRetryMap = retryMapLoad,
            modifier = Modifier.align(Alignment.Center),
        )

        TopLocationPanel(
            state = state,
            mapReady = mapLoaded,
            mapLoadFailed = mapLoadTimedOut || mapLoadFailed,
            onDestinationSelected = onDestinationSelected,
            onClearDestination = onClearDestination,
            onAdvanceToTakeoffAltitude = onAdvanceToTakeoffAltitude,
            onReturnToDestinationSelection = onReturnToDestinationSelection,
            onTakeoffAltitudeChanged = onTakeoffAltitudeChanged,
            onConfirmTakeoffAltitude = onConfirmTakeoffAltitude,
            onRetryMap = retryMapLoad,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .onSizeChanged { topPanelHeightPx = it.height }
                .windowInsetsPadding(WindowInsets.statusBars.only(androidx.compose.foundation.layout.WindowInsetsSides.Top))
                .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 6.dp)
                .widthIn(max = 600.dp),
        )

        if (state.locationStatus == LocationStatus.READY && originPosition != null && mapLoaded) {
            MapControls(
                compassReading = state.compassReading,
                onRecenter = {
                    coroutineScope.launch {
                        cameraState.animateTo(
                            finalPosition = CameraPosition(
                                target = originPosition,
                                zoom = DEFAULT_LOCATION_ZOOM,
                                padding = mapContentPadding,
                            ),
                            duration = CAMERA_ANIMATION_MILLIS.milliseconds,
                        )
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = 16.dp, vertical = 16.dp),
            )
        }
    }
}

/**
 * Adds the dynamic route, endpoint markers, and distance label to the current MapLibre style.
 *
 * Each source contains either one current feature or an empty feature collection. The route is
 * a straight, two-position GeoJSON [LineString]. Its label reports WGS-84 geodesic distance and
 * is anchored at the spherical midpoint; antipodal points fall back to the origin because their
 * spherical midpoint is undefined.
 *
 * @param origin Current phone-origin coordinate in latitude/longitude domain order, or `null`
 * while location is unresolved.
 * @param destination Final destination coordinate in latitude/longitude domain order, or `null`
 * before the user selects one.
 */
@Composable
private fun MapOverlays(origin: GeoCoordinate?, destination: GeoCoordinate?) {
    val originPosition = origin?.toPosition()
    val destinationPosition = destination?.toPosition()
    val routeSource = rememberGeoJsonSource(
        data = lineData(originPosition, destinationPosition),
        options = DynamicGeoJsonOptions,
    )
    val originSource = rememberGeoJsonSource(
        data = pointData(originPosition),
        options = DynamicGeoJsonOptions,
    )
    val destinationSource = rememberGeoJsonSource(
        data = pointData(destinationPosition),
        options = DynamicGeoJsonOptions,
    )
    val midpoint = if (origin != null && destination != null) {
        runCatching {
            sphericalMidpoint(origin, destination)
        }.getOrElse { origin }.toPosition()
    } else {
        null
    }
    val distanceLabel = if (origin != null && destination != null) {
        formatDistance(wgs84GeodesicDistanceMeters(origin, destination))
    } else {
        ""
    }
    val distanceSource = rememberGeoJsonSource(
        data = pointData(midpoint),
        options = DynamicGeoJsonOptions,
    )

    LineLayer(
        id = "shahbaz-route",
        source = routeSource,
        color = const(RouteColor),
        width = const(RouteLineWidth),
        cap = const(LineCap.Round),
        join = const(LineJoin.Round),
    )
    CircleLayer(
        id = "shahbaz-origin",
        source = originSource,
        color = const(Color(0xFF1976D2)),
        radius = const(9.dp),
        strokeColor = const(Color.White),
        strokeWidth = const(4.dp),
    )
    CircleLayer(
        id = "shahbaz-destination",
        source = destinationSource,
        color = const(Color(0xFFD32F2F)),
        radius = const(10.dp),
        strokeColor = const(Color.White),
        strokeWidth = const(3.dp),
    )
    SymbolLayer(
        id = "shahbaz-distance",
        source = distanceSource,
        textField = format(span(distanceLabel)),
        textSize = const(14.sp),
        textColor = const(RouteColor),
        textHaloColor = const(Color.White),
        textHaloWidth = const(2.dp),
        textAllowOverlap = const(true),
        textIgnorePlacement = const(true),
    )
}

/**
 * Wraps an optional MapLibre position in point-feature GeoJSON suitable for a dynamic source.
 *
 * @param position A GeoJSON position whose coordinate order is longitude then latitude, or
 * `null` when the corresponding marker should be absent.
 * @return A feature collection containing one [Point] when [position] is present, otherwise an
 * empty feature collection.
 */
private fun pointData(position: Position?): GeoJsonData {
    val features: List<Feature<Point, JsonObject?>> = position?.let {
        listOf(Feature(geometry = Point(it), properties = null))
    }.orEmpty()
    return GeoJsonData.Features(FeatureCollection(features))
}

/**
 * Builds the direct, two-position GeoJSON line shown between origin and destination.
 *
 * @param start Origin position in GeoJSON/MapLibre longitude-then-latitude order, or `null` when
 * no origin is available.
 * @param end Destination position in GeoJSON/MapLibre longitude-then-latitude order, or `null`
 * when no destination is selected.
 * @return A feature collection containing one [LineString] only when both endpoints exist;
 * otherwise, an empty feature collection that removes the route from the map.
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
 * Renders the measured, scrollable destination and takeoff-altitude workflow panel.
 *
 * The destination step shows read-only origin and destination coordinates, manual edit and clear
 * actions, and the WGS-84 route distance. Its Next action appears after a destination exists. The
 * altitude step explains that height is relative to the takeoff surface, accepts a positive meter
 * value, offers Previous without discarding the route or draft, and keeps Next visibly disabled
 * with an explanation whenever the live takeoff origin is unavailable. Inline network and map
 * failure notices remain visible in either step.
 *
 * @param state Current screen state used to populate route and flight-setup content.
 * @param mapReady Whether MapLibre has successfully finished loading its current style.
 * @param mapLoadFailed Whether map loading timed out or reported a style/load failure.
 * @param onDestinationSelected Called with the validated manual destination in latitude/longitude
 * order.
 * @param onClearDestination Called when the destination-clear action is activated.
 * @param onAdvanceToTakeoffAltitude Called by Next after a destination has been selected.
 * @param onReturnToDestinationSelection Called by Previous from altitude entry.
 * @param onTakeoffAltitudeChanged Called whenever the raw altitude input changes.
 * @param onConfirmTakeoffAltitude Called by Next when the altitude input is valid.
 * @param onRetryMap Called by the inline error action to recreate and reload the MapLibre map.
 * @param modifier Modifier applied to the panel surface; callers use it for placement, insets,
 * measurement, and width constraints.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopLocationPanel(
    state: MapUiState,
    mapReady: Boolean,
    mapLoadFailed: Boolean,
    onDestinationSelected: (GeoCoordinate) -> Unit,
    onClearDestination: () -> Unit,
    onAdvanceToTakeoffAltitude: () -> Unit,
    onReturnToDestinationSelection: () -> Unit,
    onTakeoffAltitudeChanged: (String) -> Unit,
    onConfirmTakeoffAltitude: () -> Unit,
    onRetryMap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDestinationDialog by rememberSaveable { mutableStateOf(false) }
    val density = LocalDensity.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val altitudeFocusRequester = remember { FocusRequester() }
    val windowHeight = with(density) { LocalWindowInfo.current.containerSize.height.toDp() }
    val maxPanelHeight = (windowHeight * 0.52f)
        .coerceIn(160.dp, 328.dp)
    val fieldsEnabled = state.locationStatus == LocationStatus.READY && mapReady
    val originCoordinates = state.origin?.coordinate?.let(::formatCoordinate).orEmpty()
    val destinationCoordinates = state.destination?.coordinate?.let(::formatCoordinate).orEmpty()
    val takeoffAltitudeMeters = state.takeoffAltitudeMeters
    val altitudeInputIsInvalid = state.takeoffAltitudeInput.isNotBlank() &&
        takeoffAltitudeMeters == null
    val takeoffConfirmationBlockerMessage = when (state.takeoffConfirmationBlocker) {
        TakeoffConfirmationBlocker.LIVE_ORIGIN_UNAVAILABLE ->
            stringResource(R.string.takeoff_origin_unavailable)
        TakeoffConfirmationBlocker.DESTINATION_UNAVAILABLE ->
            stringResource(R.string.takeoff_destination_unavailable)
        TakeoffConfirmationBlocker.NOT_ALTITUDE_STEP,
        TakeoffConfirmationBlocker.INVALID_ALTITUDE,
        null -> null
    }
    val panelTitle = stringResource(
        if (state.flightSetupStep == FlightSetupStep.DESTINATION) {
            R.string.route_details_step
        } else {
            R.string.flight_start_altitude
        }
    )
    val selectedDistance = state.origin?.coordinate?.let { origin ->
        state.destination?.coordinate?.let { destination ->
            formatDistance(wgs84GeodesicDistanceMeters(origin, destination))
        }
    }
    val destinationSupportingText = if (state.destination == null) {
        stringResource(R.string.destination_help)
    } else {
        null
    }
    val destinationSupportingContent: (@Composable () -> Unit)? =
        destinationSupportingText?.let { supportingText ->
            {
                Text(
                    text = supportingText,
                    maxLines = 1,
                )
            }
        }

    LaunchedEffect(state.flightSetupStep) {
        if (state.flightSetupStep == FlightSetupStep.TAKEOFF_ALTITUDE) {
            altitudeFocusRequester.requestFocus()
        }
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(20.dp),
        shadowElevation = 4.dp,
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .heightIn(max = maxPanelHeight)
                .verticalScroll(rememberScrollState())
                .semantics { paneTitle = panelTitle }
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            when (state.flightSetupStep) {
                FlightSetupStep.DESTINATION -> {
                    OutlinedTextField(
                        value = originCoordinates,
                        onValueChange = {},
                        modifier = Modifier.fillMaxWidth(),
                        readOnly = true,
                        singleLine = true,
                        label = { Text(stringResource(R.string.origin)) },
                        placeholder = { Text(stringResource(R.string.locating_title)) },
                        leadingIcon = {
                            Icon(Icons.Rounded.GpsFixed, contentDescription = null)
                        },
                    )

                    OutlinedTextField(
                        value = destinationCoordinates,
                        onValueChange = {},
                        modifier = Modifier.fillMaxWidth(),
                        enabled = fieldsEnabled,
                        readOnly = true,
                        singleLine = true,
                        label = { Text(stringResource(R.string.destination)) },
                        placeholder = { Text(stringResource(R.string.coordinate_hint)) },
                        leadingIcon = {
                            Icon(Icons.Rounded.LocationOn, contentDescription = null)
                        },
                        trailingIcon = {
                            Row {
                                if (state.destination != null) {
                                    IconButton(
                                        onClick = onClearDestination,
                                        enabled = fieldsEnabled,
                                    ) {
                                        Icon(
                                            Icons.Rounded.Close,
                                            contentDescription = stringResource(
                                                R.string.clear_destination
                                            ),
                                        )
                                    }
                                }
                                IconButton(
                                    onClick = { showDestinationDialog = true },
                                    enabled = fieldsEnabled,
                                ) {
                                    Icon(
                                        Icons.Rounded.Edit,
                                        contentDescription = stringResource(
                                            R.string.enter_coordinates
                                        ),
                                    )
                                }
                            }
                        },
                        supportingText = destinationSupportingContent,
                    )

                    if (selectedDistance != null) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.distance),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = selectedDistance,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }

                    if (state.destination != null) {
                        Button(
                            onClick = onAdvanceToTakeoffAltitude,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = fieldsEnabled,
                        ) {
                            Text(stringResource(R.string.next))
                        }
                    }
                }

                FlightSetupStep.TAKEOFF_ALTITUDE -> {
                    Text(
                        text = stringResource(R.string.flight_start_altitude),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = stringResource(R.string.flight_start_altitude_help),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedTextField(
                        value = state.takeoffAltitudeInput,
                        onValueChange = onTakeoffAltitudeChanged,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(altitudeFocusRequester),
                        singleLine = true,
                        isError = altitudeInputIsInvalid,
                        label = { Text(stringResource(R.string.flight_start_altitude)) },
                        placeholder = { Text(stringResource(R.string.altitude_meters_hint)) },
                        suffix = { Text(stringResource(R.string.meters_abbreviation)) },
                        supportingText = if (altitudeInputIsInvalid) {
                            {
                                Text(stringResource(R.string.invalid_takeoff_altitude))
                            }
                        } else {
                            null
                        },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Decimal,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                if (state.canConfirmTakeoffAltitude) {
                                    onConfirmTakeoffAltitude()
                                    keyboardController?.hide()
                                }
                            }
                        ),
                    )

                    if (takeoffConfirmationBlockerMessage != null) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics { liveRegion = LiveRegionMode.Polite },
                            color = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text(
                                text = takeoffConfirmationBlockerMessage,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = {
                                keyboardController?.hide()
                                onReturnToDestinationSelection()
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.previous))
                        }
                        Button(
                            onClick = {
                                onConfirmTakeoffAltitude()
                                keyboardController?.hide()
                            },
                            modifier = Modifier.weight(1f),
                            enabled = state.canConfirmTakeoffAltitude,
                        ) {
                            Text(stringResource(R.string.next))
                        }
                    }

                    if (state.isTakeoffAltitudeConfirmed) {
                        Text(
                            text = stringResource(R.string.takeoff_altitude_confirmed),
                            modifier = Modifier.semantics {
                                liveRegion = LiveRegionMode.Polite
                            },
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }

            when {
                !state.isOnline -> InlineNotice(
                    icon = Icons.Rounded.WifiOff,
                    text = stringResource(R.string.offline_message),
                )
                mapLoadFailed -> InlineNotice(
                    icon = Icons.Rounded.Refresh,
                    text = stringResource(R.string.map_loading_message),
                    isError = true,
                    actionLabel = stringResource(R.string.retry),
                    onAction = onRetryMap,
                )
            }
        }
    }

    if (showDestinationDialog) {
        DestinationCoordinateDialog(
            initialCoordinate = state.destination?.coordinate,
            onDismiss = { showDestinationDialog = false },
            onConfirm = { coordinate ->
                onDestinationSelected(coordinate)
                showDestinationDialog = false
            },
        )
    }
}

/**
 * Collects and validates a destination as separate latitude and longitude values.
 *
 * Latitude is presented first and must be in `[-90, 90]`; longitude is presented second and
 * must be in `[-180, 180]`. Confirmation is withheld until both finite decimal values are valid,
 * after which they are passed to [onConfirm] as a [GeoCoordinate] in that same order.
 *
 * @param initialCoordinate Existing destination used to prefill both fields, or `null` to start
 * with empty fields.
 * @param onDismiss Called when the dialog is cancelled or dismissed without changing the
 * destination.
 * @param onConfirm Called with the validated latitude/longitude coordinate when the user submits.
 */
@Composable
private fun DestinationCoordinateDialog(
    initialCoordinate: GeoCoordinate?,
    onDismiss: () -> Unit,
    onConfirm: (GeoCoordinate) -> Unit,
) {
    var latitudeInput by rememberSaveable(initialCoordinate?.latitude) {
        mutableStateOf(initialCoordinate?.latitude?.toString().orEmpty())
    }
    var longitudeInput by rememberSaveable(initialCoordinate?.longitude) {
        mutableStateOf(initialCoordinate?.longitude?.toString().orEmpty())
    }
    var validationRequested by rememberSaveable { mutableStateOf(false) }
    val keyboardController = LocalSoftwareKeyboardController.current
    val latitude = latitudeInput.trim().toDoubleOrNull()
    val longitude = longitudeInput.trim().toDoubleOrNull()
    val latitudeIsValid = latitude?.let { it.isFinite() && it in -90.0..90.0 } == true
    val longitudeIsValid = longitude?.let { it.isFinite() && it in -180.0..180.0 } == true
    val submit = {
        validationRequested = true
        if (latitudeIsValid && longitudeIsValid) {
            onConfirm(GeoCoordinate(requireNotNull(latitude), requireNotNull(longitude)))
            keyboardController?.hide()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.enter_destination_coordinates)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(R.string.coordinate_dialog_help),
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = latitudeInput,
                    onValueChange = {
                        latitudeInput = it.take(MAX_COORDINATE_COMPONENT_LENGTH)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = validationRequested && !latitudeIsValid,
                    label = { Text(stringResource(R.string.latitude)) },
                    supportingText = {
                        Text(stringResource(R.string.latitude_range))
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Next,
                    ),
                )
                OutlinedTextField(
                    value = longitudeInput,
                    onValueChange = {
                        longitudeInput = it.take(MAX_COORDINATE_COMPONENT_LENGTH)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = validationRequested && !longitudeIsValid,
                    label = { Text(stringResource(R.string.longitude)) },
                    supportingText = {
                        Text(stringResource(R.string.longitude_range))
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                )
                if (validationRequested && (!latitudeIsValid || !longitudeIsValid)) {
                    Text(
                        text = stringResource(R.string.invalid_coordinates),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = submit) {
                Text(stringResource(R.string.set_destination))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

/**
 * Displays a compact informational or error notice with an optional action.
 *
 * @param icon Decorative icon associated with the notice category.
 * @param text User-facing notice text.
 * @param isError Whether to use Material error-container colors instead of secondary-container
 * colors.
 * @param actionLabel Optional label for the trailing outlined action button.
 * @param onAction Optional action paired with [actionLabel]; no button is rendered unless both
 * values are non-null.
 */
@Composable
private fun InlineNotice(
    icon: ImageVector,
    text: String,
    isError: Boolean = false,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val containerColor = if (isError) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }
    val contentColor = if (isError) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }

    Surface(
        color = containerColor,
        contentColor = contentColor,
        shape = RoundedCornerShape(14.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(
                text = text,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
            )
            if (actionLabel != null && onAction != null) {
                OutlinedButton(
                    onClick = onAction,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                ) {
                    Text(actionLabel)
                }
            }
        }
    }
}

/**
 * Blocks map interaction and presents the current location or map-loading state.
 *
 * The gate remains visible while permission is missing, precise location is required, GPS is
 * disabled, a fix is being acquired, or MapLibre is loading/unavailable. It consumes pointer
 * input so a destination cannot be chosen through the scrim, exposes an assertive accessibility
 * live region, and offers the recovery action appropriate to the active state. Its content is
 * scrollable and offset below the measured top panel for compact windows.
 *
 * @param status Current precise-location state.
 * @param waitingForMap Whether a ready origin is waiting for an online MapLibre load to finish.
 * @param mapUnavailable Whether MapLibre cannot currently display a usable map.
 * @param topContentPadding Height reserved above the gate for the top location panel.
 * @param onRequestPermission Called to request runtime location permission.
 * @param onOpenAppSettings Called to resolve denied or approximate-only permission.
 * @param onOpenLocationSettings Called to enable device location/GPS.
 * @param onRetryLocation Called to retry a failed location acquisition.
 * @param onRetryMap Called to recreate and retry the MapLibre map after a load failure.
 * @param modifier Modifier applied to the centered gate surface.
 */
@Composable
private fun LocationGate(
    status: LocationStatus,
    waitingForMap: Boolean,
    mapUnavailable: Boolean,
    topContentPadding: androidx.compose.ui.unit.Dp,
    onRequestPermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onOpenLocationSettings: () -> Unit,
    onRetryLocation: () -> Unit,
    onRetryMap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (status == LocationStatus.READY && !waitingForMap && !mapUnavailable) return

    val title: String
    val message: String
    val icon: ImageVector
    when {
        waitingForMap -> {
            title = stringResource(R.string.map_loading_title)
            message = stringResource(R.string.map_loading_wait_message)
            icon = Icons.Rounded.Refresh
        }
        mapUnavailable -> {
            title = stringResource(R.string.map_unavailable_title)
            message = stringResource(R.string.map_unavailable_message)
            icon = Icons.Rounded.WifiOff
        }
        status == LocationStatus.PERMISSION_REQUIRED -> {
            title = stringResource(R.string.permission_title)
            message = stringResource(R.string.permission_message)
            icon = Icons.Rounded.LocationOn
        }
        status == LocationStatus.PRECISE_PERMISSION_REQUIRED -> {
            title = stringResource(R.string.precise_permission_title)
            message = stringResource(R.string.precise_permission_message)
            icon = Icons.Rounded.GpsFixed
        }
        status == LocationStatus.LOCATING -> {
            title = stringResource(R.string.locating_title)
            message = stringResource(R.string.locating_message)
            icon = Icons.Rounded.GpsFixed
        }
        status == LocationStatus.LOCATION_DISABLED -> {
            title = stringResource(R.string.location_disabled_title)
            message = stringResource(R.string.location_disabled_message)
            icon = Icons.Rounded.Settings
        }
        status == LocationStatus.UNAVAILABLE -> {
            title = stringResource(R.string.location_unavailable_title)
            message = stringResource(R.string.location_unavailable_message)
            icon = Icons.Rounded.Refresh
        }
        else -> return
    }
    val density = LocalDensity.current
    val windowHeight = with(density) { LocalWindowInfo.current.containerSize.height.toDp() }
    val maxGateHeight = (windowHeight * 0.55f)
        .coerceAtLeast(180.dp)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.34f))
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown().consume()
                    do {
                        val event = awaitPointerEvent()
                        event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.pressed })
                }
            }
            .semantics {
                paneTitle = title
                liveRegion = LiveRegionMode.Assertive
            },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = modifier
                .padding(start = 28.dp, top = topContentPadding, end = 28.dp, bottom = 16.dp)
                .widthIn(max = 420.dp)
                .heightIn(max = maxGateHeight),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 10.dp,
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (status == LocationStatus.LOCATING || waitingForMap) {
                    CircularProgressIndicator()
                } else {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                when (status) {
                    LocationStatus.PERMISSION_REQUIRED -> {
                        Button(onClick = onRequestPermission) {
                            Text(stringResource(R.string.grant_permission))
                        }
                        OutlinedButton(onClick = onOpenAppSettings) {
                            Text(stringResource(R.string.open_settings))
                        }
                    }
                    LocationStatus.PRECISE_PERMISSION_REQUIRED -> Button(
                        onClick = onOpenAppSettings
                    ) {
                        Text(stringResource(R.string.open_settings))
                    }
                    LocationStatus.LOCATION_DISABLED -> Button(
                        onClick = onOpenLocationSettings
                    ) {
                        Text(stringResource(R.string.open_settings))
                    }
                    LocationStatus.UNAVAILABLE -> Button(onClick = onRetryLocation) {
                        Icon(Icons.Rounded.Refresh, contentDescription = null)
                        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                        Text(stringResource(R.string.retry))
                    }
                    LocationStatus.LOCATING,
                    LocationStatus.READY -> Unit
                }
                if (mapUnavailable) {
                    Button(onClick = onRetryMap) {
                        Icon(Icons.Rounded.Refresh, contentDescription = null)
                        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                        Text(stringResource(R.string.retry))
                    }
                }
            }
        }
    }
}

/**
 * Places the compass at the lower start edge and the current-location camera action at the lower
 * end edge.
 *
 * @param compassReading Complete logical device orientation, or `null` when no compass reading is
 * available.
 * @param onRecenter Called when the user asks the camera to animate to the latest origin.
 * @param modifier Modifier used to position and inset the full-width controls row.
 */
@Composable
private fun MapControls(
    compassReading: CompassReading?,
    onRecenter: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val recenterDescription = stringResource(R.string.recenter)
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        CompassBadge(compassReading)
        FloatingActionButton(
            onClick = onRecenter,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.primary,
            modifier = Modifier.semantics {
                contentDescription = recenterDescription
            },
        ) {
            Icon(Icons.Rounded.MyLocation, contentDescription = null)
        }
    }
}

/**
 * Summarizes the device heading with a traditional dial and textual direction information.
 *
 * For an available heading, the badge shows the nearest eight-point cardinal abbreviation,
 * rounded heading degrees, and the shortest angular deviations from north and south. True north
 * is preferred when the reading contains it; otherwise every displayed value consistently uses
 * magnetic north. A `null` value produces the explicit compass-unavailable state instead of
 * implying a zero-degree heading.
 *
 * @param compassReading Complete logical reading, or `null` when the sensor is unavailable or
 * inactive.
 */
@Composable
private fun CompassBadge(compassReading: CompassReading?) {
    val northReference = if (compassReading?.trueAzimuthDegrees != null) {
        NorthReference.TRUE
    } else {
        NorthReference.MAGNETIC
    }
    val headingDegrees = compassReading?.azimuth(northReference)
    val direction = compassReading?.nearestDirection(northReference)
    val northDeviation = compassReading
        ?.deviationFrom(CompassDirection.NORTH, northReference)
        ?.absoluteDegrees
    val southDeviation = compassReading
        ?.deviationFrom(CompassDirection.SOUTH, northReference)
        ?.absoluteDegrees

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        shadowElevation = 5.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            CompassRose(headingDegrees)
            Text(
                text = if (headingDegrees == null || direction == null) {
                    stringResource(R.string.compass_unavailable)
                } else {
                    stringResource(
                        R.string.heading_format,
                        direction.localizedAbbreviation(),
                        headingDegrees,
                    )
                },
                color = CompassNeutralColor,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            if (northDeviation != null && southDeviation != null) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = stringResource(
                            R.string.north_deviation_format,
                            northDeviation,
                        ),
                        color = CompassNorthColor,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = stringResource(
                            R.string.south_deviation_format,
                            southDeviation,
                        ),
                        color = CompassSouthColor,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

/**
 * Resolves localized presentation text for one semantic direction from the compass module.
 *
 * @receiver semantic cardinal or intercardinal direction without embedded UI text.
 * @return localized short direction label owned by the map feature.
 */
@Composable
private fun CompassDirection.localizedAbbreviation(): String = stringResource(
    when (this) {
        CompassDirection.NORTH -> R.string.compass_direction_north
        CompassDirection.NORTH_EAST -> R.string.compass_direction_north_east
        CompassDirection.EAST -> R.string.compass_direction_east
        CompassDirection.SOUTH_EAST -> R.string.compass_direction_south_east
        CompassDirection.SOUTH -> R.string.compass_direction_south
        CompassDirection.SOUTH_WEST -> R.string.compass_direction_south_west
        CompassDirection.WEST -> R.string.compass_direction_west
        CompassDirection.NORTH_WEST -> R.string.compass_direction_north_west
    }
)

/**
 * Draws the visual compass dial and its red-north/blue-south needle.
 *
 * The device stays conceptually fixed while the needle is rotated by the negative heading: for
 * example, when the device faces east (90 degrees), north appears to the left. [CompassBadge]
 * supplies a true-north heading when available and otherwise supplies magnetic north, keeping the
 * dial consistent with all textual values. With no heading, the dial uses the neutral zero-degree
 * orientation; [CompassBadge] supplies the unavailable text that prevents this fallback from
 * being interpreted as a real reading.
 *
 * @param headingDegrees Device azimuth in degrees clockwise from the selected north reference, or
 * `null` when unavailable.
 */
@Composable
private fun CompassRose(headingDegrees: Float?) {
    val dialColor = CompassNeutralColor
    val rotation = -(headingDegrees ?: 0f)

    Box(
        modifier = Modifier.size(68.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val outlineWidth = 1.dp.toPx()
            val tickLength = 5.dp.toPx()
            val tickWidth = 1.5.dp.toPx()

            drawCircle(color = dialColor.copy(alpha = 0.08f))
            drawCircle(
                color = dialColor.copy(alpha = 0.35f),
                style = Stroke(width = outlineWidth),
            )
            drawLine(
                color = dialColor.copy(alpha = 0.45f),
                start = Offset(center.x, 0f),
                end = Offset(center.x, tickLength),
                strokeWidth = tickWidth,
            )
            drawLine(
                color = dialColor.copy(alpha = 0.45f),
                start = Offset(center.x, size.height - tickLength),
                end = Offset(center.x, size.height),
                strokeWidth = tickWidth,
            )
            drawLine(
                color = dialColor.copy(alpha = 0.25f),
                start = Offset(0f, center.y),
                end = Offset(tickLength, center.y),
                strokeWidth = tickWidth,
            )
            drawLine(
                color = dialColor.copy(alpha = 0.25f),
                start = Offset(size.width - tickLength, center.y),
                end = Offset(size.width, center.y),
                strokeWidth = tickWidth,
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(5.dp)
                .rotate(rotation),
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val needleInset = 7.dp.toPx()
                val needleWidth = 4.dp.toPx()

                drawLine(
                    color = CompassNorthColor,
                    start = center,
                    end = Offset(center.x, needleInset),
                    strokeWidth = needleWidth,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = CompassSouthColor,
                    start = center,
                    end = Offset(center.x, size.height - needleInset),
                    strokeWidth = needleWidth,
                    cap = StrokeCap.Round,
                )
                drawCircle(color = dialColor, radius = 3.dp.toPx())
            }
        }
    }
}

/**
 * Converts the domain's latitude/longitude coordinate into MapLibre's GeoJSON position format.
 *
 * @receiver Validated domain coordinate whose public order is latitude then longitude.
 * @return A [Position] populated in GeoJSON order: longitude first, then latitude.
 */
private fun GeoCoordinate.toPosition(): Position = Position(
    longitude = longitude,
    latitude = latitude,
)

/** Online wait before an unfinished MapLibre load is presented as a recoverable timeout. */
private const val MAP_LOAD_TIMEOUT_MILLIS = 15_000L

/** Duration used for initial framing, route framing, and current-location camera animations. */
private const val CAMERA_ANIMATION_MILLIS = 850L

/** Public OpenFreeMap Liberty style loaded by MapLibre as the full-screen base map. */
private const val OPEN_FREE_MAP_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"

/** Extra inset around both endpoints when the camera frames a selected route. */
private val CAMERA_BOUNDS_PADDING_DP = 64.dp

/** Bottom camera padding that keeps positioned map content above the controls. */
private val MAP_BOTTOM_CONTENT_PADDING = 104.dp

/** Bottom ornament padding that keeps MapLibre attribution and branding clear of overlays. */
private val MAP_ORNAMENT_BOTTOM_PADDING = 208.dp

/** Zoom used when the camera first shows or explicitly recenters on the current origin. */
private const val DEFAULT_LOCATION_ZOOM = 16.0

/** Zoom used when origin and destination are too close to form useful camera bounds. */
private const val CLOSE_LOCATION_ZOOM = 18.0

/** Distance below which destination framing uses [CLOSE_LOCATION_ZOOM] instead of bounds. */
private const val MIN_BOUNDS_DISTANCE_METERS = 2.0

/** Maximum number of characters retained in either manual coordinate component. */
private const val MAX_COORDINATE_COMPONENT_LENGTH = 32
