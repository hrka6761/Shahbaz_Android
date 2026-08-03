/** Reads and smooths device heading changes from Android's rotation-vector sensor. */
package ir.hrka.shahbaz.core.location

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.Surface
import kotlin.math.PI

/**
 * Lifecycle-controlled provider of screen-rotation-corrected magnetic heading updates.
 *
 * @param context context used to resolve process-scoped sensor and display services.
 */
class HeadingProvider(context: Context) {
    /** Process context retained instead of the caller's potentially short-lived context. */
    private val applicationContext = context.applicationContext

    /** Android sensor service used to discover and register the rotation-vector sensor. */
    private val sensorManager =
        applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    /** Display service used to compensate heading for the current screen rotation. */
    private val displayManager =
        applicationContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

    /** Preferred fused rotation-vector sensor, or `null` on unsupported devices. */
    private val rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    /** Monitor protecting registration state shared by lifecycle and sensor callbacks. */
    private val lock = Any()

    /** Whether [sensorListener] is currently registered with [sensorManager]. */
    private var isRegistered = false

    /** Current consumer callback, cleared whenever observation stops. */
    private var headingCallback: ((Float) -> Unit)? = null

    /** Last circularly smoothed heading in normalized degrees. */
    private var smoothedHeading: Float? = null

    /** Sensor timestamp of the last dispatched callback, in nanoseconds. */
    private var lastCallbackTimestampNanos = 0L

    /** Whether this device exposes the required rotation-vector sensor. */
    val isAvailable: Boolean
        get() = rotationVectorSensor != null

    /** Receives raw sensor events and dispatches throttled, smoothed heading values. */
    private val sensorListener = object : SensorEventListener {
        /**
         * Converts a rotation-vector event into a display-adjusted clockwise heading.
         *
         * @param event sensor event delivered by Android.
         */
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return

            val rotationMatrix = FloatArray(9)
            val remappedRotationMatrix = FloatArray(9)
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)

            val (xAxis, yAxis) = axesForDisplayRotation(currentDisplayRotation())
            if (
                !SensorManager.remapCoordinateSystem(
                    rotationMatrix,
                    xAxis,
                    yAxis,
                    remappedRotationMatrix,
                )
            ) {
                return
            }

            val orientation = FloatArray(3)
            SensorManager.getOrientation(remappedRotationMatrix, orientation)
            val rawHeading = normalizeDegrees((orientation[0] * 180f / PI.toFloat()))

            val callbackAndHeading = synchronized(lock) {
                if (!isRegistered) return

                val filteredHeading = smoothedHeading?.let { previousHeading ->
                    val shortestDelta = normalizeDelta(rawHeading - previousHeading)
                    normalizeDegrees(previousHeading + SMOOTHING_FACTOR * shortestDelta)
                } ?: rawHeading
                smoothedHeading = filteredHeading

                if (
                    lastCallbackTimestampNanos != 0L &&
                    event.timestamp - lastCallbackTimestampNanos < CALLBACK_INTERVAL_NANOS
                ) {
                    return
                }

                lastCallbackTimestampNanos = event.timestamp
                val callback = headingCallback ?: return
                callback to filteredHeading
            }

            callbackAndHeading.first(callbackAndHeading.second)
        }

        /**
         * Accepts sensor-accuracy notifications; heading updates remain available at every level.
         *
         * @param sensor sensor whose accuracy changed.
         * @param accuracy Android accuracy constant reported for [sensor].
         */
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    /**
     * Starts heading observation or replaces the callback of an already active observation.
     *
     * @param onHeadingChanged callback receiving normalized degrees clockwise from magnetic north.
     */
    fun start(onHeadingChanged: (Float) -> Unit) {
        synchronized(lock) {
            headingCallback = onHeadingChanged
            if (isRegistered) return

            val sensor = rotationVectorSensor ?: run {
                headingCallback = null
                return
            }

            smoothedHeading = null
            lastCallbackTimestampNanos = 0L
            isRegistered = sensorManager.registerListener(
                sensorListener,
                sensor,
                SENSOR_SAMPLING_PERIOD_MICROS,
            )

            if (!isRegistered) headingCallback = null
        }
    }

    /** Stops sensor observation and clears smoothing and throttling state. */
    fun stop() {
        synchronized(lock) {
            if (isRegistered) sensorManager.unregisterListener(sensorListener)
            isRegistered = false
            headingCallback = null
            smoothedHeading = null
            lastCallbackTimestampNanos = 0L
        }
    }

    /**
     * Reads the rotation of the display that hosts the application UI.
     *
     * @return a [Surface] rotation constant, defaulting to [Surface.ROTATION_0].
     */
    @Suppress("DEPRECATION")
    private fun currentDisplayRotation(): Int =
        displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: Surface.ROTATION_0

    /**
     * Selects Android coordinate-remapping axes for a display rotation.
     *
     * @param rotation one of the [Surface] rotation constants.
     * @return the X and Y axes passed to [SensorManager.remapCoordinateSystem].
     */
    private fun axesForDisplayRotation(rotation: Int): Pair<Int, Int> = when (rotation) {
        Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
        Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
        Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
        else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
    }

    /**
     * Normalizes any degree value to the half-open interval `0f until 360f`.
     *
     * @param degrees angle that may be negative or span multiple turns.
     * @return equivalent normalized heading.
     */
    private fun normalizeDegrees(degrees: Float): Float = (degrees % 360f + 360f) % 360f

    /**
     * Normalizes an angular change to the shortest signed interval `-180f until 180f`.
     *
     * @param delta raw change between two headings.
     * @return shortest signed circular change.
     */
    private fun normalizeDelta(delta: Float): Float = (delta + 540f) % 360f - 180f

    /** Sampling, throttling, and smoothing parameters for heading observation. */
    private companion object {
        /** Requested sensor sampling period in microseconds. */
        const val SENSOR_SAMPLING_PERIOD_MICROS = 50_000

        /** Minimum elapsed sensor time between consumer callbacks. */
        const val CALLBACK_INTERVAL_NANOS = 100_000_000L

        /** Exponential smoothing weight applied to the newest circular delta. */
        const val SMOOTHING_FACTOR = 0.2f
    }
}
