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

/** Unit tests for cruise-altitude parsing and [MapUiState] workflow helpers. */
class MapUiStateTest {
    /**
     * Runs the elapsed sample freshness expires missing and silent sources at the boundary operation.
     */
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

    /**
     * Runs the compass timeout distinguishes no response from a stale prior reading operation.
     */
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
    fun `cruise altitude accepts positive decimal meters`() {
        assertEquals(120.0, parseCruiseAltitudeMeters("120")!!, 0.0)
        assertEquals(12.5, parseCruiseAltitudeMeters("12.5")!!, 0.0)
        assertEquals(12.5, parseCruiseAltitudeMeters("12,5")!!, 0.0)
        assertEquals(0.5, parseCruiseAltitudeMeters("  .5 ")!!, 0.0)
    }

    /** Verifies non-positive, non-finite, exponent, and malformed inputs are rejected. */
    @Test
    fun `cruise altitude rejects invalid input`() {
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
            assertNull("Expected '$input' to be rejected", parseCruiseAltitudeMeters(input))
        }
    }

    /** Verifies level, uphill, and downhill destination-ground elevations are accepted. */
    @Test
    fun `destination ground altitude accepts signed decimal meters`() {
        assertEquals(0.0, parseDestinationGroundAltitudeMeters("0")!!, 0.0)
        assertEquals(-12.5, parseDestinationGroundAltitudeMeters("-12.5")!!, 0.0)
        assertEquals(12.5, parseDestinationGroundAltitudeMeters("+12,5")!!, 0.0)
        assertEquals(-0.5, parseDestinationGroundAltitudeMeters("  -,5 ")!!, 0.0)
    }

    /** Verifies empty, non-finite, exponent, and malformed ground elevations are rejected. */
    @Test
    fun `destination ground altitude rejects invalid input`() {
        listOf(
            "",
            "   ",
            "NaN",
            "Infinity",
            "1e3",
            "+",
            "-",
            ".",
            "1.2.3",
            "1,2,3",
        ).forEach { input ->
            assertNull(
                "Expected '$input' to be rejected",
                parseDestinationGroundAltitudeMeters(input),
            )
        }
    }

    /** Verifies the altitude step cannot be opened until a destination exists. */
    @Test
    fun `altitude step requires a destination`() {
        val state = MapUiState()

        assertSame(state, state.advanceToCruiseAltitude())
    }

    /** Verifies advancing and returning preserve the route and both altitude drafts. */
    @Test
    fun `previous preserves destination and altitude draft`() {
        val destination = PlacePoint(
            coordinate = GeoCoordinate(35.7, 51.4),
            name = "Destination",
        )
        val altitudeState = MapUiState(destination = destination)
            .advanceToCruiseAltitude()
            .updateCruiseAltitude("42.5")
            .updateDestinationGroundAltitude("-8")
        val destinationState = altitudeState.returnToDestinationSelection()

        assertEquals(FlightSetupStep.CRUISE_ALTITUDE, altitudeState.flightSetupStep)
        assertEquals(FlightSetupStep.DESTINATION, destinationState.flightSetupStep)
        assertEquals(destination, destinationState.destination)
        assertEquals("42.5", destinationState.cruiseAltitudeInput)
        assertEquals("-8", destinationState.destinationGroundAltitudeInput)
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
        ).updateCruiseAltitude("50")
        val invalidAltitudeStep = destinationStep
            .advanceToCruiseAltitude()
            .updateCruiseAltitude("0")
        val missingGroundAltitudeStep = invalidAltitudeStep.updateCruiseAltitude("50")
        val invalidGroundAltitudeStep = missingGroundAltitudeStep
            .updateDestinationGroundAltitude("50")
        val validAltitudeStep = invalidGroundAltitudeStep
            .updateDestinationGroundAltitude("12")
        val missingOriginStep = MapUiState(destination = destination)
            .advanceToCruiseAltitude()
            .updateCruiseAltitude("50")

        assertEquals(
            FlightPlanConfirmationBlocker.NOT_ALTITUDE_STEP,
            destinationStep.flightPlanConfirmationBlocker,
        )
        assertEquals(
            FlightPlanConfirmationBlocker.INVALID_ALTITUDE,
            invalidAltitudeStep.flightPlanConfirmationBlocker,
        )
        assertEquals(
            FlightPlanConfirmationBlocker.LIVE_ORIGIN_UNAVAILABLE,
            missingOriginStep.flightPlanConfirmationBlocker,
        )
        assertEquals(
            FlightPlanConfirmationBlocker.INVALID_DESTINATION_GROUND_ALTITUDE,
            missingGroundAltitudeStep.flightPlanConfirmationBlocker,
        )
        assertEquals(
            FlightPlanConfirmationBlocker.INVALID_DESTINATION_GROUND_ALTITUDE,
            invalidGroundAltitudeStep.flightPlanConfirmationBlocker,
        )
        assertFalse(destinationStep.confirmCruiseAltitude().isCruiseAltitudeConfirmed)
        assertFalse(invalidAltitudeStep.confirmCruiseAltitude().isCruiseAltitudeConfirmed)
        assertFalse(missingOriginStep.confirmCruiseAltitude().isCruiseAltitudeConfirmed)
        assertFalse(missingGroundAltitudeStep.confirmCruiseAltitude().isCruiseAltitudeConfirmed)
        assertFalse(invalidGroundAltitudeStep.confirmCruiseAltitude().isCruiseAltitudeConfirmed)
        assertTrue(validAltitudeStep.confirmCruiseAltitude().isCruiseAltitudeConfirmed)
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
        ).advanceToCruiseAltitude()
            .updateCruiseAltitude("50")
            .updateDestinationGroundAltitude("0")

        assertTrue(readyState.canConfirmCruiseAltitude)
        assertNull(readyState.flightPlanConfirmationBlocker)

        val missingOrigin = readyState.copy(
            locationStatus = LocationStatus.LOCATING,
            origin = null,
        )
        assertEquals(
            FlightPlanConfirmationBlocker.LIVE_ORIGIN_UNAVAILABLE,
            missingOrigin.flightPlanConfirmationBlocker,
        )
        assertFalse(missingOrigin.canConfirmCruiseAltitude)
        assertSame(missingOrigin, missingOrigin.confirmCruiseAltitude())

        val retainedButNotLiveOrigin = readyState.copy(locationStatus = LocationStatus.UNAVAILABLE)
        assertEquals(
            FlightPlanConfirmationBlocker.LIVE_ORIGIN_UNAVAILABLE,
            retainedButNotLiveOrigin.flightPlanConfirmationBlocker,
        )
        assertFalse(retainedButNotLiveOrigin.canConfirmCruiseAltitude)

        val missingDestination = readyState.copy(destination = null)
        assertEquals(
            FlightPlanConfirmationBlocker.DESTINATION_UNAVAILABLE,
            missingDestination.flightPlanConfirmationBlocker,
        )
        assertFalse(missingDestination.canConfirmCruiseAltitude)

        val restored = missingOrigin.copy(
            locationStatus = LocationStatus.READY,
            origin = origin,
        )
        assertTrue(restored.confirmCruiseAltitude().isCruiseAltitudeConfirmed)
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
            .advanceToCruiseAltitude()
            .updateCruiseAltitude("50")
            .updateDestinationGroundAltitude("0")
            .confirmCruiseAltitude()
        val editedState = confirmedState.updateCruiseAltitude("1".repeat(40))
        val editedGroundState = confirmedState.updateDestinationGroundAltitude("-".repeat(40))

        assertFalse(editedState.isCruiseAltitudeConfirmed)
        assertEquals(MAX_CRUISE_ALTITUDE_INPUT_LENGTH, editedState.cruiseAltitudeInput.length)
        assertFalse(editedGroundState.isCruiseAltitudeConfirmed)
        assertEquals(
            MAX_DESTINATION_GROUND_ALTITUDE_INPUT_LENGTH,
            editedGroundState.destinationGroundAltitudeInput.length,
        )
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
            .advanceToCruiseAltitude()
            .updateCruiseAltitude("42.5")
            .updateDestinationGroundAltitude("-3.25")
            .confirmCruiseAltitude()
        val movedOrigin = PlacePoint(
            coordinate = GeoCoordinate(35.61, 51.31),
            name = "Current location",
        )
        val movedState = confirmedState.copy(origin = movedOrigin)
        val plan = movedState.confirmedFlightPlan!!

        assertEquals(takeoffOrigin.coordinate, plan.origin)
        assertEquals(destination.coordinate, plan.destination)
        assertEquals(42.5, plan.targetAltitudeAboveOriginMeters, 0.0)
        assertEquals(-3.25, plan.destinationGroundAltitudeAboveOriginMeters, 0.0)
        assertEquals(movedOrigin, movedState.origin)
    }

    /** Verifies Back from dashboard restores the same completed altitude-selection draft. */
    @Test
    fun `dashboard return preserves location altitude and altitude selection step`() {
        val origin = PlacePoint(GeoCoordinate(35.6, 51.3), "Origin")
        val destination = PlacePoint(GeoCoordinate(35.7, 51.4), "Destination")
        val confirmedState = MapUiState(
            locationStatus = LocationStatus.READY,
            origin = origin,
            destination = destination,
        )
            .advanceToCruiseAltitude()
            .updateCruiseAltitude("50")
            .updateDestinationGroundAltitude("5")
            .confirmCruiseAltitude()

        val changedRoute = confirmedState.selectDestination(
            PlacePoint(GeoCoordinate(35.8, 51.5), "New destination")
        )
        val returnedFromDashboard = confirmedState.clearConfirmedFlightPlan()

        assertNull(changedRoute.confirmedFlightPlan)
        assertNull(returnedFromDashboard.confirmedFlightPlan)
        assertEquals(FlightSetupStep.CRUISE_ALTITUDE, returnedFromDashboard.flightSetupStep)
        assertEquals(origin, returnedFromDashboard.origin)
        assertEquals(destination, returnedFromDashboard.destination)
        assertEquals("50", returnedFromDashboard.cruiseAltitudeInput)
        assertEquals("5", returnedFromDashboard.destinationGroundAltitudeInput)
        assertEquals(50.0, returnedFromDashboard.cruiseAltitudeMeters!!, 0.0)
        assertEquals(
            5.0,
            returnedFromDashboard.destinationGroundAltitudeAboveOriginMeters!!,
            0.0,
        )
        assertTrue(returnedFromDashboard.canConfirmCruiseAltitude)
    }

    /** Verifies a destination change clears only its elevation while a full clear resets both. */
    @Test
    fun `destination changes reset destination bound elevation safely`() {
        val originalDestination = PlacePoint(GeoCoordinate(35.7, 51.4), "Original")
        val replacementDestination = PlacePoint(GeoCoordinate(35.8, 51.5), "Replacement")
        val draftState = MapUiState(destination = originalDestination)
            .advanceToCruiseAltitude()
            .updateCruiseAltitude("50")
            .updateDestinationGroundAltitude("12")
        val replacementState = draftState
            .returnToDestinationSelection()
            .selectDestination(replacementDestination)
        val renamedSameDestination = draftState.selectDestination(
            originalDestination.copy(name = "Resolved original", hasResolvedName = true)
        )
        val clearedState = replacementState
            .updateDestinationGroundAltitude("6")
            .clearSelectedDestination()

        assertEquals("50", replacementState.cruiseAltitudeInput)
        assertEquals("", replacementState.destinationGroundAltitudeInput)
        assertEquals("12", renamedSameDestination.destinationGroundAltitudeInput)
        assertNull(replacementState.confirmedFlightPlan)
        assertNull(clearedState.destination)
        assertEquals(FlightSetupStep.DESTINATION, clearedState.flightSetupStep)
        assertEquals("", clearedState.cruiseAltitudeInput)
        assertEquals("", clearedState.destinationGroundAltitudeInput)
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

    /** Flight consumers receive the original monotonic GPS time and fail closed on bad metadata. */
    @Test
    fun `phone location fix validates accuracy and monotonic acquisition time`() {
        val coordinate = GeoCoordinate(35.6892, 51.3890)
        val fix = PhoneLocationFix(
            coordinate = coordinate,
            horizontalAccuracyMeters = 2.5,
            observedAtElapsedRealtimeNanos = 123L,
        )

        assertEquals(coordinate, fix.coordinate)
        assertEquals(2.5, requireNotNull(fix.horizontalAccuracyMeters), 0.0)
        assertThrows(IllegalArgumentException::class.java) {
            fix.copy(horizontalAccuracyMeters = Double.NaN)
        }
        assertThrows(IllegalArgumentException::class.java) {
            fix.copy(horizontalAccuracyMeters = -1.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            fix.copy(observedAtElapsedRealtimeNanos = 0L)
        }
    }
}
