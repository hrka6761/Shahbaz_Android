package ir.hrka.shahbaz.flightcontroller

import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Immutable three-axis vector used throughout the flight controller.
 *
 * The meaning of each axis depends on the owning model. Local-position values use NED
 * coordinates, while angular-rate and torque values use body axes.
 *
 * @property x First axis component.
 * @property y Second axis component.
 * @property z Third axis component.
 */
data class Vector3d(
    /**
     * Exposes the x value.
     */
    val x: Double,
    /**
     * Exposes the y value.
     */
    val y: Double,
    /**
     * Exposes the z value.
     */
    val z: Double,
) {
    init {
        require(x.isFinite() && y.isFinite() && z.isFinite())
    }

    /** Adds each component of [other] to this vector. */
    operator fun plus(other: Vector3d) = Vector3d(x + other.x, y + other.y, z + other.z)

    /** Subtracts each component of [other] from this vector. */
    operator fun minus(other: Vector3d) = Vector3d(x - other.x, y - other.y, z - other.z)

    /** Returns the same vector pointing in the opposite direction. */
    operator fun unaryMinus() = Vector3d(-x, -y, -z)

    /** Multiplies every component by [scale]. */
    operator fun times(scale: Double) = Vector3d(x * scale, y * scale, z * scale)

    /** Divides every component by [scale]. */
    operator fun div(scale: Double) = Vector3d(x / scale, y / scale, z / scale)

    /** Returns the scalar dot product with [other]. */
    fun dot(other: Vector3d): Double = x * other.x + y * other.y + z * other.z

    /** Returns the vector cross product with [other]. */
    fun cross(other: Vector3d): Vector3d = Vector3d(
        y * other.z - z * other.y,
        z * other.x - x * other.z,
        x * other.y - y * other.x,
    )

    /** Returns the Euclidean length of this vector. */
    fun norm(): Double = sqrt(dot(this))

    /** Returns the largest absolute component. */
    fun maxAbsComponent(): Double = max(abs(x), max(abs(y), abs(z)))

    /** Returns a unit-length copy, or [ZERO] when this vector is too close to zero. */
    fun normalized(): Vector3d {
        val n = norm()
        return if (n > 1e-9) this / n else ZERO
    }

    /** Clamps every component symmetrically to the absolute limits in [maximum]. */
    fun clampAbs(maximum: Vector3d): Vector3d = Vector3d(
        x.coerceIn(-maximum.x, maximum.x),
        y.coerceIn(-maximum.y, maximum.y),
        z.coerceIn(-maximum.z, maximum.z),
    )

    /** Clamps every component between the corresponding [minimum] and [maximum] values. */
    fun clamp(minimum: Vector3d, maximum: Vector3d): Vector3d = Vector3d(
        x.coerceIn(minimum.x, maximum.x),
        y.coerceIn(minimum.y, maximum.y),
        z.coerceIn(minimum.z, maximum.z),
    )

    /** Common zero/down vector constants used by estimators and controllers. */
    companion object {
        /** Zero vector on all axes. */
        val ZERO = Vector3d(0.0, 0.0, 0.0)

        /** Unit vector pointing down in NED coordinates. */
        val DOWN = Vector3d(0.0, 0.0, 1.0)
    }
}

/** Multiplies [vector] by this scalar. */
operator fun Double.times(vector: Vector3d): Vector3d = vector * this

/**
 * Unit quaternion representing attitude or rotation.
 *
 * The public factory methods normalize their results. Constructor values are only checked for
 * finiteness because tests and adapters sometimes need to normalize external sensor data
 * explicitly.
 *
 * @property w Scalar component.
 * @property x First vector component.
 * @property y Second vector component.
 * @property z Third vector component.
 */
data class Quaterniond(
    /**
     * Exposes the w value.
     */
    val w: Double,
    /**
     * Exposes the x value.
     */
    val x: Double,
    /**
     * Exposes the y value.
     */
    val y: Double,
    /**
     * Exposes the z value.
     */
    val z: Double,
) {
    init {
        require(w.isFinite() && x.isFinite() && y.isFinite() && z.isFinite())
    }

    /** Hamilton product that composes this rotation with [other]. */
    operator fun times(other: Quaterniond): Quaterniond = Quaterniond(
        w = w * other.w - x * other.x - y * other.y - z * other.z,
        x = w * other.x + x * other.w + y * other.z - z * other.y,
        y = w * other.y - x * other.z + y * other.w + z * other.x,
        z = w * other.z + x * other.y - y * other.x + z * other.w,
    )

    /** Returns a unit-length quaternion, or [IDENTITY] when the magnitude is too small. */
    fun normalized(): Quaterniond {
        val n = norm()
        return if (n > 1e-9) Quaterniond(w / n, x / n, y / n, z / n) else IDENTITY
    }

    /** Returns the quaternion magnitude. */
    fun norm(): Double = sqrt(w * w + x * x + y * y + z * z)

    /** Returns the conjugate quaternion. For a unit quaternion, this is the inverse rotation. */
    fun conjugate(): Quaterniond = Quaterniond(w, -x, -y, -z)

    /** Returns the inverse rotation represented by this quaternion. */
    fun inverse(): Quaterniond = conjugate().normalized()

    /** Rotates [vector] by this quaternion and returns the rotated vector. */
    fun rotate(vector: Vector3d): Vector3d {
        val q = this.normalized()
        val result = q * Quaterniond(0.0, vector.x, vector.y, vector.z) * q.conjugate()
        return Vector3d(result.x, result.y, result.z)
    }

    /** Converts this quaternion to roll, pitch, and yaw Euler angles in radians. */
    fun toEuler(): EulerAngles {
        val q = normalized()
        val sinRoll = 2.0 * (q.w * q.x + q.y * q.z)
        val cosRoll = 1.0 - 2.0 * (q.x * q.x + q.y * q.y)
        val roll = atan2(sinRoll, cosRoll)

        val sinPitch = 2.0 * (q.w * q.y - q.z * q.x)
        val pitch = if (sinPitch >= 1.0) {
            PI / 2.0
        } else if (sinPitch <= -1.0) {
            -PI / 2.0
        } else {
            kotlin.math.asin(sinPitch)
        }

        val sinYaw = 2.0 * (q.w * q.z + q.x * q.y)
        val cosYaw = 1.0 - 2.0 * (q.y * q.y + q.z * q.z)
        return EulerAngles(roll, pitch, atan2(sinYaw, cosYaw))
    }

    /** Quaternion constants and construction helpers. */
    companion object {
        /** Identity rotation. */
        val IDENTITY = Quaterniond(1.0, 0.0, 0.0, 0.0)

        /** Builds a normalized quaternion from roll, pitch, and yaw angles in radians. */
        fun fromEuler(rollRadians: Double, pitchRadians: Double, yawRadians: Double): Quaterniond {
            val cr = cos(rollRadians * 0.5)
            val sr = sin(rollRadians * 0.5)
            val cp = cos(pitchRadians * 0.5)
            val sp = sin(pitchRadians * 0.5)
            val cy = cos(yawRadians * 0.5)
            val sy = sin(yawRadians * 0.5)
            return Quaterniond(
                w = cr * cp * cy + sr * sp * sy,
                x = sr * cp * cy - cr * sp * sy,
                y = cr * sp * cy + sr * cp * sy,
                z = cr * cp * sy - sr * sp * cy,
            ).normalized()
        }

        /** Builds a normalized quaternion from [axis] and [angleRadians]. */
        fun fromAxisAngle(axis: Vector3d, angleRadians: Double): Quaterniond {
            val unit = axis.normalized()
            if (unit == Vector3d.ZERO || angleRadians == 0.0) return IDENTITY
            val half = angleRadians * 0.5
            val s = sin(half)
            return Quaterniond(cos(half), unit.x * s, unit.y * s, unit.z * s).normalized()
        }

        /**
         * Builds the incremental body-frame rotation caused by [bodyRatesRadPerSecond] over
         * [dtSeconds].
         */
        fun fromAngularVelocity(bodyRatesRadPerSecond: Vector3d, dtSeconds: Double): Quaterniond {
            require(dtSeconds.isFinite() && dtSeconds >= 0.0)
            val angle = bodyRatesRadPerSecond.norm() * dtSeconds
            return if (angle < 1e-9) IDENTITY else fromAxisAngle(bodyRatesRadPerSecond, angle)
        }

        /**
         * Builds a body-to-reference quaternion from a right-handed 3x3 rotation matrix.
         *
         * Matrix values are supplied in row-major order. The matrix is expected to be
         * orthonormal; the returned quaternion is normalized to absorb small numerical error.
         */
        fun fromRotationMatrix(rowMajor: DoubleArray): Quaterniond {
            require(rowMajor.size == 9)
            require(rowMajor.all(Double::isFinite))
            val m00 = rowMajor[0]
            val m11 = rowMajor[4]
            val m22 = rowMajor[8]
            val trace = m00 + m11 + m22
            val quaternion = when {
                trace > 0.0 -> {
                    val s = sqrt(trace + 1.0) * 2.0
                    Quaterniond(
                        w = 0.25 * s,
                        x = (rowMajor[7] - rowMajor[5]) / s,
                        y = (rowMajor[2] - rowMajor[6]) / s,
                        z = (rowMajor[3] - rowMajor[1]) / s,
                    )
                }
                m00 > m11 && m00 > m22 -> {
                    val s = sqrt(1.0 + m00 - m11 - m22) * 2.0
                    Quaterniond(
                        w = (rowMajor[7] - rowMajor[5]) / s,
                        x = 0.25 * s,
                        y = (rowMajor[1] + rowMajor[3]) / s,
                        z = (rowMajor[2] + rowMajor[6]) / s,
                    )
                }
                m11 > m22 -> {
                    val s = sqrt(1.0 + m11 - m00 - m22) * 2.0
                    Quaterniond(
                        w = (rowMajor[2] - rowMajor[6]) / s,
                        x = (rowMajor[1] + rowMajor[3]) / s,
                        y = 0.25 * s,
                        z = (rowMajor[5] + rowMajor[7]) / s,
                    )
                }
                else -> {
                    val s = sqrt(1.0 + m22 - m00 - m11) * 2.0
                    Quaterniond(
                        w = (rowMajor[3] - rowMajor[1]) / s,
                        x = (rowMajor[2] + rowMajor[6]) / s,
                        y = (rowMajor[5] + rowMajor[7]) / s,
                        z = 0.25 * s,
                    )
                }
            }
            return quaternion.normalized()
        }

        /** Returns the shortest rotation from vector [from] to vector [to]. */
        fun shortestArc(from: Vector3d, to: Vector3d): Quaterniond {
            val a = from.normalized()
            val b = to.normalized()
            val dot = a.dot(b).coerceIn(-1.0, 1.0)
            if (dot > 1.0 - 1e-9) return IDENTITY
            if (dot < -1.0 + 1e-9) {
                val axis = if (kotlin.math.abs(a.x) < 0.9) a.cross(Vector3d(1.0, 0.0, 0.0)) else
                    a.cross(Vector3d(0.0, 1.0, 0.0))
                return fromAxisAngle(axis, PI)
            }
            val axis = a.cross(b)
            return fromAxisAngle(axis, acos(dot))
        }
    }
}

/**
 * Roll, pitch, and yaw angles in radians.
 *
 * @property rollRadians Rotation around the body X axis.
 * @property pitchRadians Rotation around the body Y axis.
 * @property yawRadians Heading rotation around the body Z/down axis.
 */
data class EulerAngles(
    /**
     * Exposes the rollRadians value.
     */
    val rollRadians: Double,
    /**
     * Exposes the pitchRadians value.
     */
    val pitchRadians: Double,
    /**
     * Exposes the yawRadians value.
     */
    val yawRadians: Double,
) {
    init {
        require(rollRadians.isFinite() && pitchRadians.isFinite() && yawRadians.isFinite())
    }
}

/**
 * Geographic fix used to initialize and update local NED position.
 *
 * @property latitudeDegrees WGS-84 latitude in decimal degrees.
 * @property longitudeDegrees WGS-84 longitude in decimal degrees.
 * @property altitudeAboveMeanSeaLevelMeters Optional WGS-84 altitude above mean sea level in
 * meters.
 */
data class GeoPoint(
    /**
     * Exposes the latitudeDegrees value.
     */
    val latitudeDegrees: Double,
    /**
     * Exposes the longitudeDegrees value.
     */
    val longitudeDegrees: Double,
    /**
     * Exposes the altitudeAboveMeanSeaLevelMeters value.
     */
    val altitudeAboveMeanSeaLevelMeters: Double? = null,
) {
    init {
        require(latitudeDegrees.isFinite() && latitudeDegrees in -90.0..90.0)
        require(longitudeDegrees.isFinite() && longitudeDegrees in -180.0..180.0)
        require(
            altitudeAboveMeanSeaLevelMeters == null ||
                altitudeAboveMeanSeaLevelMeters.isFinite(),
        )
    }
}

/**
 * One value paired with its monotonic acquisition timestamp.
 *
 * All timestamps supplied to one [FlightController] instance must use the same elapsed-realtime
 * clock as [FlightControllerInput.timestampNanos].
 *
 * @property value Acquired value in the units documented by the owning property.
 * @property timestampNanos Monotonic acquisition timestamp in nanoseconds.
 */
data class TimedSensorValue<out T>(
    /**
     * Exposes the value value.
     */
    val value: T,
    /**
     * Exposes the timestampNanos value.
     */
    val timestampNanos: Long,
) {
    init {
        require(timestampNanos >= 0)
    }
}

/**
 * Latest Android-device sensor values made available to the controller.
 *
 * Null fields mean that the source is unavailable or has not produced a sample yet. Every value
 * carries its own acquisition timestamp because Android sensors arrive on independent streams.
 * Vectors use aircraft body FRD axes: X forward, Y right, Z down. Attitude rotates body FRD into
 * local NED. An acquisition adapter must apply the physical phone-mount transform before creating
 * this model.
 *
 * @property accelerationBodyMps2 Specific-force vector in body FRD, meters per second squared.
 * @property angularVelocityBodyRadPerSecond Body FRD angular rate in radians per second.
 * @property magneticFieldBodyMicroTesla Magnetic-field vector in body FRD, microtesla.
 * @property attitudeBodyToNed Fused body-FRD-to-local-NED attitude quaternion.
 * @property pressureHectopascal Android barometer pressure in hectopascals.
 * @property location Optional phone location owned by the app layer.
 */
data class PhoneSensorSnapshot(
    /**
     * Exposes the accelerationBodyMps2 value.
     */
    val accelerationBodyMps2: TimedSensorValue<Vector3d>? = null,
    /**
     * Exposes the angularVelocityBodyRadPerSecond value.
     */
    val angularVelocityBodyRadPerSecond: TimedSensorValue<Vector3d>? = null,
    /**
     * Exposes the magneticFieldBodyMicroTesla value.
     */
    val magneticFieldBodyMicroTesla: TimedSensorValue<Vector3d>? = null,
    /**
     * Exposes the attitudeBodyToNed value.
     */
    val attitudeBodyToNed: TimedSensorValue<Quaterniond>? = null,
    /**
     * Exposes the pressureHectopascal value.
     */
    val pressureHectopascal: TimedSensorValue<Double>? = null,
    /**
     * Exposes the location value.
     */
    val location: TimedSensorValue<GeoPoint>? = null,
) {
    init {
        require(pressureHectopascal == null || pressureHectopascal.value.isFinite())
        require(pressureHectopascal == null || pressureHectopascal.value > 0.0)
        require(attitudeBodyToNed == null || attitudeBodyToNed.value.norm() > 1e-9)
    }
}

/**
 * Latest external sensor values supplied by the Shahbaz interface board.
 *
 * @property temperatureCelsius SHT30 ambient temperature, when available.
 * @property relativeHumidityPercent SHT30 relative humidity percentage, when available.
 * @property pressurePascal MS5611 pressure in pascals, when available.
 * @property altitudeAboveMeanSeaLevelMeters Barometric altitude derived from board pressure and QNH.
 */
data class ExternalSensorSnapshot(
    /**
     * Exposes the temperatureCelsius value.
     */
    val temperatureCelsius: TimedSensorValue<Double>? = null,
    /**
     * Exposes the relativeHumidityPercent value.
     */
    val relativeHumidityPercent: TimedSensorValue<Double>? = null,
    /**
     * Exposes the pressurePascal value.
     */
    val pressurePascal: TimedSensorValue<Int>? = null,
    /**
     * Exposes the altitudeAboveMeanSeaLevelMeters value.
     */
    val altitudeAboveMeanSeaLevelMeters: TimedSensorValue<Double>? = null,
) {
    init {
        require(temperatureCelsius == null || temperatureCelsius.value.isFinite())
        require(
            relativeHumidityPercent == null ||
                relativeHumidityPercent.value.isFinite() &&
                relativeHumidityPercent.value in 0.0..100.0,
        )
        require(pressurePascal == null || pressurePascal.value > 0)
        require(
            altitudeAboveMeanSeaLevelMeters == null ||
                altitudeAboveMeanSeaLevelMeters.value.isFinite(),
        )
    }
}

/**
 * Flight-relevant state of the Shahbaz interface board.
 *
 * @property ready True only after USB, TimeSync, DeviceInfo, heartbeat, and telemetry start succeed.
 * @property actuatorAvailable True when firmware reports initialized physical actuator output.
 * @property activeMotorChannels Number of currently active board motor outputs.
 * @property actuatorArmed Last confirmed board actuator-arming state.
 * @property observedAtNanos Monotonic time at which readiness was observed.
 * @property actuatorStateObservedAtNanos Monotonic receipt time of [actuatorArmed], or null before
 * the first device-status sample.
 */
data class FlightControllerBoardState(
    /**
     * Exposes the ready value.
     */
    val ready: Boolean = false,
    /**
     * Exposes the actuatorAvailable value.
     */
    val actuatorAvailable: Boolean = false,
    /**
     * Exposes the activeMotorChannels value.
     */
    val activeMotorChannels: Int = 0,
    /**
     * Exposes the actuatorArmed value.
     */
    val actuatorArmed: Boolean = false,
    /**
     * Exposes the observedAtNanos value.
     */
    val observedAtNanos: Long = 0L,
    /**
     * Exposes the actuatorStateObservedAtNanos value.
     */
    val actuatorStateObservedAtNanos: Long? = null,
) {
    init {
        require(activeMotorChannels >= 0)
        require(observedAtNanos >= 0)
        require(actuatorStateObservedAtNanos == null || actuatorStateObservedAtNanos >= 0)
    }
}

/**
 * Complete input consumed by one controller iteration.
 *
 * @property timestampNanos Monotonic timestamp for this input frame.
 * @property phone Android-device sensor snapshot.
 * @property external Shahbaz interface-board sensor snapshot.
 * @property board Flight-relevant board readiness and actuator state.
 */
data class FlightControllerInput(
    /**
     * Exposes the timestampNanos value.
     */
    val timestampNanos: Long,
    /**
     * Exposes the phone value.
     */
    val phone: PhoneSensorSnapshot = PhoneSensorSnapshot(),
    /**
     * Exposes the external value.
     */
    val external: ExternalSensorSnapshot = ExternalSensorSnapshot(),
    /**
     * Exposes the board value.
     */
    val board: FlightControllerBoardState = FlightControllerBoardState(),
) {
    init {
        require(timestampNanos >= 0)
    }
}

/**
 * Fresh, externally supplied control command.
 *
 * A pilot source must periodically replace or refresh its command. Reusing [sequence] is allowed
 * only while every command field remains equal. A greater sequence replaces the previous target;
 * an older or conflicting sequence is rejected. The controller never selects a replacement target.
 *
 * @property sequence Monotonically increasing command sequence within the current controller run.
 * @property issuedAtNanos Monotonic creation timestamp in nanoseconds.
 * @property validUntilNanos Inclusive monotonic deadline after which the command is rejected.
 * @property setpoint Desired low-level aircraft state or motion.
 */
data class FlightControlCommand(
    /**
     * Exposes the sequence value.
     */
    val sequence: Long,
    /**
     * Exposes the issuedAtNanos value.
     */
    val issuedAtNanos: Long,
    /**
     * Exposes the validUntilNanos value.
     */
    val validUntilNanos: Long,
    /**
     * Exposes the setpoint value.
     */
    val setpoint: FlightControlSetpoint,
) {
    init {
        require(sequence >= 0)
        require(issuedAtNanos >= 0)
        require(validUntilNanos >= issuedAtNanos)
    }

    /** Returns the command validity duration without overflowing. */
    val validityDurationNanos: Long
        get() = validUntilNanos - issuedAtNanos

    /** Factory for a bounded command validity duration. */
    companion object {
        /** Creates a command valid for [validForNanos] after [issuedAtNanos]. */
        fun validFor(
            sequence: Long,
            issuedAtNanos: Long,
            validForNanos: Long,
            setpoint: FlightControlSetpoint,
        ): FlightControlCommand {
            require(validForNanos >= 0)
            require(issuedAtNanos <= Long.MAX_VALUE - validForNanos)
            return FlightControlCommand(
                sequence = sequence,
                issuedAtNanos = issuedAtNanos,
                validUntilNanos = issuedAtNanos + validForNanos,
                setpoint = setpoint,
            )
        }
    }
}

/**
 * Lifecycle request from the caller for the current controller step.
 *
 * These values only control arming and safety state. They intentionally do not include mission
 * verbs such as takeoff, landing, return, waypoint, or route following; a pilot UI or future
 * autopilot module must translate those goals into explicit [FlightControlSetpoint] values.
 */
enum class FlightControllerLifecycleRequest {
    /** Keep the vehicle disarmed and output stopped motor pulses. */
    HOLD_DISARMED,

    /** Attempt to arm if health checks pass. */
    ARM,

    /** Run the supplied control target while maintaining the current arming state. */
    RUN,

    /** Disarm and command the board to stop actuator output. */
    DISARM,

    /** Latch emergency stop and command the board emergency-stop override. */
    EMERGENCY_STOP,
}

/**
 * Normalized collective thrust command.
 *
 * The value represents the caller's desired collective actuator level before the controller applies
 * configured armed-flight bounds. Disarming is represented by [FlightControllerLifecycleRequest],
 * not by sending zero thrust while armed.
 *
 * @property normalized Unitless collective thrust in the closed range 0.0..1.0.
 */
data class CollectiveThrust(
    /**
     * Exposes the normalized value.
     */
    val normalized: Double,
) {
    init {
        require(normalized.isFinite() && normalized in 0.0..1.0)
    }
}

/**
 * External control target consumed by the flight controller for one loop iteration.
 *
 * This sealed hierarchy is the module boundary between an operator/autopilot that decides what
 * should happen and this controller, which decides how motor outputs should track that target.
 */
sealed interface FlightControlSetpoint

/**
 * Direct body-rate target for the innermost angular-rate controller.
 *
 * @property bodyRatesRadPerSecond Desired roll, pitch, and yaw rates in body axes.
 * @property thrust Desired normalized collective thrust.
 */
data class RateControlTarget(
    /**
     * Exposes the bodyRatesRadPerSecond value.
     */
    val bodyRatesRadPerSecond: Vector3d,
    /**
     * Exposes the thrust value.
     */
    val thrust: CollectiveThrust,
) : FlightControlSetpoint

/**
 * Attitude target for the cascaded attitude and angular-rate controllers.
 *
 * @property attitude Desired vehicle attitude quaternion.
 * @property thrust Desired normalized collective thrust.
 * @property yawRateFeedForwardRadPerSecond Optional yaw-rate feed-forward added after attitude
 * error is converted to body-rate demand.
 */
data class AttitudeControlTarget(
    /**
     * Exposes the attitude value.
     */
    val attitude: Quaterniond,
    /**
     * Exposes the thrust value.
     */
    val thrust: CollectiveThrust,
    /**
     * Exposes the yawRateFeedForwardRadPerSecond value.
     */
    val yawRateFeedForwardRadPerSecond: Double = 0.0,
) : FlightControlSetpoint {
    init {
        require(attitude.norm() > 1e-9)
        require(yawRateFeedForwardRadPerSecond.isFinite())
    }

    /** Factory helpers for common attitude-target construction. */
    companion object {
        /**
         * Builds an attitude target from roll, pitch, and yaw Euler angles in radians.
         *
         * @param rollRadians Desired roll angle.
         * @param pitchRadians Desired pitch angle.
         * @param yawRadians Desired yaw/heading angle.
         * @param thrust Desired normalized collective thrust.
         * @param yawRateFeedForwardRadPerSecond Optional yaw-rate feed-forward.
         */
        fun fromEuler(
            rollRadians: Double,
            pitchRadians: Double,
            yawRadians: Double,
            thrust: CollectiveThrust,
            yawRateFeedForwardRadPerSecond: Double = 0.0,
        ): AttitudeControlTarget = AttitudeControlTarget(
            attitude = Quaterniond.fromEuler(rollRadians, pitchRadians, yawRadians),
            thrust = thrust,
            yawRateFeedForwardRadPerSecond = yawRateFeedForwardRadPerSecond,
        )
    }
}

/**
 * Altitude target relative to the estimator's captured local origin.
 *
 * The caller owns the higher-level reason for this target. For example, a descent, climb, or hover
 * all become explicit altitude and velocity targets before reaching this module.
 *
 * @property altitudeAboveOriginMeters Desired altitude above the captured local origin in meters.
 * @property verticalVelocityFeedForwardMetersPerSecond Desired climb rate feed-forward. Positive
 * values mean climb, negative values mean descent.
 * @property yawNedRadians Optional magnetic-north-referenced NED yaw target. Zero points north and
 * positive values turn clockwise toward east. Null keeps the current estimated heading.
 * @property yawRateFeedForwardRadPerSecond Optional yaw-rate feed-forward in radians per second.
 */
data class AltitudeControlTarget(
    /**
     * Exposes the altitudeAboveOriginMeters value.
     */
    val altitudeAboveOriginMeters: Double,
    /**
     * Exposes the verticalVelocityFeedForwardMetersPerSecond value.
     */
    val verticalVelocityFeedForwardMetersPerSecond: Double = 0.0,
    /**
     * Exposes the yawNedRadians value.
     */
    val yawNedRadians: Double? = null,
    /**
     * Exposes the yawRateFeedForwardRadPerSecond value.
     */
    val yawRateFeedForwardRadPerSecond: Double = 0.0,
) : FlightControlSetpoint {
    init {
        require(altitudeAboveOriginMeters.isFinite())
        require(verticalVelocityFeedForwardMetersPerSecond.isFinite())
        require(yawNedRadians == null || yawNedRadians.isFinite())
        require(yawRateFeedForwardRadPerSecond.isFinite())
    }
}

/**
 * Local NED position target for the outer position loop.
 *
 * @property localPositionNedMeters Desired local north/east/down position in meters.
 * @property localVelocityFeedForwardNedMetersPerSecond Desired local NED velocity feed-forward.
 * @property yawNedRadians Optional magnetic-north-referenced NED yaw target in radians. Null keeps
 * the current estimated heading.
 * @property yawRateFeedForwardRadPerSecond Optional yaw-rate feed-forward in radians per second.
 */
data class PositionControlTarget(
    /**
     * Exposes the localPositionNedMeters value.
     */
    val localPositionNedMeters: Vector3d,
    /**
     * Exposes the localVelocityFeedForwardNedMetersPerSecond value.
     */
    val localVelocityFeedForwardNedMetersPerSecond: Vector3d = Vector3d.ZERO,
    /**
     * Exposes the yawNedRadians value.
     */
    val yawNedRadians: Double? = null,
    /**
     * Exposes the yawRateFeedForwardRadPerSecond value.
     */
    val yawRateFeedForwardRadPerSecond: Double = 0.0,
) : FlightControlSetpoint {
    init {
        require(yawNedRadians == null || yawNedRadians.isFinite())
        require(yawRateFeedForwardRadPerSecond.isFinite())
    }
}

/**
 * Local NED velocity target for the outer velocity loop.
 *
 * @property localVelocityNedMetersPerSecond Desired local north/east/down velocity in m/s.
 * @property yawNedRadians Optional magnetic-north-referenced NED yaw target in radians. Null keeps
 * the current estimated heading.
 * @property yawRateFeedForwardRadPerSecond Optional yaw-rate feed-forward in radians per second.
 */
data class VelocityControlTarget(
    /**
     * Exposes the localVelocityNedMetersPerSecond value.
     */
    val localVelocityNedMetersPerSecond: Vector3d,
    /**
     * Exposes the yawNedRadians value.
     */
    val yawNedRadians: Double? = null,
    /**
     * Exposes the yawRateFeedForwardRadPerSecond value.
     */
    val yawRateFeedForwardRadPerSecond: Double = 0.0,
) : FlightControlSetpoint {
    init {
        require(yawNedRadians == null || yawNedRadians.isFinite())
        require(yawRateFeedForwardRadPerSecond.isFinite())
    }
}

/**
 * Independent proportional gains for roll, pitch, and yaw attitude error.
 *
 * @property roll Roll-axis gain.
 * @property pitch Pitch-axis gain.
 * @property yaw Yaw-axis gain.
 */
data class AxisGains(
    /**
     * Exposes the roll value.
     */
    val roll: Double,
    /**
     * Exposes the pitch value.
     */
    val pitch: Double,
    /**
     * Exposes the yaw value.
     */
    val yaw: Double,
) {
    init {
        require(roll >= 0.0 && pitch >= 0.0 && yaw >= 0.0)
    }

    /** Converts the axis gains to a [Vector3d] in roll/pitch/yaw order. */
    fun asVector(): Vector3d = Vector3d(roll, pitch, yaw)
}

/**
 * PID and feed-forward gains for the angular-rate controller.
 *
 * @property proportional Per-axis proportional gains.
 * @property integral Per-axis integral gains.
 * @property derivative Per-axis derivative gains applied to measured angular acceleration.
 * @property feedForward Per-axis feed-forward gains from requested body rates.
 * @property integralLimit Per-axis absolute limits for the accumulated integral term.
 */
data class RatePidGains(
    /**
     * Exposes the proportional value.
     */
    val proportional: Vector3d = Vector3d(0.16, 0.16, 0.12),
    /**
     * Exposes the integral value.
     */
    val integral: Vector3d = Vector3d(0.08, 0.08, 0.04),
    /**
     * Exposes the derivative value.
     */
    val derivative: Vector3d = Vector3d(0.003, 0.003, 0.0),
    /**
     * Exposes the feedForward value.
     */
    val feedForward: Vector3d = Vector3d.ZERO,
    /**
     * Exposes the integralLimit value.
     */
    val integralLimit: Vector3d = Vector3d(0.30, 0.30, 0.20),
) {
    init {
        require(minComponent(proportional) >= 0.0)
        require(minComponent(integral) >= 0.0)
        require(minComponent(derivative) >= 0.0)
        require(minComponent(feedForward) >= 0.0)
        require(minComponent(integralLimit) >= 0.0)
    }
}

/**
 * PID gains for local velocity error inside the position controller.
 *
 * @property proportional Per-axis proportional gains.
 * @property integral Per-axis integral gains.
 * @property derivative Per-axis derivative gains.
 * @property integralLimit Per-axis absolute limits for accumulated velocity integral.
 */
data class VelocityPidGains(
    /**
     * Exposes the proportional value.
     */
    val proportional: Vector3d = Vector3d(0.18, 0.18, 0.30),
    /**
     * Exposes the integral value.
     */
    val integral: Vector3d = Vector3d(0.04, 0.04, 0.08),
    /**
     * Exposes the derivative value.
     */
    val derivative: Vector3d = Vector3d(0.02, 0.02, 0.03),
    /**
     * Exposes the integralLimit value.
     */
    val integralLimit: Vector3d = Vector3d(0.30, 0.30, 0.40),
) {
    init {
        require(minComponent(proportional) >= 0.0)
        require(minComponent(integral) >= 0.0)
        require(minComponent(derivative) >= 0.0)
        require(minComponent(integralLimit) >= 0.0)
    }
}

/**
 * One motor's contribution signs for quad-X control allocation.
 *
 * @property channel Zero-based motor channel on the Shahbaz interface board.
 * @property rollScale Motor contribution sign/scale for normalized roll torque.
 * @property pitchScale Motor contribution sign/scale for normalized pitch torque.
 * @property yawScale Motor contribution sign/scale for normalized yaw torque.
 * @property label Human-readable position label for diagnostics and documentation.
 */
data class QuadMotorGeometry(
    /**
     * Exposes the channel value.
     */
    val channel: Int,
    /**
     * Exposes the rollScale value.
     */
    val rollScale: Double,
    /**
     * Exposes the pitchScale value.
     */
    val pitchScale: Double,
    /**
     * Exposes the yawScale value.
     */
    val yawScale: Double,
    /**
     * Exposes the label value.
     */
    val label: String,
) {
    init {
        require(channel in 0..255)
        require(rollScale.isFinite() && pitchScale.isFinite() && yawScale.isFinite())
        require(label.isNotBlank())
    }
}

/**
 * Four-motor quad-X allocation layout.
 *
 * @property motors Exactly four motor geometry entries. Channels must be unique.
 */
data class QuadXMotorLayout(
    /**
     * Exposes the motors value.
     */
    val motors: List<QuadMotorGeometry>,
) {
    init {
        require(motors.size == 4)
        require(motors.map { it.channel }.toSet().size == motors.size)
    }

    /** Built-in motor layout matching the module's documented PX4-style quad-X channel order. */
    companion object {
        /** Default quad-X layout: 0 front-right, 1 rear-left, 2 front-left, 3 rear-right. */
        val PX4_QUAD_X = QuadXMotorLayout(
            listOf(
                QuadMotorGeometry(0, rollScale = -1.0, pitchScale = 1.0, yawScale = 1.0, "front-right"),
                QuadMotorGeometry(1, rollScale = 1.0, pitchScale = -1.0, yawScale = 1.0, "rear-left"),
                QuadMotorGeometry(2, rollScale = 1.0, pitchScale = 1.0, yawScale = -1.0, "front-left"),
                QuadMotorGeometry(3, rollScale = -1.0, pitchScale = -1.0, yawScale = -1.0, "rear-right"),
            ),
        )
    }
}

/**
 * Tunable controller, estimator, safety, and PWM settings.
 *
 * @property loopPeriodMillis Nominal loop period used when there is no prior timestamp.
 * @property qnhHectopascal Sea-level pressure reference used to convert pressure to altitude.
 * @property minimumDtSeconds Smallest integration step allowed by the control loop.
 * @property maximumDtSeconds Largest integration step allowed by the control loop.
 * @property staleInputAfterMillis Maximum accepted timestamp gap between input frames.
 * @property criticalSensorMaxAgeMillis Maximum age for attitude and angular-rate samples.
 * @property altitudeSensorMaxAgeMillis Maximum age for pressure/altitude samples.
 * @property positionSensorMaxAgeMillis Maximum age for geographic position samples.
 * @property boardStateMaxAgeMillis Maximum age for board readiness and actuator-state observations.
 * @property maximumCommandLifetimeMillis Longest accepted pilot-command validity interval.
 * @property commandFutureToleranceMillis Allowed positive timestamp skew for a command.
 * @property armingConfirmationTimeoutMillis Maximum wait for board-confirmed actuator arming.
 * @property disarmingConfirmationTimeoutMillis Maximum wait for board-confirmed actuator disarming.
 * @property motorLayout Quad-X motor geometry and board-channel mapping.
 * @property minimumMotorPulseMicros PWM pulse at normalized motor output 0.0.
 * @property maximumMotorPulseMicros PWM pulse at normalized motor output 1.0.
 * @property disarmedMotorPulseMicros PWM pulse emitted while the controller is disarmed.
 * @property minimumFlyingThrottle Lowest normalized throttle sent while armed.
 * @property maximumFlyingThrottle Highest normalized throttle sent while armed.
 * @property hoverThrottle Approximate normalized throttle required for hover.
 * @property maxTiltRadians Absolute roll/pitch attitude limit in radians.
 * @property maxBodyRateRadPerSecond Per-axis body-rate limits in radians per second.
 * @property attitudeGains Proportional attitude gains.
 * @property rateGains Angular-rate PID gains.
 * @property positionGains Position-to-velocity proportional gains.
 * @property velocityGains Velocity PID gains.
 * @property maximumHorizontalVelocityMetersPerSecond Position-loop horizontal speed limit.
 * @property maximumClimbRateMetersPerSecond Position-loop upward speed limit.
 * @property maximumDescentRateMetersPerSecond Position-loop downward speed limit.
 * @property attitudeTargetToleranceRadians Completion tolerance for attitude targets.
 * @property rateTargetToleranceRadPerSecond Completion tolerance for body-rate targets.
 * @property altitudeTargetToleranceMeters Completion tolerance for altitude targets.
 * @property positionTargetToleranceMeters Completion tolerance for local-position targets.
 * @property velocityTargetToleranceMetersPerSecond Completion tolerance for velocity targets.
 */
data class FlightControllerConfig(
    /**
     * Exposes the loopPeriodMillis value.
     */
    val loopPeriodMillis: Long = 10,
    /**
     * Exposes the qnhHectopascal value.
     */
    val qnhHectopascal: Double = 1_013.25,
    /**
     * Exposes the minimumDtSeconds value.
     */
    val minimumDtSeconds: Double = 0.000125,
    /**
     * Exposes the maximumDtSeconds value.
     */
    val maximumDtSeconds: Double = 0.02,
    /**
     * Exposes the staleInputAfterMillis value.
     */
    val staleInputAfterMillis: Long = 250,
    /**
     * Exposes the criticalSensorMaxAgeMillis value.
     */
    val criticalSensorMaxAgeMillis: Long = 100,
    /**
     * Exposes the altitudeSensorMaxAgeMillis value.
     */
    val altitudeSensorMaxAgeMillis: Long = 500,
    /**
     * Exposes the positionSensorMaxAgeMillis value.
     */
    val positionSensorMaxAgeMillis: Long = 1_500,
    /**
     * Exposes the boardStateMaxAgeMillis value.
     */
    val boardStateMaxAgeMillis: Long = 1_500,
    /**
     * Exposes the maximumCommandLifetimeMillis value.
     */
    val maximumCommandLifetimeMillis: Long = 1_000,
    /**
     * Exposes the commandFutureToleranceMillis value.
     */
    val commandFutureToleranceMillis: Long = 5,
    /**
     * Exposes the armingConfirmationTimeoutMillis value.
     */
    val armingConfirmationTimeoutMillis: Long = 2_000,
    /**
     * Exposes the disarmingConfirmationTimeoutMillis value.
     */
    val disarmingConfirmationTimeoutMillis: Long = 2_000,
    /**
     * Exposes the motorLayout value.
     */
    val motorLayout: QuadXMotorLayout = QuadXMotorLayout.PX4_QUAD_X,
    /**
     * Exposes the minimumMotorPulseMicros value.
     */
    val minimumMotorPulseMicros: Int = 1_000,
    /**
     * Exposes the maximumMotorPulseMicros value.
     */
    val maximumMotorPulseMicros: Int = 2_000,
    /**
     * Exposes the disarmedMotorPulseMicros value.
     */
    val disarmedMotorPulseMicros: Int = 1_000,
    /**
     * Exposes the minimumFlyingThrottle value.
     */
    val minimumFlyingThrottle: Double = 0.08,
    /**
     * Exposes the maximumFlyingThrottle value.
     */
    val maximumFlyingThrottle: Double = 0.95,
    /**
     * Exposes the hoverThrottle value.
     */
    val hoverThrottle: Double = 0.50,
    /**
     * Exposes the maxTiltRadians value.
     */
    val maxTiltRadians: Double = 35.0 * PI / 180.0,
    /**
     * Exposes the maxBodyRateRadPerSecond value.
     */
    val maxBodyRateRadPerSecond: Vector3d = Vector3d(
        220.0 * PI / 180.0,
        220.0 * PI / 180.0,
        160.0 * PI / 180.0,
    ),
    /**
     * Exposes the attitudeGains value.
     */
    val attitudeGains: AxisGains = AxisGains(6.5, 6.5, 2.8),
    /**
     * Exposes the rateGains value.
     */
    val rateGains: RatePidGains = RatePidGains(),
    /**
     * Exposes the positionGains value.
     */
    val positionGains: Vector3d = Vector3d(0.9, 0.9, 1.0),
    /**
     * Exposes the velocityGains value.
     */
    val velocityGains: VelocityPidGains = VelocityPidGains(),
    /**
     * Exposes the maximumHorizontalVelocityMetersPerSecond value.
     */
    val maximumHorizontalVelocityMetersPerSecond: Double = 8.0,
    /**
     * Exposes the maximumClimbRateMetersPerSecond value.
     */
    val maximumClimbRateMetersPerSecond: Double = 3.0,
    /**
     * Exposes the maximumDescentRateMetersPerSecond value.
     */
    val maximumDescentRateMetersPerSecond: Double = 2.0,
    /**
     * Exposes the attitudeTargetToleranceRadians value.
     */
    val attitudeTargetToleranceRadians: Double = 2.0 * PI / 180.0,
    /**
     * Exposes the rateTargetToleranceRadPerSecond value.
     */
    val rateTargetToleranceRadPerSecond: Double = 5.0 * PI / 180.0,
    /**
     * Exposes the altitudeTargetToleranceMeters value.
     */
    val altitudeTargetToleranceMeters: Double = 0.25,
    /**
     * Exposes the positionTargetToleranceMeters value.
     */
    val positionTargetToleranceMeters: Double = 0.50,
    /**
     * Exposes the velocityTargetToleranceMetersPerSecond value.
     */
    val velocityTargetToleranceMetersPerSecond: Double = 0.25,
) {
    init {
        require(loopPeriodMillis > 0)
        require(qnhHectopascal in 800.0..1_100.0)
        require(minimumDtSeconds > 0.0 && maximumDtSeconds >= minimumDtSeconds)
        require(staleInputAfterMillis > 0)
        require(criticalSensorMaxAgeMillis > 0)
        require(altitudeSensorMaxAgeMillis > 0)
        require(positionSensorMaxAgeMillis > 0)
        require(boardStateMaxAgeMillis > 0)
        require(maximumCommandLifetimeMillis > 0)
        require(commandFutureToleranceMillis >= 0)
        require(armingConfirmationTimeoutMillis > 0)
        require(disarmingConfirmationTimeoutMillis > 0)
        require(minimumMotorPulseMicros in 1..65_535)
        require(maximumMotorPulseMicros in minimumMotorPulseMicros..65_535)
        require(disarmedMotorPulseMicros in minimumMotorPulseMicros..maximumMotorPulseMicros)
        require(minimumFlyingThrottle in 0.0..1.0)
        require(maximumFlyingThrottle in minimumFlyingThrottle..1.0)
        require(hoverThrottle in minimumFlyingThrottle..maximumFlyingThrottle)
        require(maxTiltRadians in 0.01..(PI / 2.0))
        require(minComponent(maxBodyRateRadPerSecond) > 0.0)
        require(minComponent(positionGains) >= 0.0)
        require(maximumHorizontalVelocityMetersPerSecond > 0.0)
        require(maximumClimbRateMetersPerSecond > 0.0)
        require(maximumDescentRateMetersPerSecond > 0.0)
        require(attitudeTargetToleranceRadians > 0.0)
        require(rateTargetToleranceRadPerSecond > 0.0)
        require(altitudeTargetToleranceMeters > 0.0)
        require(positionTargetToleranceMeters > 0.0)
        require(velocityTargetToleranceMetersPerSecond > 0.0)
    }
}

/** Current arming/safety state held by the flight controller. */
enum class FlightControllerArmingState {
    /** Motors are commanded to disarmed PWM and no flight-control output is sent. */
    DISARMED,

    /** An arm request was emitted and the controller is waiting for board confirmation. */
    ARMING,

    /** The board confirmed arming and the controller may emit motor PWM commands. */
    ARMED,

    /** A disarm request was emitted and motor PWM is suppressed pending fresh board confirmation. */
    DISARMING,

    /** Controller detected a health loss after arming and commands disarm. */
    FAILSAFE,

    /** Emergency stop was requested and remains latched until reset. */
    EMERGENCY_STOPPED,
}

/**
 * Immutable local-NED reference captured by the estimator from its first accepted observations.
 *
 * Autonomous callers must calculate geographic targets from this reference rather than from an
 * earlier UI location fix. Horizontal and vertical references are independent because GPS and
 * barometric samples can become available at different times.
 */
data class LocalNavigationReference(
    val horizontalOrigin: GeoPoint? = null,
    val horizontalOriginObservedAtNanos: Long? = null,
    val altitudeOriginAboveMeanSeaLevelMeters: Double? = null,
    val altitudeOriginObservedAtNanos: Long? = null,
) {
    init {
        require(horizontalOriginObservedAtNanos == null || horizontalOriginObservedAtNanos >= 0L)
        require(altitudeOriginObservedAtNanos == null || altitudeOriginObservedAtNanos >= 0L)
        require(
            altitudeOriginAboveMeanSeaLevelMeters == null ||
                altitudeOriginAboveMeanSeaLevelMeters.isFinite(),
        )
        require((horizontalOrigin == null) == (horizontalOriginObservedAtNanos == null))
        require(
            (altitudeOriginAboveMeanSeaLevelMeters == null) ==
                (altitudeOriginObservedAtNanos == null),
        )
    }
}

/**
 * Estimated vehicle state after one estimator update.
 *
 * @property localReference Immutable horizontal/vertical origins captured by this estimator run.
 * @property attitudeBodyToNed Estimated body-FRD-to-local-NED attitude quaternion.
 * @property angularVelocityBodyRadPerSecond Measured body-FRD angular rates in radians per second.
 * @property localPositionNedMeters Estimated local NED position in meters, when available.
 * @property localVelocityNedMetersPerSecond Estimated local NED velocity in m/s, when available.
 * @property altitudeAboveOriginMeters Estimated altitude above the captured local origin altitude.
 * @property verticalVelocityMetersPerSecond Estimated climb velocity; positive means climb.
 * @property attitudeObservedAtNanos Timestamp of the newest attitude-contributing sample.
 * @property angularVelocityObservedAtNanos Timestamp of the body-rate sample.
 * @property altitudeObservedAtNanos Timestamp of the altitude sample.
 * @property verticalVelocityObservedAtNanos Timestamp of the newest altitude sample used for
 * vertical velocity.
 * @property positionObservedAtNanos Timestamp of the geographic position sample.
 * @property velocityObservedAtNanos Timestamp of the newest sample contributing to local velocity.
 */
data class VehicleStateEstimate(
    /** Local coordinate/altitude references captured by the estimator. */
    val localReference: LocalNavigationReference = LocalNavigationReference(),
    /**
     * Exposes the attitudeBodyToNed value.
     */
    val attitudeBodyToNed: Quaterniond = Quaterniond.IDENTITY,
    /**
     * Exposes the angularVelocityBodyRadPerSecond value.
     */
    val angularVelocityBodyRadPerSecond: Vector3d? = null,
    /**
     * Exposes the localPositionNedMeters value.
     */
    val localPositionNedMeters: Vector3d? = null,
    /**
     * Exposes the localVelocityNedMetersPerSecond value.
     */
    val localVelocityNedMetersPerSecond: Vector3d? = null,
    /**
     * Exposes the altitudeAboveOriginMeters value.
     */
    val altitudeAboveOriginMeters: Double? = null,
    /**
     * Exposes the verticalVelocityMetersPerSecond value.
     */
    val verticalVelocityMetersPerSecond: Double? = null,
    /**
     * Exposes the attitudeObservedAtNanos value.
     */
    val attitudeObservedAtNanos: Long? = null,
    /**
     * Exposes the angularVelocityObservedAtNanos value.
     */
    val angularVelocityObservedAtNanos: Long? = null,
    /**
     * Exposes the altitudeObservedAtNanos value.
     */
    val altitudeObservedAtNanos: Long? = null,
    /**
     * Exposes the verticalVelocityObservedAtNanos value.
     */
    val verticalVelocityObservedAtNanos: Long? = null,
    /**
     * Exposes the positionObservedAtNanos value.
     */
    val positionObservedAtNanos: Long? = null,
    /**
     * Exposes the velocityObservedAtNanos value.
     */
    val velocityObservedAtNanos: Long? = null,
) {
    init {
        require(attitudeBodyToNed.norm() > 1e-9)
        require(altitudeAboveOriginMeters == null || altitudeAboveOriginMeters.isFinite())
        require(verticalVelocityMetersPerSecond == null || verticalVelocityMetersPerSecond.isFinite())
        require(
            listOfNotNull(
                attitudeObservedAtNanos,
                angularVelocityObservedAtNanos,
                altitudeObservedAtNanos,
                verticalVelocityObservedAtNanos,
                positionObservedAtNanos,
                velocityObservedAtNanos,
            ).all { it >= 0 },
        )
    }
}

/** Stable machine-readable reason why control or arming is unavailable. */
enum class FlightControllerHealthIssueCode {
    /** No controller input has been processed. */
    NO_INPUT,

    /** Loop input time did not advance strictly monotonically. */
    INPUT_TIMESTAMP_NOT_MONOTONIC,

    /** Loop input time advanced by more than the configured freshness limit. */
    INPUT_GAP_TOO_LARGE,

    /** No pilot command was supplied. */
    COMMAND_MISSING,

    /** Pilot command creation time is too far in the future. */
    COMMAND_FROM_FUTURE,

    /** Pilot command deadline has passed. */
    COMMAND_EXPIRED,

    /** Pilot command requests a validity interval longer than policy permits. */
    COMMAND_LIFETIME_TOO_LONG,

    /** Pilot command sequence is older than the latest accepted sequence. */
    COMMAND_OUT_OF_ORDER,

    /** Pilot command reused a sequence with different content. */
    COMMAND_SEQUENCE_CONFLICT,

    /** A fresh attitude estimate is unavailable. */
    ATTITUDE_UNAVAILABLE,

    /** A fresh body angular-rate sample is unavailable. */
    ANGULAR_RATE_UNAVAILABLE,

    /** A fresh altitude estimate is unavailable for the requested target. */
    ALTITUDE_UNAVAILABLE,

    /** A vertical-velocity estimate is unavailable for the requested target. */
    VERTICAL_VELOCITY_UNAVAILABLE,

    /** A complete local NED position estimate is unavailable for the requested target. */
    POSITION_UNAVAILABLE,

    /** A complete local NED velocity estimate is unavailable for the requested target. */
    VELOCITY_UNAVAILABLE,

    /** Board readiness observation is stale. */
    BOARD_STATE_STALE,

    /** Board link/session is not ready. */
    BOARD_NOT_READY,

    /** Physical actuator output is unavailable. */
    ACTUATOR_UNAVAILABLE,

    /** Configured Quad-X motor channels are unavailable. */
    MOTOR_CHANNELS_UNAVAILABLE,

    /** Board reports armed while the controller is logically disarmed. */
    ACTUATOR_ARMED_WHILE_DISARMED,

    /** Board did not confirm arming within the configured timeout. */
    ARMING_CONFIRMATION_TIMEOUT,

    /** Board did not confirm disarming within the configured timeout. */
    DISARMING_CONFIRMATION_TIMEOUT,

    /** Board unexpectedly reported disarmed while the controller was armed. */
    ACTUATOR_DISARMED_UNEXPECTEDLY,
}

/**
 * One structured health issue.
 *
 * @property code Stable issue code for programmatic handling.
 * @property message Human-readable diagnostic detail.
 */
data class FlightControllerHealthIssue(
    /**
     * Exposes the code value.
     */
    val code: FlightControllerHealthIssueCode,
    /**
     * Exposes the message value.
     */
    val message: String,
) {
    init {
        require(message.isNotBlank())
    }
}

/**
 * Health gate evaluated before arming and continuously while armed.
 *
 * @property inputFresh True when the current timestamp advanced within the configured loop gap.
 * @property attitudeAvailable True when the estimator has fresh attitude.
 * @property angularRateAvailable True when a fresh gyroscope sample is present.
 * @property boardReady True when the Shahbaz interface board is operational.
 * @property actuatorAvailable True when the board reports physical actuator output available.
 * @property motorChannelsReady True when enough motor channels are active for the configured layout.
 * @property commandAccepted True when command identity and freshness checks passed.
 * @property issues Structured reasons blocking arming or continued actuation.
 */
data class FlightControllerHealth(
    /**
     * Exposes the inputFresh value.
     */
    val inputFresh: Boolean,
    /**
     * Exposes the attitudeAvailable value.
     */
    val attitudeAvailable: Boolean,
    /**
     * Exposes the angularRateAvailable value.
     */
    val angularRateAvailable: Boolean,
    /**
     * Exposes the boardReady value.
     */
    val boardReady: Boolean,
    /**
     * Exposes the actuatorAvailable value.
     */
    val actuatorAvailable: Boolean,
    /**
     * Exposes the motorChannelsReady value.
     */
    val motorChannelsReady: Boolean,
    /**
     * Exposes the commandAccepted value.
     */
    val commandAccepted: Boolean,
    /**
     * Exposes the issues value.
     */
    val issues: List<FlightControllerHealthIssue> = emptyList(),
) {
    /** True when there are no health problems blocking arming or continued actuation. */
    val canArm: Boolean
        get() = issues.isEmpty()
}

/**
 * Intermediate setpoints generated by the cascaded controllers.
 *
 * @property attitude Desired attitude after manual or position-loop processing.
 * @property bodyRatesRadPerSecond Desired body rates in radians per second.
 * @property torqueNormalized Normalized roll/pitch/yaw torque request in the range -1.0..1.0.
 * @property throttle Normalized collective throttle request.
 */
data class ControllerSetpoints(
    /**
     * Exposes the attitude value.
     */
    val attitude: Quaterniond = Quaterniond.IDENTITY,
    /**
     * Exposes the bodyRatesRadPerSecond value.
     */
    val bodyRatesRadPerSecond: Vector3d = Vector3d.ZERO,
    /**
     * Exposes the torqueNormalized value.
     */
    val torqueNormalized: Vector3d = Vector3d.ZERO,
    /**
     * Exposes the throttle value.
     */
    val throttle: Double = 0.0,
) {
    init {
        require(attitude.norm() > 1e-9)
        require(torqueNormalized.maxAbsComponent() <= 1.0)
        require(throttle.isFinite() && throttle in 0.0..1.0)
    }
}

/** Tracking state of the externally supplied primitive. */
enum class ControlTargetStatus {
    /** The command is valid but inactive because the controller is not armed. */
    INACTIVE,

    /** The controller is actively reducing target error. */
    TRACKING,

    /** Target error is within the configured completion tolerance. */
    REACHED,

    /** Required estimated state is unavailable. */
    UNAVAILABLE,

    /** Command identity, ordering, or freshness was rejected. */
    REJECTED,
}

/**
 * Tracking diagnostics produced by one controller step.
 *
 * These values let Autopilot, a pilot UI, or a recorder observe how well the low-level
 * controller is following an externally supplied target without giving this module mission
 * ownership.
 *
 * @property commandSequence Sequence of the command described by this result, when supplied.
 * @property targetStatus Current command tracking/completion state.
 * @property positionErrorNedMeters Target minus estimated local NED position, when position control
 * was active.
 * @property velocityErrorNedMetersPerSecond Target minus estimated local NED velocity, when a
 * position or velocity loop was active.
 * @property altitudeErrorMeters Target altitude minus estimated altitude above origin, when an
 * altitude-bearing target was active.
 * @property attitudeErrorRadians Small-angle quaternion attitude error in roll, pitch, yaw order.
 * @property rateErrorRadPerSecond Target minus measured body-rate error in roll, pitch, yaw order.
 * @property motorOutputSaturated True when motor allocation had to desaturate or clamp a motor.
 */
data class ControlTracking(
    /**
     * Exposes the commandSequence value.
     */
    val commandSequence: Long? = null,
    /**
     * Exposes the targetStatus value.
     */
    val targetStatus: ControlTargetStatus = ControlTargetStatus.INACTIVE,
    /**
     * Exposes the positionErrorNedMeters value.
     */
    val positionErrorNedMeters: Vector3d? = null,
    /**
     * Exposes the velocityErrorNedMetersPerSecond value.
     */
    val velocityErrorNedMetersPerSecond: Vector3d? = null,
    /**
     * Exposes the altitudeErrorMeters value.
     */
    val altitudeErrorMeters: Double? = null,
    /**
     * Exposes the attitudeErrorRadians value.
     */
    val attitudeErrorRadians: Vector3d = Vector3d.ZERO,
    /**
     * Exposes the rateErrorRadPerSecond value.
     */
    val rateErrorRadPerSecond: Vector3d = Vector3d.ZERO,
    /**
     * Exposes the motorOutputSaturated value.
     */
    val motorOutputSaturated: Boolean = false,
)

/**
 * Final normalized and PWM representation for one motor.
 *
 * @property channel Zero-based board motor channel.
 * @property normalized Normalized motor output after allocation and saturation.
 * @property pulseMicros PWM pulse width in microseconds.
 * @property label Human-readable motor location label.
 */
data class NormalizedMotorOutput(
    /**
     * Exposes the channel value.
     */
    val channel: Int,
    /**
     * Exposes the normalized value.
     */
    val normalized: Double,
    /**
     * Exposes the pulseMicros value.
     */
    val pulseMicros: Int,
    /**
     * Exposes the label value.
     */
    val label: String,
) {
    init {
        require(channel in 0..255)
        require(normalized.isFinite() && normalized in 0.0..1.0)
        require(pulseMicros in 1..65_535)
        require(label.isNotBlank())
    }
}

/** Neutral hardware action emitted by the controller for a transport adapter to execute. */
sealed interface FlightControllerActuatorAction {
    /** Request physical actuator arming. */
    data object Arm : FlightControllerActuatorAction

    /** Request immediate physical actuator disarm. */
    data object Disarm : FlightControllerActuatorAction

    /** Request the hardware emergency-stop override. */
    data object EmergencyStop : FlightControllerActuatorAction

    /**
     * Apply one coherent Quad-X motor PWM frame.
     *
     * The hardware adapter must preserve [generatedAtNanos] as the command generation time so a
     * queued stale frame cannot be made fresh during later serialization.
     *
     * @property generatedAtNanos Monotonic controller-output timestamp.
     * @property motors Complete motor frame with unique zero-based channels.
     */
    data class ApplyMotorPwm(
        /**
         * Exposes the generatedAtNanos value.
         */
        val generatedAtNanos: Long,
        /**
         * Exposes the motors value.
         */
        val motors: List<MotorPwmActuatorOutput>,
    ) : FlightControllerActuatorAction {
        init {
            require(generatedAtNanos >= 0)
            require(motors.isNotEmpty())
            require(motors.map { it.channel }.toSet().size == motors.size)
        }
    }
}

/**
 * Hardware-neutral PWM output for one motor actuator.
 *
 * @property channel Zero-based logical motor channel.
 * @property pulseMicros PWM pulse width in microseconds.
 */
data class MotorPwmActuatorOutput(
    /**
     * Exposes the channel value.
     */
    val channel: Int,
    /**
     * Exposes the pulseMicros value.
     */
    val pulseMicros: Int,
) {
    init {
        require(channel in 0..255)
        require(pulseMicros in 1..65_535)
    }
}

/**
 * Complete result of one controller step.
 *
 * @property timestampNanos Input timestamp used for this output.
 * @property armingState Arming/safety state after processing the lifecycle request.
 * @property estimate Estimated vehicle state.
 * @property health Health gate result.
 * @property controllerSetpoints Intermediate controller setpoints.
 * @property tracking Target-tracking diagnostics for the current step.
 * @property motors Per-motor normalized and PWM outputs.
 * @property actuatorActions Neutral actions for a hardware-connection adapter.
 */
data class FlightControllerOutput(
    /**
     * Exposes the timestampNanos value.
     */
    val timestampNanos: Long,
    /**
     * Exposes the armingState value.
     */
    val armingState: FlightControllerArmingState,
    /**
     * Exposes the estimate value.
     */
    val estimate: VehicleStateEstimate,
    /**
     * Exposes the health value.
     */
    val health: FlightControllerHealth,
    /**
     * Exposes the controllerSetpoints value.
     */
    val controllerSetpoints: ControllerSetpoints,
    /**
     * Exposes the tracking value.
     */
    val tracking: ControlTracking,
    /**
     * Exposes the motors value.
     */
    val motors: List<NormalizedMotorOutput>,
    /**
     * Exposes the actuatorActions value.
     */
    val actuatorActions: List<FlightControllerActuatorAction>,
)

/**
 * Latest observable flight-controller state.
 *
 * @property armingState Current arming/safety state.
 * @property estimate Latest vehicle-state estimate.
 * @property health Latest health gate result.
 * @property lastOutput Most recent full step output, or null before the first step.
 */
data class FlightControllerSnapshot(
    /**
     * Exposes the armingState value.
     */
    val armingState: FlightControllerArmingState = FlightControllerArmingState.DISARMED,
    /**
     * Exposes the estimate value.
     */
    val estimate: VehicleStateEstimate = VehicleStateEstimate(),
    /**
     * Exposes the health value.
     */
    val health: FlightControllerHealth = FlightControllerHealth(
        inputFresh = false,
        attitudeAvailable = false,
        angularRateAvailable = false,
        boardReady = false,
        actuatorAvailable = false,
        motorChannelsReady = false,
        commandAccepted = false,
        issues = listOf(
            FlightControllerHealthIssue(
                FlightControllerHealthIssueCode.NO_INPUT,
                "No input has been processed",
            ),
        ),
    ),
    /**
     * Exposes the lastOutput value.
     */
    val lastOutput: FlightControllerOutput? = null,
)

/** Constrains a normalized scalar to the closed range 0.0..1.0. */
internal fun Double.coerceUnit(): Double = coerceFinite(0.0, 1.0)

/** Constrains a finite scalar to [minimum]..[maximum], or returns [minimum] for non-finite values. */
internal fun Double.coerceFinite(minimum: Double, maximum: Double): Double =
    if (isFinite()) coerceIn(minimum, maximum) else minimum

/** Converts a normalized motor value to a PWM pulse in microseconds. */
internal fun Double.mixToPulse(minimumMicros: Int, maximumMicros: Int): Int {
    val normalized = coerceUnit()
    return (minimumMicros + normalized * (maximumMicros - minimumMicros)).toInt()
}

/** Returns the largest component in [vector]. */
internal fun maxComponent(vector: Vector3d): Double = max(vector.x, max(vector.y, vector.z))

/** Returns the smallest component in [vector]. */
internal fun minComponent(vector: Vector3d): Double = min(vector.x, min(vector.y, vector.z))
