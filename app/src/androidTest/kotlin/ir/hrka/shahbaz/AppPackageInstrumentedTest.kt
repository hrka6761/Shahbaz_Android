/** Contains the application module's on-device package-identity smoke test. */
package ir.hrka.shahbaz

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies that the installed test target exposes Shahbaz's application identifier.
 */
@RunWith(AndroidJUnit4::class)
class AppPackageInstrumentedTest {
    /** Confirms that Android instrumentation resolves the expected application package. */
    @Test
    fun useAppContext() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("ir.hrka.shahbaz", appContext.packageName)
    }
}
