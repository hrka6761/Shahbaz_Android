package ir.hrka.shahbaz.autopilot

import kotlin.math.sqrt

/** One deterministic sample from a one-dimensional motion profile. */
internal data class MotionProfileSample(
    val positionMeters: Double,
    val velocityMetersPerSecond: Double,
    val complete: Boolean,
)

/**
 * Rest-to-rest motion over a non-negative distance with symmetric constant acceleration.
 *
 * The profile is trapezoidal when [maximumSpeedMetersPerSecond] can be reached and triangular
 * otherwise. This class is pure calculation: elapsed time is supplied by the caller and no
 * platform clock or Android type is used.
 */
internal class SymmetricMotionProfile(
    val distanceMeters: Double,
    val maximumSpeedMetersPerSecond: Double,
    val maximumAccelerationMetersPerSecondSquared: Double,
) {
    val durationSeconds: Double
    val peakSpeedMetersPerSecond: Double
    val accelerationDurationSeconds: Double
    val cruiseDurationSeconds: Double

    private val accelerationDistanceMeters: Double

    init {
        require(distanceMeters.isFinite() && distanceMeters >= 0.0) {
            "Distance must be finite and non-negative"
        }
        require(
            maximumSpeedMetersPerSecond.isFinite() &&
                maximumSpeedMetersPerSecond > 0.0,
        ) {
            "Maximum speed must be finite and positive"
        }
        require(
            maximumAccelerationMetersPerSecondSquared.isFinite() &&
                maximumAccelerationMetersPerSecondSquared > 0.0,
        ) {
            "Maximum acceleration must be finite and positive"
        }

        if (distanceMeters == 0.0) {
            durationSeconds = 0.0
            peakSpeedMetersPerSecond = 0.0
            accelerationDurationSeconds = 0.0
            cruiseDurationSeconds = 0.0
            accelerationDistanceMeters = 0.0
        } else {
            // Splitting the square root avoids overflowing distance * acceleration.
            val unconstrainedPeakSpeed =
                sqrt(distanceMeters) * sqrt(maximumAccelerationMetersPerSecondSquared)
            if (unconstrainedPeakSpeed <= maximumSpeedMetersPerSecond) {
                peakSpeedMetersPerSecond = unconstrainedPeakSpeed
                accelerationDurationSeconds =
                    sqrt(distanceMeters) / sqrt(maximumAccelerationMetersPerSecondSquared)
                cruiseDurationSeconds = 0.0
                accelerationDistanceMeters = distanceMeters / 2.0
            } else {
                peakSpeedMetersPerSecond = maximumSpeedMetersPerSecond
                accelerationDurationSeconds =
                    maximumSpeedMetersPerSecond / maximumAccelerationMetersPerSecondSquared
                accelerationDistanceMeters =
                    maximumSpeedMetersPerSecond * accelerationDurationSeconds / 2.0
                val cruiseDistanceMeters =
                    (distanceMeters - 2.0 * accelerationDistanceMeters).coerceAtLeast(0.0)
                cruiseDurationSeconds = cruiseDistanceMeters / maximumSpeedMetersPerSecond
            }
            durationSeconds = 2.0 * accelerationDurationSeconds + cruiseDurationSeconds
            require(
                durationSeconds.isFinite() &&
                    peakSpeedMetersPerSecond.isFinite() &&
                    accelerationDurationSeconds.isFinite() &&
                    cruiseDurationSeconds.isFinite() &&
                    accelerationDistanceMeters.isFinite(),
            ) {
                "Motion-profile parameters must produce finite derived values"
            }
        }
    }

    /** Samples position and forward velocity at a finite, non-negative elapsed time. */
    fun sample(elapsedSeconds: Double): MotionProfileSample {
        require(elapsedSeconds.isFinite() && elapsedSeconds >= 0.0) {
            "Elapsed time must be finite and non-negative"
        }
        if (distanceMeters == 0.0 || elapsedSeconds >= durationSeconds) {
            return MotionProfileSample(
                positionMeters = distanceMeters,
                velocityMetersPerSecond = 0.0,
                complete = true,
            )
        }

        if (elapsedSeconds <= accelerationDurationSeconds) {
            val velocity = maximumAccelerationMetersPerSecondSquared * elapsedSeconds
            return MotionProfileSample(
                positionMeters = velocity * elapsedSeconds / 2.0,
                velocityMetersPerSecond = velocity,
                complete = false,
            )
        }

        val decelerationStartedAtSeconds =
            accelerationDurationSeconds + cruiseDurationSeconds
        if (elapsedSeconds < decelerationStartedAtSeconds) {
            val cruiseElapsedSeconds = elapsedSeconds - accelerationDurationSeconds
            return MotionProfileSample(
                positionMeters = accelerationDistanceMeters +
                    peakSpeedMetersPerSecond * cruiseElapsedSeconds,
                velocityMetersPerSecond = peakSpeedMetersPerSecond,
                complete = false,
            )
        }

        // Calculate backward from the exact endpoint to retain symmetry and avoid accumulated error.
        val remainingSeconds = durationSeconds - elapsedSeconds
        val velocity = maximumAccelerationMetersPerSecondSquared * remainingSeconds
        return MotionProfileSample(
            positionMeters = (distanceMeters - velocity * remainingSeconds / 2.0)
                .coerceIn(0.0, distanceMeters),
            velocityMetersPerSecond = velocity.coerceAtLeast(0.0),
            complete = false,
        )
    }
}

/** One sample from a two-rate altitude descent schedule. */
internal data class LandingDescentSample(
    val altitudeAboveOriginMeters: Double,
    /** Altitude rate: negative while descending and zero after completion. */
    val verticalVelocityMetersPerSecond: Double,
    val complete: Boolean,
)

/**
 * Descends at a primary rate until the flare region, then at a slower final rate.
 *
 * This helper intentionally describes the landing altitude schedule only; stabilization and
 * actuator control remain responsibilities of the flight controller.
 */
internal class TwoRateLandingDescentProfile(
    val initialAltitudeAboveOriginMeters: Double,
    val groundAltitudeAboveOriginMeters: Double,
    val flareHeightMeters: Double,
    val descentRateMetersPerSecond: Double,
    val finalDescentRateMetersPerSecond: Double,
    val maximumAccelerationMetersPerSecondSquared: Double,
) {
    val flareAltitudeAboveOriginMeters: Double
    val durationSeconds: Double

    private val fastDescentProfile: SymmetricMotionProfile
    private val finalDescentProfile: SymmetricMotionProfile

    init {
        require(initialAltitudeAboveOriginMeters.isFinite()) {
            "Initial altitude must be finite"
        }
        require(groundAltitudeAboveOriginMeters.isFinite()) {
            "Ground altitude must be finite"
        }
        val descentDistanceMeters =
            initialAltitudeAboveOriginMeters - groundAltitudeAboveOriginMeters
        require(descentDistanceMeters.isFinite() && descentDistanceMeters >= 0.0) {
            "Initial altitude must be at or above ground with a finite separation"
        }
        require(flareHeightMeters.isFinite() && flareHeightMeters > 0.0) {
            "Flare height must be finite and positive"
        }
        require(descentRateMetersPerSecond.isFinite() && descentRateMetersPerSecond > 0.0) {
            "Descent rate must be finite and positive"
        }
        require(
            finalDescentRateMetersPerSecond.isFinite() &&
                finalDescentRateMetersPerSecond > 0.0 &&
                finalDescentRateMetersPerSecond <= descentRateMetersPerSecond,
        ) {
            "Final descent rate must be finite, positive, and no faster than the primary rate"
        }
        require(
            maximumAccelerationMetersPerSecondSquared.isFinite() &&
                maximumAccelerationMetersPerSecondSquared > 0.0,
        ) {
            "Landing acceleration must be finite and positive"
        }

        val finalDescentDistanceMeters = minOf(flareHeightMeters, descentDistanceMeters)
        val fastDescentDistanceMeters = descentDistanceMeters - finalDescentDistanceMeters
        flareAltitudeAboveOriginMeters =
            groundAltitudeAboveOriginMeters + finalDescentDistanceMeters
        fastDescentProfile = SymmetricMotionProfile(
            distanceMeters = fastDescentDistanceMeters,
            maximumSpeedMetersPerSecond = descentRateMetersPerSecond,
            maximumAccelerationMetersPerSecondSquared =
                maximumAccelerationMetersPerSecondSquared,
        )
        finalDescentProfile = SymmetricMotionProfile(
            distanceMeters = finalDescentDistanceMeters,
            maximumSpeedMetersPerSecond = finalDescentRateMetersPerSecond,
            maximumAccelerationMetersPerSecondSquared =
                maximumAccelerationMetersPerSecondSquared,
        )
        durationSeconds = fastDescentProfile.durationSeconds + finalDescentProfile.durationSeconds
        require(
            flareAltitudeAboveOriginMeters.isFinite() &&
                durationSeconds.isFinite(),
        ) {
            "Landing-profile parameters must produce finite derived values"
        }
    }

    /** Samples commanded altitude and altitude rate at a finite, non-negative elapsed time. */
    fun sample(elapsedSeconds: Double): LandingDescentSample {
        require(elapsedSeconds.isFinite() && elapsedSeconds >= 0.0) {
            "Elapsed time must be finite and non-negative"
        }
        if (elapsedSeconds >= durationSeconds) {
            return LandingDescentSample(
                altitudeAboveOriginMeters = groundAltitudeAboveOriginMeters,
                verticalVelocityMetersPerSecond = 0.0,
                complete = true,
            )
        }

        return if (elapsedSeconds < fastDescentProfile.durationSeconds) {
            val fast = fastDescentProfile.sample(elapsedSeconds)
            LandingDescentSample(
                altitudeAboveOriginMeters =
                    (initialAltitudeAboveOriginMeters - fast.positionMeters)
                        .coerceAtLeast(flareAltitudeAboveOriginMeters),
                verticalVelocityMetersPerSecond = -fast.velocityMetersPerSecond,
                complete = false,
            )
        } else {
            val finalElapsedSeconds = elapsedSeconds - fastDescentProfile.durationSeconds
            val final = finalDescentProfile.sample(finalElapsedSeconds)
            LandingDescentSample(
                altitudeAboveOriginMeters =
                    (flareAltitudeAboveOriginMeters - final.positionMeters)
                        .coerceAtLeast(groundAltitudeAboveOriginMeters),
                verticalVelocityMetersPerSecond = -final.velocityMetersPerSecond,
                complete = final.complete,
            )
        }
    }
}
