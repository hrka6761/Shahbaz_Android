/**
 * Provides deterministic geodesic calculations, midpoint logic, and display formatting for the
 * map feature without depending on Android APIs.
 */
package ir.hrka.shahbaz.core.domain

import ir.hrka.shahbaz.core.model.GeoCoordinate
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** IUGG mean Earth radius used by the spherical fallback calculation. */
private const val EARTH_MEAN_RADIUS_METERS = 6_371_008.8

/** Minimum vector magnitude treated as a defined non-antipodal midpoint. */
private const val ANTIPODAL_EPSILON = 1e-12

/** WGS-84 equatorial radius, also known as the ellipsoid semi-major axis. */
private const val WGS84_SEMI_MAJOR_AXIS_METERS = 6_378_137.0

/** WGS-84 ellipsoid flattening ratio. */
private const val WGS84_FLATTENING = 1.0 / 298.257223563

/** Maximum lambda change accepted as convergence by Vincenty's inverse solution. */
private const val VINCENTY_CONVERGENCE_RADIANS = 1e-12

/** Maximum number of iterations attempted by Vincenty's inverse solution. */
private const val VINCENTY_MAX_ITERATIONS = 200

/** WGS-84 polar radius derived from the semi-major axis and flattening ratio. */
private val WGS84_SEMI_MINOR_AXIS_METERS =
    WGS84_SEMI_MAJOR_AXIS_METERS * (1.0 - WGS84_FLATTENING)

/**
 * Calculates the great-circle distance between two points using the Haversine formula.
 *
 * @param start first validated coordinate.
 * @param end second validated coordinate.
 * @return shortest spherical surface distance in meters.
 */
fun haversineDistanceMeters(start: GeoCoordinate, end: GeoCoordinate): Double {
    val startLatitude = start.latitude.toRadians()
    val endLatitude = end.latitude.toRadians()
    val latitudeDelta = endLatitude - startLatitude
    val longitudeDelta = (end.longitude - start.longitude).toRadians()

    val haversine = sin(latitudeDelta / 2.0).let { it * it } +
        cos(startLatitude) * cos(endLatitude) *
        sin(longitudeDelta / 2.0).let { it * it }
    val centralAngle = 2.0 * asin(sqrt(haversine.coerceIn(0.0, 1.0)))

    return EARTH_MEAN_RADIUS_METERS * centralAngle
}

/**
 * Calculates the shortest surface distance on the WGS-84 reference ellipsoid.
 *
 * Vincenty's inverse solution provides millimeter-scale convergence for ordinary point pairs.
 * Its known non-convergence around antipodal points falls back to the finite spherical distance.
 *
 * @param start first validated coordinate.
 * @param end second validated coordinate.
 * @return shortest ellipsoidal surface distance in meters, or a Haversine fallback near antipodes.
 */
fun wgs84GeodesicDistanceMeters(start: GeoCoordinate, end: GeoCoordinate): Double {
    val startLatitude = start.latitude.toRadians()
    val endLatitude = end.latitude.toRadians()
    val longitudeDelta = normalizeRadians((end.longitude - start.longitude).toRadians())

    val reducedStartLatitude = atan((1.0 - WGS84_FLATTENING) * tan(startLatitude))
    val reducedEndLatitude = atan((1.0 - WGS84_FLATTENING) * tan(endLatitude))
    val sinReducedStart = sin(reducedStartLatitude)
    val cosReducedStart = cos(reducedStartLatitude)
    val sinReducedEnd = sin(reducedEndLatitude)
    val cosReducedEnd = cos(reducedEndLatitude)

    var lambda = longitudeDelta
    var sinSigma = 0.0
    var cosSigma = 0.0
    var sigma = 0.0
    var cosSquaredAlpha = 0.0
    var cosTwoSigmaMidpoint = 0.0
    var converged = false

    for (iteration in 0 until VINCENTY_MAX_ITERATIONS) {
        val sinLambda = sin(lambda)
        val cosLambda = cos(lambda)
        val firstTerm = cosReducedEnd * sinLambda
        val secondTerm = cosReducedStart * sinReducedEnd -
            sinReducedStart * cosReducedEnd * cosLambda
        sinSigma = sqrt(firstTerm * firstTerm + secondTerm * secondTerm)

        if (sinSigma == 0.0) return 0.0

        cosSigma = sinReducedStart * sinReducedEnd +
            cosReducedStart * cosReducedEnd * cosLambda
        sigma = atan2(sinSigma, cosSigma)
        val sinAlpha = cosReducedStart * cosReducedEnd * sinLambda / sinSigma
        cosSquaredAlpha = (1.0 - sinAlpha * sinAlpha).coerceIn(0.0, 1.0)
        cosTwoSigmaMidpoint = if (cosSquaredAlpha <= VINCENTY_EQUATORIAL_EPSILON) {
            0.0
        } else {
            cosSigma - 2.0 * sinReducedStart * sinReducedEnd / cosSquaredAlpha
        }

        val correction = WGS84_FLATTENING / 16.0 * cosSquaredAlpha *
            (4.0 + WGS84_FLATTENING * (4.0 - 3.0 * cosSquaredAlpha))
        val previousLambda = lambda
        lambda = longitudeDelta + (1.0 - correction) * WGS84_FLATTENING * sinAlpha *
            (sigma + correction * sinSigma *
                (cosTwoSigmaMidpoint + correction * cosSigma *
                    (-1.0 + 2.0 * cosTwoSigmaMidpoint * cosTwoSigmaMidpoint)))

        if (!lambda.isFinite()) return haversineDistanceMeters(start, end)
        if (abs(lambda - previousLambda) <= VINCENTY_CONVERGENCE_RADIANS) {
            converged = true
            break
        }
    }

    if (!converged) return haversineDistanceMeters(start, end)

    val squaredReducedAxisRatio = cosSquaredAlpha *
        (WGS84_SEMI_MAJOR_AXIS_METERS * WGS84_SEMI_MAJOR_AXIS_METERS -
            WGS84_SEMI_MINOR_AXIS_METERS * WGS84_SEMI_MINOR_AXIS_METERS) /
        (WGS84_SEMI_MINOR_AXIS_METERS * WGS84_SEMI_MINOR_AXIS_METERS)
    val seriesA = 1.0 + squaredReducedAxisRatio / 16_384.0 *
        (4_096.0 + squaredReducedAxisRatio *
            (-768.0 + squaredReducedAxisRatio *
                (320.0 - 175.0 * squaredReducedAxisRatio)))
    val seriesB = squaredReducedAxisRatio / 1_024.0 *
        (256.0 + squaredReducedAxisRatio *
            (-128.0 + squaredReducedAxisRatio *
                (74.0 - 47.0 * squaredReducedAxisRatio)))
    val cosTwoSigmaMidpointSquared = cosTwoSigmaMidpoint * cosTwoSigmaMidpoint
    val deltaSigma = seriesB * sinSigma *
        (cosTwoSigmaMidpoint + seriesB / 4.0 *
            (cosSigma * (-1.0 + 2.0 * cosTwoSigmaMidpointSquared) -
                seriesB / 6.0 * cosTwoSigmaMidpoint *
                (-3.0 + 4.0 * sinSigma * sinSigma) *
                (-3.0 + 4.0 * cosTwoSigmaMidpointSquared)))
    val distance = WGS84_SEMI_MINOR_AXIS_METERS * seriesA * (sigma - deltaSigma)

    return distance.takeIf { it.isFinite() && it >= 0.0 }
        ?: haversineDistanceMeters(start, end)
}

/**
 * Returns the midpoint of the shorter great-circle arc between two points.
 * Longitudes around +/-180 degrees are handled without averaging through zero.
 * A midpoint is undefined for antipodal coordinates, so those inputs are rejected.
 *
 * @param first first endpoint of the arc.
 * @param second second endpoint of the arc.
 * @return the normalized coordinate halfway along the shorter great-circle arc.
 * @throws IllegalArgumentException when the coordinates are antipodal.
 */
fun sphericalMidpoint(first: GeoCoordinate, second: GeoCoordinate): GeoCoordinate {
    val firstVector = first.toUnitVector()
    val secondVector = second.toUnitVector()
    val x = firstVector.x + secondVector.x
    val y = firstVector.y + secondVector.y
    val z = firstVector.z + secondVector.z
    val magnitude = sqrt(x * x + y * y + z * z)

    require(magnitude > ANTIPODAL_EPSILON) {
        "A spherical midpoint is undefined for antipodal coordinates"
    }

    val latitude = atan2(z, sqrt(x * x + y * y)).toDegrees()
    val longitude = normalizeLongitude(atan2(y, x).toDegrees())
    return GeoCoordinate(latitude, longitude)
}

/**
 * Formats a non-negative distance as rounded meters below 1 km, or kilometers otherwise.
 *
 * @param distanceMeters finite non-negative distance in meters.
 * @return locale-stable text using `m` or `km` as appropriate.
 * @throws IllegalArgumentException when [distanceMeters] is negative or non-finite.
 */
fun formatDistance(distanceMeters: Double): String {
    require(distanceMeters.isFinite()) { "Distance must be finite" }
    require(distanceMeters >= 0.0) { "Distance cannot be negative" }

    return if (distanceMeters < 1_000.0) {
        "${distanceMeters.roundToLong()} m"
    } else {
        String.format(Locale.US, "%.2f km", distanceMeters / 1_000.0)
    }
}

/**
 * Cartesian unit vector used to calculate a stable spherical midpoint.
 *
 * @property x component on the equatorial Greenwich axis.
 * @property y component on the equatorial 90-degree-east axis.
 * @property z component on the north-pole axis.
 */
private data class UnitVector(val x: Double, val y: Double, val z: Double)

/**
 * Converts this coordinate to a Cartesian unit vector.
 *
 * @receiver validated geographic coordinate.
 * @return equivalent unit vector on a unit sphere.
 */
private fun GeoCoordinate.toUnitVector(): UnitVector {
    val latitudeRadians = latitude.toRadians()
    val longitudeRadians = longitude.toRadians()
    val latitudeCosine = cos(latitudeRadians)
    return UnitVector(
        x = latitudeCosine * cos(longitudeRadians),
        y = latitudeCosine * sin(longitudeRadians),
        z = sin(latitudeRadians),
    )
}

/**
 * Normalizes a longitude to the inclusive `-180.0..180.0` interval.
 *
 * @param longitude longitude in degrees, potentially outside a single turn.
 * @return normalized longitude while preserving positive 180 degrees.
 */
private fun normalizeLongitude(longitude: Double): Double {
    val normalized = ((longitude + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
    return if (normalized == -180.0 && longitude > 0.0) 180.0 else normalized
}

/**
 * Normalizes an angle in radians to the shortest signed turn around zero.
 *
 * @param angle angle in radians.
 * @return equivalent angle in the `-PI..PI` interval.
 */
private fun normalizeRadians(angle: Double): Double {
    val fullTurn = 2.0 * PI
    return ((angle + PI) % fullTurn + fullTurn) % fullTurn - PI
}

/** Converts this degree value to radians. */
private fun Double.toRadians(): Double = this * PI / 180.0

/** Converts this radian value to degrees. */
private fun Double.toDegrees(): Double = this * 180.0 / PI

/** Threshold used to avoid unstable division for equatorial geodesics. */
private const val VINCENTY_EQUATORIAL_EPSILON = 1e-16
