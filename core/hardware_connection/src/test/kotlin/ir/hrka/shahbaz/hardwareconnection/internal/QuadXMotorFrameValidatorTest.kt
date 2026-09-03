package ir.hrka.shahbaz.hardwareconnection.internal

import ir.hrka.shahbaz.hardwareconnection.BoardActuatorRejection
import ir.hrka.shahbaz.hardwareconnection.BoardMotorPulse
import ir.hrka.shahbaz.hardwareconnection.BoardPulseBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QuadXMotorFrameValidatorTest {
    private val bounds = BoardPulseBounds(900, 2_100)
    private val validFrame = listOf(
        BoardMotorPulse(0, 900),
        BoardMotorPulse(1, 1_200),
        BoardMotorPulse(2, 1_800),
        BoardMotorPulse(3, 2_100),
    )

    @Test
    fun `every Quad-X channel permutation and PWM boundary is accepted`() {
        permutations(validFrame).forEach { frame ->
            assertNull(validate(frame))
        }
    }

    @Test
    fun `empty oversized and non-four frames have distinct fail-closed results`() {
        assertReason(BoardActuatorRejection.EMPTY_BATCH, emptyList())
        assertReason(
            BoardActuatorRejection.BATCH_TOO_LARGE,
            validFrame + BoardMotorPulse(4, 1_500),
            maximumBatchSize = 4,
        )
        assertReason(BoardActuatorRejection.INCOMPLETE_MOTOR_FRAME, validFrame.take(1))
        assertReason(BoardActuatorRejection.INCOMPLETE_MOTOR_FRAME, validFrame.take(2))
        assertReason(BoardActuatorRejection.INCOMPLETE_MOTOR_FRAME, validFrame.take(3))
        assertReason(
            BoardActuatorRejection.INCOMPLETE_MOTOR_FRAME,
            validFrame + BoardMotorPulse(4, 1_500),
            maximumBatchSize = 8,
        )
    }

    @Test
    fun `board active channel count must describe the same Quad-X frame`() {
        listOf(0, 1, 2, 3, 5, 8, 255).forEach { activeChannels ->
            assertReason(
                BoardActuatorRejection.INCOMPLETE_MOTOR_FRAME,
                validFrame,
                activeMotorChannels = activeChannels,
            )
        }
        assertNull(validate(validFrame, activeMotorChannels = null))
        assertNull(validate(validFrame, activeMotorChannels = 4))
    }

    @Test
    fun `missing duplicate and out-of-range channel sets are rejected`() {
        assertReason(
            BoardActuatorRejection.INVALID_CHANNEL,
            listOf(validFrame[0], validFrame[1], validFrame[2], BoardMotorPulse(2, 1_500)),
        )
        listOf(4, 5, 127, 255).forEach { invalidChannel ->
            assertReason(
                BoardActuatorRejection.INVALID_CHANNEL,
                listOf(validFrame[0], validFrame[1], validFrame[2], BoardMotorPulse(invalidChannel, 1_500)),
            )
        }
    }

    @Test
    fun `each channel rejects PWM immediately outside configured bounds`() {
        validFrame.indices.forEach { channel ->
            assertReason(
                BoardActuatorRejection.INVALID_PULSE,
                validFrame.withPulse(channel, 899),
            )
            assertReason(
                BoardActuatorRejection.INVALID_PULSE,
                validFrame.withPulse(channel, 2_101),
            )
        }
    }

    @Test
    fun `configured maximum below four rejects the otherwise complete frame`() {
        assertReason(
            BoardActuatorRejection.BATCH_TOO_LARGE,
            validFrame,
            maximumBatchSize = 3,
        )
    }

    private fun validate(
        pulses: List<BoardMotorPulse>,
        maximumBatchSize: Int = 4,
        activeMotorChannels: Int? = 4,
    ): MotorFrameValidationFailure? = validateQuadXMotorFrame(
        pulses = pulses,
        maximumBatchSize = maximumBatchSize,
        activeMotorChannels = activeMotorChannels,
        pulseBounds = bounds,
    )

    private fun assertReason(
        expected: BoardActuatorRejection,
        pulses: List<BoardMotorPulse>,
        maximumBatchSize: Int = 4,
        activeMotorChannels: Int? = 4,
    ) {
        assertEquals(expected, validate(pulses, maximumBatchSize, activeMotorChannels)?.reason)
    }

    private fun List<BoardMotorPulse>.withPulse(channel: Int, pulseMicros: Int) =
        map { pulse -> if (pulse.channel == channel) pulse.copy(pulseMicros = pulseMicros) else pulse }

    private fun <T> permutations(values: List<T>): List<List<T>> =
        if (values.size <= 1) {
            listOf(values)
        } else {
            values.flatMapIndexed { index, value ->
                permutations(values.filterIndexed { otherIndex, _ -> otherIndex != index })
                    .map { remainder -> listOf(value) + remainder }
            }
        }
}
