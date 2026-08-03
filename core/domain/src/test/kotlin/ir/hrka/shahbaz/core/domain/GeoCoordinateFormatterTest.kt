/** Exercises coordinate parsing and locale-stable display formatting. */
package ir.hrka.shahbaz.core.domain

import ir.hrka.shahbaz.core.model.GeoCoordinate
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Unit tests for [parseCoordinatePair] and [formatCoordinate]. */
class GeoCoordinateFormatterTest {
    /** Verifies every supported separator form. */
    @Test
    fun `parser accepts comma semicolon and whitespace separators`() {
        assertEquals(GeoCoordinate(35.6892, 51.3890), parseCoordinatePair("35.6892, 51.3890"))
        assertEquals(GeoCoordinate(-33.8688, 151.2093), parseCoordinatePair(" -33.8688;151.2093 "))
        assertEquals(GeoCoordinate(40.7128, -74.0060), parseCoordinatePair("40.7128   -74.0060"))
        assertEquals(GeoCoordinate(1.0, 2.0), parseCoordinatePair("1\t2"))
    }

    /** Verifies signed decimals and scientific notation. */
    @Test
    fun `parser accepts signs decimals and scientific notation`() {
        assertEquals(GeoCoordinate(0.5, -0.25), parseCoordinatePair("+.5, -2.5e-1"))
        assertEquals(GeoCoordinate(10.0, 20.0), parseCoordinatePair("1e1 2E1"))
    }

    /** Verifies malformed, non-finite, and out-of-range values are rejected. */
    @Test
    fun `parser rejects malformed non-finite and out-of-range input`() {
        listOf(
            "",
            "35.0",
            "north, east",
            "NaN, 0",
            "Infinity, 0",
            "90.0001, 0",
            "0, 180.0001",
            "1, 2, 3",
        ).forEach { input ->
            assertNull("Expected invalid input: $input", parseCoordinatePair(input))
        }
    }

    /** Verifies formatting uses six US-locale decimals and removes rounded negative zero. */
    @Test
    fun `coordinate formatting always uses US decimal separator and six places`() {
        val originalLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals(
                "35.689200, 51.389000",
                formatCoordinate(GeoCoordinate(35.6892, 51.389)),
            )
            assertEquals(
                "0.000000, 0.000000",
                formatCoordinate(GeoCoordinate(-0.0000001, -0.0)),
            )
        } finally {
            Locale.setDefault(originalLocale)
        }
    }
}
