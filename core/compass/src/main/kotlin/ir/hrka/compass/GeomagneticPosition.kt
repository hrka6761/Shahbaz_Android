/** Defines validated caller-supplied geographic input for true-north calculation. */
package ir.hrka.compass

/**
 * Position and time used by Android's geomagnetic model to calculate magnetic declination.
 *
 * Supplying this value does not grant or request location access. The host remains responsible for
 * obtaining any coordinates and deciding whether they are sufficiently current.
 *
 * @property latitudeDegrees latitude in decimal degrees within `-90.0..90.0`.
 * @property longitudeDegrees longitude in decimal degrees within `-180.0..180.0`.
 * @property altitudeMeters WGS-84 ellipsoidal altitude in meters.
 * @property timestampEpochMillis UTC instant used by the geomagnetic model.
 * @throws IllegalArgumentException when a numeric value is non-finite or outside its valid range.
 */
data class GeomagneticPosition(
    /**
     * Exposes the latitudeDegrees value.
     */
    val latitudeDegrees: Double,
    /**
     * Exposes the longitudeDegrees value.
     */
    val longitudeDegrees: Double,
    /**
     * Exposes the altitudeMeters value.
     */
    val altitudeMeters: Double = 0.0,
    /**
     * Exposes the timestampEpochMillis value.
     */
    val timestampEpochMillis: Long,
) {
    /** Validates that every value can be passed safely to Android's geomagnetic model. */
    init {
        require(latitudeDegrees.isFinite() && latitudeDegrees in -90.0..90.0) {
            "Latitude must be finite and within -90..90 degrees"
        }
        require(longitudeDegrees.isFinite() && longitudeDegrees in -180.0..180.0) {
            "Longitude must be finite and within -180..180 degrees"
        }
        require(
            altitudeMeters.isFinite() &&
                altitudeMeters in -Float.MAX_VALUE.toDouble()..Float.MAX_VALUE.toDouble()
        ) {
            "Altitude must be finite and representable as a Float"
        }
        require(timestampEpochMillis >= 0L) {
            "Timestamp cannot be negative"
        }
    }
}
