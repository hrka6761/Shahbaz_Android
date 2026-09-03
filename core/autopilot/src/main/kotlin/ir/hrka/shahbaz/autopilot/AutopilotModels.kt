package ir.hrka.shahbaz.autopilot

import ir.hrka.shahbaz.core.model.FlightPlan
import ir.hrka.shahbaz.core.model.GeoCoordinate
import ir.hrka.shahbaz.flightcontracts.FlightControlCommand
import ir.hrka.shahbaz.flightcontracts.FlightControllerLifecycleRequest
import ir.hrka.shahbaz.flightcontracts.FlightControllerSnapshot
import ir.hrka.shahbaz.flightcontracts.Vector3d
import kotlin.math.PI

/** Operator intent consumed once by an [Autopilot] step. */
enum class AutopilotRequest {
    NONE,
    START,
    ABORT,
    EMERGENCY_STOP,
}

/** Observable phase of the point-to-point mission state machine. */
enum class AutopilotPhase {
    STANDBY,
    PREFLIGHT,
    ARMING,
    TAKEOFF,
    CRUISE,
    LANDING,
    RETURN_CLIMB,
    RETURNING,
    DISARMING,
    COMPLETED,
    ABORTED,
    FAILED,
    EMERGENCY_STOPPED,
}

/** Source-reported landed state; unavailable is never interpreted as ground contact. */
enum class LandedState {
    UNAVAILABLE,
    ON_GROUND,
    AIRBORNE,
}

/** Fresh absolute navigation fix used to bind the saved mission to the live aircraft. */
data class AutopilotNavigationFix(
    val coordinate: GeoCoordinate,
    val horizontalAccuracyMeters: Double,
    val observedAtNanos: Long,
) {
    init {
        require(horizontalAccuracyMeters.isFinite() && horizontalAccuracyMeters >= 0.0)
        require(observedAtNanos >= 0L)
    }
}

/** Independent land-detector observation. Barometric altitude alone must not create this value. */
data class AutopilotLandingObservation(
    val state: LandedState = LandedState.UNAVAILABLE,
    val observedAtNanos: Long? = null,
) {
    init {
        require(observedAtNanos == null || observedAtNanos >= 0L)
        require((state == LandedState.UNAVAILABLE) == (observedAtNanos == null)) {
            "Unavailable landing state must have no timestamp, and available state must have one"
        }
    }
}

/**
 * One independently validated downward time-of-flight observation.
 *
 * The composition layer creates this only from the physical Ground VL53L0X instance after its
 * transport schema, optical range status, configured limits, and health flags have all passed.
 * The autopilot still performs freshness, quality, tilt, continuity, and disagreement checks.
 */
data class AutopilotGroundRangeObservation(
    val distanceMeters: Double,
    val signalQualityPercent: Int,
    val observedAtNanos: Long,
) {
    init {
        require(distanceMeters.isFinite() && distanceMeters > 0.0)
        require(signalQualityPercent in 0..100)
        require(observedAtNanos >= 0L)
    }
}

/** Observable state of the conservative barometer-to-ground-range landing handover. */
enum class LandingRangeAidState {
    INACTIVE,
    ACQUIRING,
    ACTIVE,
    HOLDING_FOR_VALID_RANGE,
}

/** Fresh preflight/in-flight decisions owned by power, route, geofence, and landing-zone modules. */
data class AutopilotSafetyStatus(
    val routeAndAirspaceClear: Boolean = false,
    val destinationLandingZoneClear: Boolean = false,
    val energyReserveSufficient: Boolean = false,
    val geofenceHealthy: Boolean = false,
    val windWithinLimits: Boolean = false,
    val observedAtNanos: Long? = null,
) {
    init {
        require(observedAtNanos == null || observedAtNanos >= 0L)
    }

    val isAvailable: Boolean
        get() = observedAtNanos != null
}

/** Inputs sampled before one synchronous autopilot decision. */
data class AutopilotInput(
    val timestampNanos: Long,
    val flightController: FlightControllerSnapshot,
    val navigationFix: AutopilotNavigationFix? = null,
    val landingObservation: AutopilotLandingObservation = AutopilotLandingObservation(),
    val groundRange: AutopilotGroundRangeObservation? = null,
    val safetyStatus: AutopilotSafetyStatus = AutopilotSafetyStatus(),
) {
    init {
        require(timestampNanos >= 0L)
    }
}

/** Stable machine-readable safety or mission reason. */
enum class AutopilotIssueCode {
    MISSION_DISTANCE_EXCEEDED,
    CRUISE_ALTITUDE_EXCEEDED,
    DESTINATION_GROUND_OFFSET_EXCEEDED,
    INSUFFICIENT_CRUISE_CLEARANCE,
    NAVIGATION_FIX_MISSING,
    NAVIGATION_FIX_STALE,
    NAVIGATION_FIX_INACCURATE,
    AIRCRAFT_TOO_FAR_FROM_ORIGIN,
    CONTROLLER_REFERENCE_UNAVAILABLE,
    CONTROLLER_REFERENCE_MISMATCH,
    LANDING_DETECTOR_UNAVAILABLE,
    LANDING_OBSERVATION_STALE,
    AIRCRAFT_NOT_ON_GROUND,
    SAFETY_STATUS_UNAVAILABLE,
    SAFETY_STATUS_STALE,
    ROUTE_OR_AIRSPACE_UNSAFE,
    DESTINATION_LANDING_ZONE_UNSAFE,
    ENERGY_RESERVE_INSUFFICIENT,
    GEOFENCE_UNHEALTHY,
    WIND_LIMIT_EXCEEDED,
    FLIGHT_CONTROLLER_NOT_DISARMED,
    FLIGHT_CONTROLLER_NOT_READY,
    FLIGHT_CONTROLLER_FAILSAFE,
    FLIGHT_CONTROLLER_EMERGENCY_STOPPED,
    FLIGHT_CONTROLLER_DISARMED_IN_FLIGHT,
    LOCAL_POSITION_UNAVAILABLE,
    GROUND_RANGE_UNUSABLE,
    ROUTE_CORRIDOR_EXCEEDED,
    PHASE_TIMEOUT,
    MISSION_TIMEOUT,
    TIMESTAMP_NOT_MONOTONIC,
    COMMAND_SEQUENCE_EXHAUSTED,
}

/** One diagnostic attached to the current autopilot decision. */
data class AutopilotIssue(
    val code: AutopilotIssueCode,
    val message: String,
) {
    init {
        require(message.isNotBlank())
    }
}

/** Tunable policy. Aircraft/controller gains and motor output do not belong here. */
data class AutopilotConfig(
    val commandValidityMillis: Long = 250L,
    val maximumNavigationAgeMillis: Long = 1_500L,
    val maximumLandingObservationAgeMillis: Long = 1_000L,
    val maximumSafetyStatusAgeMillis: Long = 2_000L,
    val maximumNavigationAccuracyMeters: Double = 10.0,
    val maximumOriginErrorMeters: Double = 15.0,
    val maximumMissionDistanceMeters: Double = 2_000.0,
    val maximumCruiseAltitudeAboveOriginMeters: Double = 120.0,
    val maximumDestinationGroundOffsetMeters: Double = 120.0,
    val minimumCruiseClearanceMeters: Double = 5.0,
    val routeCorridorRadiusMeters: Double = 40.0,
    val takeoffMaximumSpeedMetersPerSecond: Double = 1.5,
    val takeoffMaximumAccelerationMetersPerSecondSquared: Double = 0.75,
    val cruiseMaximumSpeedMetersPerSecond: Double = 4.0,
    val cruiseMaximumAccelerationMetersPerSecondSquared: Double = 1.0,
    val maximumTrajectoryLeadMeters: Double = 3.0,
    val targetSettleMillis: Long = 1_000L,
    val touchdownConfirmationMillis: Long = 1_000L,
    val touchdownHorizontalToleranceMeters: Double = 1.5,
    val touchdownAltitudeToleranceMeters: Double = 1.0,
    val maximumTouchdownHorizontalSpeedMetersPerSecond: Double = 0.75,
    val maximumTouchdownVerticalSpeedMetersPerSecond: Double = 0.7,
    val landingDescentRateMetersPerSecond: Double = 0.8,
    val finalDescentRateMetersPerSecond: Double = 0.25,
    val landingMaximumAccelerationMetersPerSecondSquared: Double = 0.5,
    val flareHeightMeters: Double = 2.0,
    val maximumGroundRangeAgeMillis: Long = 250L,
    val groundRangeEngageHeightMeters: Double = 2.0,
    val groundRangeReleaseHeightMeters: Double = 2.2,
    val groundRangeMinimumQualityPercent: Int = 50,
    val groundRangeRequiredConsecutiveSamples: Int = 3,
    val groundRangeMaximumSampleGapMillis: Long = 200L,
    val groundRangeLossGraceMillis: Long = 300L,
    val groundRangeMaximumTiltRadians: Double = PI / 6.0,
    val groundRangeMaximumVerticalRateMetersPerSecond: Double = 3.0,
    val groundRangeJumpAllowanceMeters: Double = 0.15,
    val groundRangeMaximumBarometerDisagreementMeters: Double = 1.5,
    val requireGroundRangeForTouchdown: Boolean = true,
    val maximumTouchdownGroundRangeMeters: Double = 0.5,
    val preflightTimeoutMillis: Long = 30_000L,
    val armingTimeoutMillis: Long = 5_000L,
    val takeoffTimeoutMillis: Long = 180_000L,
    val cruiseTimeoutMillis: Long = 900_000L,
    val landingTimeoutMillis: Long = 240_000L,
    val returnTimeoutMillis: Long = 900_000L,
    val maximumMissionDurationMillis: Long = 1_800_000L,
) {
    init {
        require(commandValidityMillis > 0L)
        require(maximumNavigationAgeMillis > 0L)
        require(maximumLandingObservationAgeMillis > 0L)
        require(maximumSafetyStatusAgeMillis > 0L)
        require(maximumNavigationAccuracyMeters.isFinite() && maximumNavigationAccuracyMeters > 0.0)
        require(maximumOriginErrorMeters.isFinite() && maximumOriginErrorMeters > 0.0)
        require(maximumMissionDistanceMeters.isFinite() && maximumMissionDistanceMeters > 0.0)
        require(
            maximumCruiseAltitudeAboveOriginMeters.isFinite() &&
                maximumCruiseAltitudeAboveOriginMeters > 0.0,
        )
        require(
            maximumDestinationGroundOffsetMeters.isFinite() &&
                maximumDestinationGroundOffsetMeters > 0.0,
        )
        require(minimumCruiseClearanceMeters.isFinite() && minimumCruiseClearanceMeters > 0.0)
        require(routeCorridorRadiusMeters.isFinite() && routeCorridorRadiusMeters > 0.0)
        require(
            takeoffMaximumSpeedMetersPerSecond.isFinite() &&
                takeoffMaximumSpeedMetersPerSecond > 0.0,
        )
        require(
            takeoffMaximumAccelerationMetersPerSecondSquared.isFinite() &&
                takeoffMaximumAccelerationMetersPerSecondSquared > 0.0,
        )
        require(
            cruiseMaximumSpeedMetersPerSecond.isFinite() &&
                cruiseMaximumSpeedMetersPerSecond > 0.0,
        )
        require(
            cruiseMaximumAccelerationMetersPerSecondSquared.isFinite() &&
                cruiseMaximumAccelerationMetersPerSecondSquared > 0.0,
        )
        require(maximumTrajectoryLeadMeters.isFinite() && maximumTrajectoryLeadMeters > 0.0)
        require(targetSettleMillis >= 0L)
        require(touchdownConfirmationMillis >= 0L)
        require(touchdownHorizontalToleranceMeters.isFinite() && touchdownHorizontalToleranceMeters > 0.0)
        require(touchdownAltitudeToleranceMeters.isFinite() && touchdownAltitudeToleranceMeters > 0.0)
        require(
            maximumTouchdownHorizontalSpeedMetersPerSecond.isFinite() &&
                maximumTouchdownHorizontalSpeedMetersPerSecond >= 0.0,
        )
        require(
            maximumTouchdownVerticalSpeedMetersPerSecond.isFinite() &&
                maximumTouchdownVerticalSpeedMetersPerSecond >= 0.0,
        )
        require(landingDescentRateMetersPerSecond.isFinite() && landingDescentRateMetersPerSecond > 0.0)
        require(finalDescentRateMetersPerSecond.isFinite() && finalDescentRateMetersPerSecond > 0.0)
        require(finalDescentRateMetersPerSecond <= landingDescentRateMetersPerSecond)
        require(
            landingMaximumAccelerationMetersPerSecondSquared.isFinite() &&
                landingMaximumAccelerationMetersPerSecondSquared > 0.0,
        )
        require(flareHeightMeters.isFinite() && flareHeightMeters > 0.0)
        require(maximumGroundRangeAgeMillis > 0L)
        require(
            groundRangeEngageHeightMeters.isFinite() && groundRangeEngageHeightMeters > 0.0,
        )
        require(
            groundRangeReleaseHeightMeters.isFinite() &&
                groundRangeReleaseHeightMeters > groundRangeEngageHeightMeters,
        )
        require(groundRangeMinimumQualityPercent in 1..100)
        require(groundRangeRequiredConsecutiveSamples >= 2)
        require(groundRangeMaximumSampleGapMillis > 0L)
        require(groundRangeLossGraceMillis >= 0L)
        require(groundRangeMaximumTiltRadians.isFinite() && groundRangeMaximumTiltRadians in 0.0..PI / 2.0)
        require(
            groundRangeMaximumVerticalRateMetersPerSecond.isFinite() &&
                groundRangeMaximumVerticalRateMetersPerSecond > 0.0,
        )
        require(groundRangeJumpAllowanceMeters.isFinite() && groundRangeJumpAllowanceMeters >= 0.0)
        require(
            groundRangeMaximumBarometerDisagreementMeters.isFinite() &&
                groundRangeMaximumBarometerDisagreementMeters > 0.0,
        )
        require(
            maximumTouchdownGroundRangeMeters.isFinite() &&
                maximumTouchdownGroundRangeMeters > 0.0 &&
                maximumTouchdownGroundRangeMeters <= groundRangeEngageHeightMeters,
        )
        require(preflightTimeoutMillis > 0L)
        require(armingTimeoutMillis > 0L)
        require(takeoffTimeoutMillis > 0L)
        require(cruiseTimeoutMillis > 0L)
        require(landingTimeoutMillis > 0L)
        require(returnTimeoutMillis > 0L)
        require(maximumMissionDurationMillis > 0L)
    }
}

/** Immutable latest state for monitoring and UI presentation. */
data class AutopilotSnapshot(
    val phase: AutopilotPhase,
    val flightPlan: FlightPlan,
    val abortingToOrigin: Boolean = false,
    val activeTargetLocalNedMeters: Vector3d? = null,
    val landingRangeAidState: LandingRangeAidState = LandingRangeAidState.INACTIVE,
    val issues: List<AutopilotIssue> = emptyList(),
)

/** Command/lifecycle pair that the composition layer must pass to one controller step. */
data class AutopilotOutput(
    val timestampNanos: Long,
    val snapshot: AutopilotSnapshot,
    val flightControlCommand: FlightControlCommand?,
    val lifecycleRequest: FlightControllerLifecycleRequest,
)

/** Synchronous autonomous policy boundary; callers serialize [step] with controller execution. */
interface Autopilot {
    val snapshot: kotlinx.coroutines.flow.StateFlow<AutopilotSnapshot>

    fun step(
        input: AutopilotInput,
        request: AutopilotRequest = AutopilotRequest.NONE,
    ): AutopilotOutput

    companion object {
        fun create(
            flightPlan: FlightPlan,
            config: AutopilotConfig = AutopilotConfig(),
        ): Autopilot = PointToPointAutopilot(flightPlan, config)
    }
}

/** Stable module entry point retained for consumers of the original placeholder artifact. */
object AutopilotModule {
    fun create(
        flightPlan: FlightPlan,
        config: AutopilotConfig = AutopilotConfig(),
    ): Autopilot = Autopilot.create(flightPlan, config)
}
