package com.shahbaz.flightblackbox.internal

import android.os.SystemClock

internal interface FbbClock {
    fun wallClockMillis(): Long
    fun elapsedRealtimeNanos(): Long
}

internal object AndroidFbbClock : FbbClock {
    override fun wallClockMillis(): Long = System.currentTimeMillis()

    override fun elapsedRealtimeNanos(): Long = SystemClock.elapsedRealtimeNanos()
}
