/** Immutable, platform-neutral presentation models for the flight dashboard. */
package ir.hrka.shahbaz.feature.dashboard

import ir.hrka.compass.CompassDirection
import ir.hrka.compass.CompassReading
import ir.hrka.compass.NorthReference
import ir.hrka.shahbaz.core.model.FlightPlan
import ir.hrka.shahbaz.core.model.GeoCoordinate
import ir.hrka.shahbaz.hardwareconnection.BoardConnectionState
import ir.hrka.shahbaz.hardwareconnection.BoardTelemetrySnapshot
import ir.hrka.shahbaz.hardwareconnection.SensorState
import kotlin.math.pow

/** Stable states shared by phone position and orientation readings. */
sealed interface PhoneReading<out T> {
    /**
     * Provides the singleton Inactive services for this module.
     */
    data object Inactive : PhoneReading<Nothing>
    /**
     * Provides the singleton AwaitingFirstSample services for this module.
     */
    data object AwaitingFirstSample : PhoneReading<Nothing>
    /**
     * Documents the Available type and the role it plays in this module.
     */
    data class Available<T>(val value: T) : PhoneReading<T>
    /**
     * Documents the Stale type and the role it plays in this module.
     */
    data class Stale<T>(val lastValue: T?) : PhoneReading<T>
    /**
     * Documents the NoResponse type and the role it plays in this module.
     */
    data class NoResponse<T>(val lastValue: T?, val reason: String) : PhoneReading<T>
    /**
     * Documents the Invalid type and the role it plays in this module.
     */
    data class Invalid<T>(val lastValue: T?, val reason: String) : PhoneReading<T>
    /**
     * Documents the NotPresent type and the role it plays in this module.
     */
    data class NotPresent(val reason: String) : PhoneReading<Nothing>
    /**
     * Documents the Unavailable type and the role it plays in this module.
     */
    data class Unavailable(val reason: String) : PhoneReading<Nothing>
    /**
     * Documents the Failed type and the role it plays in this module.
     */
    data class Failed(val reason: String) : PhoneReading<Nothing>
}

/** Phone-derived data supplied by the app composition root without coupling dashboard to map. */
data class DashboardPhoneSensors(
    /**
     * Exposes the position value.
     */
    val position: PhoneReading<GeoCoordinate> = PhoneReading.Inactive,
    /**
     * Exposes the orientation value.
     */
    val orientation: PhoneReading<CompassReading> = PhoneReading.Inactive,
)

/** Signed and absolute angular relationship between the current heading and one cardinal axis. */
data class CardinalAngle(
    /**
     * Exposes the direction value.
     */
    val direction: CompassDirection,
    /**
     * Exposes the signedDegrees value.
     */
    val signedDegrees: Float,
    /**
     * Exposes the absoluteDegrees value.
     */
    val absoluteDegrees: Float,
)

/** Calculates the four cardinal-axis deviations used by the dashboard compass. */
internal fun cardinalAngles(
    reading: CompassReading,
    reference: NorthReference,
): List<CardinalAngle> = CardinalDirections.mapNotNull { direction ->
    reading.deviationFrom(direction, reference)?.let { deviation ->
        CardinalAngle(
            direction = direction,
            signedDegrees = deviation.signedDegrees,
            absoluteDegrees = deviation.absoluteDegrees,
        )
    }
}

/** Identifies the board sample already present when one USB session first became ready. */
internal data class PressureSampleIdentity(
    /**
     * Exposes the sequence value.
     */
    val sequence: Long,
    /**
     * Exposes the receivedAtElapsedRealtimeMillis value.
     */
    val receivedAtElapsedRealtimeMillis: Long,
)

/** Arms baseline capture for exactly one fully validated USB session. */
internal data class TakeoffBaselineCaptureGate(
    /**
     * Exposes the deviceId value.
     */
    val deviceId: Int,
    /**
     * Exposes the sessionConnectedAtElapsedRealtimeMillis value.
     */
    val sessionConnectedAtElapsedRealtimeMillis: Long,
    /**
     * Exposes the samplePresentAtReady value.
     */
    val samplePresentAtReady: PressureSampleIdentity?,
)

/**
 * Creates a gate without accepting telemetry already buffered when the session became ready.
 * The first different, post-ready MS5611 sample may subsequently establish the baseline.
 */
internal fun takeoffBaselineCaptureGate(
    connection: BoardConnectionState,
    telemetry: BoardTelemetrySnapshot,
): TakeoffBaselineCaptureGate? {
    /**
     * Exposes the ready value.
     */
    val ready = connection as? BoardConnectionState.Ready ?: return null
    /**
     * Exposes the sampleAtReady value.
     */
    val sampleAtReady = when (val pressure = telemetry.ms5611) {
        is SensorState.Available -> pressure.sample
        else -> null
    }
    return TakeoffBaselineCaptureGate(
        deviceId = ready.device.deviceId,
        sessionConnectedAtElapsedRealtimeMillis = ready.connectedAtElapsedRealtimeMillis,
        samplePresentAtReady = sampleAtReady?.let {
            PressureSampleIdentity(it.sequence, it.receivedAtElapsedRealtimeMillis)
        },
    )
}

/**
 * Returns a fail-closed takeoff baseline candidate.
 *
 * An established baseline is immutable for the flight plan and therefore survives an ordinary
 * reconnect. A new baseline requires a plan, the same currently-ready session that armed [gate],
 * and a validated pressure sample that was not already present at the Ready transition.
 */
internal fun eligibleTakeoffBaselinePressure(
    establishedBaselinePascal: Int?,
    hasFlightPlan: Boolean,
    connection: BoardConnectionState,
    gate: TakeoffBaselineCaptureGate?,
    telemetry: BoardTelemetrySnapshot,
): Int? {
    if (establishedBaselinePascal != null) return establishedBaselinePascal
    if (!hasFlightPlan || gate == null) return null
    /**
     * Exposes the ready value.
     */
    val ready = connection as? BoardConnectionState.Ready ?: return null
    if (
        ready.device.deviceId != gate.deviceId ||
        ready.connectedAtElapsedRealtimeMillis != gate.sessionConnectedAtElapsedRealtimeMillis
    ) return null
    /**
     * Exposes the sample value.
     */
    val sample = when (val pressure = telemetry.ms5611) {
        is SensorState.Available -> pressure.sample
        else -> return null
    }
    if (sample.receivedAtElapsedRealtimeMillis < ready.connectedAtElapsedRealtimeMillis) return null
    /**
     * Exposes the identity value.
     */
    val identity = PressureSampleIdentity(sample.sequence, sample.receivedAtElapsedRealtimeMillis)
    if (identity == gate.samplePresentAtReady) return null
    return sample.value.pressurePascal.takeIf {
        it in MIN_PLAUSIBLE_PRESSURE_PA..MAX_PLAUSIBLE_PRESSURE_PA
    }
}

/** Complete dashboard state. Board sensors remain independent so one failure cannot erase others. */
data class DashboardUiState(
    /**
     * Exposes the flightPlan value.
     */
    val flightPlan: FlightPlan? = null,
    /**
     * Exposes the boardConnection value.
     */
    val boardConnection: BoardConnectionState = BoardConnectionState.Stopped,
    /**
     * Exposes the boardTelemetry value.
     */
    val boardTelemetry: BoardTelemetrySnapshot = BoardTelemetrySnapshot(),
    /**
     * Exposes the phoneSensors value.
     */
    val phoneSensors: DashboardPhoneSensors = DashboardPhoneSensors(),
    /**
     * Exposes the baselinePressurePascal value.
     */
    val baselinePressurePascal: Int? = null,
    /**
     * Exposes the isOnline value.
     */
    val isOnline: Boolean = true,
) {
    /** Latest board pressure even when it has just become stale, for clearly labelled retention. */
    val currentPressurePascal: Int?
        get() = when (val sensor = boardTelemetry.ms5611) {
            is SensorState.Available -> sensor.sample.value.pressurePascal
            is SensorState.Stale -> sensor.lastSample?.value?.pressurePascal
            is SensorState.Failed -> sensor.lastSample?.value?.pressurePascal
            else -> null
        }

    /** Pressure-derived altitude relative to the baseline captured for this flight plan. */
    val altitudeAboveTakeoffMeters: Double?
        get() {
            val current = currentPressurePascal ?: return null
            val baseline = baselinePressurePascal ?: return null
            if (
                current !in MIN_PLAUSIBLE_PRESSURE_PA..MAX_PLAUSIBLE_PRESSURE_PA ||
                baseline !in MIN_PLAUSIBLE_PRESSURE_PA..MAX_PLAUSIBLE_PRESSURE_PA
            ) return null
            return relativeBarometricAltitudeMeters(current, baseline)
        }
}

/** Standard-atmosphere pressure altitude relative to a measured takeoff pressure. */
internal fun relativeBarometricAltitudeMeters(
    pressurePascal: Int,
    baselinePressurePascal: Int,
): Double {
    require(pressurePascal in MIN_PLAUSIBLE_PRESSURE_PA..MAX_PLAUSIBLE_PRESSURE_PA)
    require(baselinePressurePascal in MIN_PLAUSIBLE_PRESSURE_PA..MAX_PLAUSIBLE_PRESSURE_PA)
    return BAROMETRIC_SCALE_METERS *
        (1.0 - (pressurePascal.toDouble() / baselinePressurePascal).pow(BAROMETRIC_EXPONENT))
}

/**
 * Exposes the CardinalDirections value.
 */
internal val CardinalDirections = listOf(
    CompassDirection.NORTH,
    CompassDirection.EAST,
    CompassDirection.SOUTH,
    CompassDirection.WEST,
)

/**
 * Exposes the MIN_PLAUSIBLE_PRESSURE_PA value.
 */
private const val MIN_PLAUSIBLE_PRESSURE_PA = 1_000
/**
 * Exposes the MAX_PLAUSIBLE_PRESSURE_PA value.
 */
private const val MAX_PLAUSIBLE_PRESSURE_PA = 120_000
/**
 * Exposes the BAROMETRIC_SCALE_METERS value.
 */
private const val BAROMETRIC_SCALE_METERS = 44_330.0
/**
 * Exposes the BAROMETRIC_EXPONENT value.
 */
private const val BAROMETRIC_EXPONENT = 0.190_294_957_18
