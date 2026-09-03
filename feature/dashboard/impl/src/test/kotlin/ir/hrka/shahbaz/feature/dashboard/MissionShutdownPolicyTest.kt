package ir.hrka.shahbaz.feature.dashboard

import ir.hrka.shahbaz.autopilot.AutopilotPhase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies navigation cannot discard an armed or airborne mission. */
class MissionShutdownPolicyTest {
    @Test
    fun `arming airborne return and disarming phases require controlled shutdown`() {
        listOf(
            AutopilotPhase.ARMING,
            AutopilotPhase.TAKEOFF,
            AutopilotPhase.CRUISE,
            AutopilotPhase.LANDING,
            AutopilotPhase.RETURN_CLIMB,
            AutopilotPhase.RETURNING,
            AutopilotPhase.DISARMING,
        ).forEach { phase -> assertTrue(phase.requiresControlledShutdown()) }
    }

    @Test
    fun `disarmed and terminal phases may release the plan`() {
        listOf(
            null,
            AutopilotPhase.STANDBY,
            AutopilotPhase.PREFLIGHT,
            AutopilotPhase.COMPLETED,
            AutopilotPhase.ABORTED,
            AutopilotPhase.FAILED,
            AutopilotPhase.EMERGENCY_STOPPED,
        ).forEach { phase -> assertFalse(phase.requiresControlledShutdown()) }
    }

    @Test
    fun `only completed aborted failed and emergency results are terminal`() {
        listOf(
            AutopilotPhase.COMPLETED,
            AutopilotPhase.ABORTED,
            AutopilotPhase.FAILED,
            AutopilotPhase.EMERGENCY_STOPPED,
        ).forEach { phase -> assertTrue(phase.isTerminalMissionPhase()) }
        assertFalse(AutopilotPhase.STANDBY.isTerminalMissionPhase())
        assertFalse(AutopilotPhase.PREFLIGHT.isTerminalMissionPhase())
        assertFalse(AutopilotPhase.DISARMING.isTerminalMissionPhase())
        assertFalse(null.isTerminalMissionPhase())
    }
}
