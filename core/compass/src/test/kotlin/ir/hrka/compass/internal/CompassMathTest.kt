/** Exercises pure compass angle, smoothing, timing, sampling, and calibration calculations. */
package ir.hrka.compass.internal

import android.hardware.SensorManager
import android.view.Surface
import ir.hrka.compass.CalibrationStatus
import ir.hrka.compass.CompassAccuracyLevel
import ir.hrka.compass.CompassDirection
import ir.hrka.compass.CompassSmoothing
import ir.hrka.compass.CompassUpdateRate
import kotlin.math.PI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for deterministic helpers used by the Android sensor adapter. */
class CompassMathTest {
    /** Verifies negative, complete-turn, and multi-turn angles normalize into one turn. */
    @Test
    fun `degree normalization covers negative and multiple turns`() {
        assertEquals(359f, normalizeDegrees(-1f), 0f)
        assertEquals(0f, normalizeDegrees(0f), 0f)
        assertEquals(0f, normalizeDegrees(360f), 0f)
        assertEquals(1f, normalizeDegrees(721f), 0f)
    }

    /** Verifies non-finite normalized-angle inputs fail fast. */
    @Test
    fun `degree normalization rejects non-finite values`() {
        listOf(Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                normalizeDegrees(invalid)
            }
        }
    }

    /** Verifies signed normalization uses the documented half-open interval. */
    @Test
    fun `signed normalization uses negative one hundred eighty boundary`() {
        assertEquals(-180f, normalizeSignedDegrees(180f), 0f)
        assertEquals(-180f, normalizeSignedDegrees(-180f), 0f)
        assertEquals(179f, normalizeSignedDegrees(-181f), 0f)
        assertEquals(-179f, normalizeSignedDegrees(181f), 0f)
    }

    /** Verifies circular smoothing crosses north through zero in both directions. */
    @Test
    fun `circular smoothing crosses north without jumping south`() {
        assertEquals(0f, smoothAzimuthDegrees(359f, 1f, 0.5f), 0.0001f)
        assertEquals(0f, smoothAzimuthDegrees(1f, 359f, 0.5f), 0.0001f)
    }

    /** Verifies the first filter sample is normalized and independent of prior sessions. */
    @Test
    fun `first smoothing sample has no retained history`() {
        assertEquals(5f, smoothAzimuthDegrees(null, 365f, 0.1f), 0f)
    }

    /** Verifies invalid smoothing inputs and weights are rejected. */
    @Test
    fun `smoothing rejects invalid inputs`() {
        assertThrows(IllegalArgumentException::class.java) {
            smoothAzimuthDegrees(Float.NaN, 0f, 0.2f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            smoothAzimuthDegrees(0f, Float.NaN, 0.2f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            smoothAzimuthDegrees(0f, 1f, 1.1f)
        }
    }

    /** Verifies only Android's documented positive, at-most-one-turn error range is converted. */
    @Test
    fun `heading error conversion handles available and unavailable values`() {
        assertEquals(
            90f,
            estimatedHeadingErrorDegrees((PI / 2.0).toFloat()) ?: Float.NaN,
            0.0001f,
        )
        assertEquals(
            360f,
            estimatedHeadingErrorDegrees((2.0 * PI).toFloat()) ?: Float.NaN,
            0.0001f,
        )
        assertNull(estimatedHeadingErrorDegrees(null))
        assertNull(estimatedHeadingErrorDegrees(0f))
        assertNull(estimatedHeadingErrorDegrees(-1f))
        assertNull(estimatedHeadingErrorDegrees((2.0 * PI + 0.001).toFloat()))
        assertNull(estimatedHeadingErrorDegrees(Float.NaN))
    }

    /** Verifies every smoothing preset maps to a valid increasingly stable filter weight. */
    @Test
    fun `smoothing presets expose ordered filter weights`() {
        assertEquals(1f, CompassSmoothing.NONE.newestSampleWeight(), 0f)
        assertTrue(
            CompassSmoothing.LIGHT.newestSampleWeight() >
                CompassSmoothing.BALANCED.newestSampleWeight()
        )
        assertTrue(
            CompassSmoothing.BALANCED.newestSampleWeight() >
                CompassSmoothing.STRONG.newestSampleWeight()
        )
    }

    /** Verifies update presets remain below 200 Hz and enforce ordered delivery intervals. */
    @Test
    fun `update presets remain permission free and ordered`() {
        val lowPower = CompassUpdateRate.LOW_POWER.samplingPolicy()
        val normal = CompassUpdateRate.NORMAL.samplingPolicy()
        val responsive = CompassUpdateRate.RESPONSIVE.samplingPolicy()

        assertTrue(lowPower.samplingPeriodMicros >= 5_000)
        assertTrue(normal.samplingPeriodMicros >= 5_000)
        assertTrue(responsive.samplingPeriodMicros >= 5_000)
        assertTrue(lowPower.minimumCallbackIntervalNanos > normal.minimumCallbackIntervalNanos)
        assertTrue(normal.minimumCallbackIntervalNanos > responsive.minimumCallbackIntervalNanos)
    }

    /** Verifies callback timing accepts the first sample and the exact interval boundary. */
    @Test
    fun `timestamp dispatch accepts first and exact boundary`() {
        assertTrue(shouldDispatchTimestamp(0L, 1L, 100L))
        assertFalse(shouldDispatchTimestamp(1_000L, 1_099L, 100L))
        assertTrue(shouldDispatchTimestamp(1_000L, 1_100L, 100L))
    }

    /** Verifies invalid negative timestamp arguments fail fast. */
    @Test
    fun `timestamp dispatch rejects negative values`() {
        assertThrows(IllegalArgumentException::class.java) {
            shouldDispatchTimestamp(-1L, 0L, 0L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            shouldDispatchTimestamp(0L, -1L, 0L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            shouldDispatchTimestamp(0L, 0L, -1L)
        }
    }

    /** Verifies every normalized accuracy level maps to explicit calibration guidance. */
    @Test
    fun `accuracy levels map to calibration guidance`() {
        assertEquals(
            CalibrationStatus.UNKNOWN,
            calibrationStatusForAccuracy(CompassAccuracyLevel.UNKNOWN),
        )
        assertEquals(
            CalibrationStatus.REQUIRED,
            calibrationStatusForAccuracy(CompassAccuracyLevel.UNRELIABLE),
        )
        assertEquals(
            CalibrationStatus.RECOMMENDED,
            calibrationStatusForAccuracy(CompassAccuracyLevel.LOW),
        )
        assertEquals(
            CalibrationStatus.NOT_REQUIRED,
            calibrationStatusForAccuracy(CompassAccuracyLevel.MEDIUM),
        )
        assertEquals(
            CalibrationStatus.NOT_REQUIRED,
            calibrationStatusForAccuracy(CompassAccuracyLevel.HIGH),
        )
    }

    /** Verifies all four Android display rotations map to the expected sensor axes. */
    @Test
    fun `display rotations map to expected axes`() {
        assertEquals(
            DisplayAxes(SensorManager.AXIS_X, SensorManager.AXIS_Y),
            axesForDisplayRotation(Surface.ROTATION_0),
        )
        assertEquals(
            DisplayAxes(SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X),
            axesForDisplayRotation(Surface.ROTATION_90),
        )
        assertEquals(
            DisplayAxes(SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Y),
            axesForDisplayRotation(Surface.ROTATION_180),
        )
        assertEquals(
            DisplayAxes(SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X),
            axesForDisplayRotation(Surface.ROTATION_270),
        )
    }

    /** Verifies direction centers and boundaries use deterministic eight-point sectors. */
    @Test
    fun `direction calculation covers centers and boundary policy`() {
        assertEquals(CompassDirection.NORTH, nearestCompassDirection(0f))
        assertEquals(CompassDirection.NORTH, nearestCompassDirection(22.499f))
        assertEquals(CompassDirection.NORTH_EAST, nearestCompassDirection(22.5f))
        assertEquals(CompassDirection.SOUTH, nearestCompassDirection(180f))
        assertEquals(CompassDirection.NORTH, nearestCompassDirection(360f))
    }
}
