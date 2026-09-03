package ir.hrka.shahbaz.hardwareconnection.internal

import ir.hrka.shahbaz.hardwareconnection.BoardActuatorRejection
import ir.hrka.shahbaz.hardwareconnection.BoardMotorPulse
import ir.hrka.shahbaz.hardwareconnection.BoardPulseBounds

internal const val QUAD_X_MOTOR_CHANNEL_COUNT: Int = 4

/** A pure validation result kept separate from Android and USB state for exhaustive testing. */
internal data class MotorFrameValidationFailure(
    val reason: BoardActuatorRejection,
    val message: String,
)

/**
 * Validates one complete Quad-X generation before it can enter the asynchronous USB dispatcher.
 *
 * The fixed channel set is deliberately stricter than a generic actuator batch: exactly channels
 * 0, 1, 2, and 3 must be present once each, and the connected board must report exactly four active
 * motor channels. This prevents a partial generation from being reinterpreted as a valid frame.
 */
internal fun validateQuadXMotorFrame(
    pulses: List<BoardMotorPulse>,
    maximumBatchSize: Int,
    activeMotorChannels: Int?,
    pulseBounds: BoardPulseBounds,
): MotorFrameValidationFailure? {
    if (pulses.isEmpty()) {
        return MotorFrameValidationFailure(
            BoardActuatorRejection.EMPTY_BATCH,
            "Motor frame is empty",
        )
    }
    if (pulses.size > maximumBatchSize) {
        return MotorFrameValidationFailure(
            BoardActuatorRejection.BATCH_TOO_LARGE,
            "Motor frame contains ${pulses.size} channels; configured maximum is $maximumBatchSize",
        )
    }
    if (pulses.size != QUAD_X_MOTOR_CHANNEL_COUNT) {
        return MotorFrameValidationFailure(
            BoardActuatorRejection.INCOMPLETE_MOTOR_FRAME,
            "Quad-X motor frame contains ${pulses.size} channels; exactly " +
                "$QUAD_X_MOTOR_CHANNEL_COUNT are required",
        )
    }
    if (activeMotorChannels != null && activeMotorChannels != QUAD_X_MOTOR_CHANNEL_COUNT) {
        return MotorFrameValidationFailure(
            BoardActuatorRejection.INCOMPLETE_MOTOR_FRAME,
            "Board reports $activeMotorChannels active motor channels; the Quad-X frame requires " +
                "$QUAD_X_MOTOR_CHANNEL_COUNT",
        )
    }

    val seenChannels = BooleanArray(QUAD_X_MOTOR_CHANNEL_COUNT)
    pulses.forEach { pulse ->
        if (pulse.channel !in seenChannels.indices) {
            return MotorFrameValidationFailure(
                BoardActuatorRejection.INVALID_CHANNEL,
                "Motor channel ${pulse.channel} is outside the Quad-X channel set 0..3",
            )
        }
        if (seenChannels[pulse.channel]) {
            return MotorFrameValidationFailure(
                BoardActuatorRejection.INVALID_CHANNEL,
                "Duplicate motor channel ${pulse.channel}",
            )
        }
        seenChannels[pulse.channel] = true
        if (!pulseBounds.contains(pulse.pulseMicros)) {
            return MotorFrameValidationFailure(
                BoardActuatorRejection.INVALID_PULSE,
                "Motor pulse ${pulse.pulseMicros}us is outside $pulseBounds",
            )
        }
    }
    return null
}
