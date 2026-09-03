package ir.hrka.shahbaz.autopilot

import ir.hrka.shahbaz.flightcontracts.Quaterniond
import ir.hrka.shahbaz.flightcontracts.Vector3d
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max

/** Why a downward observation was not eligible for final-descent control. */
internal enum class GroundRangeRejection {
    MISSING,
    STALE_OR_FUTURE,
    LOW_QUALITY,
    EXCESSIVE_TILT,
    TIMESTAMP_REGRESSION,
    DISCONTINUITY,
    BAROMETER_DISAGREEMENT,
}

/** One deterministic decision from the landing-range handover state machine. */
internal data class GroundRangeLandingDecision(
    val state: LandingRangeAidState,
    val verticalDistanceMeters: Double? = null,
    val targetVerticalDistanceMeters: Double? = null,
    val controllerAltitudeTargetMeters: Double? = null,
    val descentFeedForwardMetersPerSecond: Double = 0.0,
    val rejection: GroundRangeRejection? = null,
    val lossGraceExpired: Boolean = false,
) {
    val controlsFinalDescent: Boolean
        get() = state == LandingRangeAidState.ACTIVE &&
            controllerAltitudeTargetMeters != null
}

/**
 * Conservative, allocation-free barometer-to-VL53L0X landing handover.
 *
 * The rangefinder never changes the estimator's reference. Once accepted, its AGL error is
 * translated into the controller's existing altitude coordinate:
 * `barometer altitude + (target AGL - measured AGL)`. Missing or rejected data close to the
 * surface produces a level altitude hold, never an unchecked fallback descent.
 */
internal class GroundRangeLandingAid(
    private val config: AutopilotConfig,
) {
    private var active = false
    private var consecutiveAcceptedSamples = 0
    private var lastAcceptedObservedAtNanos: Long? = null
    private var lastAcceptedVerticalDistanceMeters: Double? = null
    private var targetVerticalDistanceMeters: Double? = null
    private var targetObservedAtNanos: Long? = null
    private var targetClockNeedsRebase = false

    fun reset() {
        active = false
        consecutiveAcceptedSamples = 0
        lastAcceptedObservedAtNanos = null
        lastAcceptedVerticalDistanceMeters = null
        targetVerticalDistanceMeters = null
        targetObservedAtNanos = null
        targetClockNeedsRebase = false
    }

    fun update(
        nowNanos: Long,
        observation: AutopilotGroundRangeObservation?,
        estimatedAltitudeAboveOriginMeters: Double?,
        landingGroundAltitudeAboveOriginMeters: Double,
        attitudeBodyToNed: Quaterniond,
        maximumTrajectoryLeadMeters: Double,
    ): GroundRangeLandingDecision {
        require(nowNanos >= 0L)
        require(
            estimatedAltitudeAboveOriginMeters == null ||
                estimatedAltitudeAboveOriginMeters.isFinite(),
        )
        require(landingGroundAltitudeAboveOriginMeters.isFinite())
        require(maximumTrajectoryLeadMeters.isFinite() && maximumTrajectoryLeadMeters > 0.0)

        val estimatedAgl = estimatedAltitudeAboveOriginMeters?.minus(
            landingGroundAltitudeAboveOriginMeters,
        )
        val validation = validateObservation(
            nowNanos = nowNanos,
            observation = observation,
            estimatedAglMeters = estimatedAgl,
            attitudeBodyToNed = attitudeBodyToNed,
        )
        if (validation.rejection != null) {
            expireAcquisitionIfNeeded(nowNanos)
            return unavailableDecision(
                nowNanos = nowNanos,
                estimatedAglMeters = estimatedAgl,
                rejection = validation.rejection,
            )
        }

        val verticalDistance = requireNotNull(validation.verticalDistanceMeters)
        val observedAt = requireNotNull(observation).observedAtNanos
        val priorObservedAt = lastAcceptedObservedAtNanos
        val isNewSample = priorObservedAt == null || observedAt > priorObservedAt

        if (isNewSample) {
            val continuous = priorObservedAt != null &&
                observedAt - priorObservedAt <=
                millisToNanos(config.groundRangeMaximumSampleGapMillis)
            consecutiveAcceptedSamples = if (continuous) {
                consecutiveAcceptedSamples + 1
            } else {
                1
            }
            lastAcceptedObservedAtNanos = observedAt
            lastAcceptedVerticalDistanceMeters = verticalDistance
        }

        if (active && verticalDistance > config.groundRangeReleaseHeightMeters) {
            reset()
            return GroundRangeLandingDecision(LandingRangeAidState.INACTIVE)
        }

        if (!active) {
            if (
                verticalDistance <= config.groundRangeEngageHeightMeters &&
                consecutiveAcceptedSamples >= config.groundRangeRequiredConsecutiveSamples
            ) {
                active = true
                targetVerticalDistanceMeters = verticalDistance
                targetObservedAtNanos = observedAt
            } else {
                return GroundRangeLandingDecision(
                    state = if (requiresGroundRange(estimatedAgl, verticalDistance)) {
                        LandingRangeAidState.ACQUIRING
                    } else {
                        LandingRangeAidState.INACTIVE
                    },
                    verticalDistanceMeters = verticalDistance,
                )
            }
        }

        val previousTarget = requireNotNull(targetVerticalDistanceMeters)
        val priorTargetTimestamp = requireNotNull(targetObservedAtNanos)
        val elapsedSeconds = if (
            isNewSample && !targetClockNeedsRebase && observedAt > priorTargetTimestamp
        ) {
            (observedAt - priorTargetTimestamp) / NANOS_PER_SECOND
        } else {
            0.0
        }
        val scheduledTarget = (previousTarget -
            config.finalDescentRateMetersPerSecond * elapsedSeconds).coerceAtLeast(0.0)
        val leadLimitedTarget = max(
            scheduledTarget,
            verticalDistance - maximumTrajectoryLeadMeters,
        )
        if (isNewSample) {
            targetVerticalDistanceMeters = leadLimitedTarget
            targetObservedAtNanos = observedAt
            targetClockNeedsRebase = false
        }
        val targetDistance = requireNotNull(targetVerticalDistanceMeters)
        val altitudeTarget = estimatedAltitudeAboveOriginMeters?.plus(
            targetDistance - verticalDistance,
        )
        return GroundRangeLandingDecision(
            state = LandingRangeAidState.ACTIVE,
            verticalDistanceMeters = verticalDistance,
            targetVerticalDistanceMeters = targetDistance,
            controllerAltitudeTargetMeters = altitudeTarget,
            descentFeedForwardMetersPerSecond = if (targetDistance > 0.0) {
                config.finalDescentRateMetersPerSecond
            } else {
                0.0
            },
        )
    }

    private fun validateObservation(
        nowNanos: Long,
        observation: AutopilotGroundRangeObservation?,
        estimatedAglMeters: Double?,
        attitudeBodyToNed: Quaterniond,
    ): ObservationValidation {
        if (observation == null) return ObservationValidation(GroundRangeRejection.MISSING)
        if (
            observation.observedAtNanos > nowNanos ||
            nowNanos - observation.observedAtNanos >
            millisToNanos(config.maximumGroundRangeAgeMillis)
        ) {
            return ObservationValidation(GroundRangeRejection.STALE_OR_FUTURE)
        }
        if (observation.signalQualityPercent < config.groundRangeMinimumQualityPercent) {
            return ObservationValidation(GroundRangeRejection.LOW_QUALITY)
        }

        val bodyDownInNed = attitudeBodyToNed.normalized().rotate(Vector3d(0.0, 0.0, 1.0))
        val verticalProjection = bodyDownInNed.z
        if (verticalProjection < cos(config.groundRangeMaximumTiltRadians)) {
            return ObservationValidation(GroundRangeRejection.EXCESSIVE_TILT)
        }
        val verticalDistance = observation.distanceMeters * verticalProjection
        if (!verticalDistance.isFinite() || verticalDistance <= 0.0) {
            return ObservationValidation(GroundRangeRejection.EXCESSIVE_TILT)
        }

        val previousTimestamp = lastAcceptedObservedAtNanos
        val previousDistance = lastAcceptedVerticalDistanceMeters
        // A transport/store may present the same immutable sample across several controller
        // iterations. Reuse the projection accepted for that sample rather than letting a newer
        // attitude (or inconsistent duplicate payload) reinterpret old range data and move the
        // altitude target.
        if (
            previousTimestamp != null &&
            previousDistance != null &&
            observation.observedAtNanos == previousTimestamp
        ) {
            return ObservationValidation(verticalDistanceMeters = previousDistance)
        }
        if (previousTimestamp != null && observation.observedAtNanos < previousTimestamp) {
            return ObservationValidation(GroundRangeRejection.TIMESTAMP_REGRESSION)
        }
        if (
            previousTimestamp != null &&
            previousDistance != null &&
            observation.observedAtNanos > previousTimestamp
        ) {
            val elapsedSeconds = (observation.observedAtNanos - previousTimestamp) /
                NANOS_PER_SECOND
            val maximumChange = config.groundRangeJumpAllowanceMeters +
                config.groundRangeMaximumVerticalRateMetersPerSecond * elapsedSeconds
            if (abs(verticalDistance - previousDistance) > maximumChange) {
                return ObservationValidation(GroundRangeRejection.DISCONTINUITY)
            }
        }
        if (
            estimatedAglMeters != null &&
            abs(estimatedAglMeters - verticalDistance) >
            config.groundRangeMaximumBarometerDisagreementMeters
        ) {
            return ObservationValidation(GroundRangeRejection.BAROMETER_DISAGREEMENT)
        }
        return ObservationValidation(verticalDistanceMeters = verticalDistance)
    }

    private fun expireAcquisitionIfNeeded(nowNanos: Long) {
        val lastAcceptedAt = lastAcceptedObservedAtNanos
        if (
            !active &&
            (lastAcceptedAt == null || nowNanos - lastAcceptedAt >
                millisToNanos(config.groundRangeMaximumSampleGapMillis))
        ) {
            consecutiveAcceptedSamples = 0
        }
    }

    private fun unavailableDecision(
        nowNanos: Long,
        estimatedAglMeters: Double?,
        rejection: GroundRangeRejection,
    ): GroundRangeLandingDecision {
        // Freeze trajectory time as soon as active ranging is lost. The first recovered sample
        // rebases this clock instead of converting the invalid interval into a downward catch-up.
        if (active) targetClockNeedsRebase = true
        val holdRequired = active ||
            estimatedAglMeters == null ||
            estimatedAglMeters <= config.groundRangeReleaseHeightMeters
        return GroundRangeLandingDecision(
            state = if (holdRequired) {
                LandingRangeAidState.HOLDING_FOR_VALID_RANGE
            } else {
                LandingRangeAidState.INACTIVE
            },
            rejection = rejection,
            lossGraceExpired = if (!active) {
                holdRequired
            } else {
                lastAcceptedObservedAtNanos?.let { lastAccepted ->
                    nowNanos >= lastAccepted &&
                        nowNanos - lastAccepted > millisToNanos(config.groundRangeLossGraceMillis)
                } != false
            },
        )
    }

    private fun requiresGroundRange(estimatedAgl: Double?, measuredAgl: Double): Boolean =
        measuredAgl <= config.groundRangeEngageHeightMeters ||
            estimatedAgl == null ||
            estimatedAgl <= config.groundRangeReleaseHeightMeters

    private data class ObservationValidation(
        val rejection: GroundRangeRejection? = null,
        val verticalDistanceMeters: Double? = null,
    )
}

private fun millisToNanos(milliseconds: Long): Long =
    if (milliseconds > Long.MAX_VALUE / 1_000_000L) Long.MAX_VALUE else milliseconds * 1_000_000L

private const val NANOS_PER_SECOND = 1_000_000_000.0
