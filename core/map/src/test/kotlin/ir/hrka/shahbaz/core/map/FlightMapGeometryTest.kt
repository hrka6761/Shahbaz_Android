/** Tests pure bounds, heading, and marker decisions for the reusable flight-route map. */
package ir.hrka.shahbaz.core.map

import ir.hrka.shahbaz.core.model.GeoCoordinate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM coverage for map decisions that do not require Android or a MapLibre renderer. */
class FlightMapGeometryTest {
    /** Confirms camera bounds include both fixed endpoints and the current tracked position. */
    @Test
    fun `bounds contain route and current tracked position`() {
        val bounds = calculateFlightMapBounds(
            origin = GeoCoordinate(35.70, 51.40),
            destination = GeoCoordinate(35.75, 51.55),
            currentPosition = GeoCoordinate(35.65, 51.48),
        )

        assertEquals(51.40, bounds.west, 0.0)
        assertEquals(35.65, bounds.south, 0.0)
        assertEquals(51.55, bounds.east, 0.0)
        assertEquals(35.75, bounds.north, 0.0)
        assertFalse(bounds.isEffectivelyPoint)
    }

    /** Confirms colocated route points select the stable close-camera path. */
    @Test
    fun `colocated route produces point bounds`() {
        val coordinate = GeoCoordinate(35.6892, 51.3890)
        val bounds = calculateFlightMapBounds(coordinate, coordinate, coordinate)

        assertTrue(bounds.isEffectivelyPoint)
        assertEquals(coordinate, bounds.center)
    }

    /** Confirms live marker presentation is driven only by trustworthy available inputs. */
    @Test
    fun `position marker kind reflects coordinate and heading availability`() {
        val position = GeoCoordinate(35.7, 51.4)

        assertEquals(PositionMarkerKind.NONE, positionMarkerKind(null, null))
        assertEquals(PositionMarkerKind.POSITION, positionMarkerKind(position, null))
        assertEquals(PositionMarkerKind.DIRECTIONAL, positionMarkerKind(position, 90f))
        assertEquals(PositionMarkerKind.NONE, positionMarkerKind(null, 90f))
    }

    /** Confirms negative and multi-turn headings become MapLibre-compatible rotations. */
    @Test
    fun `heading normalization uses one clockwise turn`() {
        assertEquals(350f, normalizeHeadingDegrees(-10f), 0f)
        assertEquals(5f, normalizeHeadingDegrees(725f), 0f)
        assertEquals(0f, normalizeHeadingDegrees(360f), 0f)
    }

    /** Confirms invalid public state cannot imply a heading without a position. */
    @Test
    fun `route state rejects orphaned or nonfinite tracked heading`() {
        val origin = GeoCoordinate(35.7, 51.4)
        val destination = GeoCoordinate(35.8, 51.5)

        assertThrows(IllegalArgumentException::class.java) {
            FlightRouteMapState(
                origin = origin,
                destination = destination,
                currentPositionHeadingDegrees = 90f,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            FlightRouteMapState(
                origin = origin,
                destination = destination,
                currentPosition = origin,
                currentPositionHeadingDegrees = Float.NaN,
            )
        }
    }
}
