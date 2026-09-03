package ir.hrka.shahbaz.feature.dashboard

import ir.hrka.shahbaz.autopilot.AutopilotIssue
import ir.hrka.shahbaz.autopilot.AutopilotIssueCode
import ir.hrka.shahbaz.autopilot.AutopilotPhase
import ir.hrka.shahbaz.autopilot.AutopilotSnapshot
import ir.hrka.shahbaz.core.model.FlightPlan
import ir.hrka.shahbaz.core.model.GeoCoordinate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure tests for the operator controls derived from an autopilot snapshot. */
class AutopilotControlsPresentationTest {
    @Test
    fun standbyOffersStartAndSeparateEmergencyStop() {
        val presentation = autopilotControlPresentation(snapshot(AutopilotPhase.STANDBY))

        assertEquals(AutopilotPrimaryControl.START, presentation.primaryControl)
        assertTrue(presentation.showEmergencyStop)
    }

    @Test
    fun everyNonterminalPostStartPhaseOffersAbortAndEmergencyStop() {
        val activePhases = setOf(
            AutopilotPhase.PREFLIGHT,
            AutopilotPhase.ARMING,
            AutopilotPhase.TAKEOFF,
            AutopilotPhase.CRUISE,
            AutopilotPhase.LANDING,
            AutopilotPhase.RETURN_CLIMB,
            AutopilotPhase.RETURNING,
            AutopilotPhase.DISARMING,
        )

        activePhases.forEach { phase ->
            val presentation = autopilotControlPresentation(snapshot(phase))
            assertEquals(phase.name, AutopilotPrimaryControl.ABORT, presentation.primaryControl)
            assertTrue(phase.name, presentation.showEmergencyStop)
        }
    }

    @Test
    fun terminalPhasesExposeStatusWithoutActionControls() {
        val terminalPhases = setOf(
            AutopilotPhase.COMPLETED,
            AutopilotPhase.ABORTED,
            AutopilotPhase.FAILED,
            AutopilotPhase.EMERGENCY_STOPPED,
        )

        terminalPhases.forEach { phase ->
            val presentation = autopilotControlPresentation(snapshot(phase))
            assertEquals(phase.name, AutopilotPrimaryControl.STATUS, presentation.primaryControl)
            assertFalse(phase.name, presentation.showEmergencyStop)
        }
    }

    @Test
    fun missingSnapshotFailsClosedAsUnavailableStatus() {
        val presentation = autopilotControlPresentation(null)

        assertEquals(AutopilotPrimaryControl.STATUS, presentation.primaryControl)
        assertFalse(presentation.showEmergencyStop)
        assertNull(presentation.phase)
        assertNull(presentation.firstIssueMessage)
        assertEquals(0, presentation.additionalIssueCount)
    }

    @Test
    fun issuePresentationKeepsOnlyFirstMessageAndRemainingCount() {
        val presentation = autopilotControlPresentation(
            snapshot(
                phase = AutopilotPhase.PREFLIGHT,
                issues = listOf(
                    AutopilotIssue(
                        code = AutopilotIssueCode.NAVIGATION_FIX_MISSING,
                        message = "Navigation fix is required",
                    ),
                    AutopilotIssue(
                        code = AutopilotIssueCode.ENERGY_RESERVE_INSUFFICIENT,
                        message = "Energy reserve is insufficient",
                    ),
                ),
            ),
        )

        assertEquals("Navigation fix is required", presentation.firstIssueMessage)
        assertEquals(1, presentation.additionalIssueCount)
    }

    private fun snapshot(
        phase: AutopilotPhase,
        issues: List<AutopilotIssue> = emptyList(),
    ) = AutopilotSnapshot(
        phase = phase,
        flightPlan = FlightPlan(
            origin = GeoCoordinate(latitude = 35.7, longitude = 51.3),
            destination = GeoCoordinate(latitude = 35.71, longitude = 51.31),
            targetAltitudeAboveOriginMeters = 30.0,
            destinationGroundAltitudeAboveOriginMeters = 0.0,
        ),
        issues = issues,
    )
}
