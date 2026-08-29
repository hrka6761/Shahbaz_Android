/** Defines the small lifecycle API used to create and observe a reusable Android compass. */
package ir.hrka.compass

import android.content.Context
import ir.hrka.compass.internal.AndroidCompass

/**
 * Lifecycle-controlled source of device compass readings.
 *
 * Implementations retain only [Context.getApplicationContext], capture the display associated with
 * the creation context, permit calls from any thread, and deliver [CompassListener] events on
 * Android's main thread. A stopped instance can be restarted.
 */
interface Compass {
    /** Describes whether a supported sensor source exists and which source would be used. */
    val availability: CompassAvailability

    /** Whether this instance currently owns registered Android sensor listeners. */
    val isRunning: Boolean

    /**
     * Starts sensor observation for [listener].
     *
     * A second call while observation is active returns [CompassStartResult.AlreadyRunning] and
     * preserves the original listener.
     *
     * @param listener consumer that receives serialized readings or runtime failures.
     * @return the synchronous outcome of sensor registration.
     */
    fun start(listener: CompassListener): CompassStartResult

    /**
     * Stops observation, releases every registered sensor, and clears session filter state.
     *
     * Calling this function while already stopped has no effect. A call from another thread waits
     * for an in-flight listener callback to finish; calling `stop()` from that callback is supported
     * and does not deadlock.
     */
    fun stop()

    /**
     * Supplies or clears the geographic input used to derive true north.
     *
     * The compass never obtains location itself and therefore never requires location permission.
     * Passing `null` disables true-north values while preserving magnetic readings.
     *
     * @param position validated position and time for Android's geomagnetic model, or `null`.
     */
    fun setGeomagneticPosition(position: GeomagneticPosition?)

    /** Creates framework-backed [Compass] instances without exposing their implementation. */
    companion object {
        /**
         * Creates a compass that uses the supplied application context and configuration.
         *
         * @param context Android context used to obtain sensor services and capture display identity.
         * @param config sampling and smoothing policy for this instance.
         * @return a new, initially stopped compass.
         */
        @JvmStatic
        @JvmOverloads
        fun create(
            context: Context,
            config: CompassConfig = CompassConfig(),
        ): Compass = AndroidCompass(context, config)
    }
}

/** Receives compass readings and typed runtime failures. */
fun interface CompassListener {
    /**
     * Handles one serialized event on Android's main thread.
     *
     * @param event reading or failure produced by the active observation session.
     */
    fun onEvent(event: CompassEvent)
}

/** Events that may be delivered during an active compass session. */
sealed interface CompassEvent {
    /**
     * Wraps one valid, normalized device-orientation reading.
     *
     * @property value complete logical reading derived from the selected sensor source.
     */
    data class Reading(val value: CompassReading) : CompassEvent

    /**
     * Reports a runtime failure that prevented an individual reading from being produced.
     *
     * @property failure typed diagnostic that a host may map to logging or user-facing text.
     */
    data class Failure(val failure: CompassFailure) : CompassEvent
}

/** Synchronous outcomes returned by [Compass.start]. */
sealed interface CompassStartResult {
    /** Sensor registration completed and readings may now arrive. */
    data object Started : CompassStartResult

    /** This instance was already active, so no registration or listener replacement occurred. */
    data object AlreadyRunning : CompassStartResult

    /**
     * Sensor observation could not start.
     *
     * @property failure typed reason for the rejected start operation.
     */
    data class Failed(val failure: CompassFailure) : CompassStartResult
}
