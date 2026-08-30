/**
 * Defines the immutable state contract exposed by the Shahbaz map feature.
 *
 * The types in this file describe selected geographic points, location acquisition progress,
 * connectivity, permission precision, compass heading, and the guided flight-setup workflow
 * without owning Android lifecycle work.
 */
package ir.hrka.shahbaz.feature.map

import ir.hrka.compass.CompassFailure
import ir.hrka.compass.CompassReading
import ir.hrka.compass.CompassSensorSource
import ir.hrka.compass.CompassUnavailableReason
import ir.hrka.shahbaz.core.model.FlightPlan
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
 * One validated ground-speed sample reported by Android's location provider.
 *
 * @property metersPerSecond Non-negative speed in meters per second.
 * @property accuracyMetersPerSecond Optional non-negative one-sigma speed uncertainty.
 * @property timestampEpochMillis Epoch timestamp associated with the accepted location sample.
 */
data class PhoneSpeedReading(
    /**
     * Exposes the metersPerSecond value.
     */
    val metersPerSecond: Float,
    /**
     * Exposes the accuracyMetersPerSecond value.
     */
    val accuracyMetersPerSecond: Float?,
    /**
     * Exposes the timestampEpochMillis value.
     */
    val timestampEpochMillis: Long,
) {
    /** Rejects malformed platform values before they enter presentation state. */
    init {
        require(metersPerSecond.isFinite() && metersPerSecond >= 0f) {
            "Phone speed must be finite and non-negative"
        }
        require(
            accuracyMetersPerSecond == null ||
                accuracyMetersPerSecond.isFinite() && accuracyMetersPerSecond >= 0f
        ) {
            "Phone speed accuracy must be finite and non-negative when present"
        }
        require(timestampEpochMillis >= 0L) { "Phone speed timestamp cannot be negative" }
    }
}

/** Stable reasons why the phone cannot currently provide a usable speed sample. */
enum class PhoneSpeedUnavailableReason {
    /** Neither coarse nor fine location permission is granted. */
    LOCATION_PERMISSION_REQUIRED,

    /** Fine location permission required by this flight workflow is not granted. */
    PRECISE_LOCATION_PERMISSION_REQUIRED,

    /** Device location providers are disabled. */
    LOCATION_DISABLED,

    /** Location acquisition failed or a previously accepted fix became stale. */
    LOCATION_UNAVAILABLE,

    /** The accepted location did not contain a provider-supplied speed. */
    SPEED_NOT_REPORTED,

    /** The provider supplied a speed that violated the finite non-negative contract. */
    INVALID_READING,
}

/** Typed lifecycle and availability state for phone GPS speed. */
sealed interface PhoneSpeedStatus {
    /** Foreground location observation is not running. */
    data object Inactive : PhoneSpeedStatus

    /** Location observation is active but has not supplied an accepted fix yet. */
    data object AwaitingLocation : PhoneSpeedStatus

    /** A current validated speed sample is available. */
    data class Available(val reading: PhoneSpeedReading) : PhoneSpeedStatus

    /** Speed cannot currently be produced for a stable, actionable reason. */
    data class Unavailable(val reason: PhoneSpeedUnavailableReason) : PhoneSpeedStatus
}

/** Typed lifecycle, source, and failure state for the phone compass. */
sealed interface CompassSensorStatus {
    /** Compass observation is stopped because the feature is not in the foreground. */
    data object Inactive : CompassSensorStatus

    /** Sensor discovery or listener registration is in progress. */
    data object Starting : CompassSensorStatus

    /** Required listeners are registered using [source], but no reading has arrived yet. */
    data class AwaitingFirstSample(val source: CompassSensorSource) : CompassSensorStatus

    /** A current reading is being delivered using [source]. */
    data class Active(val source: CompassSensorSource) : CompassSensorStatus

    /** Registration succeeded, but the sensor produced no first sample before the deadline. */
    data class NoResponse(val source: CompassSensorSource) : CompassSensorStatus

    /** A prior reading exists, but callbacks stopped long enough for it to become stale. */
    data class Stale(val source: CompassSensorSource) : CompassSensorStatus

    /** The phone exposes no usable compass sensor strategy. */
    data class Unavailable(val reason: CompassUnavailableReason) : CompassSensorStatus

    /** Registration or active sample processing failed. */
    data class Failed(val failure: CompassFailure) : CompassSensorStatus
}

/** Pure timeout decision shared by the Android freshness timer and local JVM tests. */
internal fun compassTimeoutStatus(
    source: CompassSensorSource,
    hasPriorReading: Boolean,
): CompassSensorStatus = if (hasPriorReading) {
    CompassSensorStatus.Stale(source)
} else {
    CompassSensorStatus.NoResponse(source)
}

/** Returns whether a monotonic platform sample is absent or has reached its freshness deadline. */
internal fun isElapsedSampleStale(
    lastSampleElapsedRealtimeMillis: Long,
    nowElapsedRealtimeMillis: Long,
    staleAfterMillis: Long,
): Boolean {
    require(lastSampleElapsedRealtimeMillis >= 0L)
    require(nowElapsedRealtimeMillis >= lastSampleElapsedRealtimeMillis)
    require(staleAfterMillis > 0L)
    return lastSampleElapsedRealtimeMillis == 0L ||
        nowElapsedRealtimeMillis - lastSampleElapsedRealtimeMillis >= staleAfterMillis
}

/** Identifies the active stage of the guided flight-setup panel. */
enum class FlightSetupStep {
    /** The user selects and reviews the destination and direct route. */
    DESTINATION,

    /** The user enters the height to climb above the local takeoff surface. */
    TAKEOFF_ALTITUDE,
}

/** Stable reason why the altitude-step Next action is currently fail-closed. */
enum class TakeoffConfirmationBlocker {
    NOT_ALTITUDE_STEP,
    LIVE_ORIGIN_UNAVAILABLE,
    DESTINATION_UNAVAILABLE,
    INVALID_ALTITUDE,
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
 * @property compassStatus Typed lifecycle, selected-source, and failure state for the compass.
 * @property phoneSpeedStatus Typed availability and latest value for provider-supplied GPS speed.
 * @property flightSetupStep Active destination or takeoff-altitude stage of the guided panel.
 * @property takeoffAltitudeInput Raw altitude text retained as the single input source of truth.
 * @property confirmedFlightPlan Immutable route and altitude snapshot accepted by the second Next
 * action, or `null` while setup is unconfirmed.
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
    /** Typed lifecycle, source, and failure state for the compass. */
    val compassStatus: CompassSensorStatus = CompassSensorStatus.Inactive,
    /** Typed lifecycle, availability, and latest provider-supplied GPS speed. */
    val phoneSpeedStatus: PhoneSpeedStatus = PhoneSpeedStatus.Inactive,
    /** Active stage of the guided flight-setup panel. */
    val flightSetupStep: FlightSetupStep = FlightSetupStep.DESTINATION,
    /** Raw takeoff-altitude input in meters. */
    val takeoffAltitudeInput: String = "",
    /** Fixed route and target-altitude snapshot produced by successful confirmation. */
    val confirmedFlightPlan: FlightPlan? = null,
) {
    /** Validated takeoff altitude in meters, or `null` while the input is invalid. */
    val takeoffAltitudeMeters: Double?
        get() = parseTakeoffAltitudeMeters(takeoffAltitudeInput)

    /** Whether setup currently has a complete immutable flight-plan snapshot. */
    val isTakeoffAltitudeConfirmed: Boolean
        get() = confirmedFlightPlan != null

    /** True only while a current precise location is available as the takeoff origin. */
    val hasLiveOrigin: Boolean
        get() = locationStatus == LocationStatus.READY && origin != null

    /** Exact fail-closed reason used by both the reducer and altitude-step presentation. */
    val takeoffConfirmationBlocker: TakeoffConfirmationBlocker?
        get() = when {
            flightSetupStep != FlightSetupStep.TAKEOFF_ALTITUDE ->
                TakeoffConfirmationBlocker.NOT_ALTITUDE_STEP
            !hasLiveOrigin -> TakeoffConfirmationBlocker.LIVE_ORIGIN_UNAVAILABLE
            destination == null -> TakeoffConfirmationBlocker.DESTINATION_UNAVAILABLE
            takeoffAltitudeMeters == null -> TakeoffConfirmationBlocker.INVALID_ALTITUDE
            else -> null
        }

    /** Whether altitude-step Next may create an immutable flight plan right now. */
    val canConfirmTakeoffAltitude: Boolean
        get() = takeoffConfirmationBlocker == null
}

/**
 * Advances to altitude entry only when a destination exists.
 *
 * @return Updated state, or this state unchanged when no destination is selected.
 */
internal fun MapUiState.advanceToTakeoffAltitude(): MapUiState =
    if (destination == null) this else copy(flightSetupStep = FlightSetupStep.TAKEOFF_ALTITUDE)

/**
 * Replaces the selected destination and invalidates any flight plan confirmed for the old route.
 *
 * @param point Newly selected and optionally named destination.
 * @return State containing [point] with no confirmed flight plan.
 */
internal fun MapUiState.selectDestination(point: PlacePoint): MapUiState = copy(
    destination = point,
    confirmedFlightPlan = null,
)

/**
 * Removes the selected route and resets its dependent altitude workflow.
 *
 * @return Destination-step state with no destination, altitude draft, or confirmed flight plan.
 */
internal fun MapUiState.clearSelectedDestination(): MapUiState = copy(
    destination = null,
    flightSetupStep = FlightSetupStep.DESTINATION,
    takeoffAltitudeInput = "",
    confirmedFlightPlan = null,
)

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
    confirmedFlightPlan = null,
)

/**
 * Confirms the altitude only from the altitude step with valid input, destination, and live origin.
 *
 * @return Confirmed state, or this state unchanged when confirmation is not currently valid.
 */
internal fun MapUiState.confirmTakeoffAltitude(): MapUiState {
    if (!canConfirmTakeoffAltitude) return this
    val fixedOrigin = requireNotNull(origin).coordinate
    val fixedDestination = requireNotNull(destination).coordinate
    val targetAltitude = requireNotNull(takeoffAltitudeMeters)

    return copy(
        confirmedFlightPlan = FlightPlan(
            origin = fixedOrigin,
            destination = fixedDestination,
            targetAltitudeAboveOriginMeters = targetAltitude,
        )
    )
}

/**
 * Removes the accepted flight-plan snapshot without changing the current route or altitude draft.
 *
 * A host calls this when the user returns from the dashboard so setup must be confirmed again.
 *
 * @return State with no confirmed flight plan, or this state when already unconfirmed.
 */
internal fun MapUiState.clearConfirmedFlightPlan(): MapUiState =
    if (confirmedFlightPlan == null) this else copy(confirmedFlightPlan = null)
