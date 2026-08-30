/** Defines sensor availability, sensor-source, and failure models for the compass API. */
package ir.hrka.compass

/** Describes whether a [Compass] instance has a supported sensor source. */
sealed interface CompassAvailability {
    /**
     * Reports the sensor strategy selected for this device.
     *
     * @property source highest-priority supported source discovered during construction.
     */
    data class Available(val source: CompassSensorSource) : CompassAvailability

    /**
     * Reports why no compass observation can be started.
     *
     * @property reason stable reason that consumers can handle without platform exceptions.
     */
    data class Unavailable(val reason: CompassUnavailableReason) : CompassAvailability
}

/** Sensor strategies supported by the framework-backed compass implementation. */
enum class CompassSensorSource {
    /** Android's fused absolute rotation-vector sensor. */
    ROTATION_VECTOR,

    /** Android's lower-power geomagnetic rotation-vector sensor. */
    GEOMAGNETIC_ROTATION_VECTOR,

    /** Orientation fused by the module from accelerometer and magnetic-field samples. */
    ACCELEROMETER_AND_MAGNETOMETER,
}

/** Stable reasons why no supported sensor strategy can be selected. */
enum class CompassUnavailableReason {
    /** Android did not provide a sensor service to this process. */
    SENSOR_SERVICE_UNAVAILABLE,

    /** The device exposes neither a supported rotation vector nor the complete fallback pair. */
    NO_SUPPORTED_SENSOR,
}

/**
 * Typed failure exposed by a start operation or active observation session.
 *
 * @property code stable category suitable for branching and telemetry.
 * @property cause optional platform exception retained for diagnostics, when one exists.
 */
data class CompassFailure(
    /**
     * Exposes the code value.
     */
    val code: CompassFailureCode,
    /**
     * Exposes the cause value.
     */
    val cause: Throwable? = null,
)

/** Failure categories that can be produced by the compass implementation. */
enum class CompassFailureCode {
    /** A start was requested without a currently usable sensor source. */
    SENSOR_UNAVAILABLE,

    /** Android rejected registration of one or more required sensor listeners. */
    REGISTRATION_FAILED,

    /** A sensor sample could not be converted into a finite device orientation. */
    INVALID_SENSOR_DATA,
}
