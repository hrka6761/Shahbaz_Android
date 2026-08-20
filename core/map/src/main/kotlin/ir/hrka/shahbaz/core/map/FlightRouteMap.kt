/** Reusable compact MapLibre presentation for a fixed flight route and a tracked position. */
package ir.hrka.shahbaz.core.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ir.hrka.shahbaz.core.model.GeoCoordinate
import kotlin.time.Duration.Companion.milliseconds
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
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonOptions
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.LineString
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position

/** Immutable coordinates rendered by [FlightRouteMap]. */
data class FlightRouteMapState(
    /** Fixed takeoff point used as the beginning of the direct route. */
    val origin: GeoCoordinate,
    /** Fixed user-selected endpoint of the direct route. */
    val destination: GeoCoordinate,
    /** Latest trustworthy tracked coordinate, or `null` while unavailable. */
    val currentPosition: GeoCoordinate? = null,
    /** Clockwise true/magnetic heading associated with [currentPosition], when available. */
    val currentPositionHeadingDegrees: Float? = null,
) {
    init {
        require(currentPosition != null || currentPositionHeadingDegrees == null) {
            "A heading requires a current tracked coordinate"
        }
        require(currentPositionHeadingDegrees == null || currentPositionHeadingDegrees.isFinite()) {
            "Current-position heading must be finite when present"
        }
    }

    /** Normalized clockwise heading used by MapLibre's directional text marker. */
    internal val normalizedPositionHeadingDegrees: Float?
        get() = currentPositionHeadingDegrees?.let(::normalizeHeadingDegrees)
}

/** Observable loading/connectivity result emitted by [FlightRouteMap]. */
enum class FlightMapLoadState {
    /** The remote style is still loading. */
    LOADING,

    /** The map style loaded and the route can be presented normally. */
    READY,

    /** The host reports no validated network connectivity. */
    OFFLINE,

    /** MapLibre failed to load the style or the load timed out. */
    ERROR,
}

/**
 * Displays a compact, read-only flight-route map suitable for a dashboard's map pane.
 *
 * The origin and destination are fixed blue/red markers joined by a straight line. A current
 * current tracked coordinate is teal; when a heading is available, a rotated triangular glyph makes the
 * marker directional. MapLibre logo and attribution ornaments remain enabled. Camera framing runs
 * once for each fixed route/style/retry generation, includes the tracked position when it is already
 * available, and does not fight later user panning as live coordinates change.
 *
 * Connectivity is supplied by the host because this library deliberately does not own network
 * monitoring. Loading, offline, and error presentation is contained within this composable while
 * [onLoadStateChanged] lets a feature record or mirror the same state.
 *
 * @param state fixed route and optional current tracked-position state.
 * @param isOnline whether the host currently has validated internet connectivity.
 * @param modifier layout modifier for the entire map and status presentation.
 * @param styleUri MapLibre style URI; defaults to the public OpenFreeMap Liberty style.
 * @param onLoadStateChanged invoked whenever the effective loading state changes.
 * @param onRetryRequested invoked after the internal map instance has been reset for a retry.
 */
@Composable
fun FlightRouteMap(
    state: FlightRouteMapState,
    isOnline: Boolean,
    modifier: Modifier = Modifier,
    styleUri: String = DEFAULT_FLIGHT_MAP_STYLE_URI,
    onLoadStateChanged: (FlightMapLoadState) -> Unit = {},
    onRetryRequested: () -> Unit = {},
) {
    require(styleUri.isNotBlank()) { "Map style URI cannot be blank" }

    val cameraState = rememberCameraState()
    val currentOnLoadStateChanged by rememberUpdatedState(onLoadStateChanged)
    val currentOnRetryRequested by rememberUpdatedState(onRetryRequested)
    var retryGeneration by remember { mutableIntStateOf(0) }
    var mapLoaded by remember(styleUri, retryGeneration) { mutableStateOf(false) }
    var mapLoadFailed by remember(styleUri, retryGeneration) { mutableStateOf(false) }
    var mapLoadTimedOut by remember(styleUri, retryGeneration) { mutableStateOf(false) }
    val fixedRouteKey = remember(state.origin, state.destination) {
        state.origin to state.destination
    }
    var cameraFramed by remember(styleUri, retryGeneration, fixedRouteKey) {
        mutableStateOf(false)
    }
    val bounds = remember(state.origin, state.destination, state.currentPosition) {
        calculateFlightMapBounds(state.origin, state.destination, state.currentPosition)
    }
    val effectiveLoadState = when {
        !isOnline -> FlightMapLoadState.OFFLINE
        mapLoadFailed || mapLoadTimedOut -> FlightMapLoadState.ERROR
        mapLoaded -> FlightMapLoadState.READY
        else -> FlightMapLoadState.LOADING
    }
    val mapDescription = stringResource(
        if (state.currentPosition == null) {
            R.string.flight_map_description
        } else {
            R.string.flight_map_description_with_position
        }
    )

    LaunchedEffect(effectiveLoadState) {
        currentOnLoadStateChanged(effectiveLoadState)
    }

    LaunchedEffect(mapLoaded, mapLoadFailed, isOnline, retryGeneration, styleUri) {
        mapLoadTimedOut = false
        if (!mapLoaded && !mapLoadFailed && isOnline) {
            delay(MAP_LOAD_TIMEOUT_MILLIS)
            if (!mapLoaded && !mapLoadFailed && isOnline) mapLoadTimedOut = true
        }
    }

    LaunchedEffect(mapLoaded, cameraFramed, bounds, fixedRouteKey, retryGeneration) {
        if (!mapLoaded || cameraFramed) return@LaunchedEffect
        if (bounds.isEffectivelyPoint) {
            cameraState.animateTo(
                finalPosition = CameraPosition(
                    target = bounds.center.toPosition(),
                    zoom = CLOSE_ROUTE_ZOOM,
                    padding = CAMERA_CONTENT_PADDING,
                ),
                duration = CAMERA_ANIMATION_MILLIS.milliseconds,
            )
        } else {
            cameraState.animateTo(
                boundingBox = BoundingBox(
                    west = bounds.west,
                    south = bounds.south,
                    east = bounds.east,
                    north = bounds.north,
                ),
                padding = CAMERA_CONTENT_PADDING,
                duration = CAMERA_ANIMATION_MILLIS.milliseconds,
            )
        }
        cameraFramed = true
    }

    Box(
        modifier = modifier
            .background(MAP_FALLBACK_COLOR)
            .semantics { contentDescription = mapDescription }
    ) {
        key(styleUri, retryGeneration) {
            MaplibreMap(
                modifier = Modifier.fillMaxSize(),
                baseStyle = BaseStyle.Uri(styleUri),
                cameraState = cameraState,
                options = MapOptions(
                    gestureOptions = GestureOptions(
                        isRotateEnabled = false,
                        isScrollEnabled = true,
                        isTiltEnabled = false,
                        isZoomEnabled = true,
                    ),
                    ornamentOptions = OrnamentOptions(
                        padding = ORNAMENT_PADDING,
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
                    mapLoadFailed = false
                    mapLoadTimedOut = false
                },
                onMapLoadFailed = {
                    mapLoaded = false
                    mapLoadFailed = true
                },
            ) {
                FlightRouteOverlays(state)
            }
        }

        FlightMapStatusPresentation(
            state = effectiveLoadState,
            mapAlreadyLoaded = mapLoaded,
            onRetry = {
                retryGeneration += 1
                currentOnRetryRequested()
            },
            modifier = Modifier.align(Alignment.Center),
        )
    }
}

/** Adds the direct route and origin, destination, and optional tracked-position markers. */
@Composable
private fun FlightRouteOverlays(state: FlightRouteMapState) {
    val origin = state.origin.toPosition()
    val destination = state.destination.toPosition()
    val currentPosition = state.currentPosition?.toPosition()
    val routeSource = rememberGeoJsonSource(
        data = lineData(origin, destination),
        options = DYNAMIC_SOURCE_OPTIONS,
    )
    val originSource = rememberGeoJsonSource(
        data = pointData(origin),
        options = DYNAMIC_SOURCE_OPTIONS,
    )
    val destinationSource = rememberGeoJsonSource(
        data = pointData(destination),
        options = DYNAMIC_SOURCE_OPTIONS,
    )
    val positionSource = rememberGeoJsonSource(
        data = pointData(currentPosition),
        options = DYNAMIC_SOURCE_OPTIONS,
    )

    LineLayer(
        id = "shahbaz-flight-route-halo",
        source = routeSource,
        color = const(Color.White.copy(alpha = 0.86f)),
        width = const(5.4.dp),
        cap = const(LineCap.Round),
        join = const(LineJoin.Round),
    )
    LineLayer(
        id = "shahbaz-flight-route",
        source = routeSource,
        color = const(ROUTE_COLOR),
        width = const(3.dp),
        cap = const(LineCap.Round),
        join = const(LineJoin.Round),
    )
    CircleLayer(
        id = "shahbaz-flight-origin",
        source = originSource,
        color = const(ORIGIN_COLOR),
        radius = const(8.dp),
        strokeColor = const(Color.White),
        strokeWidth = const(3.dp),
    )
    CircleLayer(
        id = "shahbaz-flight-destination",
        source = destinationSource,
        color = const(DESTINATION_COLOR),
        radius = const(9.dp),
        strokeColor = const(Color.White),
        strokeWidth = const(3.dp),
    )

    if (currentPosition != null) {
        CircleLayer(
            id = "shahbaz-flight-current-position",
            source = positionSource,
            color = const(CURRENT_POSITION_COLOR),
            radius = const(10.dp),
            strokeColor = const(Color.White),
            strokeWidth = const(3.dp),
        )
        if (
            positionMarkerKind(
                state.currentPosition,
                state.currentPositionHeadingDegrees,
            ) == PositionMarkerKind.DIRECTIONAL
        ) {
            SymbolLayer(
                id = "shahbaz-flight-current-position-heading",
                source = positionSource,
                textField = format(span(CURRENT_POSITION_DIRECTION_GLYPH)),
                textSize = const(14.sp),
                textColor = const(Color.White),
                textHaloColor = const(CURRENT_POSITION_COLOR),
                textHaloWidth = const(1.dp),
                textRotate = const(requireNotNull(state.normalizedPositionHeadingDegrees)),
                textKeepUpright = const(false),
                textAllowOverlap = const(true),
                textIgnorePlacement = const(true),
            )
        }
    }
}

/** Renders loading, blocking offline/error, or non-blocking offline status over the map. */
@Composable
private fun BoxScope.FlightMapStatusPresentation(
    state: FlightMapLoadState,
    mapAlreadyLoaded: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state == FlightMapLoadState.READY) return

    val compactOfflineNotice = state == FlightMapLoadState.OFFLINE && mapAlreadyLoaded
    val title = when (state) {
        FlightMapLoadState.LOADING -> stringResource(R.string.flight_map_loading)
        FlightMapLoadState.OFFLINE -> stringResource(R.string.flight_map_offline_title)
        FlightMapLoadState.ERROR -> stringResource(R.string.flight_map_error_title)
        FlightMapLoadState.READY -> return
    }
    val message = when (state) {
        FlightMapLoadState.OFFLINE -> stringResource(R.string.flight_map_offline_message)
        FlightMapLoadState.ERROR -> stringResource(R.string.flight_map_error_message)
        FlightMapLoadState.LOADING,
        FlightMapLoadState.READY -> null
    }

    if (!compactOfflineNotice) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.24f)),
            contentAlignment = Alignment.Center,
        ) {
            FlightMapStatusCard(
                state = state,
                title = title,
                message = message,
                onRetry = onRetry,
                modifier = modifier,
            )
        }
    } else {
        FlightMapStatusCard(
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

/** Compact status surface shared by blocking and non-blocking map states. */
@Composable
private fun FlightMapStatusCard(
    state: FlightMapLoadState,
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
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 4.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state == FlightMapLoadState.LOADING) {
                CircularProgressIndicator()
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            if (message != null) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (state == FlightMapLoadState.OFFLINE || state == FlightMapLoadState.ERROR) {
                Button(onClick = onRetry) {
                    Text(stringResource(R.string.flight_map_retry))
                }
            }
        }
    }
}

/** Creates one optional point feature for a dynamic MapLibre source. */
private fun pointData(position: Position?): GeoJsonData {
    val features: List<Feature<Point, JsonObject?>> = position?.let {
        listOf(Feature(geometry = Point(it), properties = null))
    }.orEmpty()
    return GeoJsonData.Features(FeatureCollection(features))
}

/** Creates the fixed two-vertex route line shown between origin and destination. */
private fun lineData(start: Position, end: Position): GeoJsonData =
    GeoJsonData.Features(
        FeatureCollection(
            listOf(
                Feature(
                    geometry = LineString(start, end),
                    properties = null,
                )
            )
        )
    )

/** Converts the latitude/longitude domain model to GeoJSON's longitude/latitude order. */
private fun GeoCoordinate.toPosition(): Position = Position(
    longitude = longitude,
    latitude = latitude,
)

/** Public default style so a host can explicitly reuse or replace the configured map source. */
const val DEFAULT_FLIGHT_MAP_STYLE_URI = "https://tiles.openfreemap.org/styles/liberty"

/** Source policy that applies tracked-position changes atomically to the active map style. */
private val DYNAMIC_SOURCE_OPTIONS = GeoJsonOptions(synchronousUpdate = true)

/** Camera/ornament insets leave compact controls and mandatory attribution unobstructed. */
private val CAMERA_CONTENT_PADDING = PaddingValues(
    start = 28.dp,
    top = 28.dp,
    end = 28.dp,
    bottom = 52.dp,
)

/** Padding used by the mandatory MapLibre logo and attribution ornaments. */
private val ORNAMENT_PADDING = PaddingValues(8.dp)

/** Neutral background visible before MapLibre has produced its first frame. */
private val MAP_FALLBACK_COLOR = Color(0xFFE6ECE8)

/** High-contrast direct-route stroke color. */
private val ROUTE_COLOR = Color(0xFF263238)

/** Conventional blue origin marker. */
private val ORIGIN_COLOR = Color(0xFF1976D2)

/** Conventional red destination marker. */
private val DESTINATION_COLOR = Color(0xFFD32F2F)

/** Teal tracked-position marker distinct from both fixed route endpoints. */
private val CURRENT_POSITION_COLOR = Color(0xFF00897B)

/** Up-pointing glyph whose clockwise rotation represents the tracked heading. */
private const val CURRENT_POSITION_DIRECTION_GLYPH = "\u25B2"

/** Online wait before an unfinished map load becomes a recoverable error. */
private const val MAP_LOAD_TIMEOUT_MILLIS = 15_000L

/** Short animation used only for initial or explicit retry route framing. */
private const val CAMERA_ANIMATION_MILLIS = 700L

/** Stable camera zoom used when all route points are effectively colocated. */
private const val CLOSE_ROUTE_ZOOM = 16.5
