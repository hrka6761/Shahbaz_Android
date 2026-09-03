package ir.hrka.shahbaz.autopilot

import ir.hrka.shahbaz.flightcontracts.Quaterniond
import kotlin.math.PI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

/** Complete state and rejection coverage for the final-descent range handover. */
class GroundRangeLandingAidTest {
    private val config = AutopilotConfig(
        groundRangeRequiredConsecutiveSamples = 3,
        groundRangeMaximumBarometerDisagreementMeters = 2.0,
    )

    @Test
    fun `three distinct continuous samples engage and repeated frames never count twice`() {
        val aid = GroundRangeLandingAid(config)
        val first = observation(1.50, 1_000.ms)

        assertEquals(
            LandingRangeAidState.ACQUIRING,
            decision(aid, 1_000.ms, first, altitude = 1.50).state,
        )
        assertEquals(
            LandingRangeAidState.ACQUIRING,
            decision(aid, 1_010.ms, first, altitude = 1.50).state,
        )
        assertEquals(
            LandingRangeAidState.ACQUIRING,
            decision(aid, 1_050.ms, observation(1.49, 1_050.ms), altitude = 1.49).state,
        )

        val engaged = decision(
            aid,
            1_100.ms,
            observation(1.48, 1_100.ms),
            altitude = 1.48,
        )
        assertEquals(LandingRangeAidState.ACTIVE, engaged.state)
        assertTrue(engaged.controlsFinalDescent)
        assertEquals(1.48, engaged.targetVerticalDistanceMeters!!, TOLERANCE)
    }

    @Test
    fun `active aid translates AGL error into the existing controller altitude coordinate`() {
        val aid = engagedAid()

        val output = decision(
            aid,
            now = 1_200.ms,
            observation = observation(1.43, 1_200.ms),
            altitude = 1.43,
        )

        // 100 ms at 0.25 m/s advances the AGL target from 1.48 m to 1.455 m.
        assertEquals(1.455, output.targetVerticalDistanceMeters!!, TOLERANCE)
        assertEquals(1.455, output.controllerAltitudeTargetMeters!!, TOLERANCE)
        assertEquals(0.25, output.descentFeedForwardMetersPerSecond, TOLERANCE)
    }

    @Test
    fun `duplicate timestamp cannot reinterpret an accepted range or move the target`() {
        val aid = engagedAid()
        val accepted = decision(
            aid,
            now = 1_200.ms,
            observation = observation(1.43, 1_200.ms),
            altitude = 1.43,
        )

        val inconsistentDuplicate = decision(
            aid,
            now = 1_210.ms,
            observation = observation(1.30, 1_200.ms),
            altitude = 1.43,
        )

        assertEquals(
            accepted.targetVerticalDistanceMeters!!,
            inconsistentDuplicate.targetVerticalDistanceMeters!!,
            TOLERANCE,
        )
        assertEquals(
            accepted.controllerAltitudeTargetMeters!!,
            inconsistentDuplicate.controllerAltitudeTargetMeters!!,
            TOLERANCE,
        )
    }

    @Test
    fun `missing range is ignored high above handover and holds near the surface`() {
        val highAid = GroundRangeLandingAid(config)
        val high = decision(highAid, 1_000.ms, null, altitude = 3.0)
        assertEquals(LandingRangeAidState.INACTIVE, high.state)
        assertFalse(high.lossGraceExpired)

        val lowAid = GroundRangeLandingAid(config)
        val low = decision(lowAid, 1_000.ms, null, altitude = 2.1)
        assertEquals(LandingRangeAidState.HOLDING_FOR_VALID_RANGE, low.state)
        assertTrue(low.lossGraceExpired)
        assertEquals(GroundRangeRejection.MISSING, low.rejection)
        assertNull(low.controllerAltitudeTargetMeters)
    }

    @Test
    fun `active sensor loss holds immediately and exposes grace expiry`() {
        val aid = engagedAid()

        val briefLoss = decision(aid, 1_200.ms, null, altitude = 1.48)
        assertEquals(LandingRangeAidState.HOLDING_FOR_VALID_RANGE, briefLoss.state)
        assertFalse(briefLoss.lossGraceExpired)

        val prolongedLoss = decision(aid, 1_401.ms, null, altitude = 1.48)
        assertEquals(LandingRangeAidState.HOLDING_FOR_VALID_RANGE, prolongedLoss.state)
        assertTrue(prolongedLoss.lossGraceExpired)
    }

    @Test
    fun `recovery rebases descent time instead of catching up through the invalid interval`() {
        val aid = engagedAid()
        val beforeLoss = decision(
            aid,
            1_200.ms,
            observation(1.43, 1_200.ms),
            altitude = 1.43,
        )
        assertEquals(1.455, beforeLoss.targetVerticalDistanceMeters!!, TOLERANCE)

        assertEquals(
            LandingRangeAidState.HOLDING_FOR_VALID_RANGE,
            decision(aid, 2_200.ms, null, altitude = 1.43).state,
        )
        val recovered = decision(
            aid,
            2_250.ms,
            observation(1.42, 2_250.ms),
            altitude = 1.42,
        )
        assertEquals(1.455, recovered.targetVerticalDistanceMeters!!, TOLERANCE)

        val resumed = decision(
            aid,
            2_350.ms,
            observation(1.40, 2_350.ms),
            altitude = 1.40,
        )
        assertEquals(1.430, resumed.targetVerticalDistanceMeters!!, TOLERANCE)
    }

    @Test
    fun `every observation rejection is explicit and cannot control descent`() {
        val stale = decision(
            GroundRangeLandingAid(config),
            1_000.ms,
            observation(1.0, 700.ms),
            altitude = 1.0,
        )
        val future = decision(
            GroundRangeLandingAid(config),
            1_000.ms,
            observation(1.0, 1_001.ms),
            altitude = 1.0,
        )
        val lowQuality = decision(
            GroundRangeLandingAid(config),
            1_000.ms,
            observation(1.0, 1_000.ms, quality = 49),
            altitude = 1.0,
        )
        val tilted = decision(
            GroundRangeLandingAid(config),
            1_000.ms,
            observation(1.0, 1_000.ms),
            altitude = 1.0,
            attitude = Quaterniond.fromEuler(PI / 4.0, 0.0, 0.0),
        )
        val disagreement = decision(
            GroundRangeLandingAid(config),
            1_000.ms,
            observation(1.0, 1_000.ms),
            altitude = 3.1,
        )

        val discontinuousAid = GroundRangeLandingAid(config)
        decision(discontinuousAid, 1_000.ms, observation(1.8, 1_000.ms), altitude = 1.8)
        val discontinuity = decision(
            discontinuousAid,
            1_050.ms,
            observation(1.0, 1_050.ms),
            altitude = 1.0,
        )
        val regression = decision(
            discontinuousAid,
            1_060.ms,
            observation(1.8, 999.ms),
            altitude = 1.8,
        )

        val results = listOf(
            stale to GroundRangeRejection.STALE_OR_FUTURE,
            future to GroundRangeRejection.STALE_OR_FUTURE,
            lowQuality to GroundRangeRejection.LOW_QUALITY,
            tilted to GroundRangeRejection.EXCESSIVE_TILT,
            disagreement to GroundRangeRejection.BAROMETER_DISAGREEMENT,
            discontinuity to GroundRangeRejection.DISCONTINUITY,
            regression to GroundRangeRejection.TIMESTAMP_REGRESSION,
        )
        results.forEach { (result, expected) ->
            assertEquals(expected, result.rejection)
            assertFalse(result.controlsFinalDescent)
        }
    }

    @Test
    fun `observation and range configuration reject every invalid boundary`() {
        listOf(Double.NaN, Double.NEGATIVE_INFINITY, 0.0, -0.1).forEach { distance ->
            assertThrows(IllegalArgumentException::class.java) {
                AutopilotGroundRangeObservation(distance, 100, 0L)
            }
        }
        listOf(-1, 101).forEach { quality ->
            assertThrows(IllegalArgumentException::class.java) {
                AutopilotGroundRangeObservation(1.0, quality, 0L)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            AutopilotGroundRangeObservation(1.0, 100, -1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AutopilotConfig(groundRangeRequiredConsecutiveSamples = 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AutopilotConfig(
                groundRangeEngageHeightMeters = 2.0,
                groundRangeReleaseHeightMeters = 2.0,
            )
        }
    }

    private fun engagedAid(): GroundRangeLandingAid = GroundRangeLandingAid(config).also { aid ->
        decision(aid, 1_000.ms, observation(1.50, 1_000.ms), altitude = 1.50)
        decision(aid, 1_050.ms, observation(1.49, 1_050.ms), altitude = 1.49)
        decision(aid, 1_100.ms, observation(1.48, 1_100.ms), altitude = 1.48)
    }

    private fun decision(
        aid: GroundRangeLandingAid,
        now: Long,
        observation: AutopilotGroundRangeObservation?,
        altitude: Double?,
        attitude: Quaterniond = Quaterniond.IDENTITY,
    ) = aid.update(
        nowNanos = now,
        observation = observation,
        estimatedAltitudeAboveOriginMeters = altitude,
        landingGroundAltitudeAboveOriginMeters = 0.0,
        attitudeBodyToNed = attitude,
        maximumTrajectoryLeadMeters = 3.0,
    )

    private fun observation(distance: Double, timestamp: Long, quality: Int = 100) =
        AutopilotGroundRangeObservation(distance, quality, timestamp)

    private val Int.ms: Long
        get() = this * 1_000_000L

    private companion object {
        const val TOLERANCE = 1e-9
    }
}
