/**
 * Defines the immutable state contract exposed by the Shahbaz map feature.
 *
 * The types in this file describe selected geographic points, location acquisition progress,
 * connectivity, permission precision, compass heading, and the guided flight-setup workflow
 * without owning Android lifecycle work.
 */
package ir.hrka.shahbaz.feature.map

import ir.hrka.compass.CompassReading
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

/** Identifies the active stage of the guided flight-setup panel. */
enum class FlightSetupStep {
    /** The user selects and reviews the destination and direct route. */
    DESTINATION,

    /** The user enters the height to climb above the local takeoff surface. */
    TAKEOFF_ALTITUDE,
}

/** Maximum number of characters retained for takeoff-altitude input. */
internal const val MAX_TAKEOFF_ALTITUDE_INPUT_LENGTH = 16

/** Decimal syntax accepted for a positive takeoff altitude without exponent notation. */
private val TakeoffAltitudePattern = Regex("(?:\\d+(?:[.,]\\d*)?|[.,]\\d+)")

/**
 * Parses a takeoff altitude expressed in meters.
 *
 * Both a period and comma are accepted as the decimal separator. Empty, malformed, non-finite,
 * zero, and negative values are rejected. No upper limit is imposed because aircraft or legal
 * limits are outside this feature's current product requirements.
 *
 * @param input User-entered altitude text.
 * @return A finite meter value greater than zero, or `null` when [input] is invalid.
 */
internal fun parseTakeoffAltitudeMeters(input: String): Double? {
    val normalizedInput = input.trim()
    if (!TakeoffAltitudePattern.matches(normalizedInput)) return null

    return normalizedInput
        .replace(',', '.')
        .toDoubleOrNull()
        ?.takeIf { altitude -> altitude.isFinite() && altitude > 0.0 }
}

/**
 * Immutable presentation state consumed by the map screen.
 *
 * @property locationStatus Current location acquisition or permission state.
 * @property origin Latest accepted device location, or `null` before a location is available.
 * @property destination User-selected destination, or `null` when none has been selected.
 * @property isOnline Whether the active network currently has validated internet access.
 * @property hasPrecisePermission Whether fine location permission is currently granted.
 * @property compassReading Complete device orientation from the reusable compass module, or
 * `null` when the compass is inactive or unavailable.
 * @property flightSetupStep Active destination or takeoff-altitude stage of the guided panel.
 * @property takeoffAltitudeInput Raw altitude text retained as the single input source of truth.
 * @property isTakeoffAltitudeConfirmed Whether the current valid altitude has been confirmed with
 * the second Next action.
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
    /** Complete device orientation, or `null` when the compass is inactive or unavailable. */
    val compassReading: CompassReading? = null,
    /** Active stage of the guided flight-setup panel. */
    val flightSetupStep: FlightSetupStep = FlightSetupStep.DESTINATION,
    /** Raw takeoff-altitude input in meters. */
    val takeoffAltitudeInput: String = "",
    /** Whether the currently parsed takeoff altitude was confirmed. */
    val isTakeoffAltitudeConfirmed: Boolean = false,
) {
    /** Validated takeoff altitude in meters, or `null` while the input is invalid. */
    val takeoffAltitudeMeters: Double?
        get() = parseTakeoffAltitudeMeters(takeoffAltitudeInput)
}

/**
 * Advances to altitude entry only when a destination exists.
 *
 * @return Updated state, or this state unchanged when no destination is selected.
 */
internal fun MapUiState.advanceToTakeoffAltitude(): MapUiState =
    if (destination == null) this else copy(flightSetupStep = FlightSetupStep.TAKEOFF_ALTITUDE)

/**
 * Returns to destination selection while preserving the destination and altitude draft.
 *
 * @return State with the destination step active.
 */
internal fun MapUiState.returnToDestinationSelection(): MapUiState =
    copy(flightSetupStep = FlightSetupStep.DESTINATION)

/**
 * Replaces the altitude draft and invalidates any earlier confirmation.
 *
 * @param input Latest user-entered altitude text.
 * @return State containing at most [MAX_TAKEOFF_ALTITUDE_INPUT_LENGTH] input characters.
 */
internal fun MapUiState.updateTakeoffAltitude(input: String): MapUiState = copy(
    takeoffAltitudeInput = input.take(MAX_TAKEOFF_ALTITUDE_INPUT_LENGTH),
    isTakeoffAltitudeConfirmed = false,
)

/**
 * Confirms the altitude only from the altitude step and only when its input is valid.
 *
 * @return Confirmed state, or this state unchanged when confirmation is not currently valid.
 */
internal fun MapUiState.confirmTakeoffAltitude(): MapUiState =
    if (
        flightSetupStep == FlightSetupStep.TAKEOFF_ALTITUDE &&
        takeoffAltitudeMeters != null
    ) {
        copy(isTakeoffAltitudeConfirmed = true)
    } else {
        this
    }
