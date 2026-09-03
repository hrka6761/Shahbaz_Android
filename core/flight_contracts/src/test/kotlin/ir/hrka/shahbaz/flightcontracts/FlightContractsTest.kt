package ir.hrka.shahbaz.flightcontracts

import kotlin.math.PI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

/** Boundary and invariant tests for every public neutral contract family. */
class FlightContractsTest {
    @Test
    fun `vector arithmetic and quaternion rotation preserve finite geometry`() {
        val vector = Vector3d(1.0, 2.0, 3.0)
        assertEquals(Vector3d(2.0, 4.0, 6.0), vector * 2.0)
        assertEquals(Vector3d.ZERO, vector - vector)
        assertEquals(14.0, vector.dot(vector), 0.0)
        val rolledDown = Quaterniond.fromEuler(PI / 2.0, 0.0, 0.0)
            .rotate(Vector3d(0.0, 0.0, 1.0))
        assertEquals(-1.0, rolledDown.y, 1e-9)
        assertEquals(0.0, rolledDown.z, 1e-9)

        assertThrows(IllegalArgumentException::class.java) { Vector3d(Double.NaN, 0.0, 0.0) }
        assertThrows(IllegalArgumentException::class.java) {
            Quaterniond(Double.POSITIVE_INFINITY, 0.0, 0.0, 0.0)
        }
    }

    @Test
    fun `geographic and reference boundaries are fail closed`() {
        GeoPoint(-90.0, -180.0)
        GeoPoint(90.0, 180.0)
        assertThrows(IllegalArgumentException::class.java) { GeoPoint(90.0001, 0.0) }
        assertThrows(IllegalArgumentException::class.java) { GeoPoint(0.0, 180.0001) }
        assertThrows(IllegalArgumentException::class.java) {
            LocalNavigationReference(GeoPoint(0.0, 0.0), null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LocalNavigationReference(null, 1L)
        }
    }

    @Test
    fun `commands validate identity time and target values including overflow`() {
        val target = PositionControlTarget(Vector3d.ZERO)
        val command = FlightControlCommand.validFor(0L, 10L, 5L, target)
        assertEquals(5L, command.validityDurationNanos)
        assertThrows(IllegalArgumentException::class.java) {
            FlightControlCommand(-1L, 0L, 0L, target)
        }
        assertThrows(IllegalArgumentException::class.java) {
            FlightControlCommand(0L, 2L, 1L, target)
        }
        assertThrows(IllegalArgumentException::class.java) {
            FlightControlCommand.validFor(0L, Long.MAX_VALUE, 1L, target)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PositionControlTarget(Vector3d.ZERO, yawNedRadians = Double.NaN)
        }
    }

    @Test
    fun `feedback health estimate and tracking reject incoherent values`() {
        assertFalse(FlightControllerHealth().canArm)
        assertTrue(FlightControllerHealth(issues = emptyList()).canArm)
        assertThrows(IllegalArgumentException::class.java) {
            FlightControllerHealthIssue("", "message")
        }
        assertThrows(IllegalArgumentException::class.java) {
            VehicleStateEstimate(attitudeBodyToNed = Quaterniond(0.0, 0.0, 0.0, 0.0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            VehicleStateEstimate(altitudeObservedAtNanos = -1L)
        }
        assertThrows(IllegalArgumentException::class.java) { ControlTracking(commandSequence = -1L) }
    }
}
