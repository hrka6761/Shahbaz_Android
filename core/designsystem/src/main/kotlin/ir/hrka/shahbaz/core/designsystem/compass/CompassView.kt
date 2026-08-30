/** Reusable animated compass presentation shared by flight features. */
package ir.hrka.shahbaz.core.designsystem.compass

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Exposes the CompassBlack value.
 */
private val CompassBlack = Color.Black
/**
 * Exposes the CompassWhite value.
 */
private val CompassWhite = Color(0xFFF7F7F7)
/**
 * Exposes the CompassGrey value.
 */
private val CompassGrey = Color(0xFF777777)
/**
 * Exposes the CompassRed value.
 */
private val CompassRed = Color(0xFFFF1010)
/**
 * Exposes the CompassDarkRed value.
 */
private val CompassDarkRed = Color(0xFFB00000)
/**
 * Exposes the RoseLight value.
 */
private val RoseLight = Color(0xFF242424)
/**
 * Exposes the RoseDark value.
 */
private val RoseDark = Color(0xFF101010)
/**
 * Exposes the RoseOutline value.
 */
private val RoseOutline = Color(0xFF292929)

/**
 * Draws a scalable rotating compass dial for a magnetic device heading.
 *
 * The top marker stays fixed while the dial, compass rose, and needle rotate by the shortest path.
 * A `null` heading keeps the full dial visible but dims it so zero is not presented as a reading.
 */
@Composable
fun CompassView(
    heading: Float?,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val animatedHeading = rememberAnimatedCompassHeading(heading)
    val semanticsModifier = if (contentDescription == null) {
        Modifier
    } else {
        Modifier.semantics { this.contentDescription = contentDescription }
    }

    Box(
        modifier = modifier
            .then(semanticsModifier)
            .aspectRatio(1f)
            .background(CompassBlack),
        contentAlignment = Alignment.Center,
    ) {
        CompassDial(
            animatedHeading = animatedHeading,
            active = heading != null,
            modifier = Modifier.fillMaxSize(),
        )
        CompassNeedle(
            animatedHeading = animatedHeading,
            active = heading != null,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** Draws the scale, upright labels, cardinal directions, rose, and fixed north marker. */
@Composable
private fun CompassDial(
    animatedHeading: State<Float>,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer(cacheSize = 32)
    val alpha = if (active) 1f else 0.38f

    Canvas(modifier) {
        val rotationDegrees = -animatedHeading.value
        val diameter = min(size.width, size.height)
        val dialCenter = center
        val outerTickRadius = diameter * 0.365f
        val degreeRadius = diameter * 0.445f
        val directionRadius = diameter * 0.255f
        val degreeStyle = TextStyle(
            color = CompassWhite,
            fontSize = (diameter * DEGREE_TEXT_SIZE_RATIO).toSp(),
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.sp,
        )
        val cardinalStyle = TextStyle(
            color = CompassWhite,
            fontSize = (diameter * CARDINAL_TEXT_SIZE_RATIO).toSp(),
            fontWeight = FontWeight.Black,
            letterSpacing = 0.sp,
        )
        val northStyle = cardinalStyle.copy(color = CompassRed)
        val intermediateStyle = TextStyle(
            color = CompassGrey,
            fontSize = (diameter * DEGREE_TEXT_SIZE_RATIO).toSp(),
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.sp,
        )

        drawCircle(
            color = RoseOutline.copy(alpha = alpha * 0.7f),
            radius = outerTickRadius,
            center = dialCenter,
            style = Stroke(width = diameter * 0.003f),
        )

        for (degree in 0 until FULL_TURN_DEGREES.toInt() step 5) {
            val important = degree % 30 == 0
            val medium = degree % 10 == 0
            val length = diameter * when {
                important -> 0.050f
                medium -> 0.039f
                else -> 0.026f
            }
            val angle = degree + rotationDegrees
            drawLine(
                color = CompassWhite.copy(alpha = alpha),
                start = pointAt(dialCenter, outerTickRadius - length, angle),
                end = pointAt(dialCenter, outerTickRadius, angle),
                strokeWidth = diameter * if (important) 0.006f else 0.0035f,
                cap = StrokeCap.Round,
            )
        }

        for (degree in 0 until FULL_TURN_DEGREES.toInt() step 30) {
            drawCenteredText(
                textMeasurer = textMeasurer,
                text = degree.toString(),
                position = pointAt(dialCenter, degreeRadius, degree + rotationDegrees),
                style = degreeStyle,
                alpha = alpha,
            )
        }

        val directions = listOf(
            DirectionLabel(0f, "N", northStyle),
            DirectionLabel(45f, "NE", intermediateStyle),
            DirectionLabel(90f, "E", cardinalStyle),
            DirectionLabel(135f, "SE", intermediateStyle),
            DirectionLabel(180f, "S", cardinalStyle),
            DirectionLabel(225f, "SW", intermediateStyle),
            DirectionLabel(270f, "W", cardinalStyle),
            DirectionLabel(315f, "NW", intermediateStyle),
        )
        directions.forEach { direction ->
            drawCenteredText(
                textMeasurer = textMeasurer,
                text = direction.label,
                position = pointAt(
                    center = dialCenter,
                    radius = directionRadius,
                    degrees = direction.degrees + rotationDegrees,
                ),
                style = direction.style,
                alpha = alpha,
            )
        }

        drawCompassRose(
            center = dialCenter,
            diameter = diameter,
            rotationDegrees = rotationDegrees,
            alpha = alpha,
        )
        drawNorthMarker(dialCenter, diameter, alpha)
    }
}

/** Draws the red north half, white south half, and center pivot above the rose. */
@Composable
private fun CompassNeedle(
    animatedHeading: State<Float>,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    val alpha = if (active) 1f else 0.38f
    Canvas(modifier) {
        val rotationDegrees = -animatedHeading.value
        val diameter = min(size.width, size.height)
        val needleCenter = center
        val needleRadius = diameter * 0.185f
        val halfWidth = diameter * 0.023f
        val northAngle = rotationDegrees
        val southAngle = 180f + rotationDegrees

        drawSplitNeedleHalf(
            center = needleCenter,
            tip = pointAt(needleCenter, needleRadius, northAngle),
            left = pointAt(needleCenter, halfWidth, northAngle - 90f),
            right = pointAt(needleCenter, halfWidth, northAngle + 90f),
            leftColor = CompassRed.copy(alpha = alpha),
            rightColor = CompassDarkRed.copy(alpha = alpha),
        )
        drawSplitNeedleHalf(
            center = needleCenter,
            tip = pointAt(needleCenter, needleRadius, southAngle),
            left = pointAt(needleCenter, halfWidth, southAngle - 90f),
            right = pointAt(needleCenter, halfWidth, southAngle + 90f),
            leftColor = Color(0xFFB8B8B8).copy(alpha = alpha),
            rightColor = CompassWhite.copy(alpha = alpha),
        )

        drawCircle(
            color = Color(0xFF7B7B7B).copy(alpha = alpha),
            radius = diameter * 0.025f,
            center = needleCenter,
        )
        drawCircle(
            color = CompassRed.copy(alpha = alpha),
            radius = diameter * 0.017f,
            center = needleCenter,
        )
        drawCircle(
            color = Color(0xFFD0D0D0).copy(alpha = alpha),
            radius = diameter * 0.008f,
            center = needleCenter,
        )
    }
}

/** Animates a normalized heading without taking the long route through north. */
@Composable
private fun rememberAnimatedCompassHeading(heading: Float?): State<Float> {
    val normalizedHeading = heading?.takeIf(Float::isFinite)?.let(::normalizeHeadingDegrees)
    val animatedHeading = remember { Animatable(normalizedHeading ?: 0f) }
    var previousHeading by remember { mutableStateOf(normalizedHeading) }
    var continuousTarget by remember { mutableFloatStateOf(normalizedHeading ?: 0f) }

    LaunchedEffect(normalizedHeading) {
        if (normalizedHeading == null) {
            previousHeading = null
            return@LaunchedEffect
        }

        val previous = previousHeading
        if (previous == null) {
            continuousTarget = normalizedHeading
            previousHeading = normalizedHeading
            animatedHeading.snapTo(normalizedHeading)
        } else {
            continuousTarget += shortestHeadingDelta(previous, normalizedHeading)
            previousHeading = normalizedHeading
            animatedHeading.animateTo(
                targetValue = continuousTarget,
                animationSpec = tween(
                    durationMillis = HEADING_ANIMATION_MILLIS,
                    easing = LinearOutSlowInEasing,
                ),
            )
        }
    }

    return animatedHeading.asState()
}

/** Normalizes one finite heading into the half-open 0 to 360 degree interval. */
internal fun normalizeHeadingDegrees(degrees: Float): Float {
    require(degrees.isFinite()) { "Heading must be finite" }
    return ((degrees % FULL_TURN_DEGREES) + FULL_TURN_DEGREES) % FULL_TURN_DEGREES
}

/** Returns the signed shortest rotation from one heading to another. */
internal fun shortestHeadingDelta(fromDegrees: Float, toDegrees: Float): Float {
    val from = normalizeHeadingDegrees(fromDegrees)
    val to = normalizeHeadingDegrees(toDegrees)
    return normalizeHeadingDegrees(to - from + HALF_TURN_DEGREES) - HALF_TURN_DEGREES
}

/**
 * Runs the DrawScope operation.
 */
private fun DrawScope.drawCompassRose(
    center: Offset,
    diameter: Float,
    rotationDegrees: Float,
    alpha: Float,
) {
    drawCircle(
        color = RoseOutline.copy(alpha = alpha),
        radius = diameter * 0.175f,
        center = center,
        style = Stroke(width = diameter * 0.012f),
    )

    repeat(8) { index ->
        val angle = index * 45f + rotationDegrees
        val radius = diameter * if (index % 2 == 0) 0.160f else 0.122f
        val halfWidth = diameter * if (index % 2 == 0) 0.041f else 0.032f
        drawSplitNeedleHalf(
            center = center,
            tip = pointAt(center, radius, angle),
            left = pointAt(center, halfWidth, angle - 90f),
            right = pointAt(center, halfWidth, angle + 90f),
            leftColor = RoseLight.copy(alpha = alpha),
            rightColor = RoseDark.copy(alpha = alpha),
        )
    }
}

/**
 * Runs the DrawScope operation.
 */
private fun DrawScope.drawSplitNeedleHalf(
    center: Offset,
    tip: Offset,
    left: Offset,
    right: Offset,
    leftColor: Color,
    rightColor: Color,
) {
    drawPath(
        path = Path().apply {
            moveTo(tip.x, tip.y)
            lineTo(left.x, left.y)
            lineTo(center.x, center.y)
            close()
        },
        color = leftColor,
    )
    drawPath(
        path = Path().apply {
            moveTo(tip.x, tip.y)
            lineTo(center.x, center.y)
            lineTo(right.x, right.y)
            close()
        },
        color = rightColor,
    )
}

/**
 * Runs the DrawScope operation.
 */
private fun DrawScope.drawNorthMarker(center: Offset, diameter: Float, alpha: Float) {
    val markerBaseY = center.y - diameter * 0.395f
    val markerTipY = center.y - diameter * 0.335f
    val markerHalfWidth = diameter * 0.027f
    drawPath(
        path = Path().apply {
            moveTo(center.x - markerHalfWidth, markerBaseY)
            lineTo(center.x + markerHalfWidth, markerBaseY)
            lineTo(center.x, markerTipY)
            close()
        },
        color = CompassRed.copy(alpha = alpha),
    )
}

/**
 * Runs the DrawScope operation.
 */
private fun DrawScope.drawCenteredText(
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
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

/**
 * Runs the pointAt operation.
 */
private fun pointAt(center: Offset, radius: Float, degrees: Float): Offset {
    val radians = degrees * PI / HALF_TURN_DEGREES
    return Offset(
        x = center.x + sin(radians).toFloat() * radius,
        y = center.y - cos(radians).toFloat() * radius,
    )
}

/**
 * Documents the DirectionLabel type and the role it plays in this module.
 */
private data class DirectionLabel(
    /**
     * Exposes the degrees value.
     */
    val degrees: Float,
    /**
     * Exposes the label value.
     */
    val label: String,
    /**
     * Exposes the style value.
     */
    val style: TextStyle,
)

/**
 * Exposes the FULL_TURN_DEGREES value.
 */
private const val FULL_TURN_DEGREES = 360f
/**
 * Exposes the HALF_TURN_DEGREES value.
 */
private const val HALF_TURN_DEGREES = 180f
/**
 * Exposes the HEADING_ANIMATION_MILLIS value.
 */
private const val HEADING_ANIMATION_MILLIS = 160
// Keeps the original 9sp and 20sp label proportions at the 184dp reference size.
/**
 * Exposes the DEGREE_TEXT_SIZE_RATIO value.
 */
private const val DEGREE_TEXT_SIZE_RATIO = 9f / 184f
/**
 * Exposes the CARDINAL_TEXT_SIZE_RATIO value.
 */
private const val CARDINAL_TEXT_SIZE_RATIO = 20f / 184f
