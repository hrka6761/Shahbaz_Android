package ir.hrka.shahbaz.core.designsystem.compass

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Documents the CompassViewTest type and the role it plays in this module.
 */
class CompassViewTest {
    /**
     * Runs the heading normalization uses one clockwise turn operation.
     */
    @Test
    fun `heading normalization uses one clockwise turn`() {
        assertEquals(359f, normalizeHeadingDegrees(-1f), 0f)
        assertEquals(0f, normalizeHeadingDegrees(360f), 0f)
        assertEquals(5f, normalizeHeadingDegrees(725f), 0f)
    }

    /**
     * Runs the shortest delta crosses north without a full rotation operation.
     */
    @Test
    fun `shortest delta crosses north without a full rotation`() {
        assertEquals(2f, shortestHeadingDelta(359f, 1f), 0f)
        assertEquals(-2f, shortestHeadingDelta(1f, 359f), 0f)
        assertEquals(20f, shortestHeadingDelta(350f, 10f), 0f)
    }
}
