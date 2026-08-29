/** Exercises reusable compass configuration, accuracy, and geomagnetic-position value objects. */
package ir.hrka.compass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** Unit tests for public compass value-object defaults and validation. */
class CompassModelsTest {
    /** Verifies the no-argument configuration uses balanced production defaults. */
    @Test
    fun `configuration defaults are normal and balanced`() {
        val config = CompassConfig()

        assertEquals(CompassUpdateRate.NORMAL, config.updateRate)
        assertEquals(CompassSmoothing.BALANCED, config.smoothing)
    }

    /** Verifies a missing accuracy estimate remains a supported state. */
    @Test
    fun `accuracy accepts an absent estimated error`() {
        val accuracy = CompassAccuracy(
            level = CompassAccuracyLevel.UNKNOWN,
            estimatedErrorDegrees = null,
            calibrationStatus = CalibrationStatus.UNKNOWN,
        )

        assertNull(accuracy.estimatedErrorDegrees)
    }

    /** Verifies estimated heading errors must be finite, positive, and at most one turn. */
    @Test
    fun `accuracy rejects invalid estimated errors`() {
        listOf(0f, -1f, 360.001f, Float.NaN, Float.POSITIVE_INFINITY).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                CompassAccuracy(
                    level = CompassAccuracyLevel.LOW,
                    estimatedErrorDegrees = invalid,
                    calibrationStatus = CalibrationStatus.RECOMMENDED,
                )
            }
        }
    }

    /** Verifies calibration guidance cannot contradict the supplied accuracy level. */
    @Test
    fun `accuracy rejects contradictory calibration guidance`() {
        assertThrows(IllegalArgumentException::class.java) {
            CompassAccuracy(
                level = CompassAccuracyLevel.HIGH,
                estimatedErrorDegrees = null,
                calibrationStatus = CalibrationStatus.REQUIRED,
            )
        }
    }

    /** Verifies valid geographic boundary values and float-representable altitude are accepted. */
    @Test
    fun `geomagnetic position accepts geographic boundaries`() {
        val position = GeomagneticPosition(
            latitudeDegrees = -90.0,
            longitudeDegrees = 180.0,
            altitudeMeters = 0.0,
            timestampEpochMillis = 0L,
        )

        assertEquals(-90.0, position.latitudeDegrees, 0.0)
        assertEquals(180.0, position.longitudeDegrees, 0.0)
    }

    /** Verifies latitude, longitude, altitude, and time invariants reject malformed positions. */
    @Test
    fun `geomagnetic position rejects invalid values`() {
        assertThrows(IllegalArgumentException::class.java) {
            GeomagneticPosition(90.0001, 0.0, 0.0, 0L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            GeomagneticPosition(0.0, -180.0001, 0.0, 0L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            GeomagneticPosition(0.0, 0.0, Double.POSITIVE_INFINITY, 0L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            GeomagneticPosition(0.0, 0.0, 0.0, -1L)
        }
    }
}
