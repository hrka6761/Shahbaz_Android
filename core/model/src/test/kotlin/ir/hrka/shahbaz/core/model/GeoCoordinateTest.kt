/** Exercises construction-time invariants of the shared coordinate model. */
package ir.hrka.shahbaz.core.model

import org.junit.Assert.assertThrows
import org.junit.Test

/** Unit tests for [GeoCoordinate]. */
class GeoCoordinateTest {
    /** Verifies that all four inclusive latitude and longitude boundaries are accepted. */
    @Test
    fun `coordinate accepts inclusive geographic boundaries`() {
        GeoCoordinate(-90.0, -180.0)
        GeoCoordinate(90.0, 180.0)
    }

    /** Verifies that invalid latitude values fail construction. */
    @Test
    fun `coordinate rejects non-finite and out-of-range latitude`() {
        listOf(Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, -90.1, 90.1)
            .forEach { latitude ->
                assertThrows(IllegalArgumentException::class.java) {
                    GeoCoordinate(latitude, 0.0)
                }
            }
    }

    /** Verifies that invalid longitude values fail construction. */
    @Test
    fun `coordinate rejects non-finite and out-of-range longitude`() {
        listOf(Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, -180.1, 180.1)
            .forEach { longitude ->
                assertThrows(IllegalArgumentException::class.java) {
                    GeoCoordinate(0.0, longitude)
                }
            }
    }
}
