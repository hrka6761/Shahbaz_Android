package ir.hrka.shahbaz.autopilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionProfileTest {
    @Test
    fun `long movement uses symmetric trapezoidal profile`() {
        val profile = SymmetricMotionProfile(
            distanceMeters = 10.0,
            maximumSpeedMetersPerSecond = 2.0,
            maximumAccelerationMetersPerSecondSquared = 1.0,
        )

        assertEquals(2.0, profile.accelerationDurationSeconds, TOLERANCE)
        assertEquals(3.0, profile.cruiseDurationSeconds, TOLERANCE)
        assertEquals(7.0, profile.durationSeconds, TOLERANCE)
        assertEquals(2.0, profile.peakSpeedMetersPerSecond, TOLERANCE)
        assertSample(profile.sample(0.0), position = 0.0, velocity = 0.0)
        assertSample(profile.sample(1.0), position = 0.5, velocity = 1.0)
        assertSample(profile.sample(2.0), position = 2.0, velocity = 2.0)
        assertSample(profile.sample(3.5), position = 5.0, velocity = 2.0)
        assertSample(profile.sample(5.0), position = 8.0, velocity = 2.0)
        assertSample(profile.sample(6.0), position = 9.5, velocity = 1.0)

        assertComplete(profile.sample(7.0), expectedPosition = 10.0)
        assertComplete(profile.sample(70.0), expectedPosition = 10.0)
    }

    @Test
    fun `short movement uses triangular profile below maximum speed`() {
        val profile = SymmetricMotionProfile(
            distanceMeters = 4.0,
            maximumSpeedMetersPerSecond = 10.0,
            maximumAccelerationMetersPerSecondSquared = 1.0,
        )

        assertEquals(2.0, profile.accelerationDurationSeconds, TOLERANCE)
        assertEquals(0.0, profile.cruiseDurationSeconds, TOLERANCE)
        assertEquals(4.0, profile.durationSeconds, TOLERANCE)
        assertEquals(2.0, profile.peakSpeedMetersPerSecond, TOLERANCE)
        assertSample(profile.sample(1.0), position = 0.5, velocity = 1.0)
        assertSample(profile.sample(2.0), position = 2.0, velocity = 2.0)
        assertSample(profile.sample(3.0), position = 3.5, velocity = 1.0)
        assertComplete(profile.sample(4.0), expectedPosition = 4.0)
    }

    @Test
    fun `profile is continuous at segment boundaries`() {
        val profile = SymmetricMotionProfile(
            distanceMeters = 10.0,
            maximumSpeedMetersPerSecond = 2.0,
            maximumAccelerationMetersPerSecondSquared = 1.0,
        )
        val epsilon = 1e-7

        val beforeCruise = profile.sample(profile.accelerationDurationSeconds - epsilon)
        val atCruise = profile.sample(profile.accelerationDurationSeconds)
        val afterCruise = profile.sample(profile.accelerationDurationSeconds + epsilon)
        assertEquals(atCruise.positionMeters, beforeCruise.positionMeters, 1e-6)
        assertEquals(atCruise.positionMeters, afterCruise.positionMeters, 1e-6)
        assertEquals(atCruise.velocityMetersPerSecond, beforeCruise.velocityMetersPerSecond, 1e-6)
        assertEquals(atCruise.velocityMetersPerSecond, afterCruise.velocityMetersPerSecond, 1e-6)

        val decelerationStart =
            profile.accelerationDurationSeconds + profile.cruiseDurationSeconds
        val beforeDeceleration = profile.sample(decelerationStart - epsilon)
        val atDeceleration = profile.sample(decelerationStart)
        val afterDeceleration = profile.sample(decelerationStart + epsilon)
        assertEquals(atDeceleration.positionMeters, beforeDeceleration.positionMeters, 1e-6)
        assertEquals(atDeceleration.positionMeters, afterDeceleration.positionMeters, 1e-6)
        assertEquals(
            atDeceleration.velocityMetersPerSecond,
            beforeDeceleration.velocityMetersPerSecond,
            1e-6,
        )
        assertEquals(
            atDeceleration.velocityMetersPerSecond,
            afterDeceleration.velocityMetersPerSecond,
            1e-6,
        )
    }

    @Test
    fun `zero distance is complete at every valid sample time`() {
        val profile = SymmetricMotionProfile(
            distanceMeters = 0.0,
            maximumSpeedMetersPerSecond = 3.0,
            maximumAccelerationMetersPerSecondSquared = 2.0,
        )

        assertEquals(0.0, profile.durationSeconds, 0.0)
        assertEquals(0.0, profile.peakSpeedMetersPerSecond, 0.0)
        assertComplete(profile.sample(0.0), expectedPosition = 0.0)
        assertComplete(profile.sample(1_000_000.0), expectedPosition = 0.0)
    }

    @Test
    fun `motion profile rejects invalid construction and sample values`() {
        listOf(-1.0, Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY).forEach {
            invalidDistance ->
            assertThrows(IllegalArgumentException::class.java) {
                SymmetricMotionProfile(invalidDistance, 1.0, 1.0)
            }
        }
        listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY).forEach { invalidSpeed ->
            assertThrows(IllegalArgumentException::class.java) {
                SymmetricMotionProfile(1.0, invalidSpeed, 1.0)
            }
            assertThrows(IllegalArgumentException::class.java) {
                SymmetricMotionProfile(1.0, 1.0, invalidSpeed)
            }
        }

        val profile = SymmetricMotionProfile(1.0, 1.0, 1.0)
        listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY).forEach { invalidElapsed ->
            assertThrows(IllegalArgumentException::class.java) {
                profile.sample(invalidElapsed)
            }
        }
    }

    @Test
    fun `two-rate landing profile slows inside flare region and completes at ground`() {
        val profile = TwoRateLandingDescentProfile(
            initialAltitudeAboveOriginMeters = 10.0,
            groundAltitudeAboveOriginMeters = 2.0,
            flareHeightMeters = 2.0,
            descentRateMetersPerSecond = 2.0,
            finalDescentRateMetersPerSecond = 0.5,
            maximumAccelerationMetersPerSecondSquared = 1.0,
        )

        assertEquals(4.0, profile.flareAltitudeAboveOriginMeters, TOLERANCE)
        assertEquals(9.5, profile.durationSeconds, TOLERANCE)
        assertLandingSample(profile.sample(0.0), altitude = 10.0, velocity = 0.0)
        assertLandingSample(profile.sample(1.0), altitude = 9.5, velocity = -1.0)
        assertLandingSample(profile.sample(3.0), altitude = 6.0, velocity = -2.0)
        assertLandingSample(profile.sample(5.0), altitude = 4.0, velocity = 0.0)
        assertLandingSample(profile.sample(5.5), altitude = 3.875, velocity = -0.5)
        assertLandingComplete(profile.sample(9.5), groundAltitude = 2.0)
        assertLandingComplete(profile.sample(100.0), groundAltitude = 2.0)
    }

    @Test
    fun `landing profile handles descent wholly within flare region and zero distance`() {
        val shortProfile = TwoRateLandingDescentProfile(
            initialAltitudeAboveOriginMeters = 3.0,
            groundAltitudeAboveOriginMeters = 2.0,
            flareHeightMeters = 2.0,
            descentRateMetersPerSecond = 2.0,
            finalDescentRateMetersPerSecond = 0.5,
            maximumAccelerationMetersPerSecondSquared = 1.0,
        )
        assertEquals(3.0, shortProfile.flareAltitudeAboveOriginMeters, TOLERANCE)
        assertEquals(2.5, shortProfile.durationSeconds, TOLERANCE)
        assertLandingSample(shortProfile.sample(1.0), altitude = 2.625, velocity = -0.5)

        val zeroProfile = TwoRateLandingDescentProfile(
            initialAltitudeAboveOriginMeters = -5.0,
            groundAltitudeAboveOriginMeters = -5.0,
            flareHeightMeters = 2.0,
            descentRateMetersPerSecond = 2.0,
            finalDescentRateMetersPerSecond = 0.5,
            maximumAccelerationMetersPerSecondSquared = 1.0,
        )
        assertEquals(0.0, zeroProfile.durationSeconds, 0.0)
        assertLandingComplete(zeroProfile.sample(0.0), groundAltitude = -5.0)
    }

    @Test
    fun `landing profile validates geometry rates and elapsed time`() {
        assertThrows(IllegalArgumentException::class.java) {
            TwoRateLandingDescentProfile(1.0, 2.0, 1.0, 1.0, 0.5, 1.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TwoRateLandingDescentProfile(Double.NaN, 0.0, 1.0, 1.0, 0.5, 1.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TwoRateLandingDescentProfile(2.0, 0.0, 0.0, 1.0, 0.5, 1.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TwoRateLandingDescentProfile(2.0, 0.0, 1.0, 0.0, 0.5, 1.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TwoRateLandingDescentProfile(2.0, 0.0, 1.0, 1.0, 1.1, 1.0)
        }

        assertThrows(IllegalArgumentException::class.java) {
            TwoRateLandingDescentProfile(2.0, 0.0, 1.0, 1.0, 0.5, 0.0)
        }

        val profile = TwoRateLandingDescentProfile(2.0, 0.0, 1.0, 1.0, 0.5, 1.0)
        listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY).forEach { invalidElapsed ->
            assertThrows(IllegalArgumentException::class.java) {
                profile.sample(invalidElapsed)
            }
        }
    }

    private fun assertSample(
        sample: MotionProfileSample,
        position: Double,
        velocity: Double,
    ) {
        assertEquals(position, sample.positionMeters, TOLERANCE)
        assertEquals(velocity, sample.velocityMetersPerSecond, TOLERANCE)
        assertFalse(sample.complete)
    }

    private fun assertComplete(sample: MotionProfileSample, expectedPosition: Double) {
        assertEquals(expectedPosition, sample.positionMeters, TOLERANCE)
        assertEquals(0.0, sample.velocityMetersPerSecond, 0.0)
        assertTrue(sample.complete)
    }

    private fun assertLandingSample(
        sample: LandingDescentSample,
        altitude: Double,
        velocity: Double,
    ) {
        assertEquals(altitude, sample.altitudeAboveOriginMeters, TOLERANCE)
        assertEquals(velocity, sample.verticalVelocityMetersPerSecond, TOLERANCE)
        assertFalse(sample.complete)
    }

    private fun assertLandingComplete(sample: LandingDescentSample, groundAltitude: Double) {
        assertEquals(groundAltitude, sample.altitudeAboveOriginMeters, TOLERANCE)
        assertEquals(0.0, sample.verticalVelocityMetersPerSecond, 0.0)
        assertTrue(sample.complete)
    }

    private companion object {
        const val TOLERANCE = 1e-9
    }
}
