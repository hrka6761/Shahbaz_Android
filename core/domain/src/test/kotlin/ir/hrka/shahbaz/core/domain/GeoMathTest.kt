/** Exercises geodesic, midpoint, distance-formatting, and compass-direction helpers. */
package ir.hrka.shahbaz.core.domain

import ir.hrka.shahbaz.core.model.GeoCoordinate
import java.util.Locale
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for the public calculations in `GeoMath.kt`. */
class GeoMathTest {
    /** Verifies identical coordinates have no WGS-84 separation. */
    @Test
    fun `WGS84 distance is zero for identical coordinates`() {
        val point = GeoCoordinate(35.6892, 51.3890)

        assertEquals(0.0, wgs84GeodesicDistanceMeters(point, point), 0.0)
    }

    /** Verifies the WGS-84 equatorial distance against a known one-degree value. */
    @Test
    fun `WGS84 distance matches one degree along equator`() {
        val distance = wgs84GeodesicDistanceMeters(
            GeoCoordinate(0.0, 0.0),
            GeoCoordinate(0.0, 1.0),
        )

        assertEquals(111_319.4908, distance, 0.001)
    }

    /** Verifies the WGS-84 meridional distance near the equator. */
    @Test
    fun `WGS84 distance matches one degree meridian near equator`() {
        val distance = wgs84GeodesicDistanceMeters(
            GeoCoordinate(0.0, 0.0),
            GeoCoordinate(1.0, 0.0),
        )

        assertEquals(110_574.3886, distance, 0.001)
    }

    /** Verifies distance is independent of endpoint order. */
    @Test
    fun `WGS84 distance is symmetric`() {
        val tehran = GeoCoordinate(35.6892, 51.3890)
        val shiraz = GeoCoordinate(29.5918, 52.5837)

        assertEquals(
            wgs84GeodesicDistanceMeters(tehran, shiraz),
            wgs84GeodesicDistanceMeters(shiraz, tehran),
            0.000001,
        )
    }

    /** Verifies antipodal Vincenty non-convergence uses the finite spherical fallback. */
    @Test
    fun `WGS84 antipodal distance uses a finite fallback`() {
        val distance = wgs84GeodesicDistanceMeters(
            GeoCoordinate(0.0, 0.0),
            GeoCoordinate(0.0, 180.0),
        )

        assertTrue(distance.isFinite())
        assertTrue(distance in 19_000_000.0..21_000_000.0)
    }

    /** Verifies basic Haversine identity and symmetry properties. */
    @Test
    fun `haversine distance is zero for the same point and symmetric`() {
        val tehran = GeoCoordinate(35.6892, 51.3890)
        val shiraz = GeoCoordinate(29.5918, 52.5837)

        assertEquals(0.0, haversineDistanceMeters(tehran, tehran), 0.0)
        assertEquals(
            haversineDistanceMeters(tehran, shiraz),
            haversineDistanceMeters(shiraz, tehran),
            0.000001,
        )
    }

    /** Verifies Haversine results against known great-circle distances. */
    @Test
    fun `haversine distance matches known great-circle distances`() {
        val oneDegreeAtEquator = haversineDistanceMeters(
            GeoCoordinate(0.0, 0.0),
            GeoCoordinate(0.0, 1.0),
        )
        assertEquals(111_195.08, oneDegreeAtEquator, 1.0)

        val newYorkToLondon = haversineDistanceMeters(
            GeoCoordinate(40.7128, -74.0060),
            GeoCoordinate(51.5074, -0.1278),
        )
        assertEquals(5_570_230.0, newYorkToLondon, 2_000.0)
    }

    /** Verifies the Haversine calculation crosses the antimeridian by the short path. */
    @Test
    fun `haversine distance takes the short path across antimeridian`() {
        val distance = haversineDistanceMeters(
            GeoCoordinate(0.0, 179.0),
            GeoCoordinate(0.0, -179.0),
        )

        assertEquals(222_390.16, distance, 2.0)
    }

    /** Verifies an ordinary great-circle midpoint. */
    @Test
    fun `spherical midpoint works on an ordinary great-circle arc`() {
        val midpoint = sphericalMidpoint(
            GeoCoordinate(0.0, 0.0),
            GeoCoordinate(0.0, 10.0),
        )

        assertEquals(0.0, midpoint.latitude, 0.0000001)
        assertEquals(5.0, midpoint.longitude, 0.0000001)
    }

    /** Verifies midpoint longitude remains near the antimeridian. */
    @Test
    fun `spherical midpoint stays at antimeridian instead of crossing Greenwich`() {
        val midpoint = sphericalMidpoint(
            GeoCoordinate(10.0, 179.0),
            GeoCoordinate(10.0, -179.0),
        )

        assertEquals(10.001493, midpoint.latitude, 0.00001)
        assertEquals(180.0, abs(midpoint.longitude), 0.0000001)
    }

    /** Verifies mathematically undefined antipodal midpoints are rejected. */
    @Test
    fun `spherical midpoint rejects antipodal coordinates`() {
        assertThrows(IllegalArgumentException::class.java) {
            sphericalMidpoint(GeoCoordinate(0.0, 0.0), GeoCoordinate(0.0, 180.0))
        }
    }

    /** Verifies the meter-to-kilometer formatting threshold and rounding. */
    @Test
    fun `distance formatting switches units at one kilometer`() {
        assertEquals("0 m", formatDistance(0.0))
        assertEquals("13 m", formatDistance(12.6))
        assertEquals("999 m", formatDistance(999.4))
        assertEquals("1000 m", formatDistance(999.6))
        assertEquals("1.00 km", formatDistance(1_000.0))
        assertEquals("1.23 km", formatDistance(1_234.56))
    }

    /** Verifies formatting does not inherit a comma-decimal system locale. */
    @Test
    fun `distance formatting always uses US decimal separator`() {
        val originalLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("1.50 km", formatDistance(1_500.0))
        } finally {
            Locale.setDefault(originalLocale)
        }
    }

    /** Verifies invalid distance values fail fast. */
    @Test
    fun `distance formatting rejects invalid values`() {
        listOf(-0.01, Double.NaN, Double.POSITIVE_INFINITY).forEach { value ->
            assertThrows(IllegalArgumentException::class.java) {
                formatDistance(value)
            }
        }
    }

    /** Verifies cardinal sectors and their exact half-sector boundaries. */
    @Test
    fun `cardinal direction covers sectors and exact boundaries`() {
        assertEquals(CardinalDirection8.NORTH, normalizedCardinalDirection(0.0))
        assertEquals(CardinalDirection8.NORTH, normalizedCardinalDirection(22.4999))
        assertEquals(CardinalDirection8.NORTH_EAST, normalizedCardinalDirection(22.5))
        assertEquals(CardinalDirection8.EAST, normalizedCardinalDirection(67.5))
        assertEquals(CardinalDirection8.SOUTH, normalizedCardinalDirection(180.0))
        assertEquals(CardinalDirection8.NORTH_WEST, normalizedCardinalDirection(315.0))
        assertEquals(CardinalDirection8.NORTH, normalizedCardinalDirection(337.5))
    }

    /** Verifies negative and multi-turn headings normalize correctly. */
    @Test
    fun `cardinal direction normalizes negative and multi-turn headings`() {
        assertEquals(CardinalDirection8.WEST, normalizedCardinalDirection(-90.0))
        assertEquals(CardinalDirection8.EAST, normalizedCardinalDirection(450.0))
        assertEquals(CardinalDirection8.NORTH, normalizedCardinalDirection(720.0))
        assertEquals("SW", normalizedCardinalDirection(-135.0).abbreviation)
    }

    /** Verifies non-finite headings are rejected. */
    @Test
    fun `cardinal direction rejects non-finite headings`() {
        listOf(Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY).forEach { value ->
            val error = assertThrows(IllegalArgumentException::class.java) {
                normalizedCardinalDirection(value)
            }
            assertTrue(error.message.orEmpty().contains("finite"))
        }
    }

    /** Verifies north and south deviations use the shortest angular path. */
    @Test
    fun `angular deviation uses the shortest path to north and south`() {
        assertEquals(0.0, angularDeviationDegrees(0.0, 0.0), 0.0)
        assertEquals(10.0, angularDeviationDegrees(350.0, 0.0), 0.0)
        assertEquals(90.0, angularDeviationDegrees(90.0, 0.0), 0.0)
        assertEquals(170.0, angularDeviationDegrees(350.0, 180.0), 0.0)
        assertEquals(0.0, angularDeviationDegrees(540.0, 180.0), 0.0)
    }

    /** Verifies non-finite heading or reference inputs are rejected. */
    @Test
    fun `angular deviation rejects non-finite values`() {
        assertThrows(IllegalArgumentException::class.java) {
            angularDeviationDegrees(Double.NaN, 0.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            angularDeviationDegrees(0.0, Double.POSITIVE_INFINITY)
        }
    }
}
