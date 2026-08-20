/** Exercises construction-time invariants of the shared flight-plan model. */
package ir.hrka.shahbaz.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Unit tests for [FlightPlan]. */
class FlightPlanTest {
    /** Verifies valid setup values are retained without changing coordinate identity or units. */
    @Test
    fun `flight plan accepts fixed coordinates and positive target altitude`() {
        val origin = GeoCoordinate(35.6892, 51.3890)
        val destination = GeoCoordinate(35.7000, 51.4100)

        val plan = FlightPlan(
            origin = origin,
            destination = destination,
            targetAltitudeAboveOriginMeters = 42.5,
        )

        assertEquals(origin, plan.origin)
        assertEquals(destination, plan.destination)
        assertEquals(42.5, plan.targetAltitudeAboveOriginMeters, 0.0)
    }

    /** Verifies invalid target altitudes fail at the shared-model boundary. */
    @Test
    fun `flight plan rejects non-positive and non-finite target altitude`() {
        val origin = GeoCoordinate(35.6892, 51.3890)
        val destination = GeoCoordinate(35.7000, 51.4100)

        listOf(
            0.0,
            -1.0,
            Double.NaN,
            Double.NEGATIVE_INFINITY,
            Double.POSITIVE_INFINITY,
        ).forEach { altitude ->
            assertThrows(IllegalArgumentException::class.java) {
                FlightPlan(
                    origin = origin,
                    destination = destination,
                    targetAltitudeAboveOriginMeters = altitude,
                )
            }
        }
    }
}
