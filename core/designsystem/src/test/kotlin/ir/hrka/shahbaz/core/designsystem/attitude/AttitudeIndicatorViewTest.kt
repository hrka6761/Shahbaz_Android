package ir.hrka.shahbaz.core.designsystem.attitude

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Documents the AttitudeIndicatorViewTest type and the role it plays in this module.
 */
class AttitudeIndicatorViewTest {
    /**
     * Runs the signed normalization stays within one half turn operation.
     */
    @Test
    fun `signed normalization stays within one half turn`() {
        assertEquals(-180f, normalizeSignedAttitudeAngle(180f), 0f)
        assertEquals(179f, normalizeSignedAttitudeAngle(-181f), 0f)
        assertEquals(-90f, normalizeSignedAttitudeAngle(630f), 0f)
    }

    /**
     * Runs the shortest roll delta crosses the wrap boundary operation.
     */
    @Test
    fun `shortest roll delta crosses the wrap boundary`() {
        assertEquals(2f, shortestAttitudeAngleDelta(179f, -179f), 0f)
        assertEquals(-2f, shortestAttitudeAngleDelta(-179f, 179f), 0f)
        assertEquals(20f, shortestAttitudeAngleDelta(170f, -170f), 0f)
    }

    /**
     * Runs the roll scale labels use aircraft signed angles operation.
     */
    @Test
    fun `roll scale labels use aircraft signed angles`() {
        assertEquals("0", rollScaleLabel(0))
        assertEquals("90", rollScaleLabel(90))
        assertEquals("180", rollScaleLabel(180))
        assertEquals("-150", rollScaleLabel(210))
        assertEquals("-90", rollScaleLabel(270))
        assertEquals("-30", rollScaleLabel(330))
    }
}
