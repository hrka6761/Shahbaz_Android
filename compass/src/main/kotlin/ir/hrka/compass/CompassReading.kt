/** Defines normalized compass readings, directions, north references, and angular deviations. */
package ir.hrka.compass

import ir.hrka.compass.internal.directionDeviation
import ir.hrka.compass.internal.nearestCompassDirection
import ir.hrka.compass.internal.normalizeDegrees
import kotlin.math.abs

/**
 * Complete logical orientation derived from one accepted sensor sample.
 *
 * Azimuth follows the top edge of the display associated with the [Compass] creation context,
 * increases clockwise, and is normalized to `0f <= value < 360f`. Pitch and roll use Android's
 * display-corrected orientation conventions. True-north and declination values are absent until
 * [Compass.setGeomagneticPosition] receives a position.
 *
 * @property magneticAzimuthDegrees clockwise azimuth relative to magnetic north.
 * @property trueAzimuthDegrees clockwise azimuth relative to true north, when configured.
 * @property declinationDegrees signed true-north correction added to magnetic azimuth.
 * @property pitchDegrees display-corrected rotation around the device's horizontal axis.
 * @property rollDegrees display-corrected rotation around the device's vertical axis.
 * @property accuracy sensor accuracy and calibration guidance for this reading.
 * @property sensorSource sensor strategy that produced this reading.
 * @property timestampNanos monotonic Android sensor timestamp in nanoseconds since boot.
 * @throws IllegalArgumentException when an angle, timestamp, or true-north relationship violates
 * the documented normalized reading contract.
 */
data class CompassReading(
    val magneticAzimuthDegrees: Float,
    val trueAzimuthDegrees: Float?,
    val declinationDegrees: Float?,
    val pitchDegrees: Float,
    val rollDegrees: Float,
    val accuracy: CompassAccuracy,
    val sensorSource: CompassSensorSource,
    val timestampNanos: Long,
) {
    /** Validates the documented finite and normalized public-reading contract. */
    init {
        require(magneticAzimuthDegrees.isNormalizedAzimuth()) {
            "Magnetic azimuth must be within 0..<360 degrees"
        }
        require(trueAzimuthDegrees == null || trueAzimuthDegrees.isNormalizedAzimuth()) {
            "True azimuth must be within 0..<360 degrees when present"
        }
        require(declinationDegrees == null || declinationDegrees.isFinite()) {
            "Declination must be finite when present"
        }
        require(pitchDegrees.isFinite()) { "Pitch must be finite" }
        require(rollDegrees.isFinite()) { "Roll must be finite" }
        require(timestampNanos >= 0L) { "Sensor timestamp cannot be negative" }
        require((trueAzimuthDegrees == null) == (declinationDegrees == null)) {
            "True azimuth and declination must either both be present or both be absent"
        }
        require(declinationDegrees == null || declinationDegrees in -180f..180f) {
            "Declination must be within -180..180 degrees when present"
        }
        if (trueAzimuthDegrees != null && declinationDegrees != null) {
            val expectedTrueAzimuth = normalizeDegrees(
                magneticAzimuthDegrees + declinationDegrees
            )
            val difference = directionDeviation(
                azimuthDegrees = trueAzimuthDegrees,
                bearingDegrees = expectedTrueAzimuth,
            ).absoluteDegrees
            require(difference <= TRUE_AZIMUTH_CONSISTENCY_TOLERANCE) {
                "True azimuth must equal magnetic azimuth plus declination"
            }
        }
    }

    /**
     * Selects the azimuth associated with [reference].
     *
     * @param reference magnetic or true north.
     * @return normalized azimuth, or `null` when true north was requested without a position.
     */
    @JvmOverloads
    fun azimuth(reference: NorthReference = NorthReference.MAGNETIC): Float? = when (reference) {
        NorthReference.MAGNETIC -> magneticAzimuthDegrees
        NorthReference.TRUE -> trueAzimuthDegrees
    }

    /**
     * Finds the nearest of the eight cardinal and intercardinal directions.
     *
     * Exact half-sector boundaries belong to the clockwise sector; for example, `22.5` degrees is
     * north-east.
     *
     * @param reference magnetic or true north used for the calculation.
     * @return nearest semantic direction, or `null` when the selected north reference is absent.
     */
    @JvmOverloads
    fun nearestDirection(
        reference: NorthReference = NorthReference.MAGNETIC,
    ): CompassDirection? = azimuth(reference)?.let(::nearestCompassDirection)

    /**
     * Calculates the shortest angular difference from [direction] to this reading.
     *
     * @param direction semantic reference direction whose bearing is compared.
     * @param reference magnetic or true north used for the reading azimuth.
     * @return signed and absolute deviation, or `null` when the selected north reference is absent.
     */
    @JvmOverloads
    fun deviationFrom(
        direction: CompassDirection,
        reference: NorthReference = NorthReference.MAGNETIC,
    ): DirectionDeviation? = deviationFrom(direction.bearingDegrees, reference)

    /**
     * Calculates the shortest angular difference from an arbitrary reference bearing.
     *
     * @param bearingDegrees finite bearing measured clockwise from the selected north reference;
     * values outside one turn are accepted and normalized.
     * @param reference magnetic or true north used for the reading azimuth.
     * @return signed and absolute deviation, or `null` when the selected north reference is absent.
     * @throws IllegalArgumentException when [bearingDegrees] is not finite.
     */
    @JvmOverloads
    fun deviationFrom(
        bearingDegrees: Float,
        reference: NorthReference = NorthReference.MAGNETIC,
    ): DirectionDeviation? = azimuth(reference)?.let { azimuth ->
        directionDeviation(azimuth, bearingDegrees)
    }
}

/** The eight semantic cardinal and intercardinal directions. */
enum class CompassDirection(
    /** Canonical clockwise bearing of this direction relative to north. */
    val bearingDegrees: Float,
) {
    /** North, centered on zero degrees. */
    NORTH(0f),

    /** North-east, centered on 45 degrees. */
    NORTH_EAST(45f),

    /** East, centered on 90 degrees. */
    EAST(90f),

    /** South-east, centered on 135 degrees. */
    SOUTH_EAST(135f),

    /** South, centered on 180 degrees. */
    SOUTH(180f),

    /** South-west, centered on 225 degrees. */
    SOUTH_WEST(225f),

    /** West, centered on 270 degrees. */
    WEST(270f),

    /** North-west, centered on 315 degrees. */
    NORTH_WEST(315f),
}

/** North references supported by [CompassReading] helper calculations. */
enum class NorthReference {
    /** Magnetic north reported by magnetometer-backed device orientation. */
    MAGNETIC,

    /** Geographic true north derived from caller-supplied geomagnetic position and time. */
    TRUE,
}

/**
 * Shortest rotation from a reference direction to a measured compass azimuth.
 *
 * @property signedDegrees deviation in `-180f <= value < 180f`; positive is clockwise and negative
 * is counter-clockwise.
 * @property absoluteDegrees unsigned magnitude in `0f..180f`.
 * @throws IllegalArgumentException when either value is non-finite, outside its normalized range,
 * or inconsistent with the other value.
 */
data class DirectionDeviation(
    val signedDegrees: Float,
    val absoluteDegrees: Float,
) {
    /** Validates the public signed and absolute range contract. */
    init {
        require(signedDegrees.isFinite() && signedDegrees >= -180f && signedDegrees < 180f) {
            "Signed deviation must be within -180..<180 degrees"
        }
        require(absoluteDegrees.isFinite() && absoluteDegrees in 0f..180f) {
            "Absolute deviation must be within 0..180 degrees"
        }
        require(abs(abs(signedDegrees) - absoluteDegrees) <= DEVIATION_CONSISTENCY_TOLERANCE) {
            "Absolute deviation must match the magnitude of signed deviation"
        }
    }
}

/** Returns whether this floating-point value satisfies the normalized azimuth contract. */
private fun Float.isNormalizedAzimuth(): Boolean = isFinite() && this >= 0f && this < 360f

/** Maximum floating-point difference accepted between signed and absolute deviation magnitude. */
private const val DEVIATION_CONSISTENCY_TOLERANCE = 0.0001f

/** Maximum circular difference accepted for magnetic, declination, and true-azimuth consistency. */
private const val TRUE_AZIMUTH_CONSISTENCY_TOLERANCE = 0.001f
