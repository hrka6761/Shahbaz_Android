package ir.hrka.shahbaz.flightcontracts

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Finite three-axis value. Local navigation uses north/east/down coordinates. */
data class Vector3d(val x: Double, val y: Double, val z: Double) {
    init {
        require(x.isFinite() && y.isFinite() && z.isFinite())
    }

    operator fun plus(other: Vector3d) = Vector3d(x + other.x, y + other.y, z + other.z)
    operator fun minus(other: Vector3d) = Vector3d(x - other.x, y - other.y, z - other.z)
    operator fun times(scale: Double) = Vector3d(x * scale, y * scale, z * scale)
    operator fun div(scale: Double) = Vector3d(x / scale, y / scale, z / scale)
    fun dot(other: Vector3d): Double = x * other.x + y * other.y + z * other.z
    fun norm(): Double = sqrt(dot(this))

    companion object {
        val ZERO = Vector3d(0.0, 0.0, 0.0)
    }
}

/** Finite body-to-NED attitude quaternion used only by policy-side geometric checks. */
data class Quaterniond(val w: Double, val x: Double, val y: Double, val z: Double) {
    init {
        require(w.isFinite() && x.isFinite() && y.isFinite() && z.isFinite())
    }

    operator fun times(other: Quaterniond) = Quaterniond(
        w = w * other.w - x * other.x - y * other.y - z * other.z,
        x = w * other.x + x * other.w + y * other.z - z * other.y,
        y = w * other.y - x * other.z + y * other.w + z * other.x,
        z = w * other.z + x * other.y - y * other.x + z * other.w,
    )

    fun norm(): Double = sqrt(w * w + x * x + y * y + z * z)
    fun normalized(): Quaterniond {
        val magnitude = norm()
        return if (magnitude > 1e-9) {
            Quaterniond(w / magnitude, x / magnitude, y / magnitude, z / magnitude)
        } else {
            IDENTITY
        }
    }
    fun conjugate() = Quaterniond(w, -x, -y, -z)
    fun rotate(vector: Vector3d): Vector3d {
        val q = normalized()
        val result = q * Quaterniond(0.0, vector.x, vector.y, vector.z) * q.conjugate()
        return Vector3d(result.x, result.y, result.z)
    }

    companion object {
        val IDENTITY = Quaterniond(1.0, 0.0, 0.0, 0.0)

        fun fromEuler(rollRadians: Double, pitchRadians: Double, yawRadians: Double): Quaterniond {
            require(rollRadians.isFinite() && pitchRadians.isFinite() && yawRadians.isFinite())
            val cr = cos(rollRadians / 2.0)
            val sr = sin(rollRadians / 2.0)
            val cp = cos(pitchRadians / 2.0)
            val sp = sin(pitchRadians / 2.0)
            val cy = cos(yawRadians / 2.0)
            val sy = sin(yawRadians / 2.0)
            return Quaterniond(
                w = cr * cp * cy + sr * sp * sy,
                x = sr * cp * cy - cr * sp * sy,
                y = cr * sp * cy + sr * cp * sy,
                z = cr * cp * sy - sr * sp * cy,
            ).normalized()
        }
    }
}

/** WGS-84 point used only as a controller feedback reference. */
data class GeoPoint(val latitudeDegrees: Double, val longitudeDegrees: Double) {
    init {
        require(latitudeDegrees.isFinite() && latitudeDegrees in -90.0..90.0)
        require(longitudeDegrees.isFinite() && longitudeDegrees in -180.0..180.0)
    }
}

enum class FlightControllerArmingState {
    DISARMED,
    ARMING,
    ARMED,
    DISARMING,
    FAILSAFE,
    EMERGENCY_STOPPED,
}

enum class FlightControllerLifecycleRequest {
    HOLD_DISARMED,
    ARM,
    RUN,
    DISARM,
    EMERGENCY_STOP,
}

sealed interface FlightControlSetpoint

data class PositionControlTarget(
    val localPositionNedMeters: Vector3d,
    val localVelocityFeedForwardNedMetersPerSecond: Vector3d = Vector3d.ZERO,
    val yawNedRadians: Double? = null,
    val yawRateFeedForwardRadPerSecond: Double = 0.0,
) : FlightControlSetpoint {
    init {
        require(yawNedRadians == null || yawNedRadians.isFinite())
        require(yawRateFeedForwardRadPerSecond.isFinite())
    }
}

data class FlightControlCommand(
    val sequence: Long,
    val issuedAtNanos: Long,
    val validUntilNanos: Long,
    val setpoint: FlightControlSetpoint,
) {
    init {
        require(sequence >= 0L)
        require(issuedAtNanos >= 0L)
        require(validUntilNanos >= issuedAtNanos)
    }

    val validityDurationNanos: Long
        get() = validUntilNanos - issuedAtNanos

    companion object {
        fun validFor(
            sequence: Long,
            issuedAtNanos: Long,
            validForNanos: Long,
            setpoint: FlightControlSetpoint,
        ): FlightControlCommand {
            require(validForNanos >= 0L)
            require(issuedAtNanos <= Long.MAX_VALUE - validForNanos)
            return FlightControlCommand(
                sequence,
                issuedAtNanos,
                issuedAtNanos + validForNanos,
                setpoint,
            )
        }
    }
}

data class LocalNavigationReference(
    val horizontalOrigin: GeoPoint? = null,
    val horizontalOriginObservedAtNanos: Long? = null,
) {
    init {
        require(horizontalOriginObservedAtNanos == null || horizontalOriginObservedAtNanos >= 0L)
        require((horizontalOrigin == null) == (horizontalOriginObservedAtNanos == null))
    }
}

data class VehicleStateEstimate(
    val localReference: LocalNavigationReference = LocalNavigationReference(),
    val attitudeBodyToNed: Quaterniond = Quaterniond.IDENTITY,
    val angularVelocityBodyRadPerSecond: Vector3d? = null,
    val localPositionNedMeters: Vector3d? = null,
    val localVelocityNedMetersPerSecond: Vector3d? = null,
    val altitudeAboveOriginMeters: Double? = null,
    val verticalVelocityMetersPerSecond: Double? = null,
    val attitudeObservedAtNanos: Long? = null,
    val angularVelocityObservedAtNanos: Long? = null,
    val altitudeObservedAtNanos: Long? = null,
    val verticalVelocityObservedAtNanos: Long? = null,
    val positionObservedAtNanos: Long? = null,
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
            ).all { it >= 0L },
        )
    }
}

data class FlightControllerHealthIssue(val code: String, val message: String) {
    init {
        require(code.isNotBlank())
        require(message.isNotBlank())
    }
}

data class FlightControllerHealth(
    val inputFresh: Boolean = false,
    val attitudeAvailable: Boolean = false,
    val angularRateAvailable: Boolean = false,
    val boardReady: Boolean = false,
    val actuatorAvailable: Boolean = false,
    val motorChannelsReady: Boolean = false,
    val commandAccepted: Boolean = false,
    val issues: List<FlightControllerHealthIssue> = listOf(
        FlightControllerHealthIssue("NO_INPUT", "No input has been processed"),
    ),
) {
    val canArm: Boolean
        get() = issues.isEmpty()
}

enum class ControlTargetStatus {
    INACTIVE,
    TRACKING,
    REACHED,
    UNAVAILABLE,
    REJECTED,
}

data class ControlTracking(
    val commandSequence: Long? = null,
    val targetStatus: ControlTargetStatus = ControlTargetStatus.INACTIVE,
) {
    init {
        require(commandSequence == null || commandSequence >= 0L)
    }
}

data class FlightControllerOutput(val tracking: ControlTracking = ControlTracking())

data class FlightControllerSnapshot(
    val armingState: FlightControllerArmingState = FlightControllerArmingState.DISARMED,
    val estimate: VehicleStateEstimate = VehicleStateEstimate(),
    val health: FlightControllerHealth = FlightControllerHealth(),
    val lastOutput: FlightControllerOutput? = null,
)
