/**
 * Defines the immutable state contract exposed by the Shahbaz map feature.
 *
 * The types in this file describe selected geographic points, location acquisition progress,
 * connectivity, permission precision, and compass heading without owning Android lifecycle work.
 */
package ir.hrka.shahbaz.feature.map

import ir.hrka.shahbaz.core.model.GeoCoordinate

/**
 * A named geographic point displayed by the map feature.
 *
 * @property coordinate Validated latitude and longitude of the point.
 * @property name Human-readable label or fallback name associated with the point.
 * @property hasResolvedName Whether [name] was produced by reverse geocoding rather than a
 * fallback resource.
 */
data class PlacePoint(
    /** Validated latitude and longitude of this point. */
    val coordinate: GeoCoordinate,
    /** Human-readable label or fallback name associated with this point. */
    val name: String,
    /** Whether [name] was produced by reverse geocoding. */
    val hasResolvedName: Boolean = false,
)

/** Describes the map feature's current ability to acquire and expose the device location. */
enum class LocationStatus {
    /** The app has neither fine nor coarse location permission. */
    PERMISSION_REQUIRED,

    /** Coarse permission may be available, but the feature requires fine location permission. */
    PRECISE_PERMISSION_REQUIRED,

    /** A precise device location is currently being requested. */
    LOCATING,

    /** A current origin is available for display and distance calculations. */
    READY,

    /** Device location providers are disabled. */
    LOCATION_DISABLED,

    /** Location providers are enabled, but no usable location could be obtained. */
    UNAVAILABLE,
}

/**
 * Immutable presentation state consumed by the map screen.
 *
 * @property locationStatus Current location acquisition or permission state.
 * @property origin Latest accepted device location, or `null` before a location is available.
 * @property destination User-selected destination, or `null` when none has been selected.
 * @property isOnline Whether the active network currently has validated internet access.
 * @property hasPrecisePermission Whether fine location permission is currently granted.
 * @property headingDegrees Clockwise device heading from north in the range `[0, 360)`, or `null`
 * when a heading is unavailable.
 */
data class MapUiState(
    /** Current location acquisition or permission state. */
    val locationStatus: LocationStatus = LocationStatus.PERMISSION_REQUIRED,
    /** Latest accepted device location, or `null` while no origin is available. */
    val origin: PlacePoint? = null,
    /** User-selected destination, or `null` when no destination has been selected. */
    val destination: PlacePoint? = null,
    /** Whether the active network has validated internet access. */
    val isOnline: Boolean = true,
    /** Whether Android fine location permission is granted. */
    val hasPrecisePermission: Boolean = false,
    /** Current device heading in degrees, or `null` when the compass is unavailable. */
    val headingDegrees: Float? = null,
)
