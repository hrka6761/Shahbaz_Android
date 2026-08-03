/** Tests altitude validation and the pure flight-setup state transitions. */
package ir.hrka.shahbaz.feature.map

import ir.hrka.shahbaz.core.model.GeoCoordinate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for takeoff-altitude parsing and [MapUiState] workflow helpers. */
class MapUiStateTest {
    /** Verifies integer, decimal-point, decimal-comma, and trimmed positive inputs are accepted. */
    @Test
    fun `takeoff altitude accepts positive decimal meters`() {
        assertEquals(120.0, parseTakeoffAltitudeMeters("120")!!, 0.0)
        assertEquals(12.5, parseTakeoffAltitudeMeters("12.5")!!, 0.0)
        assertEquals(12.5, parseTakeoffAltitudeMeters("12,5")!!, 0.0)
        assertEquals(0.5, parseTakeoffAltitudeMeters("  .5 ")!!, 0.0)
    }

    /** Verifies non-positive, non-finite, exponent, and malformed inputs are rejected. */
    @Test
    fun `takeoff altitude rejects invalid input`() {
        listOf(
            "",
            "   ",
            "0",
            "-1",
            "NaN",
            "Infinity",
            "1e3",
            ".",
            "1.2.3",
            "1,2,3",
        ).forEach { input ->
            assertNull("Expected '$input' to be rejected", parseTakeoffAltitudeMeters(input))
        }
    }

    /** Verifies the altitude step cannot be opened until a destination exists. */
    @Test
    fun `altitude step requires a destination`() {
        val state = MapUiState()

        assertSame(state, state.advanceToTakeoffAltitude())
    }

    /** Verifies advancing and returning preserve the selected route and altitude draft. */
    @Test
    fun `previous preserves destination and altitude draft`() {
        val destination = PlacePoint(
            coordinate = GeoCoordinate(35.7, 51.4),
            name = "Destination",
        )
        val altitudeState = MapUiState(destination = destination)
            .advanceToTakeoffAltitude()
            .updateTakeoffAltitude("42.5")
        val destinationState = altitudeState.returnToDestinationSelection()

        assertEquals(FlightSetupStep.TAKEOFF_ALTITUDE, altitudeState.flightSetupStep)
        assertEquals(FlightSetupStep.DESTINATION, destinationState.flightSetupStep)
        assertEquals(destination, destinationState.destination)
        assertEquals("42.5", destinationState.takeoffAltitudeInput)
    }

    /** Verifies only a valid altitude on the altitude step can be confirmed. */
    @Test
    fun `confirmation requires the altitude step and valid input`() {
        val destination = PlacePoint(
            coordinate = GeoCoordinate(35.7, 51.4),
            name = "Destination",
        )
        val destinationStep = MapUiState(destination = destination).updateTakeoffAltitude("50")
        val invalidAltitudeStep = destinationStep
            .advanceToTakeoffAltitude()
            .updateTakeoffAltitude("0")
        val validAltitudeStep = invalidAltitudeStep.updateTakeoffAltitude("50")

        assertFalse(destinationStep.confirmTakeoffAltitude().isTakeoffAltitudeConfirmed)
        assertFalse(invalidAltitudeStep.confirmTakeoffAltitude().isTakeoffAltitudeConfirmed)
        assertTrue(validAltitudeStep.confirmTakeoffAltitude().isTakeoffAltitudeConfirmed)
    }

    /** Verifies editing a confirmed altitude clears confirmation and caps retained input length. */
    @Test
    fun `editing altitude invalidates confirmation and caps input`() {
        val destination = PlacePoint(
            coordinate = GeoCoordinate(35.7, 51.4),
            name = "Destination",
        )
        val confirmedState = MapUiState(destination = destination)
            .advanceToTakeoffAltitude()
            .updateTakeoffAltitude("50")
            .confirmTakeoffAltitude()
        val editedState = confirmedState.updateTakeoffAltitude("1".repeat(40))

        assertFalse(editedState.isTakeoffAltitudeConfirmed)
        assertEquals(MAX_TAKEOFF_ALTITUDE_INPUT_LENGTH, editedState.takeoffAltitudeInput.length)
    }
}
