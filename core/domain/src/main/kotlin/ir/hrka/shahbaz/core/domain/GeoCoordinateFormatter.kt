/**
 * Parses user-entered coordinate pairs and formats validated coordinates for display.
 */
package ir.hrka.shahbaz.core.domain

import ir.hrka.shahbaz.core.model.GeoCoordinate
import java.util.Locale

/** Regular-expression fragment accepted for one finite decimal coordinate component. */
private const val DECIMAL_NUMBER_PATTERN =
    "[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?"

/** Complete pattern for a latitude/longitude pair and its supported separators. */
private val coordinatePairPattern = Regex(
    "^\\s*($DECIMAL_NUMBER_PATTERN)(?:\\s*[,;]\\s*|\\s+)" +
        "($DECIMAL_NUMBER_PATTERN)\\s*$",
)

/**
 * Parses a latitude/longitude pair separated by a comma, semicolon, or whitespace.
 *
 * @param input coordinate text whose first component is latitude and second is longitude.
 * @return a validated [GeoCoordinate], or `null` for malformed, non-finite, or out-of-range input.
 */
fun parseCoordinatePair(input: String): GeoCoordinate? {
    val match = coordinatePairPattern.matchEntire(input) ?: return null
    val latitude = match.groupValues[1].toDoubleOrNull() ?: return null
    val longitude = match.groupValues[2].toDoubleOrNull() ?: return null

    return runCatching { GeoCoordinate(latitude, longitude) }.getOrNull()
}

/**
 * Formats a coordinate as `latitude, longitude` with six decimal places and US separators.
 *
 * @param coordinate validated coordinate to format.
 * @return locale-stable coordinate text suitable for the map information panel.
 */
fun formatCoordinate(coordinate: GeoCoordinate): String = String.format(
    Locale.US,
    "%.6f, %.6f",
    coordinate.latitude.withoutRoundedNegativeZero(),
    coordinate.longitude.withoutRoundedNegativeZero(),
)

/**
 * Replaces values that round to negative zero at six decimal places with positive zero.
 *
 * @receiver coordinate component about to be formatted.
 * @return this value, except for the six-decimal negative-zero interval which returns `0.0`.
 */
private fun Double.withoutRoundedNegativeZero(): Double =
    if (this in -0.0000005..0.0000005) 0.0 else this
