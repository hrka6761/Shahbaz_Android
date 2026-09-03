/** Defines the immutable route and altitude profile accepted before entering flight monitoring. */
package ir.hrka.shahbaz.core.model

/**
 * A validated snapshot of the setup values required to monitor one flight.
 *
 * [origin] is captured when setup is confirmed and therefore remains fixed even while a live
 * device or drone position continues to change. [targetAltitudeAboveOriginMeters] is the positive
 * cruise height the drone should reach above the takeoff surface before continuing toward
 * [destination]. [destinationGroundAltitudeAboveOriginMeters] is the signed elevation of the
 * landing surface relative to that same takeoff surface, allowing the landing phase to target the
 * destination ground rather than assuming both endpoints are level.
 *
 * @property origin Fixed takeoff coordinate captured at confirmation time.
 * @property destination Fixed destination selected by the user.
 * @property targetAltitudeAboveOriginMeters Positive cruise height above the takeoff surface.
 * @property destinationGroundAltitudeAboveOriginMeters Signed elevation of the destination
 * landing surface relative to the takeoff surface. Zero means both surfaces are level.
 * @throws IllegalArgumentException when [targetAltitudeAboveOriginMeters] is non-finite or is not
 * greater than zero, or when [destinationGroundAltitudeAboveOriginMeters] is non-finite or is not
 * strictly below [targetAltitudeAboveOriginMeters].
 */
data class FlightPlan(
    /**
     * Exposes the origin value.
     */
    val origin: GeoCoordinate,
    /**
     * Exposes the destination value.
     */
    val destination: GeoCoordinate,
    /**
     * Exposes the targetAltitudeAboveOriginMeters value.
     */
    val targetAltitudeAboveOriginMeters: Double,
    /**
     * Exposes the destinationGroundAltitudeAboveOriginMeters value.
     */
    val destinationGroundAltitudeAboveOriginMeters: Double,
) {
    /** Enforces a finite, physically ordered altitude profile at the shared-model boundary. */
    init {
        require(
            targetAltitudeAboveOriginMeters.isFinite() &&
                targetAltitudeAboveOriginMeters > 0.0
        ) {
            "Target altitude above origin must be finite and greater than zero"
        }
        require(
            destinationGroundAltitudeAboveOriginMeters.isFinite() &&
                destinationGroundAltitudeAboveOriginMeters < targetAltitudeAboveOriginMeters
        ) {
            "Destination ground altitude above origin must be finite and below target altitude"
        }
    }
}
