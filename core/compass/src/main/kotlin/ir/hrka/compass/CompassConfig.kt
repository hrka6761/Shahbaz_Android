/** Defines dependency-free sampling and smoothing configuration for compass observation. */
package ir.hrka.compass

/**
 * Immutable policy applied to one [Compass] instance.
 *
 * @property updateRate desired balance between latency and power usage.
 * @property smoothing amount of circular low-pass filtering applied to azimuth readings.
 */
data class CompassConfig @JvmOverloads constructor(
    /**
     * Exposes the updateRate value.
     */
    val updateRate: CompassUpdateRate = CompassUpdateRate.NORMAL,
    /**
     * Exposes the smoothing value.
     */
    val smoothing: CompassSmoothing = CompassSmoothing.BALANCED,
)

/** Preset sampling and delivery rates that remain below permission-gated high-frequency sensing. */
enum class CompassUpdateRate {
    /** Lowest-power preset for slowly changing, non-interactive consumers. */
    LOW_POWER,

    /** Balanced preset suitable for ordinary compass interfaces and navigation state. */
    NORMAL,

    /** Lower-latency preset for consumers that need more responsive orientation changes. */
    RESPONSIVE,
}

/** Preset strengths for circular exponential smoothing of compass azimuth. */
enum class CompassSmoothing {
    /** Emits the newest normalized azimuth without low-pass filtering. */
    NONE,

    /** Applies mild stabilization while preserving quick changes. */
    LIGHT,

    /** Applies the default balance between stability and responsiveness. */
    BALANCED,

    /** Applies stronger stabilization for noisy magnetic environments. */
    STRONG,
}
