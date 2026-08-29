/** Exercises the public compass reading, direction, deviation, and validation contracts. */
package ir.hrka.compass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** Unit tests for the logical helpers exposed by [CompassReading]. */
class CompassReadingTest {
    /** Verifies all eight exact sector centers resolve to their semantic direction. */
    @Test
    fun `nearest direction resolves every sector center`() {
        CompassDirection.entries.forEach { direction ->
            assertEquals(
                direction,
                reading(magneticAzimuthDegrees = direction.bearingDegrees).nearestDirection(),
            )
        }
    }

    /** Verifies exact half-sector boundaries are assigned to the clockwise sector. */
    @Test
    fun `nearest direction uses clockwise half-sector boundaries`() {
        assertEquals(CompassDirection.NORTH, reading(22.499f).nearestDirection())
        assertEquals(CompassDirection.NORTH_EAST, reading(22.5f).nearestDirection())
        assertEquals(CompassDirection.NORTH_WEST, reading(337.499f).nearestDirection())
        assertEquals(CompassDirection.NORTH, reading(337.5f).nearestDirection())
    }

    /** Verifies signed north deviations distinguish clockwise and counter-clockwise turns. */
    @Test
    fun `direction deviation preserves shortest turn direction`() {
        val clockwise = reading(10f).deviationFrom(CompassDirection.NORTH)
        val counterClockwise = reading(350f).deviationFrom(CompassDirection.NORTH)

        assertEquals(10f, clockwise?.signedDegrees ?: Float.NaN, 0f)
        assertEquals(10f, clockwise?.absoluteDegrees ?: Float.NaN, 0f)
        assertEquals(-10f, counterClockwise?.signedDegrees ?: Float.NaN, 0f)
        assertEquals(10f, counterClockwise?.absoluteDegrees ?: Float.NaN, 0f)
    }

    /** Verifies the exact opposite direction follows the documented negative-180 policy. */
    @Test
    fun `opposite direction normalizes to negative one hundred eighty`() {
        val deviation = reading(0f).deviationFrom(CompassDirection.SOUTH)

        assertEquals(-180f, deviation?.signedDegrees ?: Float.NaN, 0f)
        assertEquals(180f, deviation?.absoluteDegrees ?: Float.NaN, 0f)
    }

    /** Verifies arbitrary bearings may be outside one turn and still normalize correctly. */
    @Test
    fun `arbitrary reference bearings normalize across multiple turns`() {
        val deviation = reading(5f).deviationFrom(725f)

        assertEquals(0f, deviation?.signedDegrees ?: Float.NaN, 0f)
        assertEquals(0f, deviation?.absoluteDegrees ?: Float.NaN, 0f)
    }

    /** Verifies true-north helpers use true azimuth instead of magnetic azimuth. */
    @Test
    fun `true north reference uses configured true azimuth`() {
        val reading = reading(
            magneticAzimuthDegrees = 350f,
            trueAzimuthDegrees = 5f,
            declinationDegrees = 15f,
        )

        assertEquals(5f, reading.azimuth(NorthReference.TRUE) ?: Float.NaN, 0f)
        assertEquals(
            CompassDirection.NORTH,
            reading.nearestDirection(NorthReference.TRUE),
        )
        assertEquals(
            5f,
            reading.deviationFrom(CompassDirection.NORTH, NorthReference.TRUE)
                ?.signedDegrees ?: Float.NaN,
            0f,
        )
    }

    /** Verifies true-relative helpers return null until geomagnetic information exists. */
    @Test
    fun `true north helpers are absent without declination`() {
        val reading = reading(45f)

        assertNull(reading.azimuth(NorthReference.TRUE))
        assertNull(reading.nearestDirection(NorthReference.TRUE))
        assertNull(reading.deviationFrom(CompassDirection.NORTH, NorthReference.TRUE))
    }

    /** Verifies non-finite arbitrary reference bearings are rejected. */
    @Test
    fun `deviation rejects non-finite reference bearing`() {
        assertThrows(IllegalArgumentException::class.java) {
            reading(0f).deviationFrom(Float.NaN)
        }
    }

    /** Verifies readings reject unnormalized, non-finite, and mismatched north-reference values. */
    @Test
    fun `reading validates all public angle invariants`() {
        assertThrows(IllegalArgumentException::class.java) { reading(-1f) }
        assertThrows(IllegalArgumentException::class.java) { reading(360f) }
        assertThrows(IllegalArgumentException::class.java) { reading(Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) {
            reading(
                magneticAzimuthDegrees = 0f,
                trueAzimuthDegrees = 1f,
                declinationDegrees = null,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            reading(
                magneticAzimuthDegrees = 0f,
                trueAzimuthDegrees = 90f,
                declinationDegrees = 0f,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            reading(
                magneticAzimuthDegrees = 0f,
                trueAzimuthDegrees = 181f,
                declinationDegrees = 181f,
            )
        }
    }

    /** Verifies direction deviations reject values outside their documented normalized ranges. */
    @Test
    fun `direction deviation validates signed and absolute ranges`() {
        assertThrows(IllegalArgumentException::class.java) {
            DirectionDeviation(signedDegrees = 180f, absoluteDegrees = 180f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DirectionDeviation(signedDegrees = 0f, absoluteDegrees = -1f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DirectionDeviation(signedDegrees = -10f, absoluteDegrees = 9f)
        }
    }

    /**
     * Creates a valid reading with concise defaults for focused public-model tests.
     *
     * @param magneticAzimuthDegrees normalized magnetic azimuth under test.
     * @param trueAzimuthDegrees optional normalized true azimuth.
     * @param declinationDegrees optional declination paired with [trueAzimuthDegrees].
     * @return immutable reading with neutral tilt, unknown accuracy, and rotation-vector source.
     */
    private fun reading(
        magneticAzimuthDegrees: Float,
        trueAzimuthDegrees: Float? = null,
        declinationDegrees: Float? = null,
    ): CompassReading = CompassReading(
        magneticAzimuthDegrees = magneticAzimuthDegrees,
        trueAzimuthDegrees = trueAzimuthDegrees,
        declinationDegrees = declinationDegrees,
        pitchDegrees = 0f,
        rollDegrees = 0f,
        accuracy = CompassAccuracy(
            level = CompassAccuracyLevel.UNKNOWN,
            estimatedErrorDegrees = null,
            calibrationStatus = CalibrationStatus.UNKNOWN,
        ),
        sensorSource = CompassSensorSource.ROTATION_VECTOR,
        timestampNanos = 1L,
    )
}
