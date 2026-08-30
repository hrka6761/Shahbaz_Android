package com.shahbaz.flightblackbox.internal

import android.os.SystemClock

/**
 * Defines the FbbClock contract used by this module.
 */
internal interface FbbClock {
    /**
     * Runs the wallClockMillis operation.
     */
    fun wallClockMillis(): Long
    /**
     * Runs the elapsedRealtimeNanos operation.
     */
    fun elapsedRealtimeNanos(): Long
}

/**
 * Provides the singleton AndroidFbbClock services for this module.
 */
internal object AndroidFbbClock : FbbClock {
    /**
     * Runs the wallClockMillis operation.
     */
    override fun wallClockMillis(): Long = System.currentTimeMillis()

    /**
     * Runs the elapsedRealtimeNanos operation.
     */
    override fun elapsedRealtimeNanos(): Long = SystemClock.elapsedRealtimeNanos()
}
