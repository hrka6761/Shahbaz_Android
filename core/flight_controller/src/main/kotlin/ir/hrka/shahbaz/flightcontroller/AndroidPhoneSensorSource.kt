package ir.hrka.shahbaz.flightcontroller

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.shahbaz.flightblackbox.FbbEventRef
import com.shahbaz.flightblackbox.FbbEventType
import com.shahbaz.flightblackbox.FbbPersistence
import com.shahbaz.flightblackbox.FlightBlackBox
import java.io.Closeable
import kotlin.math.sqrt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One Android sensor frame converted into flight-controller input units.
 *
 * @property timestampNanos Android sensor-event timestamp in nanoseconds.
 * @property snapshot Latest merged phone-sensor values after applying this event.
 */
data class AndroidPhoneSensorFrame(
    val timestampNanos: Long,
    val snapshot: PhoneSensorSnapshot,
)

/**
 * Physical phone mounting transform used by [AndroidPhoneSensorSource].
 *
 * @property bodyFromDeviceRotation Rotation from Android device axes (X right, Y toward the phone
 * top, Z out of the screen) into aircraft body FRD axes (X forward, Y right, Z down).
 */
data class AndroidPhoneSensorMounting(
    val bodyFromDeviceRotation: Quaterniond,
) {
    init {
        require(bodyFromDeviceRotation.norm() > 1e-9)
    }

    /** Reviewed mounting presets. */
    companion object {
        /** Phone screen faces up and the top edge points toward the aircraft nose. */
        val SCREEN_UP_TOP_FORWARD = AndroidPhoneSensorMounting(
            Quaterniond.fromAxisAngle(
                axis = Vector3d(1.0, 1.0, 0.0).normalized(),
                angleRadians = kotlin.math.PI,
            ),
        )
    }
}

/**
 * Android `SensorManager` adapter for the flight-controller module.
 *
 * The source registers the phone accelerometer, gyroscope, magnetometer, fused rotation vector,
 * and pressure sensor when each sensor exists. It publishes a merged [PhoneSensorSnapshot] because
 * Android delivers those sensors as independent event streams.
 *
 * @param context Android context used only to obtain the application [SensorManager].
 * @property samplingPeriodUs Android sensor sampling period passed to `registerListener`.
 * @property mounting Physical device-to-body transform. The default requires the screen-up,
 * top-forward mounting described by [AndroidPhoneSensorMounting.SCREEN_UP_TOP_FORWARD].
 */
class AndroidPhoneSensorSource(
    context: Context,
    private val samplingPeriodUs: Int = SensorManager.SENSOR_DELAY_GAME,
    private val mounting: AndroidPhoneSensorMounting =
        AndroidPhoneSensorMounting.SCREEN_UP_TOP_FORWARD,
) : Closeable {
    private val sensorManager =
        context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val mutableFrame = MutableStateFlow(
        AndroidPhoneSensorFrame(0L, PhoneSensorSnapshot()),
    )
    private var latest = PhoneSensorSnapshot()
    private var started = false
    private var lastEvent: FbbEventRef? = null

    /** Latest merged phone-sensor frame. The initial value has timestamp `0` and empty sensors. */
    val frame: StateFlow<AndroidPhoneSensorFrame> = mutableFrame.asStateFlow()

    /** Receives raw Android sensor callbacks and updates [frame] with neutral flight units. */
    private val listener = object : SensorEventListener {
        /** Converts one Android sensor event into the corresponding [PhoneSensorSnapshot] field. */
        override fun onSensorChanged(event: SensorEvent) {
            if (event.accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) return
            if (event.values.isEmpty() || event.values.any { !it.isFinite() }) return
            if (event.sensor.type == Sensor.TYPE_PRESSURE && event.values[0] <= 0f) return
            val timestampedBodyVector = { values: FloatArray ->
                TimedSensorValue(
                    value = mounting.bodyFromDeviceRotation.rotate(
                        Vector3d(
                            values[0].toDouble(),
                            values[1].toDouble(),
                            values[2].toDouble(),
                        ),
                    ),
                    timestampNanos = event.timestamp,
                )
            }
            latest = when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> latest.copy(
                    accelerationBodyMps2 = timestampedBodyVector(event.values),
                )
                Sensor.TYPE_GYROSCOPE -> latest.copy(
                    angularVelocityBodyRadPerSecond = timestampedBodyVector(event.values),
                )
                Sensor.TYPE_MAGNETIC_FIELD -> latest.copy(
                    magneticFieldBodyMicroTesla = timestampedBodyVector(event.values),
                )
                Sensor.TYPE_ROTATION_VECTOR -> {
                    val q = FloatArray(4)
                    SensorManager.getQuaternionFromVector(q, event.values)
                    val enuFromDevice = Quaterniond(
                        w = q[0].toDouble(),
                        x = q[1].toDouble(),
                        y = q[2].toDouble(),
                        z = q[3].toDouble(),
                    ).normalized()
                    latest.copy(
                        attitudeBodyToNed = TimedSensorValue(
                            value = (
                                NED_FROM_ENU_ROTATION *
                                    enuFromDevice *
                                    mounting.bodyFromDeviceRotation.inverse()
                                ).normalized(),
                            timestampNanos = event.timestamp,
                        ),
                    )
                }
                Sensor.TYPE_PRESSURE -> latest.copy(
                    pressureHectopascal = TimedSensorValue(
                        event.values[0].toDouble(),
                        event.timestamp,
                    ),
                )
                else -> latest
            }
            mutableFrame.value = AndroidPhoneSensorFrame(event.timestamp, latest)
        }

        /** Accuracy changes are intentionally ignored; consumers can add policy above this source. */
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    /** Registers all supported phone sensors and records the result in the flight black box. */
    @Synchronized
    fun start() {
        if (started) return
        started = true
        latest = PhoneSensorSnapshot()
        mutableFrame.value = AndroidPhoneSensorFrame(0L, latest)
        lastEvent = FlightBlackBox.record(
            type = FbbEventType.LIFECYCLE,
            description = "AndroidPhoneSensorSource.start()",
            cause = lastEvent,
            persistence = FbbPersistence.IMPORTANT,
        )
        register(Sensor.TYPE_ACCELEROMETER)
        register(Sensor.TYPE_GYROSCOPE)
        register(Sensor.TYPE_MAGNETIC_FIELD)
        register(Sensor.TYPE_ROTATION_VECTOR)
        register(Sensor.TYPE_PRESSURE)
    }

    /** Unregisters the Android listener and records source shutdown. */
    @Synchronized
    override fun close() {
        if (!started) return
        started = false
        sensorManager.unregisterListener(listener)
        lastEvent = FlightBlackBox.record(
            type = FbbEventType.LIFECYCLE,
            description = "AndroidPhoneSensorSource.close()",
            cause = lastEvent,
            persistence = FbbPersistence.IMPORTANT,
        )
    }

    /** Registers a single Android sensor [type] when present. */
    private fun register(type: Int) {
        val sensor = sensorManager.getDefaultSensor(type) ?: return
        val registered = sensorManager.registerListener(listener, sensor, samplingPeriodUs)
        FlightBlackBox.record(
            type = if (registered) FbbEventType.VALUE else FbbEventType.WARNING,
            description = "Android phone sensor registration",
            cause = lastEvent,
            metadata = mapOf("sensorType" to type, "registered" to registered),
            persistence = if (registered) FbbPersistence.NORMAL else FbbPersistence.IMPORTANT,
        )
    }
}

/** Rotation from Android ENU world coordinates to local NED coordinates. */
private val NED_FROM_ENU_ROTATION = Quaterniond.fromAxisAngle(
    axis = Vector3d(1.0 / sqrt(2.0), 1.0 / sqrt(2.0), 0.0),
    angleRadians = kotlin.math.PI,
)
