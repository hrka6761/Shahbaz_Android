/** Reusable animated aircraft attitude indicator shared by flight features. */
package ir.hrka.shahbaz.core.designsystem.attitude

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private val InstrumentBlack = Color.Black
private val ScaleWhite = Color(0xFFF7F7F7)
private val BezelDark = Color(0xFF121212)
private val BezelOutline = Color(0xFF292929)
private val MarkerRed = Color(0xFFFF2028)
private val AircraftAmber = Color(0xFFFFD000)
private val AircraftOutline = Color(0xFF080808)
private val SkyTop = Color(0xFF03468F)
private val SkyHorizon = Color(0xFF1486E3)
private val GroundHorizon = Color(0xFF87502C)
private val GroundBottom = Color(0xFF3C1D0C)
private val InactiveUpper = Color(0xFF454545)
private val InactiveLower = Color(0xFF252525)

/**
 * Draws a responsive aircraft-style attitude indicator from display-corrected device orientation.
 *
 * The outer scale, reference markers, and aircraft symbol stay fixed. Pitch and roll move only the
 * circular sky, ground, horizon, and pitch ladder behind them. A missing reading leaves a dimmed
 * neutral instrument visible instead of presenting zero as a live measurement.
 */
@Composable
fun AttitudeIndicatorView(
    pitchDegrees: Float?,
    rollDegrees: Float?,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val finitePitch = pitchDegrees?.takeIf(Float::isFinite)
    val finiteRoll = rollDegrees?.takeIf(Float::isFinite)
    val active = finitePitch != null && finiteRoll != null
    val animatedAttitude = rememberAnimatedAttitude(
        pitchDegrees = finitePitch.takeIf { active },
        rollDegrees = finiteRoll.takeIf { active },
    )
    val semanticsModifier = if (contentDescription == null) {
        Modifier
    } else {
        Modifier.semantics { this.contentDescription = contentDescription }
    }

    Box(
        modifier = modifier
            .then(semanticsModifier)
            .aspectRatio(1f)
            .background(InstrumentBlack),
        contentAlignment = Alignment.Center,
    ) {
        AttitudeCanvas(
            animatedPitch = animatedAttitude.pitch,
            animatedRoll = animatedAttitude.roll,
            active = active,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun AttitudeCanvas(
    animatedPitch: State<Float>,
    animatedRoll: State<Float>,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer(cacheSize = 32)
    val alpha = if (active) 1f else INACTIVE_ALPHA

    Canvas(modifier) {
        val diameter = min(size.width, size.height)
        if (diameter <= 0f) return@Canvas

        val instrumentCenter = center
        val innerRadius = diameter * INNER_RADIUS_RATIO
        val bezelRadius = diameter * BEZEL_RADIUS_RATIO
        val tickOuterRadius = diameter * TICK_OUTER_RADIUS_RATIO
        val rollLabelRadius = diameter * ROLL_LABEL_RADIUS_RATIO
        val rollLabelStyle = TextStyle(
            color = ScaleWhite,
            fontSize = (diameter * ROLL_TEXT_SIZE_RATIO).toSp(),
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.sp,
        )
        val pitchLabelStyle = TextStyle(
            color = ScaleWhite,
            fontSize = (diameter * PITCH_TEXT_SIZE_RATIO).toSp(),
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.sp,
        )

        drawRollScale(
            center = instrumentCenter,
            diameter = diameter,
            tickOuterRadius = tickOuterRadius,
            labelRadius = rollLabelRadius,
            labelStyle = rollLabelStyle,
            textMeasurer = textMeasurer,
            alpha = alpha,
        )

        drawCircle(
            color = BezelDark.copy(alpha = alpha),
            radius = bezelRadius,
            center = instrumentCenter,
        )
        drawCircle(
            color = BezelOutline.copy(alpha = alpha),
            radius = bezelRadius,
            center = instrumentCenter,
            style = Stroke(width = diameter * BEZEL_OUTLINE_WIDTH_RATIO),
        )

        drawMovingAttitudeLayer(
            center = instrumentCenter,
            innerRadius = innerRadius,
            diameter = diameter,
            pitchDegrees = animatedPitch.value,
            rollDegrees = animatedRoll.value,
            pitchLabelStyle = pitchLabelStyle,
            textMeasurer = textMeasurer,
            active = active,
            alpha = alpha,
        )

        drawCircle(
            color = InstrumentBlack.copy(alpha = alpha),
            radius = innerRadius,
            center = instrumentCenter,
            style = Stroke(width = diameter * INNER_BORDER_WIDTH_RATIO),
        )
        drawAircraftSymbol(instrumentCenter, innerRadius, alpha)
        drawSideMarkers(instrumentCenter, diameter, alpha)
        drawTopMarker(instrumentCenter, diameter, alpha)
    }
}

private fun DrawScope.drawRollScale(
    center: Offset,
    diameter: Float,
    tickOuterRadius: Float,
    labelRadius: Float,
    labelStyle: TextStyle,
    textMeasurer: TextMeasurer,
    alpha: Float,
) {
    for (degree in 0 until FULL_TURN_DEGREES.toInt() step ROLL_TICK_STEP_DEGREES) {
        val major = degree % ROLL_LABEL_STEP_DEGREES == 0
        val medium = degree % ROLL_MEDIUM_TICK_STEP_DEGREES == 0
        val tickLength = diameter * when {
            major -> MAJOR_ROLL_TICK_LENGTH_RATIO
            medium -> MEDIUM_ROLL_TICK_LENGTH_RATIO
            else -> MINOR_ROLL_TICK_LENGTH_RATIO
        }
        drawLine(
            color = ScaleWhite.copy(alpha = alpha),
            start = pointAt(center, tickOuterRadius - tickLength, degree.toFloat()),
            end = pointAt(center, tickOuterRadius, degree.toFloat()),
            strokeWidth = diameter * if (major) {
                MAJOR_ROLL_TICK_WIDTH_RATIO
            } else {
                MINOR_ROLL_TICK_WIDTH_RATIO
            },
            cap = StrokeCap.Square,
        )
    }

    for (degree in 0 until FULL_TURN_DEGREES.toInt() step ROLL_LABEL_STEP_DEGREES) {
        drawCenteredText(
            textMeasurer = textMeasurer,
            text = rollScaleLabel(degree),
            position = pointAt(center, labelRadius, degree.toFloat()),
            style = labelStyle,
            alpha = alpha,
        )
    }
}

private fun DrawScope.drawMovingAttitudeLayer(
    center: Offset,
    innerRadius: Float,
    diameter: Float,
    pitchDegrees: Float,
    rollDegrees: Float,
    pitchLabelStyle: TextStyle,
    textMeasurer: TextMeasurer,
    active: Boolean,
    alpha: Float,
) {
    val circlePath = Path().apply {
        addOval(
            Rect(
                left = center.x - innerRadius,
                top = center.y - innerRadius,
                right = center.x + innerRadius,
                bottom = center.y + innerRadius,
            )
        )
    }
    val pitchOffset = pitchDegrees
        .coerceIn(-MAX_PITCH_DEGREES, MAX_PITCH_DEGREES) * (innerRadius / PITCH_RANGE_RADIUS_DEGREES)
    val horizonY = center.y + pitchOffset
    val layerExtent = innerRadius * ATTITUDE_LAYER_EXTENT_MULTIPLIER
    val skyBrush = if (active) {
        Brush.verticalGradient(
            colors = listOf(
                SkyTop.copy(alpha = alpha),
                SkyHorizon.copy(alpha = alpha),
            ),
            startY = center.y - layerExtent,
            endY = horizonY,
        )
    } else {
        Brush.verticalGradient(
            colors = listOf(
                InactiveUpper.copy(alpha = alpha),
                InactiveUpper.copy(alpha = alpha),
            ),
            startY = center.y - layerExtent,
            endY = horizonY,
        )
    }
    val groundBrush = if (active) {
        Brush.verticalGradient(
            colors = listOf(
                GroundHorizon.copy(alpha = alpha),
                GroundBottom.copy(alpha = alpha),
            ),
            startY = horizonY,
            endY = center.y + layerExtent,
        )
    } else {
        Brush.verticalGradient(
            colors = listOf(
                InactiveLower.copy(alpha = alpha),
                InactiveLower.copy(alpha = alpha),
            ),
            startY = horizonY,
            endY = center.y + layerExtent,
        )
    }

    clipPath(circlePath) {
        rotate(degrees = -rollDegrees, pivot = center) {
            drawRect(
                brush = skyBrush,
                topLeft = Offset(center.x - layerExtent, center.y - layerExtent),
                size = Size(layerExtent * 2f, horizonY - center.y + layerExtent),
            )
            drawRect(
                brush = groundBrush,
                topLeft = Offset(center.x - layerExtent, horizonY),
                size = Size(layerExtent * 2f, center.y + layerExtent - horizonY),
            )
            drawLine(
                color = ScaleWhite.copy(alpha = alpha),
                start = Offset(center.x - layerExtent, horizonY),
                end = Offset(center.x + layerExtent, horizonY),
                strokeWidth = diameter * HORIZON_WIDTH_RATIO,
                cap = StrokeCap.Square,
            )
            drawPitchLadder(
                center = center,
                innerRadius = innerRadius,
                horizonY = horizonY,
                diameter = diameter,
                labelStyle = pitchLabelStyle,
                textMeasurer = textMeasurer,
                alpha = alpha,
            )
        }

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color.Transparent, InstrumentBlack.copy(alpha = alpha * 0.34f)),
                center = center,
                radius = innerRadius,
            ),
            radius = innerRadius,
            center = center,
        )
    }
}

private fun DrawScope.drawPitchLadder(
    center: Offset,
    innerRadius: Float,
    horizonY: Float,
    diameter: Float,
    labelStyle: TextStyle,
    textMeasurer: TextMeasurer,
    alpha: Float,
) {
    val pixelsPerDegree = innerRadius / PITCH_RANGE_RADIUS_DEGREES
    for (pitchDegree in MIN_LADDER_PITCH..MAX_LADDER_PITCH step PITCH_LADDER_STEP_DEGREES) {
        if (pitchDegree == 0) continue

        val major = pitchDegree % MAJOR_PITCH_STEP_DEGREES == 0
        val halfLength = innerRadius * if (major) {
            MAJOR_PITCH_HALF_LENGTH_RATIO
        } else {
            MINOR_PITCH_HALF_LENGTH_RATIO
        }
        val lineY = horizonY - pitchDegree * pixelsPerDegree
        drawLine(
            color = ScaleWhite.copy(alpha = alpha),
            start = Offset(center.x - halfLength, lineY),
            end = Offset(center.x + halfLength, lineY),
            strokeWidth = diameter * PITCH_LINE_WIDTH_RATIO,
            cap = StrokeCap.Square,
        )

        if (major) {
            val labelOffset = innerRadius * PITCH_LABEL_OFFSET_RATIO
            val label = pitchDegree.toString()
            drawCenteredText(
                textMeasurer = textMeasurer,
                text = label,
                position = Offset(center.x - labelOffset, lineY),
                style = labelStyle,
                alpha = alpha,
            )
            drawCenteredText(
                textMeasurer = textMeasurer,
                text = label,
                position = Offset(center.x + labelOffset, lineY),
                style = labelStyle,
                alpha = alpha,
            )
        }
    }
}

private fun DrawScope.drawAircraftSymbol(center: Offset, radius: Float, alpha: Float) {
    val aircraftPath = Path().apply {
        moveTo(center.x - radius * 0.57f, center.y)
        lineTo(center.x - radius * 0.25f, center.y)
        lineTo(center.x - radius * 0.18f, center.y + radius * 0.07f)

        moveTo(center.x - radius * 0.15f, center.y + radius * 0.06f)
        lineTo(center.x, center.y - radius * 0.05f)
        lineTo(center.x + radius * 0.15f, center.y + radius * 0.06f)

        moveTo(center.x + radius * 0.18f, center.y + radius * 0.07f)
        lineTo(center.x + radius * 0.25f, center.y)
        lineTo(center.x + radius * 0.57f, center.y)
    }
    drawPath(
        path = aircraftPath,
        color = AircraftOutline.copy(alpha = alpha),
        style = Stroke(
            width = radius * 0.040f,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        ),
    )
    drawPath(
        path = aircraftPath,
        color = AircraftAmber.copy(alpha = alpha),
        style = Stroke(
            width = radius * 0.024f,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        ),
    )
}

private fun DrawScope.drawTopMarker(center: Offset, diameter: Float, alpha: Float) {
    val markerBaseY = center.y - diameter * TOP_MARKER_BASE_RADIUS_RATIO
    val markerTipY = center.y - diameter * TOP_MARKER_TIP_RADIUS_RATIO
    val markerHalfWidth = diameter * TOP_MARKER_HALF_WIDTH_RATIO
    drawPath(
        path = Path().apply {
            moveTo(center.x - markerHalfWidth, markerBaseY)
            lineTo(center.x + markerHalfWidth, markerBaseY)
            lineTo(center.x, markerTipY)
            close()
        },
        color = MarkerRed.copy(alpha = alpha),
    )
}

private fun DrawScope.drawSideMarkers(center: Offset, diameter: Float, alpha: Float) {
    val markerHalfHeight = diameter * SIDE_MARKER_HALF_HEIGHT_RATIO
    val markerBaseRadius = diameter * SIDE_MARKER_BASE_RADIUS_RATIO
    val markerTipRadius = diameter * SIDE_MARKER_TIP_RADIUS_RATIO

    drawPath(
        path = Path().apply {
            moveTo(center.x - markerBaseRadius, center.y - markerHalfHeight)
            lineTo(center.x - markerBaseRadius, center.y + markerHalfHeight)
            lineTo(center.x - markerTipRadius, center.y)
            close()
        },
        color = AircraftAmber.copy(alpha = alpha),
    )
    drawPath(
        path = Path().apply {
            moveTo(center.x + markerBaseRadius, center.y - markerHalfHeight)
            lineTo(center.x + markerBaseRadius, center.y + markerHalfHeight)
            lineTo(center.x + markerTipRadius, center.y)
            close()
        },
        color = AircraftAmber.copy(alpha = alpha),
    )
}

@Composable
private fun rememberAnimatedAttitude(
    pitchDegrees: Float?,
    rollDegrees: Float?,
): AnimatedAttitude {
    val constrainedPitch = pitchDegrees?.coerceIn(-MAX_PITCH_DEGREES, MAX_PITCH_DEGREES)
    val normalizedRoll = rollDegrees?.let(::normalizeSignedAttitudeAngle)
    val animatedPitch = remember { Animatable(constrainedPitch ?: 0f) }
    val animatedRoll = remember { Animatable(normalizedRoll ?: 0f) }
    var previousPitch by remember { mutableStateOf(constrainedPitch) }
    var previousRoll by remember { mutableStateOf(normalizedRoll) }
    var continuousRollTarget by remember { mutableFloatStateOf(normalizedRoll ?: 0f) }

    LaunchedEffect(constrainedPitch) {
        if (constrainedPitch == null) {
            previousPitch = null
        } else if (previousPitch == null) {
            previousPitch = constrainedPitch
            animatedPitch.snapTo(constrainedPitch)
        } else {
            previousPitch = constrainedPitch
            animatedPitch.animateTo(
                targetValue = constrainedPitch,
                animationSpec = tween(
                    durationMillis = ATTITUDE_ANIMATION_MILLIS,
                    easing = LinearOutSlowInEasing,
                ),
            )
        }
    }

    LaunchedEffect(normalizedRoll) {
        if (normalizedRoll == null) {
            previousRoll = null
        } else {
            val previous = previousRoll
            if (previous == null) {
                continuousRollTarget = normalizedRoll
                previousRoll = normalizedRoll
                animatedRoll.snapTo(normalizedRoll)
            } else {
                continuousRollTarget += shortestAttitudeAngleDelta(previous, normalizedRoll)
                previousRoll = normalizedRoll
                animatedRoll.animateTo(
                    targetValue = continuousRollTarget,
                    animationSpec = tween(
                        durationMillis = ATTITUDE_ANIMATION_MILLIS,
                        easing = LinearOutSlowInEasing,
                    ),
                )
            }
        }
    }

    return remember(animatedPitch, animatedRoll) {
        AnimatedAttitude(
            pitch = animatedPitch.asState(),
            roll = animatedRoll.asState(),
        )
    }
}

/** Normalizes a finite angle into the half-open -180 to 180 degree interval. */
internal fun normalizeSignedAttitudeAngle(degrees: Float): Float {
    require(degrees.isFinite()) { "Attitude angle must be finite" }
    return ((degrees + HALF_TURN_DEGREES) % FULL_TURN_DEGREES + FULL_TURN_DEGREES) %
        FULL_TURN_DEGREES - HALF_TURN_DEGREES
}

/** Returns the shortest signed angular movement between two roll readings. */
internal fun shortestAttitudeAngleDelta(fromDegrees: Float, toDegrees: Float): Float =
    normalizeSignedAttitudeAngle(toDegrees - fromDegrees)

/** Maps a clockwise scale position to the signed label shown on the fixed roll ring. */
internal fun rollScaleLabel(clockwiseDegrees: Int): String {
    require(clockwiseDegrees in 0 until FULL_TURN_DEGREES.toInt())
    return if (clockwiseDegrees <= HALF_TURN_DEGREES.toInt()) {
        clockwiseDegrees.toString()
    } else {
        (clockwiseDegrees - FULL_TURN_DEGREES.toInt()).toString()
    }
}

private fun DrawScope.drawCenteredText(
    textMeasurer: TextMeasurer,
    text: String,
    position: Offset,
    style: TextStyle,
    alpha: Float,
) {
    val layout = textMeasurer.measure(text = text, style = style)
    drawText(
        textLayoutResult = layout,
        topLeft = Offset(
            x = position.x - layout.size.width / 2f,
            y = position.y - layout.size.height / 2f,
        ),
        alpha = alpha,
    )
}

private fun pointAt(center: Offset, radius: Float, degrees: Float): Offset {
    val radians = degrees * PI / HALF_TURN_DEGREES
    return Offset(
        x = center.x + sin(radians).toFloat() * radius,
        y = center.y - cos(radians).toFloat() * radius,
    )
}

private data class AnimatedAttitude(
    val pitch: State<Float>,
    val roll: State<Float>,
)

private const val FULL_TURN_DEGREES = 360f
private const val HALF_TURN_DEGREES = 180f
private const val MAX_PITCH_DEGREES = 90f
private const val ATTITUDE_ANIMATION_MILLIS = 120
private const val INACTIVE_ALPHA = 0.38f

private const val INNER_RADIUS_RATIO = 0.315f
private const val BEZEL_RADIUS_RATIO = 0.328f
private const val TICK_OUTER_RADIUS_RATIO = 0.355f
private const val ROLL_LABEL_RADIUS_RATIO = 0.414f
private const val BEZEL_OUTLINE_WIDTH_RATIO = 0.004f
private const val INNER_BORDER_WIDTH_RATIO = 0.010f
private const val HORIZON_WIDTH_RATIO = 0.0035f

private const val ROLL_TEXT_SIZE_RATIO = 0.031f
private const val PITCH_TEXT_SIZE_RATIO = 0.029f
private const val ROLL_TICK_STEP_DEGREES = 5
private const val ROLL_MEDIUM_TICK_STEP_DEGREES = 10
private const val ROLL_LABEL_STEP_DEGREES = 30
private const val MAJOR_ROLL_TICK_LENGTH_RATIO = 0.031f
private const val MEDIUM_ROLL_TICK_LENGTH_RATIO = 0.022f
private const val MINOR_ROLL_TICK_LENGTH_RATIO = 0.015f
private const val MAJOR_ROLL_TICK_WIDTH_RATIO = 0.006f
private const val MINOR_ROLL_TICK_WIDTH_RATIO = 0.0022f

private const val PITCH_RANGE_RADIUS_DEGREES = 32f
private const val MIN_LADDER_PITCH = -20
private const val MAX_LADDER_PITCH = 20
private const val PITCH_LADDER_STEP_DEGREES = 5
private const val MAJOR_PITCH_STEP_DEGREES = 10
private const val MAJOR_PITCH_HALF_LENGTH_RATIO = 0.30f
private const val MINOR_PITCH_HALF_LENGTH_RATIO = 0.052f
private const val PITCH_LABEL_OFFSET_RATIO = 0.40f
private const val PITCH_LINE_WIDTH_RATIO = 0.0025f
private const val ATTITUDE_LAYER_EXTENT_MULTIPLIER = 4f

private const val TOP_MARKER_BASE_RADIUS_RATIO = 0.385f
private const val TOP_MARKER_TIP_RADIUS_RATIO = 0.352f
private const val TOP_MARKER_HALF_WIDTH_RATIO = 0.019f
private const val SIDE_MARKER_BASE_RADIUS_RATIO = 0.370f
private const val SIDE_MARKER_TIP_RADIUS_RATIO = 0.340f
private const val SIDE_MARKER_HALF_HEIGHT_RATIO = 0.012f
