package ir.hrka.shahbaz.flightcontroller

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/** Feedback-cascade family used only to reset state across incompatible target transitions. */
internal enum class ControlKind { RATE, ATTITUDE, ALTITUDE, POSITION, VELOCITY }

/** Returns the feedback-cascade family selected by this target. */
internal fun FlightControlSetpoint.controlKind(): ControlKind = when (this) {
    is RateControlTarget -> ControlKind.RATE
    is AttitudeControlTarget -> ControlKind.ATTITUDE
    is AltitudeControlTarget -> ControlKind.ALTITUDE
    is PositionControlTarget -> ControlKind.POSITION
    is VelocityControlTarget -> ControlKind.VELOCITY
}

/** Internal output of one complete controller-cascade evaluation. */
internal data class ControllerComputation(
    val controllerSetpoints: ControllerSetpoints,
    val tracking: ControlTracking,
)

/** Internal outer-loop result passed into attitude and rate control. */
internal data class PositionControllerResult(
    val target: AttitudeThrottleSetpoint,
    val tracking: ControlTracking,
)

/** Internal attitude/throttle target consumed by attitude and rate control. */
internal data class AttitudeThrottleSetpoint(
    val attitude: Quaterniond,
    val bodyRates: Vector3d?,
    val throttle: Double,
    val yawRateFeedForward: Double = 0.0,
)

/**
 * Lightweight estimator for the sensors currently available to Shahbaz.
 *
 * It accepts only fresh independently timestamped samples, never integrates the same gyroscope
 * sample twice, and differentiates altitude/location using their acquisition timestamps rather
 * than the faster control-loop period. This is deliberately not an EKF.
 */
internal class StateEstimator(
    private val config: FlightControllerConfig,
) {
    private var attitudeBodyToNed = Quaterniond.IDENTITY
    private var attitudeObservedAtNanos: Long? = null
    private var lastGyroscopeTimestampNanos: Long? = null

    private var originAltitudeMslMeters: Double? = null
    private var latestAltitudeAboveOriginMeters: Double? = null
    private var latestAltitudeTimestampNanos: Long? = null
    private var previousAltitudeMslMeters: Double? = null
    private var previousAltitudeTimestampNanos: Long? = null
    private var verticalVelocityMetersPerSecond: Double? = null
    private var verticalVelocityTimestampNanos: Long? = null

    private var originLocation: GeoPoint? = null
    private var latestHorizontalPositionNedMeters: Vector3d? = null
    private var latestLocationTimestampNanos: Long? = null
    private var previousHorizontalPositionNedMeters: Vector3d? = null
    private var previousLocationTimestampNanos: Long? = null
    private var horizontalVelocityNedMetersPerSecond: Vector3d? = null
    private var horizontalVelocityTimestampNanos: Long? = null

    /** Clears attitude, local origin, and differentiated velocity state. */
    fun reset() {
        attitudeBodyToNed = Quaterniond.IDENTITY
        attitudeObservedAtNanos = null
        lastGyroscopeTimestampNanos = null
        originAltitudeMslMeters = null
        latestAltitudeAboveOriginMeters = null
        latestAltitudeTimestampNanos = null
        previousAltitudeMslMeters = null
        previousAltitudeTimestampNanos = null
        verticalVelocityMetersPerSecond = null
        verticalVelocityTimestampNanos = null
        originLocation = null
        latestHorizontalPositionNedMeters = null
        latestLocationTimestampNanos = null
        previousHorizontalPositionNedMeters = null
        previousLocationTimestampNanos = null
        horizontalVelocityNedMetersPerSecond = null
        horizontalVelocityTimestampNanos = null
    }

    /** Updates all observable states from fresh samples in [input]. */
    fun update(input: FlightControllerInput): VehicleStateEstimate {
        updateAttitude(input)
        updateAltitude(input)
        updateHorizontalPosition(input)

        val fullPosition = if (
            latestHorizontalPositionNedMeters != null && latestAltitudeAboveOriginMeters != null
        ) {
            Vector3d(
                latestHorizontalPositionNedMeters!!.x,
                latestHorizontalPositionNedMeters!!.y,
                -latestAltitudeAboveOriginMeters!!,
            )
        } else {
            null
        }
        val fullVelocity = if (
            horizontalVelocityNedMetersPerSecond != null && verticalVelocityMetersPerSecond != null
        ) {
            Vector3d(
                horizontalVelocityNedMetersPerSecond!!.x,
                horizontalVelocityNedMetersPerSecond!!.y,
                -verticalVelocityMetersPerSecond!!,
            )
        } else {
            null
        }
        val positionTimestamp = if (fullPosition != null) {
            minOf(requireNotNull(latestLocationTimestampNanos), requireNotNull(latestAltitudeTimestampNanos))
        } else {
            null
        }
        val velocityTimestamp = if (fullVelocity != null) {
            minOf(
                requireNotNull(horizontalVelocityTimestampNanos),
                requireNotNull(verticalVelocityTimestampNanos),
            )
        } else {
            null
        }
        val gyro = input.phone.angularVelocityBodyRadPerSecond.freshAt(
            input.timestampNanos,
            config.criticalSensorMaxAgeMillis,
        )
        return VehicleStateEstimate(
            attitudeBodyToNed = attitudeBodyToNed,
            angularVelocityBodyRadPerSecond = gyro?.value,
            localPositionNedMeters = fullPosition,
            localVelocityNedMetersPerSecond = fullVelocity,
            altitudeAboveOriginMeters = latestAltitudeAboveOriginMeters,
            verticalVelocityMetersPerSecond = verticalVelocityMetersPerSecond,
            attitudeObservedAtNanos = attitudeObservedAtNanos,
            angularVelocityObservedAtNanos = gyro?.timestampNanos,
            altitudeObservedAtNanos = latestAltitudeTimestampNanos,
            verticalVelocityObservedAtNanos = verticalVelocityTimestampNanos,
            positionObservedAtNanos = positionTimestamp,
            velocityObservedAtNanos = velocityTimestamp,
        )
    }

    /** Updates fused/fallback attitude and integrates each new gyroscope sample at most once. */
    private fun updateAttitude(input: FlightControllerInput) {
        val now = input.timestampNanos
        val absolute = input.phone.attitudeBodyToNed.freshAt(now, config.criticalSensorMaxAgeMillis)
            ?: fallbackAttitude(input)
        if (
            absolute != null &&
            (attitudeObservedAtNanos == null || absolute.timestampNanos >= attitudeObservedAtNanos!!)
        ) {
            attitudeBodyToNed = absolute.value.normalized()
            attitudeObservedAtNanos = absolute.timestampNanos
        }

        val gyro = input.phone.angularVelocityBodyRadPerSecond.freshAt(
            now,
            config.criticalSensorMaxAgeMillis,
        )
        if (gyro != null) {
            val priorTimestamp = lastGyroscopeTimestampNanos
            if (
                attitudeObservedAtNanos != null &&
                priorTimestamp != null &&
                gyro.timestampNanos > priorTimestamp &&
                (absolute == null || absolute.timestampNanos < gyro.timestampNanos)
            ) {
                val dt = ((gyro.timestampNanos - priorTimestamp) / 1_000_000_000.0)
                    .coerceIn(config.minimumDtSeconds, config.maximumDtSeconds)
                attitudeBodyToNed = (
                    attitudeBodyToNed * Quaterniond.fromAngularVelocity(gyro.value, dt)
                    ).normalized()
                attitudeObservedAtNanos = gyro.timestampNanos
            }
            if (priorTimestamp == null || gyro.timestampNanos > priorTimestamp) {
                lastGyroscopeTimestampNanos = gyro.timestampNanos
            }
        }
    }

    /** Builds tilt-compensated magnetic-NED attitude when no fused attitude sample exists. */
    private fun fallbackAttitude(input: FlightControllerInput): TimedSensorValue<Quaterniond>? {
        val acceleration = input.phone.accelerationBodyMps2.freshAt(
            input.timestampNanos,
            config.criticalSensorMaxAgeMillis,
        ) ?: return null
        val magnetic = input.phone.magneticFieldBodyMicroTesla.freshAt(
            input.timestampNanos,
            config.criticalSensorMaxAgeMillis,
        ) ?: return null
        val accelerationMagnitude = acceleration.value.norm()
        val magneticMagnitude = magnetic.value.norm()
        if (accelerationMagnitude !in 4.0..15.0 || magneticMagnitude !in 5.0..100.0) return null

        val downBody = (-acceleration.value).normalized()
        val magneticHorizontal = magnetic.value - downBody * magnetic.value.dot(downBody)
        if (magneticHorizontal.norm() < 1e-6) return null
        val northBody = magneticHorizontal.normalized()
        val eastBody = downBody.cross(northBody).normalized()
        val attitude = Quaterniond.fromRotationMatrix(
            doubleArrayOf(
                northBody.x, northBody.y, northBody.z,
                eastBody.x, eastBody.y, eastBody.z,
                downBody.x, downBody.y, downBody.z,
            ),
        )
        return TimedSensorValue(
            value = attitude,
            timestampNanos = minOf(acceleration.timestampNanos, magnetic.timestampNanos),
        )
    }

    /** Updates altitude and vertical velocity from the preferred fresh absolute-altitude source. */
    private fun updateAltitude(input: FlightControllerInput) {
        val observation = altitudeObservation(input) ?: return
        val latestTimestamp = latestAltitudeTimestampNanos
        if (latestTimestamp != null && observation.timestampNanos <= latestTimestamp) return

        if (originAltitudeMslMeters == null) {
            originAltitudeMslMeters = observation.value
        }
        val priorAltitude = previousAltitudeMslMeters
        val priorTimestamp = previousAltitudeTimestampNanos
        if (priorAltitude != null && priorTimestamp != null && observation.timestampNanos > priorTimestamp) {
            val dt = (observation.timestampNanos - priorTimestamp) / 1_000_000_000.0
            if (dt > 0.0) {
                val measured = (observation.value - priorAltitude) / dt
                verticalVelocityMetersPerSecond = verticalVelocityMetersPerSecond
                    ?.let { it * 0.70 + measured * 0.30 }
                    ?: measured
                verticalVelocityTimestampNanos = observation.timestampNanos
            }
        }
        previousAltitudeMslMeters = observation.value
        previousAltitudeTimestampNanos = observation.timestampNanos
        latestAltitudeAboveOriginMeters = observation.value - requireNotNull(originAltitudeMslMeters)
        latestAltitudeTimestampNanos = observation.timestampNanos
    }

    /** Selects one fresh MSL-altitude observation in deterministic priority order. */
    private fun altitudeObservation(input: FlightControllerInput): TimedSensorValue<Double>? {
        val now = input.timestampNanos
        input.external.altitudeAboveMeanSeaLevelMeters
            .freshAt(now, config.altitudeSensorMaxAgeMillis)
            ?.let { return it }
        input.external.pressurePascal
            .freshAt(now, config.altitudeSensorMaxAgeMillis)
            ?.let {
                return TimedSensorValue(
                    pressureAltitudeMeters(it.value.toDouble(), config.qnhHectopascal),
                    it.timestampNanos,
                )
            }
        input.phone.pressureHectopascal
            .freshAt(now, config.altitudeSensorMaxAgeMillis)
            ?.let {
                return TimedSensorValue(
                    pressureAltitudeMeters(it.value * 100.0, config.qnhHectopascal),
                    it.timestampNanos,
                )
            }
        return input.phone.location
            .freshAt(now, config.positionSensorMaxAgeMillis)
            ?.value
            ?.altitudeAboveMeanSeaLevelMeters
            ?.let { altitude ->
                val location = requireNotNull(
                    input.phone.location.freshAt(now, config.positionSensorMaxAgeMillis),
                )
                TimedSensorValue(altitude, location.timestampNanos)
            }
    }

    /** Updates local horizontal position and finite-difference horizontal velocity. */
    private fun updateHorizontalPosition(input: FlightControllerInput) {
        val location = input.phone.location.freshAt(
            input.timestampNanos,
            config.positionSensorMaxAgeMillis,
        ) ?: return
        val latestTimestamp = latestLocationTimestampNanos
        if (latestTimestamp != null && location.timestampNanos <= latestTimestamp) return
        if (originLocation == null) {
            originLocation = location.value
        }
        val current = localHorizontalNedFromOrigin(requireNotNull(originLocation), location.value)
        val priorPosition = previousHorizontalPositionNedMeters
        val priorTimestamp = previousLocationTimestampNanos
        if (priorPosition != null && priorTimestamp != null && location.timestampNanos > priorTimestamp) {
            val dt = (location.timestampNanos - priorTimestamp) / 1_000_000_000.0
            if (dt > 0.0) {
                val measured = (current - priorPosition) / dt
                horizontalVelocityNedMetersPerSecond = horizontalVelocityNedMetersPerSecond
                    ?.let { it * 0.70 + measured * 0.30 }
                    ?: measured
                horizontalVelocityTimestampNanos = location.timestampNanos
            }
        }
        previousHorizontalPositionNedMeters = current
        previousLocationTimestampNanos = location.timestampNanos
        latestHorizontalPositionNedMeters = current
        latestLocationTimestampNanos = location.timestampNanos
    }

    /** Converts WGS-84 fixes to local horizontal NED meters for short distances. */
    private fun localHorizontalNedFromOrigin(origin: GeoPoint, location: GeoPoint): Vector3d {
        val earthRadiusMeters = 6_378_137.0
        val dLat = (location.latitudeDegrees - origin.latitudeDegrees) * PI / 180.0
        val dLon = (location.longitudeDegrees - origin.longitudeDegrees) * PI / 180.0
        val meanLat = (location.latitudeDegrees + origin.latitudeDegrees) * 0.5 * PI / 180.0
        return Vector3d(
            x = dLat * earthRadiusMeters,
            y = dLon * earthRadiusMeters * cos(meanLat),
            z = 0.0,
        )
    }
}

/** Position/velocity controller that produces a body attitude and collective throttle. */
internal class PositionController(
    private val config: FlightControllerConfig,
) {
    private var velocityIntegral = Vector3d.ZERO
    private var lastMeasuredVelocity: Vector3d? = null

    /** Clears velocity-integral and derivative history. */
    fun reset() {
        velocityIntegral = Vector3d.ZERO
        lastMeasuredVelocity = null
    }

    /** Converts local position error into a bounded velocity target, attitude, and throttle. */
    fun update(
        setpoint: PositionControlTarget,
        estimate: VehicleStateEstimate,
        dtSeconds: Double,
        allowIntegral: Boolean = true,
    ): PositionControllerResult {
        val position = requireNotNull(estimate.localPositionNedMeters)
        val velocity = requireNotNull(estimate.localVelocityNedMetersPerSecond)
        val positionError = setpoint.localPositionNedMeters - position
        val velocitySetpoint = constrainVelocitySetpoint(
            setpoint.localVelocityFeedForwardNedMetersPerSecond +
                componentMultiply(positionError, config.positionGains),
        )
        return velocityToAttitudeTarget(
            velocitySetpoint = velocitySetpoint,
            measuredVelocity = velocity,
            yawNedRadians = setpoint.yawNedRadians,
            yawRateFeedForwardRadPerSecond = setpoint.yawRateFeedForwardRadPerSecond,
            estimate = estimate,
            dtSeconds = dtSeconds,
            positionError = positionError,
            allowIntegral = allowIntegral,
        )
    }

    /** Converts a local NED velocity target into attitude and throttle. */
    fun updateVelocity(
        setpoint: VelocityControlTarget,
        estimate: VehicleStateEstimate,
        dtSeconds: Double,
        allowIntegral: Boolean = true,
    ): PositionControllerResult = velocityToAttitudeTarget(
        velocitySetpoint = constrainVelocitySetpoint(setpoint.localVelocityNedMetersPerSecond),
        measuredVelocity = requireNotNull(estimate.localVelocityNedMetersPerSecond),
        yawNedRadians = setpoint.yawNedRadians,
        yawRateFeedForwardRadPerSecond = setpoint.yawRateFeedForwardRadPerSecond,
        estimate = estimate,
        dtSeconds = dtSeconds,
        positionError = null,
        allowIntegral = allowIntegral,
    )

    /** Controls altitude without inventing unavailable horizontal position or velocity. */
    fun updateAltitude(
        setpoint: AltitudeControlTarget,
        estimate: VehicleStateEstimate,
        dtSeconds: Double,
        allowIntegral: Boolean = true,
    ): PositionControllerResult {
        val altitude = requireNotNull(estimate.altitudeAboveOriginMeters)
        val verticalVelocity = requireNotNull(estimate.verticalVelocityMetersPerSecond)
        val altitudeError = setpoint.altitudeAboveOriginMeters - altitude
        val positionErrorNed = Vector3d(0.0, 0.0, -altitudeError)
        val velocitySetpointNed = constrainVelocitySetpoint(
            Vector3d(
                0.0,
                0.0,
                -setpoint.verticalVelocityFeedForwardMetersPerSecond +
                    positionErrorNed.z * config.positionGains.z,
            ),
        )
        val result = velocityToAttitudeTarget(
            velocitySetpoint = velocitySetpointNed,
            measuredVelocity = Vector3d(0.0, 0.0, -verticalVelocity),
            yawNedRadians = setpoint.yawNedRadians,
            yawRateFeedForwardRadPerSecond = setpoint.yawRateFeedForwardRadPerSecond,
            estimate = estimate,
            dtSeconds = dtSeconds,
            positionError = positionErrorNed,
            allowIntegral = allowIntegral,
        )
        return result.copy(
            tracking = result.tracking.copy(
                positionErrorNedMeters = null,
                altitudeErrorMeters = altitudeError,
            ),
        )
    }

    /** Runs velocity PID with derivative-on-measurement and conditional anti-windup. */
    private fun velocityToAttitudeTarget(
        velocitySetpoint: Vector3d,
        measuredVelocity: Vector3d,
        yawNedRadians: Double?,
        yawRateFeedForwardRadPerSecond: Double,
        estimate: VehicleStateEstimate,
        dtSeconds: Double,
        positionError: Vector3d?,
        allowIntegral: Boolean,
    ): PositionControllerResult {
        val velocityError = velocitySetpoint - measuredVelocity
        val measuredAcceleration = lastMeasuredVelocity
            ?.let { (measuredVelocity - it) / dtSeconds }
            ?: Vector3d.ZERO
        lastMeasuredVelocity = measuredVelocity
        val accelerationDemand = componentMultiply(velocityError, config.velocityGains.proportional) +
            velocityIntegral -
            componentMultiply(measuredAcceleration, config.velocityGains.derivative)

        val rawRoll = accelerationDemand.y / STANDARD_GRAVITY
        val rawPitch = -accelerationDemand.x / STANDARD_GRAVITY
        val rawThrottle = config.hoverThrottle - accelerationDemand.z / STANDARD_GRAVITY * 0.5
        val horizontalSaturated = abs(rawRoll) > config.maxTiltRadians || abs(rawPitch) > config.maxTiltRadians
        val verticalPushesHigh = rawThrottle >= config.maximumFlyingThrottle && velocityError.z < 0.0
        val verticalPushesLow = rawThrottle <= config.minimumFlyingThrottle && velocityError.z > 0.0

        if (allowIntegral) {
            val increment = componentMultiply(velocityError, config.velocityGains.integral) * dtSeconds
            velocityIntegral = Vector3d(
                x = if (horizontalSaturated) velocityIntegral.x else velocityIntegral.x + increment.x,
                y = if (horizontalSaturated) velocityIntegral.y else velocityIntegral.y + increment.y,
                z = if (verticalPushesHigh || verticalPushesLow) velocityIntegral.z else velocityIntegral.z + increment.z,
            ).clampAbs(config.velocityGains.integralLimit)
        }

        val yaw = yawNedRadians ?: estimate.attitudeBodyToNed.toEuler().yawRadians
        return PositionControllerResult(
            target = AttitudeThrottleSetpoint(
                attitude = Quaterniond.fromEuler(
                    rollRadians = rawRoll.coerceIn(-config.maxTiltRadians, config.maxTiltRadians),
                    pitchRadians = rawPitch.coerceIn(-config.maxTiltRadians, config.maxTiltRadians),
                    yawRadians = yaw,
                ),
                bodyRates = null,
                throttle = rawThrottle.coerceIn(
                    config.minimumFlyingThrottle,
                    config.maximumFlyingThrottle,
                ),
                yawRateFeedForward = yawRateFeedForwardRadPerSecond,
            ),
            tracking = ControlTracking(
                positionErrorNedMeters = positionError,
                velocityErrorNedMetersPerSecond = velocityError,
                altitudeErrorMeters = positionError?.let { -it.z },
            ),
        )
    }

    /** Constrains horizontal speed magnitude and asymmetric NED vertical rates. */
    private fun constrainVelocitySetpoint(value: Vector3d): Vector3d {
        val horizontalNorm = sqrt(value.x * value.x + value.y * value.y)
        val horizontalScale = if (horizontalNorm > config.maximumHorizontalVelocityMetersPerSecond) {
            config.maximumHorizontalVelocityMetersPerSecond / horizontalNorm
        } else {
            1.0
        }
        return Vector3d(
            x = value.x * horizontalScale,
            y = value.y * horizontalScale,
            z = value.z.coerceIn(
                -config.maximumClimbRateMetersPerSecond,
                config.maximumDescentRateMetersPerSecond,
            ),
        )
    }
}

/** Quaternion attitude controller producing body-rate setpoints. */
internal class AttitudeController(
    private val config: FlightControllerConfig,
) {
    private val gains = config.attitudeGains.asVector()

    /** Returns shortest small-angle error from [currentAttitude] to [desiredAttitude]. */
    fun error(currentAttitude: Quaterniond, desiredAttitude: Quaterniond): Vector3d {
        var error = (currentAttitude.inverse() * desiredAttitude).normalized()
        if (error.w < 0.0) {
            error = Quaterniond(-error.w, -error.x, -error.y, -error.z)
        }
        return Vector3d(error.x, error.y, error.z) * 2.0
    }

    /** Converts attitude error and yaw feed-forward into bounded body rates. */
    fun update(
        currentAttitude: Quaterniond,
        desiredAttitude: Quaterniond,
        yawRateFeedForward: Double,
    ): Vector3d {
        val feedback = componentMultiply(error(currentAttitude, desiredAttitude), gains)
        return Vector3d(
            feedback.x,
            feedback.y,
            feedback.z + yawRateFeedForward,
        ).clampAbs(config.maxBodyRateRadPerSecond)
    }
}

/** Angular-rate PID with derivative-on-measurement and saturation-aware conditional integration. */
internal class RateController(
    private val config: FlightControllerConfig,
) {
    private var integral = Vector3d.ZERO
    private var lastMeasuredRate: Vector3d? = null

    /** Clears integral and measured-rate derivative history. */
    fun reset() {
        integral = Vector3d.ZERO
        lastMeasuredRate = null
    }

    /** Computes normalized body torque from desired and measured body rates. */
    fun update(
        rateSetpoint: Vector3d,
        measuredRates: Vector3d,
        dtSeconds: Double,
        allowIntegral: Boolean,
    ): Vector3d {
        val error = rateSetpoint - measuredRates
        val angularAcceleration = lastMeasuredRate
            ?.let { (measuredRates - it) / dtSeconds }
            ?: Vector3d.ZERO
        lastMeasuredRate = measuredRates
        val unconstrained = componentMultiply(error, config.rateGains.proportional) +
            integral -
            componentMultiply(angularAcceleration, config.rateGains.derivative) +
            componentMultiply(rateSetpoint, config.rateGains.feedForward)

        if (allowIntegral) {
            val largeErrorScale = Vector3d(
                integralScale(error.x),
                integralScale(error.y),
                integralScale(error.z),
            )
            val integrableError = Vector3d(
                conditionalError(error.x, unconstrained.x),
                conditionalError(error.y, unconstrained.y),
                conditionalError(error.z, unconstrained.z),
            )
            integral = (
                integral + componentMultiply(
                    componentMultiply(integrableError, config.rateGains.integral),
                    largeErrorScale,
                ) * dtSeconds
                ).clampAbs(config.rateGains.integralLimit)
        }
        return unconstrained.clampAbs(Vector3d(1.0, 1.0, 1.0))
    }

    /** Reduces integration smoothly for very large rate error, matching PX4's safety behavior. */
    private fun integralScale(error: Double): Double {
        val normalized = error / (400.0 * PI / 180.0)
        return max(0.0, 1.0 - normalized * normalized)
    }

    /** Blocks integration that would push an already saturated torque farther outward. */
    private fun conditionalError(error: Double, output: Double): Double = when {
        output >= 1.0 && error > 0.0 -> 0.0
        output <= -1.0 && error < 0.0 -> 0.0
        else -> error
    }
}

/** Quad-X allocation result and saturation diagnostic. */
internal data class MotorAllocation(
    val motors: List<NormalizedMotorOutput>,
    val saturated: Boolean,
)

/** Configurable Quad-X torque/throttle allocator and PWM mapper. */
internal class QuadXControlAllocator(
    private val config: FlightControllerConfig,
) {
    /** Returns diagnostic stopped outputs; no PWM action is emitted while disarmed. */
    fun stopped(): List<NormalizedMotorOutput> = config.motorLayout.motors.map {
        NormalizedMotorOutput(
            channel = it.channel,
            normalized = 0.0,
            pulseMicros = config.disarmedMotorPulseMicros,
            label = it.label,
        )
    }

    /** Allocates normalized collective and torque into the configured four motor channels. */
    fun allocate(throttle: Double, torque: Vector3d): MotorAllocation {
        val safeThrottle = throttle.coerceFinite(
            config.minimumFlyingThrottle,
            config.maximumFlyingThrottle,
        )
        val safeTorque = torque.clampAbs(Vector3d(1.0, 1.0, 1.0))
        val raw = config.motorLayout.motors.map { motor ->
            safeThrottle +
                motor.rollScale * safeTorque.x +
                motor.pitchScale * safeTorque.y +
                motor.yawScale * safeTorque.z
        }
        val desaturated = desaturate(raw)
        val bounded = desaturated.map {
            it.coerceIn(config.minimumFlyingThrottle, config.maximumFlyingThrottle)
        }
        val saturated = raw.any { it !in 0.0..1.0 } ||
            raw.zip(desaturated).any { (before, after) -> abs(before - after) > 1e-12 } ||
            desaturated.zip(bounded).any { (before, after) -> abs(before - after) > 1e-12 }
        return MotorAllocation(
            motors = config.motorLayout.motors.zip(bounded).map { (motor, value) ->
                NormalizedMotorOutput(
                    channel = motor.channel,
                    normalized = value,
                    pulseMicros = value.mixToPulse(
                        config.minimumMotorPulseMicros,
                        config.maximumMotorPulseMicros,
                    ),
                    label = motor.label,
                )
            },
            saturated = saturated,
        )
    }

    /** Preserves motor differentials while translating/scaling values into normalized bounds. */
    private fun desaturate(values: List<Double>): List<Double> {
        var result = values
        val high = result.maxOrNull() ?: 0.0
        if (high > 1.0) {
            result = result.map { it - (high - 1.0) }
        }
        val low = result.minOrNull() ?: 0.0
        if (low < 0.0) {
            result = result.map { it - low }
        }
        val minimum = result.minOrNull() ?: 0.0
        val range = (result.maxOrNull() ?: 0.0) - minimum
        return if (range > 1.0) {
            result.map { (it - minimum) / range }
        } else {
            result.map { it.coerceUnit() }
        }
    }
}

/** Returns this sample only when its acquisition timestamp is current and not future. */
private fun <T> TimedSensorValue<T>?.freshAt(
    nowNanos: Long,
    maximumAgeMillis: Long,
): TimedSensorValue<T>? {
    val sample = this ?: return null
    if (sample.timestampNanos > nowNanos) return null
    val maximumAgeNanos = if (maximumAgeMillis > Long.MAX_VALUE / 1_000_000L) {
        Long.MAX_VALUE
    } else {
        maximumAgeMillis * 1_000_000L
    }
    return if (nowNanos - sample.timestampNanos <= maximumAgeNanos) sample else null
}

/** Multiplies vectors component by component. */
private fun componentMultiply(a: Vector3d, b: Vector3d): Vector3d =
    Vector3d(a.x * b.x, a.y * b.y, a.z * b.z)

/** Converts pressure in pascals to barometric MSL altitude using configured QNH. */
private fun pressureAltitudeMeters(pressurePa: Double, qnhHpa: Double): Double =
    44_330.0 * (1.0 - (pressurePa / (qnhHpa * 100.0)).pow(0.19029495718363465))

/** Standard gravitational acceleration in meters per second squared. */
private const val STANDARD_GRAVITY = 9.80665
