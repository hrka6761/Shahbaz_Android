/** Tests altitude validation and the pure flight-setup state transitions. */
package ir.hrka.shahbaz.feature.map

import ir.hrka.compass.CompassSensorSource
import ir.hrka.shahbaz.core.model.GeoCoordinate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for takeoff-altitude parsing and [MapUiState] workflow helpers. */
class MapUiStateTest {
    @Test
    fun `elapsed sample freshness expires missing and silent sources at the boundary`() {
        assertTrue(
            isElapsedSampleStale(
                lastSampleElapsedRealtimeMillis = 0L,
                nowElapsedRealtimeMillis = 5_000L,
                staleAfterMillis = 2_500L,
            )
        )
        assertFalse(
            isElapsedSampleStale(
                lastSampleElapsedRealtimeMillis = 1_000L,
                nowElapsedRealtimeMillis = 3_499L,
                staleAfterMillis = 2_500L,
            )
        )
        assertTrue(
            isElapsedSampleStale(
                lastSampleElapsedRealtimeMillis = 1_000L,
                nowElapsedRealtimeMillis = 3_500L,
                staleAfterMillis = 2_500L,
            )
        )
    }

    @Test
    fun `compass timeout distinguishes no response from a stale prior reading`() {
        val source = CompassSensorSource.ROTATION_VECTOR

        assertEquals(
            CompassSensorStatus.NoResponse(source),
            compassTimeoutStatus(source, hasPriorReading = false),
        )
        assertEquals(
            CompassSensorStatus.Stale(source),
            compassTimeoutStatus(source, hasPriorReading = true),
        )
    }

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
        val origin = PlacePoint(
            coordinate = GeoCoordinate(35.6, 51.3),
            name = "Origin",
        )
        val destination = PlacePoint(
            coordinate = GeoCoordinate(35.7, 51.4),
            name = "Destination",
        )
        val destinationStep = MapUiState(
            locationStatus = LocationStatus.READY,
            origin = origin,
            destination = destination,
        ).updateTakeoffAltitude("50")
        val invalidAltitudeStep = destinationStep
            .advanceToTakeoffAltitude()
            .updateTakeoffAltitude("0")
        val validAltitudeStep = invalidAltitudeStep.updateTakeoffAltitude("50")
        val missingOriginStep = MapUiState(destination = destination)
            .advanceToTakeoffAltitude()
            .updateTakeoffAltitude("50")

        assertEquals(
            TakeoffConfirmationBlocker.NOT_ALTITUDE_STEP,
            destinationStep.takeoffConfirmationBlocker,
        )
        assertEquals(
            TakeoffConfirmationBlocker.INVALID_ALTITUDE,
            invalidAltitudeStep.takeoffConfirmationBlocker,
        )
        assertEquals(
            TakeoffConfirmationBlocker.LIVE_ORIGIN_UNAVAILABLE,
            missingOriginStep.takeoffConfirmationBlocker,
        )
        assertFalse(destinationStep.confirmTakeoffAltitude().isTakeoffAltitudeConfirmed)
        assertFalse(invalidAltitudeStep.confirmTakeoffAltitude().isTakeoffAltitudeConfirmed)
        assertFalse(missingOriginStep.confirmTakeoffAltitude().isTakeoffAltitudeConfirmed)
        assertTrue(validAltitudeStep.confirmTakeoffAltitude().isTakeoffAltitudeConfirmed)
    }

    /** Verifies a lost or non-live origin disables confirmation until a fresh fix returns. */
    @Test
    fun `altitude confirmation is fail closed while live origin is unavailable`() {
        val origin = PlacePoint(GeoCoordinate(35.6, 51.3), "Origin")
        val destination = PlacePoint(GeoCoordinate(35.7, 51.4), "Destination")
        val readyState = MapUiState(
            locationStatus = LocationStatus.READY,
            origin = origin,
            destination = destination,
        ).advanceToTakeoffAltitude().updateTakeoffAltitude("50")

        assertTrue(readyState.canConfirmTakeoffAltitude)
        assertNull(readyState.takeoffConfirmationBlocker)

        val missingOrigin = readyState.copy(
            locationStatus = LocationStatus.LOCATING,
            origin = null,
        )
        assertEquals(
            TakeoffConfirmationBlocker.LIVE_ORIGIN_UNAVAILABLE,
            missingOrigin.takeoffConfirmationBlocker,
        )
        assertFalse(missingOrigin.canConfirmTakeoffAltitude)
        assertSame(missingOrigin, missingOrigin.confirmTakeoffAltitude())

        val retainedButNotLiveOrigin = readyState.copy(locationStatus = LocationStatus.UNAVAILABLE)
        assertEquals(
            TakeoffConfirmationBlocker.LIVE_ORIGIN_UNAVAILABLE,
            retainedButNotLiveOrigin.takeoffConfirmationBlocker,
        )
        assertFalse(retainedButNotLiveOrigin.canConfirmTakeoffAltitude)

        val missingDestination = readyState.copy(destination = null)
        assertEquals(
            TakeoffConfirmationBlocker.DESTINATION_UNAVAILABLE,
            missingDestination.takeoffConfirmationBlocker,
        )
        assertFalse(missingDestination.canConfirmTakeoffAltitude)

        val restored = missingOrigin.copy(
            locationStatus = LocationStatus.READY,
            origin = origin,
        )
        assertTrue(restored.confirmTakeoffAltitude().isTakeoffAltitudeConfirmed)
    }

    /** Verifies editing a confirmed altitude clears confirmation and caps retained input length. */
    @Test
    fun `editing altitude invalidates confirmation and caps input`() {
        val origin = PlacePoint(
            coordinate = GeoCoordinate(35.6, 51.3),
            name = "Origin",
        )
        val destination = PlacePoint(
            coordinate = GeoCoordinate(35.7, 51.4),
            name = "Destination",
        )
        val confirmedState = MapUiState(
            locationStatus = LocationStatus.READY,
            origin = origin,
            destination = destination,
        )
            .advanceToTakeoffAltitude()
            .updateTakeoffAltitude("50")
            .confirmTakeoffAltitude()
        val editedState = confirmedState.updateTakeoffAltitude("1".repeat(40))

        assertFalse(editedState.isTakeoffAltitudeConfirmed)
        assertEquals(MAX_TAKEOFF_ALTITUDE_INPUT_LENGTH, editedState.takeoffAltitudeInput.length)
    }

    /** Verifies confirmation snapshots the route while the live phone origin remains independent. */
    @Test
    fun `confirmation snapshots fixed plan independently from live origin`() {
        val takeoffOrigin = PlacePoint(
            coordinate = GeoCoordinate(35.6, 51.3),
            name = "Origin",
        )
        val destination = PlacePoint(
            coordinate = GeoCoordinate(35.7, 51.4),
            name = "Destination",
        )
        val confirmedState = MapUiState(
            locationStatus = LocationStatus.READY,
            origin = takeoffOrigin,
            destination = destination,
        )
            .advanceToTakeoffAltitude()
            .updateTakeoffAltitude("42.5")
            .confirmTakeoffAltitude()
        val movedOrigin = PlacePoint(
            coordinate = GeoCoordinate(35.61, 51.31),
            name = "Current location",
        )
        val movedState = confirmedState.copy(origin = movedOrigin)
        val plan = movedState.confirmedFlightPlan!!

        assertEquals(takeoffOrigin.coordinate, plan.origin)
        assertEquals(destination.coordinate, plan.destination)
        assertEquals(42.5, plan.targetAltitudeAboveOriginMeters, 0.0)
        assertEquals(movedOrigin, movedState.origin)
    }

    /** Verifies route replacement and explicit dashboard return invalidate confirmation. */
    @Test
    fun `route changes and dashboard return clear confirmed plan`() {
        val origin = PlacePoint(GeoCoordinate(35.6, 51.3), "Origin")
        val destination = PlacePoint(GeoCoordinate(35.7, 51.4), "Destination")
        val confirmedState = MapUiState(
            locationStatus = LocationStatus.READY,
            origin = origin,
            destination = destination,
        )
            .advanceToTakeoffAltitude()
            .updateTakeoffAltitude("50")
            .confirmTakeoffAltitude()

        val changedRoute = confirmedState.selectDestination(
            PlacePoint(GeoCoordinate(35.8, 51.5), "New destination")
        )
        val returnedFromDashboard = confirmedState.clearConfirmedFlightPlan()

        assertNull(changedRoute.confirmedFlightPlan)
        assertNull(returnedFromDashboard.confirmedFlightPlan)
        assertEquals(destination, returnedFromDashboard.destination)
        assertEquals("50", returnedFromDashboard.takeoffAltitudeInput)
    }

    /** Verifies malformed speed samples cannot masquerade as available dashboard data. */
    @Test
    fun `phone speed reading rejects invalid values`() {
        listOf(Float.NaN, Float.NEGATIVE_INFINITY, -1f).forEach { speed ->
            assertThrows(IllegalArgumentException::class.java) {
                PhoneSpeedReading(
                    metersPerSecond = speed,
                    accuracyMetersPerSecond = null,
                    timestampEpochMillis = 1L,
                )
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            PhoneSpeedReading(
                metersPerSecond = 1f,
                accuracyMetersPerSecond = -1f,
                timestampEpochMillis = 1L,
            )
        }
    }
}
