/** Provides pure angle, direction, filtering, accuracy, and sampling rules for the compass module. */
package ir.hrka.compass.internal

import ir.hrka.compass.CalibrationStatus
import ir.hrka.compass.CompassAccuracyLevel
import ir.hrka.compass.CompassDirection
import ir.hrka.compass.CompassSmoothing
import ir.hrka.compass.CompassUpdateRate
import ir.hrka.compass.DirectionDeviation
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor

/** Number of degrees in one complete turn. */
private const val FULL_TURN_DEGREES = 360f

/** Half of one complete turn and the maximum unsigned angular deviation. */
private const val HALF_TURN_DEGREES = 180f

/** Width of one sector in the eight-point compass rose. */
private const val DIRECTION_SECTOR_DEGREES = 45f

/** Half-width used to assign boundary angles to their clockwise compass sector. */
private const val HALF_DIRECTION_SECTOR_DEGREES = DIRECTION_SECTOR_DEGREES / 2f

/** Number of nanoseconds in one millisecond. */
private const val NANOS_PER_MILLISECOND = 1_000_000L

/** Maximum valid rotation-vector estimated heading error in radians. */
private const val MAX_HEADING_ERROR_RADIANS = (2.0 * PI).toFloat()

/**
 * Sensor sampling and consumer-delivery timing associated with an update-rate preset.
 *
 * @property samplingPeriodMicros requested Android sensor period in microseconds.
 * @property minimumCallbackIntervalNanos enforced minimum interval between emitted readings.
 */
internal data class SamplingPolicy(
    val samplingPeriodMicros: Int,
    val minimumCallbackIntervalNanos: Long,
)

/**
 * Normalizes a finite degree value into `0f <= value < 360f`.
 *
 * @param degrees angle that may be negative or span multiple turns.
 * @return equivalent normalized angle.
 * @throws IllegalArgumentException when [degrees] is not finite.
 */
internal fun normalizeDegrees(degrees: Float): Float {
    require(degrees.isFinite()) { "Angle must be finite" }
    return (degrees % FULL_TURN_DEGREES + FULL_TURN_DEGREES) % FULL_TURN_DEGREES
}

/**
 * Normalizes a finite angular delta into `-180f <= value < 180f`.
 *
 * @param degrees signed angular delta that may span multiple turns.
 * @return equivalent shortest signed turn.
 * @throws IllegalArgumentException when [degrees] is not finite.
 */
internal fun normalizeSignedDegrees(degrees: Float): Float {
    require(degrees.isFinite()) { "Angular delta must be finite" }
    return (
        (degrees + HALF_TURN_DEGREES) % FULL_TURN_DEGREES + FULL_TURN_DEGREES
    ) % FULL_TURN_DEGREES - HALF_TURN_DEGREES
}

/**
 * Maps a finite azimuth to its nearest eight-point semantic direction.
 *
 * @param azimuthDegrees clockwise azimuth, with values outside one turn accepted.
 * @return nearest [CompassDirection], assigning exact boundaries clockwise.
 * @throws IllegalArgumentException when [azimuthDegrees] is not finite.
 */
internal fun nearestCompassDirection(azimuthDegrees: Float): CompassDirection {
    val normalized = normalizeDegrees(azimuthDegrees)
    val index = floor(
        (normalized + HALF_DIRECTION_SECTOR_DEGREES) / DIRECTION_SECTOR_DEGREES
    ).toInt() % CompassDirection.entries.size
    return CompassDirection.entries[index]
}

/**
 * Calculates the shortest signed and absolute difference from a bearing to an azimuth.
 *
 * @param azimuthDegrees measured clockwise azimuth.
 * @param bearingDegrees reference bearing from which deviation is measured.
 * @return normalized deviation whose positive sign means clockwise.
 * @throws IllegalArgumentException when either input is not finite.
 */
internal fun directionDeviation(
    azimuthDegrees: Float,
    bearingDegrees: Float,
): DirectionDeviation {
    require(azimuthDegrees.isFinite()) { "Azimuth must be finite" }
    require(bearingDegrees.isFinite()) { "Bearing must be finite" }
    val signed = normalizeSignedDegrees(azimuthDegrees - bearingDegrees)
    return DirectionDeviation(signedDegrees = signed, absoluteDegrees = abs(signed))
}

/**
 * Applies circular exponential smoothing without crossing through the opposite bearing at north.
 *
 * @param previousDegrees previous normalized result, or `null` for the first sample.
 * @param currentDegrees newest finite azimuth.
 * @param newestWeight exponential weight in `0f..1f` applied to the newest angular delta.
 * @return normalized filtered azimuth.
 * @throws IllegalArgumentException when an input is non-finite or [newestWeight] is out of range.
 */
internal fun smoothAzimuthDegrees(
    previousDegrees: Float?,
    currentDegrees: Float,
    newestWeight: Float,
): Float {
    require(previousDegrees == null || previousDegrees.isFinite()) {
        "Previous azimuth must be finite when present"
    }
    require(currentDegrees.isFinite()) { "Current azimuth must be finite" }
    require(newestWeight.isFinite() && newestWeight in 0f..1f) {
        "Smoothing weight must be within 0..1"
    }
    val normalizedCurrent = normalizeDegrees(currentDegrees)
    val previous = previousDegrees ?: return normalizedCurrent
    val shortestDelta = normalizeSignedDegrees(normalizedCurrent - normalizeDegrees(previous))
    return normalizeDegrees(previous + newestWeight * shortestDelta)
}

/**
 * Converts an optional Android rotation-vector heading error from radians to degrees.
 *
 * Android uses `-1` when no estimate is available and defines valid estimates as greater than zero
 * and no greater than one complete turn. Other values are treated as unavailable.
 *
 * @param errorRadians optional sensor-provided estimated heading error in radians.
 * @return error within `0f < value <= 360f`, or `null` when unavailable or invalid.
 */
internal fun estimatedHeadingErrorDegrees(errorRadians: Float?): Float? {
    if (
        errorRadians == null ||
        !errorRadians.isFinite() ||
        errorRadians <= 0f ||
        errorRadians > MAX_HEADING_ERROR_RADIANS
    ) {
        return null
    }
    val degrees = errorRadians * 180f / PI.toFloat()
    return degrees.takeIf { it.isFinite() && it >= 0f }
}

/**
 * Maps a public smoothing preset to the newest-sample weight used by the circular filter.
 *
 * @receiver selected smoothing preset.
 * @return exponential weight in `0f..1f`.
 */
internal fun CompassSmoothing.newestSampleWeight(): Float = when (this) {
    CompassSmoothing.NONE -> 1f
    CompassSmoothing.LIGHT -> 0.5f
    CompassSmoothing.BALANCED -> 0.2f
    CompassSmoothing.STRONG -> 0.1f
}

/**
 * Maps a public update-rate preset to Android sampling and enforced callback timing.
 *
 * Every preset remains well below Android's permission-gated 200 Hz sensor threshold.
 *
 * @receiver selected update-rate preset.
 * @return immutable sampling and delivery policy.
 */
internal fun CompassUpdateRate.samplingPolicy(): SamplingPolicy = when (this) {
    CompassUpdateRate.LOW_POWER -> SamplingPolicy(
        samplingPeriodMicros = 200_000,
        minimumCallbackIntervalNanos = 250L * NANOS_PER_MILLISECOND,
    )

    CompassUpdateRate.NORMAL -> SamplingPolicy(
        samplingPeriodMicros = 50_000,
        minimumCallbackIntervalNanos = 100L * NANOS_PER_MILLISECOND,
    )

    CompassUpdateRate.RESPONSIVE -> SamplingPolicy(
        samplingPeriodMicros = 20_000,
        minimumCallbackIntervalNanos = 33L * NANOS_PER_MILLISECOND,
    )
}

/**
 * Determines whether a monotonic sensor timestamp is eligible for consumer delivery.
 *
 * @param previousTimestampNanos last delivered timestamp, or zero before the first delivery.
 * @param currentTimestampNanos current non-negative sensor timestamp.
 * @param minimumIntervalNanos enforced interval between deliveries.
 * @return `true` for the first timestamp or when the interval has elapsed.
 */
internal fun shouldDispatchTimestamp(
    previousTimestampNanos: Long,
    currentTimestampNanos: Long,
    minimumIntervalNanos: Long,
): Boolean {
    require(previousTimestampNanos >= 0L) { "Previous timestamp cannot be negative" }
    require(currentTimestampNanos >= 0L) { "Current timestamp cannot be negative" }
    require(minimumIntervalNanos >= 0L) { "Minimum interval cannot be negative" }
    return previousTimestampNanos == 0L ||
        currentTimestampNanos - previousTimestampNanos >= minimumIntervalNanos
}

/**
 * Derives host-facing calibration guidance from a normalized accuracy level.
 *
 * @param accuracy normalized platform-independent accuracy level.
 * @return calibration guidance matching the public accuracy contract.
 */
internal fun calibrationStatusForAccuracy(accuracy: CompassAccuracyLevel): CalibrationStatus =
    when (accuracy) {
        CompassAccuracyLevel.UNRELIABLE -> CalibrationStatus.REQUIRED
        CompassAccuracyLevel.LOW -> CalibrationStatus.RECOMMENDED
        CompassAccuracyLevel.MEDIUM,
        CompassAccuracyLevel.HIGH,
        -> CalibrationStatus.NOT_REQUIRED

        CompassAccuracyLevel.UNKNOWN -> CalibrationStatus.UNKNOWN
    }
