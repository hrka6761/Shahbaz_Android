/**
 * Defines the dependency-free geographic coordinate model shared across Shahbaz modules.
 */
package ir.hrka.shahbaz.core.model

/**
 * A validated WGS-84 geographic coordinate expressed in decimal degrees.
 *
 * @property latitude latitude in the inclusive range `-90.0..90.0`.
 * @property longitude longitude in the inclusive range `-180.0..180.0`.
 * @throws IllegalArgumentException if either component is non-finite or outside its range.
 */
data class GeoCoordinate(
    val latitude: Double,
    val longitude: Double,
) {
    /** Validates both coordinate components when an instance is constructed. */
    init {
        require(latitude.isFinite()) { "Latitude must be finite" }
        require(latitude in -90.0..90.0) { "Latitude must be between -90 and 90 degrees" }
        require(longitude.isFinite()) { "Longitude must be finite" }
        require(longitude in -180.0..180.0) {
            "Longitude must be between -180 and 180 degrees"
        }
    }
}
