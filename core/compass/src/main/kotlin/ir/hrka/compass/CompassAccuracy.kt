/** Defines logical sensor accuracy and calibration guidance for compass readings. */
package ir.hrka.compass

import ir.hrka.compass.internal.calibrationStatusForAccuracy

/**
 * Accuracy metadata associated with one [CompassReading].
 *
 * @property level normalized Android sensor-accuracy level.
 * @property estimatedErrorDegrees optional rotation-vector heading error in degrees.
 * @property calibrationStatus actionable calibration guidance inferred from [level].
 * @throws IllegalArgumentException when an error estimate is outside `0f < value <= 360f` or
 * [calibrationStatus] does not match the guidance inferred from [level].
 */
data class CompassAccuracy(
    val level: CompassAccuracyLevel,
    val estimatedErrorDegrees: Float?,
    val calibrationStatus: CalibrationStatus,
) {
    /** Rejects invalid error estimates and guidance that contradicts the accuracy level. */
    init {
        require(estimatedErrorDegrees == null || estimatedErrorDegrees.isFinite()) {
            "Estimated heading error must be finite when present"
        }
        require(
            estimatedErrorDegrees == null ||
                estimatedErrorDegrees > 0f && estimatedErrorDegrees <= MAX_HEADING_ERROR_DEGREES
        ) {
            "Estimated heading error must be greater than zero and at most 360 degrees"
        }
        require(calibrationStatus == calibrationStatusForAccuracy(level)) {
            "Calibration status must match the supplied accuracy level"
        }
    }
}

/** Platform-independent compass accuracy levels. */
enum class CompassAccuracyLevel {
    /** Android has not supplied usable accuracy information yet. */
    UNKNOWN,

    /** Android reports that the current magnetic result must not be trusted. */
    UNRELIABLE,

    /** The result is usable with caution and may benefit from calibration. */
    LOW,

    /** The result has ordinary accuracy for navigation-style presentation. */
    MEDIUM,

    /** Android reports its highest sensor accuracy level. */
    HIGH,
}

/** Host-facing calibration guidance inferred from sensor accuracy. */
enum class CalibrationStatus {
    /** No reliable calibration recommendation can be made yet. */
    UNKNOWN,

    /** Current sensor accuracy does not indicate a need for calibration. */
    NOT_REQUIRED,

    /** Calibration may improve a low-accuracy magnetic result. */
    RECOMMENDED,

    /** The current result is unreliable and calibration is strongly advised. */
    REQUIRED,
}

/** Maximum valid heading-error estimate reported by Android rotation-vector sensors. */
private const val MAX_HEADING_ERROR_DEGREES = 360f
