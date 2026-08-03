/** Contains the application module's host-side smoke test. */
package ir.hrka.shahbaz

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verifies that the application module's local JUnit source set is executable.
 */
class AppModuleSmokeTest {
    /** Exercises a deterministic assertion as a minimal host-side test-runner smoke check. */
    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }
}
