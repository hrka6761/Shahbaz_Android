/** Implements the public compass contract with Android orientation and magnetic-field sensors. */
package ir.hrka.compass.internal

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Surface
import android.view.WindowManager
import ir.hrka.compass.Compass
import ir.hrka.compass.CompassAccuracy
import ir.hrka.compass.CompassAccuracyLevel
import ir.hrka.compass.CompassAvailability
import ir.hrka.compass.CompassConfig
import ir.hrka.compass.CompassEvent
import ir.hrka.compass.CompassFailure
import ir.hrka.compass.CompassFailureCode
import ir.hrka.compass.CompassListener
import ir.hrka.compass.CompassReading
import ir.hrka.compass.CompassSensorSource
import ir.hrka.compass.CompassStartResult
import ir.hrka.compass.CompassUnavailableReason
import ir.hrka.compass.GeomagneticPosition
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.PI
import kotlin.math.abs

/**
 * Android-framework implementation selected by [Compass.create].
 *
 * @param context context used to capture display identity and retain the process application context.
 * @param config immutable update-rate and smoothing configuration.
 */
internal class AndroidCompass(
    context: Context,
    config: CompassConfig,
) : Compass {
    /** Display associated with the caller's context at construction time. */
    private val associatedDisplayId: Int = displayIdForContext(context)

    /** Process context retained instead of a potentially short-lived activity context. */
    private val applicationContext: Context = context.applicationContext

    /** Android sensor service, or `null` on a process where that service is unavailable. */
    private val sensorManager: SensorManager? =
        applicationContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    /** Android display service used to make orientation follow the current display's top edge. */
    private val displayManager: DisplayManager? =
        applicationContext.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager

    /** Main-thread handler that serializes every framework sensor callback. */
    private val mainHandler: Handler = Handler(Looper.getMainLooper())

    /** Highest-priority supported sensor strategy discovered for this device. */
    private val sensorSelection: SensorSelection? = sensorManager?.let(::selectSensorSelection)

    /** Sampling and delivery timing resolved once from [config]. */
    private val samplingPolicy: SamplingPolicy = config.updateRate.samplingPolicy()

    /** Circular-filter weight resolved once from [config]. */
    private val smoothingWeight: Float = config.smoothing.newestSampleWeight()

    /** Monitor protecting lifecycle, filter, fallback-sample, and listener state. */
    private val stateLock = Any()

    /**
     * Reentrant gate serializing consumer callbacks with start and stop operations.
     *
     * Reentrancy permits a listener to stop or restart its own compass without deadlocking.
     */
    private val callbackGate = ReentrantLock()

    /** Consumer retained only for the duration of an active observation session. */
    private var consumerListener: CompassListener? = null

    /** Session-specific platform listener whose generation rejects stale queued events. */
    private var activeSensorListener: SensorEventListener? = null

    /** Whether every sensor required by [sensorSelection] is currently registered. */
    private var running: Boolean = false

    /** Monotonically increasing identifier invalidating callbacks from prior sessions. */
    private var sessionGeneration: Long = 0L

    /** Last circularly smoothed magnetic azimuth, or `null` before the first valid sample. */
    private var smoothedAzimuthDegrees: Float? = null

    /** Latest processed sensor timestamp used to reject duplicate or decreasing samples. */
    private var lastProcessedTimestampNanos: Long = 0L

    /** Latest consumer-delivered sensor timestamp used to enforce [samplingPolicy]. */
    private var lastDispatchedTimestampNanos: Long = 0L

    /** Most recent accuracy level reported by the selected north-bearing sensor. */
    private var currentAccuracyLevel: CompassAccuracyLevel = CompassAccuracyLevel.UNKNOWN

    /** Latest valid accelerometer vector retained for the manual sensor-fusion fallback. */
    private var accelerometerValues: FloatArray? = null

    /** Timestamp associated with [accelerometerValues]. */
    private var accelerometerTimestampNanos: Long = 0L

    /** Latest valid magnetic-field vector retained for the manual sensor-fusion fallback. */
    private var magneticFieldValues: FloatArray? = null

    /** Timestamp associated with [magneticFieldValues]. */
    private var magneticFieldTimestampNanos: Long = 0L

    /** Caller-configured magnetic declination in degrees, or `null` when true north is disabled. */
    private var declinationDegrees: Float? = null

    /** Prevents a continuous sequence of malformed samples from flooding the consumer. */
    private var invalidDataFailureReported: Boolean = false

    /** Reports sensor-service or selected-source availability without registering a listener. */
    override val availability: CompassAvailability = when {
        sensorManager == null -> CompassAvailability.Unavailable(
            CompassUnavailableReason.SENSOR_SERVICE_UNAVAILABLE
        )

        sensorSelection == null -> CompassAvailability.Unavailable(
            CompassUnavailableReason.NO_SUPPORTED_SENSOR
        )

        else -> CompassAvailability.Available(sensorSelection.source)
    }

    /** Reads active registration state under [stateLock]. */
    override val isRunning: Boolean
        get() = synchronized(stateLock) { running }

    /** Registers the selected Android sensors and begins a fresh filtered observation session. */
    override fun start(listener: CompassListener): CompassStartResult = callbackGate.withLock {
        synchronized(stateLock) {
            if (running) return@synchronized CompassStartResult.AlreadyRunning

            val manager = sensorManager
            val selection = sensorSelection
            if (manager == null || selection == null) {
                return@synchronized CompassStartResult.Failed(
                    CompassFailure(CompassFailureCode.SENSOR_UNAVAILABLE)
                )
            }

            sessionGeneration += 1L
            resetSessionValuesLocked()
            consumerListener = listener
            val platformListener = SessionSensorEventListener(sessionGeneration)
            activeSensorListener = platformListener
            running = true

            val failure = try {
                if (
                    registerSelection(
                        sensorManager = manager,
                        selection = selection,
                        listener = platformListener,
                        samplingPeriodMicros = samplingPolicy.samplingPeriodMicros,
                        callbackHandler = mainHandler,
                    )
                ) {
                    null
                } else {
                    CompassFailure(CompassFailureCode.REGISTRATION_FAILED)
                }
            } catch (error: RuntimeException) {
                CompassFailure(CompassFailureCode.REGISTRATION_FAILED, error)
            }

            if (failure == null) {
                CompassStartResult.Started
            } else {
                runCatching { manager.unregisterListener(platformListener) }
                sessionGeneration += 1L
                running = false
                activeSensorListener = null
                consumerListener = null
                resetSessionValuesLocked()
                CompassStartResult.Failed(failure)
            }
        }
    }

    /** Unregisters the active platform listener and invalidates all queued session callbacks. */
    override fun stop() {
        callbackGate.withLock {
            synchronized(stateLock) {
                val listener = activeSensorListener
                if (listener != null) {
                    runCatching { sensorManager?.unregisterListener(listener) }
                }
                sessionGeneration += 1L
                running = false
                activeSensorListener = null
                consumerListener = null
                resetSessionValuesLocked()
            }
        }
    }

    /** Calculates and stores magnetic declination from caller-supplied position and time. */
    override fun setGeomagneticPosition(position: GeomagneticPosition?) {
        val updatedDeclination = position?.let { value ->
            GeomagneticField(
                value.latitudeDegrees.toFloat(),
                value.longitudeDegrees.toFloat(),
                value.altitudeMeters.toFloat(),
                value.timestampEpochMillis,
            ).declination.takeIf(Float::isFinite)
        }
        synchronized(stateLock) {
            declinationDegrees = updatedDeclination
        }
    }

    /**
     * Platform callback dedicated to one [generation] of sensor registration.
     *
     * @property generation immutable session identifier captured when observation starts.
     */
    private inner class SessionSensorEventListener(
        private val generation: Long,
    ) : SensorEventListener {
        /** Converts a selected-source sample into a logical event when it is eligible. */
        override fun onSensorChanged(event: SensorEvent) {
            handleSensorChanged(generation, event)
        }

        /** Retains accuracy changes only from the selected north-bearing sensor. */
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            handleAccuracyChanged(generation, sensor, accuracy)
        }
    }

    /**
     * Processes one platform sensor sample and invokes consumer code outside [stateLock].
     *
     * @param generation session identifier captured by the platform listener.
     * @param event Android sensor sample delivered on [mainHandler].
     */
    private fun handleSensorChanged(generation: Long, event: SensorEvent) {
        val dispatch = try {
            synchronized(stateLock) {
                buildReadingDispatchLocked(generation, event)
            }
        } catch (error: RuntimeException) {
            synchronized(stateLock) {
                buildInvalidDataDispatchLocked(generation, error)
            }
        }
        if (dispatch != null) deliverDispatch(generation, dispatch)
    }

    /**
     * Delivers an event only if its session remains current after entering [callbackGate].
     *
     * The gate ensures that a concurrent [stop] either invalidates this event before delivery or
     * waits for the in-flight callback before returning.
     *
     * @param generation session identifier that produced [dispatch].
     * @param dispatch listener and event captured while module state was locked.
     */
    private fun deliverDispatch(generation: Long, dispatch: CompassDispatch) {
        callbackGate.withLock {
            val remainsCurrent = synchronized(stateLock) {
                isCurrentSessionLocked(generation) && consumerListener === dispatch.listener
            }
            if (remainsCurrent) dispatch.listener.onEvent(dispatch.event)
        }
    }

    /**
     * Updates normalized accuracy for a relevant sensor and active session.
     *
     * @param generation session identifier captured by the platform listener.
     * @param sensor sensor whose accuracy changed, or `null` from the platform.
     * @param accuracy Android `SENSOR_STATUS_*` value.
     */
    private fun handleAccuracyChanged(generation: Long, sensor: Sensor?, accuracy: Int) {
        synchronized(stateLock) {
            if (!isCurrentSessionLocked(generation)) return
            val selection = sensorSelection ?: return
            if (sensor != null && selection.usesAccuracyFrom(sensor.type)) {
                currentAccuracyLevel = accuracy.toCompassAccuracyLevel()
            }
        }
    }

    /**
     * Builds a reading dispatch from one selected sensor event.
     *
     * @param generation active session identifier.
     * @param event platform sensor event.
     * @return consumer/event pair, or `null` for ignored, pending, old, or throttled data.
     */
    private fun buildReadingDispatchLocked(
        generation: Long,
        event: SensorEvent,
    ): CompassDispatch? {
        if (!isCurrentSessionLocked(generation)) return null

        return when (val matrixResult = buildRotationMatrixLocked(event)) {
            MatrixBuildResult.Ignored,
            MatrixBuildResult.Pending,
            -> null

            is MatrixBuildResult.Invalid ->
                buildInvalidDataDispatchLocked(generation, matrixResult.cause)

            is MatrixBuildResult.Ready ->
                buildReadingFromMatrixLocked(generation, event.timestamp, matrixResult.sample)
        }
    }

    /**
     * Converts a rotation matrix into display-corrected azimuth, pitch, and roll.
     *
     * @param generation active session identifier.
     * @param timestampNanos timestamp of the sensor event that completed this matrix.
     * @param sample rotation matrix and optional sensor-provided error estimate.
     * @return eligible consumer reading, invalid-data failure, or `null` when throttled or old.
     */
    private fun buildReadingFromMatrixLocked(
        generation: Long,
        timestampNanos: Long,
        sample: RotationMatrixSample,
    ): CompassDispatch? {
        if (timestampNanos <= 0L) {
            return buildInvalidDataDispatchLocked(generation)
        }
        if (
            lastProcessedTimestampNanos != 0L &&
            timestampNanos <= lastProcessedTimestampNanos
        ) {
            return null
        }

        val remappedMatrix = FloatArray(ROTATION_MATRIX_SIZE)
        val axes = axesForDisplayRotation(currentDisplayRotation())
        if (
            !SensorManager.remapCoordinateSystem(
                sample.rotationMatrix,
                axes.xAxis,
                axes.yAxis,
                remappedMatrix,
            )
        ) {
            return buildInvalidDataDispatchLocked(generation)
        }

        val orientation = FloatArray(ORIENTATION_VALUE_COUNT)
        SensorManager.getOrientation(remappedMatrix, orientation)
        if (orientation.any { !it.isFinite() }) {
            return buildInvalidDataDispatchLocked(generation)
        }

        val rawAzimuth = normalizeDegrees(orientation[AZIMUTH_INDEX].radiansToDegrees())
        val filteredAzimuth = smoothAzimuthDegrees(
            previousDegrees = smoothedAzimuthDegrees,
            currentDegrees = rawAzimuth,
            newestWeight = smoothingWeight,
        )
        smoothedAzimuthDegrees = filteredAzimuth
        lastProcessedTimestampNanos = timestampNanos
        invalidDataFailureReported = false

        if (
            !shouldDispatchTimestamp(
                previousTimestampNanos = lastDispatchedTimestampNanos,
                currentTimestampNanos = timestampNanos,
                minimumIntervalNanos = samplingPolicy.minimumCallbackIntervalNanos,
            )
        ) {
            return null
        }

        lastDispatchedTimestampNanos = timestampNanos
        val currentDeclination = declinationDegrees
        val trueAzimuth = currentDeclination?.let { declination ->
            normalizeDegrees(filteredAzimuth + declination)
        }
        val accuracy = CompassAccuracy(
            level = currentAccuracyLevel,
            estimatedErrorDegrees = sample.estimatedHeadingErrorDegrees,
            calibrationStatus = calibrationStatusForAccuracy(currentAccuracyLevel),
        )
        val selection = sensorSelection ?: return null
        val listener = consumerListener ?: return null
        val reading = CompassReading(
            magneticAzimuthDegrees = filteredAzimuth,
            trueAzimuthDegrees = trueAzimuth,
            declinationDegrees = currentDeclination,
            pitchDegrees = orientation[PITCH_INDEX].radiansToDegrees(),
            rollDegrees = orientation[ROLL_INDEX].radiansToDegrees(),
            accuracy = accuracy,
            sensorSource = selection.source,
            timestampNanos = timestampNanos,
        )
        return CompassDispatch(listener, CompassEvent.Reading(reading))
    }

    /**
     * Builds at most one failure dispatch for a consecutive sequence of invalid sensor samples.
     *
     * @param generation active session identifier.
     * @param cause optional exception raised while interpreting platform data.
     * @return consumer/failure pair, or `null` when stale or already reported.
     */
    private fun buildInvalidDataDispatchLocked(
        generation: Long,
        cause: Throwable? = null,
    ): CompassDispatch? {
        if (!isCurrentSessionLocked(generation) || invalidDataFailureReported) return null
        val listener = consumerListener ?: return null
        invalidDataFailureReported = true
        return CompassDispatch(
            listener = listener,
            event = CompassEvent.Failure(
                CompassFailure(CompassFailureCode.INVALID_SENSOR_DATA, cause)
            ),
        )
    }

    /**
     * Converts a selected sensor event into a rotation-matrix build result.
     *
     * @param event platform event delivered to the current session listener.
     * @return ignored, pending, invalid, or ready matrix result.
     */
    private fun buildRotationMatrixLocked(event: SensorEvent): MatrixBuildResult {
        val selection = sensorSelection ?: return MatrixBuildResult.Ignored
        return when (selection) {
            is SensorSelection.RotationVector ->
                buildRotationVectorMatrixLocked(selection, event)

            is SensorSelection.AccelerometerAndMagnetometer ->
                buildFallbackMatrixLocked(selection, event)
        }
    }

    /**
     * Builds a rotation matrix from a fused or geomagnetic rotation-vector event.
     *
     * @param selection selected vector sensor and its public source identity.
     * @param event current platform sensor event.
     * @return ignored, invalid, or ready matrix result.
     */
    private fun buildRotationVectorMatrixLocked(
        selection: SensorSelection.RotationVector,
        event: SensorEvent,
    ): MatrixBuildResult {
        if (event.sensor.type != selection.sensor.type) return MatrixBuildResult.Ignored
        val orientationValueCount = minOf(
            event.values.size,
            MAXIMUM_ROTATION_VECTOR_ORIENTATION_VALUE_COUNT,
        )
        if (
            orientationValueCount < MINIMUM_ROTATION_VECTOR_VALUE_COUNT ||
            !event.values.hasFinitePrefix(orientationValueCount)
        ) {
            return MatrixBuildResult.Invalid()
        }
        currentAccuracyLevel = event.accuracy.toCompassAccuracyLevel()
        val matrix = FloatArray(ROTATION_MATRIX_SIZE)
        SensorManager.getRotationMatrixFromVector(matrix, event.values)
        return MatrixBuildResult.Ready(
            RotationMatrixSample(
                rotationMatrix = matrix,
                estimatedHeadingErrorDegrees = estimatedHeadingErrorDegrees(
                    event.values.getOrNull(HEADING_ERROR_INDEX)
                ),
            )
        )
    }

    /**
     * Updates fallback vectors and fuses a sufficiently fresh accelerometer/magnetometer pair.
     *
     * @param selection selected accelerometer and magnetic-field sensors.
     * @param event current platform sensor event.
     * @return ignored, pending, invalid, or ready matrix result.
     */
    private fun buildFallbackMatrixLocked(
        selection: SensorSelection.AccelerometerAndMagnetometer,
        event: SensorEvent,
    ): MatrixBuildResult {
        when (event.sensor.type) {
            selection.accelerometer.type -> {
                if (!event.values.hasFinitePrefix(FALLBACK_VECTOR_VALUE_COUNT)) {
                    return MatrixBuildResult.Invalid()
                }
                accelerometerValues = event.values.copyOf(FALLBACK_VECTOR_VALUE_COUNT)
                accelerometerTimestampNanos = event.timestamp
            }

            selection.magnetometer.type -> {
                if (!event.values.hasFinitePrefix(FALLBACK_VECTOR_VALUE_COUNT)) {
                    return MatrixBuildResult.Invalid()
                }
                magneticFieldValues = event.values.copyOf(FALLBACK_VECTOR_VALUE_COUNT)
                magneticFieldTimestampNanos = event.timestamp
                currentAccuracyLevel = event.accuracy.toCompassAccuracyLevel()
            }

            else -> return MatrixBuildResult.Ignored
        }

        val acceleration = accelerometerValues ?: return MatrixBuildResult.Pending
        val magneticField = magneticFieldValues ?: return MatrixBuildResult.Pending
        if (
            abs(accelerometerTimestampNanos - magneticFieldTimestampNanos) >
            MAX_FALLBACK_SAMPLE_SEPARATION_NANOS
        ) {
            return MatrixBuildResult.Pending
        }

        val matrix = FloatArray(ROTATION_MATRIX_SIZE)
        if (!SensorManager.getRotationMatrix(matrix, null, acceleration, magneticField)) {
            return MatrixBuildResult.Pending
        }
        return MatrixBuildResult.Ready(
            RotationMatrixSample(
                rotationMatrix = matrix,
                estimatedHeadingErrorDegrees = null,
            )
        )
    }

    /**
     * Determines whether [generation] still identifies the active registered session.
     *
     * @param generation generation captured by a platform listener.
     * @return `true` only while that exact session remains active.
     */
    private fun isCurrentSessionLocked(generation: Long): Boolean =
        running && generation == sessionGeneration

    /** Clears all per-session samples, accuracy, timestamps, and filter state under [stateLock]. */
    private fun resetSessionValuesLocked() {
        smoothedAzimuthDegrees = null
        lastProcessedTimestampNanos = 0L
        lastDispatchedTimestampNanos = 0L
        currentAccuracyLevel = CompassAccuracyLevel.UNKNOWN
        accelerometerValues = null
        accelerometerTimestampNanos = 0L
        magneticFieldValues = null
        magneticFieldTimestampNanos = 0L
        invalidDataFailureReported = false
    }

    /**
     * Reads the rotation of the display associated with the compass creation context.
     *
     * @return one of Android's [Surface] rotation constants, defaulting to rotation zero.
     */
    @Suppress("DEPRECATION")
    private fun currentDisplayRotation(): Int =
        displayManager?.getDisplay(associatedDisplayId)?.rotation ?: Surface.ROTATION_0
}

/**
 * Captures the display identity represented by a supplied Android context without retaining it.
 *
 * Modern visual and display contexts expose [Context.getDisplay] directly. The legacy window
 * service fallback preserves compatibility with this library's API 21 minimum.
 *
 * @param context caller context that may be associated with a primary or secondary display.
 * @return stable display identifier, defaulting to [Display.DEFAULT_DISPLAY].
 */
@Suppress("DEPRECATION")
private fun displayIdForContext(context: Context): Int {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        runCatching { context.display.displayId }.getOrNull()?.let { displayId ->
            return displayId
        }
    }
    val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
    return windowManager?.defaultDisplay?.displayId ?: Display.DEFAULT_DISPLAY
}

/**
 * Selected platform sensor strategy retained by one [AndroidCompass].
 *
 * @property source public identity exposed through availability and readings.
 */
private sealed interface SensorSelection {
    /** Public identity of this platform sensor strategy. */
    val source: CompassSensorSource

    /**
     * Reports whether accuracy from [sensorType] represents magnetic heading quality.
     *
     * @param sensorType Android sensor type received by `onAccuracyChanged`.
     * @return `true` when that accuracy should update compass calibration guidance.
     */
    fun usesAccuracyFrom(sensorType: Int): Boolean

    /**
     * One Android rotation-vector sensor that directly supplies an orientation matrix.
     *
     * @property sensor selected platform sensor.
     * @property source fused or geomagnetic public source identity.
     */
    data class RotationVector(
        val sensor: Sensor,
        override val source: CompassSensorSource,
    ) : SensorSelection {
        /** Accepts accuracy only from the selected rotation-vector sensor type. */
        override fun usesAccuracyFrom(sensorType: Int): Boolean = sensorType == sensor.type
    }

    /**
     * Manual orientation fallback requiring both acceleration and magnetic-field vectors.
     *
     * @property accelerometer selected acceleration sensor.
     * @property magnetometer selected magnetic-field sensor.
     */
    data class AccelerometerAndMagnetometer(
        val accelerometer: Sensor,
        val magnetometer: Sensor,
    ) : SensorSelection {
        /** Identifies this strategy in availability and emitted readings. */
        override val source: CompassSensorSource =
            CompassSensorSource.ACCELEROMETER_AND_MAGNETOMETER

        /** Uses magnetometer accuracy because the accelerometer does not describe heading quality. */
        override fun usesAccuracyFrom(sensorType: Int): Boolean = sensorType == magnetometer.type
    }
}

/**
 * Output of attempting to build an orientation rotation matrix from a sensor event.
 */
private sealed interface MatrixBuildResult {
    /** Event came from a sensor outside the selected strategy. */
    data object Ignored : MatrixBuildResult

    /** A valid fallback pair or stable matrix is not available yet. */
    data object Pending : MatrixBuildResult

    /**
     * Sensor values were malformed or could not be interpreted.
     *
     * @property cause optional platform exception associated with the invalid result.
     */
    data class Invalid(val cause: Throwable? = null) : MatrixBuildResult

    /**
     * Rotation matrix is ready for display-coordinate remapping.
     *
     * @property sample matrix and optional sensor error estimate.
     */
    data class Ready(val sample: RotationMatrixSample) : MatrixBuildResult
}

/**
 * Rotation matrix and optional heading-error estimate created from one selected source.
 *
 * @property rotationMatrix Android three-by-three orientation matrix.
 * @property estimatedHeadingErrorDegrees optional heading error within `0f < value <= 360f`.
 */
private data class RotationMatrixSample(
    val rotationMatrix: FloatArray,
    val estimatedHeadingErrorDegrees: Float?,
)

/**
 * Consumer and event captured atomically while module state is locked.
 *
 * @property listener active consumer for the session that produced [event].
 * @property event reading or failure to invoke outside the module lock.
 */
private data class CompassDispatch(
    val listener: CompassListener,
    val event: CompassEvent,
)

/**
 * Android coordinate-remapping axes for one display rotation.
 *
 * @property xAxis Android `SensorManager.AXIS_*` value for display X.
 * @property yAxis Android `SensorManager.AXIS_*` value for display Y.
 */
internal data class DisplayAxes(
    val xAxis: Int,
    val yAxis: Int,
)

/**
 * Selects Android coordinate-remapping axes for the current display rotation.
 *
 * @param rotation one of Android's [Surface] rotation constants.
 * @return X and Y axes passed to [SensorManager.remapCoordinateSystem].
 */
internal fun axesForDisplayRotation(rotation: Int): DisplayAxes = when (rotation) {
    Surface.ROTATION_90 -> DisplayAxes(SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X)
    Surface.ROTATION_180 -> DisplayAxes(
        SensorManager.AXIS_MINUS_X,
        SensorManager.AXIS_MINUS_Y,
    )

    Surface.ROTATION_270 -> DisplayAxes(SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X)
    else -> DisplayAxes(SensorManager.AXIS_X, SensorManager.AXIS_Y)
}

/**
 * Selects the highest-priority north-bearing sensor strategy available on a device.
 *
 * Game rotation vectors are deliberately excluded because they drift without a magnetic-north
 * reference. Android's API 33 heading sensor is also excluded because it cannot supply pitch and
 * roll, while this API promises one coherent orientation reading.
 *
 * @param sensorManager Android service used to discover default sensors.
 * @return selected strategy, or `null` when no complete strategy exists.
 */
private fun selectSensorSelection(sensorManager: SensorManager): SensorSelection? {
    sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let { sensor ->
        return SensorSelection.RotationVector(
            sensor = sensor,
            source = CompassSensorSource.ROTATION_VECTOR,
        )
    }
    sensorManager.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)?.let { sensor ->
        return SensorSelection.RotationVector(
            sensor = sensor,
            source = CompassSensorSource.GEOMAGNETIC_ROTATION_VECTOR,
        )
    }
    val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return null
    val magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) ?: return null
    return SensorSelection.AccelerometerAndMagnetometer(accelerometer, magnetometer)
}

/**
 * Registers every sensor required by [selection], rolling back a partial fallback registration.
 *
 * @param sensorManager Android service that owns the listener registrations.
 * @param selection sensor strategy selected for this device.
 * @param listener session-specific listener to register.
 * @param samplingPeriodMicros requested interval between platform sensor samples.
 * @param callbackHandler explicit main-thread delivery handler.
 * @return `true` only when every required registration succeeds.
 */
private fun registerSelection(
    sensorManager: SensorManager,
    selection: SensorSelection,
    listener: SensorEventListener,
    samplingPeriodMicros: Int,
    callbackHandler: Handler,
): Boolean {
    /** Registers one sensor with the configured main-thread handler and sampling period. */
    fun register(sensor: Sensor): Boolean = sensorManager.registerListener(
        listener,
        sensor,
        samplingPeriodMicros,
        callbackHandler,
    )

    return when (selection) {
        is SensorSelection.RotationVector -> register(selection.sensor)
        is SensorSelection.AccelerometerAndMagnetometer -> {
            if (!register(selection.accelerometer)) return false
            if (register(selection.magnetometer)) {
                true
            } else {
                sensorManager.unregisterListener(listener)
                false
            }
        }
    }
}

/**
 * Maps an Android sensor accuracy constant to the public platform-independent level.
 *
 * @receiver Android `SENSOR_STATUS_*` or `SENSOR_STATUS_ACCURACY_*` value.
 * @return normalized public accuracy level.
 */
private fun Int.toCompassAccuracyLevel(): CompassAccuracyLevel = when (this) {
    SensorManager.SENSOR_STATUS_UNRELIABLE -> CompassAccuracyLevel.UNRELIABLE
    SensorManager.SENSOR_STATUS_ACCURACY_LOW -> CompassAccuracyLevel.LOW
    SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> CompassAccuracyLevel.MEDIUM
    SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> CompassAccuracyLevel.HIGH
    else -> CompassAccuracyLevel.UNKNOWN
}

/**
 * Checks that this array contains at least [count] finite leading values.
 *
 * @receiver platform sensor value array.
 * @param count number of leading values required by the selected calculation.
 * @return `true` when the required prefix exists and is entirely finite.
 */
private fun FloatArray.hasFinitePrefix(count: Int): Boolean =
    size >= count && (0 until count).all { index -> this[index].isFinite() }

/** Converts a finite radian value to degrees. */
private fun Float.radiansToDegrees(): Float = this * 180f / PI.toFloat()

/** Number of values in Android's compact three-by-three rotation matrix. */
private const val ROTATION_MATRIX_SIZE = 9

/** Number of azimuth, pitch, and roll values returned by Android orientation conversion. */
private const val ORIENTATION_VALUE_COUNT = 3

/** Index of azimuth within Android's orientation array. */
private const val AZIMUTH_INDEX = 0

/** Index of pitch within Android's orientation array. */
private const val PITCH_INDEX = 1

/** Index of roll within Android's orientation array. */
private const val ROLL_INDEX = 2

/** Minimum number of finite values required to interpret a rotation-vector event. */
private const val MINIMUM_ROTATION_VECTOR_VALUE_COUNT = 3

/** Maximum number of rotation-vector components consumed by Android's matrix conversion. */
private const val MAXIMUM_ROTATION_VECTOR_ORIENTATION_VALUE_COUNT = 4

/** Optional rotation-vector index containing estimated heading error in radians. */
private const val HEADING_ERROR_INDEX = 4

/** Number of X, Y, and Z values copied from each manual-fallback sensor event. */
private const val FALLBACK_VECTOR_VALUE_COUNT = 3

/** Maximum timestamp separation accepted for a fallback acceleration/magnetic sample pair. */
private const val MAX_FALLBACK_SAMPLE_SEPARATION_NANOS = 500_000_000L
