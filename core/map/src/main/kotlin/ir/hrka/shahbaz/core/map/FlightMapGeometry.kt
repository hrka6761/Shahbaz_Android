/** Pure geographic decisions used by the reusable flight-route map presentation. */
package ir.hrka.shahbaz.core.map

import ir.hrka.shahbaz.core.model.GeoCoordinate

/** Geographic extent used to frame the fixed route and an optional current tracked position. */
internal data class FlightMapBounds(
    val west: Double,
    val south: Double,
    val east: Double,
    val north: Double,
) {
    init {
        require(west.isFinite() && west in -180.0..180.0)
        require(east.isFinite() && east in -180.0..180.0)
        require(south.isFinite() && south in -90.0..90.0)
        require(north.isFinite() && north in -90.0..90.0)
        require(west <= east)
        require(south <= north)
    }

    /** Center used when the extent is too small for a useful bounds animation. */
    val center: GeoCoordinate
        get() = GeoCoordinate(
            latitude = (south + north) / 2.0,
            longitude = (west + east) / 2.0,
        )

    /** Whether the extent is effectively a single point at ordinary map precision. */
    val isEffectivelyPoint: Boolean
        get() = east - west <= EFFECTIVE_POINT_SPAN_DEGREES &&
            north - south <= EFFECTIVE_POINT_SPAN_DEGREES
}

/** Marker variants supported for an optional current coordinate and heading. */
internal enum class PositionMarkerKind {
    /** No current coordinate is available. */
    NONE,

    /** A current coordinate is available without a heading. */
    POSITION,

    /** Both coordinate and heading are available, so a directional marker can be rendered. */
    DIRECTIONAL,
}

/** Calculates conventional west/south/east/north bounds containing every available route point. */
internal fun calculateFlightMapBounds(
    origin: GeoCoordinate,
    destination: GeoCoordinate,
    currentPosition: GeoCoordinate?,
): FlightMapBounds {
    val coordinates = listOfNotNull(origin, destination, currentPosition)
    return FlightMapBounds(
        west = coordinates.minOf(GeoCoordinate::longitude),
        south = coordinates.minOf(GeoCoordinate::latitude),
        east = coordinates.maxOf(GeoCoordinate::longitude),
        north = coordinates.maxOf(GeoCoordinate::latitude),
    )
}

/** Selects whether no marker, a position dot, or a heading marker should be displayed. */
internal fun positionMarkerKind(
    currentPosition: GeoCoordinate?,
    headingDegrees: Float?,
): PositionMarkerKind = when {
    currentPosition == null -> PositionMarkerKind.NONE
    headingDegrees == null -> PositionMarkerKind.POSITION
    else -> PositionMarkerKind.DIRECTIONAL
}

/** Normalizes a finite clockwise heading to the half-open `0 <= value < 360` interval. */
internal fun normalizeHeadingDegrees(headingDegrees: Float): Float {
    require(headingDegrees.isFinite()) { "Current-position heading must be finite" }
    return ((headingDegrees % FULL_TURN_DEGREES) + FULL_TURN_DEGREES) % FULL_TURN_DEGREES
}

/** Span below which camera fitting uses a stable point zoom instead of degenerate bounds. */
private const val EFFECTIVE_POINT_SPAN_DEGREES = 0.000_01

/** Degrees in one complete heading turn. */
private const val FULL_TURN_DEGREES = 360f
