package ir.hrka.shahbaz.autopilot

import ir.hrka.shahbaz.core.model.FlightPlan
import ir.hrka.shahbaz.core.model.GeoCoordinate
import ir.hrka.shahbaz.flightcontracts.ControlTargetStatus
import ir.hrka.shahbaz.flightcontracts.ControlTracking
import ir.hrka.shahbaz.flightcontracts.FlightControllerArmingState
import ir.hrka.shahbaz.flightcontracts.FlightControllerHealth
import ir.hrka.shahbaz.flightcontracts.FlightControllerHealthIssue
import ir.hrka.shahbaz.flightcontracts.FlightControllerLifecycleRequest
import ir.hrka.shahbaz.flightcontracts.FlightControllerOutput
import ir.hrka.shahbaz.flightcontracts.FlightControllerSnapshot
import ir.hrka.shahbaz.flightcontracts.GeoPoint
import ir.hrka.shahbaz.flightcontracts.LocalNavigationReference
import ir.hrka.shahbaz.flightcontracts.PositionControlTarget
import ir.hrka.shahbaz.flightcontracts.Vector3d
import ir.hrka.shahbaz.flightcontracts.VehicleStateEstimate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises the deterministic point-to-point mission policy without hardware or wall-clock I/O. */
class PointToPointAutopilotTest {
    @Test
    fun `invalid mission is rejected and static issues remain visible while idle`() {
        val plan = validPlan(
            targetAltitudeMeters = 12.0,
            destinationGroundAltitudeMeters = 9.0,
        )
        val config = AutopilotConfig(
            maximumMissionDistanceMeters = 5.0,
            maximumCruiseAltitudeAboveOriginMeters = 10.0,
            maximumDestinationGroundOffsetMeters = 8.0,
            minimumCruiseClearanceMeters = 5.0,
        )
        val autopilot = Autopilot.create(plan, config)
        val expected = setOf(
            AutopilotIssueCode.MISSION_DISTANCE_EXCEEDED,
            AutopilotIssueCode.CRUISE_ALTITUDE_EXCEEDED,
            AutopilotIssueCode.DESTINATION_GROUND_OFFSET_EXCEEDED,
            AutopilotIssueCode.INSUFFICIENT_CRUISE_CLEARANCE,
        )

        assertEquals(expected, autopilot.snapshot.value.issueCodes())

        val idle = autopilot.step(
            AutopilotInput(1_000L.ms, FlightControllerSnapshot()),
        )
        assertEquals(AutopilotPhase.STANDBY, idle.snapshot.phase)
        assertEquals(expected, idle.snapshot.issueCodes())

        val rejected = autopilot.step(
            AutopilotInput(1_001L.ms, FlightControllerSnapshot()),
            AutopilotRequest.START,
        )
        assertEquals(AutopilotPhase.STANDBY, rejected.snapshot.phase)
        assertEquals(expected, rejected.snapshot.issueCodes())
        assertEquals(FlightControllerLifecycleRequest.HOLD_DISARMED, rejected.lifecycleRequest)
        assertNull(rejected.flightControlCommand)
    }

    @Test
    fun `preflight reports every missing cooperating module input`() {
        val plan = validPlan()
        val autopilot = Autopilot.create(plan)

        val output = autopilot.step(
            AutopilotInput(1_000L.ms, FlightControllerSnapshot()),
            AutopilotRequest.START,
        )

        assertEquals(AutopilotPhase.PREFLIGHT, output.snapshot.phase)
        assertEquals(
            setOf(
                AutopilotIssueCode.NAVIGATION_FIX_MISSING,
                AutopilotIssueCode.CONTROLLER_REFERENCE_UNAVAILABLE,
                AutopilotIssueCode.LANDING_DETECTOR_UNAVAILABLE,
                AutopilotIssueCode.GROUND_RANGE_UNUSABLE,
                AutopilotIssueCode.SAFETY_STATUS_UNAVAILABLE,
                AutopilotIssueCode.FLIGHT_CONTROLLER_NOT_READY,
            ),
            output.snapshot.issueCodes(),
        )
        assertEquals(FlightControllerLifecycleRequest.HOLD_DISARMED, output.lifecycleRequest)
        assertNotNull(output.flightControlCommand)
    }

    @Test
    fun `preflight rejects stale inaccurate or inconsistent navigation and landing evidence`() {
        val plan = validPlan()
        val now = 5_000L.ms

        assertPreflightCodes(
            plan,
            readyInput(
                plan = plan,
                now = now,
                navigationFix = AutopilotNavigationFix(plan.origin, 1.0, now - 1_501L.ms),
            ),
            AutopilotIssueCode.NAVIGATION_FIX_STALE,
        )
        assertPreflightCodes(
            plan,
            readyInput(
                plan = plan,
                now = now,
                navigationFix = AutopilotNavigationFix(plan.origin, 10.01, now),
            ),
            AutopilotIssueCode.NAVIGATION_FIX_INACCURATE,
        )
        assertPreflightCodes(
            plan,
            readyInput(
                plan = plan,
                now = now,
                navigationFix = AutopilotNavigationFix(
                    GeoCoordinate(plan.origin.latitude + 0.001, plan.origin.longitude),
                    1.0,
                    now,
                ),
            ),
            AutopilotIssueCode.AIRCRAFT_TOO_FAR_FROM_ORIGIN,
        )
        assertPreflightCodes(
            plan,
            readyInput(
                plan = plan,
                now = now,
                reference = GeoPoint(
                    plan.origin.latitude + 0.001,
                    plan.origin.longitude,
                ),
            ),
            AutopilotIssueCode.CONTROLLER_REFERENCE_MISMATCH,
        )
        assertPreflightCodes(
            plan,
            readyInput(
                plan = plan,
                now = now,
                landingObservation = AutopilotLandingObservation(
                    LandedState.ON_GROUND,
                    now - 1_001L.ms,
                ),
            ),
            AutopilotIssueCode.LANDING_OBSERVATION_STALE,
        )
        assertPreflightCodes(
            plan,
            readyInput(
                plan = plan,
                now = now,
                landingObservation = AutopilotLandingObservation(LandedState.AIRBORNE, now),
            ),
            AutopilotIssueCode.AIRCRAFT_NOT_ON_GROUND,
        )
    }

    @Test
    fun `preflight rejects stale unsafe and unhealthy cooperating module decisions`() {
        val plan = validPlan()
        val now = 5_000L.ms

        assertPreflightCodes(
            plan,
            readyInput(
                plan = plan,
                now = now,
                safetyStatus = healthySafety(now - 2_001L.ms),
            ),
            AutopilotIssueCode.SAFETY_STATUS_STALE,
        )
        assertPreflightCodes(
            plan,
            readyInput(
                plan = plan,
                now = now,
                safetyStatus = AutopilotSafetyStatus(observedAtNanos = now),
            ),
            AutopilotIssueCode.ROUTE_OR_AIRSPACE_UNSAFE,
            AutopilotIssueCode.DESTINATION_LANDING_ZONE_UNSAFE,
            AutopilotIssueCode.ENERGY_RESERVE_INSUFFICIENT,
            AutopilotIssueCode.GEOFENCE_UNHEALTHY,
            AutopilotIssueCode.WIND_LIMIT_EXCEEDED,
        )
        assertPreflightCodes(
            plan,
            readyInput(
                plan = plan,
                now = now,
                armingState = FlightControllerArmingState.ARMED,
            ),
            AutopilotIssueCode.FLIGHT_CONTROLLER_NOT_DISARMED,
        )
        assertPreflightCodes(
            plan,
            readyInput(
                plan = plan,
                now = now,
                health = healthyHealth().copy(
                    issues = listOf(
                        FlightControllerHealthIssue(
                            "NO_INPUT",
                            "Synthetic health blocker",
                        ),
                    ),
                ),
            ),
            AutopilotIssueCode.FLIGHT_CONTROLLER_NOT_READY,
        )
    }

    @Test
    fun `commands use fresh deadlines and preflight requires the latest sequence acknowledgement`() {
        val plan = validPlan()
        val config = fastConfig(commandValidityMillis = 200L)
        val autopilot = Autopilot.create(plan, config)
        var now = 1_000L.ms

        val first = autopilot.step(
            readyInput(plan, now, trackingSequence = null, includeLastOutput = true),
            AutopilotRequest.START,
        )
        assertCommand(first, expectedSequence = 0L, validityMillis = 200L)

        now += 5L.ms
        val unknownSequence = autopilot.step(
            readyInput(plan, now, trackingSequence = 99L),
        )
        assertEquals(AutopilotPhase.PREFLIGHT, unknownSequence.snapshot.phase)
        assertTrue(AutopilotIssueCode.FLIGHT_CONTROLLER_NOT_READY in unknownSequence.snapshot.issueCodes())
        assertCommand(unknownSequence, expectedSequence = 1L, validityMillis = 200L)

        now += 5L.ms
        val staleSequence = autopilot.step(
            readyInput(plan, now, trackingSequence = 0L),
        )
        assertEquals(AutopilotPhase.PREFLIGHT, staleSequence.snapshot.phase)
        assertCommand(staleSequence, expectedSequence = 2L, validityMillis = 200L)

        now += 5L.ms
        val acknowledged = autopilot.step(
            readyInput(plan, now, trackingSequence = 2L),
        )
        assertEquals(AutopilotPhase.ARMING, acknowledged.snapshot.phase)
        assertTrue(acknowledged.snapshot.issues.isEmpty())
        assertEquals(FlightControllerLifecycleRequest.ARM, acknowledged.lifecycleRequest)
        assertCommand(acknowledged, expectedSequence = 3L, validityMillis = 200L)
    }

    @Test
    fun `arming fails closed when a live preflight provider regresses before board confirmation`() {
        val plan = validPlan()
        val autopilot = Autopilot.create(plan, fastConfig())
        var now = 1_000L.ms

        val preflight = autopilot.step(
            readyInput(plan, now),
            AutopilotRequest.START,
        )
        val arming = autopilot.step(
            readyInput(
                plan = plan,
                now = ++now,
                trackingSequence = preflight.flightControlCommand?.sequence,
            ),
        )
        assertEquals(AutopilotPhase.ARMING, arming.snapshot.phase)

        val failed = autopilot.step(
            readyInput(
                plan = plan,
                now = ++now,
                armingState = FlightControllerArmingState.ARMING,
                safetyStatus = AutopilotSafetyStatus(),
                trackingSequence = arming.flightControlCommand?.sequence,
            ),
        )

        assertEquals(AutopilotPhase.FAILED, failed.snapshot.phase)
        assertEquals(FlightControllerLifecycleRequest.DISARM, failed.lifecycleRequest)
        assertNull(failed.flightControlCommand)
        assertTrue(AutopilotIssueCode.SAFETY_STATUS_UNAVAILABLE in failed.snapshot.issueCodes())
        assertFalse(
            AutopilotIssueCode.FLIGHT_CONTROLLER_NOT_DISARMED in failed.snapshot.issueCodes(),
        )
    }

    @Test
    fun `arming confirmation cannot enter takeoff after a live preflight provider regresses`() {
        val plan = validPlan()
        val autopilot = Autopilot.create(plan, fastConfig())
        var now = 1_000L.ms

        val preflight = autopilot.step(readyInput(plan, now), AutopilotRequest.START)
        val arming = autopilot.step(
            readyInput(
                plan = plan,
                now = ++now,
                trackingSequence = preflight.flightControlCommand?.sequence,
            ),
        )
        assertEquals(AutopilotPhase.ARMING, arming.snapshot.phase)

        val failed = autopilot.step(
            readyInput(
                plan = plan,
                now = ++now,
                armingState = FlightControllerArmingState.ARMED,
                safetyStatus = AutopilotSafetyStatus(),
                trackingSequence = arming.flightControlCommand?.sequence,
            ),
        )

        assertEquals(AutopilotPhase.FAILED, failed.snapshot.phase)
        assertEquals(FlightControllerLifecycleRequest.DISARM, failed.lifecycleRequest)
        assertNull(failed.flightControlCommand)
        assertTrue(AutopilotIssueCode.SAFETY_STATUS_UNAVAILABLE in failed.snapshot.issueCodes())
    }

    @Test
    fun `normal mission progresses through takeoff cruise touchdown disarm and completion`() {
        val harness = MissionHarness()

        val takeoff = harness.launchToTakeoff(observeAirborne = true)
        assertEquals(AutopilotPhase.TAKEOFF, takeoff.snapshot.phase)
        assertEquals(FlightControllerLifecycleRequest.RUN, takeoff.lifecycleRequest)
        assertVectorEquals(
            Vector3d(0.0, 0.0, -harness.plan.targetAltitudeAboveOriginMeters),
            takeoff.positionTarget(),
        )

        val cruise = harness.settleCurrentTarget(
            position = Vector3d(0.0, 0.0, -harness.plan.targetAltitudeAboveOriginMeters),
            altitudeMeters = harness.plan.targetAltitudeAboveOriginMeters,
        )
        assertEquals(AutopilotPhase.CRUISE, cruise.snapshot.phase)
        val destination = harness.destinationLocal().copy(
            z = -harness.plan.targetAltitudeAboveOriginMeters,
        )
        assertVectorEquals(destination, cruise.positionTarget())

        val landing = harness.settleCurrentTarget(
            position = destination,
            altitudeMeters = harness.plan.targetAltitudeAboveOriginMeters,
        )
        assertEquals(AutopilotPhase.LANDING, landing.snapshot.phase)
        assertVectorEquals(destination, landing.positionTarget())

        val touchdownPosition = harness.destinationLocal().copy(
            z = -harness.plan.destinationGroundAltitudeAboveOriginMeters,
        )
        val firstGroundEvidence = harness.step(
            armingState = FlightControllerArmingState.ARMED,
            position = touchdownPosition,
            altitudeMeters = harness.plan.destinationGroundAltitudeAboveOriginMeters,
            localVelocity = Vector3d.ZERO,
            verticalVelocityMetersPerSecond = 0.0,
            landingState = LandedState.ON_GROUND,
        )
        assertEquals(AutopilotPhase.LANDING, firstGroundEvidence.snapshot.phase)

        val disarming = harness.step(
            deltaMillis = harness.config.touchdownConfirmationMillis,
            armingState = FlightControllerArmingState.ARMED,
            position = touchdownPosition,
            altitudeMeters = harness.plan.destinationGroundAltitudeAboveOriginMeters,
            localVelocity = Vector3d.ZERO,
            verticalVelocityMetersPerSecond = 0.0,
            landingState = LandedState.ON_GROUND,
        )
        assertEquals(AutopilotPhase.DISARMING, disarming.snapshot.phase)
        assertEquals(FlightControllerLifecycleRequest.DISARM, disarming.lifecycleRequest)
        assertNull(disarming.flightControlCommand)

        val awaitingBoardConfirmation = harness.step(
            armingState = FlightControllerArmingState.DISARMING,
            position = touchdownPosition,
            altitudeMeters = harness.plan.destinationGroundAltitudeAboveOriginMeters,
            landingState = LandedState.ON_GROUND,
        )
        assertEquals(AutopilotPhase.DISARMING, awaitingBoardConfirmation.snapshot.phase)
        assertEquals(FlightControllerLifecycleRequest.DISARM, awaitingBoardConfirmation.lifecycleRequest)

        val completed = harness.step(
            armingState = FlightControllerArmingState.DISARMED,
            position = touchdownPosition,
            altitudeMeters = harness.plan.destinationGroundAltitudeAboveOriginMeters,
            landingState = LandedState.ON_GROUND,
        )
        assertEquals(AutopilotPhase.COMPLETED, completed.snapshot.phase)
        assertEquals(FlightControllerLifecycleRequest.HOLD_DISARMED, completed.lifecycleRequest)
        assertFalse(completed.snapshot.abortingToOrigin)
        assertEquals(completed.snapshot, harness.autopilot.snapshot.value)
    }

    @Test
    fun `operator abort climbs in place returns to origin lands and ends aborted`() {
        val harness = MissionHarness()
        harness.enterCruise()
        val destination = harness.destinationLocal()
        val halfway = Vector3d(
            destination.x / 2.0,
            destination.y / 2.0,
            -harness.plan.targetAltitudeAboveOriginMeters,
        )

        val returnClimb = harness.step(
            request = AutopilotRequest.ABORT,
            armingState = FlightControllerArmingState.ARMED,
            position = halfway,
            altitudeMeters = harness.plan.targetAltitudeAboveOriginMeters,
            landingState = LandedState.AIRBORNE,
        )
        assertEquals(AutopilotPhase.RETURN_CLIMB, returnClimb.snapshot.phase)
        assertTrue(returnClimb.snapshot.abortingToOrigin)
        assertTrue(returnClimb.snapshot.issues.isEmpty())
        assertVectorEquals(halfway, returnClimb.positionTarget())

        val returning = harness.settleCurrentTarget(
            position = halfway,
            altitudeMeters = harness.plan.targetAltitudeAboveOriginMeters,
        )
        assertEquals(AutopilotPhase.RETURNING, returning.snapshot.phase)
        assertVectorEquals(
            Vector3d(0.0, 0.0, -harness.plan.targetAltitudeAboveOriginMeters),
            returning.positionTarget(),
        )

        val landing = harness.settleCurrentTarget(
            position = Vector3d(0.0, 0.0, -harness.plan.targetAltitudeAboveOriginMeters),
            altitudeMeters = harness.plan.targetAltitudeAboveOriginMeters,
        )
        assertEquals(AutopilotPhase.LANDING, landing.snapshot.phase)
        assertVectorEquals(
            Vector3d(0.0, 0.0, -harness.plan.targetAltitudeAboveOriginMeters),
            landing.positionTarget(),
        )

        val originGround = Vector3d.ZERO
        harness.step(
            armingState = FlightControllerArmingState.ARMED,
            position = originGround,
            altitudeMeters = 0.0,
            landingState = LandedState.ON_GROUND,
        )
        val disarming = harness.step(
            deltaMillis = harness.config.touchdownConfirmationMillis,
            armingState = FlightControllerArmingState.ARMED,
            position = originGround,
            altitudeMeters = 0.0,
            landingState = LandedState.ON_GROUND,
        )
        assertEquals(AutopilotPhase.DISARMING, disarming.snapshot.phase)

        val aborted = harness.step(
            armingState = FlightControllerArmingState.DISARMED,
            position = originGround,
            altitudeMeters = 0.0,
            landingState = LandedState.ON_GROUND,
        )
        assertEquals(AutopilotPhase.ABORTED, aborted.snapshot.phase)
        assertTrue(aborted.snapshot.abortingToOrigin)
        assertEquals(FlightControllerLifecycleRequest.HOLD_DISARMED, aborted.lifecycleRequest)
    }

    @Test
    fun `old phase tracking cannot settle a newly requested return target`() {
        val harness = MissionHarness(config = fastConfig(targetSettleMillis = 0L))
        harness.enterCruise()
        val current = harness.destinationLocal().copy(
            x = harness.destinationLocal().x / 2.0,
            y = harness.destinationLocal().y / 2.0,
            z = -harness.plan.targetAltitudeAboveOriginMeters,
        )

        val abortUsingReachedCruiseResult = harness.step(
            request = AutopilotRequest.ABORT,
            armingState = FlightControllerArmingState.ARMED,
            position = current,
            altitudeMeters = harness.plan.targetAltitudeAboveOriginMeters,
            landingState = LandedState.AIRBORNE,
            targetStatus = ControlTargetStatus.REACHED,
        )

        assertEquals(AutopilotPhase.RETURN_CLIMB, abortUsingReachedCruiseResult.snapshot.phase)

        val acknowledgedReturnClimb = harness.step(
            armingState = FlightControllerArmingState.ARMED,
            position = current,
            altitudeMeters = harness.plan.targetAltitudeAboveOriginMeters,
            landingState = LandedState.AIRBORNE,
            targetStatus = ControlTargetStatus.REACHED,
        )
        assertEquals(AutopilotPhase.RETURNING, acknowledgedReturnClimb.snapshot.phase)
    }

    @Test
    fun `in flight safety loss starts controlled return and latches its cause`() {
        val harness = MissionHarness()
        harness.enterCruise()
        val current = harness.destinationLocal().copy(
            x = harness.destinationLocal().x / 2.0,
            y = harness.destinationLocal().y / 2.0,
            z = -harness.plan.targetAltitudeAboveOriginMeters,
        )
        val unsafeAtNextStep = AutopilotSafetyStatus(
            routeAndAirspaceClear = false,
            destinationLandingZoneClear = true,
            energyReserveSufficient = true,
            geofenceHealthy = true,
            windWithinLimits = true,
            observedAtNanos = harness.now + 1L.ms,
        )

        val output = harness.step(
            armingState = FlightControllerArmingState.ARMED,
            position = current,
            altitudeMeters = harness.plan.targetAltitudeAboveOriginMeters,
            landingState = LandedState.AIRBORNE,
            safetyStatus = unsafeAtNextStep,
        )

        assertEquals(AutopilotPhase.RETURN_CLIMB, output.snapshot.phase)
        assertTrue(output.snapshot.abortingToOrigin)
        assertEquals(
            setOf(AutopilotIssueCode.ROUTE_OR_AIRSPACE_UNSAFE),
            output.snapshot.issueCodes(),
        )
    }

    @Test
    fun `wind limit blocks preflight and initiates land in place in flight`() {
        val plan = validPlan()
        val now = 2_000L.ms
        assertPreflightCodes(
            plan,
            readyInput(
                plan = plan,
                now = now,
                safetyStatus = healthySafety(now).copy(windWithinLimits = false),
            ),
            AutopilotIssueCode.WIND_LIMIT_EXCEEDED,
        )

        val harness = MissionHarness()
        harness.enterCruise()
        val current = harness.destinationLocal().copy(
            x = harness.destinationLocal().x / 2.0,
            y = harness.destinationLocal().y / 2.0,
            z = -harness.plan.targetAltitudeAboveOriginMeters,
        )
        val excessiveWind = healthySafety(harness.now + 1L.ms).copy(
            windWithinLimits = false,
        )

        val landing = harness.step(
            armingState = FlightControllerArmingState.ARMED,
            position = current,
            altitudeMeters = harness.plan.targetAltitudeAboveOriginMeters,
            landingState = LandedState.AIRBORNE,
            safetyStatus = excessiveWind,
        )

        assertEquals(AutopilotPhase.LANDING, landing.snapshot.phase)
        assertFalse(landing.snapshot.abortingToOrigin)
        assertEquals(current.z, landing.positionTarget().z, 0.000001)
        assertEquals(
            setOf(AutopilotIssueCode.WIND_LIMIT_EXCEEDED),
            landing.snapshot.issueCodes(),
        )
    }

    @Test
    fun `energy loss during landing never commands a return climb`() {
        val harness = MissionHarness()
        harness.enterLanding(observeAirborne = true)
        val destination = harness.destinationLocal()
        val currentAltitude = harness.plan.targetAltitudeAboveOriginMeters / 2.0
        val current = destination.copy(z = -currentAltitude)

        val output = harness.step(
            armingState = FlightControllerArmingState.ARMED,
            position = current,
            altitudeMeters = currentAltitude,
            landingState = LandedState.AIRBORNE,
            safetyStatus = healthySafety(harness.now + 1L.ms).copy(
                energyReserveSufficient = false,
            ),
        )

        assertEquals(AutopilotPhase.LANDING, output.snapshot.phase)
        assertEquals(FlightControllerLifecycleRequest.RUN, output.lifecycleRequest)
        requireNotNull(output.flightControlCommand)
        assertTrue(output.positionTarget().z >= current.z)
        assertTrue(
            AutopilotIssueCode.ENERGY_RESERVE_INSUFFICIENT in output.snapshot.issueCodes(),
        )
    }

    @Test
    fun `return timeout changes to controlled landing and never disarms airborne`() {
        val config = fastConfig().copy(
            returnTimeoutMillis = 5L,
            maximumMissionDurationMillis = 1_000_000L,
        )
        val harness = MissionHarness(config = config)
        harness.enterCruise()
        val current = harness.destinationLocal().copy(
            x = harness.destinationLocal().x / 2.0,
            y = harness.destinationLocal().y / 2.0,
            z = -harness.plan.targetAltitudeAboveOriginMeters,
        )
        val returning = harness.step(
            request = AutopilotRequest.ABORT,
            position = current,
            altitudeMeters = harness.plan.targetAltitudeAboveOriginMeters,
        )
        assertEquals(AutopilotPhase.RETURN_CLIMB, returning.snapshot.phase)

        val landing = harness.step(
            deltaMillis = config.returnTimeoutMillis + 1L,
            position = current,
            altitudeMeters = harness.plan.targetAltitudeAboveOriginMeters,
        )

        assertEquals(AutopilotPhase.LANDING, landing.snapshot.phase)
        assertEquals(FlightControllerLifecycleRequest.RUN, landing.lifecycleRequest)
        requireNotNull(landing.flightControlCommand)
        assertTrue(AutopilotIssueCode.PHASE_TIMEOUT in landing.snapshot.issueCodes())
    }

    @Test
    fun `mission timeout initiates return once and never fails on the following airborne tick`() {
        val config = fastConfig().copy(
            maximumMissionDurationMillis = 20L,
            returnTimeoutMillis = 1_000_000L,
        )
        val harness = MissionHarness(config = config)
        harness.enterCruise()
        val current = harness.destinationLocal().copy(
            x = harness.destinationLocal().x / 2.0,
            y = harness.destinationLocal().y / 2.0,
            z = -harness.plan.targetAltitudeAboveOriginMeters,
        )

        val timedOut = harness.step(
            deltaMillis = 6L,
            position = current,
            altitudeMeters = harness.plan.targetAltitudeAboveOriginMeters,
        )
        assertEquals(AutopilotPhase.RETURN_CLIMB, timedOut.snapshot.phase)
        assertEquals(FlightControllerLifecycleRequest.RUN, timedOut.lifecycleRequest)

        val following = harness.step(
            position = current,
            altitudeMeters = harness.plan.targetAltitudeAboveOriginMeters,
        )
        assertEquals(AutopilotPhase.RETURN_CLIMB, following.snapshot.phase)
        assertEquals(FlightControllerLifecycleRequest.RUN, following.lifecycleRequest)
        assertTrue(AutopilotIssueCode.MISSION_TIMEOUT in following.snapshot.issueCodes())
    }

    @Test
    fun `landing timeout is one shot and continues descent rather than disarming`() {
        val config = fastConfig().copy(
            landingTimeoutMillis = 5L,
            maximumMissionDurationMillis = 1_000_000L,
        )
        val harness = MissionHarness(config = config)
        harness.enterLanding(observeAirborne = true)
        val destination = harness.destinationLocal()
        val currentAltitude = harness.plan.targetAltitudeAboveOriginMeters / 2.0
        val current = destination.copy(z = -currentAltitude)

        repeat(2) { index ->
            val output = harness.step(
                deltaMillis = if (index == 0) config.landingTimeoutMillis + 1L else 1L,
                position = current,
                altitudeMeters = currentAltitude,
                landingState = LandedState.AIRBORNE,
            )
            assertEquals(AutopilotPhase.LANDING, output.snapshot.phase)
            assertEquals(FlightControllerLifecycleRequest.RUN, output.lifecycleRequest)
            requireNotNull(output.flightControlCommand)
            assertTrue(AutopilotIssueCode.PHASE_TIMEOUT in output.snapshot.issueCodes())
        }
    }

    @Test
    fun `emergency stop wins over duplicate time and controller emergency state`() {
        val plan = validPlan()
        val now = 2_000L.ms
        val requested = Autopilot.create(plan)
        requested.step(readyInput(plan, now), AutopilotRequest.START)

        val duplicateTimeEmergency = requested.step(
            readyInput(plan, now),
            AutopilotRequest.EMERGENCY_STOP,
        )
        assertEmergencyStopped(duplicateTimeEmergency)
        assertFalse(
            AutopilotIssueCode.TIMESTAMP_NOT_MONOTONIC in
                duplicateTimeEmergency.snapshot.issueCodes(),
        )

        val controllerReported = Autopilot.create(plan)
        controllerReported.step(readyInput(plan, now), AutopilotRequest.START)
        val backwardsTimeEmergency = controllerReported.step(
            readyInput(
                plan = plan,
                now = now - 1L.ms,
                armingState = FlightControllerArmingState.EMERGENCY_STOPPED,
            ),
        )
        assertEmergencyStopped(backwardsTimeEmergency)
    }

    @Test
    fun `controller failsafe fails the mission and requests disarm`() {
        val harness = MissionHarness()
        harness.launchToTakeoff(observeAirborne = true)

        val failed = harness.step(
            armingState = FlightControllerArmingState.FAILSAFE,
            position = Vector3d(0.0, 0.0, -5.0),
            altitudeMeters = 5.0,
            landingState = LandedState.AIRBORNE,
        )

        assertEquals(AutopilotPhase.FAILED, failed.snapshot.phase)
        assertEquals(
            setOf(AutopilotIssueCode.FLIGHT_CONTROLLER_FAILSAFE),
            failed.snapshot.issueCodes(),
        )
        assertEquals(FlightControllerLifecycleRequest.DISARM, failed.lifecycleRequest)
        assertNull(failed.flightControlCommand)
    }

    @Test
    fun `duplicate or backwards ordinary timestamps fail closed`() {
        val plan = validPlan()
        val now = 2_000L.ms

        listOf(now, now - 1L.ms).forEach { invalidTimestamp ->
            val autopilot = Autopilot.create(plan)
            autopilot.step(readyInput(plan, now), AutopilotRequest.START)

            val failed = autopilot.step(readyInput(plan, invalidTimestamp))

            assertEquals(AutopilotPhase.FAILED, failed.snapshot.phase)
            assertEquals(
                setOf(AutopilotIssueCode.TIMESTAMP_NOT_MONOTONIC),
                failed.snapshot.issueCodes(),
            )
            assertEquals(FlightControllerLifecycleRequest.DISARM, failed.lifecycleRequest)
            assertNull(failed.flightControlCommand)
        }
    }

    @Test
    fun `touchdown requires sustained downward range plus independent landed evidence`() {
        val config = fastConfig(touchdownConfirmationMillis = 10L).copy(
            requireGroundRangeForTouchdown = true,
            groundRangeRequiredConsecutiveSamples = 3,
        )
        val harness = MissionHarness(config = config)
        harness.enterLanding(observeAirborne = true)
        val altitude = harness.plan.destinationGroundAltitudeAboveOriginMeters + 0.2
        val position = harness.destinationLocal().copy(z = -altitude)

        val detectorOnly = harness.step(
            position = position,
            altitudeMeters = altitude,
            landingState = LandedState.ON_GROUND,
        )
        assertEquals(AutopilotPhase.LANDING, detectorOnly.snapshot.phase)
        assertEquals(
            LandingRangeAidState.HOLDING_FOR_VALID_RANGE,
            detectorOnly.snapshot.landingRangeAidState,
        )

        repeat(2) {
            val acquiring = harness.step(
                position = position,
                altitudeMeters = altitude,
                landingState = LandedState.ON_GROUND,
                groundRangeDistanceMeters = 0.2,
            )
            assertEquals(AutopilotPhase.LANDING, acquiring.snapshot.phase)
            assertEquals(LandingRangeAidState.ACQUIRING, acquiring.snapshot.landingRangeAidState)
        }
        val firstCombinedEvidence = harness.step(
            position = position,
            altitudeMeters = altitude,
            landingState = LandedState.ON_GROUND,
            groundRangeDistanceMeters = 0.2,
        )
        assertEquals(AutopilotPhase.LANDING, firstCombinedEvidence.snapshot.phase)
        assertEquals(LandingRangeAidState.ACTIVE, firstCombinedEvidence.snapshot.landingRangeAidState)

        val confirmed = harness.step(
            deltaMillis = config.touchdownConfirmationMillis,
            position = position,
            altitudeMeters = altitude,
            landingState = LandedState.ON_GROUND,
            groundRangeDistanceMeters = 0.2,
        )
        assertEquals(AutopilotPhase.DISARMING, confirmed.snapshot.phase)
        assertEquals(FlightControllerLifecycleRequest.DISARM, confirmed.lifecycleRequest)
    }

    @Test
    fun `required downward range blocks preflight until a fresh plausible sample exists`() {
        val config = fastConfig().copy(requireGroundRangeForTouchdown = true)
        val plan = validPlan()
        val autopilot = Autopilot.create(plan, config)
        var now = 1_000L.ms

        val started = autopilot.step(
            readyInput(plan, now, groundRange = null),
            AutopilotRequest.START,
        )
        assertEquals(AutopilotPhase.PREFLIGHT, started.snapshot.phase)

        val missing = autopilot.step(
            readyInput(
                plan = plan,
                now = ++now,
                groundRange = null,
                trackingSequence = started.flightControlCommand?.sequence,
            ),
        )
        assertEquals(AutopilotPhase.PREFLIGHT, missing.snapshot.phase)
        assertTrue(AutopilotIssueCode.GROUND_RANGE_UNUSABLE in missing.snapshot.issueCodes())

        val live = AutopilotGroundRangeObservation(
            distanceMeters = 0.2,
            signalQualityPercent = 100,
            observedAtNanos = ++now,
        )
        val ready = autopilot.step(
            readyInput(
                plan = plan,
                now = now,
                groundRange = live,
                trackingSequence = missing.flightControlCommand?.sequence,
            ),
        )
        assertEquals(AutopilotPhase.ARMING, ready.snapshot.phase)
        assertFalse(AutopilotIssueCode.GROUND_RANGE_UNUSABLE in ready.snapshot.issueCodes())
    }

    @Test
    fun `touchdown requires airborne history before ground evidence is accepted`() {
        val harness = MissionHarness()
        harness.enterLanding(observeAirborne = false)
        val destinationGround = harness.destinationLocal().copy(
            z = -harness.plan.destinationGroundAltitudeAboveOriginMeters,
        )

        harness.step(
            armingState = FlightControllerArmingState.ARMED,
            position = destinationGround,
            altitudeMeters = harness.plan.destinationGroundAltitudeAboveOriginMeters,
            landingState = LandedState.ON_GROUND,
        )
        val stillLanding = harness.step(
            deltaMillis = harness.config.touchdownConfirmationMillis,
            armingState = FlightControllerArmingState.ARMED,
            position = destinationGround,
            altitudeMeters = harness.plan.destinationGroundAltitudeAboveOriginMeters,
            landingState = LandedState.ON_GROUND,
        )
        assertEquals(AutopilotPhase.LANDING, stillLanding.snapshot.phase)

        harness.step(
            armingState = FlightControllerArmingState.ARMED,
            position = destinationGround,
            altitudeMeters = harness.plan.destinationGroundAltitudeAboveOriginMeters,
            landingState = LandedState.AIRBORNE,
        )
        harness.step(
            armingState = FlightControllerArmingState.ARMED,
            position = destinationGround,
            altitudeMeters = harness.plan.destinationGroundAltitudeAboveOriginMeters,
            landingState = LandedState.ON_GROUND,
        )
        val disarming = harness.step(
            deltaMillis = harness.config.touchdownConfirmationMillis,
            armingState = FlightControllerArmingState.ARMED,
            position = destinationGround,
            altitudeMeters = harness.plan.destinationGroundAltitudeAboveOriginMeters,
            landingState = LandedState.ON_GROUND,
        )
        assertEquals(AutopilotPhase.DISARMING, disarming.snapshot.phase)
    }

    @Test
    fun `confirmed touchdown disarms before wind and timeout containment can command climb`() {
        val config = fastConfig(touchdownConfirmationMillis = 5L).copy(
            landingTimeoutMillis = 1L,
            maximumMissionDurationMillis = 1_000_000L,
        )
        val harness = MissionHarness(config = config)
        harness.enterLanding(observeAirborne = true)
        val ground = harness.destinationLocal().copy(
            z = -harness.plan.destinationGroundAltitudeAboveOriginMeters,
        )
        harness.step(
            deltaMillis = 2L,
            position = ground,
            altitudeMeters = harness.plan.destinationGroundAltitudeAboveOriginMeters,
            landingState = LandedState.ON_GROUND,
        )

        val disarming = harness.step(
            deltaMillis = config.touchdownConfirmationMillis,
            position = ground,
            altitudeMeters = harness.plan.destinationGroundAltitudeAboveOriginMeters,
            landingState = LandedState.ON_GROUND,
            safetyStatus = healthySafety(harness.now + config.touchdownConfirmationMillis.ms).copy(
                windWithinLimits = false,
            ),
        )

        assertEquals(AutopilotPhase.DISARMING, disarming.snapshot.phase)
        assertEquals(FlightControllerLifecycleRequest.DISARM, disarming.lifecycleRequest)
        assertNull(disarming.flightControlCommand)
    }

    @Test
    fun `touchdown requires fresh advancing independent detector evidence`() {
        val harness = MissionHarness()
        harness.enterLanding(observeAirborne = true)
        val destinationGround = harness.destinationLocal().copy(
            z = -harness.plan.destinationGroundAltitudeAboveOriginMeters,
        )

        val unavailable = harness.step(
            armingState = FlightControllerArmingState.ARMED,
            position = destinationGround,
            altitudeMeters = harness.plan.destinationGroundAltitudeAboveOriginMeters,
            landingState = LandedState.UNAVAILABLE,
        )
        assertEquals(AutopilotPhase.LANDING, unavailable.snapshot.phase)

        val stale = harness.step(
            armingState = FlightControllerArmingState.ARMED,
            position = destinationGround,
            altitudeMeters = harness.plan.destinationGroundAltitudeAboveOriginMeters,
            landingState = LandedState.ON_GROUND,
            landingObservedAtNanos = harness.now -
                (harness.config.maximumLandingObservationAgeMillis + 1L).ms,
        )
        assertEquals(AutopilotPhase.LANDING, stale.snapshot.phase)

        val moving = harness.step(
            armingState = FlightControllerArmingState.ARMED,
            position = destinationGround,
            altitudeMeters = harness.plan.destinationGroundAltitudeAboveOriginMeters,
            localVelocity = Vector3d(
                harness.config.maximumTouchdownHorizontalSpeedMetersPerSecond + 0.01,
                0.0,
                0.0,
            ),
            landingState = LandedState.ON_GROUND,
        )
        assertEquals(AutopilotPhase.LANDING, moving.snapshot.phase)

        harness.step(
            armingState = FlightControllerArmingState.ARMED,
            position = destinationGround,
            altitudeMeters = harness.plan.destinationGroundAltitudeAboveOriginMeters,
            landingState = LandedState.ON_GROUND,
        )
        val firstObservation = harness.now

        val unchangedObservation = harness.step(
            deltaMillis = harness.config.touchdownConfirmationMillis + 1L,
            armingState = FlightControllerArmingState.ARMED,
            position = destinationGround,
            altitudeMeters = harness.plan.destinationGroundAltitudeAboveOriginMeters,
            landingState = LandedState.ON_GROUND,
            landingObservedAtNanos = firstObservation,
        )
        assertEquals(AutopilotPhase.LANDING, unchangedObservation.snapshot.phase)

        val regressedObservation = harness.step(
            armingState = FlightControllerArmingState.ARMED,
            position = destinationGround,
            altitudeMeters = harness.plan.destinationGroundAltitudeAboveOriginMeters,
            landingState = LandedState.ON_GROUND,
            landingObservedAtNanos = firstObservation - 1L.ms,
        )
        assertEquals(AutopilotPhase.LANDING, regressedObservation.snapshot.phase)

        harness.step(
            armingState = FlightControllerArmingState.ARMED,
            position = destinationGround,
            altitudeMeters = harness.plan.destinationGroundAltitudeAboveOriginMeters,
            landingState = LandedState.ON_GROUND,
        )
        val restartedAt = harness.now
        val confirmed = harness.step(
            deltaMillis = harness.config.touchdownConfirmationMillis,
            armingState = FlightControllerArmingState.ARMED,
            position = destinationGround,
            altitudeMeters = harness.plan.destinationGroundAltitudeAboveOriginMeters,
            landingState = LandedState.ON_GROUND,
            landingObservedAtNanos = restartedAt + harness.config.touchdownConfirmationMillis.ms,
        )
        assertEquals(AutopilotPhase.DISARMING, confirmed.snapshot.phase)
    }

    @Test
    fun `local NED projection preserves axes and takes short antimeridian path`() {
        val north = localHorizontalNedMeters(
            GeoPoint(35.0, 51.0),
            GeoCoordinate(35.001, 51.0),
        )
        assertEquals(111.31949, north.x, 0.001)
        assertEquals(0.0, north.y, 0.000001)
        assertEquals(0.0, north.z, 0.0)

        val eastAcrossAntimeridian = localHorizontalNedMeters(
            GeoPoint(0.0, 179.9999),
            GeoCoordinate(0.0, -179.9999),
        )
        assertEquals(0.0, eastAcrossAntimeridian.x, 0.000001)
        assertEquals(22.263898, eastAcrossAntimeridian.y, 0.001)

        val westAcrossAntimeridian = localHorizontalNedMeters(
            GeoPoint(0.0, -179.9999),
            GeoCoordinate(0.0, 179.9999),
        )
        assertEquals(-22.263898, westAcrossAntimeridian.y, 0.001)
    }

    @Test
    fun `route corridor distance is measured to finite segment endpoints`() {
        val start = Vector3d.ZERO
        val end = Vector3d(10.0, 0.0, 0.0)

        assertEquals(
            3.0,
            distanceToSegmentMeters(Vector3d(5.0, 3.0, 100.0), start, end),
            0.000001,
        )
        assertEquals(
            5.0,
            distanceToSegmentMeters(Vector3d(-4.0, 3.0, 0.0), start, end),
            0.000001,
        )
        assertEquals(
            5.0,
            distanceToSegmentMeters(Vector3d(14.0, -3.0, 0.0), start, end),
            0.000001,
        )
    }

    private fun assertPreflightCodes(
        plan: FlightPlan,
        input: AutopilotInput,
        vararg expected: AutopilotIssueCode,
    ) {
        val output = Autopilot.create(plan).step(input, AutopilotRequest.START)

        assertEquals(AutopilotPhase.PREFLIGHT, output.snapshot.phase)
        assertEquals(expected.toSet(), output.snapshot.issueCodes())
    }

    private fun assertCommand(
        output: AutopilotOutput,
        expectedSequence: Long,
        validityMillis: Long,
    ) {
        val command = requireNotNull(output.flightControlCommand)
        assertEquals(expectedSequence, command.sequence)
        assertEquals(output.timestampNanos, command.issuedAtNanos)
        assertEquals(validityMillis.ms, command.validityDurationNanos)
        assertEquals(output.timestampNanos + validityMillis.ms, command.validUntilNanos)
    }

    private fun assertEmergencyStopped(output: AutopilotOutput) {
        assertEquals(AutopilotPhase.EMERGENCY_STOPPED, output.snapshot.phase)
        assertEquals(FlightControllerLifecycleRequest.EMERGENCY_STOP, output.lifecycleRequest)
        assertNull(output.flightControlCommand)
    }

    private fun AutopilotSnapshot.issueCodes(): Set<AutopilotIssueCode> =
        issues.mapTo(mutableSetOf()) { it.code }

    private fun AutopilotOutput.positionTarget(): Vector3d =
        (requireNotNull(flightControlCommand).setpoint as PositionControlTarget)
            .localPositionNedMeters

    private fun assertVectorEquals(expected: Vector3d, actual: Vector3d) {
        assertEquals(expected.x, actual.x, 0.000001)
        assertEquals(expected.y, actual.y, 0.000001)
        assertEquals(expected.z, actual.z, 0.000001)
    }

    private fun validPlan(
        targetAltitudeMeters: Double = 20.0,
        destinationGroundAltitudeMeters: Double = 2.0,
    ): FlightPlan = FlightPlan(
        origin = GeoCoordinate(35.6892, 51.3890),
        destination = GeoCoordinate(35.6893, 51.3890),
        targetAltitudeAboveOriginMeters = targetAltitudeMeters,
        destinationGroundAltitudeAboveOriginMeters = destinationGroundAltitudeMeters,
    )

    private fun fastConfig(
        commandValidityMillis: Long = 250L,
        targetSettleMillis: Long = 10L,
        touchdownConfirmationMillis: Long = 10L,
    ): AutopilotConfig = AutopilotConfig(
        commandValidityMillis = commandValidityMillis,
        takeoffMaximumSpeedMetersPerSecond = 1_000_000.0,
        takeoffMaximumAccelerationMetersPerSecondSquared = 1_000_000_000_000.0,
        cruiseMaximumSpeedMetersPerSecond = 1_000_000.0,
        cruiseMaximumAccelerationMetersPerSecondSquared = 1_000_000_000_000.0,
        targetSettleMillis = targetSettleMillis,
        touchdownConfirmationMillis = touchdownConfirmationMillis,
        requireGroundRangeForTouchdown = false,
    )

    private fun healthySafety(now: Long): AutopilotSafetyStatus = AutopilotSafetyStatus(
        routeAndAirspaceClear = true,
        destinationLandingZoneClear = true,
        energyReserveSufficient = true,
        geofenceHealthy = true,
        windWithinLimits = true,
        observedAtNanos = now,
    )

    private fun healthyHealth(): FlightControllerHealth = FlightControllerHealth(
        inputFresh = true,
        attitudeAvailable = true,
        angularRateAvailable = true,
        boardReady = true,
        actuatorAvailable = true,
        motorChannelsReady = true,
        commandAccepted = true,
        issues = emptyList(),
    )

    private fun readyInput(
        plan: FlightPlan,
        now: Long,
        navigationFix: AutopilotNavigationFix? = AutopilotNavigationFix(plan.origin, 1.0, now),
        landingObservation: AutopilotLandingObservation = AutopilotLandingObservation(
            LandedState.ON_GROUND,
            now,
        ),
        groundRange: AutopilotGroundRangeObservation? = AutopilotGroundRangeObservation(
            distanceMeters = 0.2,
            signalQualityPercent = 100,
            observedAtNanos = now,
        ),
        safetyStatus: AutopilotSafetyStatus = healthySafety(now),
        armingState: FlightControllerArmingState = FlightControllerArmingState.DISARMED,
        reference: GeoPoint? = GeoPoint(plan.origin.latitude, plan.origin.longitude),
        localPosition: Vector3d? = Vector3d.ZERO,
        altitudeMeters: Double? = 0.0,
        localVelocity: Vector3d? = Vector3d.ZERO,
        verticalVelocityMetersPerSecond: Double? = 0.0,
        health: FlightControllerHealth = healthyHealth(),
        trackingSequence: Long? = null,
        targetStatus: ControlTargetStatus = ControlTargetStatus.INACTIVE,
        includeLastOutput: Boolean = true,
    ): AutopilotInput = AutopilotInput(
        timestampNanos = now,
        flightController = controllerSnapshot(
            now = now,
            armingState = armingState,
            reference = reference,
            localPosition = localPosition,
            altitudeMeters = altitudeMeters,
            localVelocity = localVelocity,
            verticalVelocityMetersPerSecond = verticalVelocityMetersPerSecond,
            health = health,
            trackingSequence = trackingSequence,
            targetStatus = targetStatus,
            includeLastOutput = includeLastOutput,
        ),
        navigationFix = navigationFix,
        landingObservation = landingObservation,
        groundRange = groundRange,
        safetyStatus = safetyStatus,
    )

    private fun controllerSnapshot(
        now: Long,
        armingState: FlightControllerArmingState,
        reference: GeoPoint?,
        localPosition: Vector3d?,
        altitudeMeters: Double?,
        localVelocity: Vector3d?,
        verticalVelocityMetersPerSecond: Double?,
        health: FlightControllerHealth,
        trackingSequence: Long?,
        targetStatus: ControlTargetStatus,
        includeLastOutput: Boolean,
    ): FlightControllerSnapshot {
        val estimate = VehicleStateEstimate(
            localReference = if (reference == null) {
                LocalNavigationReference()
            } else {
                LocalNavigationReference(
                    horizontalOrigin = reference,
                    horizontalOriginObservedAtNanos = now,
                )
            },
            localPositionNedMeters = localPosition,
            localVelocityNedMetersPerSecond = localVelocity,
            altitudeAboveOriginMeters = altitudeMeters,
            verticalVelocityMetersPerSecond = verticalVelocityMetersPerSecond,
            altitudeObservedAtNanos = if (altitudeMeters == null) null else now,
            verticalVelocityObservedAtNanos = if (
                verticalVelocityMetersPerSecond == null
            ) {
                null
            } else {
                now
            },
            positionObservedAtNanos = if (localPosition == null) null else now,
            velocityObservedAtNanos = if (localVelocity == null) null else now,
        )
        val lastOutput = if (includeLastOutput) {
            FlightControllerOutput(
                tracking = ControlTracking(
                    commandSequence = trackingSequence,
                    targetStatus = targetStatus,
                ),
            )
        } else {
            null
        }
        return FlightControllerSnapshot(
            armingState = armingState,
            estimate = estimate,
            health = health,
            lastOutput = lastOutput,
        )
    }

    private inner class MissionHarness(
        val plan: FlightPlan = validPlan(),
        val config: AutopilotConfig = fastConfig(),
    ) {
        val autopilot: Autopilot = Autopilot.create(plan, config)
        var now: Long = 1_000L.ms
            private set
        private var lastOutput: AutopilotOutput? = null

        fun step(
            deltaMillis: Long = 1L,
            request: AutopilotRequest = AutopilotRequest.NONE,
            armingState: FlightControllerArmingState = FlightControllerArmingState.ARMED,
            position: Vector3d = Vector3d.ZERO,
            altitudeMeters: Double = plan.targetAltitudeAboveOriginMeters,
            localVelocity: Vector3d = Vector3d.ZERO,
            verticalVelocityMetersPerSecond: Double = 0.0,
            landingState: LandedState = LandedState.AIRBORNE,
            landingObservedAtNanos: Long? = null,
            groundRangeDistanceMeters: Double? = null,
            safetyStatus: AutopilotSafetyStatus? = null,
            targetStatus: ControlTargetStatus = ControlTargetStatus.TRACKING,
            trackLatestCommand: Boolean = true,
            trackedSequence: Long? = null,
        ): AutopilotOutput {
            now += deltaMillis.ms
            val sequence = if (trackLatestCommand) {
                lastOutput?.flightControlCommand?.sequence
            } else {
                trackedSequence
            }
            val landingObservation = if (landingState == LandedState.UNAVAILABLE) {
                AutopilotLandingObservation()
            } else {
                AutopilotLandingObservation(
                    state = landingState,
                    observedAtNanos = landingObservedAtNanos ?: now,
                )
            }
            val output = autopilot.step(
                readyInput(
                    plan = plan,
                    now = now,
                    navigationFix = AutopilotNavigationFix(plan.origin, 1.0, now),
                    landingObservation = landingObservation,
                    groundRange = groundRangeDistanceMeters?.let { distance ->
                        AutopilotGroundRangeObservation(
                            distanceMeters = distance,
                            signalQualityPercent = 100,
                            observedAtNanos = now,
                        )
                    },
                    safetyStatus = safetyStatus ?: healthySafety(now),
                    armingState = armingState,
                    localPosition = position,
                    altitudeMeters = altitudeMeters,
                    localVelocity = localVelocity,
                    verticalVelocityMetersPerSecond = verticalVelocityMetersPerSecond,
                    trackingSequence = sequence,
                    targetStatus = targetStatus,
                    includeLastOutput = sequence != null,
                ),
                request,
            )
            lastOutput = output
            return output
        }

        fun destinationLocal(): Vector3d = localHorizontalNedMeters(
            GeoPoint(plan.origin.latitude, plan.origin.longitude),
            plan.destination,
        )

        fun launchToTakeoff(observeAirborne: Boolean): AutopilotOutput {
            val preflight = step(
                request = AutopilotRequest.START,
                armingState = FlightControllerArmingState.DISARMED,
                position = Vector3d.ZERO,
                altitudeMeters = 0.0,
                landingState = LandedState.ON_GROUND,
                groundRangeDistanceMeters = 0.2,
                targetStatus = ControlTargetStatus.INACTIVE,
            )
            assertEquals(AutopilotPhase.PREFLIGHT, preflight.snapshot.phase)

            val arming = step(
                armingState = FlightControllerArmingState.DISARMED,
                position = Vector3d.ZERO,
                altitudeMeters = 0.0,
                landingState = LandedState.ON_GROUND,
                groundRangeDistanceMeters = 0.2,
                targetStatus = ControlTargetStatus.INACTIVE,
            )
            assertEquals(AutopilotPhase.ARMING, arming.snapshot.phase)

            val takeoffStarted = step(
                armingState = FlightControllerArmingState.ARMED,
                position = Vector3d.ZERO,
                altitudeMeters = 0.0,
                // The board's positive arm acknowledgement is itself revalidated against the
                // full preflight gate; liftoff evidence begins only after that boundary.
                landingState = LandedState.ON_GROUND,
                groundRangeDistanceMeters = 0.2,
            )
            assertEquals(AutopilotPhase.TAKEOFF, takeoffStarted.snapshot.phase)

            // The transition output is the trajectory origin. Advance once so callers receive
            // the final profiled command that may legitimately be acknowledged as reached.
            return step(
                armingState = FlightControllerArmingState.ARMED,
                position = Vector3d(0.0, 0.0, -plan.targetAltitudeAboveOriginMeters),
                altitudeMeters = plan.targetAltitudeAboveOriginMeters,
                landingState = if (observeAirborne) {
                    LandedState.AIRBORNE
                } else {
                    LandedState.ON_GROUND
                },
                targetStatus = ControlTargetStatus.TRACKING,
            ).also { profiledTakeoff ->
                assertEquals(AutopilotPhase.TAKEOFF, profiledTakeoff.snapshot.phase)
            }
        }

        fun settleCurrentTarget(
            position: Vector3d,
            altitudeMeters: Double,
            landingState: LandedState = LandedState.AIRBORNE,
        ): AutopilotOutput {
            val firstReached = step(
                armingState = FlightControllerArmingState.ARMED,
                position = position,
                altitudeMeters = altitudeMeters,
                landingState = landingState,
                targetStatus = ControlTargetStatus.REACHED,
            )
            val transitioned = if (config.targetSettleMillis == 0L) {
                firstReached
            } else {
                step(
                    deltaMillis = config.targetSettleMillis,
                    armingState = FlightControllerArmingState.ARMED,
                    position = position,
                    altitudeMeters = altitudeMeters,
                    landingState = landingState,
                    targetStatus = ControlTargetStatus.REACHED,
                )
            }
            return primeNewProfile(transitioned, landingState)
        }

        private fun primeNewProfile(
            transitionOutput: AutopilotOutput,
            landingState: LandedState,
        ): AutopilotOutput {
            val destination = when (transitionOutput.snapshot.phase) {
                AutopilotPhase.CRUISE -> destinationLocal().copy(
                    z = -plan.targetAltitudeAboveOriginMeters,
                )
                AutopilotPhase.RETURNING -> Vector3d(
                    0.0,
                    0.0,
                    -plan.targetAltitudeAboveOriginMeters,
                )
                else -> return transitionOutput
            }
            return step(
                armingState = FlightControllerArmingState.ARMED,
                position = destination,
                altitudeMeters = plan.targetAltitudeAboveOriginMeters,
                landingState = landingState,
                targetStatus = ControlTargetStatus.TRACKING,
            )
        }

        fun enterCruise(observeAirborne: Boolean = true): AutopilotOutput {
            launchToTakeoff(observeAirborne)
            val cruise = settleCurrentTarget(
                position = Vector3d(0.0, 0.0, -plan.targetAltitudeAboveOriginMeters),
                altitudeMeters = plan.targetAltitudeAboveOriginMeters,
                landingState = if (observeAirborne) {
                    LandedState.AIRBORNE
                } else {
                    LandedState.ON_GROUND
                },
            )
            assertEquals(AutopilotPhase.CRUISE, cruise.snapshot.phase)
            return cruise
        }

        fun enterLanding(observeAirborne: Boolean): AutopilotOutput {
            enterCruise(observeAirborne)
            val destination = destinationLocal().copy(z = -plan.targetAltitudeAboveOriginMeters)
            val landing = settleCurrentTarget(
                position = destination,
                altitudeMeters = plan.targetAltitudeAboveOriginMeters,
                landingState = if (observeAirborne) {
                    LandedState.AIRBORNE
                } else {
                    LandedState.ON_GROUND
                },
            )
            assertEquals(AutopilotPhase.LANDING, landing.snapshot.phase)
            return landing
        }
    }

    private val Long.ms: Long
        get() = this * 1_000_000L
}
