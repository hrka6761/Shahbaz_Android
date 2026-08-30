/** Defines the immutable route and target altitude accepted before entering flight monitoring. */
package ir.hrka.shahbaz.core.model

/**
 * A validated snapshot of the setup values required to monitor one flight.
 *
 * [origin] is captured when setup is confirmed and therefore remains fixed even while a live
 * device or drone position continues to change. [targetAltitudeAboveOriginMeters] is the positive
 * height the drone should reach above the takeoff surface before continuing toward [destination].
 *
 * @property origin Fixed takeoff coordinate captured at confirmation time.
 * @property destination Fixed destination selected by the user.
 * @property targetAltitudeAboveOriginMeters Positive target height above the takeoff surface.
 * @throws IllegalArgumentException when [targetAltitudeAboveOriginMeters] is non-finite or is not
 * greater than zero.
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
) {
    /** Enforces a finite, positive altitude at the shared-model boundary. */
    init {
        require(
            targetAltitudeAboveOriginMeters.isFinite() &&
                targetAltitudeAboveOriginMeters > 0.0
        ) {
            "Target altitude above origin must be finite and greater than zero"
        }
    }
}
